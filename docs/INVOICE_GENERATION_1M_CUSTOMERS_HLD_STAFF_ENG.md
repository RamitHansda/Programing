# Invoice Generation for 1M Customers at Midnight — HLD (Staff Engineer)

> Opinionated design for a billing platform that must generate ~1,000,000 invoices at a fixed cutover ("midnight"), correctly, exactly once, with legally-valid sequential numbering, and without melting every downstream dependency it touches.

---

## 1. Problem Framing & Resolved Assumptions

"Generate invoices for 1M customers at midnight" sounds like a cron job. It is actually five hard problems wearing a cron job's clothing: **a distributed scheduling problem, a financial-correctness problem, a legal-numbering problem, a fan-out/rate-limiting problem, and a partial-failure problem** — simultaneously. A Staff Engineer's job is to separate these before writing any code.

Ambiguous parts of the prompt, and the position I'd take on each:

| Decision | My answer | Why |
|---|---|---|
| What does "midnight" mean for 1M customers across timezones? | **Billing-entity-local midnight**, batched into hourly waves by UTC offset, not one global instant | A US and an IST customer don't share a midnight. Forcing one global run either bills people at 5:30pm their time or requires per-customer scheduling — see §5.10. |
| Invoice numbering | **Strictly sequential, gapless, per legal entity/jurisdiction** — no exceptions | Many jurisdictions (EU VAT, India GST, Brazil NF-e) legally require gapless sequences. This is the single hardest constraint in this system and shapes everything downstream. See §5.4. |
| Source of billable facts | **Usage/subscription data is frozen (billing period closed) before generation starts** | Generating against a live-mutating usage table means two retries of the same invoice can legitimately compute different totals. Unacceptable for financial documents. |
| Consistency model | **Strong for money and numbering; eventual for delivery** (email/notification) | You can retry sending a PDF; you cannot retry "how much do they owe" without it being the same answer every time. |
| Payment collection (auto-charge) | **Decoupled, async, triggered by `invoice.finalized` event** | Charging a card is a different failure domain (PSP outages, insufficient funds) with its own retry/dunning logic. Coupling it to generation means a PSP blip blocks the entire nightly run. |
| SLA | **All 1M invoices `FINALIZED` (numbered, immutable, stored) within 30 min of trigger, P99**; **delivered (email/portal) within 2 hours** | Finalization is the legally/financially critical step and must be fast and bounded. Delivery can tolerate more slack. |
| Corrections after issuance | **Never mutate a finalized invoice.** Corrections are **credit notes / debit notes** referencing the original | Same immutability principle as a ledger (see `LEDGER_SYSTEM_HLD_STAFF_ENG.md`) — a finalized invoice is a legal document, not a row you `UPDATE`. |
| Idempotency | **At-least-once trigger, exactly-once financial effect** via a per-customer-per-period idempotency key | Retries, worker crashes, and redelivered Kafka messages are all guaranteed to happen at this scale. |

---

## 2. Capacity (napkin math)

