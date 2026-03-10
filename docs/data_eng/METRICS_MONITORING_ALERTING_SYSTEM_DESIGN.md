# Metrics Collection, Aggregation & Monitoring Alerts — System Design

---

## Table of Contents

- [Requirements](#requirements)
- [Capacity Estimation](#capacity-estimation)
- [High-Level Architecture](#high-level-architecture)
- [Push vs Pull Model](#push-vs-pull-model)
- [Component Deep Dive](#component-deep-dive)
- [Data Model](#data-model)
- [Alerting Pipeline](#alerting-pipeline)
- [Storage Strategy](#storage-strategy)
- [High Availability & Scalability](#high-availability--scalability)
- [Trade-offs & Decisions](#trade-offs--decisions)

---

## Requirements

### Functional Requirements
- **Collect** metrics from App Servers (Push model) and DB Servers (Pull model)
- **Aggregate** metrics at multiple granularities (1s, 1m, 5m, 1h, 1d)
- **Monitor** and fire alerts as fast as possible (real-time, <30s latency)
- **Status checks** on App Servers (liveness & readiness probes)
- Support **custom alert rules** (threshold, anomaly, rate-of-change)

### Non-Functional Requirements
- **Eventual Consistency** — metrics may arrive slightly out of order; slight delay is acceptable
- **Highly Available** — no single point of failure; alerts must always fire
- **Scalable** — handle millions of metrics per second across thousands of servers
- **Fault Tolerant** — metric loss should be minimal (at-most-once tolerated for non-critical)
- **Low Latency for Alerts** — alert evaluation within 10–30 seconds of anomaly

---

## Capacity Estimation

```
Servers      : 10,000 app servers + 2,000 DB servers
Metrics/server: ~100 metrics every 10 seconds
Total metrics : 12,000 servers × 100 = 1.2M metrics/10s = 120,000 metrics/sec

Each metric payload: ~200 bytes
Ingestion throughput: 120,000 × 200B = ~24 MB/s write throughput

Storage (raw, 30-day retention):
  120,000 metrics/s × 200B × 86,400s × 30 = ~60 TB/month (compressed ~15 TB)

Alert rules: ~50,000 rules evaluated every 30s
```

---

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                          DATA SOURCES                                │
│                                                                      │
│  ┌──────────────┐   PUSH (HTTP/gRPC)    ┌──────────────────────┐   │
│  │  App Servers  │ ──────────────────►  │                      │   │
│  │  (10,000)     │                      │   Metrics Ingestion   │   │
│  └──────────────┘                       │   Gateway             │   │
│                                         │   (Load Balanced)     │   │
│  ┌──────────────┐   PULL (Prometheus)   │                      │   │
│  │  DB Servers   │ ◄─────────────────── │                      │   │
│  │  (2,000)      │                      └──────────┬───────────┘   │
└──────────────────────────────────────────────────────┼──────────────┘
                                                       │
                                          ┌────────────▼────────────┐
                                          │   Message Queue          │
                                          │   (Kafka)                │
                                          │   Topic: raw-metrics     │
                                          └────────────┬────────────┘
                                                       │
                              ┌────────────────────────┼──────────────────────┐
                              │                        │                      │
                    ┌─────────▼──────┐      ┌─────────▼──────┐    ┌─────────▼──────┐
                    │  Stream         │      │  Aggregation    │    │  Alert          │
                    │  Processor      │      │  Service        │    │  Evaluator      │
                    │  (Flink)        │      │  (Flink/Spark)  │    │  (Flink CEP)    │
                    └─────────┬──────┘      └─────────┬──────┘    └─────────┬──────┘
                              │                        │                      │
                    ┌─────────▼──────┐      ┌─────────▼──────┐    ┌─────────▼──────┐
                    │  Time-Series DB │      │  Aggregated     │    │  Alert          │
                    │  (InfluxDB /    │      │  Store          │    │  Notification   │
                    │   Prometheus)   │      │  (Cassandra)    │    │  Service        │
                    └────────────────┘      └────────────────┘    └────────────────┘
                              │                        │                      │
                              └────────────────────────┘          ┌─────────▼──────┐
                                           │                       │  PagerDuty /    │
                              ┌────────────▼────────────┐         │  Slack / Email  │
                              │   Query / Dashboard      │         └────────────────┘
                              │   (Grafana)              │
                              └─────────────────────────┘
```

---

## Push vs Pull Model

### App Servers — Push Model

```
App Server ──► Metrics Agent ──► HTTP/gRPC ──► Ingestion Gateway ──► Kafka
```

**Why Push for App Servers?**
- App servers can be **ephemeral** (auto-scaling, containers, lambdas) — pull would miss short-lived instances
- App server knows best **when** to emit (on event, on interval)
- Reduces network complexity — no need for the collector to know every server's address
- Works behind NAT/firewalls

**Protocol:**
```
POST /v1/metrics  HTTP/2
Content-Type: application/x-protobuf

MetricBatch {
  source: "app-server-us-east-1a-pod-42"
  timestamp: 1710000000000
  metrics: [
    { name: "http.request.count",    value: 4523,  tags: {method: "GET", status: "200"} },
    { name: "http.request.latency",  value: 142.3, tags: {p99: true} },
    { name: "jvm.heap.used",         value: 2048,  tags: {unit: "MB"} },
    { name: "cpu.usage.percent",     value: 67.4,  tags: {} }
  ]
}
```

**Push Flow:**
```
1. App Server collects metrics locally every 10s
2. Metrics Agent batches and compresses (gzip/snappy)
3. POST to nearest Ingestion Gateway (anycast routing)
4. Gateway validates, deduplicates, publishes to Kafka
5. ACK returned to agent
6. On failure: agent buffers locally (disk-backed queue, 10min max)
```

---

### DB Servers — Pull Model (Prometheus-style)

```
Scraper ──► HTTP GET /metrics ──► DB Exporter ──► Kafka
```

**Why Pull for DB Servers?**
- DB servers are **long-lived, known, stable** — their addresses are registered in service discovery
- Pull gives the collector **control over scrape rate** — prevents overloading the DB
- Easier to detect if a DB server is **down** (scrape fails = alert)
- DB exporters (mysqld_exporter, pg_exporter) expose Prometheus `/metrics` endpoint natively

**Pull Flow:**
```
1. Service Discovery (Consul/etcd) maintains DB server registry
2. Scraper pool (one per data center) polls every 15s
3. GET http://db-server-01:9104/metrics  (mysqld_exporter format)
4. Scraper parses Prometheus exposition format
5. Publishes to Kafka topic: raw-metrics
6. If scrape fails → mark server as DOWN → trigger alert
```

**Prometheus Exposition Format (DB Server Response):**
```
# HELP mysql_global_status_connections Total connections
# TYPE mysql_global_status_connections counter
mysql_global_status_connections{instance="db-01"} 1523456

# HELP mysql_global_status_slow_queries Slow queries
# TYPE mysql_global_status_slow_queries counter
mysql_global_status_slow_queries{instance="db-01"} 42

# HELP mysql_global_status_threads_running Running threads
# TYPE mysql_global_status_threads_running gauge
mysql_global_status_threads_running{instance="db-01"} 12
```

---

### Status Check of App Servers

```
Health Check Prober ──► GET /health ──► App Server
                    ──► TCP Connect check
                    ──► DNS resolution check
```

```
Health Check Response:
{
  "status": "UP",          // UP | DOWN | DEGRADED
  "checks": {
    "db_connection": "UP",
    "cache": "UP",
    "disk_space": "WARN"   // >80% used
  },
  "timestamp": "2026-03-10T10:00:00Z"
}
```

- Probers run from **multiple regions** — 3/5 failures = truly down (avoid false positives from network blip)
- Prober results published to Kafka topic: `health-checks`
- Status check interval: **every 10 seconds**
- Alert fires if server is DOWN for **2 consecutive checks** (20s)

---

## Component Deep Dive

### 1. Ingestion Gateway

```
                    ┌─────────────────────────────────┐
                    │        Ingestion Gateway         │
                    │                                  │
  App Server ──────►│  Rate Limiter (per source)       │
  (Push)            │  ↓                              │
                    │  Schema Validator                │
                    │  ↓                              │
  DB Scraper ──────►│  Deduplication (bloom filter)   │──► Kafka
  (Pull result)     │  ↓                              │
                    │  Timestamp normalization         │
                    │  ↓                              │
                    │  Kafka Producer (batched)        │
                    └─────────────────────────────────┘
```

- **Rate Limiting:** Token bucket per source (1000 metrics/s per server)
- **Deduplication:** Bloom filter to drop exact duplicates within 60s window
- **Timestamp Normalization:** Accept up to 5 min late; reject >10 min old
- **Kafka Producer Config:** `acks=1`, `linger.ms=5`, `batch.size=64KB`

---

### 2. Kafka Topics

```
Topic: raw-metrics
  Partitions: 200 (partitioned by source_id hash)
  Retention: 24 hours
  Replication: 3

Topic: aggregated-metrics-1m
  Partitions: 50
  Retention: 7 days

Topic: health-checks
  Partitions: 20
  Retention: 6 hours

Topic: alerts
  Partitions: 10
  Retention: 30 days
```

---

### 3. Stream Processor (Flink) — Raw Metric Processing

```java
DataStream<Metric> rawMetrics = env
    .fromSource(kafkaSource, WatermarkStrategy
        .<Metric>forBoundedOutOfOrderness(Duration.ofSeconds(30))
        .withTimestampAssigner((m, ts) -> m.getTimestamp()),
        "raw-metrics");

// Enrich with metadata (host → region, team, service)
DataStream<EnrichedMetric> enriched = rawMetrics
    .map(new MetadataEnricher(serviceRegistry));

// Write raw to time-series DB
enriched.addSink(new InfluxDBSink());

// Write to aggregation pipeline
enriched.keyBy(m -> m.getMetricName() + "|" + m.getSource())
        .window(TumblingEventTimeWindows.of(Time.minutes(1)))
        .aggregate(new MetricAggregator())   // min, max, avg, p99, count
        .addSink(new CassandraSink("aggregated_metrics"));
```

---

### 4. Aggregation Service

```
Raw metrics (per second)
        │
        ▼
   1-minute rollup   →  store in Cassandra (retention: 30 days)
        │
        ▼
   5-minute rollup   →  store in Cassandra (retention: 90 days)
        │
        ▼
   1-hour rollup     →  store in Cassandra (retention: 1 year)
        │
        ▼
   1-day rollup      →  store in Cassandra (retention: 3 years)
```

**Aggregation per window:**
- `count`, `sum`, `min`, `max`, `avg`
- `p50`, `p95`, `p99` (using t-digest or HLL sketches)

---

### 5. Alert Evaluator (Flink CEP)

```
                    ┌──────────────────────────────────────────┐
                    │           Alert Evaluator                 │
                    │                                          │
  raw-metrics ─────►│  Rule Engine                             │
                    │  (evaluate 50,000 rules every 30s)       │
                    │                                          │
  alert-rules ─────►│  Broadcast State                        │──► alerts topic
  (from config DB)  │  (rules hot-reloaded without restart)   │
                    │                                          │
  health-checks ───►│  CEP Patterns                           │
                    │  (consecutive failures → alert)          │
                    └──────────────────────────────────────────┘
```

**Alert Rule Types:**

```yaml
# Threshold Alert
- name: high_cpu_alert
  metric: cpu.usage.percent
  condition: avg(5m) > 85
  for: 2m          # must persist for 2 min (avoid flapping)
  severity: WARNING

# Rate-of-Change Alert
- name: error_spike
  metric: http.error.rate
  condition: rate(1m) > 2x baseline
  severity: CRITICAL

# Absence Alert (Pull model — DB server unreachable)
- name: db_scrape_failure
  metric: up
  condition: up == 0
  for: 30s
  severity: CRITICAL

# Composite Alert
- name: db_overload
  condition: >
    mysql_threads_running > 100 AND
    mysql_slow_queries_rate > 10/s
  severity: PAGE
```

**Flink CEP for Consecutive Failures:**
```java
Pattern<HealthCheck, ?> downPattern = Pattern
    .<HealthCheck>begin("first_down")
        .where(h -> h.getStatus().equals("DOWN"))
    .next("second_down")
        .where(h -> h.getStatus().equals("DOWN"))
    .within(Time.seconds(30));

CEP.pattern(healthChecks.keyBy(h -> h.getServerId()), downPattern)
   .select(match -> new Alert("SERVER_DOWN", match.get("second_down").get(0)));
```

---

### 6. Alert Notification Service

```
alerts topic
      │
      ▼
┌─────────────────────────────────────────┐
│         Notification Service             │
│                                         │
│  Deduplication (same alert, 5min)       │
│  ↓                                      │
│  On-call routing (PagerDuty schedule)   │
│  ↓                                      │
│  Severity routing:                      │
│    CRITICAL → Page (PagerDuty + SMS)    │
│    WARNING  → Slack channel             │
│    INFO     → Dashboard only            │
└──────────┬────────────────────┬─────────┘
           │                    │
     ┌─────▼──────┐      ┌──────▼──────┐
     │ PagerDuty  │      │    Slack     │
     └────────────┘      └─────────────┘
```

- **Alert deduplication:** Same alert fires once per 5-minute window
- **Alert suppression:** Maintenance windows suppress alerts
- **Escalation:** No ACK in 5min → escalate to secondary on-call

---

## Data Model

### Metric (Kafka / Raw)

```json
{
  "source_id":   "app-server-us-east-1a-42",
  "metric_name": "http.request.latency",
  "value":       142.3,
  "timestamp":   1710000000000,
  "tags": {
    "region":    "us-east-1",
    "service":   "payments",
    "env":       "prod",
    "host":      "app-42"
  }
}
```

### Aggregated Metric (Cassandra)

```cql
CREATE TABLE aggregated_metrics (
  metric_name   TEXT,
  source_id     TEXT,
  window_start  TIMESTAMP,
  resolution    TEXT,         -- '1m', '5m', '1h', '1d'
  count         BIGINT,
  sum           DOUBLE,
  min           DOUBLE,
  max           DOUBLE,
  avg           DOUBLE,
  p99           DOUBLE,
  tags          MAP<TEXT, TEXT>,
  PRIMARY KEY ((metric_name, source_id, resolution), window_start)
) WITH CLUSTERING ORDER BY (window_start DESC)
  AND default_time_to_live = 2592000;  -- 30 days for 1m resolution
```

### Alert State (Redis + Postgres)

```sql
-- Alert Rules (Postgres — source of truth)
CREATE TABLE alert_rules (
  id          UUID PRIMARY KEY,
  name        TEXT NOT NULL,
  metric_name TEXT NOT NULL,
  condition   TEXT NOT NULL,      -- DSL expression
  threshold   DOUBLE PRECISION,
  duration    INTERVAL,
  severity    TEXT,               -- INFO | WARNING | CRITICAL | PAGE
  enabled     BOOLEAN DEFAULT true,
  created_at  TIMESTAMPTZ
);

-- Firing Alerts (Redis — fast access)
alert:firing:{rule_id}:{source_id} → {
  "started_at": 1710000000,
  "last_seen":  1710000300,
  "value":      92.4,
  "notified":   true
}
TTL: 1 hour (auto-clears resolved alerts)
```

---

## Alerting Pipeline

```
Timeline (from anomaly to notification):

T+0s   Anomaly occurs (CPU spikes to 95%)
T+10s  App server pushes metric batch (every 10s)
T+11s  Ingestion Gateway receives & publishes to Kafka
T+12s  Flink Alert Evaluator reads from Kafka
T+15s  Rule evaluated: avg(5m) > 85 → PENDING
T+2m   Condition persists for `for: 2m` → FIRING
T+2m1s Alert published to alerts Kafka topic
T+2m2s Notification Service deduplicates
T+2m3s PagerDuty API called
T+2m5s On-call engineer paged  ✓

Total alert latency: ~2 minutes (dominated by `for` duration)
For immediate alerts (no `for`): ~15 seconds
```

---

## Storage Strategy

| Data Type | Store | Retention | Reason |
|---|---|---|---|
| Raw metrics (streaming) | Kafka | 24 hours | Buffer for reprocessing |
| Raw metrics (queryable) | InfluxDB / VictoriaMetrics | 7 days | Fast time-series queries |
| 1-min aggregates | Cassandra | 30 days | High write throughput |
| 5-min aggregates | Cassandra | 90 days | Medium-term dashboards |
| 1-hour aggregates | Cassandra | 1 year | Long-term trends |
| 1-day aggregates | Cassandra | 3 years | Capacity planning |
| Alert rules | Postgres | Forever | Source of truth |
| Firing alert state | Redis | 1 hour TTL | Fast alert evaluation |
| Alert history | Postgres | 2 years | Audit, postmortems |

---

## High Availability & Scalability

### Ingestion Gateway
- Deployed across **3+ availability zones**
- Stateless — horizontal scaling via load balancer
- If gateway fails: app servers buffer locally (disk-backed, 10 min)

### Kafka
- **3 replicas** per partition, `min.insync.replicas=2`
- Multi-AZ broker deployment
- Kafka MirrorMaker for cross-region replication

### Flink (Stream Processor & Alert Evaluator)
- **RocksDB state backend** — spills state to disk, survives restarts
- Checkpointing every **30 seconds** to S3
- On failure: restarts from last checkpoint, replays Kafka (max 30s reprocessing lag)
- Parallelism scaled independently per operator

### Cassandra (Aggregated Storage)
- **Replication factor 3** across AZs
- `LOCAL_QUORUM` for writes (HA + consistency balance)
- Compaction: `TimeWindowCompactionStrategy` — optimal for time-series
- Node auto-scaling via token-aware load balancing

### Alert Evaluator HA
- **Active-active**: multiple Flink job managers; Kafka consumer group ensures no duplicate evaluation
- Alert state in Redis Cluster (3 masters × 1 replica each)
- Redis Sentinel for automatic failover

### Health Check Probers
- **Multi-region probers** — 3 regions probe each server
- Majority vote: 2 of 3 regions must report DOWN to fire alert
- Prevents false positives from regional network blips

---

## Trade-offs & Decisions

### 1. Push vs Pull — Why Both?

| | Push (App Servers) | Pull (DB Servers) |
|---|---|---|
| Ephemeral instances | ✅ Works (push on birth) | ❌ Hard (need discovery) |
| Scrape rate control | ❌ Server controls | ✅ Collector controls |
| Detects server down | ❌ Server stops sending | ✅ Scrape failure = alert |
| Firewall friendly | ✅ Outbound only | ❌ Needs inbound port |
| DB exporter compat | — | ✅ Native Prometheus |

### 2. Kafka as Buffer — Why Not Direct Write?

- Decouples ingestion speed from processing speed
- Allows multiple consumers (stream processor, alert evaluator, debug consumer)
- Enables **replay** if alert evaluator has a bug — replay from Kafka
- Absorbs traffic spikes without dropping metrics

### 3. Eventual Consistency — How It's Handled

- Watermarks allow **30s late arrival** before window closes
- Side outputs capture **very late data** (>30s) — stored but not re-aggregated
- Dashboard queries show "data as of T-30s" — acceptable for monitoring
- Alerts use **raw stream** (not aggregates) for minimum latency

### 4. Aggregation Before Storage — Why?

```
Without aggregation:
  120,000 raw metrics/s × 86,400s/day = 10.3 BILLION rows/day → too expensive

With aggregation (1-min rollup):
  12,000 sources × 100 metrics × 1440 min/day = 1.7 BILLION 1-min rows/day
  → further roll up to 5m: 345M rows/day  ✓
```

### 5. Why Flink for Alert Evaluation (not Spark)?

- Flink evaluates rules **record-by-record** → ~10-15s alert latency
- Spark micro-batch → ~100-500ms + batch interval overhead
- CEP patterns (consecutive failures) are native in Flink
- Real-time alerting is Flink's primary strength

---

## Summary Flow

```
App Servers  ──PUSH──►┐
                      ├──► Ingestion Gateway ──► Kafka ──► Flink Stream Processor
DB Servers   ◄─PULL──►┘                                          │
                                                        ┌─────────┴──────────┐
                                                        │                    │
                                                   InfluxDB            Cassandra
                                                  (raw, 7d)         (aggregated)
                                                        │                    │
                                                        └─────────┬──────────┘
                                                                  │
                                                             Grafana Dashboard
                                                                  
Flink Alert Evaluator ──► alerts topic ──► Notification Service ──► PagerDuty/Slack
```

---

## Core Design Decisions

### Push (App Servers)
- App servers are ephemeral (containers, auto-scaling) — they push every 10s via HTTP/gRPC
- Agent buffers locally on disk if gateway is unavailable (10 min window)
- Gateway rate-limits, deduplicates, and publishes to Kafka

### Pull (DB Servers)
- DB servers are stable and long-lived — scraper polls every 15s via Prometheus `/metrics`
- Pull naturally detects if a DB is DOWN (scrape failure = immediate alert trigger)
- Compatible with `mysqld_exporter`, `pg_exporter` out of the box

### Real-Time Alerts (Flink)
- Raw stream → Flink Alert Evaluator → alert fires in **~15 seconds** (without `for` guard)
- CEP patterns catch consecutive failures (2 health check failures in 30s = alert)
- Broadcast State allows alert rules to update **at runtime** without restart
- Alert deduplication in Redis prevents notification spam

### Storage Tiering
- Raw → InfluxDB (7 days), then rolled up into Cassandra at 1m / 5m / 1h / 1d granularities
- Cassandra `TimeWindowCompactionStrategy` is purpose-built for time-series data

### High Availability Strategy
- Flink checkpoints to S3 every 30s — worst case 30s replay on crash
- Multi-region health check probers with majority vote — prevents false positives
- Kafka with 3 replicas + MirrorMaker for cross-region durability
