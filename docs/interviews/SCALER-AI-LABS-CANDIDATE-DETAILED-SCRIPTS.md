# Scaler AI Labs — Detailed Candidate Scripts
**You are the candidate (Ramit, ~10 YOE). First person. Say this / do this.**

> Full day companion: [`SCALER-AI-LABS-INTERVIEW-SCRIPTS.md`](./SCALER-AI-LABS-INTERVIEW-SCRIPTS.md) (short version)  
> Design 1-pager: [`SCALER-AI-LABS-SYSTEM-DESIGN-DAYOF.md`](./SCALER-AI-LABS-SYSTEM-DESIGN-DAYOF.md)

This doc expands **every step** with: what you **say**, what you **write on the board**, and what you **do** if they interrupt.

---

# PART 1 — System Design Round (~60 min)

**Interviewer:**  
“Design a platform where AI agents practice real enterprise workflows — think Jira, Slack, email — we score how well they do, and we only promote a new model or prompt when we’ve measured uplift on held-out tasks.”

---

## STEP 0 — Settle + frame (≈ 1 min)

### What I do
- Sit square to the whiteboard / laptop shared doc  
- Smile, repeat the prompt briefly so we’re aligned  
- Do **not** draw boxes yet  

### What I say

> “Thanks — that’s a great problem, and it’s very close to how I think about trusted agents.  
>  
> Before I draw anything, I’ll treat this as an **evaluation and training apparatus**, not a chatbot product. My plan for the next hour:  
> 1) clarify and scope,  
> 2) functional and non-functional requirements,  
> 3) a quick capacity napkin so we know the bottleneck,  
> 4) APIs and data model,  
> 5) high-level architecture,  
> 6) two or three hard deep-dives,  
> 7) failures, scale, and trade-offs.  
>  
> I’ll state assumptions out loud — please jump in anytime if you want me to go deeper or change direction.”

### What I write
```
Plan: clarify → FR/NFR → capacity → API/data → arch → deep dive → failures
```

---

## STEP 1 — Clarifying questions (≈ 3–4 min)

### What I say

> “Three questions that actually change the design for me:  
>  
> **First — agent interface.** Are we talking API tool-use only — call Jira/Slack APIs — or full computer-use where the agent operates a browser or desktop, or both?  
>  
> **Second — definition of success.** Do we need binary pass/fail on a task, partial credit across rubric dimensions, or preference labels for reinforcement learning?  
>  
> **Third — consumers.** Is this an internal platform for your own training loop, or a multi-tenant platform that frontier-lab partners use, with isolation and SLAs?”

### If interviewer says “you decide” / shrugs

> “Okay — I’ll assume **both** API tool-use and computer-use, with API as the cheap path and computer-use for high-fidelity certification. Success is **outcome verification with partial credit**, and preference data is a secondary output. And I’ll assume **multi-tenant partners**, so isolation and held-out protection are first-class. I’ll call those out as assumptions on the board.”

### What I write
```
Assumptions:
- Agents: API tools + computer-use (certify on CU)
- Success: outcome + partial credit (+ prefs later)
- Multi-tenant frontier-lab partners
- Same gyms for train & eval; held-out tasks never in training
```

### Extra Qs only if time / they invite more

> “One more if useful — rough scale: are we thinking thousands of episodes a day or hundreds of thousands? I’ll assume mid tens of thousands and you can correct me.”

---

## STEP 2 — Scope in / out (≈ 1–2 min)

### What I say

> “Let me lock scope so I don’t boil the ocean.  
>  
> **In scope:** versioned gyms and tasks, running episodes safely in sandboxes, storing trajectories, scoring with a verifier pipeline, and an eval service that compares a candidate agent to a baseline and decides ship or reject based on held-out uplift.  
>  
> **Out of scope for this design:** actually training the frontier model’s weights end-to-end, rebuilding Jira or Slack as products, and letting agents browse the open internet without a sandbox. We can extend later if you want.”

### What I write
```
IN: gyms/tasks, episodes, trajectories, verifiers, uplift gate
OUT: train frontier weights, build SaaS apps, open internet
```

---

## STEP 3 — Functional requirements (≈ 2–3 min)

### What I say

