# Navi EM – Backend | Delivery & Prioritization Round Prep

**Role:** Engineering Manager – Backend  
**Company:** Navi Technologies (Sachin Bansal's fintech, BFSI domain)  
**Round:** Delivery & Prioritization

---

## What This Round Actually Tests

Navi's culture pillars map directly onto what interviewers probe: **Ownership**, **Urgency**, **Customer-first**, and **Long-term thinking**. In this round they want to see:

1. How you turn ambiguous product goals into executable engineering plans
2. How you make hard prioritization calls under constraints (time, team capacity, tech debt)
3. How you manage dependencies and unblock your team
4. How you balance short-term delivery pressure against platform health
5. How you communicate delivery status to non-engineering stakeholders

---

## Section 1: Prioritization Frameworks You Must Know Cold

### The Core Model: Impact × Confidence ÷ Effort (ICE)

Use this as your default when explaining how you rank work. Map it to business outcomes, not just engineering complexity.

### RICE Score (for roadmap items)
- **Reach** – how many users/transactions impacted
- **Impact** – business value (revenue, risk, compliance)
- **Confidence** – % certainty of estimates
- **Effort** – eng-weeks to ship

### Four Buckets Model (use this for EM-level conversations)
1. **Must Do** – regulatory, compliance, SLA breach risk (Navi is RBI-regulated)
2. **Should Do** – revenue-critical features, high-signal product bets
3. **Nice To Do** – UX improvements, internal tooling
4. **Tech Debt / Hardening** – reliability, observability, platform investment

Always allocate capacity explicitly. A good answer at Navi: *"We protected 20% sprint capacity for debt and reliability work, non-negotiable."*

---

## Section 2: High-Probability Question Areas + Your Tailored Answers

---

### AREA 1: Scoping & Delivery Planning

**Q: Walk me through how you take a large feature from kickoff to production.**

**Your Answer (anchor to Skydo onboarding automation):**

> "At Skydo, we set out to automate onboarding for 5+ business entity types — Sole Proprietorships, LLPs, Pvt Ltds, Partnerships, SMEs. Each had different KYB rules, document requirements, and integration points with third-party verification providers.
>
> **Step 1 – Shape before Sprint.** I ran a 2-day shaping session with product, compliance, and a senior engineer before writing a single ticket. We identified: (a) the invariants — what could NOT change mid-sprint, (b) what was genuinely unknown — third-party API reliability and edge cases in KYB responses, (c) the minimum slice to validate.
>
> **Step 2 – Vertical slices, not horizontal layers.** Instead of building 'the onboarding framework' first, we shipped end-to-end for Sole Proprietorship in Week 1. That forced integration decisions early and gave us real user feedback before we built the rest.
>
> **Step 3 – Explicit risk register.** Every sprint we maintained a 5-row risk log: risk, owner, mitigation, ETA. I reviewed it weekly with the PM.
>
> **Step 4 – Definition of Done included observability.** A feature wasn't 'done' until it had dashboards, alerts, and a runbook. This is what reduced our prod incidents ~30%.
>
> We cut onboarding time from hours to minutes and launched across all entity types within the planned quarter."

---

**Q: How do you handle scope creep during active delivery?**

> "I separate 'new information' from 'scope creep'. New information — like a compliance requirement we missed — is legitimate and we adjust. Scope creep — like a PM adding a feature because a prospect asked — needs a trade-off conversation.
>
> My default is to make the cost visible: 'This adds ~3 eng-days. We can add it if we defer X or if we extend by Y days. You decide, I'll execute.' I never unilaterally absorb scope — that creates hidden tech debt and kills predictability.
>
> At Skydo, mid-way through our international payments platform build, we received a compliance mandate from our banking partner that changed the settlement reconciliation logic. That was legitimate new information. We re-pointed, dropped a nice-to-have reporting feature, and communicated the change in the same standup."

---

### AREA 2: Prioritization Under Constraints

**Q: You have a team of 8 engineers, 3 parallel product tracks, ongoing incidents, and a compliance deadline in 6 weeks. How do you prioritize?**

**Framework to demonstrate:**

> "First, I separate work by non-negotiability:
>
> - **Compliance deadline** — that's a Must Do with a fixed date. I assign dedicated engineers to it immediately and time-box it. At Navi you're likely dealing with RBI mandates, FLDG caps, or CERSAI integrations — these don't negotiate.
> - **Active incidents** — I assign one engineer on-call rotation. Incidents don't queue.
> - **Product tracks** — I rank them by: (1) revenue at risk if delayed, (2) customer SLA impact, (3) dependencies other teams have on us.
>
> Then I do capacity math explicitly:
> - 8 engineers × 8 productive hrs/day = 64 engineer-hours/day
> - Reserve 1 engineer for incidents/on-call
> - Reserve 10% for team overhead (reviews, 1:1s, hiring)
> - Assign 2 engineers to compliance track until it closes
> - Remaining ~4.5 engineers go to the highest-ranked product track
>
> The second and third tracks pause or get reduced to skeleton maintenance. I communicate this decision to stakeholders immediately with a revised ETA, not after the deadline is missed."

---

**Q: How do you balance feature delivery vs. technical debt?**

> "I treat tech debt as a delivery risk, not a separate category. Unaddressed debt always surfaces as incidents, slow releases, or brittle integrations — usually at the worst time.
>
> My rule: **20% of every sprint is reserved for hardening**. This is non-negotiable — not 'if we have time'. It covers observability improvements, refactoring hot paths, dependency upgrades, and removing workarounds.
>
> At Skydo, when we were scaling the payments platform to 10K+ transactions/day, we had two options: ship the next feature or invest a sprint in idempotency hardening and reconciliation tooling. We chose the latter. It directly resulted in a ~30% reduction in production incidents and actually accelerated the next two features because engineers weren't firefighting.
>
> When I pitch this to product leadership, I frame it as: 'Every dollar of deferred debt compounds. A refactor today costs X. The same refactor after we 3x traffic costs 5X and carries live-system risk.'"

---

### AREA 3: Handling Escalations & Blockers

**Q: Tell me about a time your team missed a deadline. What happened and what did you do?**

> "At Goldman Sachs, we were mid-delivery on a risk aggregation pipeline upgrade when we discovered that our in-memory distributed cluster had a sharding hotspot under live market stress conditions. This wasn't caught in staging because our test data volume was ~10% of production.
>
> What I did:
> 1. **Communicated immediately** — I escalated to my director the same day with a clear framing: 'We're going to miss by 2 weeks. Here's why. Here's the mitigation plan. Here's what we need from you.' No surprises.
> 2. **Contained scope** — Instead of trying to fix all sharding issues, we identified the two hot partitions causing 90% of the problem and addressed those. Full rebalancing was moved to a follow-up sprint.
> 3. **Post-mortem** — We ran a blameless post-mortem. Root cause: staging environment didn't mirror production cardinality. Action item: we built a production-shadow load test environment.
>
> The lesson I carried forward: staging environments that don't mirror production give you false confidence. I now make production-parity environments a delivery prerequisite for any high-scale system."

---

**Q: How do you unblock cross-team dependencies?**

> "Dependencies are the #1 killer of predictable delivery. My playbook:
>
> 1. **Identify dependencies before sprint start, not during.** In planning, I ask: 'What do we need from other teams this sprint?' and 'What are other teams expecting from us?' I put both in writing.
>
> 2. **Push integration contracts early.** For API or event-based dependencies, we agree on contracts (API specs, event schemas) before code is written on either side. This lets both teams build in parallel.
>
> 3. **Escalate early, not late.** If a dependency isn't resolved by midpoint of the sprint, I escalate to the other team's EM directly — not as a complaint, but as a shared problem to solve. 'We're blocked on X, what do you need from me to unblock this?'
>
> At Skydo, our payments platform depended on three external providers — a KYB vendor, a banking API, and a forex data feed. We built adapter layers with mocks for each so our core logic could be developed and tested independently. When vendor APIs changed (which they did, twice), only the adapter needed updating."

---

### AREA 4: Metrics & Delivery Health

**Q: How do you measure the health of your team's delivery?**

> "I track four categories:
>
> **Velocity & Predictability**
> - Sprint commitment vs. delivery rate (aim for 80–90% — 100% signals sandbagging)
> - Rolling 4-week throughput trend — is it improving, stable, or declining?
>
> **Quality**
> - Escaped defects per sprint (bugs found in prod that weren't caught in QA)
> - Incident count and MTTR — at Skydo I drove MTTR down by building runbooks and on-call rotation
>
> **Flow Efficiency**
> - Cycle time: idea → production (we tracked this per-ticket in Jira)
> - Deployment frequency — at Skydo we aimed for multiple deploys/week per service
>
> **Team Health**
> - Eng satisfaction on delivery process (quick pulse surveys quarterly)
> - Unplanned work % — if >30% of a sprint is unplanned, something is broken upstream
>
> I review these weekly as an EM, not quarterly. They're early warning signals, not retrospective reporting."

---

### AREA 5: Stakeholder Management & Communication

**Q: How do you communicate delivery status to non-technical stakeholders?**

> "My rule: no surprises, no jargon, and always frame status in business impact.
>
> I use a simple weekly status format:
> - **Green/Yellow/Red** per initiative (not per ticket)
> - For Yellow/Red: one sentence on what changed, one sentence on mitigation, one sentence on new ETA
>
> At Skydo, I reported weekly to the founders. They cared about: (1) will this ship on time, (2) what's the risk, (3) what do they need to decide. I never showed them Jira burndowns. I translated engineering status into business outcomes.
>
> When I had to deliver bad news — like a timeline slip — I always came with a recovery plan. 'We're slipping by 2 weeks, but here are three options: reduce scope by X and ship on time, add one engineer and keep scope, or accept the slip with a hard-commit on the new date.' Stakeholders want options, not just problems."

---

**Q: How do you push back on unrealistic timelines from product?**

> "I push back with data, not opinions. My approach:
>
> 1. Break the work into the smallest estimable units and show the math. 'Here's the work, here's our capacity, here's the date the math produces.'
>
> 2. Offer levers: 'We can hit your date if we cut features B and C, or add 2 engineers for 4 weeks, or accept higher risk by skipping load testing.'
>
> 3. I don't say 'no'. I say 'yes, if'. This keeps the conversation constructive.
>
> I've found that product teams often don't realize what's actually in scope. When I make hidden complexity visible — like 'this requires integrating with 3 external APIs, each with different error models' — timelines get re-evaluated reasonably.
>
> At Skydo, this happened when we were asked to integrate a new banking partner in 2 weeks. I walked the PM through the integration checklist — API certification, error handling, reconciliation logic, compliance review — and we aligned on 5 weeks with a phased go-live."

---

### AREA 6: Navi-Specific / Fintech Context

**Q: How do you approach delivery when there are regulatory/compliance constraints?**

> "Compliance constraints are first-class delivery requirements, not afterthoughts. At Skydo, I served as CIO and led ISO 27001 and SOC 2 Type II certifications alongside product delivery. The lesson: compliance work runs on its own cadence and can't be crammed at the end.
>
> My approach:
> - Involve compliance and legal stakeholders in shaping, not just sign-off
> - Build compliance requirements into Definition of Done for every feature
> - Maintain a compliance backlog alongside the product backlog — treated with the same rigor
>
> At Navi, I'd expect RBI guidelines, data localization, KYC/AML requirements, and FLDG regulations to all be in play. I'd want a direct line to the legal/compliance team and a shared calendar of regulatory deadlines that feeds into sprint planning."

---

**Q: How do you manage delivery in a high-stakes financial system where bugs can mean money loss?**

> "You need defense in depth — both in the system and in the process:
>
> **System level:**
> - Idempotency on every financial operation (at Skydo, this was non-negotiable for payments)
> - Reconciliation jobs that detect and alert on discrepancies before they compound
> - Staged rollouts — canary or feature flags, never big-bang deploys for payment flows
>
> **Process level:**
> - Every financial feature has a mandatory code review by a senior engineer
> - Load testing with production-scale data before go-live
> - Runbooks for every alert, including rollback procedures
>
> I've seen what happens when these aren't in place — at Moneyview, before I joined, there were manual reconciliation processes for millions of debit instructions. I automated and hardened those, reducing manual ops by 60%. The principle is the same: make the system self-healing and the process catch what the system misses."

---

## Section 3: Questions to Ask the Interviewer

These signal strategic thinking and genuine interest:

1. "What's the current biggest bottleneck in your backend delivery — is it team capacity, dependencies, tech debt, or something else?"
2. "How does the engineering roadmap currently get prioritized relative to RBI/regulatory timelines?"
3. "What does a 'good quarter' look like for this team in terms of delivery outcomes?"
4. "How embedded is engineering in product discovery at Navi — do EMs participate in shaping before specs are written?"
5. "What's the on-call and incident management culture like today, and what would you want to improve?"
6. "How does the team currently measure delivery health — DORA metrics, velocity, something else?"

---

## Section 4: Your Anchor Stories (Map to STAR)

| Story | When to Use |
|---|---|
| **Payments platform 10K tx/day** at Skydo | Scale, reliability, prioritizing hardening over features |
| **Onboarding automation** at Skydo | Large project scoping, vertical slices, cross-functional delivery |
| **ISO 27001 / SOC 2** at Skydo | Compliance alongside delivery, competing priorities |
| **Risk aggregation cluster** at Goldman | Missed deadline recovery, production-parity environments |
| **Debit instruction automation** at Moneyview | Fintech-specific delivery, reducing manual ops |
| **30% incident reduction** at Skydo | Observability investment, tech debt prioritization payoff |
| **Banking partner integration** at Skydo | Pushing back on timelines with data, phased delivery |

---

## Section 5: Key Phrases That Resonate at Navi

Navi's culture vocabulary (from their careers page): **Ownership**, **Urgency**, **Customer-first**, **Long-term thinking**.

Map your answers to these:
- "I owned the outcome end-to-end, not just the sprint" → **Ownership**
- "We chose to ship in 3 vertical slices rather than wait for a perfect design" → **Urgency**
- "The decision we made reduced transaction failure rate for customers, which was the real measure" → **Customer-first**
- "We invested in idempotency and reconciliation early even though it felt slower — it's why the system is still running without incidents 2 years later" → **Long-term thinking**

---

## Section 6: The 10-Minute Warm-Up Answer

If asked *"Walk me through your background and how you think about delivery"*, don't just recite your resume. Use this structure:

> "I've spent 10 years building backend platforms in fintech and financial services — from payment systems at Moneyview and Skydo, to risk aggregation at Goldman Sachs. The thread across all of it is delivering high-reliability systems under real constraints: regulatory timelines, financial accuracy requirements, and the kind of scale where a bug isn't just a user experience issue — it's a money problem.
>
> As an EM, my delivery philosophy comes down to three things:
> 1. **Shape before sprint** — the most expensive mistakes happen before a line of code is written. I invest heavily in clarifying scope, surfacing unknowns, and building shared understanding between product and engineering.
> 2. **Make trade-offs explicit and visible** — I don't absorb scope or timeline pressure silently. I surface the cost and let stakeholders make informed decisions.
> 3. **Protect platform health as a delivery prerequisite** — I've seen what happens when you defer reliability work. The 30% incident reduction at Skydo came directly from treating observability and hardening as first-class deliverables, not afterthoughts.
>
> At Navi, with the regulatory complexity of a BFSI environment and the scale you're operating at, I'd bring the same approach: predictable delivery, clear trade-off communication, and a team culture where quality is part of the Definition of Done — not a phase at the end."

---

*Prep doc generated: Apr 11, 2026*
