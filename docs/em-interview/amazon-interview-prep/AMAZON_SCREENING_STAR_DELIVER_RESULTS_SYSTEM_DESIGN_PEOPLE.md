# Amazon Screening — STAR Stories
**Topics:** Deliver Results · System Design · Team & People Management  
**Use:** Friday screening — memorize metrics, speak ~90–120 sec per story, leave depth for probes

---

## BEFORE YOU START

**2-min opener (use once):**
> "I'm Ramit — Engineering Manager with 10+ years building backend platforms. At Skydo I lead engineering for a cross-border payments platform processing 10K+ international transactions a day — architecture, delivery, hiring, and team growth for 12 engineers. Before that I was VP at Goldman Sachs on market-risk infrastructure at petabyte scale. I also served as CIO at Skydo and landed ISO 27001 and SOC 2 Type II. Happy to go deep on delivery, systems, or people — whatever you want to start with."

**Screening rules:**
1. Lead with **you** — "I decided / I owned / I measured" — not "we."
2. Every story ends with a **number**.
3. Name the **trade-off** once: what you gave up to get the result.
4. If they ask system design as a whiteboard: clarify → NFRs → high-level → deepest component → failures. STAR stories below still cover Invent & Simplify / Dive Deep probes.

**Metrics card (memorize):**
| Metric | Number |
|---|---|
| Team size | 12 eng (Skydo); 9 eng (GS) |
| Mentored | 8 engineers |
| Volume | 10K+ intl txn/day |
| Incidents | ~−30% YoY |
| Recon gaps | ~0.6% → &lt;0.02% TPV |
| Onboarding | hours → minutes |
| Moneyview ops | −60% manual |
| Certs | ISO 27001 + SOC 2 in ~4 months |
| GS | VP in &lt;1 year |

---

# PART A — DELIVER RESULTS

> LP: *Focus on key inputs, deliver with quality and speed, rise despite setbacks, never settle.*

### Question bank
- Tell me about a time you delivered under significant constraints.
- Tell me about a time you hit a commitment despite setbacks.
- Tell me about a time you had to reprioritize and still delivered.
- Tell me about a project others thought couldn't ship on time.

---

## A1 — PRIMARY: Moneyview pipeline with 2 engineers (−60% ops)

**Use for:** constraints, small team, still shipping quality.

**S:** At Moneyview, debit instructions ran at millions/day. Failed retries were re-triggered manually from dashboards. SLAs were slipping. We had **2 engineers** and couldn't stop production.

**T:** Redesign demand-generation + reconciliation so retries and mismatch detection were automatic — without a rewrite freeze.

**A:**
1. Cut scope into phases by **impact per week of eng**, not by "build everything."
2. **Phase 1 (3 weeks):** idempotent retries + SLA-based scheduling — each instruction owned retry state, backoff, terminal failure. Ran in parallel with the manual path for 2 weeks before cutover.
3. **Phase 2 (weeks 4–7):** auto-recon between what we submitted and what gateways acknowledged.
4. **Phase 3 (weeks 8–12):** 3 gateways behind resilient pipelines so one partner failure didn't cascade.

**R:** Manual ops **−60%** (~20 eng-hours/week back). SLA breaches → near zero. Caught a silent gateway mismatch humans couldn't have found at volume.

**One-liner framing:**
> "Constraints change the approach, not the commitment. Two engineers was the constraint; eliminating SLA breaches was the commitment."

**Probes:**
- *What did you cut?* → Fancy orchestration UI; DAG engine; full multi-gateway smart routing until Phase 3.
- *What went wrong mid-way?* → One gateway's ACK format differed — we froze cutover for that partner, fixed contract tests, then resumed.
- *How did you know it was done?* → Manual queue volume + SLA breach count + recon exception rate — not just "deployed."

---

## A2 — BACKUP: Onboarding automation (hours → minutes) under compliance pressure

**Use for:** business pressure + quality bar + still delivering.

**S:** Skydo onboarding for SMEs took hours (docs → human review → activate). Funnel drop-off was high. Business wanted "more reviewers."

**T:** Cut activation time without weakening KYC/sanctions controls.

