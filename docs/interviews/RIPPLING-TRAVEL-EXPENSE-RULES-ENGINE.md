# Rippling Coding Interview — Travel Expense / Corporate Card Rules Engine

High-frequency Rippling phone-screen / coding-round problem. Also called **Expense Rules Engine**, **Corporate Credit Card Rules Engine**, or **Travel Expense Calculation**.

Working Java solution: `src/main/java/interview/rippling/expenseengine/`

```bash
mvn -q -Dtest=interview.rippling.expenseengine.RuleEngineTest test
mvn -q -DskipTests compile
java -cp target/classes interview.rippling.expenseengine.ExpenseRulesDemo
```

---

## 1. Problem statement (what they actually ask)

Rippling wants to offer corporate cards. Managers set spending policies. You build the **rules engine** that flags expenses (and later trips) for human review.

```text
evaluateRules(rules: List<Rule>, expenses: List<Map<String,String>>) -> ???
```

- Every expense key/value is a **string** (including `amount_usd`).
- Return type is **up to you** — discuss it **before coding**.
- Design for flexibility: more rule types later + rules created via API.

### Sample expenses

| expense_id | trip_id | amount_usd | expense_type | vendor_type | vendor_name |
|---|---|---:|---|---|---|
| 001 | 001 | 49.99 | supplies | restaurant | Outback Roadhouse |
| 002 | 001 | 125.00 | supplies | retailer | Staples |
| 003 | 002 | 153.00 | meals | restaurant | Olive Yurt |
| 004 | 002 | 1996.00 | airfare | transportation | Southeast Airlines |
| 005 | 002 | 34.68 | meals | restaurant | The Great Grill |
| 006 | 002 | 22.40 | meals | restaurant | The Great Grill |
| 007 | 003 | 59.50 | entertainment | theater | Silver Screen |

**Important:** `expense_type` ≠ `vendor_type`. Expense `001` is *supplies* bought at a *restaurant*.

### Part 1 — per-expense rules

1. No restaurant expense over **$75** (`vendor_type == restaurant`)
2. No **airfare** expenses
3. No **entertainment** expenses
4. No single expense over **$250**

Expected flags: **003**, **004**, **007**. Expense **004** hits **two** rules — keep both.

### Part 2 — trip aggregates

5. Trip total cannot exceed **$2000**
6. Total **meals** per trip cannot exceed **$200**

Trip **002** violates both: total **$2206.08**, meals **$210.08**.

### Later rule families they may ask you to name

- Ban by `expense_type` / `vendor_type` / `vendor_name`
- Max amount (global or filtered)
- Trip total limit
- Sum-by-field per trip (meals, vendor type, …)
- Composite / AND-OR rules (senior follow-up)

---

## 2. Clarifying questions (say these out loud)

| Ask | Strong default assumption |
|---|---|
| Return every violation or stop at first? | **Every** — UI + audit need full explainability |
| Decline card live or flag for review? | **Flag for review** (async) |
| Boundary at exactly $75 / $250 / $2000? | Strict **`>`** — equality allowed |
| Bad `amount_usd`? | Fail that expense / throw — don't silently skip |
| Money type? | **Decimal** (`BigDecimal`), never float / string compare |
| Rule precedence / conflicts? | Report all hits; precedence is a product decision later |
| Can managers pass a subset of 100+ catalog rules? | Yes — `rules` arg is the selected subset |

**Opening line that scores well:**

> "I'll treat this as a review-queue API, not a synchronous card decline. Return type should list every violation with rule id + human message, keyed by expense and later by trip. Amounts parse to decimal. Thresholds are exclusive. Sound good?"

---

## 3. Return type — discuss first

Bad:

```text
Set<String>   // just expense ids — no explanation, can't show two hits on 004
boolean       // useless for UI
```

Good (Part 1):

```text
Map<expenseId, List<Violation>>
Violation = { ruleId, ruleName, message }
```

Good (Part 1 + 2):

```text
EvaluationResult {
  Map<expenseId, List<Violation>> expenseViolations
  Map<tripId,    List<Violation>> tripViolations
}
```

Why this shape:

- Expenses API can render review badges per line item **and** per trip
- Multiple companies share one schema
- Auditable / explainable
- Additive when Part 2 arrives — don't break Part 1 clients if you version or keep expense map stable

---

## 4. Design that passes the extensibility bar

### Strategy + scope

```text
ExpensePolicyRule
  id(), name(), scope() → EXPENSE | TRIP
  evaluateExpense(Expense) → Optional<Violation>
  evaluateTrip(tripId, List<Expense>) → Optional<Violation>

BanFieldRule          // airfare / entertainment / vendor bans
MaxAmountRule         // global $250 or filtered restaurant $75
TripTotalMaxRule      // trip sum ≤ $2000
TripFieldSumMaxRule   // meals / vendor_type aggregates
```

