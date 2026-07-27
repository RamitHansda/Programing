# Tekion Corp — LLD & HLD Question Bank (Recent Interviews, 2025–2026)

> **Note on the company name:** I couldn't find a company matching "Takion" — the closest, well-documented match with heavy LLD/HLD interview activity is **Tekion Corp** (cloud-based automotive retail platform, HQ Pleasanton, CA, large India engineering org in Bangalore/Chennai). This doc is compiled for **Tekion**. If you meant a different company, tell me the name/domain and I'll redo the research.

Compiled from ~15 independent, recent candidate-reported interview experiences (Jan 2025 – May 2026) across: LeetCode Discuss, GeeksforGeeks, AmbitionBox, CodingKaro, InterviewExperiences.in, Medium/InterviewRecap, DevBrainiac, Frontend Junction, and LearnYard. Sources are listed at the bottom with dates.

---

## 1. How Tekion Structures Its Interview Loop

Tekion's process is fairly consistent across reports — **3 to 4 rounds**, and design rounds (LLD/HLD) are treated as **eliminatory**, not a formality:

| Round | Typical Focus | Duration |
|---|---|---|
| Round 1 — DSA / Online Assessment | 2–3 LeetCode-style problems (easy–hard mix); sometimes includes MCQs on OOP/DSA fundamentals | 45–90 min |
| Round 2 — LLD (or "Bar Raiser") | Object-oriented design of a real-world system: classes, entities, relationships, extensibility, sometimes concurrency | 45–60 min |
| Round 3 — HLD | Scalable distributed system design: APIs, DB choice, caching, sharding, fault tolerance | 45–60 min |
| Round 4 — Hiring Manager / Managerial | Project deep-dive, design trade-offs from your own resume, behavioral/leadership questions, sometimes a lighter design question folded in | 45–60 min |

**Notes that recur across multiple reports:**
- LLD and HLD are sometimes **combined into a single round** for senior candidates (SDE-2/SSE and up), and sometimes **split into two separate rounds** for junior/mid candidates.
- Interviewers consistently push on **trade-offs and "why," not just a working design** — several rejections cite "wasn't satisfied with proposed trade-offs" even when the base design was correct.
- For backend roles, LLD often blends into **backend engineering fundamentals** (Kafka, concurrency, idempotency) rather than staying purely academic OOD.
- For frontend roles, "LLD" often means **machine coding** (build a working UI component) and "HLD" means **frontend system design / web performance** — not classic backend distributed systems.
- Follow-up depth is the norm: DB indexing/sharding, concurrency control, and "how would you debug this in production" are asked as a second layer on almost every design question.

---

## 2. Backend LLD Questions (Object-Oriented / Low-Level Design)

These recur most frequently — treat this list as your core practice set.

| # | Question | What they probed (from actual reports) |
|---|---|---|
| 1 | **Design a Parking Lot system** | Core entities (ParkingLot, Floor, Spot, Vehicle, Ticket), spot assignment by vehicle type, fee calculation, and a follow-up: *"how do you notify users who've parked >1 hour ago"* |
| 2 | **Design an Elevator System** (asked repeatedly — one of the most common Tekion LLD prompts) | Entities: `Elevator`, `Request`, `Floor`, `ElevatorController`, `Scheduler`. Follow-ups: multiple elevators, which elevator to dispatch, direction/SCAN-style scheduling, handling simultaneous requests, minimizing wait time, design-pattern choice (State/Strategy) for scheduling logic, state diagram for elevator states |
| 3 | **Design a Food Delivery system (LLD)** | Functional + non-functional requirements, entity/schema design (User, Restaurant, Order, Delivery, Payment), then a hard follow-up: **which tables get indexes and which get sharded, and why** |
| 4 | **Design BookMyShow / Movie Ticket Booking** | Detailed entity models, API contracts, and specifically **concurrency control for simultaneous seat bookings** (locking/optimistic concurrency to avoid double-booking) |
| 5 | **Design Snake & Ladder** | Logic/rules implementation, entity structure, and explicit ask to make it **extensible to support other board games** (tests OOD generalization, not just game logic) |
| 6 | **Design a Chess game** | Standard LLD — piece hierarchy, board representation, move validation, turn management |
| 7 | **Implement an LFU (Least Frequently Used) Cache** | Extensible, clean design expected within ~30 min; evaluated on code quality as much as correctness |
| 8 | **Design a vehicle parking app** (product-flavored variant, asked to PM/senior candidates) | Broader product+technical scope: spot reservation, GPS/real-time availability, payment integration, notifications |
| 9 | **"LLD for the human mouth"** (unusual/creative prompt, GFG report) | A deliberately odd real-world object used purely to test OOD instincts (entities, relationships, behaviors) outside a memorized template — be ready for at least one "weird" object modeling question |
| 10 | **API design + entities/schema for a "Notify Me" feature** (Amazon-style sale notification feature) | API contract design, data entities, relationships, DB schema, and how the feature integrates with an existing system |
| 11 | **Design a Word Guess game (Wordle-style)** — machine coding | Error/success-case handling, performance, extensibility, keyboard-state logic (more common on frontend loops, but also appears as a generic machine-coding LLD) |
| 12 | **Design an in-memory database / key-value store** | Data structure choice (hash table/tree) for fast access, caching, persistence strategy, concurrency control — explicitly compared to Redis/Memcached |
| 13 | **Design a Notification Service** | Delivery channels (email/SMS/push), user subscription preferences, scheduling, and (in the HLD-flavored version) the messaging backbone — see HLD section below |

