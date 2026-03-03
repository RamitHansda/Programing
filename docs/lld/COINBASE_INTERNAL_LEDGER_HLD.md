# High-Level Design: Coinbase Internal Ledger System

**Audience:** Engineers designing a regulated, high-throughput crypto exchange ledger.  
**Goal:** Keep customer and treasury balances correct under all conditions (failures, retries, partial outages), with a complete audit trail.

---

## 1. Problem & Scope

Coinbase's internal ledger is the source of truth for money movement inside the platform:

- Customer deposits and withdrawals
- Trade settlements (asset transfer between counterparties)
- Fees, rebates, and internal transfers
- Holds/reservations for pending actions
- Reversals and corrections with full auditability

### In Scope

- Multi-asset, multi-account double-entry ledger
- Atomic posting engine with idempotency and exactly-once effects
- Balance snapshots + journal entries
- Reconciliation with external systems (banks/chains/custody)
- Audit and compliance controls

### Out of Scope

- Matching engine details (handled separately)
- Blockchain node internals
- UI/reporting implementation details

---

## 2. Core Principles and Invariants

The design must enforce these invariants:

1. **Double-entry always balances**  
   Every transaction has at least 2 legs and total debits == total credits (per asset).
2. **No mutable history**  
   Journal entries are append-only; corrections are compensating entries.
3. **Deterministic idempotency**  
   Same idempotency key returns same result; no duplicate financial impact.
4. **Strong consistency for posting**  
   Ledger write path is serializable per account (or per shard) to avoid lost updates.
5. **Traceable lineage**  
   Each ledger transaction links to business event (`trade_id`, `withdrawal_id`, `deposit_id`).
6. **Separation of available vs held balances**  
   Holds are explicit and enforce spendability constraints.

---

## 3. Ledger Data Model

### 3.1 Main Entities

| Entity | Purpose | Key Fields |
|--------|---------|------------|
| **LedgerAccount** | Logical account bucket per owner + asset + purpose | `account_id`, `owner_type`, `owner_id`, `asset`, `account_type`, `status` |
| **JournalTx** | Business transaction envelope | `journal_tx_id`, `idempotency_key`, `event_type`, `external_ref`, `created_at` |
| **JournalEntry** | One leg in a double-entry posting | `entry_id`, `journal_tx_id`, `account_id`, `direction`, `amount`, `asset` |
| **BalanceSnapshot** | Fast read model for balances | `account_id`, `available`, `held`, `pending_in`, `pending_out`, `version` |
| **ReconciliationRecord** | Track external vs internal agreement | `recon_id`, `source`, `asset`, `expected`, `observed`, `status` |

### 3.2 Account Taxonomy

Common account types:

- **Customer:** `customer_available`, `customer_held`
- **Exchange treasury:** hot wallet, cold wallet, omnibus settlement
- **Revenue:** fee revenue accounts by asset
- **Clearing/suspense:** temporary transit accounts
- **Adjustments:** manual correction control account

Each account is scoped by asset. Example:

- `customer_123:BTC:available`
- `exchange_treasury:BTC:hot_wallet`
- `fee_revenue:USD`

### 3.3 Posting Shape

Each journal transaction includes multiple entries:

- Trade settlement (buyer pays USD, receives BTC; seller pays BTC, receives USD; fee legs)
- Withdrawal (customer debit, treasury outbound credit + network fee)
- Deposit (treasury inbound debit, customer credit after confirmations)

---

## 4. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│ Business Producers                                                  │
│ (Orders, Wallet, Payments, Rewards, Admin Adjustments)             │
└───────────────────────────────┬─────────────────────────────────────┘
                                │ LedgerCommand (idempotent)
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│ Ledger API / Command Gateway                                        │
│ - authN/authZ, schema validation, idempotency key checks            │
└───────────────────────────────┬─────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│ Posting Engine (single writer per shard)                            │
│ - validate account state, apply invariants, write journal + balances │
└───────────────┬───────────────────────────────┬─────────────────────┘
                │                               │
                ▼                               ▼
      ┌───────────────────┐            ┌─────────────────────┐
      │ Ledger DB         │            │ Outbox / Event Bus  │
      │ Journal + Balance │            │ PostedTx events      │
      └─────────┬─────────┘            └─────────┬───────────┘
                │                                 │
                ▼                                 ▼
      ┌───────────────────┐            ┌─────────────────────┐
      │ Balance Query API │            │ Reconciliation      │
      │ (read optimized)  │            │ + Risk + Reporting  │
      └───────────────────┘            └─────────────────────┘
