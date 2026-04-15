# LLD: Elevator / lift controller (single elevator first)

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
