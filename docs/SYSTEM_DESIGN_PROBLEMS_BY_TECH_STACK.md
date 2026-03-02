# System Design Problems by Tech Stack

**Purpose:** Map **types of system design problems** to the right architectural patterns and technologies (Kafka, Flink/Spark, async workers, etc.). Use this for interview prep and to choose stacks when designing systems.

---

## Quick Reference Matrix

| Problem type | Primary tech | Why |
|-------------|--------------|-----|
| **Event streaming / audit / fan-out** | Kafka (or similar) | Durable log, multiple consumers, replay, ordering per key |
| **Real-time stream processing** (aggregations, joins, windows) | Apache Flink, Kafka Streams | Low-latency stateful stream processing, exactly-once |
| **Batch / historical analytics** | Apache Spark (batch) | Large-scale batch jobs, ETL, ML pipelines |
| **MapReduce-style batch** (partition by key, aggregate) | Hadoop MapReduce, or Spark | Split data by key → process in parallel → reduce; HDFS/S3-native |
| **Async job queue** (tasks, retries, backoff) | Redis/Celery, SQS + workers, K8s Jobs | Decouple request from work; retries, rate limits, fan-out |
| **Request–response API** | Stateless services + DB/cache | Sync user-facing APIs |
| **Strong consistency / transactions** | DB (PostgreSQL, etc.) or distributed TX | Ledger, balances, inventory |

---

## 1. When to Use Kafka (or Similar Message/Event Bus)

**Use when:** You need **event streaming**, **audit log**, **fan-out to many consumers**, **replay**, or **ordered per key**.

### Problem characteristics
- **Multiple consumers** of the same events (persistence, analytics, notifications, search index).
- **Ordering matters** per entity (e.g. per order ID or per symbol).
- **Replay** for recovery, backfill, or new consumers.
- **Decoupling** producers from consumers (gateway → matching engine → many downstreams).
- **Durable log** so nothing is lost if a consumer is down.

### Example system design problems
- **Order/trade event bus** (exchange): orders, trades, book updates → persistence, market data, risk, analytics.
- **Activity feed / event sourcing**: user actions → feed builder, notifications, search.
- **Change Data Capture (CDC)**: DB changes → search index, cache invalidation, data lake.
- **Log aggregation**: app logs → central store, alerting, dashboards.
- **Notification pipeline**: events → email, push, SMS, in-app (each as a consumer).

### Typical stack
- **Kafka** (or Pulsar, Kinesis): topics, partitions (ordering per key), consumer groups, retention.
- Producers: services that emit events. Consumers: Flink/Spark, workers, other services.

---

## 2. When to Use Apache Flink (or Kafka Streams)

**Use when:** You need **stateful, low-latency stream processing**: windows, aggregations, joins, exactly-once.

### Problem characteristics
- **Continuous stream** of events (not “run job every hour”).
- **Time-based or count-based windows** (e.g. “volume per minute”, “top N in last 5 min”).
- **Joins** between streams or stream + table.
- **State** (e.g. running totals, session state) with **exactly-once** or **at-least-once** semantics.
- **Low latency** (seconds or sub-second), not batch delay.

### Example system design problems
- **Real-time analytics dashboard**: clicks/impressions → aggregates per dimension, stored in DB or served via API.
- **Fraud detection**: transactions → rules and ML over sliding windows.
- **Order book / market data**: raw ticks → OHLCV, VWAP, order book snapshots.
- **Sessionization**: events → sessions (timeout-based), then session-level metrics.
- **Alerts**: metrics stream → threshold checks, anomaly detection, alert emission.

### Typical stack
- **Apache Flink** or **Kafka Streams** reading from Kafka.
- State stored in RocksDB (Flink) or Kafka (Kafka Streams); checkpointing for recovery.
- Output: Kafka topic, DB, cache, or API.

### Flink vs Spark Streaming
- **Flink / Kafka Streams**: true streaming, event-time, low latency, stateful.
- **Spark Streaming (micro-batch)**: small batches (e.g. every few seconds); use when you already have Spark for batch and want one stack.

---

## 3. When to Use Apache Spark (Batch)

**Use when:** You need **large-scale batch processing**: ETL, historical analytics, ML training, one-off or scheduled jobs.

