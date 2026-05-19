# Scaling Reads — System Design (Staff / L6 Level)

Read scaling is one of the most common bottlenecks in production systems. Most real-world applications are **read-heavy**: social feeds have 100:1 read-to-write ratios; e-commerce product pages serve millions of reads for every purchase; analytics dashboards issue large, expensive queries against data that rarely changes. Understanding how to scale reads — and which technique fits which problem — is essential for staff-level system design.

---

## 1. Why Reads Become the Bottleneck

A single write-optimised primary database can handle a few thousand QPS before latency degrades. Read traffic patterns that saturate a database:

| Pattern | Why it hurts |
|---------|-------------|
| **High QPS on hot rows** | A few rows (e.g. a viral post, a product listing) receive millions of reads per second; CPU and I/O saturate on a single machine |
| **Expensive aggregation queries** | `GROUP BY`, `JOIN`, `COUNT(*)` scan large tables; a handful of concurrent queries can stall all reads |
| **Deep pagination** | `OFFSET 1_000_000 LIMIT 20` forces the DB to skip 1M rows every page |
| **Fan-out on write is missing** | Reading a user's timeline by joining 500 followed users' posts at query time |
| **Mixed OLTP + OLAP on same DB** | Analytical queries hold locks or exhaust I/O buffer pool, starving transactional reads |

The goal of read scaling is to **move reads away from the primary write path** without breaking correctness guarantees the system promises to its clients.

---

## 2. Strategy Landscape

Every read-scaling technique trades **consistency** for **throughput, latency, or cost**. Understanding this trade-off is the core of the interview answer.

```
                HIGH CONSISTENCY
                        ▲
                        │
      Read replicas      │     Read your writes
      (sync)             │     (routing tricks)
                         │
  ◄──────────────────────┼──────────────────────►
  LOWER COST /           │              LOWER LATENCY /
  SIMPLER OPS            │              HIGHER THROUGHPUT
                         │
  Async replicas         │     In-process cache
  CDN edge cache         │     Redis (sub-ms)
  Materialized views     │     CQRS read models
                         │
                        ▼
                 EVENTUAL CONSISTENCY
```

| Technique | Latency | Scalability | Consistency challenge |
|-----------|---------|-------------|----------------------|
| Read replicas | Low–medium | High | Replica lag |
| Caching (Redis) | Sub-ms | Very high | Stale data, invalidation |
| CDN / edge cache | ~1–10ms | Extreme | Cache invalidation |
| CQRS + read model | Low | Very high | Projection lag |
| Materialized views | Low | Medium | Refresh cost/lag |
| Search index (ES) | Low | High | Index staleness |
| Denormalisation | Low | High | Write-time fan-out cost |
| Sharding reads | Low | Very high | Cross-shard aggregation |

---

## 3. Read Replicas

### How It Works

The primary database streams a replication log (WAL in PostgreSQL, binlog in MySQL) to one or more **replica servers**. Replicas replay these logs and stay near-current. Application read traffic is routed to replicas; only writes go to the primary.

```
  Write Path                  Read Path
  ──────────                  ─────────
  App → Primary ──WAL──► Replica 1 ──► App (reads)
                    │──► Replica 2 ──► App (reads)
                    │──► Replica 3 ──► App (reads)
```

### Types of Replication

| Type | Lag | Use Case |
|------|-----|----------|
| **Synchronous** | ~0 (write blocks until replica confirms) | Financial, audit — RPO = 0 |
| **Semi-synchronous** | Minimal (write blocks for at least 1 replica) | Balance between durability and write latency |
| **Asynchronous** | Milliseconds to seconds | Most read-heavy use cases; acceptable eventual consistency |

### Read Routing Patterns

**Explicit routing in application code:**
```
// PGBouncer / HikariCP: configure two pools
DataSource primary  = pool("primary-host:5432");
DataSource replica  = pool("replica-host:5432");

// Use primary for writes and consistent reads
// Use replica for reporting, history, non-critical reads
```