**Recurring LLD follow-up questions, regardless of the base problem:**
- "Which design pattern would you use here, and why?" (Strategy for pricing/scheduling, State for elevator/booking status, Observer for notifications)
- "How would you make this extensible to support [a new variant] without rewriting core classes?"
- "Where would you introduce indexing? Where would you shard, and on what key?"
- "How do you prevent two concurrent requests from causing an inconsistent state?" (double-booking, double-parking, double-charging)

---

## 3. Backend HLD Questions (Scalable Distributed Systems)

| # | Question | What they probed |
|---|---|---|
| 1 | **Design a scalable distributed Web Crawler** (asked repeatedly, often paired with the Elevator LLD in the same loop) | URL frontier design, deduplication, parallel fetchers, rate-limiting, storage & indexing, fault tolerance, horizontal scaling strategy |
| 2 | **Design a Booking.com-style hotel reservation system** | Availability search, inventory locking to prevent overbooking, pricing, scaling reads vs. writes |
| 3 | **Design Zomato / a food delivery platform (HLD)** | Restaurant onboarding & menu management, search/discovery, order placement & payments, delivery assignment & real-time tracking, DB choice (SQL, sharded by `city_id`/geography), caching, event-driven architecture (Kafka topics partitioned by city), WebSocket-based real-time tracking, failure handling — **heavy emphasis on "why this DB / why this sharding key" follow-ups** |
| 4 | **Design a system for a continuous data stream** | Ingestion, buffering/backpressure, processing (stream vs. batch), storage — generic but tests distributed-systems fundamentals |
| 5 | **Design a URL Shortener (like bit.ly)** | Standard: ID generation, DB schema, caching, redirect flow, scale numbers |
| 6 | **Design a Rate Limiter** | Algorithm choice (token bucket / sliding window / leaky bucket), distributed rate limiting (Redis-based), per-user vs. global limits |
| 7 | **Design a real-time stock price update system** | Push vs. pull, WebSockets/SSE, fan-out at scale, data freshness vs. load trade-off |
| 8 | **Design a Notification Service (HLD version)** | Kafka/RabbitMQ as the backbone for high volume, user subscription/preference system, multi-channel delivery (email/SMS/push via APNs/FCM), delivery guarantees (at-least-once vs. exactly-once), scheduling |
| 9 | **Design a chat application** | WebSockets for real-time bidirectional messaging with long-polling fallback, connection registry (Redis), message persistence, group fan-out, offline-user handling |
| 10 | **Design an HLD for a franchise-based application** (agents selling products) | Roles (Admin/Franchise Owner/Agent) and permissions, product management, sales/commission tracking, geolocation |
| 11 | **RESTful PATCH API design (JSON Patch)** | API contract design specifically for partial updates — semantics of JSON Patch, idempotency of PATCH, versioning |
| 12 | **API latency debugging without code changes** | Diagnosing production latency purely via infra/config levers — caching, connection pooling, query plans, indexes, scaling out |

