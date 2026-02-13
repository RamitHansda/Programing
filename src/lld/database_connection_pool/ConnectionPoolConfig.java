package lld.database_connection_pool;

/**
 * Configuration class for the connection pool
 */
public class ConnectionPoolConfig {
    private final String databaseUrl;
    private final int minPoolSize;
    private final int maxPoolSize;
    private final long connectionTimeout; // milliseconds
    private final long idleTimeout;       // milliseconds
    
    private ConnectionPoolConfig(Builder builder) {
        this.databaseUrl = builder.databaseUrl;
        this.minPoolSize = builder.minPoolSize;
        this.maxPoolSize = builder.maxPoolSize;
        this.connectionTimeout = builder.connectionTimeout;
        this.idleTimeout = builder.idleTimeout;
    }
    
    public String getDatabaseUrl() {
        return databaseUrl;
    }
    
    public int getMinPoolSize() {
        return minPoolSize;
    }
    
    public int getMaxPoolSize() {
        return maxPoolSize;
    }
    
    public long getConnectionTimeout() {
        return connectionTimeout;
    }
    
    public long getIdleTimeout() {
        return idleTimeout;
    }
    
    public static class Builder {
        private String databaseUrl = "jdbc:mysql://localhost:3306/testdb";
        private int minPoolSize = 5;
        private int maxPoolSize = 20;
        private long connectionTimeout = 30000; // 30 seconds
        private long idleTimeout = 600000;      // 10 minutes
        
        public Builder databaseUrl(String url) {
            this.databaseUrl = url;
            return this;
        }
        
        public Builder minPoolSize(int size) {
            if (size < 1) {
                throw new IllegalArgumentException("Min pool size must be at least 1");
            }
            this.minPoolSize = size;
            return this;
        }
        
        public Builder maxPoolSize(int size) {
            if (size < minPoolSize) {
                throw new IllegalArgumentException("Max pool size must be >= min pool size");
            }
            this.maxPoolSize = size;
            return this;
        }
        
        public Builder connectionTimeout(long timeout) {
            if (timeout <= 0) {
                throw new IllegalArgumentException("Connection timeout must be positive");
            }
            this.connectionTimeout = timeout;
            return this;
        }
        
        public Builder idleTimeout(long timeout) {
            if (timeout <= 0) {
                throw new IllegalArgumentException("Idle timeout must be positive");
            }
            this.idleTimeout = timeout;
            return this;
        }
        
        public ConnectionPoolConfig build() {
            if (maxPoolSize < minPoolSize) {
                throw new IllegalStateException("Max pool size cannot be less than min pool size");
            }
            return new ConnectionPoolConfig(this);
        }
    }
    
    @Override
    public String toString() {
        return "ConnectionPoolConfig{" +
                "url='" + databaseUrl + '\'' +
                ", minPoolSize=" + minPoolSize +
                ", maxPoolSize=" + maxPoolSize +
                ", connectionTimeout=" + connectionTimeout + "ms" +
                ", idleTimeout=" + idleTimeout + "ms" +
                '}';
    }
}
