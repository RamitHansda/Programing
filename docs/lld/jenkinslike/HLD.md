# High-Level Design: Jenkins-like Execution System

**Audience:** Engineers designing or reviewing a CI/CD job orchestration platform similar to Jenkins.

---

## 1. Problem & Scope

**Goal:** Build a system that accepts job definitions, queues build requests,
assigns them to compatible workers, executes ordered pipeline stages, and exposes
build status, logs, and history.

**What we are building:**

- A control plane for job registration, build triggering, queueing, scheduling,
  cancellation, and inspection.
- A worker plane for executing pipeline steps on labeled agents.
- A metadata/log path so users can see build history and diagnose failures.

**Non-goals for the core system:**

- Not a source-code hosting system.
- Not a full artifact repository, though artifact publishing hooks are supported.
- Not a secret manager, though the execution layer integrates with one.
- Not a Kubernetes scheduler replacement; external worker platforms can be used
  underneath the agent abstraction.

---

## 2. Requirements

### Functional

- Register jobs with pipeline definitions, parameters, environment, and required
  agent labels.
- Trigger builds manually, through APIs, or from SCM/webhook events.
- Queue builds and assign them to compatible agents.
- Execute stages and steps in order, with failure short-circuiting.
- Track build status: queued, running, success, failed, cancelled.
- Store build logs, step results, timestamps, and agent assignment.
- Support cancellation before execution and best-effort interruption while
  running.
- Provide APIs/UI for job management and build inspection.

### Non-functional

- **Availability:** Users should be able to trigger and inspect builds during
  individual worker failures.
- **Durability:** Accepted build requests and build metadata should survive
  process restarts.
- **Scalability:** Scale workers horizontally by agent label and workload type.
- **Fairness:** Avoid one busy job starving all other jobs.
- **Isolation:** Builds from different jobs or tenants should not share mutable
  workspaces or credentials.
- **Observability:** Queue depth, wait time, agent utilization, build duration,
  and failure rates must be visible.

---

## 3. High-Level Architecture

```
                         ┌──────────────────────────────────┐
                         │        USER / SCM / WEBHOOKS      │
                         └────────────────┬─────────────────┘
                                          │
                         ┌────────────────▼─────────────────┐
                         │            API GATEWAY            │
                         │ AuthN/AuthZ, validation, rate lim │
                         └────────────────┬─────────────────┘
                                          │
                 ┌────────────────────────▼────────────────────────┐
                 │                 CONTROL PLANE                   │
                 │ Job registry, queue manager, scheduler, state   │
                 └───────┬───────────────────────┬────────────────┘
                         │                       │
                         ▼                       ▼
          ┌────────────────────────┐   ┌──────────────────────────┐
          │   METADATA DATABASE    │   │      DURABLE QUEUE       │
          │ jobs, runs, stages,    │   │ pending build requests   │
          │ status, parameters     │   │ partitioned by queue key │
          └───────────┬────────────┘   └───────────┬──────────────┘
                      │                            │
                      ▼                            ▼
          ┌────────────────────────┐   ┌──────────────────────────┐
          │      LOG STORAGE       │   │       WORKER AGENTS      │
          │ streamed console logs  │   │ executors with labels    │
          └────────────────────────┘   └───────────┬──────────────┘
                                                   │
                                                   ▼
                                      ┌──────────────────────────┐
                                      │ WORKSPACE / ARTIFACTS /  │
                                      │ SECRETS / SCM INTEGRATION│
                                      └──────────────────────────┘
```

**Design principle:** Keep scheduling and state transitions in the control plane;
keep build execution and tool-specific behavior inside workers. Workers should be
replaceable because they are the least trusted and most failure-prone part of the
system.

---

## 4. Core Components

### 4.1 API Gateway

- Exposes REST/gRPC endpoints for job CRUD, build trigger, cancel, logs, and
  history.
- Performs authentication, authorization, input validation, idempotency-key
  checks, and request rate limiting.
- Converts external triggers into normalized build requests.

