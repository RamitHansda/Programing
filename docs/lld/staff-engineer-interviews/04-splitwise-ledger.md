# LLD: Splitwise-style expense splitting (simplified ledger)

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

- **Group**, **UserId** (value types).
- **Expense**: immutable record; references **LedgerEntries** or contains derived splits.
- **LedgerEntry**: `(groupId, from, to, amount, expenseId, type)` if you model pairwise deltas, **or** per-user postings if you model double-entry.

Staff-level tip: prefer **append-only ledger** + derived read model over silent mutation.

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
