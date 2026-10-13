# Scaler AI Labs — System Design Day-of (one page)
**60 min · Staff bar · gyms/evals — not a chatbot**

---

## Open (30 sec)
“I’ll design an **eval + training apparatus**: requirements → capacity → contracts → architecture → hard problems → failures. Assume tool-use + computer-use agents on enterprise workflows unless you narrow it.”

## 3 Qs
1. API tools vs computer-use vs both?  
2. Binary pass vs partial credit vs preference/RL rewards?  
3. Internal only vs multi-tenant frontier-lab partners?

**Defaults:** both · outcome + partial · multi-tenant + held-out isolation

---

## FR / NFR (board)
FR: versioned gyms/tasks · episodes reset/step · trajectories · layered score · baseline vs candidate uplift gate  
NFR: step p95 &lt;2–3s (API) · 10K–100K ep/day · zero contamination · tenant isolation · no open egress · append-only reproducible · cheap train / expensive certify

---

## Draw this spine
**Task Registry → Agent Harness → Gym Runtime (sandbox) → Trajectory Store → Verifier Stack → Eval Orchestrator (ship/reject)**

Episode path: durable CreateEpisode → Reset → Step loop → Verdict → suite metrics

---

## Verifiers
L0 deterministic → L1 rubric → L2 LLM judge (calibrated) → L3 expert sample  
High-stakes: L0 when possible; **LLM never sole gate**

## Fidelity tiers
T0 mock → T1 replay → T2 live sandbox → T3 partner shadow  
Train cheap · **certify** on T2/T3 held-out

## Must name
Reward hacking · contamination/ACLs · flake vs fail · multi-app transactional reset · sandbox pool bottleneck · idempotent episode + worker lease (no magic EO)

---

## Napkin
50K ep/day × 20 steps → ~1M steps/day; burst 10×  
Storage ~200GB/day raw artifacts → diff/compress/tier  
**Bottleneck = sandbox concurrency**, not REST QPS

---

## APIs
`reset` · `step` · `verdicts` · `evals` (baseline vs candidate)

## Entities
GymVersion · TaskVersion(held_out) · AgentVersion · Episode · Step · Verdict · EvalRun

---

## Deep dive picks (do 2)
1. Verifier quality / IRR / false positives  
2. Fidelity vs cost  
3. Reward hacking  
4. Multi-tenant isolation  
5. Computer-use obs compression + safety  

---

## Close (45 sec)
“Chose layered verification + fidelity tiers over one smart judge / one mega-env — cost controllable, trust measurable. Deepen next: multi-app clocks + partner eval SLAs. Same as Skydo copilot inverted: HITL on tickets vs verifiers on gyms — product is **trust before autonomy**.”

---

## Pivot lines
| If they ask… | Spine |
|---|---|
| Computer-use | Session broker → warm VM pool → obs pipeline → action exec → verifier |
| Uplift only | Stratified suite → version pins → CIs → ship/reject/investigate |
| Data pipeline | Ingest → PII scrub → workflow extract → expert QA → task synth → gym pack |
| Multi-tenant | Primary + KMS/ACL/quotas/dedicated pools |

## Avoid
Chatbot design · LLM-judge=truth · no held-out · Kafka on step hot path · exactly-once handwave · vanity accuracy
