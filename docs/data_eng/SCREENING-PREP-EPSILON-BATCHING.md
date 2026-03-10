# Screening Prep — Staff Engineer, Batching @ Epsilon

**Role:** Staff / Lead / Principal Engineer — Attribution & Forecasting (Batching)
**Company:** Epsilon (part of Publicis Groupe)
**Interviewer:** Miruthujay (likely a hiring manager or senior tech lead)

---

## 1. Company & Role Context (Know This Cold)

**What Epsilon does:**
Epsilon is the global leader in data-driven, omnichannel marketing technology. They power personalized marketing campaigns for 15 of the top 20 global brands (Walgreens, Nike, Dell, etc.) and 8 of the top 10 Fortune 500 companies. Their India engineering center is heavily focused on data platform, attribution, and forecasting engines.

**What this team does:**
The Attribution & Forecasting team builds the engines that tell marketers: *"Which touchpoint drove this conversion? What will next quarter's campaign performance look like?"* This is compute-intensive, data-heavy work — think petabyte-scale Spark jobs running on Hadoop/cloud clusters, DAG-orchestrated pipelines, and SQL-heavy transformation layers sitting on top of data warehouses.

**Why "Batching" matters here:**
Attribution and forecasting are inherently batch workloads — you ingest massive datasets (impression logs, click streams, conversion events), run multi-stage Spark transformations, aggregate across terabytes, and produce model outputs that feed downstream dashboards and campaign engines. The "Staff" here owns the architecture of these batch pipelines end-to-end.

---

## 2. Honest Gap Analysis (Before We Get to Answers)

| JD Requirement | Your Profile | Verdict |
|---|---|---|
| 12+ years Scala | Not in resume | **Critical gap — address head-on** |
| Apache Spark (extensive) | Goldman Sachs — petabyte-scale Spark | Strong match |
| Hadoop (HDFS, YARN, MapReduce) | Not explicitly listed | Address with GS context |
| Python for infra modules | Listed, used at Skydo | Match |
| SQL / RDBMS / Warehouse (TB scale) | BigQuery, Snowflake, Redshift | Strong match |
| DAG workflow (build/schedule/maintain) | Distributed job scheduling at Skydo | Partial — no Airflow by name |
| AWS + GCP | Both in resume | Strong match |
| Mentor junior staff | 8 engineers directly mentored | Strong match |
| Docker / Kubernetes | Both in resume | Match |
| Databricks | Not in resume | Nice-to-have, manageable |
| ELK stack | Elasticsearch listed | Partial match |
| 12+ years total experience | 10+ years | Minor gap |

**The elephant in the room: Scala.** The JD is explicit: "12+ years in Scala." Your resume lists Java, Kotlin, Python, Go. You need a clear, confident answer for this — see Section 4.

---

## 3. Likely Screening Questions & Strong Answers

### Q1: Walk me through your background and why this role interests you.

**Answer:**
> "I'm currently Engineering Manager at Skydo, a fintech for international B2B payments, where I own the full platform — payments, settlement, reconciliation, and data pipelines processing 10K+ transactions daily. Before that I was VP at Goldman Sachs, where I led engineering for distributed market risk aggregation — running multi-terabyte in-memory Spark clusters and processing petabyte-scale market data for real-time VaR and stress testing.
>
> What draws me to Epsilon is the scale and the problem domain. Attribution and forecasting at marketing scale — billions of events, multi-stage batch pipelines, high-stakes outputs for Fortune 500 clients — that's technically close to what I found most challenging at Goldman. I want to go deep on the data engineering side again, own complex pipeline architecture, and operate at the scale Epsilon works at."

---

### Q2: Tell me about your experience with Apache Spark at scale.

**Answer (lead with Goldman, be specific):**
> "At Goldman Sachs, I owned a multi-terabyte in-memory distributed compute cluster that was the backbone of market risk aggregation — real-time VaR computation, stress testing, and pricing across global risk workflows. The data layer was petabyte-scale market data ingested, transformed, and made queryable for risk analytics.
>
> The specific challenges I dealt with: sharding and rebalancing live Spark clusters without downtime during trading hours — I introduced an abstraction layer between the sharding logic and the computation layer so we could re-shard while serving real-time queries. Optimizing wide transformations and shuffle operations across hundreds of partitions. Tuning executor memory and GC for jobs that ran for hours on large datasets.
>
> At Skydo, Spark is part of our data pipeline for settlement analytics and reconciliation reporting — smaller scale but similar patterns: idempotent job design, checkpoint-and-resume for long-running jobs, exactly-once semantics."

---

### Q3: What's your experience with Hadoop — HDFS, YARN, MapReduce?

