# GS Superday — Software Design and Architecture (day of)

**Panel:** Pradeep & Ralph · 8 Sept 2026 · 5:00–6:00 PM IST · 60 min · CoderPad  
**Competency they are scoring:** how you **decompose software**, pick **components / APIs / data stores**, reason about **concurrency and failure**, and defend **trade-offs**.  
**Not this round:** LeetCode DSA, Agile/CI/CD, Spark internals, Iceberg trivia. Those leak in only as colour if *they* steer there.

You already have long LLD notes: rate limiter `docs/lld/staff-engineer-interviews/01-rate-limiter.md`, LRU `07-cache-eviction.md`, parking lot `02-parking-lot.md`, notifications `09-notification-dispatcher.md`, pub-sub `14-pub-sub-message-hub.md`, checkout `13-shopping-cart-checkout.md`. This sheet is what to **say**.

---

## 1. What this competency is

Goldman names this round **Software Design and Architecture**. Candidate reports (2024–2026) show the same shape every time:

| Block | What they do |
|---|---|
| 5–10 min | Resume **HLD** of one system: boxes, APIs, DB, what broke, how you scaled it |
| 10–15 min | **Java concurrency** warm-up *or* a small CoderPad LLD (rate limiter / LRU) |
| 25–35 min | **One design**: URL shortener, notifications, WhatsApp delivery, e-commerce, billing, or a “stream → derived state → many consumers” system |
| Last 5–10 | Trade-off pile-on: Kafka vs Redis, cache invalidation, LB, SQL vs NoSQL, “how do you know it’s healthy?” |

**VP bar:** name the consistency boundary, the failure mode, and one number. Do not dump a lakehouse architecture unless they ask for a data platform.

If they *do* ask “design a data platform,” use medallion + Kafka + Iceberg + recon from the longer note. Default plan is **generic software design**.

---

## 2. Clock (say this in your head)

1. **90s clarify** — functional, then non-functional (QPS, latency, consistency, durability). State 2 assumptions.
2. **API + data model** — 3 endpoints or 5 classes. Grain of the core record.
3. **Happy path** through the boxes.
4. **Hard part they will pick** — concurrency, ordering, cache, queue, idempotency.
5. **Failure + ops** — duplicate, node death, poison message, metrics.

Opening line:

> I’ll treat this as a production service: APIs, storage, and failure modes. I’ll start single-region, then scale. Correctness and audit beat availability on any money or risk path.

---

## 3. Resume HLD (they almost always open here)

Pick **one**. Five minutes. Then stop talking and let them drill.

### A. Goldman market-risk aggregation (primary)

> Stream of market and position updates. We maintain derived state — VaR, PnL, stress — and several consumers want different views with different freshness. Compute was multi-terabyte in-memory, sharded. Reads had to be consistent enough that a desk didn’t see a half-updated book.
>
> **Boxes:** ingest → partitioned compute cluster → in-memory shards → query API / batch writers. Durable log for replay. Cache on the hot read path.
>
> **What broke:** under load, VaR went stale. Three interacting causes — shard hotspot, GC pauses looking like timeouts, and a freshness bug on the read path. Fix was observability first (trace the stale number to a shard), then rebalance + GC + freshness watermark. P99 came down; failover recovery improved about forty percent.
>
> **Design lesson I’d reuse:** derived state from a stream needs an explicit consistency story, a replay story, and metrics on *freshness*, not just CPU.

If they ask “SQL or KV?” — shard state is KV/in-memory; official numbers and audit land in a durable store. Don’t mix those jobs.

### B. Skydo payments (if they want a write-path / money system)

> Money-moving intent is persisted with an **idempotency key** before any side effect. State machine: created → pending → succeeded/failed. Partner calls are not in our DB transaction, so we lock the critical section, verify partner status on retry, and **reconciliation** closes silent partner success. Kafka/async for non-critical fan-out. About 10K+ international txns/day.

Drill answers: never claim magic exactly-once; it is at-least-once + idempotent sink. Locks don’t replace idempotency.

---

## 4. Java / concurrency warm-up (first 10 minutes)

They have opened Design rounds with threads. Have these cold.

