# Candidate Runbook: Cryptocurrency Exchange Order Book

**You are the candidate.** Use this to prepare and to stay on track during the interview. Don’t read it verbatim — internalize the structure and key phrases.

---

## When They Give the Problem

**They’ll say something like:** *"Design the system for a cryptocurrency exchange order book. Users submit orders, they get matched, and we need to expose book depth and trades."*

**You:** Take 10–20 seconds. Then say:

*"I’ll clarify scope and assumptions, then draw the high-level flow, then go deep on the matching engine and data structures, and finally touch on scale and reliability."*

Then start **Phase 1** yourself (don’t wait for them to ask).

---

## Phase 1: Requirements (you drive this)

**Say:** *"A few clarifications:"*

| If they ask… | You say… |
|--------------|-----------|
| **Scope?** | *"I’ll design the full flow: clients → API → order gateway → matching engine → persistence and market data. Core will be the matching engine and order book."* |
| **Scale?** | *"Assume something like 10k–50k orders per second, hundreds of trading pairs (symbols), and many concurrent users. We care about low latency and correctness."* |
| **Order types?** | *"Limit and market — market can be a limit at an extreme price. Plus cancel and amend."* |
| **Guarantees?** | *"No double-fill; exactly-once or at-least-once with idempotency. Strict ordering per symbol so time priority is well-defined."* |
| **Out of scope?** | *"We’re not doing clearing, settlement, custody, KYC, or multi-venue routing — just the order book and matching plus APIs and distribution."* |

**Then:** *"So we’re designing the full order-book system with that scale. I’ll draw the architecture."*

---

## Phase 2: High-Level Design (draw and talk)

**Draw left to right:** Client → API Gateway → Order Gateway → Matching Engine → (three arrows down) Persistence | Trades | Market Data

**Say (in this order):**

1. *"Users hit an API Gateway — TLS, auth, rate limiting. Then the Order Gateway validates the order, assigns a unique order ID, and forwards to the matching engine."*
2. *"The matching engine holds the order book in memory — bids and asks — and matches incoming orders against the book using price-time priority. It outputs trades and book updates."*
3. *"Those events go to: an event log for durability and replay, a DB for querying orders and fills, and a market data service that pushes L2/L3 and trades to clients."*

**Must mention:**
- Unique **order ID** (and optional idempotency key) so we don’t process the same order twice.
- **Event log** as source of truth for recovery.

**If they ask "path of an order":**  
*"Order comes in → gateway validates and assigns ID → engine matches against opposite side → fills and book deltas go to event log, DB, and market data → client gets ack and fills over WebSocket."*

---

## Phase 3: Order Book & Matching (core — know this cold)

### "How do you store bids and asks?"

**Say:**  
*"One book per symbol. Two sides: bids and asks. Bids are sorted by price **descending** so the best bid is first; asks are sorted **ascending** so the best ask is first. At each price level we keep a **FIFO queue** of orders — that gives us time priority. So we need a sorted structure by price — e.g. TreeMap or equivalent — and at each price a queue. We also keep a map from **orderId to order** for cancel and amend."*

**Keywords:** bids descending, asks ascending, FIFO at each level, orderId map.

---

### "When a buy comes in, what do you match against?"

**Say:**  
*"We look at the ask side. We match against the **lowest ask** first — best price for the buyer. At that price we take orders in **FIFO** order. We keep matching until the incoming order is filled or the price no longer crosses. Any leftover quantity rests in the book as a new order on the bid side. Same idea for a sell: match against the **highest bid** first, FIFO at that level."*

**Keywords:** buy → lowest ask; sell → highest bid; FIFO at same price (price-time priority).

---

### "Example: asks at 100, 101, 102. Incoming buy 500 @ 101."

**Say:**  
*"We match at 100 first until that level is exhausted or we’ve filled 500. Say 100 has 200 — we fill 200 at 100, 300 left. Then we match at 101 until we’ve filled the remaining 300 or 101 is exhausted. If 101 has 400, we fill 300 at 101 and 100 stays at 101. So we get fills at 100 and 101; the resting 100 qty sits at 101 on the bid side."*

