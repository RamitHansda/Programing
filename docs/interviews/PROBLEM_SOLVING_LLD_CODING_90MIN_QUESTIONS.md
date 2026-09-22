# Problem Solving, LLD and Coding (90 Mins) — Reported Questions

Research compiled from candidate interview reports (LeetCode Discuss, Interview Experiences, Glassdoor/LinkedIn writeups, company engineering blogs) and aligned with the attached **LLD + Coding Interview Preparation Guide**.

> **How to use this:** This round is a **machine-coding / LLD+implement** format. The problem statement will likely be **new**, not one of these exact titles. Drill the **patterns** (state machines, inventory reservation, concurrency, idempotency, APIs) using the high-frequency prompts below.

---

## 1. What this round actually is

| Attribute | Typical expectation |
| --- | --- |
| Duration | **90 minutes** (sometimes 60–120) |
| Deliverable | **Working, runnable code** (not just a class diagram) |
| Storage | Usually **in-memory** (HashMap / lists); some variants want HTTP APIs + Postman |
| After coding | Walkthrough / code review: design choices, edge cases, concurrency, extensions |
| Evaluation | Correctness + OOP structure + extensibility + edge cases; patterns only when useful |

Companies that commonly run this format: **Flipkart, PhonePe, Swiggy, Zepto/Blinkit-style, Meesho, CRED, Razorpay, Uber India, Amazon India, DoorDash (CodeCraft / AI-assisted), Postman**, and others.

The prep guide’s own attached interview used a **quick-commerce order-management** problem (create / cancel / status / inventory / state transitions). Treat that as one instance of a broader family — do **not** memorize only that domain.

---

## 2. Highest-priority questions (practice these first)

These appear most often in 90-min LLD/machine-coding reports and map directly to skills the prep guide emphasizes (entities, APIs, state machines, business rules, idempotency, concurrency).

### A. Quick commerce / order + inventory (closest to the attached interview)

| # | Question | What they probe | Reported at / style |
| --- | --- | --- | --- |
| 1 | **Quick-commerce Order Management** — create order, cancel, track status, inventory holds, legal state transitions | State machine, inventory reserve/release, concurrency on last unit | Prep-guide attached interview; Zepto/Blinkit-style rounds |
| 2 | **Flipkart Minutes** — onboard customers & delivery partners; place/cancel orders; auto-assign partners; queue when none free; pickup blocks cancel; thread-safety | Assignment queue, partner capacity = 1, cancel vs pickup race | Flipkart machine coding |
| 3 | **Order Management System (Flipkart-like)** — internal + external inventory; create → reserve → confirm (block) → fulfill / cancel; optional auto-cancel TTL | Reserved vs blocked stock, external seller API, race conditions | PhonePe machine coding |
| 4 | **Inventory reservation service** — `blockInventory` / `confirmOrder` / expiry release (e.g. 5 min hold) | Atomic reserve, TTL cleanup, no double-sell | Meesho machine coding |
| 5 | **Dark-store inventory + order assignment** — nearby store, reserve stock, assign delivery partner, multi-store split | Proximity, stock truth, fulfillment pipeline | Zepto LLD / machine-coding reports |
| 6 | **Order status tracker** — placed → picking → packed → dispatched → delivered (+ cancel rules) | Legal transitions, compensating actions (release reservation) | Quick-commerce style rounds |
| 7 | **Food delivery order state machine** — placed → accepted → preparing → out for delivery → delivered / cancelled | Domain state machine + notifications | Swiggy |

**Core model to internalize for this family:**

```
on_hand = reserved + available
place   → RESERVE (available → reserved)
confirm → COMMIT  (reserved leaves inventory)  // or BLOCK after payment
cancel  → RELEASE (reserved → available)
```

Classic follow-ups interviewers always ask:

1. Two orders race for the last unit — who wins?
2. Same create/cancel request arrives twice (idempotency)?
3. Cancel after pickup / after partial pick?
4. Payment never confirms — how does reserved stock free (TTL)?
5. How would you test concurrent place/cancel?

---

### B. Classic machine-coding staples (asked everywhere)

