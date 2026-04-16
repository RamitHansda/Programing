# LLD: Splitwise-style expense splitting (simplified ledger)

## Interview-ready snapshot

**Say first (≈30s):** **Append-only** expenses → ledger lines; **Strategy** per split type; `BigDecimal` + explicit rounding; balances are **derived** or simplified in a pure service.

**Default assumptions:** Single currency unless they say FX; immutability on posted expense.

| Phase | ~Time | Deliver |
|-------|------|---------|
| Align | 5 min | Edit/delete policy; graph simplify in scope or not; multi-currency. |
| Model | 12 min | Group, expense, postings/entries, Money VO, split calculator. |
| API + flow | 8 min | Add expense + query balances; walk one “equal split” to lines. |
| Hard | 10 min | Remainder cents rule; zero-sum invariant; concurrency per group. |
| Close | 5 min | Double-entry vs pairwise; audit export. |

**Whiteboard order:** (1) expense → N lines (2) split strategy interface (3) remainder rule (4) balance read path (5) optional simplifier.

**Likely probes:** Rounding? Idempotent expense id? What if payer not in participants?

**30s closer:** Domain is ledger facts + pure strategies; simplification stays out of entity mutation.

---

## Interview prompt

Design a system to **create expenses**, attach **splits** among users in a group, and query **balances** (“who owes whom”).

## Clarifying questions

- **Simplification**: exact amounts only, or also percent/ shares/ itemized?
- **Settlement**: compute minimal transfers vs show running balances only?
- **Currency**: single currency for LLD or FX rates?
- **Edits / deletes**: immutable ledger with reversing entries vs update-in-place?

## Functional requirements

- Create group, add members.
- Add expense: payer, amount, participants, split rule outputting per-user share.
- Query net balance per user in a group.
- Optional: simplify debts graph.

## Non-functional requirements

- **Auditability**: money math must be explainable months later.
- **Precision**: use `BigDecimal` with explicit scale/RoundingMode (state this in interview).

## Domain model

| Kind | Type | Responsibility |
|------|------|----------------|
| **Value object** | `UserId`, `GroupId`, `Money` | Stable identity + decimal rules (`BigDecimal` + scale). |
| **Aggregate root** | `Group` (optional) | Membership list; invariant “only members participate in expenses.” |
| **Entity / event** | `Expense` | Immutable fact: payer, total, participants, split rule type + parameters, timestamp. |
| **Value / entity** | `LedgerEntry` or `Posting` | Append-only lines caused by an expense (pairwise IOU **or** double-entry legs). |
| **Domain service** | `SplitCalculator` (Strategy host) | Validates rule params and produces **zero-sum** set of lines for one expense. |
| **Domain service** | `BalanceView` / `DebtSimplifier` | Derives net balances or simplified transfers from ledger (pure function over read model). |

**Relationships:** `Expense` **generates** many `LedgerEntry` rows (1:N). `Group` **has many** `Expense`s.

Staff-level tip: prefer **append-only ledger** + derived read model over silent mutation of balances.

**Not in core domain:** SQL dialect, message queue, notification on new expense.

## Design patterns

| Pattern | Role |
|--------|------|
| **Strategy** | `SplitStrategy`: Equal, ExactAmounts, Percent, Shares (each validates inputs). |
| **Value object** | `Money`, `UserId`, `GroupId`—encode equality and validation. |
| **Domain service** | `BalanceCalculator` / `Simplifier` separate from entities. |

## Split strategy validation (do not hand-wave)

- Equal: `amount` divisible by N or define remainder rule (e.g. distribute extra cents deterministically by user id order).
- Percent: must sum to 100.
- Exact: participant sums must equal total.

## Simplify debts (high level)

- Build directed weighted graph from net balances.
- Run greedy or min-cash-flow algorithm—keep **outside** entity classes as a pure function for testability.

## Concurrency

- If multi-threaded: serialize writes per `groupId` (actor/queue) or transactional DB (mention).

## Testing strategy

- Golden tests for split strategies including remainder distribution.
- Property: sum of created ledger lines nets to zero per expense (closed loop).

## Follow-ups

- Multi-currency with **rate snapshot** on expense timestamp.
- **Permissions** (who can add expenses), disputes, comments.

## Repo tie-in

See `docs/lld/splitwise/` for a deeper domain write-up in this repository.