| Prompt | Say |
|---|---|
| Thread vs Runnable vs Callable | Thread is the worker. Runnable = fire-and-forget. Callable = returns a value, can throw, used with `Future`. |
| Why a thread pool | Creating threads is expensive; bound concurrency; isolate pools (IO vs CPU) so a stuck IO pool doesn’t starve compute. `ThreadPoolExecutor`: core, max, queue, rejection policy. |
| synchronized vs concurrent collections | `synchronized` = one monitor, simple, coarse. `ConcurrentHashMap` = striped, better under read-heavy. Don’t iterate a HashMap from many threads. |
| volatile vs AtomicInteger | volatile = visibility, not atomic increment. Use `Atomic*` or a lock for counters. |
| Deadlock | Two locks, opposite order. Fix: lock ordering, tryLock with timeout, fewer locks, confine mutation to one thread per key. |
| Shallow vs deep copy | Shallow copies references; mutating nested objects races. Deep copy for snapshots you publish. Immutability (Builder → final fields) is the better default. |
| Builder + threads | Builder is **not** thread-safe while mutating. Publish an **immutable** product (`final` fields, no setters). Share the product, not the builder. Optionally `ThreadLocal<Builder>` or build in one thread. |
| Microservices vs monolith | Split on **consistency boundary** and independent deploy, not on “one class one service.” A service with no data and no API is not a microservice. |
| API gateway vs load balancer | LB spreads TCP/HTTP. Gateway is the policy edge: auth, rate limit, routing. You can skip a gateway; you rarely skip LB once you have >1 instance. |

---

## 5. Spoken designs (highest hit-rate for this competency)

### 5.1 Rate limiter — **most reported SDA question**

**Clarify:** per user / IP / API key? Strict or approximate? Single process or fleet?

**Why not only client-side:** clients lie, clocks skew, you cannot enforce. Client-side is UX (debounce). Enforcement is gateway / sidecar / service. Optional extra client limiter to reduce 429s.

**Algorithms (pick one, name the rest):**

| Algo | Good for | Weakness |
|---|---|---|
| Fixed window | Simple OTP (“3 tries / 5 min”) | Burst at window edge (3 at 4:59 + 3 at 5:00) |
| Sliding window log | Exact | Memory: store every timestamp |
| Sliding window counter | Approx, cheap | Two buckets, weighted |
| Token bucket | Bursts + sustained rate | Need atomic refill |
| Leaky bucket | Smooth outflow | Bursts delayed, not absorbed |

**HLD:** Client → API Gateway → limiter (Redis + Lua for atomic INCR/ZADD) → service. Key = `userId + route`. On deny: HTTP 429 + `Retry-After`. Fail **closed** on money/OTP; fail **open** with alarm on a public read API if Redis is down (state the choice).

**OTP variant (3 / 5 min):** sliding log in Redis sorted set, score = timestamp, drop older than 5 min, `ZCARD`, if < 3 add now. Or token bucket with capacity 3, refill 3/5min.

**CoderPad (sliding log, single JVM):** see §8. You have a production-shaped token bucket in `src/main/java/interview/fampay/ratelimiter/`.

**VP close:** distributed limiter needs a shared store; per-node memory counters are a load-balancer leak (sticky sessions still split).

---

### 5.2 LRU cache — **CoderPad LLD**

**Say first:** HashMap key → node, doubly linked list for recency, capacity N, `get`/`put` O(1). `get` moves to front. `put` on existing updates + moves. On full, evict tail.

**Thread safety:** `synchronized` on the cache for the interview (you already did this in `src/main/java/lld/lrucache/LRUCache.java`). Production: striped locks or Caffeine (approx LRU). Strict LRU + high concurrency is expensive; say so.

**HLD follow-up:** this is **one node**. Distributed cache = Redis + TTL; this LLD is the local hot layer. Invalidation: TTL, pub-sub on writes, or version in the key.

---

### 5.3 URL shortener — **reported as talk-only, no diagrams**

**APIs:** `POST /urls {longUrl}` → `{code}`; `GET /{code}` → 302 to long URL.

**ID:** 7-char base62 ≈ 3.5e12 codes. Prefer **pre-allocated ranges** (ZK/DB counter per instance) or hash + collision retry. Don’t MD5 the URL as the only ID (collisions, can’t have two shorts for one long).

**Store:** KV `code → longUrl` (Cassandra/Dynamo/Redis+disk). SQL for users, analytics, custom aliases (unique index).

**Read path is 100× write:** CDN + Redis in front of KV. 301 if immutable; 302 if you need click counts (browser won’t cache).

**Scale:** partition by code prefix. Rate-limit `POST` (abuse). Validate URL (SSRF).

**Trade-off they want:** uniqueness vs coordination. Range allocation = no cross-node lock on every write.

---

