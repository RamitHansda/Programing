# Email Scheduler & Delivery System — HLD (Staff Engineer)

> Opinionated design for a multi-tenant, recurring email scheduling & delivery platform. Tradeoffs named, hard problems solved explicitly.

---

## 1. Problem Framing & Resolved Assumptions

Before anything else, pinning down the ambiguous parts of the prompt. In a real interview these are negotiated; here are the positions I'd defend.

| Decision | My answer | Why |
|---|---|---|
| SLA: "predictable delivery" | **Dispatch ≤ 60s** from scheduled time (P99); **handed to provider ≤ 5 min** (P99) | Tight enough for "9am newsletter" UX, loose enough that we don't need an in-memory time-wheel |
| Delivery semantics | **At-least-once + per-recipient idempotency key** | Exactly-once is a myth over SMTP; idem key gives the illusion |
| Recurrence DSL | **RFC 5545 RRULE + IANA timezone**, not cron | Cron can't express "last business day of month" or handle DST correctly |
| Audience size | Up to **10M recipients per schedule** | Forces audience-as-definition, not materialized list |
| Timezone | Per-schedule `tz`; per-recipient tz is a **tier-2 feature** (stretch) | Per-recipient explodes row count; most customers want "9am my time (the sender)" |
| Ordering between runs | **No guarantee**. Run N+1 can start before run N completes | Otherwise a single slow run blocks a schedule forever |
| Mutability | Edits take effect for **next** run; in-flight runs are immutable (template version pinned) | Sanity |

## 2. Capacity (napkin math)

- 30M/day → **347/s avg**, **3K/s peak** (10× headroom).
- 5M active schedules, avg fan-out ~6, P99 fan-out 10M.
- Delivery records: ~500 B × 30M = **~15 GB/day**, 30-day hot = **~450 GB**, 1-year cold = **~5.5 TB**.
- Kafka: `delivery.requested` at 3K/s peak × avg 2KB payload = 6 MB/s, trivial. Sized for 10× burst and replay = **256 partitions**.
- Per-tenant P99 rate: 30M/day ÷ 100K ≈ 300/day average, but P99 tenant is ≫ this. Design for **100K/s from a single tenant** during a blast.

## 3. API (Control Plane)

```
POST /v1/schedules
  Headers: Authorization, Idempotency-Key
  Body: {
    templateRef:  { id, version? },          // version pinned at run creation if omitted
    audienceRef:  { type: "list"|"segment", id },
    schedule:     { rrule, timezone, startAt, endAt? },
    delivery:     { priority: "high"|"normal", jitterSec?: 30 },
    tags?: {...}
  }
  → 201 { scheduleId, firstRunAt }

PATCH /v1/schedules/{id}       // pause/resume/edit; affects next run only
GET   /v1/schedules/{id}/runs?status=&since=
GET   /v1/schedules/{id}/runs/{runId}/deliveries?cursor=

POST /v1/audiences             // upload list (returns presigned S3 URL for >1MB)
POST /v1/templates
```

Three things worth calling out:

- **`Idempotency-Key`** on all POSTs — mandatory at 3K/s where retries are guaranteed.
- **Audience is a reference**, never an inline array. Large lists go to S3.
- **Template version is pinned at run-creation time**, so editing a template doesn't mutate in-flight runs.

## 4. Architecture