(Adjust numbers if they give different ones; logic is: always best price first, then FIFO.)

---

### "How do you support cancel and amend?"

**Say:**  
*"We need to find the order by ID in O(1) or O(log n). So we keep a map from **orderId to the order** — and ideally a reference to its price level so we can remove it from the queue. Cancel: remove from the map and from the level queue. Amend: usually cancel and place a new order so time priority is clear; some exchanges do in-place amend but you have to be careful with time priority."*

---

### "Why single-threaded per symbol?"

**Say:**  
*"So we don’t need locks in the hot path and the order of events is deterministic. We scale by **partitioning by symbol** — one engine or one thread per symbol, each with its own book and event stream."*

**If they ask maker/taker:**  
*"The **taker** is the incoming order that matches; the **maker** is the resting order that was already in the book."*

**If they ask price representation:**  
*"We use **fixed-point or decimal**, not float, to avoid rounding errors in money."*

---

## Phase 4: Scale & Reliability

### "500 symbols, 50k orders/sec — how do you scale?"

**Say:**  
*"**Partition by symbol.** Each symbol goes to one matching engine instance or one dedicated thread. So we might have hundreds of engine instances, each handling one or a few symbols. Each has its own in-memory book and writes to its own partition of the event log. Order gateway routes by symbol."*

---

### "Matching engine crashes — how do you recover?"

**Say:**  
*"We have a **durable, append-only event log** — e.g. Kafka — that receives every order and trade event. On recovery we **replay** from the log to rebuild the book. To speed that up we take **periodic snapshots** of the full book per symbol and replay only from the last snapshot. We use **order IDs** and optional idempotency keys so replay doesn’t double-process or double-fill."*

---

### "Order book and trades to millions of clients — how?"

**Say:**  
*"A **market data service** consumes the trade and book-delta stream from the engine. It maintains the current L2 or L3 state and **pushes incremental updates** to clients — e.g. over WebSocket. We can use fan-out (multicast or message queues) or cached depth with diffs so we don’t recompute everything per client."*

---

### "Strong consistency vs availability at the matching layer?"

**Say:**  
*"At the matching layer we have a **single writer per symbol**, so we get strong consistency for the book. For reads — e.g. market data — we can eventually consistent replicas or caches for display; the critical path is the single-threaded matcher."*

---

## Phase 5: Wrap-Up

**If they ask what you’d add:**  
*"Monitoring and alerting, clear event schemas, rate-limiting details, and runbooks for failover and replay."*

**If they ask main bottleneck/risk:**  
*"Single-thread throughput per symbol caps that symbol’s rate; event log throughput and market data fan-out are other places to watch."*

**If they ask production:**  
*"Multi-dc for failover, replay and chaos testing, circuit breakers, and idempotent clients."*

---

## One-Page Cheat (glance during interview)

| Topic | Must say |
|-------|----------|
| **One-liner** | Limit/market orders → price-time priority matching → persist + broadcast depth and trades. |
| **Flow** | Client → API GW → Order GW (validate, orderId) → Matching Engine → event log, DB, market data. |
| **Book** | Bids: price **desc**. Asks: price **asc**. At each price: **FIFO**. Map: **orderId → order**. |
| **Matching** | Buy → lowest ask, FIFO. Sell → highest bid, FIFO. Output: trades (taker/maker) + book deltas. |
| **Scale** | **Partition by symbol**; one engine per symbol. |
| **Recovery** | **Event log** + **replay** + **snapshots**; **idempotency** to avoid double-fill. |
| **Terms** | Best bid = highest buy; best ask = lowest sell; spread = best ask − best bid; L2 = depth by level; L3 = full order depth. |

---

## Refs to Review Before Interview

- [SYSTEM_DESIGN.md](SYSTEM_DESIGN.md) — full system.
- [HLD.md](HLD.md) — matching and data structures in detail.

Good luck.
