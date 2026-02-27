# Message Broker (reusable library)

A small, in-process message broker with **topics** (publish-subscribe) and **queues** (point-to-point). No external dependencies; suitable for in-app event distribution and task queues.

## Concepts

| Concept | Description |
|--------|-------------|
| **Message** | Immutable envelope: `id`, `payload`, optional `headers`. |
| **Topic** | Pub/sub: every message is delivered to all subscribers. |
| **Queue** | Point-to-point: each message is consumed by exactly one consumer (competing consumers). |
| **Broker** | Creates and holds named topics and queues. |

## Usage

```java
MessageBroker broker = new InMemoryMessageBroker();

// Publish-Subscribe
Topic<String> events = broker.topic("events");
events.subscribe(msg -> System.out.println("Got: " + msg.getPayload()));
events.publish("Hello");

// Point-to-Point (unbounded queue)
Queue<String> tasks = broker.queue("tasks");
tasks.enqueue("task-1");
Message<String> msg = tasks.dequeue();

// Bounded queue (backpressure)
BoundedQueue<String> limited = ((InMemoryMessageBroker) broker).queue("limited", 100);
limited.tryEnqueue(new Message<>("item")); // false if full
```

## Design notes

- **Reusable**: `MessageBroker`, `Topic`, `Queue` are interfaces; you can add persistent or distributed implementations.
- **Thread-safe**: In-memory implementation uses concurrent data structures; safe for multi-threaded producers and consumers.
- **No persistence**: In-memory only; process exit loses messages. Extend with a `PersistentMessageBroker` if needed.

## Run demo

```bash
mvn compile exec:java -Dexec.mainClass="lld.messagebroker.MessageBrokerDemo"
```
