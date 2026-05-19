# System Design Like a Principal Engineer

A Staff Engineer designs a system well. A Principal Engineer asks whether the system should be built at all — and if so, shapes the constraints that will guide every team that builds something similar for the next five years.

This document covers the **mindset, process, vocabulary, and interview execution** of a Principal Engineer-level system design, applicable to roles at L7/P7/Distinguished Engineer level and above.

---

## 1. The Bar: What Separates Principal from Staff

The promotion criteria from Staff to Principal is not "more systems designed" — it is a change in the **unit of impact**.

| Dimension | Staff Engineer (L6) | Principal Engineer (L7+) |
|-----------|--------------------|-----------------------|
| **Scope** | One team's system or service | Cross-team platform, multi-team standards, org-wide technical direction |
| **Time horizon** | Current quarter to next year | 2–5 year technical trajectory |
| **Design output** | Correct, scalable, observable system | Platform abstractions, reference architectures, ADRs that constrain future work |
| **Trade-off framing** | Technical trade-offs within a system | Business, org, cost, and technical trade-offs across systems |
| **"No" authority** | Can push back on scope creep | Can stop or reshape a project by reframing the problem |
| **Audience** | Engineering team, EM | VP of Engineering, CPO, CTO, cross-org architecture forums |
| **Failure mode** | System is wrong, slow, or down | Technical debt accumulates across six teams; mis-set standards are expensive to unwind |
| **Leverage** | Deep expertise in a domain | Expertise that scales through writing, APIs, platforms, and other engineers |

> **The interview signal:** A staff candidate improves the design the interviewer sketched. A principal candidate questions the framing, identifies unstated constraints, and produces a design that other engineers can reason about for years.

---

## 2. The Mindset Shift: Five Questions Before the First Box

A principal engineer does not open a whiteboard and start drawing services. The first 10–15 minutes is **problem clarification** — and not just the usual "what's the scale?" clarification. It is a deeper interrogation of why this problem is being solved now, who owns it, and what org-level constraints apply.

### The Five Pre-Design Questions

**1. Why are we building this?**
- What business outcome does this system enable?
- What is the cost of not having it?
- Has anyone tried to solve this before, and why did that fail?

*Staff answer:* "I'll design the system you described."
*Principal answer:* "Before I design this, help me understand — is the goal to reduce checkout latency, or to support a new payment method, or both? Because those are architecturally different problems."

**2. Who owns the blast radius?**
- If this system fails, which teams are affected?
- Which systems depend on it (upstream)?
- Which systems does it depend on (downstream)?
- What are the SLA obligations to callers?

**3. What decision does this system encode, and is it reversible?**
- Is this a one-way door (changing the data model later will require a painful migration) or a two-way door (we can iterate quickly)?
- What technical debt are we knowingly incurring, and who will pay it?

**4. What does success look like in two years?**
- If this works perfectly, what does the platform look like?
- Will we want other teams to adopt this? Should it become a shared platform?
- What traffic/data growth must it absorb?

**5. What is the organisational constraint?**
- How many teams will own and operate this?
- Is there a team with the expertise to run the chosen technology?
- Are there existing standards (security, data residency, vendor approval) that constrain the design?

---

## 3. The Principal Design Process

### Phase Structure (90-minute system design)

| Phase | Duration | What you produce | Principal signal |
|-------|----------|-----------------|-----------------|
| **Frame** | 10–15 min | Problem statement, non-negotiables, success criteria | Reframes or sharpens the problem; calls out hidden constraints |
| **Principles** | 5 min | 3–4 design principles that will govern every decision | Consistency boundary, failure mode stance, extensibility posture |
| **High-Level Design** | 15–20 min | Component diagram, data flows, API surface | Names the seams between components; explains why each boundary exists |
| **Deep Dives** | 25–30 min | 2–3 hardest sub-problems; trade-off analysis | Shows multiple options, explicitly rejects alternatives with reasons |
| **Evolution & Migration** | 10 min | How does the system evolve from v0 to target? | Migration plan, phasing, backward compatibility |
| **Operational Readiness** | 5–10 min | SLOs, failure modes, on-call story | Names the hardest operational challenge, not just the happy path |
| **Closer** | 3–5 min | What you'd do differently with more time / information | Self-awareness, prioritisation |

