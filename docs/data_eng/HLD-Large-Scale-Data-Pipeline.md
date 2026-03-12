# HLD: Large-Scale Data Processing Pipeline
**Author:** Staff Engineer Perspective  
**Scope:** Multi-source ingestion → real-time + batch processing → multi-sink delivery  
**Target Scale:** 1M+ events/sec ingestion, petabyte-scale storage, sub-second analytics latency  

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Requirements](#2-requirements)
3. [Capacity Estimation](#3-capacity-estimation)
4. [Architecture Overview](#4-architecture-overview)
5. [Component Deep-Dives](#5-component-deep-dives)
6. [Data Flow Walkthrough](#6-data-flow-walkthrough)
7. [Reliability & Fault Tolerance](#7-reliability--fault-tolerance)
8. [Scalability Design](#8-scalability-design)
9. [Observability & Alerting](#9-observability--alerting)
10. [Security & Compliance](#10-security--compliance)
11. [Trade-offs & Alternatives Considered](#11-trade-offs--alternatives-considered)
12. [Phased Rollout Plan](#12-phased-rollout-plan)
13. [Open Questions](#13-open-questions)

---

## 1. Problem Statement

**Context:** A financial platform needs to ingest, process, and serve data from multiple heterogeneous sources (transaction systems, market feeds, user events, third-party enrichment APIs) to power:
- Real-time fraud detection and risk scoring
- Near-real-time analytics dashboards
- Batch settlement, reconciliation, and regulatory reporting
- Machine learning feature stores

**Core Pain Points (Driving This Design):**
- Tightly coupled ingestion and processing → one failure cascades
- No schema evolution strategy → schema changes break downstream consumers
- No replay capability → data loss during outages is unrecoverable
- Mixed SLA requirements (sub-second alerting vs. end-of-day batch) served by one pipeline
- No lineage tracking → impossible to audit data transformations for compliance

---

## 2. Requirements

### 2.1 Functional Requirements

| Category | Requirement |
|---|---|
| **Ingestion** | Support structured (DB CDC), semi-structured (JSON events), and binary (Avro/Protobuf) sources |
| **Processing** | Real-time enrichment, aggregation, deduplication, and ML feature computation |
| **Storage** | Raw (immutable), processed (queryable), and aggregated (serving) tiers |
| **Delivery** | Push to OLAP engines, data warehouse, feature store, and downstream microservices |
| **Replay** | Reprocess any time window without data loss |
| **Schema** | Centralized schema registry with backward/forward compatibility enforcement |

### 2.2 Non-Functional Requirements

| Requirement | Target |
|---|---|
| **Ingestion Throughput** | 1M events/sec sustained, 3M events/sec burst |
| **End-to-End Latency (hot path)** | P99 < 500ms from event creation to serving |
| **End-to-End Latency (warm path)** | P99 < 5 minutes |
| **End-to-End Latency (cold/batch)** | < 4 hours for full daily batch |
| **Availability** | 99.99% for ingestion layer, 99.9% for processing |
| **Durability** | Zero data loss (at-least-once delivery, idempotent sinks) |
| **Data Retention** | Raw: 7 years (regulatory); Processed: 3 years; Aggregates: indefinite |
| **Exactly-once semantics** | For financial settlement and reconciliation pipelines |

---

## 3. Capacity Estimation

```
Ingestion Rate:
  - 1M events/sec average
  - Average event size: 2KB
  - Raw throughput: 2 GB/sec = ~172 TB/day

Storage (Raw Tier - 7 years):
  - 172 TB/day × 365 × 7 = ~439 PB
  - With 3x replication + Parquet compression (~5:1): ~87 PB effective
  - Object storage cost (S3/GCS @ $0.023/GB): ~$2M/month → use tiered archival

Kafka Cluster:
  - 2 GB/sec write = 120 GB/min
  - 7-day retention for replay: 120 GB/min × 10,080 min = ~1.2 PB
  - With 3x replication: ~3.6 PB
  - Requires ~180 brokers at 20 GB/sec throughput per broker

Stream Processing (Flink):
  - 1M events/sec × 2KB = 2 GB/sec
  - Target parallelism: 500 task managers, 4 cores each = 2000 slots

Serving Layer:
  - OLAP: Druid/ClickHouse — 5TB hot working set in SSD
  - Feature Store: Redis cluster — 500GB in-memory, ~5B features at 100 bytes each
```

---

## 4. Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              DATA SOURCES                                           │
│  [Transactional DBs]  [Market Feeds]  [User Events]  [3rd-Party APIs]  [CDC Streams]│
└──────────┬──────────────────┬──────────────┬────────────────┬──────────────────────┘
           │                  │              │                │
           ▼                  ▼              ▼                ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                           INGESTION LAYER                                           │
│                                                                                     │
│   ┌─────────────┐   ┌──────────────┐   ┌──────────────┐   ┌─────────────────────┐ │
│   │  Debezium   │   │  Kafka       │   │  Fluentd /   │   │  Airbyte / Singer   │ │
│   │  (CDC)      │   │  Connect     │   │  Filebeat    │   │  (Batch Connectors) │ │
│   └──────┬──────┘   └──────┬───────┘   └──────┬───────┘   └──────────┬──────────┘ │
│          └─────────────────┴──────────────────┴───────────────────────┘            │
│                                        │                                            │
│                              ┌─────────▼─────────┐                                 │
│                              │   Schema Registry  │◄── Avro/Protobuf/JSON Schema    │
│                              │   (Confluent SR)   │    Compatibility enforcement    │
│                              └─────────┬──────────┘                                │
└────────────────────────────────────────┼────────────────────────────────────────────┘
                                         │
                                         ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                           MESSAGE BUS (Apache Kafka)                                │
│                                                                                     │
│  ┌──────────────┐  ┌────────────────┐  ┌─────────────────┐  ┌──────────────────┐  │
│  │ raw.txn      │  │ raw.market     │  │ raw.user-events  │  │ raw.enrichment   │  │
│  │ (500 parts)  │  │ (200 parts)    │  │ (300 parts)      │  │ (100 parts)      │  │
│  └──────────────┘  └────────────────┘  └─────────────────┘  └──────────────────┘  │
│                                                                                     │
│  [DLQ Topics per source]  [Compacted Topics for state]  [Tiered Storage: S3]       │
└────────────────────────────────────────┬────────────────────────────────────────────┘
                                         │
              ┌──────────────────────────┼──────────────────────────┐
              │                          │                           │
              ▼                          ▼                           ▼
┌─────────────────────┐  ┌──────────────────────────┐  ┌─────────────────────────┐
│   HOT PATH          │  │   WARM PATH               │  │   COLD PATH (Batch)     │
│   (Apache Flink)    │  │   (Flink / Spark          │  │   (Apache Spark)        │
│                     │  │    Structured Streaming)  │  │                         │
│  • Fraud scoring    │  │  • Sessionization         │  │  • Full recomputation   │
│  • Risk alerts      │  │  • Feature aggregation    │  │  • Regulatory reports   │
│  • CEP patterns     │  │  • Windowed analytics     │  │  • ML training sets     │
│  • Deduplication    │  │  • SLA breach detection   │  │  • Reconciliation       │
│  P99 < 500ms        │  │  P99 < 5 min              │  │  < 4 hour window        │
└──────────┬──────────┘  └─────────────┬────────────┘  └───────────┬─────────────┘
           │                           │                             │
           └──────────────┬────────────┘                            │
                          │                                          │
                          ▼                                          ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              STORAGE LAYER                                          │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                    LAKEHOUSE (Apache Iceberg on S3/GCS)                     │   │
│  │                                                                             │   │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────────────────┐ │   │
│  │  │  Bronze Layer   │  │  Silver Layer   │  │  Gold Layer                 │ │   │
│  │  │  (Raw / append) │  │  (Deduplicated  │  │  (Aggregated / Business     │ │   │
│  │  │                 │  │   Validated     │  │   domain tables)            │ │   │
│  │  │  Iceberg tables │  │   Enriched)     │  │                             │ │   │
│  │  │  Partitioned by │  │                 │  │  Star/Snowflake schema      │ │   │
│  │  │  event_date,    │  │  Iceberg MERGE  │  │  Ready for BI tools         │ │   │
│  │  │  source_type    │  │  INTO for upsert│  │                             │ │   │
│  │  └─────────────────┘  └─────────────────┘  └─────────────────────────────┘ │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  ┌──────────────┐  ┌───────────────┐  ┌──────────────────┐  ┌──────────────────┐  │
│  │  Redis       │  │  ClickHouse   │  │  BigQuery /      │  │  Elasticsearch   │  │
│  │  (Feature    │  │  (Real-time   │  │  Snowflake       │  │  (Full-text &    │  │
│  │   Store)     │  │   OLAP)       │  │  (DW / Reporting)│  │   Log Analytics) │  │
│  └──────────────┘  └───────────────┘  └──────────────────┘  └──────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                         │
                                         ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              SERVING LAYER                                          │
│                                                                                     │
│  [Analytics APIs]  [ML Inference Services]  [BI Tools]  [Operational Dashboards]   │
│  [Downstream Microservices via Kafka]        [Regulatory Reporting Systems]         │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Component Deep-Dives

### 5.1 Ingestion Layer

**Design Principle:** Decouple sources from processing. The ingestion layer's only job is to reliably move bytes from source into Kafka with correct schema tagging.

#### CDC (Debezium → Kafka)
```
PostgreSQL WAL → Debezium Connector → Kafka topic: raw.txn
```
- Debezium runs as a Kafka Connect cluster (separate from broker cluster)
- Each table has its own topic → prevents slow table from blocking fast ones
- Connector config: `snapshot.mode=initial` for cold start, `heartbeat.interval.ms=5000`
- **Key decision:** Partition by `entity_id` (e.g., `account_id`) for ordering guarantees within an entity

#### Event Streams (App → Kafka)
- SDKs (Java/Go/Python) publish with producer interceptor that:
  1. Validates against Schema Registry before publish
  2. Injects `event_id` (UUID v7 — time-sortable), `source_service`, `schema_version`, `ingestion_timestamp`
  3. Applies client-side rate limiting to prevent broker overload during traffic spikes

#### Schema Registry
- **Confluent Schema Registry** with AVRO (preferred) or Protobuf
- Compatibility mode: `BACKWARD_TRANSITIVE` — new consumers can read old data
- Schema ID embedded in every message (first 5 bytes)
- Schema promotion workflow: dev → staging → prod requires approval gate

---

### 5.2 Message Bus (Apache Kafka)

**Cluster Topology:**
```
3 Availability Zones × N brokers per AZ
                    ↕
           ZooKeeper → KRaft (migration path)
```

**Topic Design Conventions:**
```
{tier}.{domain}.{entity}[.{version}]
  raw.payments.transactions
  processed.payments.transactions.v2
  dlq.payments.transactions

Partitions  = max(desired_parallelism, throughput / 100MB/s per partition)
Replication = 3 (min.insync.replicas = 2)
Retention   = 7 days (+ Tiered Storage to S3 for 90 days)
```

**Exactly-Once Configuration (for financial pipelines):**
```properties
# Producer
enable.idempotence=true
acks=all
max.in.flight.requests.per.connection=5

# Transactional (for Flink checkpointing)
transactional.id=pipeline-{job_id}-{partition_id}
isolation.level=read_committed  # Consumer side
```

**Tiered Storage (Confluent/MSK):**  
Kafka brokers hold 7 days hot data on local NVMe SSD. Data older than 7 days offloaded to S3 transparently. Consumers can still seek to any offset — broker fetches from S3 if needed. This enables replay without scaling the broker fleet.

---

### 5.3 Hot Path — Apache Flink (Real-Time Processing)

**When to use:** Latency-sensitive operations — fraud detection, real-time risk scoring, session-level CEP patterns.

**Flink Job Architecture:**
```
Kafka Source (SourceFunction with watermarks)
     │
     ├─► [Deserialization + Schema Validation]
     │
     ├─► [Deduplication - KeyedProcessFunction]
     │         └── RocksDB state: event_id → expiry TTL 24h
     │
     ├─► [Enrichment - AsyncIO]
     │         └── Async calls to Redis (profile, limits)
     │         └── Timeout: 50ms, fallback: stale cache
     │
     ├─► [Business Logic / CEP Pattern Matching]
     │         └── e.g., 5 failed logins in 60s → fraud alert
     │
     └─► [Multi-Sink Writer]
               ├── Kafka (processed.payments.transactions)
               ├── Redis (feature updates)
               └── Alerts topic → Notification Service
```

**State Management:**
- `RocksDB` incremental checkpoints to S3 every 30 seconds
- Checkpoint timeout: 60 seconds; if exceeded → fail job, alert on-call
- `savepoint` before any deployment → zero state loss on upgrade
- State TTL configured per operator — dedup state: 24h, session state: 30min

**Watermark Strategy:**
```java
// Bounded out-of-orderness: tolerate 5-second late arrivals
WatermarkStrategy
  .forBoundedOutOfOrderness(Duration.ofSeconds(5))
  .withTimestampAssigner((event, ts) -> event.getEventTimestamp())
  .withIdleness(Duration.ofMinutes(1)); // handle idle partitions
```

**Backpressure Handling:**
- Flink's native backpressure propagates upstream to Kafka consumer → auto-throttles
- Monitoring: Flink UI busy time > 80% for > 2 min → scale up task managers
- Circuit breaker on external enrichment calls → degrade gracefully (skip enrichment, flag event)

---

### 5.4 Warm Path — Structured Streaming / Flink (Micro-Batch)

**When to use:** Windowed aggregations (5-min, 1-hour), sessionization, feature computation for ML, SLA monitoring.

**Trigger:** Micro-batch every 30 seconds OR event-time tumbling/sliding windows.

**Example: 5-Minute Tumbling Window Aggregation**
```
Input:  raw transaction events (partitioned by account_id)
Output: per-account 5-min aggregates → Feature Store

Window:   5-minute tumbling, event-time
Late data: allowed up to 10 minutes (update result), discarded after
Output:   ClickHouse (real-time dashboards) + Redis (feature store)
```

**Checkpointing:** Spark Structured Streaming with `exactly_once` sink using checkpoint location on GCS/S3.

---

### 5.5 Cold Path — Apache Spark (Batch)

**When to use:** Full historical recomputation, regulatory reports, ML training data generation, reconciliation.

**Execution Platform:** Spark on Kubernetes (Spark on K8s) or EMR/Dataproc.

**Data Lakehouse (Apache Iceberg):**

```
Why Iceberg over Delta/Hudi:
  ✓ Vendor-neutral (works on S3, GCS, ADLS)
  ✓ Hidden partitioning — query doesn't need to know physical layout
  ✓ Time travel — SELECT ... AS OF TIMESTAMP '2024-01-01'
  ✓ Schema evolution without rewrites
  ✓ Row-level deletes (GDPR compliance)
  ✓ Concurrent writes from Spark + Flink simultaneously
```

**Bronze → Silver → Gold Pipeline:**
```
Bronze:
  - Append-only, raw bytes, schema-on-read
  - Partitioned: event_date (hidden partition on event_timestamp)
  - Compaction job: runs hourly, merges small files → target 256MB Parquet files

Silver:
  - Deduplicated (event_id), validated, enriched
  - MERGE INTO for CDC upserts (Iceberg v2 row-level deletes)
  - Schema-on-write, strict column types

Gold:
  - Business-domain aggregates ready for BI/reporting
  - Partitioned by reporting_date, product_type
  - Statistics collected for query optimization
```

**Job Scheduling:** Apache Airflow (or Prefect) DAGs with:
- SLA-based alerting on DAG tasks
- Dependency management between Bronze → Silver → Gold
- Idempotent task design: every job parameterized by `processing_date`, re-runnable safely

---

### 5.6 Serving Layer

| Store | Use Case | Technology | SLA |
|---|---|---|---|
| Feature Store | ML inference, real-time scoring | Redis Cluster (+ offline: Iceberg) | P99 < 5ms read |
| Real-time OLAP | Operational dashboards, last-24h queries | ClickHouse (sharded, replicated) | P99 < 100ms |
| Data Warehouse | Historical analytics, ad-hoc, BI tools | BigQuery / Snowflake | Minutes acceptable |
| Log/Event Search | Debugging, audit trail | Elasticsearch | P99 < 500ms |
| Downstream Services | Event-driven microservices | Kafka (processed topics) | At-least-once |

**Feature Store Design (Redis):**
```
Key structure: feature:{entity_type}:{entity_id}:{feature_name}
TTL:           Short-lived features (1h), long-lived profiles (7d)
Write path:    Flink warm-path → Redis pipeline writes (batched)
Read path:     ML inference service → Redis GET (point lookup)
Consistency:   Eventual; stale reads acceptable for non-financial features
```

---

### 5.7 Data Catalog & Lineage

Every dataset registered in a **Data Catalog** (Apache Atlas or DataHub):
- Schema, owner, SLA, classification (PII/sensitive)
- Upstream/downstream lineage graph — auto-populated by Spark/Flink integrations
- Impact analysis: before changing a schema, system shows all downstream consumers

**Column-Level Lineage** for regulatory reporting:
```
source_column: raw.payments.transactions.amount
  → silver.payments.transactions.amount_usd (after FX enrichment)
    → gold.reporting.daily_settlements.total_settled_usd
      → Regulatory Report: Basel III RWA Calculation
```

---

## 6. Data Flow Walkthrough

**Scenario: Payment Transaction — End-to-End**

```
T+0ms     Payment service publishes event to Kafka topic raw.payments.transactions
          Event: { event_id: "uuid-v7", account_id: "A123", amount: 5000, currency: "USD",
                   merchant: "M456", timestamp: "2024-01-15T10:00:00Z" }

T+10ms    Flink hot-path consumes event
          → Deduplication check (RocksDB): not seen → pass
          → Async Redis enrichment: fetch account risk profile (timeout 50ms)
          → CEP evaluation: amount > threshold AND merchant in watchlist? → fraud alert

T+30ms    If fraud alert: publish to alerts.fraud topic → Notification Service
          If clean: publish to processed.payments.transactions

T+50ms    Redis feature store updated: account_id A123 last_txn_amount, velocity_1h

T+30sec   Flink warm-path micro-batch: compute 5-min rolling aggregates
          → account A123: txn_count_5m=3, txn_volume_5m=12500
          → Write to ClickHouse (operational dashboard visible within 1 min)

T+1hr     Spark Silver job: deduplicate raw.payments → silver.payments.transactions
          → MERGE INTO Iceberg table (upsert by event_id)

T+4hr     Spark Gold job: daily settlement aggregation
          → Join with FX rates, compute settled amounts
          → Write to gold.reporting.daily_settlements
          → Trigger regulatory report generation
```

---

## 7. Reliability & Fault Tolerance

### 7.1 At-Least-Once vs. Exactly-Once

| Pipeline | Delivery Guarantee | Mechanism |
|---|---|---|
| Fraud alerts | At-least-once | Acceptable to re-evaluate; idempotent alert dedup downstream |
| Settlement | **Exactly-once** | Kafka transactions + Flink two-phase commit + idempotent DB writes |
| Feature updates | At-least-once | Last-write-wins on Redis; minor staleness acceptable |
| Regulatory reports | Exactly-once | Idempotent Spark jobs parameterized by date, output to append-only table |

### 7.2 Dead Letter Queue (DLQ) Strategy

```
Every topic has a corresponding DLQ:
  raw.payments.transactions → dlq.payments.transactions

DLQ triggers:
  1. Schema validation failure
  2. Deserialization error
  3. Processing exception after N retries (N=3, exponential backoff)

DLQ processing:
  - Automated: DLQ monitor checks for schema fix or code deploy → auto-replay
  - Manual: Ops dashboard shows DLQ depth; engineer can inspect, fix, replay
  - SLA: DLQ events must be replayed within 4 hours (regulatory SLA)
```

### 7.3 Failure Scenarios

| Failure | Detection | Recovery |
|---|---|---|
| Kafka broker failure | Kafka controller reelection | Automatic partition leader reelection < 30s |
| Flink job failure | Heartbeat loss | Restart from last checkpoint < 60s; at-most 30s data reprocessed |
| Processing bug → corrupted data | Data quality checks on Silver | Time-travel to last good snapshot; re-run Spark job from Bronze |
| Enrichment service down | Circuit breaker tripped | Degrade gracefully: skip enrichment, flag event `enrichment_status=SKIPPED` |
| S3 / GCS outage | Health checks fail | Pre-warmed multi-region failover; checkpoint writes go to replica region |
| Schema incompatibility | Schema Registry rejects publish | Event goes to DLQ; producer gets clear error with schema diff |

### 7.4 Data Quality Framework

Integrated quality checks at each layer boundary:

```
Bronze → Silver checks:
  - Null rate on critical columns < 0.01%
  - Timestamp drift: event_timestamp within [now-7d, now+1h]
  - Dedup rate: flagged if > 0.1% duplicates (indicates upstream bug)
  - Volume anomaly: row count ±30% of 7-day moving average → alert

Silver → Gold checks:
  - Referential integrity: all account_ids exist in master account table
  - Financial invariant: sum(debit) = sum(credit) within settlement period
  - Completeness: no missing hours in time-series data
```

Tool: **Great Expectations** or **dbt tests** integrated into Airflow DAGs. Quality gate failures block downstream jobs and alert on-call.

---

## 8. Scalability Design

### 8.1 Horizontal Scaling

| Component | Scaling Axis | Trigger |
|---|---|---|
| Kafka | Add brokers + rebalance partitions | Broker disk > 70% or throughput > 80% capacity |
| Flink (hot path) | Add task managers | Job backpressure > 80% for 2 minutes (HPA on K8s) |
| Flink (warm path) | Increase parallelism | Checkpoint time > 80% of checkpoint interval |
| Spark | Dynamic resource allocation | Job queue depth; executor scaling on Dataproc/EMR |
| ClickHouse | Add shards | Query latency P99 > 200ms; disk usage > 60% |
| Redis | Add shards | Memory usage > 70%; latency P99 > 10ms |

### 8.2 Partitioning Strategy

**Partition key selection is critical** — wrong key causes hotspots:

```
✗ Bad:  partition by timestamp → all events at T go to same partition
✗ Bad:  partition by country → "US" partition gets 60% of traffic

✓ Good: partition by hash(account_id) for user-centric pipelines
        → Even distribution + ordering guaranteed per account

✓ Good: compound key hash(account_id + instrument_id) for trading systems
        → Co-locate related events for stateful joins without shuffle
```

**Skew handling:** Identify hot keys (e.g., large merchants generating 10K txn/sec).  
Solution: salt hot keys → `key + random_suffix[0..N]` → spread across N partitions → final merge aggregation step.

### 8.3 Backpressure & Flow Control

```
Source Rate > Processing Rate → Backpressure propagates upstream:

Flink → reduces Kafka consumer poll rate
  ↓
Kafka topic lag increases (monitored)
  ↓
If lag > threshold → auto-scale Flink task managers (K8s HPA)
  ↓
If lag continues to grow → auto-scale Kafka consumer group
  ↓
If source itself overloaded → producer-side rate limiting kicks in
```

---

## 9. Observability & Alerting

### 9.1 Three Pillars

**Metrics (Prometheus + Grafana):**
```
Pipeline Health Metrics:
  kafka_consumer_lag{topic, consumer_group}     # Primary SLA indicator
  flink_job_checkpoint_duration_ms              # Processing health
  flink_operator_backpressure_ratio             # Capacity warning
  data_quality_check_failure_rate               # Data correctness
  dlq_depth{topic}                              # Error accumulation
  pipeline_e2e_latency_p99_ms{pipeline_name}    # End-to-end SLA
```

**Distributed Tracing (OpenTelemetry → Jaeger/Tempo):**
- Trace ID injected at ingestion, propagated through Kafka headers
- Enables: "why did this specific event take 4 seconds?" root cause
- Sampled at 1% for hot path, 100% for DLQ events

**Structured Logging (ELK / Cloud Logging):**
- Every log line includes: `trace_id`, `event_id`, `pipeline_stage`, `processing_time_ms`
- Log-based metrics: error rate per pipeline stage

### 9.2 SLA-Driven Alerting

| Alert | Condition | Severity | Response |
|---|---|---|---|
| Ingestion lag spike | `kafka_lag > 100K AND growing` | P1 | Wake on-call; auto-scale |
| DLQ depth | `dlq_depth > 1000 for 5min` | P2 | On-call investigates within 30min |
| Checkpoint failure | `checkpoint_failed 2× in 10min` | P1 | Flink job restart; escalate |
| Data quality gate | `quality_check_failed on Silver` | P1 | Block Gold jobs; alert data team |
| Financial invariant | `sum(debit) ≠ sum(credit)` | P0 | Wake data + finance + on-call |
| Pipeline SLA miss | `e2e_latency_p99 > 1s for 5min` | P2 | Investigate bottleneck |

### 9.3 Runbooks

Each alert links to a runbook:
```
DLQ Spike Runbook:
  1. Check DLQ topic: consumer lag, message sample
  2. Identify failure category: schema? deserialization? business logic?
  3. If schema: check Schema Registry for recent changes
  4. If business logic: check recent Flink deployment
  5. Fix → re-deploy → trigger DLQ replay job
  6. Confirm DLQ depth returns to 0
  7. Post-mortem if > 10K events impacted
```

---

## 10. Security & Compliance

### 10.1 Encryption

| Layer | At-Rest | In-Transit |
|---|---|---|
| Kafka | Broker disk encryption (KMS-managed keys) | TLS 1.3 between producers/consumers/brokers |
| Object Storage | S3/GCS server-side encryption (SSE-KMS) | HTTPS only |
| Redis | Encryption at rest (Redis 7+) | TLS |
| ClickHouse | Disk encryption | TLS |

### 10.2 Access Control

```
Authentication: mTLS for service-to-service (Kafka clients, Flink jobs)
Authorization:  Kafka ACLs at topic level
                - Producer: write to raw.* topics
                - Flink jobs: read raw.*, write processed.*
                - BI tools: read-only on gold.* tables
                - Data scientists: read-only on silver.* (no PII columns)

Column-level security: BigQuery / Snowflake column masking policies
  - PII columns (SSN, card numbers): masked for all except compliance team
  - Policy tags propagated automatically via Data Catalog
```

### 10.3 Data Privacy (GDPR / CCPA)

```
Right to be forgotten:
  - User deletion request → triggers tombstone event in Kafka
  - Flink processes tombstone: marks entity as deleted in Redis
  - Iceberg: row-level delete via DELETE WHERE user_id = 'X'
    (Iceberg v2 supports row-level deletes without full rewrite)
  - Audit log: deletion confirmed with timestamp

PII Tokenization:
  - PII fields tokenized at ingestion (Vault or AWS KMS-backed tokenization)
  - Raw token stored; actual PII only accessible via token vault
  - Analytics pipelines see only tokens → PII never lands in data lake
```

### 10.4 Audit Trail

Every data transformation recorded in immutable audit log:
- What data was processed, when, by which job version
- Schema versions used
- Data quality check results
- Used for SOC 2 Type II and regulatory audit responses

---

## 11. Trade-offs & Alternatives Considered

### 11.1 Flink vs. Spark Streaming (Hot Path)

| Criterion | Apache Flink | Spark Structured Streaming |
|---|---|---|
| Latency | True streaming, sub-second | Micro-batch, ~1-5 second minimum |
| Exactly-once | Native, low overhead | Supported, higher overhead |
| State management | First-class, RocksDB | Limited (mapGroupsWithState) |
| Operational complexity | Higher | Lower (familiar Spark ops) |
| **Decision** | **Flink for hot path** | **Spark for warm/cold path** |

### 11.2 Iceberg vs. Delta Lake vs. Hudi

| Criterion | Iceberg | Delta Lake | Hudi |
|---|---|---|---|
| Vendor lock-in | None | Databricks ecosystem | None |
| Concurrent writes | Best-in-class | Good (optimistic) | Good |
| Query engine support | All major engines | Spark-centric | Good but complex |
| Hidden partitioning | Yes | No | No |
| **Decision** | **Iceberg** — vendor neutral, best concurrent write support for multi-engine (Flink + Spark) |

### 11.3 Redis vs. Aerospike (Feature Store)

- **Redis:** Simpler ops, great ecosystem, sufficient at 500GB
- **Aerospike:** Better for >1TB in-memory, hybrid SSD/RAM
- **Decision:** Redis at current scale; migration path to Aerospike defined when feature store exceeds 1TB

### 11.4 Kafka vs. Pulsar vs. Kinesis

- **Kafka:** Mature, large ecosystem, tiered storage available, team expertise
- **Pulsar:** Better multi-tenancy, geo-replication; ops complexity higher
- **Kinesis:** Managed but partition limits (1MB/s per shard), vendor lock-in
- **Decision:** Kafka (MSK or self-managed) — ecosystem maturity and tiered storage wins

---

## 12. Phased Rollout Plan

### Phase 1 — Foundation (Month 1-2)
- [ ] Kafka cluster setup with Schema Registry
- [ ] Debezium CDC for primary transactional DB
- [ ] Bronze layer Iceberg tables on S3
- [ ] Basic Airflow DAGs for Bronze ingestion
- [ ] Core observability: Kafka lag alerts, DLQ monitoring

### Phase 2 — Hot Path (Month 2-3)
- [ ] Flink cluster on Kubernetes
- [ ] Deduplication + enrichment Flink jobs
- [ ] Redis feature store (basic features)
- [ ] Processed topic outputs
- [ ] E2E latency dashboards

### Phase 3 — Lakehouse & Batch (Month 3-4)
- [ ] Silver layer with data quality gates
- [ ] Gold layer for reporting
- [ ] ClickHouse for real-time OLAP
- [ ] BigQuery/Snowflake DWH integration

### Phase 4 — Hardening (Month 4-6)
- [ ] Exactly-once guarantees for financial pipelines
- [ ] Full DLQ replay automation
- [ ] Column-level lineage in DataHub
- [ ] PII tokenization at ingestion
- [ ] Chaos engineering: inject broker/job failures in staging
- [ ] Load test to 3× peak capacity

---

## 13. Open Questions

1. **Multi-region:** Should the pipeline be active-active across regions or active-passive? MirrorMaker2 for Kafka replication has ~30s lag — acceptable for financial data?

2. **ML Feature Store:** Will ML inference be real-time (online store) or batch (offline store)? If both, consider dedicated feature store platform (Feast / Tecton) vs. Redis+Iceberg dual-write approach.

3. **Regulatory replay window:** 7-year retention for raw data — is this hot (queryable) or cold (archive only)? Hot retention at petabyte scale significantly increases cost.

4. **Schema governance:** Who approves schema changes in prod? Single Schema Registry owner team or distributed ownership with central standards?

5. **Cross-datacenter ordering:** If events from two DCs for the same account arrive out of order at the central Kafka cluster — what is the tie-breaking strategy?

---

## Appendix: Key Design Principles Applied

1. **Separate concerns by SLA** — hot/warm/cold paths have independent failure domains
2. **Immutable raw layer** — Bronze is append-only; any bug is fixable by re-running from source of truth
3. **Schema-first** — no event enters the pipeline without a registered, versioned schema
4. **Idempotency everywhere** — every write is safe to retry; exactly-once built on idempotent operations
5. **Degrade gracefully** — enrichment failures should not block core pipeline; flag and continue
6. **Design for replay** — Kafka tiered storage + Iceberg time travel means any pipeline stage is replayable
7. **Lineage as a first-class citizen** — no data transformation without a lineage entry; compliance depends on it
8. **Cost-aware tiering** — hot data on SSD/memory, warm on object storage, cold in archival tiers
