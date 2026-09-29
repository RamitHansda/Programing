# Toast × Abhineet — EM Interview Script (Speak These Answers)
**Role:** Engineering Manager — Payments / Funds Management  
**Round:** HM/Tech Screen · 45 min · 29 Sep 2026 · 10:00 IST  
**Interviewer:** Abhineet Mishra, Sr Manager SWE (he's hiring EMs onto his Payments org)

**Bar:** Technical EM who can dive deep on settlement *and* build/raise a team — not pure people-manager, not pure Staff IC.

---

## SCENE 0 — “Tell me about yourself”

**SAY:**

> Sure. I'm Ramit. I'm an Engineering Manager who's built and led payments platforms end-to-end.
>
> At Skydo — cross-border B2B fintech — I was founding engineer, then EM and CIO. I designed the payments, settlement, and reconciliation platform, and I built the engineering team around it — about twelve engineers. The platform runs roughly ten thousand international transactions a day.
>
> As EM my job was twofold: keep the money path correct under partner failure — idempotency, state machines, settlement timeouts, recon — and raise the team so that correctness was a standard, not a heroics. I split platform versus product tracks, enforced ADRs on load-bearing decisions, and shifted alerting to business SLOs, which cut production incidents about thirty percent. As CIO I also owned ISO 27001 and SOC 2 Type II.
>
> Before that I was VP of Engineering at Goldman Sachs — nine engineers — on distributed market-risk compute.
>
> I'm talking to you because I want to lead an EM seat on Funds Management at Toast — merchant payouts and settlement at restaurant scale — where team quality and money correctness are the same product.

**STOP.**

---

## SCENE 1 — “What are you looking for?”

**SAY:**

> I'm looking for an Engineering Manager role owning a payments or funds team — settlement, payouts, reconciliation — where I can do three things.
>
> First, build and grow a high-bar team: hire well, develop seniors toward Staff, set engineering standards on the money path.
>
> Second, stay technically deep enough to call correctness — idempotency, failure modes, payout netting — not manage from a status deck.
>
> Third, partner with product and ops so merchant trust — Monday deposits being right — is treated as the product outcome.
>
> Toast Funds Management fits that. I've done this as EM at Skydo; I want to do it at Toast's scale in Payments.

**If he asks “Why EM not Staff IC?”:**

> I've done both builder and EM. I'm choosing EM because the leverage I want next is multiplying correctness through a team — hiring, standards, roadmap tradeoffs, mentoring — while still diving deep on architecture. At Skydo the EM seat grew organically from founding eng; I know how to stay technical without becoming a bottleneck. Your posting for an EM passionate about high-performing teams and large-scale payments is exactly that intersection.

---

## SCENE 2 — “Why Toast? / Why Payments EM?”

**SAY:**

> Toast owns the restaurant OS and the money rail. Funds Management is where merchant trust lives — if Friday's sales don't land correctly, the restaurant can't pay people. Leading that team is high-leverage EM work: every eng standard you set protects real cash flow for small businesses.
>
> Your Payments org in Bengaluru is growing — EM for payments, Staff for settlement pipelines. I want the EM seat: hire and develop the people who build those pipelines, set the bar on fault-tolerant settlement, and deliver with product. That's the job I've already been doing at Skydo; Toast is the scale and domain I want next.

---

## SCENE 3 — “Walk me through a system you owned” (EM framing)

**SAY (attach leadership to every technical beat):**

> I'll cover the system and how I led it — because as EM both matter.
>
> **Business problem.** Cross-border payments: money can't be wrong; partners fail independently; compliance can hold funds. Ten thousand plus transactions a day.
>
> **Architecture.** Idempotency at every boundary — Redis fast path, Postgres as source of truth. Explicit state machines. Durable intent before partner calls. Kafka fan-out with `payment_id` as partition key. Settlement to bank with deterministic idempotency keys, UNKNOWN state on timeout, poll-don't-blind-retry. Daily recon: internal versus settlement files, fee-aware partial matches, aged exception queues.
>
> **How I led it.** I made correctness non-negotiable in code review and wrote near-misses into the eng playbook — for example when someone used `invoice_id` as partition key in staging. I structured the team into platform — payments, settlement, recon — with a higher design bar, and product moving fast inside clear contracts. ADRs for data models and integrations. Business SLO alerts — stuck PENDING more than five minutes — not just CPU. That dropped incidents about thirty percent.
>
> **Org outcome.** Team of twelve. Reliability treated as product requirement because a settlement bug meant real money stuck. Blameless incidents with concrete action items. I stayed close enough to the design that I could unblock Staff-level decisions without owning every PR.

---

## SCENE 4 — Technical follow-ups (same depth — EM must still know)

### Redis TTL / bank timeout / exactly-once

Use the same answers as IC depth — then add one leadership line:

> …And as EM I wouldn't accept a design review that handwaves this. UNKNOWN states and deterministic keys are team standards, not optional elegance.

### “How technical are you day to day as EM?”

**SAY:**

> I don't write every feature, but I own the architectural invariants. I still do design reviews on money-path changes, write or co-write ADRs, and jump into incidents when settlement is ambiguous. I measure myself on whether the team can ship correctly without me — standards, runbooks, Staff-ready seniors — not on my commit count. At Skydo I could still walk the write path and settlement failure modes cold, which is what let me push back on risky shortcuts.

---

## SCENE 5 — “How would you design / lead merchant payouts?”

**SAY:**

> As EM I'd split this into product outcome, architecture bar, and team plan.
>
> **Outcome.** Merchants get the right net deposit on the promised day, and can explain fees versus withholdings when numbers don't match sales.
>
> **Architecture bar I'd hold.** Netting: gross minus refunds minus fees minus withholdings, separate ledger lines. Deterministic payout id on merchant, settlement date, rail. Durable intent before ACH. UNKNOWN on timeout. Recon to bank credit with exception SLAs. Instant deposit is a parallel rail with the same correctness bar.
>
> **Team plan.** Staff or strong Senior owns payout state machine and idempotency library. Another owns recon matching and exception UX with product. I'd staff on-call with runbooks before accelerating payout volume. Roadmap: correctness and observability first, then latency products like instant deposit — never the reverse.
>
> **Stakeholders.** Product on fee/withholding UX; finance/ops on exception SLAs; US payments on processor boundaries. My job is clear ownership and one money invariant across those surfaces.

---

## SCENE 6 — EM behavioral scripts

### “What are you looking for in your next role?”
→ Scene 1.

### “How do you hire?”

**SAY:**

> For a payments EM team I hire for three signals: can they reason about failure modes on a money path, do they raise the bar for others, and will they own outcomes without ego.
>
> Process: structured loop — coding or practical design, system design on settlement or recon, and behavioral on ownership. Same rubric for every candidate. I debrief with evidence, not vibe. At Skydo I hired into both platform and product tracks with different bars — platform needed deeper distributed-systems judgment.
>
> Red flags: "exactly-once" handwaves, blame-heavy incident stories, can't explain a tradeoff they made. Green flags: near-miss stories that became standards, clear metrics, mentorship examples.

### “How do you grow engineers? Senior → Staff?”

**SAY:**

> Context over answers in design review — I ask what happens if the partner succeeds and the response is lost.
>
> I give seniors a production invariant to own: idempotency library, settlement job framework, recon rules engine — and ask them to measure adoption. That's the Staff transition: from shipping features to defining contracts other teams consume.
>
> I've mentored about eight engineers that way at Skydo. Cadence: weekly 1:1s, written growth plans, and putting them in front of product and cross-team design reviews so influence isn't only inside the squad.

### “How do you handle underperformance?”

**SAY:**

> Early, specific, written. Clarify the bar with examples — design quality, delivery predictability, incident ownership. Time-boxed improvement plan with support: pairing, smaller scoped ownership, clearer review feedback. If it doesn't turn, I make the hard call — leaving someone in a money-path seat who's unreliable is unfair to them and dangerous for merchants. I've had to do that; I don't prolong ambiguity.

### “Prioritization / roadmap conflict with product”

**SAY:**

> I frame tradeoffs in merchant risk and cash-flow language, not eng preference. Example: product wants instant deposit speed; we still need UNKNOWN-state handling and recon SLAs first. I'll propose a sequenced plan — ship the correctness substrate, then the latency product — with dates and risk if we invert the order. Reversible UI debt yes; load-bearing payout debt no. At Skydo that framing usually aligned us; when it didn't, I escalated with options, not a blockage.

### “Tell me about a production incident you led as EM”

**SAY:**

> Partner timeout; ops assumed failure; retry risked double-send.
>
> As EM I owned the incident response: halted automated retries for that corridor, assigned polling with the original idempotency key, confirmed partner success, advanced state cleanly. No double-settlement.
>
> After: blameless review. Three actions I drove — explicit UNKNOWN in the state machine, on-call runbook, business SLO on PENDING over five minutes. Incidents of that class got faster and rarer. My job in the room was calm ownership and durable follow-through, not hero debugging alone.

### “How do you partner with your manager (Sr Manager)?”

**SAY:**

> Direct on risks early. Written options on irreversible bets. Metrics on money SLOs and team health — hiring pipeline, Senior→Staff progress, incident trends — not just feature burnup. I want a manager who cares about operational excellence; I'll bring problems with recommended paths, and I'll disagree openly when a timeline threatens correctness. Then commit once decided.

### “Conflict between two engineers”

**SAY:**

> I get both perspectives privately first, then a facilitated discussion on the blast radius — usually a design tradeoff, not a personality fight. We leave with a written decision or ADR. Example: retry with new UUID versus deterministic key — we diagrammed double-pay failure, chose deterministic keys, added contract tests. Relationship intact; invariant protected. One team, lead with humility — argue the risk, not the ego.

### “How do you think about team structure on Funds Management?”

**SAY:**

> I'd want clear ownership slices: payout initiation and netting; recon and exception ops tooling; withholdings/Capital/instant-deposit product integrations — with a shared platform bar for idempotency and state machines. Too many people on one codebase without owners creates diffusion. Too many silos without shared standards creates inconsistent money paths. Platform track for substrate, product track for merchant-facing funds features, same correctness checklist across both.

---

## SCENE 7 — “How do you measure success in first 90 / 180 days?”

**SAY:**

> **First 90.** Earn trust: understand payout and recon architecture, on-call pain, exception queues, stakeholder map. Ship one visible reliability or clarity improvement with the team — better SLO, runbook, or recon gap. Hire or advance at least one strong loop if there's an open req. 1:1s and a written team health read for you.
>
> **By 180.** Team delivering roadmap without correctness regressions; clear owners for payout versus recon; at least one Senior visibly moving toward Staff-shaped impact; incident MTTR and exception aging trending the right way. I'll propose the metrics with you rather than invent them in a vacuum.

---

## SCENE 8 — Your questions (EM-flavored — pick 3)

**Q1:**
> What does great look like for an EM on Funds Management in the first six months — team health, payout reliability, hiring, or a specific product like Capital or instant deposit?

**Q2:**
> How is the Payments org in Bengaluru structured today — what would my team own versus sibling teams, and how do you want EMs to partner with you as Sr Manager?

**Q3:**
> What's the hardest people or delivery challenge on the team right now — hiring bar, Senior depth, cross-timezone with US, operational load?

**Q4:**
> How do you weigh roadmap speed versus money-path correctness when product pressure is high?

**Q5:**
> What separates a strong EM from someone you'd hesitate to hire onto this Payments org?

---

## SCENE 9 — 45-min rehearsal map (EM)

| Min | Likely topic | You run |
|---|---|---|
| 0–3 | Tell me about yourself | Scene 0 |
| 3–8 | What are you looking for / why Toast | Scene 1–2 |
| 8–22 | System you led + tech depth | Scene 3–4 |
| 22–35 | EM behaviors (hire, grow, incident, conflict) | Scene 6 |
| 35–40 | 90-day plan / working with him | Scene 7 |
| 40–45 | Your questions | Scene 8 |

---

## Metrics card

- EM + CIO @ Skydo · led **12** engineers  
- VP Eng @ Goldman · led **9**  
- **10K+** txn/day payments + settlement + recon  
- Incidents **~−30%** via business SLOs  
- Owned **ISO 27001 + SOC 2**  
- Mentored **~8** engineers on distributed systems / leveling  

---

## EM one-liner if you blank

> I lead payments teams that move money correctly — I hire and raise the bar, I still dive deep on settlement failure modes, and I treat merchant cash-flow trust as the product outcome. That's the EM seat I want on Funds Management.
