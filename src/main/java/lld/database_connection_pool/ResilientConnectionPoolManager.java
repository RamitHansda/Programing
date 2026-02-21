package lld.database_connection_pool;

import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Enhanced connection pool manager with database failover and recovery support
 * 
 * Features:
 * - Automatic retry on connection failures
 * - Circuit breaker pattern for DB downtime
 * - Graceful recovery when DB comes back
 * - No application restart needed
 */
public class ResilientConnectionPoolManager {
    private final ConnectionPoolConfig config;
    private final BlockingQueue<DatabaseConnection> availableConnections;
    private final ConcurrentHashMap<Long, DatabaseConnection> allConnections;
    private final ReentrantLock poolLock;
    private final ScheduledExecutorService cleanupExecutor;
    private final ScheduledExecutorService recoveryExecutor;
    private volatile boolean isShutdown;
    
    // Circuit breaker state
    private final AtomicBoolean isCircuitOpen;
    private volatile long lastFailureTime;
    private volatile int consecutiveFailures;
    private static final int FAILURE_THRESHOLD = 3;
    private static final long CIRCUIT_OPEN_DURATION = 30000; // 30 seconds
    private static final long RECOVERY_CHECK_INTERVAL = 10000; // 10 seconds
    
    public ResilientConnectionPoolManager(ConnectionPoolConfig config) {
        this.config = config;
        this.availableConnections = new LinkedBlockingQueue<>();
        this.allConnections = new ConcurrentHashMap<>();
        this.poolLock = new ReentrantLock();
        this.isShutdown = false;
        this.isCircuitOpen = new AtomicBoolean(false);
        this.consecutiveFailures = 0;
        
        // Initialize pool (with retry logic)
        initializePoolWithRetry();
        
        // Start cleanup task
        this.cleanupExecutor = Executors.newScheduledThreadPool(1);
        startIdleConnectionCleanup();
        
        // Start recovery monitoring
        this.recoveryExecutor = Executors.newScheduledThreadPool(1);
        startRecoveryMonitoring();
        
        System.out.println("Resilient connection pool initialized with config: " + config);
    }
    
    /**
     * Initialize pool with retry logic
     */
    private void initializePoolWithRetry() {
        poolLock.lock();
        try {
            int successfulConnections = 0;
            int maxRetries = 3;
            
            for (int i = 0; i < config.getMinPoolSize(); i++) {
                DatabaseConnection conn = createNewConnectionWithRetry(maxRetries);
                if (conn != null) {
                    availableConnections.offer(conn);
                    successfulConnections++;
                }
            }
            
            if (successfulConnections == 0) {
                System.err.println("WARNING: Could not create any initial connections. " +
                                 "Pool will attempt recovery when DB becomes available.");
                isCircuitOpen.set(true);
                lastFailureTime = System.currentTimeMillis();
            } else {
                System.out.println("Initialized pool with " + successfulConnections + 
                                 "/" + config.getMinPoolSize() + " connections");
            }
        } finally {
            poolLock.unlock();
        }
    }
    
