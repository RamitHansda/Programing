# Job-to-Candidate Matching System — High-Level Design (Principal Engineer)

**Target scale: 10 M+ candidates · 1 M active jobs · sub-200 ms match query p99 · fresh job feed within 60 s of posting.**

---

## 0. Executive Summary

### 0.1 Headline SLOs

| Metric | Target | Notes |
|---|---|---|
| Active candidates in index | 10 M+ | Profiles with embedding + filter attributes |
| Active job postings | 1 M | Rolling 90-day active window |
| New jobs indexed (freshness) | ≤ 60 s | Job posted → visible in candidate feed |
| Candidate feed query p99 | < 200 ms | Pull: candidate opens app → ranked job list |
| Recruiter search p99 | < 500 ms | Push: search for matching candidates per job |
| Job alert notification lag | < 5 min | Job posted → push notification to top-N matches |
| Match recall@100 | ≥ 85 % | Fraction of ground-truth relevant jobs in top-100 |
| Match nDCG@10 | ≥ 0.72 | Ranking quality vs. human-labeled relevance |
| Write availability (profile/job ingest) | 99.95 % | |
| Read availability (feed + search) | 99.99 % | Served from cache on degraded path |
| RPO | < 1 min (profiles/jobs) | Primary DB synchronous replica |
| RTO | < 5 min (full region loss) | Traffic shift via DNS/load balancer |

### 0.2 Architecture in one sentence

> **Candidates and jobs are encoded as dense vectors by a fine-tuned embedding service; two-stage retrieval (hard-filter ANN → ML re-ranker) runs at query time for pull feeds; a streaming pipeline powered by Kafka pre-fans-out new job alerts to matching candidates within minutes; a continuous feedback loop feeds click, apply, and skip signals back into the ranking model.**

### 0.3 Component Map

| Layer | Components |
|---|---|
| **Ingestion** | Profile Service, Job Service, Embedding Service |
| **Indexing** | Elasticsearch (keyword + filter), Vector DB (ANN), Redis (hot cache) |
| **Matching Core** | Retrieval Service (Stage 1), Ranking Service (Stage 2) |
| **Feed & Discovery** | Candidate Feed API, Recruiter Search API |
| **Async workflows** | Job-alert Fanout Worker, Notification Service, Feedback Collector |
| **ML Platform** | Embedding Model Training, Ranking Model Training, Feature Store |
| **Data — durable** | PostgreSQL (profiles, jobs, applications), Kafka (event log) |
| **Data — analytics** | ClickHouse (CTR, impressions, application funnels) |
| **Infra** | Kubernetes (EKS/GKE), Istio, Prometheus + Grafana, OpenTelemetry |

---

## 1. Problem Framing & Resolved Assumptions

Before drawing boxes, a Principal Engineer pinpoints every ambiguity. The answers below are the positions I'd defend in a design review.

| Decision | My answer | Why |
|---|---|---|
| Matching direction | **Bidirectional** — candidate←→job | Pull (candidate sees jobs) and push (recruiter sees candidates) are equally important |
| Matching signal | **Hybrid** — lexical + semantic + behavioral | Pure keyword misses synonyms; pure semantic is slow and unexplainable |
| Freshness model | **Streaming near-real-time** (≤60 s index lag) + **batch daily rerank** | New jobs must surface quickly; full re-scoring every N minutes is too expensive |
| Personalization | **Learning-to-rank** model trained on CTR + apply signals | Cold-start candidates fall back to rule-based scoring |
| Geography model | **Geo-cell bucketing** (H3 index) + remote-flag | Enables fast geo-filter without expensive distance joins |
| Candidate embedding | **768-d dense vector** per profile (title + skills + bio) | Captures semantic meaning beyond exact skill keywords |
| Job embedding | **768-d dense vector** per JD (title + requirements + responsibilities) | Same embedding space as candidate vectors |
| ANN algorithm | **HNSW** (Hierarchical Navigable Small World) | Sub-10 ms recall at 10 M vectors; better latency/recall than IVF-PQ alone |
| Two-stage retrieval | Stage 1: fast ANN recall (top-1 K) → Stage 2: ML re-rank (top-50) | Classic industrial pattern; decouples recall from precision |
| Scale-out unit | Each vertical (profile, job, retrieval, ranking) scales independently | Avoids monolithic bottlenecks |
| Data isolation | Tenants (enterprise customers) share infra but are logically isolated | Row-level security in PostgreSQL; tenant_id on every Kafka event |

### Out of Scope (v1)
- Resume parsing / OCR (pre-processed text assumed as input)
- Video profile matching
- ATS (applicant tracking system) integration
- Bias / fairness audit tooling (P1 in v2)

---

## 2. Capacity Estimation (Napkin Math)

