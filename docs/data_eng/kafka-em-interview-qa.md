# Kafka Fundamentals — EM Interview Q&A

Answers framed for Engineering Manager interviews: technical depth + ownership, trade-offs, and team/ops.

---

## 1. Core Concepts

### What is Apache Kafka, and how would you describe it to a non-technical stakeholder?

**EM answer:**  
“Kafka is a distributed system that lets our services pass events or messages to each other in real time, without calling each other directly. Think of it as a highly reliable, ordered pipeline: one service publishes ‘something happened’ (e.g. order placed, user signed up), and other services read and react. As an EM, I care that it gives us loose coupling, replay capability, and the ability to scale consumers independently. I’d explain the business value: faster feature delivery, fewer cascading failures, and a single source of truth for event history.”

---

### How does Kafka differ from traditional message queues (e.g., RabbitMQ, SQS)?

**EM answer:**  
“Traditional queues are often *message-in, message-out*: once consumed, the message is gone. Kafka is a *log*: messages stay for a retention period, and multiple consumers can read the same stream at their own pace. That gives us replay, new consumers joining later, and analytics on historical data. Trade-off: we own more storage and operational complexity. As an EM, I’d choose Kafka when we need event streaming, replay, or multiple consumers; I’d choose a queue when we need simple task distribution and don’t need to re-read history.”

---

### What is the role of a broker in Kafka?

**EM answer:**  
“Brokers are the servers that hold the data. They store topic partitions, serve reads and writes, and handle replication. As an EM, I care that broker count and disk/network capacity drive our throughput and durability. I’d ensure we have runbooks for broker failures, replication, and capacity planning so the team isn’t firefighting.”

---

### Explain topics, partitions, and offsets.

**EM answer:**  
“A **topic** is the logical stream—e.g. `orders`, `user-events`. A **partition** is a shard of that topic: an ordered, append-only log. **Offset** is the position of a message within a partition. Partitions give us parallelism (multiple producers/consumers) and ordering *per partition*. As an EM, I’d make sure we have a clear strategy for partition count and keys so we get the right balance of ordering and throughput, and that the team documents why we chose that layout.”

---

### What is a consumer group, and how does it relate to parallelism?

**EM answer:**  
“A consumer group is a set of consumer instances that share the work of reading a topic. Each partition is assigned to one consumer in the group, so parallelism is capped by the number of partitions. More partitions → more consumers → higher throughput, but more coordination and rebalancing. As an EM, I’d ensure we design partition count and consumer count up front, monitor consumer lag, and have a clear ownership model for who tunes and operates each consumer group.”

---

## 2. Architecture & Components

### Describe the main components of a Kafka cluster.

**EM answer:**  
“We have **brokers** (store and serve data), **producers** (publish to topics), **consumers** (read in groups), and **ZooKeeper or KRaft** (metadata and coordination). As an EM, I care that the team understands the failure modes of each: broker down, controller failover, consumer rebalancing. I’d want runbooks and alerts so we can respond without heroics.”

---

### What is ZooKeeper (or KRaft) used for in Kafka?

**EM answer:**  
“It’s used for cluster metadata and coordination: broker registration, topic config, partition leadership, and consumer group state. KRaft is the newer way to do this without ZooKeeper, simplifying the stack. As an EM, I’d track the migration path to KRaft and ensure we don’t over-invest in ZK if we’re moving off it; I’d also make sure we have a clear owner for cluster metadata and upgrades.”

---

### How does Kafka achieve high throughput and low latency?

**EM answer:**  
“Through batching, sequential disk I/O, zero-copy where possible, and partitioning for parallelism. Trade-off: batching can add latency; we tune batch size and linger for our SLA. As an EM, I’d set clear latency and throughput SLAs with the business, then work with the team to tune producer/consumer and partition count to meet them, and monitor p99 latency and throughput in production.”

---

### What is log compaction, and when would you use it?

**EM answer:**  
“Compaction keeps the latest value per key and drops older updates. Useful for changelog-style topics (e.g. user profile, config). We get a compacted ‘current state’ stream without infinite retention. As an EM, I’d use it when we need key-based state or cache sync; I’d document which topics are compacted and why, so new team members and new use cases don’t assume full history.”

---

## 3. Producers

