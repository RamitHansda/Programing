# Temporal Architecture — Staff Engineer Deep Dive & How to Actually Use It

A reference for two things at once: **how Temporal is built internally** (so you can reason about failure modes, not just call the SDK), and **how a staff engineer decides to adopt, design around, and operate it** in a real system. Part 3 is a bank of textbook-vs-production signal questions, in the same spirit as the Redis/Postgres internals docs in this repo.

See also `docs/lld/ORCHESTRATOR_TYPES_STAFF_GUIDE.md` for how Temporal compares to Airflow, Step Functions, Camunda, and Kafka at a decision level. This doc goes one level deeper into Temporal itself.

---

## Part 0: The One-Sentence Mental Model

> **Temporal turns a normal function into a durable, replayable call stack.** You write sequential code (`await charge(); await shipOrder(); await sendEmail();`). Temporal persists every meaningful step as an event. If the process running your code crashes at any point, a new worker replays the event history against your same code and resumes exactly where it left off — including local variables, loop counters, and which branch it took.

Everything else in this doc explains how that guarantee is implemented and where it leaks.

---

## Part 1: How Temporal Works Internally

### 1.1 The Four Server-Side Services

Temporal Server is not one process — it's four logically separate services (often deployed as separate binaries/pods in production, or all-in-one for dev):

- **Frontend Service** — the only service clients/workers talk to directly. gRPC API gateway: auth, rate limiting, request validation, namespace routing. Stateless, horizontally scaled behind a load balancer.
- **History Service** — the brain. Owns the **event history** (the durable log) for every workflow execution and drives the workflow's state machine (schedules timers, activity tasks, workflow tasks). **Sharded**: workflows are hash-partitioned across a fixed number of shards (`numHistoryShards`, set at cluster creation and immutable thereafter — commonly 512–4096). Each shard is owned by exactly **one** History host at a time via a membership/ownership protocol (Ringpop, gossip-based). This single-owner-per-shard design is what gives you serialized, race-free updates to any one workflow execution.
- **Matching Service** — implements task queues. Workers long-poll Matching for Workflow Tasks and Activity Tasks. When a poller is already waiting, Matching does a **sync match** (hands the task straight through, no persistence). If no poller is waiting, the task is written to the database and delivered on the next poll. Task queues are also sharded/partitioned by name.
- **Worker Service** (internal, not to be confused with *your* workers) — runs Temporal's own system workflows: archival, batch operations (bulk terminate/reset), scheduled workflow backfill, etc.

Underneath all four: a **persistence layer** (Cassandra, PostgreSQL, or MySQL) storing event history and shard/task-queue metadata, plus an optional **visibility store** (Elasticsearch, or basic SQL visibility) for searching/listing workflows by custom attributes.

**Staff-level answer:** "Temporal Server is Frontend, History, Matching, and an internal Worker service, backed by a database for event history and Elasticsearch for visibility. The part that matters operationally is History sharding — a fixed shard count set at cluster bootstrap, single-owner-per-shard, which is both how you get correctness (no split-brain on one workflow) and how you get a hot-shard problem if your workflow-ID distribution or task-queue design is skewed."

---

### 1.2 Event Sourcing: The Actual Durability Mechanism

- Every meaningful thing that happens to a workflow execution is appended as an immutable **Event** to that workflow's **Event History**: `WorkflowExecutionStarted`, `WorkflowTaskScheduled/Started/Completed`, `ActivityTaskScheduled/Started/Completed/Failed/TimedOut`, `TimerStarted/Fired`, `WorkflowExecutionSignaled`, `MarkerRecorded` (used for `sideEffect`/`GetVersion`), etc.
- Your workflow code is not "resumed" like a checkpointed process — it is **re-executed from the top**, and the Temporal SDK's client-side library intercepts every SDK call (`activity.Execute`, `Workflow.sleep`, `Workflow.await`) and compares it against the recorded history. If the history says "this activity already completed with result X," the SDK returns X immediately instead of re-running the activity. This is **replay**.
- This is why workflow code must be **deterministic**: replay only produces the same local state if the same code, given the same history, takes the same path every time.

