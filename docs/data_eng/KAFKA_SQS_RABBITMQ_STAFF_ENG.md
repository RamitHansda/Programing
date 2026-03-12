# Kafka vs SQS vs RabbitMQ — Staff Engineer Perspective

---

## First, Reframe the Question

These three are not interchangeable alternatives. Comparing them directly is like comparing a database, a cache, and a message queue. They solve **overlapping but fundamentally different problems**. Picking the wrong one isn't just a performance issue — it shapes your entire system architecture.

---

## The Mental Model

**RabbitMQ** is a **message broker** — it routes messages from producers to consumers using sophisticated exchange/binding rules. It is an intermediary that moves work around.

**SQS** is a **durable queue** — it decouples producers from consumers, buffers load, and ensures at-least-once delivery. It is infrastructure plumbing.

**Kafka** is a **distributed, replicated, ordered log** — it records events and lets any number of consumers read from any point in time. It is a source of truth, not just a pipe.

The distinction matters because:
- RabbitMQ and SQS are about **moving work**
- Kafka is about **recording what happened**

---

## Deep Dive: Where Each Breaks Down

### Kafka — The Sharp Edges

**Consumer lag is your new on-call alert.** Kafka is pull-based, which means if your consumer slows down, messages pile up. You need to actively monitor `consumer_group_lag` or you'll find out in a postmortem.

**Partition count is a forever decision.** You choose partition count upfront. Increasing it later rebalances keys and can break ordering guarantees. Over-partitioning wastes resources; under-partitioning caps your throughput ceiling.

**It's not a task queue.** Kafka has no native concept of "acknowledge and delete." If you want exactly-once task processing with retries, dead-letter queues, and backoff — you're bolting that on yourself. People use Kafka as a task queue and then spend months reinventing what RabbitMQ gives you for free.

**Compacted topics are powerful but tricky.** Log compaction lets you keep only the latest value per key — essentially a changelog. This is how Kafka powers Kafka Streams and materialized views. But compaction runs asynchronously and has edge cases that bite you in production.

---

### SQS — The Sharp Edges

**Visibility timeout is your biggest footgun.** When a consumer picks up a message, it becomes invisible for a configurable window. If processing takes longer than that window, the message becomes visible again and another consumer picks it up — now you have **duplicate processing**. Your consumers must be idempotent, full stop.

**No ordering on Standard queues.** SQS Standard gives you "best effort" ordering, which in practice means assume no ordering. FIFO queues solve this but cap you at 300 TPS per message group (3000 with batching) — which sounds fine until you hit it at 2am on Black Friday.

**Fan-out requires architecture.** SQS is point-to-point. If you need multiple consumers to receive the same message, you need an SNS topic fanning out to multiple SQS queues. This works well but adds operational components and latency.

**No replay.** Once a message is consumed and deleted, it's gone. If you need to reprocess events — a bug fix, a new downstream service, a data migration — you have no history to replay. This is the single biggest architectural limitation.

---

### RabbitMQ — The Sharp Edges

**Memory pressure can kill the broker.** RabbitMQ is memory-hungry. Under high load, it hits a memory alarm and starts blocking publishers. If your queues are not being consumed fast enough, you need flow control configured or the whole system stalls.

**Unacked messages are a silent killer.** If consumers crash without acking, messages sit in an "unacked" state and count against memory. In high-throughput systems without proper prefetch limits set (`basic.qos`), one slow consumer can starve the entire broker.

**Clustering is not trivial.** A single RabbitMQ node is easy. A HA cluster with quorum queues, proper network partitioning handling, and mirrors across AZs is a meaningful operational investment. Most teams underestimate this until they lose messages in a failover.

**No native replay.** Like SQS, once consumed and acked, the message is gone.

---

## The Architectural Trade-off Table That Actually Matters

| Concern | Kafka | SQS | RabbitMQ |
|---|---|---|---|
| **Replay / reprocessing** | Native, powerful | Not possible | Not possible |
| **Multiple independent consumers** | Native (consumer groups) | Requires SNS fan-out | Fanout exchange |
| **Exactly-once delivery** | Possible (idempotent producer + transactions) | At-least-once (FIFO helps) | At-least-once |
| **Operational burden** | High (self-hosted), Medium (MSK/Confluent) | Near-zero (fully managed) | Medium |
| **Message TTL / expiry** | Retention-based (whole topic) | Per-message TTL | Per-message or per-queue TTL |
| **Backpressure handling** | Consumer controls pace (pull) | Visibility timeout tuning | Flow control, `basic.qos` prefetch |
| **Dead letter handling** | Manual (separate topic) | Native DLQ | Native DLX (dead letter exchange) |
| **Schema evolution** | Schema Registry (Confluent) | DIY | DIY |
| **Throughput ceiling** | Millions/sec | Hundreds of thousands/sec | Tens of thousands/sec |

---

## How to Make the Call in Practice

### Use Kafka when:
- You need an event log that multiple teams/services can consume independently
- You need replay — for new services catching up, ML pipelines, debugging
- You're building event sourcing or CQRS
- Throughput is > 50k messages/sec sustained
- You're building a data platform (CDC, stream processing with Kafka Streams or Flink)

### Use SQS when:
- You're AWS-native and want zero ops overhead
- It's a background job queue — image resizing, emails, webhooks
- Your team is small and operational simplicity beats everything else
- You can make consumers idempotent (you should anyway)

### Use RabbitMQ when:
- You need sophisticated routing — route by message type, content, headers
- You need request/reply patterns (RPC over messaging)
- You're integrating with systems that speak AMQP natively
- You want push-based delivery with fine-grained consumer control

---

## The Hybrid Pattern (What Production Actually Looks Like)

Most mature systems use more than one:

```
User Action
    │
    ▼
[Kafka] ──── event log, audit trail, fan-out to multiple teams
    │
    ├──► [SQS] ──── background jobs, async tasks per microservice
    │
    └──► [RabbitMQ] ──── internal workflow routing, legacy integrations
```

Kafka owns the **event backbone**. SQS/RabbitMQ handle **task execution** within bounded contexts.

---

## The One Question to Ask Before Choosing

> **"Does any consumer need to read this message independently, at their own pace, including in the future?"**

- If **yes** → Kafka. Everything else is a workaround.
- If **no, just deliver the work once** → SQS (if AWS) or RabbitMQ (if you need routing/push).
