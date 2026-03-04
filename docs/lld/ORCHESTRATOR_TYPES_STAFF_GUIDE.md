# Orchestrator Types — Staff Engineer Decision Guide

---

## Overview

Orchestrators coordinate multi-step work across systems. The staff-engineer decision is not "what does this tool do" — it is **"what guarantees does it give me, what does it cost me, and what breaks first at scale."**

---

## 1. Apache Airflow — Workflow / DAG Orchestrator

**When a staff engineer reaches for it:**
> "I have batch pipelines where the *schedule* and *dependency graph* are the product. My data team needs to own the DAGs as code, backfill historical runs, and get a visual lineage view."

**The real reasoning:**
- **Backfill semantics** are first-class — re-run `2024-01-01` through `2024-01-31` with one command. No other tool makes this as natural.
- DAGs are Python files in git — full CI/CD, code review, diff-ability.
- The scheduler is partition-aware: if you have 500 DAGs and 10k tasks/day, the executor (Celery/K8s) scales workers independently of the scheduler.

**When you reject it:**
- Latency SLA < 1 minute → Airflow's scheduler polling loop adds 10–30s overhead. Use Temporal or Flink instead.
- You need durable wait states (pause for days waiting for a human) → Airflow has no native human task. Use Temporal or Camunda.
- Your tasks are stateful microservice calls with compensating logic → wrong tool. Use Saga.

**Staff-level gotcha:** The scheduler is a single point of failure in older versions. Run Airflow 2.x with HA scheduler + external metadata DB (Postgres RDS, not SQLite). Default SQLite in tutorials will destroy you in production.

---

## 2. Temporal — Workflow / Saga / Process Orchestrator

**When a staff engineer reaches for it:**
> "I need code-as-workflow with durable execution guarantees. My workflow must survive process crashes, network partitions, and deployments — and I need the business logic to look like normal sequential code, not a state machine YAML."

**The real reasoning:**
- **Durable execution** is the core primitive. A Temporal workflow is a function that never loses its call stack across failures. You write `await paymentService.charge(amount)` — if the worker crashes mid-execution, Temporal replays the event history and resumes from exactly where it left off.
- This eliminates an entire class of bugs: "what happens if we crash between step 3 and step 4?" — the answer is always "step 4 retries, idempotently."
- **Saga compensation** is just a `try/catch` with compensating calls in the `catch` block. No saga state machine to design and maintain separately.
- **Long-running workflows** (minutes to months) are first-class: `await signal()` blocks the workflow until an external signal arrives. Human approval flows are trivial.

**When you reject it:**
- Pure data pipeline (no compensation, no human steps, just DAG of transforms) → Airflow is simpler and your data team already knows it.
- High-throughput streaming (millions of events/sec) → Temporal is per-workflow, not a stream processor. Use Kafka + Flink.
- Your team is small and the learning curve cost (event sourcing mental model, determinism constraints) is higher than the reliability gain.

**Staff-level gotcha:** Temporal workflows must be **deterministic** — no `System.currentTimeMillis()`, no random UUIDs, no direct DB calls inside workflow code. Side effects go in Activities. Violating this causes non-determinism errors on replay that are painful to debug. This is the #1 onboarding pitfall.

---

## 3. AWS Step Functions — Workflow / Saga Orchestrator (Managed)

**When a staff engineer reaches for it:**
> "I'm all-in on AWS, my team is small, and I need durable multi-step orchestration without running infrastructure. I'll pay for managed reliability."

**The real reasoning:**
- **Zero operational overhead.** No servers, no scheduler HA to manage, no metadata DB to tune. For a 5-engineer startup on AWS, this is decisive.
- **Native AWS integration** is unmatched — direct SDK integrations for Lambda, ECS, SQS, DynamoDB, Bedrock, Glue, etc. without writing glue code.
- **Standard Workflows** give you exactly-once execution and audit history for up to 1 year. **Express Workflows** give you high-throughput (100k+ executions/sec) at lower cost with at-least-once semantics.
- **ASL (Amazon States Language)** is JSON/YAML — non-engineers (PMs, support) can read the state machine diagram in the console.

**When you reject it:**
- ASL is verbose and hard to unit-test. A 10-step workflow with error handling becomes 300 lines of JSON. Temporal/code wins for complexity.
- Vendor lock-in is total. Every state, retry, and catch is AWS-specific. If you ever need to run on-prem or multi-cloud, you're rewriting from scratch.
- Pricing at scale: Standard Workflows charge per state transition ($0.025/1k). A high-frequency order workflow doing 50 state transitions per execution at 10k orders/sec = $72k/month just in Step Functions fees.

**Staff-level gotcha:** Express Workflows are at-least-once, not exactly-once. If your activities have side effects (charge a card, send an email), you must make them idempotent. Many teams learn this the hard way after double-charging customers.

---

## 4. Apache Kafka — Event-Driven Orchestration Backbone

**When a staff engineer reaches for it:**
> "I need event-driven orchestration at exchange/fintech scale where latency is p99 < 10ms, the event log is the source of truth, and replayability must be first-class — not an afterthought."

**The real reasoning (see also: `OrderEventProcessor`):**
- **The log is the state.** Kafka's partition-ordered, durable, replayable log means your processor is stateless — full recovery is "replay from offset N." No external saga state to corrupt or lose.
- **Backpressure is free.** Consumer lag is a metric. If your matching engine is slow, Kafka buffers. No dropped events, no cascading failures.
- **Symbol-partitioned parallelism** gives you horizontal scaling without any locking. 500 symbols = 500 partitions = 500 independent threads with zero coordination.
- At exchange scale, alternatives like Temporal or Step Functions add latency hops. Kafka is microseconds off the hot path.