- **1,000,000 invoices** per run. Assume monthly billing (worst case: everyone in one plan renews on the 1st) — design for this, not the average day.
- Target: **all invoices FINALIZED within 30 min** → **556/s average**, design workers for **5,000/s peak** (9× headroom to absorb wave start-up, retries, and the fact real work isn't perfectly uniform).
- Per invoice: ~5 line items avg → invoice + line_items ≈ **6 rows** → **6M row writes** in the finalization window → **3,300 rows/s sustained** on the invoicing store. Comfortably within a well-sharded Postgres or a log-structured store.
- PDF rendering is the expensive step: ~150ms CPU-bound per PDF (template + tax table + i18n). To sustain 556/s → **~84 concurrent renderers**; provisioned pool sized to **1,000 concurrent** for the 5,000/s burst tier.
- Storage: ~150KB PDF × 1M = **~150GB per run**. Monthly cadence × 7-year legal retention = ~84 runs → **~12.6TB**, trivially cheap on S3 with lifecycle-to-Glacier after 1 year.
- Sequential numbering is the throughput ceiling for any single legal entity (see §5.4): a single counter can safely do **tens of thousands of increments/sec** (it's just a monotonic counter, not a full transaction) — not the bottleneck if implemented correctly, **is** the bottleneck if implemented naively (row lock per invoice under `SELECT ... FOR UPDATE` on one row serializes the entire entity's invoicing).
- Kafka: `invoice.generate.requested` — 1M messages × ~500B ≈ 500MB total, trivial volume; sized for burst/replay at **128 partitions**, key = `customer_id`.
- Downstream tax/PDF/email services are rate-limited to **whatever they can sustain**, typically far below 5,000/s — this drives the token-bucket design in §5.7, not the invoicing store.

---

## 3. API / Trigger (Control Plane)

Invoice generation is **not** interactively triggered per-customer by an API caller — it is triggered by the **Billing Cycle Scheduler** for a cohort, with an API surface for operability (retry, inspect, cancel a run).

```
POST /v1/billing-runs
  Headers: Idempotency-Key
  Body: {
    legalEntityId: "...",
    billingCycleId: "2026-07",     // period being closed
    cohort: { timezoneOffset: "+05:30", planTier?: "..." },
    dryRun?: false
  }
  → 201 { runId, expectedCustomerCount, status: "SCHEDULED" }

GET   /v1/billing-runs/{runId}                       // status, progress counters
GET   /v1/billing-runs/{runId}/invoices?status=&cursor=
POST  /v1/billing-runs/{runId}/retry                 // re-drive only FAILED customer-tasks
POST  /v1/billing-runs/{runId}/cancel                // stop before finalization; no-op after
GET   /v1/invoices/{id}                              // read-only, immutable once FINALIZED
POST  /v1/invoices/{id}/credit-notes                 // correction path, never a mutation
```

Three things worth calling out:

- **`billingCycleId` is the idempotency anchor**, not a client-supplied key alone. `(legalEntityId, customerId, billingCycleId)` is unique across the entire system — this *is* the exactly-once guarantee (§5.5).
- **Cohort, not customer list.** Like the audience-as-reference pattern in the email scheduler design, we never pass 1M customer IDs inline. The run resolves its cohort by streaming a query against the subscription store.
- **Retry is scoped to `FAILED` customer-tasks only** — a run is a checkpointed batch, not an all-or-nothing job; re-running 1M invoices to fix 200 failures is an operational non-starter.

---

## 4. Architecture

```
                          ┌───────────────────────┐
                          │  Billing Cycle         │  cron/leader-elected, one fire
                          │  Scheduler             │  per (legal_entity, cycle, tz-wave)
                          └───────────┬────────────┘
                                      │ creates BillingRun (Postgres, control plane)
                                      │ + outbox event
                                      ▼
                          ┌───────────────────────┐
                          │  Run Orchestrator      │  streams cohort, never materializes
                          │  (stateless)           │  1M rows in memory
                          └───────────┬────────────┘
                                      │ Kafka: invoice.generate.requested (128 part, key=customer_id)
                                      ▼
                    ┌─────────────────────────────────────┐
                    │      Rating / Snapshot Worker         │  freezes usage+subscription
                    │  (reads FROZEN billing-period data)   │  data as of cycle close
                    └───────────────────┬───────────────────┘
                                        │ Kafka: invoice.rated
                                        ▼
                    ┌─────────────────────────────────────┐
                    │        Tax Calculation Worker         │  calls Tax Service (Avalara-like)
                    │  (rate-limited, circuit-breaker)      │  per-jurisdiction rules
                    └───────────────────┬───────────────────┘
                                        │ Kafka: invoice.taxed
                                        ▼
                    ┌─────────────────────────────────────┐
                    │     Numbering & Finalization Worker   │  ★ the serialization point ★
                    │  (per legal-entity sequence, §5.4)    │  writes immutable invoice row
                    └───────────────────┬───────────────────┘
                                        │ Kafka: invoice.finalized  (source of truth reached)
                          ┌─────────────┴─────────────┐
                          ▼                            ▼
                ┌──────────────────┐         ┌──────────────────┐
                │  PDF Render Pool  │         │  Payment Charge   │  async, own retry/dunning,
                │  (autoscaled)     │         │  Trigger          │  never blocks finalization
                └────────┬─────────┘         └──────────────────┘
                         │ Kafka: invoice.rendered
                         ▼
                ┌──────────────────┐
                │  Delivery Worker  │  email/SES, portal notification,
                │  (rate-limited)   │  webhook to customer's ERP
                └──────────────────┘

Postgres (control):  billing_runs, billing_run_tasks (per-customer state), invoice_sequences
Postgres/partitioned: invoices, invoice_line_items, credit_notes   (financial source of truth)
S3:                   rendered PDFs, immutable, versioned bucket
Redis:                idempotency keys, tax-service/PDF-service token buckets
Kafka:                the pipeline above; every stage has a `.dlq`
```

Six stages, each independently scalable, each a **Kafka consumer group** so a slow downstream (tax service having a bad day) backs up its own topic without stalling rating or numbering.

---

## 5. The Hard Problems (and how I'm solving them)

### 5.1 Triggering exactly once — leader-elected scheduler, not "a cron job on a box"

A plain cron job on a single box is a single point of failure and, if you run two boxes for HA, a duplicate-trigger hazard. Instead:

- The **Billing Cycle Scheduler** runs on every instance but only the **leader** (via a distributed lock — Postgres advisory lock or etcd/ZooKeeper lease) is allowed to create `BillingRun` rows.
- Creating a `BillingRun` for `(legal_entity_id, billing_cycle_id, tz_wave)` is itself **idempotent** — a `UNIQUE` constraint on those three columns. If the leader crashes and a new leader retries, the insert simply fails/no-ops on conflict; no duplicate run.
- The run's existence (a row in Postgres, committed) is the durable record that "this cohort has been triggered." Everything downstream keys off `run_id`, never off wall-clock time again.

### 5.2 Freeze the billing period before generation starts — snapshot, don't compute live

If the Rating Worker computes "usage this month" by querying a live, still-mutating usage table, two things break: (a) a retry of a failed invoice-task can produce a **different total** than the first attempt if new usage events landed in between, and (b) there's no clean point to say "this period is closed."

- At `billing_cycle_id` cutover, a **period-close job** (runs before the Billing Run is created) writes a `usage_snapshot_ref` per customer — either a materialized aggregate table partitioned by cycle, or a frozen pointer into an append-only usage event log up to a specific offset/timestamp.
- The Rating Worker is **contractually required** to read only from the snapshot, never from live usage. This makes rating a **pure function** of `(customer_id, billing_cycle_id)` — retry-safe by construction.
- Late-arriving usage events (clock skew, delayed metering pipelines) after the freeze are **not** silently dropped — they roll into *next* cycle's invoice, or (if material) trigger a documented "late usage adjustment" credit/debit note. Never a silent retroactive edit to a finalized invoice.

### 5.3 Fan-out to 1M customers without hot-scanning the subscription table

Same failure mode as any "select all due rows" batch job: `WHERE billing_cycle_id = ? AND status = 'active'` against 1M+ subscription rows is a full scan and a lock-contention magnet if workers race to claim rows.

- The Run Orchestrator **streams** the cohort via a keyset-paginated cursor (`WHERE customer_id > :last_seen ORDER BY customer_id LIMIT 1000`), never `OFFSET`, never loading 1M IDs into memory.
- Each page is turned directly into Kafka messages on `invoice.generate.requested` (partitioned by `hash(customer_id) % 128`) — the orchestrator never "assigns" work to a specific worker; Kafka's consumer-group rebalancing does that.
- **Per-customer task rows** (`billing_run_tasks`) are inserted in the same page-read transaction, giving us a durable, resumable checklist — this is what makes §5.6 (partial failure) possible without replaying all 1M messages.

### 5.4 Legally-required sequential, gapless invoice numbering — the actual bottleneck

This is the constraint that separates a toy implementation from a production one. EU VAT, India GST/e-invoicing, Brazil NF-e, and most tax authorities require invoice numbers to be **sequential with no gaps, per legal entity** (sometimes per branch/tax registration). "Gaps" from cancelled/failed attempts are audit red flags.

Two wrong approaches, and why they fail:

- **UUID / random IDs**: satisfies uniqueness, fails every gapless-sequence compliance requirement outright.
- **`MAX(invoice_number) + 1` per insert**: race condition under concurrency (two workers read the same max), and if wrapped in `SELECT ... FOR UPDATE` on the whole table, it **serializes all invoicing for that legal entity** — directly fights the 556/s target.

**The design:**

- One row per legal entity in `invoice_sequences (legal_entity_id PK, next_number BIGINT, updated_at)`.
- Allocation is a **single, tiny, fast atomic increment** — not a business-logic transaction:
  ```sql
  UPDATE invoice_sequences
     SET next_number = next_number + 1
   WHERE legal_entity_id = $1
  RETURNING next_number;
  ```
  This is a single-row update with no joins, no application logic in the critical section — it holds the row lock for microseconds, not milliseconds. At this granularity a single Postgres row can sustain tens of thousands of increments/sec, far above our 556/s (peak 5,000/s) requirement.
- The **Numbering & Finalization Worker** is the *only* place this update runs, and it does it in the *same transaction* as writing the immutable `invoices` row (`status = FINALIZED`, `invoice_number`) — so a crash between "got a number" and "wrote the invoice" is impossible; either both happen or neither does.
- If a customer-task **fails before reaching numbering** (tax service down, rating error), **no number is ever allocated for it** — gaps are prevented by construction, not by cleanup.
- If a customer-task fails **after** numbering (e.g., PDF render crashes), the number stays allocated and finalized; **PDF rendering is retried independently** against the already-finalized invoice row. The invoice exists and is legally issued the moment it's numbered — rendering is just a representation of it.
- **One sequence-allocator instance per legal entity is a natural sharding key** — a French entity's invoicing throughput is completely decoupled from a Brazilian entity's, so no cross-entity contention exists at all. For an entity large enough that even microsecond locks matter (extreme edge case), a pre-allocated block-lease (`worker leases number range [N, N+1000)`) trades a small amount of gap-risk-on-crash for zero per-invoice contention — I would **not** build this until profiling proves the plain atomic increment is the bottleneck, which at 1M/entity/run it almost certainly won't be.

### 5.5 Exactly-once financial effect despite at-least-once messaging

Kafka redelivery, worker crashes and retries, and operator-triggered re-drives are all guaranteed. The financial effect of generating an invoice must still happen exactly once.

- **Uniqueness key**: `(legal_entity_id, customer_id, billing_cycle_id)` is a `UNIQUE` constraint on `invoices`. This is the true idempotency anchor — not a client-supplied header.
- Every stage of the pipeline (rating, tax, numbering, render) is written as an **upsert keyed on `billing_run_task_id`**, and each stage checks task status before doing work: "am I re-processing a task that's already past this stage?" If yes, skip and re-emit the existing result downstream — cheap idempotency check, no duplicate side effects.
- The **Numbering & Finalization Worker specifically** does: `INSERT ... ON CONFLICT (legal_entity_id, customer_id, billing_cycle_id) DO NOTHING RETURNING *`. If the conflict fires (this customer already has a finalized invoice for this cycle from a previous attempt), **no new number is allocated** — the sequence increment and the insert are the same transaction, so a duplicate attempt costs nothing and creates no gap.
- Downstream side effects with their own idempotency (PDF render, email send, payment charge) key off `invoice_id` (which only exists once finalized), so replays of `invoice.finalized` are naturally safe to reprocess (render already exists in S3 → skip; email already sent → check delivery log → skip).

### 5.6 Partial failures at 1M scale — per-customer state machine, not all-or-nothing

At 1M customers, **some percentage will fail** — bad address data, tax service timeout for an edge-case jurisdiction, a malformed subscription record from a legacy migration. Treating the run as atomic ("redo everything if 0.01% fails") is both wasteful and wrong — the other 999,900 correctly-generated invoices must not be blocked or reprocessed.

State model per customer-task:

```
billing_run_tasks.status:
  PENDING → SNAPSHOTTED → RATED → TAXED → FINALIZED → RENDERED → DELIVERED
                                                                 ↘ FAILED (with stage + error)
```

- Each stage transition is a row update with `attempts`, `last_error`, `failed_stage`. This table is the **run's checklist** — `SELECT count(*) GROUP BY status` is the live progress dashboard.
- **A run is "complete" when every task is `DELIVERED` or `FAILED`**, not when 1M messages have been consumed (consumption ≠ success).
- `FAILED` tasks are automatically retried with backoff up to N attempts (transient errors: tax service 503, DB deadlock), then parked for **operator-triggered retry** via `POST /billing-runs/{id}/retry`, which only re-drives tasks still in `FAILED` — never touches the 999,900+ that succeeded.
- **SLA math accounts for this**: the "30 min to FINALIZED" target is measured on the P99 *successful* task, with a separate, looser SLA ("99.9% of tasks reach a terminal state within 2 hours, including retries") for the tail.

### 5.7 Protecting downstream dependencies — token buckets, not blind concurrency

The invoicing pipeline itself can sustain 5,000/s; the **Tax Calculation Service** (often a third-party API, e.g., Avalara/Vertex) and **PDF rendering** and **email/SES** almost certainly cannot, or have their own contracted rate limits.

- Each external-call stage (Tax Worker, Delivery Worker) enforces a **token bucket in Redis**, sized to the vendor's contracted QPS, checked **before** calling out — cheaper to delay a message (leave it on the Kafka topic, don't ack yet / requeue with backoff) than to get 429'd and retry after wasting the round trip.
- **Circuit breaker per external dependency**: if the Tax Service error rate crosses a threshold, the breaker opens, the Tax Worker pauses consumption (Kafka lag grows, which is fine — Kafka is the buffer), and an alert fires. When the breaker closes, backlog drains at the configured rate — no thundering herd on recovery.
- PDF rendering is **internal and horizontally scalable** (stateless, CPU-bound workers, autoscale on queue depth) — token-bucket it too, but against infra capacity, not a vendor contract.
- **Batching where the vendor supports it**: many tax APIs offer bulk-calculation endpoints; batching 50-100 line items per call cuts round trips by 50-100× and is the single highest-leverage optimization for staying under vendor rate limits.

