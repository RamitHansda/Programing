# Cloud-Native Infrastructure Patterns

There are roughly 6 categories. Here's the full landscape:

---

## 1. Container / Pod Patterns

### Sidecar
Attach a helper container to handle cross-cutting concerns — without touching app code.

### Init Container
Runs **before** the main container starts. Blocks startup until it completes.
```
Init Container:           Main Container:
  - Wait for DB to be up    - Starts only after DB is ready
  - Run DB migrations       - Never sees a cold DB
  - Fetch secrets from Vault
```

### Ambassador
A proxy sidecar that **represents the app to the outside world** — handles connection routing, protocol translation.
```
App → Ambassador (localhost:5432)
         ↓
    Routes to: primary DB for writes
               read replica for reads
               test DB in staging
```
App code always talks to `localhost:5432` — routing logic lives in the ambassador.

### Adapter
Normalizes **non-standard output** from legacy apps into a standard format.
```
Legacy app emits metrics in custom format
    ↓
Adapter sidecar transforms → Prometheus format
    ↓
Prometheus scrapes normally
```
Useful when you can't modify the app (vendor software, legacy systems).

---

## 2. Resilience Patterns

### Circuit Breaker
Stops calling a failing service to give it time to recover.
```
CLOSED → (failure threshold hit) → OPEN → (timeout) → HALF-OPEN → test call
   ↑_________________________success________________________________________|
```
- Prevents cascading failures across your pipeline
- Implemented in: Envoy, Hystrix, Resilience4j, Flink enrichment calls

### Retry with Exponential Backoff + Jitter
```
Attempt 1: wait 1s
Attempt 2: wait 2s + random(0-1s)   ← jitter prevents thundering herd
Attempt 3: wait 4s + random(0-2s)
Attempt 4: → DLQ
```
**Jitter is critical** — without it, all retrying clients hit the recovering service simultaneously and bring it down again.

### Bulkhead
Isolate failures by resource partitioning — like watertight compartments on a ship.
```
Thread pool for Service A: 20 threads
Thread pool for Service B: 20 threads

Service A becomes slow → its 20 threads exhaust
Service B is unaffected — has its own pool
```
In Kafka: separate consumer groups per use case. One slow consumer doesn't block others.

### Timeout
Every external call **must** have a deadline. No exceptions.
```
No timeout:  one slow DB query hangs a thread forever → thread pool exhaustion
With timeout: fail fast at 200ms → caller handles gracefully → system stays healthy
```

### Fallback
Define a degraded response when the primary path fails.
```
Primary:  fetch user profile from service (50ms SLA)
Fallback: return cached/default profile (stale is acceptable)
Fallback: return empty profile + flag event for later enrichment
```

---

## 3. Service Discovery & Communication Patterns

### Service Mesh (Envoy / Istio / Linkerd)
Move all network policies — mTLS, retries, timeouts, tracing — out of app code into the mesh.
```
App A → Envoy (sidecar) → [mTLS tunnel] → Envoy (sidecar) → App B
               ↓                                    ↓
         emit metrics                         emit metrics
         trace spans                          trace spans
```

### Service Registry — Client-Side Discovery
```
Service registers → Registry (Consul / Eureka)
Client queries registry → gets list of healthy instances → picks one → connects
```

### Load Balancer — Server-Side Discovery
```
Client → Load Balancer → queries registry internally → routes to instance
```
Client doesn't know anything about instances.

### Strangler Fig
Migrate a monolith to microservices **incrementally** without a big bang rewrite.
```
All traffic → Facade/Proxy
                 ↓
    New feature? → New microservice
    Old feature? → Legacy monolith (still works)

Over time: strangle the monolith by migrating routes one by one
```

---

## 4. Data Patterns

### Event Sourcing
Store **every state change as an immutable event**, not the current state.
```
Instead of:  UPDATE account SET balance = 5000

Store events:
  { event: "credited", amount: 3000, ts: T1 }
  { event: "credited", amount: 2000, ts: T2 }

Current state = replay all events
```
Full audit trail, time travel, rebuild any projection from history. Directly relevant to risk aggregation and settlement systems.

### CQRS (Command Query Responsibility Segregation)
Separate the write model from the read model.
```
Write path:  Command → validates → writes to event store (normalized)
Read path:   Query → reads from denormalized projection (optimized for query)

Example:
  Write: append transaction event to Kafka
  Read:  pre-aggregated balance in Redis (updated by a consumer)
```

### Outbox Pattern
Solve the dual-write problem — atomically write to DB and publish to Kafka.
```
Problem:  Write to DB ✓  →  Publish to Kafka ✗  →  data inconsistency

Solution:
  1. Write to DB + write to outbox table in SAME transaction
  2. CDC (Debezium) reads outbox table → publishes to Kafka
  → Guaranteed: if DB write succeeds, Kafka publish will happen
```
Critical for payments settlement pipelines where consistency between DB state and event stream is required.

