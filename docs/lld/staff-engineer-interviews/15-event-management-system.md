# LLD: Event management system (capacity-safe registration)

## Interview-ready snapshot

**Say first (≈30s):** **Ticket inventory** is the consistency boundary (`held + confirmed ≤ capacity`). **Registration** aggregate: hold with TTL → confirm (idempotent) → cancel. Payment/notify are **ports**. Seats/waitlist are extensions.

**Default assumptions:** GA ticket quantities; single JVM LLD; assigned seats out of scope.

| Phase | ~Time | Deliver |
|-------|------|---------|
| Align | 5 min | Hold TTL; oversell rule; paid vs free |
| Model | 12 min | Event, TicketType/Inventory, Registration |
| API + flow | 8 min | hold → confirm sequence |
| Hard | 12 min | Last-ticket race; pay/confirm failure |
| Close | 5 min | Waitlist or seats as v2; TL MVP cut |

**Whiteboard order:** (1) aggregates (2) inventory inequality (3) registration states (4) race diagram (5) idempotency key.

**Likely probes:** Double-click confirm? Organizer cancels published event? Partial multi-type hold?

**30s closer:** Inventory owns capacity; registration owns lifecycle; catalog reads may lag.

**Full script:** [../event-management-system/INTERVIEW_SCRIPT.md](../event-management-system/INTERVIEW_SCRIPT.md)

---

## Interview prompt

Design an **event management** service: organizers create/publish events with ticket types and capacity; attendees register without overselling.

## Clarifying questions

- **GA vs assigned seats?**
- **Hold before payment?** TTL?
- **Waitlist** in MVP or later?

## Functional requirements

- Create/publish/cancel events; define ticket types with capacity and price.
- Hold tickets, confirm registration, cancel and release capacity.
- Search/view published events.

## Non-functional requirements

- No oversell under concurrency.
- Idempotent confirm; injectable `Clock` for expiry tests.

## Domain model

| Kind | Type | Responsibility |
|------|------|----------------|
| **Aggregate root** | `Event` | Metadata, publish state; owns ticket type defs |
| **Entity** | `TicketInventory` | Capacity counters; tryHold/confirm/release |
| **Aggregate root** | `Registration` | Hold/confirm lifecycle, idempotency key |
| **Value object** | `Money`, `HoldToken`, `TimeWindow` | |
| **Domain service** | `RegistrationService` | Orchestrates hold → pay → confirm |

**Not modeled:** seat maps, marketing, full auth product.

## Design patterns

| Pattern | Role |
|--------|------|
| **State** | Event + Registration lifecycles |
| **Saga (conceptual)** | Compensate with releaseHold |
| **Strategy** | Optional pricing / notify channels |

## Invariants

- `held + confirmed ≤ capacity`
- Confirm only from valid, owned, unexpired hold

## Java sketch

```java
public interface RegistrationService {
    HoldResult hold(HoldCommand cmd);
    ConfirmResult confirm(HoldToken token, PaymentIntent payment, String idempotencyKey);
    void cancel(RegistrationId id, UserId requester);
}
```

## Testing strategy

- Race on last ticket; clock-based hold expiry; idempotent confirm.

## Follow-ups

- Waitlist promotion; assigned seats; shard inventory by `eventId` for mega-events.
