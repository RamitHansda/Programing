# Distributed Unique ID Generator — System Design

Design a service that generates **unique, roughly time-ordered IDs** at scale for a distributed system (e.g. primary keys for orders, messages, events) without a central bottleneck. This is the classic "design a Twitter Snowflake" / "design a distributed ID generator" interview question.

---

## How to Approach This in an Interview

### 1. Clarify requirements (30–60 seconds)

| Ask | Why |
|-----|-----|
| **Uniqueness scope:** Globally unique across the whole system, or just per-table/per-shard? | Determines whether you need a global coordinator or can generate locally. |
| **Ordering:** Must IDs be sortable/monotonically increasing (e.g. for pagination, "created before")? Or is uniqueness alone enough (like UUID)? | Time-ordered IDs need an embedded timestamp; pure uniqueness can use random bits (UUID). |
| **Format:** Numeric (fits in 64-bit `long`), or string is OK? | Numeric 64-bit is compact, indexable, and comparable; string UUIDs are 128-bit and less index-friendly. |
| **Scale:** IDs/sec needed (e.g. 10K/s, 1M/s)? Number of ID-generating nodes/services? | Drives how many bits you allocate to sequence vs. machine ID. |
| **Availability:** Can we tolerate a single point of failure (e.g. one DB) for ID generation? | Usually **no** — ID generation must not be a bottleneck or SPOF since every write depends on it. |
| **Clock dependency:** Is it OK to depend on synchronized clocks (NTP) across machines? | Determines how you handle clock drift/rollback. |

**What to say:** *"I'll assume we need globally unique, 64-bit, roughly time-sortable IDs, generated with no central coordinator on the hot path, supporting a high write rate across many nodes/data centers, and tolerant of clock issues."*

### 2. State the high-level idea (30 seconds)

**What to say:** *"The standard approach is a Snowflake-style ID: pack a timestamp, a machine/shard identifier, and a per-millisecond sequence number into a single 64-bit integer. Each node generates IDs independently — no network call, no shared state, no SPOF — and the timestamp prefix keeps IDs roughly sorted by creation time."*

### 3. Walk through the bit layout

```
 63           62                    22                    12          0
┌──┬─────────────────────────────┬───────────────────┬───────────────┐
│ 0│      timestamp (41 bits)     │  machine ID (10)  │ sequence (12) │
└──┴─────────────────────────────┴───────────────────┴───────────────┘
```

- **1 bit** — unused / sign bit (keeps the value a positive `long`).
- **41 bits** — milliseconds since a custom epoch (not Unix epoch, to extend usable range). 2^41 ms ≈ **69 years**.
- **10 bits** — machine/worker ID (2^10 = **1024 nodes**), often split into datacenter ID (5 bits, 32 DCs) + worker ID (5 bits, 32 workers per DC).
- **12 bits** — per-millisecond sequence number (2^12 = **4096 IDs per node per millisecond**, i.e. ~4.1M IDs/sec/node).

**What to say:** *"That gives each node up to 4096 unique IDs per millisecond without talking to anyone else, 1024 independent nodes, and IDs naturally sort by time because the timestamp is the most significant bits."*

### 4. Generation algorithm (per node)

```
on generate():
    now = current_time_millis()
    if now < last_timestamp:
        # clock moved backwards — reject or wait
        raise ClockDriftError  (or wait until clock catches up)
    if now == last_timestamp:
        sequence = (sequence + 1) & 0xFFF   # 12-bit wraparound
        if sequence == 0:
            # exhausted this millisecond's IDs — spin/wait for next ms
            now = wait_for_next_millis(now)
    else:
        sequence = 0
    last_timestamp = now
    return (now - EPOCH) << 22 | (machineId << 12) | sequence
```

This runs **entirely in-process** — a mutex/CAS around `last_timestamp`/`sequence`, no I/O.

### 5. Edge cases and follow-ups to mention

