# Scaler AI Labs — Step-by-Step Interview Scripts
**Spoken words. Rehearse out loud. Staff / 10 YOE bar.**

> Use this like a teleprompter. Bracketed text = *actions on the board*, not speech.
> System design is Script A (main). Scripts B–E cover the rest of the day.

---

## Script A — System Design (60 min)
### Prompt you’re answering

> Design a platform where AI agents practice enterprise workflows (Jira, Slack, email…), we score them, and we only promote changes after measured uplift on held-out tasks.

---

### Step 0 — First 60 seconds (say this)

> “I’ll design this as an **evaluation and training apparatus**, not a chatbot. I’ll go: requirements → capacity → APIs and data → architecture → two hard problems → failures and trade-offs. I’ll state assumptions out loud — please correct me anytime.”

> “Working assumption: agents that do **tool-use and computer-use** on enterprise workflows; we support **multi-tenant frontier-lab partners**; success is **task completion with partial credit**, and we gate ship on **held-out uplift**. If you want a narrower scope, tell me now.”

**Then ask exactly these three:**

> “One — are agents mostly API tool-use, full computer-use, or both?  
> Two — is success binary pass/fail, partial credit, or preference scores for RL?  
> Three — is this internal only, or multi-tenant for partner labs with isolation SLAs?”

*[Write their answers. If they shrug, say:]*

> “I’ll proceed with both interfaces, outcome plus partial credit, and multi-tenant isolation.”

---

### Step 1 — Scope (1–2 min)

> “In scope: versioned gyms and tasks, episode execution, trajectory storage, layered verification, and an eval gate that compares candidate vs baseline.  
> Out of scope: training frontier model weights ourselves, rebuilding Jira as a product, and unconstrained open-internet browsing.”

*[Board: IN / OUT]*

---

### Step 2 — Functional requirements (2 min)

*[Write FR1–FR6]*

> “Functionally we need to:  
> 1 — register versioned gyms and tasks with rubrics and init state,  
> 2 — run episodes: reset, step, terminate,  
> 3 — store trajectories — observations, actions, tool calls, artifacts,  
> 4 — score episodes with a verifier pipeline,  
> 5 — run eval suites comparing baseline vs candidate and decide ship or reject,  
> 6 — optionally emit preference or reward data for RL.”

---

### Step 3 — Non-functional requirements (2 min)

*[Write NFR table]*

> “Non-functionals I’ll optimize for:  
> — Gym step latency under a few seconds for API tools; computer-use can be slower.  
> — On the order of tens of thousands of episodes per day — I’ll napkin that next.  
> — **Zero held-out contamination** — that’s a ship blocker.  
> — Strong tenant isolation.  
> — No unconstrained egress; allowlisted actions; secrets injected.  
> — Append-only reproducible trajectories with seeds and versions.  
> — Cost: cheap fidelity for training, expensive fidelity for certification.”

---

### Step 4 — Capacity napkin (3 min)

*[Write numbers]*

> “Assume fifty thousand episodes a day, average twenty steps, average observation artifact two hundred KB. That’s about a million steps a day — roughly twelve steps a second average, so I’ll design for about ten-x burst, around one hundred twenty steps a second.  
> Artifact storage is on the order of two hundred GB a day raw before compression — so we diff, compress, and tier retention.  
> Important: the bottleneck is **sandbox concurrency**, not the REST layer. I’ll size a warm sandbox pool from p95 episode length times target parallelism.”

---

### Step 5 — APIs + data model (5 min)

*[Write entities + 4 endpoints]*

> “Core entities: Tenant, GymVersion, TaskVersion with a held_out flag, AgentVersion, Episode, Step, Artifact, Verdict, EvalSuite, EvalRun.”

> “Core APIs:  
> `Reset` on a gym version with seed and task init → episode id and first observation.  
> `Step` with an action → next observation, done flag, info.  
> `CreateVerdict` with verifier version → score, subscores, evidence.  
> `CreateEval` with suite, baseline, candidate → later we read metrics and decision.”

> “Episode creation is **durable before side effects** — same instinct as a payment intent before calling a partner.”

---

### Step 6 — Draw architecture (10–12 min)

*[Draw boxes left to right while talking]*

> “Left side: **Task Registry and Gym catalog** — versioned specs, rubrics, init state, held_out bit.  
> Next: **Agent Harness** — model provider adapters, timeouts, max steps, observation truncation. The harness talks to models; it does not own success.  
> Center: **Gym Runtime** in a sandbox — owns world state, tool execution, reset semantics, clocks. Agents never hit production SaaS directly.  
> Then: **Trajectory Store** — append-only steps, content-addressed artifacts, PII scrub on ingest.  
> Then: **Verifier Stack** — layered scoring.  
> Top: **Eval Orchestrator** — runs suites, computes uplift, returns ship, reject, or investigate.  
> Side: **Control plane** for tenants, keys, ACLs, quotas — off the hot path.”

