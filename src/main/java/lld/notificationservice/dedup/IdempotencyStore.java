package lld.notificationservice.dedup;

import java.util.Optional;

/**
 * Prevents duplicate notification creation when producers retry submissions.
 *
 * <p>The composite key is {@code (producerId, eventId)}. On the first submission,
 * the assigned internal {@code notificationId} is stored. Subsequent submissions
 * with the same key return the original {@code notificationId} without re-dispatching.
 *
 * <p>In production this is a Redis {@code SETNX} call with a 24-hour TTL.
 */
public interface IdempotencyStore {

    /**
     * Register a new {@code notificationId} for the given producer + event pair.
     * No-op if the key already exists.
     *
     * @return true if the key was newly inserted, false if it was already present
     */
    boolean putIfAbsent(String producerId, String eventId, String notificationId);

    /**
     * Look up a previously stored {@code notificationId} for the producer + event pair.
     *
     * @return the notification id, or empty if this is a first-time submission
     */
    Optional<String> get(String producerId, String eventId);
}