    /**
     * Creates a new connection with retry logic
     */
    private DatabaseConnection createNewConnectionWithRetry(int maxRetries) {
        if (allConnections.size() >= config.getMaxPoolSize()) {
            return null;
        }
        
        DatabaseConnection conn = null;
        int attempts = 0;
        
        while (conn == null && attempts < maxRetries) {
            try {
                conn = new DatabaseConnection(config.getDatabaseUrl());
                conn.open();
                allConnections.put(conn.getConnectionId(), conn);
                
                // Success - reset failure counter
                if (consecutiveFailures > 0) {
                    System.out.println("Connection creation successful. Resetting failure counter.");
                    consecutiveFailures = 0;
                    isCircuitOpen.set(false);
                }
                
                return conn;
                
            } catch (Exception e) {
                attempts++;
                consecutiveFailures++;
                lastFailureTime = System.currentTimeMillis();
                
                System.err.println("Connection creation failed (attempt " + attempts + 
                                 "/" + maxRetries + "): " + e.getMessage());
                
                if (consecutiveFailures >= FAILURE_THRESHOLD) {
                    System.err.println("Opening circuit breaker - DB appears to be down");
                    isCircuitOpen.set(true);
                }
                
                if (attempts < maxRetries) {
                    try {
                        // Exponential backoff: 100ms, 200ms, 400ms...
                        long backoff = 100L * (1L << attempts);
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        
        return null;
    }
    
    /**
     * Get a connection with automatic retry and recovery
     */
    public DatabaseConnection getConnection() throws InterruptedException, TimeoutException {
        if (isShutdown) {
            throw new IllegalStateException("Connection pool is shutdown");
        }
        
        // Check circuit breaker
        if (isCircuitOpen.get()) {
            long timeSinceFailure = System.currentTimeMillis() - lastFailureTime;
            if (timeSinceFailure < CIRCUIT_OPEN_DURATION) {
                throw new IllegalStateException("Circuit breaker is OPEN - DB appears to be down. " +
                    "Will retry in " + (CIRCUIT_OPEN_DURATION - timeSinceFailure) + "ms");
            } else {
                // Try to recover
                System.out.println("Circuit breaker timeout elapsed. Attempting recovery...");
                attemptRecovery();
            }
        }
        
        long startTime = System.currentTimeMillis();
        DatabaseConnection conn = null;
        
        while (conn == null) {
            // Check timeout
            if (System.currentTimeMillis() - startTime > config.getConnectionTimeout()) {
                throw new TimeoutException("Could not acquire connection within timeout");
            }
            
            // Try to get available connection
            conn = availableConnections.poll(100, TimeUnit.MILLISECONDS);
            
            if (conn != null) {
                // Validate connection
                if (!conn.isValid()) {
                    System.out.println("Connection [" + conn.getConnectionId() + 
                                     "] is invalid, attempting to replace...");
                    removeConnection(conn);
                    conn = null;
                    
                    // Try to create replacement
                    DatabaseConnection newConn = createNewConnectionWithRetry(2);
                    if (newConn != null) {
                        conn = newConn;
                    }
                    continue;
                }
            } else {
                // No available connection, try to create new one
                poolLock.lock();
                try {
                    if (allConnections.size() < config.getMaxPoolSize()) {
                        conn = createNewConnectionWithRetry(2);
                    }
                } finally {
                    poolLock.unlock();
                }
                
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
     * Release connection back to pool
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
            System.out.println("Connection [" + conn.getConnectionId() + "] released");
        } else {
            System.out.println("Connection [" + conn.getConnectionId() + 
                             "] is invalid, removing and attempting to create replacement");
            removeConnection(conn);
            
            // Attempt to create replacement asynchronously
            recoveryExecutor.submit(() -> {
                DatabaseConnection replacement = createNewConnectionWithRetry(2);
                if (replacement != null) {
                    availableConnections.offer(replacement);
                }
            });
        }
    }
    
    /**
     * Remove connection and attempt to maintain pool size
     */
    private void removeConnection(DatabaseConnection conn) {
        poolLock.lock();
        try {
            conn.close();
            allConnections.remove(conn.getConnectionId());
            
            // Ensure minimum pool size
            if (allConnections.size() < config.getMinPoolSize() && !isCircuitOpen.get()) {
                DatabaseConnection newConn = createNewConnectionWithRetry(2);
                if (newConn != null) {
                    availableConnections.offer(newConn);
                }
            }
        } finally {
            poolLock.unlock();
        }
    }
    
    /**
     * Attempt to recover from DB downtime
     */
    private void attemptRecovery() {
        poolLock.lock();
        try {
            System.out.println("Attempting to recover connection pool...");
            
            // Try to create a test connection
            DatabaseConnection testConn = createNewConnectionWithRetry(1);
            
            if (testConn != null) {
                System.out.println("Recovery successful! Database is back online.");
                availableConnections.offer(testConn);
                
                // Create additional connections to reach min pool size
                int needed = config.getMinPoolSize() - allConnections.size();
                for (int i = 0; i < needed; i++) {
                    DatabaseConnection conn = createNewConnectionWithRetry(1);
                    if (conn != null) {
                        availableConnections.offer(conn);
                    }
                }
                
                isCircuitOpen.set(false);
                consecutiveFailures = 0;
                
            } else {
                System.err.println("Recovery failed. Database still appears to be down.");
                lastFailureTime = System.currentTimeMillis();
            }
        } finally {
            poolLock.unlock();
        }
    }
    
    /**
     * Start periodic recovery monitoring
     */
    private void startRecoveryMonitoring() {
        recoveryExecutor.scheduleAtFixedRate(() -> {
            try {
                if (isCircuitOpen.get()) {
                    long timeSinceFailure = System.currentTimeMillis() - lastFailureTime;
                    if (timeSinceFailure >= CIRCUIT_OPEN_DURATION) {
                        attemptRecovery();
                    }
                } else if (allConnections.size() < config.getMinPoolSize()) {
                    // Pool is below minimum, try to add connections
                    System.out.println("Pool below minimum size. Attempting to add connections...");
                    attemptRecovery();
                }
            } catch (Exception e) {
                System.err.println("Error during recovery monitoring: " + e.getMessage());
            }
        }, RECOVERY_CHECK_INTERVAL, RECOVERY_CHECK_INTERVAL, TimeUnit.MILLISECONDS);
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
        }, 60, 60, TimeUnit.SECONDS);
    }
    
    /**
     * Cleanup idle connections
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
            
            for (DatabaseConnection conn : allConnections.values()) {
                if (!conn.isInUse() && 
                    conn.getIdleTime() > config.getIdleTimeout() &&
                    currentSize - removed > config.getMinPoolSize()) {
                    
                    availableConnections.remove(conn);
                    removeConnection(conn);
                    removed++;
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
     * Get pool statistics
     */
    public PoolStats getStats() {
        return new PoolStats(
            allConnections.size(),
            availableConnections.size(),
            allConnections.size() - availableConnections.size(),
            config.getMinPoolSize(),
            config.getMaxPoolSize(),
            isCircuitOpen.get(),
            consecutiveFailures
        );
    }
    
    /**
     * Shutdown pool
     */
    public void shutdown() {
        if (isShutdown) {
            return;
        }
        
        isShutdown = true;
        System.out.println("Shutting down resilient connection pool...");
        
        // Stop executors
        cleanupExecutor.shutdown();
        recoveryExecutor.shutdown();
        
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
            if (!recoveryExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                recoveryExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            recoveryExecutor.shutdownNow();
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
            System.out.println("Resilient connection pool shutdown complete.");
        } finally {
            poolLock.unlock();
        }
    }
    
    /**
     * Enhanced pool statistics with circuit breaker info
     */
    public static class PoolStats {
        private final int totalConnections;
        private final int availableConnections;
        private final int inUseConnections;
        private final int minPoolSize;
        private final int maxPoolSize;
        private final boolean circuitOpen;
        private final int consecutiveFailures;
        
        public PoolStats(int total, int available, int inUse, int min, int max, 
                        boolean circuitOpen, int failures) {
            this.totalConnections = total;
            this.availableConnections = available;
            this.inUseConnections = inUse;
            this.minPoolSize = min;
            this.maxPoolSize = max;
            this.circuitOpen = circuitOpen;
            this.consecutiveFailures = failures;
        }
        
        @Override
        public String toString() {
            return String.format(
                "PoolStats{total=%d, available=%d, inUse=%d, min=%d, max=%d, " +
                "circuitOpen=%s, failures=%d}",
                totalConnections, availableConnections, inUseConnections, 
                minPoolSize, maxPoolSize, circuitOpen, consecutiveFailures);
        }
        
        public boolean isHealthy() {
            return !circuitOpen && totalConnections >= minPoolSize;
        }
        
        // Getters
        public int getTotalConnections() { return totalConnections; }
        public int getAvailableConnections() { return availableConnections; }
        public int getInUseConnections() { return inUseConnections; }
        public boolean isCircuitOpen() { return circuitOpen; }
        public int getConsecutiveFailures() { return consecutiveFailures; }
    }
}
