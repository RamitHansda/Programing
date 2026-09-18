# Reliable AI-Generated Journal Entries & Bank Reconciliation at Scale — Principal Engineer Design

> **The question:** "How would you design reliable AI-generated journal entries / bank reconciliation at scale?"
>
> **Why this is a distinct problem, not just "RAG for finance" or "reconciliation with an LLM bolted on":** Two hard-correctness domains collide here. Accounting has a zero-tolerance-for-drift invariant (`∑DEBIT == ∑CREDIT`, every dollar traceable, immutable history). LLMs are non-deterministic, can hallucinate account codes, and silently misclassify. The design problem is: **how do you get the coverage/speed benefits of AI without importing its non-determinism into the ledger.** The answer is architectural, not prompt-level: the AI never writes to the ledger directly — it only produces *proposals* that pass through deterministic gates before anything becomes an immutable journal entry.

---

## Table of Contents

1. [Problem Framing](#1-problem-framing)
2. [Requirements](#2-requirements)
3. [Non-Negotiable Principles](#3-non-negotiable-principles)
4. [System Architecture](#4-system-architecture)
5. [Data Model — Proposal Layer vs. Ledger Layer](#5-data-model)
6. [Pipeline Stage 1 — Ingestion & Normalization](#6-pipeline-stage-1)
7. [Pipeline Stage 2 — Bank Reconciliation Matching Engine](#7-pipeline-stage-2)
8. [Pipeline Stage 3 — AI-Assisted Categorization & Journal Entry Generation](#8-pipeline-stage-3)
9. [Pipeline Stage 4 — Deterministic Guardrails](#9-pipeline-stage-4)
10. [Pipeline Stage 5 — Confidence Gating & Auto-Post Decision](#10-pipeline-stage-5)
11. [Pipeline Stage 6 — Human-in-the-Loop Review](#11-pipeline-stage-6)
12. [Pipeline Stage 7 — Posting to the Immutable Ledger](#12-pipeline-stage-7)
13. [Continuous Evaluation & Drift Detection](#13-continuous-evaluation)
14. [Scalability Design](#14-scalability-design)
15. [Audit, Explainability & Compliance](#15-audit-explainability-compliance)
16. [Failure Modes & Mitigations](#16-failure-modes)
17. [Rollout Strategy](#17-rollout-strategy)
18. [Key Trade-offs](#18-key-trade-offs)
19. [Interview Q&A](#19-interview-qa)
20. [Summary](#20-summary)

---

## 1. Problem Framing

Before drawing boxes: what are we actually automating?

**Bank reconciliation** matches an external record (bank statement line, card processor settlement, payment gateway payout) against internal accounting records, and where no internal record exists yet, **creates one** — a journal entry, categorized to the correct account in the chart of accounts (COA), with the correct debit/credit split.

Historically this is done by a human bookkeeper reading a bank line like:

```
"AMAZON WEB SERVICES  -482.13  07/14"
```

and posting:

```
DEBIT  6120 · Software & Hosting     $482.13
CREDIT 1010 · Business Checking      $482.13
```

The AI's job is to do this at the volume a human cannot: thousands of accounts, tens of thousands of bank lines per day, arbitrary vendor description formats, multi-entity, multi-currency. The **risk** is that an LLM confidently miscategorizes a transaction, or worse, produces an entry that doesn't balance, and that entry becomes part of a client's financial statements or tax filing.

### The two invariants that must never depend on the LLM being right

1. **Every posted entry balances.** `∑DEBIT == ∑CREDIT`, always, verified deterministically — never trust the model's arithmetic.
2. **Every posted entry is traceable and reversible.** Which model version, which prompt, which evidence (bank line, historical pattern, rule) produced it, and who (human or system) authorized posting.

Everything else in this design — confidence scoring, HITL, guardrails, evals — exists in service of those two invariants while maximizing the % of volume that can be safely automated.

### The five questions to ask before designing

1. **What is the blast radius of a wrong journal entry?** A miscategorized $12 office-supplies charge is a rounding error on a P&L. A miscategorized $500K wire that should have hit a liability account instead of revenue can misstate financials materially and trigger audit findings. Materiality must gate automation, not just confidence.
2. **Who is accountable for the books?** In most products, a licensed accountant or the business owner remains legally responsible for the ledger. The system must never remove a human's ability to review before things become "final" for reporting periods that matter (period close, tax filing).
3. **What's the volume and latency SLA?** Reconciling 50K bank lines/day within the same business day is very different from reconciling 5M lines/day across thousands of client entities with a T+1 SLA.
4. **Is the chart of accounts fixed or per-tenant?** Multi-tenant accounting SaaS (each client has their own COA, own categorization habits) is a fundamentally different ML problem than a single company's fixed COA — you need per-tenant personalization, not a single global classifier.
5. **What's the correction/dispute workflow?** Bank data itself is sometimes wrong (duplicate feed delivery, delayed settlement, provisional holds). The system must distinguish "AI got it wrong" from "the source data was wrong."

---

## 2. Requirements

### Functional
- Ingest bank/card feeds (Plaid/Yodlee/Open Banking APIs, MT940/ISO 20022 files, direct bank SFTP) and internal transaction records.
- Deterministically match internal ↔ external records where an internal record already exists (classic reconciliation).
- For unmatched external lines (no internal record — the common case for SMB bookkeeping, e.g. a card swipe with no separate expense entry), **generate a proposed journal entry**: account classification, debit/credit split, tax treatment tag, vendor/memo normalization.
- Score every AI proposal with a calibrated confidence value.
- Auto-post only proposals above a **per-tenant, per-account-class** confidence threshold; route everything else to human review.
- Support human edit, approve, reject, and "teach the model" feedback on every proposal.
- Guarantee `∑DEBIT == ∑CREDIT` on every posted entry, enforced outside the AI.
- Full audit trail: model version, prompt/feature version, evidence used, confidence score, human action, timestamps.
- Support reversal of AI-posted entries via compensating entries (never edit/delete posted entries).
- Multi-tenant, multi-currency, multi-entity.

### Non-Functional

| Property | Target |
|---|---|
| Ledger consistency | Strong — no unbalanced entry ever reaches the ledger (enforced by DB constraint + service check) |
| AI proposal → posted latency (auto-post path) | P99 < 5s |
| AI proposal → human queue latency | < 30s from bank line ingestion |
| Reconciliation match rate (deterministic) | > 90% same-day |
| AI auto-post accuracy (post-hoc audited) | > 99.5% correct category on auto-posted entries |
| Auto-post coverage | Tenant/account-class dependent; start conservative (see §17) |
| Throughput | 5M bank lines/day sustained, burstable to 20M during month-end |
| Auditability | 7-year immutable retention (SOX/GAAP-aligned) |
| Data loss | RPO = 0 for posted ledger data |

### Out of Scope (v1)
- Fully autonomous filing/tax submission.
- AI-initiated fund movement (this system only classifies and books entries for money that has already moved).
- Cross-tenant model sharing of raw transaction text (privacy boundary — see §15).

---

## 3. Non-Negotiable Principles

| # | Principle | Consequence |
|---|---|---|
| **P1** | **The AI proposes, deterministic code disposes.** | No LLM output is ever written to the ledger table. It is written to a `journal_entry_proposals` table. A separate, non-AI posting service validates and commits. |
| **P2** | **Debits and credits are computed/validated deterministically, never trusted from the model.** | After the model emits a proposed account + amount, a rules engine computes the offsetting entry and asserts balance before the proposal is eligible for auto-post. |
| **P3** | **Confidence gates automation, not correctness.** | A confidence score doesn't mean "this is right" — it means "this is worth auto-posting given the tenant's risk tolerance." Materiality (dollar amount) and account sensitivity (e.g. equity, tax accounts) independently raise the bar regardless of model confidence. |
| **P4** | **Every proposal — accepted or rejected — is training signal.** | Human edits/rejections feed back into per-tenant personalization and global model evals. Silent correction with no feedback loop is a wasted signal and a repeat-failure risk. |
| **P5** | **Immutability + reversal, never edit.** | Mirrors classic ledger design (see companion doc `LEDGER_SYSTEM_HLD_STAFF_ENG.md`). A wrong AI-posted entry is corrected with a reversing entry, never mutated in place. |
| **P6** | **Blast radius is bounded by account class and materiality, not by overall model quality.** | A 98%-accurate model is still too risky to auto-post directly to equity/tax-liability accounts or above a dollar threshold — cap what AI can touch unsupervised regardless of its measured accuracy. |
| **P7** | **Everything is measured against a human-labeled golden set, continuously.** | "The model works" is not a launch-day claim; it's a continuously monitored SLA (accuracy, drift, per-category error rate). |

---

## 4. System Architecture

```
┌───────────────────────────────────────────────────────────────────────────────┐
│                              INGESTION LAYER                                  │
│   Bank Feeds (Plaid/Yodlee/Open Banking) │ Card Processor Settlements │ ERP   │
│   ──────────────────────────────────────────────────────────────────────────  │
│   Normalizer → Canonical Transaction Model (amount, currency, date, memo,     │
│   counterparty, source_ref) — idempotent on (source, source_ref)              │
└───────────────────────────────────────────┬───────────────────────────────────┘
                                             ▼
┌───────────────────────────────────────────────────────────────────────────────┐
│                    DETERMINISTIC RECONCILIATION MATCHER                       │
│   Exact match (ref ID) → Fuzzy match (amount±fee, date window) → Rule engine  │
│   ────────────────────────────────────┬──────────────────────────────────────  │
│           MATCHED (internal record exists) │  UNMATCHED (no internal record)  │
│                    │                        │                                  │
│                    ▼                        ▼                                  │
│         Reconcile & close             Route to AI Journal-Entry Generator      │
└────────────────────────────────────────────┼───────────────────────────────────┘
                                              ▼
┌───────────────────────────────────────────────────────────────────────────────┐
│                    AI JOURNAL ENTRY GENERATION PIPELINE                       │
│                                                                               │
│  Feature Builder ──► Retrieval (similar historical postings for this tenant, │
│  (memo, amount,        vendor-to-account history, COA embeddings)            │
│  counterparty,               │                                              │
│  MCC code, tenant COA)       ▼                                              │
│                      Classification + Generation                            │
│                      (fine-tuned small classifier for account code           │
│                       + constrained-decoding LLM for memo/description        │
│                       normalization and tax tag)                            │
│                               │                                              │
│                               ▼                                              │
│                      Confidence Calibration (isotonic/Platt scaling —        │
│                       raw model logit ≠ trustworthy probability)             │
└────────────────────────────────────────────┬──────────────────────────────────┘
                                              ▼
┌───────────────────────────────────────────────────────────────────────────────┐
│                       DETERMINISTIC GUARDRAIL LAYER                          │
│   • COA validity check (account code must exist & be active for tenant)      │
│   • Balance enforcement (compute offsetting entry, assert ∑DEBIT=∑CREDIT)    │
│   • Duplicate-posting check (idempotency key on source_ref)                  │
│   • Materiality check (dollar threshold per account class)                   │
│   • Restricted-account check (equity, tax, intercompany → always HITL)       │
│   • Anomaly check (amount/vendor pattern deviates from tenant history)       │
└────────────────────────────────────────────┬──────────────────────────────────┘
                                              ▼
                          ┌───────────────────┴────────────────────┐
                    Passed all gates                      Failed any gate
                    + confidence ≥ threshold               OR low confidence
                          │                                        │
                          ▼                                        ▼
              ┌───────────────────────┐              ┌─────────────────────────┐
              │   AUTO-POST PATH      │              │   HUMAN REVIEW QUEUE     │
              │   (still passes       │              │   (bookkeeper/accountant│
              │    through Posting    │              │    approve/edit/reject) │
              │    Service — P1)      │              └───────────┬─────────────┘
              └───────────┬───────────┘                          │
                          │                       Approved/edited│
                          ▼                                      ▼
              ┌─────────────────────────────────────────────────────────────┐
              │                    POSTING SERVICE                          │
              │   Re-validates balance, idempotency, COA — the ONLY writer  │
              │   to the immutable ledger. Same code path for AI and human. │
              └───────────────────────────┬───────────────────────────────────┘
                                          ▼
              ┌─────────────────────────────────────────────────────────────┐
              │              IMMUTABLE LEDGER (journal_entries)             │
              │        Append-only. Reversal-only correction model.        │
              └───────────────────────────┬───────────────────────────────────┘
                                          ▼
              ┌─────────────────────────────────────────────────────────────┐
              │     AUDIT LOG + EVAL PIPELINE + FEEDBACK LOOP               │
              │  Every proposal (posted or not) + human action + model     │
              │  version + confidence → nightly eval against golden set,   │
              │  per-tenant accuracy dashboards, drift alerts              │
              └─────────────────────────────────────────────────────────────┘
```

**The single most important line in this diagram:** the AI Journal Entry Generation Pipeline and the Human Review Queue both feed into the *same* Posting Service. There is no code path where an AI proposal is written to `journal_entries` directly — auto-post is just "the human review step is skipped because deterministic gates + confidence already cleared it," not "the AI writes to the ledger."

---

## 5. Data Model

The key design decision: **separate the mutable proposal/workflow layer from the immutable ledger layer**, exactly the same separation used in the companion `LEDGER_SYSTEM_HLD_STAFF_ENG.md` design between "things that can be revised" and "the append-only source of truth."

```sql
-- Canonical external transaction (from bank/processor feed)
CREATE TABLE external_transactions (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL,
    source          VARCHAR NOT NULL,          -- PLAID | BANK_SFTP | STRIPE | ...
    source_ref      VARCHAR NOT NULL,          -- dedupe key
    amount          BIGINT NOT NULL,           -- minor units, signed
    currency        CHAR(3) NOT NULL,
    txn_date        DATE NOT NULL,
    raw_memo        TEXT,
    counterparty    VARCHAR,
    mcc_code        VARCHAR,
    account_id      UUID NOT NULL,             -- bank account this line belongs to
    reconciled      BOOLEAN NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, source, source_ref)
);

-- Mutable AI proposal — the model's output BEFORE anything is trusted
CREATE TABLE journal_entry_proposals (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL,
    external_txn_id     UUID NOT NULL REFERENCES external_transactions(id),
    proposed_lines      JSONB NOT NULL,        -- [{account_code, direction, amount}], must self-balance
    normalized_memo     TEXT,
    tax_tag             VARCHAR,
    model_version       VARCHAR NOT NULL,
    feature_version     VARCHAR NOT NULL,
    raw_confidence      FLOAT NOT NULL,
    calibrated_confidence FLOAT NOT NULL,
    evidence            JSONB,                 -- similar historical postings used, rule ids matched
    guardrail_results   JSONB NOT NULL,        -- per-gate pass/fail, e.g. {"balance_check": "PASS", "materiality": "FAIL"}
    status              VARCHAR NOT NULL,      -- PENDING_REVIEW | AUTO_POST_ELIGIBLE | POSTED | REJECTED | EDITED_AND_POSTED
    reviewed_by         VARCHAR,               -- user_id or 'system'
    reviewed_at         TIMESTAMPTZ,
    posted_entry_id     UUID,                  -- FK to journal_entries once posted
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Immutable ledger — identical contract to any hand-entered journal entry
-- (see LEDGER_SYSTEM_HLD_STAFF_ENG.md §4 for the full accounts/journal_entries schema)
CREATE TABLE journal_entries (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL,
    idempotency_key     VARCHAR UNIQUE NOT NULL,   -- = external_txn source_ref for AI-originated entries
    origin              VARCHAR NOT NULL,          -- HUMAN | AI_AUTO | AI_REVIEWED
    origin_proposal_id  UUID REFERENCES journal_entry_proposals(id),
    status              VARCHAR NOT NULL DEFAULT 'POSTED',  -- POSTED | REVERSED
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
    -- append-only, no UPDATE/DELETE grants at the DB role level
);

CREATE TABLE journal_lines (
    id              UUID PRIMARY KEY,
    entry_id        UUID NOT NULL REFERENCES journal_entries(id),
    account_code    VARCHAR NOT NULL,
    direction       VARCHAR NOT NULL,   -- DEBIT | CREDIT
    amount          BIGINT NOT NULL,    -- minor units, never floats
    currency        CHAR(3) NOT NULL
);

-- Immutable audit trail, distinct from the proposal's mutable workflow state
CREATE TABLE ai_decision_audit_log (
    id                  UUID PRIMARY KEY,
    proposal_id         UUID NOT NULL REFERENCES journal_entry_proposals(id),
    event_type          VARCHAR NOT NULL,   -- PROPOSED | GUARDRAIL_EVAL | AUTO_POSTED | HUMAN_APPROVED |
                                             -- HUMAN_EDITED | HUMAN_REJECTED | REVERSED
    actor               VARCHAR NOT NULL,   -- system | user_id
    before_state        JSONB,
    after_state         JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
    -- append-only
);
```

**Why the split matters:** `journal_entry_proposals` can be revised, re-scored, and re-run against new model versions without ever touching the ledger. `journal_entries` inherits every guarantee from the base ledger design (append-only, `∑DEBIT==∑CREDIT`, DB-level `no UPDATE/DELETE` grant) whether the entry originated from a human or an AI — the ledger doesn't know or care which; that distinction lives in `origin` for audit purposes only, never as a different code path for writing.

---

## 6. Pipeline Stage 1 — Ingestion & Normalization

Same canonical-model discipline as classic payment reconciliation (see `PAYMENT_RECONCILIATION_HLD_STAFF_ENG.md`):

- Every source (Plaid, direct bank SFTP/MT940, card processor settlement CSV) is mapped to the same `external_transactions` schema.
- Amounts stored as **signed integers in minor units** — never floats.
- Idempotency at ingestion: `(tenant_id, source, source_ref)` unique constraint. Re-delivery of the same feed (common with polling-based bank aggregators) is a no-op.
- Memo/counterparty text is **not** cleaned by the LLM at this stage — raw text is preserved for audit; normalization happens downstream and is itself a model output subject to the same guardrails as categorization.

---

## 7. Pipeline Stage 2 — Bank Reconciliation Matching Engine

This stage is **entirely deterministic** — no AI. It exists to shrink the problem the AI has to solve: only unmatched lines (no corresponding internal record) need journal-entry generation; matched lines just need reconciliation status flipped.

```
Step 1 — Exact match:      source_ref / check_number / wire_ref direct lookup → ~60-70% of volume
Step 2 — Fuzzy match:      amount ± known-fee-tolerance, date window ±N days → ~15-25% more
Step 3 — Rule engine:      known patterns (e.g. "this processor always nets out a 2.9% fee") → ~5%
Remainder — unmatched:     routed to the AI Journal Entry Generator
```

This mirrors §6 of `PAYMENT_RECONCILIATION_HLD_STAFF_ENG.md` directly. The point of a Staff/Principal-level answer here is to resist the urge to point the LLM at *all* reconciliation — cheap, explainable, deterministic matching should always run first and absorb the majority of volume. AI is reserved for the genuinely ambiguous remainder: **classification of a transaction that has no existing internal counterpart**, which is a fundamentally different (and harder) problem than matching two records that both already exist.

---

## 8. Pipeline Stage 3 — AI-Assisted Categorization & Journal Entry Generation

### Two different AI problems, two different model choices

| Sub-problem | Right tool | Why |
|---|---|---|
| Classify transaction → account code | Fine-tuned small classifier (e.g. gradient-boosted trees on tabular features, or a small fine-tuned transformer) per tenant-cluster | Deterministic-ish, fast, cheap at millions/day, easy to calibrate confidence, easy to retrain on feedback |
| Normalize vendor/memo text, propose human-readable description, tag tax treatment | LLM (constrained generation) | Free text understanding ("AMAZON WEB SERVICES" → "AWS — Cloud Hosting") benefits from language understanding; classification of a fixed COA does not need a generative model |

**Principal-level framing:** a full LLM call for every one of 5M bank lines/day is unnecessary and expensive. Use the LLM only where free-text reasoning is actually required (memo normalization, ambiguous vendor disambiguation, tax-tag suggestion with explanation) and use a lightweight supervised classifier — the actually load-bearing decision — for account-code classification. This is the same principle as RAG design: **retrieval/classification quality gates generation; don't let the expensive, non-deterministic model make the load-bearing decision it's worst suited for.**

### Feature inputs to the classifier

```
- Normalized amount, currency, sign
- Counterparty / merchant name (normalized via a merchant-name lookup service)
- MCC code (if card transaction)
- Day of week / recurrence pattern (is this a known recurring vendor for this tenant?)
- Tenant's own posting history: "this tenant categorized 'AWS' → 6120 on the last 14 occurrences"
- Tenant's chart of accounts (active accounts only, embeddings of account names/descriptions for cold-start vendors)
- Industry-level prior (from anonymized, aggregated cross-tenant patterns — never raw cross-tenant text, see §15 privacy boundary)
```

### Retrieval-augmented classification

For a new/ambiguous vendor with no tenant history, retrieve the **k most similar historical postings** (by vendor-name embedding + amount range) from this tenant first, then from an anonymized industry-level corpus. This is the same hybrid-retrieval pattern as a RAG pipeline (`RAG_PIPELINE_10M_DOCS_PRINCIPAL_ENGINEER.md` §5) applied to structured accounting history instead of documents — and it is the single highest-leverage lever for cold-start accuracy, because per-tenant posting history is the strongest possible signal ("this specific business has classified this specific vendor this way 40 times before" beats any generic model prior).

### Confidence calibration — the step most systems skip

A raw softmax probability or LLM log-prob is **not** a trustworthy confidence score — models are frequently overconfident, especially on out-of-distribution vendors. Apply **isotonic regression or Platt scaling** on a held-out labeled set to map raw scores to actual empirical accuracy:

```
calibrated_confidence = calibration_model.transform(raw_model_score)

# Verify: among proposals with calibrated_confidence ∈ [0.95, 1.0],
# actual human-agreement rate should also be ~95-100%. If not, recalibrate.
```

Without this step, "confidence ≥ 0.9" gate is meaningless — you're gating on a number that doesn't mean what you think it means.

---

## 9. Pipeline Stage 4 — Deterministic Guardrails

This is where the design earns the word "reliable." Every proposal, regardless of model confidence, passes through **non-AI, deterministic** checks before it is even eligible for auto-post:

| Guardrail | Check | Failure action |
|---|---|---|
| **Balance enforcement** | Compute the offsetting line(s) programmatically from the classified account + known bank-side line; assert `∑DEBIT == ∑CREDIT` to the cent | Reject proposal outright — never post an unbalanced entry, ever |
| **COA validity** | Proposed account code exists, is active, and is postable (not a header/summary account) for this tenant | Reject; fall back to a suspense account + human review |
| **Duplicate check** | `idempotency_key` (= source_ref) not already posted | Reject as duplicate, log |
| **Materiality threshold** | `abs(amount) < tenant_materiality_cap` (e.g. $500 for auto-post, tenant-configurable) | Route to human review regardless of confidence |
| **Restricted account class** | Proposed account is NOT in {equity, tax liability, intercompany, owner draws} | If restricted → always human review, regardless of confidence or amount |
| **Anomaly/pattern deviation** | Amount or frequency deviates >Nσ from this vendor's historical pattern for this tenant | Route to human review — "this is unusual even if the category itself is plausible" |
| **Period lock** | Target posting period is not already closed/locked for reporting | Reject; route to an out-of-period adjustment workflow |

**Why these are deterministic and not "ask the LLM to check its own work":** self-verification by the same model class that produced the error has a well-documented failure mode — it makes the same mistake in the check that it made in the generation. Balance enforcement in particular must be arithmetic performed by ordinary code, not re-asked of any model.

---

## 10. Pipeline Stage 5 — Confidence Gating & Auto-Post Decision

The auto-post decision is a function of **three independent inputs**, not confidence alone:

```
auto_post_eligible =
    all_guardrails_passed
    AND calibrated_confidence ≥ tenant_account_class_threshold
    AND materiality_within_cap
    AND account_class NOT IN restricted_set
```

Thresholds are **per-tenant and per-account-class**, not global:

```
Example threshold table (illustrative):

| Account class            | Min confidence | Max auto-post amount |
|--------------------------|-----------------|-----------------------|
| Recurring SaaS/utilities | 0.85            | $2,000                |
| General COGS/expenses    | 0.92            | $500                  |
| Payroll-adjacent         | 0.97            | $200                  |
| Equity / tax / intercompany | N/A (always human) | $0                |
```

This table itself should live in a config service (analogous to the Skydo Agentic Copilot's "control plane separate from runtime" principle) — tunable by an ops/compliance team without a code deploy, with a **kill switch** to disable auto-post entirely per tenant or globally if a model regression is detected.

---

## 11. Pipeline Stage 6 — Human-in-the-Loop Review

For everything that doesn't clear auto-post gates:

- Presented in a review queue ranked by **materiality × age** (biggest, oldest-unresolved items first).
- The reviewer sees: proposed entry, calibrated confidence, the evidence used (similar historical postings, matched rule), and can **approve as-is, edit and approve, or reject**.
- Every action — approve/edit/reject — is captured as labeled training data. This is the primary channel through which the classifier improves for that tenant over time (few-shot personalization via the retrieval layer, and periodic batch retraining of the classifier).
- SLA-based escalation: unresolved review items past a threshold (e.g. before period close) escalate to a senior accountant/ops alert, mirroring the aging-exception policy in classic payment reconciliation.

---

## 12. Pipeline Stage 7 — Posting to the Immutable Ledger

**One posting service, one code path**, regardless of whether the proposal was auto-approved or human-approved:

```
PostingService.post(proposal):
    1. Re-validate balance (defense in depth — never trust upstream state alone)
    2. Re-check idempotency_key uniqueness
    3. BEGIN TRANSACTION
         INSERT INTO journal_entries (origin=AI_AUTO|AI_REVIEWED, idempotency_key=source_ref, ...)
         INSERT INTO journal_lines (...) × N
         UPDATE journal_entry_proposals SET status='POSTED', posted_entry_id=...
         UPDATE external_transactions SET reconciled=true
       COMMIT
    4. Emit event: ledger.entry_posted (consumed by balance cache, reporting, audit)
```

This guarantees the AI path and the human path produce **structurally identical** ledger entries — the only difference is what's recorded in `origin` and `origin_proposal_id` for audit purposes. There is no "AI-only fast lane" that skips balance/idempotency validation.

---

## 13. Continuous Evaluation & Drift Detection

Mirrors the RAG-eval discipline (`RAG_PIPELINE_10M_DOCS_PRINCIPAL_ENGINEER.md` §11) applied to structured classification instead of generation:

### Golden set construction
- Sample a stratified set of historically posted entries (across tenants, account classes, amount buckets) with confirmed-correct categorization (either originally human-entered, or AI-proposed + human-approved-unedited).
- Weight toward the **tail**: rare vendors, unusual amounts, new tenants — this is where accuracy actually degrades first.

### What to measure, nightly
```
- Per-account-class accuracy (precision/recall against golden set)
- Per-tenant auto-post accuracy (sampled post-hoc audit of already-auto-posted entries)
- Edit distance / edit rate on human-reviewed proposals — rising edit rate = leading indicator of model drift
- Calibration drift: re-verify that "confidence 0.9" still means "~90% correct" on rolling window
- Guardrail trip rate by type — sudden spike in "materiality" or "restricted account" trips may indicate upstream data quality issue (e.g. mis-mapped MCC codes from a new bank integration)
```

### Alerts
| Signal | Severity |
|---|---|
| Auto-post accuracy on post-hoc audit drops below SLA (99.5%) | P0 — auto-disable auto-post for affected segment, page |
| Balance-check guardrail ever fails to catch an imbalance (should be mathematically impossible) | P0 — this indicates a bug in the guardrail itself, not the AI |
| Human edit rate on a given account class rises >2x baseline | P1 — investigate model/vendor-pattern drift |
| New tenant's first 100 transactions have <70% approval-without-edit | P2 — cold start, expected; ensure retrieval fallback to industry prior is engaged |

The feedback loop closes here: human corrections from §11 become new golden-set / training examples, re-evaluated nightly, and periodically used for classifier retraining — with the retrained model itself gated through shadow mode (§17) before its outputs are trusted for auto-post.

---

## 14. Scalability Design

- **Reconciliation matching (§7)** is bulk SQL/Spark, identical scaling story to `PAYMENT_RECONCILIATION_HLD_STAFF_ENG.md` §8 — partition by `settlement_date`/`tenant_id`, Spark for historical backfill, incremental batch for daily volume.
- **Classifier inference** is cheap and batchable — run as a streaming consumer off the "unmatched transactions" Kafka topic, batching N transactions per inference call. At 5M lines/day this is a lightweight model, not an LLM-per-line cost center.
- **LLM calls (memo normalization, tax-tag explanation)** are the expensive part — apply the same caching discipline as a RAG pipeline: exact-match cache keyed on `(normalized_vendor, rounded_amount_bucket)` — the same "AWS $482.13" pattern recurs monthly for the same tenant and across many tenants for identical SaaS vendors (with tenant-scoped cache partitioning to respect the privacy boundary in §15).
- **Guardrail evaluation** is pure application logic — trivially horizontally scalable, no shared state beyond the DB transaction itself.
- **Hot tenant problem:** a very high-volume tenant (e.g. a large e-commerce business with 100K+ transactions/day) should not starve smaller tenants' review queues — shard the human review queue and inference workers by tenant with fair-share scheduling, same "bucket account" style isolation principle as the hot-account problem in `LEDGER_SYSTEM_HLD_STAFF_ENG.md` §9.

---

## 15. Audit, Explainability & Compliance

- Every posted AI-originated entry is traceable to: `model_version`, `feature_version`, `evidence` (which historical postings/rules informed it), `calibrated_confidence`, and — if it went through review — the reviewing human and any edits made. This is the accounting-domain equivalent of the citation requirement in a RAG pipeline: **no journal entry reaches the books without a reconstructable "why."**
- **Cross-tenant privacy boundary:** raw vendor/memo text from Tenant A's transactions must never leak into Tenant B's model context or logs. Cross-tenant learning is limited to anonymized, aggregated statistics (e.g. "vendors matching MCC 7372 are usually SaaS/software, categorized to account class X in 87% of observed cases across tenants") — never raw transaction text. This mirrors the PII-masking discipline in `AGENTIC-SUPPORT-COPILOT.md` — the model must never see or retain data across a tenant boundary it isn't authorized for.
- **Regulatory retention:** 7-year immutable retention on both the ledger and the full AI decision audit log (SOX/GAAP-aligned), stored with object-lock/WORM guarantees.
- **Reversal, not deletion:** an incorrectly auto-posted entry is corrected with a compensating reversal entry that itself carries an audit trail explaining why — never edited or deleted, exactly matching the base ledger's immutability principle.
- **Accountant sign-off boundary:** for tenants where a licensed accountant/bookkeeper is contractually responsible for period-end books, auto-posted entries within a period can still be flagged for a lightweight batch review before period close/lock, even if each individual entry cleared the real-time auto-post gate — a second, cheaper checkpoint rather than re-litigating every transaction.

---

## 16. Failure Modes & Mitigations

| Failure Mode | Signal | Mitigation |
|---|---|---|
| **Model confidently miscategorizes a new vendor pattern** (e.g. a vendor that legitimately changed business type) | Sudden spike in human edits for that vendor | Anomaly guardrail catches deviation from historical pattern; per-vendor override rules can be added without redeploying the model |
| **Bank feed delivers duplicate or corrected transaction** | Idempotency key collision, or amount differs from a previously seen `source_ref` | Idempotency check rejects true duplicates; amount-changed-on-same-ref triggers a reconciliation exception, not a silent re-post |
| **Guardrail bug allows an unbalanced entry through** | Should be structurally impossible — DB-level `CHECK` constraint or trigger asserting `SUM(debit)=SUM(credit)` per `entry_id` as a last-resort backstop | Defense in depth: application-level check AND DB-level constraint |
| **Confidence calibration silently drifts** (model updated, calibration not re-run) | Nightly eval shows accuracy-vs-confidence-bucket divergence | Automated calibration re-check gates any new model version before it can serve auto-post traffic |
| **Cross-tenant data leakage via shared model/cache** | Security audit / penetration test finding | Tenant-scoped cache keys and model contexts; periodic access-boundary audits |
| **Auto-post threshold too aggressive for a new account class** | Materiality-weighted error rate rises in post-hoc audit | Per-account-class thresholds (not global) + rollback via config kill switch, no redeploy needed |
| **Human reviewers rubber-stamp without real review ("approval fatigue")** | Approval time per item trends toward near-zero; downstream audit finds errors reviewers should have caught | Sample a % of "approved" items for a second independent audit review; track reviewer-level accuracy, not just system-level |

---

## 17. Rollout Strategy

Directly modeled on the Skydo Agentic Copilot's trust-earned-through-evidence rollout (`AGENTIC-SUPPORT-COPILOT.md` §15):

```
Phase 0 — Shadow mode:        AI proposes on 100% of unmatched transactions, but
                               NOTHING auto-posts. Every proposal is compared against
                               what the human bookkeeper actually posted. Measure
                               accuracy per account class with zero customer risk.

Phase 1 — Low-risk auto-post: Enable auto-post only for the highest-confidence,
                               lowest-materiality, most-recurring account classes
                               (e.g. known recurring SaaS vendors under $200) for a
                               small pilot cohort of tenants.

Phase 2 — Expand by evidence: Raise materiality caps and add account classes only
                               after N consecutive weeks of post-hoc audit accuracy
                               ≥ SLA for that specific class/threshold combination.
                               Expand tenant cohort gradually, not globally at once.

Phase 3 — Steady state:       Auto-post covers the long tail of low-risk, high-
                               volume, high-confidence categories. Restricted
                               account classes (equity, tax, intercompany) remain
                               permanently human-gated by design (P6), not as a
                               temporary rollout stage.
```

At every phase, a **runtime kill switch** (config, not deploy) can disable auto-post per tenant, per account class, or globally the moment post-hoc audit or drift monitoring detects a regression.

---

## 18. Key Trade-offs

| Decision | Chosen | Rejected Alternative | Reason |
|---|---|---|---|
| Where AI writes | Proposal table only | Direct ledger write with LLM self-check | Self-verification by the same model class is unreliable; ledger integrity must not depend on model behavior |
| Balance validation | Deterministic code, DB constraint backstop | "Ask the LLM to double-check its own totals" | Arithmetic correctness is a solved, cheap, deterministic problem — never delegate it to a probabilistic model |
| Account classification model | Fine-tuned lightweight classifier | General-purpose LLM per transaction | Classification over a fixed COA doesn't need generative reasoning; cost and latency don't justify it at millions/day |
| Confidence thresholds | Per-tenant, per-account-class, config-driven | Single global confidence threshold | Materiality and risk vary enormously by account class and tenant risk tolerance; one number can't capture that |
| Cross-tenant learning | Anonymized aggregate statistics only | Shared raw-text training corpus across tenants | Privacy/compliance boundary; also reduces overfitting to one tenant's idiosyncratic categorization habits |
| Correction mechanism | Reversal entries | In-place edit of posted entries | Matches ledger immutability; preserves auditability required by GAAP/SOX |
| Rollout | Shadow mode → gated expansion by evidence | "Turn on auto-post at launch, monitor for issues" | Financial-statement risk means you earn trust with evidence before removing the human, not after |

---

## 19. Interview Q&A

**"Why not just fine-tune a big LLM to output the full journal entry directly, end to end?"**
> Because the load-bearing correctness property — `∑DEBIT==∑CREDIT` — is exactly the kind of thing an LLM is worst at guaranteeing (it can get arithmetic and formatting wrong even when the categorization is right) and exactly the kind of thing ordinary code is best at guaranteeing. Split the problem: let the model do what it's good at (pattern matching a vendor to a category, understanding free-text memos) and let deterministic code do what it's good at (arithmetic, constraint enforcement, idempotency). This is the same "retrieval gates generation" principle from RAG design, applied to accounting: classification/matching gates generation of the final entry.

**"How do you know your confidence threshold is actually meaningful?"**
> You don't, unless you calibrate it. Raw model scores are frequently overconfident, especially out-of-distribution. Calibrate against a held-out set (isotonic/Platt scaling) and continuously re-verify in production that "confidence bucket 0.9-0.95" actually corresponds to ~90-95% empirical accuracy. If it doesn't, the threshold is a made-up number, not a risk control.

**"What's the single biggest risk at scale, beyond individual transaction accuracy?"**
> Silent drift. A model that was 99.5% accurate at launch degrades as vendor patterns, bank feed formats, or a tenant's business itself changes, and nobody notices until an audit or a customer complaint. The mitigation is the same as any ML system in production: a golden eval set, nightly re-scoring, and alerting on both accuracy regression and calibration drift — treated as an SLA, not a launch-day checkbox.

**"How is this different from a general reconciliation system with an LLM added on top?"**
> A "reconciliation system with an LLM bolted on" typically lets the model's output flow more or less directly into records that matter, with a prompt asking it to "be careful." This design treats the model as adversarial-by-default to the ledger: its output is a proposal in a separate mutable table, subject to deterministic guardrails it cannot see or influence, gated by calibrated (not raw) confidence, with per-account-class blast-radius limits that hold regardless of measured model quality. The reliability comes from architecture and separation of concerns, not from prompting.

---

## 20. Summary

> **The core insight:** in a domain with a zero-tolerance correctness invariant (the ledger must always balance and be traceable), the AI's job is to expand *coverage* of what can be automated — never to be the source of *correctness*. Correctness is guaranteed by deterministic code that the AI's output must pass through, not by trusting the AI to behave.

This design is:
- **Correct by construction** — the AI never has a code path to the ledger that bypasses balance/idempotency/materiality checks.
- **Reliable under drift** — calibrated confidence, per-account-class thresholds, and continuous golden-set evaluation catch degradation before it compounds.
- **Scalable** — cheap classifiers do the high-volume load-bearing work; expensive LLM calls are reserved for genuinely free-text problems and are cached/batched.
- **Auditable and reversible** — every entry, AI- or human-originated, carries a reconstructable "why," and corrections are reversals, never silent edits.
- **Trust-gated rollout** — automation expands only as evidence (post-hoc audit accuracy) accumulates, with a runtime kill switch at every level of granularity.
