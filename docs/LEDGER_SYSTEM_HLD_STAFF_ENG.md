# Ledger System — High-Level Design (Staff Engineer Level)

---

## 1. Problem Framing

Before drawing boxes, a Staff Engineer asks: **what problem are we actually solving?**

A ledger is the **financial source of truth**. It answers:
- How much money is in account X right now?
- What happened to every dollar that ever entered the system?
- Can I prove no money was created or destroyed?

This drives every architectural decision.

---

## 2. Requirements

### Functional
- Create/manage accounts (user, merchant, fee, reserve, suspense)
- Record transactions: debit one or more accounts, credit one or more accounts (double-entry)
- Query account balance (current & point-in-time)
- Query transaction history with pagination/filtering
- Support multi-currency
- Idempotent transaction submission
- Reversal / void of transactions
- Reconciliation reports

### Non-Functional

| Property | Target |
|---|---|
| Consistency | Strong (no money lost/created) |
| Availability | 99.99% |
| Write latency (P99) | < 50ms |
| Read latency (P99) | < 10ms |
| Throughput | 50,000 TPS (scalable) |
| Audit | 7-year immutable history |
| Data loss | RPO = 0 (no data loss) |

### Out of Scope (v1)
- FX conversion engine
- Payment network integrations (handled by upstream systems)
- Fraud detection

---

## 3. Core Principles (Non-Negotiable)

### 3.1 Double-Entry Bookkeeping
Every transaction produces **journal entries** where:

```
∑ DEBIT amounts == ∑ CREDIT amounts
```

Money is never destroyed. A transfer from Account A to Account B:

```
DEBIT  Account A  $100
CREDIT Account B  $100
```

### 3.2 Immutability
Ledger entries are **never updated or deleted**. Mistakes are corrected with **compensating/reversal entries**. This is the foundation of auditability.

### 3.3 Idempotency
Submitting the same transaction twice produces the same result. Clients provide an `idempotency_key`; the system deduplicates.

### 3.4 Atomicity
A transaction either fully commits all its journal entries or none of them. No partial state.

---

## 4. Data Model

```sql
-- Account
CREATE TABLE accounts (
    id              UUID PRIMARY KEY,
    external_id     VARCHAR(255) UNIQUE,          -- client reference
    type            VARCHAR(20) NOT NULL,          -- ASSET | LIABILITY | EQUITY | REVENUE | EXPENSE
    normal_balance  VARCHAR(10) NOT NULL,          -- DEBIT | CREDIT (accounting sign convention)
    currency        CHAR(3) NOT NULL,              -- ISO-4217 code
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | FROZEN | CLOSED
    metadata        JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT NOT NULL DEFAULT 0      -- optimistic locking
);

-- Transaction (the event)
CREATE TABLE transactions (
    id              UUID PRIMARY KEY,
    idempotency_key VARCHAR(255) UNIQUE NOT NULL,
    description     TEXT,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING | POSTED | VOIDED
    metadata        JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- JournalEntry (the immutable lines)
CREATE TABLE journal_entries (
    id              UUID PRIMARY KEY,
    transaction_id  UUID NOT NULL REFERENCES transactions(id),
    account_id      UUID NOT NULL REFERENCES accounts(id),
    direction       VARCHAR(10) NOT NULL,          -- DEBIT | CREDIT
    amount          BIGINT NOT NULL,               -- minor units (cents) — NEVER FLOATS
    currency        CHAR(3) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
    -- No UPDATE ever. Append-only.
);

-- BalanceCheckpoint (for efficient balance reads)
CREATE TABLE balance_checkpoints (
    id              UUID PRIMARY KEY,
    account_id      UUID NOT NULL REFERENCES accounts(id),
    balance         BIGINT NOT NULL,
    as_of_version   BIGINT NOT NULL,              -- last JournalEntry sequence applied
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

> **Why `BIGINT` for money?** Floating point arithmetic is non-deterministic. `$1.10` as `float64` = `1.0999999...`. Always store in minor units (cents, pence, etc.).

> **Balance is derived, not stored** — see §6 for the full strategy.

---

## 5. System Architecture

```
                        ┌──────────────────────────────────────┐
                        │           Client Layer               │
                        │  (Payment Service, Wallet, Billing)  │
                        └──────────────┬───────────────────────┘
                                       │ gRPC / REST
                        ┌──────────────▼───────────────────────┐
                        │         API Gateway / BFF            │
                        │   (Auth, Rate Limiting, TLS)         │
                        └──────────────┬───────────────────────┘
                                       │
               ┌───────────────────────┼────────────────────────┐
               │                       │                        │
   ┌───────────▼──────┐   ┌────────────▼───────┐  ┌────────────▼──────┐
   │  Ledger Write    │   │  Ledger Read       │  │  Reconciliation   │
   │  Service         │   │  Service           │  │  Service          │
   │  (Transaction    │   │  (Balance, History)│  │  (Async jobs)     │
   │   Processor)     │   │                    │  │                   │
   └───────────┬──────┘   └────────────┬───────┘  └────────────┬──────┘
               │                       │                        │
   ┌───────────▼───────────────────────▼────────────────────────▼──────┐
   │                        Database Layer                              │
   │                                                                    │
   │   ┌─────────────────────┐        ┌──────────────────────────┐     │
   │   │  Primary DB         │        │  Read Replicas (2-3)     │     │
   │   │  PostgreSQL         │───────▶│  PostgreSQL              │     │
   │   │  (Write path)       │        │  (Read path)             │     │
   │   └─────────────────────┘        └──────────────────────────┘     │
   └────────────────────────────────────────────────────────────────────┘
               │
   ┌───────────▼──────────────────────────────────┐
   │              Event Bus (Kafka)               │
   │   transaction.posted / account.updated       │
   └───────────┬──────────────────────────────────┘
               │
   ┌───────────▼──────────────────────────────────┐
   │           Balance Cache (Redis)              │
   │    account_id → { balance, as_of_version }   │
   └──────────────────────────────────────────────┘
