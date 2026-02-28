package lld.designpatterns.singleton;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents a single pooled connection. Lifecycle is managed by {@link ConnectionPoolManager}.
 */
public final class PooledConnection {

    private static final AtomicLong ID_GEN = new AtomicLong(0);

    private final long id = ID_GEN.incrementAndGet();
    private final long createdAtMs = System.currentTimeMillis();
    private final long maxLifetimeMs;
    private volatile boolean inUse;
    private volatile boolean closed;

    PooledConnection(long maxLifetimeMs) {
        this.maxLifetimeMs = maxLifetimeMs;
    }

    long id() {
        return id;
    }

    boolean isValid() {
        return !closed && (System.currentTimeMillis() - createdAtMs < maxLifetimeMs);
    }

    void setInUse(boolean inUse) {
        this.inUse = inUse;
    }

    boolean isInUse() {
        return inUse;
    }

    void close() {
        this.closed = true;
    }

    /** Placeholder for actual DB execution. */
    public void execute(String sql) {
        if (closed) {
            throw new IllegalStateException("connection closed");
        }
    }
}
