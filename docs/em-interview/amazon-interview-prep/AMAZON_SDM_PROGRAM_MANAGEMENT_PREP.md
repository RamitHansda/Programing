# Amazon SDM — Program Management Interview Prep
**Role:** SDM | Based on Ramit Hansda's resume

> Every answer below is grounded in your actual work at Skydo, Goldman Sachs, and Moneyview.
> Adapt numbers you cannot disclose. Lead every answer with business/customer context, not engineering detail.

---

## Your Core Program Management Stories (Quick Map)

| Story | Company | Key Metric | Best used for |
|-------|---------|-----------|--------------|
| ISO 27001 + SOC 2 certification program | Skydo | 4 months, 40+ gaps closed, enterprise client signed | Cross-team program, ownership, risk, stakeholder comms |
| Payments & settlement platform 0→production | Skydo | 10K+ txn/day, ~30% fewer incidents | Complex program, standards, delivery under ambiguity |
| Automated onboarding (5+ entity types) | Skydo | Hours → minutes, multi-team integration | Dependencies, customer obsession, delivery |
| Payment pipeline rebuild (manual → automated) | Moneyview | 60% reduction in manual ops, 2-engineer team | Delivery under constraints, scope, recovery |
| Distributed job scheduler (simple over complex) | Skydo | 3 weeks vs 4 months estimated | Scope tradeoff, simplification, invent & simplify |
| Stale VaR root cause investigation | Goldman Sachs | Promoted to VP in 1 yr, eliminated the issue | Dive deep, cross-functional, operational excellence |
| DynamoDB migration pushback | Skydo | PostgreSQL retained, architecture policy written | Backbone, technical tradeoff, stakeholder alignment |

---

## Q1: "Tell me about a large, cross-team program you led end-to-end."

**Use:** ISO 27001 + SOC 2 certification at Skydo

---

**S — Situation:**
At Skydo we were handling international payments for thousands of SMEs with no formal information security function. During a critical enterprise due diligence, the client flagged that we had no certifiable security posture. This was a business-blocking issue — without certification, we couldn't close the deal and couldn't credibly serve enterprise customers in regulated industries.

**T — Task:**
I stepped in as Chief Information Officer on top of my EM responsibilities and owned the entire program: achieving ISO 27001 and SOC 2 Type II simultaneously within 4 months. This touched every team — engineering, product, ops, HR — and required external auditors.

**A — Actions:**
- Recognized I needed domain expertise fast. Instead of building it in-house, I hired a Virtual CISO as a time-bound partner — deliberate resourcing to move faster without compromising quality.
- Ran a full audit against the ISO 27001 control framework. Found 40+ gaps: encryption at rest, access control policies, incident response, DLP.
- Split the program into three parallel workstreams: **engineering controls** (KMS key rotation, mTLS enforcement, DB write restrictions on immutable ledger tables), **policy and process** (data classification, incident response, security training), and **external audit readiness**.
- Created milestones with clear owners and weekly standups. I was the DRI across all three workstreams, escalating blockers to the CEO within 24 hours when they were business-level decisions.
- The hardest dependency I didn't control: the auditors' review schedule. I de-risked by front-loading documentation evidence so the audit review sessions were verification, not discovery.

**R — Result:**
Achieved both ISO 27001 and SOC 2 Type II in 4 months. The enterprise client signed. We went from zero certifiable security posture to two internationally recognized certifications — a competitive moat that most FinTech startups at our size didn't have. The security controls I implemented also reduced our production attack surface measurably.

**Learning:**
- **Lock external dependencies first, then plan backward.** We underestimated how rigid the auditor's review windows were. Locking the audit date on day 1 and working backward would have eliminated 2 weeks of late scrambling.
- **Hire for gaps faster than you think you need to.** The Virtual CISO decision was right, but I delayed it by 3 weeks trying to figure it out internally first. In a time-boxed program, borrowed expertise is cheaper than lost time.
- **Security uplift only sticks if it's tied to product milestones.** Engineers treated it as a side task until I made it a hard gate on the next product launch. Make compliance work visible in the same systems the team tracks product delivery.

---

## Q2: "A critical milestone was at risk. What did you do?"

**Use:** Payments platform delivery at Skydo — scope/date tradeoff

