# Spark Architecture — Staff Engineer Deep Dive & Interview Answers

A reference for answering Apache Spark architecture questions at Staff Engineer level: **how the cluster and execution engine actually work**, **why pipelines get slow or fail**, and **what a production-scarred answer sounds like** vs a textbook one.

Complements:
- `docs/data_eng/INTERVIEW-READING-MATERIAL.md` — study links + cold facts
- `docs/data_eng/SPARK_AND_FLINK_SYSTEM_DESIGN.md` — end-to-end system design problems
- `docs/data_eng/SCREENING-PREP-EPSILON-BATCHING.md` — spoken screening answers

---

## Part 0: The One-Sentence Mental Model

> **Spark is a distributed DAG executor with a lazy, lineage-based recovery model.** You build a plan (transformations). Nothing runs until an **action**. The driver compiles that plan into stages and tasks; executors run tasks on partitions; **shuffle boundaries** are where stages split and where cost, skew, and failure concentrate. Recompute from lineage on failure; checkpoint only when lineage gets too deep or streaming needs durable offsets.

Everything below explains how that guarantee is implemented and where it leaks in production.

---

## Part 1: Cluster Architecture

### 1.1 Driver, Executors, Cluster Manager

```
┌─────────────────────────────────────────────────────────────┐
│                     Cluster Manager                          │
│            (YARN / Kubernetes / Standalone / Mesos)          │
└──────────────┬──────────────────────────────┬───────────────┘
               │ allocates                      │
               ▼                                ▼
        ┌─────────────┐                  ┌─────────────┐
        │   Driver    │◄─── heartbeats ──│  Executor   │ × N
        │             │─── task launch ─►│  (JVM)      │
        │ SparkContext│                  │  cores +    │
        │ Scheduler(s)│                  │  memory     │
        └─────────────┘                  └─────────────┘
```

| Component | Owns | Staff takeaway |
|---|---|---|
| **Driver** | SparkContext/SparkSession, DAGScheduler, TaskScheduler, metadata, `collect()` results | Single point of orchestration. OOM on driver = job dead. Never `collect()` large data. |
| **Executor** | Task threads, cached RDD/DF blocks, shuffle files on local disk | Horizontal scale unit. Memory = execution + storage + overhead + user. |
| **Cluster manager** | Containers/pods, CPU/RAM quotas, placement | You size jobs *against* YARN queues / K8s namespaces, not in a vacuum. |

**Staff-level answer:** "The driver builds and schedules the DAG; executors run tasks. The cluster manager only gives us containers — Spark owns how work is cut into stages and tasks. If the driver dies, the application dies. If an executor dies, Spark reschedules its unfinished tasks using lineage — unless shuffle data or broadcast state was only on that executor and we have to recompute upstream."

### 1.2 Application vs Job vs Stage vs Task

Know this vocabulary cold — interviewers use it as a filter:

```
Application  = one spark-submit / one SparkSession lifetime
  └── Job      = one action (count, write, collect, show, …)
        └── Stage  = set of tasks that can run without a shuffle barrier
              └── Task   = one partition × one core on one executor
```

- **Narrow transform** (`map`, `filter`, `union`, `withColumn`): same partition stays on same executor pipeline → **same stage**.
- **Wide transform** (`groupBy`, `join`, `repartition`, `distinct`, `orderBy`): data must move → **shuffle** → **new stage**.

**Two stage types:**
- `ShuffleMapStage` — produces shuffle files for the next stage
- `ResultStage` — produces the action's final output

**Staff-level answer:** "Stage boundaries are shuffle boundaries. Task count ≈ partition count for that stage. If I see 200 tasks and one runs 40 minutes while others finish in 30 seconds, I have skew — not a 'Spark is slow' problem."

### 1.3 RDD → DataFrame → Dataset (why staff engineers almost never start on RDDs)

| API | Abstraction | Optimizer | When you still use it |
|---|---|---|---|
| **RDD** | Typed distributed collection, lineage graph | None (you are the optimizer) | Custom partitioning, low-level control, rare legacy |
| **DataFrame** | Untyped rows + schema | Catalyst + Tungsten | Default for ETL/SQL/analytics |
| **Dataset[T]** | Typed (Scala/Java encoders) | Same Catalyst path | Type-safe Scala pipelines |

