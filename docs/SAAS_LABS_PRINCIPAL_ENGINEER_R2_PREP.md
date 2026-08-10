# SaaS Labs — Principal Engineer R2 Prep (Ramit Hansda)

**Interview:** R2 with Biren Goyal  
**When:** Tuesday 11 Aug 2026, 14:30–15:30 IST  
**Role:** Principal Engineer | SaaS Labs (JustCall / AI communication platform)  
**Round type (from process doc):** Stage 3 — **Technical Deep Dive**  
**Bar:** Staff → Principal calibration; engineering judgment over product familiarity

---

## 0. What this round is actually testing

They are not primarily testing whether you know JustCall’s product surface. They are testing whether you can:

1. Go **3–4 layers deep** on systems you claim ownership of
2. Show **tradeoff-driven judgment**, not feature narration
3. Separate **Staff execution** (ship hard things well) from **Principal leverage** (raise the bar, create reusable platforms, change how the org builds)
4. Reason about **production failure, compliance, multi-tenant SaaS, and AI in the real world**

### Staff vs Principal — your framing in every answer

| Staff signal | Principal signal (use this) |
|---|---|
| “I built X and it worked” | “I defined the contract, failure modes, and ownership so *others* could build on X safely” |
| Lists technologies | Explains why alternatives were rejected and what you’d change at 10× scale |
| Owns a service | Owns a **problem domain** + standards across services |
| Mentors on code reviews | Sets architecture bar, decision records, rollout gates, and incident posture |
| Optimizes one system | Creates reusable substrate (locks, job framework, agent guardrails, observability) |

**One-liner for opening:**  
> “At Skydo I operated as a founding engineer / Eng Lead owning payments + platform foundations end-to-end. What I’m optimizing for as Principal is not just shipping systems — it’s making correctness, operability, and safe automation *the default path* for the org.”

---

## 1. Company / product context (use lightly)

**SaaS Labs** builds AI-enhanced communication & productivity products:

- **JustCall** — cloud phone + SMS for sales/support; AI voice agents, coaching, conversation intelligence, CRM sync; SOC2/HIPAA/GDPR posture; ~$40M+ ARR trajectory
- **Helpwise** — shared inbox
- **ServiceAgent / AtomsAI** — AI answering / automation products

Their AI eng hiring language emphasizes: **latency, hallucinations under edge cases, barge-in, eval pipelines that catch bugs before customers do, production-grade agents**.

**Implication for you:** Your Skydo Agentic Support Copilot + payments reliability story maps *directly*. Lead with **trusted automation + distributed systems rigor**, then bridge to voice/comms.

**Interviewer note:** Biren’s public profile patterns (Java, Spring Boot, Kafka, Elasticsearch, AWS, financial services background, Principal-level systems work) overlap heavily with your stack. Expect depth on **consistency, queues, locking, idempotency, operational failure**, not buzzwords.

---

## 2. Your signature projects (own these cold)

Memorize **numbers, failure modes, and one hard decision** for each.

### A. Skydo payments & settlement platform (primary deep dive)

| Fact | Line |
|---|---|
| Scale | 10K+ international txns/day |
| Guarantees | Idempotency, reconciliation, failure recovery |
| Primitives | Distributed locking (dlock), rule-based decisioning, async workflows (Katar) |
| Role | Founding Eng / Eng Lead — architecture + delivery + reliability |

**Principal narrative:**  
Cross-border payments are an **eventually consistent multi-party state machine** (customer, Skydo ledger, banking partners, FX, compliance). The hard problem is not “call the bank API” — it is **exactly-once business effect under retries, partial failures, and concurrent settlement**.

**Hard decision ready to tell:**  
Chose **idempotency keys + outbox/async orchestration + Redis distributed locks on critical sections** over optimistic DB-only concurrency, because partner APIs are not transactional with us and duplicate side effects are catastrophic.

**Failure modes they will poke:**
- Double settlement / double payout
- Partner timeout after debit succeeded
- Reconciliation mismatch (amount, FX fee, partial settlement)
- Lock TTL expiry mid-critical-section
- Replay of webhooks / jobs

