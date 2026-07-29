# Semantic Search Engine — HLD (Staff Engineer)

> Opinionated design for a multi-tenant semantic (+ lexical hybrid) search platform over hundreds of millions of documents per tenant. Tradeoffs named, hard problems solved explicitly.

---

## Table of Contents

1. [Problem Framing & Resolved Assumptions](#1-problem-framing--resolved-assumptions)
2. [Capacity (napkin math)](#2-capacity-napkin-math)
3. [API (Control + Query Plane)](#3-api-control--query-plane)
4. [Architecture](#4-architecture)
5. [The Hard Problems (and how I'm solving them)](#5-the-hard-problems-and-how-im-solving-them)
6. [Database Ownership](#6-database-ownership)
7. [Data Model](#7-data-model)
8. [Failure Modes](#8-failure-modes)
9. [Relevance Evaluation & Experimentation](#9-relevance-evaluation--experimentation)
10. [Observability](#10-observability)
11. [Rollout & Ops Concerns](#11-rollout--ops-concerns)
12. [What I'm Deliberately NOT Building](#12-what-im-deliberately-not-building)
13. [Trade-off Q&A](#13-trade-off-qa)
14. [Why This Design Scales](#14-why-this-design-scales)

---

## 1. Problem Framing & Resolved Assumptions

Before anything else, pinning down the ambiguous parts of the prompt "design a semantic search engine." In a real interview these are negotiated; here are the positions I'd defend.

| Decision | My answer | Why |
|---|---|---|
| Product shape | **Multi-tenant search-as-a-platform** (think: an internal Elasticsearch/Algolia/Vespa replacement), not a single-corpus consumer search box | Forces multi-tenancy, per-tenant schema, and isolation into the design from day one instead of bolting it on later |
| Corpus size | Up to **500M documents for the largest tenant**, aggregate **5B documents** across all tenants | Large enough that "just load embeddings into memory" fails; small enough it's not a distributed-database-research problem |
| Query type | **Hybrid**: lexical (BM25) + dense (embedding) + optional structured filters (price, date, tenant ACLs) | Pure vector search loses on exact-match (SKUs, IDs, names); pure lexical loses on paraphrase/intent. Real systems need both. |
| Freshness | New/updated docs **searchable within 5s (P99)**; deletes **within 1s (P99)** | Deletes must be fast for compliance/takedown; creates can tolerate slightly more lag |
| Latency budget | **P99 ≤ 150ms** end-to-end for a query, including rerank | Interactive search UX; anything past ~200ms feels laggy |
| Consistency | **Read-your-own-write is a soft goal, not a hard guarantee**, except for deletes (hard: a deleted doc must never appear) | Search is not a system-of-record; source-of-record write path already happened. But "I deleted it and it still shows up" is a trust-breaking bug. |
| Embedding model lifecycle | **Model is versioned and swappable per tenant**, with a supported zero-downtime migration path | Embedding models improve every 6–12 months; a design that requires full downtime to upgrade is not staff-level |
| Ranking | **Learning-to-rank on top of retrieval**, not just raw similarity score | Raw cosine similarity is a signal, not a final answer — CTR, recency, business boosts all matter |
| Multi-tenancy | **Shared infrastructure, isolated data and quota**, not one cluster per tenant | Cluster-per-tenant doesn't scale operationally past a few hundred tenants; shared infra with hard quota/rate-limit isolation does |

### What I'm explicitly *not* solving on day one
Full LLM-based RAG/answer generation is **out of scope** — that's a separate system built *on top of* this one (this system is the retrieval layer a RAG pipeline would call). This doc is about **returning ranked, relevant documents**, not synthesizing an answer.

---

## 2. Capacity (napkin math)

```
Largest tenant:     500M documents
Aggregate corpus:   5B documents across ~2,000 active tenants
Avg doc size:       ~2 KB text (title + body snippet used for embedding)
Embedding dim:      768 (bge-base-class model, good quality/cost balance)

Vector storage (raw, float32):
  5B docs × 768 dims × 4 bytes = ~15.4 TB raw
  After int8 quantization (4×):  ~3.8 TB
  HNSW graph overhead (~1.5–2×): ~6–8 TB total for the vector layer

Lexical index (BM25 inverted index, Lucene-style): ~1.5 TB (compressed postings)

Query volume:
  50K QPS peak aggregate, P99 tenant is bursty: design single-tenant burst to 5K QPS
  Read:write ratio ~50:1 (search-heavy, moderate ingestion churn)

Ingestion:
  200M doc creates/updates per day aggregate ≈ 2,300/s avg, 10× burst = 23,000/s
  Deletes: lower volume but latency-critical (compliance) — dedicated fast path

Query latency budget (P99 150ms):
  Query embedding:         ~10ms  (small encoder, batched or cached)
  ANN search (ScaNN/HNSW): ~15ms  (sharded, parallel fan-out)
  BM25 search:              ~10ms  (parallel with ANN, not sequential)
  Fusion (RRF):              ~1ms
  Feature fetch (LTR):      ~20ms  (recency, CTR, business signals — from a feature store)
  LTR rerank (top 100):     ~25ms  (GBDT, not a cross-encoder — see §5.5)
  Result hydration:         ~15ms  (fetch display fields from doc store)
  Network + serialization:  ~15ms
  ------------------------------
  Total:                    ~111ms  →  ~40ms headroom for tail/GC/scheduling jitter
```

**Why this matters:** at 5B vectors, exact k-NN is off the table by three orders of magnitude, and a single-node vector index is off the table by memory. Both sharding and approximation are load-bearing decisions, not optimizations — this shapes §4 and §5.3 directly.

---

## 3. API (Control + Query Plane)

```
# Ingestion (control plane)
POST /v1/tenants/{tenantId}/indexes/{indexId}/documents
  Headers: Authorization, Idempotency-Key
  Body: {
    docId: string,
    fields: { title, body, ...structured fields per tenant schema },
    acl?: { visibleTo: [...] }
  }
  → 202 { docId, ingestedAt }        # async — see §5.1 for pipeline

DELETE /v1/tenants/{tenantId}/indexes/{indexId}/documents/{docId}
  → 202 { docId, tombstonedAt }      # fast path, see §5.1

POST /v1/tenants/{tenantId}/indexes/{indexId}/documents:bulk
  Body: { s3Uri: "s3://.../batch.jsonl" }   # for backfills / large batch loads
  → 202 { jobId }

GET  /v1/tenants/{tenantId}/indexes/{indexId}/jobs/{jobId}   # bulk job status

# Query (query plane)
POST /v1/tenants/{tenantId}/indexes/{indexId}/search
  Body: {
    query: "wireless noise cancelling headphones under $100",
    filters: { price: { lt: 100 }, inStock: true },
    mode: "hybrid" | "lexical" | "semantic",   # default hybrid
    topK: 20,
    rankingProfile?: "default" | "recency_boost" | "..."
  }
  → 200 {
      results: [{ docId, score, explain?: {...} }],
      queryId,        // for click feedback / eval correlation
      tookMs
    }

POST /v1/tenants/{tenantId}/queries/{queryId}/feedback
  Body: { docId, event: "click" | "convert" | "dismiss", position }
  → 202                                # feeds the LTR / eval loop, §5.5 and §9

# Schema / config
PUT  /v1/tenants/{tenantId}/indexes/{indexId}/schema     # declare fields, embedding model, filters
```

Three things worth calling out:

- **`Idempotency-Key`** on ingestion writes — ingestion pipelines retry, and double-embedding the same doc wastes GPU cycles even if it's otherwise harmless.
- **Ingestion is async (202)**, search is synchronous (200). These have fundamentally different latency/consistency contracts and should never share a request path.
- **`queryId`** is returned on every search response and threaded through to feedback — this is the join key that makes the whole relevance-eval flywheel (§9) possible. Skipping this is the single most common mistake in a first-pass design.

---

## 4. Architecture

```
                                ┌──────────────────┐
                                │   API Gateway    │  authn, per-tenant rate limit, routing
                                └────────┬─────────┘
                    ┌────────────────────┼────────────────────┐
                    │                    │                    │
             ┌──────▼──────┐     ┌───────▼───────┐    ┌───────▼───────┐
             │  Ingestion  │     │ Query Service │    │  Schema /     │
             │  Service    │     │  (stateless)  │    │  Admin Svc    │
             └──────┬──────┘     └───────┬───────┘    └───────┬───────┘
                    │                    │                    │
          writes outbox            fan-out query       writes tenant
                    │                    │              schema/config
                    ▼                    │                    │
        ┌───────────────────────┐        │           ┌────────▼────────┐
        │ Postgres (doc         │        │           │ Postgres         │
        │ metadata, sharded     │        │           │ (tenant schema,  │
        │ by tenant)            │        │           │  quotas, config) │
        └──────────┬────────────┘        │           └─────────────────┘
                   │ CDC (Debezium)       │
                   ▼                      │
        ┌────────────────────┐            │
        │ Kafka:             │            │
        │ doc.upserted       │            │
        │ doc.deleted        │            │
        │ (part. by doc_id   │            │
        │  hash → shard)     │            │
        └──────────┬─────────┘            │
                    │                      │
      ┌─────────────┼─────────────┐        │
      ▼             ▼             ▼        │
┌───────────┐ ┌───────────┐ ┌───────────┐  │
│ Embedding │ │ Lexical   │ │ Tombstone │  │
│ Worker    │ │ Indexer   │ │ Fast-Path │  │
│ pool      │ │ (Lucene)  │ │ Worker    │  │
└─────┬─────┘ └─────┬─────┘ └─────┬─────┘  │
      │             │             │        │
      ▼             ▼             ▼        │
┌───────────────────────────────────────┐  │
│         Sharded Index Layer            │  │
│  ┌───────────┐        ┌─────────────┐  │  │
│  │ Vector    │        │ Lexical     │  │  │
│  │ shards    │        │ shards      │  │◄─┘  fan-out read (parallel)
│  │ (HNSW/    │        │ (BM25,      │  │
│  │  IVF-PQ)  │        │  Lucene)    │  │
│  └───────────┘        └─────────────┘  │
│  N shards, each replicated 3×          │
└───────────────────┬─────────────────────┘
                     │ candidates (top-100 per shard)
                     ▼
           ┌───────────────────┐
           │  Fusion (RRF)      │
           └─────────┬─────────┘
                     ▼
           ┌───────────────────┐      ┌──────────────────┐
           │ Feature Fetch      │◄─────┤ Feature Store     │
           │ (recency, CTR,     │      │ (Redis + offline  │
           │  business signals) │      │  batch pipeline)  │
           └─────────┬─────────┘      └──────────────────┘
                     ▼
           ┌───────────────────┐
           │  LTR Reranker      │  (GBDT, e.g. LightGBM — see §5.5)
           └─────────┬─────────┘
                     ▼
           ┌───────────────────┐
           │  Result Hydration  │  (fetch display fields from doc metadata store)
           └─────────┬─────────┘
                     ▼
                 Response
```

Five planes, each independently scalable: **ingestion, indexing, storage/shards, query serving, feature/feedback**.

---

## 5. The Hard Problems (and how I'm solving them)

### 5.1 Near-real-time indexing without a stop-the-world reindex

**Naive approach:** batch reindex nightly. Fails the 5s freshness requirement immediately.

**Correct approach — CDC-driven streaming index updates:**

- Every doc write lands in Postgres first (source of truth for metadata + raw fields), inside the same transaction as an **outbox row**.
- Debezium (or a native outbox publisher) tails the WAL and publishes `doc.upserted` / `doc.deleted` to Kafka, partitioned by `hash(doc_id) % numShards` — this is what makes shard assignment deterministic and lets each shard consumer own a fixed slice of the ID space.
- **Embedding Worker pool** consumes `doc.upserted`, computes the embedding (batched, GPU-backed, ~8ms/doc at batch size 64), and issues an **incremental upsert** to the vector shard (HNSW supports online insert; no rebuild needed for individual inserts).
- **Lexical Indexer** consumes the same event and does an incremental Lucene segment add — Lucene's near-real-time (NRT) reader model gives sub-second visibility.
- **Deletes get a dedicated fast path**: `doc.deleted` skips the embedding worker entirely and goes straight to a **tombstone bitset per shard**, checked at query time before results are returned. The tombstone is applied in-memory immediately (sub-100ms) while the actual vector/postings removal happens lazily during segment merge/compaction. This satisfies the hard "never show a deleted doc" requirement without waiting on compaction.

**Why not just always rebuild the ANN graph?** HNSW graphs degrade in recall if you only ever insert and never merge/rebalance (over months, deleted nodes and skewed distributions accumulate). Run a **background segment merge** (analogous to LSM compaction) per shard on a schedule (e.g., daily off-peak) that rebuilds the graph from live vectors only, then atomically swaps in the new segment. This is the same "many small immutable segments + periodic merge" pattern as Lucene, applied to the vector layer.

### 5.2 Sharding the vector index at 5B vectors

A single HNSW graph over 5B vectors doesn't fit in memory on any reasonable instance and doesn't parallelize search well past a point. Two sharding dimensions:

- **By tenant** first: a tenant's data never crosses shard boundaries with another tenant's — this is both a scaling and a security/ACL boundary. Large tenants (>10M docs) get dedicated shard ranges; many small tenants are co-located on shared shards (multi-tenant packing) to avoid fragmentation into thousands of near-empty shards.
- **Within a large tenant**, shard by `hash(doc_id) % N`. Query fan-out goes to all N shards in parallel, each returns its local top-100, and results are merged (this is why fusion happens *after* fan-out, not per-shard).

**Replication:** 3× per shard (matches the Kafka/DB pattern used elsewhere in this doc set) — one active for reads under normal operation with round-robin across replicas for load spreading, automatic failover on replica health-check failure.

**Rebalancing:** adding shards for a growing tenant is a live-migration problem — solved with **consistent hashing + dual-write during migration window**: new writes go to both old and new shard assignment until a backfill job confirms the new shard has caught up, then the old assignment is retired. This is the same pattern used for any online resharding (e.g., Cassandra vnodes, Kafka partition reassignment) — no novel mechanism needed here, just applying it to the vector layer.

### 5.3 ANN algorithm choice — HNSW vs IVF-PQ vs DiskANN

| | HNSW | IVF-PQ | DiskANN |
|---|---|---|---|
| Recall @ given latency | Best | Good | Good |
| Memory footprint | High (full vectors + graph) | Low (quantized) | Low (mostly on-disk) |
| Insert cost | Cheap (online insert) | Requires re-training centroids periodically | Cheap (append + periodic merge) |
| Best fit | Hot, high-QPS shards that fit in memory | Very large shards where memory is the constraint | Very large per-node corpora on NVMe, cost-sensitive |

**My call:** HNSW for shards that fit comfortably in memory (the majority, given int8 quantization brings 5B vectors to ~4TB total, fine across a sharded fleet), falling back to **IVF-PQ for the largest, least latency-sensitive tenants** (e.g., bulk archival search) where memory cost dominates over the last few ms of latency. This is a per-shard-class configuration, not a single global choice — a staff-level answer names the trade-off and picks per workload rather than picking one algorithm dogmatically for the whole system.

**Tuning:** `M=32–48`, `ef_construction=200`, `ef_search` tuned per tenant SLA (higher for tenants needing higher recall, at a latency cost) — this is exposed as a per-tenant knob, not baked into the binary.

### 5.4 Hybrid retrieval fusion — lexical + dense, done correctly

Neither alone is sufficient (see the classic gap: dense retrieval is poor at exact SKU/ID/name matches; lexical is poor at paraphrase and synonym intent). Both run **in parallel, not sequentially** — sequential (e.g., "lexical then rerank with dense") throws away candidates lexical search would never surface.

```
RRF_score(doc) = Σ  1 / (k + rank_i(doc))
                i ∈ {bm25, dense}
```

`k = 60` (standard smoothing constant). Rank-based fusion sidesteps the problem that BM25 scores and cosine similarities live on incomparable scales — trying to weight-average raw scores is a beginner mistake that produces unstable rankings as either subsystem's score distribution shifts.

**Query-side handling:**
- **Structured filters** (`price`, `inStock`, tenant ACL) are pushed down to *both* the lexical and vector shard queries as a pre-filter, not applied post-hoc after fusion — post-filtering a top-100 candidate set after ACL filtering can return fewer than `topK` results for restricted users, which is both a correctness bug and a subtle **information leak** (result *count* before filtering can leak the existence of restricted docs).
- **Query understanding** stage before retrieval: spell correction, synonym expansion, and a lightweight intent classifier (e.g., "is this a navigational query with a likely exact-match target?" → weight lexical higher for that query).

### 5.5 Ranking — why a cross-encoder is usually the wrong reranker here

The RAG-pipeline pattern of "ANN top-100 → cross-encoder rerank" is right for *generation-quality* grounding, but for a **latency-critical, high-QPS search product**, a cross-encoder forward pass on 100 candidates per query at 5K QPS burst is not affordable (cross-encoders are 10–30× slower per pair and don't batch as cleanly under tight latency budgets as a GBDT).

**Instead: Learning-to-Rank (LTR) with a gradient-boosted tree model (LightGBM/XGBoost) over engineered features:**

```
Features per (query, doc) candidate:
  - fused_retrieval_score (from §5.4)
  - bm25_score, dense_cosine_score (raw, not just fused)
  - recency (days since last update, log-scaled)
  - historical CTR for this doc (from feature store, decayed)
  - historical CTR for this doc given similar queries (query-cluster CTR)
  - business boost (tenant-configured field, e.g. "in stock", "sponsored")
  - text match features (exact phrase match, title match, field-level BM25)
```

This is cheap (a GBDT inference over ~15 features for 100 candidates is single-digit milliseconds), interpretable (feature importances/SHAP explain a ranking to a confused customer support engineer), and trainable offline from the click/convert feedback loop (§9) without needing GPU serving infra in the hot path.

**Where a cross-encoder still earns its keep:** as an **offline-only signal** — periodically score a sample of (query, doc) pairs with a cross-encoder and feed that as a *training label/feature* into the LTR model, rather than serving it online. Best of both: cross-encoder quality, GBDT latency.

### 5.6 Embedding model upgrades without downtime

Embedding models improve every 6–12 months, and old and new embeddings are **not comparable** (different vector spaces — you cannot mix cosine similarities from model v1 and model v2 in one ranking). This is the migration problem most designs miss entirely.

**Blue/green shadow-index migration:**

1. Stand up a **parallel vector index (green)** using the new embedding model, alongside the existing **live index (blue)**.
2. Backfill: re-embed the full tenant corpus into green via the same embedding worker pool (this is the expensive, slow part — budget it like a full reindex, hours to days depending on corpus size).
3. **Dual-write** new/updated docs to both blue and green during the migration window (the ingestion pipeline in §5.1 already fans out to N indexers; adding a second embedding-model target is additive, not a redesign).
4. Run **green in shadow mode**: serve real production queries against green in parallel, log results, do *not* return them to users. Compare against blue using the offline eval set (§9) and online interleaving experiments.
5. Once green's relevance metrics meet or beat blue's, **flip a per-tenant routing flag** to cut over. Keep blue warm for a rollback window (e.g., 7 days) before decommissioning.

**Why per-tenant, not global cutover:** different tenants have different corpora and different sensitivity to a ranking model change — a global flip risks a global regression with no blast-radius containment. Per-tenant flags turn "did the new model break something" into a scoped, revertible question instead of an incident.

### 5.7 Multi-tenancy: isolation without cluster sprawl

- **Data isolation:** every document row, every vector, every Kafka message key is prefixed/partitioned by `tenant_id`. A query can never fan out across tenant boundaries — this is enforced at the Query Service layer (tenant_id from the auth token, not from the request body) so a client cannot spoof cross-tenant reads even by accident.
- **Noisy-neighbor isolation:** per-tenant **token-bucket rate limits** on both ingestion and query QPS, enforced at the API Gateway. A tenant that bursts past its bucket gets `429`, not a slow response that steals latency budget from other tenants sharing a shard.
- **Resource isolation for large tenants:** tenants above a size threshold (e.g., >10M docs or sustained >1K QPS) get **dedicated shard ranges** instead of being packed with others — this is the same "peel off when growth forces it" principle used for database ownership decisions elsewhere in this design.
- **Schema flexibility:** each tenant declares its own field schema (`PUT .../schema`) — filterable fields, embedding model choice, ranking profile — without needing a code deploy. This is a config-driven system, not a fork-per-customer system.

### 5.8 Cold start and long-tail queries

For a new tenant (no click history) or a rare query (no historical CTR signal), the LTR model in §5.5 has missing features — this is a real failure mode, not an edge case to hand-wave.

- **Default feature values with confidence discounting:** missing CTR features default to a *global prior* (e.g., the tenant's or platform's average CTR for that position), not zero — zero implausibly penalizes candidates with no history yet.
- **Exploration budget:** reserve a small fraction of impressions (e.g., 2%) for a secondary ranking that's closer to raw retrieval score, un-reranked by CTR features — this generates the click data needed to bootstrap the CTR signal for new/rare (query, doc) pairs, avoiding a rich-get-richer feedback loop where only already-popular docs ever get shown.
- **New tenant onboarding:** ship with a reasonable **default ranking profile** (retrieval score + recency + business boost only, no CTR feature) until enough query volume accumulates to train a tenant-aware or tenant-cluster-aware LTR model.

---

## 6. Database Ownership

### The principle: database-per-bounded-context, not database-per-microservice

Strict database-per-microservice is dogma. What matters is the **bounded context**. Two services that need atomic writes to a shared aggregate belong on the same DB; two services that exchange identifiers belong on separate DBs.

| Bounded context | Services in context | DB | Why grouped (or split) |
|---|---|---|---|
| **Document metadata / source of truth** | Ingestion Service, Query Service (hydration) | **`docs-pg`** (Postgres, sharded by tenant_id) | Outbox pattern (§5.1) requires the doc write and outbox insert in one transaction. |
| **Vector index** | Embedding Worker, Vector Shard nodes | **Purpose-built vector store** (self-hosted HNSW shards, e.g. on top of a library like `hnswlib`/`faiss`, or a managed option like Qdrant/Vespa) | Wrong shape for a relational or even document DB — needs graph-structured in-memory indexes with custom serialization. |
| **Lexical index** | Lexical Indexer, Query Service | **Lucene-based segments** (self-managed or via OpenSearch) | Inverted-index data structure; different storage engine entirely from vectors. |
| **Feature store (ranking signals)** | LTR Reranker, offline training pipeline | **Redis (online) + offline warehouse (batch-computed features, e.g. on top of a lakehouse table)** | Hot path needs sub-ms feature lookup (Redis); training needs historical joins across weeks of data (warehouse). Two different access patterns, deliberately two stores. |
| **Tenant config / schema / quotas** | Schema/Admin Service | **`tenant-pg`** (Postgres) | Low write volume, strong consistency needed (a quota change must apply immediately, not eventually). |
| **Query/feedback log** | Query Service (write), Eval pipeline (read) | **Kafka → columnar store (e.g. ClickHouse)** | Append-only, high-volume, analytical access pattern — wrong shape for Postgres at this volume. |

**Cross-service references are by ID, never foreign key.** The vector shard stores `doc_id` and the embedding — nothing else. Display fields (title, image, price) are hydrated from `docs-pg` at query time (§4, "Result Hydration" stage), never denormalized into the vector store, so a metadata update never requires touching the vector index.

### Shared cluster vs. separate clusters

Pragmatic position, consistent with the rest of this design philosophy: **shared Postgres cluster with per-context logical databases** for the control-plane stores (`docs-pg`, `tenant-pg`), **dedicated purpose-built clusters** for the vector store, lexical index, and feature store, because those have fundamentally different storage engines, not just different schemas. Peel `docs-pg` into its own cluster when a single large tenant's write volume starts affecting others sharing the cluster.

---

## 7. Data Model

### Postgres (`docs-pg`, sharded by `tenant_id`)

```sql
documents (
  tenant_id uuid, doc_id text,
  fields jsonb,                      -- tenant-defined schema, validated against tenant schema config
  status text,                       -- ACTIVE | TOMBSTONED
  embedding_model_version text,      -- which model produced the currently-indexed embedding
  created_at, updated_at,
  PRIMARY KEY (tenant_id, doc_id)
);

outbox (
  id bigserial pk, tenant_id uuid, doc_id text,
  event_type text,                   -- UPSERTED | DELETED
  payload jsonb,
  created_at, published_at           -- null until publisher confirms Kafka write
);
CREATE INDEX ON outbox (published_at) WHERE published_at IS NULL;
```

### Vector shard (per-shard, off-heap index + a lightweight local metadata map)

```
vector_shard_N:
  doc_id -> (embedding vector, embedding_model_version, tombstoned: bool)
  HNSW graph over ACTIVE, non-tombstoned vectors
```

### Feature store (Redis, hot path)

```
ctr:{tenant_id}:{doc_id}            -> decayed CTR (float), TTL refreshed by batch job
ctr_cluster:{tenant_id}:{query_cluster_id}:{doc_id} -> decayed CTR for similar queries
quota:{tenant_id}:qps               -> token bucket state
```

### Query/feedback log (Kafka → ClickHouse)

```sql
query_log (
  query_id uuid, tenant_id uuid, query_text text,
  filters jsonb, mode text, results array(doc_id), scores array(float),
  took_ms int, timestamp datetime
);
feedback_log (
  query_id uuid, doc_id text, event text, position int, timestamp datetime
);
```

---

## 8. Failure Modes

| Failure | Detection | Recovery |
|---|---|---|
| Embedding worker pool falls behind (backlog grows) | Kafka consumer lag alert on `doc.upserted` | Autoscale worker pool; docs remain searchable via stale embedding until caught up (degraded relevance, not an outage) |
| Vector shard node crash | Health probe / replica promotion | Traffic fails over to a healthy replica of the same shard; re-sync from the other replica |
| Tombstone not yet applied at query time (race between delete and in-flight read) | N/A — by design the tombstone bitset check happens synchronously in the query path | Delete path is prioritized over the embedding pipeline specifically to close this window (§5.1) |
| Lexical and vector shard counts drift out of sync (one over-provisioned relative to the other) | Per-subsystem shard-count/latency dashboards | Independent autoscaling per subsystem; alert if p99 gap between BM25 and ANN legs of the fan-out exceeds budget |
| Feature store (Redis) outage | Connection errors from LTR reranker | Fall back to retrieval-score-only ranking (skip LTR stage entirely) — degraded relevance, not a hard failure |
| Postgres (`docs-pg`) primary loss | Health probe | Failover to replica; ingestion writes reject briefly; existing index continues serving reads from already-indexed data (search doesn't go down when the source-of-record has a blip) |
| Embedding model migration (§5.6) regression discovered post-cutover | Online metrics (CTR, zero-result rate) regress for a tenant | Per-tenant routing flip back to blue index (kept warm for the rollback window) |
| Kafka outage | Producer errors on outbox publisher | Outbox rows accumulate `published_at IS NULL`; publisher retries once Kafka recovers — no data loss, bounded ingestion lag |
| Hot shard (one tenant/doc skews traffic) | Per-shard QPS/latency dashboards | Tenant-aware shard rebalancing (§5.2); in the interim, add read replicas for the hot shard specifically |

---

## 9. Relevance Evaluation & Experimentation

A search system without continuous relevance measurement drifts invisibly — corpora change, query distributions shift, and "relevance" silently degrades with no error to page anyone.

**Offline:**
- **Golden query set** per major tenant (or tenant cluster): human-graded relevance labels (0–3 scale) for a sample of (query, doc) pairs. Compute **NDCG@10**, **Recall@50** (did retrieval even surface the graded-relevant docs before rerank) nightly against the live pipeline; alert on regression > 2%.
- **Retrieval-vs-rerank attribution:** track NDCG at the retrieval stage (pre-LTR) *and* post-LTR separately — this is what lets you tell whether a regression is a retrieval problem (§5.2–5.4) or a ranking-model problem (§5.5), the same "which stage owns the failure" discipline as the RAG pipeline's component-level evals.

**Online:**
- **Interleaving experiments** (Team-Draft Interleaving) for ranking model changes — more statistically efficient than traditional A/B for ranking comparisons, needs far fewer queries to reach significance.
- **Standard A/B** for larger architectural changes (e.g., embedding model migration, §5.6) where interleaving doesn't apply cleanly.
- **Guardrail metrics**, tracked on every experiment regardless of what's being tested: **zero-result rate**, **P99 latency**, **CTR@1**, **downstream conversion** (if applicable to the tenant's use case).

**The feedback flywheel:** `queryId` from §3 joins `query_log` and `feedback_log` in ClickHouse → nightly job computes decayed CTR per (tenant, doc) and per (tenant, query-cluster, doc) → written to the feature store (§7) → consumed by the next day's LTR training run. This loop is what makes ranking improve over time without a human re-tuning weights by hand.

---

## 10. Observability

**SLIs (per tenant + global):**

- `search_latency_p50/p99` (target P99 < 150ms)
- `index_freshness_lag` = `now() - last_applied_event_time` per shard (target < 5s creates, < 1s deletes)
- `zero_result_rate` (spikes indicate a query-understanding or retrieval regression)
- `ann_recall_at_100` (sampled against exact brute-force search on a held-out set — catches silent HNSW quality degradation)
- `kafka_consumer_lag` per topic per consumer group (embedding worker, lexical indexer, tombstone worker)
- `ltr_feature_store_hit_rate` (drops indicate Redis pressure or a cold-start spike)

**Alerts:** freshness lag > SLA for 5min; any tenant P99 > 300ms for 5min; zero-result rate doubles vs. 7-day baseline; ANN recall drops > 3% vs. baseline; consumer lag growing unboundedly (not just nonzero — growth rate matters more than absolute value).

**Tracing:** single trace ID per query, spanning gateway → fan-out to both retrieval legs → fusion → feature fetch → LTR → hydration. This is the only way to answer "why was this specific result ranked #4" in under a minute during an incident.

**Explainability endpoint:** given a `queryId` and `docId`, return the full feature vector and fused score that produced the rank — indispensable for both debugging and customer-facing "why am I not seeing X" support escalations.

---

## 11. Rollout & Ops Concerns

- Per-tenant **kill switch** (serve from a static fallback / cached top results if a tenant's index is unhealthy, rather than a hard 5xx).
- **Shadow mode** for new ranking models or embedding models, as described in §5.6 — never cut over without a shadow comparison window.
- **Reindex-from-scratch runbook**, tested regularly (game-day style): given only `docs-pg`, can we fully rebuild both the vector and lexical index for a tenant from zero? If this isn't a rehearsed, working runbook, "5B vectors" is one corrupted segment away from an unrecoverable incident.
- **Schema evolution:** tenants can add new filterable fields without reindexing existing docs (`NULL`/default until backfilled) — schema changes must never require a synchronous full reindex to become live.
- **Per-tenant query cost caps** (max candidates fanned out, max filter complexity) to prevent a single misconfigured tenant query from degrading shared shard latency.

---

## 12. What I'm Deliberately NOT Building

Staff engineers say no.

- **LLM answer generation / RAG** — this system is the retrieval layer a RAG pipeline calls; synthesis is a separate system with its own hallucination/grounding concerns (see the companion RAG pipeline design).
- **Cluster-per-tenant isolation** — doesn't scale operationally past a few hundred tenants; shared infra + quota isolation instead.
- **Cross-encoder in the online serving path** — too slow at this QPS; used offline only, as a training signal into the LTR model.
- **Global embedding model version** — per-tenant model choice and migration path instead; a monolithic model version blocks incremental improvement.
- **Exact k-NN** — infeasible at 5B vectors; ANN with measured, alerted recall instead.
- **Synchronous cross-tenant analytics on the hot path** — feedback/query logs go to an offline columnar store, never read synchronously during a live search request.

---

## 13. Trade-off Q&A

These are the questions interviewers ask to separate senior from staff-level candidates.

### "Why hybrid retrieval instead of just embeddings — aren't embeddings supposed to capture semantics, including exact terms?"

> "In theory a perfect embedding model captures everything. In practice, dense encoders are trained to generalize, which means they smooth over exactly the tokens that matter most for exact-match queries — SKUs, part numbers, proper nouns the model has never seen. BM25 is bad at paraphrase but perfect at exact term overlap. Fusing both, rather than picking one, means neither weakness is load-bearing for the whole system."

### "Why not use a single reranker (cross-encoder) end to end — wouldn't that give the best relevance?"

> "It would likely give the best *relevance per query*, but the question is relevance at a fixed latency and cost budget, at 5K QPS burst per tenant. A cross-encoder forward pass over 100 candidates doesn't fit a 150ms P99 budget at that volume without a very large, expensive GPU fleet. A GBDT-based LTR model gets most of the relevance benefit — because its features already encode the useful signals (raw scores, CTR, recency) — at a fraction of the latency and cost. I'd use the cross-encoder offline, as a label generator for the GBDT's training data, not online."

### "How do you avoid the CTR feature turning into a rich-get-richer bias where old popular docs always rank first?"

> "Two defenses: decay the CTR signal over time so it reflects recent, not historical-forever, engagement, and reserve a small exploration budget of impressions ranked without the CTR feature so new and rare docs get a chance to accumulate click data. Without exploration, the ranking model only ever gets training signal on what it already promotes — a closed feedback loop that calcifies the top results."

### "What happens when you need to upgrade the embedding model — walk me through it."

> "You cannot swap vectors in place because old and new embeddings aren't comparable in the same space. I'd stand up a parallel index with the new model, backfill by re-embedding the full corpus, dual-write new documents to both indexes during the migration window, run the new index in shadow mode comparing against the old index on real traffic, and only flip a per-tenant routing flag once the new index's offline and online relevance metrics meet or beat the old one — keeping the old index warm for a rollback window before decommissioning it."

### "Your P99 latency budget is 150ms with a lot of stages — what's the first thing you'd cut under load?"

> "The LTR rerank stage has the most graceful degradation path — falling back to the fused retrieval score with no reranking still returns reasonable, just less personalized, results. I'd shed that stage first under load, not the retrieval fan-out itself, since a request that skips retrieval entirely returns nothing, while one that skips reranking still returns something coherent."

### "How would you detect a silent relevance regression that doesn't show up as an error or a latency spike?"

> "This is exactly why the offline golden-query NDCG job and the ANN recall sampling job exist as their own alertable SLIs, separate from latency and error rate. A change that quietly makes results worse — a bad embedding model rollout, an HNSW graph degrading from too many unmerged deletes, a feature store returning stale CTR — will never trip a latency or 5xx alert. It only trips a relevance-quality alert, which is why relevance has to be treated as a first-class, continuously monitored metric, not a launch-day checkbox."

---

## 14. Why This Design Scales

- **Every plane is horizontally scalable.** Query Service is stateless; ingestion, embedding, and lexical indexing are Kafka consumer groups; vector and lexical shards scale by adding shard ranges.
- **Retrieval and reranking are decoupled.** The expensive-per-candidate stage (LTR, or offline cross-encoder labeling) only ever runs on ~100 candidates, never the full corpus — the two-stage funnel is what makes 5B vectors compatible with a 150ms budget.
- **Freshness and correctness are separated by priority, not by mechanism reuse.** Deletes get a dedicated fast path because "wrong" (a deleted doc showing up) is a worse failure than "slow" (a new doc taking a few extra seconds to appear).
- **Multi-tenant fair by construction.** Tenant-partitioned shards, per-tenant quotas, and per-tenant rollout flags mean one tenant's traffic spike, bad query, or model migration can't silently degrade another tenant's experience.
- **Model and index upgrades are non-events, not maintenance windows**, because blue/green shadow migration is designed in from the start rather than retrofitted after the first painful in-place upgrade.
- **Relevance is observable**, not just inferred from the absence of complaints — offline eval, online interleaving, and per-stage attribution mean a regression is caught by a dashboard, not a support ticket.

---

## Appendix A — Whiteboard Focus

If doing this on a whiteboard, draw §4 first, then spend the bulk of the time on **§5.1 (near-real-time indexing)**, **§5.4 (hybrid fusion)**, and **§5.6 (embedding model migration)**. Those three are what separate a design that works on day one from one that survives two years of model upgrades and corpus growth.

## Appendix B — Common Gaps in Mid-Level Answers

1. Treating semantic search as "just embeddings + a vector DB," with no lexical fallback for exact-match queries.
2. No answer for how to upgrade the embedding model without a full-corpus, full-downtime reindex.
3. Using a cross-encoder online at production QPS without doing the latency/cost math.
4. No fast, dedicated path for deletes — treating them the same as any other async-indexed write, which lets deleted content leak past the delete request.
5. No offline relevance evaluation — shipping ranking changes based on vibes, discovering regressions only from user complaints.
6. Post-filtering ACL/permission checks after retrieval instead of pushing filters into the retrieval query itself (both a correctness and information-leak bug).
7. A single global embedding model version with no per-tenant migration path.
8. No exploration budget in the ranking feedback loop, leading to a closed rich-get-richer bias in what ever gets shown or clicked.
