# AI/ML Engineering Leadership — Full Topic Interview Prep
**For tomorrow's interview(s).** Covers the 16 topics you listed, prepared at Staff Engineer / EM depth, grounded in your real experience at Skydo, Goldman Sachs, Moneyview, and Future Group.

> **How to use this doc**
> - Don't read this cold in the interview. Skim once tonight, sleep, skim once in the morning.
> - Every answer follows the same shape: **Framework first (30 sec) → Concrete story with numbers (90 sec) → Trade-offs you'd challenge yourself on (30 sec).** That structure alone signals Staff-level thinking, independent of content.
> - Your two anchor stories are the **Skydo Agentic Support Copilot** (RAG + tool-calling + HITL system you designed) and the **Skydo GenAI adoption rollout** (org-wide AI tooling standard). Reuse them across almost every technical question — interviewers want depth on 2 systems, not shallow coverage of 10.
> - For the EM/leadership questions, reuse your **Skydo team of 12 / Goldman team of 9** leadership base, plus the specific hiring/culture/conflict mechanics below.

---

## Table of Contents

**Part A — AI/ML Systems & Engineering Depth**
1. [RAG vs. Fine-Tuning Tradeoffs](#1-rag-vs-fine-tuning-tradeoffs)
2. [Evaluation Strategy and Failure Modes](#2-evaluation-strategy-and-failure-modes)
3. [Production Monitoring for LLM-Powered Systems](#3-production-monitoring-for-llm-powered-systems)
4. [LLM Integration](#4-llm-integration)
5. [Prompt Engineering](#5-prompt-engineering)
6. [Data Pipeline Design](#6-data-pipeline-design)
7. [Scalability and Data Modeling Decisions](#7-scalability-and-data-modeling-decisions)
8. [API Contracts](#8-api-contracts)
9. [Production Incidents & Performance Issues](#9-production-incidents--performance-issues)

**Part B — Leadership & Org**
10. [Sourcing Strategies for AI/ML Talent](#10-sourcing-strategies-for-aiml-talent)
11. [How You Calibrate the Interview Bar](#11-how-you-calibrate-the-interview-bar)
12. [Culture-Setting Within the Team](#12-culture-setting-within-the-team)
13. [Conflict Resolution and Performance Management](#13-conflict-resolution-and-performance-management)
14. [Balancing IC Contribution with Management](#14-balancing-ic-contribution-with-management)
15. [Translating Ambiguous Business Problems into AI Features](#15-translating-ambiguous-business-problems-into-ai-features)
16. [Prioritization Frameworks and Stakeholder Management](#16-prioritization-frameworks-and-stakeholder-management)

[Quick-Fire Glossary](#quick-fire-glossary) · [Questions to Ask Them](#questions-to-ask-them) · [Pre-Interview Checklist](#pre-interview-checklist)

---

## Opening Positioning Pitch (use if asked "tell me about yourself")

> "I'm an Engineering Manager with 10+ years across fintech, enterprise identity, and distributed systems, and over the last two years I've moved deep into applied AI. At Skydo, where I lead a team of 12, I designed and shipped an **agentic support copilot** — a hybrid RAG + read-only tool-calling system with human-in-the-loop approval — that took first-response time on support tickets from ~90 minutes to ~10-12 minutes and auto-triages about 65% of incoming volume, with zero autonomous money-movement risk by design. I also led the org-wide rollout of AI coding assistants, which meant I personally had to get fluent in prompt engineering, evals, and agent architecture fast, and then translate that into standards the whole engineering org could trust.
>
> Before that I was a VP at Goldman Sachs running distributed market-risk aggregation at multi-terabyte scale, which is where my instincts on correctness-under-failure and observability come from — instincts that turn out to transfer directly to AI systems, because the core problem is the same: how do you trust a system whose full state space you can't enumerate.
>
> What I bring to this role is that combination: I can go deep technically on RAG architecture, evals, and LLM production concerns, and I've also built and led the team, hiring bar, and culture that ships this kind of system reliably."

---

# PART A — AI/ML Systems & Engineering Depth

## 1. RAG vs. Fine-Tuning Tradeoffs

**Framework — the question underneath the question:** RAG and fine-tuning solve different problems and are usually complementary, not competing. State this immediately; it signals you don't see them as a binary choice.

| Dimension | RAG | Fine-tuning |
|---|---|---|
| What changes | Nothing in the model — knowledge is injected at inference time via context | Model weights are updated on domain data |
| Best for | Knowledge that changes frequently, needs provenance/citations, or is too large to memorize | Behavior, style, output format, domain-specific reasoning patterns, latency-sensitive tasks where you can't afford a long context |
| Freshness | Update a document, it's live in minutes | Requires retraining/re-tuning cycle |
| Auditability | High — every claim traces to a retrieved chunk | Low — you can't point to "why" the model said something |
| Cost profile | Pay per query (retrieval + longer context) | Upfront training cost, cheaper per-query inference (shorter prompts) |
| Failure mode | Retrieval miss → hallucination or refusal | Catastrophic forgetting, overfitting to training distribution |
| Data requirement | Works with hundreds of docs | Needs a meaningfully sized, well-labeled dataset to move the needle |

**My rule of thumb, stated plainly:** *Use RAG for knowledge, fine-tuning for behavior.* If the failure mode you're worried about is "the model doesn't know X," that's a retrieval problem. If the failure mode is "the model knows X but expresses it in the wrong format/tone/reasoning style, or ignores your instructions under pressure," that's a fine-tuning (or at minimum, few-shot prompting) problem.

**Grounded example (Skydo — Agentic Support Copilot):**
When we built the support copilot, the first instinct from a junior engineer on my team was "let's fine-tune a model on our resolved tickets." I pushed back and we went with RAG instead, for three reasons:
1. **Policy documents change constantly** (KYC rules, FX policy, compliance rules) — fine-tuning would mean retraining every time compliance updated a document, which is untenable operationally and creates an audit gap (you can't prove which policy version the model "knew" at inference time).
2. **We needed source attribution for compliance** — every auto-drafted response had to carry evidence snippets and document versions so a human reviewer (and later, an auditor) could verify it. A fine-tuned model gives you no such trail.
3. **We didn't have enough resolved-ticket volume yet** to fine-tune well without overfitting to a narrow slice of intents.

Where we *did* use a lightweight fine-tune: the **intent classifier** — a small, cheap model classifying incoming tickets into ~15 categories. That's a stable taxonomy, high volume of labeled examples, and latency-critical (runs on every ticket before routing). Fine-tuning a small model there was cheaper and faster than a few-shot prompt to a large model on every single ticket.

**The trade-off I'd volunteer if pushed further:** RAG has a ceiling — if your retrieval is bad, no amount of prompt engineering saves you, and RAG systems generally underperform a well-fine-tuned model on tasks requiring implicit reasoning over the knowledge (not just lookup). Fine-tuning has its own ceiling — it's expensive to keep current, and it silently degrades on out-of-distribution inputs (catastrophic forgetting) unless you continuously re-validate against a held-out base-capability set. In practice, most production systems I'd design use **both**: fine-tune for stable behavior/format/classification tasks, RAG for the volatile knowledge layer, with the two composed rather than treated as alternatives.

---

## 2. Evaluation Strategy and Failure Modes

**Framework — three layers, always name all three:**

1. **Component-level evals** (retrieval recall@K, reranker NDCG, classifier F1) — catch regressions before they compound.
2. **System-level evals** (RAGAS-style: faithfulness, answer relevancy, context precision/recall) — catch end-to-end quality issues.
3. **Business-level evals** (auto-resolve rate, human edit rate, SLA breach rate, thumbs up/down) — the only ones that actually matter to the business, but too lagging to be your only signal.

**The core principle I lead with:** *A single "accuracy" number is not an evaluation strategy — it's a vanity metric.* You need a **stratified golden set**, not a random sample, because average performance hides tail failures that matter most (the angry customer, the compliance-sensitive query, the multi-hop question).

**Grounded example (Skydo copilot eval design):**
We maintained a golden set of ~500 tickets, but the key design decision was **stratification**, not size. We sliced it by:
- **Intent** (payment failure, KYC query, FX rate, blocked account, compliance question)
- **Risk tier** (low-risk FAQ vs. sensitive financial answer vs. must-escalate)
- **Data dependency** (no tool call, single tool call, multi-hop tool calls, stale tool result)
- **Policy version** (current policy, recently changed policy — this catches stale-index bugs)

Every prompt, model, or retrieval change had to pass a **regression budget** before promotion: zero tolerance on severe policy misses or PII leakage, some tolerance on cost/latency with explicit sign-off, and *no* accuracy drop allowed on auto-send-eligible intents (assisted-only intents had slightly more slack).

**Failure modes I actively design against (and would walk through unprompted):**

| Failure mode | Why it happens | How I catch/prevent it |
|---|---|---|
| Retrieval miss | Answer exists in corpus but wasn't retrieved | Recall@K on golden set; query expansion; hybrid BM25+dense |
| Hallucinated citation | Model invents a source ID that doesn't exist | Deterministic post-generation validator (cheap, catches this for free) |
| Confident wrong answer on OOD input | Golden set doesn't cover the query distribution shift | Continuously mine production thumbs-down + low-confidence queries back into the golden set |
| Judge model bias (LLM-as-judge) | Same model judging its own outputs, or judge calibration drift | Use a different model family as judge; periodically recalibrate judge against human labels |
| Eval flakiness | LLM outputs are stochastic | Run each case N=3-5 times, track mean + variance, not a single pass/fail |
| Silent regression from a "harmless" prompt tweak | No one re-runs the full suite for small changes | Treat prompts as versioned code — every change triggers the golden-set diff, no exceptions |
| Metric gaming | Optimizing for the eval metric, not the underlying quality (Goodhart's law) | Rotate in fresh, unseen eval slices; pair automated metrics with periodic human spot-audits |

**The line I'd use to close this topic:** "Eval is not a pre-launch checkbox — it's the governance layer that lets you ship changes fast *because* you trust the gate, not despite it."

---

## 3. Production Monitoring for LLM-Powered Systems

**Framework — three pillars, borrowed from classic SRE but adapted for probabilistic systems:**

1. **Traditional SRE signals** — latency (P50/P95/P99, and specifically **TTFT** — time to first token, for streaming), error rate, availability, throughput, cost per request.
2. **AI-specific quality signals** — hallucination/faithfulness rate, fallback/refusal rate, retrieval recall drift, human-edit distance, policy-violation rate.
3. **Business/trust signals** — auto-resolve rate, thumbs up/down, escalation rate, SLA breach rate — the signals that tell you whether the system is *actually* helping, not just "running."

**Why this matters more than in traditional software:** a traditional service either works or throws an error — you get a clean signal. An LLM-powered system can be "up" (200 OK, fast, cheap) while silently degrading in quality — hallucinating more, drifting off-policy, or becoming less faithful to retrieved context. **Availability and correctness are decoupled** in a way they aren't in deterministic systems, so you need a monitoring layer purpose-built to catch the correctness dimension, not just the liveness dimension.

**Grounded example (Skydo copilot production dashboard):**
Every request carried a `trace_id` with full lineage — retrieved chunks and scores, the exact prompt sent, raw model output, policy-check verdict, and whether it was auto-sent, assisted, or escalated. That's non-negotiable for anything customer-facing in a regulated business — when compliance asks "why did the system say X to this customer," you need to reconstruct the decision, not guess.

Concrete SLOs we tracked in CloudWatch, with owners and alert thresholds:
- **Auto-send policy violation rate** → target zero known severe violations; any hit disables auto-send for that intent until reviewed (a hard kill switch, not a ticket).
- **PII leakage to the LLM provider** → target zero detected events; this alarms directly to security, not just eng.
- **First-draft latency P95** ≤ 60s, auto-resolve latency P95 ≤ 120s.
- **HITL edit rate**, tracked weekly as a leading indicator — rising edit rate means the model is drifting away from what humans trust, *before* it shows up as a customer complaint.
- **Cost per resolved ticket**, tracked by intent — token growth or retrieval fanout creeping up can quietly make automation uneconomic even while quality holds steady.

**The monitoring principle I'd state explicitly:** *Treat degraded quality as a first-class incident, not a "the model is being weird today" shrug.* We wired hallucination-rate and fallback-rate spikes into the same PagerDuty rotation as latency/error-rate spikes — because a support copilot confidently giving wrong compliance answers is a worse incident than one that's simply slow.

**Tooling I'd mention:** LangSmith / Langfuse / Arize Phoenix for tracing, Prometheus/CloudWatch for classic metrics, a nightly RAGAS-style eval job against the golden set feeding the same dashboards as live traffic — so pre-release and in-production quality are on one pane of glass, not two disconnected systems.

---

## 4. LLM Integration

**Framework — treat the LLM as an unreliable, expensive, occasionally-brilliant external dependency, and design accordingly.** This framing (not "the LLM is magic") is what separates a Staff-level answer here.

**Key integration decisions I'd walk through, in order:**

1. **Vendor vs. self-hosted, and model routing.** We used a **router**, not a single hardcoded model: cheap/fast model (e.g., GPT-4o-mini class) for intent classification and bulk drafting, a stronger model for complex multi-hop reasoning or borderline judge calls, and kept the routing config **external to the deploy** (SSM/config service) so we could shift traffic between models without a redeploy — critical when a vendor has an outage or a new model version regresses quality.
2. **Isolate the LLM behind a service boundary**, never call it directly from business logic scattered across the codebase. All prompts, model versions, and retry/timeout policy live in one place, versioned like code.
3. **Read-only tool-calling, not write access.** In the support copilot, the agent could call `get_account_status`, `get_transaction_details`, `get_kyc_status` — all read-only, enforced at the IAM and schema level, not just by convention or prompt instruction. Any mutation (refund, KYC status change, account unblock) stays outside the autonomous loop. This is the single most important architectural decision in any agentic system touching money or sensitive state: **the LLM can propose, deterministic code decides.**
4. **Resilience patterns specific to LLM calls:** exponential backoff for rate limits, a hard cap on tool-call loops (`MAX_TOOL_CALLS`), async job queues (SQS) so vendor latency spikes don't cascade into your request threads, and graceful degradation — if the vector store is down, proceed with reduced context and log a warning rather than hard-failing the whole request.
5. **Version everything**: prompt version, model version/route, policy version, retrieval index version — all logged per request. When something regresses, you need to know *which* of four moving parts changed.

**Grounded example:** When a model provider updated an embedding model version mid-quarter, our retrieval recall dropped silently — nothing errored, latency was fine, but relevant chunks stopped showing up in top-K. We caught it within a day because we had **pinned embedding model versions explicitly** and our nightly golden-set eval alerted on a recall@5 drop, not because anyone noticed manually. That's the argument for treating "LLM integration" as an engineering discipline with pinned dependencies and regression gates — the same rigor you'd apply to any other third-party API, just with an extra dimension (quality) beyond uptime.

**Trade-off I'd volunteer:** Tight coupling to one vendor's API shape (function-calling schema, streaming format) creates switching cost. I mitigate by keeping a thin abstraction layer (prompt + tool schema definitions decoupled from vendor SDK calls) — not a full "portable across all providers" abstraction, which is usually over-engineering, but enough that swapping a model provider is a two-week project, not a six-month rewrite.

---

## 5. Prompt Engineering

**Framework — prompt engineering is systems design, not wordsmithing.** The interviewer wants to see that you treat prompts as a versioned, tested, evaluated artifact — not a creative writing exercise.

**Principles I apply, concretely:**

1. **Explicit constraints over implicit hope.** The naive prompt is "here's context, answer the question." The correct pattern is an explicit rule list: answer *only* from provided context; if insufficient, say so verbatim; every claim must cite a `[source_id]`; do not use training knowledge to fill gaps; flag contradictions rather than silently picking one. Each rule exists because we saw the specific failure mode it prevents.
2. **Structured output over free text.** Forcing JSON output with mandatory fields (`answer`, `citations`, `confidence`, `escalate: bool`) makes downstream policy checks and audit logging deterministic instead of regex-parsing prose.
3. **Few-shot examples for edge cases, not the happy path.** The examples in a system prompt should demonstrate the *hard* cases — ambiguous intent, conflicting sources, when to refuse — because the model already handles the easy case fine.
4. **Prompts are versioned and tested like code.** Every prompt change runs against the golden set before promotion, with a diff report ("this changed faithfulness on the KYC-intent slice from 0.91 to 0.87 — is that acceptable?"). No prompt ships on "it looked better when I tried it three times."
5. **Separate the system prompt (stable, rarely changes) from the dynamic context (retrieved chunks, conversation history)** — this keeps the injectable surface area contained and makes prompt-injection defenses tractable (sanitize/delimit untrusted content distinctly from instructions).

**Grounded example — the mistake I coach engineers out of:** A common junior mistake on my team was writing a prompt that says "be helpful and accurate," which sounds fine but gives the model no falsifiable instruction — you can't write a test against "be accurate." I push every prompt review toward: *what specific, testable behavior do we want, and what's the golden-set case that would fail if this instruction were removed?* If you can't name that test case, the instruction probably isn't doing anything.

**On prompt injection specifically (they may probe this given it's an AI-native context):** Retrieved documents are untrusted input, not just data — a malicious or even accidentally instructive document ("ignore previous instructions...") in your knowledge base can override behavior if you don't defend against it. Defenses I'd list: delimiter/structural separation between instructions and retrieved content, sanitizing retrieved text before injection, never letting retrieved content itself contain executable instructions the model treats as system-level, and adversarial test suites (prompt injection, jailbreak attempts) run as part of the regression suite, not as a one-time pen test.

**Honest limitation I'd name:** Prompt engineering has diminishing returns — past a point, the failure isn't "the prompt is wrong," it's "the retrieval gave the model bad context" or "this task needs a different model/approach entirely (e.g., fine-tuning, or a smaller classifier upstream)." Part of the job is recognizing when you're prompt-engineering around a problem that isn't a prompting problem.

---

## 6. Data Pipeline Design

**Framework — for AI systems, the data pipeline is not "ETL that feeds a dashboard," it's the thing that determines what the model is even capable of seeing.** I'd open with that framing because it's the difference between a backend-engineer answer and an AI-systems answer.

**Design principles, concretely (using the RAG ingestion pipeline as the running example):**

1. **Parsing/normalization is a first-class stage, not a throwaway script.** Garbage-in, garbage-out is amplified in AI pipelines — a badly OCR'd document or a boilerplate-laden HTML page pollutes retrieval for every future query that touches it. I'd flag "chunk quality scoring" at ingestion (marking low-confidence OCR, boilerplate-heavy chunks) so downstream retrieval can down-weight them instead of trusting everything equally.
2. **Chunking strategy is a tuned parameter, not an arbitrary default.** Chunk size trades off context loss (too small) against semantic dilution (too large) — I'd default to ~512 tokens with overlap and justify it, not just cite a number.
3. **Idempotent, incremental ingestion.** Document updates must re-chunk, re-embed, and **upsert** (not delete-then-insert, which creates a window of missing data) — and must trigger downstream cache invalidation. This is the same idempotency discipline I've applied to payment pipelines at Skydo: **every pipeline stage needs a stable identity key** (`doc_id` + `chunk_id`) so re-runs are safe.
4. **Freshness and versioning are metadata, not an afterthought.** Every chunk carries `source`, `version`, `last_updated` — because for compliance-sensitive domains, a technically-relevant-but-stale chunk is worse than no chunk.
5. **Pipeline observability mirrors the request-path observability.** Ingestion throughput, embedding failure rate, dedup rate, index-build lag — these need dashboards and alerts just like the serving path, because a silently stale or partially-failed ingestion run is invisible until a user gets a wrong answer.

**Grounded example (Skydo — beyond RAG, on the payments side):** The same design discipline shows up in how I built the settlement reconciliation pipeline — three independent reconciliation stages (intent→debit, debit→FX, FX→remittance) rather than one monolithic job, specifically so a failure is *localizable*. I apply the same "decompose by failure boundary, not by convenience" instinct to AI data pipelines: separate ingestion, embedding, and indexing into independently retryable, independently observable stages, connected by a durable queue (Kafka/SQS) rather than a single script that dies halfway through and leaves you guessing what succeeded.

**Trade-off I'd volunteer on scale:** Batch ingestion (nightly re-index) is simpler to reason about and debug, but creates a freshness lag. Streaming ingestion (Kafka-driven, near-real-time upserts) solves freshness but introduces consistency hazards — a query can land between a chunk being deleted and its replacement being indexed. I'd choose batch by default and only pay for streaming complexity when the business genuinely needs sub-hour freshness (e.g., live policy changes that must apply immediately) — not because streaming is impressive to build.

---

## 7. Scalability and Data Modeling Decisions

**Framework — scalability for AI-adjacent systems has two axes people conflate: (a) classic infra scale (QPS, storage, throughput) and (b) knowledge-base scale (corpus size, update frequency, retrieval quality at scale) — and the data model has to serve both.**

**Data modeling decisions I'd walk through (RAG store schema, as the concrete example):**

```
test_cases / documents:
  - doc_id, chunk_id (composite key — idempotency + traceability)
  - embedding vector (quantized for storage efficiency)
  - source, version, last_updated (freshness + audit)
  - chunk_quality_score, tags (routing/filtering signal)

results / audit:
  - trace_id, prompt_hash, model_version, policy_version
  - scores (JSONB — faithfulness, relevance, etc.)
  - append-only, never mutated (compliance requirement)
```

**Key decisions and why:**
- **Composite key (`doc_id` + `chunk_id`)**, not a synthetic auto-increment — makes re-ingestion idempotent and makes "which document did this chunk come from" a join, not a lookup into a separate mapping table that can drift out of sync.
- **Quantized vectors (int8/product quantization)** over raw float32 once corpus size crosses tens of millions of chunks — at that scale, raw vectors are ~4x the storage for a small, usually acceptable recall hit; I'd validate the recall hit against the golden set before committing, not assume it's fine.
- **Append-only audit tables**, never updated in place — this is a compliance requirement I carry over directly from the fintech reconciliation work: if you can mutate a decision record, you can't prove what actually happened at decision time.
- **Separate the "hot" serving path data model (vector store, cache) from the "cold" analytical path (OLAP store for deep eval analysis)** — trying to make one store serve both low-latency lookups and complex aggregate queries is a classic scaling mistake; I'd route eval/analytics queries to a warehouse (Snowflake/BigQuery), not the live vector store.

**Scale math I'd volunteer to show I actually think in numbers, not vibes:** For a 10M-document corpus at ~5 chunks/doc, that's 50M chunks; at 1536-dim float32 embeddings that's ~300GB raw, quantizable to ~75-150GB with HNSW graph overhead — fits on a single high-memory instance, meaning **the bottleneck at that scale is almost never storage, it's retrieval latency and reranking throughput at query time.** I'd rather spend the design conversation on hybrid retrieval (BM25 + dense, merged via Reciprocal Rank Fusion) and two-stage reranking than on sharding a store that doesn't need sharding yet — premature horizontal scaling of the data layer is a common over-engineering trap in this space.

**The judgment call I'd name explicitly:** Data modeling for AI systems has an extra dimension traditional systems don't: **the model itself is part of the schema** (embedding dimensionality, model version) — a model upgrade can silently invalidate your entire index. I design for that by treating "embedding model version" as a first-class column, not metadata, and building the re-embed-on-model-change path into the pipeline from day one rather than as a fire drill later.

---

## 8. API Contracts

**Framework — for AI-powered features, API contracts need to encode uncertainty and provenance as first-class fields, not just the "answer."** This is the specific way AI system API design differs from typical CRUD API design, and naming it explicitly is the Staff-level signal here.

**Concrete contract shape I'd propose and defend (from the support copilot, this is close to what we actually shipped):**

```json
{
  "response_id": "uuid",
  "trace_id": "uuid",
  "answer": "string",
  "confidence": 0.0,
  "sources": [
    {"doc_id": "...", "chunk_id": "...", "version": "...", "confidence": 0.0}
  ],
  "policy_check": {"passed": true, "violated_rules": []},
  "action": "auto_send | assisted_draft | escalate",
  "model_version": "string",
  "prompt_version": "string",
  "latency_ms": 0
}
```

**Contract design decisions I'd defend if pushed:**
1. **`confidence` and `action` are contract fields, not internal details** — the consumer (a support dashboard, a downstream system) needs to make a routing decision without re-deriving business logic, and needs to render "this is an AI-assisted draft, review before sending" honestly to the human in the loop.
2. **`sources` is always present, even when empty** — an empty array is itself a signal (nothing was grounded) and should be contractually distinguishable from "sources omitted for brevity." Ambiguity here is exactly the kind of thing that causes silent trust erosion.
3. **Versioning fields (`model_version`, `prompt_version`) are part of the response contract, not just internal logs** — because downstream consumers (analytics, compliance tooling) need to correlate behavior changes with releases without cross-referencing a separate system.
4. **Backward compatibility discipline is stricter here than typical APIs**, because consumers include compliance/audit tooling — I'd default to additive-only changes and version the endpoint (`/v2/`) for anything that changes existing field semantics, exactly like I would for a payments API. AI-facing APIs are not exempt from the boring API-versioning discipline just because the payload includes LLM output.
5. **Idempotency keys on the request side** — a client retry on a slow LLM call must not trigger a duplicate `auto_send`. Same idempotency discipline as a payments API: bind the idempotency key to business intent (`ticket_id` + `attempt_id`), not to transport-level request ID.

**Trade-off I'd volunteer:** Streaming responses (token-by-token) are great for perceived latency but complicate the contract — you can't attach final `confidence`/`policy_check` until generation completes, so the contract needs a clear "provisional" vs. "final" event distinction (e.g., a terminal event carrying the full metadata after the stream closes) rather than pretending the streamed tokens are the whole contract.

---

## 9. Production Incidents & Performance Issues

**Framework — same incident discipline as any production system, plus one extra dimension: distinguishing "the system is down" from "the system is confidently wrong," because the second one doesn't page you by default.**

**Story 1 — Classic production incident (Skydo, non-AI, but this is your strongest quantified incident story):**

*Situation:* A retry bug in an upstream service re-invoked our payout API with the same business payload but a new request ID, and our idempotency key was bound to the request ID rather than business intent — causing ~0.3% of payouts in a 90-minute window to be submitted twice; 5 actually settled twice.

*Action:* Feature-flagged the retry path off within 10 minutes (stop the bleeding first, accept latency cost over more duplicates). Declared myself incident commander, split roles (investigation / comms / partner liaison) so no one was both firefighting and communicating. Coordinated with the banking partner to pause in-flight duplicates, initiated same-day reversal for the 5 that settled, sent a conservative customer-facing update within 45 minutes, and proactively informed the CEO before they heard it externally.

*Systemic fix:* Re-bound idempotency to the business-level `payout_intent_id`, built a reusable idempotency library so engineers couldn't get it wrong by default, and introduced continuously-running invariant tests against the live ledger (e.g., "debits == credits ± in-flight") that page on-call *before* a customer notices — this framework has since caught three unrelated bug classes.

*Result:* Zero recurrences in 18 months; the invariant-test pattern is now standard across the payments platform.

**Story 2 — AI-specific "quiet" incident (use this to show you understand the AI-specific dimension):**

*Situation:* On the support copilot, a third-party embedding model provider pushed a version update mid-quarter. Nothing errored — latency was normal, the service reported healthy — but retrieval recall silently dropped because the vector space shifted and our index wasn't re-embedded against the new model.

*Action:* Caught within a day via the nightly golden-set eval (recall@5 alert), not via customer complaint or manual observation. Immediately pinned the embedding model version explicitly (stopped auto-tracking "latest"), rolled back to the prior version, and scheduled a controlled re-embed of the corpus with validation against the golden set before switching versions again.

*Systemic fix:* Treat "model/embedding version" as a pinned dependency with an explicit upgrade process (re-embed + golden-set validation + shadow comparison), the same way you'd treat a database version upgrade — not something that silently rides along with a vendor's API.

*Lesson I'd state explicitly:* "The scariest AI incidents are the ones that don't look like incidents — uptime and correctness are decoupled in probabilistic systems in a way they aren't in deterministic ones, so your monitoring has to be designed to catch quality degradation as a distinct signal from liveness, or you'll find out from a customer, not a dashboard."

**Performance issue example (Goldman Sachs, for the "hardest technical problem" framing):** Desk complaints that VaR calculations were slow on high-volatility days, with no SLO and no clear culprit across cache/aggregation/feed layers. I got an explicit SLO from the head trader (≤3s EOD, ≤1.5s intraday) before doing any engineering work, then instrumented every hop with OpenTelemetry spans as the *first* milestone (a trace, not a fix). The trace showed 85% of tail latency came from GC pauses during shard rebalancing — not the cache or feed, which is what everyone assumed. Fixed with off-heap storage for hot slices and rebalance throttling; P99 dropped from ~7s to ~1.1s. **The lesson I lead with:** instrument before you optimize, because your intuition about where the time goes in a distributed system is wrong more often than it's right.

---

# PART B — Leadership & Org

## 10. Sourcing Strategies for AI/ML Talent

**Framework — AI/ML hiring in 2026 has a specific market distortion you should name: there's an oversupply of people who can call an LLM API and call it "AI engineering," and a real scarcity of people who understand evals, retrieval quality, production reliability of probabilistic systems, and the judgment to know when *not* to use an LLM. Your sourcing strategy has to be designed to find the second group.**

**Concrete sourcing channels and how I'd calibrate each:**

1. **Internal upskilling over pure external hiring for the first wave.** When I led GenAI adoption at Skydo, I deliberately didn't go external-first for the core AI capability — I identified 2-3 strong systems engineers who already had the "correctness under failure / observability" instincts and taught them the AI-specific layer (evals, RAG, prompt discipline) through a real shipped project. **Distributed-systems judgment is harder to hire for than AI-specific tooling knowledge**, and the tooling knowledge has a much shorter ramp time than the judgment does. This inverts the usual instinct to hire "AI experience" first.
2. **Portfolio/artifact-based sourcing over resume keyword matching.** For external hires, I weight a candidate's actual shipped artifact (a GitHub repo, a blog post with real eval numbers, a Kaggle result with methodology writeup) far above "3 years LLM experience" on a resume — because the field is so new that resume tenure is a weak signal, and a lot of "LLM experience" right now is superficial API glue code.
3. **Targeted communities over generic job boards for senior AI talent** — ML/AI-specific Slack/Discord communities, conference speaker lists (relevant applied-AI conferences, not just academic ML venues), and specifically people who've written publicly about evals or production LLM failures — that's a strong proxy for someone who's been burned by the hard parts and learned from it.
4. **Referral-driven for senior ICs, with an explicit "who impressed you" ask** — I ask my strongest engineers not "who's looking for a job" but "who solved a hard AI/ML problem in front of you and impressed you" — different question, much better signal.
5. **Don't over-index on big-lab pedigree alone.** A candidate from a frontier lab may have deep model-training experience but zero experience with the "boring 80%" — production reliability, cost control, data pipeline discipline — which is what most applied AI/ML roles actually need day to day. I probe for this explicitly rather than assuming pedigree implies fit.

**The sourcing principle I'd state explicitly:** *For AI/ML roles right now, sourcing for judgment and systems fundamentals is more reliable than sourcing for keyword-matched "AI experience," because the tooling is moving too fast for 2-year-old experience to still be current, but systems judgment doesn't expire.*

---

## 11. How You Calibrate the Interview Bar

**Framework — calibration is a process problem before it's a rubric problem. A great rubric used inconsistently is worse than a mediocre rubric used consistently, because inconsistency is what actually produces bad hires and unfair rejections.**

**Concrete mechanics I run:**

1. **Written rubric per round, shared before the loop, not improvised per interviewer.** For an AI/ML role specifically, I define what "strong signal" looks like at each level explicitly — e.g., for a system design round: does the candidate treat "accuracy" as a single number or push for a stratified eval? Do they default to "just fine-tune it" or reason about the RAG/fine-tune trade-off? Do they mention failure modes unprompted?
2. **Calibration sessions before the loop starts, not just after.** Before a hiring push, I run a session where 2-3 interviewers independently score the *same* recorded/sample answer against the rubric, then compare — this surfaces "I score generosity" vs. "I score harshly" miscalibration before it costs you a candidate.
3. **A bar-raiser on every loop who has no reporting-line stake in filling the role fast.** This is the single highest-leverage mechanism against bar erosion under hiring pressure — someone in the loop explicitly incentivized to protect the bar, not the headcount target.
4. **Written feedback before the debrief, always.** If people discuss before writing, you get anchoring/groupthink — the first strong opinion in the room silently becomes everyone's opinion. I require written scores submitted independently, then debrief.
5. **Debrief same day, while signal is fresh** — delayed debriefs degrade into "I think they were fine?" which is not a decision, it's a shrug.
6. **Explicitly separate "would I want to work with them" (real signal) from "did they sound confident" (a bias trap that specifically favors a certain communication style over substance)** — I ask interviewers to cite a specific moment in the interview as evidence for every score, not a vibe.

**AI/ML-specific calibration nuance I'd add:** the bar has to explicitly reward candidates who say "I don't know, but here's how I'd find out" or "I'd need to see the eval numbers before committing to that architecture" — in a field this new and fast-moving, false confidence is a negative signal, not a positive one, and I calibrate interviewers to actively look for well-reasoned uncertainty rather than penalize it as "didn't know the answer."

**Grounded example:** At Skydo, I standardized a rubric across four dimensions (problem-solving, systems design, collaboration, ownership) for every loop I ran, made bar-raiser mandatory, and required written feedback before debrief — result was a high offer-accept rate and zero regretted hires across the loops I personally ran in the last 18 months. That process, not any individual interviewer's brilliance, is what produced the consistency.

---

## 12. Culture-Setting Within the Team

**Framework — culture is what you repeatedly reward and repeatedly tolerate, not what's written on a slide. For an AI/ML team specifically, the culture question that matters most is: does the team treat "the model is probabilistic" as an excuse for sloppiness, or as a reason for more rigor?**

**Concrete mechanisms I use to set culture (not just values statements):**

1. **Make quality a shared responsibility, not a QA/eval-team's private burden.** At Skydo, every engineer owned the eval coverage of their own feature — the platform/eval infra was a shared tool, not a gatekeeper team. This mirrors how I'd run an AI/ML team: the person who ships the prompt change also owns running it against the golden set, not a separate "AI quality team" that reviews after the fact.
2. **Every incident becomes a test case, no exceptions.** Post-mortems whose output is "we fixed it" without a corresponding regression test or eval-set addition don't count as closed. This is how a team's golden set and invariant tests grow into real institutional memory instead of everyone re-learning the same lesson.
3. **Blameless post-mortems, with a banned phrase: "human error."** I treat "human error" as a description of a *symptom*, never a root cause — the real question is always "what system let a human make that error, and how do we change the system." This is the single highest-leverage cultural lever for psychological safety, and it directly increases the rate at which people report near-misses early instead of hiding them.
4. **Public "known gaps" registers over private tribal knowledge.** For ambiguous, evolving problem spaces (which almost everything in applied AI is), I keep a shared, visible list of what we know we don't know yet — this normalizes uncertainty as a team-wide fact instead of something individuals quietly worry they should already know.
5. **Weekly show-and-tell of *failures*, not just wins** — for AI features specifically, sharing "here's a case where the model did something weird and here's what we learned" is one of the fastest ways to build calibrated intuition across a team, versus everyone independently discovering the same surprising failure modes.
6. **Set the bar by what I personally do, not just what I say.** I led the GenAI adoption rollout hands-on — wrote the first MCP server myself, hit the same rough edges the team would hit, and invited the most skeptical engineers to review the resulting standard specifically because their objections sharpen it more than agreement does. Culture-setting from an EM who's visibly still building things lands very differently than culture-setting from slides.

**Result I'd cite:** GenAI tooling adoption reached ~90% of engineers within a quarter with zero security incidents, and PR cycle time improved ~25% without a corresponding rise in review rework — meaning we didn't trade speed for quality, which is the actual test of whether a culture change worked versus just looked good on a dashboard for one quarter.

---

## 13. Conflict Resolution and Performance Management

**Framework — separate two categories immediately when answering: peer/team conflict (a disagreement or friction between people) and underperformance (a gap against a clear expectation). They require different mechanics, and conflating them is a common EM mistake worth naming explicitly.**

**Conflict resolution — concrete example (a pattern, not necessarily one company):**
A strong IC was technically excellent but routinely dismissive in design reviews, which was quietly eroding trust and making junior engineers stop speaking up. My approach:
1. **Private 1:1, with specific, dated examples** (a specific PR comment, a specific meeting moment) — never "you're often dismissive," always "in the review on [date], when X said Y, your response was Z, and here's the impact I observed."
2. **Frame around impact, not personality** — "this is reducing the number of people willing to raise concerns in reviews, which is a real risk for a team shipping AI features where dissenting technical opinions catch real problems" — connect the behavior to a business/team outcome, not a character judgment.
3. **Set explicit, observable 30/60/90 goals**, and follow up with real-time feedback in the moment (I shadowed the next two design reviews specifically to give immediate, low-stakes feedback rather than waiting for the next scheduled 1:1).
4. **Result:** behavior shifted within ~6 weeks, no attrition, and the engineer later acknowledged the feedback directly — the fastest resolutions come from specificity and speed, not from waiting to accumulate more evidence.

**Performance management — the mechanics I run, stated plainly:**
- **Clear written expectations first** — performance conversations fail most often not because the manager was too harsh, but because the expectation was never made explicit and measurable in the first place.
- **A structured 30-day coaching period** before anything formal — with specific, written, checkable goals, not vague "improve your communication."
- **Document from day one**, not retroactively when you're already building a PIP case — this protects both the employee (clarity, fairness) and the process (defensibility).
- **PIP only if coaching genuinely fails**, and by that point it should surprise no one in the room, including the employee.
- **For AI/ML teams specifically**, I calibrate performance expectations to account for the field's immaturity — e.g., "ships production-quality eval infrastructure" is a fair bar; "always picks the objectively best model architecture" is not, because the field doesn't have settled best-architecture answers yet. Holding people to a false certainty standard in a genuinely uncertain field creates unfair performance conversations.

**Cross-functional conflict example (engineering vs. product, a distinct sub-case worth having ready):** A PM wanted to ship an onboarding feature; engineering flagged a compliance gap. Instead of letting it become an async Slack argument, I brought both sides into one room, quantified the actual risk explicitly (regulatory exposure $ + time-to-fix), and proposed a phased release (feature-flagged beta for a whitelisted cohort, full rollout after an audit-trail review) rather than a binary yes/no. **The EM's job in that moment is to quantify the tradeoff, not pick a side** — that reframing turns a political conflict into a shared engineering decision.

---

## 14. Balancing IC Contribution with Management

**Framework — the honest answer is that the ratio should shift with team size and maturity, and pretending you can be a 50% coder forever at any team size is a common self-deceiving answer that experienced interviewers see through immediately. Name the shift explicitly.**

**How I actually allocate my time, stated concretely:**
- **At a smaller team / earlier-stage AI initiative** (e.g., building the first version of the support copilot), I was hands-on — I personally wrote the first RAG retriever, the first MCP server, the first agent orchestration loop. Not because I distrust delegation, but because in a genuinely new problem space, being the first person to hit the rough edges is the fastest way to write standards and review other people's designs credibly.
- **As the team and system matured**, my IC time shifted from "writing the code" to **architecture review, ADR review, and unblocking**: I still read every design doc for anything touching money movement or compliance, and I still personally review the highest-risk PRs (prompt changes, policy-check logic, anything with auto-send implications) — but I stopped being the person writing the median PR.
- **The IC time I protect deliberately, even as a manager**, is time spent staying credible on the hardest, highest-leverage technical decisions — not spreading myself thin across all code. I'd rather deeply understand one system end-to-end (the copilot) than shallowly touch everything, because shallow touching produces bad architecture reviews, and bad architecture reviews are worse than no reviews.
- **A concrete rule I use:** if I'm the only person who understands a critical piece of the system, that's a bus-factor risk I created, not a badge of technical credibility — so any deep IC dive I do has an explicit "teach-back" component (pairing, documented ADR, or a workshop) so the knowledge doesn't stay bottlenecked on me.

**The trade-off I'd name honestly if pushed:** Staying hands-on has a real opportunity cost — every hour I spend writing code is an hour not spent on hiring, 1:1s, or removing organizational blockers, and at a certain team size that trade stops making sense. I calibrate the ratio to "what does the team most need from me right now," not to a fixed personal preference for coding — a manager who insists on staying 50% IC because they enjoy it more than management is optimizing for their own comfort, not the team's output.

**Grounded example:** At Skydo, I run weekly architecture reviews and pair senior+mid engineers on ADRs rather than writing every design myself — this scaled design-review coverage on major changes from ~30% to ~100% of changes, which wouldn't have been possible if I'd tried to personally review or write everything as the team grew to 12.

---

## 15. Translating Ambiguous Business Problems into AI Features

**Framework — the failure mode to explicitly guard against is "we have an LLM, what can we point it at" (technology-first) versus starting from the actual business pain and asking whether AI is even the right tool. State this discipline up front — it's the single strongest signal of product judgment in this topic.**

**Grounded example — the Agentic Support Copilot, told as a translation story:**

*The ambiguous business problem (as it arrived):* Support first-response time was ~90 minutes, backlog was growing, and the ask from leadership was vague — "can AI help with support?" That's not a spec, it's a hunch.

*How I translated it into a scoped, buildable feature:*
1. **Quantified the actual pain first, before touching architecture.** Broke down ticket volume by intent (payment failure, KYC query, FX rate, blocked account, compliance question) and found that ~65% of volume was low-risk, repetitive, and answerable from existing policy docs — versus a long tail of genuinely complex, compliance-sensitive cases. That segmentation *is* the product decision: automate the 65%, don't touch the tail.
2. **Named the non-negotiable constraint before designing anything** — no autonomous money movement, no unreviewed customer-facing compliance claims. This wasn't a technical constraint I discovered later; I set it as a design principle from day one specifically because I know how these projects fail: an early demo works, scope quietly creeps toward higher-risk automation, and eventually a bad auto-response becomes a trust or compliance incident.
3. **Reframed the deliverable from "an AI that answers tickets" to "a trusted support automation platform"** — a subtle but important reframe, because it forced the design to include HITL review, audit logging, and a rollout gate structure from the start, not as an afterthought bolted on after a hallucination incident.
4. **Shipped in shadow mode first**, comparing AI drafts against human responses without customer exposure, before expanding auto-send intent-by-intent based on measured quality — turning "will this work" from a debate into a measured rollout.
5. **Result, in the business's language, not engineering's:** first-response time ~90min → ~10-12min, ~65% auto-triage, SLA breach rate down ~50%, with zero policy-violation incidents in the rollout — numbers that map directly back to the original vague ask, which is the whole point of the exercise.

**The generalizable framework I'd state explicitly, for any future ambiguous "can AI help with X" ask:**
1. Quantify the actual pain in business terms before any architecture conversation.
2. Segment the problem by risk and repetitiveness — automate the low-risk/high-repetition slice first, always.
3. Name irreversible-action boundaries explicitly and early, not as a response to an incident.
4. Prefer a reframed, more honest problem statement over the one you were handed, and get stakeholder buy-in on the reframe.
5. Ship in a measured, reversible way (shadow mode → limited rollout → expansion) so "is this working" is answered by data, not opinion.

**Honest failure mode I'd own if asked "what would you do differently":** early on I underestimated how much support-agent trust mattered independent of model accuracy — even a technically correct draft that "felt off" in tone got rejected by reviewers, which slowed adoption more than any accuracy issue did. That taught me to treat **human trust in the AI's output** as its own metric to design and measure for, not just a downstream consequence of accuracy.

---

## 16. Prioritization Frameworks and Stakeholder Management

**Framework — for AI/ML initiatives specifically, standard prioritization (impact vs. effort) breaks down because "effort" and "risk" are much harder to estimate up front than for typical software features — a prompt tweak might be an hour or a month depending on retrieval quality, and you often don't know which until you start. Name this explicitly as the AI-specific complication.**

**My actual framework:**

1. **Segment by reversibility, not just impact/effort.** Cheap-to-reverse bets (a new prompt, a new eval slice, a shadow-mode experiment) get greenlit fast with light process. Expensive-to-reverse bets (auto-send to customers, a new fine-tuned model in the critical path, a new vendor dependency) get a heavier design-review gate. This is a direct adaptation of the reversible/irreversible framing I use generally for engineering decisions.
2. **De-risk before committing to a roadmap slot.** For anything with real architecture uncertainty (a new retrieval approach, a new model provider), I timebox a spike whose *deliverable* is "reduced uncertainty," not "shipped feature" — and only after the spike do I put a real estimate and priority on the roadmap. This avoids the classic AI-project trap of committing a quarter to something that turns out to be a two-week spike away from being obviously wrong.
3. **Make trade-offs explicit and visible to stakeholders, in their language, not eng jargon.** When there's tension between shipping fast and quality bar (e.g., product wants auto-send live this sprint, eng flags the golden-set faithfulness score isn't there yet), I don't resolve that privately — I bring the actual number to the stakeholder conversation: "current faithfulness is 0.81, our bar for auto-send is 0.85 — here's the plan to close the gap, and here's what shipping today at 0.81 would risk." Quantified trade-offs de-politicize the conversation.
4. **Weekly written status to stakeholders: delivered / at-risk / asks — no surprises.** For skip-level and cross-functional stakeholders, I default to async written updates over meetings, specifically because "no surprises" is the actual currency of stakeholder trust — a stakeholder who finds out about a slip in a status doc trusts you more than one who finds out in a meeting reacting live.
5. **When scope must shrink (hiring freeze, deadline pressure), decompose scope explicitly rather than asking the team to work weekends.** "What's the smallest version that delivers 80% of the value in the time we have" is a negotiation on *scope*, and I insist stakeholders engage with that trade-off explicitly rather than treating timeline as fixed and quality/scope as infinitely compressible.

**Grounded example:** On the support copilot, product/support leadership initially wanted auto-send live across most intents in the first month. I pushed back with the reversibility framing: shipped shadow mode first (zero customer risk, real measurement), then expanded auto-send **intent by intent**, gated by the specific promotion criteria (golden-set accuracy, shadow-comparison preference, live guardrail stability, cost-per-ticket) rather than a calendar date. This turned a potential "eng is slowing us down" conflict into a shared, data-driven rollout plan that stakeholders bought into because they could see the gate criteria themselves, not just take my word for "not ready yet."

---

## Quick-Fire Glossary

Use this for rapid-fire follow-ups — one-liners, not essays.

| Term | One-line definition |
|---|---|
| RAG | Inject knowledge at inference time via retrieved context; no weight changes |
| Fine-tuning | Update model weights on domain data; changes behavior/format, not "live" knowledge |
| RAGAS | Framework for RAG eval: faithfulness, answer relevancy, context precision/recall |
| LLM-as-judge | Use a separate LLM to score another LLM's output against a rubric; avoid self-grading |
| Golden set | Curated, stratified (input, expected) pairs used as regression ground truth |
| Hybrid retrieval | BM25 (sparse/exact) + dense (semantic) merged via Reciprocal Rank Fusion |
| Reranking | Cross-encoder scores top-K candidates precisely after cheap ANN retrieval narrows the field |
| HITL | Human-in-the-loop — humans review/approve before irreversible or low-confidence actions |
| Catastrophic forgetting | Fine-tuning degrades previously-learned base capabilities |
| TTFT | Time to first token — key latency metric for streaming responses |
| Shadow mode | Run the new system on real traffic without exposing results, to measure safely |
| Prompt injection | Untrusted input (often retrieved documents) overriding system instructions |
| Idempotency key (business-bound) | Dedup key bound to business intent, not transport-level request ID |
| Regression budget | Explicit tolerance for how much a metric may degrade before blocking a release |
| Bar-raiser | Interviewer with no stake in filling the role fast, present to protect the hiring bar |

---

## Questions to Ask Them

Pick 3-4 based on how the conversation goes. Avoid anything Googleable.

1. "What does the current eval and monitoring setup look like for your AI-powered features today — and what's the biggest gap this role is meant to close?"
2. "How do you currently decide between RAG, fine-tuning, or a smaller classical model for a given feature — is there a shared decision framework, or is it ad hoc per team?"
3. "What does the split look like between IC contribution and people management for this role in practice, day to day — not just on paper?"
4. "How does the team currently handle the tension between shipping AI features fast and maintaining a quality/trust bar — is there a concrete gate, or is it a judgment call each time?"
5. "How is the AI/ML hiring bar currently calibrated — is there a shared rubric across interviewers, and where do you see it breaking down?"
6. "What's the most recent production incident involving an AI-powered feature, and what changed systemically afterward?"

---

## Pre-Interview Checklist

- [ ] Re-skim this doc once tonight, once tomorrow morning — don't cram new material the night before.
- [ ] Have exact numbers ready: 90min→10-12min first response, 65% auto-triage, ~50% SLA breach reduction, 90% GenAI tooling adoption, 25% PR cycle time improvement, 30% production incident reduction.
- [ ] Sketch the RAG + tool-calling + HITL architecture on paper once, from memory, before the interview.
- [ ] Prepare the 90-second opening pitch (top of this doc) out loud, not just in your head.
- [ ] Pick your two anchor stories (Agentic Support Copilot, GenAI adoption rollout) and make sure you can go 5 levels deep on both without notes.
- [ ] Have 3-4 questions ready from the section above.
- [ ] For every story: end with a number and a one-sentence "lesson," every time — that's the rubric checkpoint most loops score explicitly.
- [ ] When asked about a failure, own it in the first sentence — no deflection.

---

*Compiled for tomorrow's interview(s) — covers all 16 requested topics at Staff Engineer / EM depth, grounded in Skydo, Goldman Sachs, Moneyview, and Oracle experience.*
