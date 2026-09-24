# Scaler AI Labs / Evaratus — System Design Drill Pack
**Ramit · 10 YOE · Staff bar · use for the afternoon design round**

> Goal today: one design you can run **cold in 60 minutes**, plus 3 variants you can pivot to in 5 minutes.
> Day-of one-pager: [`SCALER-AI-LABS-SYSTEM-DESIGN-DAYOF.md`](./SCALER-AI-LABS-SYSTEM-DESIGN-DAYOF.md)

---

## Table of Contents

1. [60-minute clock (their domain)](#1-60-minute-clock-their-domain)
2. [Primary design — RL Gym + Eval Platform](#2-primary-design--rl-gym--eval-platform)
3. [Variant A — Agent Execution / Computer-Use Platform](#3-variant-a--agent-execution--computer-use-platform)
4. [Variant B — Benchmarking & Uplift Measurement Service](#4-variant-b--benchmarking--uplift-measurement-service)
5. [Variant C — Enterprise Workflow Dataset Pipeline](#5-variant-c--enterprise-workflow-dataset-pipeline)
6. [Variant D — Multi-tenant Gym Platform for Frontier Labs](#6-variant-d--multi-tenant-gym-platform-for-frontier-labs)
7. [Deep-dive bank (they will poke these)](#7-deep-dive-bank-they-will-poke-these)
8. [APIs, data model, capacity napkin](#8-apis-data-model-capacity-napkin)
9. [Bridge lines to your resume](#9-bridge-lines-to-your-resume)
10. [Common failure modes in this round](#10-common-failure-modes-in-this-round)

---

## 1. 60-minute clock (their domain)

Use your standard Staff sequence, but **swap nouns** to gyms/evals. Do not open with Kafka boxes.

| Min | Phase | Must leave on the board |
|----:|-------|-------------------------|
| 0–5 | Align | Scope, 3 design-changing Qs, assumptions |
| 5–10 | FR + NFR | Task run, score, uplift gate + latency/correctness/isolation |
| 10–14 | Capacity | Episodes/day, storage, sandbox concurrency bottleneck |
| 14–20 | API + data | `reset/step`, Task/Episode/Verdict entities |
| 20–35 | Architecture | Task → Gym → Trajectory → Verifier → Eval Gate |
| 35–50 | Deep dive | Pick **2**: reward hacking, fidelity tiers, contamination, multi-app |
| 50–56 | Failures + scale | Flaky env, verifier false positive, sandbox OOM |
| 56–60 | Close | Trade-offs + Skydo bridge + “what I’d deepen” |

**Opening line (say verbatim):**

> “I’ll design this as an **evaluation-and-training apparatus**, not a chatbot: requirements → capacity → contracts → architecture → hard problems → failures. I’ll assume tool-use / computer-use agents practicing enterprise workflows unless you want a narrower scope.”

**3 clarifying questions that change the design:**

1. **Agent interface:** API tool-use only, computer-use (GUI), or both?  
2. **Success definition:** binary task pass, partial credit, or preference ranking for RL?  
3. **Consumers:** internal training loop only, or multi-tenant frontier-lab partners with isolation SLAs?

**Good defaults if they shrug:**

- Both API + computer-use; start API-heavy, computer-use for fidelity certification  
- Outcome verification + partial credit; preference data secondary  
- Multi-tenant partners; strong isolation; held-out never shared into training

---

## 2. Primary design — RL Gym + Eval Platform

### Prompt (memorize)

> Design a platform where AI agents practice real enterprise workflows (e.g. Jira + Slack + email), we score trajectories, and we only promote a new model/prompt/reward when **held-out uplift** is proven.

### 2.1 Functional requirements

1. Register versioned **gyms** (apps + tools) and **tasks** (init state + rubric)  
2. Run **episodes**: reset → step loop → terminate  
3. Store **trajectories** (obs, actions, tool calls, artifacts)  
4. **Score** episodes via layered verifiers  
5. Run **eval suites**: baseline vs candidate → metrics + ship/reject gate  
6. (Stretch) Emit preference / reward data for RL training  

**Out of scope (say it):** training the frontier model weights yourself; building full Jira product; public internet browse without sandbox.

### 2.2 Non-functional requirements

| NFR | Target (state out loud) |
|---|---|
| Gym step latency | p95 &lt; 2–3s for API tools; computer-use can be higher |
| Eval throughput | 10K–100K episodes/day (assumption — confirm) |
| Correctness | Zero contamination of held-out; verifier false-positive budget explicit |
| Isolation | Partner tenants cannot see each other’s data/gyms |
| Safety | No unconstrained egress; secrets injected; action allowlists |
| Durability | Trajectories append-only; reproducible with seed + versions |
| Cost | Prefer cheap fidelity for train; expensive fidelity for certify |

### 2.3 Architecture (draw this)

```
                    ┌──────────────────┐
   Partner / CI ───▶│  Eval Orchestrator│──▶ ship / reject / investigate
                    └────────┬─────────┘
                             │ runs suite
                             ▼
┌─────────┐   ┌─────────────┐   ┌────────────┐   ┌──────────────┐
│ Task    │──▶│ Agent       │──▶│ Gym Runtime│──▶│ Trajectory   │
│ Registry│   │ Harness     │   │ (sandbox)  │   │ Store        │
│ + Gyms  │   │ (model I/O) │   │ reset/step │   │ (+ artifacts)│
└─────────┘   └─────────────┘   └─────┬──────┘   └──────┬───────┘
                                      │                 │
                                      │    ┌────────────▼──────────┐
                                      └───▶│ Verifier Stack        │
                                           │ L0 det → L1 rubric →  │
                                           │ L2 LLM → L3 expert    │
                                           └────────────┬──────────┘
                                                        ▼
                                                 Verdict + metrics
```

**Say while drawing:**

> “The unit of work is an **episode** on a **versioned task**. The agent never talks to production SaaS directly — only to a **sandboxed gym**. Scoring is a **pipeline**, not a single LLM judge. Promotion is an **eval gate** comparing candidate to baseline on held-out tasks.”

### 2.4 Component responsibilities (30-sec each)

| Component | Owns | Does not own |
|---|---|---|
| **Task Registry** | Spec versions, held_out flag, rubric refs, init state | Running agents |
| **Gym Runtime** | World state, tool execution, reset semantics, clocks | Model inference |
| **Agent Harness** | Provider adapters, timeouts, max steps, obs truncation | Business success |
| **Trajectory Store** | Append-only steps, content-addressed blobs, PII scrub | Scoring policy |
| **Verifier Stack** | Scores + evidence; versioned | Changing gym state |
| **Eval Orchestrator** | Suite scheduling, stats, gate decision | Training loop internals |
| **Control Plane** | Tenants, keys, gym templates, ACLs | Episode hot path |

### 2.5 Request path — one episode

1. `CreateEpisode(task_version, agent_version, seed)` → durable episode row (like payment **intent before side effect**)  
2. Gym `Reset(seed, init_state)` → observation₀  
3. Loop: harness asks model → action/tool_calls → gym `Step` → obs/reward_partial → append step  
4. Terminate on success, fail, max steps, or timeout  
5. Verifier pipeline → `Verdict`  
6. Metrics aggregator updates suite results  

### 2.6 Deep dive #1 — Verifier stack (must crush)

**Problem:** Fuzzy tasks (“summarize the ticket thread and file a bug”) aren’t unit-testable.

**Design:**

```
L0 Deterministic  — final API state == expected; file hash; SQL assert
L1 Rubric heuristics — checklist: required fields, no PII leak, link present
L2 LLM-as-judge — structured rubric JSON; calibrated on expert labels
L3 Expert sample — continuous IRR; audit high-score weird trajectories
```

**Promotion rule:**

- High-stakes / partner ship: L0 required when possible; L2 never sole gate  
- Partial credit = weighted rubric dimensions  
- Every verdict stores `verifier_version` + evidence refs (audit)

**Say:**

> “Same instinct as payments recon — I don’t trust a single upstream ‘success’ webhook. Here I don’t trust a single LLM score.”

### 2.7 Deep dive #2 — Fidelity vs cost

| Tier | What | Use |
|---|---|---|
| T0 Mock tools | In-memory fakes | Unit tests, cheap RL rollouts |
| T1 Record/replay | Deterministic recorded server | Regression, debugging |
| T2 Live sandbox | Real software in containers/VMs | Main training gyms |
| T3 Partner shadow | Constrained real accounts | Certification before delivery |

**Rule:** train on T0–T2; **certify uplift on T2/T3 held-out**. Never claim T0 uplift equals production competence.

### 2.8 Deep dive #3 — Reward hacking & contamination

**Reward hacking defenses:**

- Prefer **outcome** verification over process rewards  
- Randomize non-essential UI / IDs so agents can’t memorize pixels  
- Adversarial held-out tasks designed to break known shortcuts  
- Human audit of top-reward / low-human-agreement trajectories  
- Monitor for “weird high reward” (short trajectories that game proxy)

**Contamination defenses:**

- `held_out` bit + ACLs: training jobs cannot read those task IDs  
- Separate buckets / projects; audit access  
- Version pin: eval suite references exact task versions  
- Treat leakage like trading on insider data — severity = ship blocker

### 2.9 Close (60-sec)

> “Trade-off I chose: **layered verification + fidelity tiers** over a single smart judge and one mega-realistic env. That keeps cost controllable and trust measurable. With more time I’d deepen multi-app clock sync and partner-facing eval SLAs. This mirrors our Skydo copilot inverted — there HITL on live tickets; here verifiers on gyms — product is still **trust before autonomy**.”

---

## 3. Variant A — Agent Execution / Computer-Use Platform

**Prompt:** Design infra for agents that operate a desktop/browser to complete jobs.

**Shift from primary:** heavier on:

- Browser/desktop sandbox pool (warm pool of VMs)  
- Screenshot / DOM / a11y tree observations; action: click/type/scroll  
- Observation compression (tile, diff, caption) to control tokens  
- Latency: speculative action queues; careful with double-click races  
- Safety: OS-level allowlist, no raw network, clipboard policy  

**Board spine:**

`Session Broker → Warm Sandbox Pool → Observation Pipeline → Action Executor → Trajectory → Verifier`

**One poke answer:** “Computer-use is higher fidelity and noisier; I use it to **certify**, and API tool-use to **scale training** when the job is API-complete.”

---

## 4. Variant B — Benchmarking & Uplift Measurement Service

**Prompt:** Design the service that decides whether a new model is better.

**Shift:** thinner gym detail, thicker stats + experiment design.

**Must cover:**

- Suite = stratified tasks (domain, difficulty, #apps, adversarial)  
- Fixed seeds + version pins for reproducibility  
- Metrics: success@k, partial credit, cost/episode, safety violations, latency  
- Compare candidate vs baseline with **confidence intervals** / bootstrap — not one lucky run  
- Gate states: `ship` / `reject` / `investigate` (flake suspected)  
- Leaderboard is **not** the product; **held-out delta** is  

**Say:**

> “A single accuracy number is a vanity metric. Stratified golden sets + regression budgets are the product.”

**Bridge:** Skydo golden set of ~500 tickets stratified by intent/risk/tool-hops; zero-tolerance on policy misses.

---

## 5. Variant C — Enterprise Workflow Dataset Pipeline

**Prompt:** Design pipeline from enterprise partners → scrubbed training data → tasks in gyms.

**Spine:**

```
Source Connectors → Ingest Lake → PII/BII Scrub → Workflow Graph Extract
    → Expert Rubric / QA → Task Synthesis → Gym Packaging → Delivery Catalog
```

**Hard problems:**

- PII scrub without destroying workflow structure (your SOC2/CIO story)  
- Consent / contractual boundaries per partner  
- Dedup + quality scoring; human+AI dual validation  
- Lineage: every training sample traces to source + scrub version  

**Out of scope trap:** don’t turn this into a generic Airflow resume dump — keep **workflow → task** as the point.

---

## 6. Variant D — Multi-tenant Gym Platform for Frontier Labs

**Prompt:** Multiple frontier labs share gym tech; data and evals must not leak.

**Add on top of primary:**

- Tenant = {keys, gym visibility, quotas, data residency}  
- Hard isolation: separate KMS keys, object prefixes, optional dedicated sandbox clusters for top tiers  
- Per-tenant eval reports; no cross-tenant aggregates that leak tasks  
- Rate limits + fair scheduling across noisy neighbors  
- Contract: who owns derived trajectories  

**Poke:** noisy neighbor → separate node pools; dedicated for platinum tenants.

---

## 7. Deep-dive bank (they will poke these)

### Q: Process reward vs outcome reward?

> Outcome when the end state is checkable — harder to hack. Process helps credit assignment on long tasks but agents learn to look busy. Hybrid: sparse outcome + small process bonus only on verified intermediate milestones.

### Q: How do you handle flaky environments?

> Classify fail vs flake (rerun budget, seed replay). Flakes don’t count as model fail. Track flake rate per gym version; block gym release if flake &gt; budget. Record/replay tier for debugging.

### Q: LLM judge disagrees with expert?

> Experts are ground truth for calibration set. Measure IRR. If LLM drifts, freeze judge version, re-calibrate, or demote LLM to advisory. Never ship partner uplift on uncalibrated judge alone.

### Q: Multi-app consistency (Jira + Slack)?

> One sandbox network namespace; shared logical clock; transactional reset snapshot across apps; inject consistent user identities. Avoid partial resets.

### Q: Scale to 100K episodes/day?

> Queue → worker fleet; warm sandbox pool; pack short API episodes densely; computer-use on separate pool. Metadata Postgres; trajectories object store; metrics OLAP. Autoscale on queue lag.

### Q: Exactly-once episode execution?

> Don’t claim distributed exactly-once. Durable episode intent + idempotent `CreateEpisode`; sandbox effects are resettable. At-least-once workers with episode lease + fencing token.

### Q: How is this different from unit tests?

> Unit tests check code. Gyms check **agent policy** under interactive partial observability. Verifiers score trajectories; evals measure **model uplift**, not app regressions — though gym code itself still needs classic tests.

### Q: Where would you use Kafka?

> Async paths: trajectory ingest, eval fan-out, scrub pipeline. **Not** on gym step hot path — keep step synchronous RPC to sandbox for low latency and simpler timeout semantics.

### Q: Online vs offline RL here?

> Platform primarily produces **offline datasets + evals**. Online RL possible inside gyms with care (policy updates vs contamination). I’d separate online experimental sandboxes from held-out cert suites.

---

## 8. APIs, data model, capacity napkin

### Core APIs

```
POST /gyms/{gym_ver}/reset     {seed, task_init} → {episode_id, obs}
POST /episodes/{id}/step       {action} → {obs, done, info}
POST /episodes/{id}/verdicts   {verifier_ver} → {score, subscores, evidence}
POST /evals                    {suite_id, baseline, candidate} → {eval_run_id}
GET  /evals/{id}               → {metrics, decision, cis}
```

### Entities

```
Tenant, GymVersion, TaskVersion(held_out, rubric_ref),
AgentVersion, Episode, Step, Artifact, Verdict, EvalSuite, EvalRun
```

### Capacity napkin (say your assumptions)

Assume **50K episodes/day**, avg **20 steps**, avg obs artifact **200KB**:

- Steps/day ≈ 1M → ~12 steps/sec average (bursty → design for 10× = 120/s)  
- Artifact storage ≈ 50K × 20 × 200KB ≈ **200 GB/day** raw → compress/diff/tiered retention  
- Bottleneck: **sandbox concurrency**, not API QPS — size warm pool by p95 episode length × target parallelism  

---

## 9. Bridge lines to your resume

| Design topic | Say |
|---|---|
| Durable episode before side effects | “Same as payments: intent persisted before partner call.” |
| Verifier ≠ single success signal | “Recon closes silent partner success; here layered verifiers.” |
| Promotion gate | “Shadow → gate → kill switch on the copilot; same for model ship.” |
| PII / SOC2 | “Scrub workflows, keep structure — CIO/SOC2 muscle.” |
| Job fleet / async | “Eval orchestrator ≈ Katar-style durable jobs with visibility.” |
| Locks / leases | “Episode worker lease ≈ Redis lock + fencing on critical sections.” |
| Stratified evals | “Support golden set by intent/risk/tool-hops — not random accuracy.” |

---

## 10. Common failure modes in this round

| Mistake | Fix |
|---|---|
| Designing ChatGPT clone | Force gym + verifier + uplift gate on the board first |
| Only LLM-as-judge | Layer L0–L3; say when LLM is disallowed as sole gate |
| No held-out story | Contamination = ship blocker |
| No numbers | 30-sec napkin: episodes, storage, sandbox bottleneck |
| Jumping to Kafka/K8s | Contracts + data ownership before infra |
| Ignoring computer-use cost | Fidelity tiers |
| Claiming exactly-once magic | Intent + lease + resettable sandbox |
| Forgetting multi-tenant | If partners exist, isolation is an NFR from minute 5 |
| No close | Trade-off + resume bridge + next deepening |

---

## Rehearsal plan (before you leave)

1. **Once out loud (25 min):** Primary design end-to-end with phone timer.  
2. **10 min:** Drill pokes in §7 cold.  
3. **5 min:** Sketch Variant B uplift service from memory.  
4. Stop. Don’t cram more architectures — depth on one beats shallow five.
