# LLD: In-process pub-sub message hub

## Interview-ready snapshot

**Say first (≈30s):** **`Topic`** owns subscriber set; **`publish`** fans out; **`DispatchPolicy` strategy** for sync vs executor; **subscriber errors isolated**; reentrancy policy stated upfront.

**Default assumptions:** In-process; ordering best-effort per topic unless they require serial dispatch.

| Phase | ~Time | Deliver |
|-------|------|---------|
| Align | 5 min | Sync vs async; error policy; wildcard topics. |
| Model | 8 min | Topic, Message, Subscriber, Subscription handle, Bus façade. |
| API + flow | 8 min | subscribe → publish diagram; unsubscribe. |
| Hard | 15 min | Copy-on-write subscriber list vs locks; reentrant publish; slow subscriber. |
| Close | 5 min | Durable log / Kafka comparison as HLD. |

**Whiteboard order:** (1) Topic box (2) subscriber list (3) publish loop (4) error boundary (5) dispatch policy.

**Likely probes:** Backpressure? Memory if subscribers never unsubscribe?

**30s closer:** Observer core; bus mediates policy; strategy swaps dispatch without rewriting topic.

---

## Interview prompt

Design a **publish–subscribe** bus: topics, subscribers, fan-out delivery, optional filtering.

## Clarifying questions

- **Delivery model**: sync dispatch in calling thread vs executor per subscriber?
- **Ordering**: per-topic total order or best-effort?
- **Subscriber errors**: fail-fast vs isolate?

## Functional requirements

- `subscribe(topic, Subscriber)` returns unsubscribe handle.
- `publish(topic, message)` delivers to all matching subscribers.
- Optional: wildcard topics (`orders.*`).

## Non-functional requirements

- **Subscriber isolation** (one slow subscriber must not block others if async is promised).
- **Reentrancy**: publishing from within subscriber—define deadlock policy (often “same-thread nested OK, log otherwise”).

## Domain model

| Kind | Type | Responsibility |
|------|------|----------------|
| **Aggregate root** | `Topic<T>` | Name, subscriber registry, optional wildcard matcher; owns subscribe/unsubscribe invariants. |
| **Value object** | `Message<T>` | Payload + metadata (topic name, publish time, correlation id). |
| **Port** | `Subscriber<T>` | Callback contract for consumers. |
| **Facade** | `MessageBus` | Topic registry, `publish` routing, error/dispatch policy. |
| **Handle** | `Subscription` | Disposable registration (id for unsubscribe). |

**Relationships:** `Topic` **has many** `Subscriber`s; `MessageBus` **has many** `Topic`s.

**Not modeled:** durable log, consumer groups, partition leadership (Kafka-style)—call out as HLD/infra extension.

## Design patterns

| Pattern | Role |
|--------|------|
| **Observer** | Core pattern: topic maintains subscriber list. |
| **Mediator** | `MessageBus` coordinates routing and error policy. |
| **Strategy** | `DispatchPolicy` (sync, per-subscriber executor, serial queue per topic). |
| **Adapter** | Bridge external event streams into `Subscriber`. |

## Staff-level structure

- `Topic` is an aggregate with subscriber registry.
- `Bus` is a façade for topic creation and publish routing.
- Keep **subscription mutations** thread-safe vs **publish** (copy-on-write subscriber lists are common for read-heavy fan-out).

## Java sketch

```java
public interface Subscriber<T> {
    void onMessage(Message<T> msg);
}

public final class Topic<T> {
    public Subscription subscribe(Subscriber<T> s) { /* */ }
    public void publish(T payload) { /* fan-out */ }
}
```

## Failure modes

- Subscriber throws: **catch, metric, continue** (default) vs fail-fast—state explicitly.
- Memory leaks: unsubscribe must be reliable; weak references rarely needed if handles are explicit.

## Testing strategy

- Deterministic sync bus tests for ordering.
- Isolation test: throwing subscriber does not prevent others from receiving.

## Follow-ups

- **Dead letter** channel, backpressure (bounded queues), metrics per topic.
