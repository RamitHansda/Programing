# Skydo Settlement Reconciliation — Principal Design

**Audience:** Principal / Staff architecture reviews and interview deep-dives.  
**Scope:** How reconciliation sits inside Skydo's cross-border payments ecosystem — not a generic Stripe-style recon HLD.  
**Bar:** Name the **invariant**, place recon as a **first-class subsystem** (not a nightly script), and show how it reuses Skydo platform primitives (Kafka path, Ledger, Katar, dlock, ECS workers).

---

## 0. Principal Opening

> “At Skydo, reconciliation is not ‘match CSV to DB at EOD.’ Cross-border money moves through **independent failure domains** — our intent, the debit leg, FX at the AD bank, remittance via SWIFT/HDFC — none of which share a distributed transaction. The invariant is: **every payment intent eventually reaches a terminal, explainable state across all external sources of truth, or sits in an owned exception queue with aging SLAs.** We enforce that with a **canonical payment-state vocabulary**, a **three-layer recon ladder**, an **immutable ledger as internal truth**, and async workers (Katar/SQS on ECS) that close the loop without sitting on the customer-critical path.”

That framing separates Principal from Staff: you own the **correctness contract across partner boundaries**, not a matching algorithm.

---

## 1. Where Recon Lives in the Skydo Ecosystem

### 1.1 Happy-path money movement (modular monolith + Kafka)

```
API Gateway / ALB
        │
        ▼
┌───────────────────┐
│  Funding Service  │  vendor adapters → canonical event
│  (+ dlock / Redis │  idempotency key bound to payment_id
│   + Postgres)     │
└─────────┬─────────┘
          │ funding topic (partition key = payment_id)
          ▼
 Invoice │ Compliance (TM / sanctions / dashboard) │ Ledger (fan-out)
          │
          ▼ (approved)
     Swift Service (batch by currency + VA provider)
          │
          ▼
     FX Service (AD bank) → Pricing Engine → Settlement (HDFC)
          │
          ▼
     Ledger + Notification  (+ Refund path on reject)
```

Recon is **orthogonal** to this path. It observes stages; it does not own settlement execution.

### 1.2 Ecosystem map — who owns what

| Layer | Skydo component | Role in recon |
|-------|-----------------|---------------|
| **Ingress / auth** | Suraksha | Authn for ops recon dashboard & partner file APIs; roles gate exception resolve |
| **Hot money path** | Funding → Compliance → SWIFT → FX → Pricing → Settlement | Emits state transitions; never blocked by recon |
| **Correctness primitives** | dlock + Postgres idempotency tables | Prevents duplicate money moves that would poison recon |
| **Async jobs** | Katar (SQS + `run_log` + DB `sub_config`) | Schedules stage-recon jobs, file ingest, aging/escalation |
| **Compute** | ECS Fargate (Spot for recon workers) | Scale on SQS depth + oldest message age; EOD SLA |
| **Internal truth** | Ledger (double-entry, immutable journals) | Source of record for “what we believe happened” |
| **External truth** | Bank MIS, AD FX confirms, HDFC settlement ACK / files | Independent sources matched per ladder stage |
| **Observability** | Kafka events + structured `correlation_id` / `external_ref` | Makes breaks queryable; feeds dashboards |
| **Orchestration (long-running)** | Temporal (where adopted) | Multi-day partner payout / retry workflows that recon must still validate |

### 1.3 Architectural placement

```
                    ┌──────────────────────────────────────────┐
                    │         PAYMENT CRITICAL PATH            │
                    │  Funding → … → Settlement → Ledger       │
                    │  (Kafka fan-out, dlock, idempotency)     │
                    └───────────────────┬──────────────────────┘
                                        │ state events / journal
                                        ▼
┌───────────────────────────────────────────────────────────────────────────┐
│                     RECONCILIATION SUBSYSTEM (async)                       │
│                                                                           │
│  Ingest          Normalize         Ladder match         Exception ops     │
│  ────────        ─────────         ────────────         ─────────────     │
│  Kafka stages    Canonical txn     L1 Intent↔Debit      Suspense queue    │
│  Bank/FX/HDFC    model + IDs       L2 Debit↔FX          Aging / escalate  │
│  files (SFTP/S3)                   L3 FX↔Remittance     Manual resolve UI │
│  Katar jobs                        Integrity ΣD=ΣC      Audit trail       │
└───────────────────────────────────────────────────────────────────────────┘
                                        │
                                        ▼
                              Finance / Ops dashboard
                              (self-serve, not Slack tickets)
```