```
Candidates:          10 M profiles
Active jobs:          1 M postings (rolling 90-day window)
New jobs/day:        ~50 K   →  ~0.6 /s avg, ~5 /s burst
Profile updates/day: ~500 K  →  ~6 /s avg
Feed queries (pull): ~10 M/day → ~116 /s avg, ~1 200 /s peak (9–10 AM local)
Recruiter searches:  ~200 K/day → ~2.3 /s avg
Applications:        ~1 M/day

Embedding size: 768 × 4 bytes = 3 KB per vector
  Candidate index: 10 M × 3 KB = 30 GB
  Job index:        1 M × 3 KB =  3 GB
  Total ANN RAM:    ≈ 60 GB (HNSW overhead ≈2×) — fits in a fleet of r6g.4xlarge

Elasticsearch (filter/keyword index):
  Candidate docs: 10 M × ~5 KB avg = 50 GB
  Job docs:        1 M × ~10 KB avg = 10 GB
  With replication (×2): ~120 GB — 3-node cluster, 40 GB/node

PostgreSQL (profiles + jobs + applications):
  Profiles:     10 M × 8 KB = 80 GB
  Jobs:          1 M × 16 KB = 16 GB
  Applications: 1 M/day × 365 days × 200 B = ~73 GB/year

Kafka:
  Events: ~12 M/day @ avg 1 KB = 12 GB/day
  Retention: 7 days → ~84 GB; size for 3× burst → 10 partitions/topic is safe
```

---

## 3. Full System Architecture

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║                               CLIENTS                                         ║
║   Candidate Mobile App    Candidate Web    Recruiter Dashboard    Partner API ║
╚═══════════════╤═══════════════╤═══════════════╤═══════════════════╤═══════════╝
                │ HTTPS/REST    │ HTTPS/REST    │ HTTPS/REST        │ REST/gRPC
                ▼               ▼               ▼                   ▼
╔═══════════════════════════════════════════════════════════════════════════════╗
║                         API GATEWAY / BFF LAYER                               ║
║  ┌──────────────────────────────────────────────────────────────────────────┐ ║
║  │  AWS API Gateway / Kong                                                  │ ║
║  │  • Auth (JWT / OAuth 2.0)  • Rate limiting  • Request routing           │ ║
║  │  • Candidate Feed BFF      • Recruiter Search BFF                       │ ║
║  └──────────────────────────────────────────────────────────────────────────┘ ║
╚═══════════════╤═══════════════════════════════════════════════════════════════╝
                │
    ┌───────────┴──────────────────────────────────────────────────────────┐
    │                                                                      │
    ▼                                                                      ▼
╔═══════════════════════════╗                          ╔════════════════════════╗
║   CANDIDATE FEED API      ║                          ║  RECRUITER SEARCH API  ║
║  (Pull path)              ║                          ║  (Push/search path)    ║
║                           ║                          ║                        ║
║  GET /v1/feed             ║                          ║  POST /v1/search/      ║
║  GET /v1/jobs/{id}        ║                          ║       candidates       ║
║  POST /v1/jobs/{id}/apply ║                          ║  GET /v1/candidates    ║
║  POST /v1/feedback        ║                          ║       /{id}            ║
╚══════════╤════════════════╝                          ╚═════════╤══════════════╝
           │                                                     │
           ▼                                                     ▼
╔══════════════════════════════════════════════════════════════════════════════╗
║                       MATCHING CORE                                          ║
║  ┌──────────────────────────────┐    ┌──────────────────────────────────┐   ║
║  │   RETRIEVAL SERVICE          │    │   RANKING SERVICE                │   ║
║  │  (Stage 1 — high recall)     │───▶│  (Stage 2 — high precision)      │   ║
║  │                              │    │                                  │   ║
║  │  1. Hard filters             │    │  1. Feature assembly             │   ║
║  │     • Location / remote      │    │     (profile × job cross feat.)  │   ║
║  │     • Salary range           │    │  2. LambdaRank / LightGBM        │   ║
║  │     • Work authorization     │    │     gradient boosting re-ranker  │   ║
║  │     • Job type (FT/PT)       │    │  3. Diversity / dedup            │   ║
║  │  2. ANN vector search        │    │  4. Business rules overlay       │   ║
║  │     • HNSW on job vectors    │    │     (sponsored, pinned, demoted) │   ║
║  │     • Candidate vector query │    │  Returns: ordered job list       │   ║
║  │     • top-1 000 candidates   │    │                                  │   ║
║  │  3. BM25 keyword recall      │    └──────────────────────────────────┘   ║
║  │     • Elasticsearch query    │                                            ║
║  │     • top-500 by TF-IDF      │                                            ║
║  │  4. Score fusion (RRF)       │                                            ║
║  │     • Reciprocal Rank Fusion │                                            ║
║  │     • Merged top-1 000 list  │                                            ║
║  └──────────────────────────────┘                                            ║
╚══════════════════════════════════════════════════════════════════════════════╝
           │                │
           ▼                ▼