**Staff-level answer:** "The durability isn't magic — it's event sourcing plus deterministic replay. The event history is the source of truth; your workflow function is re-run against it on every worker pickup, and the SDK short-circuits any call whose result is already in history. That's exactly why non-deterministic code (real clocks, real randomness, direct I/O, unordered concurrency) breaks replay — the second run has to match the first run's decisions exactly, or you get a non-determinism error."

---

### 1.3 Determinism Constraints (the #1 onboarding failure)

Inside workflow code, you may **not**:
- Call `System.currentTimeMillis()` / `time.Now()` / `Date.now()` directly → use `Workflow.currentTimeMillis()` (backed by a recorded event).
- Generate randomness or UUIDs directly → use `Workflow.newRandom()` / `Workflow.randomUUID()`.
- Make network/DB calls, read files, or do any real I/O directly → put it in an **Activity**.
- Spawn raw threads or rely on unmanaged concurrency → use `Workflow.newThread()`/coroutine-safe APIs the SDK provides, or `Async.function()`.
- Use mutable static/global state that isn't part of workflow input — two replays on different machines must agree.
- For a genuinely non-deterministic value you must compute inline (e.g. reading an env flag once), wrap it in `Workflow.sideEffect()`, which records the result as a Marker event so replay reuses it instead of recomputing it.

**Staff-level gotcha:** the failure mode isn't a compile error — it's a runtime **non-determinism error during replay**, often days later, on a workflow that "worked fine" until a worker restarted or a deploy happened. This is the single most common reason teams have a bad first experience with Temporal, and it is almost always traceable to real I/O, real time, or unordered goroutines/threads inside workflow code that should have been in an Activity.

---

### 1.4 Workflows, Activities, Workers, Task Queues

- A **Workflow** is orchestration logic: sequencing, branching, retries, compensation. It must be deterministic and must not do I/O directly.
- An **Activity** is where all real work happens: HTTP calls, DB writes, charging a card, sending an email. Activities are **not required to be deterministic** and can be retried independently.
- A **Worker** is a process you run (your code, your infra) that hosts both workflow and activity implementations and long-polls one or more **Task Queues** for work.
- A **Task Queue** is just a named routing key. `client.start(Workflow.class, options.setTaskQueue("orders-tq"))` puts a Workflow Task on `orders-tq`; only workers polling `orders-tq` will pick it up. This is your primary lever for **isolation and independent scaling** — e.g. separate task queues (and worker pools) for CPU-light orchestration vs. slow/heavy activities, so one slow activity type can't starve unrelated workflow task processing.

**Activity delivery is at-least-once, not exactly-once.** If a worker crashes after finishing the side effect but before Temporal records `ActivityTaskCompleted`, Temporal retries the activity after its timeout elapses. **Every activity with a side effect must be idempotent** — this is non-negotiable for anything touching money, email, or external state, and it is the second most common production incident after determinism errors (double-charging, double-sending).

---

### 1.5 Sticky Execution (why replay isn't O(history) on every task)

Full replay from event #1 on *every* workflow task would be prohibitively slow for a long workflow. In practice:
- After a worker processes a workflow task, it keeps the workflow's in-memory state (its actual call stack/local variables) in a cache and registers a **sticky task queue** unique to that worker.
- Temporal routes subsequent tasks for that workflow execution back to the same worker via the sticky queue, so the worker just applies the *new* events incrementally — no full replay.
- If that worker crashes, is evicted from cache (LRU under memory pressure), or the sticky task times out, the next worker to pick up the workflow does a **full replay** from the beginning of history.

**Staff-level implication:** worker cache size (`maxCachedWorkflows` / equivalent) and worker pool stability directly affect P99 latency on long-running workflows — a worker fleet that's constantly rolling (aggressive autoscaling, frequent deploys) pays full-replay cost far more often than a stable fleet.

---

### 1.6 Signals, Queries, and Updates

