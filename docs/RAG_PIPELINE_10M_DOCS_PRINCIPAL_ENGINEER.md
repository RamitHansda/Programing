# RAG Pipeline for 10M Docs — Principal Engineer Interview Prep

**The question:** "Design a RAG pipeline for 10 million documents with zero hallucination."

**Why this question matters at L5/L6:** It is not a vanilla system design question. It is a product + infrastructure + ML engineering question disguised as a scale question. The interviewer is watching whether you treat "zero hallucination" as a marketing phrase or as a verifiable system contract.

---

## Table of Contents

1. [Principal Engineer Framing — Before You Draw Anything](#1-principal-engineer-framing)
2. [Design Principles (Your Governing Constraints)](#2-design-principles)
3. [System Architecture — Full Picture](#3-system-architecture)
4. [Step 1 — Ingest and Normalize](#4-step-1-ingest-and-normalize)
5. [Step 2 — Hybrid Retrieval: BM25 + Embeddings](#5-step-2-hybrid-retrieval)
6. [Step 3 — ANN + Two-Stage Reranking](#6-step-3-ann--two-stage-reranking)
7. [Step 4 — Source Confidence Scoring](#7-step-4-source-confidence-scoring)
8. [Step 5 — Constrained Generation](#8-step-5-constrained-generation)
9. [Step 6 — Citation-Backed Responses](#9-step-6-citation-backed-responses)
10. [Step 7 — Hallucination Fallback Layer](#10-step-7-hallucination-fallback-layer)
11. [Step 8 — Continuous Evals](#11-step-8-continuous-evals)
12. [Step 9 — Caching and Memory Layer](#12-step-9-caching-and-memory-layer)
13. [Step 10 — Observability Everywhere](#13-step-10-observability-everywhere)
14. [Key Trade-off Decisions](#14-key-trade-off-decisions)
15. [Failure Mode Analysis](#15-failure-mode-analysis)
16. [Scale Math — Back of Envelope](#16-scale-math)
17. [Interview Q&A — Likely Follow-ups](#17-interview-qa)
18. [The One-Sentence Takeaway](#18-the-one-sentence-takeaway)

---

## 1. Principal Engineer Framing

A principal engineer does not start drawing boxes. The first move is to interrogate the problem statement. At Google L5, this signals senior ownership — you shape the problem, not just react to it.

### What to say in the first 2 minutes

> "Before I design, I want to challenge two words in the problem statement. 'Zero hallucination' is not a binary property — it is a spectrum with a measurement definition. And '10 million docs' is a red herring unless we know document size, update frequency, and domain. Let me define what 'zero hallucination' means as a contractual SLA, then design a system that enforces it."

### The five questions you ask before drawing

**1. What is the domain?** (Legal, medical, internal enterprise knowledge base?)
- Domain determines how bad a hallucination is. A medical answer with a fabricated citation can kill someone. An internal HR FAQ with a slightly wrong answer is annoying.
- This directly sets your confidence threshold, fallback behavior, and whether you can afford P99 latency vs. you must refuse.

**2. What does "zero hallucination" mean operationally?**
- Grounding rate: what % of claims in the response are traceable to a retrieved passage?
- Fabrication rate: what % of responses contain at least one claim not in the source corpus?
- Is a "I don't know" response acceptable, or does the system have to answer every query?
- **Principal framing:** "Zero hallucination" is likely P99.9 grounding, not P100. Design for the target, instrument to measure it, build a fallback when the system fails its own SLA.

**3. What is the latency budget?**
- Interactive user-facing: < 2s end-to-end
- Async/search: < 5s
- This determines whether you can afford a two-model reranking pipeline or must collapse stages.

**4. What is the document update SLA?**
- Documents indexed within minutes vs. hours changes the ingestion architecture dramatically.
- 10M static documents (S3 batch ingest) vs. 10M live-updating documents (Kafka-driven streaming pipeline) are different systems.

**5. Is there a compliance or data residency constraint?**
- If yes, you cannot call OpenAI. You're running an open-source LLM (Llama, Mistral) inside your VPC.
- This eliminates half the "just use the API" architectures immediately.

---

## 2. Design Principles

Name these before drawing. Every subsequent decision traces back to one of these.

| # | Principle | Implication |
|---|-----------|-------------|
| **P1** | **Groundedness over fluency** | The system must prefer "I don't know" over a confident fabrication. | 
| **P2** | **Every claim is attributable** | No response reaches the user without traceable provenance to a source document. |
| **P3** | **Retrieval quality gates generation** | The LLM sees only what the retrieval layer clears. Bad retrieval = refused generation, not attempted generation. |
| **P4** | **Degrade gracefully, never silently** | If the system cannot meet its hallucination SLA for a query, it says so explicitly rather than returning a low-confidence answer as if it were certain. |
| **P5** | **Everything is measured** | Hallucination rate, retrieval recall, latency, and fallback rate are first-class metrics with alerts. |

---

## 3. System Architecture — Full Picture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           INGESTION PIPELINE                                │
│                                                                             │
│  Raw Docs ──► Normalizer ──► Chunker ──► Dual Encoder ──► Vector Store     │
│  (S3/GCS)      (clean,         (512-tok    (embed +          (Qdrant/       │
│                dedup, parse)    overlap)    BM25 index)        Weaviate)     │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │ (doc_id, chunk_id, metadata)
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           RETRIEVAL LAYER                                   │
│                                                                             │
│  Query ──► Query Normalizer ──► Hybrid Retriever ──► ANN Top-K             │
│              (expand, classify)   (BM25 + Dense)     (HNSW/ScaNN)           │
│                                        │                                    │
│                                        ▼                                    │
│                              Cross-Encoder Reranker                         │
│                              (Cohere / BGE-reranker)                        │
│                                        │                                    │
│                                        ▼                                    │
│                          Source Confidence Scorer                           │
│                          (freshness + domain authority                      │
│                           + retrieval score composite)                      │
│                                        │                                    │
│                          ┌─────────────┴──────────────┐                    │
│                    Score ≥ threshold              Score < threshold         │
│                          │                             │                    │
│                          ▼                             ▼                    │
│                  Proceed to Generation            Fallback (refuse/         │
│                                                   clarify/escalate)         │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         GENERATION LAYER                                    │
│                                                                             │
│  Context (top-K chunks) ──► Constrained Prompt ──► LLM                     │
│  + System prompt with          (answer ONLY from       │                    │
│    grounding rules             provided context)       │                    │
│                                                        ▼                    │
│                                             ┌─────────────────┐             │
│                                             │ Citation Linker │             │
│                                             │ (every claim →  │             │
│                                             │  chunk_id)       │             │
│                                             └────────┬────────┘             │
│                                                      │                      │
│                                                      ▼                      │
│                                          Hallucination Verifier             │
│                                          (NLI / LLM-as-judge)               │
│                                                      │                      │
│                                       ┌──────────────┴────────────┐        │
│                                Verified ✓                   Failed ✗        │
│                                       │                           │         │
│                                       ▼                           ▼         │
│                               Return to user              Fallback handler  │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    OBSERVABILITY + EVAL + CACHE                             │
│                                                                             │
│  Cache Layer (Redis/Memcached) ← Semantic cache (embed query → cache hit)  │
│  Eval Pipeline (RAGAS / TruLens) ← continuous scoring                      │
│  Trace Store (LangSmith / Arize Phoenix) ← full lineage per response       │
│  Metrics (retrieval recall, hallucination rate, latency P50/P99, fallback %)│
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Step 1 — Ingest and Normalize

### What it is

The foundation. Garbage in, garbage out. At 10M documents, normalization is not optional.

### The components

**Parser layer:**
- PDF: use `pdfminer` or `unstructured.io` — not PyPDF2 (it silently drops tables)
- HTML: strip boilerplate (nav, headers, footers) using `trafilatura` or `readability`
- DOCX/PPTX: extract with `python-docx`, preserve heading hierarchy as metadata
- Scanned docs: OCR with `tesseract` or `AWS Textract` — flag low-confidence OCR as lower-trust chunks

**Normalization:**
- Unicode normalization (NFC), whitespace collapse, control character removal
- Language detection (handle multilingual corpora separately — don't mix embedding spaces)
- Deduplication: MinHash LSH at document level, near-duplicate detection at chunk level

**Chunking strategy:**
```
Chunk size:     512 tokens (sweet spot — not too short to lose context, not too long to dilute relevance)
Overlap:        64 tokens (sliding window — preserves cross-sentence context at boundaries)
Hierarchy:      Preserve parent document metadata on every chunk (doc_id, section, page, timestamp)
```

**Why chunk size matters:** If your chunks are too large, the dense retriever averages semantics across unrelated sentences — retrieval scores drop. If too small, you lose inter-sentence context and citations become cryptic. 512 tokens with overlap is the empirical sweet spot for most enterprise knowledge bases.

**Principal-level detail:** Add a "chunk quality score" at ingestion time:
- Flag chunks that are mostly boilerplate, headers, lists without context, or OCR noise
- Store this flag; the retrieval layer can down-weight flagged chunks

### At 10M docs — the scale challenge

- 10M docs × 5 chunks/doc average = **50M chunks** in the vector store
- At 1536-dimensional embeddings (OpenAI ada-002 / Cohere) × float32 = **~300GB of raw vectors**
- Use quantization (int8 or product quantization) to bring this to ~75GB — fits in a single high-memory instance
- Ingestion parallelism: Kafka topic → N embedding workers → bulk upsert to vector store

---

## 5. Step 2 — Hybrid Retrieval

### Why neither BM25 nor dense retrieval alone is sufficient

| Property | BM25 (Sparse) | Dense (Embedding) |
|----------|---------------|-------------------|
| Exact keyword match | Excellent | Poor ("UUID", product codes, names) |
| Semantic similarity | Poor | Excellent |
| Domain shift robustness | High | Low (model must be domain-adapted) |
| Explainability | High (term weights visible) | Low |
| Latency | Very low | Low-medium (HNSW lookup) |

**The combination** catches what each misses. A query for "CVE-2024-1234 mitigation" needs BM25 for the exact identifier and dense retrieval for the semantic "how to fix" intent.

### Reciprocal Rank Fusion (RRF) — the correct way to merge

Do not simply average scores (different scales, different distributions). Use RRF:

```
RRF_score(chunk) = Σ  1 / (k + rank_i(chunk))
                  i ∈ {bm25, dense}
```

Where `k = 60` is a smoothing constant. This is rank-based, not score-based — immune to score scale differences.

### Implementation

```
BM25:  Elasticsearch or OpenSearch with the BM25 scorer (default)
Dense: Qdrant or Weaviate with HNSW index
Fusion: Application layer merges ranked lists using RRF
Top-K: Return top 100 candidates to the reranker (not 10 — the reranker will prune)
```

### Query-side preprocessing

Before retrieving, process the query:
- **Query expansion:** Use an LLM to generate 2–3 related phrasings. Run all in parallel, merge results. Especially important for short queries.
- **Intent classification:** Route legal queries to a legal-adapted embedding model, code queries to a code-adapted model. At 10M docs spanning multiple domains, a single embedding model will underperform.
- **HyDE (Hypothetical Document Embeddings):** For complex questions, ask the LLM to generate a hypothetical answer, embed it, and use that embedding for retrieval. The hypothesis lives in the document embedding space better than the raw question.

---

## 6. Step 3 — ANN + Two-Stage Reranking

### Stage 1: ANN (Approximate Nearest Neighbor)

At 50M chunks, exact k-NN search is infeasible at interactive latency. Use **HNSW** (Hierarchical Navigable Small World graphs):

- **HNSW parameters:**
  - `ef_construction = 200` (build-time quality, controls recall)
  - `M = 48` (connectivity — higher = better recall, more memory)
  - `ef_search = 100` (query-time — trade latency for recall)
- **Recall target:** ANN should return top-100 with 98%+ recall@100 vs. exact search
- **Alternative:** Google's ScaNN for extremely high QPS requirements (GPU-accelerated)

### Stage 2: Cross-Encoder Reranker

The HNSW retriever uses a bi-encoder (query and doc embedded independently). Fast, but imprecise — the model never sees query and document together.

The cross-encoder sees both:

```
Input:  [CLS] query [SEP] chunk_text [SEP]
Output: relevance score ∈ [0, 1]
```

This is 10–30× slower per comparison, but you only run it on the top-100 from ANN, not all 50M chunks.

**Model choices:**
- `cross-encoder/ms-marco-MiniLM-L-6-v2` — fast, good for English
- `Cohere Rerank` — API-based, strong multilingual
- `BGE-reranker-large` — strong open-source option, self-hosted

**Why two stages are non-negotiable at 10M docs:**

If you skip reranking and trust only the bi-encoder, your top-5 precision drops significantly. The reranker is the component that translates "high retrieval recall" into "high generation precision." Without it, you feed noise to the LLM, which is where hallucinations begin.

---

## 7. Step 4 — Source Confidence Scoring

### What it is

After reranking, each candidate chunk gets a **composite confidence score** that determines whether it proceeds to generation or triggers the fallback.

### The three inputs to confidence

**1. Retrieval score:** The cross-encoder relevance score (0–1). Minimum threshold: `0.7` for high-stakes domains, `0.5` for general knowledge.

**2. Source authority score:** 
- Is this document from an authoritative source? (Official product docs = high; user forum post = low)
- Was it published by a verified author?
- Has it been cited/approved by humans before? (Internal feedback loop from past queries)

**3. Freshness score:**
```
freshness = 1 / (1 + decay_rate × days_since_last_updated)
```
For time-sensitive domains (legal, compliance, medical), a document from three years ago answering a question about current regulations is dangerous even if it's highly relevant.

### The composite score

```
confidence = α × retrieval_score + β × authority_score + γ × freshness_score

Typical weights for legal domain:   α=0.5, β=0.3, γ=0.2
Typical weights for general KB:     α=0.7, β=0.2, γ=0.1
```

### The gating decision

```
IF confidence < REFUSE_THRESHOLD (e.g. 0.4):
    Return: "I don't have reliable information to answer this."

IF REFUSE_THRESHOLD ≤ confidence < WARN_THRESHOLD (e.g. 0.6):
    Generate answer but add: "Note: confidence in sources is moderate. Verify independently."

IF confidence ≥ WARN_THRESHOLD:
    Proceed to constrained generation normally.
```

This is **Principle P4** in action: degrade gracefully, never silently.

---

## 8. Step 5 — Constrained Generation

### The core mistake to call out in the interview

Most engineers design the LLM prompt as: *"Here is some context. Answer the question."*

This is wrong. The LLM has internalized parametric knowledge from pretraining. Without explicit constraints, it will blend retrieved context with its own parametric knowledge and you cannot detect where one ends and the other begins.

### The correct prompt structure

```
SYSTEM:
You are a grounded assistant. You MUST follow these rules without exception:
1. Answer ONLY using information from the <context> blocks below.
2. If the context does not contain enough information to answer the question, 
   say exactly: "The provided sources do not contain enough information to 
   answer this question."
3. Every factual claim in your response must reference a [source_id] from 
   the context.
4. Do not infer, extrapolate, or use your training knowledge. Only report 
   what is explicitly stated in the context.
5. If context chunks contradict each other, flag the contradiction rather 
   than choosing one silently.

<context>
[source_1] chunk_text_1
[source_2] chunk_text_2
...
[source_k] chunk_text_k
</context>

USER: {query}
```

### Why "answer only from context" is not enough

Even with this prompt, models "leak" parametric knowledge under two conditions:
1. The context is ambiguous and the model fills gaps with training data
2. The model is instructed in ways that conflict with its RLHF tuning (helpfulness pressure)

The defense against this is the **hallucination verifier** in Step 7, not more aggressive prompting.

### Logit-based constraints (advanced)

For smaller, self-hosted models, you can constrain generation at the token level:
- Use **grammar-constrained decoding** (via `outlines` or `lm-format-enforcer`) to force structured output with mandatory citation fields
- This prevents the model from generating a claim without a following `[source_id]` token, making unattributed claims syntactically impossible

---

## 9. Step 6 — Citation-Backed Responses

### The user-facing contract

Every response returned to the user must include inline citations that map to retrievable source documents:

```json
{
  "answer": "The refund policy allows returns within 30 days [1]. 
             Items purchased during sales are non-refundable [2].",
  "sources": [
    {
      "id": 1,
      "doc_id": "policy-returns-v4",
      "chunk_id": "policy-returns-v4-chunk-3",
      "text": "Customers may return any item within 30 days of purchase...",
      "url": "https://...",
      "confidence": 0.91,
      "last_updated": "2024-11-01"
    },
    {
      "id": 2,
      "doc_id": "policy-returns-v4",
      "chunk_id": "policy-returns-v4-chunk-7",
      "text": "Items purchased during promotional events are final sale...",
      "url": "https://...",
      "confidence": 0.84,
      "last_updated": "2024-11-01"
    }
  ]
}
```

### Why this matters beyond UX

Citations are not a UX nicety. They are the **audit trail** that makes "zero hallucination" verifiable. Without them:
- You cannot run automated hallucination verification
- You cannot debug which retrieval failure caused which bad response
- You cannot build the feedback loop in Step 8

### Citation grounding validator

After the LLM generates a response with inline `[source_id]` references, run a deterministic check:

```python
def validate_citations(response: str, sources: list[Chunk]) -> bool:
    referenced_ids = extract_citation_ids(response)  # regex [N]
    for cid in referenced_ids:
        if cid not in [s.id for s in sources]:
            return False  # hallucinated citation ID
    return True
```

This is cheap and catches "hallucinated citations" — where the model invents a `[source_3]` reference that does not exist in the retrieved set.

---

## 10. Step 7 — Hallucination Fallback Layer

### Three-layer verification

This is the hardest and most important component. Think of it as a contract verifier that sits between the LLM output and the user.

**Layer 1 — Cheap syntactic checks (< 1ms)**
- Are all cited `[source_id]` references valid?
- Does the response contain the phrase "as an AI" or other model leakage phrases?
- Is the response length within bounds? (A 3-sentence answer for a doc-grounded query that is 20 paragraphs is suspicious)

**Layer 2 — NLI-based semantic verification (50–200ms)**

Use a Natural Language Inference model to verify each claim:
```
Premise:    Retrieved chunk text
Hypothesis: Extracted claim from the response

Label:      ENTAILMENT | NEUTRAL | CONTRADICTION
```

For each sentence/claim in the response, the NLI model checks whether it follows from the cited source. Claims labeled NEUTRAL or CONTRADICTION are flagged.

**Model:** `cross-encoder/nli-deberta-v3-base` is fast and strong for this.

**Layer 3 — LLM-as-judge (500ms–2s, used for borderline cases only)**

When NLI is uncertain (scores near 0.5), escalate to a second LLM call with a strict judge prompt:

```
You are a factuality verifier. Given the source text and the claim, 
determine if the claim is:
- SUPPORTED: the source explicitly states this
- UNSUPPORTED: the source does not state this (even if plausible)
- CONTRADICTED: the source explicitly contradicts this

Source: {chunk_text}
Claim: {extracted_claim}

Output JSON: {"verdict": "SUPPORTED|UNSUPPORTED|CONTRADICTED", "explanation": "..."}
```

### The fallback decision tree

```
All claims SUPPORTED →  Return response to user

Any claim CONTRADICTED → Do NOT return. Re-retrieve and re-generate OR return:
                          "Found conflicting information. Please consult [source] directly."

Claim UNSUPPORTED but confidence high → Soft warning in response:
                          "Note: part of this response could not be directly verified 
                           in the source documents."

Multiple UNSUPPORTED claims → Refuse: 
                          "The sources do not contain sufficient information to 
                           answer this confidently."
```

---

## 11. Step 8 — Continuous Evals

### The core insight

A RAG system without continuous evaluation is a system that drifts invisibly. Document corpora change, embedding model quality degrades relative to new query distributions, and hallucination rate climbs without anyone noticing.

### What to measure

**Retrieval metrics:**
- `Recall@K` — of the K chunks returned, what fraction were relevant? (Requires a labeled eval set)
- `MRR (Mean Reciprocal Rank)` — where does the first relevant chunk appear?
- `NDCG@K` — graded relevance ranking quality

**Generation metrics (RAGAS framework):**
- **Context Precision:** What fraction of retrieved chunks were actually used in the answer?
- **Context Recall:** What fraction of the answer is supported by the retrieved chunks?
- **Faithfulness:** What fraction of claims in the answer are entailed by the context?
- **Answer Relevancy:** How relevant is the answer to the question?

**System metrics:**
- Hallucination rate (% of responses failing the NLI verifier)
- Fallback rate (% of queries that hit the refuse/warn path)
- Latency P50, P99 per pipeline stage
- Cache hit rate

### Building the eval set

At 10M docs, you cannot manually label everything. Build a **self-supervised eval pipeline:**

1. Take 1000 representative queries from production logs
2. For each, retrieve and manually verify the top chunk (human-in-the-loop, can be crowdsourced)
3. Use this as your "golden set"
4. Run it nightly against the live pipeline; alert on regressions > 2% in any metric

### Golden set evolution

As the corpus changes, so must the golden set. Build a process to:
- Retire golden examples whose source documents are deleted/modified
- Add new examples from user feedback (thumbs down = candidate for eval set)

---

## 12. Step 9 — Caching and Memory Layer

### Why caching is mandatory at 10M docs

At scale, the same question gets asked thousands of times. Embedding + ANN + rerank + LLM generation is expensive. Without caching:
- A legal knowledge base with 10 standard questions = 10× full pipeline for every user
- LLM call costs dominate; at $0.01/1K tokens and 10M queries/month, full pipeline is ~$100K/month

### Two-level cache

**Level 1 — Exact cache (Redis, TTL = 24h)**
```
key = hash(normalized_query)
value = {answer, sources, trace_id, timestamp}
```
Miss rate is high for long-tail queries, but catches repeat questions exactly.

**Level 2 — Semantic cache (vector similarity)**
```
1. Embed the incoming query
2. Check if any cached query embedding is within cosine distance < 0.05
3. If yes → return cached answer (with a note that it may be from a similar prior question)
4. If no → proceed with full pipeline, store result in both caches
```

This is the more powerful cache. It catches "What is the refund policy?" and "How long do I have to return an item?" as the same query.

**Cache invalidation:**
When a source document is updated, any cached responses that cited chunks from that document must be invalidated. Implement this via:
```
doc_update → lookup all chunk_ids in that doc → find all cached responses citing those chunk_ids → evict them
```
This requires an inverted index: `chunk_id → [cache_key_1, cache_key_2, ...]`.

### Session memory

For multi-turn conversations:
- Store conversation context in a session store (Redis with sliding window TTL)
- Prepend recent turns to the retrieval query for context-aware retrieval
- Limit to last 3–5 turns to avoid context window explosion

---

## 13. Step 10 — Observability Everywhere

### Why observability is not an afterthought

"Zero hallucination" is not a launch-day claim. It is a continuous operational guarantee. You cannot maintain a guarantee you cannot measure.

### The three pillars for RAG observability

**1. Distributed tracing — full lineage per response**

Every response must have a `trace_id` that allows you to replay the exact pipeline execution:
- Which query was received (after normalization)
- Which chunks were retrieved (with their scores)
- Which chunks were passed to the LLM (after confidence filtering)
- The exact prompt sent to the LLM
- The raw LLM output
- The NLI verification result for each claim
- Final response and whether a fallback was triggered

**Tool:** LangSmith, Arize Phoenix, or a custom trace store (Clickhouse + a trace schema).

**2. Metrics and alerting**

```
Service-level indicators (SLIs) to track:
├── hallucination_rate_7d           (alert if > 0.1%)
├── retrieval_recall_at_5_24h       (alert if drops > 2% vs. baseline)
├── fallback_rate_1h                (alert if > 5% — indicates retrieval degradation)
├── p99_latency_ms                  (alert if > 2000ms end-to-end)
├── cache_hit_rate_1h               (alert if drops suddenly — index issue or attack)
└── nli_verifier_contradiction_rate (alert if spikes — model or corpus drift)
```

**3. User feedback loop**

Every response has a thumbs up/thumbs down. Negative feedback:
- Immediately adds the (query, response, sources) triple to an investigation queue
- If the hallucination verifier passed but user marked wrong → add to hard negatives for verifier retraining
- Weekly review of all thumbs-down responses by an ML engineer

### The operational dashboard

Every on-call engineer should be able to answer in 30 seconds:
- Is the hallucination rate within SLA right now?
- What is the fallback rate? Is it higher than yesterday?
- Which documents are driving the most fallbacks? (Stale? Missing? Poor OCR quality?)
- What is the retrieval recall on the golden set as of last night's eval run?

---

## 14. Key Trade-off Decisions

These are the questions interviewers ask to separate senior from principal candidates.

### "Why not just make the context window huge and stuff all 10M docs in?"

Modern LLMs have 128K–1M token context windows. Why not just use all the docs?

**The answer:**
1. **Cost:** GPT-4 at 1M context × 10M queries/day = financially impossible
2. **Attention degradation:** "Lost in the middle" — LLMs underperform on documents in the middle of long contexts. 10M docs would have even worse recall than a proper retrieval system
3. **Latency:** Time to first token scales with context length
4. **Privacy:** You cannot send all documents to a third-party API
5. **Freshness:** You would need to rebuild the context for every document update

**The right answer to this question in the interview:**
> "Context stuffing trades the retrieval problem for an attention problem. At 10M docs, retrieval is an engineering-solvable, measurable problem. Attention degradation in extremely long contexts is still an active research problem. I'd rather operate in the domain I can control and measure."

### "Why two-stage retrieval? Why not just use the cross-encoder from the start?"

> "A cross-encoder runs a forward pass over the (query, document) pair jointly. At 50M chunks × N ms per pair, cross-encoder over the full corpus is O(50M) per query — infeasible at any interactive latency. The bi-encoder (ANN) reduces the candidate set from 50M to ~100 in milliseconds; the cross-encoder then scores those 100 with high precision. The two-stage design decouples speed from quality."

### "How do you handle documents that contradict each other?"

> "This is a first-class problem. There are three responses:
> 1. Return both and flag the contradiction explicitly: 'Sources disagree on X. Source A says Y, Source B says Z.'
> 2. Prefer the more authoritative/recent source and note the preference.
> 3. Refuse to answer definitively and direct the user to resolve the ambiguity.
> 
> The choice depends on domain. For legal or medical contexts, option 1 or 3. Never silently pick one — that is a hallucination risk disguised as retrieval."

### "What happens when a document is updated?"

> "You need a document update pipeline. On update:
> 1. Re-chunk and re-embed the updated document
> 2. Upsert the new chunk embeddings to the vector store (replace by doc_id)
> 3. Invalidate the BM25 index entries for old chunks (or rebuild the shard)
> 4. Evict all cached responses that cited chunks from this document
> 5. Flag this document for re-evaluation against the golden eval set
> 
> At 10M docs with low update frequency, this is manageable. At high update frequency (e.g. live news), you need streaming ingestion with Kafka and near-real-time index updates."

---

## 15. Failure Mode Analysis

A principal engineer names failure modes before they happen.

| Failure Mode | Signal | Response |
|---|---|---|
| **Retrieval misses** — the answer is in the corpus but not retrieved | High fallback rate on queries that should be answerable | Add query expansion; audit ANN recall on golden set; consider fine-tuning embeddings |
| **Stale corpus** — document is updated but old chunks remain | Freshness score alerts; user reports outdated info | Enforce document TTL; re-ingest on update event |
| **NLI verifier false positive** — verifier rejects correct answers | High fallback rate despite correct responses | Calibrate NLI threshold; add LLM-as-judge for edge cases; human review queue |
| **Prompt injection via documents** — a malicious document contains instructions to override the system prompt | Unusual response patterns; security audit | Sanitize all chunk text before injection; use structured input formats; separate system prompt from context tokens |
| **Embedding model drift** — provider updates embedding model, vector space shifts | Sudden drop in retrieval recall | Pin embedding model versions; re-embed corpus on model change |
| **Cascading reranker failure** — reranker service is down | All requests fall back or error | Rate-limit directly to top-5 bi-encoder results as degraded mode; alert |
| **Cache poisoning** — stale cached response served after doc update | User reports outdated answers that were correct before | Cache invalidation on doc update; TTL as a safety net |

---

## 16. Scale Math

Back-of-envelope to demonstrate principal-level systems thinking.

**Storage:**
```
10M documents × 5 chunks/doc = 50M chunks
50M chunks × 1536 dimensions × 4 bytes (float32) = ~307 GB raw vectors
After int8 quantization: ~77 GB
HNSW graph overhead (~2× vectors): ~150 GB total for vector index
BM25 inverted index (Elasticsearch): ~50 GB
Total storage: ~200–300 GB (fits on a single NVMe SSD instance, e.g. r6g.4xlarge)
```

**Ingestion throughput:**
```
10M docs ÷ 48h (initial ingest window) = ~58 docs/sec
Each doc: parse → chunk (5 chunks) → embed (5 × 512 tokens)
OpenAI ada-002: 1M tokens/$0.0001, latency ~50ms/batch
Batching 100 chunks/request → 500K tokens/batch → 0.05s
At 58 docs/sec × 5 chunks = 290 chunks/sec → 3 embedding workers needed
```

**Query throughput (1000 QPS target):**
```
ANN search (HNSW):          ~5ms   (50M vectors, ef_search=100)
Cross-encoder reranking:    ~50ms  (100 candidates × 4ms each on GPU)
LLM generation (GPT-4):     ~800ms (P50)
NLI verification:           ~100ms (5 claims × 20ms each)
Cache lookup (Redis):       ~1ms
Total P50 with cache miss:  ~960ms
Total P50 with cache hit:   ~2ms

To hit 1000 QPS at 60% cache hit rate:
  Cache hits:  600 QPS × 2ms  → trivial
  Cache miss:  400 QPS × 960ms → need ~400 parallel inference pipelines (LLM is bottleneck)
  LLM scaling: Use vLLM with tensor parallelism, or distribute across multiple LLM API calls
```

---

## 17. Interview Q&A

### "What's the difference between RAG and fine-tuning? When do you choose one?"

> **RAG:** The LLM's weights do not change. Knowledge is injected at runtime via context. Ideal when: knowledge changes frequently, you need to cite sources, you need provenance, or the knowledge base is too large to fit in a model.

> **Fine-tuning:** The LLM's weights are updated on domain data. Ideal when: you need the model to adopt a specific style, format, or reasoning pattern, the knowledge is stable, and latency or cost prevents large context injection.

> **The answer in most enterprise settings:** RAG for knowledge grounding, fine-tuning for behavior and format. They are not mutually exclusive.

### "How do you prevent the LLM from using parametric knowledge?"

> Four defenses in depth:
> 1. Constrained system prompt (instruction-level)
> 2. Temperature = 0 (less creative, more likely to stay grounded)
> 3. NLI verification post-generation (catch what the prompt missed)
> 4. Structured output with mandatory citation fields (syntactically impossible to make uncited claims)
> 
> No single defense is bulletproof. You need all four.

### "What is the biggest risk at 10M document scale?"

> "Retrieval quality degradation at the tail. For the most common 10,000 queries, your pipeline is well-tuned. For the long tail — rare topics, edge-case phrasings, multi-hop questions — retrieval recall collapses and you either hallucinate or over-refuse. The principal-level solution is: continuously expand your golden eval set toward the tail, and use your fallback rate as a proxy for tail performance."

### "How would you evaluate this system end to end?"

> "I would define three evaluation layers:
> 1. **Component-level:** ANN recall@100, reranker NDCG@5, NLI verifier precision/recall on a labeled set
> 2. **System-level (RAGAS):** Context faithfulness, context recall, answer relevancy on a golden query set
> 3. **Business-level:** User satisfaction (thumbs up/down), escalation rate, time-to-resolution for support use cases
> 
> Each layer has its own SLO. A regression at component level is a warning; a regression at business level is a P1."

### "What would you change to make this real-time instead of batch-ingested?"

> "Replace the batch ingestion with a Kafka-based streaming pipeline:
> - Documents pushed to a Kafka topic on creation/update
> - Embedding workers consume from Kafka, produce embeddings in real time
> - Qdrant or Weaviate support incremental upserts
> - BM25 (Elasticsearch) supports incremental indexing
> - The hardest part is cache invalidation at high document update frequency
> 
> At very high update rates (> 1000 doc updates/second), you also need to handle index consistency: a user query may arrive between the old chunk being deleted and the new chunk being indexed. Design the update as an atomic upsert, not a delete + insert."

---

## 18. The One-Sentence Takeaway

> **At 10M docs, hallucination is not an LLM problem — it is a retrieval quality problem. A mediocre LLM with excellent retrieval will outperform a frontier model with poor retrieval every single time. The retrieval layer is where you win or lose the "zero hallucination" contract.**

This is the line that separates a senior engineer answer from a principal engineer answer. The senior engineer will spend 80% of the conversation on the LLM prompt and the hallucination verifier. The principal engineer will spend 80% on the ingestion pipeline, hybrid retrieval, and reranking — because those are the components that determine what the LLM ever sees.

---

## Quick Reference Cheat Sheet

```
INGESTION:     Unstructured.io → 512-tok chunks / 64 overlap → MinHash dedup → Kafka → embed workers
RETRIEVAL:     BM25 (Elasticsearch) + Dense (Qdrant HNSW) → RRF merge → top-100
RERANKING:     Cross-encoder (BGE-reranker-large) → top-5 to top-10
CONFIDENCE:    retrieval score + authority + freshness → gate or warn
GENERATION:    Constrained system prompt → structured output with [source_id] citations
VERIFICATION:  Citation validator → NLI (DeBERTa) → LLM-as-judge for borderline
CACHE:         Redis exact cache + semantic cache (cosine < 0.05) → invalidate on doc update
EVAL:          RAGAS nightly → golden set recall, faithfulness, fallback rate
OBSERVABILITY: Trace every request → alert on hallucination rate, fallback rate, latency P99
```

---

*Document created for Google L5 / Staff Engineer interview preparation. Covers the system design answer at a depth consistent with a principal engineering discussion.*
