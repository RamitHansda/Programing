# Staff-level LLD interview playbook (Java)

This folder is **interview-ready**: every problem guide opens with a **timeboxed snapshot** (what to say first, what to draw, likely probes). Deep sections below that snapshot are your reference during prep, not text you read aloud in the room.

**Before mock day:** read [INTERVIEW-RUNBOOK.md](./INTERVIEW-RUNBOOK.md) once (shared 45-minute clock, opener script, staff signals, red flags).

**Domain models:** [00-domain-models-and-layers.md](./00-domain-models-and-layers.md) explains conceptual vs domain vs storage and whiteboard order.

## How to use (prep vs live)

**Prep (the night before):** Read snapshot + domain model + invariants + concurrency + testing for 2–3 problems.

**Live round:** Follow the snapshot’s **clock** and **whiteboard order**; use clarifying questions from each file; if probed, jump to the matching section (failure modes, follow-ups).

1. **~5 min** — Align scope (single JVM vs distributed, sync vs async, persistence or not).
2. **~10 min** — Conceptual nouns + **domain model** (aggregates, entities, VOs, relationships).
3. **~10 min** — **Invariants** + **public APIs** (Java interfaces) + one happy-path sequence.
4. **~10 min** — **Concurrency**, consistency, idempotency, explicit failure policy.
5. **Rest** — Tests, extensions, tradeoff recap.

## Index

| # | Topic | Primary patterns | File |
|---|--------|------------------|------|
| — | **Interview runbook (read first)** | Timebox, opener, signals | [INTERVIEW-RUNBOOK.md](./INTERVIEW-RUNBOOK.md) (each `NN-*.md` has **Interview-ready snapshot** at top) |
| 0 | Domain models & layers (meta) | Aggregates, entities, VOs | [00-domain-models-and-layers.md](./00-domain-models-and-layers.md) |
| 1 | Rate limiter | Strategy, optional Template Method | [01-rate-limiter.md](./01-rate-limiter.md) |
| 2 | Parking lot | Strategy, Factory, domain services | [02-parking-lot.md](./02-parking-lot.md) |
| 3 | Elevator controller | State, Strategy | [03-elevator-controller.md](./03-elevator-controller.md) |
| 4 | Splitwise-style ledger | Strategy, immutable value types | [04-splitwise-ledger.md](./04-splitwise-ledger.md) |
| 5 | Vending machine | State, Command (optional) | [05-vending-machine.md](./05-vending-machine.md) |
| 6 | Logger framework | Chain of Responsibility | [06-logger-framework.md](./06-logger-framework.md) |
| 7 | In-process cache | Strategy, optional Decorator | [07-cache-eviction.md](./07-cache-eviction.md) |
| 8 | Movie seat booking | State, Saga-style compensation (conceptual) | [08-movie-seat-booking.md](./08-movie-seat-booking.md) |
| 9 | Notification dispatcher | Strategy, Observer / event bus | [09-notification-dispatcher.md](./09-notification-dispatcher.md) |
| 10 | In-memory file system | Composite, Iterator; `cd`/`pwd` | [10-in-memory-filesystem.md](./10-in-memory-filesystem.md) · [impl](../filesystem/README.md) |
| 11 | Chess / board game | Strategy, Template Method, Memento | [11-chess-board-game.md](./11-chess-board-game.md) |
| 12 | Meeting scheduler | Strategy, domain services, value objects | [12-meeting-scheduler.md](./12-meeting-scheduler.md) |
| 13 | Shopping cart / checkout | Builder, Strategy, Saga (conceptual) | [13-shopping-cart-checkout.md](./13-shopping-cart-checkout.md) |
| 14 | Pub-sub message hub | Observer, Mediator, Strategy | [14-pub-sub-message-hub.md](./14-pub-sub-message-hub.md) |
| 15 | Event management system | State, Saga-style hold/confirm | [15-event-management-system.md](./15-event-management-system.md) · [full script](../event-management-system/INTERVIEW_SCRIPT.md) |

## Related material in this repo

- Pattern-to-problem catalog: [../../LLD_PROBLEMS_BY_DESIGN_PATTERN.md](../../LLD_PROBLEMS_BY_DESIGN_PATTERN.md)
- Pattern usage notes: [../../DESIGN_PATTERNS_WHEN_TO_USE_AND_EXAMPLES.md](../../DESIGN_PATTERNS_WHEN_TO_USE_AND_EXAMPLES.md)
- Deeper dives elsewhere under `docs/lld/` (Splitwise, train booking, connection pool, etc.)—this playbook is **interview-shaped**; those folders may go deeper on one domain.

## Staff bar: what interviewers listen for

- **A clear domain model**: aggregates, identity, and where state transitions are legal—before implementation detail.
- **Explicit tradeoffs**: e.g. fairness vs throughput in elevators; exact vs approximate rate limiting.
- **Concurrency story**: which data is shared, what locks or structures you use, what you *refuse* to synchronize globally.
- **Testability**: pure domain vs I/O boundaries; fakes for time and randomness.
- **Evolvability**: where new behaviors plug in without editing a giant `switch`.

If you want additional files (e.g. chess, pub-sub, ride matching, meeting scheduler), extend this index using the same section template as the numbered guides.
