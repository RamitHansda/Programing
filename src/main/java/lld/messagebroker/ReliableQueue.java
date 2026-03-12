package lld.messagebroker;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * A queue that guarantees at-least-once delivery by requiring explicit consumer acknowledgment.
 *
 * <p>Delivery guarantees:
 * <ol>
 *   <li><b>Durable</b> — messages survive until explicitly ack'd or exhausted retries.</li>
 *   <li><b>Visibility timeout</b> — a polled message is hidden from other consumers; if the
 *       consumer does not ack/nack within the timeout, the broker re-enqueues it automatically.</li>
 *   <li><b>Retry with backoff</b> — nack'd messages are re-enqueued up to {@code maxRetries}.</li>
 *   <li><b>Dead Letter Queue</b> — messages that exceed max retries are routed to the DLQ.</li>
 * </ol>
 *
 * @param <T> payload type
 */
public interface ReliableQueue<T> {

    String getName();

    /**
     * Enqueue a message. Blocks if the internal queue is full.
     */
    void enqueue(Message<T> message) throws InterruptedException;

    default void enqueue(T payload) throws InterruptedException {
        enqueue(new Message<>(payload));
    }

    /**
     * Poll for a message. The message is placed in an in-flight state until ack/nack is called.
     * Blocks up to {@code timeout} if no message is available.
     *
     * @return a {@link Delivery} containing the message and its ack handle, or empty on timeout
     */
    Optional<Delivery<T>> poll(long timeout, TimeUnit unit) throws InterruptedException;

    /**
     * Access the Dead Letter Queue for inspection or manual reprocessing.
     */
    Queue<T> deadLetterQueue();

    /** Number of messages waiting to be delivered. */
    int pendingSize();

    /** Number of messages currently in-flight (delivered but not yet ack'd/nack'd). */
    int inflightSize();
}
