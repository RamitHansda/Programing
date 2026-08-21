# Interview Script: Event Management System (LLD)

Use this to practice or run a **45–60 minute** LLD interview. Written as a **Tech Lead / EM** would coach a candidate: what to say, what to draw, when to deepen, and how to close.

**Companion:** [DESIGN.md](DESIGN.md) (full domain model, APIs, concurrency).  
**Shared clock:** [../staff-engineer-interviews/INTERVIEW-RUNBOOK.md](../staff-engineer-interviews/INTERVIEW-RUNBOOK.md)

---

## Interview Setup

| Item | Suggestion |
|------|------------|
| **Duration** | 45 min (compact) or 60 min (with waitlist + concurrency deep dive) |
| **Format** | Whiteboard / shared doc; candidate drives |
| **Prompt** | *“Design an Event Management System — organizers create events, attendees register for tickets, and we must never oversell capacity.”* |
| **Level bar** | Senior / Tech Lead: invariants + concurrency + idempotency; EM: also scope, MVP cut, team ownership |

---

## First 60 seconds (candidate opener — memorize this)

> “I’ll treat this as **single-service LLD in one JVM** unless you want distributed. Core flows: **create event → publish → register (with capacity) → cancel**. I’ll start with **clarifying questions**, then **domain model + invariants**, then **APIs**, then **concurrency and failure**. Auth, email, and payment gateway I’ll stub as ports.”

Then ask **two** design-changing questions (below).

---

## Phase 0: Clock (keep this visible)

| Minutes | Phase | On the board |
|--------:|--------|--------------|
| 0–5 | Align | Scope, assumptions, FR/NFR bullets |
| 5–15 | Model | Aggregates, ticket inventory, registration lifecycle |
| 15–25 | API + happy path | Interfaces + register sequence |
| 25–35 | Hard parts | Oversell race, hold TTL, idempotency, cancel |
| 35–42 | Quality / EM | Tests; MVP vs v2; ownership cut |
| 42–45 | Close | Tradeoffs + “what I’d do with 30 more minutes” |

---

## Phase 1: Clarify Requirements (0–5 min)

### Interviewer opens with

> Design an Event Management System like Eventbrite / Meetup for a single product: organizers create events, people buy/register for tickets, capacity must be respected.

### Candidate clarifying questions (ask 4–6; don’t interrogate)

| # | You ask | Strong default if they shrug |
|---|---------|------------------------------|
| 1 | **General admission vs assigned seats?** | Start with **ticket types + quantity** (GA). Seats = BookMyShow extension. |
| 2 | **Paid tickets or free RSVP?** | Support both; payment is a **port**; invent money with `Money` VO. |
| 3 | **Hold before pay?** | Yes: **AVAILABLE → HELD → CONFIRMED** with TTL (like movie booking). |
| 4 | **Waitlist when sold out?** | In MVP: reject when full; mention waitlist as extension. |
| 5 | **Multi-session / multi-day events?** | One **Event** has one or more **Occurrences** (or keep single datetime for MVP). |
| 6 | **Scale?** | ~10k concurrent registrations on hot events; correctness > micro-optimization in LLD. |
| 7 | **Out of scope?** | Social feed, recommendations, full CRM, multi-currency FX — out. |

### You write on the board (FR / NFR)

**Functional (MVP)**
1. Organizer creates/publishes/cancels an event
2. Define ticket types (VIP / Standard) with price + capacity
3. Attendee searches / views event
4. Register: hold tickets → (pay) → confirm
5. Cancel registration → release inventory
6. Organizer sees registration counts

**Non-functional**
1. **Never oversell** a ticket type
2. Thread-safe under concurrent register
3. Idempotent confirm (duplicate pay callbacks)
4. Hold expiry releases capacity
5. Clear consistency boundary (per event inventory)

**Out of scope (say aloud)**
AuthN/Z details, email templates, seat maps, dynamic pricing, multi-venue routing.

**Wrap line:**  
> “So MVP is capacity-safe registration with hold/confirm. Assigned seats and waitlist are v2.”

---

## Phase 2: Domain Model (5–15 min)

### Interviewer nudge

> “What are the core nouns, and where is the consistency boundary?”

### Candidate script (say this)

