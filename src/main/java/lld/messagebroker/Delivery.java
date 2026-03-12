package lld.messagebroker;

/**
 * Represents a single message delivery to a consumer.
 *
 * The consumer MUST call either {@link #ack()} or {@link #nack()} after processing:
 * <ul>
 *   <li>{@code ack()}  — processing succeeded; remove message permanently.</li>
 *   <li>{@code nack()} — processing failed; re-enqueue for retry or route to DLQ.</li>
 * </ul>
 *
 * If neither is called within the visibility timeout, the broker automatically
 * re-enqueues the message (simulates consumer crash recovery).
 *
 * @param <T> payload type
 */
public interface Delivery<T> {

    Message<T> getMessage();

    /**
     * Acknowledge successful processing. The message is permanently removed from the queue.
     */
    void ack();

    /**
     * Negative-acknowledge: processing failed.
     * The message will be re-enqueued if retry attempts remain, otherwise sent to the DLQ.
     */
    void nack();
}
