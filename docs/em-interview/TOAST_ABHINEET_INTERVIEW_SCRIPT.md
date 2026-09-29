# Toast EM Screen — Complete Q&A Script
**Role:** Engineering Manager — Payments / Funds Management  
**Interviewer:** Abhineet Mishra, Sr Manager SWE · Bengaluru  
**When:** Tue 29 Sep 2026 · 10:00–10:45 IST  

**How to use:** Read out loud once. Lock shape + numbers. Don't recite robotically.

---

# PART A — OPENING & MOTIVATION

---

## Q1. “Tell me about yourself.” / “Walk me through your background.”

**SAY:**

> Sure. I'm Ramit. I'm an Engineering Manager who's built and led payments platforms end-to-end.
>
> At Skydo — a cross-border B2B fintech — I was founding engineer, then Engineering Manager and CIO. I designed the payments, settlement, and reconciliation platform from scratch, and I built the engineering organization around it — about twelve engineers. That platform processes roughly ten thousand international transactions a day.
>
> As EM my job was twofold. Keep the money path correct under partner failure — idempotency, state machines, settlement timeouts, reconciliation. And raise the team so correctness was a standard, not heroics. I split platform versus product tracks, enforced architecture decision records on load-bearing changes, and moved alerting to business SLOs — that cut production incidents about thirty percent. As CIO I owned ISO 27001 and SOC 2 Type II end-to-end.
>
> Before Skydo I was VP of Engineering at Goldman Sachs, leading nine engineers on distributed market-risk compute — multi-terabyte in-memory systems, live re-sharding without downtime.
>
> I'm talking to you because I want an EM seat on Funds Management at Toast — lead the team that owns merchant payouts and settlement at restaurant scale, and stay deep enough to call money-path decisions myself.

---

## Q2. “What are you looking for?”

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

---

## Q3. “Why EM and not Staff / IC?”

**SAY:**

> I've done both builder and EM. I'm choosing EM because the leverage I want next is multiplying correctness through a team — hiring, standards, roadmap tradeoffs, mentoring — while still diving deep on architecture.
>
> At Skydo the EM seat grew organically from founding engineer. I know how to stay technical without becoming a bottleneck: I own invariants and design reviews on the money path; the team owns day-to-day delivery.
>
> Your posting for an EM who's passionate about high-performing teams and large-scale payments is exactly that intersection. If the role needed a pure Staff IC only, I'd say so — but I want the EM seat.

---

## Q4. “Why Toast?” / “Why this team?”

**SAY:**

> Two reasons. First, Toast owns both the restaurant operating system and the money rail. Funds Management is where merchant trust is won or lost every day — deposits, fees, Capital repayments, instant deposit. Leading that team is high-leverage EM work: every standard you set protects real cash flow for restaurants.
>
> Second, Bengaluru Payments is growing — you're hiring EMs and Staff for settlement pipelines. I want the EM seat: hire and develop the people who build those pipelines, set the bar on fault-tolerant settlement, and deliver with product. That's the job I've been doing at Skydo; Toast is the scale and domain I want next.

---

## Q5. “Why are you leaving / why now?”

**SAY:**

> I've taken Skydo's payments platform from zero to a production system with a team and compliance posture I'm proud of. The next chapter I want is leading that class of problem at larger scale, in a Payments org that's already serious about funds and settlement.
>
> Toast is that opportunity — public company, restaurant money rail, Funds Management as a first-class team. Timing is about growth of scope and impact, not running from a fire.

*(Adjust honesty to your real situation; keep it forward-looking, never trash prior employers.)*

---

## Q6. “What do you know about Toast / Funds Management?”

**SAY:**

