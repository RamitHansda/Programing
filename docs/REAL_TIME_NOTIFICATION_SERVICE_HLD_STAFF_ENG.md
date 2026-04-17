# Real-Time Notification Service — High-Level Design (Staff Engineer Level)

**Target scale: 10 million notifications per minute (~167K/sec sustained, 500K/sec peak).**

---

## 0. Executive Summary (At a Glance)

A single-page summary for reviewers and interview whiteboard recall. Each block below is a pointer to the detailed section that follows.

### 0.1 Headline numbers

| | Sustained ingest | 3× peak budget | p99 push latency | Delivery SLA |
|---|---|---|---|---|
| **Target** | **167K/s** | **500K/s** | **< 2 s** | **99.9%** |

### 0.2 One-sentence architecture

> **Producers → API Gateway → Ingest → Kafka (priority topics) → Dedup + Rate-limit → Preference/Routing → Fan-out workers → per-channel Kafka → channel workers → vendors; every notification is mirrored into a Cassandra inbox; vendor receipts flow into ClickHouse.**

### 0.3 Channel mix & worker sizing

Drives worker-pool sizing. Slow channels (SMS, email) need bigger buffers and tight per-vendor concurrency caps so one slow vendor cannot starve the others.

| Channel | Share | Peak sends/s | Vendor p99 | Concurrency cap |
|---|---:|---:|---:|---:|
| Push (APNs / FCM) | 42% | 320K | 350 ms | 8,000 |
| In-app WebSocket | 35% | 260K | 20 ms | n/a — fan-out only |
| Email (SES / SendGrid) | 15% | 110K | 1.5 s | 2,000 |
| SMS (Twilio) | 6% | 45K | 2.5 s | 500 / region |
| Webhook | 2% | 15K | variable | per-tenant quota |

### 0.4 Storage choices at a glance

| Store | Purpose | Why | Partition key |
|---|---|---|---|
| Kafka | Durable backbone: raw, per-channel, receipts, DLQ | High throughput, replay, per-user ordering | `user_id` |
| Redis Cluster | Dedup, rate-limit counters, preference cache, WS presence | Sub-ms reads at 1 M+ ops/s, TTL semantics | `{tenant}:{user}` hash slot |
| Cassandra / DynamoDB | Per-user inbox (unread, history, read state) | Write-heavy, linear scale, time-series partitions | `(user_id, bucket)` |
| Postgres | Templates, tenants, user preferences source of truth | Low write rate, relational constraints, audit | `tenant_id` |
| ClickHouse | Delivery analytics, SLO dashboards, tenant billing | Columnar scans on B+ rows/day | `(tenant_id, event_date)` |
| S3 (cold) | Receipts older than 30 d, compliance archive | Cheap, integrates with ClickHouse external tables | date prefix |

### 0.5 Reliability guarantees — six cards

| Card | What it means |
|---|---|
| **Idempotency** | Producer supplies `idempotency_key`. Dedup does `SETNX` in Redis with 24 h TTL. Duplicate → same `notification_id` returned. |
| **At-least-once** | Kafka consumers commit offsets only after vendor ACK or inbox write. Crashed workers replay; idempotency key absorbs duplicates. |
| **Retries & DLQ** | Exponential backoff `1s → 5s → 30s → 5m → 30m`. After 5 attempts → DLQ topic + on-call dashboard. Vendor 4xx → no retry, mark device stale. |
| **Back-pressure** | Ingest sheds non-critical traffic when channel lag > 60 s. Priority tiers: P0 security, P1 transactional, P2 marketing. Marketing pauses first. |
| **Isolation** | Separate consumer group + worker pool per channel and per large tenant. Bulkhead + per-vendor circuit breaker. |
| **Observability** | RED metrics per component, Kafka lag, vendor error rate, p50/p95/p99 keyed by `notification_id`. 1% trace sampling with 100% for P0. |

### 0.6 Key design tradeoffs — one-line summary

| Decision | Chosen | Alternative | Why |
|---|---|---|---|
| Transport backbone | **Kafka** | RabbitMQ / SQS | Replayable, high throughput, per-user partition ordering |
| Delivery semantics | **At-least-once + idempotency** | Exactly-once | Cheaper, simpler; dedup gives effective exactly-once |
| Inbox store | **Cassandra** | Postgres / Mongo | Write-heavy (250K/s), linear scale, time-series friendly |
| Fan-out point | **After preference lookup** | At ingest | Avoids messages for opt-out users (~15% saved) |
| WebSocket routing | **Consistent hash on `user_id`** | Broadcast + filter | O(1) delivery, no N×M fan-out on hub |
| Scheduling | **Tiered: Kafka (<60 s) + durable scheduler (>60 s)** | Single system | Hot path stays fast; long delays use separate store |

### 0.7 Failure scenarios — the short version

