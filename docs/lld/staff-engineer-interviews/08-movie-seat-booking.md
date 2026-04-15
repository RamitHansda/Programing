# LLD: Movie ticket booking (seat hold + confirm)

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
