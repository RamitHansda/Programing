# Cloud-Native Infrastructure Patterns

There are roughly 6 categories. Here's the full landscape with use cases, pros, and cons.

---

## 1. Container / Pod Patterns

### Sidecar
Attach a helper container to handle cross-cutting concerns — without touching app code.

**Use Cases:**
- Service mesh (Envoy/Istio) — inject TLS, retries, tracing without touching app code
- Log shipping — Fluentd tails log files and forwards to ELK
- Secret injection — Vault Agent fetches and rotates credentials at runtime
- Metrics export — expose Prometheus `/metrics` from a legacy app that can't be modified

| Pros | Cons |
|---|---|
| Zero app code changes required | Increases pod resource footprint (CPU + memory per sidecar) |
| Language agnostic — works for Java, Go, Python equally | Adds operational complexity — two containers to debug instead of one |
| Upgradeable independently of the main app | Startup ordering must be managed carefully |
| Separates infrastructure concerns cleanly | Network calls over localhost still add latency (small but real) |
| Consistent behavior across all services in the fleet | Container sprawl — 50 services = 100 containers to maintain |

**When NOT to use:** Simple internal tools or scripts where the overhead isn't justified. Avoid if your team isn't running Kubernetes — the pattern's value multiplies in an orchestrated environment.

---

### Init Container
Runs **before** the main container starts. Blocks startup until it completes.
```
Init Container:           Main Container:
  - Wait for DB to be up    - Starts only after DB is ready
  - Run DB migrations       - Never sees a cold DB
  - Fetch secrets from Vault
```

**Use Cases:**
- Wait for a dependency (DB, cache) to be healthy before app starts
- Run DB schema migrations before the app boots
- Pre-populate a shared volume with config or static assets
- Clone a git repo into a shared volume before the app reads it

| Pros | Cons |
|---|---|
| Guarantees startup ordering without complex app-level logic | Increases pod startup time — every init must complete first |
| Failure blocks pod start — prevents app from booting against broken deps | If init container hangs, pod stays in `Init` state indefinitely |
| Can use a different image from main container (e.g., `flyway` image) | Debugging requires separate `kubectl logs -c init-container` commands |
| Cleaner than baking startup checks into app code | Not suitable for long-running or conditional initialization logic |

**Real example:** Init container runs `flyway migrate` → main app starts → schema is always current. Without this, race conditions between app startup and migration cause production failures on rolling deploys.

---

### Ambassador
A proxy sidecar that **represents the app to the outside world** — handles connection routing and protocol translation.
```
App → Ambassador (localhost:5432)
         ↓
    Routes to: primary DB for writes
               read replica for reads
               test DB in staging
```
App code always talks to `localhost:5432` — routing logic lives in the ambassador.

**Use Cases:**
- DB proxy — route writes to primary, reads to replica, transparently to the app
- Multi-environment routing — same app config in dev/staging/prod; ambassador handles actual endpoints
- API rate limiting proxy — app calls localhost, ambassador enforces limits before forwarding
- Protocol translation — app speaks HTTP/1.1, ambassador upgrades to gRPC for downstream

| Pros | Cons |
|---|---|
| App config stays identical across environments | Another container to operate and monitor |
| Protocol translation without modifying app code | Adds latency on every outbound call (small, but measurable) |
| Centralizes routing rules in one place | If ambassador crashes, app loses all connectivity |
| Can hot-reload routing rules without restarting app | Debugging connectivity issues requires checking ambassador logs first |

**When NOT to use:** If your service mesh (Istio/Envoy) already handles routing — adding an ambassador on top creates redundant proxying layers.

---

### Adapter
Normalizes **non-standard output** from legacy apps into a standard format.
```
Legacy app emits metrics in custom format
    ↓
Adapter sidecar transforms → Prometheus format
    ↓
Prometheus scrapes normally
```

**Use Cases:**
- Translate legacy metrics format to Prometheus
- Normalize log format from vendor software to structured JSON
- Convert SOAP responses to REST/JSON for modern consumers
- Expose health check endpoint for apps that don't have one natively

