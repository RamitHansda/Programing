# LLD: Rate limiter (per-tenant / per-key)

## Interview-ready snapshot

**Say first (≈30s):** Per-key limiter behind `tryAcquire`; **Strategy** for token bucket vs window algorithms; **immutable policy** + **mutable per-key state** in a concurrent map. Monotonic clock unless they want wall time.

**Default assumptions:** Single JVM, thread-safe hot path; bounded or evictable key set if they care about memory attacks.

| Phase | ~Time | Deliver |
|-------|------|---------|
| Align | 5 min | Distributed or not; exact vs approximate sliding window; retry-after required or not. |
| Model | 8 min | `RateLimitAlgorithm`, config VO, `KeyState`, orchestrating service, map ownership. |
| API + flow | 7 min | Interfaces + one walkthrough: lookup → algorithm updates state → Decision. |
| Hard | 12 min | Per-key atomicity, CAS vs lock; key explosion; fail-open vs fail-closed. |
| Close | 3 min | Burst vs smoothness tradeoff; Redis/cell limits as HLD follow-on. |

**Whiteboard order:** (1) key → state (2) `RateLimitAlgorithm` (3) `tryAcquire` sequence (4) `ConcurrentHashMap` + concurrency note (5) optional metrics.

**Likely probes:** Singleton? (Prefer DI.) Sliding window under contention? Retry-after formula?

**30s closer:** Policies plug in as strategies; service owns lifecycle of map entries and observability; clock injectable for tests.

---

## Interview prompt (typical)

Design an in-process **rate limiter** used by an API gateway or service mesh sidecar. Support multiple algorithms (token bucket, fixed window, sliding window). Keys are strings (tenant id, user id, IP). The API must answer: **“Is this request allowed right now?”** and optionally **“When can I retry?”**

## Clarifying questions (ask first)

- **Scope**: single JVM only, or assume distributed limiter later? (If distributed, this LLD becomes “local enforcement + shared counter”—call that out.)
- **Clock**: wall clock vs monotonic clock for refill (prefer **monotonic** for correctness under NTP skew).
- **Fairness**: strict ordering per key or best-effort?
- **Memory**: unbounded keys vs LRU map of buckets?

## Functional requirements

- `tryAcquire(key, cost)` returns allowed/denied (and optionally retry-after).
- Pluggable **algorithm per key** or per **policy profile** (interviewer-dependent).
- Configurable **capacity**, **refill rate**, **window size**.

## Non-functional requirements

- **Thread-safe** concurrent access.
- **Low allocation** on hot path (avoid boxing churn if possible).
- **Observable**: counts for allowed/denied, per-algorithm.

## Domain model

| Kind | Types | Notes |
|------|--------|------|
| **Value object** | `RateLimitKey` (or `String`), `Decision`, algorithm **config** (capacity, refill rate, window) | Config is immutable after build; no shared mutable policy. |
| **Mutable per-key state** | `BucketState` / `WindowCounters` (algorithm-specific) | Owned inside a concurrent map entry; identity = key string. |
| **Policy** | `LimitPolicy` = key matcher + which `RateLimitAlgorithm` + config | Optional if all keys share one policy. |
| **Application service** | `RateLimiter` / `RateLimiterService` | Orchestrates lookup, `tryAcquire`, metrics—not the algorithm itself. |
| **Strategy** | `RateLimitAlgorithm` | Pure decision + how to update **that key’s** state given `now`. |

**Relationships:** many keys → many `KeyState` rows; one `RateLimiterService` → many algorithms (if multi-policy). **Not** in the domain: HTTP layer, Redis (unless you extend scope).

## Core invariants

- For a given key and algorithm parameters, **admitted volume** over any window must not exceed policy (within defined approximation for sliding windows).
- **No negative tokens** (token bucket).
- Algorithm objects must not **mutate shared policy** after construction (immutable config).

## Design patterns (where they matter)

| Pattern | Role |
|--------|------|
| **Strategy** | `RateLimitAlgorithm` encapsulates token bucket vs window logic; the service delegates without `switch`. |
| **Template Method** (optional) | Shared skeleton: resolve state for key → delegate policy → record metrics; subclasses only vary “compute decision”. |
| **Flyweight-ish map** | Per-key mutable **state** lives in `ConcurrentHashMap`; policy objects are shared/immutable. |

Avoid **Singleton** for the limiter service in tests; use **dependency injection** of a single instance per process instead.

## Java shape (interfaces)

```java
public interface RateLimitAlgorithm {
    /** Monotonic nanos preferred. */
    Decision tryAcquire(KeyState state, long nowNanos, int cost);
}

public record Decision(boolean allowed, long retryAfterNanos) {}

public interface MutableKeyState {
    // algorithm-specific fields live in implementation classes
}
```

Concrete algorithms own **how** to update state; the outer service owns **where** state is stored (map shard, etc.).

## Concurrency model (staff answer)

- `ConcurrentHashMap<String, KeyState>` with **atomic per-key** updates, or striping if state is large.
- Prefer **compare-and-swap loops** on primitive fields inside a small `KeyState` over synchronizing on the whole map.
- Document **approximate** behavior under contention (sliding window often becomes “bucketed sliding” in production).

## Failure modes

- **Key explosion** (attack): cap map size + eviction (LRU) + default deny or fail-open policy—**state explicitly**.
- **Clock jumps**: monotonic time avoids most issues; document interaction with distributed limiters.

## Testing strategy

- **Deterministic time**: inject `LongSupplier` for `nowNanos`.
- Property-style tests: for token bucket, admitted requests over interval ≤ capacity + refill bound.
- Concurrency stress: many threads, single key, no overshoot beyond epsilon.

## Follow-ups

- Sharded limiter (hash ring), Redis cell-based limits, **leaky bucket** vs token bucket UX.
- **Priority** queues for fairness across tenants.

## Repo tie-in

If you want a working reference, see `src/main/java/interview/fampay/ratelimiter/TokenBucketRateLimiter.java` in this workspace and compare it mentally to the Strategy split above.