> Toast is the restaurant OS — POS, online ordering, payroll, and payments. Funds Management sits on the merchant money side: after guests pay, Toast batches and settles, then pays out to the restaurant bank — typically next business day or T+2 depending on batch cutoff — net of refunds, processing fees, and withholdings like Capital or EasyPay or delivery fees.
>
> Merchants reconcile deposits against sales; when numbers don't match it's usually timing, fees, or withholdings. Instant deposit is a faster rail with its own economics.
>
> That's the same family of problems I've led: settlement sweeps, partner timeouts, fee-aware reconciliation, idempotent payouts. I'd go deeper on Toast-specific processor and product details with the team — I won't pretend I already know every Capital underwriting detail.

---

# PART B — SYSTEM / TECHNICAL DEEP DIVE

---

## Q7. “Walk me through a system you owned / built.”

**SAY:**

> I'll cover the system and how I led it — as EM both matter.
>
> **Business problem.** Cross-border B2B payments. Money cannot be wrong. Partners — providers, AD banks, SWIFT rails — fail independently. Compliance can hold funds while money sits in limbo. About ten thousand transactions a day.
>
> **Write path.** Idempotency at every entry point. Redis for the fast path so retries don't stampede; Postgres unique constraint on the idempotency key in the same transaction as the business write — Redis can die, Postgres cannot silently accept a duplicate. Explicit state machine: initiated → authorized → captured → settled → reconciled, plus disputed and unknown. Durable intent before any partner side effect — never call the bank first. After commit, Kafka fan-out with partition key always `payment_id` so events for one payment stay ordered.
>
> **Settlement.** Bank APIs can timeout at sixty seconds without saying if money moved. Deterministic idempotency key derived from payment id — never a new UUID on retry. On timeout: UNKNOWN state, poll, backoff, DLQ. Never retry a whole batch — that double-settles the successes.
>
> **Reconciliation.** Internal ledger versus external settlement files. Exact match, then rule-based partial match for fees — net is not gross. Exception queue with aging SLAs. Re-runs idempotent under a run id.
>
> **How I led it.** Near-misses became eng playbook rules — like partition key discipline. Platform track for payments/settlement/recon with a higher design bar; product track moving fast inside contracts. ADRs for load-bearing decisions. Business SLO alerts — stuck PENDING more than five minutes — cut incidents about thirty percent. Team of twelve. Reliability treated as product requirement because settlement bugs stuck real money.

---

## Q8. “What if Redis lock TTL expires mid-processing?”

**SAY:**

> TTL is a cache concern only. Correctness lives in Postgres. If the first request committed, the idempotency key exists — the second attempt hits the unique constraint and we return the original outcome. If it didn't commit, the second attempt processes normally. Redis is speed, not source of truth.
>
> On money paths I fail closed if I can't establish durable dedupe. As EM I wouldn't accept a design review that treats Redis alone as the idempotency guarantee.

---

## Q9. “Bank / partner timed out — did money move?”

**SAY:**

> That's the UNKNOWN state. We do not retry with a new key — that creates a second payment. We poll the partner with the original deterministic idempotency key. Partner success → advance to SETTLED. Failed → mark FAILED and allow a clean retry. Still unknown past SLA → page humans; settlement-file recon is the backstop.
>
> Blind retry is how you double-pay. As EM I made UNKNOWN a first-class state after a real incident, with a runbook so on-call doesn't guess.

---

## Q10. “How do you guarantee exactly-once?”

**SAY:**

> I don't claim magic exactly-once across the network. I design for at-least-once delivery, plus idempotent consumers, plus a durable unique business key. That combination gives effectively-once side effects.
>
> Anyone who says the message bus alone is exactly-once is skipping the hard part. I'd push back on that in a design review on my team.

---

## Q11. “Why Kafka? Toast uses Pulsar.”

**SAY:**

> Same contracts: ordered fan-out, consumer groups, at-least-once, dead-letter, partition-key discipline. I've operated that model on Kafka. I'd ramp on Pulsar specifics — topics, cursors, consumer pause/resume — quickly. Design judgment transfers; client API is learnable. I wouldn't fake Pulsar production scars I don't have.