### 5.8 Money and rounding correctness

- **All monetary amounts are integers in minor units** (cents), never floats — same rule as the ledger design (`LEDGER_SYSTEM_HLD_STAFF_ENG.md` §4). `$19.999999` from floating point arithmetic is not an acceptable invoice line.
- **Tax rounding is jurisdiction-specific and must match the tax authority's documented rule** (round-half-up per line vs. round on the invoice total vs. round on the tax-group subtotal — these produce different cent-level totals and *are* audited). The Tax Service is the single source of truth for its jurisdiction's rounding rule; the invoicing pipeline never re-derives it locally.
- **`∑ line items + tax == invoice total`** is a hard invariant, checked at finalization time; a violation blocks finalization and pages on-call rather than issuing a financially inconsistent document.

### 5.9 Corrections after issuance — immutability, not UPDATE

Once `status = FINALIZED`, an invoice row and its line items are **never updated or deleted** — enforced at the DB grant level (no `UPDATE`/`DELETE` privilege on `invoices`/`invoice_line_items` for any application role), identical to the ledger's `journal_entries` policy.

- Wrong amount discovered post-issuance → issue a **credit note** (full or partial reversal) referencing `original_invoice_id`, and if still owed, a new corrected invoice. Both are new, separately-numbered documents in the same gapless sequence.
- This is not bureaucratic caution — it's what tax authorities require, and it's what makes the numbering guarantee in §5.4 meaningful. A "corrected in place" invoice with the same number as before is indistinguishable from tampering in an audit.

