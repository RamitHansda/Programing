# Order Event Processor — Staff Engineer LLD (Coinbase Execution Round)

**Level:** Staff Engineer  
**Scope:** Low-Level Design — Order Event Processor between the Order Gateway and Matching Engine.  
**Language:** Java 21 (sealed interfaces, records, pattern-matching switch)

---

## 1. Problem Statement

> **"Design and implement the component that sits between the Order Gateway and the Matching Engine. It must consume inbound order commands, apply them to the book, and emit a structured, replayable event stream to all downstream consumers."**

The interviewer is checking:
- Clean abstractions and separation of concerns
- Correctness under concurrent retry / at-least-once delivery
- Handling of all order types (LIMIT, IOC, FOK, MARKET)
- Observability and failure model
- Testable design

---

## 2. Architecture

```
  Kafka (orders-in) — partition key = symbol
         │
         ▼
  ┌──────────────────────────────────────────────────┐
  │              OrderEventProcessor                  │
  │  (single-threaded per symbol — no lock in path)  │
  │                                                   │
  │  1. Symbol routing guard                          │
  │  2. Idempotency check (client idempotency key)    │
  │  3. Input validation → ValidationException        │
  │  4. FOK pre-check (read-only book scan)           │
  │  5. MatchingEngine.match / cancel / amend         │
  │  6. Emit: OrderAccepted / OrderRejected /         │
  │           OrderCancelled / TradeExecuted /        │
  │           FillExecuted / BookUpdated              │
  │  7. IOC remainder cancel                          │
  │  8. Metrics (latency, trade count, error rate)    │
  └──────────────────────────────────────────────────┘
         │  publish() — in sequence, symbol-partitioned
         ▼
  Kafka (orders-out / trades-out / book-out)
         │
    ┌────┴────────────────────────────────────┐
    ▼          ▼              ▼              ▼
  Order/Fill  Market Data   Risk / PnL    Audit
  Store (DB)  (L2/L3)       (positions)   Log
```

---

## 3. Component Breakdown

### Inbound Commands (`OrderCommand` — sealed interface)

| Type | Key Fields | Notes |
|------|-----------|-------|
| `Submit` | orderId, userId, symbol, side, price, qty, orderType, **idempotencyKey** | `idempotencyKey` = client's dedup token |
| `Cancel` | orderId, symbol, requesterId | Removes resting order from book |
| `Amend` | orderId, symbol, newPrice, newQty | Cancel + re-insert (loses time priority) |

### Outbound Events (`OutboundOrderEvent` — sealed interface)

| Event | Emitted When | Key consumers |
|-------|-------------|--------------|
| `OrderAccepted` | Order processed (fill, partial, rest) | OMS, client gateway |
| `OrderRejected` | Validation / FOK / STP failure | OMS, client gateway |
| `OrderCancelled` | Cancel, IOC expiry, amend, risk kill | OMS, client gateway |
| `TradeExecuted` | Every fill between taker and maker | Risk, PnL, Market Data |
| `FillExecuted` | Per-order fill ack (one for taker, one for maker) | OMS per user |
| `BookUpdated` | Level added / filled / cancelled | Market Data (L2 feed) |

### Supporting Components

| Component | Interface | Production Impl | Test Impl |
|-----------|-----------|-----------------|-----------|
| `EventPublisher` | `publish(event)`, `publishToDlq(cmd, e)` | Kafka producer (`acks=all`) | `CapturingPublisher` |
| `IdempotencyStore` | `setIfAbsent(key, orderId)` | Redis with TTL | `InMemoryIdempotencyStore` |
| `ProcessingMetrics` | `recordProcessed`, `recordTrade`, etc. | StatsD / Prometheus | `NoOpMetrics` |

---

## 4. Key Design Decisions — The "Why"

### 4.1 Single-Threaded Per Symbol

