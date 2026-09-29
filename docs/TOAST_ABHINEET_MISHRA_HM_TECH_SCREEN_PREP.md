# Abhineet Mishra — Toast HM/Tech Screen Prep
**Round:** Virtual Tech Screen / HM Screen (45 min)  
**Interviewer:** Abhineet Mishra, Senior Manager, Software Engineering  
**Team:** Funds Management · Payments · Toast Bengaluru  
**Candidate:** Ramit Hansda  
**When:** Tuesday, 29 Sep 2026 · 10:00–10:45 IST  

Companion: `TOAST_ABHINEET_HM_TECH_SCREEN_DAYOF_CHEATSHEET.md`

---

## 1. Who is sitting across from you

### Profile
- **Senior Engineering Manager** at Toast (since ~Sep 2025), Bengaluru
- Leads **Funds Management** inside the Payments org (also referred to publicly as **Pricing & Funds Management**)
- Previously **Software Development Manager at Amazon** (Gurugram, ~2022–2025)
  - Built unified distributed **E-Invoicing / Finance Automation** for vendor payments across marketplaces (billion-$ scale; −60% manual intervention)
  - Re-architected monolithic invoice ops → microservices; org-wide modernization (~100 services, 23 workflows)
  - Scaled eng team **2 → 12**; operational excellence (−25% high-sev defects / time-to-launch)
- Public hiring signal: Staff/Principal + EM roles for building **high-throughput payment systems**, **fault-tolerant transaction pipelines**, **ultra-low latency settlement frameworks** — Java/Kotlin, microservices, payments/FinTech

### What this means for the interview
Abhineet is not a generic "restaurant POS" interviewer. He is a **payments/finance-platform** hiring manager who came from Amazon money-movement systems. He will evaluate you the way an Amazon SDM + Toast Payments SEM would:

1. **Can you own a money path end-to-end?** (correctness > cleverness)
2. **Do you reason about failure modes** (timeouts, duplicate payouts, partial settlement, fee mismatches)?
3. **Do you raise the bar** (standards, mentorship, ops excellence, measurable impact)?
4. **Will you fit Funds Management** — merchant funds *out*, netting, withholdings, recon — not just guest checkout UX?

Calibrate depth to **settlement / ledger / recon / idempotency**. POS offline sync is secondary unless he asks.

---

## 2. What Toast Funds Management actually does

Toast is the restaurant OS (POS hardware + SaaS + payments). Funds Management sits on the **merchant money** side:

```
Guest pays (card) → Auth → Capture → Batch settlement → Net payout to restaurant bank
                                              ↓
                         − processing fees
                         − refunds / chargebacks
                         − withholdings (Toast Capital, EasyPay, TDS delivery fees, instant-deposit fees, round-ups)
```

**Product facts worth knowing (enough to converse, not to lecture):**
- Batches before **9:30pm ET** → typically next-business-day deposit; later → T+2; weekends/holidays push
- Merchants reconcile via **Payouts Overview / Reconciliation / Settled Deposits** reports
- **Fees ≠ Withholdings** — fees are cost of accepting cards; withholdings are product repayments/charges taken from the same payout
- Instant deposit is a separate, accelerated funds path with its own fees
- Stack (org-wide): Java/Kotlin microservices, AWS, sharded Postgres, DynamoDB, Pulsar (events), React frontends, Android POS

**Your Skydo story maps 1:1** — settlement sweeps, partner timeouts, fee math in recon, idempotent payouts, business SLOs on stuck money.

---

## 3. Toast values (behavioral lens)

Use these names when a story fits — don't force-fit:

| Value | How to demonstrate |
|---|---|
| **We're one team** | Cross-functional with compliance/ops/finance; platform contracts for product teams |
| **Lead with humility** | Near-miss → playbook; blameless incident reviews with concrete actions |
| **Always hungry / raise the bar** | Idempotency as non-negotiable standard; ADRs; higher bar on money path |
| **Ownership** | Settlement bug = real money stuck; reliability as product requirement |
| **Customer success / hospitality** | Restaurant owner cash-flow anxiety when Monday deposit is wrong — design for that user |

---

## 4. Round format — what "HM/Tech Screen" usually is

Reported Toast process: Recruiter → **HM/Technical Screen (45–60)** → Virtual onsite (coding, system design, behavioral).

For a **Senior Manager on Funds Management**, expect a **hybrid**:
- Experience deep-dive (majority)
- Light technical probing / mini system design on payouts or recon
- Cultural / working-style fit
- Possibly a small practical coding or design sketch — **not** a hard LeetCode hour

**Win condition:** He leaves thinking "this person has already operated the systems my team builds — I want them in the loop."

---

## 5. Opening (90 seconds) — memorize shape, not script

