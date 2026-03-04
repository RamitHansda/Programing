package lld.orderbook;

/**
 * Typed rejection codes for OrderRejected events.
 *
 * <p>Having an enum (rather than a free-form string) lets downstream consumers
 * branch deterministically—e.g., risk systems can alert on SELF_TRADE while
 * gateways silently swallow DUPLICATE_ORDER.
 */
public enum RejectionReason {

    // --- Validation failures (4xx analogue) ---
    INVALID_PRICE("Price must be a positive decimal"),
    INVALID_QUANTITY("Quantity must be a positive decimal"),
    INVALID_SYMBOL("Symbol not recognised by this processor"),
    MISSING_REQUIRED_FIELD("A required order field is absent or blank"),

    // --- Business rule failures ---
    DUPLICATE_ORDER("An order with this idempotency key already exists"),
    ORDER_NOT_FOUND("No resting order found with the given orderId"),
    SELF_TRADE_PREVENTED("Incoming order would trade against an order from the same user"),
    FOK_CANNOT_FILL("FOK order cannot be fully filled by available liquidity"),
    MAX_ORDER_SIZE_EXCEEDED("Order quantity exceeds the configured maximum per order"),
    PRICE_OUT_OF_COLLAR("Price is outside the permitted distance from the mid-market"),

    // --- Internal / transient ---
    INTERNAL_ERROR("An unexpected internal error occurred during order processing");

    private final String description;

    RejectionReason(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