- **Clock drift/rollback (NTP adjustment moves clock backward):** Either (a) refuse to generate and alert, (b) wait until the clock catches up, or (c) keep a small "drift buffer" and use a logical clock. Mention running NTP with monotonic-safe settings and detecting large backward jumps as a hard failure.
- **Machine ID assignment:** Must be unique per node. Options: static config (manual, error-prone), **ZooKeeper/etcd sequential znodes** to hand out IDs at startup, or derive from a stable Kubernetes pod ordinal / IP-based hash with a registry to detect collisions.
- **Clock skew across nodes:** Not a correctness problem (IDs are unique regardless), but affects strict global time-ordering across nodes — acceptable for "approximately sortable," not for "strict total order."
- **Sequence exhaustion within 1ms:** Spin-wait for the next millisecond; at 4096/ms this only matters at very high per-node throughput (>4M/s), then increase sequence bits or shard further.
- **Multi-datacenter:** Split the 10-bit machine ID into datacenter + worker so IDs are unique across regions without cross-DC coordination.
- **Alternative when you don't need ordering:** UUID v4 (122 random bits) — zero coordination, but not index-friendly (random inserts into a B-tree cause page splits) and not sortable. UUID v7 fixes this by adding a timestamp prefix, similar spirit to Snowflake.

Keeping this structure — **clarify → high-level idea → bit layout → algorithm → edge cases** — mirrors how Staff-level candidates are expected to approach design problems.

---

## 1. Problem Statement

Design a service/library that produces **unique identifiers** for entities (orders, messages, users, events) in a distributed system, such that:

- IDs are **unique** across all generating nodes, with no coordination on the hot path.
- IDs are **compact** (ideally fit in a 64-bit integer for efficient storage/indexing).
- IDs are **roughly time-ordered** (newer IDs are numerically larger), useful for pagination, sorting, and debugging.
- Generation is **highly available** and **low-latency** (no network round-trip per ID, no single point of failure).
- The system scales horizontally to many generator nodes and very high throughput.

---

## 2. Requirements & Constraints

| Dimension | Target / Constraint |
|-----------|----------------------|
| **Uniqueness** | Guaranteed unique across all nodes/data centers, indefinitely. |
| **Throughput** | Millions of IDs/sec system-wide; thousands to millions per node. |
| **Latency** | Sub-microsecond generation; no network call in the common path. |
| **Availability** | No SPOF — a generator node failing must not block other nodes from generating IDs. |
| **Size** | 64-bit integer preferred (fits DB primary keys, indexes efficiently, comparable). |
| **Ordering** | Monotonically increasing per node; roughly increasing across nodes (time-prefix). |
| **Extensibility** | Must survive for decades before overflow; support scaling to more nodes over time. |

**Explicit non-goals (state these to show scoping judgment):**

- Strict global total ordering across all nodes at sub-millisecond resolution (not achievable without coordination — and not usually needed).
- Sequential-with-no-gaps IDs (gaps are fine and expected, e.g. unused sequence slots when a millisecond passes with fewer requests).

---

## 3. Approaches Considered