**Proxy-based routing (ProxySQL, RDS Proxy, PgBouncer):**
- Proxy inspects the SQL: `SELECT` → replica pool; `INSERT/UPDATE/DELETE` → primary.
- Transparent to the application; reduces connection count on primary.

**Driver-level routing (JDBC, libpq):**
- Drivers like AWS JDBC Driver or Spring's `AbstractRoutingDataSource` route based on a transaction annotation (`@Transactional(readOnly = true)` → replica).

### Replica Lag: The Key Risk

Replica lag means a read issued immediately after a write may return stale data. Lag sources:
- Network RTT between primary and replica
- Lock contention on the replica while replaying large transactions
- Schema operations (DDL) that pause replication

**Mitigation strategies:**

| Problem | Solution |
|---------|----------|
| Read-your-writes: user writes then immediately reads their own data and sees stale state | Route the user's reads to primary for a short window (e.g. 5s) after any write from that user. Or use a "read-after-write" token: primary returns a replication position; client sends it; replica delays serving until it has replayed past that position |
| Replica falls behind under heavy write load | Dedicated replica for reporting; ring replication; cascade replica |
| Lag spike during long transactions | Break up batch operations; use logical replication filters |
| Cache miss going to lagging replica | On cache miss, read from primary as fallback |

### When to Use Read Replicas

- Read:write ratio > 5:1
- Reporting / analytics queries that would starve OLTP reads if run on primary
- Geographic read distribution (replica in another region for local latency)
- Zero-downtime failover: promote a replica when the primary fails

### Limits of Read Replicas Alone

Replicas still execute full SQL queries — expensive aggregations, large joins, and sequential scans still run on the replica machine. If a single query is expensive, replicas only help by distributing different-user queries, not by making each query cheaper. Combine with caching or materialised views for heavy aggregate queries.

---

## 4. Caching

Caching eliminates database round-trips entirely for popular reads by serving results from memory.

### Cache-Aside (Lazy Loading) — Most Common

```
Application logic:

value = cache.get(key)
if value is None:
    value = db.query(...)
    cache.set(key, value, ttl=300)
return value
```

**Pros:** Simple; cache only populates with data that is actually accessed; cache failure is non-fatal (falls back to DB).

**Cons:** First request always misses; thundering herd on cold start or TTL expiry; application owns invalidation logic.

### Read-Through

Cache sits in front of DB; on miss, the cache itself fetches from DB, populates, and returns the value.

**Pros:** Application code is simpler — always reads from cache.

**Cons:** Cache vendor must support pluggable data loaders; less control over cache-population logic.

### Write-Through

Every write goes to both DB and cache synchronously.

```
db.write(key, value)
cache.set(key, value)          // same request
```

**Pros:** Cache is always warm and consistent with DB after every write.

**Cons:** Write latency increases (two synchronous writes); cache stores data that may never be read (wasted memory).

### Write-Behind (Write-Back)

Write goes to cache first; cache asynchronously flushes to DB in batches.

**Pros:** Very low write latency; DB is not on the critical write path.

**Cons:** Data loss risk if cache node fails before flush; complex crash-recovery logic; rarely used in financial systems.

### Refresh-Ahead

Cache proactively refreshes an entry before its TTL expires, based on predicted access.

**Pros:** Eliminates latency spikes at TTL boundaries.

**Cons:** Wastes resources fetching data that may no longer be hot; complex to implement correctly.

### Cache Topology

| Setup | Description | Use Case |
|-------|-------------|----------|
| **Local in-process** | Guava Cache, Caffeine — memory inside the app process | Immutable config, rarely-changing reference data |
| **Distributed (Redis, Memcached)** | Shared cache across all app instances | Session data, user profiles, hot rows |
| **Layered (L1 + L2)** | L1 = local in-process (sub-microsecond), L2 = Redis (sub-ms) | Maximum throughput; local serves hot, Redis serves warm |

