# LLD: In-process pub-sub message hub

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
