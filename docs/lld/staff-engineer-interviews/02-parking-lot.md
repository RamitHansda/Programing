# LLD: Parking lot

## Interview-ready snapshot

**Say first (≈30s):** `ParkingLot` aggregate owns spots; **atomic assign** of one compatible spot per vehicle; ticket is immutable proof; pricing/assignment are **strategies** if in scope.

**Default assumptions:** In-memory or unspecified persistence; single process unless they ask multi-gate distributed leasing.

| Phase | ~Time | Deliver |
|-------|------|---------|
| Align | 5 min | Payment timing; assignment rule (nearest vs any); concurrency at gate. |
| Model | 10 min | Lot → levels → spots; vehicle vs spot type; ticket; allocator service. |
| API + flow | 8 min | `park` / `unpark` signatures; one happy path + failure (full lot). |
| Hard | 12 min | No double booking; lock granularity; idempotent unpark if asked. |
| Close | 5 min | EV/charging as second resource; waitlist extension. |

**Whiteboard order:** (1) aggregates and composition (2) spot state (3) `park` sequence (4) concurrency on assign (5) optional `SpotAssignmentStrategy`.

**Likely probes:** Where do you lock? Who owns “available” invariant? What if vehicle type changes mid-design?

**30s closer:** Consistency boundary is the lot (or level); strategies swap assignment/pricing without rewriting core.

---

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

## Domain model (aggregates and nouns)

| Kind | Type | Responsibility |
|------|------|----------------|
| **Aggregate root** | `ParkingLot` | Owns levels/spots; enforces “no double booking”; entry point for `park` / `unpark` queries. |
| **Entity** | `Level` (optional), `ParkingSpot` | Spot has stable id; `SpotType` + lifecycle `SpotState` (available/occupied). |
| **Entity / VO** | `Vehicle` (or interface + concrete types) | Describes **constraints** (which spot types are legal), not the allocation algorithm. |
| **Value object** | `ParkingTicket` / `TicketId` | Immutable: ticket id, spot id, `VehicleId`, `enteredAt`. |
| **Domain service** | `ParkingAllocator` (or method on lot with injected `SpotAssignmentStrategy`) | Finds candidate spot and commits assignment in one consistency step. |
| **Optional** | `Payment`, `Receipt`, `PricingPolicy` | Only if payment is in scope; keep out of core `ParkingSpot` if possible. |

**Composition:** `ParkingLot` **contains** `Level`s **contains** `ParkingSpot`s. **Association:** `ParkingTicket` **references** one `ParkingSpot` and one `Vehicle` identity.

**Not modeled here:** UI, gate hardware, persistence schema (unless asked).

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