**A:**
1. Mapped journey end-to-end; pushed **automate 80% clean cases**, human-in-loop for 20% edge cases — not "hire more people."
2. Built rule-based decisioning across **5+ entity types** (Sole Prop, LLP, Pvt Ltd, Partnership, SME) as config, not hardcoded paths.
3. Parallel async workflow: docs + KYC + sanctions + provisioning with per-step retry/recovery.

**R:** Onboarding **hours → minutes** for clean cases; drop-off down; reviewers redeployed to exceptions; faster activation → earlier first transaction.

**Trade-off named:**
> "We delayed full entity coverage by ~2 weeks to get the configurable engine right — so the next entity type was a config change, not a project."

---

## A3 — BACKUP: ISO 27001 + SOC 2 in ~4 months (while still shipping)

**Use for:** "despite setbacks / parallel commitments / deliver when it wasn't your job."

**S:** Enterprise deal blocked — no certifiable security posture. Not on the eng roadmap. Nobody owned it.

**T:** Land ISO 27001 + SOC 2 Type II without abandoning payment delivery.

**A:** Took CIO ownership; hired Virtual CISO for framework; closed **40+ control gaps** (KMS rotation, mTLS, least-privilege on ledger writes, DLP, IR playbooks); tied security gates to launch criteria; made capacity trade-offs explicit with leadership.

**R:** Both certs landed in **~4 months**; enterprise deal closed; security became ongoing practice, not a binder.

---

## A4 — IF THEY ASK "MISSED / ALMOST MISSED A COMMITMENT"

**S:** Partner webhook integration — I under-tested edge ID formats / retry storms. Happy-path tests passed.

**T:** Contain, fix class of bug, still protect customer money.

**A:** Caught via recon before customer impact ballooned; froze path; fixed dedupe; added partner contract tests + idempotency checklist on money PRs + staging webhook-storm suite.

**R:** No lasting money loss; later partners didn't hit the same class of bug.

> "Deliver Results includes recovering without theater — fix the system, not just the ticket."

---

# PART B — SYSTEM DESIGN (stories + how to answer)

> LPs often probed here: **Invent and Simplify** · **Dive Deep**  
> Screening may be (1) behavioral ("tell me about a system you built") or (2) light design walkthrough.

### If they whiteboard — open like this
> "Before I draw — who are the clients? Target RPS / data volume? Consistency vs availability? Any compliance or audit requirements?"

Then: clarify → NFRs → high-level boxes → deepest hard component → failure modes + observability → how you'd staff/sequence as EM.

---

## B1 — PRIMARY (Invent & Simplify): Job scheduler — 3 weeks vs 4-month custom engine

**Use for:** "simplified something complex," invent & simplify, architecture judgment.

**S:** Skydo needed multi-step financial workflows — payment retries, settlement runs, recon jobs. Team proposed a **custom orchestration engine (~3–4 months)**.

**T:** Meet real requirements without inventing a platform we wouldn't operate well.

**A:**
1. Forced a requirements list: reliable retries, durable state, visibility, failure recovery, at-least-once + idempotent handlers.
2. Mapped each to what we already had: **SQS** (delivery + DLQ) + **PostgreSQL job table** (`status`, `attempts`, `next_run_at`) + **Redis lock** (dedupe) + **stateless ECS workers**.
3. Thin producer API so services didn't depend on plumbing. Explicitly **did not** build DAG designer / visual orchestrator.

**R:** Shipped in **~3 weeks**; production >1 year with minimal change; covers ~95% of workflows.

**Say this:**
> "The invention wasn't a new product — it was deciding we didn't need one. Simplification is an active trade-off: we gave up complex DAGs we didn't need for speed and operability we did."

**Probes:**
- *What did you give up?* → Cross-job DAGs, visual workflow UI, fancy dependency graphs.
- *How do you get exactly-once?* → At-least-once delivery + idempotent handlers + fencing locks = exactly-once *effect*.
- *When would you replace it?* → If we needed long-running multi-branch human workflows at high complexity — then Temporal/custom. Not before.

