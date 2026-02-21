package lld.database_connection_pool;

import java.util.concurrent.TimeUnit;

/**
 * Demo showing automatic recovery from database downtime
 * Simulates DB going down and coming back up after 5 minutes
 */
public class DatabaseFailoverDemo {
    
    // Simulate database availability
    private static volatile boolean isDatabaseUp = true;
    
    public static void main(String[] args) {
        System.out.println("=== Database Failover and Recovery Demo ===\n");
        
        // Override DatabaseConnection to simulate DB downtime
        simulateDatabaseDowntime();
        
        System.out.println("Press Ctrl+C to exit\n");
    }
    
    private static void simulateDatabaseDowntime() {
        ConnectionPoolConfig config = new ConnectionPoolConfig.Builder()
                .databaseUrl("jdbc:mysql://localhost:3306/testdb")
                .minPoolSize(3)
                .maxPoolSize(10)
                .connectionTimeout(5000)
                .build();
        
        ResilientConnectionPoolManager poolManager = new ResilientConnectionPoolManager(config);
        
        // Phase 1: Normal operation
        System.out.println("=== PHASE 1: Normal Operation ===");
        demonstrateNormalOperation(poolManager);
        
        // Phase 2: Simulate DB going down
        System.out.println("\n=== PHASE 2: Database Going Down ===");
        System.out.println("Simulating database failure...\n");
        isDatabaseUp = false;
        
        // Try to use connections during downtime
        demonstrateDuringDowntime(poolManager);
        
        // Phase 3: DB comes back up after 30 seconds (simulating 5 mins)
        System.out.println("\n=== PHASE 3: Waiting for Database Recovery ===");
        System.out.println("Waiting 35 seconds (simulates 5 mins)...");
        sleep(35000);
        
        System.out.println("\n=== PHASE 4: Database Coming Back Online ===");
        isDatabaseUp = true;
        System.out.println("Database is now back online!\n");
        
        // The pool should automatically recover
        demonstrateAfterRecovery(poolManager);
        
        System.out.println("\n=== PHASE 5: Verify Full Recovery ===");
        verifyFullRecovery(poolManager);
        
        poolManager.shutdown();
        System.out.println("\n=== Demo Complete ===");
    }
    
    private static void demonstrateNormalOperation(ResilientConnectionPoolManager pool) {
        try {
            System.out.println("Initial pool stats: " + pool.getStats());
            
            // Get and use a connection
            DatabaseConnection conn = pool.getConnection();
            System.out.println("Successfully acquired connection: " + conn.getConnectionId());
            conn.executeQuery("SELECT * FROM users");
            
            System.out.println("Pool stats during use: " + pool.getStats());
            
            pool.releaseConnection(conn);
            System.out.println("Connection released successfully");
            System.out.println("Pool stats after release: " + pool.getStats());
            
        } catch (Exception e) {
            System.err.println("Error during normal operation: " + e.getMessage());
        }
    }
    
    private static void demonstrateDuringDowntime(ResilientConnectionPoolManager pool) {
        // Try multiple times to show circuit breaker behavior
        for (int i = 1; i <= 3; i++) {
            try {
                System.out.println("\nAttempt " + i + " to get connection...");
                DatabaseConnection conn = pool.getConnection();
                System.out.println("Got connection (unexpected): " + conn.getConnectionId());
                pool.releaseConnection(conn);
            } catch (Exception e) {
                System.err.println("Expected failure: " + e.getMessage());
                System.out.println("Pool stats: " + pool.getStats());
            }
            sleep(2000); // Wait 2 seconds between attempts
        }
        
        System.out.println("\nCircuit breaker should now be OPEN");
        System.out.println("Pool stats: " + pool.getStats());
    }
    
    private static void demonstrateAfterRecovery(ResilientConnectionPoolManager pool) {
        // Wait a bit for recovery monitoring to kick in
        sleep(5000);
        
        try {
            System.out.println("Attempting to get connection after DB recovery...");
            DatabaseConnection conn = pool.getConnection();
            System.out.println("SUCCESS! Got connection: " + conn.getConnectionId());
            System.out.println("Pool stats: " + pool.getStats());
            
            conn.executeQuery("SELECT * FROM users");
            System.out.println("Query executed successfully!");
            
            pool.releaseConnection(conn);
            System.out.println("Connection released successfully");
            
        } catch (Exception e) {
            System.err.println("Error after recovery: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void verifyFullRecovery(ResilientConnectionPoolManager pool) {
        // Wait for pool to fully recover to min size
        sleep(5000);
        
        ResilientConnectionPoolManager.PoolStats stats = pool.getStats();
        System.out.println("Final pool stats: " + stats);
        
        if (stats.isHealthy()) {
            System.out.println("✓ Pool is HEALTHY");
            System.out.println("✓ Circuit breaker is CLOSED");
            System.out.println("✓ Pool has " + stats.getTotalConnections() + " connections");
            System.out.println("✓ NO APPLICATION RESTART NEEDED!");
        } else {
            System.out.println("✗ Pool is not fully recovered yet");
        }
        
        // Test multiple concurrent requests
        System.out.println("\nTesting concurrent requests after recovery...");
        for (int i = 0; i < 5; i++) {
            final int requestId = i;
            new Thread(() -> {
                try {
                    DatabaseConnection conn = pool.getConnection();
                    System.out.println("Request " + requestId + " got connection: " + 
                                     conn.getConnectionId());
                    conn.executeQuery("SELECT * FROM data");
                    Thread.sleep(100);
                    pool.releaseConnection(conn);
                    System.out.println("Request " + requestId + " completed");
                } catch (Exception e) {
                    System.err.println("Request " + requestId + " failed: " + e.getMessage());
                }
            }).start();
        }
        
        sleep(2000); // Wait for threads to complete
        System.out.println("\nAll concurrent requests completed successfully!");
    }
    
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
