# Real-Time Crypto Prices Homepage — System Design (L6 / Staff Engineer)

Design for a **low-latency, real-time crypto prices homepage** with multi-source ingestion, normalized streaming, client fanout, and global reliability. This document covers data pipelines, API design, caching, scalability, multi-region, and monitoring—aligned with Coinbase-style system design expectations for real-time financial data.

---

## 1. Requirements & Constraints

| Dimension | Target / Constraint |
|-----------|----------------------|
| **Latency** | First-byte / time-to-first-price: p99 &lt; 200 ms; streaming update latency &lt; 500 ms from exchange tick. |
| **Throughput** | 100K+ concurrent clients (homepage); 1M+ price updates/sec aggregate ingest; 10K+ symbols (pairs) with varying activity. |
| **Data freshness** | Sub-second staleness for top symbols; acceptable 1–5 s for long tail. No silent stale data—clients should see freshness indicators or backoff. |
| **Sources** | Multiple exchanges/aggregators (e.g. Coinbase, Binance, Kraken, L2 books, index feeds); different protocols (REST, WebSocket, FIX, proprietary). |
| **Consistency** | Per-client: no duplicate or out-of-order ticks for same symbol within session. Cross-client: eventual consistency; optional strong consistency for “last traded price” in critical paths. |
| **Reliability** | 99.99% uptime; multi-region failover; graceful degradation (e.g. show cached + “delayed” when primary feed is down). |
| **Compliance / audit** | Audit trail for which feed contributed which price (provenance); no silent data substitution. |

---

## 2. High-Level Architecture

```
                    ┌─────────────────────────────────────────────────────────────────┐
                    │                        CLIENT LAYER                               │
                    │  Web / Mobile — REST (snapshot) + WebSocket or SSE (streaming)   │
                    └───────────────────────────────▲─────────────────────────────────┘
                                                    │
┌───────────────────────────────────────────────────┼───────────────────────────────────┐
│                     EDGE / API LAYER               │                                   │
│  API Gateway · Auth · Rate limit · Geo routing     │  WebSocket/SSE fanout servers     │
│  REST: /prices, /prices/{symbols} (snapshot)       │  Subscribe: symbols, channels     │
└───────────────────────────────▲───────────────────┴───────────────────▲───────────────┘
                                │                                       │
                    ┌───────────┴───────────┐               ┌────────────┴────────────┐
                    │  PRICE CACHE (hot)    │               │  STREAMING LAYER        │
                    │  Redis Cluster        │◄──────────────│  Pub/Sub or Kafka       │
                    │  per-symbol, last N   │   consume     │  price-update topic(s)  │
                    └───────────▲───────────┘               └────────────▲────────────┘
                                │                                       │
                    ┌───────────┴───────────┐               ┌────────────┴────────────┐
                    │  NORMALIZATION /      │               │  INGESTION LAYER        │
                    │  AGGREGATION         │───────────────►  Protocol adapters      │
                    │  (optional index)    │   publish      │  Exchange A, B, C …     │
                    └─────────────────────┘               └────────────▲────────────┘
                                                                       │
                                                    ┌──────────────────┴──────────────────┐
                                                    │  EXTERNAL FEEDS                      │
                                                    │  REST, WebSocket, FIX, proprietary   │
                                                    └─────────────────────────────────────┘
```

**Data flow (summary):**

1. **Ingest:** Protocol adapters consume exchange/aggregator feeds → normalize to a **canonical price tick**.
2. **Stream:** Normalized ticks are published to a **message bus** (Kafka or regional pub/sub); optional aggregation service computes index/volume-weighted price.
3. **Cache:** Consumers (cache warmer / API tier) write last price (and optional N-level depth) into **Redis** keyed by symbol (and region).
4. **Serve:** REST returns snapshot from Redis; WebSocket/SSE servers subscribe to the same stream (or Redis pub/sub) and **fan out** to connected clients per symbol/channel.
5. **Persistence (optional):** Time-series DB or OLAP for history, analytics, and “price at time T” queries.

---

## 3. Data Ingestion & Normalization

### 3.1 Canonical price model

Define **one** internal representation so all downstream components are protocol-agnostic.

**Suggested canonical tick (minimal):**