```

---

## 6. Balance Computation Strategy

This is the most critical design decision in a ledger system.

### Option A: Always Compute from Journal Entries (Naive)

```sql
SELECT SUM(CASE WHEN direction='CREDIT' THEN amount ELSE -amount END)
FROM journal_entries
WHERE account_id = ?
```

**Problem:** Scans the entire history. Becomes slower every day. Unacceptable at scale.

### Option B: Running Balance Column (Dangerous)

Store a `balance` field on the Account row. Update on every transaction.

**Problem:** Creates a hot row. Under concurrent writes, requires row-level locking, destroying throughput.

### Option C: Balance Checkpointing + Cache (Recommended ✅)

```
BalanceCheckpoint
  account_id      → the account
  balance         → snapshot balance at a point in time
  as_of_version   → last JournalEntry sequence applied
```

**Algorithm:**

```
real_time_balance =
    checkpoint.balance
    + SUM(entries WHERE version > checkpoint.as_of_version)
```

1. Periodically (or on-demand) snapshot the balance at a known `version`.
2. For real-time balance: `checkpoint.balance + delta from journal entries since checkpoint`.
3. Serve from **Redis cache** keyed by `account_id`. Cache is invalidated/updated on `transaction.posted` Kafka event.

**Consistency model for reads:**
- Reads from replica lag by ~5–50ms. For balance displays, this is acceptable.
- For **authoritative balance** (e.g., before allowing a debit), read from **primary** with a row-level advisory lock.

---

## 7. Write Path — Transaction Processing

```
Client
  │
  │  POST /v1/transactions
  │  { idempotency_key, entries: [{account_id, direction, amount}], ... }
  ▼
Ledger Write Service
  │
  ├─ 1. Validate idempotency_key (check Redis cache → DB)
  │       If exists → return cached response (return 200, not 409)
  │
  ├─ 2. Validate accounts exist and are ACTIVE
  │
  ├─ 3. Validate double-entry constraint: ∑DEBIT == ∑CREDIT
  │
  ├─ 4. Validate sufficient balance (for debit accounts)
  │       SELECT balance ... FOR UPDATE   ← locks the account row briefly
  │
  ├─ 5. BEGIN TRANSACTION
  │       INSERT INTO transactions (...)
  │       INSERT INTO journal_entries (...) × N
  │       UPDATE accounts SET version = version + 1   ← optimistic lock bump
  │     COMMIT
  │
  ├─ 6. Publish event → Kafka: transaction.posted
  │
  └─ 7. Return TransactionResponse
```

**Idempotency implementation:**
- Check Redis first (fast path) for `idempotency_key`.
- If miss, check DB (ensures correctness even if Redis is cold/evicted).
- After commit, write result to Redis with TTL (24h).

---

## 8. API Design

```
# Accounts
POST   /v1/accounts                    → Account
GET    /v1/accounts/{id}               → Account
GET    /v1/accounts/{id}/balance       → Balance
PATCH  /v1/accounts/{id}/status        → Account

# Transactions
POST   /v1/transactions                → Transaction
GET    /v1/transactions/{id}           → Transaction
POST   /v1/transactions/{id}/void      → Transaction (with reversal entries)

# Journal Entries
GET    /v1/accounts/{id}/entries       → []JournalEntry  (paginated, cursor-based)

