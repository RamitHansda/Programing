# Orchestrator Choice — Principal Engineer Guide

A staff engineer picks a workflow tool for one service and can name its failure mode. A principal engineer publishes the **standard** so the next six teams do not each invent a state machine, names who operates it two years from now, and writes the exit criteria while the choice is still reversible.

Use this doc when the decision is org-wide: payouts, KYC, data pipelines, approvals, and the event backbone. Per-tool mechanics (replay, backfill, ASL, BPMN) live in [`ORCHESTRATOR_TYPES_STAFF_GUIDE.md`](./ORCHESTRATOR_TYPES_STAFF_GUIDE.md). Temporal internals live in [`../TEMPORAL_ARCHITECTURE_STAFF_ENG.md`](../TEMPORAL_ARCHITECTURE_STAFF_ENG.md).

---

## 1. The Bar

| Dimension | Staff | Principal |
|---|---|---|
| Unit of decision | One workflow, one team | The orchestration standard for the company |
| Time horizon | This system, this year | Two to five years, including the migration off the choice |
| Output | A correct design and a runbook | An ADR other teams can apply without asking you |
| Trade-off | Reliability, latency, operability of one tool | Cost, lock-in, staffing, audit, and blast radius across teams |
| Failure you own | The workflow is wrong or down | Six teams copy a bad pattern and the unwind takes a year |
| Question you open with | What guarantee does this tool give me? | What class of work is this, who owns it, and is the door one-way? |

---

## 2. The Standard

These classes of work get different tools and different owners. Collapsing them into one orchestrator is the failure mode this standard exists to prevent.

| Class of work | Standard | Who owns it | What "correct" means |
|---|---|---|---|
| Batch data: partitions, schedules, backfills | **Airflow** | Data platform owns the scheduler. Domain teams own DAGs. | Yesterday's partition can be rebuilt. Freshness SLO is hours, not milliseconds. |
| Business process: money, orders, KYC, compensation, waits of hours to months | **Temporal** | A platform team owns the cluster. Product teams own workflows and activity idempotency. | A crash between step 3 and step 4 resumes at step 4. A side effect runs once. |
| Same business process, AWS-only, no platform team yet | **Step Functions** | AWS owns the control plane. You own IAM, the state machine, and idempotency. | Every execution is explainable a year later, with no cluster to run. |
| A process that compliance and operations must read and edit | **Camunda** | Risk and ops own the process definition. Engineering owns the workers. | An auditor can see who approved what, and a 48-hour SLA escalates by itself. |
| The event log is the product (payments, matching, clickstream) | **Kafka** | Streaming platform. | Order is preserved, replay is the recovery plan, and the hot path stays off any orchestrator hop. |
| Where the containers run | **Kubernetes** | Compute platform. | Workers stay up. Kubernetes is not the workflow engine. |

Neighbors, and the narrow job each one has:

- **Dagster or Prefect** — the same job as Airflow on a greenfield data platform whose team wants an asset-centric model. A working Airflow estate stays on Airflow.
- **Argo Workflows** — "run this container, then that container" on a cluster you already operate (CI, batch jobs). It is a container DAG runner, not a durable business-process engine.
- **Cadence** — Temporal's predecessor, same execution model. Stay on it only when you are already there. New work goes to Temporal.

---

## 3. Three Questions Before the Tool Name

**What failure are we standardizing on?**

A missed warehouse partition, a double charge, an unauditable approval, and a lost event are four different products. One tool for all four means one of them will be wrong, and that wrongness will be copied.

| Failure | Tool that makes it recoverable |
|---|---|
| A partition was skipped or must be rebuilt | Airflow backfill |
| The process died between "charge" and "mark paid" | Temporal replay, or Step Functions execution history |
| An approver missed a 48-hour SLA and the audit trail is incomplete | Camunda timers and history |
| A broker crashed before the event was durable | Kafka `acks=all` and replay from offset |
| A worker pod died | Kubernetes reschedules it. The workflow engine still owns the business step. |

**Is this a one-way door?**

- Step Functions definitions and their AWS service integrations stay on AWS. Multi-cloud or on-prem on the three-year plan means this door closes behind you.
- A self-hosted Temporal cluster picks `numHistoryShards` at bootstrap. That count is fixed. Capacity planning at day zero is the decision.
- A BPMN process that compliance has signed is sticky on purpose. That is the feature.
- A homegrown Postgres-plus-queue orchestrator is the expensive door. Every team extends it. Two years later you operate a worse Temporal and pay for the rewrite.

**Who is on call in year two?**