> “**Event** is the aggregate root for catalog metadata and publish state. **TicketInventory** (per ticket type on that event) owns capacity and is the **consistency boundary for overselling**. **Registration** is its own aggregate for the attendee’s purchase lifecycle, referencing inventory via IDs. I will not put all registrations inside Event — that blows up the aggregate.”

### Draw this (whiteboard order)

```
┌──────────────┐     creates      ┌─────────────────────┐
│ Organizer    │─────────────────▶│ Event (AR)          │
│ (UserId)     │                  │ status: DRAFT/      │
└──────────────┘                  │ PUBLISHED/CANCELLED │
                                  │ venue, schedule     │
                                  └──────────┬──────────┘
                                             │ has many
                                             ▼
                                  ┌─────────────────────┐
                                  │ TicketType          │
                                  │ name, price         │
                                  │ ── owns ──▶ Inventory│
                                  │ capacity, sold, held│
                                  └──────────┬──────────┘
                                             │ reserved by
                                             ▼
┌──────────────┐     places       ┌─────────────────────┐
│ Attendee     │─────────────────▶│ Registration (AR)   │
│ (UserId)     │                  │ HELD→CONFIRMED/     │
└──────────────┘                  │ EXPIRED/CANCELLED   │
                                  │ holdExpiry, lines   │
                                  └─────────────────────┘
```

### Domain model table (recite)

| Kind | Type | Responsibility |
|------|------|----------------|
| **Aggregate root** | `Event` | Metadata, schedule, publish/cancel; owns ticket type definitions |
| **Entity** | `TicketType` | Name, price, points at inventory counters |
| **Entity / VO counters** | `TicketInventory` | `capacity`, `held`, `confirmed`; methods `tryHold`, `confirm`, `release` |
| **Aggregate root** | `Registration` | Buyer, lines (ticketType → qty), status, hold expiry, idempotency key |
| **Value objects** | `EventId`, `Money`, `TimeWindow`, `HoldToken` | Identity + money + schedule |
| **Domain service** | `RegistrationService` | Orchestrates hold → pay → confirm across inventory + registration |
| **Port** | `PaymentGateway`, `Clock`, `Notifier` | Injected; not domain |

**Not modeled:** chat, reviews, marketing campaigns.

### Invariants (must say out loud)

1. `held + confirmed ≤ capacity` for every ticket type **always**.
2. Only **PUBLISHED** events accept new holds.
3. Confirm only from **valid, unexpired hold** owned by same user/session.
4. Cancel confirmed → decrement confirmed and free capacity **exactly once**.
5. Duplicate idempotency key → return same registration, no double charge/inventory.

### Lifecycle (draw states)

```
Event:        DRAFT ──publish──▶ PUBLISHED ──cancel──▶ CANCELLED
                                    │
Registration:     HELD ──confirm──▶ CONFIRMED ──cancel──▶ CANCELLED
                    │
                    └──(TTL / abandon)──▶ EXPIRED (inventory released)
```

### Design patterns (name only where they earn their keep)

| Pattern | Where |
|---------|--------|
| **State** | Event publish lifecycle; Registration hold/confirm |
| **Strategy** | Pricing / discount (optional); notification channel |
| **Factory** | Build `Registration` from hold request |
| **Repository** | `EventRepository`, `RegistrationRepository` |
| **Saga (conceptual)** | Hold → Pay → Confirm with compensating `releaseHold` |

**Anti-pattern to avoid:** one `EventManager` god class with all counters and email logic.

---

## Phase 3: API + Happy Path (15–25 min)

### Interviewer

> “Show me the public API and walk a successful registration.”

### Interfaces (write these)

```java
public interface EventService {
    EventId createEvent(CreateEventCommand cmd);           // organizer
    void publish(EventId id);
    void cancelEvent(EventId id);                          // cascades policy
    Optional<EventView> get(EventId id);
    List<EventSummary> search(SearchQuery q);
}

public interface RegistrationService {
    HoldResult hold(HoldCommand cmd);                      // ticketTypeId → qty
    ConfirmResult confirm(HoldToken token, PaymentIntent p, String idempotencyKey);
    void cancel(RegistrationId id, UserId requester);
    RegistrationView get(RegistrationId id);
}
```

### Sequence (narrate while drawing)