**Answer skeleton:**
1. State machine of a txn (initiated → … → settled → reconciled)
2. Idempotency at API + job + partner layers
3. Locking scope (what key? TTL? reentrancy? fencing?)
4. Reconciliation as truth-finding, not “nice-to-have reporting”
5. Observability: stuck states, aged pending, partner SLAs

### B. Agentic Support Copilot (AI differentiator — high leverage for JustCall)

| Metric | Result |
|---|---|
| Auto-triage | ~65% |
| Auto/assisted resolution | ~35–40% |
| First response | 90 min → ~10 min |
| SLA breaches | −50% |
| Architecture | Hybrid RAG + **read-only** tools + HITL + compliance guardrails |

**Principal narrative:**  
Not “we put an LLM on tickets.” Built a **trusted support automation platform** where irreversible actions stay outside the autonomous loop until trust is earned via eval + audit + rollout gates.

**Non-negotiables you must say out loud:**
- Tools are **read-only** (no autonomous refunds / KYC mutation)
- PII masking before model calls
- Append-only audit (prompt, evidence, tools, model/policy version, human action)
- Shadow mode → gated auto-send → kill switch
- Prefer over-escalation to wrong auto-resolve

**Bridge to SaaS Labs / JustCall:**  
Same control plane applies to voice agents: **thin hot path, tools after first audio / first useful response, policy outside the model, eval before expand automation, human handoff as first-class**.

### C. Katar — distributed job / workflow substrate

DB-configured SQS consumers, run_log visibility (CREATED → … → SUCCESS/ERROR), executor registration by convention. Multi-cloud async execution.

**Principal angle:** Turned “queues in every service” into a **platform with visibility and config-as-data**, so operational debugging and concurrency control become org-wide, not tribal knowledge.

### D. dlock — distributed locking library

Declarative `@DistributedLock` + RedisLockRegistry; fail-fast on acquisition failure.

**Principal angle:** Encoded a dangerous concurrency pattern into a **safe default library** with clear failure semantics — platform thinking.

### E. Goldman Sachs — market risk aggregation

- Multi-TB in-memory distributed compute; failover recovery **~−40%**
- Petabyte-scale market data; EOD batch **~−25%**
- Throughput **3×** via latency/concurrency/memory work
- Promoted to VP in ~1 year

**Use when asked:** “Tell me about the hardest scale problem” or “How do you reason about memory/sharding/replication?”

### F. Compliance as engineering (ISO 27001, SOC 2 as CIO)

Security is not a checklist bolted on — controls, access, audit, change management as **product constraints**. Maps well to JustCall’s SOC2/HIPAA/GDPR world.

---

## 3. Opening 90-second intro (practice out loud)

> “I’m Ramit — Principal-caliber engineer with 10+ years in distributed systems, mostly payments and financial platforms. Most recently at Skydo as founding engineer / Eng Lead I owned the payments and settlement architecture end-to-end — idempotency, reconciliation, distributed locking, and async workflows — at about 10K+ international transactions a day. I also built an agentic support copilot with hybrid RAG, read-only tools, and human-in-the-loop guardrails that cut first response from ~90 minutes to ~10 and halved SLA breaches. Before that at Goldman I owned market-risk aggregation systems and scaled multi-terabyte in-memory compute. I’m looking for a Principal seat where I can set the technical bar on platforms that have to be both highly automated and trustworthy — which is why SaaS Labs’ JustCall AI and communication systems are a strong fit.”

---

## 4. Likely deep-dive questions + model answers (short form)

### Payments / distributed systems

**Q: Walk me through your payments architecture.**  
Start with entities & money-movement paths → consistency model → idempotency → async jobs → reconciliation → ops dashboards. Draw boxes mentally: API, ledger, partner adapters, Katar jobs, Redis locks, Postgres, audit.

**Q: How do you guarantee exactly-once payout?**  
You don’t get distributed exactly-once for free. You design **at-least-once delivery + exactly-once business effect**:
- Idempotency key on create
- Durable intent record before side effect
- Partner correlation IDs
- Lock on payout key
- Reconciliation closes the loop for silent partner success