**Staff-level answer:** "RDDs are the lineage/fault-tolerance substrate. Production code should be DataFrames/Datasets so Catalyst can push down predicates, prune columns, pick join strategies, and Tungsten can run whole-stage codegen. Dropping to RDDs for 'performance' usually *loses* performance unless you have a very specific reason."

---

## Part 2: How a Job Actually Executes

### 2.1 Laziness and the Action Trigger

```
Transformations (lazy)          Action (eager)
df.filter(...)                  .write.parquet(...)
  .select(...)          ──►     .count()
  .groupBy(...).agg(...)        .collect() / .show()
```

Until an action:
1. Spark builds an **unresolved logical plan**
2. Analyzer resolves catalogs/schemas → **analyzed logical plan**
3. Catalyst optimizes → **optimized logical plan**
4. Spark plans physical operators → **physical plan** (may have multiple candidates)
5. DAGScheduler cuts physical plan into **stages** at shuffle boundaries
6. TaskScheduler launches **TaskSets**; executors run tasks

```scala
df.explain(true)           // parsed → analyzed → optimized → physical
df.explain("formatted")    // Spark 3+ readable physical plan
```

Read plans **bottom-up**: scan at the bottom, sink/action at the top.

**Staff-level answer:** "Nothing runs until an action. That's why chaining 20 transforms is free until `.write` — and why calling `.count()` 'just to check' in the middle of a pipeline can accidentally materialize a multi-TB shuffle."

### 2.2 Catalyst Optimizer (the reason DataFrames win)

Ordered mental model of what Catalyst does:

1. **Analysis** — resolve columns, types, UDFs, catalogs
2. **Logical optimization** — predicate pushdown, projection pruning, constant folding, boolean simplification, join reordering (cost-based when stats exist)
3. **Physical planning** — choose BroadcastHashJoin vs SortMergeJoin vs ShuffleHashJoin, etc.
4. **Code generation** — Tungsten whole-stage codegen turns operator pipelines into tight Java bytecode

**What to look for in `explain`:**
- `PushedFilters` / `PartitionFilters` — did your WHERE hit the storage layer?
- `ColumnPruning` — are you reading only needed columns (critical for Parquet/ORC)?
- `BroadcastHashJoin` vs `SortMergeJoin` — broadcast = no shuffle of large side
- `Exchange` / `ShuffleExchange` — every Exchange is money (CPU + network + disk)

**Staff-level answer:** "I debug slow jobs by reading the physical plan, not by guessing configs. If I don't see pushdown on a Parquet scan, or I see an unexpected SortMergeJoin on a small dimension table, that's the bug — not 'need more executors.'"

### 2.3 Tungsten (execution engine under Catalyst)

Tungsten is the runtime that makes Catalyst plans *fast*:

- **Off-heap / binary row format** — less JVM object overhead, less GC
- **Whole-stage code generation** — fuses operators (e.g. scan → filter → project) into one function instead of virtual calls per row
- **Cache-aware algorithms** — sort/agg designed around CPU cache lines
- **Unsafe / managed memory** for shuffle and aggregation buffers

**Staff-level gotcha:** UDFs (especially Python UDFs) **break** whole-stage codegen and force row-by-row transitions. Prefer built-in SQL functions, or Pandas/Arrow vectorized UDFs when you must custom-code. A "simple" Python UDF can turn a healthy job into a CPU inferno.

### 2.4 Lineage and Fault Tolerance

- Every RDD/DF partition remembers **how to recompute** itself from parents (lineage).
- Executor loss → recompute lost partitions from lineage (narrow deps: only that partition; wide deps: may need shuffle map outputs).
- **Shuffle map outputs** live on the executor's local disk. If that executor dies, downstream stages that need those files must **recompute the upstream ShuffleMapStage** (or use external shuffle service to keep files after executor exit).
- **Checkpointing** cuts lineage: writes data to reliable storage and starts a new lineage root. Costly; use when lineage is deep (iterative ML) or for Structured Streaming offsets/state.

