# Distributed Cache: When to Use What

## What is a Distributed Cache?

A distributed cache sits between your application and your database — storing frequently accessed data in memory across multiple nodes so reads are served in microseconds instead of milliseconds. Unlike a local in-process cache, all app instances share the same cache state.

---

## 1. Redis

**What it is:** In-memory data structure store. Supports strings, hashes, lists, sets, sorted sets, streams, bitmaps, HyperLogLog, geospatial indexes.

**Pros:**
- Rich data structures — not just key-value, supports complex operations natively
- Persistence options: RDB snapshots + AOF (append-only log)
- Pub/Sub messaging built in
- Lua scripting for atomic multi-step operations
- Redis Cluster for horizontal sharding
- Sorted sets make leaderboards, rate limiting trivial
- Streams for event log / message queue use cases
- Excellent client library support across all languages
- Redis Sentinel for HA without full cluster

**Cons:**
- Single-threaded command processing (though I/O is async) — one slow Lua script blocks everything
- Memory is expensive; data must fit in RAM
- Redis Cluster has limitations (multi-key operations must hash to same slot)
- Persistence adds latency; fully disabling it risks data loss on crash
- More operationally complex than Memcached

**Use when:**
- Session storage
- Rate limiting (sliding window counters)
- Leaderboards / rankings (sorted sets)
- Distributed locks (Redlock algorithm)
- Real-time analytics
- Job queues (Redis Lists or Streams)
- Feature flags, config caching
- You need persistence or pub/sub alongside caching

---

## 2. Memcached

**What it is:** Pure, simple, high-performance distributed memory cache. Key-value only, no persistence.

**Pros:**
- Blazing fast — extremely low overhead, multi-threaded
- Dead simple — no complex data types to reason about
- Very memory efficient for plain string/blob caching
- Scales horizontally easily (consistent hashing on client side)
- Battle-tested (Facebook, Twitter at massive scale)

**Cons:**
- No persistence — everything lost on restart
- No replication built in — node failure = cache miss for that shard
- Strings/blobs only — no lists, sets, sorted sets
- No pub/sub, no scripting
- Client-side sharding means clients must coordinate
- No cluster management — operational burden on you

**Use when:**
- Pure read-through caching of database query results
- You need maximum throughput for simple object caching
- Horizontal scaling is the priority
- You don't need persistence, pub/sub, or complex data types
- Stateless cache where losing data is acceptable

---

## 3. Hazelcast

**What it is:** In-memory data grid (IMDG). Java-native, embeddable, distributed computing platform — cache + compute.

**Pros:**
- Embeds directly into JVM application (no separate server needed)
- Distributed computing: distributed executor, entry processors — run logic next to data
- Built-in distributed data structures: Maps, Queues, Topics, Lock, Semaphore
- Strong consistency options (CP subsystem via Raft)
- Near-cache feature — L1 local cache + L2 distributed cache
- Automatic data partitioning and rebalancing
- JCache (JSR-107) compliant
- Supports SQL queries over cached data

**Cons:**
- Java/JVM centric — other language clients are less mature
- Heavier footprint than Redis/Memcached
- Embedded mode creates coupling between cache topology and app deployment
- Steeper learning curve
- Community edition has limitations vs enterprise

**Use when:**
- Java microservices or Spring Boot applications
- You need distributed locking, semaphores, or coordination primitives
- Compute-near-data patterns (process data where it lives)
- Strong consistency requirements
- You want the cache embedded in the app (no separate infra)

---

## 4. Apache Ignite

**What it is:** Distributed database, caching, and processing platform. Combines persistent storage + in-memory caching + SQL + compute grid.

**Pros:**
- Full ACID transactions across distributed nodes
- Native persistence (data survives restarts without a separate DB)
- SQL support over cached data (ANSI SQL, JOINs)
- Collocated compute — run processing next to cached data
- Works as a caching layer in front of existing RDBMS
- Supports both in-memory and disk-based storage tiers
- Machine learning grid built in

**Cons:**
- Very heavy — significant operational complexity
- Overkill for simple caching needs
- JVM-centric
- Slower adoption = smaller community vs Redis
- Configuration-heavy

**Use when:**
- You need distributed ACID transactions over cached data
- Large-scale analytics on in-memory data with SQL
- Replacing a traditional RDBMS for hot data with persistence
- Financial systems needing transactional consistency + speed

---

## 5. Ehcache

**What it is:** Java-based cache, widely used with Hibernate as a second-level cache. Can be distributed via Terracotta.

**Pros:**
- Seamless integration with Hibernate (second-level cache)
- JCache compliant
- Tiered storage: heap → off-heap → disk
- Simple configuration for Spring applications

**Cons:**
- Distributed mode requires Terracotta Server (commercial)
- Java only
- Not a first-class distributed system — primarily a local cache with optional distribution
- Rarely chosen for greenfield distributed caching today

**Use when:**
- Hibernate second-level cache in a Java monolith
- You need heap → off-heap → disk tiering in a single JVM
- Already in the stack and scaling isn't a concern

---

## 6. Aerospike

**What it is:** High-performance distributed NoSQL database with strong caching characteristics. Hybrid memory architecture (DRAM + SSD).

**Pros:**
- Extremely low latency even at massive scale (sub-millisecond)
- Hybrid storage: hot data in RAM, warm data on SSD (NVMe), treated as cheap RAM
- Strong consistency with configurable durability
- Auto-sharding, replication, and rebalancing
- Used at AdTech scale (billions of ops/day)

**Cons:**
- Not a pure cache — it's a database with cache behavior
- Higher operational complexity
- Smaller ecosystem than Redis
- Cost at scale

**Use when:**
- AdTech, fraud detection — billions of lookups/day with SLA < 1ms
- You need SSD as a cost-effective RAM substitute
- Data too large to fit purely in RAM but too hot for a traditional DB

---

## Caching Strategies (applies to all)

| Strategy | How it works | Use case |
|---|---|---|
| **Cache-Aside (Lazy)** | App checks cache → miss → load from DB → write to cache | General purpose, most common |
| **Read-Through** | Cache handles DB load automatically on miss | Simplifies app code |
| **Write-Through** | Write to cache + DB synchronously | Strong consistency, write-heavy |
| **Write-Behind (Write-Back)** | Write to cache, async flush to DB | High write throughput |
| **Refresh-Ahead** | Proactively refresh before TTL expires | Predictable access patterns |

---

## Quick Decision Guide

| Need | Pick |
|---|---|
| General purpose, most use cases | **Redis** |
| Pure high-throughput object caching | **Memcached** |
| Java apps, distributed locking, compute | **Hazelcast** |
| Distributed ACID transactions + SQL | **Apache Ignite** |
| Hibernate second-level cache | **Ehcache** |
| Billions of ops/day, SSD-backed | **Aerospike** |

---

## Key Concepts to Know

### Eviction Policies
- `LRU` — evict least recently used (most common)
- `LFU` — evict least frequently used
- `TTL` — time-based expiry regardless of access

### Cache Invalidation Problems
- **Thundering herd** — cache expires, thousands of requests hit DB simultaneously → use mutex/probabilistic early expiry
- **Cache stampede** — same as above, mitigated by jitter on TTLs
- **Stale data** — write-through or event-driven invalidation required
- **Cache penetration** — querying for non-existent keys repeatedly → use bloom filters

---

> **The hard problem in caching is not performance — it's correctness. Cache invalidation and stale data cause more production bugs than slow caches.**
