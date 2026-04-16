# LLD: Movie ticket booking (seat hold + confirm)

## Interview-ready snapshot

**Say first (≈30s):** Seat **lifecycle** (available → held → booked); **hold** is TTL’d; **confirm** is atomic with payment/idempotency; no double booking at confirm boundary.

**Default assumptions:** Single service; clock injectable for hold expiry tests.

| Phase | ~Time | Deliver |
|-------|------|---------|
| Align | 5 min | Hold duration; partial seat set failure; payment failure compensation. |
| Model | 12 min | Show, Seat, Hold, Booking, tokens/ids. |
| API + flow | 8 min | hold → confirm sequence; expiry path. |
| Hard | 12 min | Per-show or per-seat locking; idempotent confirm; saga verbal. |
| Close | 5 min | Optimistic version on row; multi-show inventory. |

**Whiteboard order:** (1) seat states (2) hold record (3) two users race diagram (4) confirm transaction box (5) idempotency key.

**Likely probes:** Payment succeeds, confirm fails? Overlapping holds?

**30s closer:** Aggregate is show or seat row; holds are leases; confirm is the consistency commit.

---

## Interview prompt

Design **seat selection** for a multiplex: show layout, hold seats temporarily, confirm booking and payment.

## Clarifying questions

- **Hold TTL**: how long can seats be held without payment?
- **Concurrency**: two users selecting same seat—what is the user-visible outcome?
- **Seat types**: standard, recliner, wheelchair companion rules?

## Functional requirements

- View show seat map with availability.
- `hold(seatIds, userSession)` returns hold token or conflict details.
- `confirm(holdToken, payment)` converts hold to booking.
- Background job releases expired holds.

## Non-functional requirements

- **No double booking** for confirmed seats.
- **High contention** on popular shows.

## Domain model

| Kind | Type | Responsibility |
|------|------|----------------|
| **Aggregate root** | `Show` (or `Screening`) | Seat map, show time; owns seat lifecycle for that screening. |
| **Entity** | `Seat` | Stable `SeatId`, `SeatType`, state (`AVAILABLE` / `HELD` / `BOOKED` / `BLOCKED`). |
| **Entity** | `SeatHold` | Hold id, owner session/user, set of `SeatId`, `expiresAt`. |
| **Entity** | `Booking` | Confirmed purchase: references seats, payment reference, idempotency key. |
| **Value object** | `HoldToken`, `Money` | Opaque ids; money for pricing if in scope. |

**Relationships:** `Show` **contains** many `Seat`s; `SeatHold` **references** many seats; successful `confirm` **creates** `Booking` and transitions seats.

**Not modeled:** projector schedule, concession sales.

## Design patterns

| Pattern | Role |
|--------|------|
| **State** | Seat lifecycle: `AVAILABLE`, `HELD`, `BOOKED`, `BLOCKED`. |
| **Saga / process manager** (conceptual) | Hold → pay → confirm with compensating **release** on failure (even if implemented synchronously in LLD). |
| **Factory** | `Show`, `Seat`, `PricingRule` construction from static layout files. |

## Staff-level consistency model

- Per-show **coordinator** or **per-seat locks**.
- Hold record: `{holdId, seatIds, expiry, owner}` stored in a structure enabling fast expiry scans (bucketed timers) or lazy expiry on access.

## Invariants

- A seat cannot be `BOOKED` by two different bookings.
- `confirm` must validate hold ownership and expiry atomically.

## Java sketch

```java
public interface BookingService {
    HoldResult hold(ShowId show, Set<SeatId> seats, UserId user);
    ConfirmResult confirm(HoldId hold, PaymentIntent payment);
}
```

## Failure modes

- Payment succeeds but confirm fails: define **reconciliation** (idempotent confirm token).
- Partial seat sets: avoid; hold is **all-or-nothing**.

## Testing strategy

- Race tests: two threads holding overlapping sets—exactly one succeeds.
- Expiry: time-controlled `Clock`.

## Follow-ups

- **Optimistic locking** with version per seat row.
- **Graph seat rules** (leave single gap undesirable—business rule engine).
