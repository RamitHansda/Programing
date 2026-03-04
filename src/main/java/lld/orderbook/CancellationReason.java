package lld.orderbook;

/**
 * Reason codes emitted in {@link OutboundOrderEvent.OrderCancelled} events.
 * Separating cancellation reasons from rejection reasons keeps each enum focused
 * and lets consumers route events correctly (e.g., IOC_EXPIRED is not an error).
 */
public enum CancellationReason {
    CLIENT_REQUEST,   // user explicitly cancelled
    IOC_EXPIRED,      // Immediate-Or-Cancel remainder expired after partial fill
    AMENDED,          // order was cancelled as part of an amend (cancel + re-insert)
    RISK_SYSTEM,      // killed by the risk engine (position limit, circuit breaker, etc.)
    MARKET_CLOSE,     // book drain at end-of-day
    SYSTEM_CANCEL     // operator / admin-initiated cancel
}