- **Signal** — async, fire-and-forget external input into a *running* workflow (`workflow.signal("approve", payload)`). Appended to history as `WorkflowExecutionSignaled`; processed on the workflow's next task. This is how you implement "wait for human approval" or "wait for an external event" without polling.
- **Query** — synchronous, read-only. Does **not** append to history; it runs against the worker's current in-memory (or replayed) state and returns immediately. Cannot mutate workflow state, cannot call activities.
- **Update** (newer API, supersedes many Signal+Query workarounds) — synchronous, can validate input and mutate state, and returns a real result to the caller, unlike Signal.

Signals are the backbone of **long-running, human-in-the-loop workflows** (loan approval, order-hold-for-review) and of the **entity/actor pattern**: a workflow that loops forever (`while(true) { await signal or timer }`) representing a long-lived entity (a user session, a shopping cart, a device's state machine).

---

### 1.7 Event History Limits and Continue-As-New

- Event history has hard limits (default guardrails around **~50,000 events** or **~50 MB** per execution — Temporal logs warnings well before the hard limit and will eventually refuse further events). An "entity" workflow that loops forever on signals **will** hit this if it never resets.
- **`ContinueAsNew`** atomically completes the current execution and starts a brand-new execution of the same workflow type with a fresh, empty event history, carrying forward whatever state you explicitly pass as the new input. This is the standard pattern for infinite/long-lived workflows (actor pattern, periodic/cron-style workflows) — you periodically call `Workflow.continueAsNew(currentState)` (e.g. every N signals or every N days) to keep history bounded.

**Staff-level gotcha:** forgetting `ContinueAsNew` on a long-lived "entity" workflow is a slow-motion incident — the workflow works fine for weeks, then starts failing or degrading in performance as history balloons, and by the time someone notices, the fix (redesigning the loop) requires migrating already-running executions.

---

### 1.8 Versioning and Safe Deploys

This is where Temporal is genuinely harder to operate than a stateless service, because **in-flight workflow executions are bound to the code that started them**, and that code is about to change.

- **Patching API** (`Workflow.getVersion(changeId, minSupported, maxSupported)`): wrap a behavior change in a version check so old, still-replaying executions take the old branch and new executions take the new branch, all from the *same deployed binary*.
- **Worker Build ID versioning** (newer, cluster-native mechanism): you tag workers with a Build ID and assign Build IDs to a task queue's version sets. Existing open workflow executions stay **pinned** to the Build ID they started on; new executions get routed to the current default. This replaces a lot of manual `getVersion` bookkeeping for whole-worker-fleet rollouts.
- Either way, the discipline is the same: **never change workflow code in a way that alters the deterministic path for an already-in-flight execution** without an explicit version gate. Renaming an activity call, reordering two independent activity calls, or adding a new `await` before an existing one are all classic ways to silently break replay for workflows that were mid-flight during your deploy.
- The concrete pre-deploy safety net: **`WorkflowReplayer`** (available in every SDK) lets you take real event histories exported from production and replay them against your *new* code in a unit test, offline, before you ship. A staff engineer treats "run replay tests against a sample of real in-flight histories" as a required step before any workflow-code deploy, the same way you'd treat a DB migration dry-run.

---

### 1.9 Namespaces, Retention, and Multi-Region

- A **Namespace** is Temporal's multi-tenancy and blast-radius boundary — its own retention period, own visibility config, own task queues. Separate teams/products should get separate namespaces, the same way you'd separate databases or Kafka clusters, not just separate task queues within one namespace.
- **Retention** controls how long a *closed* workflow's history stays queryable before being deleted or archived (to S3/GCS/blob storage) — this is a compliance and audit-trail decision, not just a storage-cost one: if finance/compliance ever needs "show me the exact sequence of events for this loan approval from 8 months ago," that requires either long retention or archival configured up front.
- **Global (multi-region) namespaces**: Temporal supports active-passive replication of a namespace's event data across clusters in different regions for DR — failover promotes the passive cluster to active. This is an infrastructure/ops-heavy feature; most teams reach for **Temporal Cloud** (managed) specifically to avoid running this themselves.

---

### 1.10 Retries, Timeouts, and Heartbeating

- Every Activity has a **Retry Policy** (initial interval, backoff coefficient, max interval, max attempts, non-retryable error types) and multiple independent timeouts: `ScheduleToStart` (queued too long waiting for a worker — signals worker-capacity problems), `StartToClose` (execution itself too slow), `ScheduleToClose` (overall budget including retries).
- Long-running activities (minutes+) must call **`Activity.heartbeat(progress)`** periodically. Temporal uses missed heartbeats to detect a dead/stuck worker and reschedule the activity elsewhere — without heartbeating, Temporal has no way to know the difference between "still working" and "worker died silently," and will wait the full `StartToClose` timeout before retrying.
- Heartbeat payloads can carry progress data (e.g. "uploaded chunk 40 of 100"), so a retried activity can resume from the last checkpoint instead of restarting from zero.

---

## Part 2: Common Follow-Ups

**"Why not just use a database row with a `status` column and a cron poller instead of Temporal?"**
That's a hand-rolled, much weaker version of the same idea — and it degrades fast as steps multiply. You'd be re-implementing retries, backoff, timeout detection, crash recovery, and audit history yourself, with a poller adding latency and a shared `status` column becoming a contention point. Temporal's value is that this machinery is already correct and battle-tested; the trade is the determinism discipline and running/paying for the platform.

**"What happens if the History service shard owning my workflow goes down?"**
Ownership fails over to another History host per the membership protocol; the workflow is unavailable for that brief window but its state isn't lost (it's durably persisted, not in-memory-only on that host). This is the same trade-off as any sharded stateful service — availability dips briefly on shard movement, but there's no data loss because the shard owner is a cache/driver over persisted state, not the source of truth itself.

