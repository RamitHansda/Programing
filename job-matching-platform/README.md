# Job–Candidate Matching Platform

A production-grade, planet-scale system for matching **10 million jobs** against
**100 billion candidates** and delivering personalised email notifications to
every matched candidate.

---

## Table of Contents

1. [Scale requirements](#scale-requirements)
2. [Architecture overview](#architecture-overview)
3. [Component deep-dives](#component-deep-dives)
   - [Embedding & vector index](#1-embedding--vector-index)
   - [Matching service](#2-matching-service)
   - [Kafka pipeline](#3-kafka-pipeline)
   - [Email worker](#4-email-worker)
   - [Deduplication & rate limiting](#5-deduplication--rate-limiting)
4. [Data flow (end-to-end)](#data-flow-end-to-end)
5. [Throughput & capacity maths](#throughput--capacity-maths)
6. [Getting started](#getting-started)
7. [Running tests](#running-tests)
8. [Configuration reference](#configuration-reference)
9. [Production deployment checklist](#production-deployment-checklist)

---

## Scale requirements

| Dimension | Value |
|---|---|
| Active jobs | 10 million |
| Candidates | 100 billion |
| Top-K matches per job | 500 |
| Total match records per sweep | up to 5 billion |
| Target sweep time | < 24 hours (nightly batch) |
| Peak email throughput | 50 000 emails / second |
| Email dedup window | 30 days |
| Per-candidate email cap | 3 job emails / day |

---

## Architecture overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│  OFFLINE (nightly batch)                                                │
│                                                                         │
│  ┌──────────────┐   embed   ┌──────────────────────────────────────┐   │
│  │  Candidate   │ ────────► │  Sharded FAISS IVF_PQ Index          │   │
│  │  DB (PG)     │           │  1 000 shards × 100 M candidates     │   │
│  └──────────────┘           │  ~3.2 TB total (32 bytes/vector)     │   │
│                             └──────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│  ONLINE (batch sweep or continuous)                                     │
│                                                                         │
│  ┌──────────┐  embed   ┌────────────────┐  ANN search  ┌───────────┐  │
│  │ Job DB   │ ───────► │ Matching       │ ────────────► │  FAISS    │  │
│  │ (10 M)   │          │ Service        │               │  Shards   │  │
│  └──────────┘          │ (200 replicas) │ ◄──────────── └───────────┘  │
│                        │                │  top-K hits                  │
│                        │  hard filter   │                              │
│                        │  hydrate meta  │                              │
│                        └───────┬────────┘                              │
│                                │ MatchResult (per candidate)           │
│                                ▼                                       │
│                        ┌───────────────┐                              │
│                        │  Kafka        │  1 000 partitions             │
│                        │  job-candidate│  keyed by candidate_id        │
│                        │  -matches     │                               │
│                        └───────┬───────┘                              │
└────────────────────────────────┼────────────────────────────────────────┘

┌───────────────────────────────┼────────────────────────────────────────┐
│  EMAIL WORKERS (50 replicas)  │                                        │
│                               ▼                                        │
│  ┌────────────────────────────────────────────────────────────────┐   │
│  │  MatchConsumer  →  DedupManager (Redis)  →  EmailWorker        │   │
│  │                                                                 │   │
│  │  • Dedup: SET NX EX 30d   (sent:<job>:<candidate>)             │   │
│  │  • Daily cap: INCR + TTL  (daily_cap:<candidate>:<date>)       │   │
│  │  • Rate limit: sliding window counter (rate:<second>)          │   │
│  │  • Send: SendGrid / AWS SES  (3 retries, exp backoff)          │   │
│  │  • DLQ: candidate-emails-dlq  (for failures)                   │   │
│  └────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Component deep-dives

### 1. Embedding & vector index

**File:** `matching/embedder.py`, `matching/faiss_index.py`

- Each candidate profile (skills, title, experience, location, employment prefs)
  is serialised to a single text string and encoded by a 256-dim sentence
  transformer (`all-MiniLM-L6-v2`).
- Embeddings are stored in **1 000 FAISS IVF_PQ shards**.
  - `IVF` (Inverted File Index) with 4 096 centroids → fast coarse quantisation.
  - `PQ` (Product Quantisation) with 32 sub-quantizers × 8 bits → **32 bytes/vector**.
  - Storage: 100 B × 32 B = **~3.2 TB** — fits on cheap object storage (S3/GCS).
- Shards are rebuilt nightly by the `scripts/index_builder.py` batch job.

### 2. Matching service

**File:** `matching/matcher.py`, `scripts/run_matching.py`

- For each active job: embed → fan-out ANN query across all shards in parallel
  (`ThreadPoolExecutor`) → hydrate candidate metadata → apply hard filters →
  emit `MatchResult` objects.
- **Hard filters** (not captured by the embedding):
  - Employment type preference
  - Location preference (or remote flag)
  - Salary range compatibility
- 200 matching-service replicas partition the 10 M job set.
  Each replica processes ~50 000 jobs × 16 shard workers ≈ 13 minutes wall time.

### 3. Kafka pipeline

**Files:** `queue/kafka_producer.py`, `queue/kafka_consumer.py`

- Topic `job-candidate-matches` has **1 000 partitions**.
- Producer keys by `candidate_id` → all matches for a candidate land on the
  same partition for easy per-candidate rate enforcement.
- Idempotent producer + `acks=all` prevents duplicate records on retries.
- `linger_ms=10` + `batch_size=64 KB` + snappy compression = high throughput
  with minimal latency overhead.

### 4. Email worker

**Files:** `email/worker.py`, `email/sender.py`, `email/templates.py`

- 50 worker replicas × 100 threads = **5 000 concurrent email API calls**.
- Thread-per-message model is appropriate because email sending is pure I/O
  (HTTPS round trip to SendGrid/SES ≈ 100 ms).
- Supports **SendGrid** and **AWS SES** (configurable via `EMAIL_PROVIDER`).
- Retries: 3 attempts with exponential backoff (2 s, 4 s). Failures → DLQ.
- Beautiful HTML template + plain-text fallback with personalised match reasons.

### 5. Deduplication & rate limiting

**File:** `email/dedup.py`

| Mechanism | Redis key | Purpose |
|---|---|---|
| Sent-dedup | `sent:<job_id>:<candidate_id>` TTL=30d | Prevent re-sending same (job, candidate) pair |
| Daily cap | `daily_cap:<candidate_id>:<YYYY-MM-DD>` TTL=48h | Max 3 job emails/day per candidate |
| Global rate limit | `rate:<unix_second>` TTL=2s | Cap total outbound at 5 000 emails/s |

All three use Redis atomic operations (`SET NX EX`, `INCR`, pipeline) — safe
under concurrent workers with no application-level locking.

---

## Data flow (end-to-end)

```
1.  NIGHTLY INDEX BUILD
    └─ For each shard 0–999:
         read 100 M candidates from DB → embed → FAISS IVF_PQ → save to /data/faiss

2.  MATCHING SWEEP (nightly or continuous)
    For each active job (batched, 200 replicas):
      a. embed(job) → query_vec
      b. FAISS.search(query_vec, top_K=500) across all shards (parallel)
      c. hydrate candidate metadata from DB
      d. apply hard filters (location, salary, emp-type)
      e. build MatchResult { match_id, job_id, candidate_id, score, reasons }
      f. Kafka.produce(topic="job-candidate-matches", key=candidate_id, value=MatchResult)

3.  EMAIL DISPATCH (50 worker replicas, continuous)
    For each MatchResult batch from Kafka:
      a. Redis dedup check → skip if already sent
      b. Daily-cap check   → skip if candidate already got 3 emails today
      c. Rate-limit slot   → back-pressure if > 5 000 emails/s globally
      d. render HTML + plain-text email (personalised match reasons)
      e. send via SendGrid / SES (3 retries, exponential backoff)
      f. Redis.SET sent:<job>:<candidate> EX 30d
      g. failed after all retries → Kafka DLQ for manual review
```

---

## Throughput & capacity maths

| Stage | Calculation | Result |
|---|---|---|
| Embeddings/s (GPU A100) | 10 000 candidates / 5 s | 2 000/s per GPU |
| Index build time (200 GPUs) | 100 B / (2 000 × 200) | ~70 hours → use distributed Spark |
| ANN search per job | 1 000 shards × 50 ms | 50 ms wall time (parallel) |
| Matching throughput | 200 replicas × 16 threads / 50 ms | 64 000 jobs/s |
| Total sweep time (10 M jobs) | 10 M / 64 000 | ~156 seconds = ~2.6 min |
| Kafka match records | 10 M jobs × 500 top-K | 5 billion records |
| Email throughput | 50 workers × 100 threads / 100 ms latency | 50 000 emails/s |
| Time to email all 5 B matches | 5 B / 50 000 | ~28 hours |
| Kafka storage per sweep | 5 B × 500 B/record (compressed) | ~2.5 TB |

> **Key insight**: the bottleneck is email API throughput, not matching.
> To accelerate delivery, increase email worker replicas or negotiate higher
> rate limits with your email provider.

---

## Getting started

### Prerequisites

- Docker 24+ and Docker Compose v2
- Python 3.11+

### Local development (stub mode — no real email sent)

```bash
cd job-matching-platform

# Install dependencies
pip install -r requirements.txt

# Run matching with in-memory stub data
python -m scripts.run_matching --stub --dry-run

# Run email worker with stub data
python -m scripts.run_email_worker --stub
```

### Full stack with Docker Compose

```bash
cd job-matching-platform
docker compose up --build
```

This starts Kafka, Redis, PostgreSQL, one matching service, and 3 email
workers (all in stub mode by default — no real emails).

To test the full email pipeline with a real provider, set environment
variables in `docker-compose.yml`:

```yaml
EMAIL_PROVIDER: sendgrid
EMAIL_API_KEY: SG.xxxx
```

---

## Running tests

```bash
cd job-matching-platform
pip install pytest
pytest tests/ -v
```

All tests run without any external services (Kafka / Redis / DB) using
in-memory stubs.

---

## Configuration reference

All settings are read from environment variables with sensible defaults.

| Variable | Default | Description |
|---|---|---|
| `KAFKA_BROKERS` | `kafka:9092` | Comma-separated broker list |
| `KAFKA_MATCH_TOPIC` | `job-candidate-matches` | Match results topic |
| `KAFKA_NUM_PARTITIONS` | `1000` | Partitions for match topic |
| `REDIS_URL` | `redis://redis:6379/0` | Redis connection URL |
| `REDIS_RATE_LIMIT_RPS` | `5000` | Max emails/second (global) |
| `REDIS_DEDUP_TTL` | `2592000` | Sent-dedup key TTL (30 days) |
| `DATABASE_URL` | `postgresql://...` | PostgreSQL DSN |
| `FAISS_INDEX_DIR` | `/data/faiss` | FAISS shard directory |
| `FAISS_NUM_SHARDS` | `1000` | Number of index shards |
| `EMBEDDING_DIM` | `256` | Vector dimension |
| `FAISS_NPROBE` | `64` | IVF nprobe (accuracy vs speed) |
| `MATCHING_TOP_K` | `500` | Top-K candidates per job |
| `MATCHING_MIN_SCORE` | `0.70` | Min cosine similarity threshold |
| `MATCHING_SHARD_WORKERS` | `16` | Parallel shard threads per replica |
| `EMAIL_PROVIDER` | `sendgrid` | `sendgrid` \| `ses` \| `stub` |
| `EMAIL_API_KEY` | _(empty)_ | SendGrid API key |
| `EMAIL_FROM` | `no-reply@jobmatch.example.com` | Sender address |
| `EMAIL_MAX_RETRIES` | `3` | Max retry attempts per email |
| `EMAIL_WORKER_CONCURRENCY` | `100` | Threads per worker process |
| `EMAIL_NUM_WORKERS` | `50` | Worker replicas (K8s HPA target) |

---

## Production deployment checklist

- [ ] **Vector index**: Run `scripts/index_builder.py` as a distributed Spark
      job or K8s Job array across 200 pods to rebuild 1 000 FAISS shards overnight.
- [ ] **FAISS shards**: Store on S3/GCS and memory-map in matching pods using
      `faiss.read_index` with `io_flags=faiss.IO_FLAG_MMAP`.
- [ ] **Kafka**: Use Confluent Cloud or Amazon MSK.  Set retention to 48 h for
      the match topic (data is ephemeral once emails are sent).
- [ ] **Redis**: Use a Redis Cluster with ≥ 3 nodes for HA.  The dedup keyspace
      for 5 B (job, candidate) pairs ≈ 5 B × ~50 bytes = 250 GB RAM — use
      `allkeys-lru` eviction and rely on the DB for ground truth.
- [ ] **Email provider**: Negotiate dedicated IP pools with SendGrid or request
      a SES production access increase.
- [ ] **Matching service**: Deploy 200 K8s pods, each assigned a disjoint job
      range.  Use a CronJob for nightly sweeps.
- [ ] **Email workers**: Deploy 50 pods with HPA on Kafka consumer lag.
- [ ] **Monitoring**: Prometheus metrics on match throughput, email sent/failed
      rates, Kafka consumer lag, and Redis memory.
- [ ] **Opt-out**: Honour unsubscribe links within 10 business days (CAN-SPAM /
      GDPR).  Set `opted_out = TRUE` in the DB and check before emitting matches.