| # | Question | Patterns / focus | Companies (reported) |
| --- | --- | --- | --- |
| 8 | **Parking Lot** | Strategy (pricing/spot), multi-floor, vehicle types, concurrency | Flipkart, Amazon, Uber, PhonePe, Adobe |
| 9 | **Snake & Ladder** | Board config, turns, win rules | Amazon, Flipkart, Swiggy, CRED, Adobe |
| 10 | **Vending Machine** | **State** pattern, inventory, change | Google, Amazon, Microsoft |
| 11 | **Elevator System** | SCAN/LOOK, multi-car dispatch, State | Amazon, Microsoft, Uber, Tekion |
| 12 | **Movie Ticket Booking / BookMyShow** | Seat lock, concurrent booking | Amazon, BookMyShow, Paytm, Tekion |
| 13 | **Cab / Ride booking (Uber/Ola)** | Matching strategy, trip state machine | Uber, Ola, Swiggy |
| 14 | **Splitwise** | Equal/exact/% strategies, settle | Swiggy, Razorpay, Flipkart |
| 15 | **Chess game** | Piece polymorphism, move validation | Amazon, Adobe, Microsoft, Tekion |
| 16 | **LRU / LFU Cache** | LinkedHashMap / heap+hash, O(1) ops | Uber, Amazon, Coupang, CRED |
| 17 | **Rate Limiter** | Token bucket / sliding window, thread-safe | Stripe, Amazon, Uber, Razorpay, Postman |
| 18 | **Online Shopping Cart** | Pricing/coupon **Strategy**, checkout | Amazon, Flipkart, Walmart |

---

### C. Strong secondary set (senior / product-domain rounds)

| # | Question | Notes | Reported at |
| --- | --- | --- | --- |
| 19 | **Payment processing package** | Method Strategy/Factory, txn state machine, idempotency, tests | CRED (90 min) |
| 20 | **Payment wallet / P2P transfer** | Ledger, atomic transfer, overdraft, idempotency keys | Razorpay-style |
| 21 | **Doctor / clinic appointment booking** | Slots, waitlist promote on cancel, concurrency follow-up | Meesho (90 min HackerRank) |
| 22 | **Restaurant catalog** (items, variants, add-ons) | Nested composition, pricing combinations | Swiggy |
| 23 | **Order-slot pricing with surge** | Capacity → multiplier, nearest slot by time | Swiggy SDE-3 |
| 24 | **Job / task scheduler** | Delay, priority, cancel, pause | Swiggy, DoorDash-style |
| 25 | **Customer issue resolution** | Agent assignment strategy | PhonePe |
| 26 | **URL Shortener (HTTP APIs)** | Collision, redirect, Postman demo | Postman |
| 27 | **Nested comments / product review system** | Tree model, REST, runnable service | Postman |
| 28 | **Calendar / meeting scheduler** | Overlap detection, free slots | Uber LLD, Media.net (90+ min) |
| 29 | **File system (in-memory)** | mkdir/ls/read/write, Composite | Amazon, Coupang |
| 30 | **Notification service** | Channel Factory, retry, Observer | Amazon, Uber, Walmart |
| 31 | **Library / hotel / restaurant booking** | Reservation conflicts, waitlist | Common LLD banks |
| 32 | **Pub/Sub mini-Kafka** | Topics, consumers, offsets | Razorpay-style advanced |
| 33 | **Workflow / support automation DAG** | Order status → refund rules | DoorDash CodeCraft / AI coding |
| 34 | **Loan / EMI management** | Schedule mutation on advance pay | ServiceNow LLD reports |

Domains the prep guide says you should be ready for even if not listed above: **banking, scheduling, subscription management, workflow systems, file management**.

---

## 3. Detailed problem briefs (most likely shapes)

### 3.1 Quick-commerce Order Management (prep-guide style)

**Build:** APIs/services for an instant-delivery order system.

Typical mandatory features:

- Create order with items + quantities
- Cancel order under rules (e.g. only before packed/picked up)
- Transition statuses: `CREATED → ACCEPTED → PACKED → OUT_FOR_DELIVERY → DELIVERED` (+ `CANCELLED` / `FAILED`)
- Inventory: check availability, reserve on create, release on cancel, deduct on fulfill
- Get order status / list orders by user

Often added under time pressure:

- Idempotent create (`Idempotency-Key`)
- Concurrent checkout of last SKU
- Auto-cancel unconfirmed orders after TTL
- Delivery partner assignment queue
- HTTP endpoints + Postman/curl demo (if the interview provides a server)

### 3.2 Flipkart Minutes (reported Flipkart machine coding)

- Onboard customers and delivery partners
- Place order (items always available in some variants)
- Cancel only before pickup
- Auto-assign free partner; else queue
- Partner handles **one** order at a time
- Pickup → cannot cancel; mark delivered
- Thread-safe; in-memory; demo/driver required
- Bonus: notifications, ratings, top partners, auto-cancel after 30 min unassigned

### 3.3 PhonePe — Order Management like Flipkart (reported)

- Inventory types: **INTERNAL** (managed locally) vs **EXTERNAL** (seller APIs: get/reserve/block)
- `createOrder` reserves provisionally
- `updateOrder(orderId, state)`:
  - **CONFIRMED** — payment success → inventory **BLOCKED**
  - **CANCELLED** — unblock reserved/blocked
  - **FULFILLED** — only from confirmed; free blocked stock
- Optional: auto-cancel if not confirmed within N seconds
- Race conditions + proper error codes required

### 3.4 Meesho — Inventory block/confirm (reported)