### 5.10 Multi-timezone "midnight" — resolved with waves, not one instant

"At midnight" for 1M customers spread across timezones is ambiguous; picking one global instant is wrong for most of them.

- Each customer has a **billing-entity-local timezone** on their subscription record.
- The scheduler doesn't fire once — it fires **one `BillingRun` per `(legal_entity, billing_cycle, UTC-offset bucket)`**, at each bucket's local midnight. With ~24-38 practical offset buckets (including half-hour offsets like IST/+5:30), this **naturally spreads the 1M-customer load across the day** instead of concentrating all of it at UTC midnight — a secondary benefit that reduces the peak-concurrency requirement well below the "all 1M at once" worst case assumed in §2's capacity math (that section deliberately sizes for the pessimistic single-wave case to guarantee headroom).
- Customers who explicitly want calendar-day-of-month invariance across DST transitions get the same "advance to next valid instant / fire at first occurrence" policy as the email scheduler design (`EMAIL_SCHEDULER_DELIVERY_HLD_STAFF_ENG.md` §5.8) — no bespoke logic needed here.

### 5.11 SLA and backpressure at the top of the funnel

- The Run Orchestrator throttles how fast it emits `invoice.generate.requested` based on **consumer lag** on the topic — if Rating Workers fall behind, the orchestrator slows its cohort-streaming rate rather than dumping 1M messages instantly and letting Kafka absorb unbounded lag.
- Every stage boundary is a **Kafka topic**, which means backpressure is naturally visible as **consumer lag**, and naturally absorbed without dropping work — the classic benefit of decoupling pipeline stages with a durable log instead of direct synchronous calls.

