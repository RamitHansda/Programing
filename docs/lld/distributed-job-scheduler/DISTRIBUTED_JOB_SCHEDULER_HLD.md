# Distributed Job Scheduler — High-Level Design

**Scope:** Multi-tenant platform that accepts one-shot, delayed, and recurring jobs, fires them at the right time, and executes them via a horizontally scaled worker fleet  
**Scale Target:** **10,000 job dispatches/second** sustained (peak ~30k/sec)  
**Key Concerns:** Timer accuracy at scale, hot-partition avoidance, at-least-once execution with leases, noisy-tenant isolation, cron fan-out storms

---

## Table of Contents

1. [Problem Statement & Requirements](#1-problem-statement--requirements)
2. [Back-of-Envelope Estimation](#2-back-of-envelope-estimation)
3. [High-Level Architecture](#3-high-level-architecture)
4. [Component Deep Dive](#4-component-deep-dive)
5. [Scheduling Primitives (Immediate / Delay / Cron)](#5-scheduling-primitives-immediate--delay--cron)
6. [Execution Semantics & Leases](#6-execution-semantics--leases)
7. [Data Models](#7-data-models)
8. [Bottleneck Analysis & Mitigations](#8-bottleneck-analysis--mitigations)
9. [Scaling Strategy](#9-scaling-strategy)
10. [Trade-offs & Alternatives](#10-trade-offs--alternatives)
11. [Interview Cheat Sheet](#11-interview-cheat-sheet)

---

## 1. Problem Statement & Requirements

### What we are building

A **distributed job scheduler** (think Celery Beat + workers, Sidekiq, AWS EventBridge Scheduler + SQS workers, or a lighter Temporal for short tasks) that:

1. Accepts job definitions and run requests from many tenants/services
2. Holds jobs until `run_at` (or cron next-fire time)
3. Dispatches runnable work to workers
4. Tracks success / failure / retry / cancel
5. Survives broker, worker, and scheduler process failures without silent drops

This is **not** a full workflow orchestrator (Temporal/Airflow). Jobs are **single units of work** (HTTP callback, lambda invoke, queue publish, script). Multi-step DAGs are out of scope unless composed by callers.

### Functional Requirements

- **Submit** immediate, delayed (`run_at` / `delay_ms`), and recurring (cron / interval) jobs
- **Cancel** a pending job or pause a schedule
- **Query** job status and recent run history
- **Priority** classes (e.g. critical / default / bulk)
- **Retries** with exponential backoff + jitter; max attempts; DLQ
- **Idempotency** on submit (`idempotency_key`) and on worker execution (`run_id`)
- **Per-tenant quotas**: submit rate, concurrency, schedule count
- **Observability**: queue age, timer lag, dispatch rate, lease expirations, DLQ depth

### Non-Functional Requirements

| Property | Target |
|----------|--------|
| Dispatch throughput | **10k jobs/sec** sustained; design for **30k/sec** peak |
| Timer accuracy | p99 fire within **±1s** of `run_at` under normal load; ±5s under recovery |
| Submit latency | p99 **< 50ms** to durable accept (`202` / job id) |
| Availability | 99.9% control plane; workers can lag without losing accepted jobs |
| Durability | Zero silent drops after successful submit ack |
| Semantics | **At-least-once** dispatch; **exactly-once state transition** via CAS/leases |
| Tenant isolation | One hot tenant must not starve others |

### Out of Scope

- Arbitrary multi-step workflows / Saga compensation (use Temporal)
- Running untrusted user containers (use CI/agent platform)
- Exactly-once side effects at external systems (callers must be idempotent)
- Sub-millisecond timer precision (use in-process wheels / local schedulers)

---

## 2. Back-of-Envelope Estimation

```
Target sustained dispatch:     10,000 jobs/sec
Peak (3×):                     30,000 jobs/sec
Jobs/day at sustained:         10k × 86,400 ≈ 864 million/day

Mix assumption (typical SaaS):
  Immediate (run_now):         40%  →  4,000/sec
  Delayed (seconds–hours):     40%  →  4,000/sec
  Cron/recurring fires:        20%  →  2,000/sec

Avg job payload metadata:      ~512 B (id, tenant, type, payload ref, headers)
Kafka ready-topic ingress:     10k × 512 B ≈ 5 MB/sec  (~430 GB/day raw)
  with compression (~3×):      ~140 GB/day

Metadata writes (hot path):
  submit + enqueue intent:     ~10k writes/sec
  lease claim + complete:      ~20k writes/sec
  Total hot metadata ops:      ~30k writes/sec  →  must shard; single Postgres primary fails

In-flight concurrency:
  avg job duration 2s → 10k/sec × 2s = 20,000 concurrent leases
  p99 duration 30s spike → plan for 100k+ concurrent leases

Timer wheel / delay store:
  jobs delayed up to 7 days: if 40% delayed and uniform over 7d
  backlog ≈ 0.4 × 864M × 7 ≈ 2.4B rows  →  too large for naive "all in Redis"
  → bucket by fire time; keep only near-term buckets hot in Redis/Kafka

Cron registry:
  1M active schedules; each fires avg 2×/day → 2M fires/day ≈ 23/sec
  BUT: "every minute" crons at midnight (fan-out storm) can spike to millions/min
  → staggered next_run_at + per-schedule shard + fire jitter
```

**Design implication:** The hard problem is **not** Kafka throughput at 10k/sec. The hard problems are **(1)** scalable timers without `SELECT … WHERE run_at <= now()` scans, **(2)** metadata write throughput, and **(3)** fairness under cron fan-out.

---

## 3. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Producers (services, APIs, internal platforms)                         │
└───────────────────────────────┬─────────────────────────────────────────┘
                                │ REST / gRPC + auth + idempotency key
                                ▼
                 ┌──────────────────────────────┐
                 │         API Gateway          │  rate limit, auth, schema
                 └──────────────┬───────────────┘
                                ▼
                 ┌──────────────────────────────┐
                 │      Job Control Service     │
                 │  validate → durable write    │
                 │  → outbox / classify path    │
                 └──────┬───────────┬───────────┘
                        │           │
           immediate    │           │ delayed / cron
                        ▼           ▼
        ┌───────────────────┐   ┌──────────────────────────┐
        │  Ready Queues     │   │  Timer / Schedule Plane  │
        │  Kafka topics     │◄──│  delay buckets + cron    │
        │  by priority      │   │  scanners (sharded)      │
        └─────────┬─────────┘   └──────────────────────────┘
                  │
                  ▼
        ┌───────────────────┐   ┌──────────────────────────┐
        │  Dispatcher /     │   │  Job Metadata Store      │
        │  Lease Manager    │──►│  sharded by tenant/job   │
        └─────────┬─────────┘   └──────────────────────────┘
                  │ push or pull lease
                  ▼
        ┌───────────────────┐
        │  Worker Fleet     │  execute handler; heartbeat; ack/fail
        └─────────┬─────────┘
                  │ results
                  ▼
        ┌───────────────────┐   ┌──────────────────────────┐
        │  Result Sink      │──►│  History / Metrics / DLQ │
        └───────────────────┘   └──────────────────────────┘
```

### Core principle

> **Metadata store is source of truth. Queues are delivery. Workers are ephemeral.**  
> Duplicate messages are expected. Correctness = durable accept + CAS state + expiring leases.

### Two planes

| Plane | Owns | Scale axis |
|-------|------|------------|
| **Control** | Submit, cancel, schedules, status, quotas, outbox | Stateless API replicas + sharded metadata |
| **Dispatch** | Timer scanners, ready queues, leases, workers | Kafka partitions + worker HPA + timer shards |

---

## 4. Component Deep Dive

### 4.1 Job Control Service (API)

- `POST /jobs` — create one-shot or delayed job
- `POST /schedules` — create cron/interval schedule
- `POST /jobs/{id}/cancel`, `POST /schedules/{id}/pause`
- `GET /jobs/{id}`, `GET /runs/{run_id}`

**Hot path for submit:**

1. Auth + tenant rate limit (Redis token bucket)
2. Validate payload size / handler type / delay window (e.g. max 30 days)
3. Dedup on `(tenant_id, idempotency_key)` → return existing job id if hit
4. **Write job row + outbox row in one transaction** (or DynamoDB TransactWrite)
5. Return `202 Accepted` with `job_id` / `run_id`
6. Async publisher drains outbox → Kafka (immediate) or Timer plane (delayed)

Do **not** block the API on Kafka produce if you need strong durability under broker blips — use transactional outbox. At 10k/sec, outbox drainers must be sharded with the metadata store.

### 4.2 Job Metadata Store

**Requirement:** ~30k writes/sec + range lookups by tenant and by `run_at` shards.

**Recommended:**

| Store | Role |
|-------|------|
| **DynamoDB / Cassandra / Cockroach** | Job + run + lease records (high write, key-value access) |
| **Postgres (sharded)** | Schedule definitions, tenant config, quotas (lower churn) |
| **Redis Cluster** | Near-term delay buckets, lease heartbeats, rate limits |
| **Object store** | Large payloads (> few KB); metadata holds `payload_ref` |

Partition / shard key: `tenant_id` (fairness) or `hash(job_id)` (even load). Prefer **tenant + hash** composite so one mega-tenant can be split across shards.

### 4.3 Timer / Schedule Plane

Two sub-systems:

1. **Delay buckets** — one-shot jobs with `run_at` in the future  
2. **Cron scanner** — active schedules; compute `next_run_at`; emit run instances

See [§5](#5-scheduling-primitives-immediate--delay--cron).

### 4.4 Ready Queues (Kafka)

Topics by priority (isolation of SLAs):

| Topic | Use |
|-------|-----|
| `jobs.ready.critical` | Payments, auth, user-facing |
| `jobs.ready.default` | Normal product work |
| `jobs.ready.bulk` | Reports, backfills, analytics |

Partition key: `hash(tenant_id)` or `hash(tenant_id, job_type)` — spreads load while keeping a tenant mostly ordered.

**Partition count:** start at **64–128** so consumer parallelism can grow to tens of thousands of jobs/sec without rebalancing pain.

Message body (small):

```json
{
  "run_id": "r_...",
  "job_id": "j_...",
  "tenant_id": "t_...",
  "priority": "default",
  "attempt": 1,
  "handler": "http.callback",
  "payload_ref": "s3://...",
  "lease_timeout_ms": 30000,
  "enqueued_at": 1710000000123
}
```

### 4.5 Dispatcher / Lease Manager

Workers should not “own” a job forever from a Kafka offset alone.

Flow:

1. Consumer reads ready message
2. **CAS** run state `READY → LEASED` with `lease_owner`, `lease_expires_at`
3. If CAS fails (already leased/done) → ack Kafka (idempotent skip)
4. Hand work to worker (same process or push to worker pool)
5. Worker heartbeats → extend lease
6. On success: CAS `LEASED → SUCCEEDED`; ack Kafka
7. On fail: CAS to `RETRY_WAIT` or `FAILED` / DLQ; schedule retry via Timer plane
8. Background **lease reaper**: expired leases → re-enqueue to ready queue (at-least-once)

### 4.6 Worker Fleet

- Stateless pods; HPA on Kafka consumer lag + CPU
- Handlers: HTTP callback, internal gRPC, queue publish, script adapter
- Hard timeouts per attempt; kill/cancel on lease loss
- Emit structured result events to `jobs.results`

### 4.7 Result Sink & History

- Consume `jobs.results`; batch-write history (Cassandra / DynamoDB)
- Metrics: success rate, latency, retries, DLQ
- UI/API reads history from store, not from Kafka

---

## 5. Scheduling Primitives (Immediate / Delay / Cron)

### 5.1 Immediate jobs

API → outbox → `jobs.ready.*` directly. No timer involvement.

### 5.2 Delayed jobs — hierarchical time buckets (not DB polling)

**Anti-pattern at 10k/sec:**

```sql
SELECT * FROM jobs WHERE status='SCHEDULED' AND run_at <= now()
ORDER BY run_at LIMIT 1000;  -- full table / index hot spot; cannot shard cleanly
```

**Pattern: hierarchical delay buckets**

```
Far future (> 1 hour):   store only in metadata DB with run_at index per shard
Near term (≤ 1 hour):    promote into Redis ZSET or per-second Kafka delay topics
Due now:                 Timer scanner ZPOP / consume → jobs.ready.*
```

Concrete Redis design (per timer shard `S`):

```
Key: delay:{shard}:{yyyyMMddHHmm}     # minute bucket
Type: ZSET  score = run_at_ms, member = run_id

Promotion:
  - Far→near: every minute, each shard scans its DB partition for
    run_at in (now, now+1h] and saddles into minute ZSETs
  - Near→ready: every 100–250ms, ZRANGEBYSCORE 0..now → publish ready → ZREM
```

**Why this scales:**

- Work is **sharded** (`hash(run_id) % N` timer pods)
- Hot set in Redis is **bounded** (~1 hour of delayed traffic)
- No global `ORDER BY run_at` across the fleet

**Kafka alternative:** “delay topics” per coarse bucket (`delay.1s`, `delay.5s`, `delay.1m`, …) with consumer sleep — simpler ops, coarser accuracy. Fine for ±1s SLO.

### 5.3 Cron / recurring schedules

```
Schedule row:  cron_expr, timezone, handler, payload_ref, next_run_at, shard_id, paused
```

**Scanner loop (per shard):**

1. Claim due schedules: `next_run_at <= now` via CAS / lease on schedule row
2. Create **run instance** (idempotent key = `schedule_id + planned_fire_time`)
3. Enqueue run to ready queue (or delay bucket if jitter applied)
4. Advance `next_run_at = cron_next(expr, tz, planned_fire_time)`
5. Persist in same transaction / TransactWrite

**Cron storm mitigations (midnight / top-of-minute):**

- Store `next_run_at` already staggered with **deterministic jitter** (`hash(schedule_id) % 60s`)
- Cap fires per scanner tick; spill remainder to next tick
- Separate topic `jobs.ready.bulk` for low-priority cron
- Per-tenant schedule concurrency + global fire rate limiter

**Idempotency:** if scanner crashes after enqueue but before advancing `next_run_at`, restart re-creates the same `run_id` key → safe.

---

## 6. Execution Semantics & Leases

### State machine (run)

```
SCHEDULED → READY → LEASED → SUCCEEDED
                     │
                     ├→ RETRY_WAIT → READY → …
                     ├→ FAILED (terminal)
                     ├→ CANCELLED
                     └→ DLQ
```

### Guarantees

| Guarantee | How |
|-----------|-----|
| No silent drop after ack | Durable metadata + outbox before `202` |
| No double-success bookkeeping | CAS on terminal states |
| Crash during execution | Lease expiry → requeue (duplicate possible) |
| Worker handler safety | Require idempotent handlers keyed by `run_id` |
| Cancel while queued | Mark `CANCELLED`; workers check before execute |
| Cancel while leased | Best-effort cancel signal; lease may finish — report superseded |

### Retry policy (default)

```
attempts: 1 + 5 retries
backoff:  min(2^attempt * 1s, 15m) + full_jitter
after max: DLQ topic jobs.dlq + alarm
```

Retries go through Timer plane (`RETRY_WAIT`), not tight consumer loops — prevents retry storms.

---

## 7. Data Models

### Job (one-shot definition / request)

| Field | Notes |
|-------|-------|
| `job_id` | ULID / snowflake |
| `tenant_id` | shard + auth scope |
| `idempotency_key` | unique with tenant |
| `handler` | executor type |
| `payload_ref` | inline small or object pointer |
| `run_at` | null = immediate |
| `priority` | critical/default/bulk |
| `max_attempts` | |
| `status` | PENDING/… |
| `created_at` | |

### Schedule

| Field | Notes |
|-------|-------|
| `schedule_id` | |
| `tenant_id` | |
| `cron_expr` / `interval_ms` | |
| `timezone` | required for cron |
| `next_run_at` | indexed per shard |
| `jitter_ms` | deterministic stagger |
| `paused` | bool |
| `shard_id` | `hash(schedule_id) % N` |

### Run (execution instance)

| Field | Notes |
|-------|-------|
| `run_id` | PK |
| `job_id` / `schedule_id` | exactly one |
| `attempt` | |
| `state` | SCHEDULED/READY/LEASED/… |
| `lease_owner` | worker id |
| `lease_expires_at` | |
| `planned_fire_at` | for cron idempotency |
| `started_at` / `finished_at` | |
| `error` | truncated |

### Indexes (logical)

- `(tenant_id, idempotency_key)` unique  
- `(shard_id, next_run_at)` for cron scanners  
- `(shard_id, status, run_at)` for far-future promotion  
- `(lease_expires_at)` sparse / TTL index for reaper (or Redis lease keys)

---

## 8. Bottleneck Analysis & Mitigations

### Bottleneck #1 — Global “due job” scan

**Problem:** Central DB query for due work collapses under write load and lock contention.  
**Mitigation:** Sharded timer pods + hierarchical buckets; far-future stays cold in DB; only ≤1h hot in Redis/Kafka.

### Bottleneck #2 — Metadata write amplification (~30k/sec)

**Problem:** Single-primary Postgres saturates.  
**Mitigation:** DynamoDB/Cassandra for runs/leases; batch result sink; keep schedule table smaller in Postgres with read replicas.

### Bottleneck #3 — Kafka hot partitions (one tenant)

**Problem:** `partition = hash(tenant_id)` puts a mega-tenant on one partition → lag.  
**Mitigation:** `hash(tenant_id, run_id)` for ready topic; enforce per-tenant dispatch QPS; dedicated critical topic.

### Bottleneck #4 — Cron midnight fan-out

**Problem:** Millions of `* * * * *` schedules align.  
**Mitigation:** Deterministic jitter; per-tick fire caps; bulk priority; pre-compute next hour of fires into delay buckets off the hot path.

### Bottleneck #5 — Lease reaper thundering herd

**Problem:** Mass worker death expires 50k leases at once.  
**Mitigation:** Re-enqueue with jittered delay; rate-limit reaper publishes; separate recovery topic; alert on lease expiry rate.

### Bottleneck #6 — Large payloads in Kafka

**Problem:** 100 KB payloads × 10k/sec = 1 GB/sec bus.  
**Mitigation:** Cap inline payload (e.g. 8–16 KB); store blob in S3/GCS; pass `payload_ref` only.

### Bottleneck #7 — Noisy neighbor workers

**Problem:** Slow handlers hold leases and reduce effective throughput.  
**Mitigation:** Per-tenant concurrency caps; handler timeouts; isolate bulk workers; circuit-break bad endpoints (for HTTP handlers).

---

## 9. Scaling Strategy

### Capacity sketch at 10k jobs/sec

```
API / Job Control:     20–40 × 2 vCPU pods (p99 < 50ms; mostly memory + Redis)
Outbox publishers:     1 per metadata shard (e.g. 16 shards)
Timer scanners:        16–32 shards (delay + cron)
Kafka:                 6–9 brokers; 128 partitions on jobs.ready.default
Ready consumers /
  lease workers:       100–300 pods (depends on handler latency & concurrency)
                       e.g. 50 concurrent HTTP/pod × 200 pods = 10k in-flight
Lease reaper:          8 pods (sharded by lease keyspace)
Result sink:           16 pods batch-writing history
Redis Cluster:         8–16 shards (delay buckets + rate limits + leases)
Metadata:              DynamoDB on-demand or Cassandra 9–18 nodes RF=3
```

### Horizontal scale rules

| Component | Scale trigger | Limit |
|-----------|---------------|-------|
| API | CPU / RPS | Stateless |
| Timer shard | bucket lag / ZSET size | Add shards; rehash schedules carefully |
| Kafka consumers | consumer lag | ≤ partition count |
| Workers | lag + p99 handler latency | HPA; separate pools per priority |
| Metadata | write throttling / hot keys | Split mega-tenants; adaptive capacity |

### Multi-AZ / HA

- Kafka RF=3 across AZs  
- Timer shards: active-active with CAS on schedule/job rows (no single leader required if claim is CAS-based)  
- Avoid dual-firing cron: **idempotent run key** `schedule_id + fire_time` is mandatory  
- Region DR: active-passive; replicate schedule definitions; do not dual-run timers cross-region

---

## 10. Trade-offs & Alternatives

### Timer design

| Approach | Accuracy | Scale | Ops | Verdict |
|----------|----------|-------|-----|---------|
| DB poll `run_at <= now` | Good | Poor at 10k/sec | Easy | Reject for this SLO |
| Redis ZSET hierarchical buckets | ±100ms–1s | Excellent | Medium | **Choose** |
| Kafka delay / pause topics | ±1–5s | Excellent | Low | Good alternative |
| Per-node timing wheel (Netty-style) | ms | Memory-bound; sticky | Hard HA | Only for ultra-low latency local |

### Queue substrate

| | Kafka | SQS + EventBridge Scheduler | Redis Streams |
|--|-------|-----------------------------|---------------|
| 10k/sec | Easy | Easy (managed) | Possible; persistence/tuning harder |
| Replay | Yes | Limited | Limited |
| Delay | External timer | Native delay / scheduler | ZSET / visibility |
| Ordering | Per partition | Per group (FIFO) | Per stream |
| Ops | Higher | Lowest | Medium |

**Decision:** Kafka + sharded timer for control and replay. SQS/EventBridge is a valid managed substitute if team wants less ops and can accept AWS lock-in.

### vs Temporal / Airflow / Step Functions

| Need | Tool |
|------|------|
| 10k short independent jobs/sec | **This design** (queue + timer) |
| Long-running durable workflows | Temporal |
| Data DAG + backfills | Airflow |
| AWS-native low ops | EventBridge Scheduler + SQS + Lambda |

Do not put 10k simple “send email in 5 minutes” tasks through Temporal workflows — cost and history overhead dominate.

### At-least-once vs exactly-once

Exactly-once end-to-end is impossible if the handler has irreversible side effects outside your transaction. Target:

- Exactly-once **state** transitions (CAS)
- At-least-once **handler invocation**
- Idempotent handlers using `run_id`

---

## 11. Interview Cheat Sheet

### 30-second summary

> "At 10k jobs/sec the bottleneck is not Kafka — it's due-time discovery and metadata writes. I separate immediate jobs (API → outbox → ready topics) from delayed/cron (sharded timer plane with hierarchical delay buckets). Workers claim work with expiring leases and CAS state transitions so duplicates are safe. Cron storms are handled with deterministic jitter, fire caps, and priority isolation. Guarantees are durable accept, at-least-once execution, exactly-once bookkeeping."

### Numbers to memorize

```
10k jobs/sec → ~864M jobs/day
~30k metadata ops/sec (submit + lease + complete) → shard KV store
20k concurrent leases if avg duration = 2s
±1s timer p99 via Redis minute/second buckets; no global SELECT due
Kafka ~5 MB/sec metadata messages; keep payloads by reference
128 partitions → headroom to 30k/sec peak
```

### Whiteboard order (recommended)

1. Clarify job types (immediate / delay / cron) + SLOs  
2. Back-of-envelope (QPS, storage, concurrency)  
3. Draw control vs dispatch planes  
4. Explain why DB polling fails  
5. Delay buckets + cron scanner shards  
6. Lease + CAS state machine  
7. Tenant isolation + cron storm  
8. Failure modes (worker death, timer crash, dual fire)

### Likely interviewer questions

| Question | Crisp answer |
|----------|--------------|
| How do you fire 10k delayed jobs/sec on time? | Sharded timers; promote far→near buckets; ZPOP due members every 100–250ms into Kafka ready topics |
| What if two timer pods claim the same cron? | CAS on schedule + idempotent `run_id = schedule_id + fire_time` |
| Worker dies mid-job? | Lease expires → reaper requeues; handler must be idempotent on `run_id` |
| Why not one Postgres table? | ~30k writes/sec + due scans; use sharded KV for runs, Postgres only for low-churn schedules/config |
| How do you stop a huge tenant from starving others? | Per-tenant QPS/concurrency; hash mixing in partitions; priority topics |
| Midnight cron spike? | Deterministic jitter, per-tick caps, bulk topic, pre-bucketing |
| Exactly-once? | Exactly-once state; at-least-once invoke; idempotent workers |
| vs Temporal? | Temporal for durable multi-step workflows; this system for high-QPS independent jobs |

### Component single responsibility

```
Job Control API     → validate, dedupe, durable accept, outbox
Outbox Publisher    → metadata → Kafka / timer plane
Timer Scanners      → delay buckets + cron next_run advancement
Ready Queues        → durable dispatch buffer by priority
Lease Manager       → CAS claim, heartbeat, expiry recovery
Workers             → execute handler under timeout
Result Sink         → history, metrics, DLQ routing
Quota Service       → tenant submit/concurrency limits
```

### Failure decision tree

```
Submit path:
  DB write fails → 5xx, client retries with same idempotency key
  Kafka down → outbox retains; drain when healthy (job still accepted)

Dispatch path:
  Duplicate ready message → CAS LEASED fails → skip
  Handler 5xx/timeout → RETRY_WAIT via timer (backoff + jitter)
  Max attempts → DLQ + alert
  Lease expired → requeue with jitter (possible duplicate invoke)

Cancel:
  SCHEDULED/READY → mark CANCELLED (workers skip)
  LEASED → best-effort cancel; may still complete once
```

---

## Appendix A — API sketch

```http
POST /v1/jobs
Idempotency-Key: k-123
{
  "handler": "http.callback",
  "payload": { "url": "https://...", "body": {...} },
  "run_at": "2026-08-20T18:00:00Z",   // omit for immediate
  "priority": "default",
  "max_attempts": 6
}

POST /v1/schedules
{
  "handler": "http.callback",
  "cron": "*/5 * * * *",
  "timezone": "Asia/Kolkata",
  "payload_ref": "s3://..."
}
```

## Appendix B — Related docs in this repo

- `docs/lld/jenkinslike/HLD.md` — durable builds, agent leases, CI-shaped execution  
- `docs/lld/ORCHESTRATOR_TYPES_STAFF_GUIDE.md` — when to use Airflow / Temporal / Step Functions / Kafka instead  
- `docs/lld/webhook-delivery-system/WEBHOOK_DELIVERY_SYSTEM_HLD.md` — retry, DLQ, noisy-neighbor patterns adjacent to job workers  