---

## Q12. “Why modular monolith vs microservices?”

**SAY:**

> Early on, payments had tight transactional boundaries. Distributed sagas across many services would have added failure modes we didn't need at that team size. Modules talked through Kafka with clear interfaces, so extraction later is evolution, not a rewrite.
>
> Tradeoff I still hold as EM: reversible debt on UI is fine; load-bearing debt on the payment state machine is not. If I were redesigning today at larger scale, Settlement and FX are the first modules I'd extract — divergent scaling and regulatory boundaries.

---

## Q13. “How would you design merchant daily payouts?”

**SAY:**

> As EM I'd split outcome, architecture bar, and team plan.
>
> **Outcome.** Merchants get the right net deposit on the promised day, and can explain fees versus withholdings when deposit doesn't match sales.
>
> **Netting.** Gross card payments minus refunds minus fees minus withholdings. Fees and withholdings are separate ledger lines — restaurants debug both.
>
> **Gates.** Don't pay if net negative, risk/KYC hold, or a payout for the same settlement key is already in flight.
>
> **Idempotency.** Deterministic payout id: merchant, settlement date, rail, batch id. Unique constraint. State machine: calculated → initiated → submitted → confirmed → reconciled, plus failed and unknown.
>
> **Execution.** Durable payout intent first, then ACH or processor call with that same key. Timeout → UNKNOWN and poll — never mint a new payout id.
>
> **Cutoffs.** Batch cutoffs are business contracts. Late batch shifts expected deposit day; weekends and holidays push. System should expose expected deposit date.
>
> **Recon.** Expected payout versus bank credit versus per-transaction contribution list. Exception aging — never silently absorb a money gap.
>
> **Team plan.** Strong Senior or Staff owns payout state machine and idempotency. Another owns recon and exception UX with product. Runbooks before accelerating volume. Correctness and observability before latency products like instant deposit — never the reverse.

---

## Q14. “How does multi-location payout work?”

**SAY:**

> Decision is per-location netting versus rolled account. Per-location is clearer for operators reconciling one store. Rolled is simpler for treasury. I'd default to per-location payout identity with optional rollup reporting, unless product has a strong reason otherwise. Same idempotency rules either way — identity must include location if that's the grain of the payout.

---

## Q15. “How does reconciliation work?”

**SAY:**

> Three inputs: internal transaction events, processor or bank settlement files, optionally bank feed credits.
>
> Normalize to minor units. Keep gross, fee, and net separate.
>
> Matching waterfall: exact on processor reference, then order or merchant reference, then rule-based partial match within fee tolerance, else exception queue.
>
> Exception types: unmatched internal — delayed settlement or failed capture; unmatched external — missing internal record; amount mismatch — fee or FX; duplicates; chargebacks to disputes.
>
> Matching is idempotent under a run id. Unresolved past SLA auto-escalates.
>
> At Skydo this closed silent partner success. At Toast it's what a restaurant owner does comparing Sales Summary to Payout Overview to their bank statement.

---

## Q16. “Fees vs withholdings — how do you account for them?”

**SAY:**

> Fees are cost of accepting cards — processing. Withholdings are amounts held for other products — Capital repayment, equipment lease, delivery fees, instant-deposit fees. Both reduce net payout, but they must be separate ledger lines and report rows.
>
> If you conflate them, support and merchants can't answer "why was my deposit less than sales?" As EM I'd treat that reporting clarity as part of the payout product, not a nice-to-have.

---

## Q17. “How technical are you day to day as EM?”

**SAY:**

> I don't write every feature, but I own the architectural invariants. I still do design reviews on money-path changes, write or co-write ADRs, and jump into incidents when settlement is ambiguous.
>
> I measure myself on whether the team ships correctly without me — standards, runbooks, Staff-ready seniors — not on my commit count. At Skydo I could still walk the write path and settlement failure modes cold, which is what let me push back on risky shortcuts.

