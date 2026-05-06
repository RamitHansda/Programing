# High-Level Design: Jenkins-like Execution Platform

**Audience:** Principal engineers and senior reviewers designing a durable,
multi-tenant CI/CD execution platform similar to Jenkins, Buildkite, GitHub
Actions, or internal build orchestration systems.

---

## 1. Executive Summary

We are designing a CI/CD execution platform that accepts versioned job
definitions, turns triggers into durable build runs, schedules those runs onto
compatible workers, executes isolated pipeline steps, and exposes auditable
status, logs, and artifacts.

The system should be treated as two planes:

- **Control plane:** Owns APIs, job versions, build state transitions, queueing,
  scheduling, leases, policy, and audit.
- **Execution plane:** Owns agent lifecycle, workspace isolation, SCM checkout,
  step execution, log streaming, artifact upload, and heartbeats.

The central design principle is: **the metadata store is the source of truth for
state; queues and workers are delivery mechanisms.** Duplicate queue messages,
worker retries, and agent restarts are expected. Correctness comes from
idempotent commands, compare-and-swap state transitions, and short-lived leases.

---

## 2. Product Scope and Non-Goals

### In scope

- Register and version jobs/pipelines.
- Trigger builds manually, through API calls, schedules, or SCM webhooks.
- Queue, prioritize, cancel, and inspect builds.
- Match builds to agents using labels/capabilities.
- Execute ordered stages/steps with failure short-circuiting.
- Persist build/stage/step status, logs, artifacts, and audit records.
- Enforce tenant, folder, repository, and environment-level policies.
- Operate across multiple worker pools and availability zones.

### Non-goals

- Source-code hosting. We integrate with Git providers.
- Secret storage. We integrate with a secret manager and scope credentials at
  runtime.
- Artifact repository internals. We store artifact metadata and pointers.
- Kubernetes scheduler replacement. Kubernetes, VMs, or bare metal can be used
  under the agent abstraction.
- Exactly-once execution of arbitrary user code. The realistic target is
  exactly-once state transition and at-least-once assignment with idempotent
  callbacks.

---

## 3. Design Tenets

| Tenet | What it means |
|-------|---------------|
| Durable before visible | Return a build number only after the build run and queue intent are durable. |
| Version everything | A build points to a specific job definition version, not "latest". |
| Leases, not ownership | Agents receive expiring leases; scheduler can recover orphaned work. |
| Idempotent boundaries | Trigger, assign, heartbeat, log append, and result callbacks tolerate retries. |
| Isolate untrusted work | Each build gets a clean workspace/container and scoped credentials. |
| Control blast radius | Use tenant/job concurrency limits, label pools, quotas, and circuit breakers. |
| Separate hot metadata from heavy blobs | Store state in DB; logs/artifacts in append/object storage. |

---

## 4. Requirements and SLOs

### Functional requirements

- Job CRUD with versioned pipeline definitions.
- Build trigger APIs with idempotency keys.
- Queueing with priority, cancellation, and per-tenant/job concurrency limits.
- Agent registration, capability labels, heartbeats, and drain mode.
- Stage/step execution with terminal result reporting.
- Live log streaming and historical log retrieval.
- Artifact publishing with retention.
- Audit trail for job edits, approvals, triggers, cancels, secret access, and
  deploys.

### Non-functional requirements

| Area | Target |
|------|--------|
| Availability | Control plane remains available through a single instance/AZ failure. |
| Durability | Accepted builds, final statuses, and audit events survive process restarts. |
| Scheduling latency | Low seconds p95 from runnable queue entry to agent assignment under normal load. |
| Build inspection latency | Status reads are low latency and do not depend on workers being healthy. |
| Isolation | No mutable workspace or credential sharing across runs. |
| Operability | Queue age, label scarcity, scheduler lag, worker health, and callback errors are first-class metrics. |

### Scale assumptions

These are sizing inputs, not hard limits:

| Dimension | Initial assumption | Design implication |
|-----------|--------------------|--------------------|
| Jobs | 100k registered jobs | Job registry must be indexed by tenant/folder/repo/name. |
| Builds | 1M build runs/day | Build metadata needs partitioning/retention. |
| Concurrency | 10k running builds | Agent leases and callbacks must scale horizontally. |
| Logs | 10-100 KB/sec per active build, bursty | Logs bypass primary DB and go to append storage. |
| Artifacts | MB to GB per build | Object storage plus retention policy. |
| Tenants | Many teams/orgs sharing workers | Quotas and fair scheduling are required from day one. |

