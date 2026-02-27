package lld.messagebroker;

/**
 * Callback for topic subscription: invoked by the broker for each message.
 * Exceptions may be logged and not rethrown; implementors should handle errors internally.
 */
@FunctionalInterface
public interface Subscriber<T> {

    void onMessage(Message<T> message);
}