**Principal rule:** Recon never shares a transaction boundary with Settlement. Settlement may succeed while recon lags; recon may flag a break without rolling back money mid-flight. Healing is **compensating entries + partner queries**, not distributed undo.

---

## 2. The Invariant

### Precise contract

| Phrase | Meaning at Skydo |
|--------|------------------|
| **Every intent** | Payment with durable `payment_id` / idempotency key recorded before side effects |
| **Eventually** | Within partner SLA windows (T+0/T+1 cut-offs, FX confirm latency, HDFC ACK) — not infinite |
| **Terminal & explainable** | One of: `SETTLED`, `FAILED`, `REVERSED` — with join keys to bank/FX refs |
| **Or owned exception** | Unmatched item in suspense with owner, age, and escalation path |
| **No silent drift** | Unreconciled TPV and stage match-rate are P0/P1 signals |

### Explicit non-goals

- Real-time matching on every Kafka hop (settlement is batch/partner-paced)
- Treating bank MIS as mutable “fix” over our ledger without audit
- One monolithic job that “reconciles the day”
- Perfect exactly-once across SWIFT/HDFC/AD (impossible) — we use **at-least-once + idempotent apply + ladder localization**

### Success metrics (historical Skydo bar)

| Metric | Target / outcome |
|--------|------------------|
| Unreconciled amount / daily TPV | ~0.6% → **&lt; 0.02%** |
| Stage match rate | Alert if &lt; 98–99.5% after window |
| Exception aging | Escalation if &gt; SLA (e.g. 5 business days) |
| Partner onboarding | Ladder reuse → **~4 weeks** vs prior **~10+** |

---

## 3. Design Principles (non-negotiables)

1. **Ledger is internal SoR; partners are external SoRs.** Recon joins them; it does not invent a third truth.
2. **Vocabulary before algorithms.** Shared states beat smarter fuzzy matching.
3. **Ladder, not blob.** Decompose by **failure boundary** (intent/debit/FX/remit), same instinct as Temporal/Katar stage isolation.
4. **Idempotency on money path makes recon tractable.** dlock + Postgres keys + deterministic HDFC keys reduce poison duplicates.
5. **Partition key discipline (`payment_id`)** keeps Kafka order per payment so stage events are causally readable.
6. **Exceptions are product.** Suspense + dashboard + runbooks; Slack is not a queue.
7. **Fail closed for money movement; fail open for recon lag.** Prefer delayed “all clear” over wrong settlement.
8. **Platform reuse.** Katar for job visibility, ECS Spot for burst workers, Suraksha for ops ACL — no snowflake orchestrator for recon alone.

---

## 4. Canonical State Model

Cross-team vocabulary (Finance, Ops, Eng, Banking Partner):

```
INITIATED → DEBITED → CONVERTED → REMITTED → SETTLED
                ↘         ↘           ↘
                 FAILED / REVERSED (terminal, with compensating journals)
```

| State | Entry condition (examples) | External anchor |
|-------|----------------------------|-----------------|
| `INITIATED` | Funding accepted; durable intent + idempotency key | Internal `payment_id` |
| `DEBITED` | Source funds confirmed / VA credit observed | Bank debit / VA ref |
| `CONVERTED` | FX booked at AD | FX deal / rate ticket id |
| `REMITTED` | SWIFT / remittance instruction accepted | UETR / batch id |
| `SETTLED` | HDFC (or beneficiary bank) final ACK / credit | Settlement file / ACK |
| `FAILED` | Terminal reject with no money in limbo (or refunded) | Partner reject code |
| `REVERSED` | Compensating ledger entries posted | Reversal journal ids |

Every transition emits structured fields: `payment_id`, `correlation_id`, `external_ref`, `source_system`, `amount` (minor units), `currency`, `occurred_at`.

**Why this matters:** Without it, “₹X missing for customer Y” is four different bugs.

---

## 5. The Reconciliation Ladder

### Why three jobs, not one

A single “day recon” collapses three independent partner clocks into one red number. Debugging then requires Slack archaeology. The ladder localizes:

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│  L1             │     │  L2             │     │  L3             │
│  Intent ↔ Debit │ ──▶ │  Debit ↔ FX     │ ──▶ │  FX ↔ Remit/    │
│                 │     │                 │     │  Settlement     │
└─────────────────┘     └─────────────────┘     └─────────────────┘
   Bank / VA MIS           AD bank FX file         HDFC / SWIFT ACK