> “Functional requirements, numbered so we can point at them:  
>  
> **FR1** — Register and version **gyms** — the environment: which apps, which tools, what capabilities — and **tasks** that sit on a gym: initial world state, allowed tools, success rubric, difficulty tags, and a held-out flag.  
>  
> **FR2** — Run an **episode**: create → reset world → step loop with observations and actions → terminate on success, fail, timeout, or max steps.  
>  
> **FR3** — Persist a full **trajectory**: every step’s observation reference, action, tool calls, intermediate artifacts, timestamps.  
>  
> **FR4** — **Score** the episode through a layered verifier — not a single opaque score.  
>  
> **FR5** — Run **eval suites**: same tasks, baseline agent vs candidate agent, compute metrics and a gate decision: ship, reject, or investigate.  
>  
> **FR6** — Optionally export verified preferences or rewards for offline RL — secondary, not the hot path.  
>  
> Does that match what you had in mind, or want to add/remove one?”

### What I write
```
FR1 Versioned Gym + Task (held_out, rubric)
FR2 Episode: reset → step → terminate
FR3 Trajectory store (obs/act/tools/artifacts)
FR4 Layered verification → Verdict
FR5 Eval suite: baseline vs candidate → gate
FR6 (opt) reward / preference export
```

---

## STEP 4 — Non-functional requirements (≈ 2–3 min)

### What I say

> “Non-functionals — these drive the architecture more than the FRs:  
>  
> **Latency:** for API tool steps I want p95 under about two to three seconds; computer-use steps can be slower, but we still need hard timeouts.  
>  
> **Throughput:** I’ll design for on the order of **ten to a hundred thousand episodes per day** — napkin next.  
>  
> **Correctness / trust:** zero **held-out contamination**. If training can see eval tasks, the whole product is lying. Verifier false-positive budget must be explicit.  
>  
> **Isolation:** partner tenants cannot see each other’s gyms, tasks, or trajectories.  
>  
> **Safety:** no unconstrained network egress from sandboxes; secrets injected by the platform; action allowlists for consequential tools.  
>  
> **Durability & reproducibility:** trajectories append-only; every run pinned to gym version, task version, agent version, verifier version, and seed.  
>  
> **Cost:** we intentionally use **cheap fidelity for training volume** and **expensive fidelity for certification**. I will not pretend one mega-realistic env is always right.”

### What I write
```
NFR:
- step p95 <2–3s (API); CU higher + timeouts
- ~10K–100K ep/day
- ZERO held-out contamination
- tenant isolation
- sandbox: no open egress, allowlists
- reproducible (versions + seed)
- cheap train / expensive certify
```

---

## STEP 5 — Capacity napkin (≈ 3–4 min)

### What I say

> “Quick napkin so we know what to optimize.  
>  
> Assume **50,000 episodes per day**, average **20 steps** per episode, average observation artifact about **200 KB**.  
>  
> Steps per day ≈ 50K × 20 = **1 million steps/day**. Divided by 86,400 ≈ **12 steps/sec average**. Bursts happen when an eval suite fans out, so I’ll design for roughly **10×**, about **120 steps/sec** peak.  
>  
> Storage: 50K × 20 × 200 KB ≈ **200 GB/day** raw artifacts. That’s painful if we keep forever at full fidelity — so we **diff screenshots, compress, and tier retention** — hot for a week, warm for a month, cold sample after that.  
>  
> Critical insight: the bottleneck is almost certainly **sandbox concurrency** — how many live Jira/Slack/browser worlds we can run — not the API gateway QPS. So I’ll put a **warm sandbox pool** at the center of the scale story, sized roughly from:  
> `target parallel episodes × p95 episode duration`.  
>  
> Metadata stays small in Postgres; trajectories go to object storage; metrics go to an OLAP store for suite dashboards.”

### What I write
```
50K ep/day × 20 steps = 1M steps/day (~12/s avg → design 120/s burst)
Artifacts ~200GB/day raw → diff/compress/tier
BOTTLENECK: sandbox pool concurrency (not REST)
Meta: Postgres | Traj: object store | Metrics: OLAP
```

---

## STEP 6 — APIs and data model (≈ 5–6 min)

### What I say

> “Contracts next — this is what teams actually integrate with.  
>  
> **Entities:**  
> - `Tenant` — partner lab, keys, quotas  
> - `GymVersion` — apps, tools, capabilities  
> - `TaskVersion` — gym pointer, init state ref, rubric ref, difficulty, **held_out** boolean  
> - `AgentVersion` — model id, prompt/config hash, tool schema version  
> - `Episode` — task version, agent version, seed, status, timestamps  
> - `Step` — t, observation_ref, action, tool_calls[], reward_partial  
> - `Artifact` — content-addressed blob  
> - `Verdict` — scores, subscores, evidence refs, verifier_version  
> - `EvalSuite` / `EvalRun` — baseline, candidate, metrics, decision  