---

## 5. High-Level Architecture

```
                  +-------------------------------+
                  | Users / SCM / Schedules / API |
                  +---------------+---------------+
                                  |
                                  v
                  +-------------------------------+
                  | API Gateway                   |
                  | Auth, validation, rate limit  |
                  +---------------+---------------+
                                  |
                                  v
        +---------------------------------------------------+
        | Control Plane                                     |
        | job registry, trigger service, scheduler, policy  |
        +------+---------------------+----------------------+
               |                     |
               v                     v
   +----------------------+  +------------------------------+
   | Metadata DB          |  | Durable Queue / Outbox       |
   | jobs, runs, leases,  |  | queue entries, callbacks,    |
   | stages, steps, audit |  | retryable events             |
   +----------+-----------+  +--------------+---------------+
              |                             |
              v                             v
   +----------------------+       +-------------------------+
   | Log Store            |       | Worker Agent Pools      |
   | append-only streams  |       | linux, docker, gpu, ... |
   +----------------------+       +-----------+-------------+
                                              |
                                              v
                         +----------------------------------+
                         | Workspace / SCM / Secrets /      |
                         | Artifact Store / Deployment APIs |
                         +----------------------------------+
```

### Core separation

- The **control plane** can reject, queue, schedule, and inspect builds without
  executing user code.
- The **execution plane** can be scaled, drained, replaced, or isolated by
  environment without changing the state model.
- Logs and artifacts are separate because their write volume and retention model
  differ from metadata.

---

## 6. Component Responsibilities

### 6.1 API Gateway

- Exposes REST/gRPC endpoints for job management, trigger, cancel, retry,
  approval, logs, artifacts, and history.
- Verifies identity, tenant membership, repository access, webhook signatures,
  and rate limits.
- Requires idempotency keys for trigger APIs that can be retried by clients or
  webhook providers.
- Emits audit records for mutating operations.

### 6.2 Job Registry

- Stores versioned job definitions.
- Tracks defaults: environment, parameter schema, SCM repo/ref rules, required
  labels, timeout, retry policy, concurrency policy, retention policy, and
  approval gates.
- Enforces the invariant: **a build run always references exactly one immutable
  job version.**

### 6.3 Trigger Service

- Normalizes manual, webhook, schedule, and API triggers.
- Deduplicates by `(tenant_id, job_id, idempotency_key)`.
- Creates the build run and queue intent in one transaction, or writes an
  outbox event in the same transaction for asynchronous queue publication.

### 6.4 Queue Manager

- Maintains durable queue entries with required labels, priority, tenant/job
  keys, visibility time, and retry count.
- Applies admission controls before work becomes runnable:
  - tenant quota
  - job concurrency limit
  - environment freeze window
  - manual approval requirement
  - disabled job/repository policy
- Provides the scheduler with candidate work by label pool and fairness shard.

### 6.5 Scheduler

- Converts runnable queue entries into agent leases.
- Matches required labels to agent capabilities.
- Uses optimistic state transitions:
  - claim queue entry
  - create lease
  - transition build `QUEUED -> RUNNING`
- Handles fairness across tenants, folders, jobs, and priority classes.
- Recovers expired leases when workers stop heartbeating.

### 6.6 Worker Agent

- Registers capabilities such as `linux`, `docker`, `gpu`, `arm64`, or
  `prod-deploy`.
- Polls for assignments or receives push notifications.
- Creates isolated workspaces/containers.
- Checks out source, resolves secrets, executes steps, streams logs, uploads
  artifacts, and reports status.
- Sends heartbeats tied to an agent lease.
- Supports drain mode so rolling upgrades do not interrupt new assignments.

### 6.7 Metadata Store

- Source of truth for jobs, job versions, build runs, stage runs, step runs,
  queue entries, agent state, leases, and audit records.
- Uses compare-and-swap/version columns for state transitions.
- Partitions high-volume tables by tenant and/or time.
- Keeps recent hot rows indexed for UI/API reads; archives old history.

### 6.8 Log Store

- Append-only per build or per step.
- Supports live tail and historical reads.
- Uses sequence numbers to make appends idempotent.
- Stores metadata pointers in DB, not raw log lines.

### 6.9 Artifact Store

- Object storage keyed by tenant/job/build/artifact name.
- Integrity via checksum and size metadata.
- Retention by policy, legal hold, and environment.
- Access controlled by build/job permissions.