- `addProduct` / `getInventory` / `updateInventory` (restock)
- `blockInventory(productId, qty, orderId)` — hold for ~5 minutes
- `confirmOrder(orderId)` — permanently deduct; else expiry releases hold
- Concurrency + background expiry cleanup

### 3.5 CRED — Payment Processor (reported, 90 min)

Implement a payment-processing package with:

- Multiple payment methods (Strategy/Factory)
- Transaction lifecycle states
- Error handling + **unit tests** (timeouts, idempotency collision, invalid refund state)

---

## 4. What interviewers grade (from reports + prep guide)

1. **Requirements coverage** — every stated functional requirement actually implemented and demoed
2. **Entity & API design** — clear resources/operations, not a god class
3. **State machine correctness** — invalid transitions rejected
4. **Business rules encoded in the system** — not left to the caller
5. **Idempotency & concurrency** — duplicate and simultaneous requests
6. **Working demo** — `main` / tests / Postman against a running server
7. **Extensibility without over-patterning** — Strategy/State/Factory/Repository when they earn their keep

---

## 5. 90-minute time box (from the prep guide)

| Window | Focus |
| --- | --- |
| 0–10 min | Requirements, entities, APIs, states/rules |
| 10–50 min | Core happy path + runnable app |
| 50–75 min | Edge cases, concurrency, idempotency, tests |
| 75–90 min | Re-read requirements, demo, limitations |

---

## 6. Practice plan (if you only have a few days)

**Day 1 — Order + inventory family (must):**

1. Quick-commerce order management (full APIs + state machine + reserve/release)
2. PhonePe-style OMS (confirm/block/fulfill + TTL cancel)
3. Concurrent “last unit” + duplicate-request tests

**Day 2 — Classics:**

4. Parking Lot  
5. Splitwise **or** BookMyShow seat booking  
6. Rate limiter **or** LRU cache  

**Day 3 — Domain stretch:**

7. Ride booking **or** Elevator  
8. Payment wallet / processor  
9. Job scheduler (delay + priority)

For each: timer on, working demo first, then one extension (“add priority orders”, “add external inventory”, “make create idempotent”).

---

## 7. Sources (representative)

- Attached: *LLD + Coding Interview — Preparation Guide* (quick-commerce OMS example; AI-allowed variant; Postman/HTTP testing notes)
- Flipkart Minutes machine-coding writeup — Interview Experiences
- PhonePe OMS machine coding — LeetCode Discuss (Aug 2023)
- Meesho inventory / clinic booking machine coding — Interview Experiences / LeetCode Discuss
- Zepto / quick-commerce LLD reports — LeetCode Discuss, LinkedIn, Gronex-style analyses
- Swiggy machine-coding lists — SpaceComplexity / Interview Experiences
- CRED payment processor — candidate offer writeup (2024)
- DoorDash CodeCraft / AI-assisted order workflow — DoorDash careers blog + Reddit LLD
- Aggregated top-10 machine-coding lists — Low Level Design Mastery, InterviewLoop, SystemCraft

---

## 8. Rippling (similar LLD + coding round — different problem surface)

Rippling runs the same *style* of round (OOP + working code + extensibility), but prompts are **payroll / expense / HR-domain**, not Parking Lot or quick-commerce OMS.

**Most reported Rippling LLD/coding questions:**

1. **Delivery cost / driver payroll dashboard** — `addDriver`, `recordDelivery`, `getTotalCost`, then `payUpTo` + unpaid, then max simultaneous drivers in 24h (sweep line)
2. **Expense / corporate-card rules engine** — per-expense rules, then trip-level aggregates; Strategy/OCP; discuss return type first
3. **Generic groupBy / filter / aggregate** on employee records
4. **2D canvas** draw/move rectangles (bounded → infinite sparse grid)
5. **Task scheduler** — dedupe, priority sort, parent-child ordering (AI sometimes allowed)
6. **Document analyzer**, **configurable logger**, music analytics (unique listeners)

Full Rippling writeup with APIs and practice plan: [`RIPPLING_LLD_CODING_QUESTIONS.md`](./RIPPLING_LLD_CODING_QUESTIONS.md).

---

## 9. Bottom line

There is **no fixed question bank** for “Problem Solving, LLD and Coding (90 Mins)” — companies rotate prompts. Public reports cluster heavily around:

1. **Order / inventory / fulfillment state machines** (especially quick commerce)  
2. **Parking Lot / games / elevators / booking systems**  
3. **Fintech: payments, wallets, Splitwise**  
4. **Infra primitives: cache, rate limiter, scheduler, file system**  
5. **Rippling-style:** payroll cost, expense rules, groupBy, schedulers

Prepare the **framework** in the prep guide; drill the **order+inventory** family for general 90-min rounds; if the company is **Rippling**, switch priority to **delivery payroll + expense rules**. Do not bet the round on memorizing one PDF example.
