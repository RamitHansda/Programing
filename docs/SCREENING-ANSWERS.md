# Screening Questions — Prepared Answers
**Ramit Hansda | Engineering Manager**

---

## 1. System Architecture & Scalability

> *Can you walk me through a complex system you architected from scratch? How did you ensure scalability for traffic spikes, maintain clean architecture, and handle migration from any legacy systems if applicable?*

The most representative example is the **core payments and settlement platform I designed at Skydo**, which now processes 10,000+ international transactions per day.

When I joined, the foundation was nascent — we were building for cross-border payments which inherently carries complexity: currency conversion, multi-rail settlement, regulatory constraints across jurisdictions, and zero tolerance for financial inconsistency. I designed the system around a few non-negotiable principles:

**Idempotency as a first-class concern.**
Every payment operation — initiation, status polling, reconciliation triggers — was designed to be safely retryable. We assigned idempotency keys at the edge and propagated them across all downstream service calls, including third-party payment rails. This meant any infrastructure hiccup (network partition, timeout, duplicate webhook) would never result in a double debit or missed credit.

**Event-driven, async-first architecture.**
Rather than synchronous chains between services, we used a Kafka-based event bus with strict at-least-once delivery semantics. Each service owned its own state transitions and emitted events downstream. This decoupled the onboarding pipeline from the transaction pipeline, allowing each to scale independently.

**Distributed job scheduling for time-sensitive workflows.**
Reconciliation, settlement sweeps, and SLA-tracking jobs had to run at precise times and survive node failures. I architected a distributed scheduler using Redis-based distributed locking to guarantee exactly-one execution even in multi-instance deployments.

**Scalability during traffic spikes.**
We deployed on GCP Cloud Run with auto-scaling configured around request concurrency, backed by connection-pooled PostgreSQL and async queues (SQS/SNS on the AWS side for partner integrations). We load-tested to 5x expected peak to build confidence before each major product launch.

**Legacy migration — Goldman Sachs.**
At Goldman Sachs, I owned the modernization of a multi-terabyte in-memory distributed risk compute cluster. The legacy system had tight coupling between sharding logic and business logic. We introduced a clean abstraction layer that separated data partitioning strategy from computation, enabling us to re-shard live without downtime — critical for a system supporting real-time VaR and stress testing during trading hours.

---

## 2. Security, Compliance & Reliability

> *When building transaction-based or financial systems, how do you design for security and compliance? Specifically, how do you handle audit logging, fraud prevention, observability in distributed systems, and building architectures aligned with standards like SOC2?*

This is an area I've owned both as an engineer and as a leader. At Skydo, I served as **CIO** in addition to Engineering Manager, which meant I was directly accountable for our **ISO 27001 and SOC 2 Type II certifications** — not just as a checkbox exercise, but as a genuine security posture.

**Audit logging by design.**
Every mutation to a financial entity — account status, transaction state, limit changes — is written to an immutable audit log with actor identity, timestamp, before/after state, and the triggering event or API call. It is stored separately from the transactional database with write-only access patterns, so even internal services cannot alter history. This directly maps to SOC 2 CC6 and CC7 controls.

**Fraud prevention layers.**
We implemented rule-based decisioning at multiple checkpoints: pre-transaction (velocity checks, anomaly thresholds), during transaction (real-time flag evaluation against configurable rule engines), and post-transaction (reconciliation anomaly detection). The rule engine was designed to be operator-configurable without code deployments — a critical operational requirement.

**Observability in distributed systems.**
I drove our observability stack around three pillars: structured logging (with trace IDs propagated end-to-end), distributed tracing (to correlate latency across service boundaries), and alerting on business-level SLOs — not just infra metrics. For example, we'd alert on "transaction stuck in PENDING for >5 minutes" rather than just CPU spikes. This initiative reduced production incidents by ~30%.

**SOC 2 alignment in practice.**
We implemented DLP controls for sensitive data in transit and at rest, role-based access with least-privilege policies (IAM via AWS KMS and GCP IAM), vendor risk assessments for all payment rails, and automated evidence collection for auditors. The key insight is that compliance is easiest when it's built into engineering workflows — PR templates, automated scanning, security gates in CI/CD — not bolted on retrospectively.

**Secrets and key management.**
All credentials, API keys, and payment credentials are managed through AWS KMS and environment-level secrets injection — never hardcoded, never logged. KMS-managed encryption at rest for all PII and financial data.

---

## 3. AI & Modern Platform Design