---

## 7. Public API Sketch

### Trigger build

```
POST /tenants/{tenantId}/jobs/{jobName}/builds
Idempotency-Key: <client-generated-key>

{
  "jobVersion": "latest",
  "parameters": { "branch": "main", "suite": "smoke" },
  "source": { "type": "manual" }
}
```

Response:

```
{
  "buildId": "bld_123",
  "buildNumber": 4182,
  "status": "QUEUED",
  "statusUrl": "/tenants/t1/builds/bld_123"
}
```

### Worker lease callback

```
POST /agents/{agentId}/leases/{leaseId}/steps/{stepId}/result

{
  "attempt": 1,
  "status": "SUCCESS",
  "startedAt": "...",
  "completedAt": "...",
  "logOffset": 91823
}
```

Callbacks are idempotent by `(lease_id, step_id, attempt)`.

---

## 8. Data Model and Invariants

```
Tenant(tenant_id, name, quota_policy_id)

Job(job_id, tenant_id, folder_id, name, disabled, created_by, created_at)

JobVersion(job_version_id, job_id, version, pipeline_definition_json,
           checksum, created_by, created_at)

BuildRun(build_id, tenant_id, job_id, job_version_id, build_number,
         status, trigger_source, idempotency_key, queued_at, started_at,
         completed_at, agent_id, lease_id, failure_code, failure_message)

StageRun(stage_run_id, build_id, ordinal, stage_name, status,
         started_at, completed_at)

StepRun(step_run_id, stage_run_id, ordinal, step_name, attempt, status,
        started_at, completed_at, error_code, error_message)

QueueEntry(queue_entry_id, build_id, tenant_id, job_id, required_labels,
           priority, visible_at, claimed_by, claim_expires_at, retry_count)

Agent(agent_id, pool_id, labels, status, max_executors, used_executors,
      last_heartbeat_at, version)

AgentLease(lease_id, agent_id, build_id, status, acquired_at, expires_at,
           heartbeat_at)

AuditEvent(event_id, tenant_id, actor, action, resource, occurred_at, payload)
```

### Core invariants

- `BuildRun.job_version_id` is immutable.
- A build has at most one active `AgentLease`.
- A lease can be active only while the build is `RUNNING`.
- Terminal statuses are immutable.
- Step results are append/update-once by `(step_run_id, attempt)`.
- A queue entry is either runnable, claimed, completed, cancelled, or delayed;
  it is never "lost" without a terminal build state.
- Logs may arrive late, but final build status does not depend on log storage
  being perfectly synchronous.

### State machine

```
CREATED
  -> QUEUED
  -> WAITING_FOR_APPROVAL
  -> RUNNING
  -> CANCELLING
  -> CANCELLED

QUEUED  -> CANCELLED
RUNNING -> SUCCESS
RUNNING -> FAILED
RUNNING -> TIMED_OUT
RUNNING -> INFRA_FAILED
RUNNING -> CANCELLING
```

The implementation can start with fewer statuses, but the HLD should reserve
separate terminal failure classes. A user test failure and an infrastructure
failure require different retry and alerting behavior.

---

## 9. Consistency and Transaction Boundaries

### Trigger path

Use one of these patterns:

1. **Single DB transaction:** Insert `BuildRun` and `QueueEntry` together.
2. **Transactional outbox:** Insert `BuildRun` and `OutboxEvent` together; a
   publisher writes to the durable queue.

Avoid "insert DB row, then publish queue message" without recovery. A crash
between those operations creates a visible build that never runs.

### Assignment path

Scheduler assignment should be a compare-and-swap operation:

1. Select runnable queue entry.
2. Select compatible agent capacity.
3. Update queue entry from unclaimed to claimed with expiry.
4. Insert agent lease.
5. Transition build `QUEUED -> RUNNING` if current status is still `QUEUED`.

If any step fails, rollback or let the claim expire and retry. Duplicate
schedulers should not be able to run the same build because only one CAS should
win.

### Worker callbacks

Worker callbacks are at least once. The server makes them idempotent with
stable identifiers:

- `lease_id`
- `stage_run_id`
- `step_run_id`
- `attempt`
- monotonic log offset

---

## 10. Scheduling Strategy

### Capability model

A build requiring `{linux, docker}` can run only on agents whose label set is a
superset of those labels. Labels should distinguish:

- OS/runtime: `linux`, `windows`, `arm64`
- tooling: `docker`, `java21`, `node`
- isolation level: `trusted`, `untrusted`, `prod-deploy`
- geography/compliance: `us-east`, `eu`, `pci`