---

## 6. Database Ownership

### The principle: database-per-bounded-context

| Bounded context | Owns | Store | Why |
|---|---|---|---|
| **Billing run orchestration** | `billing_runs`, `billing_run_tasks`, `invoice_sequences` | Postgres (`billing-control-pg`) | Numbering (§5.4) needs a strongly consistent single-row counter in the *same* transactional boundary as finalization — cannot live in a different store from `invoices`. |
| **Invoicing (financial source of truth)** | `invoices`, `invoice_line_items`, `credit_notes` | Postgres, partitioned by `billing_cycle_id` (`invoicing-pg`) | Same cluster as `invoice_sequences` — finalization is one ACID transaction spanning both. Partition pruning keeps each cycle's writes/reads fast without a global index cliff. |
| **Rating / usage** | `usage_snapshots`, subscription plan data | Separate `usage-pg` or analytical store, read-only from invoicing's perspective | Different access pattern (high-volume event ingestion vs. low-volume invoice reads); usage ingestion must not compete for I/O with the invoicing store during a run. |
| **Tax** | Tax rules, calculation cache | Vendor-owned (external), local cache in Redis | We don't own tax law; we own the integration contract. |
| **Rendering / delivery** | Rendered PDFs, delivery receipts | S3 (immutable, versioned) + `delivery-pg` (small, receipt log only) | Blob storage is the wrong shape for Postgres; delivery receipts are small and query-light. |
| **Payments (charge collection)** | Charge attempts, dunning state | `payments-pg`, entirely separate service | Different failure domain and retry semantics (see §1) — must not share a transaction boundary with invoicing. |

