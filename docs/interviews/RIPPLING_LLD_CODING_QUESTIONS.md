# Rippling — LLD / Machine Coding Round Questions (Similar to Problem Solving + LLD + Coding)

Research from candidate reports (DevBrainiac, Roundz, Medium, PracHub, 1Point3Acres, Glassdoor-linked writeups). Rippling’s closest equivalent to a **90-min Problem Solving / LLD / Coding** round is usually labeled **LLD**, **Machine Coding**, or **Coding + Design Thinking** (often **45–60 min**, sometimes multi-part and extended).

> Rippling skews away from classic Parking Lot / BookMyShow. They prefer **HR / payroll / expense / logistics-domain OOD**: rules engines, delivery cost/payroll, aggregations, schedulers, loggers. Expect **working code**, then extensibility follow-ups.

---

## 1. How Rippling’s round maps to “LLD + Coding (90 Mins)”

| Rippling label | What it is | Duration (reported) |
| --- | --- | --- |
| Technical Screening — Coding + Design Thinking | Multi-part business API; code + scale discussion | ~60 min |
| LLD / Machine Coding | OOP design + implement + edge cases | ~45–60 min |
| Rules Engine Design + Coding | Strategy/OCP rules; sometimes code + design | ~60 min |
| Coding (AI allowed, senior) | Task scheduler / filter-sort; AI may be expected | ~60 min |

**What they grade:** clarify requirements → extensible OOP → runnable APIs → money/time correctness → follow-ups (idempotency, aggregates, scale).

---

## 2. Highest-frequency Rippling LLD/coding prompts

### A. Delivery Cost / Driver Payroll Dashboard ★★★ (most reported)

**Also called:** Food Delivery Cost & Driver Payout, Delivery Billing System, Delivery Cost Dashboard.

**Part I — Cost tracking**

```
addDriver(driverId, usdHourlyRate)
recordDelivery(driverId, startTime, endTime)   // epoch seconds; duration ≤ 3h
getTotalCost()                                 // Σ rate × hours
```

- Overlapping deliveries for same driver are **paid independently** (no merge for pay).
- Prefer integer cents / `BigDecimal`; avoid float drift.
- Keep a **running aggregate** so `getTotalCost()` is O(1) under high read load.

**Part II — Payments by cutoff**

```
payUpTo(payTime)           // mark deliveries with endTime ≤ payTime as paid (idempotent)
getTotalCostUnpaid()       // unpaid remaining
```

- Prefer a **monotonic paid-through cutoff** over mutating every row.
- Discuss straddling cutoffs if interviewer asks (partial pay).

**Part III — Concurrency analytics**

```
maxSimultaneousDriversInPast24Hours(now) → int
```

- Distinct **drivers**, not deliveries.
- Per-driver interval union, then **sweep line** on start/end events in the last 24h.
- Half-open intervals `[start, end)`.

**Reported at:** SDE-2 screening, Senior SWE Bengaluru (code ~30 min + Q&A ~30 min), OA/online review variants.

---

### B. Expense / Corporate Card Rules Engine ★★★ (signature Rippling question)

**Also called:** Expense Rules Engine, Rule Evaluation Engine for Spending Policy Violations, Travel/Reimbursement rules.

**Part 1 — Per-expense rules**

Expenses arrive as `Map<String, String>`:

| expense_id | trip_id | amount_usd | expense_type | vendor_type | vendor_name |
| --- | --- | --- | --- | --- | --- |

Base policies:

1. No restaurant expense over **$75** (`vendor_type == restaurant`)
2. No **airfare** (`expense_type`)
3. No **entertainment**
4. No single expense over **$250**

Implement:

```
evaluateRules(rules, expenses) → ReturnType
```

Discuss **return type before coding** (list of structured violations, not `Map<id, bool>`). Keep all violations for an expense (e.g. airfare + over $250 both appear).

**Part 2 — Trip / aggregate rules**

- Trip total ≤ **$2000**
- Trip meals total ≤ **$200**

One-pass: expense rules + group-by trip aggregates. Backward-compatible return covering expense-level and trip-level violations.

**Design goals they push:**

- Rules as **data** (Strategy / OCP) — new rule without rewriting engine
- AND / OR / NOT composition
- Explainability (rule id, reason, threshold, observed value)
- Deterministic ordering, money as decimal, parse/validate string inputs
- Multi-tenant / scale discussion for senior levels

**Reported at:** SDE-2 Round 4, technical screens, many 1Point3Acres threads.

---

### C. Generic GroupBy / Filter / Aggregate on employee data ★★

```
Given employees → group by department, sum salary
Extend: groupBy(any key) + aggregate(SUM|MIN|MAX|AVG|…)
Add: filterBy(predicate)
```

Tests extensible collection pipelines vs hardcoding. Common **screening LLD**.

---

### D. 2D Canvas — draw / move rectangles ★★

```
draw(rectangle)
move(rectangleId, dx, dy)
```