### Queue structure

Use logical queues rather than one global FIFO:

```
tenant -> priority class -> label pool -> job queue
```

Within each job queue, preserve FIFO unless priority or retry policy says
otherwise.

### Fairness policy

Recommended default:

- Per-tenant concurrency quota.
- Per-job max concurrency.
- Weighted fair selection across tenants.
- FIFO within each job.
- Priority aging so low-priority work eventually runs.

This avoids the common Jenkins failure mode where one noisy repository consumes
all executors.

### Lease and heartbeat

- Agent leases have short expiries, extended by worker heartbeats.
- Scheduler marks a lease expired if heartbeats stop.
- Expired leases transition the build according to retry policy:
  - retry from start for infra failure
  - fail terminally if retry budget is exhausted
  - never blindly resume arbitrary shell steps mid-command

---

## 11. Execution Semantics

### Stage and step model

- Stages execute sequentially by default.
- A failed step fails its stage.
- A failed required stage short-circuits later stages.
- Optional extensions:
  - parallel stages
  - matrix builds
  - retryable steps
  - manual approval gates
  - post-build cleanup hooks

### Workspace lifecycle

1. Allocate isolated workspace/container.
2. Checkout source at immutable commit SHA.
3. Resolve environment and secrets.
4. Execute steps.
5. Upload logs/artifacts.
6. Run cleanup.
7. Destroy workspace or move it to quarantine for debugging.

### Idempotency expectation for user code

The platform can make scheduling idempotent, but it cannot make arbitrary
deploy scripts idempotent. Production deployment jobs should require:

- explicit environment locks
- approval gates
- idempotent deploy commands
- rollback or compensation steps
- audit events

---

## 12. Scalability and Capacity

### Control plane

- API services are stateless and horizontally scalable.
- Scheduler is sharded by queue shard or label pool.
- Each scheduler shard uses leader election or optimistic claims.
- Metadata reads for UI are served from indexed hot tables or read replicas.

### Queue

- Partition by tenant/label pool to avoid one global bottleneck.
- Keep enough partitions to scale scheduler consumers.
- Track oldest visible queue entry per shard.

### Metadata

- `BuildRun`, `StageRun`, and `StepRun` grow quickly; partition by time and
  tenant.
- Keep recent rows hot; archive old build history to cheaper storage.
- Use narrow indexes for common UI queries:
  - latest builds by job
  - running builds by tenant
  - build by build number
  - queue by label pool

### Logs and artifacts

- Do not store console logs in the primary relational database.
- Store compressed log segments in append/object storage.
- Store artifact blobs in object storage with checksums and retention.

---

## 13. Reliability and Failure Modes

| Failure | Detection | Mitigation |
|---------|-----------|------------|
| API crash during trigger | Missing outbox publish or incomplete transaction | Transactional insert of build and queue intent; outbox replay |
| Duplicate webhook | Same idempotency key or SCM delivery ID | Return existing build or dedupe according to trigger policy |
| Scheduler split brain | Two schedulers claim same shard | CAS claims plus lease ownership; leader election for efficiency, not correctness |
| Queue message duplicated | Duplicate queue entry delivery | Build state transition idempotency by build ID |
| Worker dies mid-build | Missed heartbeats | Expire lease; mark infra failure or retry from start |
| Agent loses network but step continues | Lease expiry and late callbacks | Reject callbacks for expired lease unless explicitly reconciled |
| Log store unavailable | Append failures or lag metrics | Buffer bounded logs locally; degrade live tail; fail build only if policy requires logs |
| Artifact upload fails | Worker callback error | Mark build failed/unstable based on artifact criticality |
| Poison job definition | Validation failures or repeated infra failures | Schema validation, dry run, quarantine job/version |
| Label pool exhausted | Queue age by label rises | Autoscale agents, shed low-priority work, alert owners |
| Metadata DB unavailable | API/scheduler DB errors | Fail closed for writes; continue cached read-only status where safe |

---

## 14. Security and Isolation

### Threat model

User build code is untrusted. It may try to read secrets, escape the workspace,
exfiltrate artifacts, attack internal services, or poison shared caches.

### Controls