### Problem characteristics
- **Large datasets** (TB+) that don’t fit on one machine.
- **Scheduled or on-demand** (e.g. daily/hourly), not continuous streaming.
- **Complex transformations**: joins, aggregations, UDFs, ML.
- **Fault tolerance** via RDD/DataFrame lineage and recomputation.
- **Throughput over latency** (minutes/hours is OK).

### Example system design problems
- **Data lake ETL**: raw logs/events → cleaned, partitioned, aggregated tables.
- **Historical reporting**: “revenue by region last year” over huge fact tables.
- **ML training pipelines**: feature computation + model training on large datasets.
- **Backfill**: “reprocess last 30 days of events with new logic.”
- **Recommendation / batch scoring**: nightly job over user-item matrix.

### Typical stack
- **Spark** (Spark SQL, DataFrame API) on YARN, K8s, or standalone.
- Data in HDFS, S3, or Kafka (batch read).
- Output: DB, data lake, or another topic.

---

## 4. When to Use MapReduce (Hadoop or Similar)

**Use when:** You need **batch processing** that fits the **map–shuffle–reduce** pattern: partition data by key, process in parallel, then aggregate. Data lives on **distributed storage** (HDFS, S3); latency is not critical.

### Problem characteristics
- **Embarrassingly parallel** per record or per key: each map task is independent.
- **Natural “reduce” by key**: all values for the same key must be brought together (shuffle) then combined (count, sum, max, etc.).
- **Input/output on distributed storage** (files, object store); job reads and writes in bulk.
- **Fault tolerance** by re-running failed map/reduce tasks; no need for sub-second latency.
- **Very large datasets** (TB/PB) where “partition by key and aggregate” is the core operation.

### Example system design problems
- **Word count** (classic): map each document to (word, 1), reduce by word → (word, total count).
- **Inverted index**: map documents to (term, docId), reduce by term → list of docIds (search index).
- **Log processing / aggregation**: map raw logs to (userId, event) or (hour, metric), reduce to counts/sums per key.
- **Sort**: map to (key, value), reduce with identity; shuffle does the sort (e.g. TeraSort).
- **Distributed grep**: map filters lines, reduce (or no reduce) concatenates; often map-only.
- **Join (sort-merge)**: map both sides to (joinKey, record), reduce by key to perform join.
- **PageRank / graph batch**: multiple MapReduce rounds (map: emit edges/scores, reduce: aggregate per node).

### Typical stack
- **Hadoop MapReduce** on **YARN** (or older Hadoop 1 style), reading/writing **HDFS** or **S3**.
- **Spark** can implement the same pattern (RDD `map` / `reduceByKey`) and often replaces MapReduce for performance and richer APIs; the *pattern* is still MapReduce-style.
- **Hive** / **Pig**: SQL or high-level DSL compiled to MapReduce (or Tez/Spark) for ad-hoc batch queries.

### MapReduce vs Spark
- **MapReduce**: two-phase (map then reduce), disk-heavy shuffle, good for simple “partition by key + aggregate” batch jobs; ecosystem (HDFS, YARN, Hive) is mature.
- **Spark**: in-memory RDD/DataFrame, DAG execution, often 10–100× faster for multi-pass jobs; preferred for most new batch workloads. Use MapReduce when you’re in a Hadoop-only environment or the problem is literally “one map + one reduce” and you want minimal dependencies.

---

## 5. When to Use Async Job Processing (Queue + Workers / Cluster)

**Use when:** You need **task queues**: retries, backoff, rate limiting, decoupling HTTP request from heavy work.

### Problem characteristics
- **Request triggers work** but response doesn’t need to wait (e.g. “export started”, “we’ll email when done”).
- **Retries and backoff** (transient failures, rate limits).
- **Rate limiting** or **throttling** (e.g. don’t hammer external API).
- **Prioritization** (e.g. premium users first) or **fairness** (per-user limits).
- **Scalable workers** (horizontal scaling of job executors).

### Example system design problems
- **Document/Report generation**: user clicks “Export PDF” → job in queue → worker generates file → store + notify.
- **Email / push sending**: “send welcome email” → queue → worker calls mail provider with retries.
- **Image/video processing**: upload → queue → resize/transcode → store URL.
- **Sync with external systems**: “sync user to CRM” → queue → worker calls API, retries on 429/5xx.
- **Scheduled jobs**: cron enqueues “daily report” → workers run at scale.

