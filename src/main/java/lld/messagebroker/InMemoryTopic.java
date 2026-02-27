package lld.messagebroker;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * In-memory topic: publishes to all registered subscribers (fan-out).
 * Delivery is synchronous on the publisher thread; subscribers should not block long.
 */
final class InMemoryTopic<T> implements Topic<T> {

    private final String name;
    private final Set<Subscriber<T>> subscribers = new CopyOnWriteArraySet<>();

    InMemoryTopic(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void publish(Message<T> message) {
        for (Subscriber<T> sub : subscribers) {
            try {
                sub.onMessage(message);
            } catch (Exception e) {
                // Log and continue so one bad subscriber doesn't break others
                System.err.println("Subscriber error in topic " + name + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void subscribe(Subscriber<T> subscriber) {
        subscribers.add(subscriber);
    }

    @Override
    public void unsubscribe(Subscriber<T> subscriber) {
        subscribers.remove(subscriber);
    }
}
