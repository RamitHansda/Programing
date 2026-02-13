package lld.database_connection_pool;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents a database connection wrapper with metadata
 */
public class DatabaseConnection {
    private static final AtomicLong idGenerator = new AtomicLong(0);
    
    private final long connectionId;
    private final String url;
    private boolean isOpen;
    private long createdAt;
    private long lastUsedAt;
    private volatile boolean inUse;
    
    public DatabaseConnection(String url) {
        this.connectionId = idGenerator.incrementAndGet();
        this.url = url;
        this.isOpen = false;
        this.inUse = false;
    }
    
    /**
     * Simulates opening a database connection
     */
    public void open() {
        if (!isOpen) {
            // Simulate connection opening delay
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            this.isOpen = true;
            this.createdAt = System.currentTimeMillis();
            this.lastUsedAt = System.currentTimeMillis();
            System.out.println("Connection [" + connectionId + "] opened to " + url);
        }
    }
    
    /**
     * Simulates closing a database connection
     */
    public void close() {
        if (isOpen) {
            this.isOpen = false;
            System.out.println("Connection [" + connectionId + "] closed");
        }
    }
    
    /**
     * Validates if the connection is still usable
     */
    public boolean isValid() {
        return isOpen && !isStale();
    }
    
    /**
     * Checks if connection has been idle for too long (stale)
     */
    private boolean isStale() {
        long idleTime = System.currentTimeMillis() - lastUsedAt;
        return idleTime > 300000; // 5 minutes
    }
    
    /**
     * Executes a query (simulated)
     */
    public void executeQuery(String query) {
        if (!isOpen) {
            throw new IllegalStateException("Connection is not open");
        }
        if (!inUse) {
            throw new IllegalStateException("Connection must be acquired before use");
        }
        
        this.lastUsedAt = System.currentTimeMillis();
        System.out.println("Connection [" + connectionId + "] executing: " + query);
        
        // Simulate query execution
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    public long getConnectionId() {
        return connectionId;
    }
    
    public String getUrl() {
        return url;
    }
    
    public boolean isOpen() {
        return isOpen;
    }
    
    public boolean isInUse() {
        return inUse;
    }
    
    public void setInUse(boolean inUse) {
        this.inUse = inUse;
    }
    
    public long getLastUsedAt() {
        return lastUsedAt;
    }
    
    public long getIdleTime() {
        return System.currentTimeMillis() - lastUsedAt;
    }
    
    @Override
    public String toString() {
        return "Connection[id=" + connectionId + 
               ", inUse=" + inUse + 
               ", isOpen=" + isOpen + 
               ", idleTime=" + getIdleTime() + "ms]";
    }
}
