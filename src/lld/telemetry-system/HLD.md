# High-Level Design: Telemetry System — 10M Network Flows/sec

**Audience:** Senior eng / architects. Assumes you’ve run high-throughput systems before and care about where it hurts in prod.

---

## 1. Problem & Scale

**Goal:** Ingest, process, and make queryable **10 million network flows per second** with bounded latency and predictable cost.

**What “network flow” means here:** A record per flow (e.g. 5-tuple: src/dst IP, src/dst port, protocol, plus bytes, packets, timestamps, maybe device/interface). Typical size 50–200 bytes per record after encoding.

**Rough math:**

| Metric | Value |
|--------|--------|
| Flows/sec | 10M |
| Bytes/flow (avg) | ~100 |
| Ingest bandwidth | ~1 GB/s sustained |
| Per day (raw) | ~8.6 TB |
| Per day (compressed, ~4x) | ~2+ TB |

So we’re in the “big stream + big storage” space. You don’t “put 10M writes/sec into a single DB.” You need a pipeline: ingest → stream → process → store, with clear boundaries and backpressure.

---

## 2. Non-Goals (Explicit)

- **Not** a full packet-capture system (no payload).
- **Not** real-time DPI or inline blocking; we’re observability/analytics.
- **Not** “every flow forever” at full fidelity unless someone pays for it — sampling and aggregation are first-class.
- **Not** sub-ms p99 ingest latency; hundreds of ms to low seconds is acceptable for this use case.

---

## 3. Requirements (Pragmatic)

**Functional**

- Ingest NetFlow v9/v10 (IPFIX), sFlow, or equivalent push/stream.
- Support at least: filter, aggregate, sample at ingest and in-stream.
- Store flows for query (time-range, filters, aggregations); retention TBD (e.g. 7–30 days hot, 90+ cold).
- Expose APIs for dashboards, alerts, and ad-hoc queries.

**Non-functional**

- **Throughput:** Sustain 10M flows/sec at the front door; design for 2–3x burst.
- **Durability:** At-least-once delivery into the pipeline; define “committed” at a clear boundary (e.g. after write to durable log).
- **Availability:** Ingest and query paths survive single-region AZ failures; no single point of bottleneck.
- **Latency:** Flow-to-query in seconds to low minutes for “recent” data; older data can be minutes.
- **Cost:** Design so storage and compute scale with retention and query pattern, not just raw ingest.

---

## 4. High-Level Architecture

```
                    ┌─────────────────────────────────────────────────────────────────┐
                    │                        INGEST LAYER                               │
                    │  (Stateless collectors / protocol adapters)                       │
                    └───────────────────────────┬─────────────────────────────────────┘
                                                │
                    ┌───────────────────────────▼─────────────────────────────────────┐
                    │                     MESSAGE BUS (Durable Log)                     │
                    │   Kafka/Pulsar — partitioned by flow key (e.g. hash(5-tuple))    │
                    └───────────────────────────┬─────────────────────────────────────┘
                                                │
         ┌──────────────────────────────────────┼──────────────────────────────────────┐
         │                                      │                                      │
         ▼                                      ▼                                      ▼
┌─────────────────┐                 ┌─────────────────────┐                 ┌─────────────────┐
│  STREAM PROC    │                 │  STREAM PROC        │                 │  STREAM PROC    │
│  (aggregation,  │                 │  (enrich, filter)   │                 │  (sampling,     │
│   pre-aggregate)│                 │                     │                 │   archive)      │
└────────┬────────┘                 └──────────┬──────────┘                 └────────┬────────┘
         │                                      │                                      │
         ▼                                      ▼                                      ▼
┌─────────────────┐                 ┌─────────────────────┐                 ┌─────────────────┐
│  HOT STORAGE    │                 │  HOT STORAGE        │                 │  COLD / LAKE    │
│  (time-series   │                 │  (indexed search,   │                 │  (Parquet/S3,   │
│   for metrics)  │                 │   recent queries)   │                 │   long retention)│
└─────────────────┘                 └─────────────────────┘                 └─────────────────┘
         │                                      │                                      │
         └──────────────────────────────────────┼──────────────────────────────────────┘
                                                │
                    ┌───────────────────────────▼─────────────────────────────────────┐
                    │              QUERY / API LAYER (routing, auth, caching)          │
                    └─────────────────────────────────────────────────────────────────┘
```

**Design principle:** One durable, partitioned log at ingest; everything else is a consumer. That gives replay, multiple consumers, and a clear “committed” boundary.

---

## 5. Component Design

### 5.1 Ingest Layer