```

### Key Design Decision

- **Write path is authoritative and synchronous** for financial correctness.
- **Events are published via outbox pattern** to avoid dual-write inconsistencies.

---

## 5. Posting Engine Lifecycle

For each incoming command:

1. Validate request schema, permissions, and idempotency key.
2. Build canonical journal transaction (all entries explicit).
3. Start DB transaction.
4. Lock impacted balance rows (`SELECT ... FOR UPDATE`) or use optimistic versioning with retry.
5. Re-check invariants:
   - Sum debits == sum credits per asset
   - No negative available balance unless account permits overdraft
6. Insert `journal_tx` and `journal_entries`.
7. Update `balance_snapshots` atomically.
8. Insert outbox event in same DB transaction.
9. Commit.
10. Async dispatcher publishes outbox to Kafka and marks delivered.

### Idempotency Semantics

- Unique constraint on `(idempotency_key, producer_scope)`
- If duplicate key arrives:
  - If payload hash matches: return prior result
  - If payload hash differs: reject as conflict

---

## 6. Canonical Flows

### 6.1 Trade Settlement

For `BUY BTC-USD`:

- Debit buyer USD available
- Credit seller USD available
- Debit seller BTC available
- Credit buyer BTC available
- Debit/credit fee accounts as separate legs

All trade effects are in one ledger transaction per fill (or deterministic batch unit).

### 6.2 Hold and Release

When placing a limit buy order:

- Move funds from `customer_available` to `customer_held` (same owner, same asset)

On cancel:

- Move remaining from `held` back to `available`

On execution:

- Consume `held` into settlement legs

### 6.3 Crypto Deposit

Before final confirmations:

- Credit `pending_in` or suspense account

After required confirmations:

- Move to customer available

### 6.4 Crypto Withdrawal

At request acceptance:

- Move customer available -> customer held

At broadcast:

- Move held -> treasury pending_out

At on-chain finality:

- Settle pending_out -> external settlement account and record network fee

---

## 7. Storage and Scaling Strategy

### 7.1 Recommended Persistence

- **Primary DB:** PostgreSQL (strong consistency, mature transactional semantics)
- **Partitioning:** By `account_id` hash or `(owner_id, asset)` for horizontal scale
- **Indexes:**  
  - `journal_entries(account_id, created_at)`  
  - `journal_tx(external_ref)`  
  - `idempotency(producer_scope, idempotency_key)`

### 7.2 Throughput Model

- Route commands to **deterministic shard owners**.
- Use **single-writer workers per shard** for predictable ordering.
- Keep transactions short; avoid remote calls in DB transaction.

### 7.3 Read Models

- Balance query from `balance_snapshots` (fast path).
- Audit/history from `journal_entries`.
- Optional OLAP sink (ClickHouse/Snowflake) from event stream.

---

## 8. Reconciliation and Controls

### 8.1 Internal Reconciliation

- Check per asset:
  - Sum(customer liabilities) + equity + revenue == sum(assets)
- Validate snapshots can be recomputed from journal stream.

### 8.2 External Reconciliation

- Banks: compare fiat ledger movements with bank statements.
- Blockchains/custody: compare on-chain balances and movement hashes with treasury accounts.
- Mismatches create reconciliation cases with severity and SLA.

### 8.3 Operational Safeguards

- Circuit breakers on abnormal posting failures
- Daily trial-balance generation
- Dual-control for manual adjustments
- Immutable audit trail for all admin actions

---

## 9. Failure Modes and Recovery

| Failure | Impact | Mitigation |
|--------|--------|------------|
| API timeout after commit | Client retries may duplicate | Idempotency key guarantees same result |
| Kafka outage | Events delayed | Outbox retains events; retry dispatcher |
| Worker crash mid-processing | In-flight command uncertain | DB transaction atomicity + idempotent replay |
| Reconciliation drift | Financial mismatch | Automated drift detection + compensating entries only |
| Hot shard overload | Latency spikes | Rebalance shards, split high-volume tenants/assets |

Recovery approach:

- Rebuild balances from journal for affected accounts
- Compare rebuilt state to snapshots
- Auto-fix snapshot mismatch from authoritative journal

---

## 10. Security, Compliance, and Audit

- Encrypt sensitive data at rest and in transit.
- Strict RBAC:
  - Posting APIs: service-to-service auth only
  - Manual adjustments: privileged workflow + approval
- Immutable, queryable audit log:
  - Who initiated
  - Which business event
  - Which accounts/amounts changed
  - Before/after balance versions
- Retention and export compatible with SOX/PCI/regulatory requirements.

---

## 11. API Sketch

### Command API (write)

- `POST /ledger/v1/commands/post`
  - body: `idempotency_key`, `event_type`, `external_ref`, `entries[]`, `metadata`
  - returns: `journal_tx_id`, status, applied entries

### Query API (read)

- `GET /ledger/v1/accounts/{account_id}/balance`
- `GET /ledger/v1/accounts/{account_id}/entries?from=...&to=...`
- `GET /ledger/v1/journal/{journal_tx_id}`

---

## 12. Interview-Ready Summary

Coinbase's internal ledger should be an **append-only double-entry system** with a **strongly consistent posting engine**, **explicit hold/available balances**, and **strict idempotency**.  
The ledger DB is the financial source of truth; all downstream systems consume posted events via **outbox + event bus**.  
Correctness is maintained through invariants, reconciliation, compensating entries, and a complete audit trail.
