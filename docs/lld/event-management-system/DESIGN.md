# Event Management System — Low Level Design

Companion to the step-by-step interview walkthrough: [INTERVIEW_SCRIPT.md](INTERVIEW_SCRIPT.md).

## Table of Contents

1. [Overview](#overview)
2. [Requirements](#requirements)
3. [Domain Model](#domain-model)
4. [Design Patterns](#design-patterns)
5. [Class Sketch](#class-sketch)
6. [Key Flows](#key-flows)
7. [Concurrency & Consistency](#concurrency--consistency)
8. [API Design](#api-design)
9. [Failure Modes](#failure-modes)
10. [Testing Strategy](#testing-strategy)
11. [Trade-offs](#trade-offs)
12. [Extensions](#extensions)

---

## Overview

An Event Management System lets **organizers** publish events with **ticket types and capacity**, and lets **attendees** **hold → pay → confirm** registrations without overselling.

**Design north star:** ticket inventory is the consistency boundary; registration is a separate aggregate with an explicit lifecycle.

---

## Requirements

### Functional

| # | Requirement |
|---|-------------|
| 1 | Organizer creates event (draft), adds ticket types (name, price, capacity) |
| 2 | Publish / cancel event |
| 3 | Attendee searches and views published events |
| 4 | Hold tickets (TTL), confirm after payment, cancel registration |
| 5 | Organizer views sold / held / remaining counts |

### Non-functional

| # | Requirement |
|---|-------------|
| 1 | Never oversell: `held + confirmed ≤ capacity` |
| 2 | Safe under concurrent holds on the last tickets |
| 3 | Idempotent confirm |
| 4 | Expired holds release capacity |
| 5 | Payment and notification are replaceable ports |

### Out of scope (MVP)

Assigned seating, waitlist, dynamic pricing, social features, full auth product.

---

## Domain Model

### Aggregates, entities, value objects

| Kind | Type | Responsibility |
|------|------|----------------|
| Aggregate root | `Event` | Title, venue, schedule, status (`DRAFT` / `PUBLISHED` / `CANCELLED`); owns ticket type defs |
| Entity | `TicketType` | Display name, `Money` price, link to inventory |
| Entity | `TicketInventory` | `capacity`, `held`, `confirmed`; `tryHold` / `confirm` / `release` |
| Aggregate root | `Registration` | Buyer, line items, status, `holdExpiresAt`, idempotency key |
| Value object | `EventId`, `RegistrationId`, `HoldToken`, `Money`, `TimeWindow` | |
| Domain service | `RegistrationService` | Orchestrates hold → pay → confirm |
| Port | `PaymentGateway`, `Clock`, `EventNotifier` | Infrastructure |

### Relationships

- One `Event` **has many** `TicketType`s.
- Each `TicketType` **owns** one `TicketInventory`.
- One `Registration` **references** an `EventId` and one or more ticket lines (type + qty).
- Registrations are **not** embedded inside `Event` (keeps aggregate small).

### Invariants

1. `held + confirmed ≤ capacity` for every ticket type.
2. New holds only when `Event.status == PUBLISHED`.
3. Confirm only for unexpired hold owned by the requester.
4. Capacity release on expire/cancel happens **exactly once**.
5. Same idempotency key yields the same confirmed registration.

### Lifecycles

```
Event:         DRAFT → PUBLISHED → CANCELLED
Registration:  HELD → CONFIRMED → CANCELLED
                 └→ EXPIRED
```

---

## Design Patterns

| Pattern | Role |
|---------|------|
| **State** | Event and Registration legal transitions |
| **Strategy** | Optional pricing / notification channels |
| **Factory** | Create `Registration` from `HoldCommand` |
| **Repository** | Load/save Event and Registration aggregates |
| **Saga (conceptual)** | Compensate with `releaseHold` if pay/confirm fails |

---

## Class Sketch

```
┌─────────────────────────┐
│ EventService            │
│ + create / publish      │
│ + cancel / search       │
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐       ┌──────────────────────┐
│ Event                   │<>-----│ TicketType           │
│ - id, organizerId       │       │ - id, name, price    │
│ - status, schedule      │       │ - inventory          │
└─────────────────────────┘       └──────────┬───────────┘
                                             │
                                             ▼
                                  ┌──────────────────────┐
                                  │ TicketInventory      │
                                  │ - capacity           │
                                  │ - held, confirmed    │
                                  │ + tryHold / confirm  │
                                  │ + release            │
                                  └──────────────────────┘

┌─────────────────────────┐
│ RegistrationService     │──uses──▶ PaymentGateway, Clock
│ + hold / confirm/cancel │
└───────────┬─────────────┘
            ▼
┌─────────────────────────┐
│ Registration            │
│ - id, eventId, userId   │
│ - lines[], status       │
│ - holdExpiresAt         │
│ - idempotencyKey        │
└─────────────────────────┘
```

### Inventory core (interview-sized)

```java
public final class TicketInventory {
    private final int capacity;
    private int held;
    private int confirmed;

    public synchronized boolean tryHold(int qty) {
        if (qty <= 0 || held + confirmed + qty > capacity) return false;
        held += qty;
        return true;
    }

    public synchronized void confirm(int qty) {
        if (qty > held) throw new IllegalStateException("confirm exceeds held");
        held -= qty;
        confirmed += qty;
    }

    public synchronized void releaseHold(int qty) {
        held = Math.max(0, held - qty);
    }

    public synchronized void releaseConfirmed(int qty) {
        confirmed = Math.max(0, confirmed - qty);
    }

    public int remaining() { return capacity - held - confirmed; }
}
```

---

## Key Flows

### 1. Hold

1. Load published `Event`.
2. For each line, `inventory.tryHold(qty)` (all-or-nothing; rollback prior holds on failure).
3. Persist `Registration(HELD, expiresAt = now + TTL)`.
4. Return `HoldToken`.

### 2. Confirm

1. Load registration by token; verify owner + not expired + status HELD.
2. Charge via `PaymentGateway` (or verify intent).
3. For each line `inventory.confirm(qty)`; set status CONFIRMED.
4. Store idempotency key → registration id.
5. Notify (async / outbox).

### 3. Cancel registration

1. If HELD → `releaseHold`.
2. If CONFIRMED → `releaseConfirmed` + refund port.
3. Status CANCELLED (idempotent).

### 4. Cancel event

1. Event → CANCELLED; reject new holds.
2. Bulk-cancel active registrations; enqueue refunds/notifications.

---

## Concurrency & Consistency

| Concern | Approach |
|---------|----------|
| Oversell | Synchronize / row-lock / conditional update on `TicketInventory` |
| Hot event | Lock per ticket type (not global); measure before sharding by `eventId` |
| Duplicate confirm | Idempotency store `(userId, key)` or `paymentRef` |
| Hold expiry | Injected `Clock` + lazy expiry on access + sweeper |
| Search vs book | Catalog reads can be stale; register path strongly consistent on inventory |

---

## API Design

```java
public interface EventService {
    EventId createEvent(CreateEventCommand cmd);
    void publish(EventId id);
    void cancelEvent(EventId id);
    Optional<EventView> get(EventId id);
    List<EventSummary> search(SearchQuery q);
}

public interface RegistrationService {
    HoldResult hold(HoldCommand cmd);
    ConfirmResult confirm(HoldToken token, PaymentIntent payment, String idempotencyKey);
    void cancel(RegistrationId id, UserId requester);
    RegistrationView get(RegistrationId id);
}
```

REST sketch:

| Method | Path | Notes |
|--------|------|-------|
| POST | `/events` | Create draft |
| POST | `/events/{id}/publish` | |
| POST | `/events/{id}/holds` | Start registration |
| POST | `/registrations/confirm` | Header: Idempotency-Key |
| DELETE | `/registrations/{id}` | Cancel |
| GET | `/events` | Search |

---

## Failure Modes

| Failure | Handling |
|---------|----------|
| Sold out on hold | Return conflict; no partial hold |
| Hold expired at confirm | Reject; client must re-hold |
| Pay OK, confirm crash | Retry confirm with same idempotency / payment ref |
| Pay fail | Leave HELD until TTL or explicit abandon → release |
| Double cancel | No-op after terminal state |

---

## Testing Strategy

1. Capacity race: capacity=1, two threads hold → one win.
2. Confirm after expiry fails; inventory fully released.
3. Idempotent confirm does not double-increment `confirmed`.
4. Cancel confirmed frees capacity for a new hold.
5. Cannot hold on DRAFT / CANCELLED event.

---

## Trade-offs

| Choice | Why | Cost |
|--------|-----|------|
| Counters not seats | Simpler MVP; covers concerts/workshops | No seat map |
| Separate Registration aggregate | Avoids huge Event aggregate | Cross-aggregate orchestration |
| Hold before pay | Protects against payment latency | Need TTL + sweeper |
| Per-type lock | Correct & interview-clear | Hot-lock on mega events |

---

## Extensions

1. **Waitlist** — FIFO per ticket type; promote on cancel with short confirm window.
2. **Assigned seats** — `SeatMap` aggregate (see movie booking LLD).
3. **Promo codes** — Pricing strategy.
4. **Check-in** — QR / ticket code read model.
5. **Occurrence series** — child dates under one Event.
