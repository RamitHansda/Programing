# Name Matching Service — Staff Engineer Implementation Guide

> How to design and ship a production name-matching service: algorithms, indexing, scoring, evaluation, and ops. Framed for staff-level interviews and real builds (KYC/AML screening, customer 360 dedup, beneficiary/payee matching, merchant matching).

---

## Table of Contents

1. [Problem Framing & Resolved Assumptions](#1-problem-framing--resolved-assumptions)
2. [Capacity (napkin math)](#2-capacity-napkin-math)
3. [One-Sentence Architecture](#3-one-sentence-architecture)
4. [API Surface](#4-api-surface)
5. [Matching Pipeline (the core)](#5-matching-pipeline-the-core)
6. [Algorithms — What to Use When](#6-algorithms--what-to-use-when)
6b. [Which AI Model to Use (and when)](#6b-which-ai-model-to-use-and-when)
6c. [Train Your Own Model (what to own)](#6c-train-your-own-model-what-to-own)
6d. [Your Product Shape: Pairwise Match + Confidence](#6d-your-product-shape-pairwise-match--confidence)
7. [Indexing & Candidate Generation](#7-indexing--candidate-generation)
8. [Scoring, Thresholds & Decision Policy](#8-scoring-thresholds--decision-policy)
9. [Data Model](#9-data-model)
10. [Architecture Diagram](#10-architecture-diagram)
11. [Hard Problems (and how you solve them)](#11-hard-problems-and-how-you-solve-them)
12. [Evaluation & Continuous Quality](#12-evaluation--continuous-quality)
13. [Failure Modes & Ops](#13-failure-modes--ops)
14. [Rollout Plan](#14-rollout-plan)
15. [What You're Deliberately NOT Building](#15-what-youre-deliberately-not-building)
16. [Interview Script (45 min)](#16-interview-script-45-min)
17. [Trade-off Q&A](#17-trade-off-qa)

---

## 1. Problem Framing & Resolved Assumptions

"Name matching" is ambiguous. Pin scope before drawing boxes.

| Decision | Staff answer | Why |
|---|---|---|
| Entity types | **Person + Organization**, same pipeline, different normalizers and feature weights | Shared infra; cultural name rules and legal-entity aliases differ enough to need typed configs |
| Primary use cases | (1) **Screen a query name against a watchlist / customer corpus**, (2) **pairwise compare** two names, (3) **batch dedup** a corpus | Covers online KYC, payee verification, and offline customer-360 |
| Languages | **Unicode-first**; Latin + transliteration for Indic/CJK/Arabic as v1; language packs pluggable | Hardcoding ASCII-only is a junior trap; full i18n everywhere is a year-long project |
| Match modes | Return **ranked candidates with scores + reasons**, not a boolean | Callers (compliance, payments) need explainability and tunable risk appetite |
| Latency | Online: **P99 ≤ 80ms** for top-K against a 50M-entity shard; Batch: throughput-bound | Interactive onboarding / checkout; batch can run overnight |
| Corpus size | **50M entities / tenant** (largest), **500M aggregate**; watchlists smaller but higher recall SLA | Forces blocking indexes; rules out O(N) scan |
| Consistency | Corpus updates **searchable within 5s P99**; match decisions are **immutable audit events** | Screening must not miss a newly listed sanction; decisions need forensic replay |
| False-positive cost | **High** (ops review, blocked payments) | Prefer calibrated scores + human-review band over aggressive auto-match |
| False-negative cost | **Very high** for sanctions / AML | Dual-path recall (phonetic + token + fuzzy) and conservative thresholds for screening |

### Product shapes (pick one and say so)

| Shape | When |
|---|---|
| **Matching-as-a-service** (multi-tenant API) | Platform team, many product consumers |
| **Embedded library + shared index service** | Latency-critical path inside payments/KYC |
| **Batch EMR/Spark job only** | One-off customer merge — not a "service" |

This guide assumes **matching-as-a-service** with an online query path and a batch dedup path sharing the same scorer.

### Clarifying questions to ask first (interview or kickoff)

1. Person, org, or both? Cross-type matches?
2. Online screening, offline dedup, or both?
3. Languages / scripts in scope?
4. Acceptable false-positive vs false-negative tradeoff per use case?
5. Do we need explainability ("why did these match")?
6. Corpus size and QPS?
7. Is the corpus append-only watchlists, or mutable customer records?
8. Must we support DOB / address / ID as secondary signals, or names only?

---

## 2. Capacity (napkin math)

```
Largest tenant corpus:     50M person/org records
Aggregate:                 500M records
Avg name fields:           given + family + aliases (~5 strings / entity)
Query QPS peak:            5K aggregate, 1K single-tenant burst
Online latency budget:     P99 ≤ 80ms → top-K candidates + score

Index footprint (rough):
  Normalized tokens + phonetic keys + inverted postings:
    ~200–400 bytes/entity → 10–20 GB / large tenant (hot)
  Optional embedding (384-d int8) for semantic alias recall:
    50M × 384 ≈ 19 GB / tenant — only if alias recall needs it

Candidate gen target:      ≤ 200 candidates / query before precise scoring
Precise score:             ~50–100 µs / pair on JVM → 200 pairs ≈ 10–20ms
Normalize + index lookup:  ~5–15ms
Rerank + explain:          ~5ms
Headroom for GC/network:   ~40ms

Batch dedup (50M):
  Naive O(N²) impossible.
  Blocking → candidate pairs ~ N × 50 avg = 2.5B pair evaluations worst;
  with good blocks (phonetic + DOB bucket) → tens of millions of pairs — Spark/Flink job.
```

**Staff signal:** state that **candidate generation (blocking)** is the load-bearing decision, not the edit-distance formula. Without blocking, nothing scales.

---

## 3. One-Sentence Architecture

> **Normalize → block/candidate-generate (phonetic + token + n-gram indexes) → multi-signal score → calibrate to decision bands (AUTO_MATCH / REVIEW / NO_MATCH) → audit log; same scorer used online and in batch.**

---

## 4. API Surface

```
# Pairwise (two names)
POST /v1/match:compare
  Body: {
    left:  { type: "PERSON"|"ORG", name: "...", given?, family?, aliases?, locale? },
    right: { ... },
    profile: "kyc_strict" | "payee_lenient" | "customer_dedup"
  }
  → 200 {
      score: 0.0–1.0,
      decision: "AUTO_MATCH" | "REVIEW" | "NO_MATCH",
      signals: [{ name, value, weight }],
      explanation: "..."
    }

# Screen against a corpus / watchlist
POST /v1/tenants/{tenantId}/indexes/{indexId}/match:search
  Body: {
    query: { type, name, given?, family?, dob?, country?, aliases? },
    topK: 20,
    profile: "sanctions_screening",
    minScore?: 0.6
  }
  → 200 {
      matches: [{ entityId, score, decision, signals, explanation }],
      queryId,           // for eval / feedback
      tookMs
    }

# Corpus mutations
POST   /v1/tenants/{t}/indexes/{i}/entities
PUT    /v1/tenants/{t}/indexes/{i}/entities/{id}
DELETE /v1/tenants/{t}/indexes/{i}/entities/{id}
POST   /v1/tenants/{t}/indexes/{i}/entities:bulk   # S3/jsonl backfill

# Batch dedup job
POST /v1/tenants/{t}/indexes/{i}/jobs/dedup
  → 202 { jobId }
GET  /v1/tenants/{t}/indexes/{i}/jobs/{jobId}

# Feedback (closes the quality loop)
POST /v1/feedback
  Body: { queryId, entityId, label: "MATCH"|"NON_MATCH"|"UNSURE", reviewer }
```

**Idempotency:** mutations take `Idempotency-Key`. Search is read-only; `queryId` is server-generated for correlation.

---

## 5. Matching Pipeline (the core)

```
                    ┌──────────────┐
   raw query name → │  Normalizer  │  unicode NFKC, casefold, strip punct,
                    │              │  expand initials, drop honorifics,
                    └──────┬───────┘  language-specific rules
                           │
                           ▼
                    ┌──────────────┐
                    │  Featurizer  │  tokens, sorted tokens, bigrams,
                    │              │  phonetic keys, scripts, gender hints
                    └──────┬───────┘
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
        Phonetic idx   Token idx    N-gram / trigram idx
        (Double Meta)  (inverted)   (Elasticsearch / custom)
              │            │            │
              └────────────┼────────────┘
                           ▼
                  Candidate union (≤200)
                           │
                           ▼
                  ┌──────────────────┐
                  │  Pair scorer     │  Jaro-Winkler, token Jaccard,
                  │  (multi-signal)  │  phonetic equal, initial match,
                  └────────┬─────────┘  DOB/country soft features
                           │
                           ▼
                  ┌──────────────────┐
                  │  Calibrator      │  score → decision band per profile
                  │  + Explainer     │
                  └────────┬─────────┘
                           │
                           ▼
                     Ranked results + audit event
```

### Normalizer rules (v1 checklist)

| Rule | Example |
|---|---|
| Unicode NFKC + casefold | `İstanbul` → comparable form |
| Strip punctuation / extra spaces | `O'Brien` → `obrien` *and* keep `o brien` variant |
| Honorific / suffix drop (locale list) | `Mr.`, `Dr.`, `Jr.`, `III`, `Ltd.`, `Pvt Ltd` |
| Initial expansion optional | `J. K. Rowling` ↔ `Joanne Kathleen Rowling` (feature, not hard rewrite) |
| Transliteration map | `राज` → `raj` (Indic pack); keep original script as parallel field |
| Org legal-form normalization | `Inc`, `Incorporated`, `LLC` → canonical tags removed from core tokens |
| Alias explosion | Store `William`/`Bill`, `Mohammad`/`Muhammad`/`Mohamed` via nickname dictionary |

**Staff rule:** normalize for *indexing and comparison*, but **persist the original** for audit and display. Never irreversibly destroy information in the source-of-record.

---

## 6. Algorithms — What to Use When

Do **not** pick one distance metric. Compose signals.

| Signal | Algorithm | Catches | Misses |
|---|---|---|---|
| Exact / normalized equality | String equality after normalize | Clean duplicates | Typos, reordering |
| Edit similarity | **Jaro-Winkler** (prefer over raw Levenshtein for names) | Typos, transpositions; boosts common prefix | Token reorder (`Smith John` vs `John Smith`) |
| Token set | **Jaccard / Dice** on token sets; **token sort ratio** | Word reorder, missing middle name | Phonetic variants |
| Phonetic | **Double Metaphone** (primary+alt); optionally Beider-Morse for European | `Smith`/`Smyth`, `Catherine`/`Katherine` | Cross-language, short names (high collision) |
| Initials | Alignment of given-name initials | `J Smith` ↔ `John Smith` | Over-matches without other signals |
| Nickname / alias | Dictionary + optional embedding ANN | `Bill`/`William`, org trade styles | Novel nicknames |
| Secondary attrs | DOB exact/near, country, national ID hash | Disambiguates common names | Missing attributes |

### Recommended composite (person)

```
score =
  w1 * jaro_winkler(full_normalized)
+ w2 * token_sort_similarity
+ w3 * phonetic_match   # 1.0 / 0.5 / 0.0 for both/primary/none
+ w4 * given_family_alignment
+ w5 * nickname_boost
+ w6 * dob_country_soft  # 0 if absent — never hard-require
```

Weights live in a **matching profile** (`kyc_strict`, `payee_lenient`, …), versioned and hot-reloadable — not hardcoded.

### Org-specific additions

- Legal-form strip before score
- Acronym feature: `International Business Machines` ↔ `IBM`
- Trade-style / DBA alias list heavily weighted

### What not to do

- Pure Soundex as sole key — too coarse (English-biased, high collisions).
- Raw Levenshtein on full strings for long org names — quadratic and order-sensitive; use token-level.
- Embedding-only matching — great for aliases, weak for exact legal identity; use as *recall* aid, not sole scorer for compliance.

---

## 6b. Which AI Model to Use (and when)

**Short answer:** for “are these two names the same person/org?”, **do not use a general LLM as the primary matcher**. Use classical signals first; add a **small fine-tuned pairwise classifier** or **multilingual embedding** only where classical methods miss (aliases, transliteration). Keep GPT/Claude for review assist, not the decision.

### Decision matrix

| Job | Recommended model / approach | Latency | Use as |
|---|---|---|---|
| Primary same/not-same score | **No neural net** — Jaro-Winkler + token + Double Metaphone + nickname dict | µs–ms | Decision + explain |
| Pairwise “same entity?” when you have labels | **Fine-tuned MiniLM / DeBERTa-v3-small cross-encoder** (binary MATCH/NON_MATCH) | ~5–20ms / pair on CPU/GPU | Rerank top candidates or replace composite weights |
| Alias / transliteration recall | **`BAAI/bge-m3`** or **`intfloat/multilingual-e5-base`** bi-encoder + ANN | ~10ms embed + ANN | Candidate generation only |
| English-only, cheapest embed | **`sentence-transformers/all-MiniLM-L6-v2`** (384-d) | very cheap | Alias recall if corpus is Latin |
| Nickname / soft alias without training | Dictionary (`Bill`↔`William`) — not a model | free | Feature boost |
| Hard REVIEW-queue assist | LLM (**Claude / GPT-4-class**) with structured output | 500ms–2s | Suggestion to human only |
| Production KYC auto-decision | **Never LLM alone** | — | Fail compliance / audit |

### Why not “just use GPT/Claude”?

| Requirement | Classical / small ML | LLM |
|---|---|---|
| Deterministic replay for audit | Yes (same inputs → same score) | No (unless temp=0 + pinned prompt/model — still fragile) |
| P99 ≤ 80ms at 1K QPS | Yes | No (cost + latency) |
| Explainability (“phonetic matched”) | Explicit signals | Opaque prose |
| Calibrated thresholds per use case | Natural | Awkward |
| Cross-script aliases | Needs packs / embeds | Often good — use only as *assist* |

### Recommended stack (practical)

```
Layer 1 (always):  normalize + JW + tokens + phonetic + nickname dict
                   → enough for ~80–90% of clear MATCH / NO_MATCH

Layer 2 (optional): fine-tuned cross-encoder on labeled pairs
                   Model: cross-encoder style MiniLM or DeBERTa-v3-small
                   Train on: (name_a, name_b, label) from your locale + feedback
                   Apply to: top-50 classical candidates only

Layer 3 (optional): bge-m3 / multilingual-e5 bi-encoder for ANN recall
                   When: Indic/Arabic/CJK romanization gaps, org trade names
                   Never: sole AUTO_MATCH signal for sanctions

Layer 4 (human path): LLM explains REVIEW cases to ops
                   Output: {likely_match: bool, rationale, confidence}
                   Human still clicks MATCH / NON_MATCH
```

### If you fine-tune one model, fine-tune this

**Cross-encoder binary classifier** on your labeled pairs beats a generic LLM for same/not-same:

- Base: `microsoft/deberta-v3-small` or `sentence-transformers` cross-encoder MiniLM
- Input: `[CLS] name_a [SEP] name_b`
- Label: MATCH / NON_MATCH (optionally soft labels from reviewer confidence)
- Serve: ONNX / TorchScript next to Match Service; score only candidates from blocking
- Version: `name-xenc@v4` stamped on every audit row like a matching profile

Bi-encoders (`bge-m3`, `e5`) are for **retrieval** (find candidates). Cross-encoders are for **comparison** (are these two the same). Do not confuse the two.

### Models to avoid as the main matcher

| Choice | Problem |
|---|---|
| GPT-4 / Claude as online scorer | Cost, latency, non-determinism, weak audit story |
| Giant general embedding only (no classical) | Misses exact legal-name typos regulators care about; hard to explain |
| Soundex-only “AI” | Not AI; too coarse |
| Face/voice models | Different modality — optional fusion later, not name matching |

### One-liner for interviews / design docs

> “Same/not-same is a **calibrated pairwise decision**. Classical features decide most cases; a **fine-tuned MiniLM/DeBERTa cross-encoder** reranks ambiguous pairs; **bge-m3** only expands recall; **LLMs never auto-decide** identity.”

---

## 6c. Train Your Own Model (what to own)

If the requirement is **“we must own and train our model”**, train **one primary model**: a **pairwise cross-encoder** that outputs P(same entity). Optionally train a second **bi-encoder** later for recall. Do **not** train an LLM from scratch for this.

### Own this (primary)

| | Recommendation |
|---|---|
| **Architecture** | **Cross-encoder** (both names in one forward pass) |
| **Base checkpoint** | **`microsoft/deberta-v3-base`** if you have GPU serving and ≥50K labeled pairs; **`microsoft/deberta-v3-small`** or **`sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2`** adapted as cross-encoder if you need CPU / multilingual cheap |
| **Default pick for most teams** | **`microsoft/deberta-v3-small`** fine-tuned as binary classifier — best size/quality/latency balance to *own* |
| **Multilingual / Indic-heavy corpus** | Start from **`microsoft/mdeberta-v3-base`** (or MiniLM multilingual) instead of English-only DeBERTa |
| **Head** | Linear → 2 logits (`MATCH`, `NON_MATCH`) or 1 logit + sigmoid; use **temperature-scaled probability** as score |
| **Input** | `[CLS] {normalized_name_a} [SEP] {normalized_name_b}` (optionally append `\| dob=... \| country=...` as side features in text) |
| **Output you own** | Versioned artifact `name-xenc@{semver}` + calibration file + eval report |

**Why cross-encoder, not bi-encoder, as the owned “same or not” model:**  
Same/not-same is a **comparison**. Cross-encoders attend across both strings and beat embedding cosine for pairwise identity. Bi-encoders cannot see interactions like token reorder + typo in one shot as well.

### Own later (optional second model)

| | Recommendation |
|---|---|
| **Architecture** | **Bi-encoder** (embed once per name, ANN search) |
| **Base** | **`BAAI/bge-m3`** or **`intfloat/multilingual-e5-base`**, contrastive fine-tune on hard negatives from your blockers |
| **Role** | Candidate recall only — never the sole AUTO_MATCH decision |
| **When** | Classical blocking misses transliterations / org aliases at scale |

### Do not train / own for v1

| Skip | Why |
|---|---|
| Train LLM (7B+) for match | Overkill, slow, hard to calibrate; fine-tune small encoder instead |
| Train from random init | You need tens of millions of pairs; start from pretrained |
| Siamese with frozen BERT + tiny MLP only | Weaker than full cross-encoder fine-tune |
| One embedding model as both retrieve + decide | Compromises both jobs |

### Training data you must build

```text
name_a, name_b, label, locale, entity_type, source
"Jon Smith", "John Smith", MATCH, en, PERSON, typo_synth
"William Gates", "Bill Gates", MATCH, en, PERSON, nickname
"Raj Patel", "Rajesh Patel", MATCH, en-IN, PERSON, review_feedback
"Acme Inc", "Acme Incorporated", MATCH, en, ORG, legal_form
"John Smith", "John Smyth", NON_MATCH, en, PERSON, hard_negative  # different DOB in attrs
```

**Targets:**

- ≥ **20K–50K** labeled pairs to beat classical alone; **100K+** to stabilize multilingual
- **Hard negatives** from same blocking key (same phonetic, different person) — critical
- Balance MATCH / NON_MATCH ≈ 1:2 to 1:5 (real traffic is match-rare); use class weights or focal loss
- Hold out **by locale and by time** (no leakage from future feedback)
- Synthetic: controlled typos, initialisms, legal-form variants — then mix with real reviewer labels

### Training recipe (concrete)

```text
1. Normalize both sides with YOUR production normalizer (same code path).
2. Fine-tune DeBERTa-v3-small, 2–4 epochs, lr ~1e-5..3e-5, batch 32–64.
3. Max length 64–128 tokens (names are short — don’t waste 512).
4. Loss: cross-entropy (+ optional pairwise margin on hard negatives).
5. After train: fit temperature scaling / Platt scaling on validation → calibrated P(match).
6. Export ONNX (or TorchScript); pin checksum in Match Service config.
7. Gate promote on: PR-AUC, recall@AUTO threshold, FP rate on sanctions slice.
```

**Libraries:** Hugging Face `transformers` + `datasets`; export with Optimum/ONNX Runtime. Optional: `sentence-transformers` `CrossEncoder` trainer if you prefer that API.

### How it sits in the service you own

```text
blocking (classical) → ≤50 candidates
        → name-xenc@v1  →  P(match)
        → thresholds from matching profile
        → AUTO_MATCH | REVIEW | NO_MATCH
```

Classical features can remain as **features concatenated** or as a **fallback** when model confidence is in the middle band — many teams keep a blended score:

`final = α * P_model + (1-α) * classical_score` with α tuned on validation (start α=0.7).

### Serving the model you own

| Concern | Practice |
|---|---|
| Runtime | ONNX Runtime in Match Service sidecar or in-process |
| Hardware | CPU enough for small/DeBERTa-small at ≤50 pairs/query; GPU if QPS high |
| Versioning | `model_id` + checksum on every `match_audit` row |
| Rollback | Keep previous ONNX on disk; flip profile pointer |
| Drift | Weekly eval on new feedback; retrain when PR-AUC drops or locale mix shifts |

### Staff one-liner

> “We own **`name-xenc`**: a fine-tuned **DeBERTa-v3-small cross-encoder** on our labeled name pairs, calibrated, ONNX-served, versioned. Optional **bge-m3 bi-encoder** later for recall. We do not train an LLM for identity.”

---

## 6d. Your Product Shape: Pairwise Match + Confidence

This is the core contract you described.

### Request

```json
{
  "name_a": "Jon Smith",
  "name_b": "John Smith",
  "entity_type": "INDIVIDUAL"   // or "ORG"
}
```

### Response

```json
{
  "match": true,
  "confidence": 0.93,
  "decision": "AUTO_MATCH",
  "entity_type": "INDIVIDUAL",
  "model_id": "name-xenc@v1",
  "signals": [
    { "name": "model_prob", "value": 0.91 },
    { "name": "jaro_winkler", "value": 0.97 },
    { "name": "phonetic", "value": 1.0 }
  ]
}
```

| Field | Meaning |
|---|---|
| `confidence` | Calibrated P(same entity) in **[0, 1]** — same scale for INDIVIDUAL and ORG |
| `match` | Convenience boolean: `confidence >= threshold(entity_type, profile)` |
| `decision` | `AUTO_MATCH` / `REVIEW` / `NO_MATCH` from thresholds (keep even if UI only shows score) |

### End-to-end for this API (no corpus search required)

```text
name_a, name_b, entity_type
        │
        ▼
  normalize (type-specific: honorifics vs Inc/Ltd)
        │
        ▼
  classical features (JW, tokens, phonetic, nickname/legal-form)
        │
        ▼
  name-xenc@v1 cross-encoder
  input: [CLS] name_a [SEP] name_b
  (+ optional type token: [CLS] INDIVIDUAL [SEP] name_a [SEP] name_b)
        │
        ▼
  confidence = calibrated P(MATCH)
  decision   = apply thresholds for INDIVIDUAL vs ORG profiles
```

**No blocking index needed** for pure pairwise — both strings are already provided. Blocking/ES only matter when screening one name against a database.

### Model input trick for org vs individual

Train **one** model with type in the text (preferred) or **two** heads/profiles:

```text
[CLS] INDIVIDUAL [SEP] jon smith [SEP] john smith
[CLS] ORG [SEP] acme inc [SEP] acme incorporated
```

Same checkpoint `name-xenc`; type-conditioned behavior without maintaining two full models. Thresholds still differ:

| entity_type | AUTO_MATCH if confidence ≥ | REVIEW band |
|---|---|---|
| INDIVIDUAL | 0.94 (example) | 0.75–0.94 |
| ORG | 0.92 (example) | 0.70–0.92 |

Tune on your labeled set — numbers above are starting points.

### Minimal training rows for this API

```text
name_a, name_b, entity_type, label
"Jon Smith", "John Smith", INDIVIDUAL, MATCH
"J Smith", "John Smith", INDIVIDUAL, MATCH
"John Smith", "John Smyth", INDIVIDUAL, NON_MATCH
"Acme Inc", "Acme Incorporated", ORG, MATCH
"Acme Inc", "Acme LLC", ORG, NON_MATCH   # if different legal entities in your policy
```

### What you ship

1. Owned model: **DeBERTa-v3-small cross-encoder** → `confidence`
2. API: `POST /v1/match:compare` with `{name_a, name_b, entity_type}`
3. Classical features as backup / explanation signals
4. Separate threshold profiles for `INDIVIDUAL` vs `ORG`

---

## 7. Indexing & Candidate Generation

### Blocking keys (online + batch)

Each entity emits multiple blocking keys; query emits the same; **union** of postings = candidates.

| Block key | Example | Purpose |
|---|---|---|
| `phon:SM0` / Double Metaphone primary | Smith family | Phonetic recall |
| `tok:smith` | rare tokens | Exact token hits |
| `pref:smi` (3–4 char prefix) | typed search | Prefix / autocomplete-ish |
| `tri:smi`, `tri:mit`, … | trigrams | Typo recall (Elasticsearch-style) |
| `dob:1990-04` + `phon:…` | compound block | Batch dedup precision |
| `meta:acronym:ibm` | orgs | Acronym blocking |

**Cap candidates at ~200.** If a block is too hot (`phon` of a common Asian surname), **AND** with another key (country, DOB year, second token) or fall back to rarer tokens first.

### Storage choices

| Store | Role | Why |
|---|---|---|
| **OpenSearch / Elasticsearch** | Token, trigram, phonetic fields, filters | Fast inverted candidate gen, percolator optional |
| **Redis** | Hot phonetic/token posting lists for ultra-low latency watchlists | Sub-ms; size-limited |
| **Postgres** | Entity source of truth, profiles, audit, feedback labels | Relational integrity |
| **Kafka** | Entity change log → index updater | Replay, fan-out to search + cache |
| **S3 + Spark/Flink** | Batch dedup & backfill | Cheap large joins |
| **Optional: vector DB / ES dense_vector** | Nickname / transliteration recall | Secondary path |

### Online query path latency budget

```
Normalize + featurize:     2ms
ES multi-field candidate: 15ms
Fetch entity payloads:     5ms
Score ≤200 pairs:         15ms
Calibrate + explain:       3ms
Network / gateway:        10ms
----------------------------
Total ~50ms  →  headroom to 80ms P99
```

---

## 8. Scoring, Thresholds & Decision Policy

Never return only a raw float to compliance users. Map through a **profile**:

| Band | Typical range (example) | Action |
|---|---|---|
| `AUTO_MATCH` | ≥ 0.92 (dedup) / ≥ 0.97 (payee credit) | System proceeds |
| `REVIEW` | 0.75–0.92 | Human / secondary check |
| `NO_MATCH` | < 0.75 | Continue as distinct |

**Sanctions screening inverts the bias:** lower threshold to enter `REVIEW` (e.g. ≥ 0.70), almost never `AUTO_MATCH` without secondary identifiers — **recall over precision**.

### Calibration

1. Build a labeled set (true pairs / false pairs) per locale and use case.
2. Fit thresholds on precision-recall / cost-weighted utility (FP cost ≠ FN cost).
3. Version profiles: `sanctions_screening@v3`.
4. Canary new weights on shadow traffic before cutover.

### Explainability (required for staff bar)

Each result includes top contributing signals:

```json
{
  "score": 0.91,
  "decision": "REVIEW",
  "signals": [
    { "name": "jaro_winkler", "value": 0.96, "weight": 0.35 },
    { "name": "phonetic", "value": 1.0, "weight": 0.20 },
    { "name": "token_jaccard", "value": 0.75, "weight": 0.25 },
    { "name": "dob", "value": 0.0, "weight": 0.10, "note": "missing" }
  ],
  "explanation": "High string + phonetic similarity; missing DOB prevented AUTO_MATCH under kyc_strict@v3"
}
```

---

## 9. Data Model

```sql
-- Source of truth
CREATE TABLE entities (
  tenant_id     TEXT NOT NULL,
  index_id      TEXT NOT NULL,
  entity_id     TEXT NOT NULL,
  entity_type   TEXT NOT NULL,  -- PERSON | ORG
  primary_name  TEXT NOT NULL,  -- original display
  name_fields   JSONB NOT NULL, -- given, family, aliases[], scripts
  attributes    JSONB,          -- dob, country, ids (hashed)
  version       BIGINT NOT NULL,
  updated_at    TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, index_id, entity_id)
);

CREATE TABLE match_profiles (
  profile_id    TEXT PRIMARY KEY,
  version       INT NOT NULL,
  weights       JSONB NOT NULL,
  thresholds    JSONB NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL
);

CREATE TABLE match_audit (
  query_id      TEXT PRIMARY KEY,
  tenant_id     TEXT,
  index_id      TEXT,
  request       JSONB NOT NULL,
  response      JSONB NOT NULL,
  profile_id    TEXT NOT NULL,
  profile_version INT NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL
);

CREATE TABLE match_feedback (
  query_id      TEXT NOT NULL,
  entity_id     TEXT NOT NULL,
  label         TEXT NOT NULL,  -- MATCH | NON_MATCH | UNSURE
  reviewer      TEXT NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (query_id, entity_id, created_at)
);
```

Index documents (ES) store: `entity_id`, `type`, normalized fields, phonetic keys, trigrams, filterable attrs, `version` for optimistic delete.

---

## 10. Architecture Diagram

```
┌─────────────┐     ┌──────────────┐     ┌─────────────────────────────┐
│  KYC / Pay  │────▶│  API Gateway │────▶│  Match Service (stateless)  │
│  / CRM apps │     │  auth, quota │     │  normalize → candidates →   │
└─────────────┘     └──────────────┘     │  score → calibrate          │
                                         └───────┬───────────┬─────────┘
                                                 │           │
                                    ┌────────────▼──┐   ┌────▼─────────┐
                                    │ OpenSearch    │   │ Postgres     │
                                    │ candidate idx │   │ entities,    │
                                    └───────────────┘   │ profiles,    │
                                                        │ audit        │
┌─────────────┐     ┌──────────────┐                    └──────────────┘
│ Admin / ETL │────▶│ Entity Writer│──▶ Kafka entity.cdc ──┤
└─────────────┘     └──────────────┘         │             │
                                             ▼             ▼
                                      Index Updater   Redis watchlist
                                             │         (optional hot)
                                             ▼
                                      OpenSearch / Redis

┌─────────────┐     ┌──────────────┐     ┌─────────────────────────────┐
│ Airflow     │────▶│ Dedup job    │────▶│ Pair scores → cluster links │
│ scheduler   │     │ Spark/Flink  │     │ (Union-Find) → merge review │
└─────────────┘     └──────────────┘     └─────────────────────────────┘
```

---

## 11. Hard Problems (and how you solve them)

### 11.1 Blocking explosion / hot keys

Common surnames (`Lee`, `Patel`, `Zhang`) produce huge postings.

**Fix:** compound blocks; rarity-first token selection; per-block candidate caps; bloom prefilter; for batch, canopy clustering or sorted-neighborhood as backup.

### 11.2 Cross-script and transliteration

`Путин` vs `Putin`; `محمد` vs `Muhammad`.

**Fix:** store original + romanized fields; transliteration libraries per script pack; phonetic on romanized form; optional multilingual embedding recall. Ship script packs incrementally.

### 11.3 Short names and initials

`J Li` vs millions of candidates — phonetic useless, edit distance saturated.

**Fix:** require secondary attributes for AUTO_MATCH; down-weight pure-initial matches; raise REVIEW band.

### 11.4 Org acronyms and legal noise

`Meta Platforms, Inc.` vs `Facebook` (alias) vs `META INC`.

**Fix:** legal-form dictionary; explicit alias graph (admin-curated + learned); acronym feature; do not auto-merge without alias evidence.

### 11.5 Score drift across locales

A 0.85 in English Korean-romanization traffic ≠ 0.85 in Spanish.

**Fix:** per-locale or per-tenant calibration; never share one global threshold blindly; monitor precision/recall by segment.

### 11.6 Online/offline parity

Batch dedup and online screening disagree → trust collapse.

**Fix:** **one scorer library** (versioned artifact) used by Match Service and Spark UDF/JNI/sidecar. Profile version stamped on every decision.

### 11.7 Sanctions list freshness

Missed update = regulatory incident.

**Fix:** CDC from list provider → Kafka → index within SLA; synthetic probe queries in canary; freshness metric `max(list_version - index_version)`.

### 11.8 Privacy

Names + DOB are PII.

**Fix:** encrypt at rest, tokenize national IDs, tenant isolation, audit access, retention limits on `match_audit`, no logs of raw secondary IDs at INFO.

---

## 12. Evaluation & Continuous Quality

### Offline

| Metric | Use |
|---|---|
| Precision@K / Recall@K | Screening quality |
| Pairwise F1 / PR-AUC | Scorer quality |
| Cost-weighted utility | `U = -c_fp·FP - c_fn·FN` — staff framing |
| Slice metrics | By locale, name length, entity type |

Golden sets: hand-labeled + production feedback (with bias caveats).

### Online

- Shadow mode: new profile scores in parallel, no decision change
- Review-queue agreement rate
- Auto-match overturn rate (should be ~0)
- Latency histograms + candidate-set size (catch blocking regressions)

### Feedback loop

Reviewer labels → `match_feedback` → weekly weight/threshold retrain or manual profile bump → canary → promote.

---

## 13. Failure Modes & Ops

| Failure | Detection | Response |
|---|---|---|
| OpenSearch latency spike | P99 candidate_gen > 40ms | Serve REVIEW-biased degraded mode on Redis watchlist subset; page |
| Index lag > freshness SLA | `index_lag_seconds` | Block AUTO_MATCH for screening; alert compliance eng |
| Scorer bug ships | Overturn rate / golden-set CI | Rollback profile version; scorer lib is version-pinned |
| Hot tenant QPS | Per-tenant rate limit | Shed batch-priority traffic; preserve screening |
| Kafka CDC backlog | Consumer lag | Scale index updaters; pause non-critical bulk loads |

**SLOs (example):** availability 99.9%; P99 match latency 80ms; sanctions index freshness P99 ≤ 5s; audit durability 100% (sync write).

---

## 14. Rollout Plan

1. **Library first** — normalizer + scorer + unit tests on golden pairs (person EN).
2. **Pairwise API** — no index; products integrate compare.
3. **Watchlist index** — small sanctions / PEP lists; screening profile.
4. **Customer corpus** — ES indexing + search API; start one tenant.
5. **Review UI integration** — feedback events.
6. **Batch dedup** — Spark job using same scorer; merge suggestions, not silent merges.
7. **Locale packs** — Indic, Arabic, CJK romanization.
8. **Calibration v2** — production labels; cost-weighted thresholds.

---

## 15. What You're Deliberately NOT Building

| Out of scope (v1) | Why |
|---|---|
| Full entity resolution graph DB (everything connected) | Matching ≠ graph ER; link clusters can come later |
| LLM-only matcher | Non-deterministic, hard to audit, expensive; OK as assistive signal later |
| Silent automatic customer merges | Too dangerous; always suggestion + human for high impact |
| Real-time face/biometric fusion | Different system; may *consume* name scores |
| Perfect global nickname coverage | Dictionary grows with traffic; ship core EN + tenant overrides |

---

## 16. Interview Script (45 min)

**0–5 min — Scope**  
Restate: online screening + pairwise + optional batch dedup; person+org; explainable scores; P99 80ms; 50M entities.

**5–15 min — Pipeline**  
Draw normalize → block → score → calibrate. Emphasize blocking as the scalability hinge. Name 3–4 signals (Jaro-Winkler, token, phonetic, DOB soft).

**15–25 min — Index & API**  
ES/OpenSearch for candidates, Postgres for SoT + audit, Kafka CDC. Show search + compare APIs and decision bands.

**25–35 min — Hard parts**  
Hot blocks, cross-script, online/offline parity, sanctions freshness, FP/FN cost asymmetry.

**35–45 min — Eval & tradeoffs**  
Golden set, cost-weighted thresholds, profile versioning, what you won't build.

### Phrases that signal staff level

- "Candidate generation dominates cost; the distance function is secondary."
- "False-negative cost for sanctions is not the same as false-positive cost for payee match — different profiles."
- "One scorer artifact for online and batch so decisions don't diverge."
- "We return reasons, not just a boolean."
- "Thresholds are calibrated per locale/use case, not magic constants."

### Red flags

- Only Levenshtein over the full corpus
- Boolean match with no score/threshold story
- Ignoring Unicode / non-English names
- No audit trail for compliance use cases
- Auto-merging records without a review band

---

## 17. Trade-off Q&A

**Q: Why Jaro-Winkler over Levenshtein?**  
A: Names share prefixes often; JW boosts common prefixes and handles transpositions better for short strings. Still combine with token metrics for reorder.

**Q: Why not only embeddings?**  
A: Great for fuzzy aliases; weak when regulators need deterministic, explainable similarity on legal names. Use as recall, not sole decision.

**Q: Postgres full-text enough?**  
A: For <1M and Latin-only maybe. At 50M with phonetic/trigram/typo recall, purpose-built inverted indexes (ES) win. Postgres remains system of record.

**Q: How do you prevent two `John Smith`s from auto-merging?**  
A: Secondary attributes + high AUTO_MATCH bar + REVIEW band; common-name prior that *lowers* confidence when tokens are frequent.

**Q: Batch clustering algorithm?**  
A: Score candidate pairs above threshold → Union-Find (disjoint set) → connected components as merge suggestions. Don't transitively merge weak edges without checking path confidence (optional: require each edge ≥ threshold).

**Q: Multi-tenant isolation?**  
A: `tenant_id` on every key; separate ES indices or aliases per tenant for noisy neighbors; quota at gateway.

---

## Appendix A — Minimal scorer sketch (Kotlin/Java-shaped)

```text
fun score(q: NormalizedName, e: NormalizedName, w: Weights): Double {
  val jw   = jaroWinkler(q.full, e.full)
  val tok  = tokenSortRatio(q.tokens, e.tokens)
  val pho  = phoneticScore(q.doubleMetaphone, e.doubleMetaphone)
  val nick = nicknameBoost(q, e)
  val soft = attributeSoft(q.attrs, e.attrs) // 0 if missing
  return w.jw*jw + w.tok*tok + w.pho*pho + w.nick*nick + w.soft*soft
}
```

Keep this pure and dependency-free so Spark and the API service share a jar.

---

## Appendix B — Matching profile example

```yaml
id: sanctions_screening
version: 3
weights:
  jaro_winkler: 0.30
  token_sort: 0.25
  phonetic: 0.25
  nickname: 0.10
  attributes: 0.10
thresholds:
  review: 0.70      # wide net
  auto_match: 0.99  # essentially unused without strong secondary IDs
policy:
  missing_dob: stay_in_review
  hot_block_max_candidates: 200
```

```yaml
id: customer_dedup
version: 2
weights:
  jaro_winkler: 0.35
  token_sort: 0.30
  phonetic: 0.15
  nickname: 0.10
  attributes: 0.10
thresholds:
  review: 0.80
  auto_match: 0.94
policy:
  require_attribute_for_auto: dob_or_national_id
```

---

## Appendix C — Staff implementation checklist

- [ ] Written normalizer with golden Unicode tests
- [ ] Multi-signal scorer as a versioned library
- [ ] Blocking indexes (phonetic + token + trigram)
- [ ] Decision profiles with calibrated thresholds
- [ ] Explainable API responses
- [ ] Immutable match audit
- [ ] Feedback → eval pipeline
- [ ] Online/batch same scorer artifact
- [ ] Freshness SLO for watchlist indexes
- [ ] PII handling & tenant isolation
- [ ] Load test: candidate-set size + P99 latency
- [ ] Runbooks for index lag and hot blocks