**Architecture sketch (if they ask you to draw):**
```
Producers → SQS (+ DLQ) → ECS workers
                ↓
         Postgres job_state
                ↓
         Redis lock (TTL)
                ↓
         Kafka / callback
```

---

## B2 — PRIMARY (Dive Deep): GS stale VaR under load

**Use for:** root cause, data correctness, dive deep.

**S:** Goldman market-risk platform intermittently produced **stale VaR** under peak load (market open). Couldn't reproduce easily. VaR feeds trading + regulatory use — stale is compliance risk.

**T:** Find true root cause, not a patch.

**A:**
1. Instrumented aggregation stages with latency histograms — correlated with ingestion spikes.
2. Found **sharding imbalance** (nodes falling behind on replication).
3. Found primary **acking before standby confirmed** → failover window could lose data.
4. Found **reads from replicas with no staleness check**.
5. Fixed sync replication where needed, staleness guards on read path, rebalanced sharding by throughput not key count; added **data freshness SLAs** as first-class metrics.

**R:** Stale VaR eliminated; post-mortem reused globally; leadership impact → **VP within 1 year**.

**Say this:**
> "Three causes stacked — any one alone looked fine. Dive Deep meant instrumenting until the combination showed up under load, then making freshness a metric so users aren't the monitoring system."

---

## B3 — BACKUP (correctness under concurrency): Payments ledger / settlement

**Use for:** "design a payments system" or "hardest system you've built."

**S:** Cross-border payments — customer → ledger → bank/FX partners. Partners are outside our DB transaction. Wrong state = money + regulatory risk. Target **10K+/day** with growth headroom.

**T:** Correct under retries, timeouts, concurrency — not just "call the bank API."

**A (design decisions to verbalize):**
| Decision | Choice | Why |
|---|---|---|
| Ledger | Double-entry, immutable journal | Audit + reverse; no floating balance-only truth |
| Money | BIGINT minor units | Never float |
| Idempotency | Client key; Redis fast-path + DB durable | Survives cache loss |
| Concurrency | Locks / fencing on settle critical sections | Prevent double-settle |
| Partner I/O | Async jobs, visible retries | Don't hide side effects |
| Recon | First-class ladder (intent→debit→FX→remit) | Silent gaps can't hide |

**R:** 10K+ txn/day; recon gaps **~0.6% → &lt;0.02% TPV**; incidents **~−30%**.

**Say this:**
> "In money systems I design for partial failure first. Happy path is the easy part."

**If they push DynamoDB:** keep PostgreSQL for ledger (ACID, multi-row, recon joins); DynamoDB OK for non-financial (events, flags). You already have the backbone story if they probe disagree-and-commit.

---

## B4 — BACKUP: Reliability program (observability + resilience)

**S:** Recurring settlement pain — concurrency races + flaky partners. Failures = money/trust, not just tickets.

**T:** Cut recurring incidents systemically.

**A:** Parallel tracks — (1) correctness on hot path, (2) business KPIs in dashboards (lag, exception queues, unreconciled $), (3) recon ladder.

**R:** ~**30%** fewer production incidents; on-call load down.

---

## B5 — 60-second "how I design" cheat (EM flavor)

Use if they ask philosophy rather than a problem:
1. Clarify customer + scale + consistency.
2. Write failure modes before boxes.
3. Prefer boring primitives until complexity is earned (SQS/PG vs custom orchestrator).
4. Make correctness observable (recon, freshness SLAs).
5. Sequence: ship thin vertical slice → harden failure paths → scale knobs.
6. As EM: who owns the module, what is the first prototype, what risk do I track weekly.

---

# PART C — TEAM & PEOPLE MANAGEMENT

> LPs: **Hire and Develop the Best** · **Ownership** · (also Earn Trust / Best Employer in probes)

### Question bank (they will pick 2–4)
- Underperformer / PIP  
- Conflict between engineers  
- Difficult feedback  
- Grew / promoted someone  
- Hiring mistake  
- High performer leaving  
- Senior engineer pushback  
- Cross-functional conflict (PM)  
- Burnout / culture  

---

## C1 — MUST HAVE: Underperformance → clarity → recovery