> “Happy path: create durable episode → reset → step loop → terminate → verify → roll into suite metrics → gate.”

---

### Step 7 — Deep dive 1: Verifiers (6–8 min)

*[Draw L0–L3]*

> “Scoring is a pipeline, not a single LLM judge.  
> **L0 deterministic** — final API state equals expected, file hash, SQL assert. Prefer this whenever the task is checkable.  
> **L1 rubric heuristics** — checklists: required fields present, no PII leak, link created.  
> **L2 LLM-as-judge** — structured rubric JSON, calibrated against expert labels.  
> **L3 expert sample** — continuous inter-rater reliability; audit weird high-score trajectories.”

> “Promotion rule: on partner-facing or high-stakes cert, L0 when possible; **L2 is never the sole gate**. Every verdict stores verifier version plus evidence — audit trail.”

> “This is the same instinct as payments reconciliation — I don’t trust a single upstream success webhook, so I don’t trust a single model score.”

---

### Step 8 — Deep dive 2: Fidelity + reward hacking + contamination (8–10 min)

*[Write T0–T3]*

> “Fidelity tiers:  
> T0 mock tools — cheap unit and rollout.  
> T1 record/replay — deterministic regression.  
> T2 live sandbox — main training gyms.  
> T3 constrained partner shadow — certification.  
> We train cheap; we **certify uplift on T2/T3 held-out**. T0 uplift is not production competence.”

> “Reward hacking: prefer **outcome** verification over process rewards; randomize non-essential IDs and UI so agents can’t memorize shortcuts; keep adversarial held-out; human-audit top-reward weird trajectories.”

> “Contamination: held_out bit plus ACLs so training jobs cannot read those task IDs; separate buckets; version-pin suites. Leakage is a ship blocker — treat it like insider data.”

---

### Step 9 — Failures + scale (4 min)

> “Failure modes I’d actively design for:  
> — Flaky gym vs true fail: rerun budget, seed replay, flake rate gates gym release.  
> — Verifier false positive: dual control on gold, calibrate LLM judges, investigate lane on the gate.  
> — Sandbox OOM / poison: episode lease, hard timeouts, pool quarantine.  
> — Partial multi-app reset: transactional snapshot across apps in one namespace.”

> “Scale: queue into a worker fleet; warm sandbox pool; API episodes on a dense pool; computer-use on a separate pool. Postgres for metadata, object store for trajectories, OLAP for metrics. Autoscale on queue lag.”

> “I won’t claim distributed exactly-once. Durable episode intent, idempotent create, worker lease with fencing; sandbox effects are resettable.”

---

### Step 10 — Close (45–60 sec)

> “Trade-off I chose: **layered verification and fidelity tiers** over one smart judge and one mega-realistic environment — keeps cost controllable and trust measurable.  
> If we had more time I’d deepen multi-app clock sync and partner-facing eval SLAs.  
> This is the same shape as the Skydo support copilot, inverted: there the agent acted on live tickets with human-in-the-loop; here it acts in a gym with verifiers. In both cases the product is **trust before autonomy**.”

*[Stop talking. Invite questions.]*

---

### Script A — If they interrupt with pivots

**“Focus on computer-use only”**

> “Then the center becomes a session broker and warm VM pool. Observations are screenshots, DOM, accessibility tree — compressed with diffs and captions. Actions are click, type, scroll with race-careful sequencing. API tool-use becomes an optional fast path; computer-use is the certification path.”

**“Just design the uplift / benchmark service”**

> “Then I’ll thin the gym and thicken experiment design: stratified suites, version pins, fixed seeds, success and cost and safety metrics, bootstrap confidence intervals, gate states ship / reject / investigate. A leaderboard is not the product — held-out delta is.”

**“Multi-tenant for three frontier labs”**

> “I’ll add tenant-scoped keys, gym visibility, quotas, optional dedicated sandbox pools for top tiers, and hard ACL so no cross-tenant task leakage — including in aggregate metrics.”

---

## Script B — Opening / “Tell me about yourself” (90 sec)

> “I’m Ramit. I’ve spent about ten years on systems where wrong answers cost money. Most recently at Skydo I led engineering on a cross-border payments and settlement platform — on the order of ten thousand international transactions a day — with hard requirements on idempotency, reconciliation, and failure recovery. I also designed an agentic support copilot: RAG, read-only tools, human-in-the-loop. It triages about sixty-five percent of volume, assists resolve on roughly thirty-five to forty percent, took first-response time from about ninety minutes to ten, cut SLA breaches about in half — and by design the model never moves money. I led GenAI standards across eng, and as CIO took us through ISO 27001 and SOC 2. Before that I was a VP at Goldman Sachs on multi-terabyte distributed risk compute.  
> What draws me here is the hard version of the same problem: making models reliable on real work — gyms, verifiers, held-out uplift. I want to build the apparatus, not bolt on another chatbot.”

---

