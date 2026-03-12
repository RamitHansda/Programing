package lld.messagebroker;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of MessageBroker. Topics and queues are created on demand
 * and stored in concurrent maps. No persistence; suitable for in-process messaging.
 */
public final class InMemoryMessageBroker implements MessageBroker {

    private final Map<String, Topic<?>> topics = new ConcurrentHashMap<>();
    private final Map<String, Queue<?>> queues = new ConcurrentHashMap<>();
    private final Map<String, ReliableQueue<?>> reliableQueues = new ConcurrentHashMap<>();
    private volatile boolean shutdown;

    @Override
    @SuppressWarnings("unchecked")
    public <T> Topic<T> topic(String name) {
        if (shutdown) {
            throw new IllegalStateException("Broker is shutdown");
        }
        return (Topic<T>) topics.computeIfAbsent(name, n -> new InMemoryTopic<T>(n));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Queue<T> queue(String name) {
        if (shutdown) {
            throw new IllegalStateException("Broker is shutdown");
        }
        return (Queue<T>) queues.computeIfAbsent(name, n -> new InMemoryQueue<T>(n));
    }

    /**
     * Get or create a bounded queue. Producers block when full.
     */
    @SuppressWarnings("unchecked")
    public <T> BoundedQueue<T> queue(String name, int capacity) {
        if (shutdown) {
            throw new IllegalStateException("Broker is shutdown");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        return (BoundedQueue<T>) queues.computeIfAbsent(name, n -> new InMemoryBoundedQueue<T>(n, capacity));
    }

    /**
     * Get or create a reliable queue with at-least-once delivery guarantees.
     *
     * @param name               unique queue name
     * @param maxRetries         max delivery attempts before routing to DLQ
     * @param visibilityTimeoutMs millis a consumer has to ack/nack before automatic re-delivery
     */
    @SuppressWarnings("unchecked")
    public <T> ReliableQueue<T> reliableQueue(String name, int maxRetries, long visibilityTimeoutMs) {
        if (shutdown) {
            throw new IllegalStateException("Broker is shutdown");
        }
        return (ReliableQueue<T>) reliableQueues.computeIfAbsent(
                name, n -> new InMemoryReliableQueue<T>(n, maxRetries, visibilityTimeoutMs));
    }

    /** Reliable queue with default settings (3 retries, 30 s visibility timeout). */
    public <T> ReliableQueue<T> reliableQueue(String name) {
        return reliableQueue(name,
                InMemoryReliableQueue.DEFAULT_MAX_RETRIES,
                InMemoryReliableQueue.DEFAULT_VISIBILITY_TIMEOUT_MS);
    }

    @Override
    public void shutdown() {
        shutdown = true;
        topics.clear();
        queues.clear();
        reliableQueues.clear();
    }
}
