# Payment Reconciliation System — HLD (Staff Engineer Interview)

---

## 1. Problem Statement

Payment reconciliation is the process of matching internal transaction records (what your system recorded) against external records (what banks, payment processors, or third-party gateways report) to detect and resolve discrepancies.

**Core challenges:**
- Transactions span multiple systems (your DB, Stripe, bank, card networks)
- External settlement files arrive in batches (T+1 or T+2), not real-time
- Partial matches, currency conversions, fees deducted by processors
- Volume: millions of transactions per day at scale
- Correctness is non-negotiable — financial audits, regulatory compliance

---

## 2. Functional Requirements

- Ingest internal transaction records from payment service
- Ingest external settlement files from payment processors (Stripe, Adyen, banks)
- Match internal ↔ external records automatically
- Flag unmatched, partially matched, and disputed records
- Support manual review and resolution workflow
- Generate reconciliation reports (daily, weekly, monthly)
- Provide audit trail for every reconciliation decision
- Support multi-currency, multi-processor, multi-entity reconciliation
- Alerting on anomalies: missing settlements, amount mismatches, duplicate charges

---

## 3. Non-Functional Requirements

| Requirement | Target |
|---|---|
| Throughput | 10M transactions/day (~120 TPS avg, 500 TPS peak) |
| Reconciliation latency | Results available within 2 hours of settlement file arrival |
| Data retention | 7 years (regulatory requirement) |
| Availability | 99.9% (reconciliation is batch-oriented, not real-time critical path) |
| Consistency | Exactly-once processing — no double-reconciliation |
| Auditability | Full immutable audit log of every state change |

---

## 4. Core Concepts

### Transaction Lifecycle States
```
INITIATED → AUTHORIZED → CAPTURED → SETTLED → RECONCILED
                                          ↓
                                     DISPUTED / CHARGEBACK
```

### Reconciliation Match Types
- **Exact match** — amount, currency, reference ID all align
- **Partial match** — amount differs due to processor fees, FX rounding
- **Unmatched internal** — in your system, not in settlement file (failed settlement?)
- **Unmatched external** — in settlement file, not in your system (missed capture?)
- **Duplicate** — same transaction appears twice in either side

---

## 5. High Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         DATA INGESTION LAYER                            │
│                                                                         │
│  ┌──────────────────┐    ┌──────────────────┐    ┌──────────────────┐  │
│  │  Internal Events │    │  Settlement File  │    │   Bank Feeds     │  │
│  │  (Kafka topic)   │    │  Ingestion (SFTP/ │    │   (Open Banking  │  │
│  │                  │    │   S3/API polling) │    │    API / ISO20022)│  │
│  └────────┬─────────┘    └────────┬──────────┘    └────────┬─────────┘  │
└───────────┼──────────────────────┼───────────────────────┼─────────────┘
            │                      │                        │
            ▼                      ▼                        ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         NORMALIZATION LAYER                             │