╔══════════════════╗  ╔══════════════════════════════════════════════════════╗
║  EMBEDDING       ║  ║              DATA LAYER                              ║
║  SERVICE         ║  ║                                                      ║
║                  ║  ║  ┌─────────────┐  ┌──────────────┐  ┌───────────┐  ║
║  Fine-tuned      ║  ║  │  Vector DB  │  │Elasticsearch │  │  Redis    │  ║
║  sentence-BERT   ║  ║  │  (HNSW)     │  │  (BM25 +     │  │  Cluster  │  ║
║  768-d output    ║  ║  │  Weaviate / │  │   filters)   │  │           │  ║
║                  ║  ║  │  pgvector   │  │              │  │  Hot      │  ║
║  Serves:         ║  ║  │             │  │  Candidate   │  │  profile  │  ║
║  • Candidate     ║  ║  │  Candidate  │  │  + Job docs  │  │  cache    │  ║
║    profile embed ║  ║  │  vectors    │  │              │  │           │  ║
║  • Job embed     ║  ║  │  Job        │  │  Faceted     │  │  Pre-     │  ║
║  • Query embed   ║  ║  │  vectors    │  │  filtering   │  │  computed │  ║
║  (< 20 ms P99)   ║  ║  └─────────────┘  └──────────────┘  │  feed     │  ║
╚══════════════════╝  ║                                      │  cache    │  ║
                      ║  ┌─────────────────────────────────┐ └───────────┘  ║
                      ║  │     PostgreSQL (Primary + RR)   │                ║
                      ║  │  • candidate_profiles           │                ║
                      ║  │  • job_postings                 │                ║
                      ║  │  • applications                 │                ║
                      ║  │  • match_feedback               │                ║
                      ║  └─────────────────────────────────┘                ║
                      ╚══════════════════════════════════════════════════════╝

╔══════════════════════════════════════════════════════════════════════════════╗
║                    ASYNC / EVENT-DRIVEN LAYER                                ║
║                                                                              ║
║  Topics:  profile.updated  |  job.posted  |  job.expired  |  feedback       ║
║           application.submitted  |  embedding.ready                         ║
║                                                                              ║
║  ┌─────────────────────────┐   ┌──────────────────────────────────────────┐ ║
║  │   KAFKA CLUSTER         │──▶│  JOB-ALERT FANOUT WORKER                 │ ║
║  │                         │   │  • Consumes job.posted events            │ ║
║  │   3 brokers             │   │  • Retrieves top-N matching candidates   │ ║
║  │   Replication factor 3  │   │  • Writes alert batch to Notification DB │ ║
║  └─────────────────────────┘   └──────────────────────────────────────────┘ ║
║           │                                                                  ║
║           ├──▶  EMBEDDING WORKER  (profile/job → vector upsert)             ║
║           ├──▶  FEEDBACK COLLECTOR (CTR / apply / skip → Feature Store)     ║
║           └──▶  NOTIFICATION SERVICE  (push / email / in-app)               ║
╚══════════════════════════════════════════════════════════════════════════════╝

