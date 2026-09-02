# Goldman Sachs — Virtual Panel Interview 3

**Role:** Foundation Engineering / Data Lakehouse — Vice President  
**Job ID:** [169307](https://higher.gs.com/roles/169307) (Bengaluru; sibling postings 169290 Dallas, 169303 Hyderabad, 169296 London)  
**Interviewers:** Pradeep & Ralph (two-person panel)  
**Date / time:** 8 Sept 2026, 5:00–6:00 PM IST  
**Named competency:** Software Design and Architecture  
**Format:** 60 min, Zoom + CoderPad

This note is a question bank for *this* round, not the whole Superday. Sources are labelled: **reported** (candidate write-ups of the GS “Software Design and Architecture” competency) vs **team-likely** (this JD + GS data-engineer / lakehouse reports + public Legend Lakehouse architecture).

---

## 1. What this round actually is

Goldman names Superday competencies. “Software Design and Architecture” is the **HLD / LLD panel**, almost always with **two interviewers**, and almost always on **CoderPad** even when the work is design. Typical 60-minute shape from 2024–2026 write-ups:

| Minutes | What happens |
|---|---|
| 0–8 | Intros. Deep-dive HLD of **one resume project** (APIs, DB, scale, what broke). |
| 8–20 | Java / concurrency / distributed-systems fundamentals, or a small coded LLD (rate limiter, LRU, sliding window). |
| 20–50 | One open-ended design. They push on trade-offs, failure modes, storage, queues. Diagrams are often **not required** — they talk. |
| 50–60 | Follow-ups: Kafka vs Redis, cache invalidation, how you’d know it is healthy in prod. |

At **VP**, they expect you to defend choices with numbers, name failure modes before they prompt, and talk like someone who would ship this inside a bank (correctness, audit, entitlements) — not like a FAANG “scale Twitter” answer.

**CoderPad implication:** keep a Java (or Python) skeleton ready for LRU / sliding-window rate limiter / producer-consumer. They may ask you to type a class, not just talk.

I could **not** find public questions attributed specifically to Pradeep or Ralph. Treat them as a standard GS two-interviewer design panel from the Lakehouse / AI Data Platform team, not as anonymous names you can reverse-engineer.

---

## 2. What job 169307 is hiring you to design

Official title on the posting: **Software Engineering — Data, Lakehouse and AI Data Platform Engineer — Bangalore — Vice President**.

The team is building the firm’s **Legend Lakehouse**: Iceberg tables on object storage, governed models (FINOS Legend), multi-engine query (Spark / Databricks / Snowflake / Athena-class), entitlements. Public talks (Neema Raphael, Tim Smith, Abhishek Narang; Databricks + Snowflake + AWS Summit) describe:

- Apache Iceberg as the interoperability layer (not a Databricks-only lake)
- Legend models → pipelines / UDFs
- Native governance: entitlements, secure sharing, lineage
- AWS pieces in the public London talk: S3, Step Functions, ECS, Glue, Lambda, Athena

JD stack they want you fluent in even if you do not know the internal names on day one:

| Layer | Tools on the JD |
|---|---|
| Processing | ANSI SQL, Apache Spark, Kafka |
| Formats | JSON, Avro, Parquet |
| Platforms | Snowflake, Apache Iceberg, Databricks, Hadoop ecosystem, Sybase IQ |
| Engineering | CI/CD, containers / Kubernetes |

Your Goldman market-risk / VaR / petabyte story is the right resume hook. In this round, translate it into **lakehouse language**: bronze/silver/gold, Iceberg ACID + time travel, schema contracts, recon, entitlements — not only in-memory cluster folklore.

---

## 3. Reported questions — GS “Software Design and Architecture” competency

These are questions candidates said they were asked in the **named Superday round**, 2024–2026. Ranked by how often they show up. Practice these even if the team then steers into data platform — this competency is standardized.

### 3.1 Highest frequency (prepare to code or talk in CoderPad)

1. **Rate limiter** — algorithms (fixed window, sliding window, token bucket, leaky bucket); why not only client-side; gateway vs service vs sidecar; then **write sliding-window code**. Reported in multiple Associate/VP Superdays ([interviewexperiences.in](https://interviewexperiences.in/experience/goldman-sachs/goldman-sachs-associate-interview-experience-2), Bangalore Dec 2024, Pranav Mahajan LinkedIn Aug 2025). Variant: *block OTP after >3 attempts in 5 minutes*.
2. **LRU cache** — `get`/`put` in O(1); HashMap + doubly linked list. Reported as the entire Design round in one Analyst Superday (Hyd/Blr Feb 2025).
3. **URL shortener (tinyurl)** — HLD only, trade-offs, **no diagrams**. Virtual Panel Aug 2025 Associate USA.
4. **Notification service** — DB choice, offline users, retries. VP Bangalore (6 YOE, offer); also HM-round follow-up in the same loop.
5. **WhatsApp / sequential message delivery** — user goes offline, messages must arrive in order when they come back; **Kafka vs Redis Streams** for latency vs durability. Associate CoderPad VP write-up.
6. **Parking lot LLD** — classes, vehicle types, spots. Very common in *Software Engineering Practices*; still leaks into Design. VP Bangalore Superday listed parking lot + snake & ladder + message queues in one round.
7. **E-commerce / checkout** — orders, cancellation, payments, threading, locks, race conditions, DB schema. Several Analyst/Associate Design rounds.
8. **Product of last k integers in a stream** — O(1) approach, discussion only (Analyst Superday).

### 3.2 Also reported in the same competency or adjacent Superday design slot

| Question | Notes |
|---|---|
| Design Twitter (home / user / search timelines, celebrity fan-out, 5s freshness) | Associate Oct 2024 HLD |
| Billing system HLD | Associate Bangalore 4.5 YOE — candidate failed for weak HLD |
| File management system: APIs, DS, SQL vs Firebase | Analyst HM after Design |
| API with authn/authz flow | Associate SDE2 |
| Load balancers, horizontal scaling, DB optimization, caching | Analyst Design round after a stream-product DSA |
| Consistent hashing, distributed cache TTL, Kafka, MapReduce, multi-region DB | Associate USA “System Design & Infrastructure” |
| Microservices vs monolith; API gateway vs load balancer; can you skip the LB? | Hyderabad 2.5 YOE Design |
| ThreadPool, Callable vs Runnable, Spring vs Spring Boot | VP Bangalore Superday |
| Builder pattern in a multithreaded setting; SOLID on a scenario | Analyst Design |
| Java threads (10 min), then design | Associate Software Design round |

### 3.3 How a recent candidate described the round (shape, not a leaked problem)

Garvit Jindal, GBM Equities, May 2026 Medium: Design & Architecture was a **small distributed system** — stream of inputs, derived state, multiple consumers, latency + consistency constraints. They probed:

- strong vs eventual consistency
- in-memory vs persistent
- push vs pull consumers
- shard if volume doubles
- node failure mid-critical operation
- Kafka ordering / partitions / consumer groups
- relational vs KV vs time-series
- write-through vs write-behind cache
- what you would instrument in production

He lost points by giving **generic** failure modes. Equities interviewers wanted message loss, duplication, partial fills, clock skew. For *this* team, the analogue is: **late data, schema break, small files, skewed Spark stage, Iceberg commit conflict, entitlement leak, recon break**.

---

## 4. Team-likely questions — Data Lakehouse / Foundation Engineering

Job 169307 + GS data-engineer reports (Interview Query, DataDriven L6, Divansh Gupta LinkedIn Apr 2026, Dataford AWM) + public Legend Lakehouse talks. If Pradeep/Ralph are on the platform team, expect **one of these as the 30-minute design**, not parking lot.

### 4.1 The design you should be able to run end-to-end (highest priority)

**“Design the firm’s lakehouse for analytics + AI. Raw feeds in, governed gold tables out.”**

Cover, in order, without being asked:

1. **Requirements:** batch + streaming; consumers = risk, reporting, AI; 7-year retention; entitlements; recon.
2. **Medallion:** bronze (immutable landing) → silver (cleaned, conformed) → gold (business grain).
3. **Table format:** Iceberg on object storage — ACID, time travel, partition evolution, multi-engine (Spark, Snowflake, Trino/Athena). Why not “just Parquet files” and why not a closed warehouse as source of truth.
4. **Ingestion:** Kafka for streams / CDC; batch land to bronze; schema registry (Avro/JSON).
5. **Processing:** Spark (batch + Structured Streaming); compaction / file-size policy.
6. **Governance:** catalog + RBAC/ABAC; Legend-style semantic models; lineage; data contracts.
7. **Quality:** completeness / accuracy / consistency checks; recon to source; quarantine / DLQ.
8. **Ops:** freshness SLOs, small-file alarms, commit metrics, backfill story.

GS-specific colour you should name once: **Legend** (FINOS, Goldman-originated modelling platform) + **Iceberg** as the open table format so Databricks and Snowflake can share the same tables. Do not pretend you have used Legend in production; say you have read the public integration posts and would learn the internal wrappers.

### 4.2 Design prompts reported or strongly implied for GS data / platform loops

1. **Design a real-time market-data ingestion pipeline** (millions of events/sec). Normalize, fan-out to trading / risk / compliance. No loss, duplicates, out-of-order. Kafka → normalizer → Iceberg + hot store. Interview Query lists this as a GS DE design question; System Design Handbook lists it as a canonical GS HLD.
2. **Design a Medallion architecture (bronze → silver → gold)** — Divansh Gupta, GS Data Engineer, Round 3.
3. **Batch vs streaming for financial systems — when each.** Same report; Dataford AWM DE.
4. **Real-time fraud detection pipeline (Kafka + Spark Streaming).** Same report.
5. **Schema drift and late-arriving data.** Same report. Have a watermark + Iceberg MERGE/upsert answer.
6. **Data governance: lineage, audit, access control (RBAC).** Same report. Map to Legend + Unity-catalog-class entitlements.
7. **Design a schema for trades and orders for analytics and compliance** — grain, immutability, timestamps, unique IDs. Interview Query.
8. **Detect and resolve corrupted financial data in a critical pipeline** — validation, quarantine, rollback via Iceberg time travel. Interview Query.
9. **Design a compliance / audit logging system** — append-only, WORM, regional residency. System Design Handbook Q3.
10. **Design a risk monitoring system across multiple exchanges** — <200ms aggregate, historical + real-time. System Design Handbook; maps to your VaR work.
11. **“We have 40 pipelines producing inconsistent output; how do you fix it?”** — DataDriven GS Staff DE. VP/platform prompt: data contracts, shared ingestion, metadata store, not “I write better Spark.”
12. **Design the data platform for a 500-person org** — same Staff-level source. Talk developer experience, standardized CDC, orchestration, governance vs team autonomy.
13. **CDC from a core store (Sybase IQ / relational) into Iceberg** — implied by JD (Hadoop + Sybase IQ still on the stack). Snapshot + incremental, idempotent MERGE, initial backfill.
14. **How would you migrate a Hadoop / Hive / Sybase IQ warehouse to Iceberg without breaking consumers?** — JD literally lists those as current platforms. Dual-write, catalog cutover, time-travel validation, recon.
15. **Multi-engine query on one Iceberg table (Spark job vs Snowflake vs Databricks).** Why Iceberg; catalog (REST/Polaris/Glue/Unity); identity/entitlements consistent across engines.

### 4.3 Follow-ups they will use to distinguish VP from Associate

Have a 60-second answer for each:

| Follow-up | VP-shaped answer |
|---|---|
| Iceberg vs Delta vs Hudi | Iceberg for engine independence (GS public story). Delta if Databricks-only. Hudi if upsert-heavy CDC is the main workload. |
| Copy-on-write vs merge-on-read | COW for read-heavy gold; MOR for high-frequency silver upserts; compaction job is part of the design. |
| Hidden partitioning / partition evolution | Iceberg lets you change partition spec without rewriting all files — say when you would. |
| Snapshot isolation / concurrent writers | Iceberg optimistic commit; conflict → retry; don’t take a warehouse lock. |
| Small files | Streaming writers buffer; compaction on a schedule; target 128–512 MB Parquet. |
| Schema evolution | Additive + nullable only via registry; never `SELECT *`; Iceberg `evolve` / merge-schema; breaking changes versioned as new table or dual-run. |
| Late data | Event-time watermarks; Iceberg MERGE into gold; for regulatory facts, don’t drop — correct and audit. |
| Exactly-once | At-least-once + idempotent sink (MERGE on business key + batch id). True EOS is expensive; say where you actually need it (settlement, recon). |
| Data skew | Diagnose via stage task times; salt keys; AQE; broadcast small dim; don’t raise `spark.sql.shuffle.partitions` blindly. |
| Recon break | Source count vs bronze vs gold; hash-sum money columns with `Decimal` not float; quarantine; time-travel to last good snapshot. |
| Entitlements | Row/column filters in catalog; never “secure the dashboard only”; Legend/Unity-style contracts so producer and consumer workspaces agree. |
| Backfill | Replay from Kafka / bronze; isolated write branch or snapshot; atomic swap; don’t mutate gold in place without audit. |
| Cost | Storage cheap, shuffle expensive, Snowflake warehouse idle time; compaction vs query cost. |

### 4.4 Spark / storage questions that show up in GS DE technical rounds (may leak into this panel)

From Interview Query + Divansh Gupta:

- Explain Spark execution: DAG → stages → tasks. Stage boundary = shuffle.
- `repartition` vs `coalesce`.
- Data skew and shuffle optimization.
- Broadcast join vs sort-merge join.
- Spark job running 5+ hours — how do you debug?
- Slow Spark job on large Parquet — file sizes, partition pruning, predicate pushdown, too many small files.
- Parquet vs Avro vs JSON — when each (Avro on the wire, Parquet on disk).

---

## 5. Finance HLD prompts GS still uses (have a 10-minute sketch)

Even on a lakehouse team, Superday interviewers recycle finance HLD. Sketch these once:

1. Real-time trading platform / matching engine (fairness, audit, <1ms — you can say this is not your team’s SLO and re-scope to analytics of trades).
2. Market-data pipeline (above).
3. Risk monitoring / VaR-style aggregation — **your home ground.** Stream of risk factors → derived VaR/PnL views → multiple consumers. Consistency, sharding, node failure. Use the stale-VaR story.
4. Payment / settlement with idempotency and no double-post (Skydo).
5. Compliance audit log (append-only).
6. Caching layer for expensive risk calculations (write-through vs write-behind; invalidation on new market tick).

GS design bar (SpaceComplexity + System Design Handbook): **correctness > availability**, **audit trail is mandatory**, **never float for money**, **fail closed** on risk/settlement paths.

---

## 6. CoderPad snippets they have asked *inside* the Design competency

Not DSA Superday. These appeared in Software Design and Architecture:

| Prompt | What they want |
|---|---|
| Sliding-window rate limiter | Working code + why not fixed window |
| LRU cache | HashMap + DLL, O(1) |
| Product of last k in a stream | O(1); handle zeros |
| Integer → English words (`1234` → “one thousand…”) | Edge cases, not design |
| Thread / producer-consumer sketch | `BlockingQueue`, interruption, poison pill |
| Idempotent request handler | Dedup key + TTL store |

If they stay on lakehouse, they may instead ask you to **pseudo-code Iceberg MERGE**, a Spark Structured Streaming sink, or a recon SQL (window `ROW_NUMBER` to pick latest after duplicate ETL).

---

## 7. Resume deep-dive they will open with

Two-interviewer GS Design rounds almost always start here. Prepare a **5-minute HLD** of one system, then survive 10 minutes of “why this DB / what fails / how did you know.”

**Primary:** Goldman market-risk aggregation (multi-TB in-memory, VaR/PnL/stress, stale numbers under load, GC / shard hotspot / P99). Translate to: derived state from a stream, multiple consumers, consistency, observability.

**Secondary:** Skydo payments — idempotency, recon, distributed lock, Kafka workflows. Use this if they ask “design a financially correct pipeline.”

Do **not** spend the hour retelling career narrative. They have a design rubric to fill.

---

## 8. How to run the 60 minutes (VP bar)

1. **Clarify 90 seconds:** functional, then non-functional (throughput, freshness, consistency, retention, who is entitled to see what).
2. **State the grain** of the core table (one row = ?).
3. **Draw or list 5 boxes:** source → bus → bronze → compute → gold/serving → catalog/governance. Talk if they don’t want diagrams.
4. **Go deep on two components they pick.** For this team, likely Iceberg writer + quality/recon, or Kafka + schema registry.
5. **Failure modes they care about:** duplicate, late, schema break, poison message, Spark skew, commit conflict, entitlement miss, recon break, region residency.
6. **Ops last 5 min:** metrics (freshness, row-count drift, file size, commit fail rate), backfill, on-call.

Phrases that score at GS VP:

- “I’d fail closed and page, not silently drop a regulatory fact.”
- “At-least-once plus MERGE on (business_key, batch_id); I won’t claim exactly-once unless the sink is idempotent.”
- “Money columns are Decimal; recon is sum + count vs source, not ‘looks right in a dashboard.’”
- “Iceberg snapshot is the audit point; time-travel is how we roll back gold.”

Phrases that lose:

- Eventual consistency everywhere.
- “We’ll just use Databricks” with no Iceberg/catalog story.
- Floats for notional / PnL.
- Scaling talk with no data-quality or entitlements.

---

## 9. Practice order for 8 Sept (highest expected value)

Do these out loud, 25 minutes each, CoderPad open:

1. **Rate limiter** (talk + sliding-window code) — most reported Design-competency question.
2. **Lakehouse for firm analytics/AI** (section 4.1) — most likely *team* question.
3. **Market-data pipeline** Kafka → Iceberg, with late data + schema evolution.
4. **Resume HLD** of Goldman risk platform, then “now land this in a lakehouse.”
5. **LRU** coded cold.
6. **Notification or WhatsApp delivery** + Kafka vs Redis.
7. **CDC + Iceberg MERGE + recon** from a legacy warehouse (Sybase IQ / Hadoop).
8. **Spark job is slow / skewed** — spoken debug script.

You already have long-form material in-repo for (2) and (3): `docs/data_eng/HLD-Large-Scale-Data-Pipeline.md`, `docs/data_eng/SPARK_ARCHITECTURE_STAFF_ENG.md`, `docs/lld/staff-engineer-interviews/01-rate-limiter.md`, `docs/lld/staff-engineer-interviews/07-cache-eviction.md`.

---

## 10. Sources

**GS Software Design and Architecture write-ups**

- [Garvit Jindal — three GS rounds, Design & Architecture](https://medium.com/@garvitjindal09/what-three-rounds-at-goldman-sachs-taught-me-about-how-engineering-interviews-really-work-ddc054f4be83)
- [Aman Chowdhury — 10 recent GS experiences](https://medium.com/@amanchowdhuryaa/goldman-sachs-recent-interview-experiences-consolidated-0cdc61f7cbde) (includes VP Bangalore offer: parking lot, snake & ladder, notification, ThreadPool)
- [Associate — rate limiter + WhatsApp + Kafka vs Redis](https://interviewexperiences.in/experience/goldman-sachs/goldman-sachs-associate-interview-experience-2)
- [Virtual Panel Aug 2025 — URL shortener HLD](https://interviewexperiences.in/experience/goldman-sachs/goldman-sachs-virtual-panel-interview-august-2025-engineering-associate-usa)
- [Analyst — LRU + number-to-words in Design round](https://interviewexperiences.in/experience/goldman-sachs/goldman-sachs-analyst-15-yoe-hydblr-feb-2025)
- [Analyst — stream product + LB/cache/DB](https://leetcode.com/discuss/post/5808118/goldman-sachs-analyst-interview-experien-eqno/)
- [Pranav Mahajan — rate limiter in Practices; Design was next panel](https://www.linkedin.com/posts/pranav-mahajan-004756186_goldmansachs-javadeveloper-backendengineering-activity-7361036806789787648-UjRp)
- [VP Controllers Superday structure](https://leetcode.com/discuss/post/5044572/goldman-sachs-virtual-onsite-interview-c-f5ii/)

**Role / lakehouse**

- [Job 169307](https://higher.gs.com/roles/169307)
- [Divansh Gupta — GS DE system design (Medallion, batch vs stream, fraud, schema drift, governance)](https://www.linkedin.com/posts/divanshgupta_goldman-sachs-data-engineer-interview-activity-7444949159801032704-s085)
- [Interview Query — GS Data Engineer](https://www.interviewquery.com/interview-guides/goldman-sachs-data-engineer)
- [DataDriven — GS Staff DE platform design](https://datadriven.io/companies/goldman-sachs/staff-data-engineer)
- [FINOS Legend + Databricks](https://developer.gs.com/blog/posts/integration-of-databricks-with-legend)
- [Databricks: Legend Lakehouse / Iceberg / Unity entitlements](https://www.databricks.com/blog/finos-legend-integrate-databricks-lakehouse-improving-open-data-exchange-across-financial)
- [System Design Handbook — GS HLD prompts](https://www.systemdesignhandbook.com/guides/goldman-sachs-system-design-interview/)
- [SpaceComplexity — GS LLD+HLD bar by level](https://spacecomplexity.ai/blog/goldman-sachs-system-design-interview)
