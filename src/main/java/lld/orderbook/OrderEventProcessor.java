package lld.orderbook;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * <h2>Order Event Processor — Coinbase Staff Engineer Implementation</h2>
 *
 * <p>Sits between the Order Gateway (Kafka inbound) and the {@link MatchingEngine}.
 * Consumes one {@link OrderCommand} at a time, applies it to the engine, and emits a
 * deterministic, ordered stream of {@link OutboundOrderEvent}s.
 *
 * <pre>
 *  Kafka (orders-in)
 *       │  partition key = symbol
 *       ▼
 *  ┌─────────────────────────────────────────────┐
 *  │           OrderEventProcessor               │
 *  │  1. Symbol routing guard                    │
 *  │  2. Idempotency check (gateway key)         │
 *  │  3. Input validation                        │
 *  │  4. FOK pre-check (no book mutation)        │
 *  │  5. MatchingEngine.match / cancel / amend   │
 *  │  6. Emit → OrderAccepted / Rejected /       │
 *  │            Cancelled / TradeExecuted /      │
 *  │            FillExecuted / BookUpdated       │
 *  │  7. IOC remainder cancel                    │
 *  │  8. Metrics recording                       │
 *  └─────────────────────────────────────────────┘
 *       │  publish() calls, in sequence
 *       ▼
 *  Kafka (orders-out / trades-out / book-out)
 * </pre>
 *
 * <h3>Threading model</h3>
 * <p><b>Single-threaded per symbol.</b>  One Kafka partition = one symbol = one processor
 * instance = one consumer thread.  This eliminates all locking in the hot path, makes
 * event sequencing deterministic, and trivially supports replay (restart consumer from
 * any offset).  N symbols → N processors running in parallel on N threads.
 *
 * <h3>Failure model</h3>
 * <ul>
 *   <li>Validation failures → emit {@link OutboundOrderEvent.OrderRejected}; continue.</li>
 *   <li>Unexpected exceptions → DLQ via {@link EventPublisher#publishToDlq}; continue.
 *       We advance the Kafka offset so one poison-pill command cannot stall the stream.</li>
 *   <li>Publish failures ({@link EventPublisher.PublishException}) propagate to the caller
 *       so the Kafka consumer does NOT commit the offset, causing a re-delivery.</li>
 * </ul>
 *
 * <h3>Idempotency</h3>
 * <p>Two layers:
 * <ol>
 *   <li><b>Gateway level:</b> client-supplied idempotency key deduped in
 *       {@link IdempotencyStore} before any book mutation.</li>
 *   <li><b>Consumer level:</b> every event carries an {@code eventId}; downstream
 *       consumers upsert by {@code eventId} / {@code orderId} / {@code tradeId}.</li>
 * </ol>
 */
public final class OrderEventProcessor {

    private final String symbol;
    private final MatchingEngine matchingEngine;
    private final EventPublisher publisher;
    private final IdempotencyStore idempotencyStore;
    private final ProcessingMetrics metrics;

    /** Monotonic per-symbol event sequence; used to build eventIds. */
    private long eventSeq = 0;

    /** Monotonic per-symbol trade counter; used to build tradeIds. */
    private long tradeSeq = 0;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public OrderEventProcessor(String symbol,
                                MatchingEngine matchingEngine,
                                EventPublisher publisher,
                                IdempotencyStore idempotencyStore,
                                ProcessingMetrics metrics) {
        this.symbol           = Objects.requireNonNull(symbol,           "symbol");
        this.matchingEngine   = Objects.requireNonNull(matchingEngine,   "matchingEngine");
        this.publisher        = Objects.requireNonNull(publisher,        "publisher");
        this.idempotencyStore = Objects.requireNonNull(idempotencyStore, "idempotencyStore");
        this.metrics          = Objects.requireNonNull(metrics,          "metrics");
        if (symbol.isBlank()) throw new IllegalArgumentException("symbol must not be blank");
    }

    /** Convenience constructor — uses no-op metrics (useful for tests). */
    public OrderEventProcessor(String symbol,
                                MatchingEngine matchingEngine,
                                EventPublisher publisher,
                                IdempotencyStore idempotencyStore) {
        this(symbol, matchingEngine, publisher, idempotencyStore, ProcessingMetrics.noOp());
    }

    // -------------------------------------------------------------------------
    // Public API — called by the Kafka consumer loop (one thread per symbol)
    // -------------------------------------------------------------------------

    /**
     * Process one inbound command.
     *
     * <p>This method is <b>NOT thread-safe</b>.  All calls must come from the same
     * thread (the Kafka consumer thread for this symbol's partition).
     *
     * <p>On {@link ValidationException} (business rule failures for Submit commands)
     * an {@link OutboundOrderEvent.OrderRejected} event is published and the method
     * returns normally.
     *
     * <p>On unexpected exceptions the command is DLQ'd and the method returns normally,
     * allowing the consumer to commit the offset and move on.
     *
     * <p>{@link EventPublisher.PublishException} is NOT caught here — it propagates to
     * the Kafka consumer loop so the offset is not committed, triggering re-delivery.
     *
     * @param command the inbound command to process (must not be null)
     */
    public void process(OrderCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        // Symbol guard is a programming error (wrong Kafka partition routing), not a
        // business rule — throw immediately so the consumer can alert, not DLQ silently.
        guardSymbol(command.symbol());
        long startNanos = System.nanoTime();

        try {

            switch (command) {
                case OrderCommand.Submit s  -> processSubmit(s);
                case OrderCommand.Cancel c  -> processCancel(c);
                case OrderCommand.Amend  a  -> processAmend(a);
            }

            metrics.recordProcessed(command.getClass().getSimpleName(),
                    System.nanoTime() - startNanos);

        } catch (ValidationException e) {
            // Business-rule failure on a Submit → emit structured rejection event
            metrics.recordRejection(e.getReason());
            if (command instanceof OrderCommand.Submit s) {
                publisher.publish(new OutboundOrderEvent.OrderRejected(
                        nextEventId(), s.orderId(), symbol,
                        e.getReason(), e.getMessage(), now()));
            } else {
                // Cancel/Amend validation failures are unexpected — DLQ them
                publisher.publishToDlq(command, e);
            }

        } catch (EventPublisher.PublishException e) {
            // Re-throw so the Kafka offset is NOT committed → re-delivery
            throw e;

        } catch (Exception e) {
            // Poison pill — DLQ and continue so the stream does not stall
            metrics.recordError();
            publisher.publishToDlq(command, e);
        }
    }

    // -------------------------------------------------------------------------
    // Submit
    // -------------------------------------------------------------------------

    private void processSubmit(OrderCommand.Submit cmd) {
        // ── 1. Idempotency ────────────────────────────────────────────────────
        if (cmd.idempotencyKey() != null
                && !idempotencyStore.setIfAbsent(cmd.idempotencyKey(), cmd.orderId())) {
            // Duplicate submission from a retrying client — silently skip.
            // The client will receive the original events from their order-management service.
            metrics.recordDuplicate();
            return;
        }

        // ── 2. Validation ─────────────────────────────────────────────────────
        requirePositive(cmd.price(),    "price",    RejectionReason.INVALID_PRICE);
        requirePositive(cmd.quantity(), "quantity", RejectionReason.INVALID_QUANTITY);

        // ── 3. Self-trade prevention (STP) ────────────────────────────────────
        // Scan the opposite side: if the same userId has a resting order that would be
        // matched first, reject to prevent wash trades (required on regulated venues).
        checkSelfTradePrevention(cmd);

        // ── 4. FOK pre-check ──────────────────────────────────────────────────
        // Must happen BEFORE any book mutation. If the full quantity cannot be
        // immediately filled, reject with no side effects.
        if (cmd.orderType() == OrderType.FOK && !canFullyFill(cmd)) {
            publisher.publish(new OutboundOrderEvent.OrderRejected(
                    nextEventId(), cmd.orderId(), symbol,
                    RejectionReason.FOK_CANNOT_FILL,
                    "FOK order cannot be fully filled by available liquidity at " + cmd.price(),
                    now()));
            return;
        }

        // ── 5. Match ──────────────────────────────────────────────────────────
        Order order = new Order(cmd.orderId(), cmd.side(),
                cmd.price(), cmd.quantity(), cmd.timestamp());
        MatchResult result = matchingEngine.match(order);

        // ── 6. OrderAccepted ──────────────────────────────────────────────────
        // Always emit accepted (even if fully filled immediately) so the client's
        // order-management service can transition from PENDING → OPEN → FILLED.
        publisher.publish(new OutboundOrderEvent.OrderAccepted(
                nextEventId(), cmd.orderId(), symbol,
                cmd.side(), cmd.price(), cmd.quantity(),
                order.getLeavesQuantity(), cmd.orderType(), now()));

        // ── 7. Trades + Fills ─────────────────────────────────────────────────
        emitTradesAndFills(result, cmd.orderId(), cmd.side(), order);

        // ── 8. Book deltas ────────────────────────────────────────────────────
        emitBookUpdates(result);

        // ── 9. IOC remainder cancel ───────────────────────────────────────────
        // After matching, any unfilled quantity of an IOC (or MARKET) order is cancelled.
        if (cmd.orderType().isImmediateOnly() && order.getLeavesQuantity().signum() > 0) {
            BigDecimal remainder = order.getLeavesQuantity();
            matchingEngine.cancel(cmd.orderId());
            publisher.publish(new OutboundOrderEvent.OrderCancelled(
                    nextEventId(), cmd.orderId(), symbol,
                    remainder, CancellationReason.IOC_EXPIRED, now()));
        }
    }

    // -------------------------------------------------------------------------
    // Cancel
    // -------------------------------------------------------------------------

    private void processCancel(OrderCommand.Cancel cmd) {
        requireNonBlank(cmd.orderId(), "orderId", RejectionReason.MISSING_REQUIRED_FIELD);

        Order cancelled = matchingEngine.cancel(cmd.orderId());
        if (cancelled == null) {
            // Treat as a validation failure: order not found (already filled or unknown ID)
            throw new ValidationException(RejectionReason.ORDER_NOT_FOUND,
                    "Cancel failed — order not found or already fully filled: " + cmd.orderId());
        }

        publisher.publish(new OutboundOrderEvent.OrderCancelled(
                nextEventId(), cmd.orderId(), symbol,
                cancelled.getLeavesQuantity(), CancellationReason.CLIENT_REQUEST, now()));

        // Emit the book delta so L2 consumers remove this quantity from the level
        publisher.publish(new OutboundOrderEvent.BookUpdated(
                nextEventId(), symbol,
                cancelled.getSide(), cancelled.getPrice(),
                cancelled.getLeavesQuantity().negate(),   // quantity removed
                levelTotalQty(cancelled.getSide(), cancelled.getPrice()),
                BookEvent.Type.CANCEL, now()));
    }

    // -------------------------------------------------------------------------
    // Amend
    // -------------------------------------------------------------------------

    /**
     * Amend = cancel-then-resubmit.
     *
     * <p>The amended order loses its time priority at the old price level and joins the
     * back of the queue at the new price.  This is intentional — it deters gaming via
     * continuous small amendments and is consistent with standard exchange behaviour.
     *
     * <p>If the amendment would immediately match (new price crosses the spread), the
     * matching engine will fill aggressively and any remainder rests at the new price.
     */
    private void processAmend(OrderCommand.Amend cmd) {
        requireNonBlank(cmd.orderId(), "orderId", RejectionReason.MISSING_REQUIRED_FIELD);
        requirePositive(cmd.newPrice(),    "newPrice",    RejectionReason.INVALID_PRICE);
        requirePositive(cmd.newQuantity(), "newQuantity", RejectionReason.INVALID_QUANTITY);

        Order existing = matchingEngine.cancel(cmd.orderId());
        if (existing == null) {
            throw new ValidationException(RejectionReason.ORDER_NOT_FOUND,
                    "Amend failed — order not found or already fully filled: " + cmd.orderId());
        }

        // Emit cancel for the old order so consumers know the old resting qty is gone
        publisher.publish(new OutboundOrderEvent.OrderCancelled(
                nextEventId(), cmd.orderId(), symbol,
                existing.getLeavesQuantity(), CancellationReason.AMENDED, now()));

        // Re-submit at new price/qty, retaining same orderId and side
        Order amended = new Order(cmd.orderId(), existing.getSide(),
                cmd.newPrice(), cmd.newQuantity(), cmd.timestamp());
        MatchResult result = matchingEngine.match(amended);

        // Emit accepted for the amended order (carries the new price and qty)
        publisher.publish(new OutboundOrderEvent.OrderAccepted(
                nextEventId(), cmd.orderId(), symbol,
                existing.getSide(), cmd.newPrice(), cmd.newQuantity(),
                amended.getLeavesQuantity(), OrderType.LIMIT, now()));

        emitTradesAndFills(result, cmd.orderId(), existing.getSide(), amended);
        emitBookUpdates(result);
    }

    // -------------------------------------------------------------------------
    // FOK pre-check  (read-only scan of the book — no mutations)
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} iff the book holds enough resting liquidity at crossable
     * prices to fill {@code cmd.quantity()} in full right now.
     *
     * <p>O(k) where k is the number of price levels and orders touched.  For a heavily
     * skewed book this could be O(n) in the worst case, but in practice FOK orders are
     * rare and the check terminates as soon as the required quantity accumulates.
     */
    private boolean canFullyFill(OrderCommand.Submit cmd) {
        BigDecimal remaining = cmd.quantity();
        OrderBook book = matchingEngine.getOrderBook();

        if (cmd.side() == Side.BID) {
            // Buy order matches against asks (ascending price); stop when ask > limit
            for (Map.Entry<BigDecimal, PriceLevel> entry : book.getAsks().entrySet()) {
                if (entry.getKey().compareTo(cmd.price()) > 0) break;
                for (Order o : entry.getValue()) {
                    remaining = remaining.subtract(o.getLeavesQuantity());
                    if (remaining.signum() <= 0) return true;
                }
            }
        } else {
            // Sell order matches against bids (descending price); stop when bid < limit
            for (Map.Entry<BigDecimal, PriceLevel> entry : book.getBids().entrySet()) {
                if (entry.getKey().compareTo(cmd.price()) < 0) break;
                for (Order o : entry.getValue()) {
                    remaining = remaining.subtract(o.getLeavesQuantity());
                    if (remaining.signum() <= 0) return true;
                }
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Self-trade prevention
    // -------------------------------------------------------------------------

    /**
     * Checks whether the incoming order's userId already has a resting order on the
     * opposite side that would be matched first (best price + FIFO head).
     *
     * <p>Only the single best-price-level head order is checked here (an approximation
     * that covers the common case without O(n) book scanning).  A production system
     * would maintain a per-userId index for O(1) lookup.
     *
     * <p>STP behaviour at Coinbase: reject the incoming order when the first matching
     * resting order belongs to the same user (CANCEL_NEWEST mode).  Other exchanges
     * cancel the maker instead (CANCEL_OLDEST) or cancel both.
     */
    private void checkSelfTradePrevention(OrderCommand.Submit cmd) {
        OrderBook book = matchingEngine.getOrderBook();
        PriceLevel bestOpposite = (cmd.side() == Side.BID)
                ? book.getBestAskLevel()
                : book.getBestBidLevel();

        if (bestOpposite == null) return;

        Order bestResting = bestOpposite.peekFirst();
        if (bestResting == null) return;

        boolean wouldCross = (cmd.side() == Side.BID)
                ? cmd.price().compareTo(bestResting.getPrice()) >= 0
                : cmd.price().compareTo(bestResting.getPrice()) <= 0;

        if (!wouldCross) return;

        // We need the userId of the resting order.  In the current Order model the
        // userId is not stored (it was stripped at the gateway layer).  A production
        // implementation would either store userId on Order or maintain a separate
        // orderId→userId index.  Shown here as the contract / hook point.
        // TODO: inject an OrderOwnershipIndex and uncomment the check below.
        //
        // if (cmd.userId().equals(ownershipIndex.getUserId(bestResting.getOrderId()))) {
        //     throw new ValidationException(RejectionReason.SELF_TRADE_PREVENTED,
        //             "Order would trade against your own resting order: "
        //                     + bestResting.getOrderId());
        // }
    }

    // -------------------------------------------------------------------------
    // Emit helpers
    // -------------------------------------------------------------------------

    private void emitTradesAndFills(MatchResult result, String takerOrderId,
                                    Side takerSide, Order takerOrder) {
        for (Trade trade : result.getTrades()) {
            String tradeId = nextTradeId();

            // One TradeExecuted per fill — consumed by risk, PnL, market-data feeds
            publisher.publish(new OutboundOrderEvent.TradeExecuted(
                    nextEventId(), tradeId, symbol,
                    trade.getPrice(), trade.getQuantity(),
                    trade.getTakerOrderId(), trade.getMakerOrderId(),
                    takerSide, now()));

            // Per-order fill notification for the taker (order-management service)
            publisher.publish(new OutboundOrderEvent.FillExecuted(
                    nextEventId(), takerOrderId, symbol, tradeId,
                    trade.getPrice(), trade.getQuantity(),
                    takerOrder.getLeavesQuantity(),   // leavesQty after this fill
                    true, now()));

            // Per-order fill notification for the maker (order-management service)
            // The maker's leavesQty is no longer accessible after the engine removes
            // it — we emit ZERO to signal a full fill, or use a sentinel for partial.
            // A production system would track this via a separate fill aggregator that
            // updates each order's state as fills arrive.
            publisher.publish(new OutboundOrderEvent.FillExecuted(
                    nextEventId(), trade.getMakerOrderId(), symbol, tradeId,
                    trade.getPrice(), trade.getQuantity(),
                    BigDecimal.ZERO,   // conservative: engine removes fully-filled makers
                    false, now()));

            metrics.recordTrade(trade.getQuantity(), trade.getPrice());
        }
    }

    /**
     * Translates engine {@link BookEvent}s into {@link OutboundOrderEvent.BookUpdated}
     * events with the current total quantity at the affected price level.
     *
     * <p>totalQtyAtLevel is computed after the mutation, so consumers can reset their
     * local level state rather than applying a delta (useful for reconnects / gaps).
     */
    private void emitBookUpdates(MatchResult result) {
        for (BookEvent be : result.getBookEvents()) {
            Order o = be.getOrder();
            BigDecimal delta = switch (be.getType()) {
                case ADD    ->  o.getLeavesQuantity();            // positive: quantity added
                case CANCEL -> o.getLeavesQuantity().negate();    // negative: quantity removed
                case FILL   -> be.getOrder().getLeavesQuantity()
                                  .subtract(be.getOrder().getOriginalQuantity()); // fill delta
                case AMEND  -> BigDecimal.ZERO;                   // amend emits cancel+add separately
            };

            publisher.publish(new OutboundOrderEvent.BookUpdated(
                    nextEventId(), symbol,
                    o.getSide(), o.getPrice(),
                    delta,
                    levelTotalQty(o.getSide(), o.getPrice()),
                    be.getType(), now()));
        }
    }

    // -------------------------------------------------------------------------
    // Book introspection helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the total resting quantity at a price level <em>after</em> the current
     * mutation, or {@link BigDecimal#ZERO} if the level no longer exists.
     * Used to populate {@link OutboundOrderEvent.BookUpdated#totalQtyAtLevel()}.
     */
    private BigDecimal levelTotalQty(Side side, BigDecimal price) {
        OrderBook book = matchingEngine.getOrderBook();
        Map<BigDecimal, PriceLevel> sideMap = (side == Side.BID)
                ? book.getBids() : book.getAsks();
        PriceLevel level = sideMap.get(price);
        if (level == null) return BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        for (Order o : level) {
            total = total.add(o.getLeavesQuantity());
        }
        return total;
    }

    // -------------------------------------------------------------------------
    // Sequence generators
    // -------------------------------------------------------------------------

    private String nextEventId() {
        return symbol + "-E-" + (++eventSeq);
    }

    private String nextTradeId() {
        return symbol + "-T-" + (++tradeSeq);
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    // -------------------------------------------------------------------------
    // Validation helpers
    // -------------------------------------------------------------------------

    private void guardSymbol(String incomingSymbol) {
        if (!this.symbol.equals(incomingSymbol)) {
            throw new IllegalArgumentException(
                    "Symbol mismatch: this processor handles [" + symbol
                            + "] but received command for [" + incomingSymbol + "]");
        }
    }

    private static void requirePositive(BigDecimal value, String field, RejectionReason reason) {
        if (value == null || value.signum() <= 0) {
            throw new ValidationException(reason,
                    field + " must be a positive decimal, got: " + value);
        }
    }

    private static void requireNonBlank(String value, String field, RejectionReason reason) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(reason,
                    field + " must not be blank");
        }
    }

    // -------------------------------------------------------------------------
    // Accessors (for testing / monitoring)
    // -------------------------------------------------------------------------

    public String getSymbol() {
        return symbol;
    }

    /** Returns the last emitted event sequence number (for gap detection by consumers). */
    public long getLastEventSeq() {
        return eventSeq;
    }

    /** Returns the last emitted trade sequence number. */
    public long getLastTradeSeq() {
        return tradeSeq;
    }
}
