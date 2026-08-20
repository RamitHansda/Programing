# Job Scheduler — Hello Interview HLD

**Source problem:** [Hello Interview — Job Scheduler](https://www.hellointerview.com/learn/system-design/problem-breakdowns/job-scheduler)  
**Framing:** Design a distributed job scheduler (Airflow-like *scheduling* of independent jobs, not full DAG orchestration)  
**Scale:** **10,000 job executions / second**  
**Precision deep dive:** fire within **~2 seconds** of scheduled time  
**Delivery deep dive:** **at-least-once** execution

> Premium article content is paywalled. This HLD follows the **public problem statement, TOC, and deep-dive prompts** from Hello Interview, filled with a staff-ready solution path.

---

## Table of Contents

1. [Understanding the Problem](#1-understanding-the-problem)
2. [Requirements](#2-requirements)
3. [The Set Up (Estimates)](#3-the-set-up-estimates)
4. [Planning the Approach](#4-planning-the-approach)
5. [Core Entities](#5-core-entities)
6. [The API](#6-the-api)
7. [Data Flow](#7-data-flow)
8. [High-Level Design](#8-high-level-design)
9. [Potential Deep Dives](#9-potential-deep-dives)
10. [Final Design](#10-final-design)
11. [What Is Expected at Each Level](#11-what-is-expected-at-each-level)
12. [Interview Cheat Sheet](#12-interview-cheat-sheet)

---

## 1. Understanding the Problem

A **job scheduler** automatically runs work at a specified time or interval (immediate, one-shot future, or recurring).

Hello Interview’s vocabulary (use this in the interview):

| Term | Meaning | Example |
|------|---------|---------|
| **Task** | Abstract, reusable unit of work | “Send an email” |
| **Job** | Concrete instance: task + schedule + parameters | “Send email to john@example.com at 10:00 AM Friday” |

**Main responsibility:** take a set of jobs and execute them according to their schedules, then let users observe status.

This is **not** (unless interviewer expands scope):

- Full Airflow DAG orchestration with complex dependencies
- A container/K8s scheduler
- Exactly-once side effects at arbitrary external systems

---

## 2. Requirements

### Functional (above the line)

1. Schedule jobs to run:
   - **Immediately**
   - At a **future timestamp**
   - On a **recurring schedule** (e.g. every day at 10:00 AM)
2. **Monitor** job status (queued / running / succeeded / failed / …)

### Below the line (out of scope unless asked)

- Cancel or reschedule jobs  
- DAG dependencies between jobs  
- Priority / fairness across tenants (nice-to-have stretch)  
- Exactly-once external side effects  

### Non-functional (from interviewer)

| NFR | Target |
|-----|--------|
| Throughput | **10k jobs executed / second** |
| Timing | Deep dive: within **~2s** of scheduled time |
| Delivery | Deep dive: **at-least-once** (prefer duplicate over skip) |
| Durability | Accepted jobs survive process / node crashes |
| Availability | Scheduler and workers horizontally scalable; no single box SPOF |

---

## 3. The Set Up (Estimates)

```
Executions:     10,000 / sec
              ≈ 864 million / day

Assume mix:
  immediate     30%
  delayed       40%
  recurring     30%

Avg job metadata:           ~500 B
Ready-queue bandwidth:      10k × 500 B ≈ 5 MB/s  (fine for Kafka/SQS)

If avg execution = 2s:
  concurrent in-flight ≈ 10k × 2 = 20,000 workers slots

History retention 7 days:
  864M × 7 × 200 B ≈ ~1.2 TB run history  →  append store / TTL, not hot OLTP

Hot path writes per execution:
  claim + dispatch + complete ≈ 2–3 DB/KV ops
  → ~20–30k writes/sec  →  single Postgres primary is risky; shard or use KV
```

**Interview insight:** 10k/sec is mostly a **scheduling + dispatch** problem, not a “can Kafka handle it” problem.

---

## 4. Planning the Approach

Decompose into three planes (say this early):

```
1. Ingestion     — accept jobs, persist durably, return job_id
2. Scheduling    — discover due jobs on time, without double-claim races
3. Execution     — workers run tasks; report status; retry on failure
```

**Critical separation:** the component that decides *when* must **not** be the same bottleneck as the component that does *work*.  
Scheduler **enqueues**; workers **execute**.

Naive design to reject on the whiteboard:

```
while true:
  jobs = SELECT * FROM jobs WHERE run_at <= now()
  for j in jobs: execute(j)   # blocks, misses SLA, doesn't scale
```

---

## 5. Core Entities

```
Task
  task_id
  name                 # e.g. "send_email"
  handler              # code/key workers know how to run
  timeout_ms
  max_attempts

Job
  job_id
  task_id
  params               # JSON / blob ref
  schedule_type        # IMMEDIATE | ONCE | CRON
  run_at               # for ONCE / next fire for CRON
  cron_expr, timezone  # for CRON
  status               # ACTIVE | COMPLETED | FAILED (definition-level)
  created_at

Execution (JobRun)     # one attempt instance for monitoring
  execution_id
  job_id
  planned_fire_at      # for recurring idempotency
  attempt
  state                # PENDING | READY | RUNNING | SUCCESS | FAILED
  worker_id
  lease_expires_at
  started_at, finished_at
  error
```

**Why Execution is separate from Job:** recurring jobs produce many executions; monitoring is per-run, not per-definition.

---

## 6. The API

```http
POST /v1/jobs
{
  "task": "send_email",
  "params": { "to": "john@example.com", "template": "welcome" },
  "schedule": {
    "type": "once",                 // immediate | once | cron
    "run_at": "2026-08-20T17:00:00Z",
    // or: "cron": "0 10 * * *", "timezone": "America/Los_Angeles"
  }
}
→ 202 { "job_id": "j_123" }

GET /v1/jobs/{job_id}
→ job definition + latest execution summary

GET /v1/jobs/{job_id}/executions
→ list of runs + states (for monitoring)

GET /v1/executions/{execution_id}
→ detailed status / error / timestamps
```

Optional (only if asked): idempotency key header on `POST /jobs`.

---

## 7. Data Flow

### A) Create job

```
Client → API → validate → write Job (+ first Execution if due soon)
                       → ack 202
                       → if IMMEDIATE: publish to Ready Queue
                         if FUTURE/CRON: index by run_at / next_run_at
```

### B) Fire on schedule

```
Scheduler tick:
  find due Jobs/Executions
  claim (CAS / lock)
  create Execution if needed (cron)
  publish message to Ready Queue
  advance cron next_run_at
```

### C) Execute + monitor

```
Worker pulls Ready Queue
  mark Execution RUNNING (lease)
  run Task handler with params
  mark SUCCESS or FAILED
  on failure: schedule retry (new run_at) or stop after max_attempts

Client polls GET execution/job status
```

---

## 8. High-Level Design

### 8.1 Architecture (start here in interview)

```
                 ┌─────────────┐
                 │   Clients   │
                 └──────┬──────┘
                        │ REST
                 ┌──────▼──────┐
                 │  Job API    │  auth, validate, rate limit
                 └──────┬──────┘
                        │ durable write
          ┌─────────────▼──────────────┐
          │     Job Metadata Store      │  Jobs + Executions
          │  (Postgres sharded / Dynamo)│
          └──────┬───────────┬─────────┘
                 │           │
                 │           │ next_run index / ZSET
                 │    ┌──────▼──────────┐
                 │    │  Schedule Index │  Redis ZSET or time partitions
                 │    └──────┬──────────┘
                 │           │ poll due
                 │    ┌──────▼──────────┐
                 │    │   Schedulers    │  N shards, claim via CAS
                 │    └──────┬──────────┘
                 │           │ enqueue
                 │    ┌──────▼──────────┐
                 │    │  Ready Queue    │  Kafka / SQS
                 │    └──────┬──────────┘
                 │           │
                 │    ┌──────▼──────────┐
                 └────┤    Workers      │  execute tasks, update status
                      └─────────────────┘
```

### 8.2 Requirement mapping

| Requirement | How the design covers it |
|-------------|--------------------------|
| Immediate | API writes Execution + publishes Ready Queue |
| Future date | Job stored with `run_at`; Schedule Index holds score=`run_at` |
| Recurring | Cron Job with `next_run_at`; each fire creates an Execution; advance next |
| Monitor status | Executions table + GET APIs; workers update state |

### 8.3 Component responsibilities

| Component | Does | Does not |
|-----------|------|----------|
| Job API | Validate, persist, return ids | Execute user work |
| Metadata Store | Source of truth for Job/Execution | Serve as the only due-time scanner at 10k/sec without help |
| Schedule Index | Fast “what is due in the next 1–2s?” | Long-term sole durability (pair with DB) |
| Scheduler | Claim due work, enqueue, advance cron | Run heavy task handlers |
| Ready Queue | Buffer + at-least-once delivery | Decide *when* jobs become due |
| Workers | Execute handlers, heartbeats, status | Own the global clock |

---

## 9. Potential Deep Dives

These match Hello Interview’s published deep-dive prompts.

### Deep Dive 1 — Execute within ~2s of scheduled time

**Goal:** `actual_dispatch_at - planned_fire_at` p99 ≤ **2 seconds**.

**What eats the budget**

| Delay source | Typical cost | Mitigation |
|--------------|--------------|------------|
| Scheduler poll interval | up to poll period | Poll every **200–500ms** (not every 30s) |
| Slow due-query | seconds under load | Don’t full-scan; use **time index** |
| Claim contention | retries | Shard schedulers; small claim batches |
| Queue lag | seconds+ | HPA workers on lag; enough partitions |
| Clock skew | tens–hundreds ms | NTP; treat 2s as inclusive of skew |

**Recommended mechanism: Redis ZSET (or equivalent) as near-term schedule index**

```
ZADD schedule:{shard} <run_at_ms> <execution_id>
Every 250ms per shard:
  due = ZRANGEBYSCORE schedule:{shard} -inf <now>
  for each id: CAS-claim in DB → Kafka produce → ZREM
```

**Far future jobs:** keep only in DB; **promote** into Redis when `run_at` enters the next e.g. 15–60 minutes. Keeps Redis bounded.

**Alternative (say if asked):** hierarchical **timing wheel** in memory per shard (Kafka-style delayed purgatory) — O(1) insert/expire, still backed by durable DB.

**What fails the 2s SLO**

- One global scheduler polling Postgres every 5s  
- Executing work *inside* the scheduler loop  
- Midnight cron thundering herd without sharding/jitter  

### Deep Dive 2 — Scale to 10k jobs/sec

**Scale each stage independently**

```
Ingest API          → horizontal replicas (stateless)
Metadata writes     → shard by job_id / tenant_id (KV or Citus)
Schedule index      → hash shards (schedule:{0..N-1})
Schedulers          → one (or few) pods per shard; no single leader required if CAS
Ready queue         → Kafka 64–128 partitions
Workers             → HPA on consumer lag; concurrent handlers per pod
```

**Claim pattern (avoid double fire across schedulers)**

```sql
-- Postgres sketch
UPDATE executions
SET state = 'READY', claimed_by = :scheduler, claimed_at = now()
WHERE execution_id = :id AND state = 'PENDING';
-- 1 row updated → you own it; 0 → someone else won
```

DynamoDB equivalent: conditional `UpdateItem` on `state = PENDING`.

**Cron at 10k/sec**

- Precompute / maintain `next_run_at`  
- Idempotent execution key: `job_id + planned_fire_at`  
- Optional deterministic jitter (`hash(job_id) % 2s`) still fits the 2s window if agreed with interviewer — or keep jitter << 2s  

**Payloads:** store large params in object storage; queue message carries `execution_id` + small envelope only.

### Deep Dive 3 — At-least-once execution

**Promise:** after successful accept, the job’s work runs **≥ 1** time (duplicates possible).  
**Do not promise:** exactly-once external side effects.

**Where duplicates come from**

1. Scheduler crashes after Kafka produce, before DB update → recovery republishes  
2. Worker crashes after running handler, before ack → queue redelivers  
3. Visibility timeout / lease expiry too aggressive  

**Mechanisms**

| Layer | Technique |
|-------|-----------|
| Accept | Persist Job/Execution **before** `202` |
| Dispatch | Durable queue (Kafka/SQS); retry produce from outbox if needed |
| Claim | CAS so two schedulers don’t both think they uniquely own bookkeeping — queue may still see dup messages |
| Execute | Worker checks Execution state; skip if already `SUCCESS` |
| Failure | Retry with backoff via new `run_at`; max attempts → `FAILED` |
| External IO | Task handlers **idempotent** (dedupe key = `execution_id`) |

**Lease / heartbeat (worker death)**

```
RUNNING + lease_expires_at
Worker heartbeats extend lease
Reaper: expired lease → requeue (at-least-once)
```

**Say out loud:** “At-least-once + idempotent tasks ≈ effectively-once *business* effects.”

---

## 10. Final Design

### End-to-end picture

```
[Client]
   │ POST /jobs
   ▼
[Job API] ──persist──► [Metadata DB: Task, Job, Execution]
   │                      ▲ status updates
   │                      │
   │                   [Workers] ◄── pull ── [Ready Queue: Kafka/SQS]
   │                      ▲
   │                      │ enqueue when due
   └─ index run_at ──► [Redis ZSET shards] ◄── poll/claim ── [Scheduler pods]
```

### State machine (Execution)

```
PENDING ──(due + claimed)──► READY ──(worker lease)──► RUNNING
                                                      │
                                          ┌───────────┴───────────┐
                                          ▼                       ▼
                                       SUCCESS                  FAILED
                                                                  │
                                                          attempts left?
                                                           yes → PENDING (retry_at)
                                                           no  → FAILED terminal
```

### Tech choices (defensible defaults)

| Concern | Choice | Why |
|---------|--------|-----|
| Metadata | DynamoDB or sharded Postgres | 20–30k writes/sec |
| Due index | Redis ZSET per shard | Fast range-by-time for 2s SLO |
| Ready queue | Kafka (or SQS) | Decouple schedule from execute; backlog absorption |
| Coordination | CAS claim (no ZooKeeper required) | Simpler than leader election at this QPS |
| Workers | Stateless HPA fleet | Scale with lag |

### Explicit non-goals (Hello Interview below-the-line)

- Cancel / reschedule APIs  
- Multi-step DAG orchestration (that’s Temporal/Airflow territory)

---

## 11. What Is Expected at Each Level

Aligned to Hello Interview’s Mid / Senior / Staff+ framing:

### Mid-level

- Correct Task vs Job language  
- API + DB + queue + workers diagram  
- Immediate / once / cron covered at a basic level  
- Status monitoring via DB updates  
- Mentions retries  

### Senior

- Separates **scheduling** from **execution**  
- Explains why polling `SELECT run_at <= now()` alone won’t hold 10k/sec + 2s  
- Adds schedule index (ZSET / time partitions) + claim/CAS  
- Clear at-least-once story + idempotent workers  
- Rough capacity numbers (QPS, concurrency, partitions)

### Staff+

- Sharded schedulers + bounded near-term index + far-future promotion  
- Quantifies SLO budget (poll interval, queue lag, clock skew)  
- Failure modes: double dispatch, worker death, scheduler crash, cron storms  
- Trade-offs: Redis+DB vs timing wheel vs SQS delay; CAS vs leader election  
- Operability: lag metrics, claim fail rate, time-to-fire histogram, DLQ  
- Knows when to stop and push DAG/workflow needs to Temporal/Airflow  

---

## 12. Interview Cheat Sheet

### 30-second opener

> “I’ll treat a Task as the reusable handler and a Job as a scheduled instance with params. API durably stores jobs, a sharded schedule index finds due work within our 2s window, schedulers CAS-claim and enqueue to Kafka, and workers execute with leases. We guarantee at-least-once; handlers are idempotent on execution_id. Cancel/reschedule stay out of scope unless you want them.”

### Whiteboard order

1. Clarify Task vs Job + above/below line  
2. NFRs: 10k/sec, ~2s, at-least-once  
3. Boxes: API → DB → Schedule Index → Scheduler → Queue → Workers  
4. Data flow for immediate vs cron  
5. Deep dive the three HI prompts  
6. Call out failure modes  

### One-liners for the three deep dives

| Prompt | Line |
|--------|------|
| ≤2s | 250ms shard polls on Redis ZSET; promote near-term only; workers scaled on lag |
| 10k/sec | Shard index + CAS schedulers + Kafka partitions + HPA workers; don’t run work in scheduler |
| At-least-once | Durable accept + durable queue + lease requeue; idempotent handlers on `execution_id` |

### Common follow-ups

| Ask | Answer |
|-----|--------|
| Exactly-once? | Not end-to-end; exactly-once *state*, at-least-once *invoke* |
| Leader election? | Optional; CAS claim usually enough here |
| Airflow? | Airflow = DAG/workflow UX; this problem is distributed cron + workers |
| Postgres only? | OK for mid; at 10k/sec prefer sharded KV + Redis index |

---

## Related docs in this repo

- Broader staff notes (leases, buckets, bottlenecks): [`DISTRIBUTED_JOB_SCHEDULER_HLD.md`](./DISTRIBUTED_JOB_SCHEDULER_HLD.md)  
- When to pick Airflow vs Temporal vs queues: [`../ORCHESTRATOR_TYPES_STAFF_GUIDE.md`](../ORCHESTRATOR_TYPES_STAFF_GUIDE.md)
