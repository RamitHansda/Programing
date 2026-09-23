# LLD Design Patterns — Interview Guide

How to **choose and name** patterns in a low-level design round without turning the answer into a GoF lecture.

**Companions**

| Doc | Use |
|-----|-----|
| [DESIGN_PATTERNS_WHEN_TO_USE_AND_EXAMPLES.md](../DESIGN_PATTERNS_WHEN_TO_USE_AND_EXAMPLES.md) | Full GoF catalog: when to use + examples |
| [LLD_PROBLEMS_BY_DESIGN_PATTERN.md](../LLD_PROBLEMS_BY_DESIGN_PATTERN.md) | One practice problem per pattern |
| [staff-engineer-interviews/](./staff-engineer-interviews/) | Staff LLD playbook (rate limiter, parking lot, …) |
| [LLD_DESIGN_PATTERNS_DAYOF_CHEATSHEET.md](./LLD_DESIGN_PATTERNS_DAYOF_CHEATSHEET.md) | Day-of one-pager |
| Java code | `src/main/java/lld/designpatterns/` |

---

## 1. Patterns are second, not first

Staff interviewers want **domain model → invariants → APIs → concurrency**, then patterns **only where they clarify variation**.

**Say (≈15s):**

> “I’ll name types and invariants first. Where behavior varies by algorithm, lifecycle, or channel, I’ll plug in a Strategy / State / Observer so we don’t grow a god `switch`.”

**Anti-pattern:** opening with “I’ll use Singleton + Factory + Observer” before stating what owns mutable state.

---

## 2. Decision tree (use mid-whiteboard)

Ask yourself in this order:

```
Does behavior change with lifecycle / status?
  YES → State (elevator, vending, booking hold→confirm)
  NO  ↓

Do I need to swap algorithms for the same goal?
  YES → Strategy (pricing, eviction, split, dispatch, routing)
  NO  ↓

One subject, many dependents that must react?
  YES → Observer (or thin event bus / Mediator)
  NO  ↓

Create one of several product types without callers knowing concrete classes?
  YES → Factory Method (vehicles, exporters, channels)
  NO  ↓

Add stackable cross-cutting behavior around an interface?
  YES → Decorator (metrics, TTL, retry, logging)
  NO  ↓

Tree of leaves + containers treated uniformly?
  YES → Composite (+ Iterator for traversal)
  NO  ↓

Request passes through ordered handlers that may short-circuit?
  YES → Chain of Responsibility (logger, middleware)
  NO  ↓

Need undo / queue / replay of actions?
  YES → Command
  NO  ↓

Many optional fields / fluent construction?
  YES → Builder
  ELSE → keep plain classes; don’t force a pattern
```

---

## 3. High-frequency patterns in LLD rounds

These eight cover most machine-coding / OOD prompts. Memorize the **spoken why**, not every UML box.

### Strategy (most common)

**Smell:** `if (type == A) … else if (type == B)` for algorithms that share one goal.

**Spoken why:** “Same API, different algorithm; inject `XStrategy` so adding a new policy doesn’t edit the service.”

| Classic LLD | Strategy interface |
|-------------|--------------------|
| Rate limiter | `RateLimitAlgorithm` |
| Parking lot | `SpotAssignmentStrategy`, `PricingStrategy` |
| Splitwise | `SplitStrategy` |
| Cache | `EvictionPolicy` |
| Chess | `PieceMovementStrategy` / `MoveRule` |
| Notifications | `NotificationChannel` |
| Elevator | `DispatchScheduler` (SCAN vs FCFS) |

### State

**Smell:** giant `switch(status)` with illegal transitions possible from any method.

**Spoken why:** “Behavior depends on lifecycle; each state owns legal transitions so invalid moves are impossible by construction.”

| Classic LLD | States |
|-------------|--------|
| Elevator | Idle / Moving / DoorOpen |
| Vending machine | Idle / HasMoney / Dispensing |
| Movie booking | Available / Held / Booked |
| Order / checkout | Created → Paid → Fulfilled / Cancelled |

### Observer / event bus

**Smell:** subject hardcodes `email.send(); sms.send(); analytics.track()`.

**Spoken why:** “Publisher doesn’t know concrete listeners; subscribers register at runtime.”

| Classic LLD | Subject → observers |
|-------------|---------------------|
| Pub-sub hub | Topic → subscribers |
| Notifications | Domain event → channels |
| Stock alerts | Price feed → watchers |

### Factory Method

**Smell:** callers `new ConcreteA()` / `new ConcreteB()` scattered across services.

**Spoken why:** “Creation in one place; callers depend on the interface.”

Use for: vehicle from plate+type, exporter by format, channel by enum. Prefer **DI of a factory** over Singleton factory.

### Decorator

**Smell:** subclass explosion (`LoggedCachedMeteredX`) or editing core class for metrics/TTL.

**Spoken why:** “Wrap the interface to stack cross-cutting behavior without changing the core.”

Use for: metered cache, retrying HTTP client, formatting appender.

### Composite

**Smell:** client code branches `if (isDirectory)`.

**Spoken why:** “Leaf and container share one interface; ops recurse.”

Use for: filesystem, org chart, UI tree, nested menus.

### Chain of Responsibility

**Smell:** fixed pipeline with hard-coded next calls inside each class.