```
Attendee          RegistrationService       TicketInventory        Payment
   │                      │                        │                  │
   │── hold(VIP,2) ──────▶│                        │                  │
   │                      │── tryHold(2) ─────────▶│                  │
   │                      │◀── ok (held+=2) ───────│                  │
   │◀─ HoldToken(TTL) ────│                        │                  │
   │                      │                        │                  │
   │── confirm(token,pay)▶│── charge ─────────────────────────────────▶│
   │                      │◀── paymentRef ────────────────────────────│
   │                      │── confirmHold(2) ─────▶│                  │
   │                      │   (held-=2, conf+=2)   │                  │
   │◀─ Registration CONFIRMED ─────────────────────│                  │
```

### Candidate talking points

- Hold is **all-or-nothing** for the requested line items.
- Confirm checks: token exists, not expired, ownership, payment success, then inventory transition **atomically** with registration status.
- Search is eventually consistent OK; **register path is strongly consistent** on inventory.

### Sample request shapes (if they want REST)

```
POST /events
POST /events/{id}/publish
POST /events/{id}/holds          → { ticketLines, userId } → holdToken
POST /registrations/confirm      → { holdToken, payment, Idempotency-Key }
DELETE /registrations/{id}
GET  /events?city=&from=&to=
```

---

## Phase 4: Hard Parts — Concurrency & Failures (25–35 min)

### Interviewer probes (answer each in 30–60s)

#### Q1: Two users race for the last ticket. What happens?

**You say:**  
> “Inventory is the lock boundary. `tryHold` under a per-ticket-type (or per-event) lock / DB row version checks `held + confirmed + qty ≤ capacity`. Exactly one succeeds; the other gets `SoldOut`. I prefer **pessimistic lock or conditional update** on the inventory row over locking the whole Event.”

Sketch:

```java
boolean tryHold(int qty) {
  // synchronized(this) OR UPDATE ... WHERE held+confirmed+qty <= capacity
  if (held + confirmed + qty > capacity) return false;
  held += qty;
  return true;
}
```

#### Q2: Payment succeeds, confirm crashes. Now what?

**You say:**  
> “Confirm is **idempotent** on `idempotencyKey` / `paymentRef`. A retry or webhook completes confirm without a second charge or second inventory bump. If we held inventory and never confirm, **TTL expiry** releases hold. Reconciliation job: paid-but-not-confirmed → confirm or refund.”

#### Q3: How do holds expire?

**You say:**  
> “Inject `Clock`. On access, lazy-expire if `now > holdExpiry`. Plus a background sweeper for abandoned holds. Expiry calls `releaseHold` once (status → EXPIRED).”

#### Q4: Organizer cancels a published event with confirmed attendees?

**You say:**  
> “Policy: mark Event CANCELLED; stop new holds; bulk-cancel registrations; release inventory; enqueue refunds/notifications via outbox. Don’t silently delete history.”

#### Q5: Same user double-clicks Confirm?

**You say:**  
> “Idempotency key in a store keyed by `(userId, key)` → same `RegistrationId`. Second call is a no-op return.”

### Concurrency design choices (pick one and defend)

| Approach | Pros | Cons |
|----------|------|------|
| **Per-inventory synchronized / row lock** | Simple, correct | Hot lock on mega-events |
| **Optimistic version** on inventory | Better under low contention | Retries under hot contention |
| **Sharded counters** (advanced) | Scale | Harder exact capacity; usually overkill for LLD |

**TL recommendation for interview:** per-`TicketType` lock + idempotent confirm. Mention sharding only if asked for 100k QPS.

---

## Phase 5: Tests, Extensions, Tech Lead Manager lens (35–42 min)

### Testing strategy (say 4 tests)

1. **Oversell race:** N threads hold 1 when capacity=1 → exactly one success.
2. **Expiry:** advance `Clock` past TTL → hold released; another user can hold.
3. **Idempotent confirm:** two confirms same key → one inventory decrement from held→confirmed.
4. **Cancel:** confirmed → cancelled frees capacity; second cancel is no-op.

### Extensions (order them; don’t implement unless asked)

1. **Waitlist** — queue when sold out; promote on cancel (fairness + notify).
2. **Assigned seats** — reuse movie-booking seat map aggregate.
3. **Promo codes** — Strategy on pricing.
4. **Check-in / QR** — separate read model at door.
5. **Recurring series** — Occurrence child entities.

### Tech Lead / EM talking points (use if interview is TL/EM flavored)