**Role:** Accept flows from the network (NetFlow/sFlow/API), normalize to a single schema, optionally sample/filter, and publish to the message bus. No durable state; horizontally scalable.

- **Protocol adapters:** Separate processes or sidecars for NetFlow v9, IPFIX, sFlow, or REST/gRPC. Output: canonical flow record (e.g. Avro/Protobuf).
- **Sharding:** Publish to the bus with a partition key (e.g. `hash(device_id, exporter_id) % P` or hash of 5-tuple). Avoid hot keys (e.g. one partition per exporter with high fan-out).
- **Backpressure:** If the bus or downstream is slow, apply backpressure (drop or sample more aggressively) and expose metrics so ops see it. Better to shed load than OOM.
- **Optional pre-aggregation:** For known high-cardinality dimensions, you can do a first-stage aggregation (e.g. per /24 or per device per minute) before publish to reduce bus volume. Trade-off: flexibility vs ingest cost.

**Scale:** 10M/s ÷ (e.g. 50k flows/sec per instance) ⇒ order of hundreds of ingest nodes. Use a load balancer or DNS in front of collectors; collectors are stateless.

### 5.2 Message Bus (Durable Log)

**Choice:** Kafka or Pulsar. Both give partitioned, durable, replayable log. Kafka is more common in the wild for this pattern.

- **Partitioning:** Enough partitions to fan out 10M msg/s (e.g. 500–2000 partitions). Partition key = same as above so ordering per key is preserved (useful for aggregation).
- **Retention:** Short (hours to 1–2 days). The bus is a buffer and fan-out point, not the system of record. Longer retention = more disk and replay cost.
- **Replication:** Min 2 (prefer 3) in-sync replicas; acks=all for committed semantics.
- **Batching:** Producers batch (e.g. 100–500 ms or by size) to get throughput; tune for latency vs throughput.

**Why not “direct to DB”?** No single DB will do 10M writes/sec in a sane way. The log absorbs burst, decouples producers from consumers, and allows multiple consumers (real-time agg, search index, data lake).

### 5.3 Stream Processing

**Role:** Consume from the log, aggregate/filter/enrich, write to hot storage and/or cold lake.

- **Tech:** Apache Flink, Kafka Streams, or ksqlDB. Flink is a strong fit for high throughput and exactly-once semantics with checkpointing.
- **Patterns:**
  - **Aggregation:** Tumbling/sliding windows (e.g. 1 min, 5 min) by dimension (device, subnet, app). Reduces cardinality and storage.
  - **Enrichment:** Join with device/metadata store (e.g. lookup table in state); keep state small or use external DB.
  - **Sampling:** If you keep a “raw” path, sample (e.g. 1:10 or 1:100) for long retention to cap cost.
- **Outputs:** Write to time-series DB, search index, and/or object store (Parquet). Separate jobs per sink to isolate failure and scaling.

**Exactly-once:** Use producer idempotency + consumer offsets + transactional sink where supported. At-least-once with idempotent sinks is acceptable if duplicates are rare and query layer can tolerate (e.g. SUM is fine).

### 5.4 Hot Storage

**Purpose:** Recent data (e.g. last 7–30 days), low-latency queries (dashboard, alerts).

- **Options:**
  - **Time-series DB:** TimescaleDB, InfluxDB, or VictoriaMetrics. Good for time-range + tag filters and downsampled metrics.
  - **Search / wide table:** Elasticsearch or ClickHouse. Good for ad-hoc filters and group-by; ClickHouse is very strong for analytical queries on large volumes.
- **Recommendation:** One primary for “metrics-style” queries (time-series) and optionally ClickHouse for flexible analytics on recent flow aggregates. Don’t put 10M raw flows/sec into either; put aggregated/sampled streams.

**Schema:** Partition by time (e.g. day); sort/key by (time, device_id, …). Use TTLs and retention jobs to drop or move old data.

### 5.5 Cold / Data Lake

**Purpose:** Long retention (90+ days), compliance, ad-hoc analytics. Not low-latency.

- **Pattern:** Stream processor (or a separate job) writes Parquet files to S3/GCS, partitioned by date (and maybe device or tenant). Use a query engine (Trino, Athena, Spark) for SQL.
- **Cost:** Storage is cheap; compute is on-demand. Keep raw or lightly aggregated; avoid storing 10M/sec raw forever unless required — aggregate by hour/day for long retention.

### 5.6 Query / API Layer

- **Unified API:** One gateway that routes queries to hot (time-series / ClickHouse) or cold (lake engine) based on time range and capability.
- **Auth/RBAC:** AuthZ for who can query which scopes (e.g. tenant, network segment). Rate limits and query timeouts to protect backends.
- **Caching:** Cache frequent dashboard queries (e.g. last 5 min by device) with short TTL to reduce load on hot storage.

