# SaaS Labs R2 — Spoken Scripts (say these out loud)

**How to use:** Practice each script once out loud. In the interview, don’t recite word-for-word — hit the same beats. If they interrupt, finish the current beat, then follow their poke.

**Default answer shape (always):**
1. One-sentence context  
2. What *you* owned  
3. Hard part / tradeoff  
4. How it worked in production  
5. Result + what you’d do differently / how it maps here  

---

## 1. “Tell me about yourself” / “Walk me through your background”

**SAY THIS:**

> Sure. I’m Ramit — I’ve spent a little over ten years building distributed systems, mostly in payments and financial platforms.
>
> Most recently I was a founding engineer and then Engineering Lead at Skydo. I owned the payments and settlement platform end to end — architecture, delivery, and production reliability. We were processing about ten thousand plus international transactions a day, with strong guarantees around idempotency, reconciliation, and failure recovery.
>
> I also built an agentic support copilot there — hybrid RAG, read-only tool calling, human-in-the-loop, and compliance guardrails. That got us to roughly sixty-five percent auto-triage, thirty-five to forty percent assisted resolution, and first response time from about ninety minutes down to around ten.
>
> Before Skydo I was at Goldman Sachs as a VP, owning market-risk aggregation systems — multi-terabyte in-memory compute, petabyte-scale market data. We cut failover recovery by about forty percent and improved throughput about three-x.
>
> I’m looking for a Principal role where I can set the technical bar on systems that have to be both highly automated and trustworthy — which is why JustCall and SaaS Labs are interesting to me.

**If they ask for shorter (30 sec):**

> I’m a founding-engineer-turned-Eng-Lead with deep payments and distributed-systems background — Skydo payments at ten-K-plus transactions a day, plus a production agentic support system with strong guardrails. Before that, Goldman market-risk platforms. I want a Principal seat raising the bar on trusted, scalable automation.

---

## 2. “Why SaaS Labs / Why Principal here?”

**SAY THIS:**

> Two reasons.
>
> First, the problem shape. JustCall puts AI into the critical path of customer conversations — voice, SMS, coaching. Latency and wrong actions both hurt. That’s the same class of problem I’ve been solving: correctness under failure in payments, and safe automation in support.
>
> Second, the level. I’m not looking to just own one service. As Principal I want to set architecture standards, shared platforms, and evaluation gates so the org ships AI and communication features without creating a reliability or trust tax.
>
> So the fit for me is: production systems judgment from fintech, plus recent agentic work, applied to a high-growth communication SaaS.

---

## 3. “What’s the difference between Staff and Principal?” / “How do you operate as Principal?”

**SAY THIS:**

> For me, Staff is: you take a hard problem and ship a great system.
>
> Principal is: you make the *right path the easy path* for the whole org. You define contracts, failure modes, and reusable substrates — locking, workflows, agent safety, observability — so other teams don’t reinvent dangerous patterns.
>
> At Skydo, because I was founding, I did both. In a Principal seat here, I’d be more intentional about leverage: architecture reviews, decision records for irreversible choices, rollout gates for AI, and mentoring through design quality — while still going deep on the riskiest systems myself.

---

## 4. “Tell me about a system you designed” / “Walk me through your biggest project”

### Option A — Payments (default; use this first)

**SAY THIS:**

> The core system I’d pick is Skydo’s payments and settlement platform.
>
> Context: we were a cross-border payments company. I joined as a founding engineer and owned system architecture from the ground up — not just one microservice, the money-movement path.
>
> Mentally, think of it as a multi-party state machine: customer, our ledger, banking partners, FX, and compliance. Partners are not in our database transaction. So the hard problem is not “call the bank API” — it’s getting exactly-once *business effect* under retries, timeouts, and concurrency.
>
> How we designed it:
> One — every money-moving intent is durable first. We persist the intent with an idempotency key before we do a side effect.
> Two — critical sections like settle-or-payout are protected with distributed locks, keyed by the business entity — invoice, payout, transaction — so two workers can’t double-pay.
> Three — partner calls and long workflows run through an async job framework we built, so retries are visible and operable, not fire-and-forget.
> Four — reconciliation is first-class. Partner settlement files are the external truth we match against our ledger, and exceptions go to an ops queue — we don’t pretend the happy path covers everything.
>
> Result: we ran about ten thousand plus international transactions a day with strong recovery guarantees, and we cut a lot of manual firefighting as the system matured.
>
> The Principal lesson: in money systems, you design for partial failure first. Happy path is the easy part.

### Option B — Agentic copilot (if they steer to AI)

**SAY THIS:**