> **APIs I’d expose:**  
>  
> `POST /gyms/{gym_ver}/reset`  
> body: `{ seed, task_init }` → `{ episode_id, observation }`  
>  
> `POST /episodes/{id}/step`  
> body: `{ action }` → `{ observation, done, info }`  
>  
> `POST /episodes/{id}/verdicts`  
> body: `{ verifier_version }` → `{ score, subscores, evidence }`  
>  
> `POST /evals`  
> body: `{ suite_id, baseline_agent, candidate_agent }` → `{ eval_run_id }`  
>  
> `GET /evals/{id}` → metrics, confidence intervals, decision.  

> One design rule I’ll insist on: **creating an episode is durable before any sandbox side effect** — same pattern as a payment intent before calling a partner. If the worker dies mid-flight, we can see the intent and recover.”

### What I write
```
Tenant | GymVer | TaskVer(held_out) | AgentVer
Episode | Step | Artifact | Verdict | EvalSuite | EvalRun

reset(seed) → ep + obs
step(action) → obs, done
verdict(verifier) → score + evidence
evals(suite, base, cand) → run → decision
```

---

## STEP 7 — High-level architecture (≈ 10–12 min)

### What I do
Draw left → right. Narrate each box as I draw. Leave space above for Eval Orchestrator.

### What I say (while drawing)

> “I’ll draw the spine of the system.  
>  
> **Left — Task Registry & Gym Catalog.** This is the source of truth for versioned specs: which gym, init state, rubric, held_out. Control-plane writes go here; the hot path only reads immutable versions.  
>  
> **Next — Agent Harness.** Adapters to model providers, timeout, max steps, truncation of huge observations, retry policy for provider blips. The harness proposes actions; it does **not** decide business success.  
>  
> **Center — Gym Runtime** in a sandbox. This owns world state. Whether tools are fake APIs or real software in containers, the agent only talks to the gym. Reset is seeded and transactional across apps in that sandbox. Clocks can be controlled so multi-app workflows don’t race wall-clock.  
>  
> **Trajectory Store** sits under the step loop: append-only steps, pointers to artifacts in object storage, PII scrub on the way in.  
>  
> **Verifier Stack** consumes a finished trajectory — or checkpoints — and emits a Verdict with evidence.  
>  
> **Above everything — Eval Orchestrator.** CI or a partner triggers a suite. It fans out episodes for baseline and candidate, aggregates metrics, and returns ship / reject / investigate.  
>  
> **Side — Control Plane:** tenants, API keys, ACLs, gym template publishing, quotas. Not on the step hot path.  
>  
> Happy path in one breath: durable CreateEpisode → Reset → Step loop → Terminate → Verify → fold into EvalRun → gate.”

### What I write / draw
```
                 [Eval Orchestrator] → ship/reject/investigate
                         |
[Task Registry] → [Agent Harness] → [Gym Runtime] → [Trajectory Store]
   Gym/Task ver      model I/O         sandbox          artifacts
                         |                  |
                         +------→ [Verifier L0→L3] → Verdict
[Control Plane: tenants/keys/ACLs]
```

### Checkpoint with interviewer

> “Before I deep-dive — does this decomposition match what you wanted, or should I zoom into computer-use sandboxes vs the eval gate first?”

*(Let them steer. Default: verifiers + fidelity.)*

---

## STEP 8 — Deep dive: Verifier stack (≈ 7–8 min)

### What I say