**Recurring HLD follow-up questions, regardless of the base problem:**
- "SQL vs. NoSQL here — justify it." / ACID properties, when you'd break them.
- "Where do you shard, and what's your partition key?" (`city_id` is the canonical answer for location-based systems like food delivery)
- "How do you avoid this becoming a single point of failure?"
- "Walk me through what happens at 10x traffic." — genuine scale/bottleneck follow-ups, not rhetorical.
- "Caching strategy — what do you cache, where, and how do you invalidate it?"

---

## 4. Backend Fundamentals Woven Into Design Rounds

Tekion interviewers frequently fold classic backend/distributed-systems fundamentals directly into the LLD/HLD conversation rather than asking them separately:

- **Kafka**: "Explain how Kafka works and its core components" → immediately followed by **"a producer successfully publishes messages but a consumer isn't receiving them — how do you debug it?"** (consumer group/offset/rebalance/ACL debugging expected)
- **Kafka delivery semantics**: "How do you implement at-least-once vs. at-most-once delivery?" (asked to someone who listed Kafka on their resume — expect this if Kafka is on yours)
- **Idempotency in payments**: "If retries happen in a payment system, how do you ensure the same request doesn't deduct balance twice?" (idempotency key bound to business intent, not transport ID — this is a very close cousin to the idempotency work already documented in your `EM-AMBIGUITY-QUALITY-INTERVIEW-PREP.md` payout incident story — reuse that reasoning directly)
- **Java concurrency**: threads, synchronization, race conditions, deadlocks — expected to speak from **production debugging experience**, not textbook definitions (lock ordering, timeout-based locking, concurrent collections)
- **Testing**: Mockito/dependency injection, mocking to isolate dependencies in unit tests
- **ACID properties**, **SQL vs. NoSQL trade-offs**, **process vs. thread**

---

## 5. Frontend-Specific LLD/HLD (if relevant to your role)

Tekion also runs a distinct frontend loop where "LLD" = machine coding and "HLD" = frontend system design, not backend distributed systems:

**Machine coding ("LLD") prompts reported:**
- Build a **Stopwatch** component
- Build a **Word Guess game** (Wordle-style) with keyboard state
- Build a **Dynamic Grid Component** (configurable rows/cols, rendered efficiently)
- Build a **React list with expand/collapse**
- Implement **polyfills**: `bind`, `call`, `apply`, `debounce`, `throttle`, `Promise.all`, `Promise` itself
- Implement **deep clone** in JavaScript
- Tic-Tac-Toe game logic

**"HLD" (frontend system design) prompts reported:**
- **Optimize the performance of tekion.com** — lazy loading/code splitting, SSR + caching, reducing CLS/LCP/TBT, optimizing API calls (this is asked almost verbatim across multiple reports — clearly a house favorite)
- **Design the Flipkart search bar** — pagination, throttling/debouncing, autocomplete, API response handling
- **Design the LinkedIn notification section** (verbal/architecture-level, not full machine coding)

**Frontend fundamentals woven in:** event loop/microtasks vs. macrotasks, closures, `this` binding, Promises/async-await, React hooks (`useEffect`, `useMemo`, `useCallback`), component lifecycle/render optimization, CSS positioning (`static`/`auto`/`fixed`), Redux usage rationale.

---

## 6. Product/Managerial-Flavored Design Questions (PM & senior/lead loops)

If your loop includes a product-thinking or hiring-manager design component:
- "Design an ad sales campaign" (PM round — target audience, channels, content strategy)
- "Design a vehicle parking app" (product framing — UX, GPS, reservations, payments, reviews)
- "Design an app for people living in societies" (community/resident engagement app — APM round)

---

## 7. What Separates a Pass from a Reject (patterns across reports)

Reading across the rejected vs. offered write-ups, the differentiators are consistent:

