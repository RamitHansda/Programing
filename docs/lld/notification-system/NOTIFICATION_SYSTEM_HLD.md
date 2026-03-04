# Notification System — High-Level Design

**Scope:** Multi-channel notification platform supporting Push (FCM/APNs), SMS, Webhook, and Email  
**Scale Target:** 10 million notification events per day  
**Key Concerns:** Differing provider success rates, failure fallback, bottleneck identification, and mitigation

---

## Table of Contents

1. [Problem Statement & Requirements](#1-problem-statement--requirements)
2. [Back-of-Envelope Estimation](#2-back-of-envelope-estimation)
3. [High-Level Architecture](#3-high-level-architecture)
4. [Component Deep Dive](#4-component-deep-dive)
5. [Channel Design & Success Rates](#5-channel-design--success-rates)
6. [Failure Handling & Fallback Strategy](#6-failure-handling--fallback-strategy)
7. [Data Models](#7-data-models)
8. [Bottleneck Analysis & Mitigations](#8-bottleneck-analysis--mitigations)
9. [Scaling Strategy](#9-scaling-strategy)
10. [Trade-offs & Alternatives](#10-trade-offs--alternatives)
11. [Interview Cheat Sheet](#11-interview-cheat-sheet)

---

## 1. Problem Statement & Requirements

### Functional Requirements

- Accept notification requests from internal services (producers)
- Deliver notifications via **Push** (FCM/APNs), **SMS** (Twilio/SNS), **Email** (SendGrid/SES), **Webhook** (HTTP callback)
- Support **user preferences**: opted-in channels, DND windows, frequency caps
- **Template rendering**: dynamic content injection per channel
- **Retry** failed deliveries with exponential backoff
- **Fallback**: if primary channel fails beyond threshold, attempt next preferred channel
- **Delivery tracking**: record success/failure per notification per channel
- **Deduplication**: prevent duplicate sends for the same event

### Non-Functional Requirements

- **Scale:** 10M events/day (~116 avg/sec, peak ~1,000/sec at 8-10x spike)
- **Latency:** Push and SMS < 5s end-to-end P99; Email/Webhook < 30s
- **Availability:** 99.9% (< 9 hrs/year downtime)
- **Durability:** No notification dropped silently — all failures recorded
- **Observability:** Per-channel delivery rates, latency histograms, error rates

### Out of Scope

- User authentication (caller identity assumed via API keys)
- Two-way SMS / reply handling
- In-app notification rendering (front-end concern)

---

## 2. Back-of-Envelope Estimation

```
Events/day:          10,000,000
Avg/sec:             10M / 86,400 ≈ 116 /sec
Peak (8x spike):     ~930 /sec → round to 1,000 /sec

Avg message payload: ~2 KB (template + metadata)
Daily ingress:       10M × 2 KB = ~20 GB/day

Delivery status rows written/day:
  10M events × 1.2 avg attempts = ~12M DB writes/day
  At 12M / 86,400 ≈ 140 writes/sec (avg), ~1,100 writes/sec (peak)

Kafka storage (7-day retention):
  20 GB/day × 7 = 140 GB (compressed ~3x → ~47 GB)

Channel split assumption (typical product):
  Push:    50%  → 5M/day
  Email:   30%  → 3M/day
  SMS:     10%  → 1M/day
  Webhook: 10%  → 1M/day
```

---

## 3. High-Level Architecture

```
 Producers (internal services)
        │  REST / gRPC
        ▼
┌───────────────────────────────┐
│         API Gateway           │  Auth (API key), rate limit, schema validation
└──────────────┬────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                      Notification API Service                            │
│  • Idempotency check (Redis / DB)                                        │
│  • Enrich: look up user prefs, device tokens, contact details            │
│  • Template render (or defer to worker)                                  │
│  • Publish enriched event to Kafka topic: notifications.raw              │
└──────────────────────────────┬───────────────────────────────────────────┘
                               │  Kafka
            ┌──────────────────┴──────────────────────┐
            │         notifications.raw (partitioned)  │
            └────┬──────────────┬──────────────────────┘
                 │              │ (fan-out by channel type)
                 ▼              ▼
    ┌────────────────┐  ┌────────────────┐   ┌────────────────┐  ┌──────────────────┐
    │  Push Dispatcher│  │Email Dispatcher│   │ SMS Dispatcher │  │Webhook Dispatcher│
    │  (worker pool) │  │  (worker pool) │   │  (worker pool) │  │  (worker pool)   │
    └───────┬────────┘  └───────┬────────┘   └───────┬────────┘  └────────┬─────────┘
            │                   │                     │                    │
     FCM / APNs          SendGrid / SES         Twilio / SNS        Client HTTP endpoint
            │                   │                     │                    │
            └───────────────────┴──────────── ─────────┴────────────────── ┘
                                              │
                              ┌───────────────▼──────────────┐
                              │     Delivery Status Store     │
                              │  (PostgreSQL / Cassandra)     │
                              └───────────────┬──────────────┘
                                              │
                              ┌───────────────▼──────────────┐
                              │   Retry / DLQ Engine          │
                              │   (Kafka DLQ topics +         │
                              │    scheduled retry worker)    │
                              └──────────────────────────────┘
                                              │ fallback events
                              ┌───────────────▼──────────────┐
                              │   Fallback Orchestrator       │
                              │   (re-routes to next channel) │
                              └──────────────────────────────┘
```

### Supporting Services

```
┌─────────────────────┐    ┌──────────────────────┐    ┌──────────────────────┐
│  User Preference    │    │   Template Engine     │    │  Monitoring &        │
│  Service            │    │   (Mustache / Jinja)  │    │  Alerting            │
│  (Redis + MySQL)    │    │   (cache compiled)    │    │  (Prometheus/Grafana)│
└─────────────────────┘    └──────────────────────┘    └──────────────────────┘
```

---

## 4. Component Deep Dive

### 4.1 Notification API Service

**Responsibilities:**
- Validate request schema and API key
- **Idempotency:** hash(producer_id + event_id) → check Redis; if seen, return cached status (TTL 24h)
- Resolve user contact details: email address, phone number, device tokens
- Load user preferences: allowed channels, DND schedule, frequency cap
- Optionally render template inline (for low-latency push), or defer to worker
- Publish to `notifications.raw` Kafka topic

**Why defer rendering to workers?** Template rendering can involve DB lookups (e.g., personalised data). Doing it async decouples API latency from rendering cost.

---

### 4.2 Kafka Topology

```
Topic                      Partitions  Retention   Purpose
─────────────────────────────────────────────────────────────────
notifications.raw          32          24h         All inbound events
notifications.push         16          24h         Routed push jobs
notifications.email        16          48h         Routed email jobs
notifications.sms          8           48h         Routed SMS jobs
notifications.webhook      8           48h         Routed webhook jobs
notifications.dlq          8           7d          Failed after max retries
notifications.retry        8           48h         Scheduled retry events
```

**Partition key:** `user_id` — ensures ordering per user; prevents duplicate sends from parallel workers processing the same user.

**Consumer groups:** One consumer group per dispatcher type. Each group has N workers = N Kafka partitions consumed in parallel.

---

### 4.3 Channel Dispatchers

Each dispatcher is a pool of stateless workers that:

1. Consume from their channel-specific Kafka topic
2. Render template if not already rendered
3. Call the third-party provider API
4. Record delivery status to DB (async, batched)
5. On failure: classify error → retry, DLQ, or hard-fail

```
Worker pool size guidelines (at peak 1,000 events/sec):
  Push:    ~20 workers  (FCM batch API, 500 tokens/request → very high throughput)
  Email:   ~30 workers  (SendGrid: 1,000 req/sec/IP; 3 IPs → 3,000/sec headroom)
  SMS:     ~15 workers  (Twilio: rate-limited per account, ~100 msg/sec default)
  Webhook: ~40 workers  (client endpoints vary; long tail of slow HTTP responses)
```

---

### 4.4 User Preference Service

Stores and serves per-user notification configuration:

```
user_preferences:
  user_id          VARCHAR PK
  channel_priority JSON      -- ["push", "email", "sms"]
  opted_out        JSON      -- {"sms": true}
  dnd_start        TIME      -- "22:00"
  dnd_end          TIME      -- "08:00"
  timezone         VARCHAR   -- "America/New_York"
  freq_cap_per_day INT       -- max notifications per day
```

**Caching:** Read-through Redis cache with 5-min TTL. Hot users always served from cache. Write-through on preference update.

---

### 4.5 Template Engine

- Templates stored in DB, compiled and cached in-process memory (LRU)
- Supports Mustache / Handlebars syntax
- Channel-specific templates: same notification type has separate templates per channel (email needs HTML body, SMS is 160-char plain text)
- Template cache invalidation on publish → broadcast via Redis Pub/Sub

---

### 4.6 Delivery Status Store

Tracks every send attempt:

```
notification_deliveries:
  id              UUID PK
  notification_id UUID
  user_id         VARCHAR
  channel         ENUM(push, email, sms, webhook)
  status          ENUM(pending, sent, delivered, failed, skipped)
  provider        VARCHAR        -- "fcm", "sendgrid", "twilio"
  provider_msg_id VARCHAR        -- provider's own tracking ID
  attempt_number  INT
  error_code      VARCHAR
  created_at      TIMESTAMP
  updated_at      TIMESTAMP
```

**Write path:** Workers publish status events to a `status.updates` Kafka topic; a dedicated status writer consumes and batch-upserts to DB (1,000 rows/batch, 500ms flush interval). This prevents write hotspots.

---

## 5. Channel Design & Success Rates

### 5.1 Realistic Success Rates

| Channel | Success Rate | Primary Failure Causes |
|---------|-------------|------------------------|
| **Push (FCM/APNs)** | ~85% | Stale/invalid device token, app uninstalled, device offline, OS-level throttle |
| **Email** | ~78-82% | Spam filters, bounce (invalid address), unsubscribe, ISP throttle |
| **SMS** | ~93-97% | Number ported/inactive, carrier filtering of bulk SMS, opt-out |
| **Webhook** | ~70-90% | Client endpoint down, timeout, 5xx, TLS errors |

### 5.2 Error Classification per Channel

```
Push errors:
  HARD FAIL (no retry):   InvalidRegistration, NotRegistered (token expired)
  SOFT FAIL (retry):      Unavailable, InternalServerError, DeviceMessageRateExceeded
  QUOTA:                  MessageRateExceeded → back off and retry

Email errors:
  HARD FAIL:  5xx bounce (invalid address), unsubscribe/suppression list
  SOFT FAIL:  429 TooManyRequests, 503 ServiceUnavailable
  DEFERRED:   Spam folder (no callback; treat as delivered)

SMS errors:
  HARD FAIL:  30003 (unreachable), 30004 (message blocked), 21610 (opted out)
  SOFT FAIL:  30001 (queue overflow), 30002 (account suspended temporarily)

Webhook errors:
  HARD FAIL:  410 Gone (endpoint removed), 400 Bad Request (schema mismatch)
  SOFT FAIL:  5xx, connection timeout, DNS resolution failure
```

---

## 6. Failure Handling & Fallback Strategy

### 6.1 Retry Policy

```
Attempt 1:   immediate
Attempt 2:   delay = 30s
Attempt 3:   delay = 2 min
Attempt 4:   delay = 10 min
Attempt 5:   delay = 1 hr  (max attempts)
→ After attempt 5: move to DLQ, trigger fallback
```

Retry events are published to `notifications.retry` topic with a `deliver_after` timestamp. A **scheduled retry consumer** polls and re-dispatches when the time arrives (similar to a delay queue).

### 6.2 Fallback Channel Logic

```
                ┌────────────────────────────────────────────────────┐
                │            Notification Created                     │
                │  channel_priority = ["push", "email", "sms"]       │
                └──────────────────────┬─────────────────────────────┘
                                       │
                       ┌───────────────▼───────────────┐
                       │    Attempt Push (channel[0])  │
                       └───────────────┬───────────────┘
                                       │
               ┌───────────────────────┼───────────────────────┐
               │ SUCCESS               │ HARD FAIL             │ SOFT FAIL (max retries)
               ▼                       ▼                        ▼
         Mark DELIVERED       Immediately try           After max retries,
         Done.                 next channel             try next channel
                               email (channel[1])       email (channel[1])
                                       │
               ┌───────────────────────┼───────────────────────┐
               │ SUCCESS               │ HARD FAIL             │ SOFT FAIL
               ▼                       ▼                        ▼
         Mark DELIVERED        Try SMS (channel[2])    Try SMS (channel[2])
         Done.                         │
                               ┌───────┴──────┐
                               │ FAIL (all 3) │
                               ▼              ▼
                          DLQ + alert    Mark UNDELIVERABLE
```

**Key rules:**
- Hard failures (invalid token, opted-out) trigger **immediate** fallback — no retry waste
- Soft failures exhaust retries before fallback
- User preferences gate fallback (e.g., if user opted out of SMS, skip it)
- Fallback channel attempt counts as a **separate delivery record**
- Critical notifications (e.g., OTP, security alert) can have `force_fallback=true` to skip retry delays

### 6.3 Idempotency & Deduplication

- Producers must send a unique `event_id`; API service stores `(producer_id, event_id)` in Redis with 24h TTL
- Downstream workers use the `notification_id + channel + attempt_number` as idempotency key when calling providers
- FCM and APNs both support idempotency keys natively

### 6.4 DND & Frequency Cap

- DND check happens at routing time; notification is **held** (stored with `deliver_after = dnd_end_time`) not dropped
- Frequency cap: Redis counter per `user_id` with 24h TTL sliding window; if exceeded → drop and log

---

## 7. Data Models

### 7.1 Core Tables

```sql
-- Inbound event record
CREATE TABLE notifications (
    id              UUID PRIMARY KEY,
    producer_id     VARCHAR(64),
    event_id        VARCHAR(128),            -- producer's idempotency key
    user_id         VARCHAR(64) NOT NULL,
    type            VARCHAR(64),             -- e.g. "order_confirmed", "otp"
    priority        ENUM('critical','high','normal','low') DEFAULT 'normal',
    payload         JSONB,                   -- raw data for template rendering
    channel_order   VARCHAR[],               -- ["push","email","sms"]
    status          ENUM('pending','partial','delivered','undeliverable'),
    created_at      TIMESTAMPTZ DEFAULT now(),
    INDEX (user_id, created_at),
    UNIQUE (producer_id, event_id)           -- deduplication
);

-- Per-attempt delivery record
CREATE TABLE delivery_attempts (
    id              UUID PRIMARY KEY,
    notification_id UUID REFERENCES notifications(id),
    user_id         VARCHAR(64),
    channel         VARCHAR(16),
    provider        VARCHAR(32),
    provider_msg_id VARCHAR(128),
    attempt_num     SMALLINT,
    status          VARCHAR(16),
    error_code      VARCHAR(64),
    error_detail    TEXT,
    sent_at         TIMESTAMPTZ,
    delivered_at    TIMESTAMPTZ,             -- from provider callback
    INDEX (notification_id),
    INDEX (user_id, sent_at)
);
```

### 7.2 User Preferences (Redis + MySQL)

```
Redis key: prefs:{user_id}  →  JSON blob (TTL 5 min)
MySQL:      user_preferences table (source of truth)
```

### 7.3 Device Token Store

```sql
CREATE TABLE device_tokens (
    id          UUID PRIMARY KEY,
    user_id     VARCHAR(64) NOT NULL,
    platform    ENUM('ios','android','web'),
    token       VARCHAR(512) UNIQUE,
    app_version VARCHAR(16),
    active      BOOLEAN DEFAULT TRUE,
    updated_at  TIMESTAMPTZ,
    INDEX (user_id, active)
);
```

---

## 8. Bottleneck Analysis & Mitigations

### 8.1 Bottleneck Map

```
Ingest path:
  API → Kafka → Dispatcher Workers → 3rd Party → DB

Bottleneck #1:  Third-party provider rate limits
Bottleneck #2:  Delivery status DB write throughput
Bottleneck #3:  User preference lookups at scale
Bottleneck #4:  Template rendering cost
Bottleneck #5:  Webhook fan-out to slow/unreliable clients
Bottleneck #6:  Kafka consumer lag under traffic spikes
```

---

### Bottleneck #1 — Third-Party Provider Rate Limits

**Problem:** SMS (Twilio default: ~100 msg/sec), Email (SendGrid: 600 req/min on free, 3,000/sec on dedicated IP), FCM (no hard limit but QPS per sender).

**Mitigations:**
- **Provider-side queues:** Each dispatcher has a token bucket / leaky bucket before calling the provider. Excess jobs stay in Kafka (already buffered).
- **Multi-provider routing:** Route SMS across Twilio + AWS SNS + MessageBird. Round-robin or failover. Doubles effective throughput.
- **Email dedicated IPs:** Warm up dedicated sending IPs over weeks. Use separate pools for transactional vs. marketing.
- **Batch APIs:** FCM supports 500 tokens per multicast send — batch identical push payloads to reduce API calls 500x.

---

### Bottleneck #2 — Delivery Status DB Write Throughput

**Problem:** 12M writes/day → 140 avg/sec, 1,100/sec at peak. Naive synchronous writes will saturate connection pool.

**Mitigations:**
- **Async write pipeline:** Workers publish status to `status.updates` Kafka topic. A dedicated **status sink** consumer batches 1,000 rows and bulk-inserts.
- **Write-optimised store:** Use Cassandra or TimescaleDB for append-heavy delivery logs. Partition by `(user_id, date)`.
- **Hot partition avoidance:** Partition status topic by `notification_id` not `user_id` to distribute DB write load.
- **Read path:** Serve delivery status queries from a read replica or from Cassandra; never the write primary.

---

### Bottleneck #3 — User Preference Lookups

**Problem:** Every notification needs user prefs (channel priority, DND, freq cap). At 1,000/sec that's 1,000 DB reads/sec.

**Mitigations:**
- **Redis read-through cache** with 5-min TTL. Cache hit rate > 95% in steady state.
- **Bloom filter** at API layer: quickly skip users who have globally opted out of all channels (no cache miss needed).
- **Batch prefetch:** When routing from Kafka, consumer prefetches prefs for all users in one Redis pipeline call per batch.

---

### Bottleneck #4 — Template Rendering

**Problem:** Templates may require dynamic data fetched from other services (user name, order details). At scale, this creates a dependency fan-out.

**Mitigations:**
- **Producer pre-enrichment:** Require producers to embed all needed template variables in the notification payload at creation time. No secondary lookups needed.
- **Compiled template cache:** Compile Mustache/Jinja templates once at startup; cache in-memory. Template compilation is 100x more expensive than rendering a compiled template.
- **Async rendering:** Rendering happens in dispatcher workers, not in the API hot path. Template failures don't block API latency.

---

### Bottleneck #5 — Webhook Fan-Out

**Problem:** Each webhook call goes to a different client endpoint. Endpoints may be slow (P99 > 5s), return 5xx, or go down entirely. If workers block on slow HTTP, pool threads starve.

**Mitigations:**
- **Non-blocking HTTP clients:** Use async HTTP (Netty, Apache HttpAsyncClient, Python `aiohttp`). One worker thread can handle hundreds of in-flight requests.
- **Per-endpoint circuit breakers:** If endpoint X returns 5xx for 10 consecutive calls, open circuit for 60s. Prevents hammering dead endpoints.
- **Timeout policy:** Hard 10s connect + 30s read timeout. Don't wait indefinitely.
- **Retry with jitter:** Staggered retries prevent thundering herd on recovering endpoints.
- **Separate worker pool for webhooks:** Isolate slow webhook I/O from fast push/SMS dispatchers.

---

### Bottleneck #6 — Kafka Consumer Lag Under Spikes

**Problem:** A traffic spike (8-10x) means Kafka accumulates a backlog. If workers can't keep up, notification latency grows.

**Mitigations:**
- **Auto-scaling consumers:** K8s Horizontal Pod Autoscaler triggered on consumer lag metric (via Kafka consumer lag exporter → Prometheus → HPA). Scale up within ~60s of lag detection.
- **Prioritized topics:** Separate Kafka topics for `critical` vs `normal` priority. Critical notifications (OTP, security) always processed first. Normal topic consumers scale separately.
- **Back-pressure signalling:** API returns `202 Accepted` with a `notification_id`. Consumers are never blocked by producers. Backlog is handled gracefully.

---

## 9. Scaling Strategy

### Horizontal Scaling

| Component | Scale Axis | Strategy |
|-----------|-----------|----------|
| Notification API | Stateless | Add replicas; load-balance via nginx/ALB |
| Kafka | Partitions | Pre-provision 32 partitions for raw topic; add brokers for throughput |
| Dispatcher Workers | CPU + I/O | Add pods; scale to match Kafka partition count |
| Redis (prefs cache) | Memory | Cluster mode; shard by user_id |
| Delivery Status DB | Write throughput | Cassandra cluster; add nodes for linear write scale |
| Template Engine | CPU | In-process cache; scale workers |

### Multi-Region

- Replicate Kafka across regions (MirrorMaker 2) for disaster recovery
- Each region has own dispatcher fleet; no cross-region notification delivery
- User preferences replicated globally (CRDTs or last-write-wins)

### Capacity Planning at 10M/day

```
API servers:       3 × c5.xlarge (4 vCPU) handles 500 req/sec comfortably
Kafka brokers:     5 × broker nodes (3 for HA, 2 for throughput headroom)
Push workers:      5 pods (FCM batch API is very efficient)
Email workers:     10 pods (SendGrid concurrency limits)
SMS workers:       8 pods (Twilio per-account rate limits)
Webhook workers:   15 pods (async HTTP; one pod handles ~200 concurrent requests)
Redis cluster:     3 primaries × 1 replica; 16 GB RAM sufficient at 10M users
Cassandra:         3 nodes, RF=3; handles 5,000 writes/sec easily
```

---

## 10. Trade-offs & Alternatives

### Kafka vs. SQS/RabbitMQ

| | Kafka | SQS | RabbitMQ |
|--|-------|-----|----------|
| Throughput | Very high (millions/sec) | High | Medium |
| Replay | Yes (offset-based) | No | No |
| Ordering | Per-partition | Per FIFO queue | Per queue |
| Operational complexity | High | Low (managed) | Medium |
| Best for | High-throughput, replay needed | Simpler AWS-native | Low-scale, complex routing |

**Decision:** Kafka — replay is critical for audits and reprocessing after bugs. At 10M/day, SQS could work but lacks replay.

### Push-only vs. Multi-channel Fallback

Without fallback, ~15% of push notifications are never received (device offline, uninstalled). For high-value events (OTP, payment confirmation), fallback to SMS or email is essential. The trade-off is **cost** (SMS is 10-100x more expensive per message than push). Control via `priority` field and notification type configuration.

### Synchronous vs. Asynchronous Rendering

| | Sync (in API) | Async (in worker) |
|--|---------------|-------------------|
| API latency | Higher (fetch data, render) | Low (just publish) |
| Dependency blast radius | API breaks if data service is slow | Isolated |
| Template data freshness | Highest | Slightly stale (seconds) |

**Decision:** Async rendering — prefer lower API latency and isolation. Producers embed all necessary data in the payload.

### Database Choice for Delivery Status

| | PostgreSQL | Cassandra | DynamoDB |
|--|------------|-----------|----------|
| Write throughput | Medium (requires tuning) | Very high (linear scale) | Very high (managed) |
| Query flexibility | SQL joins, complex queries | Limited (partition key) | Limited (partition key) |
| Operational cost | Medium | High | Low (managed) |

**Decision:** Cassandra (or DynamoDB in AWS) for delivery logs — pure append-heavy time-series data, high write throughput, excellent for `SELECT WHERE user_id = ? AND date = ?` queries.

---

## 11. Interview Cheat Sheet

### 30-Second Summary

> "A notification system ingests events via a REST API, publishes them to Kafka, and routes to per-channel dispatcher worker pools that call FCM, Twilio, SendGrid, and HTTP webhooks. User preferences and DND are enforced at routing time (cached in Redis). Failures are classified as hard (immediate fallback) or soft (retry with exponential backoff, then fallback). At 10M/day the key bottlenecks are third-party rate limits, DB write throughput, and Kafka consumer lag — mitigated via batching, async status writes, multi-provider routing, and auto-scaling consumers."

### Key Numbers to Remember

```
10M/day = ~116/sec average, ~1,000/sec peak (8x)
Push success rate:   ~85%
Email success rate:  ~80%
SMS success rate:    ~95%
Webhook success rate: ~75%

Retry: 5 attempts — 0s, 30s, 2m, 10m, 1h → then DLQ + fallback
Status writes: ~12M/day → batch 1,000 rows → ~12,000 batch writes/day
```

### Top 5 Design Decisions the Interviewer Cares About

| Question | Answer |
|----------|--------|
| Why Kafka? | Replay, durability, fan-out to multiple consumer groups, handles burst |
| How do you avoid duplicate sends? | Idempotency key at API (Redis 24h); provider-level idempotency keys |
| How do you handle provider rate limits? | Token bucket per provider; multi-provider routing for SMS/email |
| How do you prevent slow webhooks from blocking workers? | Async HTTP clients; per-endpoint circuit breakers; isolated worker pool |
| How do you scale delivery status writes? | Async via Kafka → batch sink consumer; Cassandra for linear write scale |

### Failure Fallback Decision Tree (condensed)

```
Send attempt result:
  → HARD FAIL  : skip retries → immediately try next channel
  → SOFT FAIL  : retry (max 5) → if exhausted → try next channel
  → SUCCESS    : done
  → ALL CHANNELS EXHAUSTED : DLQ + mark UNDELIVERABLE + alert
```

### Components and Their Single Responsibility

```
API Service          → Validate, deduplicate, enrich, publish to Kafka
Kafka                → Buffer, fan-out, durability, replay
Dispatcher Workers   → Call 3rd party, record status, emit retry/DLQ events
Retry Engine         → Delay queue, exponential backoff, trigger fallback
Preference Service   → Channel order, DND, frequency cap (cached in Redis)
Template Engine      → Render dynamic content per channel
Status Sink          → Batch write delivery outcomes to Cassandra
Fallback Orchestrator→ Re-publish event to next channel's Kafka topic
Monitoring           → Consumer lag, delivery rates, error rates per channel
```