| Topic | What a strong TL says |
|-------|------------------------|
| **MVP cut** | “Ship GA tickets + hold/confirm + cancel. Waitlist and seats are separate milestones.” |
| **Team ownership** | “Inventory + registration domain = Team A; search/catalog read models = Team B; payments adapter = platform.” |
| **Risk register** | “#1 risk is oversell under load; #2 is paid-not-confirmed; we bake idempotency and TTL in week 1.” |
| **Observability** | “Metrics: hold_fail_sold_out, confirm_latency, expired_holds, oversell_invariant_violations (should be 0).” |
| **Rollout** | “Feature flag publish; load test one hot event; chaos: kill confirm mid-flight.” |

**Soundbite:**  
> “As TL I’d rather have a boring, correct inventory aggregate than a clever distributed counter in v1.”

---

## Phase 6: Close (42–45 min)

### Interviewer

> “If you had 30 more minutes, what would you add?”

### Candidate close (30s)

> “I’d harden the **paid-not-confirmed reconciliation**, add **waitlist promotion** on cancel, and sketch the **DB schema** with a unique constraint on `(ticket_type_id)` version and idempotency table. Main risk remains **hot-event lock contention** — I’d measure before sharding. Consistency boundary stays on ticket inventory; search can be eventually consistent.”

### Optional self-check questions

1. What’s the one invariant you refuse to break? → `held + confirmed ≤ capacity`
2. What’s the consistency boundary? → Ticket inventory (per type / event)
3. What’s out of the domain? → SMTP, card networks, React UI

---

## Full candidate “speak track” (condensed rehearsal)

Use this if you want to **run the whole interview out loud in ~8 minutes** as a dry run.

1. **Open:** Single JVM LLD; create → publish → hold → confirm → cancel; ports for pay/notify.  
2. **Clarify:** GA quantities not seats; hold TTL; waitlist later; no oversell.  
3. **Model:** Event AR + TicketInventory counters; Registration AR; invariants.  
4. **API:** `hold` / `confirm` / `cancel`; sequence diagram.  
5. **Hard:** lock/conditional update on inventory; idempotent confirm; clock-based expiry.  
6. **Close:** tests for race + expiry; TL cut MVP vs waitlist/seats.

---

## Evaluation Checklist (interviewer / self-score)

| Area | Strong | Adequate | Weak |
|------|--------|----------|------|
| **Scope** | Clear MVP + explicit out-of-scope | Vague “full Eventbrite” | Jumps to code |
| **Domain** | Event vs Registration aggregates; inventory boundary | Entities listed, weak boundaries | Anemic Manager |
| **Invariants** | States capacity inequality | Mentions “no oversell” only | Silent on double book |
| **API** | Hold/confirm split | Single `book()` only | No API |
| **Concurrency** | Lock/version + race outcome | “synchronized” with no boundary | Ignore races |
| **Idempotency / pay failure** | Key + reconciliation | Mentions retry vaguely | Ignore |
| **TL/EM (if leveled)** | MVP, risks, ownership | Feature laundry list | No prioritization |

---

## Red Flags to Avoid

- Starting with DB tables or microservices before nouns/invariants  
- One `EventManager` doing inventory + email + payment  
- Confirm without hold (no TTL story under payment latency)  
- Floating-point money  
- “Just use synchronized on the whole system”  
- Promising assigned seats + waitlist + referrals in the first 10 minutes  

---

## Likely follow-up prompts (keep ready)

| Prompt | Short answer |
|--------|--------------|
| “Make it waitlist-aware” | `WAITLISTED` status; FIFO queue per ticket type; on cancel → promote → notify → short confirm TTL |
| “Assigned seats” | New `SeatMap` aggregate per occurrence; hold seats not counters |
| “Multi-tenant white-label” | `TenantId` on Event; inventory still per event |
| “Scale to Coachella” | Partition by `eventId`; inventory service sticky by event; read replicas for catalog |

---

## Related in this repo

- Seat hold pattern: [../staff-engineer-interviews/08-movie-seat-booking.md](../staff-engineer-interviews/08-movie-seat-booking.md)  
- Scheduling / time: [../staff-engineer-interviews/12-meeting-scheduler.md](../staff-engineer-interviews/12-meeting-scheduler.md)  
- Domain modeling meta: [../staff-engineer-interviews/00-domain-models-and-layers.md](../staff-engineer-interviews/00-domain-models-and-layers.md)
