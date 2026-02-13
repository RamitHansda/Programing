package lld.database_connection_pool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test class for Connection Pool Manager
 * Demonstrates various test scenarios and edge cases
 */
public class ConnectionPoolTest {
    
    public static void main(String[] args) {
        System.out.println("Running Connection Pool Tests...\n");
        
        int passed = 0;
        int failed = 0;
        
        // Test 1: Pool initialization
        if (testPoolInitialization()) {
            System.out.println("✓ Test 1 PASSED: Pool initialization");
            passed++;
        } else {
            System.out.println("✗ Test 1 FAILED: Pool initialization");
            failed++;
        }
        
        // Test 2: Connection acquisition and release
        if (testConnectionAcquisitionAndRelease()) {
            System.out.println("✓ Test 2 PASSED: Connection acquisition and release");
            passed++;
        } else {
            System.out.println("✗ Test 2 FAILED: Connection acquisition and release");
            failed++;
        }
        
        // Test 3: Max pool size enforcement
        if (testMaxPoolSizeEnforcement()) {
            System.out.println("✓ Test 3 PASSED: Max pool size enforcement");
            passed++;
        } else {
            System.out.println("✗ Test 3 FAILED: Max pool size enforcement");
            failed++;
        }
        
        // Test 4: Concurrent access
        if (testConcurrentAccess()) {
            System.out.println("✓ Test 4 PASSED: Concurrent access");
            passed++;
        } else {
            System.out.println("✗ Test 4 FAILED: Concurrent access");
            failed++;
        }
        
        // Test 5: Connection timeout
        if (testConnectionTimeout()) {
            System.out.println("✓ Test 5 PASSED: Connection timeout");
            passed++;
        } else {
            System.out.println("✗ Test 5 FAILED: Connection timeout");
            failed++;
        }
        
        // Test 6: Connection validation
        if (testConnectionValidation()) {
            System.out.println("✓ Test 6 PASSED: Connection validation");
            passed++;
        } else {
            System.out.println("✗ Test 6 FAILED: Connection validation");
            failed++;
        }
        
        // Test 7: Pool stats
        if (testPoolStats()) {
            System.out.println("✓ Test 7 PASSED: Pool statistics");
            passed++;
        } else {
            System.out.println("✗ Test 7 FAILED: Pool statistics");
            failed++;
        }
        
        // Test 8: Shutdown behavior
        if (testShutdownBehavior()) {
            System.out.println("✓ Test 8 PASSED: Shutdown behavior");
            passed++;
        } else {
            System.out.println("✗ Test 8 FAILED: Shutdown behavior");
            failed++;
        }
        
        System.out.println("\n" + "=".repeat(50));
        System.out.println("Test Summary: " + passed + " passed, " + failed + " failed");
        System.out.println("=".repeat(50));
    }
    
    private static boolean testPoolInitialization() {
        ConnectionPoolConfig config = new ConnectionPoolConfig.Builder()
                .minPoolSize(5)
                .maxPoolSize(10)
                .build();
        
        ConnectionPoolManager poolManager = new ConnectionPoolManager(config);
        
        try {
            ConnectionPoolManager.PoolStats stats = poolManager.getStats();
            boolean success = stats.getTotalConnections() == 5 &&
                            stats.getAvailableConnections() == 5 &&
                            stats.getInUseConnections() == 0;
            return success;
        } finally {
            poolManager.shutdown();
        }
    }
    
    private static boolean testConnectionAcquisitionAndRelease() {
        ConnectionPoolConfig config = new ConnectionPoolConfig.Builder()
                .minPoolSize(3)
                .maxPoolSize(5)
                .build();
        
        ConnectionPoolManager poolManager = new ConnectionPoolManager(config);
        
        try {
            // Acquire connection
            DatabaseConnection conn = poolManager.getConnection();
            ConnectionPoolManager.PoolStats stats = poolManager.getStats();
            
            boolean acquireSuccess = stats.getInUseConnections() == 1 &&
                                    stats.getAvailableConnections() == 2;
            
            // Release connection
            poolManager.releaseConnection(conn);
            stats = poolManager.getStats();
            
            boolean releaseSuccess = stats.getInUseConnections() == 0 &&
                                    stats.getAvailableConnections() == 3;
            
            return acquireSuccess && releaseSuccess;
            
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            poolManager.shutdown();
        }
    }
    
    private static boolean testMaxPoolSizeEnforcement() {
        ConnectionPoolConfig config = new ConnectionPoolConfig.Builder()
                .minPoolSize(2)
                .maxPoolSize(5)
                .build();
        
        ConnectionPoolManager poolManager = new ConnectionPoolManager(config);
        List<DatabaseConnection> connections = new ArrayList<>();
        
        try {
            // Acquire max connections
            for (int i = 0; i < 5; i++) {
                connections.add(poolManager.getConnection());
            }
            
            ConnectionPoolManager.PoolStats stats = poolManager.getStats();
            boolean success = stats.getTotalConnections() == 5 &&
                            stats.getInUseConnections() == 5 &&
                            stats.getAvailableConnections() == 0;
            
            return success;
            
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            // Release all connections
            for (DatabaseConnection conn : connections) {
                poolManager.releaseConnection(conn);
            }
            poolManager.shutdown();
        }
    }
    
