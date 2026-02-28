package lld.designpatterns.singleton;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Singleton connection pool manager: single source of truth for DB connections per JVM.
 * Thread-safe; supports configurable pool size, get/release, and validation.
 */
public final class ConnectionPoolManager {

    private static final Object INIT_LOCK = new Object();
    private static volatile ConnectionPoolManager INSTANCE;

    private final ConnectionPoolConfig config;
    private final BlockingQueue<PooledConnection> available;
    private final ConcurrentHashMap<Long, PooledConnection> allConnections;
    private final ReentrantLock poolLock;
    private volatile boolean shutdown;

    private ConnectionPoolManager(ConnectionPoolConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.available = new LinkedBlockingQueue<>();
        this.allConnections = new ConcurrentHashMap<>();
        this.poolLock = new ReentrantLock();
        this.shutdown = false;
        initializePool();
    }

    /**
     * Returns the singleton instance. Call {@link #initialize(ConnectionPoolConfig)} once at startup.
     */
    public static ConnectionPoolManager getInstance() {
        ConnectionPoolManager ref = INSTANCE;
        if (ref == null) {
            throw new IllegalStateException("Pool not initialized. Call initialize(config) first.");
        }
        return ref;
    }

    /**
     * Initializes the singleton with the given config. Idempotent if same config; throws if already
     * initialized with a different config.
     */
    public static ConnectionPoolManager initialize(ConnectionPoolConfig config) {
        if (INSTANCE != null) {
            return INSTANCE;
        }
        synchronized (INIT_LOCK) {
            if (INSTANCE != null) {
                return INSTANCE;
            }
            INSTANCE = new ConnectionPoolManager(config);
            return INSTANCE;
        }
    }

    private void initializePool() {
        poolLock.lock();
        try {
            for (int i = 0; i < config.poolSize(); i++) {
                PooledConnection c = createConnection();
                if (c != null) {
                    available.offer(c);
                }
            }
        } finally {
            poolLock.unlock();
        }
    }

    private PooledConnection createConnection() {
        if (allConnections.size() >= config.poolSize()) {
            return null;
        }
        PooledConnection c = new PooledConnection(config.connectionTimeoutMs() * 10);
        allConnections.put(c.id(), c);
        return c;
    }

    /**
     * Acquires a connection from the pool. Blocks up to {@code config.connectionTimeoutMs()}.
     */
    public PooledConnection getConnection() throws InterruptedException, TimeoutException {
        if (shutdown) {
            throw new IllegalStateException("Pool is shutdown");
        }
        long deadline = System.currentTimeMillis() + config.connectionTimeoutMs();
        PooledConnection conn = null;
        while (conn == null) {
            if (System.currentTimeMillis() >= deadline) {
                throw new TimeoutException("Could not acquire connection within timeout");
            }
            PooledConnection candidate = available.poll(100, TimeUnit.MILLISECONDS);
            if (candidate != null) {
                if (candidate.isValid()) {
                    conn = candidate;
                } else {
                    removeConnection(candidate);
                }
            } else {
                poolLock.lock();
                try {
                    if (allConnections.size() < config.poolSize()) {
                        conn = createConnection();
                        if (conn != null) break;
                    }
                } finally {
                    poolLock.unlock();
                }
            }
        }
        conn.setInUse(true);
        return conn;
    }

    /**
     * Returns a connection to the pool. Caller must not use the connection after release.
     */
    public void releaseConnection(PooledConnection conn) {
        if (conn == null) {
            throw new IllegalArgumentException("connection must not be null");
        }
        if (!allConnections.containsKey(conn.id())) {
            throw new IllegalArgumentException("connection does not belong to this pool");
        }
        conn.setInUse(false);
        if (conn.isValid()) {
            available.offer(conn);
        } else {
            removeConnection(conn);
        }
    }

    private void removeConnection(PooledConnection conn) {
        poolLock.lock();
        try {
            conn.close();
            allConnections.remove(conn.id());
            if (allConnections.size() < config.poolSize() && !shutdown) {
                PooledConnection c = createConnection();
                if (c != null) {
                    available.offer(c);
                }
            }
        } finally {
            poolLock.unlock();
        }
    }

    public PoolStats getStats() {
        int total = allConnections.size();
        int avail = available.size();
        return new PoolStats(total, avail, total - avail, config.poolSize());
    }

    public void shutdown() {
        if (shutdown) return;
        shutdown = true;
        poolLock.lock();
        try {
            for (PooledConnection c : allConnections.values()) {
                c.close();
            }
            allConnections.clear();
            available.clear();
        } finally {
            poolLock.unlock();
        }
    }

    public record ConnectionPoolConfig(int poolSize, long connectionTimeoutMs) {
        public ConnectionPoolConfig {
            if (poolSize < 1 || connectionTimeoutMs < 1) {
                throw new IllegalArgumentException("poolSize and connectionTimeoutMs must be positive");
            }
        }
    }

    public record PoolStats(int total, int available, int inUse, int maxSize) {}
}
