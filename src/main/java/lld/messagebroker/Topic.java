package lld.messagebroker;

/**
 * Publish-subscribe channel: every message published is delivered to all current subscribers.
 */
public interface Topic<T> {

    String getName();

    /**
     * Publish a message to all subscribers. Non-blocking; delivery is best-effort.
     */
    void publish(Message<T> message);

    /**
     * Convenience: wrap payload in a Message and publish.
     */
    default void publish(T payload) {
        publish(new Message<>(payload));
    }

    /**
     * Register a subscriber that will receive all messages published after subscription.
     *
     * @param subscriber callback invoked for each message (may be called from broker threads)
     */
    void subscribe(Subscriber<T> subscriber);

    /**
     * Remove a previously registered subscriber.
     */
    void unsubscribe(Subscriber<T> subscriber);
}