**"Can two workflow executions with the same Workflow ID run concurrently?"**
No — Workflow ID is a uniqueness key (configurable reuse policy: `AllowDuplicate`, `AllowDuplicateFailedOnly`, `RejectDuplicate`). This is actually a feature: use a deterministic, business-meaningful Workflow ID (e.g. `order-12345`) to get free idempotent workflow starts — starting the "same" order twice is a no-op or an error, not a duplicate execution.

**"How is this different from just retrying at the HTTP/API layer?"**
API-layer retries only protect one call. Temporal protects an entire multi-step, multi-service *process* — including the orchestration logic itself surviving a crash mid-process, not just a single request being retried.

---

## Part 3: How to Use It Like a Staff Engineer — Design & Ops Signal Questions

For each: what a **textbook answer** sounds like vs. what a **real-production answer** sounds like.

### Q1. "Walk me through a production incident Temporal caused or exposed."

- **Textbook**: "A worker crashed and Temporal retried it, no big deal."
- **Real-production**: Names a specific replay/determinism incident — e.g. a deploy that reordered two independent activity calls (looked harmless in review) and broke replay for every workflow that was mid-flight, surfacing as a wave of `NonDeterministicWorkflowError`s in the UI; or an activity without heartbeating that silently hung for the full `StartToClose` timeout (hours) before Temporal noticed the worker had died; or a long-lived entity workflow that never called `ContinueAsNew` and started timing out on workflow tasks once history crossed tens of thousands of events.

### Q2. "How do you ship a workflow-code change without breaking in-flight executions?"

- **Textbook**: "Use `GetVersion`."
- **Real-production**: Describes an actual release process — exporting a sample of real open-workflow histories from production, running them through `WorkflowReplayer` against the candidate build in CI as a gate, then either `getVersion`-gating the specific behavior change or cutting over via Build ID versioning so new executions get the new code while pinned in-flight executions keep replaying against the old path until they naturally complete.

### Q3. "How do you guarantee activities that move money or send email aren't executed twice?"

