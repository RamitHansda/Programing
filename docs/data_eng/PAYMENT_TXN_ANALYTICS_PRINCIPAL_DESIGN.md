# Payment Transactions → Analytics: Principal Engineer Design

**Prompt:** Design a payment transaction system where every transaction must eventually appear correctly in the analytics system.

**Audience:** Principal / Staff system-design interviews and architecture reviews.  
**Bar:** Not “draw Kafka and a warehouse.” The bar is naming the **invariant**, rejecting dual-write, and proving **eventual correctness** under retries, outages, and late corrections.

---

## 0. Principal Opening (say this first)

> “The hard requirement is not low latency dashboards. It is an **invariant**: every committed payment transaction eventually appears in analytics **exactly once as a business effect**, and late corrections (refunds, chargebacks, status flips) land as **ordered, idempotent updates** — not silent drops or double counts. Payment OLTP and analytics are different consistency domains; I will not pretend a dual write gives us correctness. I will make the payment DB the source of truth, publish change reliably via an outbox/CDC, consume idempotently into analytics, and close the loop with reconciliation that can detect and heal drift.”

That framing separates you from Staff: you own the **correctness contract**, not a box diagram.

---

## 1. Reframe the Problem

### What “eventually appear correctly” actually means

| Phrase | Precise meaning |
|--------|-----------------|
| **Eventually** | Bounded lag is OK (seconds–minutes for near-real-time; hours for warehouse). Infinite lag or silent loss is not OK. |
| **Appear** | Present in the analytics store used for reporting / BI / metrics (fact tables, aggregates, or both). |
| **Correctly** | Right amount, currency, status, timestamps, and lifecycle; no duplicates; corrections applied; totals reconcile to payments. |

### Non-goals (explicit)

- Sub-second analytics freshness (unless product requires it — usually does not).
- Querying the production payments DB for dashboards (kills OLTP; couples teams).
- Exactly-once across the whole internet (impossible). We deliver **at-least-once transport + exactly-once business effect** via idempotency.
- Perfect real-time fraud scoring in the analytics path (separate hot path if needed).

### Assumed scale (state and adjust in interview)

| Dimension | Working assumption |
|-----------|--------------------|
| Peak write TPS | 5K–50K payment state changes/sec (authorize, capture, refund, fail…) |
| Event volume | Multiples of TPS (every transition emits an event) |
| Analytics freshness SLO | p99 < 60s for operational metrics; < 15 min for warehouse facts |
| Retention | Hot analytics 90 days; cold lake 7 years (finance/audit) |
| Correctness SLO | 0 silent loss; drift detected within 1 reconciliation window |

---

## 2. Design Principles (non-negotiables)

Every decision below maps to one of these:

1. **Payments DB is the system of record.** Analytics is a derived projection. Never reverse that.
2. **No dual-write of truth.** A single atomic commit records both business state and “intent to publish.”
3. **At-least-once delivery + idempotent apply = correct effect.** Transport may duplicate; sinks must not double-count.
4. **Corrections are first-class.** Refunds, voids, chargebacks are new events (or versioned upserts), never in-place silent mutation without lineage.
5. **Reconciliation is part of the product**, not an afterthought script. If you cannot measure drift, you do not have the invariant.
6. **Fail closed for money; fail open for dashboards.** Prefer delayed charts over wrong GMV.

---

## 3. The Failure Mode Everyone Skips: Dual Write

### Naive design (reject this)

```
API → write payments DB
    → publish Kafka event
    → analytics consumer writes warehouse
```

### Why it breaks the invariant

| Failure | Outcome |
|---------|---------|
| DB commit succeeds, Kafka publish fails | Transaction exists; analytics never sees it |
| Kafka succeeds, DB rolls back | Analytics shows a phantom payment |
| Consumer processes, offset commit fails | Replay → double count in SUM(GMV) |
| Out-of-order status events | Analytics shows CAPTURED then AUTHORIZED |
| Late refund never emitted | Revenue permanently overstated |

