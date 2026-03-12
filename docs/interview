# Skydo Payment Platform — Interview Guide

---

## Core Framing — Lead With Impact, Not Architecture

Never open with the diagram. Open with this:

> "At Skydo I designed and scaled the core payments and settlement platform that processes 10,000+ international transactions per day. The hardest problems were correctness — making sure money never moves twice or gets lost — and reliability under partial failures across a distributed pipeline. Let me walk you through the architecture and the specific decisions I made."

That one sentence anchors the conversation at the right level for an EM/Staff role — **scale, correctness, and tradeoffs** — not "here's what Kafka is."

---

## The 5 Parts to Always Cover

Structure it as a story, not a tour of the diagram.

---

### Part 1 — The Problem *(60 seconds)*

Set the stage. Make the interviewer feel the complexity.

> "Cross-border payments are hard for three reasons. First, money cannot be wrong — a duplicate settlement means a real loss. Second, there are multiple external dependencies — payment providers, AD banks, SWIFT, HDFC — each of which can fail independently. Third, regulatory requirements (RBI, FEMA) mean every transaction has an audit trail requirement, and compliance can block a payment for hours while money sits in limbo."

---

### Part 2 — The Idempotency Architecture *(spend the most time here)*

This is where you show Staff-level thinking.

> "The hardest thing I solved was idempotency end-to-end. Every payment entry point — Funding Service, Settlement Service, Ledger, Refund — has its own idempotency layer. The pattern is: distributed Redis lock for speed, backed by a Postgres idempotency table in the same transaction as the payment record. Redis can crash — Postgres cannot lie."

**Why this lands:** Most candidates say "we used Redis for idempotency." You're saying *why* Redis alone isn't enough and *how* you designed the durability guarantee. That's the difference.

**Follow-up the interviewer will ask:** *"What happens if the Redis lock TTL expires mid-processing?"*

> TTL is a cache concern only. The Postgres write is atomic — if it committed, the idempotency key exists and the second attempt hits the conflict and exits. If it didn't commit, the second attempt processes correctly. Redis is purely a fast path, not a correctness mechanism.

---

### Part 3 — The Kafka Fan-Out Design *(2 minutes)*

Show the event-driven thinking.

> "From the funding topic, we fan out to six consumers — Invoice Service, Compliance, TM, Sanctions, Ledger, and Notification — all in independent consumer groups. Each progresses at its own pace. The key architectural constraint I enforced is that every producer uses `payment_id` as the Kafka partition key. This ensures all events for a single payment land on the same partition and are consumed in order. If Invoice Service uses `invoice_id` instead, you can get ledger entries before funding is confirmed — that's a real money bug."

**Why this lands:** Partition key discipline is something only people who've operated Kafka at scale know about. It signals depth.

---

### Part 4 — The Compliance Gate *(1 minute)*

Show you understand the business-technical intersection.

> "Compliance is both a regulatory requirement and a UX problem. Automated checks run async — TM, sanctions, AML. High-risk cases go to a compliance dashboard for manual review. I designed this as an explicit state machine with optimistic locking — because if two reviewers simultaneously approve and reject the same transaction, you get a SWIFT message and a refund both firing. The state machine with a version column prevents that at the DB level."

**Why this lands:** Most engineers think compliance is a checkbox. You show it has real race conditions with real money consequences.

---

### Part 5 — The Settlement Pipeline + HDFC *(1 minute)*

Show you understand external system reliability.

> "The final hop — calling HDFC's settlement API — is the riskiest. Bank APIs can timeout at 60 seconds without telling you if the transaction succeeded. We handle this with a deterministic idempotency key derived from `payment_id` (never a random UUID), a status polling loop for timeouts, exponential backoff with jitter, and a DLQ after 3 retries. The idempotency key on the HDFC side is what makes the retry safe — HDFC deduplicates on their end."

---

## What NOT to Explain (Unless Asked)

| Skip this | Why |
|---|---|
| SQS at ingress | It's just a buffer, not interesting |
| DLQ existence | Table stakes — mention only if asked about error handling |
| Notification Service | Low-value domain for a Staff/EM interview |
| FX rate caching details | Too deep unless it's a fintech-specific role |
| The modular monolith decision | Lead with it only if the interviewer pushes on microservices |

---

## The Behavioral Layer — What EM Interviewers Actually Want

For an EM role, every technical answer needs a leadership signal attached:

| Technical point | Leadership signal to attach |
|---|---|
| Idempotency architecture | "I made this a non-negotiable standard — every engineer on the team had to follow the pattern, I enforced it in code review" |
| Partition key convention | "I wrote this into our engineering playbook after we caught a bug in staging where someone used `invoice_id` — that near-miss became a team-wide rule" |
| Compliance state machine | "I worked with the compliance ops team, not just engineering, to map out every state transition — the domain experts knew edge cases I hadn't thought of" |
| DLQ + reconciliation | "I built incident response runbooks for DLQ alerts — reduced MTTR from 2 hours to 20 minutes because the on-call engineer knew exactly what to do" |
| 30% incident reduction | "This came directly from the observability work — we couldn't fix what we couldn't see. I prioritized tracing and alerting before adding features for an entire quarter" |

---

## Anticipated Deep-Dive Questions and Answers

### Q: Why a modular monolith and not microservices?
> "Cross-border payments have complex transactional requirements. The overhead of distributed transactions across microservices — sagas, 2PC — would have added enormous complexity early on. The Kafka-based communication means the module interfaces are already well-defined, so extracting high-churn modules into separate services later is a natural evolution, not a rewrite."

### Q: How do you handle a SWIFT batch where 3 out of 100 transactions fail?
> "This is the hardest operational problem in the pipeline. We don't retry the full batch — that risks double-settling the 97 that succeeded. We track per-transaction ACKs, isolate the 3 failed ones, and retry them individually with their original idempotency keys. This requires HDFC to support per-transaction status queries, which we validated upfront as a hard integration requirement."

### Q: How does the compliance state machine prevent race conditions?
> "Optimistic locking with a version column. When Reviewer A opens a case, we claim it — `UPDATE payments SET claimed_by = 'reviewer_a' WHERE status = 'PENDING'`. The state transition from UNDER_REVIEW → APPROVED checks the version: if someone else updated it first, 0 rows are affected and we surface a 'case already actioned' error in the UI. Claims also have a 30-minute TTL so abandoned reviews don't block the queue."

### Q: What's your biggest regret in this architecture?
> "Not building the payment state machine with an event-sourced audit log from day one. Right now the payment's state is reconstructed by tracing which Kafka topics it has passed through. A `payment_events` table — one row per state transition — would have given us free observability, free audit trail, and made reconciliation trivial. We retrofitted it later, which was painful."

---

## One-Liner to Open or Close With

> "This is a payment platform where I designed for correctness first — idempotency at every boundary, explicit state machines, atomic DB writes — and then layered reliability on top — Kafka fan-out with partition discipline, circuit breakers, DLQs with runbooks, and a reconciliation job as the final safety net. At 10K transactions a day, one duplicate settlement is a real loss. That shaped every architectural decision."
