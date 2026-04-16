# LLD: In-process cache with pluggable eviction

## Interview-ready snapshot

**Say first (≈30s):** Bounded `Cache` with **`EvictionPolicy` strategy**; classic LRU = map + DLL but be honest about **strict vs approximate** concurrency; optional **Decorator** for metrics/TTL.

**Default assumptions:** Thread-safe; strong per-key consistency unless they accept relaxed LRU.

| Phase | ~Time | Deliver |
|-------|------|---------|
| Align | 5 min | LRU definition (read/write touch); TTL semantics; null cache policy. |
| Model | 10 min | Entry, policy interface, cache aggregate, optional decorator. |
| API + flow | 7 min | get/put/invalidate; put triggers evict until under capacity. |
| Hard | 15 min | Locking / striping / CHM + hand-over-hand; honesty on complexity. |
| Close | 3 min | W-TinyLFU or off-heap as “production follow-on.” |

**Whiteboard order:** (1) Cache interface (2) entry + links for LRU (3) eviction victim pick (4) concurrency approach (5) invariants.

**Likely probes:** `computeIfAbsent` and eviction interleaving? Iterator consistency?

**30s closer:** Eviction is a strategy; cache owns structure; document concurrency approximation if you choose it.

---

## Interview prompt

Design a **thread-safe in-memory cache** with max capacity and eviction policy (LRU, LFU, TTL). Support `get`, `put`, `invalidate`.

## Clarifying questions

- **LRU definition**: recency by read, write, or both?
- **TTL**: per-entry or global?
- **Consistency**: strong per-key atomicity vs eventual for stats?
- **Null values**: cache “negative cache” or forbid nulls?

## Functional requirements

- Bounded size; on `put`, evict if needed.
- `get` updates recency if LRU.
- Optional: `computeIfAbsent` with loader (define loader exception policy).

## Non-functional requirements

- **O(1)** expected operations for interview-grade LRU (hash map + doubly linked list).
- **Thread safety** without global lock if possible (`ReentrantReadWriteLock` per segment or `ConcurrentHashMap` + ordered structure with careful locking).

## Domain model

| Kind | Type | Responsibility |
|------|------|----------------|
| **Aggregate (conceptual)** | `Cache` + internal store | Bounded set of entries; enforces capacity as an invariant. |
| **Entity** | `CacheEntry<K,V>` | Key, value, metadata (expiry instant, frequency counters for LFU). |
| **Value object** | `CacheKey`, optional `Ttl` | Typed keys; TTL policy per entry if supported. |
| **Strategy** | `EvictionPolicy` | Decides victim order; updates recency/frequency on access/insert. |
| **Decorator** | `InstrumentedCache` | Metrics without changing eviction semantics. |

**Relationships:** one cache **indexes** many `CacheEntry`; eviction policy **reads** structure (list/tree) owned by cache implementation.

**Not modeled:** Redis protocol, disk spill (unless extended).

## Design patterns

| Pattern | Role |
|--------|------|
| **Strategy** | `EvictionPolicy` coordinates touches and eviction choice. |
| **Decorator** | `MeteredCache` adds metrics/TTL wrapping a `Cache` interface. |
| **Template Method** (light) | Shared `put` skeleton: normalize key → store → postTouch → evictUntilOk. |

## Staff-level note on LRU under concurrency

Interview honesty beats fake O(1):

- True strict LRU with high concurrency often uses **segmentation** or accepts **approximate** LRU.
- State your choice: “strict per-key mutex” vs “sharded locks”.

## Invariants

- Size never exceeds capacity (unless you allow temporary overshoot during computation—then define).
- Eviction count matches inserted volume minus removals.

## Java interfaces

```java
public interface Cache<K, V> {
    Optional<V> get(K key);
    void put(K key, V value);
    void invalidate(K key);
}

public interface EvictionPolicy<K> {
    void onAccess(K key);
    void onInsert(K key);
    Optional<K> evictCandidate();
}
```

## Testing strategy

- Deterministic policy tests with a fake clock for TTL.
- Concurrency soak: no lost entries beyond defined semantics.

## Follow-ups

- **W-TinyLFU** windowing (conceptual), off-heap caches, stamped expiration on read.