> *Have you integrated AI/LLMs into production platforms? How do you design systems where AI components interact with core business logic, and what is your perspective on agent-based orchestration vs traditional workflow automation?*

Yes, from two angles — as a practitioner building AI features, and as an engineering leader driving AI adoption across teams.

**Production AI integration.**
At Future Group, I built an **AI-powered conversational chatbot** for Easyday members handling self-serve order management — cancellations, status queries, escalations. The key architectural decision was the escalation boundary: the bot handled well-defined intents autonomously, but any low-confidence classification or emotionally charged interaction was handed off to a human agent with full context preservation. This "confidence-gated handoff" pattern is something I still consider a foundational design principle for AI in production.

**AI-augmented engineering at Skydo.**
More recently, I led **GenAI adoption across the engineering org** — defining standards for AI coding assistants like Claude, Cursor, and Windsurf. This wasn't just tooling adoption; it required establishing guardrails: what context is safe to share with external LLMs, how to review AI-generated code for correctness and security, and how to prevent over-reliance on generation without comprehension. We measurably improved development velocity and reduced boilerplate overhead.

**Agent-based orchestration vs. traditional workflow automation.**
My perspective is that these are complements, not substitutes. Traditional workflow engines (like state machines or job schedulers) excel when the decision logic is well-defined and auditable — financial workflows, compliance gates, reconciliation — because you need determinism and traceability. Agent-based orchestration with LLMs shines in **ambiguous, multi-step reasoning tasks** where the input space is too broad to enumerate rules — document extraction, customer intent classification, unstructured data processing.

The risk with agent orchestration in core financial systems is non-determinism. My design principle: **LLMs at the edge, deterministic engines at the core.** Use agents to classify, extract, and suggest — but have a rules engine or human-in-the-loop make the final state-changing decision when money is involved. Observability and auditability of agent decisions is also critical and often underbuilt in early implementations.

---

## 4. Leadership, Engineering Strategy & Mindset

> *Tell me about your leadership experience managing engineering teams and architecture decisions. How do you structure teams (product vs customization), enforce architectural discipline, balance speed vs long-term architecture, and manage systems that directly impact business or P&L?*

At Skydo, I led a team of **12 engineers** end-to-end — hiring, org structure, technical direction, and delivery execution. At Goldman Sachs, I led a team of 9 as VP. Across both, I've developed strong opinions on how engineering teams should be structured and how architectural discipline is maintained under pressure.

**Team structure — product vs. platform.**
At Skydo, I structured the team into two tracks: a **core platform track** (focused on payment rails, settlement, reconciliation — the non-negotiable foundation) and a **product delivery track** (focused on customer-facing flows, onboarding, dashboards). This separation ensured that product velocity never compromised platform integrity. Engineers on the platform track had a higher bar for design reviews and backward compatibility, while the product track moved faster with clear contracts from the platform.

**Enforcing architectural discipline.**
The most effective lever I've used is **lightweight Architecture Decision Records (ADRs)** — brief, written records of significant technical decisions, the context, and the trade-offs considered. This creates accountability, onboards new engineers to "why" decisions were made, and prevents architecture drift over time. I pair this with a design review culture — not every PR, but any change touching data models, external integrations, or system boundaries goes through a structured review.

**Speed vs. long-term architecture.**
This tension is real and I've navigated it many times. My framework: **differentiate debt that's reversible from debt that's load-bearing.** Moving fast on a UI flow or an internal tooling shortcut is very different from taking a shortcut on a payment state machine or a schema design that 10 downstream services will depend on. I give teams latitude on the former and hold a hard line on the latter. I've also learned to time-box architectural perfection — a 90% right design shipped is often better than a perfect design that ships in six months.

**Managing systems that directly impact P&L.**
At Skydo, every architectural decision had a direct revenue implication — a settlement failure meant delayed payouts, a reconciliation bug meant financial exposure. I operated with a "payment first, everything else second" mentality for prioritization. Reliability work was treated as a product requirement, not a nice-to-have. I maintained a production incident review cadence and used it to drive both technical fixes and process improvements — not to assign blame, but to systematically close gaps. That discipline is what drove the ~30% reduction in incidents.

**Growing engineers.**
I mentored 8 engineers directly in distributed systems and architectural thinking. My approach is to give ownership of a real problem rather than assign tasks — engineers grow fastest when they have to reason about trade-offs, present to stakeholders, and defend their design choices. I calibrate the scope of ownership to the engineer's current level and stretch it incrementally.