```
                            ┌──────────────────┐
                            │   API Gateway    │  auth, global RL, routing
                            └────────┬─────────┘
                                     │
        ┌────────────────┬───────────┼────────────────┬────────────────┐
        │                │           │                │                │
  ┌─────▼──────┐  ┌──────▼─────┐ ┌───▼────┐    ┌──────▼─────┐  ┌───────▼──────┐
  │ Schedule   │  │ Template   │ │Audience│    │ Suppress   │  │ Bounce WH    │
  │ Service    │  │ Service    │ │Service │    │ Service    │  │ Receiver     │
  └─────┬──────┘  └────────────┘ └────────┘    └────────────┘  └───────┬──────┘
        │ writes schedules + outbox                                    │
        ▼                                                              │
  ┌───────────────────────────────────────────────┐                    │
  │ Postgres (control plane, sharded by tenant)   │                    │
  │   schedules, schedule_runs (bucketed index),  │                    │
  │   templates, audiences, suppressions          │                    │
  └───────┬───────────────────────────────────────┘                    │
          │                                                            │
  ┌───────▼────────┐    materializes next_run  ┌──────────────────┐    │
  │ Planner (cron) │◄──────────────────────────┤  RRULE engine    │    │
  └───────┬────────┘                           └──────────────────┘    │
          │                                                            │
  ┌───────▼────────┐  claims due rows, emits dispatch events           │
  │  Dispatcher    │  (sharded, stateless, SELECT ... SKIP LOCKED)     │
  │  pool × N      │                                                   │
  └───────┬────────┘                                                   │
          │  Kafka: dispatch.requested (part. by tenant)               │
          ▼                                                            │
  ┌────────────────┐   resolves audience, streams recipients           │
  │ Fan-out Worker │   enforces per-tenant token bucket (Redis)        │
  └───────┬────────┘                                                   │
          │  Kafka: delivery.requested.{high|normal} (256 part.)       │
          ▼                                                            │
  ┌────────────────┐   render → suppression check → idem check         │
  │ Delivery       │   → provider (SES | SendGrid via router +         │
  │ Worker pool    │     circuit breaker) → write delivery record      │
  └───────┬────────┘                                                   │
          │                                                            │
  ┌───────▼───────────────────────┐                                    │
  │ Cassandra / DynamoDB          │◄───────────────────────────────────┘
  │ deliveries                    │   bounce/complaint updates
  │ PK (run_id, user_id)          │
  └───────────────────────────────┘
```

Five planes, each independently scalable: **control, scheduling, fan-out, delivery, ingress**.

## 5. The Hard Problems (and how I'm solving them)

### 5.1 How are due schedules discovered? — Time-bucketed shard index

Naive polling (`WHERE next_run_at <= now()`) dies on a hot index around :00 of every minute. Instead:

- `schedule_runs` carries two extra columns: `bucket_minute = floor(scheduled_at / 60)` and `shard_id = hash(schedule_id) % 256`.
- Composite index on `(bucket_minute, shard_id, status)`.
- **256 Dispatcher instances** (via Kafka consumer-group on a "shard assignment" topic for rebalance). Each owns a subset of shards.
- Each Dispatcher every second does:

```sql
UPDATE schedule_runs
   SET status = 'DISPATCHING', lease_until = now() + '60s', dispatcher_id = $1
 WHERE (bucket_minute, shard_id) IN (...my shards, current minute...)
   AND status = 'SCHEDULED'
   AND jittered_run_at <= now()
 RETURNING ...
FOR UPDATE SKIP LOCKED
LIMIT 500;
```

- Lease + `SKIP LOCKED` gives safe re-claim if a dispatcher dies.

**Why not a time-wheel?** We'd need WAL-backed state and leader election. Not worth it for a 60s SLA.
**Why not Kafka delay topics?** SQS caps at 15min; Kafka delay topics are a hack and lose the ability to cancel/edit.

### 5.2 Thundering herd at cron boundaries — Deterministic jitter

Every tenant schedules "Monday 09:00 UTC." The Planner computes:

```
jittered_run_at = scheduled_at + (hash(schedule_id) mod jitter_window_sec)
```

Default `jitter_window_sec = 30`. Tenant can set 0 for time-critical, or higher for soft schedules. This spreads a millions-strong spike over 30 seconds deterministically and recoverably (hash is stable across retries).

### 5.3 Fan-out to 10M recipients — Streaming, not materialization

Fan-out Worker:

1. Consumes `dispatch.requested { tenant_id, run_id, audience_ref, template_ref }`.
2. Opens a cursor/stream over the audience:
   - static list → range-read S3 object (newline-delimited)
   - segment → paginated query against the customer-data store
3. Batches 100 recipients per Kafka message, produces to `delivery.requested.{priority}`.
4. Enforces per-tenant token bucket (`rate:{tenant_id}` in Redis) so a 10M blast naturally takes ~17 min at 10K/s instead of DoS'ing the delivery tier.
5. Writes progress checkpoint every N batches, so a worker crash resumes from the last checkpoint (idempotent on delivery PK anyway).

We **never** materialize 10M rows in Postgres. Delivery rows are born in Cassandra only when a delivery is attempted.

### 5.4 Multi-tenant fairness — Weighted partitioning + quotas