### What to Cache

| Good Cache Candidates | Poor Cache Candidates |
|----------------------|-----------------------|
| User profiles, preferences | Highly personalised, unique-per-user data |
| Product catalogue, pricing | Data requiring strong consistency |
| Top-N leaderboards | Data that changes faster than TTL |
| Session tokens | Aggregations that span many entities |
| Reference data (country codes, config) | Write-heavy entities |

### Cache Invalidation Patterns

This is the **hardest problem** in caching. Three approaches:

**TTL-based expiry:** Set a TTL and accept eventual consistency. Simple; the only question is how stale is acceptable.

**Event-driven invalidation:** When the DB record changes, publish an event. Cache subscriber deletes or updates the key.
```
DB write → Kafka event → Cache consumer → cache.delete(key)
```
Consistent but adds complexity; invalidation events can be lost or delayed.

**Write-through invalidation:** Application updates cache on every write path (write-through or explicit delete-on-write).

### Cache Failure Modes and Mitigations

| Problem | Description | Mitigation |
|---------|-------------|------------|
| **Thundering herd** | Cache TTL expires simultaneously for a hot key; thousands of requests hit DB | Probabilistic early expiry (PER); mutex/single-flight (only one goroutine fetches, rest wait) |
| **Cache stampede** | Cold start or flush causes mass cache misses | Warm cache before switching traffic; background prefill |
| **Cache penetration** | Requests for non-existent keys always miss → DB scan per request | Cache null results with short TTL; Bloom filter at cache layer to reject known-absent keys |
| **Cache avalanche** | Many keys expire at once | Jitter: randomise TTLs within a window (e.g. `TTL ± 20%`) |
| **Hot key** | One key (e.g. celebrity profile) gets millions of QPS on a single Redis shard | Replicate hot key across N shards; client-side load-balances reads; local L1 cache |

---

## 5. CDN and Edge Caching

For publicly accessible, geography-distributed reads (web pages, API responses, media), a CDN serves responses from a **point of presence (PoP) close to the user**, eliminating the origin round-trip entirely.

```
User (Sydney) → CDN PoP (Sydney, cache HIT) → response in ~5ms
               vs.
User (Sydney) → Origin (US East) → response in ~200ms
```

### What CDNs Cache

- Static assets: JS, CSS, images, fonts
- API responses with `Cache-Control: max-age=N` headers
- Pre-rendered HTML pages
- Large files (video, downloads)

### Cache-Control Semantics

```http
Cache-Control: public, max-age=3600, stale-while-revalidate=60
```

| Directive | Meaning |
|-----------|---------|
| `public` | CDN may cache this response |
| `max-age=3600` | Serve from cache for 1 hour |
| `stale-while-revalidate=60` | After 1h, serve stale while asynchronously refreshing |
| `s-maxage=N` | Overrides `max-age` for shared (CDN) caches |
| `no-store` | Never cache (private, sensitive data) |

### Purging / Invalidation

CDN cache invalidation is a critical operational concern:

- **TTL expiry:** Simplest; stale for up to TTL duration.
- **Cache-key versioning:** Embed content hash in URL (`/assets/app.a3f9.js`). No invalidation needed — new hash = new URL.
- **Purge API:** Cloudflare, Fastly, Akamai provide tag-based purge — purge all assets tagged `product-id:12345` on product update.
- **Surrogate keys / Cache tags:** Tag each cached response with its data dependencies; purge by tag when data changes.

### When CDNs Don't Help

- **Personalised responses:** Content unique to each user cannot be shared across CDN cache (unless you split: cache the shell, fetch personalised data via JS).
- **Real-time data:** Stock prices, live match scores — TTL of 0 defeats the CDN.
- **POST/PUT requests:** CDNs cache GET responses; write operations pass through.

---

## 6. CQRS (Command Query Responsibility Segregation)