**Answer (honest but confident):**
> "At Goldman Sachs, the compute infrastructure ran on top of the Hadoop ecosystem — HDFS for distributed storage of market data, YARN for resource management across the Spark clusters I owned. I worked closely with the infra team on YARN queue tuning and HDFS replication factors for our risk data.
>
> MapReduce directly — less so, because by that point we were entirely on Spark as the compute engine. But I understand the execution model deeply: the shuffle, sort, and merge phases, and why Spark's in-memory DAG model outperforms it for iterative workloads. For batch attribution pipelines with multiple transformation stages, I'd still choose Spark over raw MapReduce."

---

### Q4: Describe your experience building and scheduling DAG-based workflows.

**Answer:**
> "At Skydo, I architected our distributed job scheduling and execution system for time-based and asynchronous workflows — settlement sweeps, reconciliation runs, retry pipelines. It's functionally a DAG: jobs have dependencies, some run in parallel, some are gated on upstream success.
>
> I implemented this using a combination of AWS EventBridge for scheduling, SQS for event-driven triggers, and a custom execution layer with Redis-based distributed locking to guarantee exactly-one execution. The dependency graph was encoded in a job metadata store, and the scheduler resolved and dispatched jobs based on upstream completion signals.
>
> I haven't used Airflow specifically in production — at Goldman, the orchestration layer was an internal system. But I've studied Airflow's DAG model and operator patterns, and the concepts map directly to what I've built. I'd expect to come up to speed on Airflow quickly."

---

### Q5: How do you handle data quality and pipeline reliability at scale?

**Answer:**
> "A few principles I've built around:
>
> First, **idempotency at every stage** — every job must be safe to re-run with the same input. This is non-negotiable for batch pipelines because failures and retries are the norm, not the exception. At Moneyview, I designed idempotent retry logic for debit instruction pipelines processing millions of records daily.
>
> Second, **checkpoint-and-resume** — long Spark jobs should write intermediate outputs to durable storage (HDFS/S3/GCS) so a failure mid-job doesn't mean starting from scratch. This also gives you natural observability points.
>
> Third, **data quality assertions at ingestion** — schema validation, null checks, cardinality checks run before any transformation logic. A bad input caught at ingestion is an hour saved downstream.
>
> Fourth, **SLA-aware alerting** — not just 'job failed' but 'job hasn't completed by T+2h when SLA is T+1h'. At Skydo I built SLO-based observability on top of business workflows, which cut production incidents by ~30%."

---

### Q6: Tell me about your Python experience. The JD mentions Python for infrastructure modules.

**Answer:**
> "Python is my primary scripting and infrastructure automation language. At Skydo, I used Python for data pipeline scripting, infrastructure-as-code helpers on top of Terraform, and internal tooling for deployment and monitoring. I'm comfortable with pandas, pyspark, boto3, and standard tooling.
>
> For Spark development specifically, I've used PySpark for pipeline prototyping and exploratory data analysis, then moved to the JVM-based API for production jobs where performance matters. Given this role involves Scala for production Spark, Python would fit naturally as the infra/orchestration glue layer."

---

### Q7: How do you approach mentoring engineers?

**Answer:**
> "I've directly mentored 8 engineers at Skydo in distributed systems, scalability patterns, and architecture decision-making. My approach is context over instruction — instead of telling someone the answer, I give them a real problem, have them propose a design, and then probe the edges: 'What happens when the job restarts mid-way? What if two instances pick up the same task?' They learn by defending choices.
>
> For junior engineers on batch systems specifically, the early wins are: understanding data partitioning, learning to read a Spark query plan, and developing intuition for when a transformation will cause a shuffle. Those three things cover 80% of the performance problems they'll encounter."

---

### Q8: Describe a time you owned a complex system end-to-end.

**Answer:**
> "At Goldman Sachs, I owned the full lifecycle of the market risk aggregation platform — from architecture decisions on cluster topology, to query optimization on petabyte-scale data, to production on-call reliability. No handoffs: I designed it, my team built it, and I was accountable for it serving live trading desks without downtime.
>
> The moment that tested that ownership most was a silent data corruption bug in the sharding layer — risk numbers were slightly off, not obviously broken, but wrong. I caught it through anomaly detection on aggregate outputs. We had to replay three days of market data, re-aggregate, and validate against independent calculations — all while the system stayed live for trading. That experience taught me: batch systems need independent validation paths, not just pipeline tests."

---

### Q9: Have you worked with Databricks?

