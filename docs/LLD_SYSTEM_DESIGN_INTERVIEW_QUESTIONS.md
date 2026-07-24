# LLD System Design Interview Questions — master question bank

A single, categorized index of every **Low-Level Design (LLD)** interview question covered in this repo, plus the common questions asked across companies that aren't written up yet (with a suggested pattern to reach for). Use this page as the entry point; it links out to the deep-dive guide for each problem.

**Start here if you're prepping cold:**

1. Read [staff-engineer-interviews/INTERVIEW-RUNBOOK.md](./lld/staff-engineer-interviews/INTERVIEW-RUNBOOK.md) once — shared 45-minute clock, opener script, staff-level signals.
2. Read [staff-engineer-interviews/00-domain-models-and-layers.md](./lld/staff-engineer-interviews/00-domain-models-and-layers.md) — how to think in aggregates/entities/value objects before touching code.
3. Pick 2–3 problems below matching your target level and work through the linked guide's snapshot → domain model → concurrency → tests.

---

## How to use this bank

- **✅ Full guide** = a complete write-up exists in this repo (interview snapshot, domain model, patterns, Java shape, concurrency, failure modes, tests, follow-ups).
- **📄 Deep-dive doc** = a dedicated design doc exists elsewhere in `docs/lld/` (often heavier / more production-shaped than an interview-timeboxed guide).
- **💻 Code** = a working implementation exists under `src/main/java/`.
- **🕳️ Gap** = not yet covered in this repo; a suggested primary pattern is listed so you can still prep the shape of the answer.

---

## 1. Concurrency & in-process infrastructure

Classic "build a mini-library" prompts. Interviewers probe thread-safety, lifecycle, and pluggable policy.

| Question | Status | Primary pattern(s) | Where |
|---|---|---|---|
| Rate limiter (token bucket / sliding window) | ✅📄💻 | Strategy | [staff-engineer-interviews/01](./lld/staff-engineer-interviews/01-rate-limiter.md) · `src/main/java/interview/fampay/ratelimiter/`, `src/main/java/lld/tokenbucketfilter/` |
| Worker pool / thread pool manager | ✅💻 | Strategy, Producer-Consumer | [staff-engineer-interviews/15](./lld/staff-engineer-interviews/15-worker-pool-manager.md) · `src/main/java/threads/workerpool/` |
| In-process LRU / eviction cache | ✅💻 | Strategy (eviction policy) | [staff-engineer-interviews/07](./lld/staff-engineer-interviews/07-cache-eviction.md) · `src/main/java/lld/lrucache/`, `src/main/java/interview/fampay/lrucache/` |
| Database connection pool manager | ✅📄 | Singleton (scoped), Object Pool | [docs/lld/database_connection_pool/](./lld/database_connection_pool/README.md) · `src/main/java/lld/database_connection_pool/` |
| Logger framework | ✅ | Chain of Responsibility | [staff-engineer-interviews/06](./lld/staff-engineer-interviews/06-logger-framework.md) · `src/main/java/lld/logger/`, `src/main/java/lld/concurrentlogger/` |
| In-memory key-value store with TTL | 💻 | Strategy (eviction), Facade | `src/main/java/lld/ttl_hashmap/`, `src/main/java/lld/store/` — write-up 🕳️ |
| Producer-consumer / bounded buffer | 💻 | Producer-Consumer | `src/main/java/lld/producer_consumer/`, `src/main/java/threads/boundedbuffer/` — write-up 🕳️ |
| Pub-sub / message broker (in-process) | ✅📄💻 | Observer, Mediator, Strategy | [staff-engineer-interviews/14](./lld/staff-engineer-interviews/14-pub-sub-message-hub.md) · [docs/lld/messagebroker/](./lld/messagebroker/README.md) · `src/main/java/lld/messagebroker/` |
| Hit counter / sliding-window request counter | 💻 | — | `src/main/java/interview/fampay/hitcounter/` — write-up 🕳️ |
| Distributed unique ID generator (Snowflake-style) | 📄 | — | [docs/UNIQUE_ID_GENERATOR_SYSTEM_DESIGN.md](./UNIQUE_ID_GENERATOR_SYSTEM_DESIGN.md) *(HLD-leaning)* |

## 2. Booking, scheduling & real-world simulations

Prompts that hinge on state machines, invariants, and domain services.