> “The product promise is ‘we measured uplift,’ which means scoring must be trustworthy. Fuzzy enterprise tasks aren’t a single unit test, so I use a **layered verifier**.  
>  
> **L0 — Deterministic.** Wherever the end state is checkable, assert it: issue status is Done, comment exists with ID, row in DB matches golden, file hash equals expected. This is the gold standard.  
>  
> **L1 — Rubric heuristics.** Structured checklist: required fields present, no PII in outbound message, link format valid, step count under budget. Fast, explainable.  
>  
> **L2 — LLM-as-judge.** For open-ended quality — ‘was the summary faithful?’ — a judge model fills a structured rubric JSON. Critical: it is **calibrated on expert-labeled sets**, versioned, and monitored for drift.  
>  
> **L3 — Expert sample.** Continuous human review, especially on disagreements and on high-score weird trajectories. We track inter-rater reliability between experts.  
>  
> **Promotion rule:** for partner-facing certification, L0 is required when the task allows it. L2 is **never the sole gate** on high-stakes ship decisions. Every Verdict stores `verifier_version` and evidence refs so we can audit why we thought it passed.  
>  
> Partial credit is a weighted sum across rubric dimensions — so an agent that files the bug but forgets the Slack notify gets partial score, not a silent full fail with no signal.  
>  
> This is exactly how I think about payments reconciliation: I would never treat a single partner webhook as ground truth. Same here — never a single LLM score.”

### What I write
```
Verifier:
L0 deterministic (state == expected)
L1 rubric heuristics
L2 LLM judge (calibrated, versioned)
L3 expert sample + IRR
Rule: L2 ≠ sole gate on high-stakes
Verdict: score + subscores + evidence + verifier_ver
```

### If they poke “LLM judge is enough, right?”

> “It’s useful and cheap, but uncalibrated judges drift and can be gamed by verbose or sycophantic trajectories. I use them as L2 with a calibration set and demote them to advisory if IRR against experts drops. For money-adjacent or partner contract evals, I require L0 or human.”

---

## STEP 9 — Deep dive: Fidelity tiers + reward hacking + contamination (≈ 8–10 min)

### What I say

> “Three trust problems that kill platforms like this if you ignore them: **fidelity cost**, **reward hacking**, and **contamination**.  
>  
> **Fidelity tiers.**  
> - **T0 Mock** — in-memory fake tools. Great for unit tests and cheap RL rollouts.  
> - **T1 Record/replay** — deterministic recorded servers. Great for regression and debugging.  
> - **T2 Live sandbox** — real software in containers/VMs. Main training gyms.  
> - **T3 Partner shadow** — constrained real accounts. Certification before delivery.  
>  
> Rule I would put in the engineering handbook: **train on T0–T2; certify uplift on T2/T3 held-out**. If someone waves a T0 leaderboard as ‘we’re ready for the lab,’ I reject that.  
>  
> **Reward hacking.** Agents optimize the proxy. So I prefer **outcome** verification over process rewards when the end state is checkable. If I add process rewards for long horizons, they’re small and only on verified intermediate milestones. I randomize non-essential UI and IDs so policies can’t memorize pixels. I keep adversarial held-out tasks that break known shortcuts. And I human-audit trajectories that get high reward but look weird — short paths that somehow max the proxy.  
>  
> **Contamination.** Held-out tasks get a hard flag and ACLs. Training jobs literally cannot read those IDs — separate buckets, audited access. Eval suites pin exact task versions. If we ever find leakage, it’s a **ship blocker**, same severity as a data breach in my book — because your uplift number becomes fiction.”

### What I write
```
Fidelity: T0 mock → T1 replay → T2 sandbox → T3 shadow
Train cheap | Certify T2/T3 held-out

Reward hack: outcome > process | randomize | adversarials | audit weird highs
Contamination: held_out ACL | separate buckets | version pin | ship blocker
```

---

## STEP 10 — Failures, scale, consistency (≈ 4–5 min)

### What I say

> “Failure modes I’d design for before launch:  
>  
> **Flaky environment vs model fail.** If the gym flakes, that’s our bug, not the model’s. I classify with seeded replay and a small rerun budget. Flake rate per gym version is an SLO — high flake blocks gym release.  
>  
> **Verifier false positives.** Dual control on gold labels; investigate lane on the gate when baseline and candidate look suspiciously tied with high variance.  
>  
> **Sandbox poison / OOM.** Hard timeouts, memory limits, episode leases so a dead worker doesn’t leave the world locked forever. Quarantine bad gym images.  
>  
> **Partial multi-app reset.** Jira reset but Slack not — nightmare. One sandbox network namespace, transactional snapshot restore across apps, consistent injected identities.  
>  
> **Scale path.** Ingest eval work on a queue; workers pull with leases; warm pool for API gyms; separate pool for computer-use VMs; autoscale on queue lag.  
>  
> **Consistency language.** I will not claim distributed exactly-once. I claim: durable episode intent, idempotent create, at-least-once workers with fencing tokens, and **resettable** sandbox effects. Business correctness comes from verifiers and recon-style checks — same vocabulary I’d use in payments.”