**Why:** Eliminates locking in the hot path, makes event ordering deterministic, and simplifies recovery (just replay from any Kafka offset). N symbols = N threads = embarrassingly parallel.

**Trade-off:** One slow symbol can't steal latency from others. Throughput per symbol is bounded by one CPU core → acceptable given typical exchange volumes per symbol.

### 4.2 Sealed + Records for Events

**Why:** Sealed interface ensures exhaustive pattern-matching in switch. Adding a new event type is a compile error if any consumer's switch is non-exhaustive — deliberate rigidity that prevents silent consumer breakage. Records give free `equals`, `hashCode`, and immutability.

### 4.3 Two-Layer Idempotency

```
Layer 1 (gateway level): idempotencyKey → orderId mapping in Redis
  → prevents duplicate orders from retrying clients

Layer 2 (consumer level): eventId on every outbound event
  → lets DB consumers do upsert-by-eventId / upsert-by-orderId
  → handles at-least-once delivery from Kafka
```

### 4.4 FOK Pre-Check Before Any Book Mutation

**Why:** FOK (Fill-Or-Kill) must either execute fully or not at all. Matching is destructive — once we fill a maker order we can't undo it. So we scan the opposite side depth first (read-only, O(k)), reject if insufficient, then proceed with full confidence.

### 4.5 ValidationException → OrderRejected (Not DLQ)

**Why:** Client-side errors (bad price, bad qty) are expected; they must produce a structured `OrderRejected` event so the client's OMS can display the reason. Only true unexpected exceptions (bugs, infrastructure failures) go to the DLQ.

### 4.6 `PublishException` Propagates (Not Caught)

**Why:** If Kafka is unavailable, we must NOT commit the offset. Propagating the exception causes the Kafka consumer loop to not advance, so the event is re-delivered once Kafka recovers. Swallowing it would mean a lost event — unacceptable for an audit log.

---

## 5. Event Sequence for a Crossing Order

```
Client submits BUY 10 BTC @ 100 (LIMIT)
Book has: ASK 100 → [order-a1 qty=6, order-a2 qty=4]

Emitted sequence (all share same symbol partition key):
1.  OrderAccepted  {orderId=b1, leavesQty=0}
2.  TradeExecuted  {tradeId=T-1, price=100, qty=6, taker=b1, maker=a1}
3.  FillExecuted   {orderId=b1, tradeId=T-1, fillQty=6, leavesQty=4, isTaker=true}
4.  FillExecuted   {orderId=a1, tradeId=T-1, fillQty=6, leavesQty=0, isTaker=false}
5.  BookUpdated    {side=ASK, price=100, delta=-6, total=4, type=FILL}
6.  TradeExecuted  {tradeId=T-2, price=100, qty=4, taker=b1, maker=a2}
7.  FillExecuted   {orderId=b1, tradeId=T-2, fillQty=4, leavesQty=0, isTaker=true}
8.  FillExecuted   {orderId=a2, tradeId=T-2, fillQty=4, leavesQty=0, isTaker=false}
9.  BookUpdated    {side=ASK, price=100, delta=-4, total=0, type=FILL}
```

---

## 6. Order Type Handling Matrix

| Type | Pre-check | On match | Remainder |
|------|-----------|----------|-----------|
| LIMIT | None | Fill available liquidity | Rests on book |
| MARKET | None | Fill at any price (extreme sentinel) | Cancel (IOC semantics) |
| IOC | None | Fill available liquidity | Cancel → `OrderCancelled(IOC_EXPIRED)` |
| FOK | Scan depth — reject if can't fill fully | Fill in full | Never rests |

---

## 7. Failure Handling Summary