**Q: Distributed lock expired while you held the critical section — what happens?**  
Discuss fencing tokens / version checks, short critical sections, heartbeat extension, fail-closed on uncertain state, compensating workflows. Show you fear **split-brain writers**.

**Q: How does reconciliation work?**  
Internal ledger vs partner settlement files; matching keys; partial matches (fees/FX); exception queues; human ops tooling; immutable decision audit. Correctness > speed.

**Q: Idempotent retries across Kafka/SQS/webhooks?**  
Dedupe keys, consumer offsets vs business idempotency (don’t conflate), poison-message quarantine, ordering where required (per-entity partition / lock).

### Agentic AI (high probability given JustCall)

**Q: Why not let the agent refund?**  
Blast radius. Model confidence ≠ authorization. Separate **proposal** (LLM) from **policy** (deterministic). Earn write tools only after eval gates and narrow intents.

**Q: How did you evaluate the copilot?**  
Offline golden sets + shadow mode vs human replies (edit distance, policy fails, hallucination/evidence grounding) + online: triage %, resolution %, FRT, SLA, escalation quality, cost/ticket.

**Q: RAG failure modes?**  
Stale docs, permission leakage, chunking destroying procedures, retrieval without attribution, prompt injection via tickets. Mitigations: hybrid BM25+vector, versioned sources, ACL filters, citation required, policy check post-draft.

**Q: Design a voice agent for inbound sales (JustCall-shaped).**  
Use your voice HLD instincts:
- Thin media path; no heavy CRM/RAG on TTFA path
- Streaming STT → speculative LLM → streaming TTS
- Barge-in cancel as first-class
- Prefetch greeting on ring
- Tools after first audio
- Eval for latency **and** conversation quality
- Human warm handoff with full context

### Principal / org leverage

**Q: What’s the difference between how you worked as Eng Lead vs how you’d work as Principal here?**  
At Skydo, founding context forced both IC depth and org standards. As Principal at SaaS Labs I’d spend more intentional leverage on: cross-team architecture standards, shared platforms (workflow, agent safety, observability), hiring bar, and making the hard path the easy path — while still going deep on the riskiest systems (voice latency, money-adjacent or compliance-adjacent automation).

**Q: Tell me about a technical decision you’d reverse.**  
Pick one honest example (e.g., under-investing early in reconciliation UX, or lock TTL tuning, or model routing cost). Show learning, not self-flagellation.

**Q: How do you raise the engineering bar?**  
ADRs for irreversible decisions; production readiness checklists; incident reviews that produce platform fixes; reference architectures; mentoring via design reviews not only code comments.

### Goldman (scale credibility)

**Q: How did you get 3× throughput / faster failover?**  
Be concrete: sharding strategy, replication, memory locality, batching, avoiding lock contention, GC/pressure, failover runbooks. Tie metrics to user impact (quants needing fresh VaR).

---

## 5. Stories in SBI / STAR (keep tight)

### Story 1 — Ownership under ambiguity (payments platform from zero)
- **S:** Early Skydo needed reliable international payments without a large eng org  
- **B:** Defined architecture: ledger + partner adapters + idempotency + async orchestration + locks; owned delivery and on-call posture  
- **I:** 10K+ txns/day with strong recovery/recon guarantees; foundations reused across workflows  

### Story 2 — Safe AI automation
- **S:** Support latency / SLA pain; risk of naive LLM automation on financial customers  
- **B:** Hybrid RAG + read-only tools + HITL + PII + audit + staged rollout  
- **I:** 65% triage, 35–40% assisted resolution, FRT 90→10, SLA breaches −50%  

### Story 3 — Platform thinking (Katar / dlock)
- **S:** Every team reinventing async jobs and locking incorrectly  
- **B:** Built libraries/frameworks with visibility and safe defaults  
- **I:** Fewer production footguns; faster feature delivery; shared operational model  