### What I write
```
Flake vs fail (rerun, flake SLO)
Verifier FP → investigate lane
Sandbox lease + timeout + quarantine
Multi-app transactional reset
Scale: queue → workers → warm pools
EO: intent + lease + resettable effects (no magic)
```

---

## STEP 11 — Close (≈ 1 min)

### What I say

> “To close: the trade-off I deliberately chose is **layered verification plus fidelity tiers** over ‘one smart judge’ and ‘one ultra-realistic environment.’ That keeps cost under control and makes trust measurable.  
>  
> With more time I’d deepen multi-app logical clocks and the partner-facing eval SLA — what a frontier lab gets as an artifact pack.  
>  
> Personally, this is the same shape as the agentic support copilot I built at Skydo, inverted: there the agent acted on live tickets with human-in-the-loop; here it acts in a gym with verifiers. In both cases the product isn’t the model call — it’s **trust before autonomy**.  
>  
> Happy to go deeper on computer-use, multi-tenant isolation, or the data pipeline into tasks — wherever you want.”

### What I do
- Put the marker down  
- Face the interviewer  
- Stop talking  

---

## STEP 12 — Handling their follow-ups (candidate answers)

### “How is this different from unit testing our product?”

> “Unit tests verify *our* gym code. This platform measures *agent policies* under interactive, partially observed worlds. We still unit-test gyms — but the eval product is model uplift on held-out tasks, with verifiers scoring trajectories.”

### “Where does Kafka fit?”

> “Async paths: trajectory ingest fan-in, scrub pipelines, eval fan-out notifications. I would **not** put Kafka on the gym step hot path — I want synchronous RPC to the sandbox for simple timeouts and lower tail latency.”

### “Online RL or offline?”

> “This apparatus primarily produces offline datasets and evals. You can run online RL inside experimental gyms, but I physically separate those from held-out certification suites so online exploration never contaminates the score we sell to partners.”

### “Computer-use details?”

> “Warm VM/browser pool; observations as DOM + a11y tree + compressed screenshots; actions click/type/scroll with careful sequencing; OS-level allowlists; observation compression to control tokens. Computer-use certifies; API tools scale training when the job is API-complete.”

### “You’re an EM — would you build this or manage it?”

> “I’d own the architecture and the trust invariants myself first — verifier policy, held-out discipline, fidelity handbook — and grow a team around gym authors and eval infra. At this company I lead as a builder; management is how we scale the apparatus, not a substitute for it.”

---

# PART 2 — Opening pitch (detailed)

**Interviewer:** “Tell me about yourself.”

### What I say (≈ 90 seconds, practiced pace)

> “I’m Ramit — about ten years building systems where being wrong is expensive.  
>  
> Most recently at Skydo I led engineering across a team of roughly twelve on our cross-border payments and settlement platform. We process on the order of ten thousand international transactions a day. My fingerprints are on idempotency, reconciliation, distributed locking on money paths, and the async job systems that keep retries safe. I also served as CIO for ISO 27001 and SOC 2.  
>  
> In parallel I designed and shipped an **agentic support copilot** — RAG plus read-only tools plus human-in-the-loop. It triages about sixty-five percent of incoming volume, assists resolve on about thirty-five to forty percent, moved first-response time from roughly ninety minutes to about ten, and cut SLA breaches around in half. The hard product constraint: the model never moves money.  
>  
> Before Skydo I was a VP at Goldman Sachs on multi-terabyte distributed market-risk compute — sharding, replication, failover — which is where my instincts on observability and correctness under failure come from.  
>  
> I’m here because Scaler AI Labs — Evaratus — is building the **apparatus** that makes models good at real work: gyms, data, verifiers, held-out uplift. That’s the same correctness problem I’ve lived in, applied to AI. I want to build that layer.”

---

# PART 3 — Copilot deep-dive (detailed)

**Interviewer:** “Walk me through the agentic copilot.”

### What I say

