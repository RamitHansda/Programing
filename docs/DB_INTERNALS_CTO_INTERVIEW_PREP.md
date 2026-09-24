# Database Internals — CTO-Level Interview Prep

A comprehensive reference for senior/CTO-level technical conversations on database internals, covering storage engines, indexing, transactions, replication, scaling, and design trade-offs. Answers are written to demonstrate architectural depth, production experience, and the ability to reason through trade-offs.

---

## Part 1: Storage Engine Internals

### Q1. How does an RDBMS like PostgreSQL or MySQL (InnoDB) physically store data on disk?

**Core concepts to cover**

- Data is stored in **fixed-size pages** (PostgreSQL: 8 KB; InnoDB: 16 KB). Pages are the unit of I/O — you always read/write a full page, never a partial page.
- A **table file** is a sequence of pages. Each page has a header, metadata, and row data. PostgreSQL keeps pages as heap pages (rows added wherever space exists); InnoDB organizes its primary-key pages as a **clustered index** (rows sorted by PK on disk).
- InnoDB layout: the table *is* the B-tree on the primary key. Leaf pages of the clustered index hold actual row data. Secondary indexes hold (secondary key → primary key).
- PostgreSQL layout: heap files are unordered; every index (B-tree, GIN, etc.) is a separate file. Line pointers inside each page hold (offset, length) → TID (block #, slot #) which uniquely identifies a row.

**Trade-off to highlight**

> InnoDB's clustered index makes PK lookups and range scans on PK extremely fast but makes secondary-index lookups a two-step process (secondary index → PK → clustered index page). PostgreSQL's heap avoids that extra hop for secondary lookups but sacrifices PK range-scan locality.

---

### Q2. Explain MVCC (Multi-Version Concurrency Control) — why do databases use it, and what are its costs?

**Why MVCC**

- Goal: readers don't block writers and writers don't block readers. This dramatically improves throughput in OLTP workloads.
- Each row version carries metadata (PostgreSQL: `xmin`/`xmax`; InnoDB: roll pointer into undo log) so the engine can determine which version is visible to each transaction's snapshot.

**How visibility works (PostgreSQL)**

- On transaction start, a **snapshot** is captured: the set of transaction IDs that are currently in-progress. A row is visible to my snapshot if `xmin` is committed and not newer than my snapshot, and `xmax` is either 0 (row not deleted) or from a transaction that is not committed/visible to me.
- The **commit log** (`pg_xact`) tracks commit/abort status. Reading this log is the final arbiter of visibility.

**How visibility works (InnoDB)**

- InnoDB keeps a **undo log**: old row versions are stored there, not in the table pages. The roll pointer in each row header lets the engine rebuild older versions by applying undo records in reverse.
- **Read View**: when a transaction starts (or when the first read happens, depending on isolation level), a read view records the oldest active transaction ID. Rows with a newer xid are reconstructed from undo.

**Costs and operational implications**

| Cost | PostgreSQL | InnoDB |
|------|-----------|--------|
| Dead tuple accumulation | Old row versions stay in heap pages | Old versions go to undo log (shared tablespace / undo tablespace) |
| Cleanup mechanism | **VACUUM** (and autovacuum) | **Purge thread** automatically reclaims undo |
| Bloat risk | Table/index bloat if vacuum lags | Undo log grows; can cause undo tablespace bloat |
| Long-running transactions | Hold back vacuum (pinned snapshot) | Hold back purge (undo can't be reclaimed) |

**CTO-level answer**

> "MVCC decouples read and write concurrency: every reader sees a consistent snapshot without blocking writers. The cost is that dead row versions accumulate — in PostgreSQL they stay in the heap until VACUUM, in InnoDB they go to undo logs until the purge thread cleans them. Long-running transactions are dangerous: they pin old snapshots, preventing cleanup and causing bloat. Our production SLO on long transactions is usually < 5–10 minutes; anything beyond that gets killed proactively."

---

### Q3. Walk me through what happens during a write (INSERT) from client to durable storage.

**End-to-end flow (PostgreSQL)**

1. **Parse and plan**: SQL is parsed, query tree is planned, an execution node is chosen.
2. **Heap insert**: executor calls `heapam_tuple_insert`. The tuple is written into a **shared buffer** (buffer pool in RAM). A free page is found via the free space map.
3. **WAL record**: *before* the buffer is marked dirty-and-flushed, a WAL (Write-Ahead Log) record is written to the WAL buffer describing the change. WAL record includes the full-page image on first write after a checkpoint (to allow recovery from a partial page write).
4. **WAL flush on commit**: at `COMMIT`, `pg_flush_data` ensures the WAL record is flushed to disk (`pg_wal`). This is what makes the write durable. The data page itself may still be in the buffer pool.
5. **Checkpoint**: periodically, a checkpoint flushes all dirty buffers from the buffer pool to the main data files. After a checkpoint, older WAL segments can be recycled.

**End-to-end flow (InnoDB)**

1. **Row format**: row is formatted (compact or dynamic row format).
2. **Undo log record**: written first, so the row can be rolled back and old versions can be constructed.
3. **Buffer Pool**: row is inserted into the buffer pool page. The page is marked dirty.
4. **Redo log (InnoDB WAL)**: redo log record is written. `innodb_flush_log_at_trx_commit=1` means the redo log is flushed to disk at commit.
5. **Doublewrite buffer**: before a dirty page is flushed from buffer pool to disk, InnoDB writes it to a doublewrite buffer area first. This protects against partial page writes (torn pages).
6. **Checkpoint / background flush**: dirty pages are flushed to the `.ibd` tablespace file in the background.

**Key insight**

> Durability (D in ACID) comes from WAL/redo log flush at commit, not from flushing the data page. Data pages are written lazily in the background. This is why `fsync` on the WAL file is the critical durability path.

---

### Q4. What is WAL and why does it exist? What guarantees does it provide?

**What**
- WAL (Write-Ahead Log) is an append-only log of all changes to the database. Every data modification is recorded in WAL *before* the data page is modified on disk.

**Why**
- **Crash recovery**: if the process crashes, replaying WAL from the last checkpoint forward brings the database to a consistent state.
- **Durability**: `fsync` on WAL is cheaper than `fsync` on random data pages. Sequential writes to WAL are fast; random writes to data pages are batched via checkpoints.
- **Replication**: streaming replication (PostgreSQL) and binary log replication (MySQL) both ship the WAL/redo log to replicas. This decouples the write path from replica propagation.

**Guarantees**

| Guarantee | How WAL provides it |
|-----------|---------------------|
| Durability on crash | Replay WAL from last checkpoint |
| Atomicity | Uncommitted WAL records are ignored on replay (only committed changes are applied) |
| Replication consistency | Replica applies the same WAL records in order |

**Operational implications**

- WAL segment size (PostgreSQL default: 16 MB) affects how often segments are recycled and how large the recovery window is.
- `wal_level = logical` enables logical replication (row-level change events), `replica` enables physical replication.
- Archiving WAL segments to object storage (e.g. S3 via pgBackRest or WAL-G) is the standard approach for point-in-time recovery (PITR).

---

### Q5. What is a buffer pool / shared buffer, and how does the database manage it?

**What it is**

- A **buffer pool** is a region of RAM that caches disk pages. Reads first check the buffer pool; writes go to the buffer pool and are flushed to disk asynchronously (except WAL, which is flushed on commit).
- PostgreSQL: `shared_buffers` (recommended: 25% of RAM). InnoDB: `innodb_buffer_pool_size` (recommended: 50–80% of RAM for a dedicated DB server).

**Page replacement**

- When the pool is full and a new page must be read, an **eviction policy** chooses a victim page.
- PostgreSQL uses a **clock-sweep** algorithm (approximation of LRU that is O(1) and avoids lock contention on a pure LRU list).
- InnoDB uses a **modified LRU** with a "young" and "old" sublist to handle large table scans without polluting the hot part of the pool.

**Dirty page tracking**

- Dirty pages (modified in RAM but not yet on disk) are tracked. The **checkpoint process** writes them to disk periodically. `bgwriter` pre-emptively flushes dirty pages to avoid stalling query execution.

**Why this matters**

> Buffer pool hit rate is the single most important metric for OLTP database performance. A working set that fits in the buffer pool = sequential access patterns; a working set that exceeds it = random I/O, which at typical disk latency (0.1–1 ms) can bottleneck the entire system. We monitor `cache_hit_ratio = heap_blks_hit / (heap_blks_hit + heap_blks_read)` and alert if it drops below 95–99% for steady-state OLTP.

---

## Part 2: Indexing Deep Dive

### Q6. How does a B-tree index work internally? When does it help and when does it hurt?

**Internal structure**

- B-tree: balanced tree. All leaf pages at the same depth. Internal (non-leaf) pages hold separator keys and child pointers. Leaf pages hold `(key, TID/row-pointer)` sorted by key.
- Height is `O(log_B N)` where B = branching factor (typically hundreds for a 8 KB page with 8-byte keys). A table of 1 billion rows has a tree height of ~4–5, meaning 4–5 page reads to find any row.
- Range scans: after finding the first matching leaf, we follow the **right sibling pointer** between leaf pages for sequential range access — no need to go back to the root.

**When it helps**

- Equality: `WHERE id = 42` — walk to leaf in O(log N).
- Range: `WHERE created_at BETWEEN x AND y` — find start leaf, scan forward.
- Sorting: `ORDER BY indexed_col` can use the index for free if no filter reduces the result set significantly.
- Covering index: if all needed columns are in the index, heap access can be skipped.

**When it hurts**

- Low selectivity: `WHERE status = 'active'` on a table where 90% of rows are active. Random I/O to fetch many heap pages is slower than a sequential scan.
- High write throughput: every insert/update/delete must also update every relevant index. Page splits are expensive. Wide indexes on frequently-updated columns slow down writes significantly.
- Very wide index columns: large keys reduce branching factor, increasing tree height.
- Index bloat: deleted index entries are not immediately reclaimed; VACUUM (PostgreSQL) or optimize table (MySQL) is needed.

**CTO-level follow-up: "How do you decide when to add an index?"**

> "We look at: (1) query frequency — is this query on the hot path? (2) selectivity — does the index filter out enough rows to beat a sequential scan? (3) write cost — how many writes per second hit this table, and how many indexes already exist? We use `pg_stat_user_indexes` and `EXPLAIN (ANALYZE, BUFFERS)` to validate. In high-write tables we're aggressive about dropping unused indexes."

---

### Q7. Explain the difference between a clustered and non-clustered index.

**Clustered index**

- The table rows are physically ordered by the clustered index key. There is only **one** clustered index per table (since you can only physically order the rows one way).
- InnoDB: the primary key is always the clustered index. The leaf pages of the PK B-tree *are* the data pages.
- SQL Server: also supports clustered indexes on non-PK columns.
- PostgreSQL: does **not** have a traditional clustered index. `CLUSTER` command reorders the heap file once by an index order, but the table becomes unordered again as rows are inserted/updated (unless you re-cluster periodically). PostgreSQL relies on BRIN for range locality.

**Non-clustered (secondary) index**

- A separate B-tree whose leaf pages hold `(secondary key, primary key / TID)`. To fetch the full row, you must do a second lookup into the clustered index / heap.
- The extra hop is called a **bookmark lookup** (SQL Server), **heap fetch** (PostgreSQL), or **clustered index lookup** (InnoDB).

**Performance implications**

| Pattern | Clustered index (InnoDB) | Heap (PostgreSQL) |
|---------|--------------------------|-------------------|
| PK lookup | One B-tree descent → data at leaf | Index → TID → heap page |
| PK range scan | Leaf pages in order, very cache-friendly | Index → many TIDs → random heap I/O |
| Secondary key lookup | Secondary index → PK → cluster (double descent) | Index → TID → heap (one heap I/O) |
| Insert by random PK (UUID) | Random page splits throughout the tree (fragmentation) | Heap append to free page (fast) |

**Why UUIDs as InnoDB PKs hurt**

> Random UUID inserts scatter writes across the entire clustered index, causing frequent page splits and high write amplification. Solutions: use UUIDv7 (time-sortable), ULID, or a surrogate auto-increment PK.

---

### Q8. What is a composite index? How does column order matter?

**Definition**

- An index on multiple columns: `CREATE INDEX idx ON orders(user_id, status, created_at)`.
- The B-tree sorts by `user_id` first, then `status` within the same `user_id`, then `created_at` within the same `(user_id, status)`.

**Left-prefix rule**

- The index can be used for queries that filter or sort by a left prefix of the index key: `(user_id)`, `(user_id, status)`, `(user_id, status, created_at)`.
- It **cannot** be used for `(status)` alone or `(created_at)` alone (no left prefix).
- For a range condition on a middle column, columns after that range column cannot be used for filtering via the index.

**Column order strategy**

1. Put equality columns first (high selectivity, `=` predicates).
2. Put range columns (e.g. `created_at BETWEEN`) last among the indexed columns you need.
3. If covering the query: add extra columns at the end (INCLUDE in PostgreSQL) to avoid heap fetch.

**Example**

For `WHERE user_id = ? AND status = ? AND created_at > ?`:
- `(user_id, status, created_at)` — perfect, all three can be used.
- `(user_id, created_at, status)` — `user_id` used for equality, `created_at` used for range, but `status` cannot be used for filtering within the range.
- `(status, user_id, created_at)` — only useful if `status` is very selective; if not, bad choice.

---

### Q9. What are partial indexes, covering indexes, and expression indexes?

**Partial index**

- Index only a subset of rows matching a WHERE clause: `CREATE INDEX idx ON orders(user_id) WHERE status = 'pending'`.
- Smaller index, lower maintenance cost, used only when the filter matches.
- Great for "hot subset" queries: active orders, unprocessed jobs, etc.

**Covering index (index-only scan)**

- An index that contains all columns needed by the query (in the key or as INCLUDE columns): `CREATE INDEX idx ON orders(user_id, status) INCLUDE (total_amount, created_at)`.
- The query can be answered entirely from the index without touching the heap (PostgreSQL: also requires the page to be marked all-visible in the visibility map).
- Dramatic performance win for high-frequency read queries on large tables.

**Expression (functional) index**

- Index a function of a column: `CREATE INDEX idx ON users(lower(email))`.
- Query `WHERE lower(email) = 'foo@bar.com'` uses the index.
- Common uses: case-insensitive search, JSON field extraction `((data->>'user_id')::bigint)`.
- Overhead: the expression is evaluated on every insert/update, and statistics may be less accurate.

---

## Part 3: Transactions and Isolation

### Q10. Explain ACID. Give concrete examples of what breaks without each property.

| Property | Guarantee | What breaks without it |
|----------|-----------|------------------------|
| **Atomicity** | All operations in a transaction succeed or none do | Bank transfer: debit completes but credit fails → money disappears |
| **Consistency** | A transaction takes the DB from one valid state to another | Referential integrity: order inserted with non-existent customer_id |
| **Isolation** | Concurrent transactions don't see each other's intermediate state | Dirty read: user sees a balance update that another transaction hasn't committed yet |
| **Durability** | Committed transactions survive crashes | Order confirmed to user, server crashes, order is lost |

**How databases implement each**

- **Atomicity**: transaction log (WAL/undo). On rollback, undo log records reverse all changes.
- **Consistency**: constraint enforcement (FKs, unique, check), deferred constraints, trigger validation.
- **Isolation**: MVCC (snapshot isolation), locking (2-phase locking), or a combination.
- **Durability**: WAL flush to disk at commit (`fsync`). Not just in-memory or OS buffer cache.

---

### Q11. What are the standard transaction isolation levels and what anomalies do they prevent?

| Isolation Level | Dirty Read | Non-Repeatable Read | Phantom Read | Performance |
|-----------------|-----------|---------------------|--------------|-------------|
| **Read Uncommitted** | Possible | Possible | Possible | Highest |
| **Read Committed** | Prevented | Possible | Possible | High |
| **Repeatable Read** | Prevented | Prevented | Possible (per SQL spec; prevented in PostgreSQL/InnoDB) | Medium |
| **Serializable** | Prevented | Prevented | Prevented | Lowest |

**Anomalies defined**

- **Dirty read**: reading a row modified by an uncommitted transaction.
- **Non-repeatable read**: reading the same row twice in a transaction and getting different values because another transaction committed in between.
- **Phantom read**: a range query returns different rows when run twice because another transaction inserted/deleted rows matching the range.
- **Write skew**: two concurrent transactions each read a dataset, decide to write based on what they read, and both writes together violate an invariant (e.g. on-call scheduling: both nurses remove themselves from on-call because they each read "two nurses on call").

**PostgreSQL specifics**

- Default: **Read Committed** (a new snapshot is taken for each statement).
- Repeatable Read in PostgreSQL: snapshot taken at transaction start. Prevents phantom reads (stronger than the SQL spec requires). But write skew is still possible.
- Serializable in PostgreSQL: uses **Serializable Snapshot Isolation (SSI)** — detects write skew patterns and aborts the offending transaction rather than using locks. Very efficient compared to 2PL-based serializable.

---

### Q12. What is two-phase locking (2PL) and how does it differ from MVCC?

**Two-phase locking (2PL)**

- Phase 1 (expanding): acquire locks as needed, never release.
- Phase 2 (shrinking): release locks, never acquire new ones.
- Guarantees serializability. But: readers block writers, writers block readers. High contention → deadlocks.
- **Deadlock detection**: DB builds a wait-for graph and aborts a transaction when a cycle is detected.

**MVCC**

- Readers take no locks (read old versions from MVCC). Writers lock only the *latest* row version.
- Reader/writer non-blocking: far better throughput than 2PL in read-heavy OLTP.
- Tradeoff: anomalies (write skew) can occur at snapshot isolation. Full serializability requires additional conflict detection (SSI in PostgreSQL, or predicate locks in some engines).

**When 2PL is still used**

- SERIALIZABLE in some databases (MySQL default: 2PL + gap locks for phantoms).
- Explicit `SELECT FOR UPDATE` / `SELECT FOR SHARE` — advisory locking via 2PL on top of MVCC.
- Distributed locking (Spanner: two-phase commit with Paxos-based locks).

---

### Q13. Explain deadlocks: how do they occur, how are they detected, and how do you prevent them?

**How they occur**

- Tx A holds lock on Row 1, wants Row 2. Tx B holds lock on Row 2, wants Row 1. Neither can proceed.

**Detection**

- Build a **wait-for graph** (nodes = transactions, edges = "Tx A waits for Tx B"). A cycle = deadlock. One transaction in the cycle is chosen as victim and aborted (usually the one with the least work or lowest priority).
- PostgreSQL detects deadlocks by building the wait-for graph after a lock wait timeout (`deadlock_timeout`, default 1s).

**Prevention strategies**

1. **Consistent lock ordering**: always acquire locks in the same global order (e.g. always lock row with lower ID first). Eliminates cycles by construction.
2. **Short transactions**: keep transactions brief so the lock hold time is minimal.
3. **Optimistic locking**: read without lock, check version at write time; retry on conflict (better for low-contention workloads).
4. **Application-level retry**: deadlocks are transient; always retry transactions that fail with a deadlock error.

---

## Part 4: Replication and High Availability

### Q14. Explain the different replication topologies and when to use each.

**Primary–Replica (Master–Slave)**

- Writes go to primary only. Replicas apply changes from the primary's WAL/binary log.
- Reads can be served from replicas (read scaling), but replica lag means reads may be stale.
- Failover: replica is promoted to primary. Requires orchestration (e.g. Patroni for PostgreSQL, MHA/Orchestrator for MySQL).

**Synchronous vs Asynchronous replication**

| Mode | Durability | Latency | Risk |
|------|-----------|---------|------|
| **Async** | Replica may lag; committed data can be lost on primary crash | Lower write latency | Data loss window = replica lag |
| **Sync** | Primary waits for at least one replica to confirm before committing | Higher write latency | If replica is slow/dead, primary stalls |
| **Semi-sync** (MySQL) | Commit when at least one replica ACKs the WAL receipt (not apply) | Moderate | Fallback to async if replica lags |
| **Quorum** (Patroni, Galera) | Commit when a quorum of replicas ACK | Moderate | Complex; requires odd number of nodes |

**Multi-primary (Multi-master)**

- All nodes accept writes. Conflict resolution required.
- Used in: Galera Cluster, MySQL NDB Cluster, CockroachDB, Spanner.
- Added complexity: write conflicts, certification-based ordering, or distributed locking.

**Cascading replication**

- Replica can itself have replicas (relay replica). Reduces load on primary for replication I/O. Common for large fan-out read replicas.

---

### Q15. What is replication lag, what causes it, and how do you mitigate it?

**Causes**

- **Async replication**: primary commits without waiting; replica applies at its own pace.
- **Single-threaded apply**: older MySQL/PostgreSQL versions applied WAL serially. A burst of writes on primary built up a lag queue.
- **Long-running transactions**: a very long transaction on the primary holds the replica from advancing past its start (due to snapshot dependencies).
- **I/O saturation on replica**: replica hardware lagging.
- **Network**: WAN replication over slow/lossy links.

**Mitigation**

- **Parallel apply**: PostgreSQL 14+ supports parallel WAL apply. MySQL 5.7+ has multi-threaded slave applier (`slave_parallel_workers`).
- **Monitor lag**: `SELECT now() - pg_last_xact_replay_timestamp()` (PostgreSQL) or `SHOW SLAVE STATUS` (MySQL). Alert on lag > threshold.
- **Read your own writes consistency**: route reads that follow a write to the primary (or use `synchronous_commit = remote_apply` for those sessions).
- **Synchronous replication** for critical reads: lag = 0 but at write latency cost.
- **Avoid long transactions** on primary.

---

### Q16. What is a split-brain scenario and how do you prevent it?

**What it is**

- In a replicated cluster, a network partition causes the replica to believe the primary is dead and promotes itself. Now you have two nodes both accepting writes as "primary." When the partition heals, the two datasets are diverged.

**Prevention mechanisms**

- **STONITH (Shoot The Other Node In The Head)**: fencing mechanism that forcibly powers off or isolates the node that might be old primary before promoting the replica.
- **Quorum/majority-based consensus**: only a node with acknowledgment from a quorum of peers can become primary. Prevents a minority partition from independently electing a leader (Raft, Paxos-based — used by etcd, Patroni with etcd/Consul DCS).
- **Distributed Control Store (DCS)**: Patroni uses etcd or Consul as the single source of truth for cluster leadership. Only the node that holds the DCS lock is primary.
- **Read replicas over read-write**: deploy primary in 3-node configuration across AZs. Even if one AZ is partitioned, the remaining 2 (majority) elect the leader.

**CTO-level answer**

> "Split-brain is solved by ensuring only a quorum can elect a leader and by fencing any node that might have old primary state before promoting a replica. We run our PostgreSQL clusters with Patroni + etcd, requiring a majority quorum. Fencing via a power-cycling API (cloud: instance reboot API) ensures the old primary is killed before the new one accepts writes."

---

## Part 5: Scaling Strategies

### Q17. When would you scale reads horizontally (read replicas) vs scale writes (sharding)?

**Read replicas: scale reads**

- Add replicas and distribute read traffic. Appropriate when: reads >> writes, workload is read-heavy, and write throughput fits on a single primary.
- Limit: eventual consistency (replica lag). Write throughput is still bounded by the primary.
- Typical pattern: web apps reading from replicas for catalog, session, analytics; writing to primary for orders, payments.

**Sharding: scale writes**

- Partition data across multiple independent primary databases (shards). Each shard owns a subset of rows (by shard key). Every shard can accept writes independently.
- Write throughput scales linearly with shard count. But: cross-shard queries, distributed transactions, and schema changes become complex.
- Choose the **shard key** carefully:
  - Must distribute writes evenly (avoid hotspots).
  - Queries should be local to one shard (avoid scatter-gather).
  - Should not require re-sharding frequently.

**Common shard key strategies**

| Strategy | Pros | Cons |
|----------|------|------|
| Hash of entity (e.g. `hash(user_id) % N`) | Even distribution | Range queries scatter; re-sharding is expensive |
| Range (e.g. by date, by ID range) | Range queries go to one shard | Can create hotspots (new writes all go to latest shard) |
| Logical tenant (tenant_id) | Complete tenant isolation | Uneven if tenant sizes differ |
| Directory-based | Flexible; can move shards | Extra lookup hop; directory is a single point of failure |

**When to resist sharding**

> "Sharding adds enormous operational complexity — cross-shard transactions, distributed joins, schema migrations across N databases. We try to push the envelope on a single primary (vertical scale, read replicas, connection pooling, query optimization, caching) for as long as possible. Sharding is a last resort, not a first move."

---

### Q18. What is connection pooling, and why is it critical at scale?

**Why raw connections are expensive**

- Each PostgreSQL connection spawns a backend process (~5–10 MB RAM, per connection). At 10,000 connections, that is 50–100 GB RAM just for connection overhead. The scheduler also degrades with thousands of processes.
- Establishing a TLS connection + authentication handshake takes ~10–50 ms. Re-using an existing connection is microseconds.

**Connection pooling modes (PgBouncer)**

| Mode | Connection reuse | Use case |
|------|-----------------|----------|
| **Session pooling** | One server connection per client session | Least efficient; good when clients use session-level features |
| **Transaction pooling** | Server connection returned to pool after each transaction | Most common for stateless apps; dramatically reduces connection count |
| **Statement pooling** | Connection returned after each statement | Rarely used; incompatible with multi-statement transactions |

**PgBouncer at scale**

- Run PgBouncer in transaction mode. App sees a pool of logical connections; PgBouncer multiplexes them onto a smaller set of real PostgreSQL connections (e.g. 5,000 app connections → 100 DB connections).
- For RDS/Cloud SQL, managed proxies (RDS Proxy, Cloud SQL Auth Proxy) do the same.

---

### Q19. Explain database partitioning (table partitioning). How is it different from sharding?

**Table partitioning (within one database instance)**

- A single logical table is divided into multiple physical **partition files** (child tables). The query planner routes queries to relevant partitions using partition pruning.
- Types:
  - **Range partitioning**: `orders` partitioned by `created_at` month/year. Oldest partitions can be dropped instantly (detach + drop — no DELETE overhead).
  - **List partitioning**: by discrete values (e.g. `region`).
  - **Hash partitioning**: by hash of a column (even distribution, no natural ordering).

**Benefits**

- **Partition pruning**: `WHERE created_at > '2026-01-01'` only reads 2026 partitions, not historical data.
- **Maintenance**: archive old data by detaching partitions. Rebuild indexes on a single partition without locking the whole table.
- **Autovacuum efficiency**: VACUUM operates per partition, so small partitions are vacuumed quickly.

**How partitioning differs from sharding**

| | Table Partitioning | Sharding |
|--|-------------------|---------|
| Scope | Single DB instance | Multiple DB instances |
| Transactions | Full ACID | Cross-shard transactions are complex |
| Complexity | Moderate (built into Postgres 10+) | High (application or middleware layer) |
| Write scaling | Same instance limits apply | Near-linear write scale-out |
| Cross-partition queries | Planner handles automatically | Scatter-gather, application level |

---

## Part 6: Consistency and Distributed Systems

### Q20. Explain the CAP theorem. Is it actually useful in practice?

**The theorem**

- In a distributed system that may experience **network partitions**, you cannot simultaneously guarantee both **Consistency** (linearizability: every read sees the most recent write) and **Availability** (every non-failing node responds). Partition tolerance (P) is mandatory in practice — networks do partition.

**Practical implications (AP vs CP)**

| Choose CP | Choose AP |
|-----------|-----------|
| Financial ledgers, inventory, reservations | Social feeds, counters, likes |
| Config/service discovery | Shopping carts, session data |
| Distributed locks | IoT ingestion, events |
| Examples: PostgreSQL sync, CockroachDB, etcd | Examples: Cassandra, DynamoDB (default), CouchDB |

**Limits of CAP**

> "CAP is often overused as a 2-choice framing. The PACELC model is more nuanced: even when there is no partition (the normal case), systems trade off **Latency** vs **Consistency**. For example, Dynamo-style systems (DynamoDB, Cassandra) accept eventual consistency to minimize latency even without a partition. In practice, we also tune consistency per-operation: DynamoDB offers per-request strong consistency at higher latency cost. Most production systems use a spectrum rather than a binary choice."

---

### Q21. What is eventual consistency and how do you handle it in application code?

**What it means**

- After a write, replicas will *eventually* converge to the same value — but reads from different replicas may return stale data in the interim window.

**Application-level patterns to handle it**

1. **Read-your-writes consistency**: after a write, route the subsequent read to the primary (or to the same replica that acknowledged the write). Example: after posting a comment, show the comment immediately from the primary.

2. **Monotonic read consistency**: a client never reads an older version than it previously read. Achieved by sticking a client to a specific replica, or by tracking vector clocks.

3. **Causality tokens / fencing tokens**: the write returns a version/LSN. The read request includes "read-at-least-version X." The database waits until the replica catches up to X before serving the read.

4. **Idempotent writes**: design mutations to be safe to retry/replay (use unique idempotency keys). Necessary when using at-least-once delivery from queues.

5. **Conflict resolution**: in multi-master systems, define how conflicts are resolved — last-write-wins (by timestamp), application-level merge (CRDTs), or manual reconciliation.

---

### Q22. What is the difference between strong consistency, linearizability, and sequential consistency?

| Model | Guarantee | Example |
|-------|-----------|---------|
| **Linearizability** | Every operation appears to take effect at a single point in real time (strongest). Reads always see the latest committed write. | CockroachDB, Spanner, etcd, PostgreSQL sync replication |
| **Sequential consistency** | Operations happen in some global order consistent with each client's local order. But that order needn't respect real-time. | Some CPU memory models, some early distributed systems |
| **Causal consistency** | Operations that are causally related appear in the correct causal order. Unrelated operations may appear in any order. | Cassandra lightweight transactions, MongoDB causally consistent sessions |
| **Eventual consistency** | All replicas converge to the same value eventually, if no new writes occur. | DynamoDB (default), Cassandra, CouchDB |

**Practical grading**

> "For a payment system we need linearizability — a user cannot see a balance they shouldn't see, and double-spends must be rejected. For a social feed we accept eventual consistency — seeing a post 200 ms late is fine. We never use strong consistency everywhere; the latency and availability cost is too high."

---

## Part 7: Performance, Query Optimization, and Observability

### Q23. Walk me through how a query optimizer works.

**Phases of query execution**

1. **Parse**: SQL text → abstract syntax tree (AST).
2. **Analyze / Rewrite**: validate identifiers, expand views, apply rewrite rules (e.g. `IN` → `EXISTS`, flatten subqueries).
3. **Plan (optimize)**:
   - **Logical plan**: relational algebra (joins, filters, projections).
   - **Physical plan**: choose access methods (seq scan, index scan, bitmap scan), join algorithms (nested loop, hash join, merge join), and join order.
   - The optimizer enumerates candidate plans using **dynamic programming** (or heuristics for very large join counts) and estimates costs.
4. **Execute**: execute the physical plan, producing rows.

**Cost model**

- Costs are estimated in "page reads" (I/O units). Each plan node has a startup cost and a per-row cost.
- **Statistics**: the optimizer reads `pg_statistic` (PostgreSQL) — per-column histograms, most-common values, null fractions, correlation. `ANALYZE` updates these. Stale stats → bad estimates → bad plans.

**Common plan choices**

| Join algorithm | When chosen | Why |
|---------------|-------------|-----|
| Nested loop | Small outer result, index on inner | Low startup cost; bad for large result sets |
| Hash join | Large equi-join with no useful index | Builds hash table on smaller side; one pass |
| Merge join | Both sides sorted or sortable | Good for large sorted datasets; no hash table |

**CTO-level answer**

> "The optimizer is a cost-based chooser: it enumerates access paths and join orders, estimates row counts using column statistics, and picks the lowest-cost plan. Bad plans almost always come from stale statistics or poor cardinality estimates — especially after large bulk loads, deletes, or schema changes. We run `ANALYZE` after bulk operations and use `EXPLAIN (ANALYZE, BUFFERS)` to compare estimated vs actual rows to diagnose divergence."

---

### Q24. What is a slow query, and how do you diagnose and fix it?

**Diagnosis workflow**

1. **Identify**: `pg_stat_statements` (PostgreSQL) or `slow_query_log` (MySQL) to find top queries by total time.
2. **Explain**: `EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)` — look at:
   - Estimated rows vs actual rows (large divergence = bad stats).
   - Sequential scans on large tables (missing index or wrong plan).
   - Hash joins spilling to disk (`Batches > 1`).
   - Nested loops with large outer row count (O(N²) problem).
3. **Buffers**: `Buffers: shared hit=X read=Y` — large `read` = not in buffer pool = disk I/O.
4. **Fix options**:
   - Add or change index (covering index, partial index, composite index).
   - Rewrite query (avoid `SELECT *`, reduce joins, avoid functions on indexed columns in WHERE).
   - Update statistics (`ANALYZE`).
   - Increase `work_mem` for sort/hash operations that are spilling to disk.
   - Partition the table.
   - Cache at application layer (Redis).

---

### Q25. What metrics do you monitor for a production database, and what are your alert thresholds?

**Key metrics**

| Metric | What it signals | Alert threshold (example) |
|--------|----------------|--------------------------|
| **Query latency p99** | User-facing slowness | > 200 ms for OLTP |
| **Replication lag** | Staleness of replicas; data loss risk | > 10 s (alert), > 60 s (page) |
| **Buffer cache hit ratio** | I/O efficiency | < 95% |
| **Active connections** | Connection saturation | > 80% of max_connections |
| **Lock waits / deadlocks** | Contention | Any deadlock per minute |
| **Autovacuum / VACUUM** | Bloat and xid wraparound risk | Oldest xid age > 1.5 billion (of 2 billion limit) |
| **WAL generation rate** | Write load on primary and replica lag budget | Monitor for spikes |
| **Temp file usage** | Sorts/hashes spilling to disk (`work_mem` too low) | Any temp file > 100 MB |
| **Table/index bloat** | Dead tuple accumulation | Table bloat > 20% of live data |
| **Checkpoint duration** | I/O pressure during checkpoint | checkpoint_warning in logs |

**Tooling**

- PostgreSQL: `pg_stat_activity`, `pg_stat_statements`, `pg_stat_user_tables/indexes`, `pg_stat_replication`, `pg_stat_bgwriter`.
- Export via `postgres_exporter` → Prometheus → Grafana.
- For RDS/Aurora: CloudWatch metrics + Enhanced Monitoring.

---

## Part 8: Design and Architecture Questions

### Q26. How would you design a database schema for a multi-tenant SaaS application?

**Three primary patterns**

| Pattern | Description | Trade-offs |
|---------|-------------|-----------|
| **Shared DB, shared schema** | All tenants in same tables, `tenant_id` column | Simplest; risk of data leakage (missing `tenant_id` in queries); index design must include `tenant_id` |
| **Shared DB, separate schema** | One schema per tenant in same Postgres instance | Good isolation; schema-per-tenant migrations are straightforward; ~hundreds of tenants max per instance |
| **Separate DB per tenant** | One DB (or cluster) per tenant | Strongest isolation; easy data backup/restore per tenant; expensive for large numbers of small tenants |

**Shared schema best practices**

- Row-level security (RLS): `CREATE POLICY tenant_isolation ON orders USING (tenant_id = current_setting('app.tenant_id')::uuid)`. Zero risk of cross-tenant data leakage even with missing WHERE clauses.
- Always index on `(tenant_id, ...)` as the leading column.
- Partition by `tenant_id` for large tenants with high row counts.

---

### Q27. How do you handle database migrations with zero downtime?

**The challenge**

- Schema changes in production on a live table can take exclusive locks (e.g. `ADD COLUMN NOT NULL`, `CREATE INDEX`) that block reads and writes for minutes or hours on large tables.

**Safe migration patterns**

1. **Expand–contract (Blue-Green schema migration)**:
   - Expand: add new column (nullable, with default). No lock on rows.
   - Backfill: update existing rows in batches, with rate limiting.
   - Contract: add NOT NULL constraint after backfill complete.

2. **Online index creation**: `CREATE INDEX CONCURRENTLY` (PostgreSQL) — builds the index in the background without blocking DML. Slower but non-blocking.

3. **Feature flags + dual writes**: deploy code that writes to both old and new schema. Migrate data in background. Once migrated, switch reads to new schema. Remove old schema in a later deployment.

4. **Pt-online-schema-change / gh-ost (MySQL)**: shadow table approach — creates a new table, copies data in chunks, syncs with triggers or row-based replication, then swaps atomically.

5. **Avoid long-running transactions during migrations**: they hold locks or block DDL.

**CTO-level answer**

> "We treat every schema change as a multi-phase deployment: (1) add the new column/table (backwards-compatible), (2) deploy code that writes to both, (3) backfill in batches at an off-peak time with `pg_sleep(0.1)` between batches to throttle I/O, (4) add constraints only after backfill, (5) deploy code that only uses the new schema, (6) drop the old column in a final cleanup migration. This takes longer but never takes the site down."

---

### Q28. When would you use a NoSQL database over a relational one?

**Reasons to choose NoSQL**

| Reason | Example scenario | Example DB |
|--------|----------------|-----------|
| Flexible/evolving schema | Rapid prototyping; schema changes every sprint | MongoDB |
| Extreme write throughput | IoT telemetry ingestion at millions of events/sec | Cassandra, ScyllaDB |
| Low-latency key lookups at scale | Session store, cache, rate limiting | Redis |
| Geo-distributed writes | Multi-region active-active without cross-region latency | DynamoDB, Cassandra |
| Document/hierarchical data | E-commerce product catalog with variable attributes | MongoDB, DynamoDB |
| Graph relationships | Fraud detection, social network | Neo4j, Amazon Neptune |

**Reasons to stay with relational**

- Complex joins and ad-hoc queries.
- ACID transactions across multiple entities.
- Strong consistency requirements.
- Well-understood schema unlikely to change radically.
- Reporting and analytics (or use a columnar store for analytics).

**CTO-level perspective**

> "We almost always start with PostgreSQL. It's extremely capable, supports JSON, full-text search, time-series via TimescaleDB, and has excellent tooling. We add a specialized store only when we hit a specific bottleneck that PostgreSQL genuinely cannot address — e.g. we added Redis for session caching and Cassandra for event ingestion, but kept PostgreSQL as the system of record."

---

## Part 9: Advanced Topics

### Q29. What is a columnar (column-store) database and when would you use it?

**Row store vs column store**

| | Row Store (OLTP) | Column Store (OLAP) |
|--|-----------------|---------------------|
| Storage layout | All columns of a row stored together | Each column stored separately |
| Best for | `SELECT * WHERE id = X` — full row | `SELECT SUM(amount) FROM orders` — one column, many rows |
| Compression | Low (mixed types per page) | High (same type per column, e.g. RLE, delta encoding) |
| Insert/update | Fast (write one contiguous row) | Slow (must update N column files) |
| Analytics scan | Reads unused columns (wasted I/O) | Reads only queried columns |
| Examples | PostgreSQL, MySQL, InnoDB | Redshift, BigQuery, Snowflake, ClickHouse, DuckDB |

**Use cases**

- OLAP / data warehousing: queries aggregate large ranges of data over a few columns.
- Business intelligence / reporting: `GROUP BY`, `SUM`, `AVG` over billions of rows.
- Real-time analytics at scale: ClickHouse for event-level analytics.

**Hybrid: HTAP**

- Some systems bridge OLTP and OLAP: TiDB, CockroachDB, Aurora with Parallel Query, SingleStore. PostgreSQL with columnar extensions (Citus columnar).

---

### Q30. Explain distributed transactions. What are the main approaches and their trade-offs?

**Why distributed transactions are hard**

- Changes span multiple databases or services. Need atomicity across all of them. But the coordinator can crash, the network can partition, and nodes may be slow.

**Approaches**

**Two-Phase Commit (2PC)**

- Phase 1 (Prepare): coordinator asks all participants to prepare and promise to commit if asked.
- Phase 2 (Commit): if all said yes, coordinator sends commit; if any said no, sends abort.
- Problem: coordinator SPOF; if coordinator crashes between phases, participants are blocked ("in-doubt" transactions). Blocking protocol.
- Used by: PostgreSQL `PREPARE TRANSACTION`, Spanner (with Paxos for coordinator), XA transactions in Java EE.

**Saga pattern**

- Each step is a local transaction. If a step fails, compensating transactions roll back prior steps.
- Two coordination styles:
  - **Choreography**: services emit events, others react (EventBridge, Kafka).
  - **Orchestration**: central saga orchestrator sends commands and listens for replies.
- Tradeoff: no isolation between saga steps (intermediate states are visible). Requires idempotency and careful compensation logic.

**Outbox pattern**

- Service writes its business record + an outbox event in the **same local transaction**. A separate process reads the outbox and publishes to a message broker. Guarantees at-least-once delivery without distributed transaction.

**Spanner / CockroachDB approach**

- True distributed ACID across shards. Uses TrueTime (Spanner) or hybrid logical clocks (CockroachDB) to order transactions globally. Paxos per shard for durability. 2PC across shards but with Paxos-based coordinator that is not a SPOF.

**CTO-level answer**

> "We avoid 2PC in application-level distributed transactions because of its blocking behavior and coordinator SPOF. Instead we use the Outbox pattern for cross-service consistency (transactional outbox + idempotent consumer), and Saga for long-running multi-step processes with compensations. For systems that genuinely need distributed ACID, we evaluate CockroachDB or Spanner, accepting the latency overhead of multi-shard consensus."

---

## Quick-Reference: CTO-Level Talking Points Summary

| Topic | One-liner | Key trade-off to articulate |
|-------|----------|----------------------------|
| Storage pages | Unit of I/O; 8 KB (PG) / 16 KB (InnoDB) | Page size balances overhead vs I/O granularity |
| MVCC | Readers/writers non-blocking via snapshots | Dead tuple bloat; needs VACUUM / purge |
| WAL | Sequential writes for durability; crash recovery | `fsync` on WAL = durability; skipping it = data loss risk |
| Buffer pool | RAM cache of disk pages; most important performance lever | Hit ratio drives IOPS; sizing is first tuning step |
| B-tree index | Sorted, balanced; O(log N) lookup | Expensive on high-write tables; wrong index = slower than seq scan |
| Clustered index | Rows ordered by PK; two-hop for secondary keys | Random PK (UUID) causes fragmentation; InnoDB only |
| Isolation levels | Read Committed (default) → Serializable | Higher isolation = lower concurrency and throughput |
| Replication | Async (lag risk) vs sync (latency cost) | Sync replication = zero RPO; async = lower latency |
| Sharding | Linear write scale-out | Cross-shard queries and transactions are complex |
| CAP / PACELC | CP vs AP; also latency vs consistency without partitions | Context-dependent; not a global choice |
| Columnar DB | OLAP: read few columns across many rows | Insert-heavy or OLTP: row store wins |
| Distributed txn | 2PC (blocking, SPOF) vs Saga (no isolation) | Outbox pattern is the pragmatic middle ground |