**External Shuffle Service:** lets executors be dynamic (dynamic allocation) without losing shuffle files when an executor is removed.

**Staff-level answer:** "Spark's default recovery is recompute-from-lineage, not replay-from-WAL like a streaming engine's state store. That's cheap for narrow pipelines and expensive when you lose shuffle files mid-job — which is why long shuffles + aggressive dynamic allocation without an external shuffle service is a classic footgun."

---

## Part 3: Shuffle — Where Staff Engineers Spend Their Time

### 3.1 What a Shuffle Is

A shuffle is a **distributed all-to-all redistribute**:

1. **Map side (write):** each task sorts/partitions records by target reducer, spills to local disk as shuffle blocks
2. **Transfer:** reducers fetch blocks over the network (or via external shuffle service)
3. **Reduce side (read):** merge/sort/aggregate incoming blocks

This hits **CPU (sort/hash) + disk + network** simultaneously. It is usually the dominant cost in analytics jobs.

### 3.2 Config Knobs That Actually Matter

| Config | Default | Staff guidance |
|---|---|---|
| `spark.sql.shuffle.partitions` | 200 | Often wrong. Too high → tiny tasks + scheduler overhead. Too low → huge partitions + OOM/spills. Target ~128–256MB shuffle partition input. |
| `spark.sql.adaptive.enabled` | true (3.x) | Leave on. Lets Spark coalesce/split at runtime. |
| `spark.sql.autoBroadcastJoinThreshold` | 10MB | Raise carefully if dim tables are larger but still fit executor memory. |
| `spark.serializer` | Java (legacy) | Use Kryo for RDD workloads; DataFrames already use Tungsten binary. |
| `spark.shuffle.service.enabled` | false | Required for safe dynamic allocation. |

### 3.3 Data Skew

**Symptom:** stage progress bar stuck at 99%; one task runtime ≫ others; one executor disk/CPU pegged.

**Mitigations (in order staff engineers try):**

1. **AQE skew join** (Spark 3+) — splits oversized shuffle partitions automatically when size ≫ median
2. **Broadcast the small side** — eliminate shuffle of the large side
3. **Salting** — append random `0..N` to hot keys, explode/replicate the other side, join, then re-aggregate
4. **Pre-aggregate** — reduce row count before the wide transform
5. **Isolate hot keys** — process skewed keys on a separate path

**Staff skew answer:** "First confirm AQE is on and look at the Spark UI stage task duration histogram. If it's a join and one key is hot, AQE skew join or salting. If it's a groupBy of a power-law key, partial aggregate then final aggregate, or salt. Adding executors does nothing for a single hot partition."

### 3.4 `repartition` vs `coalesce`

| API | Shuffle? | Use when |
|---|---|---|
| `repartition(n)` | Yes (full) | Increase partitions, or rebalance after filter skew |
| `repartition(cols...)` | Yes | Partition by business key for locality / write layout |
| `coalesce(n)` | No (narrow merge) | Reduce partitions before write; cannot increase evenly |
| `repartitionByRange` | Yes | Sorted/range layout for range queries |

**Partition size target:** ~128–256MB per partition after filters. Match input file/split size where possible (Parquet row groups, HDFS blocks historically 128MB).

---

## Part 4: Memory, GC, and Executor Sizing

### 4.1 Unified Memory Model (Spark 1.6+)

Executor heap roughly:

```
spark.executor.memory
├── Reserved / user (spark.executor.memoryOverhead is OFF-heap container extra)
└── Unified memory (spark.memory.fraction, default ~0.6 of heap)
    ├── Execution (shuffles, joins, sorts, aggregations)
    └── Storage (cache/persist)
         ↕ can borrow from each other under pressure
```

- **Execution memory pressure** → spill to disk (slow, not always fatal)
- **Storage pressure** → evict cached blocks (recompute later)
- **Driver memory** — separate; used for scheduling state, broadcast build on driver path, `collect` results

**`spark.executor.memoryOverhead`:** off-heap (YARN/K8s container) for JVM overhead, off-heap Tungsten, Netty, Python worker memory. Undersizing overhead → **container killed by YARN/K8s**, not a clean Spark OOM — classic "works locally, dies on cluster" bug.

