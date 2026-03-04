package lld.orderbook;

import java.math.BigDecimal;

/**
 * Observability hook for the {@link OrderEventProcessor}.
 *
 * <p>In production, back this with a StatsD / Prometheus client that emits:
 * <ul>
 *   <li>Latency histograms per command type (p50/p99 of matching time)</li>
 *   <li>Counters for accepted, rejected, cancelled, duplicated commands</li>
 *   <li>Trade volume (notional = price × qty) for fee reconciliation</li>
 *   <li>Error rate for alerting</li>
 * </ul>
 *
 * <p>The interface is intentionally narrow — only the processor calls these methods,
 * so we expose exactly what the processor needs to observe (not a generic metrics API).
 */
public interface ProcessingMetrics {

    /**
     * Called after a command has been successfully processed.
     *
     * @param commandType simple class name, e.g. "Submit", "Cancel", "Amend"
     * @param latencyNanos wall-clock nanoseconds from start to end of {@code process()}
     */
    void recordProcessed(String commandType, long latencyNanos);

    /**
     * Called when a command is rejected due to a validation / business rule failure.
     * The processor still emits an {@code OrderRejected} event; this counter tracks
     * the rate of client-side errors.
     */
    void recordRejection(RejectionReason reason);

    /**
     * Called when a command is deduplicated (idempotency key already seen).
     */
    void recordDuplicate();

    /**
     * Called for each trade that is executed.
     *
     * @param quantity traded quantity
     * @param price    execution price (multiply to get notional)
     */
    void recordTrade(BigDecimal quantity, BigDecimal price);

    /**
     * Called when an unexpected exception escapes command processing (the command is DLQ'd).
     * Alert on this counter — it signals a bug or infrastructure issue.
     */
    void recordError();

    // -------------------------------------------------------------------------
    // No-op implementation for tests and local dev
    // -------------------------------------------------------------------------

    /** Returns a no-op implementation that discards all metric calls. */
    static ProcessingMetrics noOp() {
        return NoOpMetrics.INSTANCE;
    }
}