| Scenario | Detection | Automatic response | Why it doesn't melt the system |
|---|---|---|---|
| **APNs / FCM outage** | Per-vendor error rate > 25% for 60 s | Circuit breaker opens; messages buffer in per-channel Kafka (72 h retention) | In-app channel still works; degrade push only |
| **Kafka partition hot-spot** | `max_partition_lag / avg > 5` | Salt high-volume tenants with a secondary key; auto-reshard | Relaxed per-user ordering for celebrities, documented trade-off |
| **Redis node loss** | Cluster failover event | ~10 s window of possible duplicate sends | Inbox LWT on `(user_id, idem_key)` catches duplicates |
| **Producer flood / abuse** | Per-tenant RPS z-score anomaly | Token-bucket throttle → isolated topic if ignored | Sheds P2 first; P0/P1 of other tenants unaffected |

### 0.8 Rollout shape

> Start with push + in-app on one region behind a feature flag. Shadow-test at 2× target (1 M/min) via production log replay. Add email, SMS, webhook one per week. Multi-region active-active only after per-region peak holds for 30 days.

---

## 1. Problem Framing

Before drawing boxes, a Staff Engineer first asks: **what problem are we actually solving, and what would failure look like to the business?**

A notification service is the **last-mile delivery layer** for every product event a user cares about: a payment confirmation, a 2FA code, a price alert, a chat message, a marketing blast. It has two very different personalities fighting inside one platform:

- **Transactional** — a single lost 2FA SMS is a customer-trust incident.
- **Broadcast / marketing** — losing 0.1% of a 10M-user blast is acceptable; blocking checkout to send it is not.

Every architectural decision below is really a decision about **how to keep these two personalities from destroying each other**.

---

## 2. Requirements

### Functional

- Accept notification requests from N producer services via a uniform API.
- Deliver to multiple channels: **push (APNs/FCM), email, SMS, in-app (WebSocket/SSE), webhook**.
- Per-user preference resolution: channel selection, quiet hours, locale, opt-outs.
- Templating with variable substitution and localization.
- Idempotent submission (producers can safely retry).
- Scheduled notifications (send at `T+Δ` up to 30 days out).
- Per-user durable inbox (unread, history, read receipts).
- Delivery receipts and analytics (delivered, opened, clicked, bounced).
- Multi-tenant isolation (one noisy tenant cannot starve another).

### Non-Functional

| Property | Target |
|---|---|
| Sustained throughput | 10 M / min = **167K/s** |
| Peak throughput (3×) | **500K/s** |
| p99 end-to-end latency (push, in-app) | **< 2 s** |
| p99 end-to-end latency (email, SMS) | < 30 s (bounded by vendor) |
| Delivery SLA (transactional) | 99.9% within SLA window |
| Availability | 99.95% |
| Data loss | RPO ≤ 1 minute for transactional |
| Idempotency window | 24 h |
| Retention — inbox | 90 days hot, archive to cold |
| Retention — receipts | 30 days hot, 7 y cold (compliance) |

### Out of Scope (v1)

- Rich push payload editing UI (console-only).
- On-device rendering pipeline for rich push / interactive notifications.
- ML-driven send-time optimization (hooks exist, model is a follow-up).
- Cross-channel deduplication beyond the idempotency key (product-level problem).

---

## 3. Core Principles (Non-Negotiable)

### 3.1 At-Least-Once + Idempotency, Never Exactly-Once

Exactly-once delivery across a vendor you don’t control (APNs, Twilio) is a fantasy. We commit to:

1. **At-least-once** transport (Kafka with consumer commits after ACK).
2. **Idempotency keys** on every notification. Dedup happens at ingest (Redis `SETNX` with 24 h TTL) **and** at the inbox write (unique constraint on `(user_id, idempotency_key)`).

Duplicate sends to a vendor are accepted as the cost of not losing a message during a worker crash. The inbox UI and read-receipt pipeline deduplicate using the idempotency key, so the **user** sees exactly one notification even if the vendor gets two.

### 3.2 Priority Tiering Is a First-Class Concept

Every notification carries a priority:

| Tier | Examples | Behavior under stress |
|---|---|---|
| **P0** — security | 2FA, password reset, account-takeover alert | Never shed. Separate Kafka topic + worker pool. 100% trace sampling. |
| **P1** — transactional | Payment receipt, order shipped, chat | Shed only when P0 lag > SLA. Standard pool. |
| **P2** — marketing | Promotions, re-engagement, digest | First to shed. Paused automatically when any consumer lag > 60 s. |

This is enforced at ingest (separate topics), not as a field checked everywhere downstream — that’s how tiering silently erodes into a mere header.

### 3.3 Bulkheads Between Channels and Tenants

One bad vendor must not stall the others. One flooding tenant must not block another. Implemented via:

- **Per-channel** Kafka topics + dedicated consumer groups + dedicated worker pools.
- **Per-vendor** circuit breakers and concurrency caps.
- **Per-large-tenant** partitions/topics for producers above a traffic threshold.

