# Database Connection Pool Manager - Low Level Design

A thread-safe, production-ready database connection pool manager implementation in Java.

## Overview

This implementation provides a robust connection pool manager that efficiently manages database connections with features like connection reuse, validation, automatic cleanup, and thread-safe operations.

## Key Features

### 1. **Connection Pool Management**
- Maintains minimum and maximum pool size
- Dynamic connection creation up to max limit
- Automatic connection recycling

### 2. **Thread Safety**
- Thread-safe operations using `ReentrantLock` and `ConcurrentHashMap`
- BlockingQueue for connection availability
- Safe concurrent access from multiple threads

### 3. **Connection Validation**
- Validates connections before returning to pool
- Removes stale/invalid connections
- Automatic replacement of invalid connections

### 4. **Idle Connection Cleanup**
- Periodic cleanup of idle connections (every 60 seconds)
- Maintains minimum pool size during cleanup
- Configurable idle timeout

### 5. **Timeout Handling**
- Configurable connection acquisition timeout
- Prevents indefinite blocking
- Graceful timeout exceptions

### 6. **Monitoring & Statistics**
- Real-time pool statistics
- Track total, available, and in-use connections
- Connection usage tracking

## Architecture

### Class Diagram

```
┌─────────────────────────────┐
│ ConnectionPoolConfig        │
├─────────────────────────────┤
│ - databaseUrl: String       │
│ - minPoolSize: int          │
│ - maxPoolSize: int          │
│ - connectionTimeout: long   │
│ - idleTimeout: long         │
└─────────────────────────────┘
           ▲
           │
           │ uses
           │
┌──────────┴──────────────────┐
│ ConnectionPoolManager       │
├─────────────────────────────┤
│ - config: Config            │
│ - availableConnections      │
│ - allConnections            │
│ - poolLock: ReentrantLock   │
├─────────────────────────────┤
│ + getConnection()           │
│ + releaseConnection()       │
│ + getStats()                │
│ + shutdown()                │
└─────────────────────────────┘
           │
           │ manages
           ▼
┌─────────────────────────────┐
│ DatabaseConnection          │
├─────────────────────────────┤
│ - connectionId: long        │
│ - url: String               │
│ - isOpen: boolean           │
│ - inUse: boolean            │
│ - lastUsedAt: long          │
├─────────────────────────────┤
│ + open()                    │
│ + close()                   │
│ + isValid()                 │
│ + executeQuery()            │
└─────────────────────────────┘
```

## Components

### 1. DatabaseConnection
Represents a wrapper around a database connection with metadata:
- Unique connection ID
- Connection state (open/closed, in-use)
- Timestamp tracking (creation, last used)
- Connection validation
- Query execution simulation

### 2. ConnectionPoolConfig
Configuration class using Builder pattern:
- Database URL
- Min/Max pool size
- Connection timeout
- Idle timeout
- Validation rules

### 3. ConnectionPoolManager
Main pool manager class:
- Connection lifecycle management
- Thread-safe connection acquisition/release
- Periodic idle connection cleanup
- Connection validation and recycling
- Pool statistics tracking

### 4. ConnectionPoolDemo
Demonstration class with multiple scenarios:
- Basic usage
- Concurrent access
- Pool size management
- Timeout handling

## Usage Examples

### Basic Usage

```java
// Create configuration
ConnectionPoolConfig config = new ConnectionPoolConfig.Builder()
    .databaseUrl("jdbc:mysql://localhost:3306/mydb")
    .minPoolSize(5)
    .maxPoolSize(20)
    .connectionTimeout(5000)
    .idleTimeout(300000)
    .build();

// Create pool manager
ConnectionPoolManager poolManager = new ConnectionPoolManager(config);

// Get connection
DatabaseConnection conn = poolManager.getConnection();

// Use connection
conn.executeQuery("SELECT * FROM users");

// Release connection
poolManager.releaseConnection(conn);

// Shutdown pool
poolManager.shutdown();
```

### Concurrent Access

```java
ExecutorService executor = Executors.newFixedThreadPool(10);

for (int i = 0; i < 100; i++) {
    executor.submit(() -> {
        try {
            DatabaseConnection conn = poolManager.getConnection();
            conn.executeQuery("SELECT * FROM data");
            poolManager.releaseConnection(conn);
        } catch (Exception e) {
            e.printStackTrace();
        }
    });
}

executor.shutdown();
executor.awaitTermination(1, TimeUnit.MINUTES);
```

### Monitoring Pool Statistics

