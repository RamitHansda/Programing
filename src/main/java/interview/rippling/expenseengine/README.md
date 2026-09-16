# Rippling — Travel Expense / Corporate Card Rules Engine

Java solution for the high-frequency Rippling coding interview (phone screen / onsite coding).

Full interview guide: [`docs/interviews/RIPPLING-TRAVEL-EXPENSE-RULES-ENGINE.md`](../../../../docs/interviews/RIPPLING-TRAVEL-EXPENSE-RULES-ENGINE.md)

## Run

```bash
# From repo root
mvn -q -Dtest=interview.rippling.expenseengine.RuleEngineTest test
mvn -q -DskipTests compile
java -cp target/classes interview.rippling.expenseengine.ExpenseRulesDemo
```

## Design snapshot

- **Strategy rules** (`ExpensePolicyRule`) with `EXPENSE` or `TRIP` scope
- **`BigDecimal`** amounts — never lexicographic string compares
- **`EvaluationResult`** returns *all* violations per expense and per trip (004 keeps both hits)
- Managers pass any rule subset; new rule types do not require changing `RuleEngine`

## Sample expectation

| Subject | Violations |
|---|---|
| Expense 003 | restaurant > $75 |
| Expense 004 | airfare ban + amount > $250 |
| Expense 007 | entertainment ban |
| Trip 002 | total > $2000 + meals > $200 |