| Pros | Cons |
|---|---|
| Integrate legacy/vendor apps without modifying them | Transformation logic can become complex and hard to test |
| Standardize heterogeneous output across your fleet | Adds resource overhead and another failure point |
| Enables observability on apps you don't own | Transformation bugs silently corrupt data — needs careful testing |
| Decouples consumer from legacy output format | Transformation latency adds to end-to-end response time |

---

## 2. Resilience Patterns

### Circuit Breaker
Stops calling a failing service to give it time to recover.
```
CLOSED → (failure threshold hit) → OPEN → (timeout) → HALF-OPEN → test call
   ↑_________________________success________________________________________|
```

**Use Cases:**
- Protect against downstream service failures cascading upstream
- Flink enrichment calls to Redis or external APIs
- Payment gateway integrations — stop hammering a failing gateway
- Any synchronous RPC call in a distributed system

| Pros | Cons |
|---|---|
| Prevents cascading failures — one bad service can't bring down the system | False positives can open the circuit unnecessarily (tune thresholds carefully) |
| Gives failing service time to recover without being overloaded | Adds complexity — need to define fallback behavior for OPEN state |
| Fails fast — callers get an immediate error instead of waiting for timeout | Half-open state logic needs careful design to avoid flapping |
| Observable — circuit state is a first-class metric to monitor | Per-instance circuit state means each pod has its own state (not coordinated) |

**Real pitfall:** Setting the failure threshold too low (e.g., 1 failure = open) causes constant flapping. Typical good defaults: 50% failure rate over 10 requests, 30-second reset timeout.

---

### Retry with Exponential Backoff + Jitter
```
Attempt 1: wait 1s
Attempt 2: wait 2s + random(0-1s)   ← jitter prevents thundering herd
Attempt 3: wait 4s + random(0-2s)
Attempt 4: → DLQ
```

**Use Cases:**
- Transient network failures (TCP timeout, DNS blip)
- Rate limit responses (HTTP 429) — back off and retry
- DB connection pool exhaustion — brief backoff before retry
- Kafka producer failures on broker leader reelection

| Pros | Cons |
|---|---|
| Handles transient failures automatically | Can mask real problems — a bug that always fails looks like a transient error |
| Jitter prevents thundering herd on recovery | Increases end-to-end latency during retry storms |
| Bounded retries prevent infinite loops | Without idempotency, retries cause duplicate side effects |
| Simple to implement and well-understood | Must be combined with circuit breaker — retries alone can overload a struggling service |

**Critical rule:** Never retry without idempotency. A payment that retries 3 times must not charge 3 times. Idempotency key on every mutating call.

---

### Bulkhead
Isolate failures by resource partitioning — like watertight compartments on a ship.
```
Thread pool for Service A: 20 threads
Thread pool for Service B: 20 threads

Service A becomes slow → its 20 threads exhaust
Service B is unaffected — has its own pool
```

**Use Cases:**
- Isolate slow external API calls from core business logic thread pool
- Separate Kafka consumer groups per use case — fraud pipeline vs. analytics pipeline
- Separate DB connection pools per query type — OLTP vs. reporting queries
- Kubernetes resource quotas per namespace — one team can't starve another

| Pros | Cons |
|---|---|
| Failure in one partition doesn't cascade to others | Resource underutilization — idle pool in A can't help overloaded pool in B |
| Predictable resource allocation | More pools = more configuration and monitoring overhead |
| Critical paths get dedicated, guaranteed resources | Sizing pools correctly requires load testing and tuning |
| Easy to reason about under load | Static partitioning — can't adapt dynamically to load patterns without additional logic |

---

### Timeout
Every external call **must** have a deadline. No exceptions.
```
No timeout:  one slow DB query hangs a thread forever → thread pool exhaustion
With timeout: fail fast at 200ms → caller handles gracefully → system stays healthy
```