---

## Q18. “Offline POS takes a payment — how do you avoid double-charge on sync?”

**SAY:**

> Terminal writes a durable local payment intent with a client-generated idempotency key before capture. Sync replays that same key. Server dedupes — second sync is a no-op returning the original result. For money I'd avoid silent last-write-wins on concurrent check edits; prefer explicit payment states.
>
> Happy to go deeper on terminal sync — or stay on settlement and payouts if that's more useful for Funds Management. For an EM role on funds, I'd usually steer to payout correctness unless you want POS.

---

## Q19. “What's your biggest technical regret?”

**SAY:**

> Not event-sourcing payment state transitions from day one. We reconstructed state by tracing Kafka topics, then retrofitted a payment_events table. Free audit trail and easier recon — painful to add later.
>
> As EM the lesson I took: load-bearing auditability is cheaper on day one than as a compliance scramble. I'd budget that explicitly on a Funds Management roadmap.

---

# PART C — EM LEADERSHIP & BEHAVIORAL

---

## Q20. “How do you hire?”

**SAY:**

> For a payments EM team I hire for three signals: can they reason about failure modes on a money path, do they raise the bar for others, and will they own outcomes without ego.
>
> Process: structured loop — practical coding or design, system design on settlement or recon, behavioral on ownership. Same rubric every candidate. Debrief with evidence, not vibe.
>
> At Skydo I hired into platform and product tracks with different bars — platform needed deeper distributed-systems judgment.
>
> Red flags: exactly-once handwaves, blame-heavy incident stories, can't explain a tradeoff. Green flags: near-miss stories that became standards, clear metrics, mentorship examples.

---

## Q21. “How do you grow engineers? Senior → Staff?”

**SAY:**

> Context over answers in design review — what happens if the partner succeeds and your response is lost? What's your dedupe key?
>
> I give seniors a production invariant to own: idempotency library, settlement job framework, recon rules engine — and ask them to measure adoption. That's Staff: from shipping features to defining contracts other teams consume.
>
> I've mentored about eight engineers that way at Skydo. Cadence: weekly 1:1s, written growth plans, putting them in front of product and cross-team reviews so influence isn't only inside the squad.

---

## Q22. “How do you handle underperformance?”

**SAY:**

> Early, specific, written. Clarify the bar with examples — design quality, delivery predictability, incident ownership. Time-boxed improvement plan with support: pairing, smaller scoped ownership, clearer review feedback.
>
> If it doesn't turn, I make the hard call. Leaving someone unreliable in a money-path seat is unfair to them and dangerous for merchants. I've had to do that; I don't prolong ambiguity.

---

## Q23. “Tell me about a production incident you led as EM.”

**SAY:**

> **Situation.** A settlement partner returned a gateway timeout. Ops assumed failure. Automated retry was about to fire in a way that could double-send.
>
> **Task.** Confirm whether money moved, prevent double-pay, restore clear state for customer and on-call.
>
> **Action.** As EM I owned the response: halted automated retries for that corridor, assigned polling with the original deterministic idempotency key, confirmed partner success, advanced to settled — no retry. Then blameless review with three durable actions I drove: explicit UNKNOWN state in the state machine, on-call runbook, business SLO alert on PENDING longer than five minutes.
>
> **Result.** No double-settlement. Similar issues got faster and rarer. My job in the room was calm ownership and follow-through, not hero debugging alone.

---

## Q24. “Tell me about raising the engineering bar.”

**SAY:**

> Two examples. First, idempotency and partition-key discipline. After a staging near-miss where a consumer used invoice_id instead of payment_id, I didn't just fix the bug — I wrote it into the eng playbook and made it a code-review checkbox on money-path PRs.
>
> Second, I split platform versus product tracks. Platform — payments, settlement, recon — had a higher design bar and ADR requirement. Product moved fast inside clear contracts. Mixing those bars slows both down.
>
> Outcome: fewer sev-1s — about thirty percent down after business SLO alerting — and faster onboarding because ADRs explained why, not just what.

