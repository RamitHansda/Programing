# Safe Security — System Design Interview Questions

**Company:** [Safe Security](https://safe.security) (SAFE One) — Palo Alto HQ, large Bengaluru engineering org  
**Product:** Autonomous cyber risk quantification and exposure management (CRQ + CTEM + TPRM + AI-SPM)  
**This doc:** Question bank + spoken HLD answers for **system design / DOE** rounds  
**Prepared for:** Ramit Hansda (EM / Staff+ IC; payments, identity, risk data, ISO 27001 / SOC 2)

Sources are labelled **reported** (candidate write-ups) vs **team-likely** (current JDs + public product architecture). Do not treat team-likely prompts as confirmed; treat them as the problems a serious interviewer *should* ask if they want to see whether you understand the product.

Companion LLD notes already in this repo (reuse if they throw a generic problem):

| If they ask | Use |
|---|---|
| Parking lot | [`docs/lld/staff-engineer-interviews/02-parking-lot.md`](../lld/staff-engineer-interviews/02-parking-lot.md) |
| Movie / show booking | [`docs/lld/staff-engineer-interviews/08-movie-seat-booking.md`](../lld/staff-engineer-interviews/08-movie-seat-booking.md) |
| Rate limiter | [`docs/lld/staff-engineer-interviews/01-rate-limiter.md`](../lld/staff-engineer-interviews/01-rate-limiter.md) |
| Notification service | [`docs/lld/staff-engineer-interviews/09-notification-dispatcher.md`](../lld/staff-engineer-interviews/09-notification-dispatcher.md) |
| Large-scale pipeline | [`docs/data_eng/HLD-Large-Scale-Data-Pipeline.md`](../data_eng/HLD-Large-Scale-Data-Pipeline.md) |
| Secure API gateway | [`docs/lld/api-gateway/SECURE_API_GATEWAY_DESIGN.md`](../lld/api-gateway/SECURE_API_GATEWAY_DESIGN.md) |
| Auth / sessions | [`docs/skydo-tech/auth_system.md`](../skydo-tech/auth_system.md) |

---

## 1. What they actually build (say this in 45 seconds)

SAFE One is a **multi-tenant B2B security-data platform**. It does **not** replace CrowdStrike / Qualys / Palo Alto. It sits **on top** of 150+ existing tools, ingests their telemetry, builds a **cybersecurity knowledge graph**, then:

1. **CTEM** — continuous exposure: assets, findings, controls, identities, network reachability  
2. **Attack-path intelligence** — which chains of identity + network + vuln actually reach a crown-jewel asset  
3. **CRQ** — FAIR-based financial loss exposure (likelihood × magnitude), not a red/yellow/green score  
4. **TPRM** — same machinery applied to 1.3M+ third parties  
5. **AI-SPM** — AI-vendor and model-surface exposure  
6. **Agentic workflows** — turn a prioritized finding into a Jira / ServiceNow campaign, then verify risk dropped

Public scale signals to use as design assumptions (state them out loud):

| Signal | Number | Design implication |
|---|---|---|
| Series C | ~$170M | Enterprise SaaS, not a toy |
| Customers | ~10% of Fortune 500 (Apple, Netflix, AT&T, Verizon cited on JDs) | Multi-tenant isolation, data residency, audit, 99.9%+ |
| Connectors | 150+ MCP/API integrations | Connector framework + schema evolution is the platform |
| Telemetry | “billions of events/day” on the SDE II Data JD | Kafka + lakehouse, not “cron + Postgres” |
| Third parties | 1.3M vendors in TPRM (CEO talks) | Graph + search at internet-vendor scale |
| Agents | 100+ AI agents, 1,000+ workflow templates | Workflow engine + guardrails, not a chatbot bolted on |

**Your mapping, in one sentence:**

> “Goldman taught me how to turn messy high-volume market data into a defensible risk number. Oracle IDCS taught me enterprise identity. Skydo taught me multi-party integrations with idempotency and audit. SAFE is the same three problems — ingestion, identity-aware graph, quantified risk — in cyber.”

---

## 2. How the loop is structured

Public reports (2024–2026) are consistent enough to treat this as the default loop. Senior / Staff / EM loops keep the same shape and raise the bar on **trade-offs, failure modes, and product-domain depth**.

| Round | Duration | What happens |
|---|---|---|
| Recruiter / HR screen | 15–30 min | Why change, scale of systems, **CS fundamentals** (TCP vs UDP, SQL vs NoSQL, process vs thread, CAP), and the signature culture question: *“Rate your hardworking level 1–10”* |
| Technical 1 — DSA + backend | 45–60 min | 1–2 medium DSA problems (graphs/trees/DP/intervals show up). Plus Spring Boot / Node concurrency, caching, REST, resume deep-dive |
| Technical 2 — **DOE / system design** | 45–60 min | HLD first, often **drops into LLD** (classes, schema, APIs, payloads) in the same round. Sometimes a second situational pipeline question |
| Hiring manager / DOE / CEO | 30–60 min | Project ownership, pace, “how you handle customer pressure,” sometimes a light design or resume architecture probe |
| HR | 15–30 min | Comp. Culture is discussed explicitly |

**Recurring process facts:**
- Last three rounds are often **back-to-back the same day**.
- Design is **eliminatory**. A good HLD that cannot drop into schema / APIs / concurrency is not enough.
- Data-engineering loops add SQL (`GROUP BY` / `HAVING`) and cost-vs-latency-vs-durability trade-offs.
- Staff Attack-Path JD is **hands-on**: they want an architect who still writes graph / reachability code.

**What they are scoring in design (say this to yourself before you draw):**

| Junior / SDE-1 | Senior / Staff / EM |
|---|---|
| Boxes that work | **Trust boundaries**, tenant isolation, evidence, blast radius |
| “I’ll use Kafka and Redis” | **Why this queue, this key, this consistency, this cost** |
| Happy path | Connector failure, partial sync, schema drift, poison findings |
| Generic Uber/parking lot | Same problem **plus** “now make this a security-data platform” |

---

## 3. The 45-minute method (use on every prompt)

Do not open with boxes. SAFE interviewers (internship through SDE-1 DOE) reward **requirements → trade-offs → maintenance**. Staff interviewers will additionally punish a design with no **threat model**.

| Min | Do | Produce |
|---|---|---|
| 0–5 | Restate. Clarify actors, SLA, consistency, **tenancy**, **audit**. State 3–4 NFRs | Scope + numbers |
| 5–8 | Name 3 design principles that will govern every later choice | e.g. “ingest is at-least-once; graph is the SoT; scoring is replayable” |
| 8–20 | HLD: ingress → bus → normalize → store → serve. Name ownership of each box | Diagram + data flow |
| 20–35 | Deep-dive the **hard** subproblem (they will pick one: concurrency, graph, scoring, isolation, connector) | Schema / API / algorithm |
| 35–42 | Failure modes, backfill, cost, how on-call knows | SLOs + runbook |
| 42–45 | What you’d cut for v0 vs 12 months | Phasing |

**Security-first overlay** (use on *any* prompt, including parking lot / booking, if they are a security company):

1. **Assets** — what must not leak, corrupt, or be repudiated  
2. **Entry points** — APIs, connectors, admin, webhooks, jobs  
3. **Trust boundaries** — tenant, control plane vs data plane, customer cloud vs SAFE cloud  
4. **Identity & policy** — who can read findings, who can trigger a workflow, service-to-service  
5. **Detection & audit** — append-only evidence for every score and every action  
6. **Failure** — fail closed on authz; fail open on *ingestion buffering* so you don’t drop customer telemetry

---

## 4. Reported design questions

Compiled from LeetCode Discuss, LinkedIn (Satvik Singh), AmbitionBox (2024–2025), Safe’s own internship blog, and MyInternships round descriptions.

### 4.1 Confirmed in candidate reports

| # | Prompt | Level seen | What they actually probed | Prep |
|---|---|---|---|---|
| 1 | **Parking lot** | SDE-I Data (manager round) | Functional + NFRs, **table schemas, indexes, SQL vs NoSQL**, layout of tables — *not* just OOP classes | §6.1 + parking-lot LLD |
| 2 | **High-throughput read/write pipeline** | SDE-I Data (same round) | Partitioning, sharding, queues, batching, **backpressure**, read replicas, cache, monitoring | §5.1 (same bones as telemetry) |
| 3 | **Online show booking** | SDE-1 DOE | HLD (architecture, scale) **then LLD** (classes, data flow, schema, API payloads, design patterns) | §6.2 + movie-booking LLD |
| 4 | **Microservices system** | Internship final | Service decomposition, **scalability vs maintainability**, fault tolerance | §6.6 |
| 5 | **OYO Rooms** | SE III (hiring drive) | Classic booking HLD; architect reportedly liked a strong design | §6.3 |
| 6 | **Uber in 20 minutes** | SSE-1 | Time-boxed ride-hailing HLD + “in-depth analysis of design” | §6.4 |
| 7 | **Zomato delivery** | SSE | Orders, assignment, tracking | §6.5 |
| 8 | **HLD + LLD (unspecified)** | SDE-1 (Dec 2024) | Combined design + behavioural + resume; CEO behavioural after | Be ready to drop from HLD → schema in one round |

### 4.2 Adjacent / listed on AmbitionBox company page

Restaurant **table reservation** appears on the SDE-1 question widget; treat it as the same family as show booking (inventory + time slot + no double-book). Not independently confirmed as a SAFE prompt.

### 4.3 Non-design questions that leak into the design round

Expect these even in a “system design” slot:

- “What happens when you type `google.com`?” — DNS → TCP → TLS → HTTP → CDN cache. Be able to name **where certs and cookies live**.
- CAP, TCP vs UDP, process vs thread, SQL vs NoSQL — recruiter *and* manager.
- Spring Boot vs Node multithreading (Satvik Singh DSA round).
- Cost vs latency vs durability (data loops).
- Longest Common Subsequence (coded); SQL spend-by-user `HAVING SUM > 10000`; non-overlapping intervals.

---

## 5. Team-likely questions (prepare these for Senior / Staff / Data / Platform)

If the interviewer is an architect, graph engineer, or data EM, they will not spend 45 minutes on parking lot. They will pick a slice of SAFE One. These eight cover the product. **Practice 1, 2, 3, and 4 until you can do them without notes.**

| # | Prompt | Why they ask it | Maps to your resume |
|---|---|---|---|
| 1 | Design **security-tool telemetry ingestion** for 150+ connectors, multi-tenant | This *is* the company | Skydo partner integrations; GS market-data ingest |
| 2 | Design a **cybersecurity knowledge graph** | CTEM + attack path SoT | Identity graph from IDCS + relationship modeling |
| 3 | Design **attack-path / blast-radius** computation | Staff Network Security JD | Graph traversal + risk ranking, not “hacking” |
| 4 | Design a **FAIR CRQ scoring engine** | Category-defining product | Goldman **VaR / stress** — same shape |
| 5 | Design **tenant isolation + RBAC + audit** for Fortune-500 SaaS | Platform SDE II JD (authn/z, multi-tenant) | Oracle IDCS + Skydo Suraksha + SOC 2 |
| 6 | Design **TPRM** for 1M+ vendors | CEO-stated scale | Search + graph + questionnaires + outside-in scans |
| 7 | Design **finding dedup / source of truth** across scanners | CTEM “zero-drop” language on datasheets | Reconciliation (payments analog) |
| 8 | Design **connector credential vault + sync workers** | Every integration is a secret + a job | AWS KMS, Secrets Manager, distributed jobs |

Below: interview-ready spoken designs. Stay in **architecture, policy, and evidence**. Do not volunteer exploit steps, payloads, or “how to break in.” If they ask how an attacker *moves*, answer with **graph edges** (identity membership, network reachability, known CVE presence) and **control effectiveness** — not how to weaponize them.

---

### 5.1 Design a multi-tenant telemetry ingestion platform (150+ security tools)

**Prompt:** Fortune-500 customers connect CrowdStrike, Qualys, AWS, Azure AD, ServiceNow, etc. Ingest continuously. Normalize. Don’t drop data. Don’t leak tenant A into tenant B.

**Clarify (30s):**
- Push (webhooks) vs pull (API poll) vs file drop vs cloud-to-cloud. **All three.**
- Freshness: findings dashboards minutes; CRQ hourly; board weekly.
- Volume: start at **10K events/sec aggregate**, 1K tenants, bursty (a Qualys scan dumps millions of rows).
- Must we re-process 90 days when a parser bug is fixed? **Yes — replay is a requirement.**

**Principles:**
1. Ingest is a **dumb, durable log**. Parsing is a replayable consumer.  
2. Tenant id is on **every** record and **every** Kafka key.  
3. Connectors are plugins; the bus and lake are the platform.  
4. At-least-once + **idempotent upsert** on `(tenant_id, source, native_id)`.

**HLD (draw this):**

```
Customer tools ──OAuth/API key──► Connector Workers (pull)
Webhooks / S3 ──────────────────► Ingest API (push)
                                      │
                                      ▼
                              Kafka (tenant_id + source)
                                      │
                    ┌─────────────────┼─────────────────┐
                    ▼                 ▼                 ▼
             Normalizer          Raw Lake           DLQ + quarantine
             (schema map)     (Iceberg/S3, 90d+)
                    │
                    ▼
             Canonical findings / assets / controls
                    │
                    ├──► Graph writer
                    ├──► Search (OpenSearch)
                    └──► Scoring / CRQ jobs
```

**Capacity (say numbers):**
- 10K events/s × 2 KB ≈ 20 MB/s ≈ 1.7 TB/day raw.  
- Kafka: 30–60 partitions to start, keyed by `tenant_id` (or `tenant_id % N + source`) so one noisy tenant cannot starve others if you **also** have a per-tenant quota.  
- Iceberg on object storage for replay; Postgres / document store only for **hot entity** upserts.

**Hard subproblems they will poke:**

| Probe | Answer |
|---|---|
| One tenant’s Qualys dump is 50M rows | Per-tenant **token-bucket** on the worker and on Kafka produce. Spill to S3, process as a batch job, don’t block the realtime topic. |
| Qualys API rate-limit 429s | Connector owns backoff + cursor. Progress stored as `sync_checkpoint (tenant, connector, cursor, etag)`. Idempotent. |
| Parser bug last Tuesday | Raw lake is immutable. Bump parser version, re-consume from Iceberg, upsert with `source_version`. Graph edges get a `valid_from/valid_to`. |
| Poison JSON | Schema registry + DLQ. Quarantine UI. Never retry infinitely on the hot path. |
| Webhook authenticity | Per-tenant HMAC secret, timestamp window, replay cache. Same pattern as a payments webhook. |
| Cost | Hot Kafka 24–72h; cold Iceberg. Don’t put raw JSON in Postgres. |

**Schema (minimum they expect on a whiteboard):**

```
connector_account (id, tenant_id, type, status, checkpoint, last_success_at)
raw_event         (tenant_id, source, ingested_at, payload_uri, hash)
asset             (tenant_id, asset_id, type, native_ids[], first_seen, last_seen)
finding           (tenant_id, finding_id, asset_id, source, native_id, severity, status, hash)
control           (tenant_id, control_id, framework, coverage, reliability)
sync_checkpoint   (tenant_id, connector_id, cursor, updated_at)
```

Indexes: `(tenant_id, source, native_id)` unique; `(tenant_id, last_seen)`; `(tenant_id, status, severity)`.

**Spoken close:**

> “The platform is the log and the canonical entity store. Connectors are adapters. If I can’t replay, I can’t defend a FAIR number to a board — so raw immutability is a product requirement, not an infra nicety.”

---

### 5.2 Design the cybersecurity knowledge graph

**Prompt:** Model assets, identities, vulnerabilities, network objects, controls, and business apps so we can ask “what can reach the SAP payroll system?”

**Clarify:** Multi-cloud + on-prem; tens of millions of nodes per large tenant; queries are **path queries with constraints**, not “friend of friend.”

**Node types (name 7, don’t invent 30):**  
`Asset`, `Identity`, `Account`, `Group`, `Finding`, `Control`, `NetworkSegment`, `BusinessService`.

**Edge types:**  
`RUNS_ON`, `OWNS`, `MEMBER_OF`, `HAS_FINDING`, `PROTECTED_BY`, `CAN_REACH`, `EXPOSED_TO`, `DEPENDS_ON`.

**Storage split (this is the Staff answer):**

| Store | Holds | Why |
|---|---|---|
| **Graph DB** (Neptune / Neo4j / JanusGraph) or **in-memory graph shard** | Topology + identity edges, hot paths | Path / reachability queries |
| **OLTP (Postgres)** | Entity master, tenancy, RBAC, tickets | Transactions, unique constraints |
| **Lake (Iceberg)** | Edge history, nightly rebuilds | Replay, ML, audit |
| **Search** | Textual finding lookup | Analyst UX |

Do **not** put 90-day raw events in Neo4j. Graph is a **projection**. Rebuildable.

**Update path:**
1. Canonical upsert in OLTP (idempotent).  
2. Emit `graph_mutation` events.  
3. Graph writer applies edge add/remove with version.  
4. Nightly **full rebuild** from lake as the correctness backstop (same as a search index).

**Multi-tenant:** **database-per-tenant or graph-per-tenant** for F500 isolation. Shared cluster only with a mandatory `tenant_id` partition and a query planner that **cannot** omit it (row-level filter in the driver, not “the analyst will remember”).

**Query examples to volunteer:**
- Shortest path from internet-facing asset to `BusinessService.criticality = crown_jewel`, max depth 6.  
- Blast radius: 2-hop neighbors of a compromised identity.  
- “Which findings sit on assets that `CAN_REACH` this service **and** are not `PROTECTED_BY` an effective control?”

**Hard probes:**

| Probe | Answer |
|---|---|
| Graph is stale vs scanner | Entities have `as_of`. UI shows **freshness SLO** (e.g. EDR 15 min, network config 6h). Never pretend real-time if the connector is poll-hourly. |
| Super-node (Domain Admins, `0.0.0.0/0` rule) | Pre-compute degrees; special-case “unbounded” edges; don’t expand `CAN_REACH` naively. |
| Cycle in AD nested groups | Identity closure is **transitive closure with cycle detection**, materialized on write, not DFS at query time for every request. |
| 10× edges | Shard graph by tenant; inside a huge tenant, shard by site / VPC and stitch at trust-boundary vertices. |

**Tie to IDCS:**

> “At Oracle Identity Cloud I lived in the identity side of this graph — users, groups, apps, tokens. The attack-path product is that graph **plus** network reachability and findings. Same modeling discipline: canonical identity, group expansion, least privilege.”

---

### 5.3 Design attack-path / blast-radius intelligence

This matches the **Staff Engineer – Network Security & Attack Path Intelligence** JD. Stay at **model + compute + explain**. No exploit recipes.

**Prompt:** Given topology, firewall/ACL/NAT, identities, and findings, rank **reachable, business-critical** paths — not every theoretical walk.

**Clarify:** Online (analyst expands a node) vs offline (batch recompute after a sync). Need **explanations** a CISO can trust.

**Pipeline:**

```
Config parsers (routing, ACL, SG, NSG, AD)
        │
        ▼
Effective reachability (CAN_REACH edges)
        │
        ▼
Attack graph: (node, privilege)  --technique-->  (node, privilege)
        │
        ▼
Path search (bounded depth, scored)
        │
        ▼
Filter: exploitability × control effectiveness × asset criticality
        │
        ▼
Explain + recommend compensating control (policy-level, vendor-neutral)
```

**Key design claim (say it):**

> “Documented topology is a lie. Effective reachability is computed from routing + ACL + NAT + segmentation. Attack paths are walks on the **product** of network reachability and identity privilege, scored by whether a finding is present **and** whether a control actually covers that technique. Theoretical paths get dropped.”

**Scoring a path (keep it simple in-room):**

```
path_score = asset_criticality
           × reachability_confidence
           × max(finding_exploitability along path)
           × (1 - control_effectiveness)
           × business_impact($)
```

This is how you later plug FAIR (frequency from threat intel + control gaps; magnitude from FAIR-MAM).

**Compute strategy:**
- **Incremental:** when one SG changes, recompute `CAN_REACH` in that VPC, then re-score paths that touched those vertices.  
- **Batch:** nightly full reachability for regression.  
- **Online:** precomputed top-N paths per crown jewel; ad-hoc BFS with depth cap 6 and a priority queue (Dijkstra on path_score).  
- Store paths as **edge-id lists + score + evidence refs**, not as screenshots.

**Safety / governance (JD asks for this):**
- Recommendations are **diffs against policy** (“deny 445 from VLAN 20 to PCI”) with approval + rollback.  
- Validation in a **simulator / digital twin**, not by flipping production firewalls from an agent.  
- Every path is **evidence-backed**: config hash, finding id, control id, as_of timestamps.

**Hard probes:**

| Probe | Answer |
|---|---|
| Too many paths | Cap K per target; cluster equivalent paths; show “cut edges” (controls that break the most score). |
| Explainability vs ML | Deterministic graph first. LLM **narrates** the already-computed path; it does not invent edges. Cite node/edge ids. |
| Hybrid cloud | Normalize to a vendor-neutral topology IR (nodes, interfaces, prefixes, policies). Parsers per vendor; reasoner once. |
| Identity (AD) | Materialize nested-group closure and ACL privileges as edges. Kerberos/delegation is another edge type — model it; don’t live-demo it. |

---

### 5.4 Design the FAIR CRQ scoring engine

**This is your home-game question.** Goldman VaR is the same computational shape: noisy inputs → model → **distribution of loss**, not a point.

**Prompt:** Produce a defensible dollar loss exposure per scenario, per business unit, refresh as telemetry changes. Board-ready.

**FAIR in 20 seconds (must be fluent):**

```
Risk = Loss Event Frequency  ×  Loss Magnitude
LEF  = Threat Event Frequency × Vulnerability (controls gap)
LM   = primary + secondary loss (FAIR-MAM drivers)
```

SAFE automates FAIR, FAIR-CAM (control effectiveness), FAIR-MAM (materiality). You do not need the full taxonomy. You need **inputs, simulation, lineage, replay**.

**HLD:**

```
Graph + findings + controls + threat intel + firmographics
        │
        ▼
Scenario compiler  (asset group × threat × method)
        │
        ▼
Input resolver     (telemetry → calibrated distributions; questionnaires fill gaps)
        │
        ▼
Monte Carlo worker (N=10k–50k trials / scenario)
        │
        ▼
Result store (P10/P50/P90 ALE, as_of, input_hash, model_version)
        │
        ├──► Dashboard / board pack
        └──► “What if we buy control X?” (re-run with CAM delta)
```

**Requirements to state:**
- **Replayable:** same `input_hash + model_version` → same distribution (seeded RNG).  
- **Explainable:** every input tagged `measured | inferred | questionnaire | industry-prior`. Garbage-in is the CEO’s stated problem; your job is to **label** it.  
- **Incremental:** a new critical finding bumps affected scenarios to a queue; don’t re-score the world.  
- **Multi-tenant model versions:** customers cannot silently change FAIR math.

**Data model:**

```
risk_scenario (id, tenant_id, name, asset_group, threat, method, owner)
scenario_input (scenario_id, factor, distribution, source, as_of)
score_run     (id, scenario_id, model_version, input_hash, p10, p50, p90, ale, started_at)
score_sample  (run_id, trial, loss)   -- optional; often keep histograms only
```

**Compute:** Spark / Flink batch for full estate; sidecar workers for interactive what-if (cache last input vector, mutate one control, 5k trials, return in seconds).

**Hard probes:**

| Probe | Answer |
|---|---|
| Why not a ML score? | Boards and auditors need a **standard**. FAIR is the standard. ML can estimate a *factor* (TEF from intel); it must not be a black-box ALE. |
| How is this like VaR? | “Same: time-series / position inputs, a loss function, a distribution, a model version, recon against actuals. Different: cyber events are sparse, so we rely more on calibrated priors and control telemetry than on a deep loss history.” |
| 6 million scenario combinations (SAFE factsheet) | Don’t enumerate naively. Compile from **graph cuts**: crown jewels × applicable threats. Materialize top-N by ALE. The rest on demand. |
| Control bought, risk didn’t drop | CAM reliability vs coverage vs capability are separate. Re-score with the new control **and** show which scenarios were insensitive — that’s the product insight. |

**Spoken close:**

> “A CRQ engine is a **risk calculation platform**: versioned model, lineage of every input, idempotent runs, and a result that a CISO can take to the board. I have shipped that class of system for market risk. I would not let a dashboard query recompute Monte Carlo on page load.”

---

### 5.5 Multi-tenant SaaS: isolation, identity, audit

Platform JD: Authentication, Authorization, scaling, cost, resiliency.

**Clarify:** F500 will ask “is my data in a shared DB?” Some will require **region pin** and **CMEK**.

**Isolation tiers (offer a ladder):**

| Tier | How | Who |
|---|---|---|
| Shared (row-level `tenant_id` + forced predicate) | Cheapest | Mid-market |
| Schema / database per tenant | Stronger blast-radius | Enterprise default |
| Dedicated stack / VPC | Highest | Named F500, regulated |

**Identity:**
- Customer IdP via **OIDC/SAML** (Okta, Azure AD). SAFE is SP.  
- Internal service-to-service: **mTLS + SPIFFE** or signed service JWT, not a shared `x-secret-key` forever.  
- Fine-grained authz: **ReBAC** on the graph (`user can view finding if user ∈ team that owns BusinessService`) plus coarse RBAC (Admin / Analyst / ReadOnly / Auditor).  
- Admin actions: step-up MFA.

**Audit (non-negotiable for CRQ):**
- Append-only `audit_event (tenant_id, actor, action, resource, before, after, ts, hash_chain)`.  
- Ship a copy to customer SIEM (webhook / S3 export).  
- Scoring runs are audit events too.

**Data protection:**
- TLS everywhere; envelope encryption; tenant DEK in KMS.  
- Connector secrets: §5.8.  
- Field-level: raw vuln evidence may be more sensitive than the dollar ALE.

**Hard probes:**
- Cross-tenant bug: **automated tenant-isolation tests** in CI that fail the build if a query lacks `tenant_id`.  
- Broken authz: default deny, policy-as-code (OPA / Cedar) evaluated at the gateway **and** in the service.  
- GDPR delete: delete OLTP + schedule lake compaction / tombstones; graph rebuild omits the subject.

**Tie to Skydo:** Suraksha (shared auth filter) + central auth service + SOC 2 evidence — same control plane, larger blast radius.

---

### 5.6 Third-party risk (TPRM) at 1M+ vendors

**Prompt:** Continuously assess vendors: questionnaires, outside-in scans, contract signals, fourth parties. 1.3M entities.

**This is a search + graph + workflow problem, not a CRUD app.**

```
Vendor master (legal entity resolution — the hard data problem)
   ├── Outside-in posture (scans, certs, breaches — internet-scale, shared across tenants)
   ├── Inside-in (customer’s CloudTrail / SaaS connected to that vendor)
   ├── Questionnaires + evidence (per customer × vendor)
   └── Graph: customer --USES--> vendor --USES--> fourth-party
```

**Split data that is global vs tenant:**
- **Global:** vendor identity, public breaches, outside-in score — computed once, reused.  
- **Tenant:** relationship, inherent risk, questionnaires, exceptions, residual FAIR.

**Entity resolution:** same as payments KYC. Deterministic keys (domain, LEI, tax id) then probabilistic name match. Don’t merge Apple Inc and Applebee’s.

**Scale:** inverted index (OpenSearch) for search; graph for fourth-party walk; lake for scan history. Nightly outside-in; on-demand when a vendor is onboarded.

**Workflow:** questionnaire send → reminders → AI auto-fill from uploaded SOC 2 (label as inferred) → analyst review → residual risk → exception with expiry.

---

### 5.7 Finding dedup / “single source of truth”

CTEM datasheet: *zero-drop architecture, normalize and deduplicate without losing specialist context.*

**Problem:** Qualys, Tenable, Defender, and a pentest all report “the same” CVE on “the same” host — except hostnames, IPs, and CVE IDs disagree.

**This is reconciliation.** You have shipped it for money. Same design:

1. **Identity resolution for assets:** correlation keys (agent id, instance id, MAC, hostname+domain, cloud ARN). Union-find / entity graph. Never throw away a source record.  
2. **Finding identity:** `(normalized_cve_or_rule, asset_entity_id)` with a `source_evidence[]` array.  
3. **State machine:** `open → risk-accepted → mitigated → verified_closed`. Sources can disagree; **policy** picks the worst open evidence unless an analyst exception exists.  
4. **Zero-drop:** raw findings immutable; the merge is a view. Analyst can always expand “4 sources.”  
5. **Idempotent merge job** keyed by entity id; late-arriving evidence re-opens if severity increases.

**Say this:**

> “Dedup that destroys source context is how you lose a CISO’s trust. I would rather have a canonical finding with an evidence list than a greedy merge that drops the only scanner that still sees the hole.”

---

### 5.8 Connector credential vault and sync workers

**Prompt:** Store 150 connector types × N tenants of API keys, rotate them, run syncs, never log secrets.

**Vault:**
- Customer pastes key or OAuth-connects. Secret written via **envelope encryption** (tenant DEK in KMS/HSM, ciphertext in DB).  
- App servers get short-lived data keys; **no long-lived secret in env vars**.  
- Rotation: dual-key window; connector health check before dropping old key.  
- Access: only the connector worker role; audit every decrypt.

**Workers:**
- Distributed job scheduler (you built this at Skydo): per-tenant per-connector jobs, jittered cron, lease/lock, heartbeat.  
- Fair scheduling so one tenant cannot occupy all workers.  
- Idempotent handlers; checkpoint in DB; poison queue.

**Outbound policy:** egress allow-list per connector; no SSRF to customer metadata services. Treat customer-supplied URLs as **untrusted**.

---

### 5.9 Extra team-likely prompts (outline only)

If they still have time, or a second interviewer:

| Prompt | 60-second skeleton |
|---|---|
| **Board risk dashboard** | Precompute score_runs; serve from Redis/CDN with `as_of`; never Monte Carlo on GET. WebSocket for “sync running.” |
| **Remediation workflow engine** | Finding → playbook template → create Jira/ServiceNow → track SLA → verify via next connector sync. Idempotent ticket keys. Human approval for anything that changes a firewall. |
| **Webhook notification for ALE breach** | Same as payment webhooks: signed, at-least-once, customer endpoint, DLQ, replay UI. |
| **Multi-region** | Control plane in home region; data plane pinned; Kafka MirrorMaker or region-local ingest; graph does not cross region. |
| **Rate limiter** at ingest API | Token bucket per tenant + per connector; Redis; Staff-level: local + global, fail-open vs fail-closed (ingest: shed load with 429, **don’t** drop already-accepted events). |
| **Questionnaire LLM auto-fill** | LLM proposes control answers with citations to the PDF; human confirm; never write inferred as measured. Eval set of SOC 2 PDFs. |

---

## 6. Generic reported prompts — what to say in-room

SAFE uses **classic** design questions for SDE-1 / SSE loops. They still want schema, indexes, and “why this DB.” Do not give a FAANG-tour answer with no tables.

### 6.1 Parking lot (reported — know the **schema**, not just classes)

**Clarify:** one lot or a city of lots? Payment on exit? Multiple gates?

**If they want LLD:** follow the parking-lot staff guide (aggregate, atomic assign, strategy for spot type).

**If they want HLD / DB (the actual SDE-I Data report):**

**Why Postgres, not NoSQL:** spots and tickets are **relational inventory**. You need transactions so two gates cannot assign the same spot. NoSQL only if they later ask for a national “find nearest lot” geo index (then Redis geo + Postgres SoT).

```
lot        (id, name, tz)
floor      (id, lot_id, number)
spot       (id, floor_id, type, status)           -- status: FREE|OCCUPIED|RESERVED|OOS
vehicle    (id, plate, type)
ticket     (id, spot_id, vehicle_id, in_at, out_at, fee)
gate_event (id, gate_id, ticket_id, ts, type)
```

**Indexes:** `spot(floor_id, type, status)` for assignment; unique `(lot_id, plate) WHERE out_at IS NULL`; `ticket(in_at)`.

**Concurrency:** `UPDATE spot SET status='OCCUPIED' WHERE id=? AND status='FREE' RETURNING *`. If 0 rows, retry next candidate. Optional: skip-locked queue of free spots per type.

**Scale follow-up:** 1000 lots → shard by `lot_id`; availability cache in Redis with 2s TTL + invalidation on park/unpark. Search service for “nearest with a free EV spot.”

### 6.2 Online show booking (reported DOE — HLD **then** LLD)

**HLD flow:** browse → seat map (cache) → **hold** (TTL 10 min) → pay → **confirm**. Inventory is the consistency core; search/browse is eventually consistent.

**Services:** Show Catalog, Seat Inventory, Hold, Booking, Payment, Notification.

**LLD they explicitly asked:** classes (`Show`, `Seat`, `Hold`, `Booking`), APIs:

```
POST /shows/{id}/holds     { seat_ids[], session_id } → hold_token, expires_at
POST /bookings             { hold_token, payment_ref, idempotency_key }
```

**No double-book:** hold row unique on `seat_id` where active; confirm is a transaction: seats HELD→BOOKED, hold consumed. Payment failure: leave hold until TTL or compensating release.

**Pattern to name:** State (seat lifecycle) + Strategy (pricing) + Saga (pay vs book).

Full drill: movie-booking LLD in this repo.

### 6.3 OYO Rooms (reported)

Same as booking: **search is dirty-ok; reservation is strongly consistent.**

- Search: ES geo + filters; availability denormalized by `(property_id, date)` counts.  
- Book: `UPDATE inventory SET remaining = remaining-1 WHERE remaining > 0 AND version=?`.  
- Overbooking policy is a **business** flag, not an accident.  
- Idempotency key on book.  
- Multi-property: shard by city or property_id.

### 6.4 Uber in 20 minutes (reported SSE-1)

Do **not** design the whole company. Pick the matching problem.

**Scope:** rider requests trip → match driver → track → complete → pay.

| Box | Choice | Why |
|---|---|---|
| Location ingest | Driver app → gateway → Redis geo / Kafka | High write, ephemeral |
| Matching | Dispatch workers consume `trip.requested`, query geo radius, offer, timeout, re-offer | Need fairness + cancellation |
| Trip state | Postgres | Money + dispute |
| Tracking | WebSocket / MQTT via gateway | Fan-out to one rider |
| Payments | async + idempotency | You know this |

**Say the hard parts:** (1) matching is an **offer state machine**, not “closest point.” (2) location is **lossy by design** (1–2s). (3) trip SoT is SQL. (4) surge is a pricing service, skip unless asked.

**20-min closer:** “If we have 5 minutes left I can go deep on matching or on exactly-once fare capture — which do you want?”

### 6.5 Zomato delivery (reported)

Three domains: catalog/order, logistics assignment, tracking.

- Order + payment: SQL, idempotent, restaurant accept timeout.  
- Dispatch: like Uber matching but **batched** (food isn’t instant).  
- Tracking: Redis + WS.  
- Shard by `city_id` (canonical). Kafka key `order_id` for ordered events per order.

### 6.6 Microservices vs maintainability (internship / junior, still asked)

**Answer they want:** split on **consistency boundaries and team ownership**, not on nouns.

- Catalog / ingest / graph / scoring / identity / billing — different SLAs and deploy cadences.  
- Don’t split “UserService” and “ProfileService.”  
- Start modular monolith if one team; extract when independent scale or independent failure is proven.  
- Cost of microservices: distributed transactions, versioned APIs, on-call. For SAFE, **connectors** and **scoring** are the first extract because they scale and fail independently.

---

## 7. Recurring follow-ups (memorize)

Regardless of prompt:

1. **SQL vs NoSQL — pick, then justify.** Inventory, money, tickets, score_run metadata → SQL. Telemetry, locations, raw evidence → log / object / wide-column. Graph queries → graph projection.  
2. **Where is the unique key?** Findings, bookings, payments, syncs: `(tenant_id, source, native_id)` or an idempotency key.  
3. **What happens at 10×?** Partition, isolate noisy tenants, move raw off OLTP.  
4. **Single point of failure?** Stateless app, replicated Kafka, failover DB, region story.  
5. **How do you know it’s healthy?** Freshness lag per connector, DLQ depth, graph rebuild age, score_run success, isolation canaries.  
6. **Who can see this data?** Tenant predicate + ReBAC + audit.  
7. **Can we replay?** If no, the CRQ number is not defensible.

---

## 8. Resume stories to keep loaded

Memorize **one number, one failure, one decision** each.

| Story | Number | Failure they will poke | Decision |
|---|---|---|---|
| Skydo payments | 10K+ txns/day | Duplicate settlement, partner timeout | Redis lock = fast path; Postgres idempotency = truth |
| Skydo jobs / dlock | async workflows across cloud | Lock TTL mid-critical section | Fencing / DB as source of truth |
| Skydo CIO | ISO 27001 + SOC 2 Type II | Evidence gaps, DLP | Security as a **control plane**, not a checklist |
| Goldman risk | PB-scale market data, in-memory clusters | Partial shard loss, recon breaks | Sharding + replication + recon as a product |
| Oracle IDCS | enterprise identity cloud | Token/session, multi-tenant IAM | Central authz, distributed enforcement |
| Moneyview | millions of debit instructions / day | Retry double-debit | Idempotent rails + SLA scheduler |

**Opening if they say “walk me through a system you designed”:**

> “I’ll use Skydo’s settlement platform — it’s the closest analog to SAFE’s world: many external systems, at-least-once delivery, a number that cannot be wrong, and an audit trail. Then I can map the same patterns onto telemetry and FAIR if useful.”

---

## 9. Culture questions that sit next to design

These showed up in **the same rounds** as parking lot / DOE. Do not freeze.

**“Hardworking, 1–10?”**  
Multiple reports say they expect **10**, and “why not 10?” is a trap. Do not say 9.5 and philosophize.

> “Ten on ownership when the customer or the board is waiting — I’ve been on-call for money-moving systems and for SOC 2 evidence. I also run teams so that 10 is sustainable: we measure toil and we automate. I’m not interested in performative hours; I am interested in outcomes with extreme ownership.”

**“Fast-paced / tight deadlines / customer in the loop?”** STAR: payments go-live, certification deadline, incident. Situation → you cut scope → you protected correctness → you communicated.

**“Why SAFE?”**

> “Three reasons. One: I have been the *buyer* — as CIO I owned ISO 27001 and SOC 2, and I know CISOs don’t need another dashboard of CVEs; they need a defensible number and a ranked path to reduce it. Two: the engineering problem is my career: high-volume ingest, identity, quantified risk. Three: Staff/platform work here is a graph + data + workflow problem, not a CRUD SaaS.”

Be aware: public reviews (AmbitionBox, a LeetCode offer-decline) describe a **high-intensity, long-hours** culture. The JDs say the same thing in nicer words (“Series C hustle,” “extreme ownership”). Decide your boundary before the CEO round. Do not volunteer bitterness; do not pretend you want 9-to-9 theater if you don’t.

---

## 10. Day-of cheat sheet

**If they say “design X” and X is generic:**  
Requirements → schema → concurrency → scale → *then* “in a security-data company I would also add tenant isolation and audit.” That last sentence is free senior signal.

**If they say “design SAFE / CTEM / CRQ / attack path”:**  
Connectors → immutable log → canonical entities → graph projection → scored paths / FAIR runs → RBAC + evidence. Pick **one** deep dive (replay, reachability, or Monte Carlo).

**Do not:**
- Draw 15 microservices for a parking lot.  
- Put raw JSON in the graph DB.  
- Recompute FAIR on every dashboard GET.  
- Claim exactly-once ingest (you mean at-least-once + idempotent upsert).  
- Walk through offensive techniques. Model **edges and controls**.  
- Skip indexes when they asked for a schema.

**Do:**
- Put `tenant_id` on every table and every Kafka key.  
- Name the unique key.  
- Volunteer replay, DLQ, and freshness SLOs.  
- Compare SQL / log / graph **per workload**.  
- Tie back to a system you actually operated.

---

## 11. Sources

**Reported interviews**
- [LeetCode Discuss — Safe Security SDE-I (Data)](https://leetcode.com/discuss/post/7345521/safe-security-sde-i-data-interview-exper-q467/) — parking lot schema, high-throughput pipeline, LCS, SQL HAVING, `google.com`, hardworking 1–10  
- [Satvik Singh — LinkedIn interview experience](https://www.linkedin.com/posts/satvik-singh-3989a51b5_safesecurity-interviewexperience-dsa-activity-7297110036974055424-ohL3) — DSA + Spring/Node, DOE show-booking HLD→LLD, back-to-back rounds  
- [AmbitionBox — Safe Security interviews](https://www.ambitionbox.com/interviews/safe-security-interview-questions) — OYO Rooms HLD, Uber-in-20, Zomato delivery, SDE-1 HLD/LLD + CEO, CAP/TCP/SQL screen  
- [SAFE internship blog — interview loop](https://safe.security/resources/blog/internship-journey-at-safe-security/) — DSA + backend, then microservices design (scale vs maintenance)  
- [MyInternships — Safe Security prep](https://myinternships.in/interview-prep/safe-security) — round shape (DSA then design)

**Product / JD (team-likely)**
- [Staff Engineer — Network Security & Attack Path Intelligence](https://jobs.lever.co/safe/9f3ffa00-48fd-45b4-8f17-20fdfd634d2b)  
- [SDE II — Data](https://builtin.com/job/software-development-engineer-ii-data/9516356) — Spark/Flink/Airflow, Iceberg, Kafka, billions of events/day  
- [SDE II — Platform](https://jobs.lever.co/safe/e4000f75-e7cd-4296-aa69-3fdca7e12345) — multi-tenant, authn/z, IaC  
- [SAFE One platform](https://safe.security), [integrations](https://safe.security/integrations/), [FAIR](https://safe.security/the-fair-standard/), CTEM / CRQ datasheets (150+ connectors, knowledge graph, FAIR-CAM/MAM, agentic workflows)

*If you share the exact role (SDE-2 Data vs Platform vs Staff Attack Path vs EM) and JD, this can be narrowed to one 60-minute script the way the SaaS Labs / GS prep docs are.*