**Use Cases:**
- Every DB query, HTTP call, cache lookup, external API call
- Kafka producer `max.block.ms` — don't hang on metadata fetch
- Flink async I/O operator timeout — AsyncIO timeout + fallback

| Pros | Cons |
|---|---|
| Prevents thread starvation from slow downstream | Too aggressive timeouts cause false failures on momentarily slow deps |
| Makes failure mode explicit and fast | Setting correct timeout values requires profiling P99 latency of dependencies |
| Enables predictable SLA behavior | Timeout + retry combination can amplify load on recovering services |
| Forces teams to think about dependency SLAs | Cascading timeouts — if A times out on B, A's caller also needs appropriate timeout |

**Rule of thumb:** Timeout = P99 latency of dependency × 1.5. Review when dependency SLAs change.

---

### Fallback
Define a degraded response when the primary path fails.
```
Primary:  fetch user profile from service (50ms SLA)
Fallback: return cached/default profile (stale is acceptable)
Fallback: return empty profile + flag event for later enrichment
```

**Use Cases:**
- Return stale cached data when live service is unavailable
- Skip optional enrichment in Flink pipeline — flag event, continue processing
- Default risk score when scoring service is down — apply conservative limit
- Static asset CDN fallback when origin is unreachable

| Pros | Cons |
|---|---|
| System stays partially functional during downstream failure | Stale or default data may lead to incorrect business decisions |
| Users get degraded experience instead of hard error | Fallback logic must be explicitly tested — it's often the least-tested path |
| Enables graceful degradation as a design principle | Risk of fallback masking real failures — monitor fallback invocation rate |
| Keeps pipeline moving — critical for streaming systems | Complex systems may have chains of fallbacks, making behavior unpredictable |

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

**Use Cases:**
- Zero-trust networking — mTLS between all services without app code changes
- Uniform observability — all service-to-service traffic traced automatically
- Traffic management — canary releases, A/B testing at the mesh layer
- Policy enforcement — rate limits, circuit breaking, retries without app changes

| Pros | Cons |
|---|---|
| Network policies enforced uniformly across all services | Significant operational complexity — Istio is notoriously hard to operate |
| Zero app code changes for mTLS, tracing, retries | Adds latency per hop (Envoy proxy adds ~1ms per call) |
| Central control plane for traffic policy | Debugging requires understanding both app and mesh layers |
| Enables zero-trust security by default | Resource overhead — each pod gets an Envoy sidecar consuming CPU/memory |
| Powerful traffic splitting for progressive delivery | Learning curve is steep; misconfigured Istio can silently drop traffic |

**When NOT to use:** Small teams (<5 services) where the operational overhead outweighs the benefit. Start with direct service-to-service calls and add a mesh when you have 20+ services and clear security/observability requirements.

---

### Service Registry — Client-Side Discovery
```
Service registers → Registry (Consul / Eureka)
Client queries registry → gets list of healthy instances → picks one → connects
```

**Use Cases:**
- Microservices that need to find each other dynamically as instances scale up/down
- Multi-datacenter deployments where IP addresses change frequently
- Blue-green or canary deployments where instance sets change

| Pros | Cons |
|---|---|
| Client controls load balancing strategy (round robin, least-conn, zone-aware) | Registry becomes a critical dependency — its failure impacts all services |
| No single proxy bottleneck | Client-side library must be maintained in every language |
| Low latency — direct client-to-server connection | Each client must implement health-check-aware load balancing |
| Works well with gRPC and custom protocols | Registry data can be stale — client may try a recently-dead instance |

---

### Load Balancer — Server-Side Discovery
```
Client → Load Balancer → queries registry internally → routes to instance
```

**Use Cases:**
- HTTP/HTTPS services where clients don't need to know instance details
- Legacy clients that can't run a service discovery library
- When you need centralized traffic control (WAF, DDoS protection)