### The Opening Frame (say this in the first 60 seconds)

> "Before I draw anything, let me make sure I understand the problem. I want to establish: what is the latency and throughput target, what is the consistency requirement — specifically, what is the worst-case user impact of stale data — and are there compliance or regulatory constraints I should treat as non-negotiables? I'll also call out the top two architectural decisions I see and explain why I'm making those calls."

This signals: you drive the interview rather than responding to prompts. You separate constraints (non-negotiable) from preferences (trade-offs).

---

## 4. Design Principles as the Anchor

Before drawing a single box, name the **design principles** that govern the system. Every architectural decision should trace back to one of these. If a decision cannot be justified by a principle, the principle set is incomplete — or the decision is wrong.

### Example Principle Sets

**Payments platform:**
1. **Correctness over availability** — we will reject a request rather than double-charge.
2. **Idempotent by default** — every write operation must be safe to retry.
3. **Auditability is not optional** — every state change has an immutable record with who/what/when.
4. **Fail closed on security** — unknown inputs are denied, not defaulted.

**Content delivery platform:**
1. **Eventual consistency is acceptable for reads** — slight staleness in feeds is tolerable; user writes must be visible to the user immediately.
2. **Degrade gracefully** — partial failures return degraded content, not errors.
3. **Consumer isolation** — one slow consumer cannot affect others.
4. **Cost-aware design** — storage and compute cost is a first-class requirement, not an afterthought.

**Data pipeline:**
1. **Exactly-once semantics for aggregations** — double-counted metrics cause business decisions on bad data.
2. **Reprocessability** — any pipeline stage can be replayed from the raw event log.
3. **Schema evolution without downtime** — producers and consumers can be deployed independently.
4. **Observability is built in** — latency, throughput, and error rate are emitted at every stage.

> **Interview signal:** Naming principles early lets you use them to resolve disputes. "Option A violates our 'fail closed on security' principle, which is why I'm choosing Option B despite its higher complexity."

---

## 5. The Architecture Seam — Where Principals Spend Most Time

Most system design interviews are won or lost on **where you draw boundaries**, not on whether you know the names of technologies. A principal engineer obsesses over seams.

### What Makes a Good System Boundary

A boundary is good when:
- It can be **owned by a single team** — clear accountability
- It **encapsulates a data model** — internal state cannot be accessed except through the API
- It **can change independently** — one side can be rewritten without coupling changes to the other
- It **has a documented contract** — schema, SLA, error semantics

A boundary is bad when:
- It is drawn based on technology rather than domain (e.g. "the Redis service" is not a bounded context)
- It requires two-phase coordination for every operation
- It shares a database with another service (implicit coupling)

### Conway's Law — Build the Team You Want First

> "Any organisation that designs a system will produce a design whose structure is the mirror image of the organisation's communication structure."
> — Melvin Conway, 1968

Principal engineers use Conway's Law as a **design tool**, not just a warning. Before settling on a service topology, ask: what team structure would naturally own this topology? If you cannot draw a clear team boundary around a service, it will accrue shared ownership, ambiguous accountability, and slow velocity.

**Practical application:**
- Service X and Service Y are candidates for the same team → consider merging them or making one a library
- Service X is owned by Team A but must be changed every time Team B ships a feature → the boundary is wrong; Team B likely needs to own part of what is currently in Service X
- You have five services with no obvious team that owns more than one → consider a platform team

---

## 6. Technology Decisions at Org Scale

At staff level, you select a technology for a system. At principal level, you set a precedent that may cause ten teams to adopt the same technology — or produce ten incompatible choices.