### 4.2 Job Registry

- Stores immutable or versioned job definitions.
- Holds pipeline stages, parameters, default environment, agent label
  requirements, concurrency policy, retention policy, and SCM metadata.
- Supports a "build uses definition version N" invariant so changing a job does
  not mutate already queued builds.

### 4.3 Queue Manager

- Persists accepted build requests into a durable queue.
- Maintains queue metadata such as enqueue time, priority, job key, and required
  labels.
- Can enforce per-job or per-tenant concurrency limits before a request becomes
  runnable.

### 4.4 Scheduler

- Matches queued builds to compatible agents based on labels and capacity.
- Handles fairness across jobs, tenants, and label pools.
- Marks a build as running only after an agent lease is acquired.
- Requeues builds when an agent disappears before acknowledging the assignment.

### 4.5 Worker Agent

- Polls or receives assignments from the scheduler.
- Creates an isolated workspace for each build.
- Checks out source, resolves environment and credentials, runs steps, streams
  logs, uploads artifacts, and reports step results.
- Sends heartbeats so the control plane can detect stuck or dead agents.

### 4.6 Metadata Store

- Source of truth for jobs and build state.
- Tables/entities: jobs, job_versions, build_runs, stage_runs, step_runs,
  agent_leases, queue_entries.
- Supports optimistic state transitions, for example:
  `QUEUED -> RUNNING -> SUCCESS|FAILED|CANCELLED`.

### 4.7 Log and Artifact Storage

- Logs are append-only and streamed during execution.
- Artifacts are stored separately from metadata because they are large and have
  different retention/access patterns.
- Metadata keeps pointers to log segments and artifact objects.

---

## 5. Main Flows

### 5.1 Trigger Build

1. User or SCM webhook calls `POST /jobs/{job}/builds`.
2. API authenticates the caller and validates parameters.
3. Control plane loads the latest job definition version.
4. Build run row is created with status `QUEUED`.
5. Durable queue entry is created with build number and required labels.
6. API returns build number and status URL.

### 5.2 Schedule and Execute

1. Scheduler reads runnable queue entries.
2. Scheduler finds an available agent matching all required labels.
3. Scheduler creates an agent lease and transitions build to `RUNNING`.
4. Worker receives the assignment and starts streaming logs.
5. Worker executes stages sequentially.
6. Each step result is persisted.
7. First failed step marks the stage and build failed; later stages are skipped.
8. On success, worker uploads artifacts and marks build `SUCCESS`.

### 5.3 Cancel Build

- If build is `QUEUED`, remove or tombstone the queue entry and mark
  `CANCELLED`.
- If build is `RUNNING`, send an interrupt/cancel signal to the worker and mark
  the run cancelled after acknowledgement or timeout.
- If build is terminal, cancellation is rejected as a no-op.

### 5.4 Agent Failure

1. Agent heartbeats stop.
2. Scheduler expires the agent lease.
3. Any running build assigned to that lease is marked failed or requeued based on
   job retry policy.
4. Queue capacity for that label pool is recalculated.

---

## 6. Data Model Sketch

```
Job
  job_id, name, created_by, disabled, created_at

JobVersion
  job_version_id, job_id, version, pipeline_definition_json, created_at

BuildRun
  build_id, job_id, job_version_id, build_number, status,
  queued_at, started_at, completed_at, agent_id, failure_message

StageRun
  stage_run_id, build_id, stage_name, ordinal, status,
  started_at, completed_at

StepRun
  step_run_id, stage_run_id, step_name, ordinal, status,
  started_at, completed_at, error_message

QueueEntry
  queue_entry_id, build_id, required_labels, priority,
  queued_at, lease_id, visible_at

Agent
  agent_id, labels, status, last_heartbeat_at, max_executors

AgentLease
  lease_id, agent_id, build_id, status, acquired_at, expires_at
```

**Important invariant:** A build run points to a specific job version. This makes
replay, audit, and debugging deterministic even after the job is edited.

---

## 7. Scheduling Strategy