| Approach | How it works | Pros | Cons | Verdict |
|----------|---------------|------|------|---------|
| **UUID (v4, random)** | 128-bit random value, generated locally. | Zero coordination, trivially unique, simple. | Not sortable by creation time; large (16 bytes) hurts index locality (random B-tree inserts → page splits, cache misses). | Fine for opaque tokens/keys; poor as a clustered primary key at scale. |
| **UUID v7 (timestamp-prefixed)** | 48-bit ms timestamp + random bits, per RFC 9562. | Sortable by time, still zero coordination, standardized. | Still 128-bit; less compact than a 64-bit int. | Good modern default when you don't control the schema tightly and want standard tooling support. |
| **Database auto-increment** | Single DB (or one per shard) issues `AUTO_INCREMENT`/`SERIAL` IDs. | Simple, strictly ordered, easy to reason about. | DB becomes a write bottleneck and SPOF; doesn't scale horizontally; cross-shard uniqueness needs partitioned ranges (e.g. odd/even, or `id * N + shardId`). | OK for small/medium scale or single-writer systems; not for high-throughput distributed writers. |
| **Ticket server (dedicated ID service, e.g. MySQL with `REPLACE INTO`)** | Centralized service hands out ID blocks/tickets. | Centralizes complexity, easy to audit. | Centralized service is a SPOF/bottleneck unless made HA; adds a network hop per ID (or per batch). | Used by companies like Flickr historically; superseded by Snowflake-style approaches for scale. |
| **ZooKeeper-assigned ranges** | Coordinator hands each node a range of IDs to consume locally (e.g. Twitter's original approach used ZK for worker-id assignment, not per-ID). | Removes per-ID network calls once a node has its range/worker-id. | Adds dependency on ZK/etcd for startup and rare re-assignment; extra operational component. | Commonly combined with Snowflake for **worker ID assignment only**, not per-ID generation. |
| **Snowflake-style (timestamp + machine ID + sequence in one integer)** | Each node embeds time + its own ID + a local counter into a 64-bit value, entirely in-process. | No network call per ID, no SPOF, roughly time-sortable, compact (64-bit), horizontally scalable to 1000s of nodes. | Requires clock synchronization (NTP) and a scheme to hand out unique machine IDs; limited lifespan by bit budget (still ~69 years typical). | **Recommended default** for high-throughput, ordered, distributed ID generation. |

---

## 4. High-Level Architecture (Snowflake-Style)

```
┌────────────────────────────────────────────────────────────────────┐
│                         Application Nodes                          │
│  ┌───────────────┐   ┌───────────────┐   ┌───────────────┐         │
│  │ ID Gen Lib     │   │ ID Gen Lib     │   │ ID Gen Lib     │       │
│  │ (embedded in   │   │ (embedded in   │   │ (embedded in   │       │
│  │  service A)    │   │  service B)    │   │  service C)    │       │
│  │ machineId = 3  │   │ machineId = 7  │   │ machineId = 41 │       │
│  └───────┬────────┘   └───────┬────────┘   └───────┬────────┘       │
│          │  no network call on the hot path         │              │
└──────────┼─────────────────────┼───────────────────┼───────────────┘
           │                     │                    │
           ▼                     ▼                    ▼
     generate() in-process, protected by a mutex/CAS, returns a 64-bit long

┌────────────────────────────────────────────────────────────────────┐
│         Coordination plane (cold path — only at startup/rare)      │
│  ZooKeeper / etcd / Consul: assigns a unique machineId to each     │
│  node on boot (e.g. sequential znode), detects/prevents collisions │
└────────────────────────────────────────────────────────────────────┘
```

Key property: **the coordination service is only touched at node startup** (to obtain a machine ID), never on the per-request ID-generation path. This is what makes the design scale linearly with the number of nodes and removes any runtime SPOF.

---

## 5. Detailed Design

### 5.1 Bit Layout (64-bit signed integer)

| Field | Bits | Range | Notes |
|-------|------|-------|-------|
| Sign bit | 1 | always 0 | Keeps value non-negative for languages/DBs with signed 64-bit ints. |
| Timestamp | 41 | ~69 years from custom epoch | `now_ms - EPOCH_MS`. Custom epoch (e.g. "2024-01-01") maximizes usable years vs. Unix epoch. |
| Datacenter ID | 5 | 0–31 | Optional split of machine ID for multi-region deployments. |
| Worker ID | 5 | 0–31 | Unique per node within a datacenter. |
| Sequence | 12 | 0–4095 | Reset to 0 each new millisecond; increments within the same millisecond. |

Adjust the split (e.g. 8 bits machine ID + 14 bits sequence) based on measured node count vs. required per-node throughput — this is a tunable trade-off to discuss explicitly in an interview.

### 5.2 Per-Node Generation Algorithm

Single mutex (or atomic CAS loop) per node guarding `(lastTimestamp, sequence)`:

1. Read current time in ms.
2. If `now < lastTimestamp`: clock went backwards (NTP correction). Either block until `now >= lastTimestamp`, or throw and alert (choose based on how much backward drift is tolerable — e.g. tolerate <5ms by waiting, alert/fail on larger jumps).
3. If `now == lastTimestamp`: increment `sequence`. If it overflows 12 bits, **busy-wait** until the clock ticks to the next millisecond, then reset sequence to 0.
4. If `now > lastTimestamp`: reset `sequence = 0`.
5. Set `lastTimestamp = now`.
6. Compose and return: `((now - EPOCH) << 22) | (datacenterId << 17) | (workerId << 12) | sequence`.

This is entirely CPU-bound and lock-scoped to a few instructions — sub-microsecond latency, no I/O.

### 5.3 Machine/Worker ID Assignment

Options, in order of typical maturity:

1. **Static configuration** (env var / config file per deployment) — simplest, but manual and error-prone at scale (duplicate assignment risk).
2. **Coordination service (ZooKeeper/etcd) sequential nodes** — on startup, a node creates an ephemeral sequential znode under `/id-gen/workers/`; the sequence number (mod 1024) becomes its worker ID. Ephemeral node disappears on crash, and the slot can be reclaimed. This is the classic Twitter Snowflake approach.
3. **Orchestrator-derived** — in Kubernetes, use the StatefulSet **pod ordinal** (stable, unique per replica) directly as the worker ID; simplest in container environments, avoids external coordination entirely.
4. **Database lease table** — a row per worker ID with a heartbeat/TTL lease (`UPDATE ... WHERE worker_id = ? AND lease_expires < now()`), reclaimed if a node dies. Adds a DB dependency only at startup/renewal, not per ID.

### 5.4 Handling Clock Issues

- Run **NTP** (or chrony) on all nodes with alerting on drift; prefer `slew` correction over `step` (steps can jump backward).
- On detected backward jump: small jumps (a few ms) → wait it out inline. Larger jumps → refuse to generate (fail closed) and page on-call, since silently reusing a past timestamp risks ID collisions across a restart.
- Persist `lastTimestamp` to local disk periodically (or on shutdown) so a process restart can detect "did the clock or a previous instance already use this millisecond" — an extra safety net, not required for basic correctness within a single running process.

### 5.5 API Surface

```
interface IdGenerator {
    long nextId();               // returns the packed 64-bit ID
}

// Convenience decoding for debugging/observability
record DecodedId(long timestampMs, int datacenterId, int workerId, int sequence) {
    static DecodedId decode(long id) { ... }
}
```

Typically deployed as an **embedded library** inside each service (Java/Go/Python package) rather than a network service, precisely to avoid a per-request network hop.

---

## 6. Scalability & Capacity

- **Per-node throughput:** 2^12 = 4096 IDs/ms = ~4.1M IDs/sec/node (theoretical ceiling; real callers rarely approach this).
- **Node capacity:** 2^10 = 1024 concurrent worker IDs (or 32 × 32 split across DCs/workers) — scale by widening the field if you exceed this, at the cost of timestamp or sequence bits.
- **Lifespan:** 2^41 ms ≈ 69.7 years from the chosen epoch — pick an epoch close to "system launch date" to maximize headroom, and plan a migration (e.g. to 128-bit or a re-epoch) well before exhaustion.
- **Horizontal scaling:** Adding nodes only requires assigning new worker IDs — no impact on existing nodes, no re-sharding, no coordination on the hot path.

---

## 7. Failure Modes & Mitigations

| Failure | Impact | Mitigation |
|---------|--------|------------|
| Coordination service (ZK/etcd) down | New nodes can't start (can't get a worker ID) | Existing nodes keep generating IDs fine (no runtime dependency); cache/retry worker-ID assignment with backoff; consider static fallback ID pool for emergencies. |
| Clock rolls backward | Risk of duplicate IDs if not handled | Wait-out small drift; fail closed on large drift; NTP with slew, not step. |
| Duplicate worker ID (misconfiguration) | Two nodes generate colliding IDs | Use coordination service with atomic/sequential allocation instead of static config; add a canary check comparing assigned worker ID against a registry on startup. |
| Sequence overflow within 1ms | Would wrap and risk collision if not handled | Busy-wait for next millisecond tick before continuing (bounded to <1ms stall). |
| Node crash mid-generation | None — no shared mutable state beyond the node's own memory | Stateless with respect to other nodes; on restart, gets a (possibly new) worker ID and starts fresh with `sequence = 0` at the current timestamp. |