Engine loop:

1. Parse maps → `Expense` (`BigDecimal` amount)
2. Split rules by scope
3. For each expense × expense-rule → collect violations (**no short-circuit**)
4. Group expenses by `trip_id`
5. For each trip × trip-rule → collect violations
6. Return `EvaluationResult`

### Why this beats `if/else` soup

- New rule type = new class, not a new `switch` arm in the engine
- API can deserialize JSON → concrete rule instances later
- Managers pass any subset without redeploying evaluator code
- Trip rules share one grouping pass (don't re-sum per rule naively at scale — cache aggregates if many trip rules)

### Interview trap: string amount compare

```java
"999".compareTo("1000") > 0  // true — WRONG
new BigDecimal("999").compareTo(new BigDecimal("1000")) > 0  // false — RIGHT
```

If starter code uses string/`Double` compare, **fix that first**.

---

## 5. Walk the sample out loud

| Id | Why flagged |
|---|---|
| 001 | restaurant but $49.99 ≤ 75 → **pass** |
| 002 | retailer $125 ≤ 250 → **pass** |
| 003 | restaurant $153 > 75 → **R1** |
| 004 | airfare → **R2**; $1996 > 250 → **R4** |
| 005/006 | restaurant under $75 → **pass** as expenses |
| 007 | entertainment → **R3** |
| Trip 001 | $174.99 ≤ 2000; no meals sum over → **pass** |
| Trip 002 | total $2206.08 → **R5**; meals $210.08 → **R6** |
| Trip 003 | only entertainment expense; trip totals under limits → expense flagged, trip ok |

---

## 6. Complexity & scale talking points

- Let `E` = expenses, `R_e` = expense rules, `R_t` = trip rules, `T` = trips  
  Time ≈ `O(E·R_e + E + T·R_t)` with one group-by pass.
- Cache per-trip aggregates (`total`, `sumByExpenseType`, `sumByVendorType`) if `R_t` grows.
- Determinism: stable iteration order (LinkedHashMap) for golden tests / UI.
- Multi-tenant: rules are an argument — no global mutable policy store in the screen.
- Follow-up: incremental re-eval when one expense is edited (invalidate that trip's aggregates only).

---

## 7. Spoken 60-second outline

> "Expenses come in as string maps. I'll parse amounts to BigDecimal and model rules as Strategy objects with an EXPENSE or TRIP scope so we can add airfare bans, max amounts, and trip aggregates without rewriting the evaluator.  
> Return type is an EvaluationResult with expenseViolations and tripViolations maps — each value is a list so one expense can carry multiple hits.  
> Engine: evaluate all expense rules with no short-circuit, group by trip_id once, then run trip rules. Thresholds are exclusive. Sample should flag 003, 004 (twice), 007, and trip 002 twice."

Then code Part 1 end-to-end, run the sample mentally, then add Part 2.

---

## 8. Follow-ups Rippling likes

| Follow-up | Direction |
|---|---|
| Composite rules (AND/OR) | Composite pattern wrapping child rules; short-circuit OR for perf |
| Rule priority / hard-block vs warn | Add `severity` on Violation; still collect all for audit |
| API to create rules | Persist `RuleDefinition` JSON; factory maps type → class |
| Multi-currency | Normalize to policy currency before compare |
| Real-time auth vs batch review | Same evaluator; sync path may evaluate only cheap expense rules |
| Testing | Golden sample + numeric-order unit test + boundary at threshold |

---

## 9. Files in this repo

| File | Role |
|---|---|
| `RuleEngine.java` | `evaluateRules` + default travel policies |
| `ExpensePolicyRule.java` | Strategy interface |
| `BanFieldRule` / `MaxAmountRule` | Part 1 |
| `TripTotalMaxRule` / `TripFieldSumMaxRule` | Part 2 |
| `EvaluationResult` / `Violation` | API return shape |
| `SampleData` / `ExpenseRulesDemo` | Canonical sample + printable report |
| `RuleEngineTest` | Expected flags, multi-violation, numeric compare, boundaries |

---

## 10. Day-of checklist

1. Agree return type + assumptions (2–3 min)
2. Sketch Rule interface + EvaluationResult (2 min)
3. Implement parsing + Part 1 rules + engine loop
4. Print/assert sample → 003, 004×2, 007
5. Add grouping + Part 2 → trip 002×2
6. Call out BigDecimal, no short-circuit, extensibility
7. If time: unit tests for numeric compare and `$75` boundary