---

## Q25. “Describe a disagreement with an engineer or partner team.”

**SAY:**

> An engineer wanted settlement retries to mint a fresh UUID each attempt — simpler client code. I pushed back because that breaks partner-side dedupe and creates double-pay under timeout.
>
> I didn't win by title. We walked a sequence diagram: timeout after success, retry with new key, two debits. We agreed retries reuse the business idempotency key, and added contract tests that fail if a new key is minted on retry.
>
> Relationship stayed fine. Argue the blast radius, leave a durable guardrail. That's one team and lead with humility in practice.

---

## Q26. “Conflict between two engineers on your team.”

**SAY:**

> I get both perspectives privately first, then facilitate on the blast radius — usually a design tradeoff, not personality. We leave with a written decision or ADR.
>
> Same UUID-versus-deterministic-key example works here: diagram the failure, pick the invariant, add tests, move on. I don't let money-path disagreements stay verbal-only.

---

## Q27. “How do you prioritize / handle roadmap conflict with product?”

**SAY:**

> I frame tradeoffs in merchant risk and cash-flow language, not eng preference. Example: product wants instant deposit speed; we still need UNKNOWN-state handling and recon SLAs first.
>
> I propose a sequenced plan — correctness substrate, then latency product — with dates and risk if we invert the order. Reversible UI debt yes; load-bearing payout debt no.
>
> At Skydo that usually aligned us. When it didn't, I escalated with options, not a silent blockage.

---

## Q28. “How do you balance speed vs correctness?”

**SAY:**

> Reversible debt is fine. Load-bearing debt is not. I'll let a team cut corners on a UI experiment. I will not let them shortcut a payment state machine ten services depend on.
>
> At Skydo a settlement bug meant real money stuck — reliability was a product requirement, not engineering overhead. On Funds Management I'd hold the same line: ship fast on reporting UX; go slow and explicit on payout initiation and netting.

---

## Q29. “How do you partner with your manager (Sr Manager)?”

**SAY:**

> Direct on risks early. Written options on irreversible bets. Metrics on money SLOs and team health — hiring pipeline, Senior-to-Staff progress, incident trends — not only feature burnup.
>
> I bring problems with recommended paths. I'll disagree openly when a timeline threatens correctness, then commit once decided. I want a manager who cares about operational excellence — sounds aligned with how you ran finance automation at Amazon and Payments here.

---

## Q30. “How do you work with product / design / ops?”

**SAY:**

> Shared outcome language: merchant gets the right deposit on the promised day. Eng owns invariants and operability; product owns prioritization and UX for payouts and recon; ops owns exception handling SLAs — and we design the queues together so ops isn't drowning.
>
> Cadence: joint roadmap review, shared incident reviews, ADRs when we change money semantics. I don't throw "no" over the wall — I throw sequenced options with risk.

---

## Q31. “How would you structure a Funds Management team?”

**SAY:**

> Clear ownership slices: payout initiation and netting; recon and exception tooling; withholdings / Capital / instant-deposit integrations — with a shared platform bar for idempotency and state machines.
>
> Too many people on one codebase without owners creates diffusion. Too many silos without shared standards creates inconsistent money paths. Platform track for substrate, product track for merchant-facing funds features, same correctness checklist across both. On-call with runbooks before we amp volume.

---

## Q32. “How do you run delivery / execution?”

**SAY:**

> Clear owners, small milestones, visible risks. For money-path work: design review before build, feature flags or dark launches where possible, recon and SLO dashboards as launch criteria — not "tests passed" alone.
>
> Standups for blockers, not theater. I protect focus from drive-bys. If a commit date is at risk I'll say so early with a cut plan — scope, sequencing, or staffing — never a surprise slip the day before.

