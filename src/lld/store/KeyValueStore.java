package lld.store;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * A simple key-value store with TTL and prefix search.
 */
public interface KeyValueStore<K, V> extends AutoCloseable {

    /**
     * Put a value without expiration.
     */
    void put(K key, V value);

    /**
     * Put a value with a time-to-live.
     */
    void put(K key, V value, Duration ttl);

    /**
     * Get a value if present and not expired.
     */
    Optional<V> get(K key);

    /**
     * Remove a key if present.
     */
    void remove(K key);

    /**
     * Return keys that start with the given prefix. Expired entries are excluded.
     */
    List<K> keysWithPrefix(String prefix);

    /**
     * Current number of non-expired entries.
     */
    int size();

    /**
     * Close and release resources.
     */
    @Override
    void close();
}

