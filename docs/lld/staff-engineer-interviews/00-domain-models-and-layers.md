# Domain models in LLD (why they matter and what to draw)

## Interview-ready snapshot (meta — use in any LLD)

**Say first (≈20s):** “I will separate **conceptual nouns**, **domain model** (who owns state), and **persistence** only if you want storage. I will state **invariants** on the model before writing lots of Java.”

**Whiteboard order:** scope → nouns → aggregates/VOs + relationships → invariants → APIs → concurrency → tests.

**Likely probes:** “Where is the consistency boundary?” “What is immutable?” “What would break under two threads?”

Pair with [INTERVIEW-RUNBOOK.md](./INTERVIEW-RUNBOOK.md) for the full clock.

---

Staff-level LLD is not “patterns first.” It is **behavior + data that must stay true over time**. The **domain model** is where you show you understand that: who owns what state, what the nouns are, and which relationships are **1:1**, **1:N**, or **composition**.

## Why the first playbook pass underplayed “models”

The numbered guides leaned on **requirements, invariants, and APIs** because many candidates jump straight to classes without stating **what exists in the problem world**. That was meant to correct the *order* of thinking, not to skip modeling. **You should still name and relate your core nouns explicitly**—usually right after requirements (or in parallel on the whiteboard).

## Three useful “models” (do not conflate them)

| Layer | What it is | Interview use |
|-------|------------|-----------------|
| **Conceptual model** | Business language: “Booking”, “Hold”, “Ledger line” — no Java yet. | Align with interviewer; catch wrong assumptions. |
| **Domain model** (object / DDD-ish) | Aggregates, entities, value objects, **invariants** on those types. | Where behavior should live; what must be consistent after each operation. |
| **Physical / storage model** (optional) | Tables, indexes, Redis keys—only if they ask persistence. | Honesty about races, idempotency, TTL. |

For a **45-minute LLD**, you usually need **conceptual + domain** clearly; persistence can be one paragraph unless the prompt is “design the DB too.”

## What to put in a “Domain model” section (template)

1. **Aggregates** (consistency boundary): one transaction updates one aggregate at a time, ideally.
2. **Entities** (identity matters): `Order`, `ElevatorCab`, `SeatHold`.
3. **Value objects** (replace, don’t mutate): `Money`, `TimeInterval`, `SeatId`, `Move` (chess).
4. **Domain services** when behavior does not naturally sit on one aggregate: `BalanceCalculator`, `SlotFinder`, `ChangeMaker`.
5. **Relationships**: composition (“Ticket **references** Spot”), multiplicity (“Lot **has many** Levels”).
6. **Out of scope types**: `HttpClient`, `JdbcTemplate`—push to infrastructure; keep domain ignorant.

## Anti-patterns interviewers notice

- **Anemic domain**: entities are only getters/setters; all logic in a `*Manager` god class.
- **Entity–DB row 1:1** without saying **why** (sometimes fine for LLD, but then say “this is persistence-shaped, not domain-shaped”).
- **Missing lifecycle**: e.g. seat with no `HELD → BOOKED` story; elevator with no explicit **door vs motion** coupling.

## Order on the whiteboard (recommended)

1. Conceptual nouns and verbs (2 minutes).
2. **Domain model**: 5–10 types + relationships + invariants.
3. **Use cases** as sequences touching those types.
4. Public **Java interfaces** for application-facing API.
5. Patterns **only** where they clarify variation or structure.

## How this repo’s numbered guides use this

Each `NN-*.md` file includes a **Domain model** section: aggregates, entities, value objects, and what is intentionally **not** modeled. Cross-read with this page when prepping.

## Interview timebox (pair with [INTERVIEW-RUNBOOK.md](./INTERVIEW-RUNBOOK.md))

| When | Focus |
|------|--------|
| First 5 min | Scope + assumptions; write **conceptual** nouns only if helpful. |
| Next 10 min | **Domain model** table on board: aggregates, VOs, who owns mutable state. |
| Next 10 min | **Invariants** on the model; then **API** (interfaces). |
| Then | Concurrency, failures, tests—still tied back to **which type** owns which transition. |

If you only have **20 minutes** total, do: assumptions (2 min) → domain model + one invariant (8 min) → one interface + concurrency (10 min). Skip deep pattern names unless they clarify variation.