---

## Q33. “Tell me about a time you failed / missed a deadline.”

**SAY:**

> We underestimated partner certification for a new settlement corridor and slipped an external launch window. My miss was not forcing an earlier integration spike with the bank's sandbox failure modes.
>
> What I changed: any new money rail gets a time-boxed failure-mode spike — timeouts, duplicate ACKs, partial batch failures — before we commit a date. We also publish a "confidence" flag on launch dates tied to integration readiness, not just eng coding progress.
>
> I owned the slip with product and the customer-facing team — no blame-shifting to the partner alone.

---

## Q34. “Tell me about mentoring / developing someone.”

**SAY:**

> A mid-level engineer was strong at features but shallow on failure modes. I put them on ownership of settlement retry and DLQ handling, with me in design reviews asking timeout and double-pay questions until they owned the answers.
>
> Within a few months they were reviewing others' money-path PRs for idempotency and writing the runbook section themselves. That's the pattern: real production invariant, high-support coaching, then step back.

---

## Q35. “How do you create psychological safety while keeping a high bar?”

**SAY:**

> Blameless on people, ruthless on systems. In incidents we ask what allowed the failure — missing UNKNOWN state, no runbook — not who to punish. Then we still keep a high design bar: I will block a payout change that mints new idempotency keys on retry.
>
> People feel safe to surface near-misses because near-misses become standards, not performance weaponry. The bar is on the work product.

---

## Q36. “How do you handle on-call / operational load?”

**SAY:**

> On-call is a product of design quality. If the team is page-fatigued, I treat that as a roadmap input — SLOs, runbooks, eliminate classes of pages — not a badge of honor.
>
> Fair rotation, shadowing for new on-calls, blameless reviews with action items that get scheduled. Business alerts beat raw CPU alerts. At Skydo that shift cut incidents about thirty percent and made on-call human.

---

## Q37. “Describe your leadership style.”

**SAY:**

> High ownership, high clarity, low ego. I set invariants and context, then get out of the way. I'm closer on money-path design and incidents; looser on reversible product UI.
>
> I write things down — ADRs, growth plans, incident actions. I prefer disagree-and-commit over false harmony. Toast values like one team and lead with humility match how I already try to operate.

---

## Q38. “How do you measure success in first 90 / 180 days?”

**SAY:**

> **First 90.** Earn trust: learn payout and recon architecture, on-call pain, exception queues, stakeholder map. Ship one visible reliability or clarity win with the team. Keep hiring loop healthy if there's a req. Establish 1:1s and give you a written team-health read.
>
> **By 180.** Roadmap delivering without correctness regressions; clear owners for payout versus recon; at least one Senior moving toward Staff-shaped impact; incident MTTR and exception aging trending right. I'd propose the exact metrics with you rather than invent them alone.

---

## Q39. “How do you think about diversity / inclusion on your team?”

**SAY:**

> High bar, wide aperture. Structured interviews and rubrics reduce gut-feel bias. I source beyond the usual networks and make onboarding explicit — ADRs, playbooks — so success isn't "who already knows the undocumented lore."
>
> Psychological safety matters on money teams especially: people must feel safe to escalate risk without politics.

---

## Q40. “Tell me about aligning with Toast values” (One Team / Humility / Hungry / Ownership / Customer)

**SAY:**

> **Ownership** — settlement bug meant real money stuck; reliability was a product requirement.
>
> **Lead with humility** — near-misses became playbooks; blameless incidents with concrete actions.
>
> **One team** — platform and product tracks with shared contracts; compliance and ops in the state-machine design, not afterthoughts.
>
> **Customer success** — for Funds Management the customer is the restaurant depending on Monday's deposit. I design for that cash-flow anxiety: clear netting, explainable fees versus withholdings, alerts before the merchant calls support.
>
> **Always hungry** — raised the bar after every near-miss; still want larger-scale funds problems.

---