### The Org-Scale Technology Decision Framework

Ask these questions before recommending a technology:

| Question | Why it matters |
|----------|---------------|
| Is this technology already in our stack? | Operational expertise, security review, vendor contract — adding a new technology has hidden org-wide cost |
| Who will operate it on-call? | If no team has expertise, you are creating operational risk alongside the system |
| What is the escape hatch? | If this technology becomes a bottleneck in 3 years, how do we migrate away? |
| Does this set a healthy or unhealthy precedent? | Will other teams adopt this? Should they? |
| What is the total cost of ownership? | Licensing, infrastructure, migration, on-call training, not just build cost |

### Build vs Buy vs Borrow (Platform Reuse)

| Option | When to choose | Risk |
|--------|---------------|------|
| **Build** | Existing solutions don't meet functional requirements; this is core to competitive differentiation | High build and maintenance cost; underestimate of complexity |
| **Buy** (managed service) | Commodity capability; operational complexity is high; vendor SLA is sufficient | Vendor lock-in; feature gaps at edge cases; cost at scale |
| **Borrow** (internal platform) | Another team has solved the same problem; adopt their abstraction | Dependency on another team's roadmap; support overhead for platform team |
| **Adapt** | A vendor solution is 80% of the need; fork or wrap it | Fork maintenance; upgrade pain |

> **Principal framing:** "We should build X" is a staff answer. "We should build X as a platform that also serves teams Y and Z, replacing the three different solutions they currently have" is a principal answer.

---

## 7. Data Ownership and Data Contracts

Data is the hardest thing to change in a distributed system. Principals treat data decisions with the most scrutiny.

### Data Ownership Principles

1. **One service owns each entity** — only the owning service writes to the canonical record. Other services hold read copies (eventually consistent projections), not the source of truth.

2. **Schemas are contracts** — breaking a schema (removing a field, changing a type) requires the same process as breaking an API: versioning, deprecation period, consumer migration.

3. **Events are the integration layer** — services exchange state changes via events (Kafka, SNS, etc.), not direct DB access. Event schemas must be backward-compatible.

4. **Separate operational data from analytical data** — the OLTP store that serves users should not be queried by analytics jobs. CDC → data warehouse for analytics.

### Schema Evolution Rules (non-negotiable at principal level)

| Change type | Safe? | What to do |
|------------|-------|-----------|
| Add optional field | Yes | Ship; consumers that don't know the field ignore it |
| Add required field | No — breaks existing producers | Add as optional first; backfill; then make required in v2 |
| Remove field | No | Deprecate; audit consumers; remove after migration |
| Rename field | No | Add new name, keep old name, migrate consumers, remove old name |
| Change field type | No | New field with new type; migrate; remove old |
| Change event semantics | No | New event type; route to new consumers; drain old |

> **Interview signal:** Naming the expand-contract migration pattern (add → migrate → remove) and tying it to zero-downtime deployment signals principal-level maturity.

---

## 8. Failure Thinking: From Single System to Org-Wide Blast Radius

Staff engineers design for system-level failures. Principal engineers model **org-level failure cascades**.

### The Blast Radius Framework

For every critical dependency, answer:

```
What is the worst thing that happens if this component:
  (a) is completely unavailable for 10 minutes?
  (b) returns incorrect data for 30 minutes without alerting?
  (c) is slow (10x latency degradation) for 2 hours?

For each scenario:
  - Which downstream systems fail, degrade, or silently corrupt?
  - Which business operations are blocked?
  - What is the revenue / SLA / compliance impact?
  - How long does it take to detect?
  - How long does it take to recover?
```

### Failure Mode Vocabulary

