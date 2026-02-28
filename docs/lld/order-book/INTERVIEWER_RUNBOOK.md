# Interviewer Runbook: Cryptocurrency Exchange Order Book

**Use this when you are conducting the interview.** Read the bold lines and questions; use the ✓ bullets to tick what the candidate covered. ~45–60 min total.

---

## Before You Start (1 min)

- Share screen/whiteboard; candidate will drive drawing.
- Say: *"We have 45 [or 60] minutes. I’ll give you a problem; you’ll clarify, design, and we’ll go deeper in a few areas. Ask questions anytime."*

---

## Opening (1 min)

**Say exactly:**

*"Design the system for a cryptocurrency exchange order book. Users submit orders, those orders get matched, and we need to expose order book depth and trades to clients. Go ahead — start by clarifying what we’re building and any assumptions."*

Then stay quiet. Let them ask questions or state scope.

---

## Phase 1: Requirements (5–8 min)

If they don’t cover these, ask **one at a time** (don’t read the list):

1. *"What should we focus on — just the matching engine, or the full flow from user to persistence and market data?"*  
   ✓ Full system vs matching-only; APIs, persistence, market data mentioned.

2. *"What scale are we designing for — orders per second, number of trading pairs, users?"*  
   ✓ Some numbers or “high throughput / low latency.”

3. *"What order types do we need to support?"*  
   ✓ Limit, market; cancel/amend. (IOC/FOK is a plus.)

4. *"What guarantees do we need — e.g. no double-fill, ordering?"*  
   ✓ Exactly-once or idempotency; ordering per symbol.

5. *"What are we explicitly not building today?"*  
   ✓ Clearing, custody, KYC, multi-venue routing, etc.

**Transition:** *"Good. So we’re designing [repeat their scope in one sentence]. Draw the high-level architecture — the path of an order from user to fill."*

---

## Phase 2: High-Level Design (10–15 min)

**Say:**

*"Walk me through the path of an order from the moment a user submits it until they see a fill. What are the main components and what does each do?"*

Let them draw. Then ask:

*"Where does the actual matching happen, and what does it consume and produce?"*

**Tick what they mention:**

- ✓ Client / API (REST or WebSocket, auth)
- ✓ Gateway that validates, assigns order ID, maybe rate limits
- ✓ Matching engine that takes orders and outputs trades + book updates
- ✓ Order book (in-memory, two sides, “best” price)
- ✓ Persistence (event log or DB) and/or market data

**If they skip “no double process”:**  
*"How do we make sure we don’t process the same order twice?"*  
✓ Idempotency / unique order ID.

**If they skip storage:**  
*"Where do we store orders and trades for history and recovery?"*  
✓ Event log, DB, or similar.

**Transition:** *"Let’s go deeper into the matching engine and the order book data structures."*

---

## Phase 3: Order Book & Matching (15–20 min)

Ask in this order; wait for an answer before moving on.

**Q1:** *"How do you store bids and asks so we can always find the best price and the next order to match?"*  
- ✓ Bids sorted by price **descending**, asks **ascending**.  
- ✓ At each price, orders in **FIFO** (queue or list).  
- ✓ TreeMap/sorted map + queue per level is strong.

**Q2:** *"When a buy order comes in, how do you decide which sell orders to match against, and in what order?"*  
- ✓ Match against **lowest ask** first; at same price, **first in, first out** (price-time priority).

**Q3:** *"Walk through an example: the book has asks at 100, 101, 102. Incoming buy for quantity 500 at price 101. What happens?"*  
- ✓ Fills at 100 first, then 101; remainder rests at 101 if any.

**Q4:** *"How do you support cancel and amend? How do you find an order quickly?"*  
- ✓ Map from **orderId → order** (or to level); cancel = remove; amend = cancel + new or careful in-place.

**Q5:** *"Why single-threaded or strictly serialized matching per symbol?"*  
- ✓ No locks in hot path; deterministic; scale by partitioning across symbols.

**If they don’t mention:**  
- Maker vs taker → *"What’s the difference between maker and taker?"*  
- Price representation → *"How do you represent price to avoid rounding errors?"*  
✓ Fixed-point or decimal, not float.

---

## Phase 4: Scale & Reliability (10–12 min)

**Q1:** *"We have 500 trading pairs and 50k orders per second. How do we scale the matching layer?"*  
- ✓ **Partition by symbol**; one engine (or thread) per symbol or group; own book and stream per partition.

**Q2:** *"The matching engine crashes. How do we recover without losing orders or double-filling?"*  
- ✓ **Durable event log** (append-only); **replay** to rebuild book; **snapshots** to shorten replay; **order IDs / idempotency** to avoid duplicates.

**Q3:** *"How do we get the order book and last trades to millions of clients with low latency?"*  
- ✓ Separate **market data service**; consume engine output; **incremental updates** (deltas); L2/L3; fan-out (multicast, queues, or cached depth + diff).

**Q4 (optional):** *"Trade-off: strong consistency for every read of the book vs availability. What would you choose at the matching layer and why?"*  
- ✓ Matching: single-writer, strong consistency; market data/reads can be eventually consistent.

**If time:** *"How would you add a REST API for ‘my open orders’ and ‘my recent fills’?"*  
- ✓ Query service backed by DB or materialized view from event log.

---

## Phase 5: Wrap-Up (3–5 min)

Pick 1–2:

1. *"If you had another 30 minutes, what would you refine or add?"*  
2. *"What’s the main bottleneck or risk in this design?"*  
3. *"What would you do differently in production?"*

Then: *"We’re out of time. Any questions for me?"*

---

## Scoring (fill after interview)

| Area | Strong ✓ | Adequate | Weak |
|------|----------|----------|------|
| Requirements (scope, scale) | | | |
| Architecture (all layers) | | | |
| Order book (price order + FIFO) | | | |
| Matching (price-time, crossing) | | | |
| Cancel/amend (orderId index) | | | |
| Scale (partition by symbol) | | | |
| Reliability (log + replay + snapshots) | | | |
| Trade-offs (explains why) | | | |

**Strong** = would ship with minor tweaks. **Adequate** = right idea, gaps. **Weak** = wrong or missing.

---

## Quick reference (for you only)

- **Best bid** = highest buy price; **best ask** = lowest sell price.  
- **Price-time** = best price first, then FIFO at that price.  
- **Maker** = resting order; **taker** = incoming order that matches.  
- **L2** = depth by level; **L3** = full order-level depth.

**Refs:** [SYSTEM_DESIGN.md](SYSTEM_DESIGN.md) · [HLD.md](HLD.md)
