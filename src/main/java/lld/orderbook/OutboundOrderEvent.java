package lld.orderbook;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Events emitted by {@link OrderEventProcessor} onto the outbound event stream.
 *
 * <p>Every event carries:
 * <ul>
 *   <li>{@code eventId}  – monotonic ID scoped to this processor/symbol; enables consumers to
 *       detect gaps and deduplicate at-least-once delivery.</li>
 *   <li>{@code symbol}   – partition key so Kafka routes all events for the same instrument
 *       to the same partition, preserving total order.</li>
 *   <li>{@code timestamp} – wall-clock millis at the time the event is created; used by
 *       downstream audit / compliance consumers.</li>
 * </ul>
 *
 * <p>Records are immutable by construction. Using a sealed hierarchy instead of an open
 * class hierarchy means new event types cannot be added without updating all consumers'
 * switch statements — a deliberate rigidity that prevents silent consumer breakage.
 */
public sealed interface OutboundOrderEvent
        permits OutboundOrderEvent.OrderAccepted,
                OutboundOrderEvent.OrderRejected,
                OutboundOrderEvent.OrderCancelled,
                OutboundOrderEvent.TradeExecuted,
                OutboundOrderEvent.FillExecuted,
                OutboundOrderEvent.BookUpdated {

    String eventId();
    String symbol();
    long timestamp();

    // -------------------------------------------------------------------------
    // OrderAccepted
    // -------------------------------------------------------------------------

    /**
     * The matching engine has accepted and processed the order.
     * Emitted regardless of whether it was immediately filled, partially filled, or rests.
     *
     * @param leavesQty remaining quantity after any immediate fills; 0 means fully filled now
     */
    record OrderAccepted(
            String eventId,
            String orderId,
            String symbol,
            Side side,
            BigDecimal price,
            BigDecimal originalQty,
            BigDecimal leavesQty,
            OrderType orderType,
            long timestamp
    ) implements OutboundOrderEvent {
        public OrderAccepted {
            Objects.requireNonNull(eventId,     "eventId");
            Objects.requireNonNull(orderId,     "orderId");
            Objects.requireNonNull(symbol,      "symbol");
            Objects.requireNonNull(side,        "side");
            Objects.requireNonNull(price,       "price");
            Objects.requireNonNull(originalQty, "originalQty");
            Objects.requireNonNull(leavesQty,   "leavesQty");
            Objects.requireNonNull(orderType,   "orderType");
        }
    }

    // -------------------------------------------------------------------------
    // OrderRejected
    // -------------------------------------------------------------------------

    /**
     * The order was rejected before reaching the matching engine (validation failure,
     * business rule, or FOK unfillable).  No book state was mutated.
     */
    record OrderRejected(
            String eventId,
            String orderId,
            String symbol,
            RejectionReason reason,
            String message,
            long timestamp
    ) implements OutboundOrderEvent {
        public OrderRejected {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(orderId, "orderId");
            Objects.requireNonNull(symbol,  "symbol");
            Objects.requireNonNull(reason,  "reason");
            Objects.requireNonNull(message, "message");
        }
    }

    // -------------------------------------------------------------------------
    // OrderCancelled
    // -------------------------------------------------------------------------

    /**
     * A resting order (or the unfilled remainder) was removed from the book.
     *
     * @param cancelledQty the quantity that was resting at the time of cancellation
     * @param reason       why it was cancelled (client request, IOC expiry, amend, etc.)
     */
    record OrderCancelled(
            String eventId,
            String orderId,
            String symbol,
            BigDecimal cancelledQty,
            CancellationReason reason,
            long timestamp
    ) implements OutboundOrderEvent {
        public OrderCancelled {
            Objects.requireNonNull(eventId,      "eventId");
            Objects.requireNonNull(orderId,      "orderId");
            Objects.requireNonNull(symbol,       "symbol");
            Objects.requireNonNull(cancelledQty, "cancelledQty");
            Objects.requireNonNull(reason,       "reason");
        }
    }

    // -------------------------------------------------------------------------
    // TradeExecuted  (one per match between a taker and a maker)
    // -------------------------------------------------------------------------

    /**
     * A trade occurred between a taker (incoming) and a maker (resting) order.
     * Consumers who manage positions, PnL, or market data subscribe to this event.
     *
     * <p>One {@code TradeExecuted} is emitted per resting order consumed; a single
     * incoming order sweeping N price levels produces N {@code TradeExecuted} events.
     *
     * @param tradeId      globally unique trade ID (symbol-scoped monotonic counter)
     * @param takerSide    side of the incoming (taker) order
     */
    record TradeExecuted(
            String eventId,
            String tradeId,
            String symbol,
            BigDecimal price,
            BigDecimal quantity,
            String takerOrderId,
            String makerOrderId,
            Side takerSide,
            long timestamp
    ) implements OutboundOrderEvent {
        public TradeExecuted {
            Objects.requireNonNull(eventId,      "eventId");
            Objects.requireNonNull(tradeId,      "tradeId");
            Objects.requireNonNull(symbol,       "symbol");
            Objects.requireNonNull(price,        "price");
            Objects.requireNonNull(quantity,     "quantity");
            Objects.requireNonNull(takerOrderId, "takerOrderId");
            Objects.requireNonNull(makerOrderId, "makerOrderId");
            Objects.requireNonNull(takerSide,    "takerSide");
        }
    }

    // -------------------------------------------------------------------------
    // FillExecuted  (one per order per trade — taker AND maker get their own fill)
    // -------------------------------------------------------------------------

    /**
     * Per-order fill notification.  One event is emitted for the taker and one for the
     * maker on every trade, so each user's order-management service only needs to filter
     * by orderId rather than inspecting both sides of a {@link TradeExecuted}.
     *
     * @param isTaker    true = this order was the aggressor; false = resting maker
     * @param leavesQty  remaining quantity after this fill (0 = fully filled)
     */
    record FillExecuted(
            String eventId,
            String orderId,
            String symbol,
            String tradeId,
            BigDecimal fillPrice,
            BigDecimal fillQty,
            BigDecimal leavesQty,
            boolean isTaker,
            long timestamp
    ) implements OutboundOrderEvent {
        public FillExecuted {
            Objects.requireNonNull(eventId,   "eventId");
            Objects.requireNonNull(orderId,   "orderId");
            Objects.requireNonNull(symbol,    "symbol");
            Objects.requireNonNull(tradeId,   "tradeId");
            Objects.requireNonNull(fillPrice, "fillPrice");
            Objects.requireNonNull(fillQty,   "fillQty");
            Objects.requireNonNull(leavesQty, "leavesQty");
        }
    }

    // -------------------------------------------------------------------------
    // BookUpdated  (book-level delta for L2 / depth-of-book feeds)
    // -------------------------------------------------------------------------

    /**
     * A price level on the book changed.  Downstream market-data consumers apply these
     * deltas to reconstruct and maintain the live L2 order book.
     *
     * @param deltaQty       change in total quantity at this level:
     *                       positive = quantity added, negative = quantity removed
     * @param totalQtyAtLevel total resting quantity at this level after the update
     *                       (0 means the level was removed)
     * @param updateType     ADD / FILL / CANCEL / AMEND — mirrors {@link BookEvent.Type}
     */
    record BookUpdated(
            String eventId,
            String symbol,
            Side side,
            BigDecimal price,
            BigDecimal deltaQty,
            BigDecimal totalQtyAtLevel,
            BookEvent.Type updateType,
            long timestamp
    ) implements OutboundOrderEvent {
        public BookUpdated {
            Objects.requireNonNull(eventId,        "eventId");
            Objects.requireNonNull(symbol,         "symbol");
            Objects.requireNonNull(side,           "side");
            Objects.requireNonNull(price,          "price");
            Objects.requireNonNull(deltaQty,       "deltaQty");
            Objects.requireNonNull(totalQtyAtLevel,"totalQtyAtLevel");
            Objects.requireNonNull(updateType,     "updateType");
        }
    }
}
