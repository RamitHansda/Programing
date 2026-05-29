# DualEntry — Hardcore Engineer (All Levels) Interview Prep

**Role:** Hardcore Engineer (All Levels)
**Location:** Remote (EU, LATAM, Canada)
**Compensation:** $120K–$300K base + $40K–$100K equity (scales with experience)

---

## Table of Contents

1. [Company & Role Context](#1-company--role-context)
2. [Interview Process](#2-interview-process)
3. [What DualEntry Actually Evaluates](#3-what-dualentry-actually-evaluates)
4. [The Opening Pitch — Own the Room in 90 Seconds](#4-the-opening-pitch--own-the-room-in-90-seconds)
5. [Behavioral / Culture Fit Round](#5-behavioral--culture-fit-round)
6. [Technical Deep-Dive — Backend & Python](#6-technical-deep-dive--backend--python)
7. [System Design Round](#7-system-design-round)
8. [Domain Knowledge — Accounting & ERP](#8-domain-knowledge--accounting--erp)
9. [Questions to Ask the Interviewer](#9-questions-to-ask-the-interviewer)
10. [Red Flags to Avoid](#10-red-flags-to-avoid)
11. [Quick-Fire Cheat Sheet](#11-quick-fire-cheat-sheet)

---

## 1. Company & Role Context

### What is DualEntry?

DualEntry is an **AI-native ERP** company targeting the $220B+ global ERP market. Their pitch: every legacy ERP (NetSuite, SAP, Sage) was built before AI existed. DualEntry was architected after ChatGPT launched — meaning AI automation is the foundation, not a bolt-on.

Key facts to weave into conversation:

| Fact | Detail |
|---|---|
| **Funding** | $100M+ raised; Series A led by Lightspeed Venture Partners, Khosla Ventures, Contrary Ventures, Google Ventures |
| **Customers** | $5M-ARR startups to NYSE-listed companies |
| **Integrations** | 13,000+ banks across 60 countries; 200+ tools (Stripe, Salesforce, Ramp, Gusto) |
| **Team DNA** | Ex-Ramp, Meta, Microsoft, Lyft, PwC, Deloitte, J.P. Morgan, Bloomberg, Sage, Xero, Intuit |
| **HQ** | 7 World Trade Center, New York City |
| **Stage** | ~18 months old; growing fast; early-stage enough that every engineer shapes the product |

### What the Hardcore Engineer role actually means

This is **not a standard backend engineer role.** DualEntry uses the word "hardcore" deliberately. The explicit job description says:

- **9am–9pm, 6 days per week** — they mean it
- Full end-to-end ownership: you design it, build it, deploy it, monitor it, fix it
- No hand-offs, no support teams to delegate to
- Founders and Santiago Nestares (co-founder/CEO) are hands-on and will be in your interviews

**Primary responsibilities:**

- Design and build core backend systems in Python
- Build APIs and services that handle real-time, business-critical, complex accounting logic
- Launch production-ready features from scratch (CI/CD, deployment, monitoring included)
- Manage data migrations and schema evolution in a business-logic-heavy product
- Integrate third-party tools and external systems — proactively managing edge cases and risk
- Own work from architecture → testing → monitoring → post-release care

**Stack signals from job descriptions:**

| Layer | Technologies |
|---|---|
| **Language** | Python (FastAPI, Flask, or Django) |
| **Database** | PostgreSQL, SQL, ORMs (SQLAlchemy/Django ORM), schema migrations |
| **Cloud** | AWS (preferred) |
| **Infrastructure** | CI/CD pipelines, Docker/containers, deployments, monitoring |
| **Integrations** | REST APIs, third-party platforms (AP/AR, billing, CRM, payroll, data warehouses) |

---

## 2. Interview Process

Based on DualEntry's careers FAQ and co-founder interviews:

| Stage | Format | What to Expect |
|---|---|---|
| **1. Application screen** | Async | Resume reviewed by Talent team; they move fast — response within days if interested |
| **2. Intro call** | 30 min, Talent/Recruiter | Role fit, compensation, logistics, timeline |
| **3. Technical interviews (×2–3)** | 60 min each, live | System design, coding, technical walk-throughs — no take-home assignments |
| **4. Culture/values interview** | 45–60 min | Founder or senior engineer assessing agency, ownership, intensity alignment |
| **5. Founder meeting** | 30 min | Santiago Nestares or co-founder; final gut check on culture alignment |
| **6. Backchannel references** | Async | They go beyond formal references — backchannel conversations with people who worked with you |

**Key process facts:**

- **No take-home assignments.** DualEntry has explicitly moved away from these. Expect live technical assessments only.
- **Backchannel references are real.** Santiago Nestares has publicly stated they do deep backchannel checks: how you handle pressure, show up day-to-day, work with a team. Prepare your references proactively.
- **Move fast.** Their process is quick. If you're strong, offers come within 1–2 weeks.
- **Experience level is secondary.** The JD says "experience level doesn't matter." What they screen for is *how ambitious and how capable* you are — not your YoE.

---

## 3. What DualEntry Actually Evaluates

### The 4 signals they care about most

**1. High agency and ownership**

> They want engineers who see a problem, decide what to do, build it, and own the outcome — without being managed. They're not looking for someone who waits for a ticket.

**2. Speed and throughput**

> This is an early-stage team competing against companies with thousands of employees. Every engineer is expected to ship at a pace that would be unusual elsewhere. They'll probe: how fast have you shipped in the past? What's your longest unbroken streak of shipping production code?

**3. "Experience, not surface knowledge"**

> Co-founder Santiago Nestares: *"Experience is hard to fake. You can usually tell when someone's just read about something versus having lived it."* Expect probing follow-up questions. Surface-level answers won't work.

**4. Correctness and pragmatism**

> Accounting software has zero tolerance for data errors. They look for engineers who balance speed with correctness — who know when to cut corners and when absolute correctness is non-negotiable.

---

## 4. The Opening Pitch — Own the Room in 90 Seconds

Structure your intro as: *Impact → Scope → Why here.*

**Template:**

> "Over the last [X] years I've built and owned backend systems in [domain]. The most relevant work is [specific system] — I designed and built it end to end, from the data model through the API, deployment, and post-launch monitoring. The hardest problems were [correctness / scale / integration complexity]. I'm here because DualEntry is the most interesting backend problem in fintech right now — you're rebuilding accounting infrastructure from scratch with AI at the core, and the engineering bar you're holding is exactly where I want to be working."

**Why this works:**

- You lead with scope and impact, not job titles
- You name the *kind* of hard problem you've solved — which is the same kind they have
- You're specific about why DualEntry, not generic ("I love fast-paced startups")

---

## 5. Behavioral / Culture Fit Round

This is not a "tell me about a time you showed teamwork" STAR round. DualEntry is assessing *intensity, ownership, and values alignment.* Every behavioral question is a screen for one of their four values: **Move Fast, Work Hard, Be Relentless, Play to Win.**

---

### Q1. Walk me through a time you owned a feature completely end-to-end.

**What they want:** Evidence you've gone from zero to production without relying on other teams for the hard parts.

> "At [Company], I owned [feature] entirely. I wrote the spec, designed the data model, built the API, set up the CI/CD pipeline, handled the migration, and owned on-call for the first 30 days post-launch. The most complex part was [specific technical detail] — where most engineers would have handed off to a DBA or infra team, I did it myself because I wanted to own the correctness guarantees end-to-end. The feature [shipped in X days / handled X transactions / saved X hours of manual work]."

**Attach a number.** They care about measurable impact.

---

### Q2. Tell me about a time you moved extremely fast. What does "fast" mean to you?

**What they want:** Proof of shipping velocity, not just the capability.

> "Fast for me means production code, not demos. At [Company], I [shipped feature X in N days] — from design to deployed. The key was [cutting scope ruthlessly / reusing existing patterns / not asking for permission on every decision]. I made the call to [specific decision], which saved [X days]. It wasn't perfect on day one, but it was correct and observable, and we iterated from there."

**Frame it as: I bias toward shipping and fixing, not toward planning until it's perfect.**

---

### Q3. Describe a situation where you disagreed with a decision and what you did.

**What they want:** High agency, not compliance. They want someone who pushes back, makes their case, then commits.

> "At [Company], [person] wanted to [approach A]. I disagreed because [specific technical reason]. I built a quick prototype to show the tradeoff concretely, walked through the failure modes, and proposed [approach B]. They came around. The thing I've learned is that at a fast-moving company, 'I disagree' without a concrete alternative is just noise — you have to come with evidence and a better option."

---

### Q4. Why do you want to work 9am–9pm, 6 days per week?

**This is a direct filter question.** They're not looking for someone who wants work-life balance. Don't try to soften or hedge this.

> "Honestly, I thrive in environments like this. The people who build category-defining companies aren't working 40-hour weeks — they're obsessed. I've had stretches in my career where I've worked this kind of schedule not because I had to, but because the problem was so interesting I didn't want to stop. DualEntry is at that inflection point — AI-native ERP is a 30-year category that nobody has cracked yet. If there's ever a time to go all in, it's now."

**Red line:** Do not say "I value work-life balance" or "I'll adjust my habits." This is a hard filter.

---

### Q5. What's the fastest you've ever shipped a production feature?

Have a concrete story ready. They will ask this or a variant of it. **Specifics matter more than the number.**

> "The fastest was [feature] — I shipped it in [X days / X hours]. The context was [urgent customer need / competitive pressure]. I made the decision to [scope cut / shortcut that was technically sound] and [shipped it]. The thing that made it possible was [specific engineering decision]."

---

### Q6. Tell me about a production incident you caused and what you did.

**What they want:** Ownership, not defensiveness. They hire people who acknowledge mistakes, fix fast, and build systems to prevent recurrence.

> "I shipped [change] that caused [incident description]. I owned it completely — stayed on it until it was resolved, wrote the post-mortem myself, and personally implemented the monitoring that would have caught it earlier. What I changed permanently after: [specific practice or system change]."

---

## 6. Technical Deep-Dive — Backend & Python

DualEntry uses Python + FastAPI/Django + PostgreSQL + AWS. Live coding and technical walk-throughs happen in real-time. Expect both implementation questions and conceptual architecture discussions.

---

### Python & Backend Fundamentals

**Q: Walk me through how you'd design a FastAPI endpoint for creating a journal entry.**

Be specific. Show you know the full stack — not just the HTTP handler.

```python
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from pydantic import BaseModel
from decimal import Decimal
from typing import List
from datetime import date

router = APIRouter()

class JournalLineItem(BaseModel):
    account_id: int
    debit: Decimal = Decimal("0.00")
    credit: Decimal = Decimal("0.00")
    description: str | None = None

class CreateJournalEntryRequest(BaseModel):
    entity_id: int
    date: date
    reference: str
    memo: str | None = None
    lines: List[JournalLineItem]

    def is_balanced(self) -> bool:
        total_debits = sum(line.debit for line in self.lines)
        total_credits = sum(line.credit for line in self.lines)
        return total_debits == total_credits

@router.post("/journal-entries", status_code=201)
def create_journal_entry(
    payload: CreateJournalEntryRequest,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    if not payload.is_balanced():
        raise HTTPException(
            status_code=422,
            detail="Journal entry must balance: total debits must equal total credits"
        )
    # Persist atomically — all lines or nothing
    entry = JournalEntry(
        entity_id=payload.entity_id,
        date=payload.date,
        reference=payload.reference,
        memo=payload.memo,
        created_by=current_user.id,
    )
    db.add(entry)
    db.flush()  # get entry.id before committing

    for line in payload.lines:
        db.add(JournalLineItem(
            entry_id=entry.id,
            account_id=line.account_id,
            debit=line.debit,
            credit=line.credit,
            description=line.description,
        ))

    db.commit()
    return {"id": entry.id}
```

**Follow-up they'll ask:** *"What happens if we receive the same request twice?"*

> Add an idempotency key — either client-supplied (preferred) or derived from `(entity_id, reference, date)`. On duplicate, return the existing entry's ID with 200. Never create a duplicate journal entry. This is an accounting correctness requirement, not a nice-to-have.

---

**Q: How do you handle database schema migrations at scale?**

> "I treat migrations as first-class production deployments. Every migration must be backward-compatible — which means adding columns before the code that uses them, and dropping columns only after the old code reading them is gone. For large tables, I use `LOCK` with `NOWAIT` to fail fast rather than block indefinitely, or multi-step approaches: add a new column with a default, backfill in batches, then make it non-nullable. We use Alembic with auto-generated migrations reviewed in code review. Zero-downtime deployments require you to think in phases, not single-step schema changes."

---

**Q: You need to backfill 50 million rows after a schema change. How do you do it?**

> "Never with a single UPDATE statement — that locks the table and causes an outage. The pattern: write a background job that processes rows in batches of 1,000–5,000, with `WHERE id BETWEEN X AND Y AND new_column IS NULL`, with a configurable delay between batches to avoid saturating the DB. Monitor replica lag as the key health signal. Run during off-peak hours. The job must be idempotent — if it crashes mid-run, restarting it should not double-process or corrupt data."

---

**Q: How do you design a PostgreSQL schema for multi-entity accounting?**

Core tables for a multi-tenant, multi-entity general ledger:

```sql
-- Each customer is an organization; each subsidiary/entity is a legal entity
CREATE TABLE organizations (
    id          BIGSERIAL PRIMARY KEY,
    name        TEXT NOT NULL,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE entities (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    name            TEXT NOT NULL,
    currency        CHAR(3) NOT NULL,  -- ISO 4217: USD, EUR, GBP
    fiscal_year_end DATE NOT NULL,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE accounts (
    id              BIGSERIAL PRIMARY KEY,
    entity_id       BIGINT NOT NULL REFERENCES entities(id),
    code            TEXT NOT NULL,   -- e.g. "1000", "4100"
    name            TEXT NOT NULL,
    account_type    TEXT NOT NULL CHECK (account_type IN ('asset', 'liability', 'equity', 'revenue', 'expense')),
    is_active       BOOLEAN DEFAULT TRUE,
    UNIQUE (entity_id, code)
);

CREATE TABLE journal_entries (
    id          BIGSERIAL PRIMARY KEY,
    entity_id   BIGINT NOT NULL REFERENCES entities(id),
    date        DATE NOT NULL,
    reference   TEXT,
    memo        TEXT,
    posted      BOOLEAN DEFAULT FALSE,
    created_by  BIGINT REFERENCES users(id),
    created_at  TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (entity_id, reference)   -- prevent duplicate posts
);

CREATE TABLE journal_lines (
    id          BIGSERIAL PRIMARY KEY,
    entry_id    BIGINT NOT NULL REFERENCES journal_entries(id) ON DELETE CASCADE,
    account_id  BIGINT NOT NULL REFERENCES accounts(id),
    debit       NUMERIC(20, 2) NOT NULL DEFAULT 0,
    credit      NUMERIC(20, 2) NOT NULL DEFAULT 0,
    description TEXT,
    CHECK (debit >= 0 AND credit >= 0),
    CHECK (NOT (debit > 0 AND credit > 0))  -- a line is debit OR credit, not both
);

CREATE INDEX idx_journal_lines_account ON journal_lines(account_id);
CREATE INDEX idx_journal_entries_entity_date ON journal_entries(entity_id, date);
```

**Critical design decisions to mention:**
- Use `NUMERIC(20, 2)` — never `FLOAT` for money. Floating point arithmetic introduces rounding errors in accounting.
- Separate debit and credit into distinct columns — this maps directly to accounting double-entry and avoids sign-convention bugs.
- The `CHECK` constraint on `journal_lines` prevents a line from being both a debit and a credit.
- Multi-tenancy isolation: always filter by `entity_id`; index it accordingly.

---

### AWS & Infrastructure

**Q: Walk me through how you'd deploy a FastAPI app on AWS.**

> "My standard setup: FastAPI containerized with Docker, pushed to ECR. ECS Fargate for compute — no server management, just task definitions. An ALB in front for TLS termination and health checks. RDS PostgreSQL in a private subnet, connection pooling with PgBouncer. Secrets in AWS Secrets Manager, injected as environment variables. CloudWatch for logs and metrics. CI/CD via GitHub Actions: lint → test → build Docker image → push to ECR → deploy to ECS with a rolling update strategy. For migrations, I run them as a one-off ECS task before the new image goes live."

---

**Q: How do you handle secrets in a cloud-native Python app?**

> "Never in environment variables hardcoded at build time. In AWS: Secrets Manager or Parameter Store, fetched at runtime. The app uses `boto3` to pull secrets on startup, or the ECS task definition fetches and injects them as env vars via Secrets Manager ARN references. The key discipline: secrets are never in git, never in Dockerfiles, never in CloudFormation/Terraform templates in plaintext."

---

## 7. System Design Round

DualEntry's product domain dominates system design questions. Expect to design systems that are core to how their product works. The common themes: **correctness, idempotency, double-entry integrity, real-time processing, third-party reliability.**

---

### Design 1: Double-Entry Accounting Ledger System

**Problem Statement:**
Design a ledger system that supports real-time journal entry creation, account balance queries, multi-entity support, and audit history. The system must guarantee that debits always equal credits for every transaction.

**Key areas to cover:**

| Area | What they want to hear |
|---|---|
| **Data model** | Double-entry: separate debit/credit columns; NUMERIC not FLOAT; entity isolation |
| **Correctness** | Balance check enforced at API layer AND at DB layer (constraint/trigger) |
| **Idempotency** | Unique constraint on `(entity_id, reference)` prevents duplicate posts |
| **Balance queries** | Materialized running balance vs. on-the-fly SUM — tradeoff discussion |
| **Audit trail** | Immutable journal lines; no UPDATE or DELETE on posted entries; corrections via reversals |
| **Scale** | How to handle high-volume orgs: partitioning by entity, read replicas for reporting |
| **Locking** | Optimistic locking for concurrent period closes; pessimistic locking for critical account updates |

**Balance query tradeoff:**

```
Option A — On-the-fly SUM:
  SELECT SUM(debit) - SUM(credit) FROM journal_lines WHERE account_id = ?
  Pro: Always accurate. No stale data.
  Con: Slow for accounts with millions of lines.

Option B — Materialized running balance:
  Maintain account_balances table; update on every journal post.
  Pro: O(1) balance reads.
  Con: Consistency risk if materialization fails mid-transaction.
  Mitigation: Update account_balances in the SAME transaction as journal_lines.

Recommendation: Start with on-the-fly; add materialized balance as a hot-path optimization
with a periodic reconciliation job to catch any drift.
```

**Correction entry pattern (never DELETE from a ledger):**

```
Original entry: DR Cash 1000 / CR Revenue 1000
Discovered error: Revenue should be 900, Tax Payable 100

Correction step 1 (reversal): DR Revenue 1000 / CR Cash 1000
Correction step 2 (repost):   DR Cash 1000 / CR Revenue 900 / CR Tax Payable 100

Never UPDATE or DELETE journal lines. Corrections are entries.
```

---

### Design 2: Revenue Recognition Engine (ASC 606)

**Problem Statement:**
Design a system that ingests contracts, determines performance obligations, and automatically recognizes revenue according to ASC 606 rules as milestones are met or over time.

**Why this matters at DualEntry:** Revenue recognition is one of their core product features — this is not a theoretical question.

**Core ASC 606 concepts to demonstrate:**

| Concept | Engineering implication |
|---|---|
| **Performance obligations (POs)** | Each PO is a separate unit of delivery; model as rows in a `performance_obligations` table |
| **Transaction price allocation** | Allocate contract price across POs based on standalone selling price (SSP) |
| **Recognition method** | Point-in-time (milestone) vs. over time (straight-line or input/output method) |
| **Deferred revenue** | Revenue not yet earned sits as a liability; recognized as work is delivered |

**Simplified schema:**

```sql
CREATE TABLE contracts (
    id              BIGSERIAL PRIMARY KEY,
    entity_id       BIGINT NOT NULL,
    customer_id     BIGINT NOT NULL,
    total_value     NUMERIC(20, 2) NOT NULL,
    start_date      DATE NOT NULL,
    end_date        DATE,
    status          TEXT NOT NULL  -- draft, active, completed, cancelled
);

CREATE TABLE performance_obligations (
    id              BIGSERIAL PRIMARY KEY,
    contract_id     BIGINT NOT NULL REFERENCES contracts(id),
    description     TEXT NOT NULL,
    allocated_value NUMERIC(20, 2) NOT NULL,
    recognition_method TEXT NOT NULL,  -- 'point_in_time' | 'over_time'
    start_date      DATE,
    end_date        DATE,
    recognized_to_date NUMERIC(20, 2) DEFAULT 0
);

CREATE TABLE recognition_events (
    id              BIGSERIAL PRIMARY KEY,
    po_id           BIGINT NOT NULL REFERENCES performance_obligations(id),
    recognized_at   TIMESTAMPTZ NOT NULL,
    amount          NUMERIC(20, 2) NOT NULL,
    journal_entry_id BIGINT REFERENCES journal_entries(id),
    created_at      TIMESTAMPTZ DEFAULT NOW()
);
```

**Recognition engine flow:**

```
Trigger (cron or event) → Revenue Recognition Service
  → For each PO with method=over_time:
      days_elapsed / total_days × allocated_value − recognized_to_date = amount_to_recognize
      → Create journal entry: DR Deferred Revenue / CR Revenue
      → Insert recognition_event
      → Update recognized_to_date

  → For each PO with method=point_in_time:
      Wait for milestone_completed event
      → Create journal entry on event receipt
```

**Key correctness guarantees:**
- Recognition events are idempotent — keyed on `(po_id, period)` to prevent double-recognition
- Journal entry creation and `recognized_to_date` update happen in the same DB transaction
- Reconciliation job daily: `sum(recognition_events.amount)` must equal `recognized_to_date`

---

### Design 3: Third-Party Integration Pipeline

**Problem Statement:**
Design a system that syncs data from 200+ third-party tools (Stripe, Salesforce, Ramp, Gusto, etc.) into DualEntry's ledger reliably and in near-real-time.

**Key design areas:**

| Area | Approach |
|---|---|
| **Ingestion** | Webhooks (push) for real-time sources; polling for sources without webhooks |
| **Normalization** | Adapter pattern per integration — translate vendor events into DualEntry's canonical transaction format |
| **Idempotency** | Vendor event ID as idempotency key; store in `processed_events` table; skip duplicates |
| **Reliability** | Retry with exponential backoff; dead-letter queue after N failures |
| **Ordering** | Process events per-source in order (Kafka partition key = `integration_id`) |
| **Error isolation** | One integration failing must not block others |

**Component diagram:**

```
Stripe / Ramp / Gusto / Salesforce
        ↓ webhooks / polling
  Ingestion Gateway (FastAPI)
        ↓
  Queue (SQS/Kafka) per integration type
        ↓
  Adapter Workers (one per integration)
    → Normalize to canonical format
    → Idempotency check (processed_events table)
    → Emit canonical_transaction event
        ↓
  Transaction Categorizer (AI/rules engine)
        ↓
  Ledger Service → Journal Entry creation
        ↓
  Reconciliation Engine (runs nightly)
    → Compare DualEntry ledger vs. vendor statement
    → Flag unmatched items for human review
```

**Failure modes to discuss:**
- Webhook delivery failure → implement reconciliation/catch-up polling as a fallback
- Duplicate webhook delivery → idempotency key on vendor's event ID
- Vendor API rate limits → implement backoff + jitter; per-integration rate limiting
- Schema changes in vendor APIs → adapter layer protects core; failing adapters don't block core

---

## 8. Domain Knowledge — Accounting & ERP

DualEntry engineers work directly with complex accounting logic. You don't need to be a CPA, but you need to know enough to work with accounting teams and build systems that are correct.

### Core concepts to know

**Double-entry accounting:**

> Every transaction affects at least two accounts. Debits must always equal credits. This is not a convention — it is a mathematical invariant that your data model must enforce.

| Transaction | Debit | Credit |
|---|---|---|
| Customer pays invoice | Cash (Asset ↑) | Accounts Receivable (Asset ↓) |
| Company pays vendor | Accounts Payable (Liability ↓) | Cash (Asset ↓) |
| SaaS subscription revenue | Cash or AR | Revenue |
| Unearned subscription revenue | Cash | Deferred Revenue (Liability) |
| Revenue recognized over time | Deferred Revenue | Revenue |

**Chart of Accounts:**

> The hierarchical list of all accounts in an entity. Typical categories: Assets, Liabilities, Equity, Revenue, Expenses. DualEntry lets users customize their COA and map imported transactions to it.

**Accounts Payable / Accounts Receivable:**

> AP: money the company owes to vendors. AR: money customers owe to the company. Both require aging reports, payment matching, and reconciliation workflows.

**Month-end / Period close:**

> The process of finalizing all transactions for an accounting period, reconciling accounts, running adjusting entries, and producing financial statements. DualEntry's core value proposition is automating this from 20 days down to 1 day.

**Flux analysis:**

> Comparing account balances period-over-period (month/month or year/year) to explain material variances. DualEntry's AI flags anomalies automatically — this requires the system to maintain historical snapshots of account balances.

**Multi-entity consolidation:**

> A parent company with subsidiaries must produce consolidated financial statements, eliminating intercompany transactions. Engineering challenge: you need elimination entries to avoid double-counting revenue/expenses.

---

### Accounting questions they might ask

**Q: A customer pays us $12,000 upfront for an annual SaaS subscription. How do we record this and how do we recognize revenue?**

> "At the time of payment: Debit Cash $12,000, Credit Deferred Revenue $12,000. Deferred revenue is a liability — we've received cash but haven't delivered the service yet. Each month, we recognize $1,000: Debit Deferred Revenue $1,000, Credit Revenue $1,000. At month 12, Deferred Revenue is zero and we've recognized all $12,000 in Revenue. This is ASC 606 point-in-time recognition for a ratable delivery obligation."

**Q: How would you model intercompany eliminations in a multi-entity consolidation?**

> "When Entity A sells to Entity B (both under the same parent), the sale creates Revenue in A and an Expense in B. At consolidation, these must cancel out — otherwise the group double-counts. The engineering model: tag intercompany journal lines with a `counterparty_entity_id`. The consolidation engine queries for all line pairs where entity A's counterparty is entity B and vice versa, generates elimination entries, and excludes them from the consolidated P&L. The challenge is ensuring perfect matching — every intercompany debit must have a matching credit. Reconciliation jobs flag any imbalances."

---

## 9. Questions to Ask the Interviewer

Ask these thoughtfully — they signal you're thinking like an owner, not a candidate.

**On the product:**
- "What are the top three accounting workflows that are still mostly manual today and are on your roadmap to automate in the next 6 months?"
- "How does the AI layer decide what to auto-categorize vs. what to surface for human review? What's the error rate and how do you measure it?"
- "How do you handle correctness when AI is making accounting decisions — what's the override and audit trail model?"

**On the engineering:**
- "What does the current CI/CD pipeline look like? What's your average time from merge to production?"
- "What's the biggest area of technical debt that slows you down right now?"
- "How are data migrations handled today — is there a pattern the team follows or does each engineer figure it out per feature?"
- "How do you think about test coverage in a product where a data bug can mean a customer's financials are wrong?"

**On the team and culture:**
- "What does a really strong week look like for an engineer on this team — what are you shipping, what problems are you solving?"
- "What's the on-call rotation and what kinds of incidents come up most often?"
- "For someone joining today, what would be the first meaningful thing you'd want them to own in the first 30 days?"

**On growth:**
- "How do engineers level up here — is there a structured path, or is it more about the scope of problems you're owning?"

---

## 10. Red Flags to Avoid

| What you might say | Why it lands wrong | What to say instead |
|---|---|---|
| "I prefer working sustainable hours" | Hard disqualifier — the JD says 9am–9pm, 6 days | "I thrive in high-intensity environments — I do my best work when the stakes are high" |
| "I like to consult with the team before making technical decisions" | Signals low agency | "I move fast and bring teammates in when I need a second opinion, but I don't wait for consensus to start" |
| "I read about ASC 606 / revenue recognition" | They'll probe and the gap shows | Either know it deeply or say "I don't have domain depth here yet, but I pick up domain knowledge fast — here's an example" |
| "We used microservices at my last company" | Fine if you can justify it; red flag if it sounds like a default | Lead with the *problem* microservices solved, not the architecture pattern |
| Generic answers about "scale" | Vague — everyone claims to have scaled | Give the actual number: "We processed 10,000 transactions/day / 50M rows / $5B in annual volume" |
| "The team handled that" | They screen for ownership | Always say "I designed/built/owned X" — even when it was collaborative, describe your specific contribution |
| Asking about work-life balance | Immediate mismatch signal | Ask about the engineering problems, the roadmap, the team's technical culture |
| Hedging on mistakes or failures | Signals lack of self-awareness | Own failures directly, state what you learned, describe what you changed |

---

## 11. Quick-Fire Cheat Sheet

### DualEntry one-liners

| Topic | What to say |
|---|---|
| What they build | AI-native ERP — accounting automation from GL to close, for $5M-ARR to NYSE companies |
| Why now | ERP is a 30-year-old market; first company built after AI became capable; massive greenfield |
| Their moat | Deep accounting domain + AI automation + speed of implementation (data in 24h, live in 4–6 weeks) |
| Their culture | Move fast, work hard, be relentless, play to win — literal values, not posters |
| Stack | Python (FastAPI/Django), PostgreSQL, AWS, CI/CD |
| Interview style | Live only, no take-homes; founder involvement; backchannel references |

### Technical cheat sheet

| Topic | Key point |
|---|---|
| Money in PostgreSQL | Always `NUMERIC(20, 2)` — never `FLOAT` |
| Journal entries | Debits must equal credits — enforce at API and DB constraint level |
| Idempotency | Unique constraint + idempotency key on every write path; never rely on client not retrying |
| Migrations | Backward-compatible only; add before use, drop after retire; large tables = batched updates |
| Corrections in a ledger | Never DELETE or UPDATE posted entries; issue reversal + repost entries |
| Revenue recognition | Deferred Revenue (liability) → Revenue as service delivered; ASC 606 |
| Multi-entity | Filter everything by entity_id; intercompany eliminations at consolidation |
| Integration reliability | Idempotency key on vendor event ID; retry + DLQ; reconciliation as backstop |
| Balance queries | On-the-fly SUM for accuracy; materialized balance for hot-path; reconcile both |

### Culture one-liners under pressure

| Their value | Your proof point to have ready |
|---|---|
| Move fast | "Shipped [feature] in [N days] — here's what I cut and why" |
| Work hard | "I've operated at [schedule] before — it's the mode I naturally go into on hard problems" |
| Be relentless | "When [system] went down at 2am, I stayed on it until [time] — here's what I did" |
| Play to win | "At [Company], I chose [approach] because it was the best outcome for the customer, even though it was harder for engineering" |

---

*Last updated: May 2026*
