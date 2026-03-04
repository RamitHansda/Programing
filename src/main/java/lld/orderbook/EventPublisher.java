package lld.orderbook;

/**
 * Outbound event sink consumed by the {@link OrderEventProcessor}.
 *
 * <p>In production this is backed by a Kafka producer with:
 * <ul>
 *   <li>partition key = {@code symbol} (total ordering per instrument)</li>
 *   <li>{@code acks=all} + idempotent producer (exactly-once to the log)</li>
 *   <li>synchronous send inside the matching thread to ensure the event is durable
 *       before the processor advances its offset</li>
 * </ul>
 *
 * <p>In tests it is replaced by a {@link java.util.List}-backed capture that lets
 * assertions inspect emitted events without standing up a broker.
 *
 * <p><b>Contract:</b> implementations must be non-blocking from the caller's perspective
 * (or block only for the Kafka ack, which is sub-millisecond in normal operation).
 * The processor calls {@code publish} strictly in event-sequence order.
 */
public interface EventPublisher {

    /**
     * Publish an event to the outbound stream.
     * Implementations must be idempotent with respect to duplicate calls carrying
     * the same {@code eventId}.
     *
     * @throws PublishException if the event cannot be written (e.g. broker unavailable)
     */
    void publish(OutboundOrderEvent event);

    /**
     * Route a poison-pill command (or its accompanying exception) to the dead-letter
     * queue for manual inspection.
     *
     * <p>Sending to DLQ is best-effort; the processor continues to the next command
     * regardless of whether this call succeeds.
     */
    void publishToDlq(OrderCommand command, Throwable cause);

    // -------------------------------------------------------------------------
    // Typed exception so callers can distinguish publish failures from logic errors
    // -------------------------------------------------------------------------

    class PublishException extends RuntimeException {
        public PublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
