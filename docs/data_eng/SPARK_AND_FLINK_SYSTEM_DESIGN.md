# System Design Problems: Spark & Flink

---

## Table of Contents

- [Part 1: Apache Spark — Real-Time Log Analytics Pipeline](#part-1-apache-spark--real-time-log-analytics-pipeline)
- [Part 2: Apache Flink — Fraud Detection System](#part-2-apache-flink--fraud-detection-system)
- [Spark vs Flink Comparison](#spark-vs-flink-comparison)

---

## Part 1: Apache Spark — Real-Time Log Analytics Pipeline

### Problem Statement

Design a system to **ingest, process, and analyze web server logs** from 500+ microservices generating ~10 million log events per minute. The system must:

- Detect error spikes in near real-time (within 30 seconds)
- Compute hourly/daily aggregations (request counts, latency percentiles, error rates)
- Store processed results for dashboarding
- Support ad-hoc queries on historical data

**Constraint: Use only Apache Spark.**

---

### Architecture

```
Raw Logs (Files/S3)
        │
        ▼
┌───────────────────┐
│  Spark Structured  │  ← Streaming ingestion
│     Streaming      │    (readStream from S3/Kafka-less file source)
└────────┬──────────┘
         │
         ▼
┌───────────────────┐
│   Spark Streaming  │  ← Windowed aggregations
│   Processing Layer │    (5-min, 1-hour windows)
└────────┬──────────┘
         │
    ┌────┴────┐
    ▼         ▼
┌───────┐ ┌─────────────┐
│ Delta │ │  Parquet on  │
│ Lake  │ │  S3/HDFS     │
└───────┘ └─────────────┘
         │
         ▼
┌───────────────────┐
│   Spark SQL /      │  ← Batch analytics & ad-hoc queries
│   Spark ML         │
└───────────────────┘
```

---

### Component Breakdown

#### 1. Ingestion — Spark Structured Streaming

```python
df = spark.readStream \
    .format("cloudFiles")        # Auto Loader (S3 trigger)
    .option("cloudFiles.format", "json") \
    .load("s3://logs/raw/")
```

- Uses **Auto Loader** or file-based streaming source
- Schema inference with `cloudFiles.schemaLocation`
- Handles late data with **watermarking**

#### 2. Real-Time Error Detection — Windowed Aggregations

```python
from pyspark.sql.functions import window, col

error_spikes = df \
    .filter(col("status_code") >= 500) \
    .withWatermark("timestamp", "1 minute") \
    .groupBy(
        window("timestamp", "30 seconds"),
        "service_name"
    ) \
    .count() \
    .filter(col("count") > 1000)   # spike threshold
```

- **Sliding windows** for continuous monitoring
- Watermark handles out-of-order events
- Output trigger every 10 seconds

#### 3. Storage — Delta Lake (via Spark)

```python
# Write streaming results
error_spikes.writeStream \
    .format("delta") \
    .outputMode("append") \
    .option("checkpointLocation", "s3://checkpoints/errors/") \
    .start("s3://processed/error_spikes/")

# Write hourly batch aggregations
hourly_stats.write \
    .format("delta") \
    .mode("overwrite") \
    .partitionBy("date", "hour") \
    .save("s3://processed/hourly_stats/")
```

#### 4. Batch Analytics — Spark SQL

```python
spark.sql("""
    SELECT
        service_name,
        date_trunc('hour', timestamp) AS hour,
        COUNT(*) AS total_requests,
        SUM(CASE WHEN status_code >= 500 THEN 1 ELSE 0 END) AS errors,
        percentile_approx(latency_ms, 0.99) AS p99_latency
    FROM delta.`s3://processed/hourly_stats/`
    WHERE date >= current_date() - 7
    GROUP BY 1, 2
    ORDER BY errors DESC
""")
```

#### 5. Anomaly Detection — Spark MLlib

```python
from pyspark.ml.clustering import KMeans
from pyspark.ml.feature import VectorAssembler

assembler = VectorAssembler(
    inputCols=["request_count", "error_rate", "avg_latency"],
    outputCol="features"
)
kmeans = KMeans(k=3, seed=42)
model = kmeans.fit(assembler.transform(hourly_features))
```

---

### Spark-Only Capabilities Used

| Need | Spark Feature |
|---|---|
| Streaming ingestion | Structured Streaming + Auto Loader |
| Real-time aggregations | Windowed ops + Watermarking |
| Fault tolerance | Checkpointing |
| ACID storage | Delta Lake (Spark native) |
| Historical queries | Spark SQL |
| ML anomaly detection | Spark MLlib |
| Schema evolution | Delta Lake schema merge |
| Backfill/replay | Batch `spark.read` on same Delta tables |

---

### Key Interview Discussion Points

1. **Exactly-once semantics** — achieved via Spark checkpointing + Delta Lake ACID transactions
2. **Backpressure** — Spark Structured Streaming auto-adjusts `maxFilesPerTrigger`
3. **Scalability** — horizontal scaling via adding executor nodes; partitioning by `service_name` + `date`
4. **Late data** — watermark set to `"5 minutes"` for tolerance, with `append` output mode
5. **Hot partitions** — `DISTRIBUTE BY service_name` with salting for high-traffic services

---

## Part 2: Apache Flink — Fraud Detection System

### Problem Statement

Design a system to **detect fraudulent transactions in real-time** for a payment platform processing **5 million transactions per minute** across 50 million users. The system must:

- Flag suspicious transactions within **200ms** of occurrence
- Track user spending patterns over time (sliding windows)
- Detect velocity abuse (too many transactions in short bursts)
- Block fraud before payment settles
- Never lose an event, even during failures

**Constraint: Use only Apache Flink.**

---

### Architecture

```
Payment Events (Kafka)
        │
        ▼
┌─────────────────────┐
│   Flink Source       │  ← KafkaSource connector
│   (Event Ingestion)  │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│   Flink CEP          │  ← Complex Event Processing
│   (Pattern Matching) │    detects fraud patterns
└──────────┬──────────┘
           │
      ┌────┴─────┐
      ▼          ▼
┌──────────┐ ┌──────────────┐
│ Flink    │ │  Flink        │
│ Keyed    │ │  Broadcast    │
│ State    │ │  State        │
│(per user)│ │(fraud rules)  │
└──────────┘ └──────────────┘
           │
           ▼
┌─────────────────────┐
│  Flink Sink          │  ← KafkaSink (alerts) + FileSink (audit)
└─────────────────────┘
```

---

### Component Breakdown

#### 1. Ingestion — Flink Kafka Source

```java
KafkaSource<Transaction> source = KafkaSource.<Transaction>builder()
    .setBootstrapServers("kafka:9092")
    .setTopics("payment-transactions")
    .setGroupId("fraud-detector")
    .setStartingOffsets(OffsetsInitializer.latest())
    .setValueOnlyDeserializer(new TransactionDeserializer())
    .build();

DataStream<Transaction> transactions = env
    .fromSource(source, WatermarkStrategy
        .<Transaction>forBoundedOutOfOrderness(Duration.ofSeconds(5))
        .withTimestampAssigner((tx, ts) -> tx.getEventTime()),
        "Kafka Source");
```

- **Event-time processing** with watermarks for out-of-order tolerance
- Bounded out-of-orderness of 5 seconds

#### 2. Velocity Check — Keyed Windows

```java
DataStream<Alert> velocityAlerts = transactions
    .keyBy(tx -> tx.getUserId())
    .window(SlidingEventTimeWindows.of(
        Time.minutes(1),   // window size
        Time.seconds(10)   // slide interval
    ))
    .aggregate(new TransactionCountAgg(), new VelocityAlertFunction());

// Flag if > 10 transactions in any 1-minute window
public class VelocityAlertFunction
        extends ProcessWindowFunction<Long, Alert, String, TimeWindow> {
    @Override
    public void process(String userId, Context ctx,
                        Iterable<Long> counts, Collector<Alert> out) {
        long count = counts.iterator().next();
        if (count > 10) {
            out.collect(new Alert(userId, "VELOCITY_ABUSE", ctx.window()));
        }
    }
}
```

#### 3. Pattern Detection — Flink CEP

```java
// Detect: small test charge followed by large charge within 5 minutes
Pattern<Transaction, ?> fraudPattern = Pattern
    .<Transaction>begin("probe")
        .where(tx -> tx.getAmount() < 1.00)   // micro test charge
    .followedBy("large_charge")
        .where(tx -> tx.getAmount() > 500.00)
    .within(Time.minutes(5));

PatternStream<Transaction> patternStream =
    CEP.pattern(transactions.keyBy(tx -> tx.getUserId()), fraudPattern);

DataStream<Alert> cepAlerts = patternStream.select(
    (Map<String, List<Transaction>> match) -> {
        Transaction probe = match.get("probe").get(0);
        Transaction large = match.get("large_charge").get(0);
        return new Alert(probe.getUserId(), "PROBE_AND_CHARGE", large.getAmount());
    }
);
```

#### 4. Dynamic Fraud Rules — Flink Broadcast State

```java
// Rules stream (from a control topic, updated without restart)
DataStream<FraudRule> rulesStream = env
    .fromSource(rulesKafkaSource, WatermarkStrategy.noWatermarks(), "Rules");

MapStateDescriptor<String, FraudRule> ruleStateDesc =
    new MapStateDescriptor<>("fraud-rules", String.class, FraudRule.class);

BroadcastStream<FraudRule> broadcastRules =
    rulesStream.broadcast(ruleStateDesc);

// Apply dynamic rules to every transaction
DataStream<Alert> dynamicAlerts = transactions
    .keyBy(tx -> tx.getUserId())
    .connect(broadcastRules)
    .process(new DynamicRuleEvaluator(ruleStateDesc));
```

- Rules (e.g., block country X, flag amount > $N) updated **at runtime**
- No restart needed — rules broadcast to all parallel workers

#### 5. Per-User Spending State — Flink Keyed State

```java
public class SpendingPatternFunction
        extends KeyedProcessFunction<String, Transaction, Alert> {

    private ValueState<Double> dailySpendState;
    private ValueState<Long> lastTxTimeState;

    @Override
    public void open(Configuration cfg) {
        dailySpendState = getRuntimeContext().getState(
            new ValueStateDescriptor<>("daily-spend", Double.class));
        lastTxTimeState = getRuntimeContext().getState(
            new ValueStateDescriptor<>("last-tx-time", Long.class));
    }

    @Override
    public void processElement(Transaction tx, Context ctx,
                               Collector<Alert> out) throws Exception {
        double spend = dailySpendState.value() == null ? 0 : dailySpendState.value();
        spend += tx.getAmount();
        dailySpendState.update(spend);

        // Alert if daily spend exceeds $5000
        if (spend > 5000.0) {
            out.collect(new Alert(tx.getUserId(), "DAILY_LIMIT_BREACH", spend));
        }

        // Reset state at midnight using a timer
        ctx.timerService().registerEventTimeTimer(endOfDay(tx.getEventTime()));
    }

    @Override
    public void onTimer(long timestamp, OnTimerContext ctx,
                        Collector<Alert> out) throws Exception {
        dailySpendState.clear();  // Reset daily spend at midnight
    }
}
```

#### 6. Output — Flink Sinks

```java
// Fraud alerts → Kafka (to block payment in real-time)
KafkaSink<Alert> alertSink = KafkaSink.<Alert>builder()
    .setBootstrapServers("kafka:9092")
    .setRecordSerializer(KafkaRecordSerializationSchema.builder()
        .setTopic("fraud-alerts")
        .setValueSerializationSchema(new AlertSerializer())
        .build())
    .setDeliveryGuarantee(DeliveryGuarantee.EXACTLY_ONCE)
    .build();

// Audit log → Parquet files on S3
FileSink<Alert> auditSink = FileSink
    .forBulkFormat(new Path("s3://fraud-audit/"),
                   ParquetAvroWriters.forReflectRecord(Alert.class))
    .withRollingPolicy(OnCheckpointRollingPolicy.build())
    .build();

unionAlerts.sinkTo(alertSink);
unionAlerts.sinkTo(auditSink);
```

---

### Flink-Only Capabilities Used

| Need | Flink Feature |
|---|---|
| Real-time ingestion | `KafkaSource` with event-time watermarks |
| Velocity detection | Sliding `KeyedWindows` |
| Pattern matching | Flink CEP (Complex Event Processing) |
| Per-user state | `KeyedProcessFunction` + `ValueState` |
| Dynamic rule updates | **Broadcast State** |
| Exactly-once output | `DeliveryGuarantee.EXACTLY_ONCE` on KafkaSink |
| Fault tolerance | Flink Checkpointing (incremental RocksDB) |
| Audit storage | `FileSink` (Parquet / S3) |
| Late event handling | Watermarks + Side outputs |
| Timers | `timerService()` for TTL/resets |

---

### Key Interview Discussion Points

1. **Latency** — Flink processes each event record-by-record (not micro-batch like Spark), achieving sub-100ms latency
2. **State backend** — Use `RocksDBStateBackend` for large per-user state that doesn't fit in memory
3. **Exactly-once** — Flink's two-phase commit protocol with Kafka ensures no duplicate alerts
4. **Scalability** — `keyBy(userId)` distributes load; increase parallelism by adding task slots
5. **CEP timeout** — Use `PatternProcessFunction` with `TimedOutPartialMatchHandler` to handle incomplete patterns
6. **Savepoints** — Use Flink savepoints to upgrade fraud rules logic with zero data loss

---

## Spark vs Flink Comparison

| Criteria | Spark | Flink |
|---|---|---|
| Processing model | Micro-batch | True event-at-a-time streaming |
| Latency | ~100–500ms | ~10–50ms |
| State management | External store often needed | Native, low-latency keyed state |
| Complex Event Processing | Not native | Built-in CEP library |
| Event-time support | Good | First-class citizen |
| ML support | Excellent (MLlib) | Limited |
| Batch + Streaming | Unified (Spark SQL) | Unified (Table API) |
| Best for | Analytics, ETL, ML pipelines | Fraud detection, real-time alerting |
| Storage layer | Delta Lake (native) | External (Kafka, S3, etc.) |
| Fault tolerance | Checkpointing | Checkpointing + Savepoints |

### Rule of Thumb

- Use **Spark** when you need rich analytics, ML, SQL queries, and batch+streaming in one engine
- Use **Flink** when you need sub-100ms latency, complex stateful event processing, or CEP patterns
