# dlock Architecture

## Table of Contents
1. [System Overview](#system-overview)
2. [Architecture Diagram](#architecture-diagram)
3. [Component Details](#component-details)
4. [Design Decisions](#design-decisions)
5. [Data Flow](#data-flow)
6. [Integration Points](#integration-points)
7. [Security Considerations](#security-considerations)
8. [Performance & Scalability](#performance--scalability)
9. [Error Handling Strategy](#error-handling-strategy)
10. [Future Architecture Evolution](#future-architecture-evolution)

---

## System Overview

### Purpose
dlock is a lightweight Spring Boot library that provides distributed locking capabilities through declarative annotations. It enables developers to protect critical sections of code across multiple application instances without writing explicit lock management code.

### Core Principles
- **Declarative over Imperative**: Use annotations instead of manual lock management
- **AOP-based Interception**: Non-invasive integration with existing code
- **Battle-tested Backend**: Leverage Spring Integration's proven `RedisLockRegistry`
- **Fail-fast**: Throw exceptions on lock acquisition failure rather than silent failures

### Technology Stack
| Component | Technology | Version | Purpose |
|-----------|-----------|---------|---------|
| Runtime | Java | 11+ | Base platform |
| Framework | Spring Boot | 2.7.x | Application framework |
| AOP Engine | Spring AOP / AspectJ | 5.3.22 | Method interception |
| Lock Backend | Spring Integration Redis | 5.5.14 | Distributed lock registry |
| Redis Client | Jedis | 4.2.3 | Redis connectivity |
| Build Tool | Maven | 3.x | Dependency & build management |

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                    Spring Boot Application                       │
│                                                                   │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │              Business Service Layer                      │   │
│  │  @DistributedLock(timeout=30)                           │   │
│  │  @Service                                                │   │
│  │  public class PaymentService {                          │   │
│  │    public void settle(@LockKey String invoiceId) {      │   │
│  │      // Critical section                                │   │
│  │    }                                                     │   │
│  │  }                                                       │   │
│  └──────────────────┬──────────────────────────────────────┘   │
│                     │ Method Invocation                         │
│                     ↓                                            │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │           DistributedLockAspect (AOP Layer)             │   │
│  │  • Intercepts @DistributedLock annotated methods        │   │
│  │  • Extracts @LockKey parameter                          │   │
│  │  • Manages lock lifecycle                               │   │
│  │  • Handles exceptions                                   │   │
│  └──────────────────┬──────────────────────────────────────┘   │
│                     │ Lock Request                              │
│                     ↓                                            │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │              LockConfig (Configuration Layer)            │   │
│  │  • Provides LockRegistry bean                           │   │
│  │  • Wraps RedisLockRegistry                              │   │
│  │  • Requires RedisConnectionFactory                      │   │
│  └──────────────────┬──────────────────────────────────────┘   │
│                     │ Lock Operations                           │
└─────────────────────┼───────────────────────────────────────────┘
                      ↓
         ┌────────────────────────────┐
         │  Spring Integration Redis  │
         │   RedisLockRegistry        │
         │  • Distributed lock impl   │
         │  • Reentrant locks         │
         │  • TTL-based expiry        │
         └────────────┬───────────────┘
                      │ Redis Protocol
                      ↓
              ┌───────────────┐
              │  Redis Server │
              │  (Cluster-safe)│
              └───────────────┘
```

---

## Component Details

### 1. Annotation Layer

#### `@DistributedLock`
**Purpose**: Class-level annotation to enable distributed locking for all public methods.

**Location**: `lib.skydo.dlock.DistributedLock`

**Attributes**:
- `timeout`: Lock acquisition timeout in seconds (default: 30L)

**Retention**: Runtime

**Design Choice**: Class-level annotation reduces boilerplate compared to method-level annotations when all methods need protection.

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {
    long timeout() default 30L;
}
```

#### `@LockKey`
**Purpose**: Parameter-level annotation to mark which method parameter should be used as the lock key.

**Location**: `lib.skydo.dlock.LockKey`

**Constraints**:
- Must annotate exactly one parameter per method
- Parameter must be of type `String`
- Value must be non-empty

**Design Choice**: Parameter-based key extraction allows flexible, context-aware locking (e.g., per-user, per-invoice, per-SKU).

---

### 2. AOP Layer

#### `DistributedLockAspect`
**Location**: `lib.skydo.dlock.DistributedLockAspect`

**Responsibilities**:
1. Intercept method calls on `@DistributedLock` classes
2. Extract lock key from `@LockKey` parameter
3. Acquire distributed lock with timeout
4. Execute target method
5. Release lock in finally block

**Pointcut Strategy**:
```java
@Pointcut("within(@lib.skydo.dlock.DistributedLock *)")
public void distributedLock() {}

@Pointcut("execution(public * *(..))")
public void publicMethod() {}

@Around("publicMethod() && @within(distributedLock)")
```

**Key Methods**:

| Method | Purpose | Notes |
|--------|---------|-------|
| `doUnderLock()` | Main advice method | Orchestrates lock lifecycle |
| `getKey()` | Extract lock key | Validates `@LockKey` parameter |
| `getWaitingTime()` | Get timeout value | **Currently hardcoded to 30s** |
| `runProceedingJoinPoint()` | Execute target method | Wraps exceptions |

**Lock Lifecycle**:
```
1. Extract key from @LockKey parameter
2. Obtain Lock from LockRegistry
3. tryLock(timeout, SECONDS)
   ├─ Success → Execute method
   └─ Failure → Throw DistributedLockException
4. Finally: Release lock if acquired
```

---

### 3. Configuration Layer

#### `LockConfig`
**Location**: `lib.skydo.dlock.LockConfig`

**Purpose**: Provides `LockRegistry` bean backed by Redis.

**Bean Definition**:
```java
@Bean
@Qualifier("redis")
public LockRegistry getLockRegistry(RedisConnectionFactory connectionFactory) {
    return new RedisLockRegistry(connectionFactory, "lock-key");
}
```

**Dependencies**:
- Requires `RedisConnectionFactory` bean from host application
- Typically provided by `spring-boot-starter-data-redis`

**Properties** (defined but not fully wired):
- `dlock.lock_registery`
- `dlock.host`
- `dlock.port`

---

#### `DlockConfiguration`
**Location**: `lib.skydo.dlock.DlockConfiguration`

**Purpose**: Auto-configuration class enabled by `@EnableDlock`.

**Key Annotations**:
```java
@EnableAspectJAutoProxy              // Enable AOP
@Configuration                        // Spring configuration
@ConfigurationPropertiesScan         // Scan for @ConfigurationProperties
@ComponentScan(basePackages = {"lib.skydo.dlock"})  // Scan components
```

---

#### `@EnableDlock`
**Location**: `lib.skydo.dlock.EnableDlock`

**Purpose**: Single annotation to bootstrap entire library.

**Import Chain**:
```
@EnableDlock
    ↓
@Import({DlockConfiguration.class})
    ↓
- Enable AspectJ Auto Proxy
- Component Scanning
- Configuration Properties Scanning
```

---

### 4. Exception Hierarchy

```
Throwable
    ↓
Exception
    ├─ DistributedLockException
    │  • Thrown when lock acquisition fails
    │  • Wraps timeout/key validation errors
    │
    └─ DistributedProxyException
       • Wraps exceptions from target method execution
       • Preserves original exception as cause
```

**Error Propagation Flow**:
1. Target method throws exception → Wrapped in `DistributedProxyException`
2. Lock acquisition fails → `DistributedLockException` thrown
3. Invalid `@LockKey` parameter → `Exception` thrown (generic)

---

## Design Decisions

### 1. Why AOP over Manual Lock Management?

**Decision**: Use AspectJ AOP to intercept methods rather than requiring explicit lock calls.

**Rationale**:
- ✅ Keeps business logic clean
- ✅ Reduces boilerplate (no try-finally blocks)
- ✅ Centralizes lock management
- ✅ Prevents common errors (forgotten unlock, missing finally)

**Trade-off**: Requires Spring AOP support, adds framework coupling.

---

### 2. Why Redis over Other Backends?

**Decision**: Use `RedisLockRegistry` from Spring Integration.

**Rationale**:
- ✅ Battle-tested in production (Spring Integration since 2014)
- ✅ Cluster-safe distributed locking
- ✅ Built-in reentrancy support
- ✅ TTL-based lock expiry (handles crash scenarios)
- ✅ Common in Spring Boot ecosystems

**Alternatives Considered**:
- **Database locks**: Higher latency, less scalable
- **ZooKeeper**: Over-engineered for simple locking
- **Hazelcast**: Additional dependency footprint

---

### 3. Why Class-Level Annotation?

**Decision**: `@DistributedLock` at class level, not method level.

**Rationale**:
- ✅ Reduces repetition when all methods need protection
- ✅ Clear opt-in semantics (entire class is lock-aware)

**Trade-off**: Less granular control. Considered acceptable since mixed lock/non-lock methods in same class indicate poor separation of concerns.

---

### 4. Why String-Based Lock Keys?

**Decision**: Require `@LockKey` parameter to be `String` type.

**Rationale**:
- ✅ Simplicity: No complex serialization logic
- ✅ Clarity: Lock keys are human-readable in logs
- ✅ Compatibility: Redis keys are strings

**Trade-off**: Developers must handle composite keys manually (e.g., `"user:" + userId`).

---

### 5. Why Fail-Fast on Lock Failure?

**Decision**: Throw exception immediately if lock not acquired within timeout.

**Rationale**:
- ✅ Forces caller to handle contention explicitly
- ✅ Prevents silent failures or indefinite waits
- ✅ Supports retry/backoff patterns at higher layers

**Alternative**: Return boolean or Optional → Rejected as error-prone.

---

## Data Flow

### Successful Lock Acquisition Flow

```
1. Client invokes @DistributedLock class method
   PaymentService.settle("INV-123")
        ↓
2. AspectJ intercepts call (DistributedLockAspect)
        ↓
3. Extract lock key: "INV-123"
        ↓
4. Get Lock from RedisLockRegistry
   lockRegistry.obtain("INV-123")
        ↓
5. Attempt lock acquisition
   lock.tryLock(30, TimeUnit.SECONDS)
        ↓
6. Redis SET NX EX command (via Jedis)
   SET lock-key:INV-123 <thread-id> NX EX <ttl>
        ↓
7. Lock acquired → proceed with method
   pjp.proceed()
        ↓
8. Method executes successfully
        ↓
9. Finally: Release lock
   lock.unlock() → Redis DEL lock-key:INV-123
        ↓
10. Return result to caller
```

---

### Lock Contention Flow

```
1. Thread A acquires lock for "INV-123"
2. Thread B attempts lock for "INV-123"
        ↓
3. tryLock(30, SECONDS) → waits up to 30s
        ↓
4. If Thread A releases within 30s:
   ├─ Thread B acquires lock
   └─ Proceeds normally
        ↓
5. If timeout expires:
   ├─ tryLock() returns false
   ├─ Throw DistributedLockException
   └─ Caller receives exception
```

---

### Key Extraction Flow

```
Method Signature:
public void process(@Param1 String userId, @LockKey String orderId)

1. Aspect intercepts method
        ↓
2. Get method signature via reflection
   MethodSignature.getMethod().getParameterAnnotations()
        ↓
3. Iterate through parameters:
   - userId: No @LockKey → Skip
   - orderId: Has @LockKey → Validate
        ↓
4. Validation:
   - Is String? ✓
   - Is non-empty? ✓
        ↓
5. Return "orderId" value as lock key
```

**Validation Rules**:
- Exactly one `@LockKey` per method (enforced at runtime)
- Parameter must be `String` type
- Value must be non-empty (checked via `toString().isEmpty()`)

---

## Integration Points

### 1. Host Application Requirements

**Mandatory**:
```xml
<!-- Redis connection factory -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>

<!-- dlock library -->
<dependency>
    <groupId>com.skydo.lib</groupId>
    <artifactId>dlock</artifactId>
    <version>0.0.3</version>
</dependency>
```

**Configuration** (`application.properties`):
```properties
spring.redis.host=localhost
spring.redis.port=6379
# Optional: password, ssl, pool settings
```

---

### 2. Bean Dependencies

```
┌─────────────────────────────┐
│  Host Application Context   │
│                              │
│  • RedisConnectionFactory   │ ◄─── Provided by spring-boot-starter-data-redis
└──────────────┬───────────────┘
               │ Autowired
               ↓
┌─────────────────────────────┐
│     LockConfig (dlock)      │
│  @Bean LockRegistry         │ ◄─── Created by dlock
└──────────────┬───────────────┘
               │ @Qualifier("redis")
               ↓
┌─────────────────────────────┐
│  DistributedLockAspect      │ ◄─── Aspect uses LockRegistry
└─────────────────────────────┘
```

---

### 3. Redis Commands Used

| Operation | Redis Command | Purpose |
|-----------|--------------|---------|
| Acquire Lock | `SET lock-key:<key> <value> NX EX <ttl>` | Atomic set-if-not-exists with expiry |
| Release Lock | `DEL lock-key:<key>` | Remove lock key |
| Check Ownership | `GET lock-key:<key>` | Verify thread still holds lock |

**Lock Key Format**: `lock-key:<user-provided-key>`
- Example: `lock-key:INV-123`

---

## Security Considerations

### 1. Lock Hijacking Protection
**Mechanism**: `RedisLockRegistry` associates locks with thread identity.
- Only the thread that acquired a lock can release it
- Prevents accidental/malicious unlock by other threads

### 2. Lock Expiry (TTL)
**Purpose**: Prevent indefinite lock holding if JVM crashes.
- Redis automatically expires locks after TTL
- Default TTL managed by `RedisLockRegistry` (typically 60s)

**Trade-off**: If method execution exceeds TTL, lock may be released prematurely.

### 3. Redis Authentication
**Responsibility**: Host application must configure Redis credentials.
```properties
spring.redis.password=<secure-password>
spring.redis.ssl=true
```

### 4. Key Injection Risks
**Risk**: User-controlled `@LockKey` values could collide intentionally.

**Mitigation**:
- Use namespaced keys: `"payment:" + invoiceId`
- Validate key format at service layer
- Consider adding configurable key prefix in library

---

## Performance & Scalability

### 1. Lock Acquisition Latency

| Operation | Typical Latency | Notes |
|-----------|----------------|-------|
| Local Redis | 1-5ms | Same datacenter |
| Remote Redis | 10-50ms | Cross-region |
| Lock contention | 0-30s | Waits up to timeout |

**Optimization**: Keep critical sections short to minimize hold time.

---

### 2. Throughput Considerations

**Single Lock Key**:
- Maximum: ~1000 acquisitions/sec (network bound)
- Effective: ~100/sec with 10ms hold time

**Multiple Lock Keys**:
- Scales horizontally (different keys = no contention)
- Limited only by Redis throughput (~100K ops/sec)

---

### 3. Redis Scalability

**Recommended Setup**:
- **Single Redis instance**: Up to 10K locks/sec
- **Redis Cluster**: Horizontal scaling (keys distributed via hash slots)
- **Redis Sentinel**: High availability (automatic failover)

**Cluster Considerations**:
- `RedisLockRegistry` supports Redis Cluster
- Lock keys should distribute evenly (avoid hotspots)

---

### 4. Memory Usage

**Per Lock**:
- Key: ~50 bytes (`lock-key:<user-key>`)
- Value: ~50 bytes (thread identifier)
- Total: ~100 bytes per active lock

**Example**: 10,000 concurrent locks = ~1MB memory

---

## Error Handling Strategy

### 1. Exception Types

| Exception | Trigger | Recovery Strategy |
|-----------|---------|-------------------|
| `DistributedLockException` | Lock not acquired within timeout | Retry with backoff, alert user |
| `DistributedProxyException` | Target method throws exception | Propagate original exception |
| Generic `Exception` | Invalid `@LockKey` parameter | Fix code, validation error |

---

### 2. Retry Pattern Example

```java
@Service
public class RetryablePaymentService {
    
    @Autowired
    private PaymentService paymentService;
    
    @Retryable(
        value = DistributedLockException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public void settleWithRetry(String invoiceId) {
        paymentService.settle(invoiceId);
    }
}
```

---

### 3. Monitoring & Alerting

**Key Metrics**:
- Lock acquisition success rate
- Average lock wait time
- Lock timeout frequency
- Lock hold duration

**Recommended Instrumentation**:
```java
// Add in DistributedLockAspect
Timer.Sample sample = Timer.start(meterRegistry);
// ... lock acquisition ...
sample.stop(Timer.builder("dlock.acquisition")
    .tag("key", key)
    .tag("success", String.valueOf(lockAcquired))
    .register(meterRegistry));
```

---

## Future Architecture Evolution

### 1. Planned Enhancements

#### Phase 1: Bug Fixes & Configuration
- ✅ Honor `@DistributedLock.timeout()` attribute (currently hardcoded)
- ✅ Wire `DlockProperties` into `LockConfig`
- ✅ Support external configuration:
  ```properties
  dlock.default-timeout=30
  dlock.registry-key-prefix=myapp-lock
  ```

#### Phase 2: Observability
- Add built-in metrics (Micrometer integration)
- Structured logging with correlation IDs
- Health check endpoint for Redis connectivity

#### Phase 3: Advanced Features
- Support method-level `@DistributedLock` (override class-level)
- Composite key support: `@LockKey(prefix="user", fields={"id", "tenantId"})`
- Lock leasing (auto-extend for long-running operations)
- Read-write lock support

---

### 2. Alternative Backend Support

**Potential Abstractions**:
```java
public interface LockProvider {
    Lock obtain(String key);
}

// Implementations:
- RedisLockProvider (current)
- DatabaseLockProvider (JDBC pessimistic locks)
- HazelcastLockProvider
- EtcdLockProvider
```

**Migration Path**:
1. Introduce `LockProvider` interface
2. Make `RedisLockProvider` default implementation
3. Allow configuration via `@EnableDlock(provider = RedisLockProvider.class)`

---

### 3. Spring Boot 3.x Compatibility

**Required Changes**:
- Upgrade to Spring 6.x
- Replace `javax.*` with `jakarta.*` packages
- Test with Java 17+ (required by Spring Boot 3)
- Update Spring Integration to 6.x

**Backward Compatibility Strategy**:
- Maintain separate branches for 2.x and 3.x
- Version scheme: `0.x.y` (Spring Boot 2), `1.x.y` (Spring Boot 3)

---

### 4. Testing Infrastructure

**Proposed Test Architecture**:
```
tests/
├── unit/
│   ├── DistributedLockAspectTest (mock LockRegistry)
│   └── LockConfigTest (bean creation)
├── integration/
│   ├── RedisLockIntegrationTest (embedded Redis)
│   └── ContendedLockTest (multi-threaded)
└── performance/
    └── LockThroughputTest (benchmark)
```

---

## Appendix

### A. Redis Lock Registry Internals

`RedisLockRegistry` uses Lua scripts for atomic lock operations:

```lua
-- Acquire lock (simplified)
if redis.call("exists", KEYS[1]) == 0 then
    redis.call("set", KEYS[1], ARGV[1], "PX", ARGV[2])
    return 1
else
    return 0
end

-- Release lock (simplified)
if redis.call("get", KEYS[1]) == ARGV[1] then
    redis.call("del", KEYS[1])
    return 1
else
    return 0
end
```

---

### B. AspectJ Weaving Modes

dlock uses **Spring AOP Proxy-based weaving**:
- Works via JDK dynamic proxies or CGLIB
- Only intercepts Spring-managed beans
- Requires `@EnableAspectJAutoProxy`

**Limitation**: Cannot intercept:
- Private methods
- Final methods
- Self-invocations (within same class)

---

### C. Thread Safety Guarantees

1. **Lock Acquisition**: Atomic (Redis SET NX)
2. **Lock Release**: Atomic (Redis DEL with owner check)
3. **Reentrancy**: Supported (same thread can re-acquire)
4. **Fairness**: Not guaranteed (Redis doesn't queue waiters)

---

### D. Operational Runbook

**Scenario: Lock Acquisition Failures**

1. Check Redis connectivity:
   ```bash
   redis-cli -h <host> -p <port> PING
   ```

2. Inspect active locks:
   ```bash
   redis-cli KEYS "lock-key:*"
   ```

3. Check lock holder (if key exists):
   ```bash
   redis-cli GET "lock-key:<your-key>"
   redis-cli TTL "lock-key:<your-key>"
   ```

4. Force release (emergency only):
   ```bash
   redis-cli DEL "lock-key:<your-key>"
   ```

**Scenario: Slow Lock Acquisition**

1. Monitor Redis latency:
   ```bash
   redis-cli --latency
   ```

2. Check for long-running transactions:
   ```bash
   redis-cli SLOWLOG GET 10
   ```

3. Review application logs for long critical sections

---

### E. Version History

| Version | Release Date | Key Changes |
|---------|--------------|-------------|
| 0.0.3 | Current | Production release, Maven Central |
| 0.0.2 | Unknown | Beta testing |
| 0.0.1 | Unknown | Initial release |

---

## Document Metadata

- **Author**: Generated for dlock project documentation
- **Last Updated**: 2026-03-02
- **Version**: 1.0
- **Target Audience**: Engineers, Architects, DevOps
- **Maintenance**: Update when major architectural changes occur
