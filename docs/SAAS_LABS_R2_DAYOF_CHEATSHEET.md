# SaaS Labs R2 — Day-of Cheat Sheet (Biren Goyal · 11 Aug 2026 · 14:30 IST)

## Round
Technical Deep Dive · Staff→Principal bar · Judgment > product familiarity

## 90-sec open
Founding Eng/Lead @ Skydo: payments+settlement (10K+/day), idempotency/recon/locks/async. Agentic support copilot (RAG + read-only tools + HITL): 65% triage, 35–40% resolve, FRT 90→10, SLA −50%. GS risk compute (failover −40%, batch −25%, 3× throughput). Want Principal seat setting bar on trusted, production AI + distributed platforms → JustCall fit.

## Staff → Principal (say this shape)
Not “I built X.” → “I defined contracts, failure modes, safe defaults, and reusable substrate so the org ships correctly.”

## Deep-dive A — Payments
State machine · idempotency key · durable intent before side effect · Redis lock on critical $ path · Katar async · reconciliation closes silent partner success · never claim magic exactly-once

**Poke answers:** lock TTL → fencing/version + short CS + fail-closed · webhook replay → business dedupe · FX/fees → partial match queues

## Deep-dive B — Agentic copilot
Trusted automation platform, not “LLM on tickets.” Read-only tools · PII mask · policy outside model · audit (prompt/evidence/tools/versions/human) · shadow→gate→kill switch · over-escalate > wrong auto-resolve

**JustCall bridge:** same for voice — thin hot path, tools after first audio, barge-in, evals before expand automation, warm human handoff

## Deep-dive C — Platform leverage
Katar (visibility + config-as-data jobs) · dlock (safe locking defaults) · eng standards · ISO/SOC2 as product constraints

## Voice agent sketch (if asked)
SIP→media→VAD→stream STT→speculative LLM→stream TTS · prefetch greeting · TTFA p95 &lt;800ms · no Kafka/CRM on hot path · eval latency+quality

## 3 stories
1. Payments from zero — correctness under partner failure  
2. Safe AI automation — metrics above  
3. GS scale — memory/shard/failover with quant impact  

## Ask Biren
1. Principal 6-month success: platform vs features?  
2. Biggest risk: latency / evals / multi-tenant / eng bar?  
3. How are cross-product architecture decisions made?  
4. Common Staff→Principal misses in this loop?

## Avoid
Feature lists · “exactly-once” handwave · LangChain-as-architecture · autonomous money movement · JustCall trivia you don’t know

## Metrics card
10K+ txn/d · 65% triage · 35–40% resolve · 90→10 FRT · SLA −50% · incidents −30% · GS −40% failover / −25% batch / 3× · Moneyview 5M+/mo