| Field | Type | Description |
|-------|------|-------------|
| `symbol` | string | Normalized pair (e.g. `BTC-USD`, `ETH-EUR`) — one namespace across exchanges. |
| `source` | enum | Exchange or aggregator id (provenance). |
| `price` | decimal | Last trade or mid or index value. |
| `bid` / `ask` | decimal (optional) | Best bid/ask if L2; null for last-only feeds. |
| `volume_24h` | decimal (optional) | If provided by feed. |
| `timestamp` | int64 | Epoch ms (exchange time or receipt time; document which). |
| `sequence` | int64 | Per-source sequence for ordering and dedup. |

**Why canonical:** Downstream (aggregation, cache, API) never sees exchange-specific fields. Adding a new exchange = new adapter only.

### 3.2 Protocol adapters (one per source)

- **Interface:** e.g. `ExchangeAdapter` with `CanonicalTick parse(RawMessage raw)` or stream `Flowable<CanonicalTick>`.
- **One adapter per exchange/feed:** CoinbaseAdapter, BinanceAdapter, IndexFeedAdapter, etc. Each maps protocol-specific payloads (REST JSON, WebSocket frames, FIX) into `CanonicalTick`.
- **Stateless:** Parse → normalize → emit. No durable state in adapter; optional in-memory buffer for batching.
- **Output:** Publish **only** `CanonicalTick` (e.g. Avro/Protobuf) to a **single** ingest topic (e.g. `price-ticks-raw`) with partition key `hash(symbol)` so per-symbol order is preserved.

**Failure handling:**

- **Parse errors:** Log, metric, dead-letter topic; do not crash process.
- **Backpressure:** If bus is slow, apply backpressure (drop/sample or block) and expose metrics; prefer shedding load over OOM.

### 3.3 Symbol normalization

- Map exchange-specific symbols (e.g. `BTCUSD`, `btc_usdt`) to **canonical** symbols (e.g. `BTC-USD`) in the adapter.
- Maintain a small config or table (symbol mapping per source); adapters use it at parse time.

---

## 4. Streaming Updates & Message Bus

### 4.1 Topic design

- **Option A — Single topic, partitioned by symbol:**  
  Topic `price-ticks-raw`, partition key `symbol`. Preserves per-symbol order; high-throughput symbols can be hot partitions (mitigate with more partitions or composite key).

- **Option B — Tiered topics:**  
  `price-ticks-tier1` (top 100 symbols), `price-ticks-tier2` (rest). Lets consumers prioritize tier1 for lower latency and separate scaling.

- **Option C — Per-symbol or per-symbol-group topics:**  
  Only at very large scale; adds operational complexity.

**Recommendation:** Start with single topic, partition by `symbol`; move to tiered if partition skew is a problem.

### 4.2 From bus to cache and to clients

- **Cache warmer:** Consumer group reads from `price-ticks-raw` (or aggregated topic), updates **Redis** keyed by `symbol` (e.g. `price:{symbol}` with hash or string). Optionally publish to a **Redis Pub/Sub** or **fanout topic** for real-time clients.
- **Alternative:** A dedicated **price-stream service** consumes the bus, updates Redis, and **also** pushes to connected WebSocket/SSE clients (in-process or via internal pub/sub). This service is the “fanout” layer: one update from bus → many clients subscribed to that symbol.

### 4.3 Ordering and deduplication

- **Ordering:** Partition by `symbol` so all ticks for a symbol go to one partition → total order per symbol. Consumers process in order.
- **Dedup:** Use `(source, sequence)` or `(source, timestamp, sequence)` to drop duplicates (e.g. at-least-once delivery). Idempotent cache writes (e.g. “set if newer”) also help.

---

## 5. API Design

### 5.1 REST — Snapshot (cold / initial load)

- **GET /api/v1/prices**  
  Returns current prices for a **default set** (e.g. top 20 symbols). Response: `{ "prices": [ { "symbol": "BTC-USD", "price": "...", "timestamp": ... }, ... ] }`.  
  Use **Redis** (or cache) as source of truth; TTL or “last updated” in response.

- **GET /api/v1/prices?symbols=BTC-USD,ETH-USD**  
  Filter by symbols; same shape. Good for “above the fold” and SEO.

**Design choices:**

- **Pagination:** If symbol set is large, use cursor/keyset over symbol list or return a bounded set (e.g. max 100 symbols per request).
- **Consistency:** Snapshot is eventually consistent; add `as_of` or `updated_at` so clients know freshness.

### 5.2 WebSocket or SSE — Real-time stream