| Failure mode | Description | Principal-level mitigation |
|-------------|-------------|---------------------------|
| **Cascading failure** | Slow dependency causes callers to queue up requests → memory pressure → caller also fails | Timeouts + circuit breakers at every dependency boundary; bulkhead isolation |
| **Silent corruption** | Incorrect data propagates through the system without triggering alerts | Invariant checks (e.g. ∑DEBIT = ∑CREDIT); end-to-end consistency probes |
| **Split brain** | Two nodes both believe they are the primary; diverging writes | Fencing tokens; majority quorum writes; single-leader with STONITH |
| **Clock skew** | Distributed nodes have different notions of time; ordering assumptions break | Use logical clocks (Lamport timestamps, vector clocks); DB server timestamps, never client timestamps |
| **Hot shard** | One partition receives disproportionate traffic; others are idle | Consistent hashing with virtual nodes; detect and rebalance; shard key selection based on access pattern |
| **Thundering herd** | Simultaneous cache expiry, deploy, or restart causes traffic spike to the origin | Staggered restarts; jitter on cache TTLs; circuit breaker with gradual recovery |

### Chaos Engineering Posture

At principal level, failure thinking should inform a **fault injection strategy**, not just design-time analysis:

- What experiments would validate the blast radius analysis?
- Are production traffic patterns tested in load tests (not just unit tests)?
- Is the on-call runbook sufficient to resolve each failure mode within the RTO?

---

## 9. Cost as a First-Class Design Constraint

Principal engineers are accountable for cost, not just correctness and latency. In a system design interview, bringing cost into the conversation without being prompted is a strong signal.

### Cost Estimation Framework

For any proposed architecture, estimate:

```
Storage cost:
  - How many bytes per event/record?
  - Retention period?
  - Replication factor?
  - Total = events/day × bytes × days × replication × $/GB

Compute cost:
  - How many CPU-hours per unit of work?
  - At what QPS does cost become non-linear (e.g. auto-scaling)?

Network cost:
  - Cross-region traffic (expensive — $0.08–0.09/GB on AWS between regions)
  - Data transfer out to internet

Operational cost:
  - How many on-call engineers does this require?
  - What is the complexity tax on hiring, onboarding, velocity?
```

### Cost-Aware Design Patterns

| Pattern | Cost saving | Trade-off |
|---------|------------|-----------|
| **Tiered storage** | Move cold data (>90 days) to S3/Glacier from hot DB | Higher read latency for historical queries |
| **Compression** | 5–10× reduction in storage and network cost for log/event data | CPU cost of compression/decompression |
| **Batch over stream** | Processing in 5-min micro-batches vs per-event stream reduces cost 10–100× for non-latency-sensitive workloads | Higher latency |
| **Right-sizing replicas** | Read replicas at smaller instance class than primary if reads are simpler | May bottleneck under complex read queries |
| **Spot/preemptible instances** | 70% cost reduction for stateless, fault-tolerant workloads | Instance interruption; not suitable for stateful primaries |
| **TTL on hot data** | Auto-expire cache entries and non-essential data | Stale reads; careful TTL calibration needed |

---

## 10. Technical Strategy and Roadmap Thinking

At staff level, you deliver a design document. At principal level, you deliver a **technical strategy** — a narrative that explains the current state, the target state, why the target state is correct, and the phased path from here to there.

### The Technical Strategy Document Structure

```
1. Problem Statement
   - What is broken or missing in the current architecture?
   - What business outcome is blocked by the current state?
   - Quantify: latency numbers, error rates, team velocity impact

2. Constraints and Non-Negotiables
   - Regulatory, security, budget, staffing
   - What cannot change (other systems' contracts, migration cost too high)

3. Target Architecture
   - What does the system look like when this is done?
   - Key properties: latency, throughput, consistency, operability

4. Decision Log
   - Top 3–5 architectural decisions, alternatives considered, why chosen option wins
   - Format: Decision | Options considered | Chosen | Rationale | Trade-off accepted

5. Migration Path
   - Phase 1: Minimum viable change (what is the smallest thing that provides value?)
   - Phase 2: Incremental improvement
   - Phase 3: Target state
   - Each phase: what it unlocks, how to validate, how to roll back

6. Risks and Mitigations
   - Top 3 risks, probability, impact, mitigation

7. Success Metrics
   - How will you know this worked? (latency p99, error rate, team velocity, cost)
```