### 4.2 Sizing Heuristic Staff Engineers Use

Rough starting point (then measure):

- Cores per executor: **4–5** (not 1, not 20 — sweet spot for HDFS/S3 throughput vs task concurrency)
- Memory per executor: enough that **per-task working set** for your widest shuffle fits without constant spill
- Prefer **more medium executors** over few gigantic ones (GC pauses, failure blast radius)
- For PySpark: budget overhead for Python workers; prefer JVM/SQL expressions over Python UDFs

**Staff-level answer:** "I size for shuffle working set and overhead kills, not for 'max out the node.' A common failure is large executor heap with tiny memoryOverhead — K8s OOMkills the pod while Spark heap still looks fine."

### 4.3 Caching

```scala
df.persist(StorageLevel.MEMORY_AND_DISK)  // common production default
df.unpersist()
```

Cache only when **reused multiple times** in the same job/app (iterative algorithms, multi-branch writes). Caching a one-shot ETL step wastes memory and can **increase** GC pressure.

---

## Part 5: Joins and AQE (Spark 3 Must-Know)

### 5.1 Join Strategies

| Strategy | When | Cost |
|---|---|---|
| **Broadcast Hash Join** | One side < broadcast threshold / fits memory | Best — no large-side shuffle |
| **Sort Merge Join** | Default for large-large equi-joins | Two shuffles + sort |
| **Shuffle Hash Join** | When sort can be avoided / certain configs | Shuffle + hash build |
| **Cartesian / Nested Loop** | Non-equi or unconstrained | Avoid unless intentional and tiny |

```scala
import org.apache.spark.sql.functions.broadcast
large.join(broadcast(dim), "id")
```

### 5.2 Adaptive Query Execution (AQE)

AQE re-optimizes **at runtime** using real shuffle stats:

| Feature | What it fixes |
|---|---|
| Coalesce post-shuffle partitions | 200 tiny partitions → fewer healthy ones |
| Skew join optimization | Split giant partitions; replicate other side |
| Dynamic join strategy | Convert SMJ → broadcast if runtime size is small |
| Empty relation propagation | Skip useless work |

**Staff-level answer:** "AQE doesn't remove the need to design partitions and joins — it removes a class of static-planning mistakes. I still set sensible shuffle partition baselines and still broadcast known-small dims explicitly when I want a guarantee."

---

## Part 6: Structured Streaming Architecture

### 6.1 Mental Model

Structured Streaming treats a stream as an **unbounded table**. Micro-batches (default) or continuous processing (rare/specialized) run the same Catalyst plan repeatedly.

```
Source (Kafka/files/…)
    → micro-batch DataFrame
    → same transformations as batch
    → Sink (Kafka/Delta/Parquet/…)
Checkpoint directory stores:
  - offsets processed
  - state store snapshots (for stateful ops)
  - sink commit coordination for end-to-end exactly-once (with capable sinks)
```

### 6.2 Triggers, Watermarks, Output Modes

- **Trigger:** `ProcessingTime("10 seconds")`, `AvailableNow`, `Once`, continuous
- **Watermark:** bound late data for stateful aggregations; enables state cleanup
- **Output modes:** `append` (new rows only — needs watermark for aggs), `update`, `complete`

**Exactly-once:** needs (1) replayable source offsets in checkpoint, (2) idempotent or transactional sink (e.g. Delta/Kafka transactional). Checkpoint loss → replay/duplicates or gaps depending on sink.

**Staff-level answer:** "Spark streaming is micro-batch DAG execution with checkpointed offsets — great for ETL/analytics SLAs of seconds to minutes. If you need sub-100ms event-at-a-time with rich keyed state and CEP, that's Flink territory. Don't sell Spark as a fraud-decision engine in the hot path."

### 6.3 State Store

Stateful aggregations/joins keep state in Spark's **state store** (HDFS-backed versioned snapshots + RocksDB-based store in modern versions for large state). State grows with key cardinality × windows — watermarking is how you stop unbounded state.

---

## Part 7: File Layout and I/O (Often the Real Bottleneck)

