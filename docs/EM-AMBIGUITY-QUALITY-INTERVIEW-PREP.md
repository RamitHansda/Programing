# EM Interview Prep — Ambiguity, Technical Depth & Quality Bar

Tailored to Ramit Hansda's experience at Skydo, Goldman Sachs, Moneyview, and Oracle.

> **Delivery tips**
> - Use STAR: Situation → Task → Action → Result. Keep each answer 2–3 minutes.
> - Anchor every answer with a concrete metric (latency, incidents avoided, $ saved, % reduction).
> - Show the **manager lens**: how you structured the team/process, not just the technical decision.

---

## 1. Ambiguity Resolution & Structuring

### Q1.1 — Describe an ambiguous problem you recently solved. How did you structure the problem, define the requirements, and systematically approach the solution?

**Situation (Skydo — International Settlement Reconciliation)**
At Skydo, we were seeing intermittent settlement mismatches between our internal ledger, the banking partner's MIS, and the FX provider. Finance was raising tickets like *"₹X is missing for customer Y"* but there was no shared definition of what "settled" even meant — each team (Ops, Finance, Engineering, Banking Partner) had its own mental model. We were processing 10K+ cross-border transactions/day, and the blast radius was growing.

**Task**
Bring clarity to an ill-defined problem where we didn't yet know whether it was a data issue, a timing issue, a code bug, or a process gap — and deliver a reconciliation framework the org could trust.

**Action — how I structured it**