1. **Trade-off articulation, not just a working design.** Multiple rejections explicitly cite "interviewer wasn't satisfied with proposed trade-offs" even when the base design was functionally fine — e.g., an interviewer pushing on indexing/sharding choices until the candidate could defend the choice with reasoning, not just state it.
2. **Requirements-gathering discipline.** The offers consistently describe candidates who opened with functional/non-functional requirements before touching entities — don't skip straight to classes/schema.
3. **Design-pattern fluency applied contextually**, not memorized — State pattern for elevator/booking status, Strategy for pricing/scheduling, Observer for notifications — and the ability to say *why* that pattern fits this specific follow-up constraint.
4. **Comfort narrating production reality**: debugging a stuck Kafka consumer, deadlock resolution, idempotency under retries — these read as "have you actually operated this in prod," and shallow textbook answers are called out negatively in at least two reports.
5. **Concurrency-first thinking on anything transactional** (booking, parking, payments) — locking strategy is asked as a default follow-up, not an edge case.

---

## 8. Recommended Practice Set (priority order, based on frequency)

1. **Elevator System** — asked in at least 4 independent reports; know entities, scheduling strategy, and a state diagram cold.
2. **Parking Lot** — asked in at least 3 reports; know concurrency handling for spot assignment.
3. **Food Delivery (LLD + HLD)** — asked as both a schema/indexing LLD question and a full Zomato-style HLD; prepare both depths.
4. **BookMyShow / Ticket Booking** — concurrency control for booking is the crux; don't skip it.
5. **Web Crawler (HLD)** — asked repeatedly for SSE/senior loops.
6. **Snake & Ladder** — specifically test extensibility to other games; think about the OOD abstraction (not just this one game) before you start.
7. **Kafka debugging + idempotent payments** — these are near-guaranteed follow-ups if either Kafka or payments appears on your resume.
8. **Notification Service (LLD + HLD)** — both flavors have been asked; know the messaging backbone and the entity model.
9. If frontend: **tekion.com performance optimization** and **Flipkart search bar** — asked almost verbatim across multiple frontend reports.

---

## 9. Sources (all recent, dated where available)

| Source | Date reported | Round(s) covered |
|---|---|---|
| interviewexperiences.in — "Tekion Corp \| SDE 2" | March 2026 | LLD (Kafka, idempotent payments, food delivery, indexing/sharding) |
| interviewexperiences.in — "Tekion Interview \| SDE2" | 2025/2026 | LLD (BookMyShow) |
| Medium (Ritwik Chakraborty / InterviewRecap) — "Tekion SDE2" | May 2026 | API design (Notify Me), Snake & Ladder, Web Crawler HLD |
| DevBrainiac — "Tekion SDE-1 Backend" | May 2026 | Elevator LLD, Java concurrency, Mockito |
| LeetCode Discuss — "Tekion SDE-1 Interview experience" | 2025/2026 | Elevator LLD with state diagram + design pattern discussion |
| LeetCode Discuss — "Tekion Corp \| SSE \| Bangalore" | 2025/2026 | Parking Lot LLD, Snake & Ladder HLD, DB/ACID fundamentals |
| CodingKaro — 21 aggregated Tekion experiences | Jun 2025 – Jan 2026 | Parking Lot, Food Delivery LLD, continuous data stream HLD, Booking.com HLD, PATCH API design |
| GeeksforGeeks — Tekion Associate SWE | reported historically, pattern still cited in 2025/2026 roundups | Elevator LLD (multi-elevator dispatch) |
| GeeksforGeeks — Tekion Interview Experience | — | LLD "for the human mouth", ACID/SQL-NoSQL |
| Medium (Kashish Babbar) | March 2025 | LFU Cache LLD, Kafka delivery semantics |
| Medium (Sweta Kumari) | early 2025 | Chess game LLD |
| Frontend Junction — "Tekion Frontend — Offer Declined" | Jan 2026 | Word Guess machine coding, tekion.com performance HLD, Flipkart search bar |
| LearnYard — "My Tekion Frontend Engineer Interview Experience" | 2025 | Flipkart search bar, LinkedIn notification HLD |
| ScaleEngineer — Tekion L2 Software Engineer guide | — | Rate limiter, URL shortener, real-time stock prices |
| AmbitionBox — Tekion Interview Questions (aggregated) | rolling, updated Aug 2025 | In-memory DB, Notification Service, franchise-app HLD, vehicle parking app |

---

*Compiled for interview prep — reflects the most recent (2025–2026) candidate-reported LLD/HLD questions at Tekion Corp. If you can share the actual role/level and JD, I can narrow this down further and build round-by-round model answers the way the other prep docs in this repo do.*