**Cross-service references are by ID.** `invoices.usage_snapshot_ref` points at the Rating context; it is denormalized (amounts already computed) into the invoice row at finalization time, so a finalized invoice never needs to re-query the usage store to know what it billed for.

### Why numbering and invoicing share a cluster (the one exception to "separate everything")

Every other bounded context above is intentionally isolated. `invoice_sequences` is the deliberate exception: it **must** be transactionally atomic with the `invoices` insert (§5.4), and the only way to guarantee that without a distributed transaction is to put them in the same database. This is the right kind of exception — made explicitly, for a named correctness reason, not by accident.

---

## 7. Data Model

```sql
-- Control plane
CREATE TABLE billing_runs (
    id                UUID PRIMARY KEY,
    legal_entity_id   UUID NOT NULL,
    billing_cycle_id  TEXT NOT NULL,          -- e.g. '2026-07'
    tz_offset_bucket  TEXT NOT NULL,          -- e.g. '+05:30'
    status            TEXT NOT NULL,          -- SCHEDULED|RUNNING|COMPLETED|COMPLETED_WITH_FAILURES
    expected_count    INT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ,
    UNIQUE (legal_entity_id, billing_cycle_id, tz_offset_bucket)
);

CREATE TABLE billing_run_tasks (
    id                UUID PRIMARY KEY,
    run_id            UUID NOT NULL REFERENCES billing_runs(id),
    customer_id       UUID NOT NULL,
    status            TEXT NOT NULL,          -- PENDING|SNAPSHOTTED|RATED|TAXED
                                               -- |FINALIZED|RENDERED|DELIVERED|FAILED
    failed_stage      TEXT,
    attempts          INT NOT NULL DEFAULT 0,
    last_error        TEXT,
    invoice_id        UUID,                   -- set once FINALIZED
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON billing_run_tasks (run_id, status);

-- The serialization point (§5.4) — one row per legal entity, ever
CREATE TABLE invoice_sequences (
    legal_entity_id   UUID PRIMARY KEY,
    next_number       BIGINT NOT NULL DEFAULT 1,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Financial source of truth — immutable once FINALIZED
CREATE TABLE invoices (
    id                UUID PRIMARY KEY,
    legal_entity_id   UUID NOT NULL,
    customer_id       UUID NOT NULL,
    billing_cycle_id  TEXT NOT NULL,
    invoice_number    BIGINT NOT NULL,        -- gapless per legal_entity_id
    status            TEXT NOT NULL,          -- FINALIZED (only terminal state; corrections are new rows)
    currency          CHAR(3) NOT NULL,
    subtotal_amount   BIGINT NOT NULL,        -- minor units
    tax_amount        BIGINT NOT NULL,
    total_amount      BIGINT NOT NULL,
    usage_snapshot_ref TEXT NOT NULL,
    pdf_ref           TEXT,                   -- S3 key, set after render
    finalized_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (legal_entity_id, customer_id, billing_cycle_id),  -- exactly-once anchor (§5.5)
    UNIQUE (legal_entity_id, invoice_number)                  -- gapless sequence enforcement
) PARTITION BY LIST (billing_cycle_id);
-- No UPDATE / DELETE grant on this table or invoice_line_items for any app role.

CREATE TABLE invoice_line_items (
    id                UUID PRIMARY KEY,
    invoice_id        UUID NOT NULL REFERENCES invoices(id),
    description       TEXT NOT NULL,
    quantity           NUMERIC NOT NULL,
    unit_amount       BIGINT NOT NULL,        -- minor units
    line_total        BIGINT NOT NULL,
    tax_rate_bps      INT NOT NULL            -- basis points, avoids float rates
);

-- Corrections — new documents, never mutations of the original
CREATE TABLE credit_notes (
    id                UUID PRIMARY KEY,
    original_invoice_id UUID NOT NULL REFERENCES invoices(id),
    legal_entity_id   UUID NOT NULL,
    credit_note_number BIGINT NOT NULL,       -- own gapless sequence, or shared with invoices per jurisdiction rule
    amount            BIGINT NOT NULL,
    reason            TEXT NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (legal_entity_id, credit_note_number)
);
```

