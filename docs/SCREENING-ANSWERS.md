# Screening Answers — Ramit Hansda

---

## 1. System Architecture & Scalability

At Skydo, I designed the payments and settlement platform from scratch — it now handles 10K+ international transactions a day. The core challenges were idempotency, concurrent state management, and failure recovery across third-party payment rails.

A few things I got right early: idempotency keys at the edge propagated across all service calls, so retries were always safe. Kafka for async event-driven flows so onboarding and transaction pipelines scaled independently. Redis-based distributed locking for scheduled jobs like reconciliation and settlement sweeps — so exactly-one execution was guaranteed even in multi-node setups.

For traffic spikes, we ran on GCP Cloud Run with auto-scaling and load-tested to 5x peak before every major launch.

At Goldman Sachs, I migrated a legacy in-memory risk compute cluster (multi-terabyte) without downtime. The trick was introducing an abstraction layer between sharding logic and computation, so we could re-shard live while the system was serving real-time VaR queries during trading hours.

---

## 2. Security, Compliance & Reliability

I was CIO at Skydo alongside my EM role, so I owned ISO 27001 and SOC 2 Type II end-to-end — not just coordinating, but building the actual controls.

For financial systems, a few things are non-negotiable for me:

- **Immutable audit logs** — every state change on a financial entity goes to a write-only store with actor, timestamp, and before/after state. No service can modify it.
- **Rule-based fraud checks** at pre, during, and post-transaction stages — and the rules are operator-configurable without code changes.
- **Observability on business SLOs**, not just infra. "Transaction stuck in PENDING >5 mins" is more useful than a CPU alert. This alone cut production incidents by ~30%.
- Compliance built into CI/CD — security gates, automated evidence collection, secrets via KMS, not bolted on at audit time.

---

## 3. AI & Modern Platform Design

At Future Group, I built a production chatbot for Easyday — self-serve order management with a clean handoff to human agents when confidence was low. The escalation boundary was the hardest design problem: you don't want the bot to fail silently.

At Skydo, I led GenAI adoption across the team — standards for using Claude, Cursor, Windsurf. The real work was guardrails: what's safe to share externally, how to review AI-generated code properly, and preventing devs from shipping things they don't understand.

On agent orchestration vs workflow automation — I think they solve different problems. Deterministic workflows are better for anything financial — you need auditability and predictability. LLM agents are great at ambiguous, open-ended reasoning tasks like document parsing or intent classification. My rule: **LLMs at the edge, deterministic engines at the core.** Don't let non-determinism near a state-changing financial operation.

---

## 4. Leadership, Engineering Strategy & Mindset

I've led 12 engineers at Skydo and 9 at Goldman Sachs as VP. A few things I've found actually work:

**Team structure** — I split Skydo into a platform track (payments, settlement, reconciliation) and a product track (customer flows, onboarding). Platform engineers had a higher design bar. Product engineers moved fast within clear contracts. Mixing them slows both down.

**Architectural discipline** — ADRs (Architecture Decision Records) kept things honest. Any decision touching data models, integrations, or system boundaries needed a written rationale. It also onboards new engineers faster than any wiki.

**Speed vs architecture** — my line is: reversible debt is fine, load-bearing debt is not. I'll let a team cut corners on a UI feature. I won't let them shortcut a payment state machine that 10 services depend on.

**P&L impact** — at Skydo, a settlement bug meant real money stuck. That changes how you prioritize. Reliability was treated as a product requirement, not engineering overhead. Incident reviews were blameless but rigorous — every one closed with a concrete action item.