### Saga Pattern
Manage distributed transactions without 2PC.
```
Choreography Saga (event-driven):
  PaymentService credits account → emits event
  SettlementService reacts → emits event
  NotificationService reacts → done

  On failure: each service listens for failure event → runs compensating transaction

Orchestration Saga:
  Saga Orchestrator calls each service in sequence
  On failure: orchestrator calls compensating transactions in reverse order
```

---

## 5. Deployment Patterns

### Blue-Green Deployment
```
Blue (current live):  v1 ← 100% traffic
Green (new version):  v2 ← 0% traffic (warming up, tested)

Switch:  Blue ← 0%   Green ← 100%
Rollback: flip back instantly
```

### Canary Deployment
```
v1: 95% traffic
v2:  5% traffic  ← monitor error rate, latency, business metrics

Gradually shift: 5% → 20% → 50% → 100%
Auto-rollback if metrics degrade
```

### Feature Flags
Deploy code to production but control activation separately.
```java
if (featureFlag.isEnabled("new-pricing-engine", userId)) {
    return newPricingEngine.price(instrument);
} else {
    return legacyPricingEngine.price(instrument);
}
```
Decouple **deploy** from **release**. Ship code daily; activate for 1% → 10% → everyone.

### Immutable Infrastructure
Never patch a running server. Replace it.
```
Bug in container → build new image → deploy new pods → drain old pods
Never: SSH into prod and hotfix
```

---

## 6. Observability Patterns

### Health Check — Liveness vs. Readiness
```
Liveness probe:   Is the app alive? (restart if not)
                  → checks: process running, no deadlock

Readiness probe:  Is the app ready to serve traffic? (remove from LB if not)
                  → checks: DB connection up, cache warmed, dependencies healthy

Startup probe:    Give slow-starting apps time to initialize before liveness kicks in
```

### Correlation ID / Trace Propagation
Inject a unique ID at the entry point, pass it through every service call, log it everywhere.
```
Request → API Gateway injects X-Trace-ID: abc123
  → Service A logs with trace_id=abc123
    → Kafka message header: trace_id=abc123
      → Flink logs with trace_id=abc123
        → DB query logs with trace_id=abc123

Debug any issue: grep trace_id=abc123 across all systems
```

### Structured Logging
```
Bad:   logger.info("Payment processed for user 123 amount 500")

Good:  logger.info({
         "event": "payment_processed",
         "user_id": "123",
         "amount": 500,
         "currency": "USD",
         "duration_ms": 45,
         "trace_id": "abc123"
       })
```
Structured logs are machine-parseable → Kibana/Splunk can query them as fields.

---

## Pattern Selection Cheat Sheet

| Problem | Pattern |
|---|---|
| Cross-cutting concerns (TLS, logs, tracing) | Sidecar |
| Legacy app modernization | Strangler Fig / Adapter |
| Dual-write consistency (DB + Kafka) | Outbox Pattern |
| Distributed transactions | Saga |
| Cascading failures | Circuit Breaker + Bulkhead |
| Audit trail + time travel | Event Sourcing |
| Read/write scale independently | CQRS |
| Zero-downtime deploy | Blue-Green / Canary |
| Decouple deploy from release | Feature Flags |
| Debug distributed request end-to-end | Correlation ID + Structured Logging |
| Slow-starting legacy app in K8s | Init Container + Startup Probe |
| Multi-DB routing (primary/replica) | Ambassador |

---

## How These Compose in a Real System

These patterns aren't independent. In a well-designed data pipeline you'd use them together:

```
Ingestion Service
  ├── Outbox Pattern          → guaranteed DB + Kafka consistency
  ├── Sidecar (Envoy)         → mTLS to Kafka, retry policy, circuit breaker
  └── Structured Logging      → trace_id propagated into Kafka message headers

Flink Processing
  ├── Circuit Breaker         → on Redis enrichment calls
  ├── Bulkhead                → separate thread pools per enrichment source
  ├── Fallback                → skip enrichment, flag event, continue pipeline
  └── Correlation ID          → trace_id from Kafka header → Flink logs → alerts

Deployment
  ├── Canary                  → 5% traffic to new Flink job version
  ├── Feature Flags           → toggle new fraud model without redeployment
  └── Immutable Infrastructure → new Flink image per release, never patch in-place

Settlement Pipeline
  ├── Saga (Orchestration)    → multi-step settlement with compensating transactions
  ├── CQRS                    → write to event store, read from aggregated balance view
  └── Event Sourcing          → full audit trail of every balance change (regulatory)
```