A tool with no owning team is a future incident. If you cannot name the people who will run Temporal's history store, visibility store, and worker versioning, you do not self-host it. You buy Temporal Cloud, or you use Step Functions, after a data-residency review. If the data platform already runs Airflow, product engineering does not stand up a second batch scheduler.

---

## 4. What Each One Is For

### Airflow — the data plane

The unit of work is a business date, not a customer. Nightly ingest, dbt, feature jobs, model training, "re-run 1–31 January." Backfill is the feature you are buying. DAGs are Python in git. The executor scales separately from the scheduler.

The contract with the rest of the company is pipeline freshness and a metadata database you can restore (Postgres, HA scheduler, Airflow 2.x). Default SQLite is a tutorial, not a production topology.

Reach for it when the schedule and the dependency graph are the product and the data platform already has the operating muscle.

Leave it for anything that charges a card, waits on a human, or has a sub-minute latency SLO. The scheduler loop is tens of seconds, and there is no native human task. Payout logic inside a DAG trains the org to think in cron. Extracting it later is a rewrite of the business process.

### Temporal — the application control plane

The unit of work is one business entity through a process that must be durable: payout, KYC with a three-day document wait, an order saga, subscription dunning. The code reads `charge`, then `settle`, then `notify`. The platform stores the call stack. A deploy or a dead worker replays history and continues, including which branch was taken. Compensation is the catch path.

Two promises go in the ADR, because they are the seam between platform and product:

- **Platform promise:** replay. History is the source of truth. A worker crash resumes the workflow.
- **Product promise:** workflow code stays deterministic (no clocks, randomness, or I/O in the workflow; those live in activities), and every activity that touches money, email, or external state is idempotent. Activity delivery is at-least-once. That is how double charges happen.

History per execution is bounded (guardrails on the order of 50,000 events or ~50 MB). An entity workflow that loops forever continues-as-new, or the server eventually refuses further events. A million events per second belongs in Kafka. Temporal is per workflow, not a stream processor.

Self-host only with a platform team. History sharding, the visibility store, and worker versioning (in-flight executions are bound to the code that started them) are the operating cost. Temporal Cloud moves that cost to a vendor plus a residency review.

### Step Functions — the same control plane when you will not operate one

Small team, the account is already AWS, the graphs are short. Standard workflows keep an execution history for up to a year and fit audit. Express workflows take the high-volume path and are at-least-once, so idempotency stays yours. Native integrations (Lambda, ECS, SQS, DynamoDB, Glue, Bedrock) are the reason a five-engineer team picks this over running a cluster.

Standard workflow pricing is per state transition (about $0.025 per thousand). Fifty transitions per execution at high rate is a finance conversation. Write the exit criteria on day one: state-machine size the team can no longer unit-test, a monthly bill past an agreed line, or a wait-and-compensate flow that no longer fits Amazon States Language. Past that line, new workflows go to Temporal. In-flight executions finish where they started.

### Camunda — when the flowchart is the contract

Loan approval, claims, underwriting, maker-checker. The artifact a regulator reads is the BPMN diagram, the task list, and the timer that escalates at 48 hours. Claim, unclaim, and the audit of every token move are built in. Engineering-owned system-to-system flows do not need that ceremony; Temporal is enough for those. Throughput targets above roughly a thousand process instances a second are a stream-processing problem, not a BPMN problem.

Camunda 7 embedded in the app shares your database and heap. Camunda 8 (Zeebe) is a separate cluster with real ops cost. Pick the one whose operating model you can staff.

### Kafka — the log, beside the orchestrator

Use it where partition order and replay are the recovery story. A saga may consume from Kafka and hand the business process to Temporal or Step Functions. The matching or ledger hot path does not take a detour through either of them. For money, `acks=all` with `min.insync.replicas=2`, and commit the offset after downstream work succeeds.

### Kubernetes — placement, not process

Desired replica count, self-healing, resource limits, rollout. It runs Airflow workers, Temporal workers, and stream processors. It does not remember that step 3 of a payout succeeded.

---

## 5. What You Refuse

**A custom orchestrator**, unless a named constraint (residency, latency, cost) is measured and a team is staffed to own the result. "Our workflows are special" is how a company accumulates thousands of lines of scheduler, then a class of incidents from the gap between step 3 and step 4, then a migration onto Temporal.

**A per-team choice.** Airflow plus Celery beat plus cron plus a SQS state machine plus Step Functions inside one payout flow means nobody can say where a stuck payment lives.

**One orchestrator for the data plane and the business plane.** Airflow stays after Temporal is adopted. The boundary, written down:

> A business date and a backfill go to Airflow. A customer and a compensation go to Temporal.

---

## 6. Operating Model

| Tool | Platform owns | Product / domain owns | On-call story |
|---|---|---|---|
| Airflow | Scheduler HA, metadata DB, executor, backfill guardrails | DAG correctness, idempotent tasks, partition logic | "Is the scheduler healthy, and which DAG missed freshness?" |
| Temporal | Cluster, shards, visibility, worker versioning policy, namespaces | Deterministic workflows, idempotent activities, signals | "Is replay healthy?" vs "Which activity double-fired?" |
| Step Functions | Account guardrails, IAM patterns, cost alarm | State machine, retries, idempotency | "Which execution failed?" AWS owns the control plane. |
| Camunda | Engine (or the Zeebe cluster), audit retention | BPMN, task routing, worker integrations | "Which case breached SLA?" |
| Kafka | Brokers, replication, quotas | Topics, keys, consumer idempotency | Consumer lag and under-replicated partitions |

The principal move is to make that seam explicit. Temporal's platform team guarantees replay. Product teams guarantee determinism and idempotency. Mixing those responsibilities is how the first production incident gets blamed on "Temporal" when the activity charged the card twice.

---

## 7. Exit Criteria

Write these into the ADR on the day you adopt, not the day the bill arrives.

| Choice | Stay while | Leave for new work when |
|---|---|---|
| Step Functions | AWS-only, graphs a team can test, state-transition spend inside the agreed budget, no multi-day human workflow the ASL cannot express | Monthly state-transition cost crosses the line you set, or compensation and versioning need ordinary code |
| Self-hosted Temporal | A named platform team owns history, visibility, and upgrades | That team does not exist — move the control plane to Temporal Cloud or Step Functions |
| Airflow for a business saga | Never the steady state | The moment the workflow waits on a human or must compensate |
| Homegrown state machine | A measured constraint the standards cannot meet, with staff | The constraint disappears, or the second team asks to reuse it |
| Camunda for system-to-system calls | A business owner still edits the process | Engineers are the only readers — Temporal |

In-flight work finishes on the engine that started it. The standard applies to new workflows. A big-bang migration of live payouts is how you create the incident you adopted the tool to avoid.

---

## 8. ADR Sketch

Title: **Orchestration standard**

- **Context.** Multiple teams are about to coordinate multi-step work. Without a standard, each will build a queue and a status column.
- **Decision.** Airflow for batch data. Temporal for durable business processes. Step Functions only under the exit criteria in section 7. Camunda where compliance owns the flowchart. Kafka for the event log. Kubernetes for placement.
- **Consequences.** A platform team (or Temporal Cloud) for the business-process engine. Data platform keeps Airflow. Product teams accept the determinism and idempotency rules. No new homegrown orchestrator without an exception reviewed in the architecture forum.
- **Revisit.** When Step Functions spend, residency, or workflow complexity hits the exit criteria, or when a second data-orchestration stack is proposed.

---

## 9. What You Say in the Room

> We will not have one orchestrator. Data pipelines stay on Airflow because backfill and partition freshness are the product, and the data platform already operates it. Customer-facing processes that must survive a crash — payouts, KYC, anything with a compensation — go to Temporal, because the failure we are buying insurance against is a half-finished business transaction. Step Functions is the allowed exception only while we are AWS-only, the graph is small, and we have no one to run a cluster. The exit criteria are cost per transition and workflow complexity, and they are in the ADR. Kafka remains the event log. Kubernetes only runs the workers. I will not fund another homegrown state machine.

---

## 10. Failure Modes the Standard Is Built Around

| Tool | What breaks first | What the standard requires |
|---|---|---|
| Airflow | Scheduler lag, metadata-DB loss, backfill stampedes | HA scheduler, external Postgres, backfill concurrency limits |
| Temporal | Non-determinism on deploy, history-size limits, hot history shards, at-least-once activities | Determinism rules, continue-as-new, workflow-id design, idempotent activities |
| Step Functions | Cost curve, ASL the team cannot test, Express at-least-once side effects | Cost alarm, exit criteria, idempotency keys |
| Camunda | Embedded engine as a noisy neighbor, or an unstaffed Zeebe cluster | Pick the deployment model you can operate |
| Kafka | `acks=1` plus a crash, auto-commit before the side effect | `acks=all`, commit after success |
| Homegrown | The crash between two status updates | Do not build it |

The question to close on: **what is the failure, who can recover it, and which standard makes that recovery the default for every team after this one?**