### 3.4 The Source of Truth Is the Inbox, Not the Vendor

A push to APNs is fire-and-forget from APNs’ point of view; we can’t rely on it for history. Every notification is written to the **inbox store** (Cassandra) before and independent of channel delivery. If every vendor is down, the in-app inbox still reflects reality when the user opens the app.

---

## 4. Back-of-the-Envelope Sizing

| Dimension | Value | Reasoning |
|---|---|---|
| Logical notifications | 10 M / min | = 166,667 / sec |
| Peak (3×) | 500K / sec | Flash sale, world-cup goal, incident broadcast |
| Avg fan-out | 1.5× | Push + in-app is common, many events are single-channel |
| Channel-level sends | **250K/s avg, 750K/s peak** | Drives worker pool and vendor quota |
| Avg payload | 1 KB | Template id + small JSON context |
| Daily logical volume | 14.4 B | 10M × 1440 min |
| Kafka ingress | ~500 MB/s peak | 500K × 1 KB, before replication |
| Kafka on-disk (72 h retention, RF 3) | ~125 TB | 500 MB/s × 86.4 k s × 3 × safety |
| Inbox writes | ~250K/s | Cassandra, LWT disabled, plain insert |
| Inbox storage (90 d, 1 KB/row, RF 3) | ~4 PB logical | Use compression + TTL |
| Redis ops (dedup + rate + presence) | ~1.5 M ops/s peak | Sharded Redis Cluster |

**Cluster sketch** (rough order of magnitude, not provisioning):

- Kafka: **30 brokers**, NVMe, 24 cores / 192 GB each, 10 GbE.
- Ingest API: **200 pods**, HPA 4–8 vCPU each.
- Fan-out + channel workers: **500 pods** across pools.
- Redis Cluster: **60 shards**, 3 replicas each.
- Cassandra: **60 nodes**, i4i.4xlarge-class.
- ClickHouse: **12 nodes** for receipts/analytics.

---

## 5. API Design

### 5.1 Submit Notification (producer → ingest)

```
POST /v1/notifications
Headers:
  Idempotency-Key: <UUIDv7>
  Tenant-Id: <tenant>
  Authorization: Bearer <mTLS or OAuth2>

{
  "user_id": "u_123",
  "priority": "P1",                     // P0 | P1 | P2
  "channels": ["push", "in_app"],       // optional override; preference service wins by default
  "template_id": "order_shipped_v3",
  "locale_override": null,
  "variables": { "order_id": "O-42", "eta": "Tue" },
  "deliver_at": null,                   // ISO-8601 for scheduled; null = now
  "ttl_seconds": 3600,                  // drop if not delivered within TTL
  "dedup_window_seconds": 86400
}
```

Returns `202 Accepted` with `notification_id`. **Never 200** — this is an async pipeline and the response must not lie about delivery.

### 5.2 Batch Submit

```
POST /v1/notifications:batch
```

Up to 1,000 entries, each with its own idempotency key. Batch is the default for marketing producers — a single HTTP round-trip per 1,000 users is the difference between sustaining 500K/s at 50 Gbps vs 5 Gbps of HTTP overhead.

### 5.3 User-Facing (in-app inbox)

```
GET  /v1/users/{user_id}/inbox?cursor=&limit=50
POST /v1/users/{user_id}/inbox/{notification_id}:markRead
POST /v1/users/{user_id}/inbox:markAllRead?before=<ts>
```

Cursor is `(deliver_ts, notification_id)` descending. No offset pagination — it breaks at scale and leaks rows during concurrent writes.

### 5.4 WebSocket (real-time in-app)

```
WS /v1/stream
  Subprotocol: notif.v1
  Auth: short-lived JWT (5 min) obtained via /v1/stream/token
Server pushes:
  { "type": "notification", "payload": { ... } }
  { "type": "read_receipt", "id": "...", "ts": "..." }
Client sends:
  { "type": "ack", "id": "..." }
Heartbeat: ping/pong every 20 s; drop after 2 missed.
```

---

## 6. High-Level Architecture

