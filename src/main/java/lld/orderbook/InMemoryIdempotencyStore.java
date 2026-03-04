package lld.orderbook;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link IdempotencyStore} backed by a {@link ConcurrentHashMap}.
 *
 * <p><b>Use this in:</b> unit / integration tests and single-node deployments.
 * In multi-node production deployments replace with a Redis-backed implementation
 * so deduplication survives process restarts and works across replicas.
 *
 * <p><b>No TTL:</b> this implementation holds keys indefinitely.  For a
 * production-grade in-memory store, wrap entries in a timestamped record and evict
 * with a scheduled task.
 *
 * <p><b>Thread safety:</b> {@link ConcurrentHashMap#putIfAbsent} is atomic, so
 * concurrent calls with the same key are safe — exactly one caller gets {@code true}.
 */
public final class InMemoryIdempotencyStore implements IdempotencyStore {

    private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();

    @Override
    public boolean setIfAbsent(String idempotencyKey, String orderId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        return store.putIfAbsent(idempotencyKey, orderId) == null;
    }

    @Override
    public Optional<String> get(String idempotencyKey) {
        return Optional.ofNullable(store.get(idempotencyKey));
    }

    /** Visible for testing. */
    public int size() {
        return store.size();
    }
}
