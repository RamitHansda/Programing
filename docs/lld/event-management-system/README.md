# Event Management System (LLD)

Low-level design for an Eventbrite-style system: organizers publish events with ticket capacity; attendees hold → confirm registrations without overselling.

## Start here

| Doc | Use when |
|-----|----------|
| **[INTERVIEW_SCRIPT.md](INTERVIEW_SCRIPT.md)** | Step-by-step interview script (what to say, draw, and deepen) |
| **[DESIGN.md](DESIGN.md)** | Full domain model, APIs, concurrency, trade-offs |

## Interview-ready snapshot

**Say first (≈30s):** Ticket **inventory** is the consistency boundary (`held + confirmed ≤ capacity`). **Registration** is a separate aggregate: **HELD → CONFIRMED** with TTL; confirm is **idempotent**; payment/notify are ports.

**Default assumptions:** General admission quantities (not assigned seats); waitlist out of MVP; single service LLD.

| Phase | ~Time | Deliver |
|-------|------|---------|
| Align | 5 min | GA vs seats; hold TTL; no oversell |
| Model | 10 min | Event, TicketInventory, Registration |
| API + flow | 10 min | hold → pay → confirm |
| Hard | 10 min | Race on last ticket; paid-not-confirmed |
| Close | 5 min | Tests + MVP cut (TL/EM) |

**Whiteboard order:** (1) nouns (2) inventory counters (3) registration states (4) hold/confirm sequence (5) lock boundary.

**30s closer:** Inventory owns capacity math; registration owns purchase lifecycle; search can be eventually consistent.

## Related

- [Movie seat booking](../staff-engineer-interviews/08-movie-seat-booking.md) — seat hold pattern  
- [Meeting scheduler](../staff-engineer-interviews/12-meeting-scheduler.md) — time windows  
- [Interview runbook](../staff-engineer-interviews/INTERVIEW-RUNBOOK.md) — shared 45-min clock