```
┌──────────────┐    ┌────────────┐    ┌─────────────┐    ┌────────────────┐
│  Producers   │───▶│ API Gateway│───▶│   Ingest    │───▶│ Kafka: raw.PX  │
│  (services)  │    │  (auth,    │    │  (validate, │    │  (per-priority │
└──────────────┘    │   quota)   │    │   enrich)   │    │   topics)      │
                    └────────────┘    └─────────────┘    └────────┬───────┘
                                                                  │
                                                                  ▼
                                                         ┌──────────────────┐
                                                         │ Dedup + Rate     │
                                                         │ (Redis SETNX)    │
                                                         └────────┬─────────┘
                                                                  ▼
                                                         ┌──────────────────┐
                                                         │ Preference +     │
                                                         │ Routing          │◀─── Redis cache ← Postgres
                                                         └────────┬─────────┘
                                                                  ▼
                                                         ┌──────────────────┐
                                                         │ Fan-out Workers  │
                                                         │ 1 event → N msgs │───▶ Inbox DB (Cassandra)
                                                         └────────┬─────────┘
                                          ┌──────────────┬────────┼──────────────┬──────────────┐
                                          ▼              ▼        ▼              ▼              ▼
                                   Kafka: push     Kafka: email  Kafka: sms  Kafka: in_app  Kafka: webhook
                                          │              │        │              │              │
                                          ▼              ▼        ▼              ▼              ▼
                                   Push Workers   Email Workers SMS Workers  WebSocket Hub  Webhook Workers
                                          │              │        │              │              │
                                          ▼              ▼        ▼              ▼              ▼
                                     APNs / FCM    SES/SendGrid  Twilio      User devices    Tenant URLs
                                          │              │        │              │              │
                                          └──────┬───────┴────────┴──────────────┴──────────────┘
                                                 ▼
                                         Kafka: receipts ──▶ ClickHouse (analytics, SLOs)
                                                          └─▶ Retry / DLQ topics
```

---

## 7. Component Deep Dives

### 7.1 Ingest Service

- **Stateless Go/gRPC + HTTP.** HPA on CPU **and** consumer-lag proxy metric from downstream.
- **Validation:** JSON-schema against `template_id` — rejects unknown variables at the edge.
- **Idempotency:** compute `hash(tenant, user, idempotency_key)`, `SETNX` in Redis with the configured TTL. Duplicate → return the original `notification_id` (cached in the same Redis key).
- **Priority routing:** write to one of `raw.P0`, `raw.P1`, `raw.P2`. Topic count stays small; this gives us natural bulkheading at the storage layer.
- **Back-pressure:** reject with `429` if Kafka producer buffer > threshold, if tenant’s token bucket is empty, or if P1/P2 consumer lag exceeds policy (P0 is never rate-limited at this layer).

**Why separate from producers?** A monolithic “just call the SDK from your service” design ties every producer to Kafka uptime. A thin REST/gRPC edge lets us evolve the backbone independently, apply consistent auth, and centralize quota.

### 7.2 Dedup + Rate-Limit Stage

Implemented as the first Kafka consumer, not inline in ingest, for two reasons:

1. Ingest stays **sub-10ms p99** — Redis hot-path is fast but not free, and a Redis hiccup shouldn’t 500 the producer.
2. The **same** dedup logic protects against producer retries *and* against replay from Kafka after a worker crash.

Keys:

- `dedup:{tenant}:{user}:{idem_key}` → `notification_id`, TTL 24 h.
- `rate:{tenant}:{minute}` → counter with `INCR` + `EXPIRE`.
- `rate:{tenant}:{user}:{minute}` → per-user cap (e.g., marketing ≤ 5 / day).

**Sharding:** Redis Cluster hash-slotted on `{tenant}:{user}` (curly braces force co-location) so multi-key ops for a user are on one node.

### 7.3 Preference + Routing Service

- Reads `UserPreference` (source of truth in Postgres, hot cache in Redis with 60 s TTL + pub/sub invalidation).
- Resolves:
  - Channels allowed for this template × this user.
  - Quiet hours (defer until window opens; enqueue to scheduler if beyond 60 s).
  - Locale for template rendering.
  - Device tokens (for push) from the `UserDevice` table.
- **Hot-path cache hit target: > 99%.** Cold hits are acceptable — Postgres is the floor, not the ceiling.

Output is one message per channel, pushed to per-channel Kafka topics partitioned by `user_id`.

### 7.4 Channel Workers

One consumer group per channel, one worker pool per vendor.

- **Push (APNs + FCM):** long-lived HTTP/2 connections, 8,000 in-flight cap per pod, token-bucket backoff on vendor 429. On invalid-token errors, publish to `device.stale` topic; another service marks the device bad in Postgres.
- **Email:** SMTP/HTTP vendors (SES, SendGrid) — multiple vendors active-active behind a **weighted random** router with per-vendor error budgets. One vendor degrades → weight drops to 10% automatically.
- **SMS:** Twilio primary, MessageBird secondary. Regional concurrency caps (e.g., 500/region) to respect carrier limits.
- **WebSocket hub:** see 7.5.
- **Webhook:** per-tenant worker pools keyed on tenant id; one slow webhook endpoint cannot block another tenant’s deliveries.

All workers commit Kafka offsets **after** the vendor returns a terminal status (success, permanent failure, or retry-enqueued). Transient failures re-publish to a delayed topic (see 7.7).

### 7.5 WebSocket / SSE Hub

- Stateless edge, sticky routing via **consistent hashing** on `user_id`. Client connects to any edge; the edge looks up the authoritative hub node from a Ring stored in etcd.
- Presence in Redis (`presence:{user_id}` → `{hub_node, conn_id, expires}`). TTL renewed by heartbeat.
- A fan-out worker publishing to `in_app` topic partitions by `user_id`; the matching hub partition consumer looks up presence. If the user is online, push over the open socket; if offline, skip (the inbox write already happened).
- **Scaling unit**: 1 hub node handles ~50K concurrent sockets. 10 M concurrent users → 200 hub nodes.