| Pros | Cons |
|---|---|
| Client simplicity — just call one endpoint | Load balancer is a bottleneck and single point of failure (mitigated with HA LB) |
| Centralized traffic control and visibility | Adds one extra network hop on every request |
| Works for any protocol | LB must scale with total traffic — can become a cost driver |
| Easy to add WAF, rate limiting, SSL termination | Harder to implement client-side strategies like zone-aware routing |

---

### Strangler Fig
Migrate a monolith to microservices **incrementally** without a big bang rewrite.
```
All traffic → Facade/Proxy
                 ↓
    New feature? → New microservice
    Old feature? → Legacy monolith (still works)

Over time: strangle the monolith by migrating routes one by one
```

**Use Cases:**
- Modernizing a legacy payments monolith — migrate one domain at a time
- Extracting a high-traffic module (e.g., pricing engine) to scale independently
- Tech stack migration — run old Java app and new Go service in parallel

| Pros | Cons |
|---|---|
| No big-bang rewrite — lowers risk dramatically | Facade/proxy becomes a long-lived piece of infrastructure to maintain |
| Business continuity — monolith keeps running throughout | Dual maintenance burden during transition — both systems must be kept working |
| Rollback is easy — flip proxy back to monolith | Data synchronization between monolith DB and new service DB is complex |
| Allows incremental team upskilling | Migration can drag on for years if not driven with clear milestones |
| Validates new service under real traffic before full cutover | Increased operational complexity during the transition period |

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

**Use Cases:**
- Financial ledgers — every balance change needs a full audit trail
- Risk systems — replay market events to reconstruct portfolio state at any point in time
- Compliance — regulators require full history of all state changes
- Debugging — replay events to reproduce exact production state that caused a bug

| Pros | Cons |
|---|---|
| Complete audit trail — every change is recorded with who, what, when | Query complexity — reading current state requires replaying or maintaining projections |
| Time travel — reconstruct state at any point in history | Event store grows indefinitely — snapshotting strategy required for performance |
| Enables multiple read projections from the same event stream | Schema evolution of past events is extremely difficult |
| Natural fit with Kafka — events are immutable log entries | Steep learning curve — developers default to thinking in current-state mutations |
| Rebuild any broken projection by replaying from source | High read latency for current state if projection is not maintained |

**When NOT to use:** Simple CRUD applications without audit requirements. The complexity overhead is not justified if you don't need time travel or multiple projections.

---

### CQRS (Command Query Responsibility Segregation)
Separate the write model from the read model.
```
Write path:  Command → validates → writes to event store (normalized)
Read path:   Query → reads from denormalized projection (optimized for query)

Example:
  Write: append transaction event to Kafka
  Read:  pre-aggregated balance in Redis (updated by a consumer)
```

**Use Cases:**
- High read/write ratio — scale read replicas independently of write masters
- Complex aggregations — maintain pre-computed views for dashboards
- Feature store — write raw events, read pre-computed ML features
- Reporting — write normalized transactions, read denormalized reporting tables (Gold layer)

| Pros | Cons |
|---|---|
| Read and write models optimized independently | Eventual consistency — read model lags behind write model |
| Read replicas can scale horizontally without affecting write path | Significantly increases system complexity — two models to maintain |
| Query performance dramatically better on denormalized read models | Developers must understand which operations are commands vs. queries |
| Natural fit with Event Sourcing — events feed read projections | Debugging requires tracing through both write and read paths |
| Enables different storage backends for read vs. write | Synchronization failures between write and read model are hard to detect |

---

### Outbox Pattern
Solve the dual-write problem — atomically write to DB and publish to Kafka.
```
Problem:  Write to DB ✓  →  Publish to Kafka ✗  →  data inconsistency

Solution:
  1. Write to DB + write to outbox table in SAME transaction
  2. CDC (Debezium) reads outbox table → publishes to Kafka
  → Guaranteed: if DB write succeeds, Kafka publish will happen
```

**Use Cases:**
- Payment processing — DB state and event stream must never diverge
- Order management — order created in DB must always emit an event
- Any write that must trigger a downstream event reliably
- Replacing distributed transactions (2PC) between DB and message broker