| Question | Status | Primary pattern(s) | Where |
|---|---|---|---|
| Parking lot | ✅ | Strategy, Factory | [staff-engineer-interviews/02](./lld/staff-engineer-interviews/02-parking-lot.md) · `src/main/java/lld/parking_lot/` |
| Elevator controller | ✅ | State, Strategy | [staff-engineer-interviews/03](./lld/staff-engineer-interviews/03-elevator-controller.md) |
| Vending machine | ✅📄 | State | [staff-engineer-interviews/05](./lld/staff-engineer-interviews/05-vending-machine.md) · [docs/lld/vendingmachine/](./lld/vendingmachine/README.md) · `src/main/java/lld/vendingmachine/` |
| Movie/theatre seat booking | ✅ | State, Saga-style compensation | [staff-engineer-interviews/08](./lld/staff-engineer-interviews/08-movie-seat-booking.md) |
| Meeting/room scheduler | ✅ | Domain services, value objects | [staff-engineer-interviews/12](./lld/staff-engineer-interviews/12-meeting-scheduler.md) |
| Train/flight seat booking with concurrency | ✅📄 | Strategy, optimistic locking | [docs/lld/trainbooking/](./lld/trainbooking/README.md) · `src/main/java/lld/trainbooking/` |
| Snake & ladder / board game engine | 💻 | State, Strategy | `src/main/java/lld/snakeladder/` — write-up 🕳️ |
| Chess / generic board game | ✅ | Strategy, Template Method, Memento | [staff-engineer-interviews/11](./lld/staff-engineer-interviews/11-chess-board-game.md) |
| Tic-tac-toe | 💻 | State, Strategy | `src/main/java/lld/tictactoe/` — write-up 🕳️ |
| Ride-sharing dispatch (Uber/Lyft-style matching) | 🕳️ | Strategy (matching algorithm), State (ride lifecycle) | — |
| Restaurant table reservation / food delivery order flow | 🕳️ | State, Saga | — |

## 3. E-commerce, payments & ledgers

| Question | Status | Primary pattern(s) | Where |
|---|---|---|---|
| Splitwise-style expense splitting & ledger | ✅📄 | Strategy, immutable value types | [staff-engineer-interviews/04](./lld/staff-engineer-interviews/04-splitwise-ledger.md) · [docs/lld/splitwise/](./lld/splitwise/README.md) · `src/main/java/lld/splitwise/` |
| Shopping cart / checkout flow | ✅ | Builder, Strategy, Saga (conceptual) | [staff-engineer-interviews/13](./lld/staff-engineer-interviews/13-shopping-cart-checkout.md) |
| Order book / matching engine | ✅📄 | Command, Strategy | [docs/lld/order-book/](./lld/order-book/HLD.md) · `src/main/java/lld/orderbook/` |
| Internal ledger (double-entry accounting) | 📄 | Immutable events | [docs/lld/COINBASE_INTERNAL_LEDGER_HLD.md](./lld/COINBASE_INTERNAL_LEDGER_HLD.md) |
| Pricing engine (dynamic pricing rules) | 📄💻 | Strategy, Chain of Responsibility | [docs/data_eng/DISTRIBUTED_PRICING_ENGINE_HLD.md](./data_eng/DISTRIBUTED_PRICING_ENGINE_HLD.md) · `src/main/java/lld/pricingengine/` |
| Invoice generation at scale | 📄 | — | [docs/INVOICE_GENERATION_1M_CUSTOMERS_HLD_STAFF_ENG.md](./INVOICE_GENERATION_1M_CUSTOMERS_HLD_STAFF_ENG.md) *(HLD-leaning)* |
| Vending-machine-style inventory + payment | ✅ | State | see Vending machine above |

## 4. Social, content & notification systems

