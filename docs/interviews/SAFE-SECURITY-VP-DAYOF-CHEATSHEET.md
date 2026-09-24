# SAFE × Hitesh Sethi — Day-of Cheat Sheet

**Interviewer:** Hitesh Sethi · VP Engineering · ~100 eng (BLR + SV)  
**Full prep:** `SAFE-SECURITY-VP-HITESH-SETHI-INTERVIEW.md`  
**Design bank:** `SAFE-SECURITY-SYSTEM-DESIGN-INTERVIEW.md`

## 90-sec open
EM 10+ yrs. Skydo: **12 eng**, payments/settlement **10K+/day**, idempotency/recon/locks; CIO → **ISO 27001 + SOC 2**. Goldman **VP &lt;1y**: 9 eng, multi-TB risk compute, PB-scale market data. Oracle **IDCS**. Want SAFE: ingest + identity + quantified risk + agentic loop — hands-on leadership, not negotiation layer.

## Why SAFE (3)
1. Buyer lens (CIO) — CISOs need a **defensible $ number**, not CVE wallpaper  
2. Career shape — ingest, multi-tenant trust, explainable risk (VaR ↔ FAIR)  
3. Culture match — hands-on at hard seams, ADRs, ownership

## Why leave Skydo
Team + platform + compliance done → next = larger multi-tenant security-data + AI-native loop. No boss/comp/burnout story.

## Hitesh lens
Hands-on VP (still ships code). Scores **trade-offs**, agentic **evals**, multi-tenant SaaS, bar-raising. EM JD: delivery + tech depth + AI tooling. Principal JD: Cyber AGI loop, systems at extreme scale, simplify complexity.

## Hardworking 1–10
**Say 10.** Ownership when customer/auditor/board waits. Sustainable via toil metrics + automate. Not performative hours.

## Metrics card
10K+ txn/d · incidents **−30%** · recon **~0.6% → &lt;0.02%** TPV · ISO/SOC2 · GS VP &lt;1y · team 12 / mentored 8

## System walk (default: Skydo payments)
Durable intent + idempotency key → lock+fencing → async jobs → **recon ladder** → no magic exactly-once → map to SAFE connectors/findings/CRQ/audit

**Alts:** Goldman VaR (CRQ shape) · Oracle IDCS (tenant/IAM/graph identity)

## Light design probes
| Prompt | Beats |
|---|---|
| Telemetry | dumb durable log · replayable parse · `tenant_id` key · at-least-once + idempotent upsert |
| Attack path | edges+controls · incremental recompute · shard by tenant |
| FAIR CRQ | labelled inputs · offline Monte Carlo · replayable · explainable |
| Agents | gold set · P/R/completeness/repro · prompt versioning · **HITL before irreversible** |
| Tenancy | `tenant_id` everywhere · edge authz · append-only audit |

## Leadership 30s
Bar = forums (ADR/design review), not hero mentorship · Underperform = private+receipts+30/60/90 · Hire = rubric+written feedback · Conflict = quantify risk, phase ship · 90 days = listen→state-of-eng→one structural bet + one customer win

## 3 reasons to hire
1. Still technical at scale (GS + Skydo + IDCS)  
2. Compliance buyer + multi-party integrations + audit  
3. Bar that survives you (ADRs, mentored 8, GenAI standards)

## Ask him (pick 3)
1. Where agent accuracy breaks today — data, evals, or action policy?  
2. 50→100+: what broke first — arch ownership, EM craft, or US/India latency?  
3. Under-invested seam — connectors, graph, scoring, or agents?  
4. What may agents do unsupervised — how enforced in code?  
5. Recurring SEV pattern — poison connector, noisy tenant, score drift, hallucination?

## Avoid
Pure people-manager · Kafka+LLM name-drop · exploit talk · “exactly-once ingest” · trash employers · 90h theater · Cyber AGI mysticism · skip `tenant_id`

## Bridge lines
> “Goldman taught me messy data → defensible risk number. IDCS → enterprise identity. Skydo → multi-party + audit. SAFE is those three in cyber.”

> “Demos are easy. Production agents need gold sets, Δ metrics on prompt change, and fail-closed actions.”