Staff engineers diagnose "Spark is slow" as **storage layout** problems first:

- **Too many small files** → task launch overhead, S3 LIST costs, poor throughput
- **Not partitioned / wrong partition columns** → full scans
- **No predicate pushdown** → reading unused row groups
- **Skewed partition folders** (`country=US` huge) → same as compute skew
- **Schema evolution / mergeSchema surprises** — expensive job starts

**Write path hygiene:**
- Coalesce/repartition before write to target file size (~128–512MB)
- Partition by low-cardinality columns that match query filters (`date`, not `user_id`)
- Prefer columnar (Parquet/ORC) + compression (zstd/snappy)
- Use a table format (Delta/Iceberg/Hudi) for ACID, time travel, compaction

---

## Part 8: Observability — How You Actually Debug

**Spark UI tabs that matter:**

| Tab | What you look for |
|---|---|
| Jobs / Stages | Stage duration, failed tasks, retry storms |
| Tasks | Duration skew, spill bytes, GC time, shuffle read/write |
| Storage | Cache hit/eviction |
| Environment | Effective configs (what actually ran) |
| SQL | Whole-stage codegen, join strategy, Exchange ops |

**Metrics vocabulary:**
- **Shuffle read/write bytes** — cost center
- **Spill (memory → disk)** — execution memory undersized or partition too big
- **GC time** — executor sizing / object churn (UDFs, row-based APIs)
- **Scheduler delay** — too many tiny tasks

**Staff-level answer:** "I don't tune blind. UI first: find the longest stage, check whether it's shuffle-bound, skew-bound, or scan-bound. Fix the plan or the data layout before changing cluster size."

---

## Part 9: Common Follow-Ups (Answer These Cleanly)

**"RDD vs DataFrame?"**  
DataFrames go through Catalyst/Tungsten; RDDs don't. Prefer DataFrames. RDD when you need custom partitioner or lineage control Catalyst can't express.

**"map vs mapPartitions?"**  
`map` per-record; `mapPartitions` once per partition — use for heavy setup (DB connection per partition), but prefer DataFrame ops when possible.

**"reduceByKey vs groupByKey?"** (RDD era, still asked)  
`reduceByKey` combines locally before shuffle; `groupByKey` shuffles all values — often a memory bomb.

**"How does Spark achieve fault tolerance?"**  
Lineage recomputation + optional checkpointing; shuffle service for surviving executor loss with dynamic allocation; streaming checkpoints for offsets/state.

**"How do you guarantee exactly-once in a batch ETL?"**  
Idempotent outputs: write to staging then atomic publish (manifest/table swap), or use transactional table formats (Delta/Iceberg). Spark tasks are at-least-once; **you** make sinks idempotent.

**"Dynamic allocation?"**  
Scale executors up/down with load. Needs external shuffle service so releasing an executor doesn't delete shuffle files still needed by running stages.

**"Broadcast variables vs broadcast join?"**  
Broadcast variable: explicit read-only share of a value to tasks (RDD era / custom). Broadcast join: Catalyst/AQE strategy to replicate a small relation for hash join.

---

## Part 10: Real-World Usage — Signal Questions

Textbook vs production — same format as the Redis/Temporal staff docs.

### Q1. "Tell me about a Spark job that was slow, and how you fixed it."

- **Textbook:** "I increased executors and partitions."
- **Production:** Names the stage, shows skew or unexpected SortMergeJoin, mentions `explain`/UI evidence, and a concrete fix (broadcast, salt, AQE, file compaction, remove Python UDF). Cluster scale is last resort.

### Q2. "How do you choose `spark.sql.shuffle.partitions`?"

- **Textbook:** "Default 200" or "set it to a big number."
- **Production:** Targets partition size from shuffle bytes / N; uses AQE coalesce; different values for different job classes; knows 200 can be both too high (small dims) and too low (multi-TB joins).

### Q3. "Your job is stuck at 99%. What's going on?"

- **Textbook:** "Maybe a straggler."
- **Production:** Opens stage task metrics, finds one partition with huge shuffle read, identifies hot key, discusses AQE skew join vs salting vs isolating hot keys. Mentions speculative execution as a band-aid for transient stragglers, not for systemic skew.