### 7.6 Inbox Service (Cassandra)

Schema:

```sql
CREATE TABLE notif_inbox (
  user_id       text,
  bucket        text,              -- yyyy-mm; prevents unbounded partitions
  ts            timeuuid,
  notif_id      text,
  idem_key      text,
  tenant        text,
  template_id   text,
  payload       blob,
  read_at       timestamp,
  PRIMARY KEY ((user_id, bucket), ts)
) WITH CLUSTERING ORDER BY (ts DESC)
  AND default_time_to_live = 7776000;    -- 90 d
```

- Partition key `(user_id, bucket)` bounds partition size (~a few MB/month even for power users).
- Unique enforcement of `idem_key` via **write-time LWT on a dedicated `seen_idem` table** — we only pay the LWT cost once per notification, not on every read.
- Reads are single-partition by `(user_id, current_bucket)`, then prior buckets on “load more”.

**Why Cassandra, not Postgres?** 250K writes/sec sustained, partitioned user traffic, time-series shape — Cassandra is a natural fit. Postgres at this rate requires aggressive sharding that we’d end up reimplementing.

### 7.7 Retries, DLQ, and Scheduled Delivery

**Retry ladder** (transient errors only): `1 s → 5 s → 30 s → 5 m → 30 m`, then DLQ.

Implemented using **per-delay Kafka topics** (`retry.5s`, `retry.30s`, …) and a retry dispatcher that reads from them when the message’s `next_attempt_at` has passed. Simpler and more observable than trying to use a single topic with in-place sleeps.

**Scheduled notifications > 60 s** do not sit in Kafka. They’re written to a dedicated **scheduler store** (Postgres with partitioned table on `deliver_at`, or DynamoDB with a TTL-driven stream). A scheduler worker polls “due” rows every second and re-injects them into the ingest stage. This keeps Kafka hot-path focused on sub-minute traffic.

**DLQ policy:**

- 4xx from vendor (invalid token, malformed payload) → no retry, classified and archived.
- 5xx or timeout → retry ladder.
- DLQ feeds a dashboard with replay tooling; P0 DLQ pages the on-call immediately.

### 7.8 Receipts Pipeline

Vendor callbacks (delivery, open, click, bounce) hit `POST /v1/receipts/{vendor}` endpoints, which write to the `receipts` Kafka topic. A Flink (or Kafka Streams) job aggregates:

- 1-minute rollups into ClickHouse keyed by `(tenant, template, channel, vendor, status)`.
- Per-notification terminal status back into a compacted Kafka topic for audit.

---

## 8. Data Model

### 8.1 Template (Postgres)

```sql
CREATE TABLE template (
  id              text PRIMARY KEY,
  tenant_id       text NOT NULL,
  version         int NOT NULL,
  channels        text[] NOT NULL,          -- allowed channels
  schema          jsonb NOT NULL,           -- JSON-schema for variables
  bodies          jsonb NOT NULL,           -- per-locale per-channel templates
  priority_default text CHECK (priority_default IN ('P0','P1','P2')),
  created_at      timestamptz DEFAULT now()
);
```

Versioning is strictly append-only. A deploy that changes a template creates a new version; producers pin `template_id@version` once rollout is complete.

### 8.2 User Preference (Postgres, cached)

```sql
CREATE TABLE user_preference (
  user_id           text PRIMARY KEY,
  locale            text,
  quiet_hours       jsonb,                  -- per-channel windows in user tz
  channel_opt_in    jsonb,                  -- { push: true, email: false, ... }
  category_opt_in   jsonb,                  -- per-template-category overrides
  updated_at        timestamptz
);
```

### 8.3 User Device (Postgres)

```sql
CREATE TABLE user_device (
  user_id    text,
  device_id  text,
  platform   text,                          -- ios | android | web
  token      text,                          -- APNs / FCM token
  status     text,                          -- active | stale
  last_seen  timestamptz,
  PRIMARY KEY (user_id, device_id)
);
```

### 8.4 Receipts (ClickHouse)

```sql
CREATE TABLE receipts (
  ts              DateTime,
  notif_id        String,
  tenant          LowCardinality(String),
  template_id     LowCardinality(String),
  channel         LowCardinality(String),
  vendor          LowCardinality(String),
  status          LowCardinality(String),   -- sent | delivered | opened | clicked | bounced | failed
  latency_ms      UInt32,
  error_code      LowCardinality(String)
) ENGINE = MergeTree
  PARTITION BY toYYYYMM(ts)
  ORDER BY (tenant, template_id, channel, ts);
```

---

## 9. Trade-Offs (With Rejected Alternatives)

Every decision below has a **why** and a **why-not**. Answers without trade-offs are interview red flags.

