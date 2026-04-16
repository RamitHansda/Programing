# LLD: Meeting scheduler (Calendly-style slice)

## Interview-ready snapshot

**Say first (≈30s):** Per-user **busy intervals**; **slot finder** as pure function over calendars in **UTC**; **book** enforces no overlap across attendees; **idempotent** book with client key.

**Default assumptions:** Recurring out of scope unless they insist; zoned display at edges only.

| Phase | ~Time | Deliver |
|-------|------|---------|
| Align | 5 min | Buffers; time zones; conflict definition. |
| Model | 12 min | UserCalendar, CalendarEvent, Meeting, TimeInterval VO. |
| API + flow | 8 min | propose slots + book; one conflict example. |
| Hard | 12 min | DST edges; transactional multi-calendar book; rollback/cancel. |
| Close | 5 min | Resource rooms as attendees with capacity. |

**Whiteboard order:** (1) timelines sketch (2) free window intersection (3) book writes on all calendars (4) idempotency (5) DST note.

**Likely probes:** All-day events? Partial attendee acceptance?

**30s closer:** Slot finding is pure; booking service owns consistency; values carry zone metadata.

---

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

## Domain model

| Kind | Type | Responsibility |
|------|------|----------------|
| **Aggregate root** | `UserCalendar` | Single user’s **busy** `CalendarEvent` intervals; enforces no overlaps (or allows with explicit rule). |
| **Entity** | `CalendarEvent` | Immutable busy block: `TimeInterval`, title optional, event id. |
| **Entity** | `Meeting` | Booked slot: participants, interval, organizer, idempotency key. |
| **Value object** | `TimeInterval`, `UserId`, `MeetingId` | Zone-safe construction; compare/split operations. |
| **Domain service** | `SlotFinder` | Pure function over read models of many calendars. |
| **Domain service** | `SchedulingService` | Transactional book/cancel across participants’ calendars. |

**Relationships:** one `Meeting` **references** many `UserId`s; each user’s calendar **gains** an event when booked.

**Not modeled:** video conference URLs, CRM integration.

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
