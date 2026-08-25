# Dezerv — Engineering Manager Interview Prep
**Date:** Tuesday, 25 Aug 2026 · **12:00–13:00 IST**  
**Interviewer:** Arijit Mukhopadhyay (Engineering Manager, Growth / Partnerships / Data)  
**Organiser:** Simran Taneja (`simran.taneja@dezerv.in`)  
**Meet:** `meet.google.com/oaq-wakb-rzp` · Phone (US) +1 631-709-2074 PIN 173 267 705#  
**Feedback portal (SCOUT):** interviewer link was emailed; join Meet from calendar as panelist. Candidate unlocks Meet via SCOUT portal.

---

## 1. Company Context — Know This Cold

**Dezerv in one line:** Tech-led wealth house for India's affluent / HNI families — expert-managed portfolios (PMS, AIF, MF/IPA) + Wealth Monitor analytics, backed by Premji Invest, Accel, Elevation, Matrix.

**Numbers worth dropping once (don't recite a deck):**
- Founded 2021 by Sandeep Jethwani, Vaibhav Porwal, Sahil Contractor (ex JP Morgan / UBS / BNP / IIFL Wealth; ~USD 7B AUM collectively in prior careers)
- Clients have trusted them with **₹15,000+ Cr** of wealth (JD language; AUM has scaled past ₹10–14k Cr in public reporting)
- Series C ~₹350 Cr (2025); valuation ~₹2,640 Cr post-money in public reports
- Data platform (Snowflake) tracks **₹1,00,000+ Cr** of assets reviewed via Wealth Monitor (market impact + missed-gains analytics)

**Products / eng surfaces**
- Client onboarding + KYC / compliance flows
- Portfolio construction, rebalancing, order/ops workflows
- Wealth Monitor — Account Aggregator / MF Central style portfolio ingestion + diagnostics
- Advisor / RM tooling, reporting, transparency to clients
- Growth, partnerships, data (Arijit's vertical) — acquisition funnels, partner integrations, analytics substrate

**What matters to them technically (from JD + public eng signals)**
- Hands-on EM who stays close enough to code to earn trust
- High ownership, technical rigor, intellectual honesty about tradeoffs
- Decide what gets built / killed; where AI actually changes the game
- Fintech-grade standards for sensitive financial / PII data: design, performance, observability, reliability
- Ship fast without lowering the bar (they've invested in AI code review / agentic SDLC experiments)
- Stack signals: Python, Java, React, Flutter, PostgreSQL, microservices, AWS (Lambda/SQS/SNS), Docker, Snowflake, SQL

**Competitors / context (one sentence if asked):** Traditional wealth (IIFL, Edelweiss, etc.) vs digital wealth (Scripbox, Groww wealth-ish, Cube, etc.) — Dezerv positions as expert-led + tech-transparent for HNIs, not a DIY broker.

---

## 2. Your Interviewer — Arijit Mukhopadhyay

| | |
|---|---|
| Role | EM @ Dezerv since Oct 2024 — **Growth, Partnerships, and Data** |
| Prior | EM @ ShareChat (consumer scale: Feed/Profile/Explore; ~35M DAU, ~300K RPS at gateway; scaled eng org; Moj launch story). Co-founder/CPTO & COO @ hypergro. Earlier BookMyShow backend. |
| Education | M.Tech IIT Delhi · B.Tech Heritage Institute |
| Style signal | Builder-operator; cares about ownership, delivery velocity, infra cost, product metrics (CTR, retention, MRR), hiring Tech Leads who own decisions |

**Implication for this hour:** Expect **people + delivery + technical judgment**, not a pure whiteboard LLD grind. He will test whether you can lead seniors, stay technical, and ship in a product org that moves like a startup with fintech constraints. Lean into **growth/data/platform** language: funnels, integrations, data correctness, observability — not only payments ledger depth (still use payments as proof of correctness under money risk).

**Rapport hooks (light, not flattery):**
- Shared Bengaluru / eng-manager path
- You both care about scale + ownership culture (ShareChat scale stories ↔ your Goldman/Skydo reliability stories)
- Don't over-index on ShareChat trivia — connect on *how* he thinks about bar and velocity

---

## 3. JD Decoded → Your Evidence Map

From Dezerv's EM posting (paraphrased):

> Building a team that ships fast, argues well, and holds its own bar without being asked — that's the job. Engineering isn't executing someone else's roadmap; it's deciding what gets built, what gets killed, and where AI changes the game for a platform handling sensitive financial data.

| JD signal | Your proof | Say it like this |
|---|---|---|
| Hands-on EM close to code | Skydo payments architecture, ADRs, design reviews | "I still review critical path PRs and write the hard ADRs myself when money moves." |
| Lead BE / FE / fullstack | Led 12 at Skydo across platform surface area | "I hire and grow T-shaped engineers; I don't only manage backend." |
| Grow senior talent → future leaders | Mentored 8; promotions; design-review culture | "My job is creating forums — ADRs, reviews, 1:1 growth plans — not one-off mentoring." |
| Hire senior engineers independently | Built team to 12; Goldman loops | Rubric + same-day debrief + written feedback before vote |
| High ownership + honesty about tradeoffs | Compliance vs speed onboarding story | Quantify risk, phase release, don't pick a tribal side |
| Fintech-grade reliability / sensitive data | 10K+ txn/day; ISO 27001 + SOC 2 as CIO | Correctness, audit trail, incident −30% |
| AI changes the game | Claude/Cursor/Windsurf standards at Skydo | Guardrails + measured velocity, not vibe coding |
| Cross-functional alignment | Product / compliance / ops / finance | Written problem statements + shared vocabulary |

---

## 4. Opening Pitch (60–90 sec) — Memorize Shape

> "I'm an Engineering Manager with 10+ years across fintech and large-scale financial systems. At Skydo I lead a team of 12 on an international payments and settlement platform — 10K+ transactions a day — with hard requirements on idempotency, reconciliation, and failure recovery. I also serve as CIO and led our ISO 27001 and SOC 2 Type II programs.
>
> Before that I was a VP at Goldman Sachs leading a 9-person team on distributed market risk aggregation on multi-terabyte in-memory clusters — promoted to VP within a year. Earlier I was lead developer on Oracle Identity Cloud Service.
>
> I'm interested in Dezerv because it's the rare combination of HNI wealth complexity, data-intensive product, and a culture that wants EMs who stay technical and decide what to build — not just execute a backlog. Happy to go deeper on people, delivery, or a system I've owned."

**Arc:** Fintech correctness (Skydo) → market/risk scale (Goldman) → identity/security foundation (Oracle) → why Dezerv (wealth + data + hands-on EM bar).

---

## 5. Likely Round Shape (60 min)

Assume hiring-manager / peer-EM style unless they say otherwise:

| Segment | Time | Probe |
|---|---|---|
| Intro + career walk | 5–8 min | Narrative, communication, seniority |
| Why Dezerv / why EM now | 3–5 min | Motivation, level fit |
| Behavioral / leadership STAR | 15–20 min | Hiring, conflict, underperformance, culture |
| Delivery & prioritization | 10–15 min | Roadmap kill decisions, stakeholder alignment |
| Technical depth on a system you own | 10–15 min | Architectural judgment, hands-on credibility |
| Your questions | 5 min | Curiosity, peer-level conversation |

SCOUT co-pilot may structure notes/rubric in the background — speak in numbered lists, end stories with **metric + lesson**.

---

## 6. STAR Stories — Pre-Loaded (2 min each)

### A — Critical production ownership (reliability)
- **S:** Skydo payments/settlement — money + regulatory risk; partner flakiness.
- **T:** Cut recurring incidents from concurrency + third-party failure modes.
- **A:** Distributed locks, idempotency everywhere, SLA-aware retries, reconciliation ladder, observability tied to business KPIs.
- **R:** Incidents ~**−30%**; recon gaps from ~**0.6% → &lt;0.02%** TPV; on-call load down.
- **Lesson:** Observability and vocabulary before clever architecture.

### B — Growing engineers / raising bar
- **S:** Team of 12, uneven design judgment.
- **T:** Level up seniors without slowing shipping.
- **A:** Design-doc-first for money paths; ADRs; paired reviews; 1:1s with skill-based growth plans.
- **R:** 8 mentored; promotions; design coverage ~30% → ~100% on major changes.
- **Lesson:** Forums beat hero mentoring.

### C — Hard conversation / underperformance or behavior
- **S:** Strong IC, corrosive review tone.
- **T:** Keep talent, fix behavior, protect team.
- **A:** Private feedback with receipts; 30/60/90 behavior goals; shadow reviews.
- **R:** Shift in ~6 weeks; no attrition.
- **Lesson:** Specific examples or it feels like a character attack.

### D — Hiring bar
- **S:** Scaling Skydo eng; Goldman loops.
- **T:** Consistent senior signal, hire for +12 months needs.
- **A:** Rubric (problem solving, systems, collaboration, ownership); written feedback before debrief; same-day decision.
- **R:** No regret hires in recent loops you owned.
- **Lesson:** Process makes hiring fair *and* fast — matches Dezerv "hold the bar without being asked."

### E — Kill / phase a roadmap item (JD gold)
- **S:** PM push for new entity onboarding; eng flagged KYC/compliance gaps.
- **T:** Don't block growth; don't ship unsafe.
- **A:** Quantified risk; feature-flagged beta → full rollout after audit trail.
- **R:** Beta 2 weeks, full 5 weeks, zero findings next audit.
- **Lesson:** EM job is quantifying the tradeoff, not picking Product vs Eng.

### F — Org/compliance impact
- **S/T:** ISO 27001 + SOC 2 as CIO to unlock enterprise.
- **A:** Gap analysis, controls, DLP, access reviews, IR playbook, evidence pipeline.
- **R:** Certs landed; sales unblocked; security culture shift.
- **Lesson:** Compliance as platform, not theatre — resonates with wealth PII / SEBI-adjacent world.

### G — Ambiguity (reconciliation)
Use the written problem statement → state vocabulary → three-layer recon ladder story from your ambiguity prep. Result metrics as above.

### H — AI in engineering (proactive differentiator)
Led Claude/Cursor/Windsurf standards: security/IP guardrails, PR norms, measured speed/quality. Bridge: Dezerv already leans into AI review / agentic experiments — you won't fight the culture; you'll professionalize it.

### I — Failure (must have)
Own a real miss (optimistic estimate, insufficient partner failure testing, migration cutover). What you changed systemically. No blame theater.

---

## 7. Management Philosophy — 30-Second Cards

- **1:1s:** Weekly, IC-owned agenda; rotate delivery / growth / feedback / career; shared notes.
- **Team health:** DORA + incident load + 1:1 signal + qualitative eNPS-style pulse — never one metric.
- **Tech vs features:** Explicit ~20% debt/reliability budget tracked like features.
- **Underperformance:** Written expectations → coaching → PIP only if coaching fails; document from day 1.
- **Two seniors disagree:** Competing ADRs → design review → decide reversible vs irreversible → move on.
- **Skip-level wants 2-week miracle:** Negotiate scope, not weekends; smallest 80% slice.
- **How you manage up:** Weekly written: delivered / risks / asks. No surprises.

---

## 8. Technical Depth — Refresh These

Arijit may ask you to walk a system. Prefer **one** deep story over three shallow ones.

### A. Payments / settlement (home turf)
Idempotency keys · durable intent before side effect · outbox · saga vs 2PC · recon ladder · Redis lock fencing · never claim magic exactly-once · partner webhook replay.

### B. Wealth / portfolio analytics sketch (Dezerv-shaped)
If asked "how would you think about Wealth Monitor-style ingestion?":
1. **Ingest** Account Aggregator / MF Central / broker CAS — normalize holdings, lots, cashflows
2. **Identity & consent** — tokenized PII, consent ledger, retention
3. **Canonical portfolio model** — instruments, lots, corporate actions, FX
4. **Compute** — allocation, risk, overlap, fee drag, "missed gains" vs benchmark
5. **Warehouse** — Snowflake-style analytical layer + serving API for app
6. **Freshness SLOs** — daily batch vs intraday; late data; reconciliation of NAV
7. **Security** — row-level access for RM vs client; audit every read of sensitive fields

### C. Growth / partnerships (his vertical)
Partner webhooks, lead → KYC → funded funnel metrics, idempotent CRM writes, experiment flags, cost of acquisition instrumentation — speak product + systems.

### D. Concepts in 30s
CAP/PACELC · circuit breaker · rate limit · cache stampede · Kafka lag/backpressure · observability SLIs tied to money/portfolio freshness

---

## 9. "Why Dezerv?" / "Why leave Skydo?"

**Why Dezerv**
> "Three things. One — wealth for HNIs is a correctness-and-trust problem, same muscle as payments and risk systems I've owned. Two — the product is data-heavy: Snowflake, Wealth Monitor, daily portfolio impact — I like platforms where eng decisions show up in client trust, not just dashboards. Three — the EM bar in your JD is explicit: stay close to code, decide what to kill, use AI honestly. That's how I already operate."

**Why leave Skydo**
> "Skydo was a strong chapter — team built, platform stable, compliance bar cleared. Next I want a larger consumer/HNI product surface and a data-product org where EM craft compounds. Dezerv fits that without leaving fintech trust problems I care about."

Never: compensation-first, boss issues, burnout.

---

## 10. Questions to Ask Arijit (pick 3)

1. "On Growth / Partnerships / Data — what's the hardest reliability or correctness problem the team is carrying right now?"
2. "What does a great first 90 days look like for this EM — hiring, delivery system, or a specific product bet?"
3. "How do you decide what to kill on the roadmap when Product, Investments, and Eng disagree?"
4. "Where is AI already changing your SDLC or product, and where has it been hype?"
5. "What's the typical team shape I'd inherit — seniors vs mid, BE/FE mix — and how hands-on is the EM expected to be week to week?"
6. "Tell me about a recent incident or post-mortem that changed how the team works." *(culture probe)*

---

## 11. Traps

- Rambling past ~2.5 min — pause.
- "We" when they need **your** decision.
- No numbers.
- Pure people-manager vibe with no systems depth (fails their JD).
- Pure IC deep-dive with no team/process lens (fails EM bar).
- Trashing past employers.
- Pretending SEBI/PMS domain expertise you don't have — instead map **controls, audit, data correctness, client trust**.

---

## 12. Pre-Interview Checklist (last 30 min)

- [ ] Meet link works; backup phone PIN ready
- [ ] Quiet space, water, notepad
- [ ] Resume + **day-of cheat sheet** on second screen
- [ ] 3 stories warmed: reliability, hiring/bar, kill-or-phase roadmap
- [ ] Opening pitch once out loud
- [ ] Join 3 min early
- [ ] After call: submit SCOUT feedback if you're on panel side; as candidate, note follow-ups for Simran

---

Good luck. Your profile matches what they wrote in the JD unusually well — the work is crisp stories with numbers and a peer conversation with Arijit, not proving you know wealth jargon.
