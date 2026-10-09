# Safe Security — Interview with VP Engineering (Hitesh Sethi)

**Company:** [Safe Security](https://safe.security) (SAFE One) — Palo Alto HQ, large Bengaluru engineering org  
**Interviewer:** [Hitesh Sethi](https://www.linkedin.com/in/sethihitesh) — **Vice President, Engineering**  
**Prepared for:** Ramit Hansda (EM / Staff+ IC; Goldman VP risk infra; Skydo payments + CIO)  
**Companion design bank:** if the loop also has a DOE round, use [`SAFE-SECURITY-SYSTEM-DESIGN-INTERVIEW.md`](./SAFE-SECURITY-SYSTEM-DESIGN-INTERVIEW.md) (telemetry, knowledge graph, attack path, FAIR CRQ). This doc is for the **VP hour**.

**How to use:** Practice the spoken answers out loud once. In the room, hit the same beats — do not recite. If he interrupts, finish the current beat, then follow his poke.

---

## 1. Who he is (30-second briefing)

| | |
|---|---|
| Role | VP Engineering @ SAFE — leads eng across **TPRM, CTEM, CRQ**, and **AI-first / agentic** platform work |
| Scope | ~**100+** engineers across Bengaluru + Silicon Valley (CEO posts; his brand says 80+) |
| Path at SAFE | EM (2021) → SEM → Director → **VP (Sep 2025)** |
| Prior | Engineering Lead / SWE at Rapyuta Robotics (cloud robotics); patents in cyber-robotics |
| Public signal | Wrote SAFE Engineering Medium post on **measuring AI agent accuracy**; posts that **demos ≠ production**; hiring Principal / EM / Staff Attack-Path / Data |
| CEO framing (Saket Modi) | Deeply **hands-on** architect who still writes/tests new services while running the org. SAFE rejects “management by negotiation” |

**What that means for your hour:**

1. He will **smell pure people-manager** in under five minutes. Stay close to systems, numbers, failure modes.
2. He scores **trade-offs, not absolutes** — his Principal JD language. Never “Kafka solves X”; say *why this queue, this key, this consistency, this cost*.
3. He cares about **agentic systems with guardrails** — evals, reproducibility, explainability — not ChatGPT bolted onto a ticket.
4. He scaled 50 → 100+ eng fast. Expect questions on **ambiguity, structure, when to step in vs step out**.
5. Culture is **high bar + high ownership + warp speed**. Decide your pace boundary before you walk in; do not perform 9-to-9 theater and do not trash hustle.

---

## 2. What this round is scoring

SAFE’s public loop (recruiter → DSA → DOE → HM/CEO → HR) puts a senior leader somewhere between **hiring manager** and **executive culture**. With Hitesh specifically, treat it as a **technical leadership panel**, not a soft chat.

| Dimension | Pass signal | Fail signal |
|---|---|---|
| Hands-on depth | Can draw a system you owned, name the hard failure, defend the trade-off | “I empower the team” with no architecture |
| Product fit | Maps payments/risk/identity → ingest, graph, quantified risk, audit | Generic SaaS CRUD talk |
| Agentic AI judgment | Evals, gold sets, fail-closed actions, human-in-loop | “We’ll use LLMs to auto-remediate everything” |
| Org / delivery | Predictable shipping, RCA, bar-raising forums | Status meetings as craft |
| Pace / ownership | Extreme ownership on outcomes + sustainable systems | Bitterness about hours *or* performative martyrdom |
| Influence without title | Architecture influence via ADRs, reviews, written judgment | “I need the VP title to drive change” |

**Role note:** He hires for EM, Principal, Staff Attack-Path, Data. If your loop is EM-shaped, lean **people + delivery + technical judgment**. If Principal/Staff-shaped, lean **architecture + multi-team influence + production AI/data**. The spoken answers below cover both; pick the default track before the call and mark it.

---

## 3. SAFE in 45 seconds (say this if he asks “what do you know about us?”)

> SAFE One sits **on top of** CrowdStrike / Qualys / cloud / identity tools — it does not replace them. It ingests telemetry from 150+ connectors, builds a cybersecurity knowledge graph, then does three jobs boards actually buy: **CTEM** (continuous exposure), **attack-path** prioritization, and **FAIR-based CRQ** so risk is a **dollar number**, not a red/yellow/green. Same machinery applied to **1M+ third parties** in TPRM, plus AI-SPM. The product thesis is a **closed loop**: data → risk → controls → agentic actions, with guardrails. Series C / Cyber AGI language on the outside; multi-tenant SaaS + lakehouse + graph + workflow engine on the inside.

**Your one-line mapping:**

> “Goldman taught me how to turn messy high-volume data into a defensible risk number. Oracle IDCS taught me enterprise identity. Skydo taught me multi-party integrations with idempotency and audit — and as CIO I was the *buyer* of cyber controls. SAFE is those three problems in cyber.”

---

## 4. Opening pitch (~90 sec) — memorize shape

**SAY THIS:**

> I’m Ramit — Engineering Manager with ten-plus years in fintech and large-scale financial systems, and I’m still close to the metal.
>
> At Skydo I lead twelve engineers on our international payments and settlement platform — about ten thousand plus transactions a day — with hard guarantees on idempotency, reconciliation, and failure recovery. I own hiring, delivery, and the technical bar. I also serve as CIO and led ISO 27001 and SOC 2 Type II.
>
> Before that I was a VP at Goldman Sachs leading a nine-person team on distributed market-risk aggregation — multi-terabyte in-memory clusters for VaR and stress testing. Promoted to VP in under a year.
>
> Earlier I was lead developer on Oracle Identity Cloud Service — distributed identity for enterprise cloud customers.
>
> I’m talking to SAFE because the product is the intersection of what I’ve already lived: high-volume ingest, identity-aware systems, quantified risk, and a compliance buyer’s view of what CISOs actually need. Happy to go deep on architecture, people, or agentic/production AI — whichever is most useful for this hour.

**30-sec version:**

> EM, ten-plus years. Skydo: twelve eng, payments/settlement 10K+/day, CIO for ISO 27001 + SOC 2. Goldman VP on market-risk compute. Oracle IDCS. SAFE fits because it’s ingest + identity + quantified risk with agentic action — and I want a hands-on leadership seat, not a negotiation layer.

---

## 5. “Why SAFE?” / “Why this role?” / “Why now?”

**Why SAFE (3 beats):**

> First — I’ve been on the **buyer** side. As CIO I owned ISO 27001 and SOC 2. CISOs don’t need another CVE dashboard; they need a **defensible number** and a ranked path to reduce it. That’s SAFE’s thesis.
>
> Second — the engineering problem is my career shape: high-volume ingest, multi-tenant trust boundaries, identity, and a score that must be **explainable and replayable** — same muscle as VaR/stress at Goldman and settlement at Skydo.
>
> Third — the leadership culture Hitesh and Saket describe publicly matches how I already operate: **hands-on at the hard seams**, raise the bar through forums and ADRs, and ship with ownership — not management by status updates.

**Why now / why leave Skydo:**

> Skydo was a strong chapter. I built the team, got payments into a stable high-throughput state, and cleared the compliance bar. The next step I want is a **larger surface** — more engineers, harder multi-tenant security-data problems, and an AI-native product loop — without leaving correctness-and-trust work I care about. SAFE is that.

**Don’t say:** compensation first, “looking for change,” burnout, or “I want a bigger title.”

---

## 6. Hardworking 1–10 / pace / “can you keep up?”

Multiple SAFE reports treat **“Rate your hardworking level 1–10?”** as a culture probe. Expect **10**, and “why not 10?” as a trap. Do not say 9.5 and philosophize.

**SAY THIS:**

> Ten on ownership when a customer, an auditor, or a board number is waiting — I’ve been on-call for money-moving systems and for SOC 2 evidence packs. I also run teams so that intensity is **sustainable**: we measure toil, we automate the boring pain, and we protect correctness under deadline pressure by cutting scope, not by silent quality debt. I’m not interested in performative hours. I am interested in outcomes with extreme ownership.

**Fast-paced / customer in the loop (STAR shape):**

> Situation: payments go-live or certification deadline with a partner and a real customer waiting.  
> Action: I cut non-critical scope, put a written risk register in front of Product/Compliance, protected idempotency and audit on the money path, staffed a war-room with clear owners.  
> Result: shipped on the date that mattered; zero audit findings / no double-settlement incidents.  
> Lesson: speed without a correctness boundary is just future incident debt.

**Decide your boundary offline.** Public reviews describe long hours. Do not volunteer bitterness. Do not pretend you want theater if you don’t. If asked about work-life: reframe to **outcome ownership + sustainable on-call + ruthless prioritization**.

---

## 7. Walk me through a system you own (default deep dive)

**Pick Skydo payments/settlement** — closest analog to SAFE (many external systems, at-least-once delivery, a number that cannot be wrong, audit trail).

**SAY THIS:**

> I’ll take Skydo’s payments and settlement platform.
>
> Context: cross-border money movement. Customer, our ledger, banking partners, FX, compliance — partners are not in our database transaction. The hard problem isn’t “call the bank API.” It’s getting the right **business effect** under retries, timeouts, and concurrency — without double-paying or losing money in a silent gap.
>
> Design:
> One — every money-moving intent is durable first. Persist the intent with an idempotency key before any side effect.  
> Two — critical sections like settle-or-payout are protected with distributed locks keyed by the business entity, with fencing so a lock expiry can’t cause double execution.  
> Three — partner calls and long workflows run through an async job system so retries are visible and operable.  
> Four — reconciliation is first-class: a ladder — intent vs debit, debit vs FX, FX vs remittance — so we know *where* a break happened. Partner files are external truth; exceptions go to an ops queue.
>
> Result: ~10K+ international transactions/day. Production incidents down ~30%. Unreconciled amount from ~0.6% of daily TPV to under 0.02%.
>
> Lesson: design for partial failure first. And you need a shared state vocabulary across Eng, Finance, and Ops — otherwise you’re debugging Slack forever.
>
> If useful, I can map the same patterns onto SAFE: connectors as partners, findings as intents, graph/CRQ as the “number that cannot be wrong,” and audit as the evidence trail.

**If he pokes “exactly-once?”**

> I don’t claim magic exactly-once delivery. We aim for exactly-once **business effect**: durable intent, idempotent handlers, dedupe on partner refs, reconciliation to catch what the happy path misses. Same framing I’d use for telemetry upserts: at-least-once ingest + idempotent `(tenant_id, source, native_id)`.

**Alternate deep dive — Goldman VaR / stress (use if he steers to CRQ):**

> Petabyte-scale market data into multi-terabyte in-memory clusters. The product is a **defensible risk number** under partial shard loss and late data. Sharding + replication + recon-as-a-product. Same shape as FAIR CRQ: inputs labelled, runs replayable, evidence attached, don’t recompute the world on every dashboard GET.

**Alternate — Oracle IDCS (use if he steers to multi-tenant / identity / attack path):**

> Enterprise identity cloud: sessions/tokens, multi-tenant IAM, central authz with distributed enforcement. Maps to SAFE tenant isolation, RBAC on findings/workflows, and identity edges in the attack graph.

---

## 8. Light architecture probes he may throw (VP-shaped)

He may not run a full DOE. He may poke one SAFE-shaped problem for **judgment**. Keep answers to 3–5 minutes unless he pulls you deeper. Full spoken designs live in the companion system-design doc.

### 8.1 Telemetry / connectors at scale

**Principles to say:** ingest is a dumb durable log; parsing is replayable; `tenant_id` on every record and Kafka key; at-least-once + idempotent upsert; connectors are plugins, bus + lake are the platform.

**Fail closed vs fail open:** authz fails closed; **ingestion buffering fails open** so you don’t drop customer telemetry.

### 8.2 Knowledge graph / attack path

Answer with **edges and controls**, not weaponization: identity membership, network reachability, known CVE presence, control effectiveness. Incremental recompute on change. Shard by tenant; inside huge tenants shard by site/VPC and stitch at trust boundaries.

### 8.3 FAIR CRQ scoring

Likelihood × magnitude; Monte Carlo offline; every input tagged `measured | inferred | questionnaire | industry-prior`; scores must be **explainable and replayable**; garbage-in is labelled, not hidden.

### 8.4 Agentic remediation / TPRM agents

This is **his** language — use it.

> Demo agents that summarize questionnaires are easy. Production agents that change risk posture need: gold datasets, precision/recall/completeness/reproducibility, prompt/version control like code, eval gates on change, confidence + citation, and **human approval before any irreversible action** (open Jira that pages someone is different from auto-closing a finding). Autonomy with guardrails — not assistants, not unsupervised mutants.

Mirror his Medium post vocabulary: **TP/FP/FN, field accuracy, reproducibility error, Δ metrics on prompt change**.

### 8.5 Multi-tenant isolation

Named F500 may need dedicated stacks; everyone needs `tenant_id` on every table/key, authz at the edge, append-only audit for score changes and agent actions.

---

## 9. Leadership / EM track spoken answers

### 9.1 How do you raise the bar?

> I don’t believe in hero mentoring. I build forums. At Skydo: design-doc-first for anything touching money, ADRs for irreversible choices, seniors paired in reviews, 1:1 growth plans tied to skills. Mentored eight engineers directly; design-review coverage on major changes from ~30% to ~100%. Good judgment becomes the default path.

### 9.2 Underperformance / hard feedback

> Strong IC, dismissive in reviews — eroded safety. Private, with receipts: dates, threads, impact framed. Thirty/sixty/ninety behavior goals. Shadowed next reviews. Outcome: either course-correct with dignity or exit with clarity. Never ambush in public; never let toxicity hide behind “high performer.”

### 9.3 Hiring bar

> Rubric before the loop. Same-day written feedback before debrief. Hire for systems thinking + ownership evidence, not crossword DSA only. At SAFE’s pace I’d still protect the bar — a wrong senior hire compounds faster than a empty seat.

### 9.4 Delivery when Product wants everything

> Written problem statement, forced rank, capacity math, and a kill list. At Skydo, compliance vs onboarding speed: we shipped a flagged beta, then full in five weeks, zero audit findings. I quantify risk; I don’t pick a tribal side.

### 9.5 First 90 days (if EM / senior leader seat)

> Days 1–30: listen — 1:1s, map reliability risks, customer-facing SEVs, where ambiguity compounds.  
> Days 31–60: share a candid state-of-eng note — top 3 tech risks, top 3 delivery risks, hiring gaps.  
> Days 61–90: one structural bet (platform seam, on-call, or design forum) and one shipped customer outcome. I don’t reorganize for sport.

### 9.6 Scaling from ~12 to a larger org (he lived 50 → 100+)

> Ambiguity compounds faster than headcount. Structure absorbs chaos. Metrics when instincts stop scaling. Step in on irreversible architecture and cultural violations; step out when the team can decide. Relationships across India/US matter as much as org charts. I’d ask him what broke first when SAFE doubled — that’s usually where the next leader can help.

### 9.7 AI coding assistants (Cursor / Claude) — he lists this on EM JDs

> At Skydo I defined standards for Claude/Cursor/Windsurf: where AI is allowed, what must still be human-reviewed (money paths, authz, migrations), and how we measure velocity without shipping confident garbage. Same philosophy as product agents: **assist with guardrails and eval**, not vibe-deploy.

---

## 10. Principal / Staff track spoken answers

### 10.1 How do you influence architecture without a title?

> Written ADRs, design reviews with a clear decision owner, and showing up in production incidents with a systemic fix — not a Slack opinion. At Goldman and Skydo I drove sharding/recon and payments correctness by making the trade-off visible and owning the outcome.

### 10.2 Correctness, cost, latency, reliability together

> Pick the SLO that is the product. For CRQ/board numbers: correctness and explainability beat p99 of a dashboard. For connector ingest: durability and replay beat pretty freshness charts. Cost is a first-class constraint at Series C — Iceberg/lake for cold, hot entity store for serving, don’t put the graph on the write path for every event.

### 10.3 What would you change in a multi-agent risk platform?

> Unify the evidence model first. Agents without a shared canonical entity layer and eval harness become a demo zoo. I’d invest in: (1) tenant-safe data contracts, (2) gold sets per agent family, (3) action policy engine (what agents may do unsupervised), (4) end-to-end trace from finding → score → ticket → verified risk drop.

---

## 11. “Give me 3 reasons to hire you”

**SAY THIS (~60–90 sec):**

> Three reasons.
>
> First — I’ve shipped systems at the scale and correctness bar SAFE needs, and I can still sit in the design review. Goldman VP on petabyte-scale risk infra; Skydo payments with idempotency and recon; Oracle identity. I’m not a leader who drifted from the metal.
>
> Second — I’ve run the hard adjacent problems end-to-end: multi-party integrations, audit, and as CIO I led ISO 27001 and SOC 2. I know what a CISO and an auditor actually ask for when a number must stand up.
>
> Third — I raise the engineering bar in a way that survives me: ADRs, review culture, mentored eight engineers, GenAI standards with guardrails. SAFE’s culture — hands-on, high ownership, agentic systems with rigor — is how I already work.

---

## 12. Questions to ask him (pick 3)

Strong questions (signal seniority):

1. **Where does accuracy break today** in agentic TPRM / CTEM — data quality, eval harness, or action policy?
2. When you scaled **50 → 100+**, what broke first: architecture ownership, EM craft, or cross-US/India decision latency?
3. For Principal/Staff seats: what does **“raise the bar for the entire org”** look like in the first two quarters — concrete artifacts, not vibes?
4. Which platform seam is most under-invested: **connector framework, graph, scoring, or workflow/agents**?
5. How do you decide what agents may do **unsupervised** vs human-approved — and how is that enforced in code?
6. What’s the hardest production incident pattern you’re still paying for — poison connectors, tenant noisy-neighbor, score drift, or agent hallucination?

Avoid: “What’s the culture like?” (you already know: intense). Avoid compensation in this round. Avoid asking him to explain FAIR from scratch.

---

## 13. Rapport hooks (light, not flattery)

- Shared Bengaluru engineering-leadership path; he grew EM → VP inside SAFE — speak as a peer builder, not a fan.
- His Medium accuracy post + your CIO/audit experience = natural bridge on **evidence and evals**.
- Goldman VaR ↔ FAIR CRQ is a clean intellectual bridge — use once, don’t overplay.
- GenAI coding standards (Cursor/Claude) appear on his EM JD — you have a real Skydo story.

Do **not** name-drop Saket Modi birthday posts or over-quote his LinkedIn. Show you’ve done homework by **how you think**, not by reciting bios.

---

## 14. Anti-patterns (instant miss with this interviewer)

| Don’t | Do instead |
|---|---|
| “I haven’t coded in years; I enable people” | Own one hard system end-to-end with numbers |
| “We’ll use Kafka + LLM” | Name consistency, keys, evals, fail modes |
| Weaponization / exploit talk | Graph edges + control effectiveness |
| Claim exactly-once ingest | At-least-once + idempotent upsert |
| Trash previous employers | Clean “why now” narrative |
| Performative 90-hour brag | Ownership + sustainable intensity |
| Vague Cyber AGI mysticism | Closed loop: data → risk → controls → guarded actions |
| Skip tenant isolation | `tenant_id` everywhere; trust boundaries |

---

## 15. Metrics card (memorize)

| Metric | Number |
|---|---|
| Skydo team | 12 eng; mentored 8 |
| Payments volume | 10K+ intl txn/day |
| Reliability | Incidents ~−30% |
| Recon quality | ~0.6% → &lt;0.02% daily TPV |
| Compliance | ISO 27001 + SOC 2 Type II as CIO |
| Goldman | VP &lt;1 year; 9 eng; multi-TB risk compute; PB-scale market data |
| Identity | Oracle IDCS lead |
| AI craft | Claude / Cursor / Windsurf standards at Skydo |

---

## 16. Sources & further reading

**Interviewer / culture**
- [Hitesh Sethi — LinkedIn](https://www.linkedin.com/in/sethihitesh) — VP Eng; hiring posts for Principal, EM, Staff Attack-Path, Data
- [SAFE About — leadership bio](https://safe.security/about-us/) — TPRM/CTEM/CRQ + AI-first platform
- [Saket Modi on Hitesh](https://www.linkedin.com/posts/samodi_safe-would-simply-not-be-where-it-is-today-activity-7422255730545606656-oTsw) — hands-on VP; 100+ eng; warp-speed product
- [Building Trustworthy AI Agents (accuracy)](https://medium.com/safe-engineering/building-trustworthy-ai-agents-a-deep-dive-into-measuring-accuracy-8f33ce41b2a2) — his eval framework
- [Why SAFE’s AI Is Ahead](https://safe.security/resources/blog/why-safes-ai-is-ahead-of-the-industry/) — specialized agents, privacy, continuous eval

**Process (same company, lower levels — culture probes still apply)**
- [LeetCode — SDE-I Data](https://leetcode.com/discuss/post/7345521/safe-security-sde-i-data-interview-exper-q467/) — hardworking 1–10; parking lot; pipeline
- [Satvik Singh — interview experience](https://www.linkedin.com/posts/satvik-singh-3989a51b5_safesecurity-interviewexperience-dsa-activity-7297110036974055424-ohL3) — DOE + CEO pace questions; back-to-back rounds

**Product assumptions**
- [SAFE One](https://safe.security), integrations, FAIR, CTEM/CRQ/TPRM public materials

*If you share the exact role (EM vs Principal AI vs Staff Attack-Path vs Data) and JD link, narrow this to one 45-minute script the way the Dezerv / Jumio preps are.*