## Script C — Resume deep-dive: Agentic copilot (5–7 min)

### If they say “Walk me through the copilot”

> “Problem: support volume and slow first response, but wrong answers on payments are unacceptable.  
> Shape: not ‘LLM on tickets’ — a **trusted automation platform**. Ingest ticket → classify risk → retrieve policy with citations → optional read-only tools → draft or triage → human gate for anything sensitive → audit everything.”

> “Hard constraints I set: tools are read-only on money paths; PII masked before the model; policy lives outside the model; every run stores prompt, evidence, tools, versions, human action; we shadow, then gate, then keep a kill switch. Over-escalate beats wrong auto-resolve.”

> “Evals: stratified golden set by intent, risk tier, tool hops, policy version. Regression budget: zero tolerance on policy misses and PII leakage before promote.”

> “Results: about sixty-five percent triage, thirty-five to forty percent assisted resolve, first response ninety to ten minutes, SLA breaches down about fifty percent.”

### Live modify: “Make tools write but safe”

> “I’d add a capability model: read / draft / propose / execute. Execute only behind human approval or a narrow allowlist with amount caps, idempotency keys, and dual control. Same durable-intent pattern as payments — no execute without an auditable intent row.”

### Live modify: “Add multi-tenant”

> “Tenant-scoped indexes, keys, and tool credentials. Retrieval never crosses tenants. Eval suites per tenant policy pack. Quotas on model spend.”

---

## Script D — Resume deep-dive: Payments (4–5 min)

> “Core guarantee: never double-pay, never silently drop a partner success.  
> Pattern: persist **payment intent** with idempotency key before side effect; Redis lock with short critical section and fencing on the money path; async jobs for retries; reconciliation closes the gap when the partner succeeds but we timeout. I don’t claim magic exactly-once across the network — I claim **at-least-once with business idempotency and recon**.”

> “That maps directly to episodes: durable create, leases, resettable sandbox effects, and verifiers instead of partner webhooks.”

---

## Script E — Coding round protocol (script, not a problem)

### Minute 0–2

> “Let me restate the problem in my words… Constraints I’m assuming are… Here’s a small example… And an edge case: empty input / single element / duplicates.”

### Minute 2–5

> “Brute force would be… complexity…. A better approach is… because…. I’ll go with that unless you prefer I code brute first.”

### While coding

> “Invariant I’m maintaining is…  
> I’ll name this clearly…  
> I’ll handle the null/empty case up front…”

### After code

> “Dry run on the example…  
> Complexity time … space …  
> Tests I’d add: empty, one element, adversarial, large input.  
> If I had more time I’d …”

### If stuck (say this — don’t freeze)

> “I’m going to take thirty seconds to rethink. The part that’s blocking me is X. Two options I’m considering are A and B — I’ll try A because …”

---

## Script F — HM / culture (short answers)

### “Why us?”

> “Most AI companies demo agents. You’re building the apparatus that makes agents competent at real jobs — data, gyms, experts, uplift gates. That’s the leverage layer. My career has been leverage layers in payments and risk; I want that seat in the AI stack.”

### “Why leave Skydo?”

> “I took the foundations as far as I could there — platform, team, certifications. I wanted builder mode on a harder AI reliability problem, and I left intentionally to choose the next chapter carefully — not from desperation.”

### “Conflict: research vs shipping?”

> “Time-box experiments; promote only on held-out metrics. Demos don’t ship. If uplift isn’t proven, it stays in investigate — same as I wouldn’t enable auto-send on the copilot without eval gates.”

### “Failure story?” (pick payments near-miss or eval miss)

> “We nearly trusted a partner success path that wasn’t reconciled. Recon caught silent successes. I tightened durable intent and alerting. Lesson: never equate upstream ACK with business success — same lesson as verifier design.”

---

## Script G — Questions you ask (pick 3)

> “How do you decide fidelity tier when you stand up a new gym — mock vs live sandbox?”  
> “Where do you see more false signal today — flaky envs, weak verifiers, or contamination risk?”  
> “For this seat, what does success look like in six months?”  
> “How do partner frontier labs consume your artifacts — datasets, gyms, ranked diffs?”  
> “How does Bangalore eng pair day-to-day with SF and lab partners?”

**To Pranav:**

> “What does a strong full-day candidate look like in this loop? How does AI Labs work with the SST campus day to day?”

---

## How to practice (45 min)

| Time | Do |
|----:|----|
| 25 min | Script A start to finish with a timer — stand up, draw on paper |
| 8 min | Script B + C once each |
| 7 min | Script A pivots + § deep-dive pokes from the drill pack |
| 5 min | Script G — say questions out loud so they sound natural |

**Day-of:** take only [`SCALER-AI-LABS-SYSTEM-DESIGN-DAYOF.md`](./SCALER-AI-LABS-SYSTEM-DESIGN-DAYOF.md) + this file’s Script A steps as a mental checklist — don’t read paragraphs mid-interview.