- **WebSocket (preferred for bidirectional):**  
  - **Subscribe:** Client sends `{ "action": "subscribe", "symbols": ["BTC-USD", "ETH-USD"] }`.  
  - Server adds client to channel(s) for those symbols; streams only **incremental ticks** for subscribed symbols.  
  - **Unsubscribe:** `{ "action": "unsubscribe", "symbols": ["ETH-USD"] }`.

- **SSE (simpler, one-way):**  
  - **GET /api/v1/prices/stream?symbols=BTC-USD,ETH-USD**  
  - Server keeps connection open; sends `data: { "symbol": "BTC-USD", "price": "...", ... }` on each update.  
  - Reconnect with same `symbols` for resilience.

**Throttling:** Per connection and per symbol: cap message rate (e.g. max 1 msg per symbol per 100 ms) to avoid flooding; if feed is faster, send latest only or sample.

### 5.3 Hybrid (recommended)

- **Initial load:** REST snapshot from Redis (fast, cacheable).  
- **Ongoing:** WebSocket/SSE for deltas only. Client merges snapshot + deltas for live view.  
- **Reconnect:** On reconnect, client can re-fetch snapshot (REST) then re-subscribe to stream to avoid gaps.

---

## 6. Caching & Storage

### 6.1 Hot path — Redis

- **Key design:** `price:{symbol}` → hash or JSON with `price`, `bid`, `ask`, `timestamp`, `source`, etc.  
- **Optional:** `price:snapshot:top100` — single key with JSON array for “homepage top 100” to reduce round-trips.  
- **TTL:** No TTL (or long TTL); value is overwritten on each tick. Optionally set a “stale” flag if no update for N seconds (for monitoring and client UX).  
- **Cluster:** Redis Cluster for sharding; key by symbol so same symbol always hits same shard.  
- **Multi-region:** Per-region Redis; populated by regional consumers or by replication (see §8).

### 6.2 Persistence — Time-series / OLAP (optional)

- **Use case:** “Price at time T,” charts, analytics, compliance.  
- **Options:** TimescaleDB, InfluxDB, or object store + columnar (e.g. Parquet) with a query layer.  
- **Write path:** Async consumer from same Kafka topic; batch or stream insert. Do not block the hot path.  
- **Retention:** Configurable (e.g. 1 year raw, 5 years downsampled).

### 6.3 Cache consistency

- **Eventual:** Normalized tick → bus → cache warmer → Redis. No strong consistency between exchanges and Redis; document “as of” time.  
- **Stale handling:** If a feed is down, stop updating that symbol in Redis; expose “last_updated” so UI can show “delayed” or “stale.”

---

## 7. Scalability

### 7.1 Ingest

- **Adapters:** Stateless; scale horizontally. Each adapter instance can run multiple connections (e.g. one per symbol group) or shard by symbol.  
- **Kafka:** Increase partitions for `price-ticks-raw` if needed; partition key by symbol (or composite) to preserve order.  
- **Backpressure:** Monitor lag; if consumer lag grows, scale consumers or shed load (sample ticks for non–tier-1 symbols).

### 7.2 Cache and API

- **Redis:** Cluster mode; add shards as symbol set and read QPS grow.  
- **REST API:** Stateless API servers; scale horizontally behind load balancer.  
- **WebSocket/SSE fanout:**  
  - **Sticky sessions** (same client → same server) so subscription state is local.  
  - Fanout servers subscribe to the price stream (Kafka consumer or Redis Pub/Sub); each server pushes only to its connected clients for the symbols they care about.  
  - Scale by adding more fanout servers; use a **connection manager** or **router** that assigns new connections to the least-loaded server.

### 7.3 Per-symbol fanout efficiency

- Avoid “broadcast to all clients then filter.” Prefer **symbol → set of connections** (in-memory or via Redis-backed presence) so one tick triggers sends only to subscribers of that symbol.  
- Batching: If many symbols update in one Kafka batch, group pushes by connection (each connection gets one message with multiple symbol updates) to reduce syscalls.

---

## 8. Multi-Region Reliability

### 8.1 Goals

- **Low latency:** Users in region X read from a nearby API and cache.  
- **Availability:** If region X fails, traffic fails over to another region; no single-region single point of failure.  
- **Data:** Prices are the same globally (eventual); slight cross-region delay is acceptable.

### 8.2 Topology options