**Spoken why:** “Handlers form a chain; each handles and/or forwards; order is configurable.”

Use for: logger levels, HTTP middleware, approval workflow.

### Command

**Smell:** undo needs reverse-engineering side effects from raw method calls.

**Spoken why:** “Action as object with `execute`/`undo`; history stack becomes trivial.”

Use for: editor undo, job queue, macros, transactional step list.

---

## 4. Patterns worth knowing but rarely primary

| Pattern | Interview use | Caution |
|---------|---------------|---------|
| **Singleton** | Connection pool, process-wide clock | Prefer DI of one instance; Singleton hurts tests |
| **Builder** | Cart, HTTP request, query, alert | Don’t Builder a 2-field DTO |
| **Adapter** | Wrap third-party payment/SDK | Name it when integrating legacy APIs |
| **Facade** | `fulfillOrder` coordinating subsystems | Thin application service is often enough |
| **Template Method** | Fixed pipeline, variable steps | Inheritance-heavy; Strategy often clearer |
| **Mediator** | Chat room, complex form wiring | Don’t invent for simple pub-sub |
| **Memento** | Config rollback, game checkpoint | Alternative to Command undo |
| **Proxy** | Lazy load, access control | Overlaps Decorator; say which intent |
| **Abstract Factory / Bridge / Flyweight / Visitor / Prototype** | Niche prompts | Don’t volunteer unless the problem forces it |

---

## 5. Classic LLD problem → pattern map

| Problem | Primary | Secondary |
|---------|---------|-----------|
| Parking lot | Strategy (assign/price), Factory (vehicle) | — |
| Elevator | State + Strategy (scheduler) | — |
| Vending machine | State | Strategy (change maker) |
| Logger framework | Chain of Responsibility | Decorator (format), Observer (appenders) |
| LRU / cache | Strategy (eviction) | Decorator (metrics/TTL) |
| Rate limiter | Strategy (algorithm) | — |
| Splitwise | Strategy (split) | Value objects (`Money`) |
| Chess / board game | Strategy (moves) | Template Method (turn), Memento (undo) |
| File system | Composite | Iterator |
| Notification system | Strategy (channel) | Observer / bus |
| Pub-sub | Observer + Mediator | Strategy (dispatch) |
| Movie / event booking | State (hold→book) | Saga-style compensation |
| Meeting scheduler | Strategy (slot find / policy) | Value objects (interval) |
| Shopping cart | Strategy (pricing/payment) | Builder, Saga |
| Text editor | Command | Memento |
| Connection pool | Singleton (sparingly) + pooling | — |

Full staff writeups: [staff-engineer-interviews/README.md](./staff-engineer-interviews/README.md).

---

## 6. How to say it in the room

### Name the pattern + one sentence why

> “I’ll use **Strategy** for `EvictionPolicy` so LRU vs LFU swap without touching `Cache`.”

> “Cab lifecycle is a **State** machine—`Moving` can’t open doors; transitions live on the state objects.”

> “Channels are **Observers** of domain events so adding Push doesn’t edit OrderService.”

### Show the plug-in point

Draw or write:

```text
Cache
  └─ EvictionPolicy   ← Strategy
       ├─ LruPolicy
       └─ LfuPolicy
```

Interviewers care that you know **where** variation plugs in, not that you recite the pattern catalog.

### When they ask “which design pattern?”

1. Restate the variation (“algorithms for the same goal” / “lifecycle” / “1:N notify”).
2. Name **one** primary pattern.
3. Optional secondary only if asked (“Decorator for metrics around the same interface”).

---

## 7. Staff bar vs junior overuse

| Junior signal | Staff signal |
|---------------|--------------|
| Patterns first | Domain + invariants first |
| Singleton everywhere | DI of a single instance |
| God `Manager` + empty entities | Behavior on aggregates / domain services |
| Pattern name without plug-in | Interface + 2 implementations sketched |
| Ignore concurrency | Say what is shared and what lock/structure |

---

## 8. 30-minute drill plan

1. Pick 3 problems from the [staff playbook](./staff-engineer-interviews/README.md).
2. For each: domain model (5 min) → name primary pattern + draw plug-in (3 min) → one concurrency note (2 min).
3. Implement one Strategy or State package from `src/main/java/lld/designpatterns/` cold.
4. Day of: skim [DAYOF cheatsheet](./LLD_DESIGN_PATTERNS_DAYOF_CHEATSHEET.md).

---

## 9. Quick “which pattern?” table

| You need… | Pattern |
|-----------|---------|
| Swap algorithm, same API | **Strategy** |
| Behavior by lifecycle / status | **State** |
| 1 subject → many listeners | **Observer** |
| Create by type without `new` everywhere | **Factory Method** |
| Stack metrics/retry/TTL around interface | **Decorator** |
| File/dir-style tree, uniform ops | **Composite** |
| Ordered handlers / middleware | **Chain of Responsibility** |
| Undo / queue / replay actions | **Command** |
| Many optional construction steps | **Builder** |
| Wrap foreign API to your interface | **Adapter** |
| One entry for multi-subsystem use case | **Facade** |
| Exactly one process-scoped resource | **Singleton** (prefer DI) |

---

*Patterns clarify variation. Domain truth and concurrency still carry the round.*