> I'm Ramit. At Skydo I was founding engineer, then EM/CIO — I designed the cross-border payments and settlement platform that now runs 10K+ transactions a day. The hard problems were correctness under partial failure: idempotency across services, concurrent state machines, settlement to bank partners that timeout without telling you if money moved, and reconciliation that closes silent partner success. Before Skydo I was VP at Goldman Sachs on distributed market-risk compute. I'm especially interested in Funds Management because it's the trust layer for restaurants — if Friday's sales don't land correctly, the merchant can't pay people. That's the class of problem I've been solving, and I want to do it at Toast's scale.

Then stop. Let him drive.

---

## 6. The 12 questions he is most likely to ask

### Q1: "Walk me through a payment / settlement system you built."

**Why he asks:** Domain fit for Funds Management. He wants structure + failure modes, not a service list.

**Answer skeleton (5–7 min):**
1. **Problem:** Cross-border B2B payments — money can't be wrong; partners fail independently; audit trail required.
2. **Write path:** Idempotency gate → validate → lock → durable state transition → partner call with deterministic key → post-commit events.
3. **Settlement:** Scheduled sweeps; bank API with timeout/poll/DLQ; never invent "exactly-once" — use at-least-once + idempotent side effects.
4. **Recon:** Internal records ↔ external settlement files; exact / partial / unmatched; exception aging.
5. **Impact:** 10K+/day; incidents −30% from business SLOs; ISO/SOC2 controls you owned as CIO.

**Depth traps:**
- *"What if Redis lock TTL expires?"* → Postgres unique constraint is source of truth.
- *"What if bank times out after debit?"* → Poll with same idempotency key; recon file is backstop.
- *"How do you avoid double payout?"* → Deterministic `payout_id` + unique constraint + state machine that only transitions SETTLED→PAID once.

---

### Q2: "How would you design merchant daily payouts?"

**Why he asks:** This *is* Funds Management.

**Answer shape:**
```
Inputs: captured/settled txns for merchant M on business day D
Compute: gross − refunds − fees − withholdings = net_payout
Gate: risk holds / KYC / negative balance / Capital repayment schedule
Emit: payout intent (durable) → ACH/processor initiate (idempotent) → confirm → ledger
Observe: expected deposit date; alert if bank credit missing past SLA
Recon: payout record ↔ bank statement credit ↔ per-txn contribution
```

**Call out:**
- Batch cutoff clocks (Toast-like 9:30pm ET) are **business contracts**, not infra details
- Fees and withholdings need **separate accounting lines** (restaurants debug both)
- Multi-location: decide netting per location vs rolled account
- Instant deposit = different product with risk limits + fee + faster rail

---

### Q3: "Tell me about a production incident involving money or settlement."

**STAR structure:**
- **S:** Partner returned ambiguous timeout; ops thought payment failed; retry risked double-send.
- **T:** Confirm money state without guessing; prevent duplicate; restore merchant/customer trust.
- **A:** Halt automated retries for that corridor; poll partner with original idempotency key; check ledger vs partner status; add "unknown" state + runbook; tighten alert on PENDING > N minutes.
- **R:** No double-send; runbook cut MTTR; later made recon catch silent success automatically.

Map to **Lead with humility** + **Ownership**.

---

### Q4: "Idempotency — how do you actually implement it?"

> Two layers. Fast path: Redis key for in-flight requests. Source of truth: unique constraint on idempotency key in Postgres in the same transaction as the business write. Redis can die; the DB cannot silently accept a duplicate money movement. Downstream partners get a **deterministic** idempotency key derived from our payment/payout id — never a random UUID on retry.

Follow-ups ready: TTL races, fencing tokens, webhook replay, at-least-once consumers.

---

### Q5: "How do you reconcile internal records with external settlement?"

Reuse your recon HLD verbally:
- Ingest internal events + external files (SFTP/S3/API)
- Normalize amounts to minor units; separate gross / fee / net
- Match: exact ref → fuzzy → rule-based fee tolerance → exception queue
- Partial matches from FX/fees go to tolerance or manual
- Aging + audit log; re-runs are idempotent (`reconciliation_run_id`)

Bridge to Toast: "That's exactly what a restaurant owner is doing when deposit ≠ Sales Summary — timing, fees, withholdings, batch cutoffs."

---

### Q6: "Senior vs Staff — how do you operate?"

For Staff bar (his hiring posts target Staff/Principal):
> Senior ships complex features well. Staff defines the **contracts, failure modes, and reusable substrate** so multiple teams ship correctly — payout idempotency library, recon matching rules as data, settlement job framework, SLOs that pages on stuck money. I did that at Skydo with platform vs product tracks and ADRs on load-bearing decisions.

---

### Q7: "Describe a time you raised engineering standards."