**Principal line:**  
> “Dual write between OLTP and the bus is the #1 reason payment analytics lie. I treat that as a correctness bug, not an ops inconvenience.”

---

## 4. Target Architecture

```
┌──────────────────────────┐
│  Clients / Checkout /    │
│  PSP webhooks            │
└────────────┬─────────────┘
             │ idempotent API
             ▼
┌──────────────────────────┐
│  Payment Service (OLTP)  │
│  - state machine         │
│  - ledger / txn tables   │
│  - outbox row SAME TXN   │
└────────────┬─────────────┘
             │ commit
             ▼
┌──────────────────────────┐     ┌─────────────────────┐
│  Payments DB (Postgres)  │────▶│ CDC / Outbox Relay  │
│  transactions + outbox   │ WAL │ (Debezium or poller)│
└──────────────────────────┘     └──────────┬──────────┘
                                            │ at-least-once
                                            ▼
                                 ┌─────────────────────┐
                                 │ Event Bus (Kafka)   │
                                 │ payment.txn.v1      │
                                 │ partition=merchant  │
                                 │ or payment_id       │
                                 └──────────┬──────────┘
                        ┌───────────────────┼───────────────────┐
                        ▼                   ▼                   ▼
              ┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐
              │ Stream proc     │ │ Ops metrics     │ │ Lake / WH loader│
              │ (Flink/KS)      │ │ (real-time)     │ │ (Spark/Flink)   │
              │ enrich+dedupe   │ │ Redis/Druid/    │ │ Bronze→Silver→  │
              └────────┬────────┘ │ ClickHouse      │ │ Gold            │
                       │          └────────┬────────┘ └────────┬────────┘
                       └───────────────────┼───────────────────┘
                                           ▼
                                 ┌─────────────────────┐
                                 │ Analytics serving   │
                                 │ BI, APIs, alerts    │
                                 └─────────────────────┘
                                           ▲
┌──────────────────────────┐               │
│ Reconciliation Worker    │───────────────┘
│ payments DB ⋈ analytics  │  detect/heal drift
└──────────────────────────┘
```

### Why these seams exist

| Seam | Reason |
|------|--------|
| OLTP vs bus | Protect payment latency and isolation from analytics load |
| Outbox/CDC | Atomically couple “committed payment” ↔ “publishable event” |
| Kafka | Durable buffer, fan-out, replay, backpressure |
| Multiple sinks | Same events feed realtime metrics and warehouse without coupling them |
| Reconciliation | Independent proof of the invariant |

---

## 5. Payment Domain Model (source of truth)

### 5.1 Transaction state machine (illustrative)

```
CREATED → AUTHORIZED → CAPTURED → SETTLED
              │            │
              ├→ VOIDED    ├→ REFUNDED (partial/full)
              └→ FAILED    └→ CHARGEBACK
```

Rules that matter for analytics:

- Only **terminal and money-moving transitions** update revenue facts (or facts are versioned by status).
- Every transition has: `payment_id`, `event_id`, `sequence` (or `version`), `amount`, `currency`, `occurred_at`, `merchant_id`, `idempotency_key`.
- Amounts are **integer minor units** — never floats.

### 5.2 Core tables (sketch)

```sql
-- System of record
CREATE TABLE payments (
  payment_id        UUID PRIMARY KEY,
  merchant_id       UUID NOT NULL,
  amount_minor      BIGINT NOT NULL,
  currency          CHAR(3) NOT NULL,
  status            TEXT NOT NULL,
  version           BIGINT NOT NULL,          -- monotonic per payment
  idempotency_key   TEXT NOT NULL,
  created_at        TIMESTAMPTZ NOT NULL,
  updated_at        TIMESTAMPTZ NOT NULL,
  UNIQUE (merchant_id, idempotency_key)
);

CREATE TABLE payment_events (               -- optional append-only journal
  event_id          UUID PRIMARY KEY,
  payment_id        UUID NOT NULL REFERENCES payments(payment_id),
  version           BIGINT NOT NULL,
  event_type        TEXT NOT NULL,
  payload           JSONB NOT NULL,
  occurred_at       TIMESTAMPTZ NOT NULL,
  UNIQUE (payment_id, version)
);

-- Publish intent (same DB transaction as payment write)
CREATE TABLE outbox (
  outbox_id         BIGSERIAL PRIMARY KEY,
  aggregate_id      UUID NOT NULL,           -- payment_id
  event_id          UUID NOT NULL UNIQUE,    -- natural idempotency for bus
  event_type        TEXT NOT NULL,
  payload           JSONB NOT NULL,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at      TIMESTAMPTZ              -- null until relayed
);

CREATE INDEX outbox_unpublished_idx ON outbox (created_at)
  WHERE published_at IS NULL;
```

