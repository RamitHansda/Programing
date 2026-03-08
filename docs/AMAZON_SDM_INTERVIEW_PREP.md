# Amazon Interview Preparation Guide

**Role:** Software Dev Manager, FinTech
**Date:** March 9, 2026, 11:00 AM – 12:00 PM IST
**Interviewer:** Saurabh Singh (Software Development Manager)
**Meeting:** [Amazon Chime](https://chime.aws/3292399877) | [Bluescape Whiteboard](https://client.ext.bluescape.ee-infra.aws.dev/BpF64-XT9g35HxfCv96x)

---

## FORMAT BREAKDOWN (1 hour)

| Time | What |
|---|---|
| 0–10 min | Informal intro — tell me about yourself |
| 10–35 min | 2–3 Leadership Principle behavioral questions (STAR) |
| 35–55 min | System design on Bluescape — FinTech focused |
| 55–60 min | Your questions for the interviewer |

---

## PART 1: YOUR 2-MINUTE INTRO

> "I'm Ramit — I have 10 years building and scaling production-grade backend platforms, with the last 4 at a FinTech startup called Skydo as the Engineering Manager. I own end-to-end engineering there — architecture, delivery, and reliability of a payments and settlement platform that processes 10,000+ international transactions daily. I've spent most of my career at the intersection of distributed systems and financial data — before Skydo I was a VP at Goldman Sachs leading market risk aggregation infrastructure at petabyte scale. My background spans payments, identity, data systems, and cloud infrastructure. I'm also fairly hands-on — I write Java and Python, and I've led the team through ISO 27001 and SOC 2 Type II certifications as CIO. I'm excited about Amazon's scale in FinTech because the problems I've been solving at Skydo — idempotency, reconciliation, fault tolerance — exist at orders of magnitude more complexity here."

---

## PART 2: LEADERSHIP PRINCIPLES — Questions & Answers

> **Key rule:** Always quantify results. Always name the trade-off. Amazon interviewers probe for specifics.

---

### LP 1 — OWNERSHIP

**"Tell me about a time you took ownership of something outside your direct responsibility."**

**S:** At Skydo, we had no formal information security function. The business was growing rapidly — handling international payments for thousands of SMEs — and a major enterprise client flagged during due diligence that we had no certifiable security posture. Nobody owned this.

**T:** I stepped in and took the role of Chief Information Officer, in addition to my SDM responsibilities. The goal: achieve ISO 27001 and SOC 2 Type II certifications.

**A:** I mapped every system against the ISO 27001 control framework — identified 40+ gaps across encryption at rest, access control policies, and incident response. I personally designed DLP controls, defined the data classification policy, ran employee security training, and coordinated with external auditors. I enforced engineering changes: rotating KMS keys, enforcing mTLS between services, restricting DB-level write privileges on immutable ledger tables. Drove this end-to-end over 8 months while continuing to lead engineering delivery.

**R:** Achieved both ISO 27001 and SOC 2 Type II. The enterprise client signed. We went from zero certifiable security posture to two internationally recognized certifications — a competitive moat most FinTech startups our size didn't have.

**Follow-up:** *"What was the hardest part?"* → Getting engineering to treat security as a first-class delivery requirement, not a checkbox. I tied it to product milestones and made it non-negotiable.

---

### LP 2 — DELIVER RESULTS

**"Tell me about a time you delivered results under significant constraints."**

**S:** At Moneyview, the payments team was manually managing debit instruction retries — agents were literally re-triggering failed transactions from dashboards. We processed millions of debit instructions and the manual recovery time was killing SLAs. There were 2 engineers on the team including me.

**T:** Redesign the payment demand generation and reconciliation pipeline to eliminate manual intervention, without stopping production.

**A:** Designed and shipped idempotent retry logic with SLA-based scheduling — each debit instruction tracked its own retry state, backoff schedule, and terminal failure condition. Built reconciliation jobs that auto-detected discrepancies between what we submitted to payment gateways and what was acknowledged. Integrated 3 payment gateways in parallel with resilient, high-throughput pipelines designed so any single gateway failure didn't cascade.

**R:** Manual operations dropped **60%**. The team reclaimed ~20 engineer-hours per week. SLA breach incidents went to near zero. Also caught a systematic reconciliation discrepancy with one gateway that had been silently failing — something no human would have caught at volume.

---

### LP 3 — HIRE AND DEVELOP THE BEST

**"Tell me about a time you raised the bar on your team's technical capability."**

**S:** I joined Skydo as an **IC engineer** — the first engineering hire under a Head of Engineering I had previously worked with at Moneyview. For the first year, it was a small founding team of 5. As the product gained traction, I transitioned into the Engineering Manager role and owned hiring and engineer development. About a year ago, the Head of Engineering left — I stepped up and took full ownership of the entire engineering org, end-to-end: architecture, delivery, team growth, and technical direction.

**T:** Through each phase — IC, EM, and then sole engineering leader — continuously raise the bar on who we hired and how we developed engineers, in a domain where technical rigour directly maps to financial integrity.

**A:**
- **Hiring:** Took full ownership of the hiring process as we scaled. I designed interview scenarios around real FinTech problems — "how do you ensure a transaction is processed exactly once?" — hiring for slope over current altitude. I passed on candidates with strong résumés who couldn't reason through failure modes. Grew the team from 5 to 12 over roughly 2 years as EM.
- **Developing:** Engineers I hired were strong at shipping features but had limited exposure to production-grade distributed systems. I ran recurring internal design review sessions: idempotency, distributed locking, saga orchestration, circuit breaking. Created reference architecture documents and made design review mandatory for new services.
- **Mentored 8 engineers directly** — pairing on design, reviewing architecture, giving explicit feedback on reasoning quality, not just code.
- **GenAI standards:** Defined org-wide standards for AI coding assistants (Claude, Cursor, Windsurf) — not just "use AI," but how to validate AI-generated code in financial systems where correctness is critical.

**R:** Production incident rate dropped **~30% year-over-year**. Two engineers I hired and mentored took full module ownership independently — one was promoted. The team became self-sufficient on distributed systems decisions, which freed me to operate at the strategy level rather than being a technical bottleneck on every design.

**Follow-up:** *"What was your relationship with the Head of Engineering?"* → He was my manager from Moneyview and brought me in as the first engineer. As the team scaled, I took on the EM role and owned engineering delivery, hiring, and development — he focused on product and business alignment. About a year ago he left the company, and I stepped up to own the full engineering function. That transition forced me to operate at a higher level of ownership across strategy, cross-functional alignment, and org leadership — not just delivery.

**Follow-up:** *"What's a hire you're most proud of?"* → One engineer I hired came from a non-FinTech background. I took a bet on their problem-solving rigour. Within 18 months they owned our entire onboarding automation platform end-to-end — reducing onboarding time from hours to minutes.

---

### LP 4 — INSIST ON THE HIGHEST STANDARDS

**"Tell me about a time you refused to lower the bar, even under pressure."**

**S:** At Skydo, the business wanted to launch a new payment corridor feature in two weeks. Engineers proposed a simplified ledger model — a running balance column with no journal entries — because it was faster to build.

**T:** Make a call: ship fast with a fragile ledger model, or push back and do it right.

**A:** Rejected the simplified approach and explained the failure modes — running balance on a hot row creates a write bottleneck under concurrency; without journal entries, you can't audit, reconcile, or reverse transactions correctly. Designed the proper model: immutable journal entries, balance checkpoints, idempotent transaction submission. Committed to pairing with the team to ship it in the same two weeks. Reorganized the sprint so the ledger model was built first.

**R:** Shipped on time with the correct model. Six months later, a payment gateway failure caused duplicate submissions — our idempotency and reversal logic caught and corrected it automatically with **zero money lost**. The shortcut would have resulted in financial discrepancies we couldn't have reconciled.

> **Punchline:** "Technical shortcuts in financial systems don't show up immediately. They show up during the worst incident at the worst time. My job is to make sure the team never has to make that trade-off."

---

### LP 5 — CUSTOMER OBSESSION

**"Tell me about a time you advocated for a customer outcome even when it was technically harder."**

**S:** At Skydo, SME onboarding was a major friction point. Business owners had to submit documents and wait hours — sometimes a full business day — before their account was activated and they could start transacting internationally.

**T:** Fully automate onboarding for multiple business entity types: Sole Proprietorships, LLPs, Private Limited companies, Partnerships, SMEs — each with different regulatory requirements.

**A:** Designed fully automated onboarding pipelines per entity type. Integrated with government databases for document verification. Built a configurable rule-based decisioning engine (not hardcoded per type) and async workflow orchestration so each step — document upload, KYC check, sanctions screening, account creation — could run in parallel and recover from partial failures.

**R:** Onboarding time dropped from **hours to minutes** for most entity types. Customer drop-off during onboarding decreased significantly. The manual review team was redeployed to handle only edge cases. Faster activation directly increased revenue.

---

### LP 6 — DIVE DEEP

**"Tell me about a time you had to go deep into a problem that wasn't immediately obvious."**

**S:** At Goldman Sachs, the distributed market risk aggregation platform was intermittently producing stale VaR numbers under high load. We couldn't reproduce it consistently.

**T:** Stale risk numbers are a regulatory and financial exposure issue. I had to find root cause, not just patch symptoms.

**A:** Instrumented the in-memory distributed compute clusters with detailed latency histograms and capture points at each aggregation stage. Found that during market open — when data ingestion spikes — a sharding imbalance caused specific nodes to fall behind on replication. The primary was acknowledging writes before the standby confirmed. During failover, a small window of data could be lost. The VaR calculation was also reading from replicas with no staleness check.

**R:** Fixed the synchronous replication configuration, implemented a staleness check on the read path, and improved the sharding algorithm to rebalance by throughput rather than key count. Stale VaR issue was eliminated. Wrote a post-mortem adopted by the global risk platform team across other regions. **Promoted to VP within 1 year.**

---

### LP 7 — HAVE BACKBONE; DISAGREE AND COMMIT

**"Tell me about a time you disagreed with a decision and what you did."**

**S:** At Skydo, leadership pushed to migrate our primary database from PostgreSQL to DynamoDB, influenced by an investor who believed we should be "all-in on AWS."

**T:** I strongly disagreed. Our payments ledger relied on ACID transactions, JOINs for reconciliation, and row-level locking for balance checks — things DynamoDB either doesn't support or makes significantly more complex.

**A:** Prepared a detailed technical trade-off document — not "DynamoDB is wrong" but specifically: what we'd lose (strong consistency, multi-row transactions, reconciliation queries), what we'd gain (managed scaling), and the engineering cost to replicate PostgreSQL's guarantees in DynamoDB. Presented with concrete examples of how financial integrity bugs would manifest post-migration. Proposed a middle ground: migrate non-financial workloads to DynamoDB where eventual consistency was acceptable.

**R:** Leadership agreed to keep PostgreSQL for the ledger. Non-financial workloads — event logs, session data — moved to DynamoDB. This became our official data architecture policy. The key was making technical risk visible and quantifiable, not a personal preference.

---

### LP 8 — INVENT AND SIMPLIFY

**"Tell me about a time you simplified something others assumed required complexity."**

**S:** At Skydo, the team was discussing building a custom workflow engine for orchestrating multi-step financial operations — estimated at 3–4 months of engineering.

**T:** Questioned whether we actually needed that level of complexity.

**A:** Analyzed what we actually needed: reliable retries, state persistence, step execution visibility, failure recovery. Evaluated existing tooling. Designed a simpler approach: a distributed job scheduler built on top of existing infrastructure (SQS + PostgreSQL for state) with a thin abstraction layer. It didn't cover every orchestration edge case, but it handled 95% of use cases with 20% of the build complexity.

**R:** Shipped in **3 weeks instead of 4 months**. Running in production for over a year with minimal changes. Lesson: don't build for hypothetical complexity — build for the actual problem, design the abstraction so it can evolve.

---

## PART 3: SYSTEM DESIGN (Bluescape)

> **SDM framing:** You're evaluated on judgment and leadership, not just architecture. Verbalize trade-offs. Connect technical decisions to customer and business impact. Ask clarifying questions first.

---

### Option A: Design a Payment Processing / Ledger System

**Opening move (always do this first):**
> "Before I draw anything — let me clarify scope. Are we designing the ledger layer, the payment gateway integration layer, or both? What's our scale target? What's our consistency requirement? What does the client interface look like?"

**Key points to hit:**

| Decision | What to say |
|---|---|
| **Double-entry bookkeeping** | Every debit has a corresponding credit. `∑ DEBIT == ∑ CREDIT`. Money is never created or destroyed. Non-negotiable. |
| **Idempotency** | Client-provided `idempotency_key`. Check Redis (fast path) → DB (correctness fallback). Return 200 on duplicate, never 409. |
| **Atomicity** | Transaction + journal entries in a single DB transaction. No partial commits. |
| **Balance computation** | NOT a running balance column (hot row under concurrent writes). Use balance checkpoints + delta from journal entries + Redis cache. Authoritative balance: primary DB with `SELECT ... FOR UPDATE`. |
| **Scalability** | Shard by `ledger_group_id`, not `account_id` — co-locates related accounts, avoids cross-shard distributed transactions. Hot accounts use shadow/bucket accounts. |
| **Consistency** | Strong, synchronous replication. RPO = 0. Eventual consistency is not acceptable for money. |
| **Reconciliation** | Async job runs hourly: `∑ DEBIT == ∑ CREDIT` across all accounts. P0 alert if drift detected. |
| **Money type** | `BIGINT` in minor units (cents). Never `FLOAT` — floating point is non-deterministic. `$1.10 as float64 = 1.09999...` |
| **Pagination** | Cursor-based, not OFFSET — stable under concurrent inserts. |
| **DB choice** | PostgreSQL over Cassandra/DynamoDB — ACID, JOINs for reconciliation. |

**Architecture to draw on Bluescape:**

```
Client Layer (Payment Service, Wallet, Billing)
        │ gRPC / REST
API Gateway (Auth, Rate Limiting, TLS)
        │
   ┌────┴────────────────────────┐
   │                             │
Ledger Write Service        Ledger Read Service
(Transaction Processor)     (Balance, History)
   │                             │
   └────────────┬────────────────┘
                │
        Primary PostgreSQL ──► Read Replicas (2–3)
                │
        Kafka (transaction.posted)
                │
        Redis (Balance Cache)
```

**Trade-offs to verbalize explicitly:**
- "I chose synchronous replication over async because in FinTech, losing even one transaction is a regulatory issue — no latency gain justifies that."
- "I use checkpoints + delta over a running balance column because under concurrent writes, a hot row requires row-level locking and destroys throughput."
- "I shard by `ledger_group_id` so a merchant's accounts live on the same shard — eliminates the need for cross-shard distributed transactions in the common case."

---

### Option B: Design a Reconciliation System

1. Two levels: **(a) internal integrity** — do journal entries sum to zero; **(b) external reconciliation** — does our ledger match bank/gateway reports.
2. T+1 batch jobs — pull settlement files from banks/gateways, parse, match against our transactions by external reference ID.
3. Exception handling — unmatched items go to a suspense account, flagged for human review.
4. Every reconciliation run produces an immutable report stored in cold storage (S3 with Object Lock for compliance).

---

### Option C: Design a Fraud Detection System

1. Real-time scoring (< 100ms) + batch ML model training (offline).
2. Feature store — velocity features: count of transactions in last 1h/24h per user/card, behavioral baseline.
3. Rule engine (deterministic, fast) + ML model (probabilistic, richer signals). Rule engine blocks obvious fraud; ML flags borderline for review.
4. Don't block the payment path on fraud check — return a risk score, let the payment system decide the threshold per transaction type.
5. Feedback loop — chargebacks feed back into model retraining.

---

## PART 4: MANAGEMENT-SPECIFIC QUESTIONS

**"How do you balance technical depth with management responsibilities?"**

> "I stay hands-on at the architecture level — I own or review every significant technical decision. I write code during spikes when I need to validate a design assumption. But my highest leverage isn't writing production code — it's defining standards that 6 engineers execute consistently, and unblocking decisions. I've deliberately built a team where engineers own modules end-to-end so I'm not the bottleneck."

**"Tell me about your approach to hiring."**

> "I hire for slope, not current altitude. I want to understand how someone thinks through ambiguous problems — not whether they know the right answer. I listen for: do they ask clarifying questions, do they surface trade-offs voluntarily, can they defend a decision when pushed? I've passed on strong engineers who couldn't explain the 'why' behind their choices, and hired less experienced engineers who could think out loud rigorously."

**"How do you handle underperformance?"**

> "Direct and early. The worst thing you can do is let it linger. I start with a direct conversation — name the gap specifically, not generally. We agree on what 'good' looks like in 30 days and check in weekly. Most underperformance issues are actually clarity issues — the person didn't know exactly what was expected."

**"How do you manage up and communicate risk?"**

> "I treat risk as probability × impact, and I make it visible rather than managing it silently. When we had a potential data integrity issue in the payments pipeline at Skydo, I escalated immediately with a written summary — current state, impact scope, what we were doing to fix it, ETA. Leadership can handle bad news. What they can't handle is surprise. I send a weekly 3-line status for anything with a 2+ week horizon: what shipped, what's blocked, what I need."

---

## PART 5: QUESTIONS TO ASK SAURABH

> Ask 2–3 of these. They signal you think like an SDM, not just a candidate.

1. **"What does the engineering org structure look like for the FinTech team — how many SDMs, and how do they partition ownership?"**
2. **"What's the biggest technical challenge the FinTech team is working through right now — scale, compliance, reliability, or something else?"**
3. **"How does Amazon measure engineering excellence for SDMs — delivery metrics, team growth, or system reliability?"**
4. **"What would success look like in the first 6 months for someone in this role?"**
5. **"What's the on-call and operational culture like for FinTech systems — how does the team handle production incidents?"**

---

## QUICK REFERENCE: LP → YOUR STORY MAPPING

| Leadership Principle | Your Story | Key Metric |
|---|---|---|
| Ownership | CIO role → ISO 27001 + SOC 2 at Skydo | 2 certifications, enterprise client signed |
| Deliver Results | Moneyview payment pipeline redesign | 60% reduction in manual ops |
| Hire and Develop the Best | First engineer at Skydo — built team 0→12, defined hiring bar, mentored 8 directly | 30% fewer incidents, 2 engineers promoted |
| Insist on Highest Standards | Rejected ledger shortcut → saved from incident | Zero money lost during gateway failure |
| Customer Obsession | Automated SME onboarding at Skydo | Hours → minutes |
| Dive Deep | Goldman Sachs stale VaR root cause investigation | Promoted to VP within 1 year |
| Have Backbone | Pushed back on DynamoDB migration | PostgreSQL retained; DynamoDB for non-financial |
| Invent and Simplify | Job scheduler: simple over custom orchestration engine | 3 weeks vs. 4 months |
| Think Big | Payments + settlement platform architecture at Skydo | 10K+ international transactions/day |
| Are Right, A Lot | Double-entry ledger vs. running balance decision | Prevented unrecoverable data integrity bug |

---

## DAY-OF CHECKLIST

- [ ] Test Chime at https://app.chime.aws/check the morning of
- [ ] Open and test Bluescape: https://client.ext.bluescape.ee-infra.aws.dev/BpF64-XT9g35HxfCv96x
- [ ] Have pen + paper as backup for system design if Bluescape has issues
- [ ] Join Chime exactly at 11:00 AM IST — waiting room times out after 10 minutes
- [ ] Use a headset for better audio quality
- [ ] Keep mobile nearby — interviewer has +91 9632155404 for technical fallback

---

## KEY REMINDERS

1. **LP-first answers.** Every STAR story maps to a principle. Optionally name it at the end: *"This is really about ownership for me."*
2. **Always quantify.** 30% fewer incidents. 60% reduction in manual ops. Hours to minutes. 10K transactions/day.
3. **For system design, lead with trade-offs.** Don't just describe what you'd build — say why you'd choose it over the alternative, in the context of FinTech requirements.
4. **Ask clarifying questions before drawing.** It signals seniority. Junior candidates dive straight into boxes.
5. **You have a genuinely strong FinTech story.** Payments, ledger, reconciliation, idempotency, ISO 27001 — this is exactly what Amazon FinTech values. Don't undersell it.