### Phasing Philosophy

A principal engineer phases work to:
1. **Reduce risk** — make the smallest change that validates the core assumption before committing to the full design
2. **Deliver value continuously** — each phase ships something useful, not just "infrastructure for future phases"
3. **Enable rollback** — if Phase 2 is wrong, what is the rollback path without losing Phase 1's work?
4. **Avoid parallel tracks** — two large in-flight migrations at once create operational chaos and unclear ownership

**Strangler Fig Pattern for large migrations:**
```
Phase 1: New system exists in parallel; receives 0% of traffic; shadow mode validates correctness
Phase 2: Route 5% of traffic → new; monitor; validate; ramp up slowly
Phase 3: New system handles 100%; old system receives 0%
Phase 4: Decommission old system (this phase is always harder than expected — plan 2× the effort)
```

---

## 11. API Design at Org Scale

APIs are not just interfaces — they are contracts that constrain future design choices. At principal level, API decisions affect every team that integrates.

### Principles of Long-Lived APIs

**1. Design for the consumer, not the implementation.**
The API shape should match how consumers think about the domain, not how your database is structured.

**2. Stability over convenience.**
A slightly less convenient API that does not change is better than a convenient one that breaks every six months. Mark experimental endpoints explicitly.

**3. Idempotency is mandatory for writes.**
Every mutation API must accept an idempotency key. Clients must be able to safely retry without business-logic duplication.

**4. Errors must be actionable.**
Error responses should tell the client what went wrong and what they can do about it. Generic "500 Internal Server Error" without detail forces clients to guess.

**5. Version at the right level.**
URL versioning (`/v1/`, `/v2/`) for major breaking changes. Field additions and optional fields do not require a version bump — they are backward compatible.

### API Evolution Without Breaking Changes

| Strategy | How | When |
|----------|-----|------|
| **Additive-only changes** | New optional fields; new endpoints; new enum values (if clients use unknown-value handling) | Most common; zero migration |
| **API versioning** | `/v2/` endpoint with new contract; `/v1/` remains functional with deprecation notice | Major semantic change |
| **Field aliasing** | Serve both `user_name` and `userName` for a transition period | Rename during migration |
| **Long deprecation windows** | Minimum 6 months notice; monitor usage; alert owners of still-active callers before shutdown | Decommissioning old API versions |

---

## 12. Communication: Different Audiences

A principal engineer produces the same technical content for multiple audiences. The **content does not change — the emphasis and vocabulary do**.

### Audience Map

| Audience | What they care about | What to lead with | What to skip |
|----------|---------------------|-------------------|--------------|
| **Engineering team** | Correctness, implementation path, edge cases | Data model, failure modes, API contract | Business context they already know |
| **Engineering Manager** | Team impact, delivery timeline, risk | Phasing, dependencies, rollback plan | Low-level implementation details |
| **VP of Engineering / CTO** | Business outcome, cost, org-wide precedent | Problem statement, decision rationale, risk | Most technical detail |
| **Product Manager** | Feature velocity, reliability, user impact | Trade-offs that affect feature timeline | Internal architecture |
| **Finance / Legal** | Cost, compliance, vendor contracts | Total cost of ownership, compliance posture | Technical architecture |
| **External partners / audit** | SLA, security posture, data governance | API contract, SLA, data handling policy | Internal implementation |

### The 3-Level Summary

For any design, prepare three summaries:

**30 seconds (executive):**
> "We're replacing five bespoke notification systems with a single platform. It reduces per-notification cost by 40%, makes it possible to add a new channel in days instead of months, and gives us one SLA to measure instead of five."