---

## 6. Key Trade-offs

| Decision | Option A | Option B | Choice / Rationale |
|----------|----------|----------|---------------------|
| Ingest semantics | At-least-once | Exactly-once | At-least-once at ingest; exactly-once in stream with idempotent sinks. Simpler and enough for analytics. |
| Raw vs aggregated | Store raw, aggregate on read | Aggregate in stream, store aggregates | Aggregate in stream for hot path; keep limited raw/sampled for debugging. Cost and scale force aggregation. |
| Retention | One tier | Hot + cold | Hot (days) + cold (months). Balances query latency and cost. |
| Partition key | Random | 5-tuple / device | Device or exporter to preserve order and locality; avoid one key dominating a partition. |
| Sampling | None | At ingest + in-stream | Use sampling for raw/sample path; full fidelity only where needed and for short windows. |

---

## 7. Failure Modes & Mitigations

- **Ingest overload:** Shed load (sample more, or reject with 503), alert, scale collectors. Don’t let ingest OOM.
- **Bus backlog:** Add consumers (scale stream jobs); if sustained, increase partitions and consumers. Monitor lag.
- **Storage slow:** Throttle write path (backpressure to stream layer); consider more nodes or different storage engine. Degrade to “recent data only” if necessary.
- **Region/AZ failure:** Run ingest and bus in multi-AZ; consumers and storage in at least two AZs. Failover for query layer (DNS/load balancer).
- **Bad traffic (poison flow):** Validate schema and size at ingest; dead-letter or drop invalid records; alert on error rate.

---

## 8. Observability of the System Itself

- **Metrics:** Ingest rate (per collector, per partition), bus lag, processing lag, write latency and error rate to hot/cold storage, query latency and error rate.
- **Logs:** Structured logs at ingest and in stream jobs; avoid logging every flow.
- **Alerting:** Lag above threshold, error rate spike, ingest drop/sample rate above threshold, storage write failures.

You’re building a pipeline that others will rely on; if you can’t see where it’s slow or dropping data, you’ll lose trust fast.

---

## 9. Security

- **Ingest:** Authenticate and authorize exporters (e.g. API key or mTLS for push); validate source IP if in a trusted network. Don’t accept arbitrary internet.
- **In transit:** TLS for all external and cross-AZ links.
- **At rest:** Encrypt storage (KMS + envelope encryption). Control access to cold store with IAM and bucket policies.
- **PII:** Define policy for IPs and other identifiers; mask or restrict access in query layer if required; apply retention and deletion for compliance.

---

## 10. Capacity Sketch (Order of Magnitude)

| Component | Rough sizing |
|-----------|------------------|
| Ingest | 200–500 nodes (20k–50k flows/sec each), stateless |
| Kafka | 500–2k partitions, 3 replicas; broker count from throughput (e.g. 1 broker per 100–200 MB/s). |
| Stream | Flink/Kafka Streams: scale by partition count and state size; start with 50–100 task slots, tune. |
| Hot storage | Per your DB choice; e.g. ClickHouse cluster sized for write throughput and query load. |
| Cold | S3/GCS; cost dominated by retention and query scan volume. |

Numbers are starting points; measure and iterate. Right-size from actual traffic and query patterns.

---

## 11. Phased Rollout (How I’d Do It)

1. **MVP:** Ingest (one protocol) → Kafka → one consumer writing to one hot store (e.g. ClickHouse or TimescaleDB). Prove 10M/sec on a single pipeline with sampling/aggregation.
2. **Add:** Retention, cold path (Parquet + query engine), and query API with routing.
3. **Harden:** Multi-AZ, exactly-once where needed, full observability and runbooks.
4. **Optimize:** Cost (aggregation, retention, compression), query performance, and operational playbooks.

---

## 12. One-Page Diagram (Logical)

```
[Exporters] --> [LB] --> [Collectors] --> [Kafka]
                    (normalize, sample)       |
                    (partition by key)        +--> [Flink] --> [Hot TSDB / ClickHouse]
                                              |         +--> [Parquet] --> [S3 + Trino]
                                              +--> [Flink] --> [Alerts / real-time]
                                                                    |
[Users] <-- [API Gateway] <-- [Query Router] <--+ [Hot] + [Cold]
```

---

**Summary:** 10M flows/sec is a throughput problem, not a “one big database” problem. Use a durable, partitioned log as the spine; stateless ingest in front; stream processing to aggregate and fan out; hot storage for recent queries and cold lake for retention. Design for backpressure, observability, and cost from day one, and you’ll have a system that can scale and operate in production.
