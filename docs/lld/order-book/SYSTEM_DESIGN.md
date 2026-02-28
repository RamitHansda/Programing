# System Design: Cryptocurrency Exchange Order Book

**Scope:** End-to-end system design for a crypto exchange order book — from client APIs to matching, persistence, and market data. Builds on the [HLD](HLD.md) (matching engine + book mechanics).

---

## 1. Overview

A **cryptocurrency exchange order book system** lets users place limit/market orders, matches them in real time (price-time priority), and exposes order book depth and trade data. This doc covers the full system: ingestion, matching, persistence, and distribution.

### 1.1 Goals

| Goal | Description |
|------|-------------|
| **Correctness** | Price-time priority, no double-fill, consistent book state. |
| **Low latency** | Sub-millisecond matching for hot path; minimal tail latency. |
| **Availability** | High uptime; failover and recovery without data loss. |
| **Scalability** | Many symbols, high order throughput, many concurrent users. |

### 1.2 Out of Scope (for this doc)

- Clearing, settlement, custody, KYC/AML.
- Smart order routing / multi-venue aggregation.
- Detailed FIX/WebSocket wire protocol specs.

---

## 2. High-Level Architecture

```
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│                                    CLIENTS (Traders / Bots)                              │
└───────────────────────────────────────────┬──────────────────────────────────────────────┘
                                            │
                    ┌───────────────────────┼───────────────────────┐
                    │  REST / WebSocket     │  WebSocket (orders)    │  Optional: FIX
                    │  (auth, order submit, │  (orders, acks,       │
                    │   cancel, account)    │   fills, book stream)  │
                    └───────────────────────┼───────────────────────┘
                                            │
┌───────────────────────────────────────────▼──────────────────────────────────────────────┐
│                                    API GATEWAY / LOAD BALANCER                           │
│  TLS termination, rate limiting, auth, request routing                                   │
└───────────────────────────────────────────┬──────────────────────────────────────────────┘
                                            │
┌───────────────────────────────────────────▼──────────────────────────────────────────────┐
│                                    ORDER GATEWAY (per region or cluster)                 │
│  Validate (size, price, symbol), enrich (user, timestamp), assign orderId, dedup         │
└───────────────────────────────────────────┬──────────────────────────────────────────────┘
                                            │
┌───────────────────────────────────────────▼──────────────────────────────────────────────┐
│                                    MATCHING ENGINE (core)                                │
│  Single-threaded per symbol (or lock per symbol)                                         │
│  • Order book (bids/asks, price-time)                                                    │
│  • Match incoming vs book → trades + book deltas                                         │
│  • Emit: Trade events, Book events (L2/L3)                                               │
└───────────────────────────────────────────┬──────────────────────────────────────────────┘
                                            │
         ┌──────────────────────────────────┼──────────────────────────────────┐
         │                                  │                                  │
         ▼                                  ▼                                  ▼
┌─────────────────┐              ┌─────────────────────┐              ┌─────────────────────┐
│  PERSISTENCE    │              │  TRADE / FILL       │              │  MARKET DATA        │
│  Event log /    │              │  PUBLISH            │              │  PUBLISH            │
│  DB (orders,    │              │  (internal +        │              │  (L2/L3, trades     │
│  fills, book    │              │   downstream)       │              │   to clients/       │
│  snapshots)     │              │                     │              │   data vendors)     │
└─────────────────┘              └─────────────────────┘              └─────────────────────┘
```

---

## 3. Core Components

### 3.1 API Gateway

- **TLS termination**, **rate limiting** (per user / IP), **authentication** (API keys, JWT, or session).
- Route to **Order Gateway** and optionally to **Market Data** services.
- Health checks and circuit breakers for downstream services.

### 3.2 Order Gateway

- **Validation:** symbol exists, price/quantity in valid range, order type supported.
- **Enrichment:** attach user ID, account ID, timestamp; **assign order ID** (globally unique).
- **Idempotency:** optional idempotency key to reject duplicates.
- **Serialization:** forward orders to the matching engine in a single logical stream (per symbol) to preserve order and time priority.

### 3.3 Matching Engine

- See [HLD](HLD.md) for full detail. Summary:
  - **One order book per symbol** (in-memory): bids (price desc), asks (price asc), FIFO at each price.
  - **Match** incoming order against opposite side (price-time priority); emit **trades** (taker vs maker) and **book deltas**.
  - **Single-threaded per symbol** (or strict serialization) to avoid locks and keep behavior deterministic.
- **Outputs:** stream of events: `OrderAccepted`, `OrderRejected`, `Trade`, `OrderCancelled`, `BookUpdate` (add/remove/change level).

### 3.4 Persistence

