# Distributed Job Scheduler (10k jobs/sec)

Interview-oriented high-level design for a multi-tenant job scheduler that handles immediate, delayed, and cron jobs at **10,000 dispatches/second**.

| Doc | Purpose |
|-----|---------|
| [DISTRIBUTED_JOB_SCHEDULER_HLD.md](./DISTRIBUTED_JOB_SCHEDULER_HLD.md) | Full HLD: requirements, estimates, architecture, timers, leases, bottlenecks, cheat sheet |

**One-liner:** Metadata is source of truth; sharded hierarchical delay buckets replace DB due-scans; Kafka ready queues + expiring leases give at-least-once execution with exactly-once bookkeeping.
