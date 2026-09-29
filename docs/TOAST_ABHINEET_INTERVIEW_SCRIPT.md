# Toast × Abhineet — Interview Script (Speak These Answers)
**Round:** HM/Tech Screen · 45 min · 29 Sep 2026 · 10:00 IST  
**Use:** Read out loud once. Don't memorize word-for-word — lock the *shape* and numbers.

---

## SCENE 0 — Opening (he asks “Tell me about yourself”)

**SAY:**

> Sure. I'm Ramit. Most recently I've been founding engineer, then Engineering Manager and CIO at Skydo — a fintech for cross-border B2B payments. I designed the payments, settlement, and reconciliation platform from scratch. It processes about ten thousand international transactions a day.
>
> The hard problems weren't "scale to millions of QPS" — they were correctness under partial failure. Payment partners timeout without telling you if money moved. Retries can double-settle. Settlement files arrive T+1 with fees already deducted. So I spent a lot of energy on idempotency end-to-end, explicit state machines, durable intent before side effects, and reconciliation as the final safety net.
>
> Before Skydo I was VP of Engineering at Goldman Sachs on distributed market-risk compute — multi-terabyte in-memory clusters, live re-sharding without downtime.
>
> I'm talking to you because Funds Management at Toast is the same class of problem at restaurant scale — merchant payouts, fee and withholding netting, settlement correctness. If Friday's sales don't land correctly Monday, the restaurant can't pay staff. That's the money path I want to own hands-on again.

**STOP.** Let him pick the thread.

---

## SCENE 1 — “Why Toast? / Why this team?”

**SAY:**

> Two reasons. First, Toast uniquely owns both the restaurant operating system and the money rail. A lot of fintechs own one or the other. Funds Management is where merchant trust is won or lost every day — deposits, fees, Capital repayments, instant deposit. That's load-bearing product, not a back-office afterthought.
>
> Second, from what I understand of your team — Pricing and Funds Management building high-throughput settlement pipelines — that's exactly the work I've done at Skydo: settlement sweeps, bank partner integration under timeout ambiguity, recon that explains why net ≠ gross. I want that depth again at Toast's volume, on a team that already treats settlement as a first-class platform.

---

## SCENE 2 — “Walk me through the payment system you built.”

**SAY (5–7 min — this is your main deep dive):**

> Happy to. I'll structure it as problem → write path → settlement → recon → what I'd do differently.
>
> **Problem.** Cross-border B2B payments are hard for three reasons. Money cannot be wrong — a duplicate settlement is a real loss. Multiple external dependencies — payment providers, AD banks, SWIFT rails — each fail independently. And every transaction needs an audit trail for compliance; money can sit in limbo while a review happens.
>
> **Write path.** Every entry point — funding, settlement, ledger, refund — has an idempotency layer. Pattern is: Redis for the fast path so retries don't stampede, backed by a Postgres unique constraint on the idempotency key in the same transaction as the business write. Redis can crash. Postgres cannot silently accept a duplicate money movement.
>
> Inside the transaction we advance an explicit state machine — initiated, authorized, captured, settled, reconciled, plus disputed. We never skip states. For concurrent updates on the same payment we use optimistic locking with a version column, or FOR UPDATE on account-balance critical paths.
>
> Critical rule: **durable intent before side effect.** We write the intent to the DB first, then call the partner. Never partner-first. If we crash after the partner succeeds but before we record it, reconciliation and status polling close the gap — we don't invent magic exactly-once delivery.
>
> After commit we fan out on Kafka. Partition key is always `payment_id` so all events for one payment stay ordered. We learned that the hard way — someone used `invoice_id` in staging and you can get ledger entries before funding is confirmed. That became a team-wide rule in the eng playbook.
>
> **Settlement.** The riskiest hop is the bank settlement API. They can timeout at sixty seconds without telling you if the debit happened. We send a deterministic idempotency key derived from `payment_id` — never a random UUID on retry. On timeout the payment goes to UNKNOWN, we poll, exponential backoff with jitter, DLQ after three attempts. Batch failures are per-transaction, never "retry the whole batch" — that would double-settle the ones that already succeeded.
>
> **Recon.** Daily we match internal ledger against external settlement files. Exact match on processor ref, then rule-based partial matches for fee math — net is not gross. Unmatched and amount-mismatch go to an exception queue with aging SLAs. Re-runs are idempotent under a `reconciliation_run_id`.
>
> **Impact.** Ten thousand plus a day. Business SLO alerts — "stuck in PENDING more than five minutes" — cut production incidents about thirty percent. I also owned ISO 27001 and SOC 2 as CIO, so auditability wasn't bolted on later.
>
> **Regret, briefly.** I wish we'd event-sourced the payment state transitions from day one. We retrofitted a payment_events table later. Free audit trail and easier recon — painful to add after the fact.

