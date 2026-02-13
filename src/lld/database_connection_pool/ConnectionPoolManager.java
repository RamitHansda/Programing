package lld.database_connection_pool;

import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe database connection pool manager
 * Features:
 * - Min/Max pool size management
 * - Connection validation and recycling
 * - Automatic idle connection cleanup
 * - Connection timeout handling
 * - Thread-safe operations
 */
public class ConnectionPoolManager {
    private final ConnectionPoolConfig config;
    private final BlockingQueue<DatabaseConnection> availableConnections;
    private final ConcurrentHashMap<Long, DatabaseConnection> allConnections;
    private final ReentrantLock poolLock;
    private final ScheduledExecutorService cleanupExecutor;
    private volatile boolean isShutdown;
    
    public ConnectionPoolManager(ConnectionPoolConfig config) {
        this.config = config;
        this.availableConnections = new LinkedBlockingQueue<>();
        this.allConnections = new ConcurrentHashMap<>();
        this.poolLock = new ReentrantLock();
        this.isShutdown = false;
        
        // Initialize minimum connections
        initializePool();
        
        // Start cleanup task for idle connections
        this.cleanupExecutor = Executors.newScheduledThreadPool(1);
        startIdleConnectionCleanup();
        
        System.out.println("Connection pool initialized with config: " + config);
    }
    
    /**
     * Initialize the pool with minimum connections
     */
    private void initializePool() {
        poolLock.lock();
        try {
            for (int i = 0; i < config.getMinPoolSize(); i++) {
                DatabaseConnection conn = createNewConnection();
                if (conn != null) {
                    availableConnections.offer(conn);
                }
            }
            System.out.println("Initialized pool with " + availableConnections.size() + " connections");
        } finally {
            poolLock.unlock();
        }
    }
    
    /**
     * Creates a new database connection
     */
    private DatabaseConnection createNewConnection() {
        if (allConnections.size() >= config.getMaxPoolSize()) {
            System.out.println("Max pool size reached. Cannot create more connections.");
            return null;
        }
        
        DatabaseConnection conn = new DatabaseConnection(config.getDatabaseUrl());
        conn.open();
        allConnections.put(conn.getConnectionId(), conn);
        return conn;
    }
    
    /**
     * Get a connection from the pool
     */
    public DatabaseConnection getConnection() throws InterruptedException, TimeoutException {
        if (isShutdown) {
            throw new IllegalStateException("Connection pool is shutdown");
        }
        
        long startTime = System.currentTimeMillis();
        DatabaseConnection conn = null;
        
        // Try to get an available connection
        while (conn == null) {
            // Check timeout
            if (System.currentTimeMillis() - startTime > config.getConnectionTimeout()) {
                throw new TimeoutException("Could not acquire connection within timeout");
            }
            
            // Try to poll an available connection
            conn = availableConnections.poll(100, TimeUnit.MILLISECONDS);
            
            if (conn != null) {
                // Validate the connection
                if (!conn.isValid()) {
                    System.out.println("Connection [" + conn.getConnectionId() + "] is invalid, removing...");
                    removeConnection(conn);
                    conn = null;
                    continue;
                }
            } else {
                // No available connection, try to create a new one
                poolLock.lock();
                try {
                    if (allConnections.size() < config.getMaxPoolSize()) {
                        conn = createNewConnection();
                    }
                } finally {
                    poolLock.unlock();
                }
                
                // If still no connection, wait a bit and retry
                if (conn == null) {
                    Thread.sleep(50);
                }
            }
        }
        
        conn.setInUse(true);
        System.out.println("Connection [" + conn.getConnectionId() + "] acquired by thread " + 
                          Thread.currentThread().getName());
        return conn;
    }
    
    /**
     * Release a connection back to the pool
     */
    public void releaseConnection(DatabaseConnection conn) {
        if (conn == null) {
            throw new IllegalArgumentException("Cannot release null connection");
        }
        
        if (!allConnections.containsKey(conn.getConnectionId())) {
            throw new IllegalArgumentException("Connection does not belong to this pool");
        }
        
        conn.setInUse(false);
        
        // Validate before returning to pool
        if (conn.isValid()) {
            availableConnections.offer(conn);
            System.out.println("Connection [" + conn.getConnectionId() + "] released by thread " + 
                              Thread.currentThread().getName());
        } else {
            System.out.println("Connection [" + conn.getConnectionId() + "] is invalid, removing...");
            removeConnection(conn);
        }
    }
    
