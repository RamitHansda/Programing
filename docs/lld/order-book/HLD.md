# High-Level Design: Order Book — Bid/Ask, Price-Time Priority, Matching Engine

**Audience:** Engineers designing or reviewing exchange/trading order-book systems. Covers core concepts and how they fit together.

---

## 1. Problem & Scope

**Goal:** Maintain a **central limit order book (CLOB)** that:

- Stores **bids** (buy orders) and **asks** (sell orders) for one or more instruments.
- Matches incoming orders against the book using **price-time priority**.
- Produces **trades** (fills) and **book updates** with predictable semantics and low latency.

**What we’re building:** A single-symbol (or multi-symbol) order book with a **matching engine** that executes orders according to standard exchange rules. No clearing/settlement, risk, or market-data distribution in scope here — just the book and matching.

---

## 2. Core Concepts

### 2.1 Bid / Ask

| Side | Meaning | Book behavior |
|------|--------|----------------|
| **Bid** | Buy order | Willing to buy at this price or better (lower). Stored in the **bid book** (buy side). |
| **Ask** | Sell order | Willing to sell at this price or better (higher). Stored in the **ask book** (sell side). |

- **Best bid** = highest buy price in the book.
- **Best ask** = lowest sell price in the book.
- **Spread** = best ask − best bid (when both exist).

Orders are **limit orders**: price and quantity. (Market orders can be modeled as limit orders at an extreme price.)

### 2.2 Price-Time Priority

**Matching rule:** Among orders at the same price, the one that **arrived first** is matched first.

- **Price priority:** Better price always wins. For a **sell**: match against the **highest bid** first. For a **buy**: match against the **lowest ask** first.
- **Time priority:** At the same price level, **first in, first out (FIFO)**.

So the book is ordered:

- **Bid side:** prices **descending** (best bid first), and at each price level orders in **arrival order**.
- **Ask side:** prices **ascending** (best ask first), and at each price level orders in **arrival order**.

### 2.3 Matching Engine

The **matching engine**:

1. **Accepts** an order (side, price, quantity, order ID, etc.).
2. **Matches** it against the **opposite side** of the book:
   - Buy order → match against asks (lowest price first, then FIFO at that price).
   - Sell order → match against bids (highest price first, then FIFO at that price).
3. **Stops** when either the order is fully filled or no more resting orders can match (price no longer crossing).
4. **Outputs**:
   - **Trades (fills):** price, quantity, taker order ID, maker order ID(s).
   - **Book updates:** new/cancel/amend orders, so the book state can be reconstructed or broadcast.

---

## 3. High-Level Architecture

```
                    ┌───────────────────────────────────────────────────────────────────┐
                    │                         ORDER GATEWAY                             │
                    │   Validate, enrich, assign order ID / timestamp                   │
                    └───────────────────────────┬───────────────────────────────────────┘
                                                │
                    ┌───────────────────────────▼───────────────────────────────────────┐
                    │                      MATCHING ENGINE                              │
                    │   Single-threaded (per symbol) or lock-protected critical section │
                    │   • Match incoming vs book                                        │
                    │   • Emit trades + book events                                     │
                    └───────────────────────────┬───────────────────────────────────────┘
                                                │
         ┌──────────────────────────────────────┼──────────────────────────────────────┐
         │                                      │                                      │
         ▼                                      ▼                                      ▼
┌─────────────────┐                 ┌──────────────────────┐                 ┌───────────────────┐
│  ORDER BOOK     │                 │  TRADE / FILL        │                 │  BOOK / DEPTH     │
│  (in-memory     │                 │  OUTPUT              │                 │  OUTPUT           │
│   bid + ask)    │                 │  (Fills, executions) │                 │  (L2/L3 updates)  │
└─────────────────┘                 └──────────────────────┘                 └───────────────────┘
```

**Order book** is the in-memory structure holding bids and asks. The **matching engine** is the logic that runs on each incoming order and updates the book + produces outputs.

---

## 4. Order Book Data Structures

### 4.1 Price Levels and Orders

- **Order:** `orderId`, `side` (BID/ASK), `price`, `quantity` (original + leaves quantity), `timestamp` (for time priority).
- **Price level:** Same price; multiple orders in **FIFO** (queue or linked list).
- **Book (per side):**
  - **Bids:** Sorted by price **descending** (e.g. `TreeMap<Price, PriceLevel>` or similar).
  - **Asks:** Sorted by price **ascending**.

So:

- **Bid book:** `TreeMap<Price, PriceLevel>` (or equivalent) with comparator for descending price.
- **Ask book:** `TreeMap<Price, PriceLevel>` with comparator for ascending price.
- **PriceLevel:** Queue/list of orders at that price (FIFO).