> “Context: support volume was hurting first-response time, but in payments a wrong answer is worse than a slow one. So I refused a design that was ‘put GPT on the ticket.’  
>  
> **Architecture:** ticket comes in → risk/intent classification → retrieve policy chunks with citations → optionally call **read-only** tools — things like ‘fetch transaction status’ — → produce a draft triage or reply → **human-in-the-loop** for anything sensitive → full audit log.  
>  
> **Trust invariants I set as non-negotiable:**  
> 1) tools on money paths are read-only,  
> 2) PII is masked before the model,  
> 3) policy lives outside the model weights — documents in the index, not vibes,  
> 4) every run stores prompt, retrieved evidence, tool I/O, model version, and human action,  
> 5) rollout is shadow → gated expand → kill switch. Over-escalate beats wrong auto-resolve.  
>  
> **Evals:** we didn’t use a random accuracy number. We built a stratified golden set — by intent, risk tier, number of tool hops, and policy version — and a regression budget: zero tolerance on policy misses and PII leaks before promoting a prompt or model.  
>  
> **Results:** ~65% triage, ~35–40% assisted resolve, FRT ~90→10 minutes, SLA breaches ~−50%.”

### If they say “Add write tools”

> “I’d introduce an explicit capability ladder: read → draft → propose → execute. Execute requires either a human approval or a very narrow allowlist with amount caps, idempotency keys, and durable intent rows — same as payments. No execute without an auditable intent.”

### If they say “How does this map to our gyms?”

> “Inverted shape. There: live tickets + human verifier. Here: sandbox gym + programmatic/expert verifier. Same product: don’t grant autonomy without a measurement gate.”

---

# PART 4 — Payments deep-dive (detailed)

**Interviewer:** “Tell me about the payments platform guarantees.”

### What I say

> “The two failure modes that matter: **double-pay** and **silent partner success** — partner charged the customer but we timed out and thought it failed.  
>  
> Pattern:  
> 1) persist a **payment intent** with an idempotency key *before* any side effect,  
> 2) take a short Redis lock with fencing/version on the critical money section,  
> 3) call partner,  
> 4) durable async retries via our job system,  
> 5) **reconciliation** jobs close gaps when webhooks or responses disagree with intent state.  
>  
> I never tell an interviewer we have magic exactly-once delivery across the internet. We have **at-least-once execution with business-level idempotency and recon**.  
>  
> That’s the same vocabulary I used in the gym design: durable episode, worker lease, resettable effects, verifier as recon.”

---

# PART 5 — Coding round (detailed candidate behavior)

**Interviewer gives a problem.**

### Step-by-step what I do

1. **Listen fully.** Don’t interrupt.  
2. **Restate:**  
   > “So in my words, we need … Inputs are … Outputs are … I’ll assume … Is that right?”  
3. **Examples:** write one normal example, one edge (empty / single / duplicate).  
4. **Brute force first (verbal):**  
   > “Naive approach is … O(?). Fine for n up to …”  
5. **Optimize:**  
   > “We can do better with … because the bottleneck is …. Time … space …”  
6. **Confirm approach before coding hard.**  
7. **Code aloud:** name variables for invariants; handle empty first.  
8. **Dry run** the example on the board.  
9. **Complexity + tests.**  
10. If stuck:  
    > “I’m blocked on X. Two options — A or B. I’ll try A for thirty seconds.”

---

# PART 6 — HM / wrap (detailed)

### Why us?

> “Most companies demo agents. You’re building the apparatus — enterprise and consumer workflow data, RL gyms, experts, and ship gates tied to held-out uplift. That’s leverage. I’ve built leverage layers in payments and risk; I want that seat in the AI stack.”

### Why leave Skydo?

> “I took the foundations far — platform, team, compliance. I wanted builder intensity on AI reliability as the core product, and I left on purpose so I could choose carefully.”

### Questions I ask (natural tone)

> “When you stand up a new gym, how do you decide mock vs live sandbox vs partner shadow?”  
> “Where do you see more false signal today — flaky environments, weak verifiers, or contamination risk?”  
> “For this role, what does success look like six months in?”  
> “How do partner labs consume what you produce — datasets, gym packs, ranked diffs?”

### Closing thank-you to Pranav (end of day)

> “Thanks for hosting today at SST. Two things that stuck with me: [specific technical detail from a round] and [how the team thinks about uplift/trust]. I’d love to keep the conversation going.”

---

## Practice order (candidate)

1. Read PART 1 Steps 0–11 once silently.  
2. Stand up and perform PART 1 with a **60-minute timer**, drawing on paper — full voice.  
3. Record yourself on phone for Steps 7–9 only; listen for rambling.  
4. Run PART 2 + PART 3 once each.  
5. Stop cramming. Sleep / travel buffer > another architecture.