**3 minutes (management/cross-team):**
> "The current state is five teams each maintaining a notification system. Each has different reliability, each has independently had incidents. The solution is a shared notification platform: a single ingestion API, channel-agnostic routing, delivery tracking, and retry logic — owned by one team. Migration is phased: new channels onboard to the platform; existing channels migrate over six months. Main risk is migration of the email channel, which has the highest volume. Mitigation is shadow-mode testing before cutover."

**20 minutes (engineering team):**
> Full design document with data model, API shape, delivery guarantees, retry logic, observability, migration runbook.

---

## 13. Anti-Patterns at the Principal Level

The most expensive failures at principal level are not system failures — they are architectural decisions that age badly and spread across the organisation.

| Anti-pattern | Description | Consequence |
|-------------|-------------|-------------|
| **Resume-Driven Architecture** | Choosing a technology because it is novel or impressive, not because it fits the problem | Operational complexity the team cannot sustain; a technology nobody else knows when the champion leaves |
| **Distributed Monolith** | Splitting a monolith into services without splitting data ownership or team ownership | All the operational complexity of microservices with none of the independence; every deploy still requires coordination |
| **Shared Database** | Multiple services read and write the same DB tables | Schema changes require coordination across all services; no team can evolve independently; incidents cascade |
| **Premature Abstraction** | Building a generic platform before you have two concrete use cases | Platform optimised for a use case that doesn't exist; the real use cases end up with awkward workarounds |
| **One-Way Door Made Two-Way** | Treating a reversible decision as if it requires months of design | Delays shipping; the ideal design can only be found by building and learning |
| **Two-Way Door Made One-Way** | Treating a highly reversible decision (e.g. a schema choice that requires a painful migration later) as low-stakes | Accumulating irreversible technical debt |
| **Heroic Architecture** | Design that only works if a specific person (or small group) understands it | Dependency on individuals; bus factor of 1; knowledge that doesn't transfer |
| **Coordination Tax** | Architecture that requires synchronous coordination between many teams to ship anything | Velocity collapse; Conway's Law manifesting as dysfunction |
| **Platform Before Product-Market Fit** | Building general-purpose infrastructure before knowing what the product needs | Over-engineered for a problem that changes; features that don't match what consumers need |

---

## 14. The Principal System Design Interview — What Evaluators Look For

Unlike a staff interview (design one system well), a principal interview tests whether the candidate **operates at the level of the organisation**, not just the system.

### The Evaluation Rubric

| Dimension | Staff bar | Principal bar |
|-----------|-----------|--------------|
| **Problem framing** | Clarifies functional and non-functional requirements | Reframes the problem; surfaces hidden constraints; challenges assumptions |
| **Architecture** | Correct, scalable, observable system | Identifies the critical architectural boundaries; justifies them; names what changes independently |
| **Technology selection** | Picks the right database/queue/cache | Considers org-wide precedent, operational expertise, total cost, build vs buy vs borrow |
| **Trade-offs** | Names trade-offs within a design | Explicitly compares two or more architectures, rejects with reasons, calls out what assumptions would change the decision |
| **Evolution** | Designs for current requirements | Designs for v0, v1, and articulates what the v2 trigger condition is |
| **Communication** | Explains the design to engineers | Produces executive summary, engineering deep-dive, and phased roadmap for the same design |
| **Org impact** | Considers team that owns the system | Considers teams that depend on the system, teams whose roadmap this unblocks or blocks |
| **Self-awareness** | Knows what they don't know | Explicitly calls out assumptions, what they'd validate first, what the highest-risk decision is |

### What Principals Say That Staff Don't

**Instead of:** "I'll use Kafka for the event stream."
**Say:** "I'll use Kafka because it gives us exactly-once semantics for payment events, persistent replay for audit, and consumer group isolation. The trade-off is operational complexity — we'll need a platform team to manage it, and I'd want to confirm that team exists before committing to this choice."

**Instead of:** "I'll add a cache to reduce DB load."
**Say:** "Before adding a cache, I want to understand the read:write ratio and the staleness tolerance. If this is financial data, stale cache values can cause disputes. I'd instrument first to confirm whether the DB is actually the bottleneck, rather than over-designing before the problem is measured."