### What are acks (0, 1, all)? How do they affect durability vs throughput?

**EM answer:**  
“**acks=0**: fire-and-forget, highest throughput, no guarantee. **acks=1**: leader wrote it; we can lose it if the leader fails before replication. **acks=all** (or -1): leader + in-sync replicas; strongest durability, slightly more latency. As an EM, I’d standardize on **acks=all** for business-critical events unless we have a clear, documented reason (e.g. metrics with at-most-once). I’d make this a platform/default so teams don’t accidentally choose weak guarantees.”

---

### What is idempotence in producers, and why does it matter?

**EM answer:**  
“Idempotence means retrying a send won’t create duplicates: the broker deduplicates by producer ID and sequence. Critical for at-least-once or exactly-once semantics. As an EM, I’d enable it by default for all producers and ensure the team knows that without it, retries can double-count orders or payments. I’d treat it as a non-negotiable for financial or critical business events.”

---

### How does partitioning work? How is the partition chosen?

**EM answer:**  
“If we send a **key**, the partition is `hash(key) % num_partitions`, so the same key always goes to the same partition—good for ordering per key. Without a key, it’s round-robin. As an EM, I’d establish guidelines: use keys when we need ordering (e.g. per user or order), and ensure key distribution is not too skewed; otherwise we get hot partitions and lag. I’d also document our partition-count strategy so it’s consistent across topics.”

---

## 4. Consumers

### How does consumer offset management work?

**EM answer:**  
“Consumers commit offsets (e.g. after processing) to Kafka (or another store in older setups). On restart or rebalance, they resume from the last committed offset. If we commit before processing, we get at-least-once; after processing we reduce duplicates but risk losing progress on crash. As an EM, I’d standardize on one pattern (e.g. commit after processing with idempotent handlers) and make sure we monitor commit failures and consumer lag so we catch offset issues before they become incidents.”

---

### What is exactly-once semantics, and how can Kafka support it?

**EM answer:**  
“Exactly-once means each event is processed precisely once: no duplicates, no drops. Kafka supports it via transactional producers, consumer read-your-writes, and committing offsets in the same transaction as downstream writes. As an EM, I’d use it for payment or inventory; for less critical flows I might accept at-least-once with idempotent processing to reduce complexity. I’d own the decision per domain and document it so the team and auditors understand our guarantees.”

---

### Explain consumer rebalancing and what triggers it.

**EM answer:**  
“Rebalancing is when the group coordinator reassigns partitions to consumers—e.g. consumer joins/leaves or topic partition count changes. During rebalance, consumption often pauses. Triggers: new consumer, consumer down, heartbeat timeout, new partition. As an EM, I’d minimize unnecessary rebalances (stable membership, tuned session timeouts), monitor rebalance frequency and duration, and ensure our deploy and scaling strategies don’t cause constant churn. I’d also set expectations with product on brief lag spikes during deploys.”

---

### What are lag and consumer lag, and why do they matter?

**EM answer:**  
“Lag is how far behind the latest offset a consumer (or group) is—messages not yet processed. High lag means delayed processing and risk of falling further behind. As an EM, I’d treat lag as a core SLA: we’d define acceptable lag (e.g. &lt; 1k or &lt; 5 min) and alert when we breach it. I’d own the response playbook: scale consumers, fix slow processing, or temporarily add capacity, and I’d do post-incident reviews when lag causes business impact.”

---

## 5. Partitions & Replication

### Why are partitions important for parallelism and ordering?

**EM answer:**  
“Partitions are the unit of parallelism: we can have one consumer per partition in a group, and producers can send to multiple partitions in parallel. Ordering is *per partition* only. So we get global order only by having one partition (bottleneck) or we accept ordering per key and design keys accordingly. As an EM, I’d make this explicit in our design docs: we choose partition count and keys to match our ordering and throughput requirements, and we document the ordering guarantees we give to the business.”

---

### How does replication work? Leader vs follower?

**EM answer:**  
“Each partition has a leader replica and one or more followers. Writes go to the leader; the leader replicates to followers. Reads can be from leader (or followers in some setups). If the leader fails, a follower in the ISR is promoted. As an EM, I’d ensure we run with replication factor ≥ 2 (typically 3 in production), understand ISR and under-replicated partition alerts, and have runbooks for broker failure and partition leadership so we don’t lose data or availability.”