**Write path (single transaction):**

1. Validate idempotency key / apply state transition.
2. Upsert `payments`, insert `payment_events`.
3. Insert `outbox` row with canonical event payload.
4. Commit.

If commit fails, nothing is published. If commit succeeds, relay **will** eventually publish.

---

## 6. Reliable Publication: Outbox vs CDC

### Option A — Transactional Outbox + Relay (recommended default)

- App writes outbox in the same TX.
- Relay polls unpublished rows **or** Debezium tails outbox table.
- Publish to Kafka with key = `payment_id` (ordering per payment).
- Mark published / delete after ack (with care: mark only after broker ack).

**Pros:** Explicit contract; easy to reason about; payload shaped for consumers.  
**Cons:** App must remember to write outbox; needs discipline + library/platform support.

### Option B — CDC on `payments` / `payment_events` (Debezium)

- No app outbox; WAL → Kafka.
- Consumers interpret row images / changelog.

**Pros:** Hard to “forget” to emit.  
**Cons:** Leaky schema coupling; harder to emit rich domain events; tombstones/schema evolution pain.

### Principal decision

> “For a payments platform used by many teams, I standardize on **transactional outbox as a platform primitive** (shared library + relay). CDC is acceptable for lake ingestion of raw tables, but **analytics correctness should ride domain events**, not raw row replicas — otherwise every schema tweak becomes a breaking analytics change.”

Hybrid that often wins in practice:

- Outbox domain events → Kafka `payment.txn.v1` (product analytics, metrics).
- CDC/snapshot of payments table → Bronze lake (audit, reprocessing, recon).

---

## 7. Event Contract (the real API between worlds)

```json
{
  "event_id": "01J…",
  "event_type": "payment.status_changed",
  "event_version": 1,
  "payment_id": "pay_123",
  "merchant_id": "m_9",
  "version": 4,
  "status": "CAPTURED",
  "amount_minor": 4999,
  "currency": "USD",
  "previous_status": "AUTHORIZED",
  "occurred_at": "2026-08-19T04:01:02.123Z",
  "recorded_at": "2026-08-19T04:01:02.200Z",
  "idempotency_key": "checkout:ord_55:capture",
  "trace_id": "…"
}
```

### Contract rules

| Rule | Why |
|------|-----|
| Stable `event_id` | Dedup across at-least-once delivery |
| Monotonic `version` per `payment_id` | Drop/ignore stale events; detect gaps |
| Partition key = `payment_id` | Preserve per-payment order in Kafka |
| Schema Registry (Avro/Protobuf) | Prevent poison evolution |
| Backward-compatible evolution | Analytics must not break on deploy |

**Ordering note:** Global order across all payments is unnecessary. **Per-payment total order** is the requirement.

---

## 8. Analytics Ingestion: Making “Correct” True

### 8.1 Semantics

```
Kafka: at-least-once
Consumer: idempotent upsert / merge on (payment_id) or (event_id)
Effect: exactly-once business state in analytics facts
```

### 8.2 Silver fact table (warehouse)