# PART D — LEVEL, COMP, WORKING STYLE

---

## Q41. “What level are you targeting?”

**SAY:**

> Engineering Manager for a payments / funds team — owning a squad that delivers settlement and payout systems, hiring and developing toward Staff-depth ICs, partnering with a Sr Manager like you on roadmap and org health. I'm not shooting for Director on day one; I want to earn scope through delivery and team outcomes.

---

## Q42. “What's your working style with stakeholders across time zones?”

**SAY:**

> Bengaluru with US partners means written clarity and overlap-hour discipline. ADRs and decision logs beat verbal-only agreements. Critical money-path launches get explicit go/no-go with US stakeholders in writing. I protect the team's focus hours and use overlap for decisions, not status theater.

---

## Q43. “Do you still code?”

**SAY:**

> Yes when it helps — spikes, incident forensics, reference implementations for standards — not as the team's primary throughput. My coding time should unblock or teach, not compete with seniors for feature tickets. On a Funds Management team I'd expect to stay fluent in the payout and recon codepaths.

---

## Q44. “Salary / comp expectations?” *(only if asked)*

**SAY:**

> I'm flexible and focused on the right EM scope and team. Happy to align with Toast's band for Engineering Manager in Bengaluru Payments once we confirm fit. I'd like the full picture — base, bonus, equity, level — and I'll be straightforward.

*(Have a number range ready privately; don't negotiate hard in the first HM screen unless he opens it.)*

---

# PART E — YOUR QUESTIONS FOR HIM (pick 3–4)

---

## Q45. Ask: Six-month success

> What does great look like for an EM on Funds Management in the first six months — team health, payout reliability, hiring, or a specific product like Capital or instant deposit?

## Q46. Ask: Team ownership

> How is Payments in Bengaluru structured today — what would my team own versus sibling teams, and how do you want EMs to partner with you as Sr Manager?

## Q47. Ask: Hardest challenge

> What's the hardest people or delivery challenge on the team right now — hiring bar, Senior depth, cross-timezone with US, operational load?

## Q48. Ask: Speed vs correctness

> How do you weigh roadmap speed versus money-path correctness when product pressure is high?

## Q49. Ask: EM bar

> What separates a strong EM from someone you'd hesitate to hire onto this Payments org?

## Q50. Ask: Tech landscape

> Which part of the funds stack is most painful today — payout initiation, netting/withholdings, recon/exceptions, or instant deposit — and where would you want a new EM to lean in first?

---

# PART F — CLOSING

---

## Q51. “Any questions for me?” / wrap-up

Pick 3 from Part E, then:

> Thanks — this reinforced that Funds Management is the EM seat I want. Happy to go deeper in later rounds on hiring plans, a payout design, or how I've run delivery on money systems.

## Q52. “Anything else I should know about you?”

**SAY:**

> Only that I care about money paths the way restaurants care about Monday deposits. I'm at my best leading teams where correctness, operability, and standards are the product — and I still dive deep enough to catch a double-pay design in review. That's the EM I want to be on your team.

---

# CHEAT: 45-MIN FLOW

| Min | Questions likely | Use |
|---|---|---|
| 0–8 | Q1–Q6 | Opening & motivation |
| 8–22 | Q7–Q17 | System + tech depth |
| 22–38 | Q20–Q38 | EM leadership |
| 38–45 | Q45–Q52 | Your asks + close |

---

# METRICS CARD (say exactly)

- EM + CIO @ Skydo · led **12** engineers  
- VP Eng @ Goldman · led **9**  
- **10K+** txn/day  
- Incidents **~−30%**  
- Mentored **~8** engineers  
- Owned **ISO 27001 + SOC 2 Type II**

---

# ONE-LINER IF YOU BLANK

> I lead payments teams that move money correctly — I hire and raise the bar, I still dive deep on settlement failure modes, and I treat merchant cash-flow trust as the product outcome.
