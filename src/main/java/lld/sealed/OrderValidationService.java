package lld.sealed;

import java.math.BigDecimal;

/**
 * <h2>OrderValidationService — consumer of the {@link Result} sealed type</h2>
 *
 * <p>Shows the three things sealed interfaces enable in practice:
 *
 * <ol>
 *   <li><b>Exhaustive switch</b> — no {@code default} branch, compiler enforces all cases.</li>
 *   <li><b>Pattern binding</b> — each {@code case} gives you a typed variable directly,
 *       no casting.</li>
 *   <li><b>Chaining</b> — {@link Result#flatMap} threads multiple validation steps,
 *       short-circuiting on the first failure.</li>
 * </ol>
 */
public class OrderValidationService {

    // -------------------------------------------------------------------------
    // Validated domain object
    // -------------------------------------------------------------------------

    /** An order that has passed all validation rules — safe to send to the engine. */
    public record ValidatedOrder(
            String orderId,
            String symbol,
            BigDecimal price,
            BigDecimal quantity
    ) {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Validates an order and returns a typed {@link Result}.
     *
     * <p>Validation rules checked in order:
     * <ol>
     *   <li>orderId must not be blank</li>
     *   <li>symbol must not be blank</li>
     *   <li>price must be positive</li>
     *   <li>quantity must be positive</li>
     * </ol>
     *
     * <p>The first failing rule short-circuits — subsequent rules are not checked.
     * This is the "railway-oriented" style: each step either stays on the happy
     * track ({@link Result.Success}) or diverts to the failure track ({@link Result.Failure}).
     */
    public Result<ValidatedOrder> validate(String orderId, String symbol,
                                           BigDecimal price, BigDecimal quantity) {
        return checkOrderId(orderId)
                .flatMap(id     -> checkSymbol(id, symbol))
                .flatMap(sym    -> checkPrice(orderId, symbol, price))
                .flatMap(p      -> checkQuantity(orderId, symbol, p, quantity));
    }

    /**
     * Converts the {@link Result} into the HTTP-style response string that a gateway
     * would send back to the client.
     *
     * <p>This is the canonical exhaustive-switch usage: no {@code default}, every
     * permitted type is handled, the compiler guarantees correctness.
     */
    public String toGatewayResponse(Result<ValidatedOrder> result) {

        // ----- THE KEY PATTERN -----
        // switch on a sealed type → compiler rejects any non-exhaustive switch.
        // Each case binds a typed variable (s, f) — no casting needed.
        return switch (result) {
            case Result.Success<ValidatedOrder> s ->
                    "200 OK — order accepted: " + s.value().orderId()
                    + " | symbol=" + s.value().symbol()
                    + " | price="  + s.value().price()
                    + " | qty="    + s.value().quantity();

            case Result.Failure<ValidatedOrder> f ->
                    "400 Bad Request — " + f.code() + ": " + f.message();
        };
    }

    // -------------------------------------------------------------------------
    // Individual validation steps (each returns a Result)
    // -------------------------------------------------------------------------

    private Result<String> checkOrderId(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return Result.fail("MISSING_ORDER_ID", "orderId must not be blank");
        }
        return Result.ok(orderId);
    }

    private Result<String> checkSymbol(String orderId, String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return Result.fail("MISSING_SYMBOL", "symbol must not be blank for order: " + orderId);
        }
        return Result.ok(symbol);
    }

    private Result<BigDecimal> checkPrice(String orderId, String symbol, BigDecimal price) {
        if (price == null || price.signum() <= 0) {
            return Result.fail("INVALID_PRICE",
                    "price must be positive, got: " + price + " for order: " + orderId);
        }
        return Result.ok(price);
    }

    private Result<ValidatedOrder> checkQuantity(String orderId, String symbol,
                                                  BigDecimal price, BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            return Result.fail("INVALID_QUANTITY",
                    "quantity must be positive, got: " + quantity + " for order: " + orderId);
        }
        return Result.ok(new ValidatedOrder(orderId, symbol, price, quantity));
    }
}