> I’d walk through the agentic support copilot we built at Skydo.
>
> The business problem was support latency — first response was around ninety minutes, and SLA breaches were painful. The naive solution is “put an LLM on the ticket queue.” That would have been dangerous for a financial product.
>
> So I framed it as a trusted support automation platform, not a chatbot.
>
> Architecture in one pass:
> Ticket comes in → we mask PII → cheap intent classification for triage → for eligible intents, an orchestrator does hybrid RAG over policy and FAQ, plus *read-only* tools like account status, transaction details, KYC status → a policy layer checks the draft → then human-in-the-loop for anything sensitive or low-confidence → everything audited: prompt, evidence, tools, model version, policy version, human action.
>
> Non-negotiable: the agent could not refund, mutate KYC, or move money. Tools were read-only by design. Auto-send only after shadow mode and quality gates. Kill switch always available.
>
> Outcomes: about sixty-five percent auto-triage, thirty-five to forty percent assisted or auto resolution, first response roughly ninety minutes to ten, SLA breaches down about fifty percent.
>
> That’s the same control plane I’d bring to voice agents at JustCall — model proposes, policy decides, humans stay accountable.

---

## 5. “How do you handle idempotency / exactly-once?”

**SAY THIS:**

> I don’t claim distributed exactly-once as a free property. Networks and partners give you at-least-once. What we design for is exactly-once *business effect*.
>
> Practically:
> Client or upstream sends an idempotency key.
> We create a durable intent row first — unique on that key.
> Retries hit the same intent and return the same result; they don’t create a second payout.
> For async jobs and webhooks, we dedupe on a business correlation ID, not only on queue message ID — because the same business event can arrive from multiple channels.
> And reconciliation is the backstop for the case where the partner succeeded but we timed out and aren’t sure.
>
> So: at-least-once delivery, idempotent handlers, durable intent, then recon to close uncertainty.

---

## 6. “How do distributed locks work in your system?” / “What if the lock expires?”

**SAY THIS:**

> We built a small library, dlock — declarative distributed locks on Redis for critical financial sections.
>
> Example: settle this invoice. The lock key is the invoice ID. Worker A acquires the lock, does the side effect, updates state, releases. Worker B fails fast if it can’t acquire — we don’t silently skip.
>
> The scary case is lock TTL expiry while you’re still in the critical section — now two workers can think they own it.
>
> How we reason about it:
> Keep the critical section short — no long partner waits inside the lock if we can avoid it.
> Persist state transitions with versions / conditional updates, so even if a second worker enters, it can’t overwrite a completed payout blindly.
> On uncertainty — timeout after we may have succeeded — we go to a verify-with-partner or reconciliation path, not “retry payout immediately.”
> Fail closed on money movement when state is unknown.
>
> Locks reduce concurrency bugs; they don’t replace idempotency and state checks.

---

## 7. “How does reconciliation work?”

**SAY THIS:**

> Reconciliation is how we find truth when our system and the partner disagree.
>
> Flow: we have an internal ledger of what we think happened. Partners send settlement files or statements — often T+1. We ingest those, normalize them, and match against internal records using correlation IDs, amounts, currencies, and value dates.
>
> Matches close the loop. Mismatches — amount off because of fees or FX, missing on one side, duplicates — go to an exception queue for ops, with an audit trail of every decision.
>
> Design stance: reconciliation is not a reporting nice-to-have. It’s part of correctness. In cross-border payments, silent drift is how you lose money and trust.

---

## 8. “Walk me through a production incident” / “Tell me about a failure”

**SAY THIS (template — fill with your real incident if you have a sharper one):**

> One class of incident we took seriously was uncertain partner outcomes — we timed out calling a banking partner, the client retried, and without good discipline you can double-initiate.
>
> Situation: elevated timeouts on a partner; retries spiked; we saw risk of duplicate side effects.
>
> What I did: froze blind retries for that money path, forced verify-status against partner for in-flight intents, tightened idempotency checks on the payout key, and added alerting on “pending beyond SLA” ages so humans saw stuck money early.
>
> Impact: we avoided duplicate payouts, cleared the backlog safely, then made the verify-on-timeout path a standard pattern — not a one-off hotfix.
>
> Lesson I carry: in distributed money movement, timeout is not failure — it’s *unknown*. Unknown needs a different code path than failure.

**If they want AI incident instead:**

> Early in the copilot rollout, the risk wasn’t latency — it was confident wrong answers on policy-ish tickets.
>
> We caught it in shadow mode: drafts looked fluent but weren’t grounded in retrieved evidence. We blocked auto-send for that intent class, required citations, tightened retrieval, and kept humans in the loop until edit-distance and policy-fail rates were acceptable.
>
> That’s why I insist on shadow traffic and eval gates before expanding automation.

