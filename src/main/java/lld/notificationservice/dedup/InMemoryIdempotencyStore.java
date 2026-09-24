package lld.notificationservice.dedup;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe in-memory implementation of {@link IdempotencyStore}. */
public final class InMemoryIdempotencyStore implements IdempotencyStore {

    // key: "producerId:eventId" → notificationId
    private final Map<String, String> store = new ConcurrentHashMap<>();

    @Override
    public boolean putIfAbsent(String producerId, String eventId, String notificationId) {
        return store.putIfAbsent(buildKey(producerId, eventId), notificationId) == null;
    }

    @Override
    public Optional<String> get(String producerId, String eventId) {
        return Optional.ofNullable(store.get(buildKey(producerId, eventId)));
    }

    private static String buildKey(String producerId, String eventId) {
        return producerId + ":" + eventId;
    }
}
