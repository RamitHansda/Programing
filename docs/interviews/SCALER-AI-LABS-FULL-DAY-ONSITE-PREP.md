# Scaler AI Labs (Evaratus) — Full-Day Onsite Interview Prep
**Ramit Hansda · Bangalore office · Full-day loop**

> **Naming:** People still say “Scaler AI Labs” / “Scalar AI Lab.” The product company is now **Evaratus** ([evaratus.com](https://evaratus.com/)). Email domain still uses `scalerailabs.com`. If a badge or deck says Evaratus, same company — don’t look confused.
>
> **Not the same as:** [Scalar Labs](https://scalarlab.co/) (Polaris / family-office applied-intelligence studio). Different entity. This pack is for **Scaler AI Labs → Evaratus**.

---

## How to use this

1. Skim Sections 1–3 night before (company + pitch + day map).
2. Drill Section 5 (RL gym / eval HLD) once out loud with a whiteboard.
3. Rehearse 4 STAR stories in Section 7 — don’t invent new ones day-of.
4. Morning of: print/open the day-of cheatsheet only.

---

## Table of Contents

1. [What they build (talk like an insider)](#1-what-they-build-talk-like-an-insider)
2. [Your positioning for this loop](#2-your-positioning-for-this-loop)
3. [Likely full-day structure](#3-likely-full-day-structure)
4. [Round-by-round playbook](#4-round-by-round-playbook)
5. [System design — RL gym / eval platform (must nail)](#5-system-design--rl-gym--eval-platform-must-nail)
6. [Technical depth they will poke](#6-technical-depth-they-will-poke)
7. [STAR stories mapped to their world](#7-star-stories-mapped-to-their-world)
8. [Coding / LLD / DSA focus](#8-coding--lld--dsa-focus)
9. [Leadership & culture (HM / founders)](#9-leadership--culture-hm--founders)
10. [Questions to ask](#10-questions-to-ask)
11. [Red flags to avoid](#11-red-flags-to-avoid)
12. [Logistics](#12-logistics)

---

## 1. What they build (talk like an insider)

**One-liner you can say:**

> “Evaratus is the apparatus that makes frontier models better at real work — proprietary enterprise/consumer workflow data, high-fidelity RL gyms, expert-verified tasks, and held-out evals that only ship when uplift is proven.”

### Four pillars (memorize)

| Pillar | What it is | Why it matters |
|---|---|---|
| **Enterprise data** | Proprietary workflow data from 350+ sector leaders; PII/BII scrubbed, workflows retained; SOC 2 | Models can’t learn real enterprise jobs from public web alone |
| **Consumer data** | Power-user workflows across 25+ countries / 100+ use cases | Personal superintelligence needs real life tasks, not demos |
| **RL gyms** | 1000+ computer-use / tool-use environments; multi-app gyms that simulate whole jobs | Practice environment before production; shaped like the job, not like what’s easy to verify |
| **Expert network** | Millions of experts (eng, MLE, medical, tax, IB, …) | Rubrics, verification, hard task design at scale |

### Their research loop (say this shape)

```
Proprietary inputs (data + gyms + experts)
        ↓
Applied research: failure analysis → task design → verification → reward modeling
        ↓
Held-out eval: baseline → post-training → Δ uplift
        ↓
Ship only after proven uplift
        ↓
Deployed models surface new failures → new tasks / data → next cycle
```

### Public signals worth knowing (don’t recite as press release)

- Working with **4/5 top frontier labs**
- **$30M+/mo** of work delivered (as of Aug 2026, per site)
- **250+** enterprise partners
- Research areas: coding, verifiable job domains, life sciences/healthcare, world models / physical AI
- Roots in Scaler’s 10 years of learning + evaluation rubrics (millions of learners, hiring pipelines)
- Bangalore engineering presence; SF entity / Form D as Scaler AI Labs Inc. (rebrand Evaratus)

### What “good engineer” means here

They hire for people who **ship environments, verifiers, evals, and data pipelines** — not people who only talk about fine-tuning papers. Ownership, debugging under ambiguity, and product-quality software inside messy AI loops.

---

## 2. Your positioning for this loop

You are not pitching “EM who wants to manage.” You are pitching **senior engineering leader who builds trusted agent/automation systems and can own gyms, evals, and production correctness**.

### 90-second open

> “I’m Ramit. I’ve spent 10+ years on systems where wrong answers cost money — payments at Skydo (10K+ international txns/day, idempotency and recon), and before that VP at Goldman on multi-terabyte risk compute.
>
> At Skydo I also designed an **agentic support copilot**: RAG + read-only tools + human-in-the-loop. ~65% triage, 35–40% assisted resolve, FRT 90→10 minutes, SLA −50% — with hard policy that the model never moves money. I led GenAI adoption standards across eng, and as CIO took us through ISO 27001 and SOC 2.
>
> What pulls me to Scaler AI Labs / Evaratus is the hard version of that problem: **making models reliable on real enterprise work**. Gyms, verifiers, held-out uplift — that’s the same correctness instinct I’ve lived in payments and risk, applied to AI. I want to build the apparatus, not bolt a chatbot on top.”

### Why you fit their pillars

| Their need | Your proof |
|---|---|
| High-fidelity environments / tool-use | Agentic copilot: tools, guardrails, audit trail, escalation |
| Evals before ship | Stratified golden sets, regression budgets, shadow → gate → kill switch |
| Enterprise / regulated data | Payments + SOC2/ISO; PII-aware design |
| Expert rubrics / hiring bar | Led hiring for team of 12; Scaler-adjacent culture of rigorous evaluation |
| Scale + correctness | GS sharding/failover; Moneyview millions of debit instructions/day |

### Role framing if level is ambiguous

If loop is **Staff/Principal IC** → lean IC: architecture of gyms, eval platforms, execution infra.  
If loop is **EM / Eng Lead** → lean: shipping multi-team systems, hiring bar for AI eng, quality of evals as a product.  
Default if unclear: **IC depth first, leadership as amplifier** — this company worships builders.

---

## 3. Likely full-day structure

Full-day Bangalore onsites for senior AI/platform roles usually look like this. Exact schedule may vary — ask recruiter night before and rewrite times on the cheatsheet.

| Block | Typical length | What they’re testing |
|---|---|---|
| Welcome / agenda / culture intro | 15–30m | Energy, curiosity, why here |
| Coding / problem-solving | 45–60m | Clarity, correctness, communication under pressure |
| System design (gym / eval / agent platform) | 60–75m | Architecture judgment for *their* domain |
| Deep dive on your work (agentic / payments) | 45–60m | Real ownership vs resume inflation |
| LLD or live extension (“change this design”) | 45–60m | Code taste, APIs, state machines |
| HM / leadership / founder | 30–45m | Ownership, pace, culture fit |
| Team lunch / informal | — | Are you someone they want in the room |
| Your questions + wrap | 15–20m | Signal quality |

**Energy budget:** Front-load water + protein; protect voice for afternoon deep-dive. Treat lunch as interview — no ranting about past employers.

---

## 4. Round-by-round playbook

### A. Coding

**Style:** Medium-hard DSA or practical coding (parsers, state machines, queues, file/log tooling, graph of tool calls). They care about **thought process** more than memorized patterns.

**Your protocol:**
1. Restate + constraints + examples (2 min)
2. Brute → better → complexity (3 min)
3. Code cleanly; narrate invariants
4. Tests: empty, one element, adversarial, large
5. If stuck: say what you’d try next — don’t freeze

**Practice set (morning skim):** sliding window / deque; topological order (tool dependency); try/retry with backoff; rate limiter; LRU; parse structured logs; “tail -n” style streaming; interval merge; BFS shortest path with constraints.

### B. System design (see Section 5)

Lead with **problem framing for RL/evals**, not a generic chat-app design. Ask: who is the agent? what is a step? what is success? how do we verify? what’s held-out?

### C. Resume deep-dive

They will pressure-test the agentic copilot and payments platform. Have numbers ready. Be ready to whiteboard the state machine and failure modes.

### D. Live modification

Expect: “Add multi-tenant gym isolation,” “Add reward hacking defenses,” “Make verifier async,” “Support multi-app trajectories.” Think in interfaces, not rewrites.

### E. HM / culture

Pace is high. They filter for people who **don’t wait for permission**, debug their own doubt, and ship. Match with concrete stories of 0→1 under ambiguity — not “I need perfect specs.”

---

## 5. System design — RL gym / eval platform (must nail)

### Prompt they may give (or close variants)

> Design a platform where AI agents practice enterprise workflows (e.g. Jira + Slack + Gmail), we score them, and we only promote training changes after measured uplift on held-out tasks.

### 5.1 Clarify (5 min)

| Question | Good default |
|---|---|
| Agent type? | Tool-use / computer-use agents (browser + APIs + MCP) |
| Scale? | 1K+ gyms, 10K–100K concurrent episodes/day (say your assumption) |
| Success metric? | Task success rate, partial credit, cost, latency, safety violations |
| Training vs eval? | Same gyms; **held-out task split** never used for training |
| Verifiers? | Mix: unit checks, golden finals, LLM-as-judge + expert audit |
| Latency? | Eval batch OK minutes–hours; interactive gym steps &lt; few seconds |

### 5.2 Core abstractions (draw these)

```
┌─────────────┐     ┌──────────────┐     ┌─────────────┐
│ Task Spec   │────▶│ Gym Runtime  │────▶│ Trajectory  │
│ (rubric,    │     │ (state, apps │     │ (steps,     │
│  tools,     │     │  tools, sand │     │  obs, acts, │
│  success)   │     │  box)        │     │  rewards)   │
└─────────────┘     └──────────────┘     └──────┬──────┘
                                                 │
                        ┌────────────────────────▼────────┐
                        │ Verifier / Reward Model         │
                        │ deterministic → heuristic → LLM │
                        │ → expert spot-check             │
                        └────────────────────────┬────────┘
                                                 │
                        ┌────────────────────────▼────────┐
                        │ Eval Service                    │
                        │ baseline vs candidate, uplift Δ │
                        │ gate: ship / reject / investigate│
                        └─────────────────────────────────┘
```

### 5.3 Components

1. **Task registry** — versioned task specs: tools allowed, initial world state, success criteria, difficulty tags, domain, held-out flag.
2. **Gym / environment service** — sandboxed replicas of apps (or high-fidelity mocks + real APIs in constrained accounts). Episode = reset → step loop → terminate.
3. **Agent harness** — adapter for model providers; tool schema; timeout; max steps; observation truncation.
4. **Trajectory store** — append-only steps; content-addressed artifacts; PII scrubbing pipeline.
5. **Verifier stack** — layered:
   - L0 deterministic (SQL assert, file hash, API state equals expected)
   - L1 rubric heuristics (checklist)
   - L2 LLM judge with structured rubric (never sole authority on high-stakes)
   - L3 expert review sample
6. **Reward model training** (if RL) — train on verified preferences; monitor reward hacking.
7. **Eval orchestrator** — runs suites; computes metrics with CIs; compares to baseline; blocks ship without uplift.
8. **Observability** — failure taxonomy (tool error vs planning vs hallucination vs verifier false negative).

### 5.4 Data model (sketch)

```
Gym { id, version, apps[], capabilities }
Task { id, gym_id, version, held_out, rubric_ref, init_state_ref }
Episode { id, task_version, agent_version, status, started_at }
Step { episode_id, t, observation_ref, action, tool_calls[], reward_partial }
Verdict { episode_id, verifier_version, score, subscores{}, evidence_refs[] }
EvalRun { suite_id, baseline_agent, candidate_agent, metrics{}, decision }
```

### 5.5 Hard problems they want you to name

| Problem | Strong answer |
|---|---|
| **Reward hacking** | Prefer outcome verification over process; randomize non-essentials; adversarial held-out; human audit of high-reward weird trajectories |
| **Env fidelity vs cost** | Tiered: mock → recorded replay → live sandbox → production shadow. Train on cheaper tiers; certify on higher |
| **Non-determinism** | Seeded resets; record/replay; separate flaky vs fail; flake budget in CI |
| **Verifier quality** | Dual control on gold labels; IRR between experts; calibrate LLM judges against expert panels |
| **Contamination** | Cryptographic held-out split; access control; never train on eval IDs |
| **Multi-app state** | Single orchestrated sandbox network; transactional reset; clock control |
| **Safety** | No unconstrained network; secret injection; action allowlists; audit every consequential tool |
| **PII** | Scrub at ingest; synthetic identities in gyms; SOC2-minded retention |

### 5.6 Scale sketch (if they go HLD-deep)

- Episodes: queue (SQS/PubSub) → worker pool (K8s) → sandbox VMs/containers
- Hot path: gym step API low latency; cold path: batch evals
- Storage: object store for observations; OLAP for metrics; Postgres for metadata
- Multi-tenant isolation per partner lab if needed (separate accounts / projects)

### 5.7 Bridge to your experience (say out loud)

> “This is the same shape as our support copilot, just inverted: there the agent acted on real tickets with HITL; here the agent acts in a gym with a verifier. In both cases the product is **trust** — audit trails, policy outside the model, and promotion gates based on measured quality, not vibes.”

---

## 6. Technical depth they will poke

### RL / agents (practical, not textbook)

- **Episode, observation, action, reward, termination** — define crisply.
- **Online vs offline RL** — they do a lot of data + eval apparatus; don’t pretend you’re a pure RL researcher unless you are.
- **Process reward vs outcome reward** — outcome preferred when verifiable; process helps credit assignment but hacks easier.
- **Computer-use vs API tool-use** — computer-use = higher fidelity, noisier, costlier; API = cleaner, may miss UI-only work.
- **MCP / tool servers** — schema, auth, least privilege.

### Evals

- Golden sets must be **stratified** (difficulty, domain, tool count, adversarial).
- Metrics: pass@k, success rate, partial credit, cost/token, safety violation rate, human agreement.
- **Uplift** = candidate − baseline on held-out; require statistical confidence, not a single run.
- Contamination and leakage are career-ending bugs in this domain — treat like trading on insider data.

### Backend / platform

- Idempotent episode starts; exactly-once *effects* inside sandbox via transactional reset (don’t claim magic exactly-once across distributed systems).
- Job scheduling for eval fleets (your Katar / async experience maps well).
- Distributed locks when mutating shared gym templates.
- Observability: structured failure reasons &gt; raw accuracy %.

### Quick definitions (30-sec answers)

| Term | Say |
|---|---|
| RLHF | Human preference → reward model → policy optimize; brittle if prefs noisy |
| RLAIF | AI feedback instead of/in addition to humans; cheaper, needs calibration |
| Verifier | Program or model that scores trajectory; prefer deterministic when possible |
| Gym | Environment implementing reset/step; task lives *on* gym |
| Held-out | Never trained on; measures true generalization |
| Reward hacking | Agent exploits proxy reward without solving task |
| Trajectory | Sequence of obs/act/(reward) for an episode |

---

## 7. STAR stories mapped to their world

### Story 1 — Trusted agent (copilot)

- **S:** Support volume; need speed without wrong financial advice / money movement  
- **T:** Ship agentic assist with measurable FRT/SLA gains and zero autonomy on money  
- **A:** RAG + read-only tools + PII mask + policy outside model + audit + shadow→gate→kill switch  
- **R:** 65% triage, 35–40% resolve assist, FRT 90→10, SLA −50%  
- **Map:** Same as their “agents that earn keep” + verification before expand

### Story 2 — Correctness under failure (payments)

- **S:** Cross-border payments, partner webhooks unreliable  
- **T:** No double-pay, silent success must reconcile  
- **A:** Idempotency keys, durable intent before side effect, Redis lock on critical path, async Katar jobs, recon queues  
- **R:** 10K+/day; incidents −30% via observability  
- **Map:** Env reset + durable episode state + “never claim success without verify”

### Story 3 — Eval / quality bar (GenAI adoption)

- **S:** Org wanted AI coding assistants everywhere  
- **T:** Speed without secret leakage / garbage PRs  
- **A:** Standards, allow/deny data sources, review norms, measure adoption vs quality  
- **R:** Faster delivery with explicit guardrails (CIO/SOC2 lens)  
- **Map:** Rubrics, expert network mindset, ship only with proven uplift

### Story 4 — Scale platform (Goldman)

- **S:** Multi-TB in-memory risk aggregation  
- **T:** Failover and throughput  
- **A:** Sharding, replication, fault tolerance  
- **R:** Failover −40%, batch −25%, ~3× throughput  
- **Map:** Eval fleet / gym worker scale

---

## 8. Coding / LLD / DSA focus

### LLD prompts you should be able to sketch in 20 min

1. **Episode runner** — interfaces for `Env`, `Agent`, `Verifier`; timeout; cancel; retries  
2. **Rate limiter** for model API calls across tenants  
3. **Priority eval queue** — P0 regression suite vs bulk training rollouts  
4. **Rubric engine** — composable checks with short-circuit on hard fail  
5. **Audit log** — immutable append, hash chain optional

### DSA themes aligned to their work

- Graphs: tool dependency / multi-hop plans  
- Queues / heaps: job scheduling  
- Strings / parsing: log and trajectory parsers  
- Sliding window: streaming metrics  
- Union-find / components: clustering failure modes (conceptual)

### Language

Be ready in **Python** (AI/data) and **Java/TypeScript** if they mirror your resume. Ask which they prefer; don’t fight it.

---

## 9. Leadership & culture (HM / founders)

### What to signal

- High agency; ships under incomplete specs  
- Obsessed with **measurement** (uplift, not demos)  
- Comfortable with research-adjacent eng (uncertainty) *and* production eng (SLOs)  
- Can hire and raise bar for people who build gyms/verifiers  
- Security/compliance as product constraint (SOC2 story)

### Likely questions + angles

| Question | Angle |
|---|---|
| Why leave Skydo / why us? | Builder mode; AI reliability as core product; their apparatus thesis |
| Conflict with research vs shipping? | Time-box experiments; promotion gates on held-out metrics |
| How do you set quality bar? | Stratified evals; flake budgets; no vibes-only launch |
| Failures? | Prefer a verifier false-negative story or payments near-miss with recon catch |
| How do you work with frontier lab partners? | Clear contracts, data boundaries, reproducible eval harness |

### Why Scaler AI Labs / Evaratus (spoken)

> “Most AI companies demo agents. You’re building the **training and evaluation apparatus** that makes agents competent at real jobs — gyms, data, experts, uplift gates. That’s the leverage layer. My career has been building leverage layers in payments and risk; I want that same seat in the AI stack.”

---

## 10. Questions to ask

Pick 3–4 max per interviewer; don’t spray.

1. How do you decide the fidelity tier for a new gym (mock vs live sandbox)?  
2. What’s the biggest source of false signal in your evals today — flaky envs, weak verifiers, or contamination risk?  
3. For a Staff/EM hire, what does success look like in 6 months — gym coverage, uplift contracts with labs, or platform reliability?  
4. How are reward models validated against expert IRR before they train policies?  
5. Where do Bangalore eng teams own vs partner with SF / research?  
6. What’s harder right now: computer-use fidelity or verifiable rewards in fuzzy domains?  
7. How do partner frontier labs consume your artifacts — datasets, gyms, ranked diffs?

---

## 11. Red flags to avoid

- Calling it “just Prompt Engineering Co.”  
- Claiming **exactly-once** delivery casually  
- “We’ll fine-tune everything” as architecture  
- Treating LLM-as-judge as ground truth  
- Dismissing DSA (“I don’t need algorithms for AI”)  
- Badmouthing Scaler’s education business — it’s their data/rubric moat  
- Confusing them with Scalar Labs (Polaris) mid-conversation — if you slip, recover: “I mean Evaratus / Scaler AI Labs”  
- Over-indexing on people management if the room is IC-heavy  

---

## 12. Logistics

- **Location:** Confirm Bangalore office address + floor with recruiter; arrive 15 min early.  
- **Bring:** Laptop charged, notebook, water, ID, list of questions.  
- **Laptop:** Be ready to code in their IDE or shared doc; know your GitHub if they ask for past code.  
- **Phone:** Silent; battery pack if long day.  
- **After:** Same-day thank-you note to recruiter with 2 concrete technical takeaways from the day (shows you were listening).

---

## Appendix A — One-page architecture script (memorize flow)

1. Clarify task, gym, success, held-out.  
2. Draw Task → Gym Runtime → Trajectory → Verifier → Eval Gate.  
3. Name reward hacking, fidelity tiers, contamination.  
4. Sketch scale: queue + sandbox workers + object store + metrics.  
5. Bridge to Skydo agent trust model.  
6. Ask what constraint matters most to *them* (cost, fidelity, partner SLA).

## Appendix B — Metrics card (yours)

- Skydo: 10K+ txn/day · incidents −30% · onboarding hours→minutes  
- Copilot: 65% triage · 35–40% resolve · FRT 90→10 · SLA −50%  
- GS: failover −40% · batch −25% · ~3× throughput  
- Moneyview: millions debit instructions/day · ops −60%  
- Compliance: ISO 27001 · SOC 2 Type II (CIO)