```sql
CREATE TABLE fact_payments (
  payment_id        STRING NOT NULL,
  merchant_id       STRING NOT NULL,
  status            STRING NOT NULL,
  amount_minor      BIGINT NOT NULL,
  currency          STRING NOT NULL,
  version           BIGINT NOT NULL,
  last_event_id     STRING NOT NULL,
  captured_at       TIMESTAMP,
  updated_at        TIMESTAMP NOT NULL,
  _ingested_at      TIMESTAMP NOT NULL
)
USING ICEBERG;  -- or Delta / BigQuery equivalent

-- Apply rule (MERGE):
-- IF incoming.version > existing.version THEN update
-- IF incoming.version <= existing.version THEN no-op (idempotent / stale)
-- Track processed event_ids for audit if needed
```

### 8.3 Metrics / realtime path

- Stream processor maintains keyed state by `payment_id`.
- Updates counters with version checks.
- For additive metrics (GMV), prefer:

  - **Status-aware measures:** only count when status enters `CAPTURED`/`SETTLED`.
  - Or store **event contributions** with reversible signs (capture +X, refund −X) and sum.

**Anti-pattern:** `COUNT(*)` of raw Kafka messages as “transactions.”

### 8.4 Handling late arrivals & corrections

| Case | Handling |
|------|----------|
| Duplicate event | Ignore via `event_id` / version |
| Out-of-order older version | Ignore |
| Gap in version (3 then 5) | Buffer briefly / flag; recon will catch if permanent |
| Refund after capture | New event, new version; update status + revenue measures |
| Reprocessing from epoch | Safe because MERGE is idempotent |

---

## 9. Reconciliation: Proof of the Invariant

Without recon, “eventually correct” is faith.

### 9.1 What to compare

Daily (or hourly) job:

```
payments_db (status in money-relevant states, by updated_at window)
    ⋈
fact_payments (same keys)
```

Checks:

1. **Missing in analytics** — in DB, not in facts → republish from outbox/events or backfill.
2. **Phantom in analytics** — in facts, not in DB → quarantine / tombstone (should be rare if outbox-only).
3. **Field mismatch** — amount/status/version differ → overwrite from SoR or emit repair event.
4. **Aggregate drift** — `SUM(amount)` by merchant/day must match within tolerance 0.

### 9.2 Healing

- Prefer **automated replay** of missing `event_id`s from `payment_events` / outbox archive.
- Alert on non-zero missing count beyond SLO.
- Page humans only when auto-heal fails or mismatch class is novel.

### 9.3 Principal metric

| Metric | Target |
|--------|--------|
| `analytics_missing_payments` | 0 after recon window |
| `analytics_amount_mismatch` | 0 |
| `payment_to_analytics_lag_p99` | < freshness SLO |
| `outbox_oldest_unpublished_age` | < 30s (or page) |

---

## 10. End-to-End Guarantees (honest table)

| Layer | Guarantee |
|-------|-----------|
| Payment API | Strong consistency for a single payment write; idempotent retries |
| DB ↔ Outbox | Atomic (same TX) |
| Outbox → Kafka | At-least-once |
| Kafka retention | Long enough for replay + late consumers (e.g. 7–30 days) + lake archive |
| Analytics apply | Idempotent MERGE → exactly-once effect |
| Cross-system | **Eventual consistency** with **detectable, healable** drift |

> There is no free “exactly-once everywhere.” There is **exactly-once money effect in OLTP** and **eventually exact projection in analytics**.

---

## 11. Deep Dive Trade-offs (interview gold)

### Q: Why not 2PC between DB and Kafka?

**Reject.** 2PC couples availability; Kafka coordinator + DB = operational footgun. Outbox is the industry pattern for a reason.

### Q: Why not query payments DB for analytics?

**Reject for primary analytics.** Contends with OLTP, encourages accidental heavy queries, blocks independent scaling and ownership. CDC/outbox projection is the boundary between payment and data teams.

### Q: Sync call from payment service to analytics API?

**Reject.** Analytics downtime would block checkout or force best-effort dual write again.

### Q: Event sourcing the payment service itself?

**Optional.** Strong for audit, heavier for team maturity. You can get the analytics invariant with **state + outbox** without full ES. If already event-sourced, projections become natural.

### Q: Exactly-once Kafka transactions?

