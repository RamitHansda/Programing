package lld.multiplayer.event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lightweight, asynchronous, in-process publish/subscribe bus for {@link SessionEvent}s.
 *
 * <p>Design choices:
 * <ul>
 *   <li>Listeners can be registered globally (receive all events) or scoped to a specific
 *       {@link SessionEventType} for fine-grained filtering.</li>
 *   <li>Each {@link #publish(SessionEvent)} call dispatches to all matching listeners on a
 *       dedicated virtual-thread executor so the caller is never blocked.</li>
 *   <li>Listener exceptions are caught and logged; a misbehaving listener never disrupts
 *       delivery to other listeners or the publishing thread.</li>
 *   <li>{@link #shutdown()} stops the executor gracefully; after calling it, further
 *       {@link #publish} calls are silently dropped.</li>
 * </ul>
 *
 * <p>This bus is intentionally in-process. In production the same interface would be
 * backed by a durable broker (Kafka, RabbitMQ) or a WebSocket fan-out layer.
 */
public class SessionEventBus {

    private static final Logger LOG = Logger.getLogger(SessionEventBus.class.getName());

    private final List<SessionEventListener> globalListeners = new CopyOnWriteArrayList<>();
    private final Map<SessionEventType, List<SessionEventListener>> typedListeners =
            new ConcurrentHashMap<>();

    private final ExecutorService executor;
    private volatile boolean running = true;

    public SessionEventBus() {
        // Virtual threads (Java 21+) keep dispatch cheap even at high throughput
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Subscribes {@code listener} to every event published on this bus, regardless of type.
     */
    public void subscribe(SessionEventListener listener) {
        globalListeners.add(listener);
    }

    /**
     * Subscribes {@code listener} to events of the specified {@code type} only.
     */
    public void subscribe(SessionEventType type, SessionEventListener listener) {
        typedListeners.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /**
     * Removes a previously registered global listener.
     */
    public void unsubscribe(SessionEventListener listener) {
        globalListeners.remove(listener);
    }

    /**
     * Removes a previously registered typed listener.
     */
    public void unsubscribe(SessionEventType type, SessionEventListener listener) {
        List<SessionEventListener> list = typedListeners.get(type);
        if (list != null) list.remove(listener);
    }

    /**
     * Publishes {@code event} asynchronously to all matching listeners.
     *
     * <p>Delivery order within a single {@code publish} call is not guaranteed across
     * concurrent publishes, but each individual listener receives events in the order
     * they were submitted to the executor.
     */
    public void publish(SessionEvent event) {
        if (!running) return;

        executor.submit(() -> {
            for (SessionEventListener l : globalListeners) {
                dispatch(l, event);
            }
            List<SessionEventListener> typed = typedListeners.get(event.getType());
            if (typed != null) {
                for (SessionEventListener l : typed) {
                    dispatch(l, event);
                }
            }
        });
    }

    private void dispatch(SessionEventListener listener, SessionEvent event) {
        try {
            listener.onEvent(event);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "SessionEventListener threw an exception for event " + event, e);
        }
    }

    /**
     * Shuts down the bus. After this call, {@link #publish} is a no-op and in-flight events
     * finish delivery before the executor terminates.
     */
    public void shutdown() {
        running = false;
        executor.shutdown();
    }
}