---

### What is ISR, and how does it relate to acks=all?

**EM answer:**  
“ISR (in-sync replicas) is the set of replicas that are caught up with the leader. **acks=all** means the leader waits for all ISR replicas to acknowledge before replying to the producer. If a replica falls out of ISR, we still require acks from the remaining ISRs—so we don’t wait for slow or failed replicas forever. As an EM, I’d monitor ISR shrink (e.g. replicas dropping out) and under-replicated partitions; I’d treat them as early warning of broker or disk issues and fix before we lose redundancy.”

---

### How do you choose the number of partitions for a topic?

**EM answer:**  
“We consider: (1) consumer parallelism—at least as many partitions as max consumers we’ll run, (2) throughput—more partitions spread load, (3) future growth—adding partitions later can break per-key ordering in some cases. We don’t over-partition (e.g. 1000s) without need—it increases metadata and rebalance cost. As an EM, I’d define a lightweight design template: document target throughput and consumer count, derive partition count, and review when we scale so we don’t make one-off guesses per topic.”

---

## 6. Reliability & Durability

### How does Kafka provide durability of messages?

**EM answer:**  
“Through replication (multiple replicas), acks=all (write to ISR), and persistent storage. Messages aren’t considered committed until in-sync replicas have them. As an EM, I’d enforce acks=all and replication factor ≥ 2 for production, and make retention and replication part of our capacity and DR planning so we can recover from broker or datacenter failure without data loss.”

---

### At-least-once, at-most-once, exactly-once — trade-offs?

**EM answer:**  
“**At-most-once**: we may drop messages (e.g. acks=0); simple, low latency. **At-least-once**: we may duplicate (e.g. commit before process, retry on failure); common default. **Exactly-once**: no duplicates, no drops; more complex (transactions, idempotent sinks). As an EM, I’d map each domain to a guarantee: payments/inventory → exactly-once or at-least-once with strong idempotency; metrics/analytics → at-most-once sometimes acceptable. I’d document this and review it with the team so we don’t over or under-engineer.”

---

### How would you design for exactly-once processing with Kafka?

**EM answer:**  
“Use idempotent producers, transactional producers where we write to Kafka and another store, and commit consumer offsets in the same transaction as downstream writes (e.g. Kafka Streams or a transactional DB). Alternatively, at-least-once plus idempotent consumers and idempotent keys in the sink system. As an EM, I’d choose based on team maturity and existing stack: transactions if we’re already on a supported stack; idempotency if we want simpler ops. I’d own the standard and the review process for any new ‘exactly-once’ use case.”

---

## 7. Scaling & Performance

### How do you scale a Kafka cluster?

**EM answer:**  
“We scale **brokers** for more storage and I/O; we add **partitions** (where possible without breaking semantics) for more producer/consumer parallelism; we add **consumers** in the same group up to the number of partitions. As an EM, I’d own a capacity model: we’d project growth, define when we add brokers vs partitions vs consumers, and review it quarterly. I’d also ensure we don’t scale partitions blindly—we’d document the impact on rebalancing and ordering.”

---

### What are typical bottlenecks, and how do you identify them?

**EM answer:**  
“Disk I/O, network, and sometimes CPU on brokers; consumer processing speed and partition count on the consumer side. We’d use broker metrics (disk utilization, network, request latency), consumer lag, and per-partition throughput. As an EM, I’d make sure we have dashboards and alerts on these, and that we do capacity reviews before big campaigns or new use cases so we’re not surprised by bottlenecks in production.”

---

## 8. Operations & Monitoring

### What metrics would you track for Kafka in production?

**EM answer:**  
“**Broker**: disk usage, under-replicated partitions, request rate/latency, network. **Consumers**: lag per group and per partition, commit rate, rebalance rate. **Producers**: send rate, error rate, latency. As an EM, I’d define SLOs (e.g. p99 latency, max lag, zero under-replicated for &gt; 5 min) and alert when we breach them. I’d also track cost (storage, broker count) and tie it to usage so we can right-size and justify spend.”

---

### How would you approach capacity planning for a new Kafka-based system?

