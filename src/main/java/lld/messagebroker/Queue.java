package lld.messagebroker;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Point-to-point channel: each message is consumed by exactly one consumer (competing consumers).
 */
public interface Queue<T> {

    String getName();

    /**
     * Enqueue a message. Blocks if the queue is bounded and full.
     */
    void enqueue(Message<T> message) throws InterruptedException;

    /**
     * Convenience: wrap payload in a Message and enqueue.
     */
    default void enqueue(T payload) throws InterruptedException {
        enqueue(new Message<>(payload));
    }

    /**
     * Dequeue one message. Blocks until a message is available.
     */
    Message<T> dequeue() throws InterruptedException;

    /**
     * Dequeue one message, waiting up to the specified time.
     *
     * @return empty if timeout elapsed before a message was available
     */
    Optional<Message<T>> dequeue(long timeout, TimeUnit unit) throws InterruptedException;

    /**
     * Current number of messages in the queue (approximate for concurrent implementations).
     */
    int size();
}
