# Interview Reading Material — Epsilon Staff Engineer (Batching)

3 focused study tracks. Each is structured: **Concept → What to read → What to know cold.**
Estimated total time: **6–8 hours**.

---

## Track 1 — Spark Internals (3 hours)

This is the highest-signal area. Every question about performance, debugging, and pipeline design at Epsilon will trace back to these concepts.

---

### 1.1 How Spark Executes a Job (Read First)

**Best article:**
> [Apache Spark job execution in-depth](https://codeswarm.io/2024/06/16/apache-spark-job-execution-in-depth/)

**What you need to know cold:**

```
Action triggers → Logical Plan → Catalyst Optimizer → Optimized Logical Plan
→ Physical Plan (DAG) → DAGScheduler splits into Stages → Stages → TaskSets → Tasks
```

**Stage boundaries = shuffle boundaries.**
- **Narrow transformation** (map, filter, union): no shuffle, same stage
- **Wide transformation** (groupBy, join, reduceByKey): causes shuffle, new stage starts

**Two stage types:**
- `ShuffleMapStage` — writes intermediate shuffle output files
- `ResultStage` — produces the final output of the job

**Task = 1 partition processed by 1 executor thread.** If you have 200 partitions, you get 200 tasks.

---

### 1.2 Catalyst Optimizer (What Makes DataFrames Fast)

**Best article:**
> [Mastering Spark DAGs](https://blog.devgenius.io/mastering-spark-dags-the-ultimate-guide-to-understanding-execution-ce6683ae785b)

**What it does to your query plan (in order):**
1. **Predicate pushdown** — moves filters as early as possible (before joins, before scans)
2. **Projection pruning** — drops columns not needed downstream
3. **Constant folding** — evaluates constant expressions at planning time
4. **Join reordering** — small tables joined first

**How to read a query plan:**
```scala
df.explain(true)  // shows all 4 plan stages: parsed, analyzed, optimized, physical
df.explain("formatted")  // cleaner output in Spark 3+
```

Reading a plan is read **bottom-up** — the scan is at the bottom, the action is at the top.

---

### 1.3 Shuffle Deep Dive (The Most Expensive Operation)

**Best article:**
> [How DAG Execution Plans Work](https://www.sparkcodehub.com/spark/fundamentals/how-dag-execution-plans-work)

**What triggers a shuffle:**
- `groupBy`, `join` (sort-merge join), `distinct`, `repartition`, `orderBy`
- `reduceByKey` triggers a shuffle; `mapPartitions` does NOT

**Shuffle write/read sequence:**
1. Map tasks write shuffle files to local disk
2. Network transfer happens between stages
3. Reduce tasks read and merge shuffle files

**Key config knobs:**
```
spark.sql.shuffle.partitions = 200  (default, often too high for small data, too low for large)
spark.shuffle.file.buffer = 32k     (write buffer size per shuffle file)
```

**Skew handling:** If one partition is 100x larger than others, one task takes 100x longer. Solutions:
- Salting the key (append random suffix, then reduce twice)
- AQE skew join handling (automatic in Spark 3+)

---

### 1.4 Adaptive Query Execution (AQE) — Spark 3+ Must-Know

**Best resource:**
> [Databricks AQE Documentation](https://docs.databricks.com/gcp/en/optimizations/aqe)

> [Dynamic Skew Join Fixes in Spark](https://blog.dataengineerthings.org/from-bottlenecks-to-balance-dynamic-skew-join-fixes-in-spark-5a9d2a3466ec)

**AQE is enabled by default in Spark 3.** It re-optimizes the query *at runtime* using actual shuffle statistics instead of estimates.

**4 things AQE does automatically:**

| Feature | What it does | Config |
|---|---|---|
| Coalesce partitions | Merges small post-shuffle partitions | `spark.sql.adaptive.coalescePartitions.enabled` |
| Skew join split | Splits oversized partitions, replicates the other side | `spark.sql.adaptive.skewJoin.enabled` |
| Broadcast join conversion | Converts sort-merge join to broadcast if one side is small | `spark.sql.autoBroadcastJoinThreshold` (default 10MB) |
| Empty relation propagation | Skips joins when one side is empty | automatic |

**Skew detection thresholds:**
- Partition size > 256MB **AND**
- Partition size > 10x the median partition size
→ Both must be true to trigger skew handling.

**The interview answer to "how do you handle data skew":**
> "First, I check whether AQE is enabled — it handles most cases automatically in Spark 3. For cases AQE doesn't cover (like left outer joins where the right side can't be split), I salt the key: append a random suffix 0-N, explode the small side N times, join, then aggregate again to remove the salt."

---

### 1.5 Partitioning Strategy (Know repartition vs coalesce)

**Rule:**
- `repartition(N)` — full shuffle, use when increasing partition count or when you need even distribution
- `coalesce(N)` — no shuffle, only merges existing partitions, use when reducing
- `repartitionByRange` — for range-partitioned writes (e.g., sorted Parquet)

**Partition size target:** Aim for 128MB–256MB per partition. Smaller = too many tasks (overhead). Larger = task memory pressure.

```scala
// Check partition sizes
df.rdd.mapPartitions(iter => Iterator(iter.size)).collect()
```

---

### 1.6 Broadcast Joins

Use when one table is small enough to fit in executor memory.

```scala
import org.apache.spark.sql.functions.broadcast
val result = largeDF.join(broadcast(smallDF), "key")
```

- Threshold: `spark.sql.autoBroadcastJoinThreshold` — default 10MB
- Avoids the shuffle entirely on the small side
- Best join optimization available when applicable

---

## Track 2 — Scala for Spark (2 hours)

You don't need to be a Scala expert. You need to speak confidently about the patterns Spark uses and write readable code. Focus on these 5 areas.

---

### 2.1 Interactive Practice (Do This First)

**Start here — free, browser-based, no setup:**
> [scala-exercises.org — Standard Library](https://www.scala-exercises.org/scala_tutorial/standard_library)
> [scala-exercises.org — Collections (Lists, Maps, Traversables)](https://www.scala-exercises.org/std_lib/lists)

**Spend 30 minutes here.** Work through Lists, Maps, and Options. The goal is muscle memory for `.map`, `.filter`, `.flatMap`, `.fold`, `.groupBy`.

---

### 2.2 Case Classes — The Schema Definition Pattern

**Best article:**
> [21 Days of Spark Scala: Day 3 — Case Classes](https://awstip.com/21-days-of-spark-scala-day-3-exploring-case-classes-the-building-blocks-of-functional-c4962b927b15)

**What case classes do in Spark:**

```scala
// Define your schema as a case class
case class ClickEvent(
  userId: String,
  campaignId: String,
  timestamp: Long,
  eventType: String
)

// Spark can infer the schema and give you type safety
val ds: Dataset[ClickEvent] = spark.read.json("s3://...").as[ClickEvent]

// Now you get compile-time type checking
ds.filter(_.eventType == "click").groupBy(_.campaignId).count()
```

**Why this matters for Epsilon:** Attribution pipelines ingest structured event schemas (impressions, clicks, conversions). Case classes make the schema explicit, the encoder is derived automatically, and you catch schema drift at compile time rather than runtime.

---

### 2.3 Pattern Matching — The if-else Replacement

**Best article:**
> [21 Days of Spark Scala: Day 2 — Pattern Matching](https://awstip.com/21-days-of-spark-scala-day-2-understanding-pattern-matching-a-powerful-alternative-to-if-else-21e98e4a4477)

> [7 Things Every Data Engineer Should Know About match/case in Scala](https://blog.dataengineerthings.org/7-things-every-data-engineer-should-know-about-match-case-in-scala-f222f86723f9)

```scala
// Pattern matching on types — common in pipeline transforms
def classifyEvent(event: ClickEvent): String = event.eventType match {
  case "impression" => "top-funnel"
  case "click"      => "mid-funnel"
  case "conversion" => "bottom-funnel"
  case _            => "unknown"
}

// Pattern matching on Option — avoids null checks
val result: Option[String] = maybeValue match {
  case Some(v) => Some(v.toUpperCase)
  case None    => None
}
// Or more idiomatically:
val result = maybeValue.map(_.toUpperCase)
```

---

### 2.4 Option and Either — No Nulls in Scala

```scala
// Option — value may or may not be present
def findUser(id: String): Option[User] = ...

findUser("123") match {
  case Some(user) => process(user)
  case None       => log("not found")
}

// Either — computation may succeed or fail with a reason
def parseAmount(s: String): Either[String, Double] =
  Try(s.toDouble).toEither.left.map(_.getMessage)

parseAmount("12.5")   // Right(12.5)
parseAmount("bad")    // Left("For input string: \"bad\"")
```

**Why this matters:** You will see `Option` everywhere in Spark Scala code. `getOrElse`, `flatMap`, `map` on Options is idiomatic. Never use `.get` directly (it throws on None).

---

### 2.5 A Complete Mini Spark Job in Scala (Write This Out)

```scala
import org.apache.spark.sql.{SparkSession, Dataset}
import org.apache.spark.sql.functions._

case class ClickEvent(userId: String, campaignId: String, eventType: String, amount: Double)
case class Attribution(campaignId: String, totalRevenue: Double, clickCount: Long)

object AttributionJob {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("AttributionJob")
      .getOrCreate()

    import spark.implicits._

    // Read and parse
    val events: Dataset[ClickEvent] = spark.read
      .option("header", "true")
      .csv("s3://epsilon-data/events/")
      .as[ClickEvent]

    // Filter to conversion events only
    val conversions = events.filter(_.eventType == "conversion")

    // Aggregate by campaign
    val result: Dataset[Attribution] = conversions
      .groupBy($"campaignId")
      .agg(
        sum($"amount").as("totalRevenue"),
        count("*").as("clickCount")
      )
      .as[Attribution]

    // Write output
    result.write
      .mode("overwrite")
      .partitionBy("campaignId")
      .parquet("s3://epsilon-data/attribution-output/")

    spark.stop()
  }
}
```

**Practice: Type this out yourself, don't copy-paste.** Reading and writing are different skills. Typing it once will make you speak about it naturally in the interview.

---

## Track 3 — Airflow DAG Concepts (1 hour)

You don't need production Airflow experience. You need to know the vocabulary and the mental model. Focus on concepts, not syntax.

---

### 3.1 The Official Tutorial (Best Source)

**Read this end to end — it's short (20 mins):**
> [Airflow 101: Building Your First Workflow — Official Docs](https://airflow.apache.org/docs/apache-airflow/stable/tutorial/fundamentals.html)

---

### 3.2 The Core Mental Model

```
DAG = the workflow definition (the graph)
Task = one unit of work (a node in the graph)
Operator = the type of task (what it does)
DAG Run = one execution instance of the DAG
Task Instance = one execution of one task within a DAG Run
```

**Defining task dependencies:**
```python
# These are equivalent:
extract >> transform >> load       # bitshift (most common)
transform.set_upstream(extract)    # explicit

# Fan-out (parallel branches):
extract >> [transform_A, transform_B]

# Fan-in (wait for both):
[transform_A, transform_B] >> load
```

---

### 3.3 Backfill and Catchup (Will Come Up)

**Best article:**
> [Managing Dependencies and Backfilling in Airflow](https://reintech.io/blog/managing-dependencies-backfilling-airflow)

**Key concept:** Every DAG run has a `logical_date` (previously `execution_date`) — the time interval it represents, NOT when it ran. A daily DAG running on Jan 2 has `logical_date = Jan 1`.

**Backfill = running a DAG for historical dates:**
```bash
airflow dags backfill --start-date 2024-01-01 --end-date 2024-01-31 attribution_dag
```

**Catchup:** When `catchup=True`, Airflow auto-schedules all missed runs since `start_date`. Almost always set `catchup=False` in production to avoid accidentally running 6 months of jobs when you redeploy.

**Making tasks backfill-safe (idempotent):**
- Always use `logical_date`, never `datetime.now()`
- Delete then rewrite output, don't append
- Partition output by `logical_date` so reruns overwrite cleanly

---

### 3.4 Common Operators to Know by Name

| Operator | What it does |
|---|---|
| `PythonOperator` | Runs a Python function |
| `BashOperator` | Runs a shell command |
| `SparkSubmitOperator` | Submits a Spark job |
| `EmptyOperator` | Placeholder / dependency anchor |
| `BranchPythonOperator` | Conditionally routes to different tasks |
| `ExternalTaskSensor` | Waits for a task in another DAG to complete |

---

### 3.5 What Epsilon Likely Uses

Epsilon's batch pipelines almost certainly use `SparkSubmitOperator` to submit Scala Spark jobs, with DAGs structured as multi-stage attribution pipelines:

```
ingest_raw_events >> validate_schema >> compute_last_touch_attribution
                                     >> compute_multi_touch_attribution
                                     >> [compute_last_touch_attribution,
                                         compute_multi_touch_attribution] >> aggregate_results
                                                                           >> write_to_warehouse
```

**The interview framing:**
> "I've built dependency-managed job orchestration systems — at Skydo, using EventBridge and SQS with a custom dependency resolution layer. The Airflow model maps directly: tasks are my job nodes, operators are the execution adapters, and the DAG is the dependency graph I maintain in metadata. The concepts are identical; the specific tooling is what I'd ramp up on."

---

## Quick-Reference: The 10 Numbers to Know

These come up in Spark performance discussions. Have them ready:

| Config | Default | What it controls |
|---|---|---|
| `spark.sql.shuffle.partitions` | 200 | Post-shuffle partition count |
| `spark.sql.autoBroadcastJoinThreshold` | 10 MB | Max size for broadcast join |
| `spark.sql.adaptive.skewJoin.skewedPartitionThresholdInBytes` | 256 MB | AQE skew detection threshold |
| `spark.sql.adaptive.skewJoin.skewedPartitionFactor` | 10 | AQE skew factor vs median |
| Target partition size | 128–256 MB | Rule of thumb for tuning |
| `spark.executor.memory` | 1g | Memory per executor |
| `spark.executor.cores` | 1 | Cores per executor |
| `spark.driver.memory` | 1g | Driver memory |
| HDFS default block size | 128 MB | Maps naturally to Spark partition size |
| Spark default parallelism | 2x CPU cores | For RDD operations without shuffle |

---

## Study Schedule (If Interview is in 3–5 Days)

| Day | Focus | Time |
|---|---|---|
| Day 1 | Track 1: Spark internals (Sections 1.1 → 1.4) | 2 hrs |
| Day 2 | Track 2: Scala (do exercises + write the mini job) | 2 hrs |
| Day 3 | Track 1: Finish AQE, partitioning, broadcast joins | 1 hr |
| Day 3 | Track 3: Airflow (read official tutorial + backfill article) | 1 hr |
| Day 4 | Review SCREENING-PREP-EPSILON-BATCHING.md answers out loud | 1 hr |
| Day 5 | Rest. Read the 10 numbers table. Sleep. |  |