**Answer:**
> "Not in a production role, but I've explored it for analytics use cases. Databricks' value proposition is clear to me: it abstracts cluster management, gives you the Delta Lake ACID layer on top of object storage, and the notebook-first workflow speeds up collaborative pipeline development.
>
> In my current stack we use BigQuery and Snowflake for warehouse workloads. The concepts — Delta Lake vs Iceberg (which I've used), Unity Catalog vs BigQuery's dataset model — are transferable. I'd expect a ramp-up period of a few weeks to be productive on Databricks, not months."

---

### Q10: Why are you moving from an Engineering Manager role to a Staff IC role?

**Answer (this will likely come up):**
> "My natural mode has always been depth-first. The EM role at Skydo grew organically — I was the founding engineer who built the platform, and as the team scaled, I moved into the leadership role. But over the last year, I've been deliberate about wanting to get back to owning hard technical problems directly — not through a team, but hands-on.
>
> A Staff Engineer role at a company like Epsilon, where the technical bar is high and the problems are genuinely complex at scale, is where I want to operate. I'm not leaving leadership behind — I'll still influence architecture, mentor engineers, and lead technical direction. But I want 'the system works correctly at scale' to be my primary accountability again."

---

## 4. The Scala Gap — How to Handle It

This is the hardest part of your application. The JD says "12+ years in Scala." Your resume doesn't list it.

**Do NOT bluff.** Screeners at this level will ask you to write Scala code or explain Scala-specific patterns.

**Your honest, confident position:**

> "I want to be direct: Scala isn't in my primary programming history. My Spark work at Goldman was largely through the JVM API using Java and some internal tooling. I've written Scala for configuration and small pipeline components, and I've read a lot of Scala codebases — Spark itself is written in Scala, so understanding the source-level behavior has been useful.
>
> I understand what makes Scala powerful for this domain: functional composition, pattern matching, the type system catching data shape errors at compile time, and the natural fit with Spark's RDD/Dataset API. What I bring is deep Spark operational experience and the ability to learn a language's production patterns quickly — I went from Java to Kotlin to Go in production across different roles. I'd expect 4-6 weeks to be productive in Scala and 3 months to be writing idiomatic code."

**What to do before the interview:**
- Spend 3-4 hours on Scala Exercises (scala-exercises.org) — cover case classes, pattern matching, Option/Either, collections
- Write one small PySpark or Spark Scala job from scratch: read a CSV, filter, group-by, write to parquet
- Know these Scala-specific Spark concepts: `Dataset[T]` vs `DataFrame`, encoder resolution, why Scala's case classes make Spark schemas ergonomic

---

## 5. Technical Areas to Refresh (Priority Order)

### High Priority (likely in screening)
1. **Spark internals** — DAG scheduler, stages vs tasks, wide vs narrow transformations, shuffle, catalyst optimizer, query plans (`explain()`)
2. **Partitioning strategies** — hash vs range partitioning, partition pruning, repartition vs coalesce
3. **Spark performance tuning** — broadcast joins, skew handling, executor sizing, adaptive query execution (AQE)
4. **DAG workflow concepts** — dependency graphs, retry semantics, idempotency in pipelines, backfill strategies

### Medium Priority
5. **Scala basics** — case classes, pattern matching, Option, Either, for-comprehensions, collections API
6. **Data lake concepts** — Apache Iceberg (you've used it!) vs Delta Lake, Z-ordering, compaction, time travel
7. **Hadoop ecosystem** — YARN resource management, HDFS block size and replication, NameNode architecture

### Good to Know
8. **Databricks** — Delta Lake basics, Unity Catalog, job clusters vs all-purpose clusters
9. **Airflow** — DAG definition, operators, XComs, task dependencies, backfill

---

## 6. Smart Questions to Ask the Interviewer

These signal that you think at the right level:

1. **"What's the current pain point in the attribution pipeline — is it latency, data quality, cost, or scalability?"** (shows you're solution-oriented)

2. **"How does the team split ownership between pipeline architecture and ML model integration? Where does the batch infrastructure responsibility end and the data science layer begin?"**

3. **"What's the typical scale of a single attribution job — input data size, runtime, number of stages?"** (anchors your prior experience to their reality)

4. **"You mentioned on-prem and cloud — what's the migration trajectory? Are most new workloads greenfield on cloud, or is there significant Hadoop on-prem you're maintaining in parallel?"**

5. **"What does mentoring look like for a Staff Engineer here — is there an expectation to own a sub-team technically, or is it more advisory?"**

6. **"What does success look like in the first 90 days?"**

---

## 7. Your Strongest Cards — Lead With These

1. **Petabyte-scale Spark at Goldman Sachs** — real production ownership, not just usage
2. **End-to-end platform ownership** — you designed, built, scaled, and operated, not just coded
3. **Data warehousing depth** — BigQuery, Snowflake, Redshift, Iceberg — strong warehouse vocabulary
4. **Python + AWS + GCP** — direct matches to the JD
5. **Mentorship track record** — 8 engineers, measurable outcomes
6. **Engineering rigor** — idempotency, observability, SLO-driven reliability — these transfer directly to batch pipeline ownership