### Story 4 — Scale & mentorship (GS)
- **S:** Risk compute failover and batch windows hurting workflows  
- **B:** Led sharding/replication/memory and throughput work; partnered with quants  
- **I:** Failover −40%, batch −25%, throughput 3×; promoted VP in ~1 year  

### Story 5 — Compliance as product constraint
- **S:** Needed ISO 27001 / SOC 2 for customer trust  
- **B:** As CIO drove controls into eng SDLC, access, logging, change mgmt  
- **I:** Certifications achieved; security posture became selling and operating advantage  

---

## 6. Questions to ask Biren (pick 3–4)

1. What does Principal success look like in the first 6 months on the JustCall / AI platform side — platform leverage vs product feature delivery?  
2. Where do you feel the biggest engineering risk today: voice latency, agent reliability/evals, multi-tenant isolation, CRM integration correctness, or org scaling of eng practices?  
3. How are architecture decisions made across product lines (JustCall vs Helpwise etc.) — strong central platform group or embedded principals?  
4. What Staff→Principal calibration misses do you commonly see in this loop?  
5. How productionized are AI evals today — shadow traffic, online quality gates, latency SLOs?

---

## 7. Red flags to avoid

- Feature-touring Skydo or GS without **tradeoffs**
- Claiming “exactly-once” without explaining the **business-effect** design
- Treating LLMs as reliable decision makers for money or compliance
- Saying “we used LangChain” as if that were the architecture
- Vague “I mentored the team” without a concrete bar you set
- Over-rotating into JustCall product trivia you don’t actually know

---

## 8. 24-hour drill plan (interview tomorrow)

### Tonight (90–120 min)
1. Speak the **90-sec intro** 5 times  
2. Whiteboard (paper) **payments state machine + idempotency + lock + recon** once  
3. Whiteboard **agentic copilot control flow** once (ingest → triage → RAG/tools → policy → HITL → audit)  
4. Rehearse Stories 1–3 out loud with metrics  

### Tomorrow morning (45 min)
1. Skim JustCall AI positioning (voice agent, coaching, CI) — 10 min  
2. Refresh dlock failure modes + Katar run_log states — 15 min  
3. One mock: “Design inbound AI voice agent with p95 TTFA &lt; 800ms” — 20 min  

### Last 10 min before call
- Water, notes with **metrics only** (no reading paragraphs)
- Reminder: every answer ends with **tradeoff + failure mode + what you’d do differently at JustCall scale**

---

## 9. Cheat-sheet metrics (print / keep nearby)

| Area | Number |
|---|---|
| Skydo payments | 10K+ intl txns/day |
| Copilot triage | 65% |
| Copilot resolution | 35–40% |
| FRT | 90 min → 10 min |
| SLA breaches | −50% |
| Incidents | ~−30% via observability/resilience |
| GS failover | −40% recovery time |
| GS batch | −25% EOD |
| GS throughput | 3× |
| Moneyview | 5M+ debit instructions/month; −60% manual ops |
| Career | 10+ yrs; IISc ME; GS VP in ~1 yr; Skydo founding → Eng Lead; ISO/SOC2 as CIO |

---

## 10. Linked internal deep refs (if you want more drill)

- `docs/skydo-tech/AGENTIC-SUPPORT-COPILOT.md`
- `docs/skydo-tech/dlock_Architecture.md`
- `docs/skydo-tech/Katar_Architure.md`
- `docs/REAL_TIME_VOICE_SALES_AGENT_HLD.md`
- `docs/AI_CONVERSATION_PLATFORM_PRINCIPAL_DESIGN.md`
- `docs/data_eng/PAYMENT_RECONCILIATION_HLD_STAFF_ENG.md`
- `docs/AI-AGENTIC-INTERVIEW-PREP.md`

---

## 11. Closing line if asked “Why SaaS Labs / Principal?”

> “You’re building AI into the critical path of customer conversations — voice, SMS, coaching — where latency and wrong actions both cost revenue and trust. I’ve spent years making financial systems correct under failure, and more recently making agentic automation safe enough for production support. Principal work for me is joining those: set the architecture and evaluation bar so JustCall’s AI features scale without becoming a hallucination or reliability tax on the business.”
