# LLD: Parking lot

## Interview prompt

Design a **parking lot** system: multiple floors, multiple spot types (compact, large, handicapped, EV). Vehicles enter, get assigned a spot, pay (optional in scope), and exit freeing the spot.

## Clarifying questions

- **Payment**: on entry, on exit, subscription model?
- **Assignment**: nearest spot, cheapest, reserved spots?
- **Concurrency**: one kiosk per gate or centralized allocator?
- **Persistence**: in-memory for interview vs transactional store?

## Functional requirements

- Register lot configuration (floors, counts per spot type).
- `park(vehicle)` → ticket with spot id, or failure reason (full).
- `unpark(ticket)` → charges if in scope, frees spot.
- Query availability by type.

## Non-functional requirements

- **Consistency**: a spot must not be double-assigned.
- **Extensibility**: new vehicle types or pricing rules without rewriting parking core.

## Domain model (keep it boring and correct)

- **ParkingLot** aggregates **Levels** (or a flat pool if interviewer insists).
- **ParkingSpot** has `SpotType` and `SpotState` (available/occupied).
- **Vehicle** is abstract or interface with `requiredSpotType()` (or strategy).
- **ParkingTicket** is immutable value: id, spot id, entry time.

## Design patterns

| Pattern | Role |
|--------|------|
| **Factory** | `Vehicle` creation from external input (plate + type) keeps constructors clean. |
| **Strategy** | `SpotAssignmentStrategy` (nearest, random, reserved-first), `PricingStrategy` if payment is in scope. |
| **Domain service** | `ParkingAllocator` coordinates transactional assignment—not every behavior belongs on `Vehicle`. |

Avoid an **Anemic** model where `ParkingLot` is only getters/setters; keep **invariants** on aggregates.

## Concurrency (staff answer)

- Assignment should be **atomic**: `findFirstAvailable` + `markOccupied` must not interleave.
- Practical Java approach: **per-level locks** or `synchronized` allocator with small critical section; alternatively `StampedLock` if read-heavy availability scans.
- If distributed: move to **lease-based** spot holds with TTL (out of classic LLD, mention boundary).

## Invariants

- A spot is in exactly one of `{AVAILABLE, OCCUPIED}`.
- Ticket references a spot that matches vehicle constraints.
- Unpark is **idempotent** if ticket already closed (define behavior).

## Java API sketch

```java
public interface SpotAssignmentStrategy {
    Optional<ParkingSpot> assign(ParkingLotView view, Vehicle vehicle);
}

public final class ParkingService {
    public ParkingTicket park(Vehicle vehicle) { /* transactional */ }
    public Receipt unpark(TicketId id) { /* */ }
}
```

## Testing strategy

- Small lot fixtures; assert no double booking under parallel `park`.
- Strategy tests isolated with a canned `ParkingLotView`.

## Follow-ups

- **Waitlist** when full (queue + notification).
- **Reservations** and overbooking policies.
- **EV charging** as a resource separate from “spot” (second dimension).