```

| Layer | Left | Right | Typical break |
|-------|------|-------|---------------|
| **L1** | Intent / ledger debit intent | Bank debit / VA credit | Webhook late, wrong VA mapping, duplicate debit |
| **L2** | Debited amount/currency | FX convert confirm | Rate ticket missing, partial convert, cut-off |
| **L3** | Converted INR (or settle currency) | Remittance / HDFC settle | Batch partial fail, ACK timeout, fee delta |

Each layer produces: `MATCHED | PARTIAL | UNMATCHED_LEFT | UNMATCHED_RIGHT | DUPLICATE` into `reconciliation_results` with `run_id` + `ladder_stage`.

### Matching strategy (per stage)

1. **Exact** — stable join key (`external_ref` / partner txn id) + currency + gross amount  
2. **Rule / tolerance** — known fee schedules, FX rounding to minor unit, date window ±N for cut-offs  
3. **Manual** — ops resolve in dashboard; decision append-only audited  

Amounts always **integer minor units**; never float.

### Integrity check (orthogonal to ladder)

Hourly (or continuous): **Σ Debit = Σ Credit** on ledger journals. Ladder finds partner drift; integrity finds **internal** corruption. Both are required.

---

## 6. Data & Control Plane

### 6.1 Sources

| Source | Ingest | Idempotency |
|--------|--------|-------------|
| Kafka stage events | Consumer → `internal_stage_facts` | `payment_id` + `state` + event id |
| Bank / FX / HDFC files | SFTP/S3 → Katar job → normalize | File SHA-256; row fingerprint |
| Partner status APIs | Poll on timeout / exception age | Deterministic query by original idempotency key |

Raw files land immutable in object storage; normalized rows are derived.

### 6.2 Canonical join model (sketch)

```text
payment_facts          -- internal progression per payment_id
external_legs          -- source_system, external_ref, amount, settlement_date, file_id
recon_runs             -- run_id, ladder_stage, window_start/end, status
recon_results          -- match_status, match_type, amount_delta, resolved_by
recon_exceptions       -- aging, owner, SLA_due_at
recon_audit_log        -- append-only before/after JSONB
```

Postgres remains preferred for ledger + recon OLTP (JOINs, ACID, exception workflow). Warehouse optional for historical analytics; **not** the exception SoR.

### 6.3 Katar job types (illustrative)

| Job type | Trigger | Notes |
|----------|---------|-------|
| `RECON_L1_WINDOW` | EventBridge / cron + SQS | Incremental window for intent↔debit |
| `RECON_L2_WINDOW` | Same | Debit↔FX |
| `RECON_L3_WINDOW` | Same | FX↔settle |
| `RECON_FILE_INGEST` | S3 event / poll | Checksum gate |
| `RECON_EXCEPTION_AGE` | Frequent | Escalate aged suspense |
| `RECON_INTEGRITY_Σ` | Hourly | Ledger sum check → P0 |

Katar `run_log` gives **task visibility** (CREATED → PENDING → IN_PROGRESS → SUCCESS / ERROR_*), which is how on-call sees “did tonight’s L3 finish?” without SSH.

### 6.4 Concurrency

- Stage windows partitioned by `settlement_date` / partner  
- Critical resolve / adjust paths use **dlock** on `payment_id` so two ops agents cannot post conflicting compensating entries  
- Workers are **stateless**; Spot interruption → SQS visibility timeout → another task continues (idempotent handlers required)

---

## 7. Exception Management & Healing

| Exception | Likely cause | System action |
|-----------|--------------|---------------|
| Unmatched intent | Partner debit delayed / lost webhook | Wait window → poll bank → escalate |
| Unmatched external | Missing internal capture / wrong ID map | Investigate; create adjustment or dispute |
| Amount delta | Fees / FX rounding | Auto-resolve inside rule book; else manual |
| Duplicate external | Double file / double webhook | Fingerprint dedupe; alert partner |
| ACK timeout (HDFC) | 60s bank silence | Status poll with **same** idempotency key; never new random key |
| Partial SWIFT batch | 3/100 fail | Per-txn retry only; never full-batch replay |

**Healing tools:** status poll, compensating journal, refund workflow, partner ticket — each logged in `recon_audit_log`.

**Ops product:** dashboard replaces ad-hoc Finance tickets; weekly ladder triage + public known-gaps register.

---

## 8. Relationship to Other Skydo Hard Problems

| Concern | How recon depends on it |
|---------|-------------------------|
| **Idempotency end-to-end** | Without durable keys, L1/L3 cannot distinguish retry from new money |
| **Kafka `payment_id` partition key** | Ordered stage history per payment makes ladder joins causal |
| **Compliance gate** | Recon must understand money in limbo under review vs failed vs settled |
| **HDFC timeout** | Classic “success unknown” → recon + poll is the safety net |
| **DLQ runbooks** | DLQ is transport failure; recon is **business-state** failure — both needed |
| **Temporal workflows** | Orchestration retries ≠ recon confirmation; recon still closes external truth |
| **ECS Spot workers** | Cost-efficient for EOD windows; correctness via idempotent Katar handlers |
| **Suraksha roles** | Only authorized ops can mark exceptions resolved |

---

## 9. SLOs, Alerts, Scale

| Signal | Why |
|--------|-----|
| `recon.unmatched_tpv_ratio` | Business risk — primary KPI |
| `recon.stage_match_rate{stage}` | Localizes which partner clock broke |
| `recon.exception_age_p95` | Ops SLA |
| `recon.file_lag_seconds` | Partner delivery / ingest health |
| `katar.run_log` ERROR_* rate | Worker / job health |
| SQS oldest message age | ECS scale-out before EOD breach |
| Ledger Σ imbalance | Internal integrity P0 |

**Scale posture at Skydo (~10K+/day):** SQL batch matching + Katar workers is enough. Spark/warehouse matching is a later lever if volume jumps orders of magnitude — do not over-build.

**Scale-out policy (recon workers):** primary = SQS visible depth; secondary = oldest message age (e.g. &gt;15 min → aggressive scale). Scale-in aggressive to save cost (Spot-friendly).

---

## 10. Threats & Compliance

- Settlement files encrypted at rest (KMS) and in transit  
- PII / account numbers masked in logs  
- Append-only audit for every manual resolve (regulator / SOC 2 / ISO 27001 narrative)  
- RBAC via Suraksha: resolve ≠ read  
- Retention aligned to financial record policy (multi-year)

---

## 11. What We Would Do Differently (honest Principal note)

1. **Event-sourced `payment_events` table from day one** — reconstructing state only from Kafka topic traversal made early recon painful; a first-class transition log would have made the ladder trivial.  
2. **Freshness SLO on recon runs**, not only correctness — lag incidents were caught reactively.  
3. Extract **Settlement + FX** as independent services when regulatory/scaling boundaries dominate; recon ladder already treats them as separate SoRs, so service extraction maps cleanly.

---

## 12. One-Slide Summary

```
Skydo money path:  Funding → Compliance → SWIFT → FX → Settlement → Ledger
                         │         (Kafka, dlock, idempotency)
                         ▼
