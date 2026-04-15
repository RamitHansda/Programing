# LLD: Meeting scheduler (Calendly-style slice)

## Interview prompt

Design a service to **propose meeting slots** given participant availability, book a slot, and handle cancellations.

## Clarifying questions

- **Time zones**: explicit IANA ids per user?
- **Buffers**: travel time between meetings?
- **Recurring**: in scope or explicitly out?

## Functional requirements

- Maintain per-user **busy intervals** (immutable events).
- Find **intersection** of free windows across users for duration `D`.
- Book meeting: reserve interval, fail if conflict.

## Non-functional requirements

- **Correctness** across DST transitions (mention `ZonedDateTime` rules).
- **Idempotent booking** with client-supplied idempotency key (staff signal).

## Design patterns

| Pattern | Role |
|--------|------|
| **Strategy** | `AvailabilityPolicy` (working hours, buffers), `SlotFinder` (sweep line vs brute force for small N). |
| **Domain service** | `SchedulingService` coordinates users and calendar aggregates. |
| **Value object** | `TimeInterval`, `UserId`, `MeetingId`. |

## Staff-level algorithm talk

- Normalize to **UTC** internally; render in user zone at edges only.
- Slot finding: sweep busy boundaries; O((n+k) log n) for n events across users for small interview data.

## Invariants

- Meetings do not overlap for the same attendee.
- Canceled meetings release intervals exactly once.

## Java sketch

```java
public interface SlotFinder {
    List<TimeInterval> propose(List<UserCalendar> calendars, Duration duration, Instant searchStart, Instant searchEnd);
}

public final class SchedulingService {
    public Meeting book(BookMeetingCommand cmd) { /* transactional */ }
}
```

## Testing strategy

- DST edge cases around “spring forward” gaps.
- Conflict races: two bookings same slot—one wins (define with version or DB constraint).

## Follow-ups

- **Resource rooms** as attendees with capacity, delegation / assistant booking.