### Q4. "Executors keep getting killed. Heap looks fine."

- **Textbook:** "OOM — increase memory."
- **Production:** Checks `memoryOverhead`, off-heap, Python workers, YARN/K8s container limits vs Spark heap; recognizes "heap OK, container OOMKilled" pattern.

### Q5. "When would you not use Spark?"

- **Textbook:** "When data is small."
- **Production:** Small data (single-node pandas/SQL is simpler); sub-100ms event decisions (Flink); low-latency point lookups (OLTP DB / serving store); simple scheduled SQL already handled by the warehouse. Spark's fixed cost (driver, scheduling, JVMs) dominates tiny jobs.

### Q6. "How do you design idempotent daily pipelines?"

- **Textbook:** "Overwrite the table."
- **Production:** Partition-by-date overwrite or MERGE with deterministic keys; checkpoint/staging paths; late-data reprocessing strategy; data quality gates before publish; time travel rollback on bad deploys (Delta/Iceberg).

### Q7. "PySpark or Scala for production?"

- **Textbook:** "Either is fine."
- **Production:** PySpark fine for orchestration and SQL-heavy jobs; JVM (Scala/Java) when UDF-heavy or tight CPU loops; **avoid Python row UDFs**; prefer Spark SQL expressions / Pandas UDF with Arrow. Speaks to serialization and worker process overhead honestly.

### Q8. "How do you handle a multi-hop pipeline where job 3 depends on job 2?"

- **Textbook:** "Run them in order."
- **Production:** Orchestrator (Airflow/Dagster) with data-availability sensors, not just `SparkSubmit` chaining; partition success markers; backfill story; partial-recompute without full replay of upstream raw data.

---

## Part 11: Staff-Level Trade-off Summary

| Topic | One-liner | Deeper point |
|---|---|---|
| Driver/executors | Driver schedules; executors compute | Driver death kills app; never pull big data to driver |
| Stages/tasks | Stage = no-shuffle pipeline; task = 1 partition | Skew = one task owns the stage SLA |
| Catalyst | Optimizes logical → physical plans | Prefer DataFrames so pushdown/codegen happen |
| Tungsten | Binary rows + whole-stage codegen | UDFs (esp. Python) defeat codegen |
| Shuffle | All-to-all redistribute | Primary cost center; tune partitions + joins first |
| AQE | Runtime re-optimization | Fixes many static plan mistakes; not a substitute for layout design |
| Memory | Unified execution/storage pool | Container overhead kills ≠ Spark heap OOM |
| Lineage | Recompute on failure | Shuffle file loss forces upstream recompute |
| Streaming | Micro-batch unbounded tables | Seconds–minutes latency; Flink for true event-time CEP |
| Storage | File layout dominates | Small files / bad partitions look like "compute" problems |

---

## Part 12: 60-Second Spoken Answer (Memorize This)

> "Spark runs as a driver plus executors on a cluster manager. Transformations build a lazy plan; an action triggers Catalyst optimization and a physical DAG. The DAGScheduler splits that DAG into stages at shuffle boundaries, and each partition becomes a task on an executor. Narrow transforms pipeline inside a stage; wide transforms shuffle and start a new stage. Fault tolerance is lineage recomputation, with checkpoints when lineage or streaming state needs a durable cut. In production, performance is almost always about shuffle size, skew, join strategy, and file layout — not about blindly adding machines. I debug with the Spark UI and `explain`, fix the plan and data layout, then size executors for working set and memory overhead."

---

## Summary

- **Architecture:** driver-orchestrated DAG execution over partitioned data on executors.
- **Core cost:** shuffles and skew; optimize joins, partitions, and storage layout before scale-out.
- **Core advantage:** Catalyst + Tungsten on DataFrames, unified batch/streaming API, mature lakehouse ecosystem.
- **Staff signal:** talk in stages/tasks/Exchange/spill/skew, cite UI evidence, and know when *not* to use Spark.

Use Part 1–8 to answer "how does Spark work." Use Part 10 to sound like you've operated it at scale.