- **Textbook**: "Activities are idempotent."
- **Real-production**: Explains *how* — e.g. deriving an idempotency key from the deterministic Workflow ID + Activity attempt-independent business key (not the SDK's internal attempt number, which changes per retry) and passing it to the downstream payment/email API's own idempotency-key parameter, so a Temporal-level retry and a downstream-level retry both collapse to one real side effect.

### Q4. "You have a workflow that represents a long-lived entity — a shopping cart, a user session. How do you keep it from becoming an incident?"

- **Textbook**: "Loop on signals."
- **Real-production**: Knows this is the entity/actor pattern and calls `ContinueAsNew` on a schedule (event count or wall-clock interval) to keep history bounded, distinguishes "process crashed, needs replay" (fine, cheap with sticky cache) from "history grew unbounded because nobody continue-as-new'd" (the actual failure mode), and has a concrete number for when they trigger it (e.g. "every 500 signals or every 24h, whichever first").

### Q5. "How do you decide task queue and worker pool topology?"

- **Textbook**: "One task queue for the app."
- **Real-production**: Separates task queues by resource profile and blast radius — e.g. `orders-workflows-tq` (cheap, CPU-light orchestration, high poller concurrency) vs `orders-activities-heavy-tq` (slow external API calls, tuned `maxConcurrentActivityExecutionSize`, its own worker pool that scales independently) — specifically so one slow/flaky downstream dependency can't starve workflow task throughput for unrelated workflows sharing a queue.

### Q6. "Describe a Saga you implemented in Temporal and how compensation ordering works."

- **Textbook**: "Try/catch with rollback calls."
- **Real-production**: Describes tracking a stack (or list) of already-completed steps as the workflow progresses, and on failure, running compensations in **reverse order** deliberately (not just "call all the undo functions") — plus the harder edge case: what happens if a *compensation itself* fails (retry it, escalate to a human task/alert via signal, or leave a durable "needs manual reconciliation" record) — because a compensation that can silently fail defeats the entire point of the Saga.

### Q7. "How do you test Temporal workflows, especially ones with timers spanning days?"

- **Textbook**: "Unit tests."
- **Real-production**: Uses the SDK's **time-skipping test environment** (`TestWorkflowEnvironment`) to fast-forward simulated time so a workflow with a 7-day timer completes in milliseconds in CI, combines it with mocked/stubbed activities to test orchestration logic in isolation, and separately uses `WorkflowReplayer` against captured production histories as a regression gate for deploys (see Q2) — these are two different test tools for two different risks (logic correctness vs. deploy-time compatibility).

### Q8. "How do you size number of history shards, and can you change it later?"

- **Textbook**: "More shards, more scale."
- **Real-production**: Knows `numHistoryShards` is **set once at cluster creation and cannot be changed without standing up a new cluster and migrating** — so it's sized upfront based on expected peak concurrent-workflow count and per-shard throughput ceilings, deliberately over-provisioned, because under-provisioning means a costly migration later, not a config change.

### How to Use This Section

Ask one or two questions and push on specifics ("what was the actual error in the UI," "what number triggered your continue-as-new threshold," "how did you pick the idempotency key"). A candidate who's only read the docs gives correct-sounding but generic answers; production experience shows up as specific thresholds, specific error names, and specific "we didn't expect X" narratives.

---

## Part 4: When to Reach for Temporal (and When Not To)

**Reach for it when:**
- The business logic has multiple steps across services/APIs that must survive process crashes and deploys, and "what happens if we die between step 3 and step 4" is a question you're currently answering with fragile status columns and cron pollers.
- You need Saga-style compensation and want it expressed as normal code (`try`/`catch`) instead of a separately-maintained state machine.
- Workflows are genuinely long-running (minutes to months) with human-in-the-loop waits, external signals, or scheduled resumption.

**Don't reach for it when:**
- It's a pure batch/DAG data pipeline with no compensation or human step — Airflow is simpler and your data team already knows it.
- You need millions-of-events/sec streaming throughput — Temporal is per-workflow-execution overhead, not a stream processor; use Kafka/Flink.
- Your team is small and the determinism/event-sourcing mental model's ramp-up cost outweighs the reliability win for a genuinely simple, short-lived process — a plain retryable job queue may be enough.

---

## Part 5: Staff-Level Trade-off Summary

| Topic | One-line | Deeper point |
|---|---|---|
| Durability model | Event-sourced history + deterministic replay. | Not checkpointing — full re-execution against history, short-circuited by recorded results. |
| Determinism | No real time/randomness/I/O/unmanaged concurrency in workflow code. | The #1 onboarding failure mode; surfaces as replay errors, often long after the offending deploy. |
| Activity delivery | At-least-once. | Every side-effecting activity must be idempotent — non-negotiable for payments/email. |
| Sticky execution | Worker caches in-memory state, avoids full replay per task. | Unstable worker fleets (aggressive scaling/frequent restarts) pay full-replay cost far more often. |
| History Service sharding | Fixed shard count, single owner per shard. | Set once at cluster creation — under-provisioning means a cluster migration, not a config change. |
| Entity/actor pattern | Long-lived workflow looping on signals. | Must call `ContinueAsNew` on a schedule or event history grows unbounded and starts failing. |
| Versioning | `getVersion` patching or Build-ID-based worker versioning. | In-flight executions are bound to old code paths; test with `WorkflowReplayer` against real histories before deploying. |
| Signals/Queries/Updates | Async input / sync read-only / sync read-write. | Queries never touch history; Updates are the modern replacement for Signal+Query round-trip hacks. |
| Namespaces | Multi-tenancy + retention + blast-radius boundary. | Separate teams should get separate namespaces, not just separate task queues. |

---

## Part 6: Minimal Shape of the Code (Java SDK)

```java
@WorkflowInterface
public interface OrderWorkflow {
    @WorkflowMethod
    void placeOrder(OrderRequest request);

    @SignalMethod
    void cancel();
}

public class OrderWorkflowImpl implements OrderWorkflow {
    private final OrderActivities activities = Workflow.newActivityStub(
        OrderActivities.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setRetryOptions(RetryOptions.newBuilder()
                .setMaximumAttempts(5)
                .build())
            .build());

    private boolean cancelled = false;

    @Override
    public void placeOrder(OrderRequest request) {
        activities.reserveInventory(request);
        try {
            activities.chargePayment(request);   // idempotency key derived from Workflow.getInfo().getWorkflowId()
            activities.shipOrder(request);
        } catch (ActivityFailure e) {
            activities.releaseInventory(request); // compensation, reverse order
            throw e;
        }
    }

    @Override
    public void cancel() {
        this.cancelled = true; // observed on the next await/yield point, not asynchronously
    }
}
```

The point of showing this: notice there is **no retry loop, no status column, no polling** written by hand — `ActivityOptions`/`RetryOptions` express the retry policy declaratively, and the `try`/`catch` *is* the Saga. That collapse of "distributed systems failure handling" into "code that reads like a tutorial" is Temporal's entire pitch — and everything in Parts 1–5 is what a staff engineer needs to know about what's happening underneath it before betting a critical path on that pitch.

---

## Summary

- **Internals**: four server-side services (Frontend/History/Matching/Worker), event-sourced execution with deterministic replay, sharded History ownership, sticky worker caching, and Signals/Queries/Updates as the interaction surface with a running workflow.
- **Where it breaks**: non-deterministic workflow code, non-idempotent activities, unbounded entity-workflow history, and unsafe deploys against in-flight executions — all four are staff-level design responsibilities, not just SDK details.
- **How to use it like a staff engineer**: isolate task queues by resource profile, treat `WorkflowReplayer` against real production histories as a mandatory pre-deploy gate, design idempotency keys deliberately, budget `ContinueAsNew` into any long-lived workflow from day one, and size `numHistoryShards` before you're locked into a migration.
- Use Part 1–2 to explain *how* Temporal works precisely. Use Part 3 to calibrate whether someone's Temporal experience is textbook-deep or production-deep.