### 9.1 Kafka vs RabbitMQ vs SQS vs Pulsar

**Chose Kafka.**

- **Why:** partition-ordered per user, replayable (72 h retention), proven at this scale, mature ecosystem (Connect, Streams, Flink).
- **Why not RabbitMQ:** we need **replay** for recovery and retroactive features (e.g., re-deliver last 24 h after a schema bug). RabbitMQ is queue-shaped, not log-shaped.
- **Why not SQS:** no ordering, 256 KB limit, 14-day max retention, per-message cost at 14 B/day is eye-watering.
- **Why not Pulsar:** technically excellent but smaller ops community; we optimize for the engineer we can hire at 3 AM.

### 9.2 At-Least-Once vs Exactly-Once

**Chose at-least-once + idempotency keys.**

- **Why:** exactly-once across a 3rd-party vendor is impossible; EOS inside Kafka is real but paid for in throughput and operational complexity.
- **Why not:** the consequence is that vendors occasionally get duplicate sends. The inbox collapses them for the user; it’s invisible in the product.

### 9.3 Inbox Store: Cassandra vs DynamoDB vs Postgres-sharded

**Chose Cassandra.**

- **Why:** linear horizontal scale, time-series partition shape, we can operate it on-prem or cloud-neutral.
- **Why not DynamoDB:** excellent fit, but vendor lock-in and cost at 90-day retention × 14 B/day.
- **Why not Postgres-sharded:** we’d end up reinventing Cassandra (hash partitioning, replication, compaction) in application code.
- **Trade-off accepted:** read-your-writes is tunable but not automatic; we rely on local-quorum reads for user-facing inbox.

### 9.4 Fan-Out Timing: At Ingest vs After Preferences

**Chose after preferences.**

- **Why:** avoids producing, storing, and shipping messages that will be dropped because the user is opted out.
- **Why not at ingest:** would give slightly lower end-to-end latency for users who accept all channels, but at 15% opt-out rates we save 75K messages/sec peak.

### 9.5 WebSocket Routing: Consistent Hash vs Broadcast-Filter

**Chose consistent hash on `user_id`.**

- **Why:** O(1) delivery. A 10 M-user broadcast-and-filter design makes every hub node read every message, which is a cache-coherence and bandwidth nightmare.
- **Why not:** rebalancing when we add/remove hub nodes does churn open sockets. We mitigate with **bounded-load consistent hashing** and a hand-off protocol that doesn’t disconnect users during rebalance.

### 9.6 Scheduling: One System vs Tiered

**Chose tiered — Kafka for < 60 s, durable scheduler for > 60 s.**

- **Why:** keeps Kafka hot-path simple and fast. A 30-day delay in Kafka means 30 days of retention and replica storage for a single rarely-hot topic — not worth it.
- **Why not single system:** simpler mental model but worse resource utilization and awkward replay semantics.

### 9.7 Multi-Tenancy: Shared vs Dedicated Topics

**Chose shared topics with per-tenant partitioning for small tenants, dedicated topics for whales.**

- **Why:** most tenants don’t warrant their own topic, but a top-5 customer sending 30% of traffic can saturate a partition if co-tenanted.
- **Operational note:** promote a tenant to a dedicated topic automatically when their 95th-percentile RPS exceeds a threshold for 3 consecutive days.

### 9.8 Push Delivery: Direct vs Provider Aggregator

**Chose direct integration with APNs + FCM.**

- **Why:** aggregators (OneSignal, Airship) add a hop of latency, a hop of failure, and a pricing surprise at 10 M/min.
- **Why not:** we own the vendor integration complexity — token rotation, HTTP/2 connection pooling, APNs’ per-connection priority rules. Acceptable at this scale.

---

## 10. Failure Scenarios

This is where the design actually gets stress-tested. For each, I want to see three things written down: **detection, automatic response, human response.**

### 10.1 Vendor Outage (APNs down for 45 minutes)

**Detection:** per-vendor error rate > 25% for 60 s → circuit breaker half-opens. Datadog monitor pages the on-call after 2 m of sustained elevated error.

**Automatic response:**
- Breaker opens; push worker stops pulling from `push` topic.
- Messages accumulate in Kafka (72 h retention absorbs this — 45 m × 320K/s ≈ 860 M messages, ~860 GB, well within budget).
- **In-app channel is unaffected** — users who open the app still see new notifications.
- TTL-expired messages are tombstoned on re-entry (see 10.6).

**Human response:**
- On-call confirms with APNs status page, opens bridge with vendor TAM.
- If outage persists > 4 h, manually enable **degradation mode**: temporarily route push-eligible messages to email fallback for P0 only.

**Post-mortem fuel:** we log every breaker state change with a `reason` and a `duration`; the monthly reliability review will count these.

### 10.2 Kafka Partition Hot-Spot (one celebrity user)

