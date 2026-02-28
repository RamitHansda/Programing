# Interview Script: Cryptocurrency Exchange Order Book (System Design)

Use this script to run or practice a **45–60 minute** system design interview on the crypto exchange order book. Works for both **interviewer** (questions + what to listen for) and **candidate** (phases + key points to hit).

---

## Interview Setup

| Item | Suggestion |
|------|------------|
| **Duration** | 45 min (compact) or 60 min (with deep dive) |
| **Format** | Whiteboard / doc; candidate drives, interviewer asks and nudges |
| **Prompt** | "Design the system for a cryptocurrency exchange order book — users submit orders, they get matched, and we need to expose book depth and trades." |
| **References** | [SYSTEM_DESIGN.md](SYSTEM_DESIGN.md), [HLD.md](HLD.md) |

---

## Phase 1: Clarify Requirements (5–8 min)

**Interviewer:** Start with the prompt above, then use these as needed.

### Questions to ask

1. **Scope**  
   *"What should we focus on — just the matching engine, or the full flow from user to persistence and market data?"*  
   - Listen for: willingness to scope (e.g. "full system" vs "matching only") and mentioning APIs, persistence, market data.

2. **Scale**  
   *"What scale do you have in mind — orders per second, number of symbols, number of users?"*  
   - Listen for: concrete numbers (e.g. 10k–100k orders/sec, 100–1000 symbols) or "high throughput / low latency."

3. **Order types**  
   *"What order types must we support?"*  
   - Listen for: limit, market (or "market as limit at extreme price"), maybe cancel/amend. IOC/FOK if senior.

4. **Consistency**  
   *"What are the guarantees we need — e.g. no double-fill, strict ordering?"*  
   - Listen for: exactly-once or at-least-once with idempotency, ordering per symbol.

5. **Out of scope**  
   *"What are we explicitly not building today?"*  
   - Listen for: clearing/settlement, custody, KYC, multi-venue routing, etc.

**Wrap:** *"So we’re designing [X]. Let’s start with the high-level architecture."*

---

## Phase 2: High-Level Design (10–15 min)

**Interviewer:** Let the candidate draw and describe. Nudge only if they skip a whole layer.

### Questions to ask

1. *"Walk me through the path of an order from the moment a user submits it until they see a fill."*
2. *"What are the main components, and what does each one do?"*
3. *"Where does the actual matching happen, and what does it consume/produce?"*

### What to listen for

| Component | Candidate should mention |
|-----------|---------------------------|
| **Client / API** | REST or WebSocket for orders; auth; maybe separate channel for market data |
| **Gateway** | Something that validates orders, assigns IDs, maybe rate limits; forwards to matching |
| **Matching engine** | Core: takes orders, matches against a book, outputs trades and book updates |
| **Order book** | In-memory; two sides (bid/ask); some notion of "best" price |
| **Downstream** | Persistence (orders, fills, log); market data (depth, trades); risk/reporting if they mention it |

**Nudge if missing:**  
- *"How do we make sure we don’t process the same order twice?"* → idempotency / order ID.  
- *"Where do we store orders and trades for history and recovery?"* → event log / DB.

---

## Phase 3: Order Book & Matching (15–20 min)

**Interviewer:** Go deep on the matching engine and data structures. This is the core.

### Questions to ask

1. *"How do you store bids and asks so we can always find the best price and the next order to match?"*
   - Listen for: **bids sorted by price descending**, **asks ascending**; at each price, **FIFO** (queue/list). TreeMap / sorted structure + queue per level is strong.

2. *"When a buy order comes in, how do you decide which sell orders to match against, and in what order?"*
   - Listen for: match against **lowest ask** first; at same price, **first in first out** (price-time priority).

3. *"Walk through one example: book has asks at 100, 101, 102. Incoming buy for 500 @ 101. What happens?"*
   - Listen for: fill at 100 until exhausted, then at 101 until order filled or level exhausted; remainder rests at 101 if any.

4. *"How do you support cancel and amend? How do you find an order quickly?"*
   - Listen for: **orderId → order** map (or pointer to level); cancel = remove from book and level; amend = cancel + new order or in-place update with care for time priority.

5. *"Why single-threaded (or strictly serialized) matching per symbol?"*
   - Listen for: no locks in hot path, deterministic order, simpler correctness; scale by partitioning across symbols.

**Nudge if missing:**  
- *"What’s the difference between maker and taker?"*  
- *"How do you represent price to avoid rounding issues?"* → fixed-point / decimal, not float.

---

## Phase 4: Scale, Reliability, Trade-offs (10–12 min)

**Interviewer:** Push on scale, failure, and choices.

### Questions to ask

