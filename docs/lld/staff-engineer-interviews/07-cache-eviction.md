# LLD: In-process cache with pluggable eviction

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
