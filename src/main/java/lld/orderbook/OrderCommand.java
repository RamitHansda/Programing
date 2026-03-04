package lld.orderbook;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Inbound commands consumed by {@link OrderEventProcessor}.
 *
 * <p>Sealed + records guarantee exhaustive pattern-matching in the processor's
 * dispatch switch and make each command truly immutable (no defensive copies needed).
 *
 * <p><b>Threading model:</b> commands are produced by the Order Gateway (after ID
 * assignment, timestamp stamping, and write-ahead logging to Kafka) and consumed
 * single-threedly per symbol by the processor. Records are safe to pass across
 * threads because they hold only final fields.
 *
 * <p><b>Design note – idempotencyKey:</b> The gateway stamps a client-supplied
 * idempotency key before publishing. The processor deduplicates on this key so
 * a client retrying after a network blip does not create a second order.
 */
public sealed interface OrderCommand
        permits OrderCommand.Submit, OrderCommand.Cancel, OrderCommand.Amend {

    String symbol();
    long timestamp();

    // -------------------------------------------------------------------------
    // Submit — place a new order on the book
    // -------------------------------------------------------------------------

    /**
     * Request to place a new limit (or IOC / FOK / market) order.
     *
     * @param orderId        globally unique order ID assigned by the gateway
     * @param userId         owner of the order (used for self-trade prevention)
     * @param symbol         instrument (e.g. "BTC-USD")
     * @param side           BID (buy) or ASK (sell)
     * @param price          limit price; for MARKET orders use an extreme sentinel price
     * @param quantity       requested quantity (positive)
     * @param orderType      LIMIT / MARKET / IOC / FOK
     * @param idempotencyKey optional client-supplied dedup key; null if not provided
     * @param timestamp      nanosecond epoch assigned by the gateway before publishing
     */
    record Submit(
            String orderId,
            String userId,
            String symbol,
            Side side,
            BigDecimal price,
            BigDecimal quantity,
            OrderType orderType,
            String idempotencyKey,
            long timestamp
    ) implements OrderCommand {

        public Submit {
            Objects.requireNonNull(orderId,    "orderId");
            Objects.requireNonNull(userId,     "userId");
            Objects.requireNonNull(symbol,     "symbol");
            Objects.requireNonNull(side,       "side");
            Objects.requireNonNull(price,      "price");
            Objects.requireNonNull(quantity,   "quantity");
            Objects.requireNonNull(orderType,  "orderType");
            if (orderId.isBlank())  throw new IllegalArgumentException("orderId is blank");
            if (userId.isBlank())   throw new IllegalArgumentException("userId is blank");
            if (symbol.isBlank())   throw new IllegalArgumentException("symbol is blank");
        }

        /** Convenience factory — no idempotency key, timestamp = now. */
        public static Submit limitOrder(String orderId, String userId, String symbol,
                                        Side side, BigDecimal price, BigDecimal quantity) {
            return new Submit(orderId, userId, symbol, side, price, quantity,
                    OrderType.LIMIT, null, System.nanoTime());
        }
    }

    // -------------------------------------------------------------------------
    // Cancel — remove a resting order
    // -------------------------------------------------------------------------

    /**
     * Request to cancel a resting order.
     *
     * @param orderId     the order to cancel
     * @param symbol      must match the symbol this processor manages
     * @param requesterId userId of the requester (used for authorisation checks upstream)
     * @param timestamp   nanosecond epoch
     */
    record Cancel(
            String orderId,
            String symbol,
            String requesterId,
            long timestamp
    ) implements OrderCommand {

        public Cancel {
            Objects.requireNonNull(orderId,     "orderId");
            Objects.requireNonNull(symbol,      "symbol");
            Objects.requireNonNull(requesterId, "requesterId");
            if (orderId.isBlank()) throw new IllegalArgumentException("orderId is blank");
        }
    }

    // -------------------------------------------------------------------------
    // Amend — cancel + re-insert at new price / quantity (loses time priority)
    // -------------------------------------------------------------------------

    /**
     * Request to amend the price and/or quantity of a resting order.
     *
     * <p>An amend is semantically cancel-then-resubmit: the order loses its
     * time priority at the old price level and gets a new position at the new level.
     * This is the standard exchange behaviour for CLOB amendments.
     *
     * @param orderId     the order to amend
     * @param symbol      must match the symbol this processor manages
     * @param newPrice    replacement price (positive)
     * @param newQuantity replacement total quantity (positive); uses leavesQty semantics —
     *                    the full new quantity, not a delta
     * @param timestamp   nanosecond epoch
     */
    record Amend(
            String orderId,
            String symbol,
            BigDecimal newPrice,
            BigDecimal newQuantity,
            long timestamp
    ) implements OrderCommand {

        public Amend {
            Objects.requireNonNull(orderId,     "orderId");
            Objects.requireNonNull(symbol,      "symbol");
            Objects.requireNonNull(newPrice,    "newPrice");
            Objects.requireNonNull(newQuantity, "newQuantity");
            if (orderId.isBlank()) throw new IllegalArgumentException("orderId is blank");
        }
    }
}