Idempotency + partition-key (`payment_id`) playbook after near-miss; ADRs for data model / integration boundaries; code review bar higher on money path than UI. Metrics: fewer sev-1s, faster onboarding.

---

### Q8: "Conflict / disagreement with another engineer or partner team."

Pick a real one. Shape: disagreed on shortcut that threatened money correctness → data + blast radius → compromise on reversible parts, non-negotiable on state machine → relationship intact. **One Team + Humility.**

---

### Q9: "Why Toast? Why leave / why this team?"

> Toast uniquely owns the restaurant OS *and* the money rail. Funds Management is where merchant trust is won or lost daily. My strongest work is making money move correctly at scale under partial failure. Bengaluru Payments building core platforms is exactly where I want to go deep again — hands-on on settlement systems, not only managing around them.

Avoid trash-talking current/past employers.

---

### Q10: "System design lite — offline POS takes payment, how do you not double-charge on sync?"

Only if he steers restaurant-local:
- Local durable intent + local txn id
- Sync queue with idempotent capture using client-generated key
- Server dedupes; conflict policy for check edits (LWW vs merge — for **payments**, prefer explicit states over silent LWW)
- Never capture twice for same local payment intent

Then pivot: "Happy to go deeper on terminal sync — or on the settlement/payout side, which is closer to Funds Management."

---

### Q11: "How do you mentor / grow seniors?"

Context over answers; design reviews that poke failure modes; platform track with higher bar; specific examples of engineers who leveled up (keep anonymized).

---

### Q12: "What's your working style with a manager like me?"

> Direct on risks early; written ADRs for irreversible bets; metrics on money SLOs; I escalate with options not just problems. I like managers who care about operational excellence and correctness — sounds like how you ran finance automation at Amazon.

Subtle Amazon LP nod without sounding performative: Ownership, Dive Deep, Deliver Results, Insist on Highest Standards.

---

## 7. Mini designs to have on the whiteboard (mental)

### A. Payout netting
`net = Σ(captured) − Σ(refunds) − Σ(fees) − Σ(withholdings[Capital, EasyPay, TDS, …])`  
Hold if net < 0 or risk flag; else create `payout_intent` unique on `(merchant_id, settlement_date, rail)`.

### B. Partner timeout state machine
`INIT → IN_FLIGHT → SUCCESS | FAILED | UNKNOWN`  
UNKNOWN only exits via poll result or recon match — never via blind retry with new key.

### C. Fee vs withholding ledger
Separate journals so reporting can explain "why was my deposit less than sales?" without conflating 2.9%+30¢ with Capital repayment.

---

## 8. Questions to ask him (pick 3)

1. Hardest Funds Management problem next 6–12 months — payout latency, multi-product withholdings, recon, Capital/instant deposit scale?
2. Bengaluru vs US ownership on payments / processor integrations?
3. Staff success on your team: platform substrate vs feature velocity?
4. What does great ops excellence look like here (payout MTTR, exception SLAs)?
5. How do you grow Staff engineers toward Principal on this stack?

---

## 9. Stack translation (speak his language)

| You've used | Toast-adjacent |
|---|---|
| Kafka | Pulsar (Toast's event backbone) — same at-least-once + idempotent consumer story |
| Java / Spring / Kotlin comfort | First-class on Payments jobs |
| Postgres + Redis locks | Sharded Postgres + Redis/distributed locks patterns |
| ECS/AWS | AWS microservices |
| Settlement jobs / recon | Direct domain match |

Don't pretend you've operated Pulsar in prod — say "same event-driven contracts I've run on Kafka; I'd ramp on Pulsar specifics quickly."

---

## 10. Anti-patterns for this specific screen

| Don't | Do instead |
|---|---|
| 10-minute POS dinner-rush story | 5-minute settlement correctness story |
| "We guarantee exactly-once" | At-least-once + idempotent consumers + recon |
| Fake Toast Capital underwriting expertise | Honest: "I'd want to learn underwriting; I know payout withholdings as a funds-flow problem" |
| Only people-manager talk | Show hands-on depth — he is hiring builders for Staff+ |
| Algorithm puzzle flex | Practical money-path reasoning |

---

## 11. Pre-call checklist (30 min before)

- [ ] Quiet space, camera, water, notebook
- [ ] Re-read **day-of cheatsheet** once (not this whole doc)
- [ ] Metrics card memorized
- [ ] One payout design sketch ready on paper
- [ ] 3 questions for Abhineet circled
- [ ] LinkedIn open on Abhineet (Funds Management / Amazon finance automation) for mental framing only — don't name-drop awkwardly

---

## 12. After the call

Note: what he probed deepest (settlement? leadership? coding?), any team hints (Capital, instant deposit, pricing), level language (Senior vs Staff), and next-loop composition. Update this folder if you get an onsite — add coding + system design toast-flavored drills.