---

## 8. Comparison Summary (Quick Reference)

| Property | UUID v4 | UUID v7 | DB Auto-Increment | Snowflake-style |
|----------|---------|---------|--------------------|------------------|
| Coordination needed per ID | None | None | Yes (DB round-trip) | None (only at node startup) |
| Sortable by creation time | No | Yes | Yes | Yes |
| Size | 128-bit | 128-bit | Typically 32/64-bit | 64-bit |
| Horizontal scalability | Excellent | Excellent | Poor (single writer/shard) | Excellent |
| Index-friendliness | Poor (random) | Good | Good | Good |
| SPOF risk | None | None | Yes, unless sharded | None on hot path |

---

## 9. Summary

1. Pack **timestamp + machine ID + per-millisecond sequence** into a single 64-bit integer, generated **entirely in-process** on each node.
2. Use a coordination service (ZooKeeper/etcd) or orchestrator identity **only at startup** to assign a unique machine ID — never on the per-ID hot path.
3. Handle clock drift explicitly (wait for small backward jumps, fail closed on large ones) and pick an epoch that maximizes the 41-bit timestamp's usable lifespan.
4. This gives unique, compact, roughly time-ordered IDs at millions/sec system-wide with no single point of failure — the same core idea behind Twitter Snowflake, Instagram's ID generator, Discord's IDs, and Sony's Sonyflake.