**Instead of:** "We can shard the database."
**Say:** "Sharding solves the write bottleneck but creates a cross-shard query problem. For our reporting requirements, I'd pair sharding with a denormalised read model in Elasticsearch so analytics queries don't require scatter-gather across shards."

**Instead of:** "The system should have SLOs."
**Say:** "The SLO should be set based on the business impact. If checkout fails, we lose revenue — that's a 99.99% availability target. If the order history page fails, the user can retry tomorrow — that's a 99.9% target. Different components need different SLOs, and over-engineering the non-critical path wastes resources."

---

## 15. Deep Dive Framework: How to Discuss the Hardest Part

The deep dive is where principal-level candidates differentiate themselves. The structure:

```
1. State the problem precisely
   "The hardest part of this design is X. Here's why it's hard: [root cause]."

2. Present Option A
   - How it works (2–3 sentences max)
   - Pros and cons
   - Under what assumptions it is the right choice

3. Present Option B
   - How it works
   - Pros and cons
   - Under what assumptions it is the right choice

4. Make the call
   "Given our constraints [reference the principles set at the start],
    I'd go with Option A because [reason tied to principle].
    The trade-off we accept is [specific downside].
    We would revisit this decision if [trigger condition]."

5. Name the risk
   "The highest risk in Option A is [failure mode].
    We'd detect it via [metric/alert] and mitigate by [mechanism]."
```

> Do NOT say "it depends" without immediately saying **on what it depends and what you'd pick for each case**. "It depends" without resolution signals avoidance, not sophistication.

---

## 16. Decision Framework for Principal-Level System Design

```
Step 1: Frame the problem
  ├─ What business outcome does this serve?
  ├─ What is the cost of failure?
  └─ What is the org context (teams, existing standards)?

Step 2: Establish non-negotiables
  ├─ Regulatory / compliance constraints
  ├─ SLA obligations to callers
  └─ Existing contracts you cannot break

Step 3: Set design principles (3–4 max)
  └─ Every trade-off decision will reference one of these

Step 4: Identify the hardest problem
  ├─ Consistency boundary: where can you tolerate stale data?
  ├─ Ownership boundary: which service owns which entity?
  └─ Failure mode: what is the worst cascade?

Step 5: Design the seams (boundaries before internals)
  ├─ What are the APIs between components?
  ├─ What data does each component own?
  └─ Can each component change independently?

Step 6: Select technology with org context
  ├─ Already in stack? → prefer
  ├─ Who operates it? → must have a team
  └─ What is the exit strategy?

Step 7: Phase the rollout
  ├─ Phase 1: Smallest change with measurable value
  ├─ Phase 2: Incremental + validated
  └─ Phase 3: Target state

Step 8: Define success
  └─ SLOs, business metrics, cost targets
```

---

## 17. Common Interview Questions and Principal-Level Answers

**Q: Design a global notification system for 10 million users.**

*Staff answer:* Kafka for ingestion, worker fleet for delivery, Redis for deduplication, retry queue for failures.

*Principal answer:* "Before I design this, let me establish: do all notifications have the same delivery guarantee? A payment receipt must be delivered; a marketing push notification can be dropped. That distinction changes the architecture significantly. For critical notifications, I'd use at-least-once delivery with idempotency keys; for non-critical, fire-and-forget with sampling for observability. The second question: is this a platform for other teams, or for one use case? If it's a platform, the API contract needs to be stable, extensible to new channels without API changes, and the ownership model needs to be defined — we can't have five teams on-call for the same service..."

---

**Q: Our monolith is slow and hard to deploy. We should break it up into microservices. How do you approach this?**

*Staff answer:* Identify service boundaries, extract modules, introduce APIs.