Useful inside stream processors writing to Kafka sinks; **not sufficient** alone for DB↔bus atomicity. Still need outbox/CDC on the produce side and idempotent sinks on the consume side.

---

## 12. Failure Modes & Operability

| Failure | Detection | Mitigation |
|---------|-----------|------------|
| Relay down | `outbox_oldest_unpublished_age` | Scale relay; Kafka independence means DB keeps accepting payments |
| Kafka unavailable | Same + producer errors | Outbox buffers; payments stay healthy |
| Poison event | Consumer DLQ depth | Quarantine; fix schema; replay |
| Consumer bug double-counts | Recon aggregate drift | Fix MERGE; rebuild Silver from Bronze/events |
| Partial deploy schema break | Canary consumer lag / error rate | Schema compat checks in CI |
| Clock skew on `occurred_at` | Sanity checks | Prefer server `recorded_at` for ordering ties; version is source of order |
| Hot partition (mega-merchant) | Partition lag skew | Partition by `payment_id` not `merchant_id` if one merchant dominates |

**On-call story:** Checkout must not depend on analytics. Degrade dashboards; never block capture.

---

## 13. Rollout Plan (v0 → target)

1. **v0:** Outbox library in payment service + Kafka topic + single warehouse loader with MERGE + daily recon.
2. **v1:** Realtime metrics consumer; SLOs/alerts on lag and outbox age; schema registry.
3. **v2:** Multi-sink fan-out (fraud features, CRM); automated repair from `payment_events`.
4. **v3:** Platformize — other money services reuse outbox + recon framework (principal leverage).

**Migration from dual-write legacy:**

- Shadow-publish via outbox alongside old path.
- Recon compares both; cut consumers when missing=0.
- Disable legacy publish; keep recon forever.

---

## 14. Org / Ownership (principal scope)

| Concern | Owner |
|---------|--------|
| Payment state machine & SoR | Payments team |
| Outbox library + relay platform | Platform / streaming team |
| Event contract & compatibility | Payments + Data (joint ADR) |
| Warehouse models & BI | Analytics engineering |
| Reconciliation SLO | Shared — Payments owns SoR truth; Analytics owns apply; both own drift zero |

**ADR you would write:** “All money-moving services must emit durable domain events via transactional outbox; dual-write to Kafka is prohibited.”

That ADR is the principal artifact — it constrains the next five systems.

---

## 15. Spoken 3-Minute Answer (memorize)

> “I’d treat this as a correctness problem across two consistency domains. The payments database is the system of record. On every state transition we write the payment change and an outbox event in the same database transaction, so we never dual-write to Kafka. A relay or CDC publishes at-least-once to a Kafka topic partitioned by payment_id, with a stable event_id and a monotonic version per payment.
>
> Analytics consumers apply events with idempotent upserts: higher version wins, duplicates are no-ops. That gives us exactly-once business effect even though delivery is at-least-once. Freshness is an SLO — say under a minute for operational metrics — but the non-negotiable is zero silent loss.
>
> I’d close the loop with continuous reconciliation between payments SoR and analytics facts, with automated replay for gaps. I’d explicitly reject sync writes to the warehouse and dual-write from the app, because those couple checkout availability to analytics and create permanent drift under partial failure.
>
> If this becomes org-wide, I’d platformize the outbox and recon so every money service inherits the invariant by default.”

---

## 16. Checklist — Did We Satisfy the Prompt?

| Requirement | Mechanism |
|-------------|-----------|
| Every committed txn reaches analytics | Atomic outbox + durable bus + replay |
| Appears **correctly** | Versioned idempotent MERGE; integer amounts; status-aware measures |
| **Eventually** (not instantly) | Async projection + lag SLO |
| Survives retries/outages | At-least-once + idempotency + recon heal |
| Principal-level | Invariant, ADR, ownership, rejected alternatives, measurable drift |

---

## 17. Whiteboard One-Liner

```
Commit(payment + outbox) → reliable publish → idempotent project → reconcile to zero drift
```

That is the entire design. Everything else is detail.