- `delivery.requested.high` and `delivery.requested.normal` give tier-based priority (trivial).
- Within a priority topic, **partition by `hash(tenant_id)`** so one tenant can't saturate every consumer — their traffic is pinned to a bounded partition set.
- Per-tenant **token bucket** enforced at fan-out *producer* side (cheaper to delay than to drop later).
- **Global circuit breaker per provider** prevents one provider's outage from blocking everyone.

### 5.5 At-least-once + idempotency

- Delivery record PK is `(run_id, user_id)` in Cassandra. Writes are upserts, naturally idempotent.
- Before calling the provider, `SET idem:{run_id}:{user_id} NX EX 604800` in Redis. If it fails, skip.
- If a worker crashes *after* provider send but *before* Redis/Cassandra write: on retry, we'll re-send (duplicate). We accept rare duplicates as the cost of not having distributed transactions over SMTP. Most providers also dedup on a customer-supplied `X-Entity-Ref-ID`.

### 5.6 Bounce & suppression — First-class, not an afterthought

- Providers call `POST /webhooks/{provider}`.
- Hard bounce / complaint → insert into `suppressions(tenant_id, email_hash)` + update a per-tenant **Redis bloom filter** (cheap membership check during send).
- Delivery Worker checks bloom filter **before** calling the provider. False-positive rate ~1% is fine; we fall back to Postgres lookup on filter hit.

### 5.7 Timezones — Delegate to ICU

- Store `rrule` and `timezone` (IANA, e.g. `America/Los_Angeles`).
- Planner uses an RRULE library with a real TZ database to compute the next UTC instant. DST transitions are the library's problem, not ours.
- Stretch: per-recipient timezone. This requires a row per `(run_id, tz_bucket)` and changes the model; I'd build it as a v2 add-on.

### 5.8 Recurrence Lifecycle — Roll-forward, not pre-materialization

This is the recurring-schedule story end-to-end.

#### State model

```
schedules.status:     DRAFT → ACTIVE ⇄ PAUSED → ARCHIVED
schedule_runs.status: SCHEDULED → DISPATCHING → FANNING_OUT → DELIVERING
                                                            → COMPLETED
                                                            → FAILED
                                                            → SKIPPED (missed / concurrency-capped)
                                                            → CANCELLED (edit / pause)
```

**Invariant:** for any `ACTIVE` schedule with `now < end_at`, exactly one `schedule_run` exists in state `SCHEDULED` (the next upcoming occurrence). Past runs are in terminal states. Lazy materialization, horizon = 1.

#### Roll-forward mechanism — transactional, not cron

When a worker flips `schedule_runs.status` to a **terminal** state, the same transaction rolls forward:

```sql
BEGIN;
  UPDATE schedule_runs
     SET status = 'COMPLETED', completed_at = now()
   WHERE id = $1;

  -- compute next occurrence (RRULE lib, handles DST)
  INSERT INTO schedule_runs (
    id, schedule_id, tenant_id,
    scheduled_at, jittered_run_at,
    bucket_minute, shard_id,
    template_version, audience_snapshot_ref,
    status
  ) VALUES (
    gen_random_uuid(), $schedule_id, $tenant_id,
    $next_occurrence_utc, $next_occurrence_utc + jitter($schedule_id, $jitter_sec),
    floor(extract(epoch from $next_occurrence_utc) / 60),
    hash($schedule_id) % 256,
    $template_version, $audience_snapshot_ref,
    'SCHEDULED'
  );

  UPDATE schedules SET next_run_at = $next_occurrence_utc WHERE id = $schedule_id;
COMMIT;
```

If the worker crashes between "run terminates" and "next run inserted," a **Planner reconciliation job** (every 1 min) catches it:

```sql
SELECT s.id FROM schedules s
 WHERE s.status = 'ACTIVE'
   AND (s.end_at IS NULL OR s.end_at > now())
   AND NOT EXISTS (
     SELECT 1 FROM schedule_runs r
      WHERE r.schedule_id = s.id AND r.status = 'SCHEDULED'
   );
-- for each orphan: compute next occurrence, insert SCHEDULED row
```

Two-layer safety: **fast path is txn-local, slow path is reconciliation.**

#### Why not pre-materialize the future?

