# RAG Pipeline for 10M Docs — Zero Unsupported Claims (Principal / Staff HLD)

**The question:** "Design a RAG pipeline for 10 million documents with zero hallucination."

**Why this question matters at L5/L6:** It is not a vanilla scale question. It is a product contract + retrieval + verification systems question. The interviewer is watching whether you treat "zero hallucination" as a slogan or as a **verifiable release gate**.

---

## Table of Contents

1. [Principal Framing — Redefine the Contract](#1-principal-framing--redefine-the-contract)
2. [Resolved Assumptions](#2-resolved-assumptions)
3. [Design Principles](#3-design-principles)
4. [Capacity Math](#4-capacity-math)
5. [Architecture — Full Picture](#5-architecture--full-picture)
6. [Ingestion & Indexing](#6-ingestion--indexing)
7. [Hybrid Retrieval + Rerank](#7-hybrid-retrieval--rerank)
8. [Answerability Gate](#8-answerability-gate)
9. [Dual Generation Path (Extractive-First)](#9-dual-generation-path-extractive-first)
10. [Claim Decomposition & Verification](#10-claim-decomposition--verification)
11. [Citation Contract & Response Schema](#11-citation-contract--response-schema)
12. [Caching, Freshness & Invalidation](#12-caching-freshness--invalidation)
13. [Evals, SLOs & Observability](#13-evals-slos--observability)
14. [Failure Modes](#14-failure-modes)
15. [Key Trade-offs](#15-key-trade-offs)
16. [Interview Q&A](#16-interview-qa)
17. [One-Sentence Takeaway](#17-one-sentence-takeaway)
18. [Cheat Sheet](#18-cheat-sheet)

---

## 1. Principal Framing — Redefine the Contract

A principal engineer does not start drawing boxes. The first move is to challenge the prompt.

> "Zero hallucination is not a binary model property. LLMs are probabilistic. What we *can* enforce is a product SLA: **no response is released unless every atomic claim is entailed by a retrieved, cited span.** Anything that fails that gate is refused, clarified, or escalated — never soft-served as fact."

### Operational definition (what we ship)

| Term | Definition | Target |
|---|---|---|
| **Unsupported claim** | A factual assertion in the answer that is not entailed by any cited retrieved span | **0 released** (hard gate) |
| **Groundedness rate** | % of released answers where 100% of claims pass NLI entailment vs cited spans | **≥ 99.9%** of *released* answers |
| **Abstention rate** | % of queries refused / clarified instead of answered | Domain-dependent (often 5–20%; *not* a failure) |
| **Citation validity** | Every `[source_id]` maps to a real retrieved chunk | **100%** (deterministic check) |
| **Fabricated citation rate** | Invented doc/chunk IDs or URLs | **0** |

**Critical distinction:** "Zero hallucination" ≠ "always answers."  
It means **zero unsupported claims leave the system**. Abstention is a success mode.

### Five questions before architecture

1. **Domain severity?** Medical/legal → extractive-first + hard refuse. Internal FAQ → generative with warn band allowed.
2. **Latency budget?** Interactive ≤ 2s P99 forces verifier budget discipline.
3. **Freshness SLA?** Minutes vs hours changes Kafka vs batch ingest.
4. **Update rate?** Near-static corpus vs live docs changes cache invalidation design.
5. **Data residency / VPC?** Determines self-hosted LLM vs API.

---

## 2. Resolved Assumptions

| Decision | Position | Why |
|---|---|---|
| Corpus | **10M documents**, ~5 chunks/doc → **~50M chunks** | Large enough that exact kNN and single-node "load all embeddings" fail |
| Query shape | Enterprise Q&A over policies/docs/tickets (not open-web chat) | Grounding + citations are the product |
| Retrieval | **Hybrid BM25 + dense + cross-encoder rerank** | Exact IDs need sparse; paraphrase needs dense |
| Generation | **Extractive-first dual path**; free-form generation only after answerability clears | Closest practical path to "zero unsupported claims" |
| Verification | **Mandatory** citation check + claim NLI before release | Prompting alone is not a control |
| Consistency | Search is not system-of-record; **deleted docs must never be cited** | Trust-breaking otherwise |
| Multi-tenancy | Out of day-one scope unless asked; design ACLs as a filter stage | Avoid boiling the ocean |

Out of scope day one: multi-agent tool use, full agentic browse, training a custom foundation model.

---

## 3. Design Principles

| # | Principle | Implication |
|---|---|---|
| **P1** | Groundedness over fluency | Prefer "I don't know" over a polished fabrication |
| **P2** | Every claim is attributable | No released answer without span-level provenance |
| **P3** | Retrieval quality gates generation | Weak retrieval → refuse, don't invent |
| **P4** | Verify before release | Post-generation entailment is a hard gate, not telemetry |
| **P5** | Degrade loudly | Warn / clarify / escalate — never silent low-confidence |
| **P6** | Measure the contract | Faithfulness, abstention, citation validity are SLIs with alerts |

---

## 4. Capacity Math

```
Corpus:
  10M docs × 5 chunks/doc          = 50M chunks
  Embedding dim                    = 768 (bge-base) or 1536 (OpenAI-class)
  Raw vectors (768×f32):           50M × 768 × 4 ≈ 154 GB
  Raw vectors (1536×f32):          50M × 1536 × 4 ≈ 307 GB
  After int8 quantization:         ~40–77 GB vectors
  HNSW graph overhead (~1.5–2×):   ~80–150 GB vector layer
  BM25 inverted index:             ~40–60 GB
  Doc/chunk metadata store:        ~50–100 GB
  ────────────────────────────────
  Comfortable footprint:           multi-node sharded cluster, not one laptop

Ingestion (initial load in 48h):
  10M / 48h ≈ 58 docs/s ≈ 290 chunks/s
  Parallel embed workers + bulk upsert; Kafka for streaming updates

Query path latency budget (interactive P99 ≤ 2.0s, cache miss):
  Query embed + rewrite:     20–40ms
  Hybrid retrieve (parallel):  30–60ms
  Cross-encoder top-100:       40–80ms (GPU batch)
  Answerability + confidence:  10–20ms
  Generation (extractive):     50–150ms   OR generative 400–900ms
  Claim split + NLI verify:    80–200ms
  Hydration + serialize:       20–40ms
  ────────────────────────────────
  Extractive path P50:         ~300–500ms
  Generative path P50:         ~700–1200ms
  Headroom for tail/GC:        keep P99 under 2s with autoscaled LLM/NLI

QPS (example 1K peak, 60% semantic-cache hit):
  600 QPS served from cache (~2ms)
  400 QPS full pipeline → LLM/NLI are the bottleneck → scale those fleets independently
```

**Load-bearing conclusion:** at 50M chunks, ANN + sharding are required; the cross-encoder cannot scan the corpus; verification must be budgeted into latency like any other service dependency.

---

## 5. Architecture — Full Picture

```
┌──────────────────────────────── INGESTION ─────────────────────────────────┐
│ Raw docs (S3/GCS/Drive)                                                    │
│   → Parse/OCR → Normalize → Dedup (MinHash) → Hierarchical chunk           │
│   → Quality score → Embed (versioned) → BM25 index + Vector index          │
│   → Chunk store (text + provenance) + tombstone delete path                │
└────────────────────────────────────┬───────────────────────────────────────┘
                                     │
┌──────────────────────────────── QUERY PATH ────────────────────────────────┐
│ Query → Normalize / expand / classify                                      │
│      → Hybrid retrieve (BM25 ‖ Dense ANN) → RRF → top-100                  │
│      → Cross-encoder rerank → top-8–12                                     │
│      → Confidence + Answerability gate                                     │
│            │ fail → ABSTAIN / CLARIFY / ESCALATE                           │
│            ▼ pass                                                          │
│      ┌─────┴──────────────────────────────┐                                │
│      │ EXTRACTIVE path (default high-stakes)│  GENERATIVE path (cleared) │
│      │ span select / quote / template       │  constrained LLM + cites   │
│      └─────┬──────────────────────────────┘                                │
│            ▼                                                               │
│      Claim decomposer → Citation validator → NLI entailment (per claim)    │
│            │ fail → regenerate once OR abstain                             │
│            ▼ pass                                                          │
│      Release {answer, sources[], claim_trace[], abstained=false}           │
└────────────────────────────────────┬───────────────────────────────────────┘
                                     │
┌──────────────────────── OBSERVABILITY / EVAL / CACHE ──────────────────────┐
│ Exact + semantic cache (invalidate on cited-doc update)                    │
│ Golden-set nightly eval (RAGAS-style faithfulness + recall)                │
│ Full trace per request (retrieval → prompt → claims → NLI verdicts)        │
└────────────────────────────────────────────────────────────────────────────┘
```

### Request sequence (happy path)

```
Client → API Gateway → Query Service
  → Retriever (BM25 + ANN, parallel)
  → Reranker
  → Answerability Gate
  → Generator (extractive or constrained LLM)
  → Verifier (citations + NLI)
  → Response (only if all claims SUPPORTED)
```

Anything failing the verifier **does not** fall through to the user as an answer.

---

## 6. Ingestion & Indexing

### Parse & normalize

| Format | Approach | Trust note |
|---|---|---|
| PDF | `unstructured` / pdfminer; preserve tables | Low-confidence OCR → down-weight chunks |
| HTML | trafilatura / readability; strip chrome | Boilerplate contaminates retrieval |
| DOCX/PPTX | Preserve heading hierarchy as metadata | Section path becomes citation context |
| Scans | Textract / tesseract | Store OCR confidence on chunk |

Normalize Unicode (NFC), collapse whitespace, detect language (don't mix embedding spaces blindly), dedupe with MinHash LSH at doc level and near-dup at chunk level.

### Chunking

```
Size:        512 tokens (default); tune per domain (policies often 256–384)
Overlap:     64 tokens
Structure:   Prefer heading-aware / recursive splits over naive windows
Metadata:    doc_id, chunk_id, section_path, page, updated_at, source_authority,
             ocr_confidence, embed_model_version, acl_tags
```

**Chunk quality score at ingest:** flag boilerplate, orphan list items, header-only chunks. Retrieval down-weights them.

### Dual index

- **Sparse:** OpenSearch/Elasticsearch BM25 (exact IDs, SKUs, CVE strings, names)
- **Dense:** Qdrant / Weaviate / Vespa / OpenSearch kNN — HNSW, sharded by doc hash or tenant
- **Chunk store:** authoritative text + provenance (object store + metadata DB); indexes hold pointers

### Scale: sharding

```
50M chunks → shard by hash(doc_id) % N
Query: fan-out top shards OR broadcast with early termination
Deletes: tombstone in chunk store + index delete; hard guarantee deleted never cited
Embedding upgrades: version embeddings; blue/green reindex; dual-read during migration
```

### Streaming updates

```
doc create/update → Kafka → parse/chunk/embed → upsert BM25 + vector + chunk store
                 → invalidate caches citing old chunk_ids
doc delete       → fast tombstone path (P99 ≤ 1–5s) before async index cleanup
```

---

## 7. Hybrid Retrieval + Rerank

### Why hybrid

| Signal | Wins on | Loses on |
|---|---|---|
| BM25 | Exact tokens, IDs, rare terms | Paraphrase / intent |
| Dense | Semantic similarity | Exact codes, novel tokens |
| Cross-encoder | Precision on small set | Cannot scan 50M chunks |

### Fusion: Reciprocal Rank Fusion

```
RRF(chunk) = Σ_i 1 / (k + rank_i(chunk))   # k ≈ 60
```

Rank-based merge avoids incomparable score scales. Take **top-100** into the reranker.

### Query-side enrichment (selective)

- **Expansion:** 2–3 paraphrases for short queries; merge via RRF
- **HyDE:** optional for complex questions (embed a hypothetical answer)
- **Intent route:** domain-specific embedders if corpus spans legal + code + HR
- **Filters first:** time range, product, locale, ACL — applied *before* or as post-filter on ANN

### ANN (stage 1)

HNSW defaults to defend: `M=32–48`, `ef_construction=200`, `ef_search=100+`. Target **≥98% recall@100** vs exact on a holdout. Quantize (int8/PQ) for memory; measure recall after quantization.

### Cross-encoder (stage 2)

Score `(query, chunk)` jointly on top-100 → keep **top 8–12**. Models: BGE-reranker, Cohere Rerank, ms-marco MiniLM (latency-sensitive).

**Why non-negotiable at 10M docs:** bi-encoder alone feeds noisy context → generation invents bridges between weakly related spans. That is a primary hallucination source.

---

## 8. Answerability Gate

Before any generation, score whether the retrieved set can support an answer.

### Inputs

1. **Top rerank score** (cross-encoder)
2. **Margin** between rank-1 and rank-3 (ambiguity / conflict signal)
3. **Coverage heuristic:** keyword/entity overlap between query and top chunks
4. **Authority × freshness** composite on top chunks
5. Optional small **answerability classifier** trained on (query, context) → {ANSWERABLE, PARTIAL, UNANSWERABLE}

### Gate

```
IF max_confidence < REFUSE (e.g. 0.40):
    → ABSTAIN: "Sources do not contain enough reliable information."

IF REFUSE ≤ score < WARN (e.g. 0.60) OR PARTIAL:
    → CLARIFY / WARN path, or extractive-only with explicit uncertainty

IF score ≥ WARN AND ANSWERABLE:
    → Proceed (prefer extractive for high-stakes)
```

This implements **P3**: bad retrieval never becomes a confident essay.

---

## 9. Dual Generation Path (Extractive-First)

This is the architectural answer to "zero hallucination."

### Path A — Extractive (default for high-stakes)

1. Select supporting spans from top chunks (QA extractive model or constrained highlighter)
2. Compose answer as **quoted / lightly templated** text with mandatory citations
3. Disallow free synthesis beyond glue words ("According to [1]…")

**Property:** every sentence is a near-copy or tight paraphrase of a span → NLI almost always entails. Closest to operational zero unsupported claims.

### Path B — Constrained generative (when synthesis is required)

Use only after answerability clears and domain allows synthesis (compare/contrast, multi-doc summary).

```
SYSTEM rules (non-negotiable):
1. Use ONLY <context> blocks.
2. If insufficient, say the fixed abstain string — nothing else.
3. Every factual claim must cite [source_id].
4. No parametric knowledge, no inference beyond explicit text.
5. If sources conflict, surface the conflict; do not pick silently.

Temperature = 0 (or very low)
Prefer structured output: { "claims": [{"text": "...", "cite": [1,2]}], "answer": "..." }
Optional: grammar-constrained decoding so uncited claims are syntactically invalid
```

### Why prompt-only grounding fails

Models still leak parametric knowledge when context is thin or RLHF "helpfulness" pressure wins. **Verification (next section) is the control plane; the prompt is a soft preference.**

---

## 10. Claim Decomposition & Verification

Hard gate between draft answer and user.

### Layer 1 — Deterministic (< 1ms)

- All `[source_id]` exist in the retrieved set
- No empty answer with fake citations
- Schema validates if structured output used
- Blocked leakage phrases / prompt-injection patterns from chunk text

### Layer 2 — Claim split + NLI (50–200ms)

1. Split answer into atomic claims (sentence splitter + lightweight claim extractor)
2. For each claim, run NLI: premise = **cited** chunk text(s), hypothesis = claim
3. Require **ENTAILMENT** (not merely NEUTRAL-but-plausible)

```
Premise:    cited span(s)
Hypothesis: atomic claim
Label:      ENTAILMENT | NEUTRAL | CONTRADICTION
```

Model example: `DeBERTa-v3` NLI / similar. Tune threshold on a labeled faithfulness set.

### Layer 3 — LLM-as-judge (borderline only)

Escalate only when NLI confidence is near decision boundary. Judge labels: SUPPORTED / UNSUPPORTED / CONTRADICTED. Prefer a **different** model family than the generator to reduce correlated failure.

### Release policy

| Verdict | Action |
|---|---|
| All claims ENTAILED/SUPPORTED | Release |
| Any CONTRADICTED | Do not release; one regenerate with stricter prompt **or** abstain |
| UNSUPPORTED / NEUTRAL | Strip claim and re-verify, or abstain if core claim fails |
| Citation invalid | Do not release |

**One regenerate max.** Loops burn latency and can amplify hallucination. Prefer abstention.

---

## 11. Citation Contract & Response Schema

Citations are the audit surface for the zero-unsupported-claims SLA.

```json
{
  "answer": "Refunds are accepted within 30 days of purchase [1]. Sale items are final [2].",
  "abstained": false,
  "confidence": 0.88,
  "claims": [
    {
      "text": "Refunds are accepted within 30 days of purchase",
      "cite": [1],
      "nli": "ENTAILMENT",
      "nli_score": 0.96
    },
    {
      "text": "Sale items are final",
      "cite": [2],
      "nli": "ENTAILMENT",
      "nli_score": 0.91
    }
  ],
  "sources": [
    {
      "id": 1,
      "doc_id": "policy-returns-v4",
      "chunk_id": "policy-returns-v4#3",
      "span": "Customers may return any item within 30 days of purchase...",
      "url": "https://...",
      "updated_at": "2024-11-01",
      "authority": 0.95
    }
  ],
  "trace_id": "..."
}
```

Without claim↔span linkage you cannot automate faithfulness measurement or debug failures.

---

## 12. Caching, Freshness & Invalidation

### Two-level cache

1. **Exact:** `hash(normalized_query + filters + embed_model_version)` → Redis TTL
2. **Semantic:** nearest cached query embedding within cosine distance threshold (e.g. 0.05); stricter in high-stakes domains

### Invalidation (correctness-critical)

```
doc update/delete
  → resolve chunk_ids
  → inverted index: chunk_id → cache_keys
  → evict those keys
  → TTL as safety net only
```

Serving a cached answer after a policy change is an operational hallucination.

### Session memory

Last 3–5 turns for conversational rewrite of the retrieval query; do not stuff full chat into generation context without re-grounding.

---

## 13. Evals, SLOs & Observability

### SLIs / SLOs

| SLI | SLO (example) |
|---|---|
| Unsupported claim rate on *released* answers | **0** hard gate; offline audit ≤ 0.1% |
| Citation validity | 100% |
| Faithfulness (RAGAS / claim NLI) on golden set | ≥ 99% |
| Retrieval Recall@5 on golden set | ≥ domain baseline; alert on −2% absolute |
| Abstention rate | Band-alert (spike ⇒ retrieval/index health) |
| End-to-end P99 latency | ≤ 2s interactive |
| Delete visibility | Deleted doc never cited within delete SLA |

### Golden set

~1k production-representative queries with human-verified supporting chunks; nightly regression. Expand toward the long tail. Retire examples when source docs change.

### Component metrics

- ANN recall@100 vs exact holdout
- Reranker NDCG@5
- NLI precision/recall on faithfulness labels
- Cache hit rate + invalidation lag

### Tracing

Every response stores: normalized query, retrieved IDs+scores, prompt, raw draft, claims, NLI verdicts, final decision (release/abstain). Tools: LangSmith / Phoenix / ClickHouse traces.

### Feedback

Thumbs-down → investigation queue. Verifier-pass + user-wrong ⇒ hard negatives for NLI/gate calibration.

---

## 14. Failure Modes

| Failure | Signal | Mitigation |
|---|---|---|
| Retrieval miss (answer in corpus, not retrieved) | High abstention on known-answerable queries | Expansion, embed fine-tune, ANN recall audit |
| Stale chunks after update | User reports outdated policy | Streaming upsert + cache eviction by chunk_id |
| NLI false reject | Abstention spike, user "but it's right" | Threshold calibrate; LLM-judge band; human review |
| NLI false accept | Offline audit finds unsupported release | Stricter entailment; extractive path; dual verifier |
| Prompt injection via docs | Odd instruction-following | Sanitize chunks; delimiters; never concatenate untrusted text into system role |
| Embedding version drift | Sudden recall drop | Pin model version; dual-index migration |
| Reranker outage | Latency/errors | Degrade to bi-encoder top-K + **force extractive or abstain** |
| Cache poisoning / stale hit | Correct yesterday, wrong today | Citation-linked invalidation + TTL |
| Contradictory sources | Conflicting top chunks | Surface conflict; never silent arbitrate in high-stakes |

---

## 15. Key Trade-offs

### "Stuff 10M docs into a 1M context window?"

No. Cost explodes, "lost in the middle" kills recall, latency and privacy suffer, freshness requires constant rebuild. Retrieval is the measurable, operable problem.

### "Cross-encoder over all 50M chunks?"

Infeasible at interactive latency. Bi-encoder ANN reduces to ~100; cross-encoder precision-ranks that set.

### "Can prompting alone deliver zero hallucination?"

No. Prompting reduces leak rate; **verification + abstention** enforce the contract.

### "Generative vs extractive?"

| | Extractive | Generative |
|---|---|---|
| Faithfulness | Highest | Needs heavy verification |
| Fluency / synthesis | Limited | Strong |
| Multi-hop summary | Weak alone | Better |
| Zero-unsupported-claims | Best default | Allowed only behind gates |

**Staff answer:** extractive-first; generative as a privileged path.

### Contradictory documents

Return both with explicit conflict, prefer authority/recency with disclosure, or refuse definitive answer. Never silently pick in legal/medical.

---

## 16. Interview Q&A

### RAG vs fine-tuning?

RAG for changing knowledge + citations + provenance. Fine-tune for style/format/reasoning habits. Enterprise default: RAG for facts, light fine-tune/SFT for behavior.

### How do you stop parametric knowledge leak?

Defense in depth: constrained prompt → low temperature → structured cited claims → deterministic citation check → NLI entailment → abstain on failure. No single layer is enough.

### Biggest risk at 10M scale?

**Long-tail retrieval failure.** Head queries look great; rare phrasings and multi-hop questions collapse recall → either abstention spikes or (if ungated) hallucinations. Continuously grow golden set into the tail; treat abstention rate as a health signal.

### Real-time ingest instead of batch?

Kafka → embed workers → incremental BM25 + vector upsert; atomic upsert not delete-then-insert; cache invalidation is the hard part at high churn.

### Difference between this and "semantic search"?

Semantic search returns ranked docs. This system **must not release an answer** unless claims are entailed by cited spans. Retrieval is necessary but not sufficient.

---

## 17. One-Sentence Takeaway

> **At 10M docs, "zero hallucination" is a release-gate contract — extractive-first retrieval, mandatory claim-level entailment, and abstention — not a smarter prompt.**

Senior answers over-invest in the LLM. Principal answers invest in ingestion quality, hybrid retrieval + rerank, answerability gating, and a verifier that can veto the model.

---

## 18. Cheat Sheet

```
CONTRACT:   Zero unsupported claims released; abstention is success
INGEST:     Parse → hierarchical 512/64 chunks → quality score → BM25 + HNSW (sharded)
RETRIEVE:   BM25 ‖ Dense → RRF → top-100 → cross-encoder → top-8–12
GATE:       Confidence + answerability → abstain/clarify before generate
GENERATE:   Extractive-first; constrained LLM only when synthesis required
VERIFY:     Citation IDs valid → atomic claims → NLI entailment → else abstain
CACHE:      Exact + semantic; invalidate via chunk_id → cache_key index
EVAL:       Nightly faithfulness/recall; alert on gate & faithfulness regressions
OBS:        Full claim-level traces; SLOs on unsupported rate, P99, delete lag
```

---

*Interview-prep HLD for Staff / Principal discussions. Pairs with `docs/SEMANTIC_SEARCH_ENGINE_HLD_STAFF_ENG.md` (retrieval substrate) and extends it with generation + verification for a grounded Q&A product.*
