# Toast HM/Tech Screen — Day-of Cheat Sheet
**Abhineet Mishra · Sr Manager, SWE · Funds Management (Payments) · Bengaluru**  
**Tue 29 Sep 2026 · 10:00–10:45 IST · Virtual · 45 min**

**Full spoken answers:** `TOAST_ABHINEET_INTERVIEW_SCRIPT.md` (rehearse out loud)

---

## 90-sec open (say this)

> I'm Ramit — founding eng / EM / CIO at Skydo, a cross-border B2B payments company. I designed the payments + settlement + reconciliation platform from scratch — 10K+ txns/day. Core problems were correctness under partner failure: idempotency end-to-end, concurrent state machines, settlement sweeps, and recon that closes silent partner success. Before that, VP at Goldman Sachs on distributed market-risk compute. I'm talking to you because Funds Management at Toast is the same class of problem at restaurant scale — merchant payouts, fee withholdings, Capital repayments, settlement correctness — and I want to own that money path hands-on again.

---

## Who Abhineet is (calibrate to him)

| Fact | Implication |
|---|---|
| SEM, **Funds Management** in Toast Payments (BLR) | Domain = merchant funds out, not guest card swipe UX |
| Hiring Staff/Principal for **Pricing & Funds Mgmt** | Wants Java/Kotlin microservices + fault-tolerant settlement pipelines |
| Ex-Amazon SDM — e-invoicing / vendor payments, team 2→12 | Amazon-flavored: ownership, metrics, ops excellence, modernization |
| Scale language: "$100B+", "ultra-low latency settlement" | Speak money correctness + throughput, not "we used Kafka" |

**He cares about:** payout/settlement correctness, idempotency, recon, fee math, failure modes, ops metrics, mentorship/bar-raising.  
**He will not be impressed by:** POS dinner-rush theater, LeetCode flex, "exactly-once" handwaves, restaurant trivia without funds depth.

---

## Likely 45-min shape

| Min | Block |
|---|---|
| 0–5 | Intros + why Toast / why this team |
| 5–25 | Deep dive your payments/settlement work (his main signal) |
| 25–35 | Light systems / scenario (payout, recon, timeout) OR behavioral |
| 35–40 | Level / scope / working style |
| 40–45 | Your questions |

HM/Tech Screen ≠ pure coding. Lead with **domain depth + judgment**. If coding appears, it's practical (state machine, dedupe, matching) — not hard algo.

---

## Map your stories → his world

| Toast Funds Mgmt concept | Your Skydo analogue | Open with |
|---|---|---|
| Merchant **payout / deposit** (T+1/T+2 batch) | Settlement sweeps to AD bank / HDFC | "Bank APIs timeout without telling you if money moved" |
| **Fees vs withholdings** (processing fees vs Capital/EasyPay/TDS) | Processor fees + partner deductions in recon | "Net ≠ gross — rule engine for fee math" |
| **Reconciliation** report (sales ↔ deposit ↔ bank) | Internal ledger ↔ external settlement file | "Partial match queues, not magic exact match" |
| **Instant deposit** | Faster settlement / priority rails | Idempotency + risk limits before accelerating money |
| Card **auth → capture → settle → payout** | Funding → compliance → settle → ledger | Explicit state machine; never skip states |
| Multi-location restaurants | Multi-entity / multi-corridor | Partition by merchant/location for order + isolation |

---

## Deep-dive A — Payments write path (5 min version)

1. **Idempotency gate** — client key; Redis fast path + Postgres unique constraint (durability)
2. **State machine** — INITIATED→AUTHORIZED→CAPTURED→SETTLED→RECONCILED (+ DISPUTED)
3. **Lock** — Redis dlock / `SELECT FOR UPDATE` on money-critical account; short CS; fail-closed
4. **Durable intent before side effect** — write intent, then call partner; never partner-first
5. **Async fan-out** — Kafka/Pulsar after commit; partition key = `payment_id`
6. **Recon closes the gap** — partner success with lost response → polling + settlement file match

**Poke answers:**
- Lock TTL expires mid-flight → Postgres uniqueness is source of truth; Redis is speed only
- Partner timeout → poll with deterministic idempotency key (never random UUID); DLQ after N; recon catches silent success
- Never claim magic exactly-once — at-least-once + idempotent consumers

## Deep-dive B — Settlement / Funds out (his team's core)

> Guest pays Friday night → Toast batches before 9:30pm ET → merchant bank sees deposit next business day (or T+2). Payout = card payments − refunds − fees − withholdings (Capital, EasyPay, TDS, instant-deposit fees).

**Design points to hit:**
- **Batch cutoff semantics** — late batch shifts deposit day; weekends/holidays push
- **Netting math** — fees ≠ withholdings (separate ledgers/lines; restaurants debug both)
- **Idempotent ACH/payout initiation** — payout_id deterministic from (merchant, settlement_date, batch_id)
- **Recon loop** — internal expected payout ↔ bank credit ↔ per-txn contribution list
- **Exception aging** — unmatched after SLA → escalate; never silently absorb money gaps

## Deep-dive C — Leadership signal (attach to every tech point)

| Tech | Leadership attach |
|---|---|
| Idempotency standard | Non-negotiable in CR; near-miss → eng playbook |
| Partition key = payment_id | Caught staging bug; became team rule |
| Business SLO alerts ("stuck PENDING >5m") | Incidents −30%; reliability = product requirement |
| Platform vs product tracks | Higher design bar on money path |

---

## 3 STAR stories (keep tight)

1. **Payments from zero** — correctness under partner failure; 10K+/day; idempotency + recon  
2. **Observability as product** — business SLOs; incidents −30%  
3. **GS scale / judgment** — live re-shard of multi-TB risk cluster; abstraction before migration  

Behavioral map to Toast values: **One Team · Lead with Humility · Always Hungry · Ownership · Customer Success** (restaurant = merchant whose cash flow depends on correct payouts).

---

## Why Toast / Why Funds Mgmt (30 sec)

> Toast's moat is being the restaurant OS *and* the money rail. Funds Management is where trust lives — if Friday's sales don't land correctly Monday, the restaurant can't pay staff or suppliers. I've spent years making money move correctly under partial failure. I want that problem at Toast's volume, with a team that already treats settlement as a first-class platform.

---

## Ask Abhineet (pick 3)

1. What's the hardest correctness or scale problem Funds Management is solving in the next 6–12 months — payout latency, multi-product withholdings, recon UX, or something else?
2. How does Bengaluru Payments interact with US payments / processors — ownership boundaries?
3. For Staff on your team: is success more platform leverage (shared settlement substrate) or product delivery (Capital/instant deposit features)?
4. What separates a strong Senior from someone you'd hire at Staff on this team?
5. How do you think about ops excellence — payout incident MTTR, recon exception SLAs?

---

## Avoid

- Long POS/KDS dinner-rush stories (unless he steers there)
- "Exactly-once delivery" without at-least-once + idempotent consumer
- Claiming Toast product trivia you don't know (Capital underwriting details, Exact deposit hours)
- Feature lists over failure modes
- Over-indexing on AI/agents — money path stays deterministic

## Metrics card

10K+ txn/d · incidents −30% · GS failover −40% / batch −25% / 3× · Moneyview 5M+/mo · led 12 @ Skydo / 9 @ GS · ISO/SOC2 as CIO