| Question | Status | Primary pattern(s) | Where |
|---|---|---|---|
| Social media feed (post + timeline, fan-out) | ✅ | Strategy, Observer, Facade | [staff-engineer-interviews/17](./lld/staff-engineer-interviews/17-social-media-feed.md) |
| Notification dispatcher (multi-channel) | ✅📄 | Strategy, Observer/event bus | [staff-engineer-interviews/09](./lld/staff-engineer-interviews/09-notification-dispatcher.md) · [docs/lld/notification-system/](./lld/notification-system/NOTIFICATION_SYSTEM_HLD.md) |
| Webhook delivery system (retries, backoff) | 📄 | Strategy, Chain of Responsibility | [docs/lld/webhook-delivery-system/](./lld/webhook-delivery-system/WEBHOOK_DELIVERY_SYSTEM_HLD.md) |
| URL shortener | ✅ | Strategy, Repository | [staff-engineer-interviews/16](./lld/staff-engineer-interviews/16-url-shortener.md) |
| Chat room / real-time messaging | ✅ (pattern-level) | Mediator | [LLD_PROBLEMS_BY_DESIGN_PATTERN.md #16](./LLD_PROBLEMS_BY_DESIGN_PATTERN.md) |
| Comment/thread system with nesting | 🕳️ | Composite | — |

## 5. Platform & developer-tooling systems

| Question | Status | Primary pattern(s) | Where |
|---|---|---|---|
| CI/CD pipeline executor (Jenkins-like) | ✅📄💻 | Command, State, Strategy | [docs/lld/jenkinslike/](./lld/jenkinslike/HLD.md) · `src/main/java/lld/jenkinslike/` |
| Multiplayer game session system | ✅📄💻 | State, Observer | [docs/MULTIPLAYER_SESSION_SYSTEM_HLD.md](./MULTIPLAYER_SESSION_SYSTEM_HLD.md) · `src/main/java/lld/multiplayer/` |
| Protocol adapters (multi-protocol gateway) | 📄💻 | Adapter, Strategy | [docs/lld/protocol-adapters/](./lld/protocol-adapters/HLD.md) · `src/main/java/lld/protocoladapters/` |
| Secure API gateway | 📄 | Chain of Responsibility, Facade | [docs/lld/api-gateway/](./lld/api-gateway/SECURE_API_GATEWAY_DESIGN.md) |
| Telemetry / metrics collection system | 📄 | Observer, Strategy | [docs/lld/telemetry-system/](./lld/telemetry-system/HLD.md) |
| In-memory file system (Composite tree) | ✅ | Composite, Iterator | [staff-engineer-interviews/10](./lld/staff-engineer-interviews/10-in-memory-filesystem.md) |
| Orchestrator (workflow engine) design space | 📄 | Strategy | [docs/lld/ORCHESTRATOR_TYPES_STAFF_GUIDE.md](./lld/ORCHESTRATOR_TYPES_STAFF_GUIDE.md) |

## 6. Pattern-first drills

If you want to practice **recognizing which pattern fits which prompt** rather than depth on one domain, use the 22-problem, one-per-GoF-pattern set:

- [LLD_PROBLEMS_BY_DESIGN_PATTERN.md](./LLD_PROBLEMS_BY_DESIGN_PATTERN.md) — one bite-sized LLD problem per Creational/Structural/Behavioral pattern (Singleton → connection pool, Observer → stock alerts, Visitor → AST processing, etc.), with a quick-reference table at the end.
- [DESIGN_PATTERNS_WHEN_TO_USE_AND_EXAMPLES.md](./DESIGN_PATTERNS_WHEN_TO_USE_AND_EXAMPLES.md) — when to reach for each pattern and real-world examples, independent of any single interview prompt.

---

## Suggested prep tracks

**Track A — first-time LLD interview (any level):** Rate limiter → Parking lot → Vending machine → LRU cache → In-memory file system. Covers Strategy, State, Composite, and basic concurrency without heavy domain modeling.

**Track B — staff/senior track (deeper domain modeling + concurrency):** Splitwise ledger → Movie seat booking → Meeting scheduler → Pub-sub message hub → Worker pool manager. Covers idempotency, saga-style compensation, and explicit concurrency tradeoffs.

**Track C — "systems that scale" flavor (LLD with an HLD tail):** URL shortener → Social media feed → Notification dispatcher → Webhook delivery system. Each ends with a "what changes at scale" follow-up (distributed IDs, fan-out, caching, retries/backoff).

**Track D — pattern recognition drills:** Work through [LLD_PROBLEMS_BY_DESIGN_PATTERN.md](./LLD_PROBLEMS_BY_DESIGN_PATTERN.md) end-to-end; for each, draw the class diagram from memory before reading the "why it fits" explanation.

---

## What staff+ interviewers listen for (applies across all of the above)

- **A clear domain model** — aggregates, identity, and where state transitions are legal — before implementation detail.
- **Explicit tradeoffs** — e.g. fairness vs throughput, exact vs approximate rate limiting, pull vs push fan-out.
- **A concurrency story** — which data is shared, what locks/structures you use, what you *refuse* to synchronize globally.
- **Testability** — pure domain vs I/O boundaries; fakes for time and randomness.
- **Evolvability** — where new behaviors plug in without editing a giant `switch`.

(Lifted from [staff-engineer-interviews/README.md](./lld/staff-engineer-interviews/README.md) — see that file for the full timeboxed playbook format used by every "✅ Full guide" entry above.)