Follow-ups:

- Rectangle moved outside bounded grid
- Switch to **infinite / sparse** grid (coord → HashMap, not 2D array)

Reported as **LLD + light DS/Algo**, ~60 min.

---

### E. Task Scheduler (filter / sort / parent-child) ★★

Reported variants:

1. Dedupe by `(description, dueDate)` → drop completed → sort by priority → **subtasks immediately after parent**
2. Filter + sort task manager
3. Task scheduler with parent-child dependencies

Senior loops may **explicitly allow / expect AI** (Cursor / Claude / Codex) while you still own design + tests.

---

### F. Document Analyzer (machine coding) ★

SDE-1 report (2026 writeup): **document analyzer–type** problem — design + implement with OOP, working solution, clarify requirements first. Exact feature list not published; treat as text/doc processing + clean class design.

---

### G. Configurable Logger ★

```
Logger with handler pipeline
Message transforms (truncate / uppercase / remove substring / store-only)
Keyword search over stored logs
```

Extensible pipeline (Chain of Responsibility / Decorator).

---

### H. Music / analytics mini-system ★

Reported SDE-2 “coding + product design”:

```
add_song(songId)
play_song(userId, songId)
print_analytics()                 // unique listeners per song, not raw play count
print_recently_played(userId, k)
```

Data modeling: `Map<SongId, Set<UserId>>`, per-user recency (Deque / LinkedHashMap).

---

### I. Other reported OOD / coding prompts

| Prompt | Notes |
| --- | --- |
| Sliding window rate limiter | Strategy + thread-safety discussion |
| Poker / Camel Cards hand ranking | Extensible hand types (OOD) |
| Stack Overflow–like REST service | Simple REST machine coding |
| Port allocation manager (EM track) | O(1) get/release |
| Excel-like spreadsheet with cascading recompute (EM) | Dependency graph / recompute |
| Merge overlapping intervals | Often as payroll helper |

---

## 3. Related HLD (not the same round, but often paired)

Rippling loops usually also include HLD after LLD/coding:

- **Event rollup aggregation** — client batching, idempotency, 15m / 1h / 1d rollups
- **News aggregator** — poll publishers, feeds, Redis sorted sets, dedupe
- Metrics / multi-tenant SaaS designs

Prepare these separately from the coding/LLD hour.

---

## 4. Practice priority for a Rippling-style LLD+coding hour

| Priority | Problem | Why |
| --- | --- | --- |
| P0 | Delivery cost + payUpTo + max simultaneous drivers | Highest hit rate; money + intervals + sweep line |
| P0 | Expense rules engine (per-expense + trip aggregates) | Signature Rippling product problem |
| P1 | Generic groupBy / filter / aggregate | Common screen |
| P1 | Task scheduler (dedupe / priority / parent-child) | Recent senior / AI-coding reports |
| P2 | Canvas draw/move + sparse infinite grid | Edge-case + DS thinking |
| P2 | Logger pipeline **or** document analyzer | Less frequent but same skills |

**Timed drill (60–75 min):**

1. Clarify APIs + money/time assumptions (5 min)  
2. Implement Part 1 happy path (20–25 min)  
3. Part 2 extension (15–20 min)  
4. Tests + Part 3 sketch or implement (10–15 min)  
5. Extensibility / scale narration (5 min)

---

## 5. Rippling vs Flipkart/PhonePe/Zepto machine coding

| | Rippling | Flipkart / PhonePe / Zepto-style |
| --- | --- | --- |
| Domain | Payroll, expenses, HR rules, logistics billing | Orders, inventory, booking, games |
| Shape | Multi-part API that **grows** mid-interview | Spec sheet with mandatory + bonus features |
| Patterns | Strategy/OCP rules, aggregates, sweep line | State machines, reservation, assignment queues |
| Money/time | First-class (cents, intervals, cutoffs) | Secondary to stock/order state |
| Duration | Often 45–60 min + deep Q&A | Often 90–120 min implement |

Same **prep muscles** as your 90-min LLD+coding guide (entities, APIs, rules, idempotency, concurrency) — different **problem surface**.

---

## 6. Sources

- DevBrainiac — Rippling SDE-2 offer writeup (delivery cost, music analytics, expense rules)
- Roundz #154 — employee groupBy/filter LLD; canvas draw/move
- Medium — Rippling SDE-1 2026 (document analyzer machine coding)
- PracHub / 1Point3Acres — delivery cost dashboard, expense rules engine, task scheduler, logger
- Candidate reports noting AI-allowed coding on senior loops

---

## 7. Bottom line

For a **Rippling-similar LLD + coding round**, practice these two until timed and demoable:

1. **Delivery cost / driver payroll** (cost → payUpTo → max concurrent drivers)  
2. **Expense rules engine** (per-expense → trip aggregates → extensible Rule interface)

Then add **groupBy/filter**, **task scheduler**, and **canvas/logger** as coverage.