CQRS separates the **write model** (command side) from the **read model** (query side). Instead of reading from the same normalised schema that writes use, you maintain a **denormalised read model** optimised for each query pattern.

```
            WRITE SIDE                    READ SIDE
            ──────────                    ─────────
Client → Command Handler              Query Handler → Client
              │                             ▲
              ▼                             │
       Write DB (normalised)        Read Store (denormalised)
       PostgreSQL / primary          Redis / Elasticsearch /
                │                    materialised table
                │   Event / CDC
                └──────────────────► Projection Builder
                                      (updates read model
                                       on each write event)
```

### Concrete Example: Social Feed

**Without CQRS** (fan-out on read):
```sql
-- Timeline query: expensive JOIN at read time
SELECT p.*, u.name FROM posts p
JOIN follows f ON f.followed_id = p.user_id
WHERE f.follower_id = :me
ORDER BY p.created_at DESC LIMIT 20;
-- Scans follows table (potentially 500+ rows) × posts per follow
```

**With CQRS** (fan-out on write):
- On write: when user A posts, push post ID to the timeline cache of each follower.
- On read: `LRANGE timeline:{user_id} 0 19` — O(1) Redis read, no join.

Write cost increases (fan-out to N followers), but read cost drops from O(follows × posts) to O(1).

### Projection Builder Patterns

| Pattern | How | Trade-off |
|---------|-----|-----------|
| **Synchronous** | Command handler updates read model in the same transaction | Consistent but slow writes; couples write + read stores |
| **Event-driven async** | Command → event → Kafka → consumer updates read model | Decoupled, scalable; introduces projection lag |
| **CDC (Debezium)** | DB change log → stream processor → read model | No application change needed; adds operational complexity |
| **Scheduled rebuild** | Batch job periodically rebuilds entire read model | Simple; staleness up to rebuild interval |

### When to Use CQRS

- Query patterns and write patterns have very different shapes (e.g. writes are row-at-a-time; reads are denormalised aggregations)
- Read QPS >> Write QPS by 10x or more
- Read model needs a different storage technology (e.g. Elasticsearch for full-text search)
- You can tolerate eventual consistency in the read model (projections may lag writes by milliseconds to seconds)

### CQRS Risks

- **Projection lag:** The read model lags behind the write model. For operations that require seeing your own recent write (e.g. "did my order confirm?"), you must either read from the write side, or accept the lag.
- **Operational complexity:** Two data stores, projection consumers, lag monitoring, replay logic for rebuilding stale projections.
- **Not a universal pattern:** For simple CRUD with low QPS, CQRS is over-engineering. Introduce it when a specific query pattern cannot be served acceptably by replicas or caching.

---

## 7. Materialised Views

A materialised view is a **pre-computed, persisted result set** that the database refreshes either on demand, on a schedule, or incrementally.

### Database-native (PostgreSQL example)

```sql
CREATE MATERIALIZED VIEW product_summary AS
SELECT
    p.category_id,
    COUNT(*)            AS total_products,
    AVG(p.price)        AS avg_price,
    SUM(p.inventory)    AS total_inventory
FROM products p
GROUP BY p.category_id;

-- Refresh manually (takes a full lock on the view during refresh):
REFRESH MATERIALIZED VIEW product_summary;

-- Concurrent refresh (no read lock, requires unique index):
REFRESH MATERIALIZED VIEW CONCURRENTLY product_summary;
```

Query hits the view (`SELECT * FROM product_summary WHERE category_id = ?`) — pre-computed, O(1) scan on a small result set, not a full table scan.

### Incremental Materialisation (streaming)

Tools like **Apache Flink**, **Materialize**, or **dbt incremental models** maintain views that update in near real-time as the source data changes, rather than requiring a full recompute.

```
Source DB → CDC stream → Flink job → materialised store (Redis / DB table)
```

### Trade-offs