### 5.4 Notification service — **VP Bangalore offer loop**

**Clarify:** channels (email/SMS/push/in-app)? At-least-once? Ordering per user? Offline?

**HLD:** API `POST /notify` → persist **Notification** (id, user, channel, payload, idempotency key, status) → Kafka topic `notify.{channel}` partitioned by `userId` → workers → provider adapters. Preferences service (quiet hours, opt-out). DLQ + retry with backoff. Status store for “did it send.”

**Offline:** in-app/push: durable inbox; device syncs on reconnect (see WhatsApp). Email/SMS: provider handles; we retry 4xx/5xx with policy.

**Isolation:** Twilio down must not block email (separate topics / pools).

**DB:** SQL for notification records + audit (you will be asked). Don’t put the hot fan-out in Postgres.

---

### 5.5 WhatsApp-style sequential delivery — **Kafka vs Redis**

**Invariant:** per chat (or per recipient), messages appear in send order after reconnect.

**Design:** each message durable with `(chatId, seq)`. Partition Kafka by `chatId` so one partition = total order. Consumer for that user’s **online session** pushes. If offline, don’t ack the push — **inbox table** (`userId, seq, payload`) is the source of truth; on reconnect, `SELECT … WHERE seq > lastAck ORDER BY seq`.

**Kafka vs Redis Streams:**

| | Redis Streams | Kafka |
|---|---|---|
| Latency | Lower, in-memory | Higher, disk log |
| Durability / retention | Minutes–hours unless AOF tuned | Days, replay, compliance |
| Fan-out | Small consumer set | Consumer groups, many teams |
| Ordering | Per stream | Per partition |

**Say:** Redis for presence + last-mile fan-out; Kafka (or a durable inbox DB) for the log you must not lose. GS will like “I won’t use Redis as the system of record for messages.”

---

### 5.6 Parking lot LLD — **often Practices, still shows up in Design**

**Types:** Vehicle (car/bike/bus), Spot (size), Ticket, Lot (floors).  
**API:** `park(vehicle) → ticket`, `leave(ticket) → fee`.  
**Invariant:** one vehicle one spot; find first matching size (Strategy: nearest vs any).  
**Concurrency:** lock per floor or `ConcurrentHashMap` of spots; don’t lock the whole lot if they push scale.  
**Fee:** Strategy by vehicle type × hours.  
Don’t over-build EV/charging unless they ask.

---

### 5.7 E-commerce checkout — **classes + schema + races**

**Objects:** Cart (line items + **price snapshot**), CheckoutSession, Order (immutable), Payment port, Inventory port.  
**Idempotency:** `Idempotency-Key` on `placeOrder`; same key returns the same Order.  
**Races:** double-submit, stock steal, price change mid-checkout. Freeze prices at checkout; reserve stock with TTL; payment then capture; compensating release on fail (saga).  
**DB:** SQL for orders/payments (ACID); Redis for cart if they want scale. Money = `BigDecimal` / longs, **never float**.

---

### 5.8 “Stream in, derived state, many consumers” — **Equities Design shape**

If they don’t name a product, this *is* the round.

> Inputs on a log. Maintain materialized views. Consumers: some need strong consistency (risk number), some can lag (dashboard).
>
> Log = Kafka, key = entity id (instrument / account) so updates for one entity stay ordered. Processor is idempotent (`eventId`). State store = RocksDB / in-memory shard + snapshot to durable store. Consumers **pull** for replayable APIs; **push** (WS/SSE) for UIs.
>
> Volume doubles → more partitions + more processor instances; watch hot keys (one ticker) — salt or isolate.
>
> Node dies mid-write → at-least-once replay + idempotent apply; don’t take a dual-write without an outbox.

This is your VaR system without saying “lakehouse.”

---

## 6. Fundamentals pile-on (they ask these after the design)

**Load balancer:** L4 vs L7; round-robin vs least-conn vs consistent hashing (sticky sessions, caches). Health checks. Don’t put business logic here.

**Horizontal scale:** stateless app + shared store. Shard by a key with even cardinality. Cache before you shard.

**Caching:** cache-aside default. Write-through if read-after-write must see new data. Write-behind = latency, risk of loss — not for money. Invalidation is the hard part: TTL + explicit delete on write + versioned keys.

**DB:** SQL when you have relations, transactions, audit (orders, payments, notifications status). KV when you have huge point lookups (short URL, session). Time-series for metrics. Don’t put an order book in a document DB “because JSON.”