╔══════════════════════════════════════════════════════════════════════════════╗
║                        ML PLATFORM (OFFLINE)                                 ║
║                                                                              ║
║  S3 raw events ──▶ Spark ETL ──▶ Feature Store (Redis online + S3 offline)  ║
║       │                                  │                                   ║
║       ▼                                  ▼                                   ║
║  Embedding Model Training           Ranking Model Training                   ║
║  (sentence-BERT fine-tune)          (LightGBM LambdaRank)                   ║
║       │                                  │                                   ║
║       ▼                                  ▼                                   ║
║  Model Registry (MLflow) ──▶  Canary deploy ──▶  Embedding Service /        ║
║                                                   Ranking Service            ║
╚══════════════════════════════════════════════════════════════════════════════╝
```

---

## 4. Core Data Models

### 4.1 Candidate Profile

```sql
CREATE TABLE candidate_profiles (
    candidate_id        UUID PRIMARY KEY,
    user_id             UUID NOT NULL REFERENCES users(user_id),
    headline            TEXT,                        -- "Senior Backend Engineer"
    summary             TEXT,
    current_title       VARCHAR(200),
    years_of_experience SMALLINT,
    skills              TEXT[],                      -- ["Java", "Kafka", "AWS"]
    desired_salary_min  INTEGER,
    desired_salary_max  INTEGER,
    desired_locations   JSONB,                       -- [{"city": "NYC", "h3_cell": "..."}]
    remote_preference   VARCHAR(20),                 -- REMOTE | HYBRID | ONSITE
    work_authorization  VARCHAR(50),                 -- US_CITIZEN | H1B | etc.
    open_to_work        BOOLEAN DEFAULT true,
    embedding_version   VARCHAR(20),                 -- "v3.1"
    embedding_updated_at TIMESTAMPTZ,
    created_at          TIMESTAMPTZ DEFAULT NOW(),
    updated_at          TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_candidate_open     ON candidate_profiles(open_to_work) WHERE open_to_work = true;
CREATE INDEX idx_candidate_location ON candidate_profiles USING GIN(desired_locations);
CREATE INDEX idx_candidate_skills   ON candidate_profiles USING GIN(skills);
```

### 4.2 Job Posting

```sql
CREATE TABLE job_postings (
    job_id              UUID PRIMARY KEY,
    employer_id         UUID NOT NULL REFERENCES employers(employer_id),
    title               VARCHAR(200) NOT NULL,
    description         TEXT NOT NULL,
    required_skills     TEXT[],
    preferred_skills    TEXT[],
    min_years_exp       SMALLINT,
    max_years_exp       SMALLINT,
    salary_min          INTEGER,
    salary_max          INTEGER,
    location            JSONB,                       -- {"city": "SF", "h3_cell": "..."}
    remote_policy       VARCHAR(20),
    job_type            VARCHAR(20),                 -- FULL_TIME | PART_TIME | CONTRACT
    work_authorization  TEXT[],                      -- accepted auth types
    status              VARCHAR(20) DEFAULT 'ACTIVE',
    embedding_version   VARCHAR(20),
    embedding_updated_at TIMESTAMPTZ,
    posted_at           TIMESTAMPTZ DEFAULT NOW(),
    expires_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_job_status         ON job_postings(status) WHERE status = 'ACTIVE';
CREATE INDEX idx_job_posted         ON job_postings(posted_at DESC);
CREATE INDEX idx_job_employer       ON job_postings(employer_id);
CREATE INDEX idx_job_skills         ON job_postings USING GIN(required_skills);
```

### 4.3 Match Feedback (behavioral signals)

```sql
CREATE TABLE match_feedback (
    feedback_id     BIGSERIAL PRIMARY KEY,
    candidate_id    UUID NOT NULL,
    job_id          UUID NOT NULL,
    signal          VARCHAR(30) NOT NULL,      -- IMPRESSION | CLICK | APPLY | SKIP | DISLIKE
    rank_position   SMALLINT,                  -- position in the feed when signal fired
    retrieval_score FLOAT,                     -- Stage 1 fusion score
    rank_score      FLOAT,                     -- Stage 2 model score
    session_id      UUID,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);
-- Partitioned by month; cold-archived to S3 after 90 days
CREATE INDEX idx_feedback_candidate ON match_feedback(candidate_id, created_at DESC);
CREATE INDEX idx_feedback_job       ON match_feedback(job_id, signal);
```

### 4.4 Vector Store Schema (Weaviate / pgvector)

```
Class: CandidateVector
  candidate_id    (string, indexed)
  vector          (float32[768], HNSW index)
  open_to_work    (boolean, filterable)
  h3_cells        (string[], filterable)   -- multi-resolution H3 hexes for geo
  work_auth       (string, filterable)
  years_of_exp    (int, filterable)
  remote_pref     (string, filterable)
  updated_at      (date, filterable)

Class: JobVector
  job_id          (string, indexed)
  vector          (float32[768], HNSW index)
  status          (string, filterable)
  h3_cells        (string[], filterable)
  remote_policy   (string, filterable)
  job_type        (string, filterable)
  salary_min      (int, filterable)
  salary_max      (int, filterable)
  min_years_exp   (int, filterable)
  posted_at       (date, filterable)
```

---

## 5. Matching Algorithm — Deep Dive

### 5.1 Two-Stage Architecture

The fundamental insight: **recall and precision are separate problems requiring separate machinery.**

```
Query (candidate vector)
       │
       ▼
┌──────────────────────────────────────────────────────┐
│  STAGE 1 — RETRIEVAL  (target: high recall, fast)    │
│                                                      │
│  Branch A: ANN Search                                │
│    • Filter by hard constraints (geo, auth, remote)  │
│    • HNSW k-NN on job vectors (k = 500)              │
│    • Cosine similarity scores                        │
│                                                      │
│  Branch B: BM25 Keyword Search                       │
│    • Elasticsearch: title^3 + skills^2 + desc       │
│    • Same hard filters as Branch A                   │
│    • Returns top-500 job IDs + BM25 scores           │
│                                                      │
│  Score Fusion: Reciprocal Rank Fusion (RRF)          │
│    fused_score(d) = Σ  1 / (k + rank_i(d))          │
│    k = 60 (empirically tuned)                        │
│    Merged, deduplicated → top-1 000 candidates       │
└──────────────────────┬───────────────────────────────┘
                       │  top-1 000 job IDs + scores
                       ▼
┌──────────────────────────────────────────────────────┐
│  STAGE 2 — RANKING  (target: precise ordering)       │
│                                                      │
│  Feature Assembly (per candidate×job pair):          │
│    Static features (low latency, from Feature Store):│
│    • embedding cosine similarity                     │
│    • BM25 score                                      │
│    • skill overlap ratio (Jaccard)                   │
│    • experience delta (candidate.yoe − job.min_exp)  │
│    • salary fit score                                │
│    • geo distance / remote match                     │
│    • job freshness (hours since posted)              │
│    • employer popularity (historical apply rate)     │
│                                                      │
│    Behavioral features (from Redis Feature Store):   │
│    • candidate historical CTR on similar job types   │
│    • candidate apply-rate at similar salary bands    │
│    • job's platform-wide CTR (popularity signal)     │
│    • candidate's recent skill searches               │
│                                                      │
│  Model: LightGBM LambdaRank (trained on nDCG loss)   │
│    • ~40 features, inference < 2 ms per 1 000 items  │
│    • Output: relevance score ∈ [0, 1]                │
│                                                      │
│  Post-processing:                                    │
│    • Deduplication (same employer, same role)        │
│    • Diversity injection (max 3 jobs per employer)   │
│    • Business rules (sponsored boost, demoted)       │
│    • Returns final top-50 ordered job list           │
└──────────────────────────────────────────────────────┘
```

### 5.2 Embedding Model

```
Model architecture: Fine-tuned sentence-transformers/all-mpnet-base-v2
  Encoder:    12-layer BERT, 768 hidden dim
  Input:      "[CLS] {title} [SEP] {top-10 skills} [SEP] {summary first 256 tokens}"
  Output:     mean-pooled [CLS] vector, L2-normalized to unit sphere
  Inference:  ONNX runtime, TensorRT GPU, batch size 32, < 20 ms P99

Training setup:
  Dataset: 50 M (candidate, job) pairs labeled by application outcome
  Positive: candidate applied + got recruiter response
  Hard negative: candidate skipped after impression
  Loss: Multiple Negatives Ranking Loss (contrastive)
  Batch: 256 pairs
  Epochs: 5, AdamW lr=2e-5, warmup 5 %

Retraining cadence: Weekly (sufficient data freshness vs. training cost)
Embedding refresh: Rolling daily, prioritizing recently-updated profiles/jobs
```

### 5.3 Reciprocal Rank Fusion

```python
def reciprocal_rank_fusion(rankings: list[list[str]], k: int = 60) -> list[tuple[str, float]]:
    """
    rankings: list of ordered job_id lists from each retriever
    Returns merged list sorted by descending fused score.
    """
    scores: dict[str, float] = defaultdict(float)
    for ranking in rankings:
        for rank, job_id in enumerate(ranking, start=1):
            scores[job_id] += 1.0 / (k + rank)
    return sorted(scores.items(), key=lambda x: x[1], reverse=True)
```

RRF is chosen over linear interpolation because:
- No calibration of per-retriever score scales needed
- Robust to outliers; a single very-high-scoring document from one source does not dominate
- Empirically matches or beats learned fusion at this scale

---

## 6. API Design

### 6.1 Candidate Feed (Pull)

```
GET /v1/feed
  Headers: Authorization: Bearer <JWT>
  Query:   cursor=<opaque_token>&limit=20&filters=<urlencoded_json>

Filters schema:
  {
    "remote_only": true,
    "job_types": ["FULL_TIME"],
    "salary_min": 150000,
    "location": { "city": "Seattle", "radius_km": 50 },
    "exclude_applied": true
  }

Response 200:
  {
    "jobs": [
      {
        "job_id": "uuid",
        "title": "Senior Backend Engineer",
        "employer": { "id": "uuid", "name": "Acme Corp", "logo_url": "..." },
        "location": "Seattle, WA | Hybrid",
        "salary_range": "$160K – $210K",
        "skills_match": ["Java", "Kafka"],          // skills the candidate has
        "skills_gap": ["Go"],                        // required skills candidate lacks
        "match_score": 0.87,                         // displayable 0–1
        "posted_at": "2026-05-12T14:00:00Z",
        "apply_url": "..."
      }
    ],
    "next_cursor": "<token>",
    "total_estimate": 1420
  }
```

### 6.2 Recruiter Candidate Search (Push / Talent Search)

```
POST /v1/search/candidates
  Headers: Authorization: Bearer <recruiter_JWT>
  Body:
  {
    "job_id": "uuid",                   // search relative to a specific job posting
    "filters": {
      "skills": ["Python", "ML"],
      "min_years_exp": 3,
      "locations": ["San Francisco", "Remote"],
      "work_authorization": ["US_CITIZEN", "GC"]
    },
    "sort": "relevance",               // relevance | recency | experience
    "cursor": null,
    "limit": 25
  }

Response 200:
  {
    "candidates": [
      {
        "candidate_id": "uuid",
        "headline": "ML Engineer @ Google",
        "skills": ["Python", "PyTorch", "Kafka"],
        "years_of_experience": 6,
        "location": "San Francisco, CA",
        "remote_preference": "HYBRID",
        "match_score": 0.91,
        "skill_overlap": ["Python", "ML"],
        "open_to_work": true
      }
    ],
    "next_cursor": "<token>",
    "total_estimate": 340
  }
```

### 6.3 Feedback / Signal Collection

```
POST /v1/feedback
  Body:
  {
    "job_id": "uuid",
    "signal": "APPLY" | "CLICK" | "SKIP" | "DISLIKE",
    "session_id": "uuid",
    "rank_position": 3
  }
  → 204 No Content
```

### 6.4 Profile & Job Upsert (Internal)

```
PUT /internal/v1/candidates/{id}/embedding    // called by Embedding Worker
  Body: { "vector": [0.12, -0.04, ...], "version": "v3.1" }
  → 200

PUT /internal/v1/jobs/{id}/embedding
  Body: { "vector": [...], "version": "v3.1" }
  → 200
```

---

## 7. Streaming Pipeline — Job Alert Fanout

When a new job is posted the system must notify the top-N best-matching candidates within minutes. This is the **push path** and is fundamentally different from the pull feed.

```
Employer posts job
      │
      ▼
Job Service ──── writes to PostgreSQL ────────────────────────────────┐
      │                                                               │
      └──── publishes to Kafka topic: job.posted                     │
                       │                                             │
                       ▼                                             ▼
          ┌────────────────────────┐                     ┌──────────────────────┐
          │  EMBEDDING WORKER      │                     │  JOB-ALERT FANOUT    │
          │                        │                     │  WORKER              │
          │  1. Consume job.posted │                     │                      │
          │  2. Call Embedding Svc │                     │  Triggered by:       │
          │  3. Upsert vector into │                     │  embedding.ready     │
          │     Vector DB          │                     │  event               │
          │  4. Publish:           │                     │                      │
          │    embedding.ready     │──────────────────▶  │  1. Run Stage 1      │
          └────────────────────────┘                     │     retrieval for    │
                                                         │     job vector       │
                                                         │  2. Re-rank top-500  │
                                                         │  3. Filter:          │
                                                         │     • open_to_work   │
                                                         │     • alert prefs    │
                                                         │  4. Write top-1 000  │
                                                         │     to alerts table  │
                                                         │  5. Batch-publish    │
                                                         │     to Notification  │
                                                         │     Service          │
                                                         └──────────────────────┘
                                                                  │
                                                                  ▼
                                                    ┌─────────────────────────┐
                                                    │  NOTIFICATION SERVICE   │
                                                    │  • Push (FCM / APNs)    │
                                                    │  • Email (SendGrid)     │
                                                    │  • In-app notification  │
                                                    │  • Respects user        │
                                                    │    notification prefs   │
                                                    │    & quiet hours        │
                                                    └─────────────────────────┘
```

**Fanout rate limiting**: A single viral job posting should not spike notification load. The Fanout Worker:
- Processes alerts in batches of 1 000 candidates
- Caps per-job alert blasts at 10 000 push notifications / 5 minutes
- Surplus candidates get in-app feed insertion only

---

## 8. Caching Strategy

| Cache layer | What's cached | TTL | Invalidation |
|---|---|---|---|
| Redis L1 (hot profiles) | Candidate embedding + filter attrs for active users | 1 hour | Write-through on profile update |
| Redis L1 (hot jobs) | Job metadata for recently-posted active jobs | 30 min | Write-through on job update/expire |
| Redis (pre-computed feed) | Top-50 job IDs per candidate_id (for returning users) | 4 hours | Evicted on significant profile change |
| Redis (feature store) | Behavioral features per candidate (CTR, apply history) | 24 hours | Updated by Feedback Collector |
| CDN edge (recruiter assets) | Employer logos, static job card content | 7 days | Cache-busted on content hash change |
| Elasticsearch query cache | Repeated structured filter queries | 60 s | Automatic (ES native) |

**Cold-start strategy**: New candidates with no pre-computed feed get a synchronous Stage 1+2 call on first request. The result is written back to Redis for subsequent requests.

---

## 9. Handling Scale — Key Engineering Decisions

### 9.1 Why Not Brute-Force?

At 10M candidates × 1M jobs: **10 trillion pair-comparisons** per full recompute.
- At 1 μs/comparison: 10⁷ seconds. Completely infeasible.
- Solution: ANN reduces this to ~1 000 comparisons per query at 85%+ recall.

### 9.2 HNSW Index Characteristics

```
HNSW parameters (tuned for this workload):
  M = 32          (# connections per node; higher = better recall, more RAM)
  ef_construction = 200  (build-time search breadth)
  ef_search = 100        (query-time search breadth)

Benchmarks (Weaviate, r6g.4xlarge, 768-d, 10M vectors):
  Index build time:   ~4 hours (parallelized, incremental upserts stream in)
  Memory footprint:   ~60 GB
  QPS:                ~500 (single node), ~5 000 (10-node fleet)
  Recall@100:         ~92 %
  Latency p50/p99:    4 ms / 18 ms (with hard filters applied)
```

Filtered ANN caveat: when the post-filter selectivity is < 5% of the corpus, recall degrades. Mitigation: **pre-filter inside HNSW** (Weaviate native) rather than post-filter.

### 9.3 Read Scaling for Feed API

```
Traffic:   1 200 req/s peak  (after applying cache hit rate)
Cache hit: ~70% returning users served from Redis pre-computed feed
Miss path: Stage 1+2 in ~150 ms
Total:     360 cache misses/s → 360 × 150 ms / parallelism factor
           = 12 Retrieval pods + 8 Ranking pods handle this comfortably
```

### 9.4 Embedding Index Freshness

- **Profiles updated** → Embedding Worker picks up from Kafka `profile.updated` → re-embeds → upsert into Vector DB within 30 s.
- **Jobs posted** → Indexed within 60 s (Embedding Worker SLA).
- **Jobs expired** → Soft-deleted in Vector DB (status filter; hard-deleted nightly in batch).
- **Embedding model retrained** → Trigger a full re-embed of all 10M candidates + 1M jobs in batch (Spark → GPU farm → parallel Vector DB upserts). Target: 24-hour full re-index cycle.

### 9.5 Horizontal Scaling Per Component

| Component | Scaling unit | Bottleneck | Mitigation |
|---|---|---|---|
| Profile Service | Stateless pods | PostgreSQL write throughput | Connection pool (PgBouncer), read replicas for reads |
| Embedding Service | GPU pods | GPU memory | ONNX TensorRT, batch-32, auto-scale on CPU queue depth |
| Retrieval Service | Stateless pods | Vector DB query throughput | Vector DB read replicas, Elasticsearch shard scaling |
| Ranking Service | Stateless pods | Feature assembly latency | Redis pipeline for batch feature fetch |
| Fanout Worker | Kafka consumer group | Throughput per partition | Scale consumer group; 32 partitions on job.posted topic |
| Vector DB | Sharded cluster | RAM per node | H3 geo-sharding (candidates by geo cell); hot shards replicated |

---

## 10. ML Feedback Loop

Matching quality compounds over time through a closed feedback loop:

```
User interactions (clicks, applies, skips)
           │
           ▼  (Kafka: feedback topic)
  ┌────────────────────────┐
  │  FEEDBACK COLLECTOR    │
  │  • Dedup (session      │
  │    window 30 min)      │
  │  • Enriches with:      │
  │    rank position,      │
  │    retrieval scores    │
  └──────────┬─────────────┘
             │
             ▼  (Spark batch, daily)
  ┌────────────────────────┐
  │  FEATURE ENGINEERING   │
  │  • Label creation:     │
  │    apply=1, skip=0,    │
  │    click-no-apply=0.3  │
  │  • Session grouping    │
  │    for LTR training    │
  └──────────┬─────────────┘
             │
    ┌────────┴────────────────────┐
    ▼                             ▼
┌─────────────────┐    ┌──────────────────────────┐
│  EMBEDDING      │    │  RANKING MODEL RETRAINING │
│  MODEL FINETUNE │    │                           │
│  Weekly         │    │  LightGBM LambdaRank       │
│                 │    │  nDCG loss                 │
│  Improves:      │    │  ~40 features              │
│  • Semantic     │    │  Train: 3 M sessions       │
│    alignment    │    │  Validation: holdout week  │
│  • Hard negat.  │    │  Frequency: Daily          │
│    mining from  │    │                            │
│    skip signals │    │  Champion/challenger A/B:  │
└─────────────────┘    │  5% traffic to challenger  │
                       └──────────────────────────┘
```

**Guard rails on the feedback loop:**
- Popularity bias correction: inverse propensity scoring (IPS) weights
- Position bias correction: rank-based propensity (items shown at position 1 are clicked more regardless of relevance)
- Novelty injection: 10% of feed slots reserved for exploration (Thompson sampling)

---

## 11. Failure Modes & Mitigations

| Failure | Impact | Detection | Mitigation |
|---|---|---|---|
| Vector DB node down | Degraded ANN recall or query failure | Health check, p99 latency alert | Replica reads; fallback to ES-only retrieval |
| Embedding Service overloaded | Stale embeddings; new profiles/jobs not indexed | Queue depth alert | Scale GPU pods; serve cached embeddings |
| Kafka consumer lag spike | Job alerts delayed > 5 min | Consumer lag metric > 10 K | Scale consumer group; add partitions |
| Redis eviction during spike | Pre-computed feeds evicted | Cache miss rate alert | Increase maxmemory; promote hot keys to TTL=∞ |
| Ranking model produces uniform scores | Feed looks un-personalized | nDCG drop alert in shadow eval | Auto-rollback to prior champion model |
| PostgreSQL primary failover | Write outage for profile/job updates | Replication lag + heartbeat | Aurora Multi-AZ: automatic failover < 30 s |
| Full region failure | Complete unavailability | Synthetic health checks | Route 53 health check → standby region warm; RPO < 1 min via synchronous replica |
| Embedding model drift | Match quality degrades | Offline nDCG weekly eval | Retraining pipeline triggers on nDCG < 0.68 |

### Circuit Breaker Pattern

Each downstream call from Retrieval Service wraps in a circuit breaker (Resilience4j):

```
Vector DB call:
  CLOSED  → normal operation
  OPEN    → fast-fail, return ES-only results (degraded but functional)
  HALF_OPEN → allow 10 req/30s probe; close on 80% success

Ranking Service call:
  OPEN    → return Stage 1 score-sorted list (no ML re-rank)
```

---

## 12. Observability

### 12.1 Key Metrics

```
# Business
job_impressions_total{source="feed"|"alert"|"search"}
job_applications_total
feed_ctr_ratio                          -- click / impression per session
match_nDCG_p50_p99                      -- evaluated hourly on labeled holdout

# Latency
feed_api_duration_seconds{p50,p95,p99}
retrieval_stage1_duration_ms{p99}
retrieval_stage2_duration_ms{p99}
embedding_inference_duration_ms{p99}

# Infrastructure
vector_db_query_latency_ms{p99}
elasticsearch_query_latency_ms{p99}
redis_hit_ratio
kafka_consumer_lag{topic,group}
embedding_index_lag_seconds             -- now() - newest embedding updated_at in index
```

### 12.2 Distributed Tracing

Every inbound request carries a `trace_id` propagated through:
```
API Gateway → Retrieval Service → [Vector DB call, ES call] → Ranking Service → Redis feature fetch
```
OpenTelemetry → Jaeger/Tempo. Alerts fire on > 5% of traces with p99 > SLO threshold.

### 12.3 Dashboards

| Dashboard | Audience | Key panels |
|---|---|---|
| Match Quality | ML team | nDCG@10, recall@100, CTR, apply-rate trend |
| Feed Latency | Platform SRE | p50/p99 per stage, error rate, cache hit ratio |
| Index Freshness | Data Eng | Job indexing lag histogram, embedding staleness % |
| Fanout Health | SRE | Kafka lag, notification delivery rate, blast error rate |

---

## 13. Data Privacy & Security

| Concern | Mechanism |
|---|---|
| Candidate PII in vectors | Embeddings contain no raw PII (encoded from structured fields); raw text stored encrypted at rest (AES-256) |
| Recruiter access control | Candidates can opt-out of recruiter search; `searchable` flag in Vector DB filter attribute |
| Right to erasure (GDPR) | Candidate deletion: PostgreSQL soft-delete → async propagate to Vector DB deletion, Elasticsearch delete by ID, Redis TTL expiry |
| Audit log | All profile views by recruiters logged to append-only table + S3 (7-year retention) |
| Embedding inversion attacks | L2-normalized 768-d vectors: academic work shows partial reconstruction is possible; mitigate with differential privacy noise on embeddings in recruiter-facing responses |
| Tenant isolation | `employer_id` on every job; ACL enforced at Ranking Service; no cross-tenant data leakage |

---

## 14. Evolution Roadmap

### v1 (MVP — described above)
- Hybrid ANN + BM25 retrieval
- LightGBM re-ranker trained on CTR/apply signals
- Job alert fanout via Kafka
- Redis pre-computed feeds

### v2 — Personalization Depth
- **Session context**: incorporate what the candidate viewed in the last 30 minutes as a soft query modifier (query-time embedding shift)
- **Cross-encoder re-ranker**: replace LightGBM with a BERT cross-encoder for top-50 re-rank (higher accuracy, but 10–20ms added latency; acceptable at stage 2)
- **Bias audit tooling**: measure disparate impact by gender/ethnicity-proxied signals; add fairness constraints to LambdaRank loss

### v3 — LLM-Augmented Matching
- **LLM-generated match explanations**: "We think you're a strong match because your Kafka experience aligns with their real-time data pipeline work."
- **Resume ↔ JD structured extraction**: LLM-based entity extraction to normalize skills, titles, and requirements into canonical form before embedding
- **Conversational job search**: natural language query → embedding → feed (e.g., "find me a remote ML role at a fintech startup paying 200K+")

### v4 — Global Scale
- Multi-region active-active (US, EU, APAC) with geo-partitioned Vector DB shards
- Per-language embedding models (fine-tuned per locale)
- Cross-border job recommendations with legal work-auth aware routing

---

## 15. Interview Trade-off Discussion Points

| Question | Answer |
|---|---|
| "Why not just use Elasticsearch for everything?" | ES BM25 misses semantic synonyms (e.g., "ML Engineer" ≠ "machine learning developer" in keyword space). ANN on embeddings captures latent semantics. But ES is retained for keyword precision and structured filters. |
| "Why HNSW over IVF-PQ?" | HNSW gives better recall at the same latency; no training step needed; handles incremental upserts without full rebuild. IVF-PQ is better when RAM is severely constrained (quantization). |
| "Why LightGBM over a neural ranker?" | At 1 000 items to re-rank per query, a neural ranker (cross-encoder) is 10–20ms per pair = too slow. LightGBM at 40 features is 2 ms for 1 000 items. A cross-encoder is reserved for final top-20 in v2. |
| "How do you handle cold-start candidates?" | Fall back to rule-based scoring: experience match, skill overlap, salary fit. As soon as 5+ behavioral signals accumulate, the learned ranker takes over. |
| "What if the Vector DB goes down?" | Circuit breaker opens; Retrieval Service falls back to ES-only BM25. Match quality degrades but the system stays available. |
| "How do you prevent popular jobs from dominating the feed?" | Diversity injection (max 3 jobs per employer); IPS correction for popularity bias in ranking model training. |
| "Why weekly embedding retraining vs. daily?" | Embedding model training takes 12–18 hours on our GPU budget. The delta from one more day of signal is marginal compared to the operational overhead. The ranking model (cheaper to train) runs daily. |
| "How do you ensure GDPR compliance for embeddings?" | Deletion pipeline: soft-delete in PG → async propagate to Vector DB delete by ID → Redis TTL. Full propagation SLA: 24 hours. Embeddings do not reverse-reconstruct PII with high fidelity but we add DP noise as a defense-in-depth. |