---

**S — Situation:**
During a major expansion of Skydo's payments platform to support a new international corridor, we discovered mid-way that the proposed ledger implementation was fragile — a running balance model that would create write bottlenecks under concurrent transactions and make reconciliation impossible.

**T — Task:**
Two weeks to the committed launch date. I had to make a call: cut scope and ship the right model on time, or explain why the shortcut was not viable — knowing the business had already aligned a banking partner on the date.

**A — Actions:**
- Stopped the existing sprint and ran a focused 1-day design session with the leads. Mapped exactly what the "shortcut" model would fail at: concurrent write bottleneck, no auditability, impossible to reverse a transaction correctly.
- Brought two concrete options to leadership with business-language risk: **Option A** — ship on the date with a fully correct immutable journal model (I would pair with the team to deliver it); **Option B** — ship the simplified model and accept that a future incident would produce financial discrepancies we couldn't reconcile.
- I recommended Option A with a clear delivery plan: freeze scope to the ledger core, defer non-critical corridor features to week 3, re-sequence the sprint so the ledger foundation was done in 3 days, then build on top.
- Communicated to the banking partner: same date, slightly narrower initial feature set, with phase 2 explicitly scheduled.

**R — Result:**
Shipped on the committed date with the correct model. Six months later a payment gateway failure caused duplicate submissions — our idempotency and reversal logic caught and corrected it automatically with zero money lost. The shortcut would have caused unrecoverable financial discrepancies.

**Learning:**
- **Surface implementation risk during planning, not mid-sprint.** We caught the ledger flaw 2 weeks before launch. A design review with failure-mode analysis at kickoff would have caught it at week 1. I now require an explicit "how does this fail under load and concurrency?" question in every design review.
- **Always present options with business-language costs, not just engineering arguments.** "This model won't scale" loses to a deadline. "This model will produce financial discrepancies we cannot reconcile during the next gateway failure" wins.
- **Write scope decisions down the moment they are made.** The team's trust in the tradeoff came from a written record — not my memory of the conversation.

---

## Q3: "How do you manage dependencies you don't control?"

**Use:** Moneyview — multiple payment gateway integrations under a 2-person team

---

**S — Situation:**
At Moneyview, I was redesigning the payment demand generation and reconciliation pipeline with a 2-engineer team. We needed to integrate three payment gateways simultaneously — all with different APIs, different SLA windows, different acknowledgement behaviors, and none of which we could change or accelerate.

**T — Task:**
Own integration delivery for all three gateways while ensuring our pipeline design didn't create a single point of failure if any one gateway was slow, down, or inconsistent.

**A — Actions:**
- First, wrote a **written interface contract** for each gateway: expected inputs, outputs, failure modes, timeouts, and idempotency guarantees (or lack thereof). This surfaced the differences early rather than during integration.
- Built **test doubles** for each gateway so we could develop and test our retry and reconciliation logic before the actual gateway connections were stable.
- Designed the pipeline so each gateway was isolated behind its own adapter — a failure or timeout in one didn't block processing for others.
- For the hardest dependency — one gateway had undocumented batch settlement files — I escalated to our banking relationship manager with a concrete ask (sample file format + SLA), not just "we need help."
- Tracked gateway-level health as a first-class metric in our reconciliation reports so I had evidence when a gateway was producing silent failures.

**R — Result:**
All three integrations shipped on time. 60% reduction in manual operations. The reconciliation system caught a systematic discrepancy with one gateway that had been silently failing at low volume — something no human would have caught. That finding also gave us leverage in a commercial renegotiation with that gateway.

**Learning:**
- **Test doubles are not optional for external dependencies.** Without them, your development pace is hostage to the third party's availability. Build the mock first, integrate the real system second.
- **Silent failures are the most dangerous kind in financial systems.** The gateway discrepancy was losing money at low volume without triggering any alarm. The learning: instrument for correctness, not just uptime. A system can be "up" and still be wrong.
- **Written interface contracts create accountability on both sides.** When one gateway failed to match the agreed SLA, the contract gave us concrete grounds to escalate to the relationship manager — not just a complaint, but a documented deviation.

---

## Q4: "Tell me about a time you had to cut scope to hit a date."

