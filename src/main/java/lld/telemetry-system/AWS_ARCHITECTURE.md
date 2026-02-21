# Telemetry System on AWS — 10M Network Flows/sec

AWS-native mapping of the [HLD](HLD.md): same pipeline (ingest → durable log → stream processing → hot + cold storage → query), implemented with managed services.

---

## 1. Architecture Diagram (AWS)

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│  NETWORK (Exporters: NetFlow / sFlow / IPFIX)                                            │
└─────────────────────────────────────────────┬───────────────────────────────────────────┘
                                               │
                    ┌──────────────────────────▼──────────────────────────┐
                    │  AWS: NLB (or ALB)                                    │
                    │  • UDP/TCP for NetFlow/sFlow (NLB) or HTTP (ALB)      │
                    └──────────────────────────┬───────────────────────────┘
                                               │
                    ┌──────────────────────────▼───────────────────────────┐
                    │  INGEST LAYER (stateless)                             │
                    │  • ECS Fargate or EC2 (ASG) — protocol adapters       │
                    │  • Normalize → partition key → put to Kinesis         │
                    │  • Optional: VPC Flow Logs → Firehose → Kinesis       │
                    └──────────────────────────┬───────────────────────────┘
                                               │
                    ┌──────────────────────────▼───────────────────────────┐
                    │  MESSAGE BUS (durable log)                             │
                    │  • Amazon Kinesis Data Streams (or MSK if Kafka API)  │
                    │  • Partition key = hash(device_id / 5-tuple)           │
                    │  • On-Demand or provisioned shards for 1 GB/s         │
                    └──────────────────────────┬───────────────────────────┘
                                               │
    ┌──────────────────────────────────────────┼──────────────────────────────────────────┐
    │                                          │                                          │
    ▼                                          ▼                                          ▼
┌───────────────────────┐     ┌─────────────────────────────┐     ┌───────────────────────┐
│ Kinesis Data          │     │ Kinesis Data                 │     │ Kinesis Data           │
│ Analytics (Flink)     │     │ Analytics (Flink)            │     │ Firehose (optional)    │
│ • Aggregate (1m/5m)   │     │ • Enrich, filter             │     │ • Raw/sample → S3      │
└───────────┬───────────┘     └──────────────┬───────────────┘     └───────────┬───────────┘
            │                                │                                  │
            ▼                                ▼                                  ▼
┌───────────────────────┐     ┌─────────────────────────────┐     ┌───────────────────────┐
│ Amazon Timestream      │     │ Amazon OpenSearch Service   │     │ S3 (Parquet)          │
│ • Time-series metrics  │     │ • Search, dashboards         │     │ • Cold / data lake    │
│ • 7–30 day hot         │     │ • Recent flow search        │     │ • Partitioned by date  │
└───────────┬───────────┘     └──────────────┬───────────────┘     └───────────┬───────────┘
            │                                │                                  │
            └────────────────────────────────┼──────────────────────────────────┘
                                             │
                    ┌────────────────────────▼────────────────────────┐
                    │  QUERY LAYER                                     │
                    │  • API Gateway + Lambda (route by time range)    │
                    │  • Timestream / OpenSearch / Athena              │
                    │  • Cognito + IAM (auth), CloudWatch (metrics)    │
                    └────────────────────────┬────────────────────────┘
                                             │
                    ┌────────────────────────▼────────────────────────┐
                    │  USERS: Dashboards, Alerts, Ad-hoc SQL           │
                    └─────────────────────────────────────────────────┘