---

## SCENE 3 — Follow-ups on the deep dive (rapid fire)

### “What if the Redis lock TTL expires mid-processing?”

**SAY:**

> TTL is a cache concern only. Correctness lives in Postgres. If the first request committed, the idempotency key row exists — the second attempt hits the unique constraint and we return the original outcome. If it didn't commit, the second attempt processes normally. Redis is speed, not source of truth. On money paths I fail closed if I can't establish durable dedupe.

### “What if the bank times out and you don't know if money moved?”

**SAY:**

> That's the UNKNOWN state. We do not retry with a new key — that creates a second payment. We poll the partner with the original deterministic idempotency key. If the partner confirms success, we advance to SETTLED. If failed, we mark FAILED and allow a clean retry. If still unknown past SLA, we page and recon against the settlement file is the backstop. Blind retry is how you double-pay.

### “How do you guarantee exactly-once?”

**SAY:**

> I don't claim magic exactly-once across the network. I design for at-least-once delivery plus idempotent consumers plus a durable unique business key. The combination gives you effectively-once side effects. Anyone who says the message bus alone is exactly-once is skipping the hard part.

### “Why Kafka? Toast uses Pulsar — is that a problem?”

**SAY:**

> Same contracts. Ordered fan-out, consumer groups, at-least-once, dead-letter, partition-key discipline. I've operated that model on Kafka. I'd ramp on Pulsar specifics — topics, cursors, consumer pause/resume — quickly. The design judgment transfers; the client API is learnable.

### “Why modular monolith vs microservices early on?”

**SAY:**

> Payments have tight transactional boundaries. Early on, distributed sagas across ten services would have added failure modes we didn't need. Modules talked through Kafka with clear interfaces, so extraction later is evolution, not a rewrite. Load-bearing money path stayed consistent; high-churn product surfaces could move faster. Reversible debt on UI is fine. Load-bearing debt on the state machine is not.

---

## SCENE 4 — “How would you design merchant daily payouts?” (Toast domain)

**SAY:**

> I'd treat payout as its own product with the same correctness bar as capture.
>
> **Inputs.** All captured and settled card transactions for merchant M covering business day D, plus refunds, chargebacks, processing fees, and withholdings — Capital repayment, equipment lease, delivery fees, instant-deposit fees, and so on.
>
> **Netting.**
> `net = gross card payments − refunds − fees − withholdings`
> Fees and withholdings are separate ledger lines. Restaurants debug both when deposit ≠ sales — conflating them makes support impossible.
>
> **Gates.** Don't initiate payout if net is negative, if there's a risk or KYC hold, or if a prior payout for the same settlement key is already in flight.
>
> **Idempotency.** Payout identity is deterministic: `(merchant_id, settlement_date, rail, batch_id)`. Unique constraint. State machine: CALCULATED → INITIATED → SUBMITTED → CONFIRMED → RECONCILED, plus FAILED / UNKNOWN.
>
> **Execution.** Durable payout intent first, then ACH or processor call with that same idempotency key. On timeout — UNKNOWN + poll, never new payout id.
>
> **Cutoff clocks.** Batch cutoffs are business contracts. Late batch shifts expected deposit day; weekends and banking holidays push. The system should expose expected deposit date so support and the merchant aren't guessing.
>
> **Recon.** Expected payout amount and date versus actual bank credit versus the per-transaction contribution list. Exception aging with escalation — never silently absorb a money gap.
>
> **Instant deposit** is a parallel rail: faster, priced differently, stricter risk limits, same idempotency rules. I wouldn't shortcut correctness to make money arrive faster.

**If he asks multi-location:**