│                                                                         │
│   ┌──────────────────────────────────────────────────────────────────┐  │
│   │  Schema Normalizer — maps each source format to Canonical Txn    │  │
│   │  Model (amount, currency, processor_ref, internal_ref, timestamp)│  │
│   └──────────────────────────┬───────────────────────────────────────┘  │
└──────────────────────────────┼──────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         MATCHING ENGINE                                 │
│                                                                         │
│   ┌─────────────────┐   ┌─────────────────┐   ┌──────────────────────┐ │
│   │  Exact Matcher  │   │  Fuzzy Matcher  │   │  Rule Engine         │ │
│   │  (reference ID  │   │  (amount ± fee  │   │  (configurable match │ │
│   │   lookup)       │   │   tolerance,    │   │   rules per processor│ │
│   │                 │   │   date windows) │   │   / currency / type) │ │
│   └────────┬────────┘   └────────┬────────┘   └──────────┬───────────┘ │
│            └─────────────────────┼─────────────────────── ┘            │
│                                  ▼                                      │
│   ┌──────────────────────────────────────────────────────────────────┐  │
│   │              Match Result Store (PostgreSQL / DynamoDB)          │  │
│   │   MATCHED | PARTIAL_MATCH | UNMATCHED_INTERNAL | UNMATCHED_EXT  │  │
│   └──────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    EXCEPTION MANAGEMENT LAYER                           │
│                                                                         │
│   ┌──────────────────┐    ┌──────────────────┐    ┌──────────────────┐  │
│   │  Exception Queue │    │  Manual Review   │    │  Auto-Resolution │  │
│   │  (unmatched,     │    │  Workflow UI     │    │  (known fee      │  │
│   │   disputed items)│    │  (ops dashboard) │    │  patterns, FX)   │  │
│   └──────────────────┘    └──────────────────┘    └──────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                      REPORTING & AUDIT LAYER                            │
│                                                                         │
│   ┌──────────────────┐    ┌──────────────────┐    ┌──────────────────┐  │
│   │  Recon Reports   │    │  Audit Log       │    │  Alerting        │  │
│   │  (daily summary, │    │  (immutable,     │    │  (PagerDuty /    │  │
│   │   aging reports) │    │   append-only)   │    │   Slack / email) │  │
│   └──────────────────┘    └──────────────────┘    └──────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 6. Component Deep Dive

### 6.1 Data Ingestion Layer

#### Internal Transaction Events
- Payment service publishes events to **Kafka** on every state transition
- Topics: `payment.authorized`, `payment.captured`, `payment.refunded`
- Each event carries: `internal_txn_id`, `amount`, `currency`, `processor`, `timestamp`, `idempotency_key`
- Recon service consumes these and writes to `internal_transactions` table

#### External Settlement Files
- Processors (Stripe, Adyen, Braintree) deliver CSV/JSON/ISO8583 files via:
  - SFTP drop (most legacy banks)
  - S3 bucket delivery
  - REST API polling (modern processors)
- **File Ingestion Service** polls/listens, downloads, validates checksums, stores raw file in S3 (immutable), and triggers processing
- Idempotency: file fingerprint (MD5/SHA256) checked before processing to prevent double-ingestion

#### Bank Statement Feeds
- ISO 20022 XML or MT940 format from banks
- Captures actual bank account debits/credits
- Used for final cash reconciliation (bank ↔ processor ↔ internal)

---

### 6.2 Normalization Layer

Every source has its own schema. The normalizer maps each to a **Canonical Transaction Model**:

```json
{
  "canonical_id": "uuid",
  "source": "STRIPE | ADYEN | INTERNAL | BANK",
  "source_reference": "ch_3Nx...",
  "internal_reference": "txn_12345",
  "amount": 10050,
  "currency": "USD",
  "amount_usd_normalized": 10050,
  "transaction_type": "CAPTURE | REFUND | CHARGEBACK | FEE",
  "settlement_date": "2024-01-15",
  "processor_fee": 29,
  "net_amount": 10021,
  "status": "SETTLED",
  "metadata": {}
}
```

- All amounts stored as **integers in minor units** (cents) — no floating point
- FX conversion at ingestion time using rate snapshot for the settlement date
- Processor fee separated from gross amount upfront

---

### 6.3 Matching Engine

**Step 1 — Exact Match (fast path)**
```
JOIN internal_transactions i
  ON external_transactions e
 WHERE i.processor_reference = e.source_reference
   AND i.settlement_date = e.settlement_date
   AND i.currency = e.currency
   AND i.gross_amount = e.gross_amount
```
~80-90% of transactions resolved here. Done in bulk SQL batch.

**Step 2 — Fuzzy Match (configurable rules)**

Configurable per processor / transaction type:
- Amount tolerance: `|internal_amount - (external_amount - processor_fee)| < threshold`
- Date window: settlement date ± N days (for cross-day settlements)
- Reference fallback: match on order_id or customer_id if processor_ref missing