1. *"We have 500 symbols and 50k orders per second. How do we scale the matching layer?"*  
   - Listen for: **partition by symbol**; one engine (or thread) per symbol or per group; each has its own book and event stream.

2. *"The matching engine crashes. How do we recover without losing orders or double-filling?"*  
   - Listen for: **durable event log** (append-only); replay to rebuild book; **snapshots** to shorten replay; **order IDs** and idempotency to avoid duplicates on replay.

3. *"How do we get the order book and last trades to millions of clients with low latency?"*  
   - Listen for: separate **market data service**; consume engine output; **incremental updates** (deltas); L2/L3; fan-out (multicast, queues, or cached depth + diff).

4. *"Trade-off: strong consistency for every read of the book vs availability. What would you choose at the matching layer and why?"*  
   - Listen for: matching is single-writer (per symbol), so strong consistency; read replicas or market data can be eventually consistent for display.

**Optional (if time):**  
- *"How would you add a REST API for 'my open orders' and 'my recent fills'?"* → query service backed by DB or materialized view from event log.

---

## Phase 5: Wrap-Up (3–5 min)

**Interviewer:** Open-ended and self-assessment.

### Questions to ask

1. *"If you had another 30 minutes, what would you refine or add?"*  
   - Listen for: monitoring/alerting, schema for events, rate limiting details, auth flow, or failure scenarios.

2. *"What’s the main bottleneck or risk in this design?"*  
   - Listen for: single-thread per symbol (throughput cap), event log throughput, or market data fan-out.

3. *"What would you do differently in production?"*  
   - Listen for: multi-dc, replay testing, circuit breakers, or operational runbooks.

---

## Evaluation Checklist (Interviewer)

Use this to score; adjust for level (e.g. L4 vs L6).

| Area | Strong | Adequate | Weak |
|------|--------|----------|------|
| **Requirements** | Clear scope, scale, and constraints | Scope stated; scale vague | No scope or scale |
| **Architecture** | All layers (API → gateway → engine → persistence → market data) | Most layers; one missing | Only matching or only APIs |
| **Order book** | Sorted by price + FIFO per level; correct side ordering | Correct idea, fuzzy structure | Wrong order (e.g. FIFO only) |
| **Matching** | Price-time priority; crossing rule; maker/taker | Correct idea, one slip | Wrong priority or no FIFO |
| **Cancel/amend** | orderId index; O(1) or O(log n) find | Mentioned; structure unclear | Not addressed |
| **Scale** | Partition by symbol; single-thread per symbol | Partitioning mentioned | No scaling strategy |
| **Reliability** | Event log + replay + snapshots | Log or replay | No recovery story |
| **Trade-offs** | Explains why (e.g. single-thread, decimal price) | States choice | No reasoning |

---

## Candidate Cheat Sheet (Practice Outline)

Use this to rehearse; don’t read verbatim in the interview.

### One-liner

*"Users submit limit/market orders; we match them by price-time priority in a central order book; we persist orders and trades and broadcast book depth and trades."*

### Flow (say it in 30 seconds)

1. Client → API Gateway (auth, rate limit) → Order Gateway (validate, assign orderId).
2. Order Gateway → Matching Engine (per symbol).
3. Matching Engine: match against opposite side (price-time), update in-memory book, emit trades + book deltas.
4. Events → event log (durability), DB (query), market data service (L2/L3 + trades to clients).

### Order book (say it in 20 seconds)

- **Bids:** sorted by price **descending** (best first). **Asks:** sorted **ascending**.
- At each price: **FIFO** queue of orders.
- **orderId → order** map for cancel/amend.
- **Price:** fixed-point or decimal.

### Matching (say it in 20 seconds)

- **Buy:** match against **lowest ask** while order price ≥ ask price; within a level, FIFO.
- **Sell:** match against **highest bid** while order price ≤ bid price; FIFO at level.
- **Output:** trades (taker vs maker), book updates (add/remove/change level).

### Scale

- **Partition by symbol;** one matching engine (or thread) per symbol.
- **Event log** partitioned; consumers scale by partition.
- **Market data:** separate service; incremental updates; fan-out to clients.

### Recovery

- **Event log** = source of truth; **replay** to rebuild book and state.
- **Snapshots** of book to reduce replay time.
- **Idempotency** (order IDs / keys) to avoid duplicate processing.

---

## Quick Reference: Key Terms

| Term | Meaning |
|------|--------|
| **Best bid / best ask** | Highest buy price, lowest sell price |
| **Spread** | Best ask − best bid |
| **Price-time priority** | Best price first; at same price, first in first out |
| **Maker / taker** | Maker = resting order; taker = incoming order that matches |
| **L2 / L3** | L2 = depth by price level; L3 = full order-level depth |
| **CLOB** | Central limit order book |

Good luck.