**EM answer:** “I’d work with product and eng to get: expected event volume (peak and growth), retention, and latency SLA. Then: size brokers (disk, network), partition count (throughput + consumer count), replication factor, and retention. I’d add a buffer (e.g. 2x headroom) and plan for scaling triggers (e.g. add brokers when disk &gt; 70%). I’d document assumptions and review them when requirements change so we don’t over-provision or under-provision.”

---

### What is Kafka Connect, and when would you use it?

**EM answer:**  
“Kafka Connect is a framework for streaming data in and out of Kafka using connectors (DB, S3, etc.) without writing custom producer/consumer code. We’d use it for standard ETL or CDC patterns. As an EM, I’d prefer Connect when we have a supported connector and the use case fits; for custom logic or non-standard systems, I’d allow custom apps. I’d own the decision so we don’t duplicate effort or over-build custom pipelines where Connect would suffice.”

---

### How do you approach schema evolution (e.g. with Schema Registry)?

**EM answer:**  
“We’d use a schema registry and enforce compatibility (backward/forward) so producers and consumers can evolve without big-bang deploys. As an EM, I’d set a team standard: all event payloads have a schema; we use compatible evolution by default and require review for breaking changes. I’d also define ownership: who approves schema changes and how we communicate breaking changes to consuming teams so we avoid production breakages.”

---

## 9. Design & Trade-offs (EM-Focused)

### When would you choose Kafka over other messaging or streaming options?

**EM answer:**  
“I’d choose Kafka when we need: high throughput, replay, multiple consumers, event sourcing, or stream processing. I’d choose a simple queue (SQS, RabbitMQ) when we need fire-and-forget task distribution and don’t need history or multiple readers. As an EM, I’d document our ‘when to use Kafka’ guidelines and review new use cases so we don’t over-use Kafka for simple queues or under-use it where replay and scale matter.”

---

### How would you design an event-driven architecture using Kafka for a new product?

**EM answer:**  
“I’d identify core events (e.g. order.created, payment.completed), define topics and ownership per domain, set retention and partitioning based on volume and ordering needs, and choose at-least-once or exactly-once per flow. I’d also plan for schema registry, monitoring, and runbooks from day one. As an EM, I’d run a short design review with the team and stakeholders so we align on ownership, SLAs, and rollout—and I’d treat the first few topics as a template for the rest of the org.”

---

### How would you introduce Kafka to a team that has never used it?

**EM answer:**  
“I’d start with a small, non-critical use case (e.g. analytics or audit log) so the team learns without risking core business. I’d provide training (concepts, producer/consumer patterns, ops), standardize on a platform (client libs, defaults, Schema Registry), and document runbooks and ownership. I’d pair new adopters with someone experienced and run blameless post-mortems on early incidents so we improve our patterns and docs. As an EM, I’d own the rollout plan and the decision to expand Kafka to more critical flows once the team is confident.”

---

### What operational runbooks and SLAs would you define for a Kafka-based platform?

**EM answer:**  
“**SLAs**: availability (e.g. 99.9%), max consumer lag (e.g. &lt; 5 min or &lt; 10k messages), producer/consumer latency p99. **Runbooks**: broker down, under-replicated partitions, consumer lag spike, schema compatibility failure, disk full. I’d assign an on-call and escalation path, and link runbooks to alerts. I’d also define ownership: who owns which topics and consumer groups, and who is responsible for capacity and schema evolution. As an EM, I’d review these quarterly and update them after incidents.”

---

## 10. Quick Reference (EM Cheat Sheet)

| Term | One-liner for EM |
|------|-------------------|
| **Topic** | Logical stream; we own naming and lifecycle. |
| **Partition** | Shard of a topic; unit of parallelism and ordering scope. |
| **Offset** | Position in a partition; consumers commit to resume. |
| **Consumer group** | Set of consumers sharing partitions; we size and monitor. |
| **Replication factor** | Number of copies; we use ≥ 2 (often 3) in prod. |
| **ISR** | In-sync replicas; we alert when ISR shrinks. |
| **Lag** | How far behind the consumer is; we treat as SLA. |
| **acks** | Durability vs latency; we default to **all** for critical data. |

---

*Use this doc to rehearse answers out loud and tailor examples to your own experience (e.g. “In my last role we used Kafka for X and we decided Y because…”).*
