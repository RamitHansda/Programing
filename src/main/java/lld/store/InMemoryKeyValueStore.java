package lld.store;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe in-memory key-value store with TTL and prefix search.
 * Keys are assumed to be strings for prefix search; the implementation enforces this.
 */
public class InMemoryKeyValueStore<V> implements KeyValueStore<String, V> {

    private static final long NO_EXPIRY = Long.MAX_VALUE;

    private static final class Entry<V> {
        final V value;
        final long expiresAtMillis; // epoch millis when this entry expires

        Entry(V value, long expiresAtMillis) {
            this.value = value;
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    private static final class Expiry implements Comparable<Expiry> {
        final String key;
        final long expiresAtMillis;

        Expiry(String key, long expiresAtMillis) {
            this.key = key;
            this.expiresAtMillis = expiresAtMillis;
        }

        @Override
        public int compareTo(Expiry o) {
            return Long.compare(this.expiresAtMillis, o.expiresAtMillis);
        }
    }

    private final ConcurrentHashMap<String, Entry<V>> store = new ConcurrentHashMap<>();
    private final PriorityBlockingQueue<Expiry> expiryQueue = new PriorityBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread cleanerThread;

    public InMemoryKeyValueStore() {
        cleanerThread = new Thread(this::cleanerLoop, "kvs-cleaner");
        cleanerThread.setDaemon(true);
        cleanerThread.start();
    }

    @Override
    public void put(String key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        store.put(key, new Entry<>(value, NO_EXPIRY));
    }

    @Override
    public void put(String key, V value, Duration ttl) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(ttl, "ttl");
        long expiresAt = ttl.isZero() || ttl.isNegative() ? System.currentTimeMillis() : System.currentTimeMillis() + ttl.toMillis();
        store.put(key, new Entry<>(value, expiresAt));
        if (expiresAt != NO_EXPIRY) {
            expiryQueue.offer(new Expiry(key, expiresAt));
        }
    }

    @Override
    public Optional<V> get(String key) {
        Objects.requireNonNull(key, "key");
        Entry<V> entry = store.get(key);
        if (entry == null) return Optional.empty();
        if (isExpired(entry.expiresAtMillis)) {
            store.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.value);
    }

    @Override
    public void remove(String key) {
        Objects.requireNonNull(key, "key");
        store.remove(key);
    }

    @Override
    public List<String> keysWithPrefix(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        if (prefix.isEmpty()) {
            return Collections.emptyList();
        }
        long now = System.currentTimeMillis();
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Entry<V>> e : store.entrySet()) {
            String key = e.getKey();
            Entry<V> entry = e.getValue();
            if (key.startsWith(prefix) && entry.expiresAtMillis > now) {
                result.add(key);
            }
        }
        result.sort(Comparator.naturalOrder());
        return result;
    }

    @Override
    public int size() {
        long now = System.currentTimeMillis();
        int count = 0;
        for (Entry<V> e : store.values()) {
            if (e.expiresAtMillis > now) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            cleanerThread.interrupt();
            try {
                cleanerThread.join(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean isExpired(long expiresAtMillis) {
        return expiresAtMillis != NO_EXPIRY && System.currentTimeMillis() >= expiresAtMillis;
    }

    private void cleanerLoop() {
        while (running.get()) {
            try {
                Expiry exp = expiryQueue.take(); // waits until next expiry is scheduled
                long wait = exp.expiresAtMillis - System.currentTimeMillis();
                if (wait > 0) {
                    Thread.sleep(wait);
                }
                Entry<V> entry = store.get(exp.key);
                if (entry != null && isExpired(entry.expiresAtMillis)) {
                    store.remove(exp.key, entry);
                }
            } catch (InterruptedException ie) {
                // exit if shutting down
                if (!running.get()) return;
            } catch (Throwable t) {
                // avoid terminating the cleaner unexpectedly
            }
        }
    }
}



