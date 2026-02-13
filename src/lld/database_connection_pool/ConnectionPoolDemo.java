package lld.database_connection_pool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Demo class to showcase the connection pool manager
 */
public class ConnectionPoolDemo {
    
    public static void main(String[] args) {
        // Demo 1: Basic usage
        System.out.println("=== Demo 1: Basic Connection Pool Usage ===\n");
        basicUsageDemo();
        
        System.out.println("\n\n=== Demo 2: Concurrent Access ===\n");
        concurrentAccessDemo();
        
        System.out.println("\n\n=== Demo 3: Pool Size Management ===\n");
        poolSizeManagementDemo();
        
        System.out.println("\n\n=== Demo 4: Connection Timeout ===\n");
        connectionTimeoutDemo();
    }
    
    private static void basicUsageDemo() {
        // Create configuration
        ConnectionPoolConfig config = new ConnectionPoolConfig.Builder()
                .databaseUrl("jdbc:mysql://localhost:3306/mydb")
                .minPoolSize(3)
                .maxPoolSize(10)
                .connectionTimeout(5000)
                .idleTimeout(300000)
                .build();
        
        // Create pool manager
        ConnectionPoolManager poolManager = new ConnectionPoolManager(config);
        
        try {
            // Get a connection
            DatabaseConnection conn = poolManager.getConnection();
            
            // Use the connection
            conn.executeQuery("SELECT * FROM users");
            conn.executeQuery("SELECT * FROM orders WHERE user_id = 1");
            
            // Check stats
            System.out.println("\nPool stats: " + poolManager.getStats());
            
            // Release connection
            poolManager.releaseConnection(conn);
            
            System.out.println("Pool stats after release: " + poolManager.getStats());
            
        } catch (InterruptedException | TimeoutException e) {
            e.printStackTrace();
        } finally {
            poolManager.shutdown();
        }
    }
    
    private static void concurrentAccessDemo() {
        ConnectionPoolConfig config = new ConnectionPoolConfig.Builder()
                .minPoolSize(3)
                .maxPoolSize(8)
                .connectionTimeout(10000)
                .build();
        
        ConnectionPoolManager poolManager = new ConnectionPoolManager(config);
        
        // Create multiple threads that access the pool
        List<Thread> threads = new ArrayList<>();
        
        for (int i = 0; i < 10; i++) {
            final int taskId = i;
            Thread thread = new Thread(() -> {
                try {
                    DatabaseConnection conn = poolManager.getConnection();
                    System.out.println("Task " + taskId + " got connection");
                    
                    // Simulate work
                    conn.executeQuery("SELECT * FROM table_" + taskId);
                    Thread.sleep(500);
                    
                    poolManager.releaseConnection(conn);
                    System.out.println("Task " + taskId + " released connection");
                    
                } catch (Exception e) {
                    System.err.println("Task " + taskId + " error: " + e.getMessage());
                }
            }, "Worker-" + i);
            
            threads.add(thread);
            thread.start();
        }
        
        // Wait for all threads
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        System.out.println("\nFinal pool stats: " + poolManager.getStats());
        poolManager.shutdown();
    }
    
    private static void poolSizeManagementDemo() {
        ConnectionPoolConfig config = new ConnectionPoolConfig.Builder()
                .minPoolSize(2)
                .maxPoolSize(5)
                .connectionTimeout(5000)
                .build();
        
        ConnectionPoolManager poolManager = new ConnectionPoolManager(config);
        List<DatabaseConnection> connections = new ArrayList<>();
        
        try {
            System.out.println("Initial stats: " + poolManager.getStats());
            
            // Acquire connections up to max
            for (int i = 0; i < 5; i++) {
                DatabaseConnection conn = poolManager.getConnection();
                connections.add(conn);
                System.out.println("Acquired connection " + (i + 1) + ": " + poolManager.getStats());
                Thread.sleep(100);
            }
            
            System.out.println("\nAll connections acquired: " + poolManager.getStats());
            
            // Release all connections
            for (DatabaseConnection conn : connections) {
                poolManager.releaseConnection(conn);
                Thread.sleep(100);
            }
            
            System.out.println("All connections released: " + poolManager.getStats());
            
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            poolManager.shutdown();
        }
    }
    
    private static void connectionTimeoutDemo() {
        ConnectionPoolConfig config = new ConnectionPoolConfig.Builder()
                .minPoolSize(2)
                .maxPoolSize(2)  // Small max to trigger timeout
                .connectionTimeout(2000)  // 2 seconds timeout
                .build();
        
        ConnectionPoolManager poolManager = new ConnectionPoolManager(config);
        
        try {
            // Acquire all available connections
            DatabaseConnection conn1 = poolManager.getConnection();
            DatabaseConnection conn2 = poolManager.getConnection();
            
            System.out.println("Acquired 2 connections (pool is now full)");
            System.out.println("Pool stats: " + poolManager.getStats());
            
            // Try to acquire one more - should timeout
            System.out.println("\nTrying to acquire 3rd connection (should timeout)...");
            try {
                DatabaseConnection conn3 = poolManager.getConnection();
                System.out.println("Unexpectedly got connection: " + conn3);
            } catch (TimeoutException e) {
                System.out.println("Timeout occurred as expected: " + e.getMessage());
            }
            
            // Release one and try again
            poolManager.releaseConnection(conn1);
            System.out.println("\nReleased one connection. Trying again...");
            DatabaseConnection conn3 = poolManager.getConnection();
            System.out.println("Successfully acquired connection after release!");
            
            poolManager.releaseConnection(conn2);
            poolManager.releaseConnection(conn3);
            
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            poolManager.shutdown();
        }
    }
}
