# LLD: Elevator / lift controller (single elevator first)

## Interview-ready snapshot

**Say first (≈30s):** **State machine** for door vs motion; **scheduler Strategy** for SCAN/FCFS; cab aggregate holds floor, direction, pending requests—invalid transitions impossible by construction.

**Default assumptions:** One car first; discrete floors; simulate with `tick()` or events unless they want real timers only.

| Phase | ~Time | Deliver |
|-------|------|---------|
| Align | 5 min | Hall vs cabin calls; door timing; multi-car extension timeboxed. |
| Model | 10 min | `ElevatorCab`, requests, `ElevatorState`, `DispatchScheduler`. |
| API + flow | 8 min | Submit request + step simulation; show one transition chain. |
| Hard | 12 min | Safety: no move with doors open; starvation / SCAN fairness mention. |
| Close | 5 min | Multi-car dispatcher as separate coordinator. |

**Whiteboard order:** (1) states list (2) context fields (3) one illegal vs legal transition (4) scheduler input/output (5) optional multi-elevator box.

**Likely probes:** Same-floor request? Emergency stop? How to test—inject clock/events.

**30s closer:** States encode safety; strategy swaps scheduling; easy to extend to N cars with a dispatcher service.

---

## Interview prompt

Model an **elevator** serving requests in a building. Start with **one cabin**; extend to multiple cabins if time permits.

## Clarifying questions

- **Scheduling policy**: FCFS, SCAN (elevator algorithm), LOOK?
- **Door**: minimum open time, re-open on obstruction?
- **Request types**: external hall calls vs internal cabin panel?
- **Weight / capacity**: enforce or ignore for LLD scope?

## Functional requirements

- Submit **hall** and **floor** requests.
- Elevator moves floor-by-floor; doors open/close.
- Deterministic handling of **same-floor** requests.

## Non-functional requirements

- **Safety invariants**: don’t move with doors open; don’t skip emergency stop if modeled.
- **Extensibility**: swap scheduling without rewriting state machine.

## Domain model

| Kind | Type | Responsibility |
|------|------|----------------|
| **Aggregate root** | `ElevatorCab` (or `Elevator`) | Current floor, door state, travel direction, cabin request set; the thing that must not violate motion/door rules. |
| **Value object** | `Floor` (int + min/max validation), `Direction` | No behavior beyond validation if kept as VO. |
| **Entity / VO** | `HallCall`, `CabinRequest` | Source + target floor; may merge into `Request` with discriminant. |
| **Domain service** | `DispatchScheduler` | Chooses next stop from pending sets given policy (SCAN, FCFS). |
| **State pattern** | `ElevatorState` + `ElevatorContext` | Context holds cab snapshot; state objects encode **legal transitions**. |

**Relationships:** one `ElevatorCab` **has** current queue / pending requests (either split hall vs cabin or unified). **Building** (min/max floor) can be a VO passed into context.

**Not modeled here:** motor controller firmware, multi-car shaft interlocks (mention as real-world boundary).

## State machine (this is the heart)

States: `Idle`, `MovingUp`, `MovingDown`, `DoorOpen`, `DoorClosing`, `OutOfService` (optional).

Transitions driven by **events**: `DoorTimerElapsed`, `RequestReceived`, `ArrivedAtFloor`, `Emergency`.

## Design patterns

| Pattern | Role |
|--------|------|
| **State** | `ElevatorState` encapsulates transition rules; `ElevatorContext` holds cabin data. |
| **Strategy** | `DispatchScheduler` chooses next stop from pending requests (SCAN vs FCFS). |
| **Command** (optional) | Wrap requests for logging/undo in simulation tools—not always needed live. |

## Staff-level modeling choice

Separate **physics/simulation clock** from real time:

- In interviews, a `tick()` or `step()` method makes behavior testable.
- Production uses async timers; same states, different “driver”.

## Java sketch

```java
public interface ElevatorState {
    void onRequest(ElevatorContext ctx, Request r);
    void onDoorClosed(ElevatorContext ctx);
    void onArrived(ElevatorContext ctx, int floor);
}

public interface DispatchScheduler {
    Optional<Integer> nextStop(ElevatorContext ctx);
}
```

## Invariants

- Cabin floor is always within `[minFloor, maxFloor]`.
- Door state and motion state are compatible (define forbidden pairs).
- A served request is removed exactly once.

## Multi-elevator extension (mention even if not coded)

- **Dispatcher** assigns hall calls to a cabin (nearest, least loaded, directional match).
- Avoid shared mutable **global** lists without a coordinator; prefer **per-cabin** queues + assignment events.

## Testing strategy

- Table-driven tests for transitions.
- Scheduler unit tests with synthetic pending sets.

## Follow-ups

- **Destination dispatch** (enter desired floor at hall panel).
- **Batch peak traffic** optimizations and starvation avoidance.