### Typical stack
- **Queue**: Redis (Bull/Celery), RabbitMQ, **Amazon SQS**, or Kafka (with consumer group as “workers”).
- **Workers**: long-running processes (Celery workers, K8s Deployments) or **serverless** (Lambda, Cloud Run) triggered by queue.
- **Cluster/scheduler** (optional): **Kubernetes Jobs**, **Nomad**, or **Celery** with a broker; for batch-style “run this job once” at scale.

### When to add a “cluster manager”
- **Celery + Redis/RabbitMQ**: classic “task queue + workers”; no separate cluster manager.
- **Kubernetes Jobs / CronJobs**: when jobs are batch-style (run to completion), need resource limits, or you’re already on K8s.
- **Nomad / YARN**: when you need a dedicated scheduler for mixed workloads (short tasks + long batch).

---

## 6. When to Use “Just” a Database (Sync, Strong Consistency)

**Use when:** The core requirement is **immediate, consistent reads/writes** (ledger, inventory, user-facing API).

### Problem characteristics
- **User waits for the result** (e.g. “place order”, “get balance”).
- **Strong consistency** (e.g. no double-spend, inventory never negative).
- **Transactions** across multiple entities (order + balance + inventory).

### Example system design problems
- **Order placement and matching**: accept order, update book, update balances in a consistent way.
- **Payment ledger**: debit A, credit B in one transaction.
- **Inventory**: reserve item, then confirm or release.

### Typical stack
- **PostgreSQL** (or similar) with transactions; optionally **caching** (Redis) for read scaling with invalidation strategy.

---

## 7. Combined Patterns (Typical in One System)

Many systems use **several** of the above together:

| System | Kafka | Flink/Spark | Async workers | DB (strong consistency) |
|--------|--------|-------------|----------------|--------------------------|
| **Exchange** | Orders, trades, book events | Optional: real-time analytics | Optional: notifications | Order/trade store, ledger |
| **Social feed** | Activity events | Feed aggregation (Flink) or batch (Spark) | Push/email workers | User, post, graph DB |
| **E-commerce** | Order/cart events | Analytics, recommendations (Spark) | Order fulfillment, emails | Orders, inventory, payments |
| **Data platform** | Ingestion from apps/DBs | ETL + streaming (Spark + Flink), MapReduce-style batch | Orchestration (Airflow + workers) | Metadata, serving layer |

---

## 8. Decision Flow (Simplified)

```
Is the user waiting for the result?
├─ YES → Sync path: API + DB (and maybe cache). Strong consistency.
└─ NO  → Is it continuous stream with windows/joins/state?
         ├─ YES → Stream processing: Kafka + Flink (or Kafka Streams).
         └─ NO  → Is it one-off or scheduled heavy job (TB, ETL, ML)?
                  ├─ YES → Batch: Spark or MapReduce (Hadoop); data on HDFS/S3, partition-by-key + aggregate.
                  └─ NO  → Is it “do this task later with retries”?
                           └─ YES → Async jobs: Queue (SQS/Redis/RabbitMQ) + workers.
```

---

## 9. One-Line Summary by Problem Type

| You need… | Use… |
|-----------|------|
| Durable event log, many consumers, replay | **Kafka** (or similar) |
| Real-time windows, joins, state on streams | **Flink** or **Kafka Streams** |
| Big batch ETL, historical analytics, ML | **Spark** (batch) |
| Partition-by-key batch, aggregate over huge files (HDFS/S3) | **MapReduce** (Hadoop) or **Spark** |
| “Do this later” with retries and workers | **Queue + workers** (Celery, SQS, etc.) |
| Run batch jobs at scale with a scheduler | **K8s Jobs**, **Nomad**, or **Airflow + workers** |
| User-facing consistent read/write | **DB + optional cache** |

---

*See also: [COINBASE_DOMAIN_SYSTEMS_HLD.md](lld/COINBASE_DOMAIN_SYSTEMS_HLD.md) (Kafka in exchange design), [ORDER_EVENT_PROCESSING_DESIGN.md](lld/order-book/ORDER_EVENT_PROCESSING_DESIGN.md) (event flow).*