- **Active–passive:** One primary region ingests and writes to Kafka/Redis; replicate Kafka and Redis to DR region; failover on primary failure. Simpler; DR may have slightly staler data.  
- **Active–active (per region):** Each region runs full stack: ingest (or ingest in one region and replicate stream), cache, API. Clients are routed to nearest region. Redis can be per-region (each region’s consumer fills local Redis from global or regional stream).  
- **Ingest once, read everywhere:** Ingest and normalize in one “data” region; replicate Kafka to other regions; each region runs cache + API and consumes from local Kafka replica. Ensures single ordering of ticks; read path is local.

### 8.3 Failover and routing

- **DNS / global LB:** Route client to nearest healthy region (latency-based or geo).  
- **Health checks:** Monitor Redis, Kafka consumer lag, API and WebSocket server health; exclude unhealthy regions from routing.  
- **Graceful degradation:** If a region’s feed is down, serve from cache with “delayed” indicator; or route to another region if cross-region read is acceptable.

### 8.4 Cross-region replication

- **Kafka:** Use MirrorMaker 2 or Confluent replicator to replicate `price-ticks-*` topics to other regions.  
- **Redis:** Redis can replicate across regions (async); or each region’s consumer fills local Redis from local Kafka replica—no cross-region Redis write.

---

## 9. Monitoring & Observability

### 9.1 Metrics

| Area | Metrics |
|------|--------|
| **Ingest** | Messages/sec per adapter, parse errors, publish latency, dead-letter count. |
| **Bus** | Produce/consume latency, consumer lag per partition, partition skew. |
| **Cache** | Redis hit rate, write latency, key count, memory. |
| **API** | Request rate, latency (p50/p99), error rate by endpoint; WebSocket: connections per server, messages sent/sec, subscribe/unsubscribe rate. |
| **Data freshness** | Time since last tick per symbol (or per source); alert if no update for N seconds for tier-1 symbols. |
| **Business** | Number of symbols with recent update; top symbols by update rate. |

### 9.2 Alerting

- **Critical:** Consumer lag &gt; threshold; Redis unavailable; API error rate spike; no ticks for tier-1 symbols for &gt; 30 s.  
- **Warning:** Elevated p99 latency; parse error rate increase; partition skew.

### 9.3 Tracing and debugging

- **Trace id** across: API request → cache read; or adapter → Kafka → consumer → Redis.  
- **Provenance:** Log or store `source` (exchange) with each price so “where did this price come from?” is answerable (compliance).

### 9.4 Dashboards

- Pipeline view: ingest → bus → cache (throughput, latency).  
- Per-region: API latency, cache hit, WebSocket connections.  
- Per-symbol (top N): update rate, last updated time.

---

## 10. Summary: L6-Level Decisions

| Decision | Rationale |
|----------|-----------|
| **Canonical price model + protocol adapters** | Single schema and single ingest path; add exchanges without changing downstream; aligns with [protocol adapters design](lld/protocol-adapters/PROTOCOL_ADAPTERS_DESIGN.md). |
| **Partition by symbol** | Preserves per-symbol order; enables deterministic cache updates and dedup. |
| **Redis for hot path** | Sub-ms reads; supports high QPS and WebSocket fanout input. |
| **REST snapshot + WebSocket/SSE deltas** | Fast first paint + low-bandwidth updates; reconnection uses snapshot to avoid gaps. |
| **Symbol-scoped fanout** | Scale to many connections without broadcasting every tick to everyone. |
| **Multi-region: ingest once or per-region** | Trade off between consistency and latency; replicate stream so each region can serve locally. |
| **Freshness and provenance** | Timestamps and source on every tick; monitoring and UI for “delayed” or “stale”; audit trail for compliance. |
| **Backpressure and graceful degradation** | Shed load at ingest/cache rather than OOM; serve stale with indicator when feeds fail. |

---

## 11. Optional Extensions

- **Index / composite prices:** Dedicated aggregator consumes `price-ticks-raw`, computes index (e.g. volume-weighted), publishes to `price-ticks-index`; same cache and API pattern.  
- **L2 order book:** Canonical model includes `bids[]` and `asks[]`; same adapter + stream + cache pattern; cache key e.g. `book:{symbol}`.  
- **Rate limiting:** Per user and per connection (e.g. max N symbols per connection, max connections per user) to protect fanout layer.  
- **A/B and feature flags:** Serve different symbol sets or update rates by segment for experimentation.

---

*This design is suitable for an L6 system design discussion: it covers end-to-end data flow, trade-offs, scalability, multi-region, and observability, and ties decisions to latency, reliability, and compliance requirements.*