```java
PoolStats stats = poolManager.getStats();
System.out.println("Total connections: " + stats.getTotalConnections());
System.out.println("Available connections: " + stats.getAvailableConnections());
System.out.println("In-use connections: " + stats.getInUseConnections());
```

## Design Patterns Used

### 1. **Object Pool Pattern**
- Reuses expensive-to-create database connections
- Improves performance and resource utilization

### 2. **Builder Pattern**
- `ConnectionPoolConfig.Builder` for flexible configuration
- Validates configuration parameters

### 3. **Singleton (per pool instance)**
- Each pool instance manages its own set of connections
- Thread-safe operations

### 4. **Factory Method**
- `createNewConnection()` encapsulates connection creation logic

## Thread Safety Mechanisms

### 1. **ReentrantLock**
- Protects critical sections during connection creation/removal
- Ensures pool size constraints are maintained

### 2. **BlockingQueue**
- Thread-safe connection availability management
- Automatic blocking when no connections available

### 3. **ConcurrentHashMap**
- Thread-safe storage of all connections
- Lock-free reads for better performance

### 4. **Volatile Variables**
- `isShutdown` flag for safe shutdown signaling
- `inUse` flag for connection state

## Connection Lifecycle

```
[Created] → [Open] → [Available] → [Acquired] → [In-Use] → [Released] → [Available]
                                                                │
                                                                ↓
                                                            [Validated]
                                                                │
                                                    ┌───────────┴───────────┐
                                                    │                       │
                                                [Valid]                [Invalid]
                                                    │                       │
                                                [Available]             [Closed]
                                                                            │
                                                                        [Removed]
```

## Configuration Guidelines

### Min Pool Size
- Should be based on expected concurrent requests
- Too low: Frequent connection creation overhead
- Too high: Unnecessary resource consumption
- **Recommended**: 5-10 for small applications

### Max Pool Size
- Should handle peak load
- Too low: Connection timeouts under load
- Too high: Database server overload
- **Recommended**: 20-50 for medium applications

### Connection Timeout
- Time to wait for an available connection
- Too low: Premature timeout failures
- Too high: Slow response under load
- **Recommended**: 5-30 seconds

### Idle Timeout
- Time before idle connections are cleaned up
- Balance between quick response and resource usage
- **Recommended**: 5-10 minutes

## Performance Considerations

1. **Connection Reuse**: Avoids expensive connection creation overhead
2. **Bounded Pool**: Prevents resource exhaustion
3. **Lock-Free Operations**: Uses concurrent data structures where possible
4. **Lazy Creation**: Creates connections on-demand up to max
5. **Proactive Cleanup**: Removes stale connections automatically

## Error Handling

- **TimeoutException**: Connection acquisition timeout
- **IllegalStateException**: Pool shutdown or invalid operations
- **InterruptedException**: Thread interruption during wait
- **Connection Validation**: Automatic retry on invalid connections

## Testing Scenarios

The demo includes four comprehensive scenarios:

1. **Basic Usage**: Single-threaded connection lifecycle
2. **Concurrent Access**: 10 threads competing for connections
3. **Pool Size Management**: Growing and shrinking the pool
4. **Timeout Handling**: Testing timeout behavior

## Limitations & Future Enhancements

### Current Limitations
- Simulated database connections (not real JDBC)
- Fixed cleanup interval (60 seconds)
- No connection pooling per database

### Potential Enhancements
1. Integration with real JDBC connections
2. Connection health checks (ping)
3. Configurable cleanup intervals
4. Connection pool per database URL
5. Advanced monitoring (JMX integration)
6. Connection leak detection
7. Prepared statement caching
8. Transaction management
9. Connection events/listeners
10. Metrics and analytics

## Comparison with Production Libraries

### HikariCP
- Similar architecture with blocking queues
- More optimized (lock-free algorithms)
- Advanced metrics and monitoring

### Apache DBCP
- More configuration options
- Connection validation queries
- Abandoned connection tracking

### c3p0
- Automatic retry logic
- Statement pooling
- Helper threads for management

**This implementation** provides the core concepts and patterns used by production libraries, making it an excellent learning resource for understanding connection pool internals.

## Running the Demo

```bash
# Compile
javac src/lld/database_connection_pool/*.java

# Run demo
java -cp src lld.database_connection_pool.ConnectionPoolDemo
```

## Conclusion

This implementation demonstrates a production-quality connection pool manager with proper thread safety, resource management, and error handling. It serves as an excellent foundation for understanding how real-world connection pools work and can be extended for production use with real database drivers.