> Decision point is per-location netting versus rolled account. Per-location is clearer for restaurant operators reconciling one store. Rolled is simpler for treasury. I'd default to per-location payout identity with optional rollup reporting, unless product has a strong reason otherwise.

---

## SCENE 5 — “How does reconciliation work?”

**SAY:**

> Three inputs: internal transaction events, processor or bank settlement files, and optionally bank feed credits.
>
> Normalize everything to minor units. Keep gross, fee, and net separate.
>
> Matching waterfall: exact on processor reference, then order or merchant reference, then rule-based partial match within fee-tolerance, else exception queue.
>
> Exception types: unmatched internal — settlement delayed or capture failed; unmatched external — missing internal record; amount mismatch — fee or FX; duplicates; chargebacks routed to disputes.
>
> Matching is idempotent under a run id so re-processing a file doesn't double-resolve. Unresolved past SLA auto-escalates.
>
> At Skydo this is what closed silent partner success. At Toast it's what a restaurant owner is doing when they compare Sales Summary to Payout Overview to their bank statement — timing, fees, withholdings, batch cutoffs.

---

## SCENE 6 — Behavioral scripts (STAR, 90–120 sec each)

### “Tell me about a production incident involving money.”

**SAY:**

> **Situation.** We had a settlement partner return a gateway timeout. Ops assumed failure. The automated retry path was about to fire with what could have been treated as a new attempt.
>
> **Task.** Confirm whether money had actually moved, prevent a double-send, and restore a clear state for the customer and for on-call.
>
> **Action.** I halted automated retries for that corridor immediately. We polled the partner with the original deterministic idempotency key. Ledger said IN_FLIGHT; partner eventually confirmed success. We advanced to SETTLED, did not retry. Then we made three durable changes: an explicit UNKNOWN state in the state machine, a runbook for on-call, and a business SLO alert on PENDING longer than five minutes so we don't discover this from a customer ticket.
>
> **Result.** No double-settlement. MTTR on similar issues dropped because on-call had a playbook. That incident is also why I treat partner timeout as a first-class design case, not an edge case.

### “Tell me about raising the engineering bar.”

**SAY:**

> Two examples. First, idempotency and partition-key discipline. After a staging near-miss where a consumer used `invoice_id` instead of `payment_id`, I didn't just fix the bug — I wrote it into the eng playbook and made it a code-review checkbox on every money-path PR. Near-misses are cheaper teachers than production losses.
>
> Second, I split the team into a platform track — payments, settlement, recon — and a product track. Platform had a higher design bar and ADR requirement for data models and integrations. Product moved fast inside clear contracts. Mixing those bars slows both down.
>
> The measurable outcome was fewer severity-one incidents — about thirty percent down after we also shifted alerting to business SLOs — and faster onboarding because ADRs explained *why*, not just *what*.

### “Tell me about a disagreement with another engineer.”

**SAY:**

> An engineer wanted to ship a settlement retry by generating a fresh UUID on each attempt — simpler client code. I pushed back because that breaks partner-side dedupe and creates double-pay risk under timeout.
>
> I didn't win by seniority. I walked through the failure mode with a sequence diagram: timeout after success, retry with new key, two debits. We agreed retries must reuse the business idempotency key, and we added a unit test and contract test that fail if a new key is minted on retry.
>
> Relationship stayed fine — the point was shared ownership of the money invariant, not being right in the room. That's how I think about "one team" and leading with humility: argue the blast radius, then leave a durable guardrail.

### “How do you balance speed vs correctness?”

**SAY:**

> Reversible debt is fine. Load-bearing debt is not. I'll let a team cut corners on a UI experiment. I will not let them shortcut a payment state machine that ten services depend on. At Skydo a settlement bug meant real money stuck — reliability was a product requirement, not engineering overhead. For Funds Management I'd hold the same line: ship fast on reporting UX; go slow and explicit on payout initiation and netting math.

### “How do you mentor / grow seniors toward Staff?”

**SAY:**

> Context over answers. In design reviews I ask failure-mode questions — what happens if the partner succeeds and your response is lost? What's your dedupe key? What's the blast radius of this lock?
>
> I also separate "ship the feature" from "define the contract other teams will reuse." Seniors who start writing the idempotency library, the settlement job framework, or the recon rule engine — and measuring adoption — are operating at Staff. I've mentored that transition with eight engineers at Skydo by giving them a real production invariant to own, not a toy project.

