package lld.orderbook;

/**
 * Signals that an inbound {@link OrderCommand} failed a validation or business-rule check.
 *
 * <p>Using a typed exception (rather than {@link IllegalArgumentException}) lets the
 * {@link OrderEventProcessor} catch it at a specific boundary and convert it into a
 * structured {@link OutboundOrderEvent.OrderRejected} event — keeping the error
 * representation in the event stream rather than in exception stack traces.
 */
public final class ValidationException extends RuntimeException {

    private final RejectionReason reason;

    public ValidationException(RejectionReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectionReason getReason() {
        return reason;
    }
}
