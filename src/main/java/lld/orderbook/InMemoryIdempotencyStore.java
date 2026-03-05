package lld.orderbook;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link IdempotencyStore} backed by a {@link ConcurrentHashMap}.
 *
 * <p><b>Use this in:</b> unit / integration tests and single-node deployments.
 * In multi-node production deployments replace with a Redis-backed implementation
 * so deduplication survives process restarts and works across replicas.
 *
 * <p><b>TTL:</b> each entry is stamped with an expiry instant derived from a
 * configurable {@link Duration} and a {@link Clock} (injected for testability).
 * Expired entries are treated as absent on reads and are lazily evicted during
 * {@link #setIfAbsent} calls.  An explicit call to {@link #evictExpired()} lets
 * tests and background tasks perform a full sweep.
 *
 * <p><b>No TTL mode:</b> pass {@link Duration#ZERO} (or use the no-arg constructor)
 * to keep keys indefinitely — useful when memory growth is bounded by other means.
 *
 * <p><b>Thread safety:</b> {@link ConcurrentHashMap#putIfAbsent} is atomic, so
 * concurrent calls with the same key are safe — exactly one caller gets {@code true}.
 */
public final class InMemoryIdempotencyStore implements IdempotencyStore {

    /**
     * A stored entry: the orderId paired with the instant at which it expires.
     * {@code expiresAt == null} means the entry never expires.
     */
    private record Entry(String orderId, Instant expiresAt) {
        boolean isExpired(Instant now) {
            return expiresAt != null && !now.isBefore(expiresAt);
        }
    }

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /** No TTL — entries live forever.  Equivalent to the old behaviour. */
    public InMemoryIdempotencyStore() {
        this(Duration.ZERO, Clock.systemUTC());
    }

    /**
     * @param ttl   how long each entry lives; {@link Duration#ZERO} means no expiry
     * @param clock clock used to derive entry timestamps (inject a fixed clock in tests)
     */
    public InMemoryIdempotencyStore(Duration ttl, Clock clock) {
        this.ttl   = ttl;
        this.clock = clock;
    }

    // -------------------------------------------------------------------------
    // IdempotencyStore
    // -------------------------------------------------------------------------

    @Override
    public boolean setIfAbsent(String idempotencyKey, String orderId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }

        Instant now = clock.instant();
        Entry newEntry = new Entry(orderId, expiresAt(now));

        // Atomically remove an expired entry so the next putIfAbsent succeeds.
        store.compute(idempotencyKey, (k, existing) -> {
            if (existing != null && existing.isExpired(now)) {
                return null;  // evict; compute returns null → key removed
            }
            return existing; // keep as-is (null stays null, live entry stays)
        });

        return store.putIfAbsent(idempotencyKey, newEntry) == null;
    }

    @Override
    public Optional<String> get(String idempotencyKey) {
        Entry entry = store.get(idempotencyKey);
        if (entry == null) return Optional.empty();
        if (entry.isExpired(clock.instant())) return Optional.empty();
        return Optional.of(entry.orderId());
    }

    // -------------------------------------------------------------------------
    // Maintenance
    // -------------------------------------------------------------------------

    /**
     * Removes all entries whose TTL has elapsed.  Call from a scheduled task or
     * in tests where you want a clean sweep rather than relying on lazy eviction.
     *
     * @return number of entries evicted
     */
    public int evictExpired() {
        Instant now = clock.instant();
        int count = 0;
        Iterator<Map.Entry<String, Entry>> it = store.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isExpired(now)) {
                it.remove();
                count++;
            }
        }
        return count;
    }

    /** Returns the number of live (non-expired) entries.  Visible for testing. */
    public int size() {
        Instant now = clock.instant();
        return (int) store.values().stream().filter(e -> !e.isExpired(now)).count();
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private Instant expiresAt(Instant now) {
        return (ttl == null || ttl.isZero()) ? null : now.plus(ttl);
    }
}
