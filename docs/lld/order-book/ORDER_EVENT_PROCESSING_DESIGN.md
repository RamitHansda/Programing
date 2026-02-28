# Design: Crypto Order Event Processing

**Scope:** How order and trade events flow from ingestion through the matching engine to persistence and downstream consumers. Complements [SYSTEM_DESIGN.md](SYSTEM_DESIGN.md) and [HLD.md](HLD.md).

---

## 1. Overview

**Goal:** Ingest orders as events, process them through the matching engine in a well-defined order, and emit **order lifecycle events** and **trade events** so that:

- Every state change is durable and replayable.
- Downstream systems (persistence, market data, risk) consume the same stream.
- Ordering per symbol is strict so time priority and book state are deterministic.

---

## 2. Event Types

### 2.1 Order lifecycle events (inbound → engine)

| Event | When | Key fields |
|-------|------|-------------|
| **OrderSubmitted** | Gateway accepts and forwards | orderId, userId, symbol, side, price, quantity, orderType, timestamp, idempotencyKey? |
| **OrderCancelRequest** | User cancels | orderId, symbol, timestamp |
| **OrderAmendRequest** | User amends (price/qty) | orderId, symbol, newPrice?, newQuantity?, timestamp |

These are **commands** that the matching engine consumes.

### 2.2 Order lifecycle events (outbound from engine)

| Event | When | Key fields |
|-------|------|-------------|
| **OrderAccepted** | Order accepted (resting or partially filled) | orderId, symbol, side, price, quantity, leavesQty, timestamp |
| **OrderRejected** | Validation or business rule failure | orderId, symbol, reason, timestamp |
| **OrderCancelled** | Cancel executed | orderId, symbol, leavesQty (cancelled), timestamp |
| **OrderFilled** (optional) | Per-fill ack to owner | orderId, fillQty, price, tradeId, timestamp |

### 2.3 Trade events (outbound)

| Event | When | Key fields |
|-------|------|-------------|
| **Trade** | Match between taker and maker | tradeId, symbol, price, quantity, takerOrderId, makerOrderId, takerSide, timestamp |

### 2.4 Book events (outbound)

| Event | When | Key fields |
|-------|------|-------------|
| **BookUpdate** | Level added/removed/changed | symbol, side, price, deltaQuantity, totalQuantityAtLevel?, orderCount? |
| **BookSnapshot** (optional) | Full book state | symbol, bids[], asks[], timestamp |

---

## 3. Event Flow Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│  PRODUCERS                                                                               │
├─────────────────────────────────────────────────────────────────────────────────────────┤
│  Order Gateway                    Matching Engine (per symbol)                           │
│  • OrderSubmitted                 • OrderAccepted / OrderRejected                         │
│  • OrderCancelRequest             • OrderCancelled                                        │
│  • OrderAmendRequest              • Trade                                                  │
│                                   • BookUpdate (add/remove/change level)                  │
└──────────────────────┬──────────────────────────────────┬───────────────────────────────┘
                       │                                  │
                       ▼                                  ▼
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│  EVENT LOG / STREAM (durable, ordered per partition)                                      │
│  • Partition key: symbol (so all events for BTC-USD are in order)                         │
│  • Or: separate topics — orders-in, trades-out, book-out                                 │
└──────────────────────┬──────────────────────────────────┬───────────────────────────────┘
                       │                                  │
         ┌─────────────┼─────────────┬────────────────────┼─────────────┐
         ▼             ▼             ▼                    ▼             ▼
┌─────────────┐ ┌─────────────┐ ┌─────────────┐  ┌─────────────┐ ┌─────────────┐
│  Matching   │ │  Order/Fill │ │  Market     │  │  Risk /      │ │  Audit /    │
│  Engine     │ │  Store (DB) │ │  Data       │  │  Position    │ │  Compliance │
│  (replay)   │ │  (query)    │ │  (L2/L3)    │  │  (PnL)      │ │  (log)      │
└─────────────┘ └─────────────┘ └─────────────┘  └─────────────┘ └─────────────┘
     CONSUMERS