**When you reject it:**
- You need compensating transactions (Kafka has no native rollback) → add a Saga orchestrator on top.
- Your workflow has human wait states or approval steps → Kafka can technically do this but it's awkward. Use Temporal.
- Small team, low volume — Kafka cluster ops (broker tuning, partition rebalancing, consumer group management) is non-trivial. Use SQS/SNS + Lambda instead.

**Staff-level gotcha:** `acks=all` + `min.insync.replicas=2` is not optional for financial systems. Default `acks=1` means a leader crash before replication = lost event. Never use auto-commit offset in a financial processor — always commit after successful publish of all downstream events.

---

## 5. Kubernetes — Container / Infrastructure Orchestrator

**When a staff engineer reaches for it:**
> "I need to decouple my workload's compute requirements from the underlying infrastructure, get self-healing, and enable my team to deploy independently without coordinating machine access."

**The real reasoning:**
- **Declarative desired state** is the core value. You say "I want 5 replicas of this service, min 2 vCPU, always running version X" and K8s continuously reconciles reality to that spec. No runbooks for "restart service after crash."
- **HPA + KEDA** give you event-driven autoscaling. For an order processor: `KafkaConsumerLag > 10k` → scale out more consumer pods. This is impossible to do cleanly with EC2 autoscaling groups.
- **Namespace isolation + RBAC** lets 20 teams share one cluster without stepping on each other. In a monorepo/microservices org, this is the difference between "one cluster per team" ($$$) and "one cluster, 20 namespaces."
- **Rolling deployments + readiness probes** give you zero-downtime deploys as a default, not a special case.

**When you reject it (or right-size it):**
- Serverless workloads (Lambda) are strictly better when traffic is spiky and cold start latency is acceptable — you pay for zero idle.
- Small teams (< 5 engineers): K8s operational surface is large. Use ECS Fargate or Railway/Render to start. Migrate to K8s when you have a platform team.
- Stateful workloads (databases): Managed DB (RDS, Cloud SQL) beats self-managed Postgres on K8s for 90% of teams.

**Staff-level gotcha:** **Resource requests and limits are mandatory, not optional.** A container without limits will OOM-kill its neighbors. A container without requests gets scheduled on an overloaded node and causes p99 latency spikes. 90% of K8s production incidents trace back to missing or wrong resource specs.

---

## 6. Camunda — BPM / Process Orchestrator

**When a staff engineer reaches for it:**
> "The workflow involves humans, external parties, SLA timers, and audit requirements — and the business owns the process definition, not engineering."

**The real reasoning:**
- **BPMN is a contract between business and engineering.** A loan officer can read and validate a BPMN diagram. A Temporal workflow in Java cannot be reviewed by compliance. When regulatory auditors ask "show me your approval process," you show them a BPMN diagram.
- **Native human task management** — Camunda's task list, claim/unclaim, escalation timers, and SLA breach alerts are built-in. In Temporal, you'd build all of this yourself.
- **Audit trail is first-class.** Every token movement, every variable change, every human task completion is persisted and queryable. GDPR, SOX, PCI audits all need this.
- **Timer events** — "If the underwriter doesn't respond in 48 hours, auto-escalate to manager" is a single BPMN boundary event. In pure code orchestrators, this is a custom scheduler + cron + state check.

**When you reject it:**
- Pure system-to-system workflows with no human steps → Temporal is simpler and more developer-friendly.
- High throughput (> 1k process instances/sec) → BPMN engines are not designed for streaming scale. Use Kafka + your own state machine.
- Your team is all engineers and no business analysts → the BPMN overhead adds ceremony without value. Temporal wins on developer productivity.

**Staff-level gotcha:** Camunda 7 embeds in your Spring Boot app (zero infra) but shares your DB and JVM heap — the process engine becomes a noisy neighbor at scale. Camunda 8 (Zeebe) is a distributed, cloud-native engine that scales better but requires running a separate cluster with steeper ops overhead.

---

## Decision Framework

```
Is it a data pipeline (batch, scheduled, DAG of transforms)?
  → Airflow

Is it a high-throughput event stream where the log IS the state?
  → Kafka (+ a stateless processor like OrderEventProcessor)

Is it a multi-service distributed transaction with rollback needs?
  → Temporal (code-first, complex logic)
  → Step Functions (AWS-native, managed, low complexity)

Is it a long-running process with humans, timers, and SLA audits?
  → Camunda (BPMN, business-owned)
  → Temporal (if engineers own the process fully)

Is it managing workload placement, scaling, and self-healing?
  → Kubernetes

Are you a small team on AWS needing "good enough" managed orchestration?
  → Step Functions (pay for simplicity now, migrate to Temporal at scale)
```

---

## Quick Comparison

| Tool | Sync/Async | State | Scope | Best For |
|---|---|---|---|---|
| Kafka | Async, streaming | Log (external) | Event pipelines | Fintech, exchange-scale event processing |
| Temporal | Async, durable | Replayed event history | Distributed workflows | Saga, long-running, code-first |
| Airflow | Async, scheduled | DB-persisted task state | Batch DAGs | ETL, ML pipelines, data engineering |
| Step Functions | Async, managed | AWS-managed | AWS-native workflows | Startup, AWS all-in, low ops |
| Camunda | Async + human | DB-persisted BPMN tokens | Human + system | Regulated industries, approval flows |
| Kubernetes | Continuous | Cluster desired state | Infrastructure | All containerized workloads |

---

*The staff-engineer move: always ask **"What is my failure mode, and which tool makes that failure mode observable, recoverable, and provably correct?"** — not just "what solves the happy path."*