### 4.2 Lookup by Order ID

For **cancel** and **amend**, you need to find an order by ID:

- Keep a **map:** `orderId → Order` (or order + reference to its price level) so you can remove/update in O(log n) or O(1) depending on structure.

### 4.3 Diagram (Single Symbol)

```
                    ORDER BOOK (one symbol)
    ┌──────────────────────────────────────────────────────────┐
    │  BID SIDE (buy)              │  ASK SIDE (sell)          │
    │  Price DESC → best first     │  Price ASC → best first   │
    │  ─────────────────────────   │  ─────────────────────────│
    │  100.00 → [order1, order2]   │  100.50 → [order5]        │
    │   99.50 → [order3]           │  101.00 → [order6, order7]│
    │   99.00 → [order4]           │  ...                      │
    └──────────────────────────────────────────────────────────┘
                    │
                    │  Matching: buy @ 100.50 crosses with ask 100.50 (FIFO)
                    ▼
              MATCHING ENGINE
              → Trades (taker vs maker)
              → Book updates (remove/fill, new level)
```

---

## 5. Matching Engine Logic (Price-Time Priority)

**On incoming order:**

1. **Determine opposite side** (e.g. incoming BUY → look at ASK book).
2. **Check if crossing:** For BUY, crossing means order price ≥ best ask. For SELL, order price ≤ best bid.
3. **While crossing and order has quantity:**
   - Take **best price level** on the opposite side.
   - Within that level, take **first order** (FIFO).
   - **Match:** fill quantity = min(incoming leaves, resting order leaves).
   - Emit **trade(s)** (taker = incoming, maker = resting).
   - Decrease both quantities; remove resting order if filled.
   - If price level is empty, remove level; move to next best level.
4. **If incoming still has quantity:** insert remainder as **resting order** in the book (correct side, correct price, FIFO position by time).

**Price-time priority** is enforced by: (a) always choosing the best price first, and (b) at each price, always choosing the head of the FIFO queue.

---

## 6. Key Design Decisions

| Decision | Option chosen | Reason |
|----------|----------------|--------|
| **Ordering at a price** | FIFO (time priority) | Standard for many exchanges; simple and fair. |
| **Book per symbol** | One book per symbol | Clear isolation; allows single-threaded matching per symbol. |
| **Matching thread model** | Single-threaded per symbol (or strict serialization) | No locks in hot path; deterministic order of events. |
| **Order ID → order map** | Maintain index by orderId | Required for cancel/amend without scanning the book. |
| **Price representation** | Fixed-point or decimal (not float) | Avoids rounding errors in money. |
| **Output: trades + book** | Emit events (trades, add/cancel/amend) | Downstream can build state, replay, or broadcast depth. |

---

## 7. Non-Goals (Explicit)

- **Not** multi-venue aggregation or smart order routing.
- **Not** clearing, settlement, or margin.
- **Not** market-data distribution (top of book, L2, L3) — only the mechanics to produce book updates.
- **Not** FIX/API protocol design — abstract order submission/cancel/amend.
- **Not** persistence/recovery of book (can be added as a separate layer consuming the same events).

---

## 8. Minimal Class / Component Sketch

```
┌─────────────────────┐     ┌─────────────────────┐     ┌─────────────────────┐
│  Order              │     │  OrderBook          │     │  MatchingEngine     │
│  orderId, side,     │     │  bids: TreeMap      │     │  match(Order)       │
│  price, qty, time   │     │  asks: TreeMap      │     │  → List<Trade>      │
└──────────┬──────────┘     │  orderById: Map     │     │  → BookEvents       │
           │                └──────────┬──────────┘     └──────────┬──────────┘
           │                           │                           │
           │                ┌──────────▼──────────┐                │
           └───────────────►  PriceLevel          │◄───────────────┘
                            │  price, orders:     │
                            │  Queue<Order> (FIFO)│
                            └─────────────────────┘
```

- **Order:** Immutable or versioned; has remaining (leaves) quantity.
- **PriceLevel:** One price, queue of orders (FIFO).
- **OrderBook:** Bids + asks + `orderId` index; methods: add, cancel, amend (via remove + add if needed).
- **MatchingEngine:** Takes order, runs matching algorithm against OrderBook, returns trades and book deltas.

---

## 9. Summary

- **Bid/ask:** Two sides of the book (buy vs sell); best bid and best ask define the spread.
- **Price-time priority:** Best price first; at same price, first order in wins (FIFO).
- **Matching engine:** Consumes orders, matches against the opposite side using that rule, produces trades and book updates.

This HLD gives a solid basis to implement a single-symbol (or multi-symbol) order book and matching engine; persistence, APIs, and market data can be layered on top.