- Run untrusted jobs in containers, VMs, or sandboxed runners.
- Use clean workspaces by default.
- Scope credentials to tenant/job/environment and inject just in time.
- Mask secrets in logs and block known secret patterns at upload.
- Enforce network egress policies.
- Separate trusted deployment agents from general CI agents.
- Validate SCM webhook signatures.
- Apply RBAC for job edit, trigger, cancel, approve, log read, artifact read,
  and secret use.
- Record immutable audit events for sensitive operations.

### Production deployments

Production deploy jobs should require stronger policy:

- protected branches/tags
- explicit approvals
- environment locks
- change ticket or release record
- break-glass audit path
- restricted agent labels

---

## 15. Observability and Operating Model

### Golden signals

- Trigger success/error rate.
- Queue depth and oldest queue age by tenant/job/label.
- Scheduling latency.
- Agent lease acquisition failures.
- Agent heartbeat lag and offline count.
- Running build count by label pool.
- Build duration percentiles by job.
- Failure rate by reason: user failure, infra failure, timeout, cancellation.
- Log append lag and artifact upload failures.

### Required dashboards

- Fleet health by label pool.
- Queue health by tenant and priority.
- Scheduler shard health.
- Top failing jobs and top resource consumers.
- Webhook ingestion and dedupe.
- DB/queue/log/artifact dependency health.

### Runbooks

- Drain a worker pool.
- Recover a scheduler shard.
- Requeue orphaned builds.
- Disable a noisy tenant/job.
- Investigate stuck queued builds by label.
- Rotate worker credentials.
- Restore build metadata from backup.

---

## 16. Disaster Recovery and Data Retention

| Data | Recovery expectation | Retention |
|------|----------------------|-----------|
| Job definitions | Strong backup/restore; versioned | Long-lived |
| Build metadata | Restore recent history and terminal status | Hot for weeks/months, archived later |
| Logs | Best-effort live; durable historical logs after flush | Policy driven |
| Artifacts | Durable object storage with lifecycle policy | Policy driven |
| Audit events | High durability, tamper-evident | Long-lived/compliance |

Backups should be tested by restoring into an isolated environment. DR plans
must define whether queued/running builds are replayed, failed, or manually
reconciled after regional failover.

---

## 17. Key Trade-offs

| Decision | Choice | Rationale | Cost |
|----------|--------|-----------|------|
| State authority | Metadata DB | Strong inspectability and auditable transitions | DB must scale and be protected |
| Queue semantics | At-least-once durable queue | Practical and recoverable | Requires idempotent consumers |
| Worker ownership | Expiring leases | Handles worker death and split brain | More state-machine complexity |
| Scheduling fairness | Weighted fair queues plus quotas | Prevents noisy-neighbor starvation | More scheduler state |
| Logs | Append/object storage | Handles high write volume | Requires log index/pointers |
| Job definitions | Immutable versions | Deterministic replay/debugging | More storage and UI complexity |
| Isolation | Fresh workspace/container | Reduces contamination and secret leakage | Startup overhead |
| Deployment jobs | Restricted labels and approvals | Limits production blast radius | Slower deploy path |

---

## 18. Implementation Phases

### Phase 1: Correct single-region core

- Versioned jobs.
- Durable build runs and queue entries.
- Basic label scheduling.
- Agent heartbeats and leases.
- Logs and artifacts outside primary DB.
- Idempotent trigger and worker callbacks.

### Phase 2: Multi-tenant scale

- Scheduler sharding.
- Tenant/job quotas.
- Weighted fair queues.
- Autoscaling worker pools.
- Read replicas and archival for metadata.
- Operational dashboards and runbooks.

### Phase 3: Enterprise controls

- Approval gates.
- Environment locks.
- Policy-as-code.
- Stronger sandboxing.
- Compliance retention.
- Cross-region DR.

---

## 19. Open Questions

- Should retries re-run the entire build, a stage, or only explicitly marked
  retryable steps?
- Which jobs are allowed to use privileged labels such as `prod-deploy`?
- What is the maximum acceptable queue age per priority class?
- Are logs required for a build to be considered successful?
- How long must artifacts and audit events be retained per tenant?
- Do production deploys need global environment locks or scoped locks per
  service/region?

---

## 20. Summary

A principal-engineer-level Jenkins-like design is less about "run a task from a
queue" and more about the contracts around that queue: immutable job versions,
durable trigger semantics, idempotent callbacks, lease-based scheduling,
tenant-aware fairness, untrusted-code isolation, and operability. The system is
correct when a build can be audited from trigger to terminal state even when
APIs retry, schedulers crash, queue messages duplicate, workers disappear, and
logs arrive late.