| Pros | Cons |
|---|---|
| Atomicity between DB write and event publish — no dual-write inconsistency | Requires CDC infrastructure (Debezium) which adds operational complexity |
| Resilient to Kafka downtime — events accumulate in outbox until broker recovers | Outbox table grows and requires cleanup — needs a purge job |
| At-least-once delivery guaranteed | Additional DB write per operation — slight write amplification |
| No distributed transaction (2PC) required | CDC adds latency — event publish is not synchronous with DB commit |
| Works with any relational DB that supports transactions | Outbox table becomes a hot table under high write throughput — needs indexing strategy |

---

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

**Use Cases:**
- Multi-step payment flows — debit sender, credit receiver, update ledger, notify
- Order fulfillment — reserve inventory, charge card, dispatch shipment
- KYC onboarding — verify identity, check sanctions, create account, send welcome email
- Any business process spanning multiple services that needs rollback capability

| Choreography Pros | Choreography Cons |
|---|---|
| Fully decoupled — services don't know about each other | Hard to visualize the overall flow — events are scattered across services |
| No single point of failure | Cyclic dependencies can emerge as sagas grow complex |
| Easy to add new steps by adding a new subscriber | Debugging a failed saga requires tracing events across multiple services |

| Orchestration Pros | Orchestration Cons |
|---|---|
| Single place to see the full saga flow | Orchestrator becomes a central dependency |
| Easier to debug — one service owns the state machine | Orchestrator can become a bottleneck |
| Clear compensating transaction logic | Services are coupled to the orchestrator's API |
| Better for complex, long-running workflows | Orchestrator needs persistent state — adds DB dependency |

---

## 5. Deployment Patterns

### Blue-Green Deployment
```
Blue (current live):  v1 ← 100% traffic
Green (new version):  v2 ← 0% traffic (warming up, tested)

Switch:  Blue ← 0%   Green ← 100%
Rollback: flip back instantly
```

**Use Cases:**
- Zero-downtime deployments for customer-facing APIs
- Major version upgrades where gradual rollout isn't possible
- DB schema changes that require running two compatible app versions simultaneously
- Regulatory deployments with hard cutover requirements

| Pros | Cons |
|---|---|
| Instant rollback — flip the load balancer back | Requires double the infrastructure cost during deployment |
| Zero downtime — traffic switches atomically | DB migrations must be backward compatible (both versions must work with same DB) |
| Full production environment to test green before switching | Warm-up time required — green environment must be fully provisioned and warmed |
| Simple mental model — only two states | No gradual rollout — all users hit new version simultaneously on cutover |

---

### Canary Deployment
```
v1: 95% traffic
v2:  5% traffic  ← monitor error rate, latency, business metrics

Gradually shift: 5% → 20% → 50% → 100%
Auto-rollback if metrics degrade
```

**Use Cases:**
- New ML model rollout — compare prediction quality between old and new model
- Performance-sensitive changes — validate latency improvement at small scale first
- High-risk features — limit blast radius if there's a latent bug
- Infrastructure changes — new DB, cache, or queue configuration

| Pros | Cons |
|---|---|
| Limited blast radius — only N% of users affected by a bug | Two versions running simultaneously — both must be maintained during rollout |
| Data-driven rollout decisions based on real traffic | More complex than blue-green — requires sophisticated traffic splitting |
| Catch performance regressions before full rollout | Session stickiness issues — user may hit v1 then v2 within same session |
| Auto-rollback on metric degradation | Longer deployment cycle — full rollout can take hours or days |
| A/B testing capability built-in | Requires robust monitoring to catch subtle regressions at 5% traffic |

---

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

**Use Cases:**
- Dark launching — deploy new code, test in production with 0 users, then gradually enable
- Kill switch — instantly disable a feature that's causing issues without a deployment
- Beta programs — enable for specific user segments or internal users first
- Trunk-based development — merge incomplete features behind a flag, keep main releasable