| Aspect | Pro | Con |
|--------|-----|-----|
| Read latency | Dramatically reduced — pre-computed | — |
| Write-time cost | — | Refresh cost; full refresh holds locks |
| Freshness | Controllable (schedule vs. incremental) | Stale between refreshes for scheduled |
| Consistency | Strong if refreshed in same transaction | Eventual if async |

### When to Use

- Expensive aggregation queries (dashboards, reports) that run frequently on rarely-changing data
- When the refresh schedule matches the freshness tolerance (e.g. hourly report = refresh every hour)
- Avoid for fast-moving data or data needing per-request freshness

---

## 8. Search Indexes (Elasticsearch / OpenSearch)

For **full-text search, faceted filtering, and complex ad-hoc queries**, relational databases are poorly suited. Elasticsearch maintains an **inverted index** that makes these operations efficient.

```
Write path:
  DB write → CDC / dual-write → Elasticsearch index update

Read path:
  Client → App → Elasticsearch → results
                           (no DB read for search)
```

### What Elasticsearch Optimises

| Query type | DB cost | ES cost |
|-----------|---------|---------|
| Full-text search (`LIKE '%term%'`) | Full table scan | O(log N) inverted index lookup |
| Multi-field faceting (`category + price range + rating`) | Multiple index scans + joins | Single document-store aggregation |
| Fuzzy / synonym matching | Requires PG extensions | Built-in analyzers |
| Geospatial range queries | Possible but slow | Native geo index |

### Consistency Model

Elasticsearch is **eventually consistent** with the source DB. After a DB write, the ES index is updated within seconds (configurable `index.refresh_interval`, default 1s). The application must decide:

- **For writes that must be immediately searchable:** dual-write synchronously (write DB + ES in same request, accept higher write latency).
- **For slightly stale search acceptable:** async CDC pipeline (Debezium → Kafka → ES consumer).

### Limits

- Elasticsearch is not a primary datastore: no ACID transactions, no arbitrary joins.
- Operational complexity: cluster management, index mapping changes, reindexing at scale.
- Use ES for search/analytics; keep primary data in a relational or document DB.

---

## 9. Denormalisation

Denormalisation stores **redundant, pre-joined data** in the schema so reads avoid expensive joins at query time.

### Example

**Normalised (join at read time):**
```sql
-- orders joined with users and products: 3 tables, 2 joins
SELECT o.id, u.name, u.email, p.title, p.price
FROM orders o
JOIN users u ON u.id = o.user_id
JOIN products p ON p.id = o.product_id
WHERE o.id = :order_id;
```

**Denormalised (data stored together):**
```sql
-- All data embedded in orders table at write time
SELECT id, user_name, user_email, product_title, product_price
FROM orders
WHERE id = :order_id;
-- Single index seek; no join
```

At write time, the order record stores `user_name`, `user_email`, etc. copied from the user record. If the user later changes their email, historical orders still show the email at time of purchase — which is often semantically correct for order history.

### Trade-offs

| Aspect | Denormalised | Normalised |
|--------|-------------|------------|
| Read speed | Fast — single row lookup | Slower — multiple joins |
| Write speed | Slower — must update all copies | Fast — single source |
| Storage | Higher (duplicated data) | Lower |
| Consistency | Risk of stale copies | Always consistent |

### When to Use

- Point-in-time correctness is desired (e.g. invoice/order history — preserve data as it was at creation time)
- Hot read paths (millions of QPS) where join cost is measurable
- Document stores (MongoDB, DynamoDB): denormalise naturally since joins are not supported

---

## 10. Database Sharding for Read Distribution

Horizontal sharding splits data across multiple database instances. Reads are routed to the shard that owns the requested data — each shard serves only its subset.

```
Request (user_id = 1234) → Shard Router → Shard 2 (handles user_id 1000–1999)
Request (user_id = 5678) → Shard Router → Shard 5 (handles user_id 5000–5999)
```

### Read Scaling via Sharding