**Step 3 — Rule Engine**
- YAML/DB-driven rules: "For Stripe, net amount = gross - 2.9% + $0.30"
- Known patterns (FX rounding to nearest cent, specific processor quirks) codified as rules
- New rules added without code deployments

**Matching is idempotent** — re-running produces the same result. Match results stored with a `reconciliation_run_id`.

---

### 6.4 Exception Management

Unresolved items after matching go to the **Exception Queue**:

| Exception Type | Likely Cause | Resolution |
|---|---|---|
| `UNMATCHED_INTERNAL` | Settlement delayed, capture failed | Wait N days, then raise dispute |
| `UNMATCHED_EXTERNAL` | Missing internal record, fraud | Investigate, create adjustment |
| `AMOUNT_MISMATCH` | Fee calculation difference | Auto-resolve if within tolerance, else manual |
| `DUPLICATE_EXTERNAL` | Processor sent file twice | Deduplicate, flag processor |
| `CHARGEBACK` | Customer dispute | Route to dispute management workflow |

**Aging Policy:** Exceptions unresolved after SLA (e.g., 5 days) auto-escalate and trigger alerts.

---

### 6.5 Data Model (Simplified)

```sql
-- Internal transactions sourced from payment service
CREATE TABLE internal_transactions (
    id              UUID PRIMARY KEY,
    idempotency_key VARCHAR UNIQUE,
    processor       VARCHAR,        -- STRIPE, ADYEN, etc.
    processor_ref   VARCHAR,
    amount          BIGINT,         -- in minor units
    currency        CHAR(3),
    txn_type        VARCHAR,
    txn_date        DATE,
    settlement_date DATE,
    status          VARCHAR,
    created_at      TIMESTAMPTZ
);

-- External records from settlement files
CREATE TABLE external_transactions (
    id              UUID PRIMARY KEY,
    source_file_id  UUID REFERENCES settlement_files(id),
    processor       VARCHAR,
    source_ref      VARCHAR,
    gross_amount    BIGINT,
    processor_fee   BIGINT,
    net_amount      BIGINT,
    currency        CHAR(3),
    settlement_date DATE,
    created_at      TIMESTAMPTZ
);

-- Match results
CREATE TABLE reconciliation_results (
    id                   UUID PRIMARY KEY,
    run_id               UUID,
    internal_txn_id      UUID REFERENCES internal_transactions(id),
    external_txn_id      UUID REFERENCES external_transactions(id),
    match_status         VARCHAR,   -- MATCHED, PARTIAL, UNMATCHED_INT, UNMATCHED_EXT
    match_type           VARCHAR,   -- EXACT, FUZZY, RULE_BASED, MANUAL
    amount_delta         BIGINT,    -- difference if partial match
    resolved_by          VARCHAR,   -- system or user_id
    resolved_at          TIMESTAMPTZ,
    notes                TEXT,
    created_at           TIMESTAMPTZ
);

-- Immutable audit log
CREATE TABLE recon_audit_log (
    id              UUID PRIMARY KEY,
    entity_type     VARCHAR,
    entity_id       UUID,
    action          VARCHAR,
    before_state    JSONB,
    after_state     JSONB,
    actor           VARCHAR,
    created_at      TIMESTAMPTZ
) -- append-only, no UPDATE/DELETE allowed
```

---

## 7. Idempotency & Exactly-Once Processing

Critical for correctness:

1. **File-level deduplication** — SHA256 of settlement file stored; reprocessing same file is a no-op
2. **Transaction-level deduplication** — `idempotency_key` unique constraint on internal_transactions
3. **Reconciliation runs** — each run assigned a `run_id`; partial runs can be safely retried
4. **Outbox pattern** — state changes written to DB + outbox table in same transaction; downstream events published from outbox (no dual-write problem)

---

## 8. Scalability Design