    /**
     * Remove a connection from the pool
     */
    private void removeConnection(DatabaseConnection conn) {
        poolLock.lock();
        try {
            conn.close();
            allConnections.remove(conn.getConnectionId());
            
            // Ensure we maintain minimum pool size
            if (allConnections.size() < config.getMinPoolSize()) {
                DatabaseConnection newConn = createNewConnection();
                if (newConn != null) {
                    availableConnections.offer(newConn);
                }
            }
        } finally {
            poolLock.unlock();
        }
    }
    
    /**
     * Start periodic cleanup of idle connections
     */
    private void startIdleConnectionCleanup() {
        cleanupExecutor.scheduleAtFixedRate(() -> {
            try {
                cleanupIdleConnections();
            } catch (Exception e) {
                System.err.println("Error during idle connection cleanup: " + e.getMessage());
            }
        }, 60, 60, TimeUnit.SECONDS); // Run every 60 seconds
    }
    
    /**
     * Cleanup idle connections beyond minimum pool size
     */
    private void cleanupIdleConnections() {
        poolLock.lock();
        try {
            int currentSize = allConnections.size();
            if (currentSize <= config.getMinPoolSize()) {
                return;
            }
            
            System.out.println("Running idle connection cleanup...");
            int removed = 0;
            
            // Check available connections for idle ones
            for (DatabaseConnection conn : allConnections.values()) {
                if (!conn.isInUse() && 
                    conn.getIdleTime() > config.getIdleTimeout() &&
                    currentSize - removed > config.getMinPoolSize()) {
                    
                    availableConnections.remove(conn);
                    removeConnection(conn);
                    removed++;
                    System.out.println("Removed idle connection [" + conn.getConnectionId() + "]");
                }
            }
            
            if (removed > 0) {
                System.out.println("Cleanup complete. Removed " + removed + " idle connections.");
            }
        } finally {
            poolLock.unlock();
        }
    }
    
    /**
     * Get current pool statistics
     */
    public PoolStats getStats() {
        return new PoolStats(
            allConnections.size(),
            availableConnections.size(),
            allConnections.size() - availableConnections.size(),
            config.getMinPoolSize(),
            config.getMaxPoolSize()
        );
    }
    
    /**
     * Shutdown the connection pool
     */
    public void shutdown() {
        if (isShutdown) {
            return;
        }
        
        isShutdown = true;
        System.out.println("Shutting down connection pool...");
        
        // Stop cleanup task
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Close all connections
        poolLock.lock();
        try {
            for (DatabaseConnection conn : allConnections.values()) {
                conn.close();
            }
            allConnections.clear();
            availableConnections.clear();
            System.out.println("Connection pool shutdown complete.");
        } finally {
            poolLock.unlock();
        }
    }
    
    /**
     * Pool statistics holder
     */
    public static class PoolStats {
        private final int totalConnections;
        private final int availableConnections;
        private final int inUseConnections;
        private final int minPoolSize;
        private final int maxPoolSize;
        
        public PoolStats(int total, int available, int inUse, int min, int max) {
            this.totalConnections = total;
            this.availableConnections = available;
            this.inUseConnections = inUse;
            this.minPoolSize = min;
            this.maxPoolSize = max;
        }
        
        @Override
        public String toString() {
            return String.format("PoolStats{total=%d, available=%d, inUse=%d, min=%d, max=%d}",
                totalConnections, availableConnections, inUseConnections, minPoolSize, maxPoolSize);
        }
        
        public int getTotalConnections() { return totalConnections; }
        public int getAvailableConnections() { return availableConnections; }
        public int getInUseConnections() { return inUseConnections; }
        public int getMinPoolSize() { return minPoolSize; }
        public int getMaxPoolSize() { return maxPoolSize; }
    }
}