**Use:** Distributed job scheduler at Skydo — simplify vs. build custom

---

**S — Situation:**
At Skydo, the team was planning to build a custom workflow orchestration engine for financial operations — estimated 3–4 months. It was on the roadmap because operations like onboarding, reconciliation, and payout processing all needed reliable retries, state visibility, and failure recovery.

**T — Task:**
As EM, it was my job to pressure-test whether 3–4 months was justified. We had other priorities — the new corridor launch and the ISO certification — and 4 months of engineering on an internal engine felt like a significant bet.

**A — Actions:**
- Ran a scoping session: what did we actually need in v1? Reliable retries, state persistence, per-step visibility, failure recovery. We did not need a full DAG engine, complex branching, or multi-tenancy.
- Evaluated what we already had: SQS for durable queueing, PostgreSQL for state. A thin abstraction on top of these could handle 95% of our use cases with 20% of the complexity.
- Made the call to descope to a simpler design. Explicit cut: no custom UI, no DAG support, no job dependency chaining (we didn't need it yet). The abstraction was designed to be **replaceable** — if we outgrew it, migration was scoped as a future isolated refactor, not a rewrite.
- Communicated to the team: this is the v1 scope, here's the roadmap item for v2 when we have evidence we've outgrown it.

**R — Result:**
Shipped in 3 weeks. Running in production for over a year with minimal changes. Engineering capacity freed up for the corridor launch. The "cut" features were never actually needed — which validated the decision.

**Learning:**
- **Estimates for custom tooling are almost always inflated by hypothetical requirements.** The team was scoping for a system that could handle DAGs, multi-tenancy, and complex branching — none of which we needed. Before any infra build, I now ask: "what is the simplest thing that solves the actual problem we have today?"
- **Design the abstraction to be replaceable, not perfect.** If we'd built the simple scheduler as a leaky abstraction, migration would have been painful. Because we hid it behind a clean interface, replacing it is a future isolated decision, not a rewrite.
- **Deferred scope items need a named condition for revisiting.** "We'll add DAG support when we have evidence we've outgrown v1" is a better close than "we'll do it later" — it prevents scope creep while keeping the door open.

---

## Q5: "How do you prioritize when multiple things are P0?"

**Use:** Skydo — payments platform, onboarding automation, ISO certification all in parallel

---

**S — Situation:**
In 2022–2023 at Skydo, I had three simultaneous high-priority programs running: the payments platform scaling (direct revenue), the onboarding automation (direct customer drop-off), and the ISO 27001/SOC 2 certification (enterprise client at risk). All three had real deadlines. My team had 12 engineers and finite capacity.

**T — Task:**
Make an explicit prioritization call and allocate accordingly — not pretend all three were equally resourced.

**A — Actions:**
- Forced ranked these by **cost of delay**: ISO certification was a binary gate (enterprise client signs or doesn't), so it had the highest urgency even if it felt less visible day-to-day. Onboarding automation had direct revenue impact through conversion rates. Platform scaling was important but could be delivered incrementally without a hard external deadline.
- Split the team into focused squads rather than having everyone context-switch: 2 engineers dedicated to ISO engineering controls, 4 engineers on onboarding automation, 4 on platform work, 2 floating for critical path support and on-call.
- Set **explicit WIP limits**: each squad had one active workstream. I killed two initiatives that had been "in flight" but were actually fractional attention.
- Created a **weekly program review** where I tracked milestone confidence for all three — not sprint velocity, but: are we on track for the hard dates?
- Where timelines compressed, I made explicit scope decisions: for onboarding v1, we launched fully automated for 3 entity types and committed to the other 2 in the next cycle, not "eventually."

**R — Result:**
ISO 27001 and SOC 2 achieved in 4 months. Onboarding automation shipped on time across 5 entity types (hours → minutes). Platform maintained 10K+ transactions/day throughout. No team burned out — because the allocation was explicit, not a vague "do everything."

**Learning:**
- **"Everything is P0" is a symptom of missing prioritization, not a workload problem.** When I forced a ranked order with cost-of-delay reasoning, the team immediately understood what to protect and what to defer. The clarity itself reduced stress.
- **Kill zombie initiatives before you start new ones.** The two "in-flight" projects consuming fractional attention were costing more in context-switching tax than their partial progress was worth. Explicit stops are as important as explicit starts.
- **Reserve ~20% capacity or you will spend 40% firefighting.** The two floating engineers absorbed on-call spikes and unplanned integrations without derailing squads. An over-allocated plan is not a plan — it is a guaranteed miss.

---

## Q6: "Tell me about a program that failed or seriously under-delivered."

**Use:** Early Goldman Sachs — stale VaR investigation that exposed a process gap

---

**S — Situation:**
At Goldman Sachs, the market risk aggregation platform was intermittently producing stale VaR numbers under high load. We'd shipped several patches — each seemed to fix it temporarily, then it came back. This had been dragging for weeks across multiple engineers.

**T — Task:**
As the engineering lead on this platform, I needed to own root cause resolution — not another workaround. Stale VaR numbers are a regulatory and financial exposure issue, so "intermittent" was not acceptable.

**A — Actions:**
- The first thing I changed was the **investigation process**. We'd been treating it as an individual debugging task. I converted it to a structured **incident investigation**: one DRI (me), a dedicated war room session, and explicit instrumentation before any more patches.
- Added detailed latency histograms and capture points at each aggregation stage of the in-memory distributed compute cluster.
- Found the root cause: during market open — when data ingestion spiked — a sharding imbalance caused specific nodes to fall behind on replication. The primary was acknowledging writes before the standby confirmed. During failover, a small window of data could be lost. The VaR calculation was reading from replicas with no staleness check — so it would serve old data without any error.
- **Where we under-delivered earlier**: the team had been debugging without instrumenting. We were guessing. Each patch addressed a symptom, not the cause. That wasted 3–4 weeks.

**R — Result:**
Fixed the synchronous replication configuration, implemented a staleness check on the read path, improved the sharding algorithm to balance by throughput instead of key count. Stale VaR issue eliminated. Wrote a post-mortem that was adopted by the global risk platform team across other regions.

**Learning:**
- **Instrumentation before investigation is non-negotiable in distributed systems.** We lost 3–4 weeks patching symptoms because we were debugging on intuition. I now treat "add observability first" as a hard gate before any investigation begins — not a nice-to-have after the patch.
- **Repeated failures at the same layer indicate a process failure, not just a technical one.** Each workaround felt like progress, but the recurring issue was a signal that our incident process was broken — no DRI, no structured investigation, no root cause requirement. A single bad investigation process can waste more time than the original bug.
- **Post-mortems are wasted if they don't cross organizational boundaries.** Writing a post-mortem that stayed within our team would have let other regions repeat the same failure. Sharing it globally turned one team's pain into a platform-level fix.

---

## Q7: "How do you communicate program risk to leadership?"

**Use:** Skydo — DynamoDB migration pushback

---

**S — Situation:**
At Skydo, leadership pushed to migrate our primary database from PostgreSQL to DynamoDB based on an investor who believed we should be "all-in on AWS." The migration was framed as a strategic initiative.

**T — Task:**
I believed this was the wrong call for our payments ledger specifically. Our use case relied on ACID transactions, multi-row JOINs for reconciliation, and row-level locking for balance checks — none of which DynamoDB supports natively. But I couldn't just say "engineering says no."

**A — Actions:**
- Prepared a **written technical trade-off document** — specifically structured to be useful for a business decision, not a database argument. Not "DynamoDB is wrong." Instead: what we'd **lose** (strong consistency, multi-row transactions, reconciliation queries), what we'd **gain** (managed scaling, reduced ops overhead), and the **engineering cost** to replicate PostgreSQL's guarantees in DynamoDB (estimated 3–4 months of careful migration plus ongoing complexity).
- Included **concrete failure scenarios** with business language: "if a payment gateway sends a duplicate submission during a DynamoDB partition event, here's the race condition and the financial exposure."
- Proposed a **middle path**: migrate non-financial workloads to DynamoDB where eventual consistency was acceptable (event logs, session data, notification history), and keep PostgreSQL for the ledger and reconciliation core.
- Presented this in a 30-minute session with two slides: the risk framing and the phased plan. Not a debate — options with costs, a recommendation, and my reasoning.

**R — Result:**
Leadership agreed. PostgreSQL retained for the ledger. Non-financial workloads moved to DynamoDB. This became our official **data architecture policy** — written and shared with future engineers as the rationale behind the choice. The key: I made technical risk **visible and quantifiable**, not a personal preference.

**Template I use for risk communication:**
- Current state + trigger (what changed)
- Business impact (customers, revenue, compliance)
- Options A / B / C with tradeoffs
- Recommendation + what I need from them

**Learning:**
- **Technical risk only moves leadership when it is framed as business risk.** "DynamoDB doesn't support multi-row transactions" is an engineering statement. "A gateway duplicate event during a DynamoDB partition would produce a financial discrepancy we cannot reconcile" is a business statement. Same fact, entirely different impact.
- **A middle path almost always exists and is almost always the right recommendation.** Presenting "PostgreSQL everywhere vs DynamoDB everywhere" as a binary would have stalled the conversation. The workload-based split turned a disagreement into a documented architecture policy everyone could commit to.
- **Decisions made under social pressure (investor, VP opinion) without technical grounding will resurface as incidents.** The DynamoDB push came from an investor opinion. Without the written trade-off document, it might have been implemented by a future engineer with no context. The policy document is the long-term protection.

---

## Q8: "How do you ensure operational readiness at launch?"

**Use:** Payments & settlement platform at Skydo — 10K+ txn/day

---

**S — Situation:**
At Skydo, we were launching new international payment corridors and payment methods on a platform processing 10K+ transactions daily. A failure in production is not a UX bug — it's a financial discrepancy that could affect real money movement.

**T — Task:**
Define and enforce a launch readiness bar that the team could execute consistently, not just for high-stakes launches but as a repeatable standard.

**A — Actions:**
Created a **launch readiness checklist** that became part of every release cycle:

- **Idempotency validation**: every payment entry point tested with duplicate submissions before launch.
- **Reconciliation dry run**: the new corridor's journal entries validated against double-entry rules (∑ DEBIT == ∑ CREDIT) in staging before production.
- **Failure mode coverage**: explicit test cases for gateway timeout, partial acknowledgement, duplicate response, and network partition.
- **Dashboards and alerts**: before any launch, monitoring for the new flow was live — error rate, latency, reconciliation drift — with **PagerDuty** thresholds set and tested.
- **Runbook**: written and reviewed by the on-call engineer, not just the author.
- **Rollback criteria**: agreed before launch, not debated during an incident. If error rate exceeded X% or reconciliation drift exceeded Y, we rolled back automatically.
- **Gradual rollout**: new corridors launched at 5% traffic first, with a manual promotion step at 24h if metrics were clean.

**R — Result:**
~30% reduction in production incidents year-over-year. The launch checklist also became an **onboarding tool** — new engineers understood what "production-ready" meant for a financial system by reading it, not by experiencing an incident first.

**Learning:**
- **Rollback criteria must be agreed before launch, not during an incident.** When a production issue is live, the pressure to "just wait and see" is enormous. Pre-committed thresholds remove that debate at the worst possible moment.
- **Readiness checklists degrade into checkbox theater unless a senior engineer reviews the evidence, not just the ticks.** After our first few launches, I added a mandatory 30-minute readiness review where the on-call engineer walked through actual metric dashboards and the runbook — not just confirmed boxes were checked.
- **The definition of "production-ready" must include the team's ability to operate it, not just ship it.** The most common failure mode I've seen is a service shipped with no runbook, no alert owner, and no one trained on the failure behavior. Bake operability into launch criteria from the start.

---

## Q9: "Tell me about your planning process."

**Framework (your words):**

"I anchor quarterly planning on three inputs: **customer roadmap** (what capabilities unblock growth), **operational health** (incident data, SLO performance, on-call load), and **realistic capacity** (actual engineers, hiring timeline, compliance overhead).

At Skydo, I run planning in two passes. First pass: themes and bets — 3–5 high-level outcomes we want to achieve this quarter, each with a clear success metric. Second pass: capacity reality check — map themes to team capacity including on-call, debt, and ramp time for new hires. I force a **committed vs aspirational** split so we don't overpromise.

Every theme has:
- **Definition of done** (not just feature shipped — launched, monitored, supportable)
- **Milestones** (intermediate checkpoints, not just the end)
- **Top 3 risks** with owners and trigger dates for escalation
- **Dependencies** on other teams or platforms, each with a named counterpart

At Goldman Sachs, planning was more structured — annual OP doc, cross-team dependency review, capacity-based commitment. Same principles, more formal cadence.

The anti-pattern I avoid: planning to 100% of capacity. I keep ~20% unallocated for incidents, interruptions, and the unexpected work that always shows up in financial systems."

**Learning:**
- **Committed vs aspirational is the most important split in a plan.** Without it, everything is technically "committed" and nothing is actually predictable. The aspirational backlog gives the team stretch goals without creating broken promises with stakeholders.
- **Planning is a forcing function for surfacing hidden dependencies.** At Goldman Sachs, the annual planning exercise was the one time cross-team leads were in a room together. The dependencies that emerged were not surprises — they were just never made explicit until that forcing function. I now run a dedicated dependency mapping session as a standalone step before finalizing any quarterly plan.
- **Operational load is always underestimated in planning.** On-call, incident response, and compliance overhead consistently consume more than teams expect. I budget it explicitly in headcount allocation — not as a line item that "won't happen this quarter."

---

## Q10: "How do you run a cross-team program without being a bottleneck?"

**Framework (your words):**

"I assign **DRIs per workstream** — at Skydo, the onboarding automation program had one DRI per entity type (Sole Prop, LLP, Pvt Ltd). My job was alignment, risk, and escalation — not routing every decision.

I keep the program rhythm lightweight but consistent:
- **Weekly written status**: headline (on track / at risk / off track), progress since last week in outcomes not tasks, top risks with owners, decisions needed.
- **Biweekly cross-team sync**: only blocking dependencies and interface changes — not a status tour.
- **Async-first**: for non-blocking decisions, I write the context and options in a doc and give a decision deadline. This forces me to think clearly and respects engineers' time.

The most important habit: **escalate early with options, not noise**. When I saw the ISO 27001 audit date conflicting with a critical payment launch at Skydo, I brought leadership two options — pause the audit preparation for 2 weeks and accept a 2-week slip on certification, or add a contractor for 3 weeks to parallelize. They chose option 2. That conversation took 15 minutes because I'd done the thinking before escalating."

**Learning:**
- **DRI ownership only works if the DRI has genuine decision authority, not just accountability.** Early on, DRIs at Skydo owned accountability but had to escalate almost every decision. I fixed this by writing explicit decision rights per workstream — what the DRI could decide alone, what needed my sign-off, and what needed leadership. Clarity of authority is what makes delegation real.
- **Bottlenecks are often invisible until you track decision latency.** I started noticing that certain decisions took 3–4 days to resolve because they flowed through me. Tracking how long decisions sat unanswered surfaced where I was the choke point — and pushed me to either delegate authority or batch my reviews more efficiently.
- **Async-first communication reduces meeting load but requires higher-quality written context.** When I moved to async decision docs, the quality of the options I wrote had to improve significantly — vague docs just generated clarifying questions in Slack, which was worse than a meeting. Write the doc as if the reader has no context and limited time.

---

## Cheat Sheet — Your Numbers to Know Cold

| Claim | Number |
|-------|--------|
| Skydo team size | 12 engineers (grew from 5) |
| Payment transactions/day | 10K+ international |
| Incident reduction | ~30% year-over-year |
| ISO 27001/SOC 2 timeline | 4 months |
| Control gaps addressed | 40+ |
| Onboarding time improvement | Hours → minutes |
| Entity types automated | 5+ |
| Manual ops reduction (Moneyview) | 60% |
| Job scheduler build time | 3 weeks (vs 4-month estimate) |
| Goldman Sachs promotion | VP within 1 year |

---

## Red Flags to Avoid in Every Answer

- Saying "the team did X" — make **your** role explicit.
- Describing what *should* happen — give a **specific situation**.
- Ending with the ship — include the **business/customer outcome**.
- Vague risk language — say **what** the risk was and **what you did** about it.
- Avoiding failures — pick one real under-delivery, own it, and show what changed.

---

*Companion: `AMAZON_SDM_INTERVIEW_PREP.md` for LP-mapped behavioral answers and system design.*
