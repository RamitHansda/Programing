# Staff-level LLD interview playbook (Java)

This folder is a **structured interview library**: each document is written as if you were explaining the design to a senior panel—**requirements, invariants, boundaries, concurrency, failure modes, tests**, and **where design patterns earn their keep** (not pattern theater).

## How to use

1. Pick one problem. Spend **5 minutes** clarifying scope with the interviewer (single machine vs distributed, sync vs async, persistence or in-memory).
2. List **invariants** and **failure modes** before class names.
3. Sketch **public APIs** (interfaces) and **one happy path** sequence diagram.
4. Only then add **patterns** where they reduce coupling or encode real variation.

## Index

| # | Topic | Primary patterns | File |
|---|--------|------------------|------|
| 1 | Rate limiter | Strategy, optional Template Method | [01-rate-limiter.md](./01-rate-limiter.md) |
| 2 | Parking lot | Strategy, Factory, domain services | [02-parking-lot.md](./02-parking-lot.md) |
| 3 | Elevator controller | State, Strategy | [03-elevator-controller.md](./03-elevator-controller.md) |
| 4 | Splitwise-style ledger | Strategy, immutable value types | [04-splitwise-ledger.md](./04-splitwise-ledger.md) |
| 5 | Vending machine | State, Command (optional) | [05-vending-machine.md](./05-vending-machine.md) |
| 6 | Logger framework | Chain of Responsibility | [06-logger-framework.md](./06-logger-framework.md) |
| 7 | In-process cache | Strategy, optional Decorator | [07-cache-eviction.md](./07-cache-eviction.md) |
| 8 | Movie seat booking | State, Saga-style compensation (conceptual) | [08-movie-seat-booking.md](./08-movie-seat-booking.md) |
| 9 | Notification dispatcher | Strategy, Observer / event bus | [09-notification-dispatcher.md](./09-notification-dispatcher.md) |
| 10 | In-memory file system | Composite, Iterator | [10-in-memory-filesystem.md](./10-in-memory-filesystem.md) |
| 11 | Chess / board game | Strategy, Template Method, Memento | [11-chess-board-game.md](./11-chess-board-game.md) |
| 12 | Meeting scheduler | Strategy, domain services, value objects | [12-meeting-scheduler.md](./12-meeting-scheduler.md) |
| 13 | Shopping cart / checkout | Builder, Strategy, Saga (conceptual) | [13-shopping-cart-checkout.md](./13-shopping-cart-checkout.md) |
| 14 | Pub-sub message hub | Observer, Mediator, Strategy | [14-pub-sub-message-hub.md](./14-pub-sub-message-hub.md) |

## Related material in this repo

- Pattern-to-problem catalog: [../../LLD_PROBLEMS_BY_DESIGN_PATTERN.md](../../LLD_PROBLEMS_BY_DESIGN_PATTERN.md)
- Pattern usage notes: [../../DESIGN_PATTERNS_WHEN_TO_USE_AND_EXAMPLES.md](../../DESIGN_PATTERNS_WHEN_TO_USE_AND_EXAMPLES.md)
- Deeper dives elsewhere under `docs/lld/` (Splitwise, train booking, connection pool, etc.)—this playbook is **interview-shaped**; those folders may go deeper on one domain.

## Staff bar: what interviewers listen for

- **Explicit tradeoffs**: e.g. fairness vs throughput in elevators; exact vs approximate rate limiting.
- **Concurrency story**: which data is shared, what locks or structures you use, what you *refuse* to synchronize globally.
- **Testability**: pure domain vs I/O boundaries; fakes for time and randomness.
- **Evolvability**: where new behaviors plug in without editing a giant `switch`.

If you want additional files (e.g. chess, pub-sub, ride matching, meeting scheduler), extend this index using the same section template as the numbered guides.