- RRULE can be "every minute forever" — **unbounded**.
- Pre-materializing N runs ahead multiplies DB rows by N and complicates edits (you'd have to cancel and re-compute all N on every change).
- Horizon = 1 is correct for correctness; a small UI-layer **cache** of the next ~7 occurrences is fine for "upcoming runs" queries, but it's a read-through cache, not the source of truth.

#### Missed-run policy — per-schedule knob

Planner was down, Monday 9am didn't fire. Policy is **per-schedule**, not global:

| Policy | Behavior | Use case |
|---|---|---|
| `skip` (default) | Mark as `SKIPPED`, roll forward to next | Newsletters, marketing — no one wants two |
| `fire_once` | Dispatch now, flagged `late=true` | Transactional digests, compliance reports |
| `fire_all` | Replay every missed occurrence | Rare; financial close, audit logs |

Implemented at the Dispatcher: when it claims a SCHEDULED row with `now - jittered_run_at > grace_window` (default 10 min), it consults `schedules.missed_run_policy` and either dispatches, marks SKIPPED + rolls forward, or generates back-dated runs.

#### DST edge cases — pick a policy and document it

Two cases the RRULE lib can't decide for us:

| Case | Example | Our policy |
|---|---|---|
| **Skipped time** (spring-forward) | "2:30am LA" on March DST — doesn't exist | Advance to first valid instant (3:00am local) |
| **Duplicated time** (fall-back) | "1:30am LA" on November DST — happens twice | Fire at the **first** (pre-transition) occurrence only |

These are the right defaults for most recurring-email use cases. We expose no knob; it's documented behavior.

#### Per-schedule concurrency cap

Pathological: schedule is "every minute," but fan-out for a 10M audience takes 5 minutes. Without a cap, 5 runs stack up per schedule.

- Add `max_concurrent_runs` to `schedules` (default: **1**).
- Dispatcher, before claiming a SCHEDULED row, checks:

  ```sql
  SELECT count(*) FROM schedule_runs
   WHERE schedule_id = $1
     AND status IN ('DISPATCHING','FANNING_OUT','DELIVERING');
  ```

- If `>= max_concurrent_runs`: the new row is marked `SKIPPED` (with `reason = 'concurrency_cap'`) and roll-forward proceeds.

#### Edit / pause / resume semantics on live recurrences

| Action | Run currently SCHEDULED (not started) | Run currently in-flight |
|---|---|---|
| Edit RRULE / template / audience | Cancel SCHEDULED row; insert new one with next occurrence under new rules | In-flight run is **immutable** (template version pinned). Edit applies to next roll-forward. |
| Pause | Cancel SCHEDULED row; `schedules.status = PAUSED` | Let it complete; no new roll-forward until resume |
| Resume | Compute `nextOccurrence(rrule, tz, after=max(now, start_at))`; insert SCHEDULED row | — |
| Reach `end_at` | Do not roll forward after current run terminates; `status = ARCHIVED` | — |
| Delete | Cancel SCHEDULED row; `status = ARCHIVED` (soft). Keep `schedule_runs` history for audit | Let it complete |

All of these are single transactions against `schedules` + `schedule_runs`.

#### Schema additions for recurrence

```sql
ALTER TABLE schedules
  ADD COLUMN missed_run_policy text    NOT NULL DEFAULT 'skip',   -- skip|fire_once|fire_all
  ADD COLUMN grace_window_sec   int    NOT NULL DEFAULT 600,      -- 10 min
  ADD COLUMN max_concurrent_runs int   NOT NULL DEFAULT 1;

ALTER TABLE schedule_runs
  ADD COLUMN skip_reason text,          -- 'concurrency_cap' | 'missed_window' | 'paused' | null
  ADD COLUMN is_late boolean NOT NULL DEFAULT false;
```

#### Observability for recurring schedules

- `schedule_run_interval_drift` = `scheduled_at(run_N+1) - scheduled_at(run_N) - expected_interval`. Non-zero on DST days (expected) or when reconciliation kicked in (investigate).
- `skip_rate_per_schedule` — alert if a tenant's schedule is silently skipping due to concurrency cap.
- Per-schedule "last 30 runs" UI query — trivial from `schedule_runs` since it's the instance log.

## 6. Database Ownership

### The principle: database-per-bounded-context, not database-per-microservice

Strict database-per-microservice is dogma. What matters is the **bounded context**. Two services that need atomic writes to a shared aggregate belong on the same DB; two services that exchange identifiers belong on separate DBs.

For this system:

| Bounded context | Services in context | DB | Why grouped (or split) |
|---|---|---|---|
| **Scheduling kernel** | Schedule Service, Planner, Dispatcher | **`scheduling-pg`** (Postgres) | Roll-forward (§5.8) requires atomic txn over `schedules` + `schedule_runs`. Splitting forces a distributed transaction on every run completion — unacceptable. |
| **Templates** | Template Service | **`templates-pg`** (Postgres) + S3 (content blobs) | Different access pattern (read-mostly, blob-backed), versioned independently. Schedule references by `(template_id, version)`. |
| **Audiences** | Audience Service | **`audiences-pg`** (Postgres) + S3 (list snapshots) + federated reads to customer-data warehouse for segments | Segment queries may federate to a separate analytical DB (e.g. Snowflake / Redshift); we don't want that latency on the scheduling-kernel cluster. |
| **Suppressions / compliance** | Suppression Service, Bounce Webhook Receiver | **`suppression-pg`** (Postgres) + Redis bloom filter | Hot path is the bloom filter (Redis); Postgres is source of truth. Independent retention policy (GDPR right-to-be-forgotten). |
| **Delivery records** | Delivery Worker | **`deliveries-cassandra`** | 30M writes/day, append-mostly, key-only reads — wrong shape for Postgres. |
| **Identity / multi-tenancy** | Tenant Service (cross-cutting) | **`identity-pg`** (Postgres) | Owns tenants, API keys, quotas, tier. Every other service holds a `tenant_id` reference; no foreign key. |

**Cross-service references are by ID, never foreign key.** A `schedule_runs` row stores `template_id + template_version` and `audience_id`; resolution happens via the owning service's API. Values that must not change for the lifetime of the run (template version, audience snapshot ref) are **denormalized into the run row at creation time** so the in-flight run is self-contained.

### Shared cluster vs. separate clusters

Pragmatic position: **same Postgres cluster, separate logical databases**, until growth forces a peel-off.

```
Postgres cluster "control":
  ├── scheduling-pg     (Schedule Service / Planner / Dispatcher)
  ├── templates-pg      (Template Service)
  ├── audiences-pg      (Audience Service)
  ├── suppression-pg    (Suppression Service)
  └── identity-pg       (Tenant Service)

Cassandra cluster "deliveries":
  └── deliveries        (Delivery Worker)

Redis cluster "hot":
  ├── idem:{run}:{user}             (Delivery Worker)
  ├── rate:{tenant}                 (Fan-out Worker)
  ├── suppress:bf:{tenant}          (Suppression Service writes; Delivery reads)
  └── run:{run}:counters            (Delivery Worker)

S3 buckets:
  ├── templates/                    (Template Service)
  └── audiences/                    (Audience Service)

Kafka cluster "events":
  ├── dispatch.requested            (Dispatcher → Fan-out)
  ├── delivery.requested.high       (Fan-out → Delivery)
  ├── delivery.requested.normal     (Fan-out → Delivery)
  ├── delivery.outcome              (Delivery → analytics)
  ├── bounce.received               (Bounce WH → Suppression)
  └── *.dlq                         (DLQs per topic)
```

Why not separate Postgres clusters from day one? Operational tax: 5× more failover drills, 5× more tuning, 5× more on-call surface. Logical-database isolation gives us the schema-autonomy benefit (each service runs its own migrations, owns its own users/roles) without the ops cost. **Peel off when you must, not when you can.** Triggers to peel:
- One service's I/O dominates the cluster
- One service needs a different Postgres version
- Compliance requires physical isolation (e.g., suppression in EU-only)

### Cross-service consistency — outbox + event-driven

The system never relies on a 2-phase commit. Two patterns instead:

1. **Transactional outbox** (Schedule Service → Kafka): the API write to `schedules` and the publish-intent to Kafka happen in the **same Postgres transaction** by inserting into an `outbox` table. A separate publisher process tails the outbox and writes to Kafka with at-least-once semantics. This is how `schedule.created` and `schedule.updated` events reach downstream consumers (analytics, audit) reliably without distributed transactions.

2. **Reference-and-resolve** (Schedule → Template / Audience): Schedule Service calls Template Service synchronously at run-creation time, retrieves `template_version` + `content_ref`, **denormalizes** them into the `schedule_run` row. The run never re-reads templates after that. Eliminates a class of "what if the template changed during fan-out" bugs.

### Ownership matrix (who writes what)

| Table / store | Write owner | Read by | Notes |
|---|---|---|---|
| `schedules` | Schedule Service | Schedule, Planner, Dispatcher | Edits go through the API only |
| `schedule_runs` | Schedule Svc (insert), Planner (insert), Dispatcher (status), Workers (status) | All scheduling-kernel components | Roll-forward txn writes here too |
| `outbox` (in scheduling-pg) | Schedule Service, Planner | Outbox publisher → Kafka | Cleared after publish |
| `templates` | Template Service | Schedule, Delivery (via API) | Versioned, immutable per version |
| `audiences` | Audience Service | Schedule, Fan-out (via API) | Static lists snapshot to S3 at run time |
| `suppressions` | Suppression Service (from Bounce WH + manual) | Delivery (via Redis bloom + PG fallback) | Per-tenant, GDPR-relevant |
| `deliveries` (Cassandra) | Delivery Worker | Delivery (retries), API (read-only views) | Idempotent upserts |
| `tenants` (identity) | Tenant Service | All services (cached at API gateway) | API-key + quota source of truth |

### What we deliberately do NOT do

- **No shared schema across services in the same cluster.** Each service has its own logical DB, its own migration tool, its own connection pool, its own user. Cross-service `JOIN` is a code review failure.
- **No cross-service foreign keys.** Always reference by ID. The owning service is the authority.
- **No two-phase commit.** Outbox + at-least-once + idempotency wins every time at this scale.
- **No "shared cache" for business data.** Redis namespaces per use case; never a free-for-all of arbitrary keys.

## 7. Data Model

### Postgres (control plane, sharded by `tenant_id`)

```sql
schedules (
  id uuid pk, tenant_id uuid, template_id uuid, template_version int,
  audience_id uuid, rrule text, timezone text,
  start_at timestamptz, end_at timestamptz,
  priority text, jitter_sec int,
  status text,                       -- ACTIVE | PAUSED | ARCHIVED
  next_run_at timestamptz,           -- for planner
  version bigint,                    -- optimistic concurrency
  created_at, updated_at
);

schedule_runs (
  id uuid pk, schedule_id uuid, tenant_id uuid,
  scheduled_at timestamptz,
  jittered_run_at timestamptz,
  bucket_minute bigint,              -- floor(scheduled_at / 60)
  shard_id smallint,                 -- hash(schedule_id) % 256
  template_version int,              -- pinned at creation
  audience_snapshot_ref text,        -- for static lists, S3 key at time of run
  status text,                       -- SCHEDULED | DISPATCHING | FANNING_OUT
                                     -- | DELIVERING | COMPLETED | FAILED
  lease_until timestamptz, dispatcher_id text,
  attempts int, last_error text,
  created_at, completed_at
);
CREATE INDEX ON schedule_runs (bucket_minute, shard_id, status)
  WHERE status IN ('SCHEDULED','DISPATCHING');

templates   (id, tenant_id, version, content_ref, created_at, PK(id,version))
audiences   (id, tenant_id, type, ref, estimated_size, created_at)
suppressions(tenant_id, email_hash, reason, created_at, PK(tenant_id,email_hash))
```

### Cassandra (delivery records, write-heavy, append-mostly)

```
deliveries (
  schedule_run_id uuid,
  user_id uuid,
  email text,
  status text,          -- QUEUED|SENT|DELIVERED|BOUNCED|FAILED|SUPPRESSED
  provider text, provider_message_id text,
  attempts int, last_error text,
  queued_at, sent_at, delivered_at,
  PRIMARY KEY ((schedule_run_id), user_id)
);
-- Secondary by (tenant_id, sent_at) via materialized view for per-tenant queries.
```

### Redis

- `idem:{run_id}:{user_id}` (TTL 7d)
- `rate:{tenant_id}` token bucket
- `suppress:bf:{tenant_id}` bloom filter
- `run:{run_id}:counters` (sent / failed / suppressed)

### Kafka

- `dispatch.requested` — 64 partitions, key = tenant_id
- `delivery.requested.high` / `.normal` — 256 partitions each, key = tenant_id
- `delivery.outcome` — for analytics consumers
- `bounce.received` — from webhook ingress

## 8. Failure Modes

| Failure | Detection | Recovery |
|---|---|---|
| Dispatcher crash mid-claim | `lease_until` expires | Another dispatcher re-claims via SKIP LOCKED |
| Fan-out worker crash | Kafka consumer offset unadvanced | Re-consume from last commit; checkpoint skips done batches |
| Delivery worker crash post-send, pre-write | Kafka redeliver | Retry → provider may dedup on `X-Entity-Ref-ID`; else rare dup (accepted) |
| Provider outage | Circuit breaker (error-rate threshold) | Failover to secondary provider; if both down, messages park in Kafka |
| Postgres primary loss | Health probe | Failover replica (RPO ~seconds); new schedules rejected briefly; running fan-out continues from Kafka |
| Redis outage | Connection errors | Idem check falls back to Cassandra PK uniqueness; bloom filter falls back to PG query (slower) |
| Kafka outage | Producer errors | Schedule service outbox keeps rows; dispatcher retries publish; no data loss |
| Clock skew on dispatchers | NTP monitoring | Lease-based claim tolerates ±few seconds; larger skew alerts |

## 9. Observability

**SLIs (per tenant + global):**

- `dispatch_latency = dispatched_at - scheduled_at` (target P99 < 60s)
- `handoff_latency  = sent_at - scheduled_at`     (target P99 < 5min)
- `delivery_success_rate`                         (target > 99.5%)
- `suppression_rate`, `bounce_rate`               (health of tenant's list)
- `queue_depth` per Kafka topic, per tenant

**Alerts:** dispatch P99 > 60s for 5min; any tenant delivery success < 99% for 10min; circuit breaker open > 2min; Postgres replica lag > 10s.

**Tracing:** single trace ID from API POST → schedule_run → each delivery message, attached to Kafka headers.

**Audit log:** every schedule/run state transition + who/what triggered it. Indispensable for "why didn't my email send?"

## 10. Rollout & Ops Concerns

- Per-tenant **kill switch** via feature flag (refuse to fan-out for tenant X).
- Per-tenant **send cap** (daily & per-second) enforced at API + fan-out.
- **Shadow mode** for new delivery workers: consume, render, but don't send, compare against prod.
- **DLQ + replay tool:** every terminal-failed message lands in `delivery.dlq` with enough context to re-inject.
- Template preview + test-send-to-me endpoint (catches 90% of customer self-inflicted issues).
- Compliance: unsubscribe link injection at render time, SPF/DKIM/DMARC configured per-tenant sending domain, GDPR data-residency via region-pinned deployments.

## 11. What I'm Deliberately NOT Building

Staff engineers say no.

- **Real-time analytics / campaign dashboards** — consume `delivery.outcome` into a separate OLAP system. Not this team's problem.
- **A/B testing / smart send-time** — layers on top, not core.
- **In-memory hierarchical time-wheel** — overkill for a 60s SLA. Revisit if SLA tightens to <5s.
- **Per-recipient local timezone** — deferred to v2.
- **Exactly-once delivery** — impossible over SMTP; don't oversell it.

## 12. Why This Design Scales to 30M/day and Beyond

- **Every plane is horizontally scalable.** Control plane = stateless API + sharded Postgres. Dispatcher, fan-out, delivery = Kafka consumer groups.
- **No single hot table scan.** Bucket + shard index keeps each dispatcher reading a narrow slice.
- **No synchronous fan-out.** Audience streams directly to Kafka; Postgres never holds 10M recipient rows.
- **Multi-tenant fair by construction.** Partitioning by `tenant_id` + per-tenant quotas prevents blast radius.
- **Back-pressure is natural.** Slow downstream → Kafka grows → consumers slow → fan-out rate-limits → tenants feel backpressure at the API (429) instead of the system melting.
- **10× headroom** on partition count and worker count is cheap today.

---

## Appendix A — Whiteboard Focus

If doing this on a whiteboard, draw §4 first, then spend 15 minutes on **§5.1 (dispatch)** and **§5.3 (fan-out)**. Those are the parts that distinguish a scalable design from one that will page you at 9:00:00 every Monday.

## Appendix B — Common Gaps in Mid-Level Answers

1. Using cron instead of RRULE+TZ (breaks on DST and "last business day").
2. Materializing audience rows in Postgres (dies at 10M recipients).
3. Polling `next_run_at <= now()` on a single table (hot index, thundering herd).
4. No per-tenant rate limiting (one customer's 100M blast starves everyone).
5. Treating bounces/suppressions as "later" (spam complaints kill sender reputation fast).
6. Claiming exactly-once delivery (you can't).
7. No idempotency key on API POST (duplicate schedules from retries).
8. Missing template version pinning (edits corrupt in-flight runs).
