# MoneyView — Engineering Manager R1 Prep

**When:** Thursday 8 October 2026 · 16:00–17:00 IST  
**Meet:** [meet.google.com/vkt-hfda-czd](https://meet.google.com/vkt-hfda-czd) · phone (US) +1 956-332-5792 · PIN 660 376 154#  
**Invite title:** R1_Engineering Manager_Ramit  
**Guests:** Subrata Parial · Rishika Singh · you  
**Artifacts:** day-of card [`MONEYVIEW-EM-DAYOF-CHEATSHEET.md`](./MONEYVIEW-EM-DAYOF-CHEATSHEET.md) · spoken scripts [`MONEYVIEW-EM-SPOKEN-ANSWERS.md`](./MONEYVIEW-EM-SPOKEN-ANSWERS.md)  
**Resume source:** [`RAMIT-HANSDA-RESUME-EM.md`](./RAMIT-HANSDA-RESUME-EM.md) · story bank [`DEZERV-EM-15-STORIES-FROM-RESUME.md`](./DEZERV-EM-15-STORIES-FROM-RESUME.md)

---

## 1. What this round is

R1 for an EM lateral. Expect a **hiring-manager / bar screen**, not a pure coding grind:

| Likely block | ~time | What “good” looks like |
|---|---|---|
| Intro + career arc | 8–10 min | Crisp; MoneyView alum called out without living in 2019 |
| Why MV / why now | 5 min | Specific to multi-product + scale; no trash talk |
| Technical ownership deep dive | 15–20 min | Invariants, failures, metrics — Skydo or MV debit story |
| People / delivery / conflict | 15–20 min | STAR with one number + mechanism you installed |
| Your questions | 5–8 min | Product-team pain, 90-day bar, AI realism |

Rishika is likely TA/process; Subrata is the signal. Talk to **him** as Director Eng evaluating whether you can own a pod.

Confirm remaining loop with Rishika after (typical lateral: more tech/design + people + HR). Don’t invent stages.

---

## 2. Interviewer lens — Subrata Parial

**Role:** Director of Engineering @ Moneyview (from ~Jun 2025; LinkedIn also frames “Leading AI Innovations”).  
**Prior:** Director of Engineering @ MFine · Manager @ Amazon.  
**Public signal:** Proud of **MV UPI** and **Digital Gold** launches with his team.

**Implications for you:**

- He has Amazon + hypergrowth-product DNA → values **ownership, velocity with guardrails, measurable outcomes**.
- Product launches matter — speak like someone who ships under constraints, not only runs processes.
- “AI innovations” headline → have a clean take on **SDLC AI** (you’ve done standards at Skydo) vs **credit/fraud AI** (curious, not fake expert).
- Peer-up conversation: you’re interviewing for EM under or beside Director-level leaders (org also has Sachin Kumar, Santosh Sahu VP Eng, etc.). Be collegial, not deferential-and-empty.

**Do not:** name-drop org chart people you haven’t met; claim insider knowledge of current microservice boundaries.

---

## 3. Company snapshot (use lightly — don’t recite)

| Fact | Use in answers |
|---|---|
| Founded 2014 · Bangalore · founders Puneet Agarwal & Sanjay Aggarwal (CTO) | Orientation only |
| Unicorn (~$1.2B, Sep 2024 Accel/Nexus) · IPO-bound trajectory | “Public-company rigor coming” |
| Credit-led platform for **Middle India** (HH income band in filings) | Mission fit without charity tone |
| Flagship: **personal loans** via LSP partners + **WhizDM Finance** NBFC books | Lending + balance-sheet awareness |
| Expanded: home loans, cards, insurance, digital gold, FD marketplace, **UPI**, bill pay, earned wage (Jify) | Multi-product prioritization stories |
| Scale signals (filings / reporting): large registered-user base; managed AUM on order of **₹20k+ Cr** (mid-2026 reporting) | Volume / trust — don’t invent precise ops metrics |
| Eng culture (Sachin Kumar “Inside View”): trust with customer money, compliance rhythm, microservice segregation (payments / KYC / communications), real-time fraud/anomaly monitoring, continuous learning + collaboration | Align language: trust, regulation, scale |

**Your alumni hook (resume):** Senior SDE Jul 2019 – Jul 2020 — payment demand generation & reconciliation; millions of debit instructions/day; idempotent retries, SLA scheduling, multi-gateway; **manual ops −60%**.

---

## 4. Narrative spine

**One sentence:**  
*I’m a MoneyView alum who left as a strong payments IC and want to return as an EM who has since owned international settlement, market-risk compute, and org-wide compliance — ready for MV’s multi-product consumer scale.*

| Beat | Line |
|---|---|
| Past MV | Debit + recon at millions/day; ops −60% |
| Goldman | VP in &lt;1y; multi-TB risk systems; correctness under data scale |
| Skydo | EM of 12; 10K+/day; recon 0.6%→&lt;0.02%; ISO/SOC2 as CIO |
| Return | Same failure modes, bigger surface (PL + UPI + Gold + partners + WhizDM) |

---

## 5. Likely questions → which story

| Question | Lead with | Backup |
|---|---|---|
| Tell me about yourself | Spoken #1 | — |
| Why MoneyView / why back | Spoken #2 | — |
| Why leave Skydo | Spoken #3 | — |
| What would you do differently as EM | Spoken #4 | Mgmt OS #13 |
| Deep dive a system | Skydo payments (#5) | MV debit (#5 variant) |
| Reliability / incident | Spoken #6 | Failure #12 |
| Mentoring / bar | Spoken #7 | Hiring #9 |
| Hard people conversation | Spoken #8 | — |
| Hiring | Spoken #9 | — |
| Product / compliance conflict | Spoken #10 | Kill/phase story bank #5 |
| AI on the team | Spoken #11 | GenAI story bank #10 |
| Biggest failure | Spoken #12 | — |
| How do you prioritize multi-product | Spoken #10 bridge | Sachin-style vision filter |
| Questions for us | Spoken #14 | — |

Story bank IDs map to [`DEZERV-EM-15-STORIES-FROM-RESUME.md`](./DEZERV-EM-15-STORIES-FROM-RESUME.md).

---

## 6. Domain maps (whiteboard-ready, no fake internals)

### A. Money movement spine (works for Skydo *and* MV debit)

```
Intent (durable + idempotency key)
  → side effect (gateway / bank / partner)   [outside your DB txn]
  → observation (webhook / file / statement)
  → reconcile ladder
  → exception queue (owned, aged)
```

**Say:** transport is at-least-once; business rule is at most one successful money movement per intent.

### B. Consumer lending sketch (if they pull product)

```
Acquire → KYC / bureau / fraud → decision / offer
  → e-sign / mandate → disburse (partner or WhizDM)
  → schedule EMI debits → collections / foreclosure
  → partner settlement / commission recon
```

**EM points:** state machine clarity; partner vs own-book ledger boundary; debit SLA ops; fraud real-time vs batch; compliance change as roadmap interrupt.

### C. Multi-product org tension

Products (PL, UPI, Gold, cards, …) share platform (comms, KYC, payments, identity). EM job: protect platform SLOs while staffing company bets — not equal eng to every logo on the app.

---

## 7. Metrics card (only numbers already on resume / prior prep)

| Metric | Source |
|---|---|
| Millions of debit instructions / day; manual ops **−60%** | MoneyView tenure |
| Team of **12**; mentored **8** | Skydo |
| **10K+** international txns / day | Skydo |
| Incidents **~−30%** | Skydo |
| Unreconciled TPV **~0.6% → &lt;0.02%** | Skydo prep |
| Onboarding **hours → minutes** | Skydo |
| **ISO 27001 + SOC 2 Type II** as CIO | Skydo |
| Goldman team of **9**; **VP &lt;1 year** | GS |
| Design-review coverage **~30% → ~100%** | Prior EM prep |

Do **not** invent MoneyView 2026 AUM/disbursal numbers as personal achievements.

---

## 8. Day-of runbook

**T−60 min**

1. Read cheat sheet once; speak open + Why MV + one tech story out loud.  
2. Open Meet link; headset; quiet room; water.  
3. Have resume PDF and cheat sheet on a second screen — glance, don’t read.

**First 2 minutes**

- Smile, confirm audio, thank Rishika + Subrata.  
- If “tell me about yourself” — Spoken #1, stop at ~90s.

**During**

- Numbers early.  
- If Subrata goes deep on UPI/lending — use spine B/C; admit what you don’t know about *current* MV internals; show how you’d learn in 30 days.  
- Alumni humility: “I was here as IC; I’d re-learn today’s boundaries before prescribing architecture.”

**Last 5 minutes**

- Ask 3 questions from Spoken #14.  
- Confirm next steps with Rishika.

**Avoid**

- Living in 2019 gossip or naming old teammates as leverage.  
- Pure people-manager posture (“I just unblock”).  
- “Exactly-once delivery.”  
- Rambling past 2.5 minutes without a metric.

---

## 9. Related prep already in repo

| Need | Doc |
|---|---|
| Leadership answer shapes + payout invariants | [`LEADERSHIP-DISCUSSION-PREP.md`](./LEADERSHIP-DISCUSSION-PREP.md) |
| Skydo payments EM walkthrough | [`payment-platform-em-explainer.md`](./payment-platform-em-explainer.md) |
| Ambiguity / quality | [`EM-AMBIGUITY-QUALITY-INTERVIEW-PREP.md`](./EM-AMBIGUITY-QUALITY-INTERVIEW-PREP.md) |
| 15 STAR stories | [`DEZERV-EM-15-STORIES-FROM-RESUME.md`](./DEZERV-EM-15-STORIES-FROM-RESUME.md) |

---

## 10. Bottom line

This R1 is won by sounding like a **returning operator**: you already felt MV’s debit/recon pain; you’ve since become the EM who can install bar, reliability, and compliance on a multi-product consumer fintech. Subrata should leave thinking *he can put a team under this person and sleep*.