| Scenario | Behaviour |
|----------|-----------|
| Duplicate Submit (same idempotency key) | `IdempotencyStore` returns false → skip silently |
| Invalid price / qty | `ValidationException` → `OrderRejected` event |
| FOK insufficient liquidity | `OrderRejected(FOK_CANNOT_FILL)` — no book mutation |
| Cancel unknown order | `ValidationException` → DLQ (cancel/amend errors don't have a client-facing event) |
| Unexpected exception (bug) | DLQ via `publishToDlq` → processor advances offset |
| Kafka publish failure | `PublishException` propagates → offset not committed → re-delivery |
| Processor crash | Replay Kafka partition from last snapshot offset → deterministic recovery |

---

## 8. Class Diagram (Flat)

```
OrderEventProcessor
  ├── uses: MatchingEngine          (book mutation)
  ├── uses: EventPublisher          (outbound stream)
  ├── uses: IdempotencyStore        (dedup)
  └── uses: ProcessingMetrics       (observability)

OrderCommand (sealed)
  ├── Submit  (record)
  ├── Cancel  (record)
  └── Amend   (record)

OutboundOrderEvent (sealed)
  ├── OrderAccepted   (record)
  ├── OrderRejected   (record)
  ├── OrderCancelled  (record)
  ├── TradeExecuted   (record)
  ├── FillExecuted    (record)
  └── BookUpdated     (record)

Enums: OrderType, RejectionReason, CancellationReason
Exceptions: ValidationException
```

---

## 9. Scaling to Multiple Symbols

```
Kafka topic: orders-in
  Partition 0 (key=BTC-USD) → Thread-0 → OrderEventProcessor("BTC-USD")
  Partition 1 (key=ETH-USD) → Thread-1 → OrderEventProcessor("ETH-USD")
  Partition 2 (key=SOL-USD) → Thread-2 → OrderEventProcessor("SOL-USD")
  ...
```

- Each processor has its own `MatchingEngine`, `EventPublisher`, `IdempotencyStore`
- No shared mutable state across processors → linear horizontal scaling
- Total ordering per symbol is preserved by Kafka's partition guarantee

---

## 10. Extension Points (Interview Follow-ups)

| Question | Answer |
|----------|--------|
| How do you recover after a crash? | Replay Kafka from last committed offset + snapshot of book state (periodic snapshot to S3) |
| How do you handle duplicate events in consumers? | Each event has a monotonic `eventId`; consumers upsert by `eventId` / `orderId` / `tradeId` |
| How would you add Self-Trade Prevention? | Maintain `orderId → userId` index alongside the order book; check in `checkSelfTradePrevention` before matching |
| How would you add price collars? | Validate `|price − midMarket| / midMarket < threshold` in `requirePositive` step; emit `PRICE_OUT_OF_COLLAR` |
| What about circuit breakers? | Subscribe to a `CircuitBreakerState` (OPEN/CLOSED); wrap `processSubmit` with a check before touching the book |
| How do you do canary / shadow mode? | Run two processors in parallel; secondary publishes to a shadow topic; diff event streams in a reconciliation job |

---

## 11. Files Created

```
src/main/java/lld/orderbook/
├── OrderType.java                  enum: LIMIT, MARKET, IOC, FOK
├── RejectionReason.java            enum: typed rejection codes
├── CancellationReason.java         enum: CLIENT_REQUEST, IOC_EXPIRED, AMENDED, ...
├── ValidationException.java        typed exception carrying RejectionReason
├── OrderCommand.java               sealed interface + Submit / Cancel / Amend records
├── OutboundOrderEvent.java         sealed interface + all 6 event records
├── EventPublisher.java             interface (Kafka producer in prod)
├── IdempotencyStore.java           interface (Redis in prod)
├── InMemoryIdempotencyStore.java   ConcurrentHashMap impl (tests / dev)
├── ProcessingMetrics.java          interface (StatsD / Prometheus in prod)
├── NoOpMetrics.java                no-op impl
└── OrderEventProcessor.java        THE MAIN CLASS — orchestrates all of the above

src/test/java/lld/orderbook/
└── OrderEventProcessorTest.java    27 tests across 6 nested classes
```