---

## 9. “How did you build the AI / agent system?” / “Why RAG + tools?”

**SAY THIS:**

> Pure RAG is good for “what’s our FX cutoff?” style questions. It fails when the answer needs live account state.
>
> Pure tool-calling without retrieval hallucinates policy.
>
> So we used hybrid: retrieve policy and FAQ with BM25 plus vectors, and call read-only tools for live state. The orchestrator loops — think, retrieve or call tool, observe, draft.
>
> Then a deterministic policy layer decides if that draft can go out. The LLM proposes; it does not authorize.
>
> That separation is the whole game for production agents.

---

## 10. “Why read-only tools? When would you allow writes?”

**SAY THIS:**

> Because in a fintech support context, a wrong write is worse than a slow human.
>
> Read-only got us most of the value — status, transaction lookup, KYC state — without autonomous money or compliance mutations.
>
> I would allow writes only with: narrow intent allowlist, strong authz, human approval or dual control for irreversible actions, full audit, and eval proof that the agent chooses the right tool under adversarial tickets.
>
> Earn write access. Don’t start with it.

---

## 11. “How do you evaluate an AI system in production?”

**SAY THIS:**

> Three layers.
>
> Offline: golden ticket sets — did we retrieve the right docs, is the answer grounded, did we violate policy, tool choice correct.
>
> Shadow: agent drafts while humans still send; measure edit distance, escalation correctness, hallucination-ish failures.
>
> Online: triage rate, resolution rate, first response time, SLA, customer reopen rate, cost per ticket, and kill-switch drills.
>
> If you only measure “automation percent,” you’ll ship confident wrong answers. Quality and grounding matter as much as deflection.

---

## 12. “Design a voice AI agent for JustCall” / “How would you approach our AI voice agent?”

**SAY THIS:**

> I’d treat it as a real-time systems problem first, model problem second.
>
> Hard constraint: after the caller stops talking, time-to-first-audio should feel instant — I’d target p95 under about eight hundred milliseconds. Callers forgive a longer answer; they don’t forgive dead air.
>
> Architecture sketch:
> Telephony comes in over SIP. Media plane stays thin and co-located. Streaming turn detection into streaming STT, into a dialog orchestrator that can start the LLM on high-confidence partials, into streaming TTS back out.
> Prefetch the greeting while the phone is ringing so answer feels immediate.
> Barge-in is first-class — if the user speaks, cancel TTS fast.
> Keep CRM enrichment and heavy RAG off the first-audio path; run tools after the caller hears something useful.
> Human handoff must carry transcript and structured context.
>
> Control plane from my support agent work still applies: policy outside the model, evals for latency and conversation quality, staged rollout, kill switch.
>
> Cascaded STT-LLM-TTS can hit the first latency bar; speech-to-speech is how you push lower later — but only after you can measure turn-taking and quality.

---

## 13. “How do you scale async jobs / queues?” (Katar)

**SAY THIS:**

> At Skydo we had many workflows — onboarding, settlement steps, notifications — and if every service invents its own SQS consumer, you get invisible failures.
>
> So we built Katar: a queue abstraction on SQS with DB-backed config and a run log for every message — created, pending, in progress, success, error.
>
> Handlers register by convention. Ops can see what’s stuck. Concurrency and subscriptions are data, not only code.
>
> Principal angle: the transport is SQS; the product is *operability*. At JustCall scale, the same idea applies to webhooks, CRM sync, post-call AI pipelines — anything async that must not silently die.

---

## 14. “Tell me about Goldman / hardest scale problem”

**SAY THIS:**

> At Goldman I owned architecture and delivery for distributed market-risk aggregation — pricing, VaR, PnL, stress workflows.
>
> The hard part was multi-terabyte in-memory distributed compute under failover and heavy market-data ingest. When failover was slow, quants didn’t trust numbers at market open — that’s a business risk, not just an infra metric.
>
> We improved sharding, replication, and fault tolerance — failover recovery roughly forty percent faster. We also optimized petabyte-scale ingest and cut end-of-day batch about twenty-five percent, and got about three-x throughput through latency, concurrency, and memory work.
>
> What I took from that: measure what the user of the system actually feels — freshness, recovery, batch windows — then work backward into memory and distribution mechanics.

---

## 15. “How do you mentor / raise the bar?”

**SAY THIS:**