### Label matching

- A build requiring labels `{linux, docker}` can run only on agents containing
  both labels.
- Label pools should be monitored independently because bottlenecks are often
  label-specific.

### Fairness

Common options:

| Strategy | Benefit | Trade-off |
|----------|---------|-----------|
| FIFO global queue | Simple and predictable | One noisy job can dominate workers |
| Per-job concurrency limits | Prevents starvation | Requires more scheduler state |
| Weighted fair queues | Better multi-tenant fairness | More complex to tune |
| Priority queues | Supports urgent jobs | Can starve low-priority work |

Practical default: FIFO within a job, per-job concurrency limits, and weighted
fair selection across jobs or tenants.

---

## 8. Scalability

- **API layer:** Stateless; scale horizontally behind a load balancer.
- **Queue:** Partition by queue group or required label family. Keep ordering
  where needed, but avoid one global partition.
- **Scheduler:** Use leader election per queue shard, or make scheduling
  optimistic with compare-and-swap leases.
- **Workers:** Scale by label pool. Autoscale from queue depth, oldest queued
  age, and agent utilization.
- **Logs:** Stream to append-only storage; avoid writing every log line through
  the primary metadata database.
- **Artifacts:** Store in object storage with retention policies.

---

## 9. Reliability and Failure Modes

| Failure | Mitigation |
|---------|------------|
| API process crashes after DB write | Use transactional outbox or write queue entry transactionally with build row |
| Queue message delivered twice | Make build state transitions idempotent by build ID |
| Worker dies mid-build | Heartbeat lease expires; mark failed or retry from start |
| Log storage unavailable | Buffer briefly on worker; fail build or degrade log streaming after timeout |
| Artifact upload fails | Mark build unstable/failed based on job policy |
| Poison job definition | Validate schema at registration; fail fast before scheduling |
| Scheduler crash | Durable queue plus DB state allows another scheduler to resume |

---

## 10. Security and Isolation

- Run each build in a clean workspace or container.
- Resolve secrets just-in-time and mask them in logs.
- Scope credentials to job, folder, tenant, and environment.
- Apply RBAC for job configuration, trigger, cancel, log read, and artifact read.
- Validate SCM webhook signatures.
- Enforce network egress policies for untrusted jobs.
- Keep audit logs for job changes, manual triggers, approvals, and secret access.

---

## 11. Observability

Key metrics:

- Queue depth by label and job.
- Oldest queued build age.
- Scheduling latency and build duration percentiles.
- Agent online/offline count and executor utilization.
- Build success/failure/cancel rate.
- Step-level failure distribution.
- Log streaming lag and artifact upload failures.

Useful traces:

- Trigger request -> queue entry -> scheduler decision -> worker lease -> stage
  and step execution -> terminal status.

---

## 12. Key Trade-offs

| Decision | Option chosen | Reason |
|----------|---------------|--------|
| Control plane state | Durable metadata DB plus durable queue | Survives restarts and supports inspection |
| Worker assignment | Label-based agent matching | Simple, extensible capability model |
| Build isolation | Fresh workspace/container per run | Avoids cross-build contamination |
| Logs | Append-only log storage | High write volume and independent retention |
| Job definitions | Versioned definitions | Deterministic audit and replay |
| Execution model | Sequential stages by default | Matches common CI semantics; parallel stages can be added later |

---

## 13. Extensions

- Parallel stages and matrix builds.
- Retry policies per stage or step.
- Manual approval gates.
- Cron/scheduled triggers.
- Distributed artifact cache.
- Dynamic cloud agents.
- Blue/green deployment plugins.
- Policy-as-code for approvals, secrets, and production deploys.

---

## 14. Summary

A Jenkins-like execution system splits naturally into a durable control plane and
an elastic worker plane. The control plane owns job versions, queueing,
scheduling, leases, and state transitions. Workers own isolated execution,
logs, artifacts, and heartbeats. This separation keeps the scheduling semantics
auditable while allowing executors to scale and fail independently.