Skydo recon:       Canonical 7 states
                   + Ladder L1/L2/L3 (intent↔debit↔FX↔remit)
                   + Katar/SQS/ECS workers
                   + Exception product + audit
                   + Ledger integrity ΣD=ΣC

Invariant: every payment reaches explainable terminal state
           or owned suspense — no silent partner drift.
```

**Spoken close:**

> “Recon in Skydo’s ecosystem is the **final safety net across partner failure domains**. We made it a ladder with shared vocabulary, wired it through the same async platform we trust for money jobs, and measured unreconciled TPV like a product KPI — because at 10K international transactions a day, one unexplained rupee is a trust and compliance event, not a dashboard glitch.”

---

## Related docs in this repo

- `docs/em-interview/payment-platform-em-explainer.md` — payment path walkthrough  
- `docs/interviews/SKYDO-PAYMENT-PLATFORM-INTERVIEW-GUIDE.md` — interview framing  
- `docs/em-interview/EM-AMBIGUITY-QUALITY-INTERVIEW-PREP.md` — ladder narrative & metrics  
- `docs/skydo-tech/Katar_Architure.md` — async job visibility  
- `docs/skydo-tech/dlock_Architecture.md` — distributed locks on critical sections  
- `docs/skydo-tech/ECS-vs-EKS-Skydo.md` — recon worker scaling  
- `docs/data_eng/PAYMENT_RECONCILIATION_HLD_STAFF_ENG.md` — generic matching HLD (complementary)  
- `docs/LEDGER_SYSTEM_HLD_STAFF_ENG.md` — internal SoR principles  