**S:** Skydo eng — every sprint slipped 2–3 days; same review comments (e.g. idempotency) unanswered; peers said design sessions were unproductive.

**T:** Intervene early without labeling on one data point.

**A:**
1. Noted Sprint 1 slip; acted when Sprint 2 confirmed the **pattern** (same week, ~3 days later).
2. Private 1:1 with receipts: "Sprint X slipped 3 days; this PR still ignores the idempotency comment from two weeks ago."
3. Listened — personal circumstances; **30-day window** with adjusted expectations, not indefinite excuse.
4. Wrote "what good looks like" (sprint done = committed tickets + no open review debt; design = comes with a position).
5. Weekly 20-min Monday check-ins for 30 days; then monthly.

**R:** Performance recovered; later owned a module independently. Insight: most underperformance is **clarity**, not motivation.

**If no improvement at 30 days:** formal PIP with HR, weekly milestones, clear exit if unmet.

**Timeline probes — memorize:**
| Probe | Answer |
|---|---|
| First notice? | End Sprint 1 — noted, did not intervene |
| Why wait? | One data point = noise |
| Pattern → talk? | Same week (~3 days) |
| Improvement window? | 30 days formal; visible by ~week 4 |

---

## C2 — MUST HAVE: Hard feedback to a strong senior IC

**S:** Senior eng — excellent design/code — interrupted, dismissed "obvious" questions; juniors went quiet in reviews.

**T:** Specific, actionable feedback without losing the talent or soft-pedaling.

**A:** Private 1:1; led with strength; three dated examples; impact ("juniors stop speaking — we lose the questions that catch gaps"); coached language: disagree with the idea, not the person; followed up after next design review.

**R:** Behavior shifted in ~2 weeks; juniors spoke again; they later called it the most useful feedback they'd gotten.

---

## C3 — Hire & Develop: Mid-level → module owner (onboarding platform)

**S:** Mid-level — strong implementer, not owning design/failure modes; wanted senior.

**T:** Close the gap: ownership + design judgment, not more tickets.

**A:** Named the gap in career 1:1; stretch: **write the design doc** for onboarding automation; mandate end-to-end ownership (on-call, design calls, cross-team); feedback like "failure path for sanctions missing — seniors own failure modes."

**R:** Within ~18 months owned the platform that cut onboarding **hours → minutes**; ran design reviews; supported promotion.

**Hiring bar one-liner:**
> "I hire for slope and judgment under novel problems — domain vocabulary is learnable. Interview: exactly-once payments, lost partner response — I want clarifying questions and trade-offs, not memorized patterns."

---

## C4 — Hiring mistake: domain knowledge over judgment

**S:** Hired strong FinTech pedigree; weighted domain fluency too high.

**T:** Close gap or exit fairly.

**A:** Within ~3 months: novel problems → recycled old patterns; weak review catch-rate. Coached 6 months with structured assignments; improved but below bar; left for better fit.

**R / change:** Interviews now prioritize first-principles on novel problems; domain is a weak signal.

---

## C5 — Conflict: two engineers, saga vs event-driven

**S:** Senior wanted saga for multi-step financial workflow; mid wanted simpler events. Disagreement got personal ("naive" / "over-engineering"); team avoiding the module in standups.

**T:** Fix tech + behavior without damaging standing.