### Redis

- `idem:finalize:{legal_entity_id}:{customer_id}:{billing_cycle_id}` — fast-path duplicate check before hitting Postgres (belt-and-suspenders on top of the DB unique constraint).
- `rate:tax-svc:{legal_entity_id}` / `rate:pdf-render` / `rate:email` — token buckets (§5.7).
- `run:{run_id}:counters` — live progress (`rated`, `taxed`, `finalized`, `delivered`, `failed`) for the status dashboard, refreshed from `billing_run_tasks` aggregates.

### Kafka

- `invoice.generate.requested` — 128 partitions, key = `customer_id`.
- `invoice.rated`, `invoice.taxed`, `invoice.finalized`, `invoice.rendered` — pipeline stage events, same partitioning key so per-customer ordering is preserved through the pipeline.
- `credit_note.issued` — for downstream analytics/accounting sync.
- `*.dlq` per stage, with enough context (`billing_run_task_id`, `failed_stage`, `last_error`) to retry or hand to a human.

---

## 8. Failure Modes

| Failure | Detection | Recovery |
|---|---|---|
| Scheduler leader crashes before creating BillingRun | Lease expiry | New leader elected, retries create (idempotent on unique constraint) |
| Rating Worker crashes mid-batch | Kafka consumer offset unadvanced | Re-consume; rating is a pure function of frozen snapshot, safe to redo |
| Tax Service outage | Circuit breaker error-rate threshold | Breaker opens; Tax Worker pauses consumption; Kafka absorbs backlog; alert fires; drains at token-bucket rate on recovery |
| Numbering Worker crashes after allocating a number, before invoice insert commits | Impossible by construction — same transaction | N/A: allocation and insert are atomic together |
| Numbering Worker crashes after commit, before ack | Kafka redelivers `invoice.taxed` message | `ON CONFLICT DO NOTHING` on the unique constraint — no duplicate, no new number, no gap |
| PDF render crashes | Task stuck in `FINALIZED`, not `RENDERED`, beyond timeout | Retried independently; invoice already legally exists, only the artifact is regenerated |
| Delivery (email) fails for a customer | Delivery receipt missing / bounce webhook | Retry with backoff; invoice remains accessible via customer portal regardless of email outcome |
| Postgres primary loss (invoicing-pg) | Health probe | Failover to synchronous standby (RPO ~0 for financial writes, same durability posture as the ledger design) |
| Operator needs to retry a subset | N/A (deliberate action) | `POST /billing-runs/{id}/retry` re-drives only `FAILED` tasks, never touches finalized invoices |
| Late usage event arrives after cycle freeze | Usage pipeline timestamp vs. freeze cutoff | Rolled into next cycle, or explicit adjustment credit/debit note — never a silent edit to the finalized invoice |

---

## 9. Observability

**SLIs (per run + global):**

- `run_progress{status}` — live count of `billing_run_tasks` in each state; the core dashboard.
- `time_to_finalized_p99` per run (target < 30 min from trigger).
- `finalization_failure_rate` per run (target < 0.1%, paged above that).
- `sequence_gap_check` — a periodic job that verifies `invoice_number` sequences per legal entity have **zero gaps** among finalized invoices; any gap is a **P0** (indicates a correctness bug, not just an ops issue).
- `tax_service_error_rate`, `pdf_render_queue_depth`, `delivery_success_rate`.

**Alerts:** any run not `COMPLETED`/`COMPLETED_WITH_FAILURES` by SLA + grace window; `sequence_gap_check` non-zero (page immediately — see §5.4); circuit breaker open > 5 min; `∑ line_items + tax ≠ total` on any invoice (blocks finalization, also pages).

