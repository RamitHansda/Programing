# Webhook Delivery System — High-Level Design

**Scope:** Reliable, scalable HTTP callback (webhook) delivery platform for SaaS products  
**Scale Target:** 1M source events/day → ~5M delivery tasks/day (avg 5 subscribers per event)  
**Key Concerns:** At-least-once delivery, slow/dead subscriber endpoints, fan-out amplification, retry storms, noisy-tenant isolation

---

## Table of Contents

1. [Problem Statement & Requirements](#1-problem-statement--requirements)
2. [Back-of-Envelope Estimation](#2-back-of-envelope-estimation)
3. [High-Level Architecture](#3-high-level-architecture)
4. [Component Deep Dive](#4-component-deep-dive)
5. [Failure Handling & Retry Strategy](#5-failure-handling--retry-strategy)
6. [Data Models](#6-data-models)
7. [Bottleneck Analysis & Mitigations](#7-bottleneck-analysis--mitigations)
8. [Scaling Strategy](#8-scaling-strategy)
9. [Security Design](#9-security-design)
10. [Trade-offs & Alternatives](#10-trade-offs--alternatives)
11. [Interview Cheat Sheet](#11-interview-cheat-sheet)

---

## 1. Problem Statement & Requirements

### Functional Requirements

- Tenants register **webhook subscriptions**: endpoint URL, event type filters, secret
- System validates the endpoint via a **challenge-response handshake** on registration
- On event publish, deliver an **HTTP POST** to every matching subscription
- Sign each payload with **HMAC-SHA256** so subscribers can verify authenticity
- **Retry** failed deliveries with exponential backoff up to 72 hours
- After max retries, surface events in a **Dead Letter Queue (DLQ)** for manual replay
- Tenants can query **delivery logs** (attempt history, HTTP status, latency) via API
- Support **pausing and resuming** a subscription without losing queued events
- Emit a **test event** on demand (endpoint verification without a real event)

### Non-Functional Requirements

| Property             | Target                                             |
|----------------------|----------------------------------------------------|
| Delivery latency     | < 5s p99 from event publish to first HTTP attempt  |
| Throughput           | 5M delivery tasks/day; peak ~600/sec               |
| Availability         | 99.9% (< 9 hrs/year downtime)                      |
| Durability           | Zero silent drops — every failure is recorded      |
| Retry window         | Up to 72 hours with exponential backoff            |
| Tenant isolation     | One misbehaving tenant must not starve others      |
| Observability        | Per-tenant delivery rates, latency histograms, DLQ depth |

### Out of Scope

- Authentication of inbound event producers (assumed via API keys / service identity)
- Bi-directional webhooks (response data from subscriber fed back into the platform)
- SMS / email / push notification channels (separate system)
- Schema validation of subscriber payloads

---

## 2. Back-of-Envelope Estimation

```
Source events/day:             1,000,000
Avg subscriptions per event:   5  (fan-out multiplier)
Delivery tasks/day:            5,000,000
Avg delivery tasks/sec:        5M / 86,400  ≈  58 /sec
Peak (10x spike):              ~580 /sec

Avg payload size:              2 KB (headers + JSON body)
Daily Kafka ingress:           5M × 2 KB  =  ~10 GB/day

Delivery attempt records/day:
  5M tasks × 1.3 avg attempts  =  ~6.5M rows/day
  → 75 DB writes/sec avg, ~750 writes/sec peak

Kafka storage (7-day retention):
  10 GB/day × 7  =  70 GB (compressed ×3 → ~23 GB)

Subscription registry size:
  100K tenants × 20 subscriptions avg  =  2M rows
  2M × 500 bytes (row + indexes)       =  ~1 GB  (fits in memory)

Retry queue depth (worst case — mass subscriber outage):
  5M tasks backlogged × 2 KB  =  ~10 GB  →  buffer with longer Kafka retention
```

---

## 3. High-Level Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                          EVENT PRODUCERS                             │
│        (Payment Service, Order Service, User Service, ...)          │
└──────────────────────────────┬───────────────────────────────────────┘
                               │  REST / gRPC  +  API Key auth
                               ▼
               ┌───────────────────────────────┐
               │       API Gateway             │  Rate limit, auth, schema check
               └───────────────┬───────────────┘
                               │
               ┌───────────────▼───────────────────────────────────┐
               │          Event Ingestion Service                   │
               │  • Idempotency check  (Redis, 24h TTL)            │
               │  • Normalise & validate payload                   │
               │  • Publish to Kafka topic: webhook.events.raw     │
               └───────────────────────────────────────────────────┘
                               │  Kafka
               ┌───────────────▼──────────────────────┐
               │      webhook.events.raw (topic)       │
               └───────────────┬──────────────────────┘
                               │ consume
               ┌───────────────▼──────────────────────────────────┐
               │           Fan-out Service                         │
               │  • Query subscription registry (Redis cache)     │
               │  • For each match, emit one delivery task        │
               │  • Partition tasks by subscriptionId             │
               └───────────────┬──────────────────────────────────┘
                               │ one task per subscriber
               ┌───────────────▼──────────────────────┐
               │    webhook.delivery.tasks (topic)     │  partitioned by subscriptionId
               └───────────────┬──────────────────────┘
                               │ consume
               ┌───────────────▼──────────────────────────────────────┐
               │             Delivery Worker Pool                      │
               │  • Sign payload: X-Webhook-Signature (HMAC-SHA256)   │
               │  • HTTP POST with configurable timeout (10s)         │
               │  • Classify response: success / soft-fail / hard-fail│
               │  • Emit status event to webhook.delivery.status       │
               └──────────────┬────────────────────────┬──────────────┘
                    success   │                        │  failure
                              ▼                        ▼
               ┌──────────────────────┐   ┌───────────────────────────┐
               │   Status Sink        │   │    Retry Scheduler        │
               │   (batch DB writes)  │   │    (exponential backoff)  │
               └──────────────────────┘   └──────────┬────────────────┘
                                                      │ re-enqueue after delay
                                                      ▼
                                          ┌────────────────────────┐
                                          │  webhook.retry (topic) │  delay queue
                                          └──────────┬─────────────┘
                                                      │ (after max retries)
                                                      ▼
                                          ┌────────────────────────┐
                                          │  webhook.dlq (topic)   │  manual replay / alert
                                          └────────────────────────┘
```

### Supporting Services

```
┌──────────────────────┐  ┌──────────────────────┐  ┌──────────────────────┐
│  Subscription        │  │  Circuit Breaker      │  │  Monitoring &        │
│  Registry            │  │  State Store          │  │  Alerting            │
│  (Postgres + Redis)  │  │  (Redis per endpoint) │  │  (Prometheus/Grafana)│
└──────────────────────┘  └──────────────────────┘  └──────────────────────┘
```

---

## 4. Component Deep Dive

### 4.1 Event Ingestion Service

**Responsibilities:**
- Validate API key and request schema
- **Idempotency:** `hash(tenantId + idempotencyKey)` → check Redis; return cached `eventId` if seen (24h TTL)
- Normalise event envelope: attach `eventId` (UUID), `timestamp`, `tenantId`, `eventType`
- Publish to `webhook.events.raw` Kafka topic; ack to producer only after Kafka ack

**Key decision:** Publish to Kafka synchronously before responding `202 Accepted` — guarantees durability before returning to producer.

---

### 4.2 Kafka Topic Topology

```
Topic                       Partitions  Retention  Purpose
──────────────────────────────────────────────────────────────────────
webhook.events.raw          16          24h        All inbound source events
webhook.delivery.tasks      32          48h        Per-subscription delivery jobs
webhook.delivery.status     16          24h        Delivery outcome events (for status sink)
webhook.retry               16          72h        Delayed retry tasks (with deliver_after header)
webhook.dlq                 8           30d        Dead events; manual replay / audit
```

**Partition key:**
- `webhook.events.raw` → `tenantId` (group events by tenant for fan-out locality)
- `webhook.delivery.tasks` → `subscriptionId` (preserves per-subscription ordering)
- `webhook.retry` → `subscriptionId`

**Consumer groups:**
- `fanout-service` consumes `webhook.events.raw`
- `delivery-workers` consumes `webhook.delivery.tasks`
- `retry-dispatcher` consumes `webhook.retry`
- `status-sink` consumes `webhook.delivery.status`

---

### 4.3 Fan-out Service

- Consumes `webhook.events.raw` (one consumer group, N workers = N partitions)
- For each event, queries subscription registry: `SELECT * FROM subscriptions WHERE tenant_id = ? AND ? = ANY(event_types) AND status = 'active'`
- Cache: Redis hash `subscriptions:{tenantId}:{eventType}` with 30s TTL; invalidated on subscription mutation
- For each matching subscription, publishes one **delivery task** to `webhook.delivery.tasks`:

```json
{
  "deliveryId":       "uuid",
  "subscriptionId":   "uuid",
  "eventId":          "uuid",
  "tenantId":         "uuid",
  "targetUrl":        "https://...",
  "secretRef":        "vault-path/...",
  "payload":          { ... },
  "eventType":        "order.completed",
  "attemptNumber":    1,
  "scheduledAt":      "2026-03-07T10:00:00Z"
}
```

**Fan-out scale:** At 1M events/day with avg 5 subscriptions = 5M tasks/day. Fan-out is I/O-bound (Redis + Kafka publish); scale consumer group horizontally.

---

### 4.4 Delivery Worker Pool

Each worker is **async I/O** (non-blocking HTTP via Netty / `aiohttp`) — a single pod handles hundreds of concurrent in-flight requests.

**Per-request flow:**
1. Fetch secret from in-process cache (populated from Vault/KMS at startup, refreshed every 5 min)
2. Serialise payload; compute `HMAC-SHA256(secret, body)`
3. Build HTTP POST:
   ```
   POST {targetUrl}
   Content-Type:         application/json
   X-Webhook-Event-Id:   {eventId}
   X-Webhook-Event-Type: {eventType}
   X-Webhook-Timestamp:  {unix_ts}
   X-Webhook-Signature:  sha256={hmac_hex}
   User-Agent:           WebhookDelivery/1.0
   ```
4. Enforce: connect timeout 5s, read timeout 10s
5. Classify response → publish status event to `webhook.delivery.status`

**Worker pool sizing at peak 600 tasks/sec:**
```
Avg HTTP round-trip: 500ms (P50), 3s (P99)
Target concurrency per pod: 200 in-flight requests
Throughput per pod: 200 / 0.5s = 400 req/sec (P50 scenario)
Pods required: ceil(600 / 400) = 2 pods minimum → run 10 for headroom + P99 tail
```

**Circuit breaker state per endpoint (stored in Redis):**
```
CLOSED   → normal delivery
OPEN     → skip delivery, re-queue with backoff (threshold: 5 consecutive 5xx)
HALF-OPEN → probe with 1 request every 60s; success → CLOSED, failure → OPEN
```

---

### 4.5 Retry Scheduler

- Consumes `webhook.retry` topic
- Each message carries a `deliver_after` epoch timestamp
- Scheduler checks `now() >= deliver_after`; if not yet due, **nacks and re-publishes** with a short sleep (1s poll loop)
- Alternative: use **Kafka's native timestamp-based consumer** or a dedicated delay-queue service (e.g., RabbitMQ delayed exchange, SQS visibility timeout)
- On final failure (attempt 6): publish to `webhook.dlq`; fire alert

---

### 4.6 Status Sink

- Consumes `webhook.delivery.status` topic
- **Batch upserts:** accumulate 1,000 rows or 500ms flush interval, then bulk-insert to delivery_attempts table
- Prevents the write primary from being saturated by per-request synchronous writes
- Also maintains a Redis counter per `(subscriptionId, date)` for fast dashboard queries

---

## 5. Failure Handling & Retry Strategy

### 5.1 Response Classification

```
HTTP Response             Classification    Action
────────────────────────────────────────────────────────────────────
2xx                       SUCCESS           Mark delivered. Done.
3xx (redirect)            HARD FAIL         Log; do not follow. Operator must fix URL.
400 Bad Request           HARD FAIL         Schema mismatch; retry won't fix. DLQ immediately.
401 / 403                 HARD FAIL         Auth failure; DLQ immediately.
404 Not Found             HARD FAIL         Endpoint removed; disable subscription + alert.
410 Gone                  HARD FAIL         Endpoint explicitly gone; disable + alert.
429 Too Many Requests     SOFT FAIL         Honour Retry-After header; otherwise backoff.
5xx Server Error          SOFT FAIL         Retry with backoff.
Connection timeout        SOFT FAIL         Retry with backoff.
DNS resolution failure    SOFT FAIL         Retry (may be transient); disable after 72h.
TLS handshake failure     SOFT FAIL         Retry 1×; if persists → HARD FAIL (cert issue).
```

### 5.2 Retry Schedule (Exponential Backoff + Full Jitter)

```
Attempt   Delay (base)   Jitter (±20%)   Total elapsed
──────────────────────────────────────────────────────
1         immediate      —               0
2         30s            ±6s             ~30s
3         5 min          ±1 min          ~6 min
4         30 min         ±6 min          ~36 min
5         2 hr           ±24 min         ~2.6 hr
6         6 hr           ±72 min         ~8.6 hr
→ After attempt 6: DLQ + disable circuit + alert tenant
```

Full jitter formula: `delay = random(0, base_delay * 2^attempt)` — prevents thundering herd when many subscriptions fail simultaneously (e.g., subscriber datacenter outage).

### 5.3 Delivery Guarantee: At-Least-Once

- Workers commit Kafka offset **only after** successfully writing to `webhook.delivery.status`
- If worker crashes mid-delivery, the task is reprocessed by another worker → potential duplicate
- Subscribers must be idempotent using `X-Webhook-Event-Id` as deduplication key
- The system provides `X-Webhook-Event-Id` (stable across retries) and `X-Webhook-Delivery-Id` (unique per attempt) to allow downstream deduplication

### 5.4 Delivery State Machine

```
              ┌──────────┐
    created   │ PENDING  │
 ─────────────►          │
              └────┬─────┘
                   │ worker picks up
                   ▼
              ┌──────────┐   success     ┌───────────────┐
              │ IN_FLIGHT ├──────────────► DELIVERED      │  terminal
              └────┬──────┘              └───────────────┘
                   │ soft fail
                   ▼
              ┌──────────┐   max retries ┌───────────────┐
              │ RETRYING  ├──────────────► DEAD           │  terminal → DLQ
              └────┬──────┘              └───────────────┘
                   │ hard fail
                   ▼
              ┌──────────┐
              │  FAILED  │  terminal (immediate, no retry)
              └──────────┘
```

---

## 6. Data Models

### 6.1 subscriptions

```sql
CREATE TABLE subscriptions (
    subscription_id   UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID          NOT NULL,
    target_url        TEXT          NOT NULL,
    secret_ref        VARCHAR(256)  NOT NULL,   -- Vault path; secret never stored in DB
    event_types       TEXT[]        NOT NULL,
    status            VARCHAR(16)   NOT NULL DEFAULT 'active',  -- active|paused|disabled
    description       TEXT,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),

    INDEX idx_subscriptions_tenant    (tenant_id),
    INDEX idx_subscriptions_status    (tenant_id, status),
    INDEX idx_subscriptions_event     USING GIN (event_types)   -- array containment queries
);
```

### 6.2 events

```sql
CREATE TABLE events (
    event_id          UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID          NOT NULL,
    event_type        VARCHAR(128)  NOT NULL,
    payload           JSONB         NOT NULL,
    idempotency_key   VARCHAR(256)  NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),

    UNIQUE (tenant_id, idempotency_key),
    INDEX idx_events_tenant_type  (tenant_id, event_type, created_at DESC)
)
PARTITION BY RANGE (created_at);   -- daily partitions, archived to S3 after 30d
```

### 6.3 delivery_attempts

```sql
CREATE TABLE delivery_attempts (
    attempt_id        UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    delivery_id       UUID          NOT NULL,   -- stable ID grouping all attempts for event+sub
    subscription_id   UUID          NOT NULL,
    event_id          UUID          NOT NULL,
    tenant_id         UUID          NOT NULL,
    attempt_number    SMALLINT      NOT NULL,
    status            VARCHAR(16)   NOT NULL,   -- pending|in_flight|delivered|failed|retrying|dead
    http_status       SMALLINT,
    response_body     TEXT,                     -- truncated to 4KB
    duration_ms       INT,
    error_code        VARCHAR(64),
    attempted_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    next_retry_at     TIMESTAMPTZ,

    INDEX idx_delivery_delivery_id      (delivery_id),
    INDEX idx_delivery_subscription     (subscription_id, attempted_at DESC),
    INDEX idx_delivery_tenant_status    (tenant_id, status, attempted_at DESC),
    INDEX idx_delivery_next_retry       (next_retry_at) WHERE status = 'retrying'
)
PARTITION BY RANGE (attempted_at);   -- weekly partitions; archive after 90d
```

### 6.4 Circuit Breaker State (Redis)

```
Key:   cb:{subscriptionId}
Type:  Hash
Fields:
  state           CLOSED | OPEN | HALF_OPEN
  failure_count   INT
  opened_at       epoch_ms
  last_probe_at   epoch_ms
TTL:   no expiry (managed by worker transitions)
```

### 6.5 Subscription Cache (Redis)

```
Key:   subs:{tenantId}:{eventType}        (list of delivery task templates)
Key:   sub:{subscriptionId}               (single subscription detail)
TTL:   30s; invalidated on write via Redis PUBLISH to invalidation channel
```

---

## 7. Bottleneck Analysis & Mitigations

### Bottleneck Map

```
Ingest path:
  Producer → API → Kafka (raw) → Fan-out → Kafka (tasks) → Workers → Subscriber HTTP → DB

Bottleneck #1:  Slow or unresponsive subscriber endpoints blocking delivery workers
Bottleneck #2:  Fan-out amplification causing Kafka write storms
Bottleneck #3:  Subscription registry lookup on every fan-out event
Bottleneck #4:  Delivery log write throughput at peak
Bottleneck #5:  Retry storm after a mass subscriber outage recovers
Bottleneck #6:  Kafka consumer lag under traffic spikes
```

---

### Bottleneck #1 — Slow Subscriber Endpoints

**Problem:** Subscriber P99 response is 5-30s. With sync HTTP, each worker thread is blocked. 600 tasks/sec × 10s avg = 6,000 concurrent connections needed.

**Mitigations:**
- **Async non-blocking HTTP client** (Netty / Python `aiohttp`): one event loop thread handles thousands of in-flight connections.
- **Per-endpoint circuit breaker** (Redis-backed): 5 consecutive 5xx → OPEN state for 60s. Worker skips HTTP call, re-queues with backoff. Prevents wasting threads on dead endpoints.
- **Hard connect timeout (5s) + read timeout (10s):** Never wait indefinitely. Treat timeout as a soft failure.
- **Per-host concurrency cap** (`max_concurrent = 50/host`): prevents a single slow subscriber from monopolising all workers.
- **Separate worker pools by tenant priority tier:** Platinum/Gold tenants get dedicated partitions; their delivery is not delayed by high-volume free-tier tenants.

---

### Bottleneck #2 — Fan-out Amplification

**Problem:** One source event can match 100+ subscriptions. At 1M events/day × 100 subs = 100M delivery tasks/day — a 100x write amplification to Kafka.

**Mitigations:**
- **Subscription count cap per event type per tenant** (configurable, default 1,000): prevents pathological fan-out.
- **Batch publish:** Fan-out service publishes up to 500 delivery tasks per `producer.send()` batch — reduces per-message overhead.
- **Pre-filter in fan-out:** evaluate `event_type` filters server-side before emitting tasks. Use a bloom filter per tenant to early-exit if no subscriptions exist for that event type.
- **Lazy fan-out for large tenants:** For tenants with >1,000 subscriptions, store fan-out list in the task payload as a reference (subscription IDs), and a second-stage fan-out service materialises individual tasks asynchronously. This keeps the first-stage Kafka message small.

---

### Bottleneck #3 — Subscription Registry Lookup at Fan-out Time

**Problem:** 58 source events/sec × avg 5 subscription queries = 290 DB reads/sec sustained; 2,900/sec at peak. Primary DB cannot absorb this.

**Mitigations:**
- **Redis cache with GIN-indexed query cache:** Cache `subs:{tenantId}:{eventType}` as a Redis list (serialised delivery task templates). Cache hit rate > 99% in steady state.
- **Cache warm-up on startup:** Fan-out service pre-loads active subscriptions for top-N tenants by event volume on pod start.
- **Read replicas for subscription DB:** All fan-out lookups go to a read replica. Write primary handles only CRUD mutations.
- **Cache invalidation:** On subscription create/update/delete, publish a Redis `PUBLISH invalidation:subs` message. All fan-out pods subscribe and invalidate locally.

---

### Bottleneck #4 — Delivery Log Write Throughput

**Problem:** 6.5M rows/day = 75 writes/sec avg, ~750 writes/sec peak. Synchronous per-attempt inserts will saturate the write primary.

**Mitigations:**
- **Async write pipeline:** Workers publish status events to `webhook.delivery.status` Kafka topic. A dedicated **status sink** consumer batches 1,000 rows and bulk-inserts every 500ms.
- **Write-optimised store for delivery log:** Use **Cassandra** (or TimescaleDB) for `delivery_attempts` — pure append-heavy time-series data; linear write scale by adding nodes.
- **Partition key:** `(tenant_id, month)` in Cassandra → distributes write load; enables efficient `WHERE tenant_id = ? AND attempted_at > ?` queries.
- **Hot partition avoidance in Kafka:** `webhook.delivery.status` partitioned by `delivery_id` (random UUID), not `tenant_id`, to spread DB write load evenly across sink workers.

---

### Bottleneck #5 — Retry Storm After Mass Outage Recovery

**Problem:** If a major subscriber (e.g., a large tenant's endpoint) is down for 4 hours, thousands of retries accumulate. When the endpoint recovers, all retries fire simultaneously — potentially overwhelming the subscriber and causing it to fail again.

**Mitigations:**
- **Full jitter in backoff:** `delay = random(0, 2^attempt × base)` — naturally spreads retry attempts over time even when they were enqueued simultaneously.
- **Per-subscription retry concurrency cap (1 in-flight retry at a time):** Before firing a retry, check Redis for an existing in-flight delivery for that subscription. If one exists, skip (the result of the in-flight attempt will either succeed or re-schedule).
- **Tenant-level retry rate limit:** Cap total retry throughput per tenant at `N` tasks/sec (e.g., 100/sec). Excess retries stay in Kafka and are consumed at the capped rate.
- **Circuit breaker HALF-OPEN probe:** After OPEN state, send exactly 1 probe every 60s. Only re-open the full delivery pipeline after 3 consecutive successes. This acts as a gradual ramp-up for recovered subscribers.

---

### Bottleneck #6 — Kafka Consumer Lag Under Spikes

**Problem:** A 10x traffic spike means Kafka accumulates a backlog. Notification latency grows proportionally with lag.

**Mitigations:**
- **Kubernetes HPA on consumer lag metric** (via `kafka-lag-exporter` → Prometheus → HPA): scale delivery worker pods within ~60s of lag detection.
- **Priority topic separation:** `webhook.delivery.tasks.critical` (separate topic) for tenants on premium SLA. Critical consumers scale independently; a free-tier spike doesn't delay premium delivery.
- **Pre-provisioned partitions (32) for delivery tasks:** Consumer group scales up to 32 pods without repartitioning.
- **Back-pressure at ingestion:** API returns `202 Accepted` immediately. Kafka absorbs the burst. Producers are never blocked by downstream lag.

---

## 8. Scaling Strategy

### Horizontal Scaling

| Component              | Scale Axis          | Strategy                                            |
|------------------------|---------------------|-----------------------------------------------------|
| Event Ingestion API    | Stateless           | Replicas behind ALB; HPA on CPU                     |
| Fan-out Service        | Kafka consumer lag  | HPA; max replicas = partition count (16)            |
| Delivery Workers       | Kafka consumer lag  | HPA; max replicas = partition count (32)            |
| Retry Scheduler        | Queue depth         | HPA; 2-4 pods typically sufficient                  |
| Subscription DB        | Read throughput     | 1 write primary + 2 read replicas; Redis cache      |
| Delivery Log DB        | Write throughput    | Cassandra cluster; add nodes for linear scale       |
| Redis                  | Memory              | Cluster mode; shard by key prefix                   |

### Capacity Planning at 5M Deliveries/Day (peak 600/sec)

```
Event Ingestion API:   3 × 2 vCPU pods         (stateless; 200 req/sec each)
Fan-out Service:       8 × 2 vCPU pods         (I/O bound; 8 Kafka partitions)
Delivery Workers:      15 × 4 vCPU pods        (async HTTP; 50 concurrent/pod)
Status Sink:           3 × 2 vCPU pods         (Kafka → batch DB writes)
Retry Scheduler:       3 × 2 vCPU pods

Subscription Postgres: 1 primary + 2 replicas  (c5.xlarge; 2M row registry ~1 GB)
Delivery Cassandra:    3 nodes RF=3            (handles 5,000 writes/sec easily)
Redis Cluster:         3 primary + 3 replica   (16 GB RAM; circuit breaker + sub cache)
Kafka:                 5 brokers               (3 for HA, 2 for throughput; 70 GB storage)
```

### Multi-Region / Disaster Recovery

- **Active-passive per region:** Each region runs an independent full stack. Kafka is not replicated cross-region by default (avoid cross-region latency in critical delivery path).
- **Global subscription registry:** Postgres subscriptions replicated cross-region via logical replication (read replicas in each region). Fan-out uses local replica.
- **Failover:** DNS failover (Route 53 health checks) routes producers to healthy region. Kafka offsets in the failed region are replayed after recovery using MirrorMaker 2.
- **DLQ is region-local:** Operators replay from the DLQ within the originating region.

---

## 9. Security Design

### 9.1 Payload Signing (HMAC-SHA256)

Subscribers verify authenticity by computing the expected signature and comparing to `X-Webhook-Signature`:

```
1. Concatenate:  timestamp + "." + raw_request_body
2. Compute:      HMAC-SHA256(subscription_secret, concatenated_string)
3. Encode:       hex(hmac_bytes)
4. Header sent:  X-Webhook-Signature: sha256={hex}
                 X-Webhook-Timestamp: {unix_ts}

Subscriber validation pseudocode:
  expected = HMAC-SHA256(secret, timestamp + "." + body)
  if not hmac.compare_digest(expected, received_signature):
      return 401
  if abs(now() - timestamp) > 300:   # 5-minute replay window
      return 400  # reject replayed requests
```

Including the timestamp in the signed string prevents **replay attacks** — a captured valid request cannot be re-used after the 5-minute window.

### 9.2 Secret Management

- Secrets are **never stored in the application DB** — only a Vault path reference (`secret_ref`) is stored
- Delivery workers fetch secrets from **HashiCorp Vault** (or AWS Secrets Manager) at startup; cached in-process with AES-256 encryption at rest in memory
- Secret rotation: subscribers call `POST /subscriptions/{id}/rotate-secret`; system stores both old and new secret for a 24h grace period, signing with both during transition
- Secrets are **never returned** in API responses after initial creation (return a masked value: `sk_***...abc`)

### 9.3 Additional Controls

| Control                  | Implementation                                           |
|--------------------------|----------------------------------------------------------|
| HTTPS-only endpoints     | Reject `http://` URLs at subscription registration       |
| IP allowlist (optional)  | Tenants can specify CIDR allowlists; system adds `X-Forwarded-For` |
| Subscription ownership   | All API calls scoped to `tenantId` extracted from API key; cross-tenant access is impossible |
| Rate limiting on ingestion | Token bucket per `tenantId`; default 1,000 events/sec   |
| Audit log                | All subscription mutations (create/update/delete/rotate) written to immutable audit log |

---

## 10. Trade-offs & Alternatives

### Kafka vs. SQS vs. RabbitMQ

|                       | Kafka                          | SQS                          | RabbitMQ                    |
|-----------------------|-------------------------------|------------------------------|-----------------------------|
| Throughput            | Very high (millions/sec)       | High                         | Medium                      |
| Replay                | Yes (offset-based, configurable retention) | No (once consumed, gone) | No               |
| Ordering              | Per-partition                  | Per FIFO queue               | Per queue                   |
| Tenant isolation      | Partition-level                | Separate queues per tenant   | Separate queues per tenant  |
| Operational overhead  | High (manage brokers/ZooKeeper)| Low (fully managed)          | Medium                      |
| DLQ support           | Native (separate topic)        | Native                       | Dead-letter exchange        |

**Decision:** Kafka — event replay is critical for reprocessing after bugs, audits, and retroactive subscription creation. At 5M tasks/day, SQS could technically work but loses replay capability. Use **Amazon MSK** (managed Kafka) to reduce operational overhead.

### At-Least-Once vs. Exactly-Once Delivery

|                       | At-Least-Once                  | Exactly-Once                                    |
|-----------------------|-------------------------------|--------------------------------------------------|
| Complexity            | Low                            | Very high (2-phase commit or Kafka transactions) |
| Latency overhead      | Minimal                        | ~10-30% higher (transactional overhead)          |
| Subscriber burden     | Must be idempotent             | No idempotency needed at subscriber              |
| Failure scenario      | Duplicate delivery possible    | No duplicates                                    |
| Industry standard     | Stripe, GitHub, Shopify, Twilio| Rare in webhook systems                          |

**Decision:** At-least-once. Exactly-once in a distributed system requires Kafka transactions + atomic DB writes + subscriber coordination — impractical given that the subscriber's HTTP endpoint is outside our transaction boundary. Provide stable `X-Webhook-Event-Id` for subscriber-side deduplication.

### Push (Kafka Worker) vs. Pull (Subscriber Polls API)

|                       | Push (current design)          | Pull (subscriber polls)                         |
|-----------------------|-------------------------------|--------------------------------------------------|
| Latency               | Low (near real-time)           | Depends on poll interval (often 1-60s)           |
| Subscriber complexity | Low (just expose HTTP endpoint)| Higher (implement polling loop, cursor tracking)|
| Delivery guarantee    | System-managed retries         | Subscriber responsible for reprocessing         |
| Firewall friendliness | Subscriber needs public HTTPS  | Subscriber only needs outbound access            |
| Scalability           | Delivery workers scale with load | Subscriber controls their polling rate         |

**Decision:** Push. Lower latency, simpler subscriber implementation, and aligns with industry standard (Stripe, Shopify, GitHub all use push). Offer an optional **polling API** as a secondary access pattern for subscribers behind strict firewalls.

### Fan-out Strategy: Fan-out-on-write vs. Fan-out-on-read

|                       | Fan-out-on-write (current)     | Fan-out-on-read                                 |
|-----------------------|-------------------------------|--------------------------------------------------|
| Write amplification   | High (1 event → N tasks in Kafka) | Low (1 event stored once)                    |
| Read complexity       | Low (worker gets pre-built task)  | Higher (worker must query subscriptions at delivery time) |
| Subscription changes  | Subscriptions created after event is ingested miss the event | Subscriptions always current at delivery time |
| Latency               | Low (tasks pre-computed)          | Slightly higher (query at delivery time)      |

**Decision:** Fan-out-on-write. Pre-building delivery tasks decouples subscription registry performance from delivery performance. New subscriptions are documented as taking effect for events published **after** registration.

---

## 11. Interview Cheat Sheet

### 30-Second Summary

> "A webhook delivery system ingests events via a REST API, publishes to Kafka, and a fan-out service creates per-subscription delivery tasks (also in Kafka). Delivery workers use async HTTP clients to POST to subscriber endpoints, sign payloads with HMAC-SHA256, and classify responses as hard-fail (immediate DLQ) or soft-fail (retry with exponential backoff up to 72h). Slow subscriber endpoints are isolated via per-endpoint circuit breakers and per-host concurrency caps. The five key bottlenecks are: slow endpoints, fan-out amplification, subscription cache misses, delivery log write throughput, and retry storms — each addressed with circuit breakers, Redis caching, async batch writes, and full-jitter backoff."

### Key Numbers to Remember

```
1M source events/day  →  5M delivery tasks/day (5x fan-out avg)
58 tasks/sec avg       →  580 tasks/sec peak (10x spike)
Retry schedule:        0s, 30s, 5m, 30m, 2h, 6h → DLQ (72h total window)
Delivery log writes:   6.5M rows/day → batch 1,000 rows → ~6,500 batch inserts/day
Subscription cache:    Redis TTL 30s; hit rate > 99% in steady state
Circuit breaker:       Opens at 5 consecutive 5xx; half-open probe every 60s
Payload signing:       HMAC-SHA256(secret, timestamp + "." + body); 5-min replay window
```

### Top Questions the Interviewer Will Ask

| Question | Answer |
|---|---|
| Why Kafka over SQS? | Replay is critical — retroactive subscription creation, reprocessing after bugs, audits. SQS has no replay. |
| How do you prevent duplicate deliveries? | At-least-once is intentional. Provide stable `X-Webhook-Event-Id` across retries for subscriber deduplication. |
| How do you handle slow subscriber endpoints? | Async HTTP clients (non-blocking); per-endpoint circuit breaker in Redis; per-host concurrency cap; hard timeouts (5s connect, 10s read). |
| How do you avoid retry storms? | Full jitter backoff; per-subscription in-flight cap (1); tenant-level retry rate limiter; circuit breaker half-open gradual ramp. |
| How do you isolate noisy tenants? | Partition `webhook.delivery.tasks` by `subscriptionId`; premium tenants on dedicated partitions; per-tenant delivery concurrency caps. |
| How does payload signing prevent replay attacks? | Timestamp is included in the signed string. Subscribers reject requests where `abs(now - timestamp) > 5 min`. |
| What happens if the fan-out service crashes mid-fan-out? | Kafka offset is committed only after all tasks are published for that event. Crash → event is reprocessed from last committed offset. Fan-out is idempotent (delivery workers deduplicate on `eventId + subscriptionId`). |
| How do you handle a subscriber permanently down for 72 hours? | Retries exhaust across the 72h window → task moves to DLQ. Subscription auto-paused. Tenant alerted. Manual replay available from DLQ. |

### Component Single Responsibility

```
Event Ingestion API      → Validate, deduplicate, publish to Kafka
Fan-out Service          → Subscription lookup, emit one task per subscriber
Delivery Worker Pool     → Sign, HTTP POST, classify response, emit status event
Retry Scheduler          → Delay queue, re-enqueue with backoff, emit to DLQ at limit
Status Sink              → Batch write delivery outcomes to DB
Circuit Breaker Store    → Redis-backed per-endpoint state (CLOSED/OPEN/HALF_OPEN)
Subscription Registry    → CRUD, Redis cache with 30s TTL, invalidation on write
Monitoring               → Consumer lag, delivery success rate, DLQ depth, circuit state
```

### Failure Decision Tree (condensed)

```
Delivery attempt result:
  → 2xx              : DELIVERED. Done.
  → Hard fail (3xx, 400, 401, 403, 404, 410):
                       No retry. DLQ immediately. Alert tenant if 404/410 (endpoint gone).
  → Soft fail (5xx, timeout, DNS):
                       Retry up to 6 attempts with exponential backoff + jitter.
                       After attempt 6: DLQ + pause subscription + alert tenant.
  → Circuit OPEN:
                       Skip HTTP call. Re-queue at next retry interval.
                       Probe every 60s (HALF_OPEN). 3 successes → CLOSED.
```