| Pros | Cons |
|---|---|
| Instant rollback without deployment — flip the flag | Flag debt — old flags accumulate and become tech debt if not cleaned up |
| Enable/disable per user, segment, or region | Code complexity — branches for every flag make code harder to read |
| Supports trunk-based development at scale | Flags must be tested in both states — doubles QA surface |
| Eliminates long-lived feature branches | Feature flag service becomes a dependency — its downtime affects all features |
| Enables experimentation (A/B tests) at the app layer | Risk of flag interactions — two flags enabled simultaneously can create unexpected behavior |

---

### Immutable Infrastructure
Never patch a running server. Replace it.
```
Bug in container → build new image → deploy new pods → drain old pods
Never: SSH into prod and hotfix
```

**Use Cases:**
- Kubernetes workloads — every deploy is a new pod, never mutated in-place
- Auto-scaling groups — scale out with fresh AMIs, never patch running instances
- Security patches — rebuild image with patched base, redeploy
- Compliance environments — auditors can verify exact image SHA that ran in production

| Pros | Cons |
|---|---|
| No configuration drift — every instance is identical | Longer deployment cycle — build, push, deploy instead of just `apt upgrade` |
| Rollback is trivial — deploy previous image | Requires mature CI/CD pipeline to be practical |
| Full audit trail — every production image has a known SHA and build provenance | Stateful services are hard — data must live outside the container |
| Eliminates "works on my machine" — prod image is reproducible | Cold start latency — new instances need time to pull image and warm up |
| Forces infrastructure-as-code discipline | Higher storage costs — keeping multiple image versions in registry |

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

**Use Cases:**
- Kubernetes pod lifecycle management — auto-restart deadlocked pods
- Rolling deploys — new pod only receives traffic when readiness passes
- Graceful shutdown — mark not-ready to drain traffic before SIGTERM
- Circuit breaker integration — readiness fails when circuit is open

| Pros | Cons |
|---|---|
| Automatic recovery from deadlocks and hangs | Overly aggressive liveness → restart loops under transient load spikes |
| Zero-downtime rolling deploys via readiness gates | Shallow health checks (just returns 200) miss real dependency failures |
| Clear separation of "alive" vs. "ready to serve" concerns | Health check endpoint itself must be lightweight — a slow check is counterproductive |
| Prevents traffic from hitting cold instances | Cascading readiness failures — if DB is slow, all pods fail readiness simultaneously |

---

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

**Use Cases:**
- Debug a specific customer complaint — trace their exact request through all systems
- Performance profiling — trace a slow request and see exactly which hop was slow
- Audit trail — follow a payment event from creation through settlement to notification
- Incident investigation — reconstruct the exact sequence of events that caused a failure

| Pros | Cons |
|---|---|
| End-to-end request visibility across all services | Must be implemented consistently — one service missing propagation breaks the chain |
| Dramatically reduces MTTR for production incidents | Adds a small overhead to every log line and network call |
| Works across async boundaries (Kafka, SQS, async jobs) | Sampling decisions (for traces) can miss the exact request you need to debug |
| Enables distributed tracing tools (Jaeger, Zipkin, Tempo) | PII risk — trace IDs in logs must not leak user-identifiable data |

---

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

**Use Cases:**
- Machine-parseable logs for Kibana/Splunk/CloudWatch Insights querying
- SLA monitoring via log-based metrics (count of `duration_ms > 500`)
- Security audit — query all events for a specific `user_id` across a time range
- Alerting on business-level events (e.g., alert when `event=payment_failed` rate spikes)

| Pros | Cons |
|---|---|
| Machine-parseable — enables powerful log queries as field filters | Verbose — logs are larger than plain text equivalents |
| Consistent format across all services enables unified dashboards | Developers must enforce discipline — free-text logging is a common regression |
| Log-based metrics without additional instrumentation | Sensitive fields (PII, tokens) can accidentally end up in structured log fields |
| Correlates naturally with trace IDs and span IDs | High-cardinality fields (user_id in log fields) can overload log indexing |

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