**A:** Separated behavior vs design; 1:1 on language with senior; asked mid to write one-pager; both wrote proposals; 45-min decision session (argue each other's side first); landed hybrid: events for happy path, saga patterns for failure recovery; co-owned.

**R:** Shipped on time; process change: **written design doc before first review**.

---

## C6 — Ownership / cross-functional: push back on simplified ledger

**S:** Corridor launch in 2 weeks; PM + eng floated running-balance shortcut.

**T:** Hold financial integrity without becoming "the blocker."

**A:** Offline with PM; failure mode in business language (duplicate gateway retry → can't audit/reverse); offered: ship correct model on time with me pairing, or ship shortcut and rebuild in production later.

**R:** Shipped correct model on time; months later duplicate submissions caught by idempotency — **zero money lost**.

---

## C7 — Senior pushback on design docs

**S:** Introduced mandatory design docs; deepest architect said "I've done this 12 years — I don't need a doc."

**T:** Hold the standard without losing them.

**A:** Private; "doc isn't for you — it's for the person on-call at 2am and the next owner"; bar = 1 page of trade-offs + failure analysis; they wrote it — one of the best docs.

**R:** Became advocate; team adopted docs in ~2 months.

---

## C8 — Retention: high performer with external offer

**S:** Strongest eng (onboarding owner) considering larger company — growth/visibility.

**T:** Find real reason; don't lead with counter-offer theater.

**A:** "What would have to be true for you to stay?"; visibility + scope, not only comp; put them in external forums / attribution up the chain; honest about scale Amazon/Google vs ownership at Skydo.

**R:** Stayed ~18 more months as top contributor.

**If they left:** support it; failure = leave for something you could have fixed and didn't. After a past leave on comp, you now do annual market checks proactively.

---

## C9 — Burnout after certs + launch

**S:** After ISO/SOC + major delivery (~4 months hard), signals: ticket quality down, quiet design reviews, late nights without blockers.

**T:** Protect capacity before attrition.

**A:** Explicit 1:1 wellbeing check; cut **~30%** next-sprint scope with stakeholder explanation; enforced recovery weeks.

**R:** Morale recovered; no attrition from that period; delivery stabilized.

---

## C10 — Psychological safety: blameless + you own a miss publicly

**S:** Recon discrepancy lived too long; people hesitated to raise early signals.

**T:** Make early raise the rewarded behavior.

**A:** Blameless post-mortem; you wrote post-mortem on a migration call you approved with insufficient validation; standup item "early signals?"; "I'd rather hear a false alarm Thursday than a P0 Monday."

**R:** Next issues caught in dev; people flag design concerns earlier.

---

# QUICK MAP — which story for which ask

| They ask… | Lead with |
|---|---|
| Deliver under constraints | **A1 Moneyview −60%** |
| Customer / business outcome | **A2 Onboarding** |
| Owned something not your job | **A3 ISO/SOC2** |
| Failure / setback then delivered | **A4 Partner webhook** |
| Invent & Simplify | **B1 Job scheduler** |
| Dive Deep / root cause | **B2 GS VaR** |
| Design a payment / money system | **B3 Ledger** |
| Reliability / incidents | **B4 −30%** |
| Underperformer | **C1** |
| Hard feedback | **C2** |
| Grow / promote someone | **C3** |
| Hiring mistake | **C4** |
| Team conflict | **C5** |
| Disagree with PM / backbone | **C6** |
| Manage senior / process pushback | **C7** |
| Retention | **C8** |
| Burnout | **C9** |
| Culture / trust | **C10** |

---

# DAY-OF CHEAT — 8 stories to rehearse out loud (Friday)

Practice each once out loud (~2 min). If you only have 30 minutes total, do these eight:

1. **A1** Moneyview −60% (Deliver Results)  
2. **A2** Onboarding hours→minutes (Deliver Results / Customer)  
3. **B1** Job scheduler simplify (System Design)  
4. **B2** GS stale VaR (Dive Deep)  
5. **B3** Ledger correctness (System Design depth)  
6. **C1** Underperformer (People)  
7. **C2** Hard feedback senior (People)  
8. **C3** Grow mid→owner (Hire & Develop)

Keep **C4 hiring mistake** and **C6 ledger pushback** as your "I also have…" reserves.

---

# QUESTIONS TO ASK THEM (pick 2)

1. For this EM/SDM role, what does great look like in the first 6 months — delivery vs team health vs tech debt?
2. What's the hardest people or systems problem the team is facing right now?
3. How do EMs here partner with principals on architecture ownership?
4. How is the bar-raiser used in hiring for this org?

---

# CLOSING LINE (if they ask "why Amazon")

> "I want to operate where customer obsession and bar-raising are the operating system — payments and risk taught me that silent wrongness is worse than downtime. Amazon's scale forces the same rigor on people systems and technical systems, and that's the level I want to lead at."
