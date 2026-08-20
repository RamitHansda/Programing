# Distributed Job Scheduler (10k jobs/sec)

Interview-oriented HLDs for distributed job scheduling at **~10k executions/second**.

| Doc | Purpose |
|-----|---------|
| [HELLO_INTERVIEW_JOB_SCHEDULER_HLD.md](./HELLO_INTERVIEW_JOB_SCHEDULER_HLD.md) | **Hello Interview problem breakdown** — Task vs Job, ±2s, 10k/sec, at-least-once, mid/senior/staff bar |
| [DISTRIBUTED_JOB_SCHEDULER_HLD.md](./DISTRIBUTED_JOB_SCHEDULER_HLD.md) | Broader staff notes: delay buckets, leases, bottlenecks, capacity |

**Hello Interview one-liner:** API persists Jobs; sharded Redis ZSET finds due work within ~2s; schedulers CAS-claim → Kafka; workers execute with leases (at-least-once + idempotent handlers).