- Each shard has its own read replicas → total read capacity = shards × replicas per shard.
- Query cost per shard is lower (smaller dataset per shard → smaller indexes, better cache hit rates, faster scans).

### Shard Keys and Read Access Patterns

The shard key must match the primary read access pattern:

| Shard key | Read pattern it optimises | Problem |
|-----------|--------------------------|---------|
| `user_id` | "All data for a user" — single-shard read | Cross-user aggregation scans all shards |
| `tenant_id` | Multi-tenant SaaS — tenant isolation | Imbalanced tenants (large tenant on one shard) |
| Hash of key | Even distribution | Range queries scatter across shards |
| `created_at` (range) | Time-series, recent data is hot | Hot-shard problem: newest shard takes all writes |

### Cross-Shard Aggregation

When a query must span multiple shards (e.g. global leaderboard, platform-wide analytics), the application **fans out** the query to all shards and merges results. This is expensive — prefer a separate read aggregate store (e.g. a dedicated analytics replica or a pre-built CQRS projection) rather than scatter-gather at query time.

---

## 11. Consistency Models in Read Scaling

Every read-scaling technique weakens consistency in some dimension. Know the model you're operating in.

| Consistency Level | Description | Techniques that provide it |
|-------------------|-------------|---------------------------|
| **Strong** | Every read sees the latest committed write | Read from primary; synchronous replica |
| **Read-your-writes** | A user always sees their own writes | Route user's reads to primary for N seconds post-write; sticky sessions |
| **Monotonic reads** | A user never sees a "rollback" of state (time doesn't go backwards) | Route same user to same replica consistently |
| **Consistent prefix** | Reads see a consistent prefix of the write order | Single-shard reads; replica with guaranteed ordering |
| **Eventual** | Reads will converge to correct state, but may be transiently stale | Async replicas, CDN, Redis TTL, CQRS projections |

### Choosing a Consistency Level

Ask the product question: **what is the worst-case user experience from stale data?**

- Balance page shows stale balance → user disputes a charge. **Use strong consistency (read from primary).**
- News feed is 2 seconds behind → acceptable staleness. **Async replica or cache.**
- Search results don't reflect a product that was just listed → tolerable for 1 min. **Elasticsearch async pipeline.**
- User clicks "post comment" and immediately doesn't see their own comment → bad UX. **Read-your-writes required.**

---

## 12. Combining Techniques: Layered Architecture

Production systems rarely use a single technique. A typical high-traffic read path layers multiple strategies:

```
                            ┌─────────────────────────────────┐
Client Request              │      Application Layer          │
     │                      │                                 │
     ▼                      │  1. L1: In-process cache        │
  Load Balancer             │     (Caffeine, sub-microsecond) │
     │                      │         │  miss                 │
     ▼                      │         ▼                       │
  App Server ─────────────► │  2. L2: Redis                   │
                            │     (sub-ms, distributed)       │
                            │         │  miss                 │
                            │         ▼                       │
                            │  3. Read Replica                │
                            │     (SQL, milliseconds)         │
                            │         │  miss / strong read   │
                            │         ▼                       │
                            │  4. Primary DB                  │
                            │     (authoritative source)      │
                            └─────────────────────────────────┘
```

**Example: E-commerce product page (10,000 QPS)**

| Layer | What it serves | Hit rate target |
|-------|---------------|-----------------|
| CDN (edge) | Full page or HTML shell | 70% of traffic |
| Redis (product data) | Product details, price, inventory | 25% of remaining |
| Read replica | Recent reviews, live inventory | 4% of remaining |
| Primary | Authoritative inventory for checkout | <1% (write path only) |

The 10,000 QPS drops to ~100 QPS on the read replica and near-zero on the primary. Each layer only needs to handle what the layer above it misses.

---

## 13. Operational Concerns

### Monitoring Read Scaling Health

Key metrics to track on every read-scaling component:

| Component | Metric | Alert Threshold |
|-----------|--------|-----------------|
| Read replica | Replication lag (`seconds_behind_master`) | > 5s |
| Redis | Cache hit rate | < 85% |
| Redis | Eviction rate | > 0 (memory pressure) |
| CDN | Cache hit ratio | < 80% |
| Elasticsearch | Index refresh lag | > 30s |
| Application | p99 read latency | > SLO threshold |
| DB replica | Active connections | > 80% of pool |

### Capacity Planning

- Read replicas: add a replica when average CPU on existing replicas > 60% sustained.
- Redis: scale horizontally (Redis Cluster) when memory usage > 70% or p99 > 1ms.
- CDN: CDN providers auto-scale; monitor origin request rate (what CDN fails to serve).

### Replica Promotion (Failover)

If the primary fails, a replica must be promoted:

1. **Stop replication** on the promoted replica.
2. **Redirect writes** (update DNS / proxy config) to the new primary.
3. **Other replicas replicate from the new primary**.
4. **Monitor for data divergence** if the old primary had writes that were not yet replicated.

Use tools like Patroni (PostgreSQL), MHA (MySQL), or managed services (AWS RDS Multi-AZ) to automate failover.

---

## 14. Decision Framework

When asked "how would you scale reads in this system?", walk through:

```
1. What is the read:write ratio?
   - < 5:1  → No scaling needed yet; optimise indexes first
   - 5:1 to 20:1 → Read replicas
   - > 20:1  → Add caching; consider CQRS or materialised views

2. What is the acceptable staleness?
   - 0ms (financial, user's own data) → Read from primary; sync replica; read-your-writes routing
   - <1s (social feed, notifications) → Async replica; Redis with short TTL
   - Minutes acceptable (analytics, reports) → Materialised views; CQRS projections; CDN

3. What is the query shape?
   - Point lookups by key → Redis cache; read replicas
   - Expensive aggregations → Materialised views; CQRS read model; analytics replica
   - Full-text search → Elasticsearch
   - Fan-out reads (timeline, feed) → CQRS with denormalised read model; Redis sorted sets
   - Geographically distributed → CDN; regional read replicas

4. Is the data personalised?
   - No (product pages, public content) → CDN; aggressive caching
   - Yes (user dashboard, personalised feed) → Redis per-user cache; CQRS per-user projection

5. What is the traffic pattern?
   - Uniform → Read replicas + Redis
   - Spiky / unpredictable → CDN for burst absorption; auto-scaling read replica fleet
   - Hot keys (celebrity / viral content) → Local L1 cache; Redis key replication across shards
```

---

## 15. Common Interview Questions and Answers

**Q: How do you handle replica lag for a feature that requires read-your-writes?**

Route the user's reads to the primary for a bounded window (e.g. 5 seconds) after any write from that user. Store a `last_write_timestamp` per user in Redis; middleware compares with current time and routes accordingly. Alternative: use monotonic replication tokens — primary returns a log sequence number (LSN) with the write response; the client sends this LSN with subsequent reads; the replica waits until it has replayed past that LSN before responding.

**Q: When would you NOT use a cache?**

When correctness requires the latest value on every read (financial balances, stock inventory for checkout), stale cache data causes real business harm. Also: when data changes faster than TTL, the cache hit rate is so low it adds latency without benefit. And when every read is unique (highly parameterised queries), there is no reuse, so caching adds memory overhead with no throughput gain.

**Q: CQRS sounds like a lot of complexity — when is it worth it?**

When the write model and read model are fundamentally different shapes. If your writes are simple row inserts but your reads are 10-table aggregations, you are doing expensive computation on every read to serve a shape that does not exist in the write model. CQRS pre-computes that shape once on write and amortises the cost across all reads. The operational overhead (projection builders, lag monitoring, replay logic) is justified when read latency or DB load from expensive queries has become a measured problem.

**Q: How do you prevent a thundering herd on cache expiry?**

Three techniques: (1) **Jitter** — randomise TTLs within ±20% so keys don't all expire at once. (2) **Probabilistic early expiration (PER)** — with some probability, start refreshing before TTL expires; the earlier it is, the higher the probability. (3) **Request coalescing (single-flight)** — on a cache miss, only one goroutine/thread fetches from DB; all other concurrent requests for the same key wait for that result. Most Redis client libraries support this pattern.

**Q: What happens to your caching strategy when you shard your database?**

The cache key should include the shard identifier (or the shard key value) to avoid serving data from the wrong shard. Cache invalidation events must be published per-shard. Hot-key problems are amplified at shard boundaries — a celebrity's profile might be on shard 3, making shard 3's cache disproportionately hot. Address with per-key local L1 caches or key replication across Redis shards.

**Q: How do you keep Elasticsearch in sync with your primary database?**

Preferred: CDC pipeline using Debezium to capture the PostgreSQL WAL, publish to Kafka, consumed by an Elasticsearch sink connector. This is decoupled, retryable, and adds minimal write-path overhead. Alternative: dual-write in the application (write DB + ES in the same request); simpler but couples the write path to ES availability. For bulk initial loads, use the Elasticsearch bulk API with parallelised export from a read replica.

---

## 16. Trade-off Summary

| Technique | Best For | Consistency Risk | Operational Complexity |
|-----------|----------|-----------------|----------------------|
| Read replicas | General read offload, failover | Replica lag | Low (managed services) |
| Redis cache | Sub-ms latency, hot key absorption | Stale data, invalidation | Medium |
| CDN | Public, geographically distributed content | Stale until TTL/purge | Low |
| CQRS | Differently-shaped read models, high read:write ratio | Projection lag | High |
| Materialised views | Pre-computed aggregations | Stale between refreshes | Low–Medium |
| Elasticsearch | Full-text search, complex faceting | Index staleness | High |
| Denormalisation | Point-in-time reads, removing joins | Stale copies on update | Low (design-time cost) |
| Sharding | Horizontal partition of massive datasets | Cross-shard aggregation | High |

---

## 17. Interview Cheat Sheet

**Opening frame (30 seconds):** "Read scaling means moving reads away from the primary write path without breaking the consistency guarantees the system requires. The key variable is acceptable staleness — that determines which techniques are available."

**Three-layer answer:**
1. **Immediate wins:** Read replicas + connection routing. Low complexity, high impact.
2. **Cache layer:** Redis cache-aside with TTL jitter. Absorbs 90%+ of hot reads.
3. **Structural changes:** CQRS / materialised views / denormalisation when query shape fundamentally differs from write shape.

**Key trade-off to name:** Every read-scaling technique is a consistency trade-off. Sync replicas add write latency. Caches go stale. CQRS projections lag. Name the trade-off, then name how you'd detect and mitigate it (lag monitoring, read-your-writes routing, TTL tuning, etc.).

**Numbers to remember:**
- Async replica lag: typically < 100ms under normal load; can spike to seconds under heavy write load
- Redis: < 1ms p99 at typical scale; handles 100,000+ QPS per node
- CDN cache hit rate target: > 80% for meaningful origin offload
- L1 in-process cache: sub-microsecond; 10–100× faster than Redis
- Rule of thumb: add read replicas when replica CPU > 60% sustained; add Redis when DB read QPS > 5,000 on hot data

**One-liner for each technique:**
- **Read replica** — same data, different machine, eventual consistency
- **Redis** — hot data in memory, sub-ms, manages invalidation carefully
- **CDN** — push reads to the network edge, cache-control is the contract
- **CQRS** — pre-compute the read shape at write time, pay once serve many
- **Materialised view** — pre-aggregated result set, refresh on schedule or incrementally
- **Elasticsearch** — inverted index for full-text and faceted search, async sync from DB
- **Denormalise** — store data in the shape you read it, avoid joins at query time
