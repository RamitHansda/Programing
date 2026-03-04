package lld.orderbook;

import java.math.BigDecimal;

/**
 * No-op {@link ProcessingMetrics} — discards every observation.
 * Use in unit tests or environments without a metrics backend.
 */
final class NoOpMetrics implements ProcessingMetrics {

    static final NoOpMetrics INSTANCE = new NoOpMetrics();

    private NoOpMetrics() {}

    @Override public void recordProcessed(String commandType, long latencyNanos) {}
    @Override public void recordRejection(RejectionReason reason) {}
    @Override public void recordDuplicate() {}
    @Override public void recordTrade(BigDecimal quantity, BigDecimal price) {}
    @Override public void recordError() {}
}