> Mentoring for me is not only pairing on code.
>
> I set the bar through design reviews: what’s the failure mode, what’s the idempotency story, what’s the rollback. I write decision records for irreversible choices so the team doesn’t re-litigate tribal knowledge. And I build libraries and templates — like locking and job frameworks — so the safe pattern is the default.
>
> With juniors I go deep on distributed systems fundamentals. With seniors I challenge tradeoffs and ownership boundaries.
>
> Impact I look for: fewer repeated production classes of bugs, and engineers who can explain *why*, not only *what*.

---

## 16. “Tell me about a technical disagreement” / “A decision you’d reverse”

**Disagreement — SAY THIS:**

> We had a disagreement on how far to automate support replies early.
>
> Product pressure was: auto-send more, faster ROI. My position was: shadow mode until grounding and policy metrics clear the bar, because one bad financial answer costs more than a week of slower automation.
>
> I didn’t block the goal — I proposed a staged expand by intent class, with kill switch and human review on sensitive intents. We aligned on metrics that meant “ready,” not vibes.
>
> Result: we still hit large latency gains, without giving the model write-like authority it hadn’t earned.

**Reverse — SAY THIS:**

> One thing I’d invest in earlier is reconciliation and ops tooling UX — not only the matching engine.
>
> We made the core correctness path solid, but operators still felt friction on partial matches. If I did it again, I’d ship exception workflows and explainability sooner, because correctness in payments includes *humans being able to resolve edge cases quickly*.

---

## 17. “How do you handle compliance / security?” (ISO / SOC2)

**SAY THIS:**

> At Skydo I drove ISO 27001 and SOC 2 Type II as CIO — but I treated it as engineering design, not a paperwork exercise.
>
> Access control, audit logging, change management, encryption, and least privilege have to show up in the architecture. Same with the copilot: PII masking before model calls, append-only audit, no autonomous sensitive actions.
>
> For JustCall — SOC2, HIPAA, GDPR — I’d assume conversation content and CRM data are sensitive by default. Tenant isolation, retention, and access paths are part of the product contract.

---

## 18. “What would you do in the first 90 days as Principal?”

**SAY THIS:**

> First thirty days: listen and map. Where are the reliability and AI quality fires? What’s the current architecture for voice, messaging, CRM sync, and agent pipelines? Where do teams disagree on standards?
>
> Days thirty to sixty: pick one or two high-leverage platform bets — for example agent eval + rollout gates, or shared async/observability patterns on post-call pipelines — and deliver something real with a partner team, not a slide deck.
>
> Days sixty to ninety: codify the bar — production readiness expectations, design review norms, and a clear ownership map for the riskiest paths: real-time voice and anything that can take an incorrect customer-facing action.
>
> Success looks like: one painful class of failure gets structurally harder to repeat, and teams feel the Principal made shipping *safer and faster*, not slower.

---

## 19. When you don’t know / need to clarify

**SAY THIS:**

> I want to make sure I answer the right problem. Can I confirm constraints — scale, latency target, consistency needs, and whether this is multi-tenant SaaS?
>
> Here’s how I’d approach it with assumptions, and I’ll flag where I’d validate with the team…

**If stuck technically:**

> I don’t want to hand-wave. What I know is X. What I’d verify next is Y — logs, partner status API, partition lag — and until then I’d fail closed on money or customer-facing automation.

---

## 20. Closing / “Any questions for me?”

**Pick 3:**

> 1. What does Principal success look like in the first six months here — more platform leverage, or deep ownership inside one JustCall AI surface?
>
> 2. Where do you see the biggest engineering risk today — voice latency, agent eval quality, multi-tenant isolation, or scaling eng practices across products?
>
> 3. How are cross-team architecture decisions made? Is there a strong platform group, or are Principals embedded and aligning through reviews?
>
> 4. In this loop, what Staff-to-Principal misses do you see most often?

**Closing line if natural:**

> I’ve enjoyed this. The problems you’re solving — real-time AI on customer conversations with trust and reliability — are exactly where I want to operate as a Principal.

---

## Quick recovery phrases (use when nervous)

- “Let me structure that in two parts — design, then failure modes.”
- “The tradeoff we chose was X because Y; the cost was Z.”
- “What I’d do differently at JustCall’s scale is…”
- “The metric that told us it worked was…”
- “Unknown is not the same as failed — here’s the path for unknown…”

---

## 15-minute rehearsal order (morning of)

1. Section 1 — intro (2 min)  
2. Section 4A — payments walkthrough (4 min)  
3. Section 5 + 6 — idempotency + locks (3 min)  
4. Section 4B or 9 — AI copilot (3 min)  
5. Section 12 — voice agent sketch (3 min)  