```

- **Order Gateway** publishes **OrderSubmitted** (and cancel/amend) into the stream (or sends directly to the engine with a write-ahead to the log).
- **Matching Engine** consumes order events (from stream or in-process), runs matching, and **publishes** OrderAccepted/Rejected/Cancelled, **Trade**, and **BookUpdate**.
- All events are written to a **durable event log** (e.g. Kafka) with **partition key = symbol** so ordering per symbol is preserved.
- **Consumers** read from the log: replay for recovery, DB writer for orders/fills, market data for depth/trades, risk for positions, audit for compliance.

---

## 4. Ordering and Partitioning

| Requirement | Design |
|-------------|--------|
| **Ordering per symbol** | Partition the event log by **symbol**. All events for BTC-USD go to the same partition → total order for that symbol. |
| **Global order vs per-symbol** | Per-symbol ordering is enough for the matching engine (one book per symbol). Global order across symbols is not required for correctness. |
| **Who writes** | Option A: Matching engine writes all outbound events to the log. Option B: A thin layer next to the engine writes to the log so the engine stays pure in-memory. |

**Partition key:** `symbol`. Optional: separate topics `order-events` and `trade-events` if you want different retention or consumers.

---

## 5. Processing Pipeline (per symbol)

1. **Ingest:** Order Gateway assigns `orderId`, validates, and produces **OrderSubmitted** (and optionally cancel/amend) to the stream (partition = symbol) or sends to the engine with **write-ahead** to the log.
2. **Process:** Matching engine (single-threaded for that symbol) reads the next event (or receives it in-process), applies it:
   - **OrderSubmitted** → match against book; emit **Trade**(s), **OrderAccepted** (and optionally **OrderFilled**), **BookUpdate**(s).
   - **OrderCancelRequest** → remove order from book; emit **OrderCancelled**, **BookUpdate**(s).
   - **OrderAmendRequest** → cancel + new order or in-place amend; emit **OrderCancelled** + **OrderAccepted** and **BookUpdate**(s).
3. **Emit:** Every outbound event (OrderAccepted, Trade, BookUpdate, etc.) is **appended to the event log** (same partition for that symbol) so consumers see a single ordered stream.
4. **Consume:** Downstream readers consume from the log (by symbol or all partitions); they apply events in order to build local state (DB rows, L2/L3, positions).

---

## 6. Idempotency and Deduplication

| Problem | Solution |
|--------|----------|
| **Duplicate order submission** | Client sends **idempotency key**; gateway maps key → orderId. If same key seen again, return existing orderId and do not create a new order. |
| **Duplicate processing after replay** | Every event carries **orderId** (and tradeId for fills). Consumers (e.g. DB writer) **deduplicate by orderId/tradeId** when applying (e.g. upsert by primary key). |
| **At-least-once delivery** | Log guarantees at-least-once; consumers must be **idempotent** (same event applied twice → same result). |

**Event schema suggestion:** Include `eventId` (unique per event) so consumers can skip already-applied events.

---

## 7. Event Schema (minimal)

Use a single envelope for all event types:

```text
EventEnvelope:
  eventId: UUID
  eventType: enum (OrderSubmitted | OrderAccepted | OrderRejected | OrderCancelled | Trade | BookUpdate | ...)
  symbol: string
  timestamp: milliseconds
  payload: JSON (type-specific)
```

**Payload examples:**

- **OrderSubmitted:** orderId, userId, side, price, quantity, orderType, idempotencyKey?
- **Trade:** tradeId, price, quantity, takerOrderId, makerOrderId, takerSide
- **BookUpdate:** side, price, deltaQuantity, totalQuantityAtLevel

Use **eventType** so consumers can deserialize `payload` and route to the right handler.

---

## 8. Consumers and Their Needs

| Consumer | Reads | Use |
|----------|--------|-----|
| **Matching engine (replay)** | OrderSubmitted, OrderCancelRequest, OrderAmendRequest | Rebuild in-memory book by replaying; then continue from latest offset. |
| **Order/Fill store (DB)** | OrderAccepted, OrderRejected, OrderCancelled, Trade | Upsert orders and fills by orderId/tradeId; serve "my orders" and "my fills" APIs. |
| **Market data** | Trade, BookUpdate | Maintain L2/L3 and last N trades; push to clients. |
| **Risk / position** | Trade | Update positions and PnL per user/symbol. |
| **Audit / compliance** | All | Append to immutable audit log. |

Each consumer maintains its own **offset** (or position) in the log and processes events in order per partition.

---

## 9. Failure Handling and Recovery

| Scenario | Handling |
|----------|----------|
| **Matching engine crash** | Replay event log for that symbol from last **snapshot** (or from start); replay restores book and state. Then resume consuming from current offset. |
| **Consumer lag** | Consumers read at their own pace; backpressure via partition offset. If a consumer is slow, it does not block the matching engine (engine writes to log and continues). |
| **Duplicate event delivery** | Consumers use **eventId** or (orderId + eventType) to deduplicate; idempotent writes (e.g. DB upsert by orderId/tradeId). |
| **Gateway crash after submit, before ack** | Client retries with same idempotency key; gateway returns existing orderId and does not re-submit. Engine has at-most-once per orderId from gateway. |

---

## 10. Summary

| Aspect | Choice |
|--------|--------|
| **Event types** | Order lifecycle (submit, cancel, amend) in; OrderAccepted/Rejected/Cancelled, Trade, BookUpdate out. |
| **Ordering** | Partition by **symbol**; strict order per symbol. |
| **Durability** | Durable event log (e.g. Kafka); all outbound events appended before ack. |
| **Idempotency** | Idempotency keys at gateway; eventId/orderId/tradeId for consumer deduplication. |
| **Consumers** | Replay, DB, market data, risk, audit — each reads from log and applies in order. |

This design gives you a clear, replayable, and scalable event pipeline for crypto order and trade processing that fits the order-book system in [SYSTEM_DESIGN.md](SYSTEM_DESIGN.md) and [HLD.md](HLD.md).
