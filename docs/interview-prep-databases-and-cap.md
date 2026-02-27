# Interview Prep: Databases & CAP Theorem

A concise reference for database types, use cases, and CAP theorem.

---

## Part 1: Database Types & Best Use Cases

### 1. Relational (SQL) Databases
**Examples:** PostgreSQL, MySQL, MariaDB, SQL Server, Oracle

| Aspect | Details |
|--------|---------|
| **Model** | Tables, rows, columns; foreign keys; ACID transactions |
| **Best for** | Structured data with clear relationships (users, orders, products); strong consistency; complex queries, joins, reporting; known/stable schema |
| **Avoid when** | Very high write throughput, schema-less or deeply nested data, graph-like relationships |

---

### 2. Document Databases (NoSQL)
**Examples:** MongoDB, CouchDB, DynamoDB (document mode)

| Aspect | Details |
|--------|---------|
| **Model** | Documents (e.g. JSON); collections; flexible schema |
| **Best for** | Flexible/evolving schemas; hierarchical data; fast reads/writes by ID; horizontal scaling; rapid prototyping |
| **Avoid when** | Heavy cross-document joins, strict relational integrity, complex multi-table transactions |

---

### 3. Key–Value Stores
**Examples:** Redis, Memcached, DynamoDB, Riak

| Aspect | Details |
|--------|---------|
| **Model** | Key → value (strings, hashes, lists, etc.) |
| **Best for** | Caching (sessions, API responses); simple lookups; low latency; counters, rate limiting, leaderboards; pub/sub (e.g. Redis) |
| **Avoid when** | Complex queries, relationships, or full-text search as primary store |

---

### 4. Wide-Column / Column-Family
**Examples:** Cassandra, HBase, ScyllaDB

| Aspect | Details |
|--------|---------|
| **Model** | Row key + column families; sparse columns |
| **Best for** | High write throughput; large-scale data; time-series, events, logs; multi-region, high availability; access by row key / key range |
| **Avoid when** | Ad-hoc joins, complex analytics, strong cross-row transactions |

---

### 5. Graph Databases
**Examples:** Neo4j, Amazon Neptune, ArangoDB

| Aspect | Details |
|--------|---------|
| **Model** | Nodes (entities) and edges (relationships); properties on both |
| **Best for** | Relationship-heavy data: social networks, recommendations, fraud detection; “friends of friends”, paths, influence |
| **Avoid when** | Simple CRUD or bulk analytics; flat data |

---

### 6. Search Engines
**Examples:** Elasticsearch, OpenSearch, Solr

| Aspect | Details |
|--------|---------|
| **Model** | Documents; inverted index; full-text and structured fields |
| **Best for** | Full-text search, autocomplete, fuzzy search; log aggregation, APM; faceted search, relevance tuning |
| **Avoid when** | Primary source of truth for transactional data (use alongside a transactional DB) |

---

### 7. Time-Series Databases
**Examples:** InfluxDB, TimescaleDB, Prometheus

| Aspect | Details |
|--------|---------|
| **Model** | Timestamp + tags + values; optimized for time-ordered data |
| **Best for** | Metrics, IoT, monitoring, sensor data; downsampling, retention, time-based aggregations |
| **Avoid when** | General-purpose CRUD or highly relational models |

---

### 8. In-Memory Databases
**Examples:** Redis (with persistence), Memcached, VoltDB

| Aspect | Details |
|--------|---------|
| **Model** | Varies; data in RAM |
| **Best for** | Ultra-low latency; caching; session store; real-time analytics on hot data |
| **Avoid when** | Primary durable store for large datasets (unless persistence/replication is explicit) |

---

### Quick Reference: Scenario → Database Type

| Scenario | Good fit |
|----------|----------|
| CRUD app, transactions, reporting | **Relational** (PostgreSQL, MySQL) |
| Flexible schema, document-style data | **Document** (MongoDB) |
| Caching, sessions, simple key lookups | **Key–value** (Redis) |
| Huge writes, time-series, logs | **Wide-column / time-series** |
| Social graph, recommendations | **Graph** (Neo4j) |
| Full-text search, log search | **Search** (Elasticsearch) |
| Metrics, IoT, monitoring | **Time-series** |

---

## Part 2: CAP Theorem & Database Choices

### What is CAP?

In a **partition** (network split), a distributed system can guarantee at most **two** of:

- **C – Consistency:** Every read sees the latest write (linearizable).
- **A – Availability:** Every non-failing node can serve reads/writes.
- **P – Partition tolerance:** System keeps working when the network splits.

In practice **P** is assumed (partitions happen), so the real choice is **CP** vs **AP** during a partition.

---

### CAP Classification of Common Databases

| Database | Typical choice | During partition |
|----------|----------------|------------------|
| **MongoDB** | CP (configurable) | Sacrifices availability on minority; can tune for AP |
| **Redis (cluster)** | CP | Unavailable on minority to keep consistency |
| **PostgreSQL** | CA (single) / CP (replicated) | With sync replication: CP |
| **MySQL** | CA / CP | Same idea as PostgreSQL |
| **Cassandra** | AP | Keeps serving; eventual consistency |
| **DynamoDB** | AP (default) | Available; eventual consistency; optional strong consistency |
| **CouchDB** | AP | Multi-master; always writable; eventual consistency |
| **Riak** | AP | Tunable; typically AP |
| **HBase** | CP | Sacrifices availability on minority |
| **Neo4j (cluster)** | CP | Consistency over availability |
| **Elasticsearch** | CP (often) | Can refuse writes without quorum |
| **etcd / Consul / ZooKeeper** | CP | Strong consistency; coordination/config |

---

### When to Choose CP vs AP

#### Prefer **CP** (Consistency + Partition tolerance)
- Financial systems, payments, inventory, reservations
- Config, service discovery, distributed locks
- When correctness matters more than always being writable

**Examples:** PostgreSQL (sync replication), MongoDB (default), Redis Cluster, HBase, etcd, Consul

#### Prefer **AP** (Availability + Partition tolerance)
- Social feeds, likes, views; shopping cart; session/cache
- IoT, event ingestion; global multi-region apps
- When “always on” matters more than immediate consistency

**Examples:** Cassandra, DynamoDB, CouchDB, Riak

---

### Quick Reference: Scenario → CAP Choice

| Scenario | CAP preference | Example DBs |
|----------|----------------|-------------|
| Payments, banking, inventory | **CP** | PostgreSQL, MongoDB, Redis Cluster |
| Session store, cache | **AP** or single-node | Redis, DynamoDB |
| Social feed, counters | **AP** | Cassandra, DynamoDB |
| Multi-region, always-on | **AP** | Cassandra, DynamoDB, CouchDB |
| Config, service discovery, locks | **CP** | etcd, Consul, ZooKeeper |
| Search index | Often **CP** | Elasticsearch |
| Time-series / metrics | Often **AP** | Cassandra, InfluxDB |

---

## One-Liner Summary

- **Database type:** Pick by data shape and access pattern (relational vs document vs key-value vs graph vs search vs time-series).
- **CAP:** Pick **CP** when correctness is critical; pick **AP** when availability and global reach are critical. Many systems use multiple DBs (e.g. relational + cache + search).
