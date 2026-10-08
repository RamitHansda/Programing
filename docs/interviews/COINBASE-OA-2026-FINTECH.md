# Coinbase OA 2026 — Fintech CodeSignal Patterns

Source: [Lodely — Coinbase Online Assessment 2026](https://www.lodely.com/blog/coinbase-online-assessment-2025)

Format: CodeSignal, ~90 min, ~2–3 problems, proctored. Score ~500+/800 to proceed.

## The three fintech patterns

| # | Problem | Core DS | Java |
|---|---------|---------|------|
| 15 | In-Memory Banking (deposits, withdrawals, fees, queries) | HashMap + PriorityQueue | `interview.coinbase.oa2026.BankWithFees` |
| 16 | Task Management with TTL | HashMap + expiry heap | `interview.coinbase.oa2026.TaskManagerTTL` |
| 17 | Transaction Ledger Reconciliation | Hash + sort + state | `interview.coinbase.oa2026.LedgerReconciler` |

Also practice the classic multi-level banking OA: `interview.coinbase.bankingsystem.BankSystem`.

## Run tests

```bash
mvn -Dtest=interview.coinbase.oa2026.** test
```

## Patterns to internalize

1. **Strict timestamps** — process deferred/TTL events before every mutating op.
2. **Half-open TTL** — active on `[start, start+ttl)`.
3. **Never go negative** — reject withdraw/transfer that would overdraw.
4. **Canonical hash** — fixed field order before SHA-256; never `toString()` on maps.
5. **Sort then scan** — out-of-order logs: sort by `(timestamp, id)`, then replay.