**Symptom:** one partition’s lag climbs into the millions while peers stay flat. P99 latency for users on that partition blows past SLA.

**Detection:** lag metric per partition, alert on ratio `max_partition_lag / avg_partition_lag > 5`.

**Automatic response:**
- For dedicated-topic tenants: add partitions and reshuffle (requires producer restart to pick up new partitioner).
- For shared topics: the producer salt-key ring (a per-user counter sampled only for very-high-volume users) redistributes the worst offenders across multiple partitions. Ordering is relaxed for these users — documented as an accepted trade-off in preferences.

**Human response:** if a single user is hot enough to matter, they’re likely a celebrity account or a bot. Product review whether this is a legitimate traffic pattern.

### 10.3 Redis Node Loss During Failover

**Symptom:** dedup keys for the failing shard briefly unavailable. Incoming messages with the same idempotency key are treated as new.

**Automatic response:**
- Redis Cluster fails over to replica within ~10 s. During that window we accept **possible duplicate sends**.
- The inbox LWT on `(user_id, idem_key)` catches the duplicate at the inbox layer; the channel workers may still deliver twice, but the inbox UI shows one.

**Why this is acceptable:** we have **defense in depth** — Redis for the fast path, inbox LWT as the floor. Making the fast path strongly consistent would cost p99 latency every day to prevent a harm that surfaces during a ~10 s failover.

### 10.4 Producer Flood / Abuse (one tenant sends 10× their usual traffic)

**Detection:** per-tenant RPS anomaly detector (rolling z-score).

**Automatic response:**
- Token bucket at ingest throttles excess to their provisioned rate. Returns `429` with `Retry-After`.
- If the tenant bypasses back-pressure (e.g., ignoring 429s), a second control **moves their traffic to an isolated Kafka topic** with reduced consumer capacity. Their traffic no longer harms other tenants.

**Human response:** Slack notification to their CSM; if malicious, API key rotation.

### 10.5 Schema Bug in Producer (10% of messages have a bad `template_id`)

**Detection:** ingest validation-rejection rate alert. DLQ volume alert.

**Automatic response:**
- Rejected at ingest with `400`; producer sees the error immediately.
- None of the bad messages enter Kafka, so there’s nothing to replay.

**Human response:** producer team rolls back. If the bug shipped inside the notification service (e.g., template deploy breaks an existing `template_id`), the on-call rolls forward to the previous template version — all templates are versioned and CAS-safe.

### 10.6 TTL Expiry During Backlog Recovery

**Scenario:** after a 6 h APNs outage, the oldest messages in the push topic have exceeded their `ttl_seconds`. Delivering them now is worse than dropping them (a 2FA code from 6 h ago is useless and confusing).

**Automatic response:**
- Push worker checks `now() > created_at + ttl_seconds` at consume time. Expired → publish a `receipts` record with `status=expired`, advance offset, do not call vendor.
- Metrics surface expired counts per tenant per template; product sees a clean record of “we dropped these, here’s why”.

### 10.7 Region Outage (active-passive → active-active migration)

**v1:** we run active-passive. A regional outage fails writes over to standby after manual approval (RPO ~1 min via async Kafka mirror).

**v2 (planned):** active-active with per-region Kafka and **cross-region inbox replication** via Cassandra’s multi-DC. A user’s “home” region is sticky but any region can accept a write; the inbox replicates within seconds. Idempotency keys are globally unique (UUIDv7 with a region prefix) so cross-region duplicates collapse.

**Failure mode to watch:** split-brain on preference updates. We resolve by making preference writes go to a single leader region with synchronous replication, at the cost of a few extra ms on a rarely-written path.

### 10.8 WebSocket Thundering Herd After Hub Restart

**Scenario:** a hub node restart disconnects 50K users. They all reconnect in a 2 s window → login service DDoSes itself.

**Automatic response:**
- Client reconnect uses **full jitter backoff** with a 5–30 s window (not the usual 1–2 s).
- Login service and token minting have their own rate limits; a denied reconnect is retried on the jittered schedule.
- Connection-rebuild traffic is in its own Envoy cluster so it cannot starve ingest traffic.

---

## 11. Observability & SLOs

### 11.1 SLIs

| SLI | Definition | Target |
|---|---|---|
| Ingest success | 2xx / total | ≥ 99.99% |
| P0 end-to-end latency | `vendor_ack_ts - api_accept_ts`, p99 | < 2 s |
| P1 end-to-end latency | same, p99 | < 10 s |
| Delivery success | `delivered / (delivered + failed)` | ≥ 99.9% |
| Inbox read latency | p99 | < 50 ms |
| WebSocket delivery latency | `push_ts - event_ts`, p99 | < 500 ms |

### 11.2 Error Budget Policy

- Each SLI has a 99.9% or 99.95% target → 43 m or 22 m of budget per 30 days.
- When a month burns > 50% budget, **feature work freezes** on that component until budget recovers.
- P0 shares no budget with P1/P2; P2 can burn its own budget without gating P0 work.

