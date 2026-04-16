# LLD interview runbook (Java, 45–60 minutes)

Use this with any guide in `01`–`14`. Each problem file starts with an **Interview-ready snapshot** tuned to that prompt; this page is the **shared clock and behaviors** that panels expect.

## What “interview-ready” means here

- You can **open cold** with a short goal statement and **default assumptions**.
- You have a **timeboxed sequence** so you do not spend 25 minutes on APIs before drawing the model.
- You have **whiteboard order** so you never stare at a blank board wondering what to draw next.
- You have **probes** pre-answered so follow-ups feel prepared, not improvised from zero.

## First 60 seconds (script template)

Say something shaped like this (adapt nouns to the problem):

1. “I will treat this as **[in-process / single JVM / sync]** unless you want distributed or async—I will call that boundary explicitly.”
2. “Core API I am designing toward: **[one sentence, e.g. tryAcquire / park / book].**”
3. “I will start with **domain types and invariants**, then **concurrency**, then **extensibility** (where algorithms or policies plug in).”

Then ask **two** clarifying questions that change the design (not trivia).

## 45-minute clock (adjust if they say 60)

| Minutes | Phase | What is on the board / in the IDE |
|--------:|--------|-------------------------------------|
| 0–5 | **Align** | Scope, assumptions, 2–3 clarifying Qs answered |
| 5–15 | **Model** | Nouns, aggregates, relationships, lifecycle (states if any) |
| 15–25 | **API** | 3–7 Java interfaces or public methods; one happy-path sequence |
| 25–35 | **Hard parts** | Concurrency, consistency, idempotency, failure policy |
| 35–42 | **Quality** | Tests you would write; one extension (“if we added X”) |
| 42–45 | **Close** | Recap tradeoffs; ask their constraint for v2 |

If coding is required, **compress** model to 10 minutes and shift 10 minutes to a minimal vertical slice (one path + tests).

## Whiteboard checklist (every problem)

1. **Boundary**: what is in / out of scope (persistence, UI, multi-node).
2. **Domain model**: boxes for aggregates + arrows for owns / references.
3. **Lifecycle** (if any): state names on edges or a small table.
4. **Public API**: interface names only first; signatures if time.
5. **Concurrency**: which map / lock / per-aggregate serialization.
6. **One edge case**: double-click, duplicate request, expiry, key explosion, etc.

## Staff-level signals (say these phrases, mean them)

- “**Consistency boundary** is here: …”
- “**Invariant** I will not violate: …”
- “**Idempotency** matters if … so I would use …”
- “I would inject **clock** / **random** / **I/O** for tests.”
- “This is **exact** under single-thread; under contention I accept **approximate** behavior because …”

## Red flags (avoid)

- Classes before **requirements or invariants**.
- **Singleton** “because there is only one” without lifecycle or test story.
- **God Manager** with all logic and DTO entities.
- Silent **double booking**, **negative balance**, **negative tokens**, or **overshoot** on limits.

## If they push “production”

Acknowledge in one minute: **persistence**, **leader election**, **Redis**, **outbox**, **metrics**—then say which part you would still **LLD in Java** (domain + service + ports) vs **HLD** (sharding, SLO). Do not derail the whole round into Kafka unless they ask.

## After the round (self-check)

- Did you state **one** explicit tradeoff you chose?
- Did you name **one** test that would catch your worst bug?
- Did you separate **domain** from **infrastructure** at least once?

## Index of problem snapshots

Use the table in [README.md](./README.md); each linked file begins with **Interview-ready snapshot**.