# Reconciliation
GET    /v1/reports/trial-balance       → []{ account_id, debit_total, credit_total }
GET    /v1/reports/integrity-check     → { status: OK | BROKEN, drift_amount }
```

### Request Example — Post a Transaction

```json
POST /v1/transactions
{
  "idempotency_key": "payment-svc-txn-abc123",
  "description": "User purchase",
  "metadata": { "order_id": "ord-456" },
  "entries": [
    { "account_id": "acct-user-001",     "direction": "DEBIT",  "amount": 10000, "currency": "USD" },
    { "account_id": "acct-merchant-001", "direction": "CREDIT", "amount": 9700,  "currency": "USD" },
    { "account_id": "acct-fee-001",      "direction": "CREDIT", "amount": 300,   "currency": "USD" }
  ]
}
```

---

## 9. Scalability Strategy

### Sharding
- Shard by `account_id` using consistent hashing.
- A single transaction touching accounts on different shards requires a **distributed transaction** (2PC via saga pattern).
- **Design recommendation:** Co-locate related accounts using a `ledger_group_id`. All accounts belonging to one merchant go to the same shard.

### Hot Account Problem

High-volume accounts (e.g., a platform fee account receiving thousands of credits/sec) become a write bottleneck.

**Solution: Shadow/Bucket Accounts**

```
fee_account_bucket_1
fee_account_bucket_2
...
fee_account_bucket_N
```

- Writes go to a random bucket — no contention.
- Total balance = `∑ bucket balances`.
- Periodically consolidate buckets asynchronously.

### Read Scaling
- 3 read replicas handle all balance reads and history queries.
- Redis cache absorbs >95% of balance reads.
- Cursor-based pagination (not `OFFSET`) for history queries:
  ```sql
  WHERE created_at < :cursor ORDER BY created_at DESC LIMIT 100
  ```

---

## 10. Consistency & Correctness Guarantees

| Scenario | Mechanism |
|---|---|
| Partial write (crash mid-transaction) | PostgreSQL ACID — all or nothing |
| Duplicate submission | Idempotency key deduplication |
| Race condition on balance check | `SELECT ... FOR UPDATE` on account row |
| Silent data corruption | Integrity check job: ∑DEBIT == ∑CREDIT every hour |
| Replica lag serving stale balance | Read from primary for authoritative ops |
| Clock skew on distributed nodes | Use DB server timestamp, not client timestamp |

---

## 11. Durability & Disaster Recovery

- PostgreSQL with **synchronous replication** to at least 1 standby (`synchronous_commit = on`).
  - A write is only acknowledged after standby confirms WAL write → RPO = 0.
- Point-in-time recovery (PITR) with WAL archiving to S3 → RTO < 15 minutes.
- **Multi-AZ deployment:**
  - Primary → AZ-1
  - Synchronous standby → AZ-2
  - Async standby → AZ-3
- Daily integrity snapshot exported to cold storage (immutable S3 bucket with Object Lock for compliance).

---

## 12. Observability

### Key Metrics (Prometheus/Grafana)

```
ledger_transactions_total{status="posted|failed"}
ledger_balance_computation_latency_ms{quantile="0.99"}
ledger_idempotency_cache_hit_ratio
ledger_db_replication_lag_seconds
ledger_integrity_drift_amount          ← alert if non-zero
```

### Alerts

| Alert | Severity |
|---|---|
| Integrity drift detected (∑DEBIT ≠ ∑CREDIT) | **P0** — page immediately |
| Replication lag > 1s | P1 |
| Balance cache miss rate > 20% | P2 |
| Transaction failure rate > 0.1% | P1 |

### Distributed Tracing
- Every request carries a `trace_id` propagated to DB queries and Kafka messages.
- Correlate `idempotency_key` with `trace_id` in structured logs.

---

## 13. Security

- All writes require **service-to-service mTLS** — no direct client access.
- Account access is scoped: a payment service can only touch accounts in its `ledger_group`.
- PII in `metadata` fields is encrypted at rest (AES-256) with key rotation.
- The `journal_entries` table has **no `DELETE` or `UPDATE` privilege** granted to any application role — enforced at DB level.
- Audit log of who initiated each transaction (`operator_id`, `service_name`) stored immutably.

---

## 14. Key Trade-offs & Decision Log

| Decision | Chosen | Rejected Alternative | Reason |
|---|---|---|---|
| DB engine | PostgreSQL | Cassandra, DynamoDB | Strong ACID, joins for reconciliation |
| Balance storage | Checkpoint + cache | Running balance column | Avoids hot-row contention |
| Sharding key | `ledger_group_id` | `account_id` | Co-locates related accounts, avoids cross-shard txns |
| Money type | `BIGINT` (minor units) | `DECIMAL`, `FLOAT` | Deterministic arithmetic |
| Consistency | Strong (sync replication) | Eventual consistency | Financial correctness is non-negotiable |
| Pagination | Cursor-based | Offset-based | Stable under concurrent inserts |
| Idempotency storage | Redis + DB fallback | DB only | Fast path + correctness under cache misses |

---

## 15. Phase 2 Roadmap

- **Event Sourcing full replay:** rebuild any account's balance from scratch from journal entries — useful for audits and migrations.
- **Multi-currency with FX:** introduce `FxRate` table and currency conversion journal entries.
- **Async settlement:** introduce `PENDING → SETTLED` state transition with T+1 batch jobs.
- **Global Active-Active:** per-region ledger shards with cross-region replication (only if latency requirements demand it — very high operational complexity).

---

## 16. Summary

> The most critical insight a Staff Engineer brings to a ledger system:
> **Correctness over performance.** You can optimize a correct system, but you cannot trust a fast incorrect one.

This design is:
- **Correct by construction** — double-entry, immutability, idempotency
- **Horizontally scalable** — sharding, read replicas, Redis cache, bucket accounts
- **Operationally observable** — integrity checks, drift alerts, distributed tracing
- **Durable** — synchronous replication, PITR, RPO = 0