**Tracing:** `billing_run_task_id` propagated through every Kafka message header end-to-end, so "why is this customer's invoice stuck" is a single trace lookup, not a cross-system archaeology exercise.

**Audit log:** every state transition on `billing_run_tasks`, every credit note issuance with `operator_id`/`reason` — required for both internal debugging and external tax audits.

---

## 10. Rollout & Ops Concerns

- **Dry-run mode**: run the full pipeline through rating and tax calculation, skip numbering/finalization, compare totals against a shadow of the previous month — catches pricing-logic regressions before they touch a legally-numbered document.
- **Per-legal-entity kill switch**: pause a specific entity's run without affecting others (useful when a single jurisdiction's tax rules change mid-rollout).
- **Gradual cohort rollout for new billing logic**: run new rating logic for 1% of a cohort first, compare against old logic in shadow mode, before cutting over the full 1M.
- **Manual finalization override**: an documented, heavily-audited break-glass path for a human to finalize a single stuck invoice — still goes through the same sequence-allocation transaction, never bypasses numbering.
- **Legal/Finance sign-off gate**: any change touching tax rounding rules or the numbering scheme requires Finance review before deploy — this is a compliance system, not just an engineering one.

---

## 11. What I'm Deliberately NOT Building

Staff engineers say no.

- **Real-time invoice generation on demand** — this design is a batch pipeline optimized for a scheduled cohort; a "generate my invoice right now" feature is a different, much smaller code path (single-customer, synchronous rating+tax+number+render) that can reuse the same finalization primitive but doesn't need the fan-out machinery.
- **A generic workflow engine** — the per-customer state machine is deliberately simple (a status column + stage-specific workers), not a full BPMN/Temporal-style orchestrator. Revisit only if the pipeline grows materially more branchy.
- **Custom tax calculation engine** — buy, don't build (Avalara/Vertex/local equivalents); tax law correctness is not a competitive differentiator and the liability of getting it wrong ourselves is enormous.
- **In-pipeline payment collection** — decoupled to its own async service (§1) with its own retry/dunning semantics.
- **Cross-jurisdiction shared numbering** — every jurisdiction's gapless-sequence requirement is per legal entity; unifying them would violate the actual legal requirement for aesthetic simplicity.

---

## 12. Why This Design Scales to 1M and Beyond

- **Every pipeline stage is an independent Kafka consumer group** — rating, tax, numbering, rendering, delivery all scale (or degrade) independently. A slow tax vendor doesn't stall rendering for already-taxed invoices.
- **The only serialization point (invoice numbering) is a single-row atomic increment, sharded naturally by legal entity** — it is cheap by construction, not by luck, so it scales to 10M without redesign.
- **Nothing materializes the full cohort in memory** — streaming cursors and Kafka partitions carry the 1M customers through the pipeline without ever holding them all at once.
- **Backpressure is visible, not silent** — Kafka consumer lag surfaces exactly which stage is falling behind; the system slows gracefully rather than failing opaquely.
- **Partial failure is a first-class state**, not an exception path bolted on afterward — a run can be 99.9% successful and the remaining 0.1% is a normal, observable, retryable queue, not a fire drill.
- **Financial correctness (idempotency, immutability, gapless numbering) is enforced at the database constraint level**, not just in application logic — the kind of guarantee that survives a bug in a retry loop, not just the happy path.

---

## Appendix A — Whiteboard Focus

If doing this on a whiteboard, draw §4 first, then spend most of the remaining time on **§5.4 (sequential numbering)** and **§5.6 (partial failure state machine)**. Numbering is the constraint that separates "I built a batch job" from "I built a compliant billing system," and most candidates never mention it unprompted.

## Appendix B — Common Gaps in Mid-Level Answers

1. Not mentioning invoice numbering has legal gapless-sequence requirements at all — treating invoice IDs like any other UUID.
2. Computing amounts against live, mutating usage data instead of a frozen snapshot — makes retries non-deterministic.
3. Treating the run as all-or-nothing instead of a per-customer checkpointed state machine.
4. Using floats for money, or not naming a jurisdiction-specific tax-rounding rule.
5. Coupling payment collection (charging the card) to invoice generation — one PSP blip shouldn't block 1M invoices.
6. No rate limiting against the tax/PDF/email vendors — blind full-concurrency fan-out gets you 429'd or contract-violating.
7. "Fix" a wrong invoice with an `UPDATE` instead of issuing a credit note — breaks immutability and the audit trail.
8. Assuming one global "midnight" instead of per-customer/per-entity timezone-local cutovers.
