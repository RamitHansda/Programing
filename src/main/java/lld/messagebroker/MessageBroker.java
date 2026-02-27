package lld.messagebroker;

/**
 * Main entry point for the message broker library.
 * Creates named topics (pub/sub) and queues (point-to-point) and provides
 * producer/consumer handles. Implementations can be in-memory, persistent, or distributed.
 */
public interface MessageBroker {

    /**
     * Get or create a topic. All subscribers receive every message published to the topic.
     *
     * @param name unique topic name
     * @return topic handle for publish and subscribe
     */
    <T> Topic<T> topic(String name);

    /**
     * Get or create a queue. Each message is delivered to exactly one consumer (competing consumers).
     *
     * @param name unique queue name
     * @return queue handle for produce and consume
     */
    <T> Queue<T> queue(String name);

    /**
     * Shutdown the broker: stop accepting new work and release resources.
     */
    void shutdown();
}