**Kafka vs Rabbit vs Redis:** Kafka = durable log, replay, high throughput, order per partition. Rabbit = task queues, routing, shorter retention. Redis = cache, locks, presence, *not* your ledger.

**Observability:** QPS, latency p99, error rate, queue lag, cache hit ratio, **freshness** of derived data, saturation (pool threads, heap). Alert on lag and poison, not only 5xx.

---

## 7. Design patterns (they asked these in SDA)

Have one sentence + one GS-relevant example.

| Pattern | When you say it |
|---|---|
| Strategy | Rate-limit algorithm, fee calc, notification channel |
| Builder | Immutable config / order snapshot; don’t share a mutating builder |
| Adapter | Payment provider, SMS provider |
| Observer / pub-sub | In-process hub; Kafka is the distributed version |
| Saga | Checkout: payment + inventory, compensating cancel |
| Outbox | DB write + Kafka event without dual-write loss |
| Circuit breaker | Downstream risk/partner call — fail closed |

SOLID one-liners: S = one reason to change (don’t mix parking fee into Vehicle). O = add a channel without editing dispatcher. L = don’t make Square extend Rectangle if invariants break. I = don’t force SMS adapter to implement email. D = depend on `PaymentPort`, not Stripe SDK.

---

## 8. CoderPad skeletons (type from memory)

### Sliding window (OTP: max `limit` in `windowMs`)

```java
class SlidingWindowLimiter {
    private final int limit;
    private final long windowMs;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    SlidingWindowLimiter(int limit, long windowMs) {
        this.limit = limit;
        this.windowMs = windowMs;
    }

    boolean allow(String key) {
        long now = System.currentTimeMillis();
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() >= windowMs) q.pollFirst();
            if (q.size() >= limit) return false;
            q.addLast(now);
            return true;
        }
    }
}
```

### LRU (same idea as `lld.lrucache.LRUCache`)

```java
class LRUCache<K, V> {
    private final int cap;
    private final Map<K, Node<K, V>> map = new HashMap<>();
    private final Node<K, V> head = new Node<>(null, null), tail = new Node<>(null, null);
    LRUCache(int cap) { this.cap = cap; head.next = tail; tail.prev = head; }
    synchronized V get(K k) {
        Node<K, V> n = map.get(k);
        if (n == null) return null;
        moveToHead(n);
        return n.val;
    }
    synchronized void put(K k, V v) {
        Node<K, V> n = map.get(k);
        if (n != null) { n.val = v; moveToHead(n); return; }
        if (map.size() == cap) {
            Node<K, V> lru = tail.prev;
            remove(lru);
            map.remove(lru.key);
        }
        Node<K, V> created = new Node<>(k, v);
        map.put(k, created);
        addAfterHead(created);
    }
    // Node + addAfterHead / remove / moveToHead
}
```

### Product of last k in a stream (O(1) — they discussed only)

Prefix products; if zeros appear, the product of last k is 0 if a zero sits in the window. Store last k values in a ring buffer, or prefix product and reset after zero. Mention the zero case out loud.

---

## 9. Phrases that score vs die

**Score**

- “Consistency boundary is this aggregate / this Kafka partition.”
- “Idempotency key on the write; at-least-once on the bus.”
- “Fail closed on OTP, payments, risk. Fail open with an alarm on a public GET if the limiter store is down — I’ll pick one and stick to it.”
- “I’d instrument freshness and lag, not just CPU.”
- “Money is BigDecimal / long; floats are a design bug.”

**Die**

- Eventual consistency on a ledger “because CAP.”
- Client-only rate limiting as the whole answer.
- Redis as source of truth for messages or money.
- Drawing 15 microservices in 10 minutes with no data model.
- Talking Spark partitions when they asked you to design a URL shortener.

---

## 10. Practice tomorrow afternoon (90 minutes)

Out loud, CoderPad open:

1. Resume HLD (Goldman) — 5 min, then answer “what if volume doubles / node dies.”
2. Rate limiter talk + type sliding window — 20 min.
3. URL shortener trade-offs, no drawing — 15 min.
4. LRU typed from scratch — 15 min.
5. Notifications + Kafka vs Redis — 15 min.
6. ThreadPool / deadlock / Builder immutability — 10 min.

If extra time: parking lot classes, checkout idempotency.

Lakehouse / Iceberg remains backup in `docs/interviews/GS-VP-LAKEHOUSE-SOFTWARE-DESIGN-ARCHITECTURE.md` **only if they re-scope the prompt to a data platform**.
