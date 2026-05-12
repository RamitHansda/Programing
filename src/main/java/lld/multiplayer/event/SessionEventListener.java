package lld.multiplayer.event;

/**
 * Callback interface for components that need to react to session lifecycle changes.
 *
 * <p>Implementations must be non-blocking. The {@link SessionEventBus} dispatches events on a
 * shared thread-pool; long-running work should be delegated to a separate executor.
 */
@FunctionalInterface
public interface SessionEventListener {

    /**
     * Called when a {@link SessionEvent} is published to the bus.
     *
     * @param event the event that occurred; never {@code null}
     */
    void onEvent(SessionEvent event);
}