### 11.3 Tracing

- OpenTelemetry. `notification_id` is the trace root.
- **1% sampling default**, **100% for P0**, **100% for anything that hits DLQ**.
- Trace spans: `ingest → dedup → preference → fanout → channel_worker → vendor_call → receipt`.

### 11.4 Dashboards

- **Traffic:** RPS in/out per priority, per channel, per tenant (top 20).
- **Lag:** Kafka consumer-group lag heatmap per topic.
- **Errors:** vendor error rate per vendor, DLQ depth per topic.
- **Latency:** end-to-end histograms by priority; vendor call histograms.
- **Fleet:** worker pool utilization, HPA decisions, Redis memory, Cassandra tail latency.

### 11.5 Alerts (examples)

- **Page on:** P0 delivery p99 > 2 s for 5 m · P0 DLQ depth > 0 · Kafka broker down · Cassandra replica count < 3 · any circuit breaker open > 10 m.
- **Ticket on:** tenant 429 rate > baseline · template-version drift between producers.
- **Info on:** marketing traffic shed (expected under load).

---

## 12. Security & Compliance

- **Producer auth:** mTLS preferred for service-to-service, OAuth2 client-credentials for external tenants. All requests require a tenant id that must match the credential.
- **PII:** payload contents are tenant data; we encrypt at rest (Cassandra SSTable encryption, ClickHouse encrypted volumes) and in transit (TLS 1.3 everywhere).
- **Right-to-erasure (GDPR):** a user-deletion event publishes a tombstone; scheduler drops future scheduled notifications, inbox rows fall out via TTL, receipts anonymize `user_id` after 30 days.
- **Per-tenant data isolation:** enforced by `tenant_id` in every key; audit pipeline flags any cross-tenant reads.
- **Vendor keys (APNs p8, Twilio, SES):** stored in Vault, rotated quarterly, worker pods hold short-lived leases.

---

## 13. Rollout Plan

**Phase 1 — single region, push + in-app only.**
- Behind a feature flag. Shadow traffic at 2× target (1 M/min = 20% of 10 M/min) using a replay service that reads from the old system’s audit log.
- Run for 30 days. Error budget must be green.

**Phase 2 — add email.**
- Dual-write with existing email system for 2 weeks; compare vendor receipts.

**Phase 3 — add SMS + webhook.**
- SMS rollout is regional: NA first, then EU, then APAC — each vendor has regional quirks.

**Phase 4 — multi-region active-active.**
- Requires cross-region Cassandra, regional Kafka, prefixed UUIDv7. Ship when single-region peak has held for 30 days.

**Kill switches** (always available):
- Per-channel kill: stop consumers for a channel. Good for vendor incidents.
- Per-tenant kill: isolate a tenant’s traffic. Good for abuse / runaway bugs.
- Priority shed: force-pause P2. Good for widespread degradation.

---

## 14. Interview Prompts This Design Answers

If the interviewer asks follow-ups, be ready for these:

1. *“What if the interviewer raises the number to 100 M/min?”* — Vertical limits: Kafka partitions (can add), Redis shards (linear), Cassandra nodes (linear). The real bottleneck becomes the **vendor rate limits** — APNs and Twilio impose per-account caps. Answer: multi-account pool + regional split + more aggressive batching.

2. *“How do you guarantee ordering for chat notifications?”* — Partition by `conversation_id`, not `user_id`, for chat templates. Accept that per-user ordering across conversations is not guaranteed.

3. *“How do you avoid the ‘ringing phone’ problem when a user has 5 devices?”* — Per-user last-delivery timestamp in Redis; push workers check and collapse if a delivery to any device happened within a short window. This is a product decision and belongs in preferences, not hard-coded.

4. *“Why not just use SNS / Firebase Cloud Messaging for everything?”* — Vendor lock-in, no replay, no per-tenant observability, no priority tiers. Fine for simple apps, wrong for a platform serving many tenants.

5. *“What breaks first as traffic grows?”* — Historically: Redis memory (dedup key cardinality). Then: Kafka partition count per topic. Then: Cassandra compaction. We monitor all three with headroom alerts at 70% and scale-out runbooks for each.

---

## 15. What I Would Build in Week 1

A Staff Engineer doesn’t ship a slide deck. The **MVP shape** is what earns trust:

1. **Ingest API + `raw.P0`/`raw.P1` topics** — minimal auth, minimal validation.
2. **A single fan-out worker** writing to `push` + `in_app` topics.
3. **Push worker** integrated with FCM only (sandbox).
4. **In-app inbox** in Cassandra with single-partition reads.
5. **One dashboard** — ingest RPS, Kafka lag, push success rate.

Nothing about email, SMS, webhooks, scheduling, multi-region, or ML. Those are follow-ups that the MVP will **earn the right to build**.

---

*Document ends. In an interview, be ready to draw sections 6 and 10 on the whiteboard from memory.*