### Partitioning Strategy
- Partition `internal_transactions` and `external_transactions` by `settlement_date` (range partitioning)
- Hot partitions: last 7 days actively queried; older partitions archived to cold storage (S3 + Parquet)

### Batch Processing at Scale
- Matching engine runs as **Apache Spark** job for historical reconciliation (billions of records)
- Daily incremental runs as **scheduled SQL jobs** (Airflow DAG) for T+1 settlement
- Parallel processing by processor: Stripe batch runs independently of Adyen batch

### Read Scalability
- Reporting queries served from **read replicas** or **data warehouse** (Snowflake/BigQuery)
- OLTP (exception management) on primary PostgreSQL
- OLAP (reports, dashboards) on warehouse

---

## 9. Failure Modes & Resilience

| Failure | Mitigation |
|---|---|
| Settlement file arrives late | SLA monitoring; alert if file not received by T+1 09:00 |
| Matching job fails mid-run | Checkpointing; resume from last committed offset |
| Duplicate file delivery | SHA256 idempotency check at ingestion |
| DB write failure during match | Transactional writes; retry with exponential backoff |
| Processor API down | Dead letter queue; retry with circuit breaker |
| Incorrect FX rate used | FX rates snapshotted at ingestion time and stored immutably |

---

## 10. Observability

### Key Metrics
- `recon.match_rate` — % of transactions matched (target: > 99.5%)
- `recon.exception_count` by type and processor
- `recon.file_ingestion_lag_seconds` — time from file available → processed
- `recon.unmatched_amount_usd` — total dollar value of unresolved exceptions
- `recon.aging_exceptions_count` — exceptions past SLA threshold

### Alerts
- Match rate drops below 98% → PagerDuty
- Settlement file not received by deadline → PagerDuty
- Unmatched amount exceeds $X → PagerDuty + escalation
- Processing job takes > 2x baseline → Slack warning

---

## 11. Security & Compliance

- Settlement files encrypted at rest (S3 SSE-KMS) and in transit (TLS)
- All DB access via IAM roles, no static credentials
- PII masked in logs — card numbers, bank account numbers never logged
- Role-based access: ops team can view/resolve exceptions; read-only for auditors
- Data retention: 7 years per PCI-DSS and SOX requirements
- All reconciliation decisions immutably logged (audit trail for regulators)

---

## 12. Staff Engineer Trade-Off Discussion Points

### Why not real-time reconciliation?
Settlement is fundamentally a batch process — banks and card networks operate on T+1/T+2 cycles. Real-time matching against pending authorizations is possible but adds complexity without changing the final reconciliation outcome. Design for the actual settlement cycle.

### SQL vs Spark for matching?
- SQL (PostgreSQL) is sufficient for daily incremental volumes (< 5M records/day)
- Spark for historical backfill, large-scale re-reconciliation, or > 50M records/day
- Start with SQL; migrate to Spark when complexity/volume demands it

### Eventual consistency in match results?
Reconciliation results are eventually consistent by design — a transaction unmatched today may be matched tomorrow when the delayed settlement file arrives. The system must model time windows, not just point-in-time matching.

### Idempotency is not optional
Double-reconciliation (marking a transaction as settled twice) causes financial ledger corruption. Every processing step must be idempotent. This is a hard requirement, not a nice-to-have.

### Canonical model is the key abstraction
The normalizer is the most important component. Getting the canonical model right means the matching engine is processor-agnostic. Adding a new processor = writing a new normalizer, not touching the matching logic.

---

## 13. Interview Follow-Up Questions to Expect

- How do you handle chargebacks and reversals?
- How do you reconcile multi-currency transactions with FX fluctuation?
- What happens if your internal system recorded a transaction but it never reached the processor?
- How do you handle a processor sending the same settlement file twice?
- How would you scale this to 1 billion transactions/day?
- How do you ensure the audit log is tamper-proof?
- How do you handle schema changes in settlement files from processors?
- What is your SLA for exception resolution and how do you enforce it?