```

---

## 2. Pipeline Explained (No AWS Background Assumed)

This section walks through the diagram **left to right**, explaining what each box does in plain language and why it's there.

---

### Step 0: Where the data comes from

**Network (Exporters)**  
Routers, firewalls, or other devices that see traffic. They don't store flows; they **export** summaries (who talked to whom, how many bytes, etc.) using protocols like NetFlow, sFlow, or IPFIX. Think of them as "sensors" that send a continuous stream of small records. Our job is to receive that stream at huge scale (10 million records per second) and eventually let people query it.

---

### Step 1: Front door — NLB (Network Load Balancer)

**What it is:** A **load balancer** is a single entry point that spreads incoming traffic across many servers. If one server is busy or dies, traffic goes to others.

**NLB** = Network Load Balancer. It works at the TCP/UDP level (layer 4). NetFlow and sFlow often use **UDP**, so NLB is a good fit: it takes the UDP packets from the exporters and forwards them to the next layer.

**In one line:** NLB is the "front door" that receives the flow data from the network and distributes it across many ingest servers so no single server is overwhelmed.

---

### Step 2: Ingest layer — ECS Fargate (or EC2)

**What it is:**  
- **EC2** = virtual servers in AWS. You run your code on them.  
- **ECS** = Elastic Container Service. It runs **containers** (like Docker) instead of whole VMs.  
- **Fargate** = "ECS without managing servers." You say "run 100 containers"; AWS runs them. You don't patch OS or size instances.

**What it does here:** These are the **collectors**. Each one: (1) receives raw flow records (NetFlow/sFlow) from the NLB, (2) converts them into one common format (e.g. source IP, dest IP, bytes, time, device ID), (3) sends them to the next stage (Kinesis) with a **partition key** (e.g. device ID) so related data stays together.

They are **stateless**: they don't store anything. If one dies, the load balancer sends traffic to others.

**In one line:** ECS/Fargate runs the programs that receive flow data, normalize it, and push it into Kinesis. We run many of them and scale up/down with load.

---

### Step 3: Message bus — Kinesis Data Streams

**What it is:** **Kinesis Data Streams** is a **durable, ordered log** for streaming data. Think of it as a conveyor belt: producers put records on it; many consumers can read from it at their own pace. Data is stored for a while (e.g. 24 hours), so if a consumer crashes, it can catch up later.

**Why we need it:** We can't write 10 million records per second directly into a database. So we: (1) write everything into Kinesis (built for high write throughput), (2) have **several consumers** (Flink, Firehose) read from the same stream and do different things—aggregate, search index, archive.

**Partition key:** Records are grouped into **shards**. The partition key (e.g. device ID) decides which shard a record goes to, so all flows from the same device go to the same shard. That helps when we aggregate "per device" in the next step.

**In one line:** Kinesis is the "conveyor belt" that accepts 10M records/sec and lets multiple downstream systems read and process them without overloading one database.

---

### Step 4: Stream processing — Flink and optional Firehose

**Flink (Kinesis Data Analytics)**  
- **Flink** = a **stream processing** engine. It reads a stream and does things like "every minute, sum bytes per device" or "filter only flows from this subnet" in real time.  
- **Kinesis Data Analytics** = AWS's **managed Flink**. You point it at a Kinesis stream (source) and at destinations (sinks).

**What it does here:** One Flink job **aggregates** flows into 1‑minute or 5‑minute buckets and writes to **Timestream**. Another **enriches** and **filters**, then writes to **OpenSearch**. So: raw stream in, summarized or filtered stream out.

**Firehose (optional)**  
- **Kinesis Data Firehose** = "stream to storage." It reads from a Kinesis stream and writes to S3 (or OpenSearch) in batches.  
- Here we use it to write a **raw or sampled** copy to S3 (cold storage) without custom Flink code.

**In one line:** Flink does the "smart" work (aggregate, enrich, filter); Firehose is a simple "stream → S3" pipe. Both read from the same Kinesis stream.

---

### Step 5: Hot storage (Timestream, OpenSearch) and cold storage (S3)

**Hot** = recent data, queried often. **Cold** = older data, cheap storage.

**Amazon Timestream**  
- A **time-series database**: optimized for "value at time T" and "aggregate over time range."  
- We write **aggregated** metrics from Flink (e.g. bytes per device per minute), not 10M raw flows. Good for dashboards and alerts.

**Amazon OpenSearch Service**  
- **OpenSearch** = search and analytics engine. You can search and filter by many fields (IP, port, device, time).  
- We write recent, enriched flow data (or aggregates) from Flink. Good for "show me all flows for this IP in the last hour."

**S3 (cold)**  
- **S3** = Simple Storage Service. Object storage: you store files (e.g. Parquet) in "buckets." Cheap, durable, scalable.  
- We store raw or aggregated flow data by day for long-term retention. **Athena** lets you run SQL on these S3 files without loading them into a database.

**In one line:** Timestream and OpenSearch hold "recent" data for fast queries; S3 holds "all" data for a long time. We never write 10M raw flows/sec into Timestream or OpenSearch—only what Flink aggregated or sampled.

---

### Step 6: Query layer — API Gateway + Lambda

**API Gateway**  
- The **front door for APIs**. Users or dashboards send HTTP requests (e.g. "give me flows for device X in the last 24 hours"). API Gateway receives the request, checks auth, and forwards it to Lambda.

**Lambda**  
- **Serverless functions**: you write a small piece of code that runs when someone calls the API. AWS runs it; you don't manage servers.  
- Here, Lambda **routes** the request: if "recent" → query **Timestream** or **OpenSearch**. If "old" or "big scan" → query **Athena** (SQL on S3). Return the result.

**Cognito / IAM**  
- **Auth**: who is allowed to call the API and see which data.

**In one line:** API Gateway + Lambda is the "query API": users ask questions here; Lambda decides whether to ask Timestream, OpenSearch, or Athena and returns the result.

---

### Step 7: Users — Dashboards, Alerts, Ad-hoc SQL

- **Dashboards** call the API or query Timestream/OpenSearch to show charts (traffic over time, top talkers).  
- **Alerts** run periodic queries (e.g. Lambda on a schedule) against Timestream or OpenSearch.  
- **Ad-hoc SQL**: analysts use Athena on S3 for one-off questions over long time ranges.

**Full path in one paragraph:**  
Exporters send flows → **NLB** spreads the load → **ECS** normalizes and pushes to **Kinesis** → **Flink** (and optionally Firehose) reads Kinesis, aggregates/enriches, and writes to **Timestream**, **OpenSearch**, and **S3** → **API Gateway + Lambda** answer user queries by reading from Timestream, OpenSearch, or **Athena** (S3) → **Users** get dashboards, alerts, or SQL. No single service handles 10M/sec alone; the pipeline together achieves the scale.

---

## 3. Service Mapping (Reference)

| HLD component       | AWS service(s) | Purpose |
|---------------------|----------------|---------|
| Load balancer       | NLB / ALB      | Front ingest; NLB for UDP/TCP flow protocols. |
| Ingest (collectors) | ECS Fargate, EC2 + ASG | Stateless adapters; scale on flow rate. |
| Message bus         | **Kinesis Data Streams** or **Amazon MSK** | Durable, partitioned log; single write path. |
| Stream processing   | **Kinesis Data Analytics for Apache Flink** | Aggregate, enrich, sample; write to Timestream, OpenSearch, S3. |
| Hot storage (TS)    | **Amazon Timestream** | Time-series metrics, TTL, SQL. |
| Hot storage (search/analytics) | **OpenSearch Service** or **Redshift** | Search and heavy SQL on recent data. |
| Cold / lake         | **S3** + **Athena** (+ **Glue** catalog) | Parquet, long retention, ad-hoc SQL. |
| Query API           | **API Gateway** + **Lambda** | Route to Timestream / OpenSearch / Athena. |
| Auth                | **Cognito**, **API Gateway authorizers**, **IAM** | Users and service identity. |
| Observability       | **CloudWatch** (metrics, logs, alarms), **X-Ray** | Pipeline health and tracing. |

---

## 4. Kinesis: Sizing for ~1 GB/s Ingest

- **Provisioned:** 1 shard ≈ 1 MB/s write, 1k records/s. 1 GB/s ⇒ **~1000 shards** (or split across multiple streams). Scale up/down by resharding.
- **On-Demand:** No shard management; pay per throughput. Easiest for variable or very high throughput; cap with service quotas.
- **Best practice:** Use a **partition key** (e.g. `device_id` or hash of 5-tuple) so ordering per key is preserved for aggregation. Avoid a single key dominating one shard.

If you need Kafka semantics (consumer groups, replay, multiple apps on same topic): use **Amazon MSK** and run the same Flink job against MSK instead of Kinesis.

---

## 5. Stream Processing (Flink on AWS)

- **Kinesis Data Analytics for Apache Flink:** Managed Flink; source = Kinesis (or MSK), sinks = Timestream, OpenSearch, Kinesis Firehose, Lambda, or custom (e.g. HTTP). Scale by KPU (parallelism).
- **Alternative:** **EMR** with Flink reading from MSK/Kinesis and writing to S3/Timestream/OpenSearch — more control, more ops.
- **Not for 10M/sec:** Lambda-triggered from Kinesis for “every record” — use only for light filtering or fan-out; heavy aggregation belongs in Flink.

---

## 6. Hot Storage Choices

| Need | AWS service | When to use |
|------|-------------|-------------|
| Time-series metrics (rate, count, percentiles) | **Timestream** | Dashboards, alerts on aggregated flow metrics. |
| Full-text / flexible filters on recent flows | **OpenSearch** | “Show me flows for this IP in last hour.” |
| Heavy SQL / BI on aggregated data | **Redshift** | Analytics on top of pre-aggregated flows. |

Don’t write 10M raw flows/sec into any of these; write **aggregated** and **sampled** streams from Flink.

---

## 7. Cold Path (Data Lake)

- **Flink** (or Firehose) writes **Parquet** to **S3**, partitioned by date (and optionally tenant/device).
- **AWS Glue** for catalog and partition metadata.
- **Athena** for SQL on S3; use partition pruning and compression to control cost.
- Lifecycle: transition to Glacier for old partitions if retention is long and access rare.

---

## 8. VPC Flow Logs (AWS-Only Flows)

If a major source is AWS VPC traffic:

- **VPC Flow Logs** → **S3** or **CloudWatch Logs**.
- To merge with the main pipeline: **Lambda** or **Kinesis Data Firehose** (subscription filter on CloudWatch Logs) to push into the same **Kinesis stream** (or MSK topic) so Flink sees one unified stream.

---

## 9. Security on AWS

- **Network:** Ingest in private subnets; NLB/ALB in public or DMZ subnets. No direct internet to Kinesis/Flink.
- **IAM:** Roles for ECS tasks, Flink, Lambda, and query services with least-privilege access to Kinesis, Timestream, S3, OpenSearch.
- **Encryption:** Kinesis (server-side encryption with KMS), S3 (SSE-KMS), Timestream and OpenSearch encryption at rest. TLS in transit.
- **Secrets:** **Secrets Manager** (or Parameter Store) for API keys and DB credentials used by ingest or Flink.

---

## 10. Observability (CloudWatch)

- **Custom metrics:** Ingest rate (per collector), Kinesis iterator age (consumer lag), Flink checkpoint duration, write errors to Timestream/S3/OpenSearch.
- **Alarms:** Iterator age > threshold, error rate, ingest drop rate.
- **Logs:** ECS/Flink → **CloudWatch Logs**; avoid logging every flow — log aggregates and errors.
- **X-Ray:** Enable on API Gateway and Lambda for query-path tracing.

---

## 11. Cost Levers

| Area | Levers |
|------|--------|
| Ingest | Right-size ECS/EC2; use Spot for non-critical ingest if acceptable. |
| Kinesis | On-Demand vs provisioned; retention (short on bus). |
| Flink | KPU and parallelism; optimize state and checkpoint interval. |
| Timestream | Batch writes; TTL and magnetic store vs memory store. |
| S3 / Athena | Retention, lifecycle to Glacier, partition pruning, compression. |
| OpenSearch | Instance size and count; use only for “recent” data. |

---

## 12. Phased Rollout on AWS

1. **MVP:** NLB + ECS (one protocol) → Kinesis Data Streams (On-Demand) → Kinesis Data Analytics (Flink) → Timestream. Prove throughput with aggregation.
2. **Cold path:** Flink → S3 (Parquet) + Glue + Athena; API Gateway + Lambda route hot vs cold by time range.
3. **Optional:** OpenSearch for search; Redshift if BI workload appears.
4. **Harden:** Multi-AZ (already with managed services), backup/DR for config and state, runbooks and alarms.

---

**Summary:** You can run the full 10M flows/sec telemetry pipeline on AWS using **Kinesis** (or MSK) as the spine, **Kinesis Data Analytics for Flink** for processing, **Timestream** and **OpenSearch** for hot, **S3 + Athena** for cold, and **API Gateway + Lambda** for the query API. Same design as the cloud-agnostic HLD; AWS services replace each component with managed alternatives.