### “Senior vs Staff — where do you sit?”

**SAY:**

> I operate at Staff. Senior ships complex features well inside a team. Staff defines contracts, failure modes, safe defaults, and reusable substrate so multiple teams ship correctly — and defends those invariants under pressure. At Skydo I wasn't just implementing settlement; I set the money-path standards, owned recon as a platform capability, and structured the team so product could move without breaking correctness. That's the seat I want on Funds Management.

---

## SCENE 7 — Light system / coding prompts (if he goes technical)

### “Design offline POS payment without double-charge on sync”

**SAY:**

> Terminal writes a durable local payment intent with a client-generated idempotency key before any capture attempt. Sync queue replays that same key to the server. Server dedupes on the key — second sync is a no-op returning the original capture result. For check edits I'd be careful: last-write-wins is dangerous on money; prefer explicit payment states over silently merging concurrent edits. Happy to go deeper on terminal sync — or stay on settlement and payouts if that's more useful for Funds Management.

### “Model a payment state machine” (verbal or whiteboard)

**SAY while sketching:**

```
INITIATED → AUTHORIZED → CAPTURED → SETTLED → RECONCILED
                ↓             ↓          ↓
             FAILED       REFUNDED   DISPUTED
                ↑
            UNKNOWN  (only from in-flight partner calls; exits via poll or recon)
```

> Transitions are versioned. Side effects — partner calls, ACH — only fire on specific transitions and are keyed by the payment or payout id. UNKNOWN cannot go straight back to INITIATED with a new key.

### “Deduplicate webhook retries”

**SAY:**

> Webhooks are at-least-once. I dedupe on a business event id from the partner, store processed event ids with the resulting state transition, and make handlers idempotent — processing twice yields the same ledger state. I never trust "we only send once."

---

## SCENE 8 — Closing / your questions (last 5 min)

**When he asks “Any questions for me?” — pick 3:**

**Q1:**
> What's the hardest correctness or scale problem Funds Management is solving in the next six to twelve months — payout latency, multi-product withholdings, recon UX for restaurants, Capital and instant deposit, or something else?

**Q2:**
> How does Bengaluru Payments share ownership with US payments and processor integrations — where does the boundary sit for a Staff engineer on your team?

**Q3:**
> For someone joining at Staff, is success more about building shared settlement substrate other teams consume, or delivering product surfaces like Capital withholdings and instant deposit? Or both — and how do you weigh them?

**Q4 (optional):**
> What separates a strong Senior from someone you'd hire at Staff on Funds Management?

**Q5 (optional):**
> How do you think about ops excellence here — payout incident MTTR, recon exception SLAs, that kind of thing?

**If he asks “What else should I know about you?”:**

> Only that I care about money paths the way restaurants care about Monday deposits. I'm at my best when correctness, operability, and team standards are the product. I'd rather prevent one double-payout in design review than ship three features that leave UNKNOWN states undefined.

---

## SCENE 9 — Full 45-min rehearsal map (practice once)

| Min | He says | You run |
|---|---|---|
| 0–2 | Intro / tell me about yourself | Scene 0 |
| 2–5 | Why Toast | Scene 1 |
| 5–20 | Walk through your system | Scene 2 + 3 follow-ups |
| 20–30 | Design payouts OR recon OR incident | Scene 4 / 5 / 6 |
| 30–38 | Behavioral / Staff bar | Scene 6 |
| 38–45 | Your questions | Scene 8 |

---

## Metrics card (say exactly these)

- **10K+** international txns/day at Skydo  
- Production incidents **~−30%** after business SLO alerting  
- Led **12** engineers at Skydo, **9** at Goldman as VP  
- GS: failover **−40%**, batch **−25%**, **3×** throughput (when relevant)  
- Owned **ISO 27001 + SOC 2 Type II** as CIO  
- Moneyview prior: **5M+/mo** debit instructions (if scale comparison comes up)

---

## One-liner if you blank

> I design payment systems for correctness under partial failure — idempotency at every boundary, explicit state machines, durable intent before side effects, and reconciliation as the final safety net. At ten thousand transactions a day, one duplicate settlement is a real loss. That shaped every decision.