1. **Reframed the problem in writing first.** I wrote a one-page "Problem Statement" doc with three sections: *What we observe, What we don't know, What "done" looks like.* Shared with Finance, Ops, and the banking partner before any engineering work started.
2. **Decomposed ambiguity into answerable questions.** I broke "reconciliation is broken" into a tree:
   - Is the **event model** consistent? (internal states vs. bank states vs. FX states)
   - Is the **timing model** consistent? (T+0, T+1, cut-off windows, timezones)
   - Is the **identity model** consistent? (which ID joins our txn to the bank's txn to the FX leg?)
3. **Defined a canonical vocabulary.** Introduced 7 well-defined states (`INITIATED`, `DEBITED`, `CONVERTED`, `REMITTED`, `SETTLED`, `FAILED`, `REVERSED`) with entry/exit conditions. Made this the single source of truth across all teams.
4. **Built a reconciliation ladder, not a reconciliation job.** Instead of one big job, I split it into three independent reconciliations — intent vs. debit, debit vs. FX, FX vs. remittance — so we could pinpoint *where* the break happened.
5. **Instrumented for future ambiguity.** Every state transition emits a structured event with `correlation_id`, `external_ref`, and `source_system`, so the next unknown problem is debuggable by query, not by Slack archaeology.
6. **Ran a weekly triage** with Finance + Ops where we walked the ladder. Kept a public "known gaps" register.

**Result**
- Unreconciled amount dropped from **~0.6% of daily TPV to < 0.02%** within two quarters.
- Finance stopped filing ad-hoc tickets — they query the dashboard directly.
- This same three-layer ladder became the template when we integrated our **next banking partner**; onboarding the partner took 4 weeks instead of the 10+ weeks we'd previously estimated.

**Manager takeaway I emphasize**
> The real output of resolving ambiguity isn't the fix; it's the **shared vocabulary and the self-service tooling** that prevents the next ambiguity from landing on your desk.

---

### Q1.2 — How do you bring clarity to ambiguous technical problems? Explain with an example.

**My framework (I say this out loud in the interview)**

I use a **4-step clarity loop** whenever a problem feels foggy:

1. **Write the problem, don't discuss it.** Discussion creates the illusion of alignment. A written one-pager exposes it.
2. **Separate what's unknown from what's unknowable.** Unknowns are research tasks. Unknowables are assumptions that need to be documented and revisited.
3. **Define "done" before defining "how".** If success criteria are fuzzy, architecture debates are pointless.
4. **Make the first milestone a learning milestone, not a delivery milestone.** A spike, a trace, a dashboard — something that *reduces uncertainty* before we commit to a design.

**Concrete example (Goldman Sachs — Risk Aggregation Latency)**

We had vague complaints from the trading desk that "VaR calculations feel slow on high-volatility days." No SLO existed, no single reproducer, and different teams blamed different layers — the distributed cache, the aggregation tier, or the source market data feed.

**How I applied the loop:**

- **Wrote it down.** I published a doc: *"VaR P99 on high-vol days — investigation plan."* Listed 6 hypotheses ranked by likelihood × blast radius.
- **Separated unknowns.** "Is it network?" was knowable via a one-day tcpdump spike. "Will desk tolerate 2s vs 5s?" was unknowable — I booked 20 min with the head trader to get a number. **Got "≤ 3s on EOD, ≤ 1.5s intraday"** as our working SLO.
- **Defined done.** P99 latency under the stated SLO on the worst 5 days of the prior quarter, replayed.
- **First milestone = a latency-attribution trace**, not a fix. We instrumented every hop of the aggregation pipeline with OpenTelemetry spans before touching any optimization.

**Outcome**
The trace showed that **85% of tail latency came from GC pauses during shard rebalancing**, not the cache or the feed — which was everyone's top suspicion. We fixed it with off-heap storage for hot slices and rebalance throttling. P99 dropped from ~7s to ~1.1s.

**The clarity win wasn't the fix** — it was that **the trace made the answer undebatable**, which killed three weeks of ongoing architectural arguments across teams.

---

## 2. Technical Depth & Learning Velocity

### Q2.1 — How do you assess and adopt a new technology for a project? Can you provide an example?

**My evaluation framework (5 gates)**

I tell candidates and peers I run every new tech through these five gates, in order — if it fails an early gate, I stop:

| Gate | Question | Fail Mode |
|---|---|---|
| 1. **Problem-fit** | Does this solve a problem I *actually have*, or one I anticipate in 12 months? | Resume-driven development |
| 2. **Operability** | Can my on-call engineer at 3 AM debug this? Is there a healthy community/vendor? | "Works great until it breaks" |
| 3. **Integration cost** | What does auth, observability, IaC, backup, and disaster recovery look like? | Hidden platform cost |
| 4. **Exit cost** | If we're wrong in 18 months, how painful is migration? | Lock-in trap |
| 5. **Team readiness** | Do we have (or can we build) 2+ engineers who deeply understand it? | Single point of knowledge |

I also run a **time-boxed PoC against a realistic load profile** — never against the vendor's benchmark — and write a *decision record* (ADR) whether we adopt or not, so the next team doesn't relitigate.

**Example — Adopting Temporal for workflow orchestration at Skydo**

**Context.** We had handwritten state machines for onboarding (KYC, KYB, bank linking) and settlement workflows. They were correct but fragile: retries, timeouts, and compensating actions were scattered across services. Multi-day workflows (e.g., KYC callback after 48h) required cron jobs and database polling.

**How I evaluated:**

- **Gate 1 — Problem-fit.** ✅ Durable state, long-running workflows, retries, human-in-the-loop steps — textbook Temporal use case.
- **Gate 2 — Operability.** Ran a 2-week PoC: killed worker pods mid-workflow, simulated DB failover, induced poison messages. Temporal recovered cleanly. Also evaluated **Cadence, AWS Step Functions, and a lighter option — just Postgres + SQS + state machine**. Step Functions' state-transition pricing was a dealbreaker at our volume; Cadence had weaker community momentum.
- **Gate 3 — Integration cost.** Required running a Temporal cluster (Temporal Cloud was an option; we chose self-hosted initially for data residency). Added ~2 weeks of IaC, observability wiring, and auth.
- **Gate 4 — Exit cost.** Workflow code in Go/Java is standard business logic — exit path is "rewrite the orchestration layer," which is contained.
- **Gate 5 — Team readiness.** I ran a 2-week internal workshop for 4 engineers, built a **reference workflow + test harness** as the canonical pattern, then had them teach it to the rest of the team.

**Rollout strategy.** Piloted on **one workflow — the partner-bank payout retry** — for 6 weeks in production before expanding. Migrated workflows in order of fragility, not glamour.

**Result.**
- Retired **~3K LOC of bespoke retry/scheduler logic.**
- Payout-related incidents dropped by ~40% QoQ.
- Onboarding new business-entity-type flows (Pvt Ltd, LLP, etc.) went from ~3 weeks to ~4 days because we reused workflow primitives.

**Honest trade-off I acknowledge:** We took on **operational complexity** (another stateful system to run). Worth it at our scale; would not have been worth it at 1/10th the volume.

---

### Q2.2 — Walk us through a recent situation where you had to quickly ramp up on a completely new technical domain or programming language to complete a critical feature. What was your process.

**Situation (Skydo — GenAI adoption & standards definition)**

Mid-2024, I was asked to lead the company-wide adoption of AI coding assistants (Cursor, Claude, Windsurf) and define guardrails. I had written production code in Java/Kotlin/Go/Python for a decade — but **prompt engineering, agent scaffolding, MCP servers, RAG pipelines, eval frameworks, and the fast-moving tooling ecosystem were a new domain**. And the stakes were real: we handle PII and payments data, so getting secrets/data handling wrong was not an option.

**Task**
Within ~6 weeks, (a) produce a usable org-wide standard, (b) personally be credible enough to review edge-case questions from senior engineers, and (c) avoid a security incident.

**My ramp-up process**

I use a **T-shaped learning approach**: go **deep** on one axis fast, go **broad** just enough to reason about the rest.

1. **Day 1–3: Build an internal mental map.**
   - Read the *official* docs end-to-end for Cursor, Claude Code, Anthropic's tool-use spec, and MCP. No blog posts.
   - Wrote my own one-page glossary: *agent loop, tool calls, context window, system prompt, sub-agents, skills, rules, hooks.*
   - Identified the **primitives** (prompt, context, tools, memory, eval) vs. the **vendor-specific wrappers** — so I could separate signal from marketing.
2. **Day 4–10: Hands-on depth on one slice.**
   - Built a **real internal tool**: an MCP server that exposed our observability stack to Claude. Shipped it to myself. That one project forced me to learn auth, tool schemas, error handling, and rate-limit behavior under load.
   - Kept a daily "what surprised me" log. Surprise is where my assumptions were wrong — that's where to double-click.
3. **Day 11–20: Breadth pass + steal from the best.**
   - Read the public rules/hooks/skills repos from companies I respected (Anthropic, Vercel, Sourcegraph). Extracted patterns.
   - Pair-programmed with two engineers already using the tools deeply — the **fastest ramp-up hack is always a 90-minute session with someone 6 months ahead of you**.
4. **Day 21–30: Write the standard & stress-test it.**
   - Drafted a Skydo-wide AI coding policy: approved tools, data classification rules, secrets handling, prompt/rule conventions, code-review expectations for AI-generated code.
   - Ran it past Security, Legal, and three senior engineers from different teams. Deliberately invited **the most skeptical** engineers to review — their objections sharpened the doc more than agreement would have.
5. **Day 31+: Ship, measure, iterate.**
   - Rolled out in waves — platform team first (highest-trust), then product teams.
   - Added a weekly "AI patterns" office hour so the standard kept evolving instead of calcifying.

**Result**
- Adoption reached **~90% of engineers** within a quarter, with zero security incidents.
- Measured PR cycle time improved by ~25% on backend services; code-review rework rate stayed flat (i.e., we didn't trade speed for defects).
- The *standards doc itself* became a hiring signal — candidates cited it in interviews.

**Manager takeaway**
> When ramping on a new domain fast, I never try to learn everything. I learn the **primitives deeply**, learn the **vocabulary** broadly, and then **ship one real thing** that forces me to confront what I don't know.

---

## 3. High Quality Bar

### Q3.1 — How do you define "high quality code" for a production service? Describe one specific measure you introduced to your team to improve the overall quality bar.

**My definition of high-quality code (in priority order)**

I define quality along **five dimensions**, and I rank them in this order deliberately:

1. **Correctness under failure**, not just under the happy path. Idempotency, retries, partial failures, poison messages.
2. **Observability by default.** If I can't explain *why* a request failed from logs/metrics/traces alone, the code isn't done.
3. **Readable to the next engineer**, not clever. Optimize for the p50 reader, not the p99 author.
4. **Changeability.** Tests exist at the right layer (not just unit; contract + integration where the risk lives). New engineers can make a safe change in < 1 day.
5. **Performance fit**, not maximum performance. Meets the SLO with headroom; doesn't over-engineer.

**Quality ≠ test coverage %.** I've seen 90%-coverage services that were unmaintainable and 60%-coverage services that were rock-solid. I measure **outcomes** (incident rate, MTTR, change failure rate, PR cycle time) — not proxies.

---

**Specific measure I introduced at Skydo — the "Production Readiness Checklist + Ownership Review"**

**Context.** We were shipping fast, but new services had wildly inconsistent quality — some had great dashboards, others had none; some had runbooks, some didn't; idempotency was handled in three different ways.

**What I introduced.**

A **mandatory Production Readiness Review (PRR)** before any new service (or major new endpoint) could take production traffic. The PRR has a 30-item checklist grouped into 6 categories:

- **Correctness.** Idempotency keys defined? Retry semantics documented? Poison-message handling?
- **Observability.** Golden-signal dashboards? Structured logs with `correlation_id`? Alerts tied to SLOs (not to symptoms)?
- **Resilience.** Timeouts on every outbound call? Circuit breakers where relevant? Graceful degradation path?
- **Security.** Secrets in KMS? PII classification documented? Authn/z matrix written?
- **Operability.** Runbook with "top 5 alerts + what to do"? On-call owner named? Rollback procedure tested?
- **Data.** Schema migration strategy? Backfill plan? Data retention + deletion policy?

**How I made it stick (this is the part most teams get wrong):**

- It's **a peer review, not a gate by management.** A senior engineer from *another team* runs it. This creates cross-team learning and removes the "manager-as-bottleneck" failure mode.
- It's **a living Google Doc template, not a Jira workflow.** Friction kills adoption.
- We review it again **30 days post-launch** against actual incidents — and the checklist evolves based on what *actually* hurt us.

**Result**
- Production incidents dropped **~30% YoY.**
- MTTR improved because runbooks and dashboards were no longer an afterthought.
- New-hire ramp-up improved: the PRR doc for any service is now the best onboarding read for that service.
- The checklist has been adopted by two teams I don't manage, which is the strongest signal it works.

---

### Q3.2 — Give an example of a time your service experienced a production issue due to a quality lapse. What was your immediate response, and what long-term systemic change did you propose?

**Situation (Skydo — duplicate payout incident)**

About 18 months ago, a bug in our payout service caused **~0.3% of payouts over a 90-minute window to be submitted twice** to our banking partner. The root cause: a retry in the upstream service re-invoked our payout API with the **same business payload but a new request ID** because of a client-side bug, and our idempotency key was bound to the request ID rather than the business intent (the `payout_intent_id`).

The financial exposure was contained (partner caught most duplicates on their side), but **5 payouts actually settled twice** and we had to claw back funds. Customer trust and partner trust both took a hit.

---

**Immediate response (first 2 hours)**

I followed a disciplined incident playbook — and I was explicit about roles so nobody was both firefighting *and* communicating:

1. **Stop the bleeding first.** Feature-flagged the payout retry path off within 10 minutes. Accepted the cost of elevated latency over the cost of more duplicates.
2. **Declared roles.** I was Incident Commander. Assigned one engineer to investigation, one to comms, one to partner liaison.
3. **Contained the blast radius.** Worked with the banking partner to pause in-flight duplicates on their side. For the 5 that settled, initiated reversal within the same business day.
4. **Transparent comms.** Sent a customer-facing status update within 45 minutes — conservative language, concrete commitment on reversal timelines. **I also proactively informed the CEO and Head of Partnerships** before they heard it from the partner.
5. **Wrote a preliminary timeline within the same day** — not a full RCA, but enough for leadership to trust we understood what happened.

**Immediate tactical fix (within 24h):** Re-bound the idempotency key to `payout_intent_id` (the business-level identifier), added a duplicate-detection window at the API layer, and wrote a backfill job to re-check the prior 30 days for similar patterns (found none).

---

**Long-term systemic changes I proposed and owned**

I resisted the temptation to stop at "we fixed the bug." The bug was a symptom of three systemic gaps:

1. **Idempotency was a convention, not a contract.**
   - Introduced an **Idempotency Standard** as part of the Production Readiness Review: every money-moving endpoint must bind idempotency to a **business-level intent ID** (not a transport-level ID), must persist the idempotency record **atomically with the side effect**, and must define a TTL.
   - Built a reusable library so engineers couldn't get it wrong by default.

2. **We had no "property-based" tests for financial invariants.**
   - Introduced invariant tests that continuously assert properties like *"for any payout_intent_id, the count of debits equals the count of credits ± in-flight."* These run as scheduled jobs in production against the live ledger, not just in CI.
   - When an invariant breaks, it pages on-call **before** the customer notices.

3. **Post-mortem culture was inconsistent.**
   - Established a **blameless post-mortem template** with a mandatory "systemic contributors" section (not just "root cause"). I banned the phrase "human error" as a terminal cause — it's always a system that let a human make that error.
   - Created a quarterly **"Pattern Review"** where we look across all post-mortems to find themes. This is how we realized idempotency was a cross-cutting issue, not a one-off.

---

**Result**
- Zero duplicate-payout incidents in the 18 months since.
- The invariant-test framework has caught **three other classes of bugs** before customer impact (one wrong-currency bug, one double-credit bug in a new feature, one stale-FX-rate bug).
- The Pattern Review surfaced two more systemic issues — a timeout-propagation gap and a schema-migration gap — which we've since addressed.

**Manager takeaway I emphasize**
> The interviewer is listening for whether you treat incidents as **failures of the system** or failures of a person. My principle: *a production incident is a free audit. Waste it at your peril.* The best engineering orgs convert one incident into three systemic improvements — the immediate fix, the test that would have caught it, and the process that would have prevented the class.

---

## Quick-Reference Crib Sheet (for day-of)

| Theme | Go-to story | Key metric |
|---|---|---|
| Ambiguity — structuring | Skydo reconciliation ladder | 0.6% → 0.02% unreconciled |
| Ambiguity — clarity framework | Goldman Sachs VaR latency | P99 7s → 1.1s |
| Tech adoption | Skydo Temporal rollout | 3K LOC retired, 40% incident drop |
| Rapid ramp-up | Skydo GenAI standard | 90% adoption, 25% PR cycle time ↓ |
| Quality definition | PRR checklist at Skydo | 30% YoY incident drop |
| Quality lapse incident | Duplicate payout | 0 recurrences in 18 months |

**Universal tips:**
- Every answer ends with a **measurable outcome** and a **manager-level takeaway** (the principle you pulled forward).
- If you don't know a number, say "I don't remember the exact number, but the direction was X" — never invent.
- When asked about failure, own it in the first sentence. Deflection is the fastest way to lose an EM loop.