    private static boolean testConcurrentAccess() {
        ConnectionPoolConfig config = new ConnectionPoolConfig.Builder()
                .minPoolSize(3)
                .maxPoolSize(10)
                .build();
        
        ConnectionPoolManager poolManager = new ConnectionPoolManager(config);
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(50);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        
        try {
            // Submit 50 tasks
            for (int i = 0; i < 50; i++) {
                executor.submit(() -> {
                    try {
                        DatabaseConnection conn = poolManager.getConnection();
                        Thread.sleep(10); // Simulate work
                        poolManager.releaseConnection(conn);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }
            
            // Wait for all tasks
            latch.await(30, TimeUnit.SECONDS);
            
            boolean success = successCount.get() == 50 && errorCount.get() == 0;
            return success;
            
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            executor.shutdown();
            poolManager.shutdown();
        }
    }
    
    private static boolean testConnectionTimeout() {
        ConnectionPoolConfig config = new ConnectionPoolConfig.Builder()
                .minPoolSize(2)
                .maxPoolSize(2)
                .connectionTimeout(1000) // 1 second timeout
                .build();
        
        ConnectionPoolManager poolManager = new ConnectionPoolManager(config);
        
        try {
            // Acquire all connections
            DatabaseConnection conn1 = poolManager.getConnection();
            DatabaseConnection conn2 = poolManager.getConnection();
            
            // Try to acquire one more - should timeout
            try {
                DatabaseConnection conn3 = poolManager.getConnection();
                // If we get here, test failed
                poolManager.releaseConnection(conn3);
                return false;
            } catch (TimeoutException e) {
                // Expected timeout - test passed
                poolManager.releaseConnection(conn1);
                poolManager.releaseConnection(conn2);
                return true;
            }
            
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            poolManager.shutdown();
        }
    }
    
    private static boolean testConnectionValidation() {
        ConnectionPoolConfig config = new ConnectionPoolConfig.Builder()
                .minPoolSize(2)
                .maxPoolSize(5)
                .build();
        
        ConnectionPoolManager poolManager = new ConnectionPoolManager(config);
        
        try {
            // Get a connection
            DatabaseConnection conn = poolManager.getConnection();
            
            // Connection should be valid and open
            boolean isValid = conn.isValid() && conn.isOpen();
            
            // Close the connection (invalidate it)
            conn.close();
            
            // Connection should now be invalid
            boolean isInvalid = !conn.isValid();
            
            // Release it - pool should handle invalid connection
            poolManager.releaseConnection(conn);
            
            // Pool should maintain minimum size
            ConnectionPoolManager.PoolStats stats = poolManager.getStats();
            boolean maintainsMinSize = stats.getTotalConnections() >= config.getMinPoolSize();
            
            return isValid && isInvalid && maintainsMinSize;
            
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            poolManager.shutdown();
        }
    }
    
    private static boolean testPoolStats() {
        ConnectionPoolConfig config = new ConnectionPoolConfig.Builder()
                .minPoolSize(3)
                .maxPoolSize(8)
                .build();
        
        ConnectionPoolManager poolManager = new ConnectionPoolManager(config);
        
        try {
            // Initial stats
            ConnectionPoolManager.PoolStats stats = poolManager.getStats();
            boolean initialCheck = stats.getTotalConnections() == 3 &&
                                  stats.getMinPoolSize() == 3 &&
                                  stats.getMaxPoolSize() == 8;
            
            // Acquire some connections
            DatabaseConnection conn1 = poolManager.getConnection();
            DatabaseConnection conn2 = poolManager.getConnection();
            
            stats = poolManager.getStats();
            boolean afterAcquireCheck = stats.getInUseConnections() == 2 &&
                                       stats.getAvailableConnections() == 1;
            
            poolManager.releaseConnection(conn1);
            poolManager.releaseConnection(conn2);
            
            stats = poolManager.getStats();
            boolean afterReleaseCheck = stats.getInUseConnections() == 0 &&
                                       stats.getAvailableConnections() == 3;
            
            return initialCheck && afterAcquireCheck && afterReleaseCheck;
            
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            poolManager.shutdown();
        }
    }
    
    private static boolean testShutdownBehavior() {
        ConnectionPoolConfig config = new ConnectionPoolConfig.Builder()
                .minPoolSize(3)
                .maxPoolSize(5)
                .build();
        
        ConnectionPoolManager poolManager = new ConnectionPoolManager(config);
        
        try {
            // Acquire a connection
            DatabaseConnection conn = poolManager.getConnection();
            poolManager.releaseConnection(conn);
            
            // Shutdown the pool
            poolManager.shutdown();
            
            // Try to get a connection after shutdown
            try {
                poolManager.getConnection();
                // If we get here, test failed
                return false;
            } catch (IllegalStateException e) {
                // Expected exception - pool is shutdown
                return true;
            }
            
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}