*Principal answer:* "I'd push back on the framing. The root cause is rarely 'it's a monolith' — it's usually one of three things: the data model has become entangled; the deployment pipeline doesn't support independent releases; or a few hot paths are starving other traffic. Each has a different solution. If it's a deployment problem, we can introduce feature flags and modular builds before splitting services. If it's data entanglement, we need to untangle the data model regardless of whether we split services — and that work is the same work either way. Microservices add operational complexity; I wouldn't start there. I'd run three diagnostics first — slowest-to-deploy modules, data coupling graph, and hot path latency breakdown — before deciding what to split."

---

**Q: How do you handle a disagreement with another principal engineer on a key architectural decision?**

*Principal answer:* "I try to convert the disagreement from 'who is right' to 'what data would settle this.' If we disagree on eventual vs strong consistency, I'd say: describe the user experience that results from serving stale data. If neither of us can articulate a concrete user harm, we've probably been arguing about a theoretical preference. If one of us can articulate a specific user harm (e.g. 'a user could see a balance before a debit clears and attempt to double-spend'), that becomes the deciding constraint, not the opinion. I also try to make the decision reversible if it is — commit to a six-month review rather than permanently encoding an assumption we're uncertain about."

---

**Q: When should a company build a platform team vs letting individual teams solve their own problems?**

*Principal answer:* "The trigger for a platform is when you observe the same problem being solved in incompatible ways by three or more teams. Below three teams, the coordination cost of a platform team exceeds the benefit. At three or more, you have enough signal that the problem is real and enough consumers to justify an investment. The platform must lead with a product mindset — internal users are customers; the platform is justified by the teams it unblocks, not by the elegance of its architecture. The failure mode of platform teams is building for the use case they imagine rather than the use cases teams actually have. I'd run a discovery sprint before committing to a platform architecture: work alongside two or three consumer teams for a sprint and let their real pain define the v1."

---

## 18. Interview Cheat Sheet

**30-second opener:**
> "Let me frame this before drawing anything. The key questions I need to answer are: what is the consistency requirement — what's the cost of stale data — what is the blast radius if this system fails, and are there org-level constraints (compliance, existing platforms, team ownership) that constrain the design. I'll set three to four design principles that will govern every decision I make, so you can audit my trade-offs against them."

**The five non-negotiable questions:**
1. Why are we building this? (business outcome)
2. Who owns the blast radius? (upstream/downstream impact)
3. Is this decision reversible? (one-way vs two-way door)
4. What does success look like in two years? (evolution)
5. What is the organisational constraint? (teams, expertise, existing standards)

**The four signals that separate principal from staff:**
1. Reframes the problem before solving it
2. Names design principles before naming technologies
3. Draws system boundaries based on data ownership and team ownership, not just technical grouping
4. Explicitly rejects alternative designs with traceable reasons

**Technology selection checklist:**
- Already in our stack?
- Who operates it on-call?
- What is the escape hatch in 3 years?
- Does this set a healthy org-wide precedent?
- What is the total cost of ownership?

**Deep dive structure:**
Option A (pros/cons/assumptions) → Option B (pros/cons/assumptions) → Make the call (tie to a principle) → Name the risk + detection + mitigation

**Migration principle:**
Strangler Fig → Phase 1 shadow → Phase 2 gradual ramp → Phase 3 full cutover → Phase 4 decommission (always 2× harder than expected)

**Numbers a principal knows:**
- Typical async replica lag: < 100ms normal; seconds under write spike
- Cross-region network: ~$0.08–0.09/GB on AWS
- Kafka retention: typically 7 days default; can replay from beginning for rebuilds
- Schema migration window: minimum 6 months deprecation for internal APIs; 12+ months for external
- Platform team trigger: same problem solved 3+ times incompatibly across teams
- Circuit breaker: open after N failures in T seconds; half-open after reset timeout; close after M successes

**The one question that makes every design sharper:**
> "What is the worst-case user experience if this component returns incorrect data for 30 minutes without triggering an alert?"