- **Event log (e.g. Kafka / custom log):** append-only stream of all order and trade events. Source of truth for replay and recovery.
- **Order and fill store (DB):** queryable by user/orderId (e.g. PostgreSQL or similar). Updated asynchronously from the event stream or from the engine’s output.
- **Book snapshots:** periodic or on-demand snapshots of the full book (per symbol) for fast recovery; replay events after snapshot to reach current state.

### 3.5 Trade / Fill Distribution

- **Internal:** risk, position, PnL, and reporting services consume trade events (from event log or direct stream).
- **External:** clients receive their fills via WebSocket (and optionally REST polling).

### 3.6 Market Data

- **L2 (depth):** top N levels (e.g. 10) bid/ask; **L3:** full order-level depth.
- **Trades:** public trade feed (price, size, side, time).
- **Mechanics:** matching engine emits book deltas and trades; a **market data service** maintains state (or rebuilds from events) and pushes to clients (WebSocket) or downstream systems.

---

## 4. Data Model (Summary)

### 4.1 Order

| Field | Description |
|-------|-------------|
| `orderId` | Unique ID (assigned by gateway or engine). |
| `userId` / `accountId` | Owner. |
| `symbol` | e.g. BTC-USD. |
| `side` | BID / ASK. |
| `price` | Limit price (fixed-point/decimal). |
| `quantity` | Original and leaves (remaining) quantity. |
| `timestamp` | For time priority. |
| `orderType` | LIMIT, MARKET (market = limit at extreme price). |

### 4.2 Trade (Fill)

| Field | Description |
|-------|-------------|
| `tradeId` | Unique. |
| `symbol`, `price`, `quantity` | Execution details. |
| `takerOrderId`, `makerOrderId` | Order IDs. |
| `takerSide` | BUY or SELL. |
| `timestamp` | Execution time. |

### 4.3 Book Level (L2)

- **Price**, **total quantity** at that price, optionally **number of orders** (L2.5).
- **L3:** full list of orders per level (order ID, size, time).

---

## 5. Critical Path (Order Flow)

1. **Client** sends order (REST or WebSocket) → **API Gateway**.
2. **Order Gateway** validates, enriches, assigns `orderId`, sends to **Matching Engine** (per-symbol queue or direct call).
3. **Matching Engine** (single-threaded for that symbol):
   - Matches against opposite side (price-time).
   - Updates in-memory book; emits **trades** and **book deltas**.
4. **Outputs**:
   - Events written to **event log** (durable).
   - **Trade/fill** and **book** events sent to **market data** and **persistence** (async).
5. **Client** receives **ack** and **fills** via WebSocket; **order book / trades** from market data feed.

---

## 6. Scaling and Throughput

| Concern | Approach |
|--------|----------|
| **Many symbols** | Partition by symbol: one matching engine instance (or thread) per symbol or per group of symbols; each has its own book and event stream. |
| **High order rate per symbol** | Keep matching single-threaded per symbol; scale by adding more symbols/partitions. Use lock-free or minimal-lock structures if multiple threads touch the same book (not recommended for core path). |
| **Gateway scale** | Stateless order gateways behind load balancer; they only validate and forward to the correct matching engine partition. |
| **Persistence** | Event log partitioned by symbol (or orderId); consumers (DB writer, snapshot) scale by partition. |
| **Market data** | Separate service that subscribes to book/trade streams and fans out to many clients (multicast, fan-out queues, or cached L2 with incremental updates). |

---

## 7. Reliability and Recovery

| Mechanism | Description |
|-----------|-------------|
| **Event log** | Durable, ordered log of all orders and matches; replay to rebuild book and fill store. |
| **Snapshots** | Periodic full book snapshot per symbol; replay events after snapshot to reduce recovery time. |
| **Failover** | Standby matching engine replays from same log (or receives replicated stream); on primary failure, promote standby and resume from last committed position. |
| **Idempotency** | Order IDs and idempotency keys prevent duplicate processing after retries. |

---

## 8. Security and Integrity

- **Authentication** on all order and account APIs; **authorization** (e.g. only cancel own orders).
- **Rate limiting** to prevent abuse and ensure fair usage.
- **Audit trail:** every order and trade in event log for compliance and dispute resolution.
- **Price/quantity checks:** reject invalid or out-of-range values; use **fixed-point or decimal** for money to avoid rounding errors.

---

## 9. Summary

| Layer | Responsibility |
|-------|----------------|
| **API Gateway** | TLS, auth, rate limit, route. |
| **Order Gateway** | Validate, enrich, assign ID, send to engine. |
| **Matching Engine** | Single-threaded per-symbol book + price-time matching; emit trades and book deltas. |
| **Persistence** | Event log + DB + book snapshots for recovery and query. |
| **Market Data** | Consume book/trade events; serve L2/L3 and trades to clients. |

The **order book and matching logic** are described in detail in the [HLD](HLD.md). This document adds the **full system**: how orders enter, how results are stored and distributed, and how to scale and recover.
