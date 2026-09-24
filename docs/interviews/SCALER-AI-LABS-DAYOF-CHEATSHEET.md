# Scaler AI Labs / Evaratus — Day-of Cheat Sheet
**Full-day Bangalore onsite**

> Same company if badge says **Evaratus**. Not Scalar Labs (Polaris).

---

## 90-sec open
Payments+settlement @ Skydo (10K+/day, idempotency/recon). Agentic support copilot: RAG + read-only tools + HITL → 65% triage, 35–40% resolve, FRT 90→10, SLA −50%; model never moves money. GS VP multi-TB risk compute. CIO: ISO/SOC2. Here for the **apparatus**: RL gyms, verifiers, held-out uplift — correctness instinct applied to AI.

---

## Company (30 sec)
Evaratus = enterprise/consumer workflow data + **RL gyms** + expert network → failure analysis → task design → verification → reward model → **held-out uplift** → ship only if Δ proven. Partners with frontier labs. Research: coding, verifiable jobs, life sciences, world models.

---

## Day energy
Morning coding/clarity · Mid design (whiteboard) · Afternoon deep-dive voice · Lunch = interview · Ask 3 sharp questions · Water

---

## Design spine (draw this)
**Task Spec → Gym Runtime → Trajectory → Verifier stack → Eval Gate (baseline vs candidate → Δ uplift)**

Verifiers: deterministic → rubric → LLM judge → expert sample  
Must name: **reward hacking · fidelity tiers · held-out contamination · flaky envs · PII scrub**

Bridge: “Skydo copilot inverted — real tickets+HITL vs gym+verifier; product is trust.”

---

## Fidelity tiers
mock → recorded replay → live sandbox → prod shadow  
Train cheap · certify expensive

---

## Hard pokes → one-liners
| Poke | Answer |
|---|---|
| Reward hack | Outcome verify; randomize; adversarials; audit weird high-reward |
| LLM judge | Calibrate to experts; never sole gate on high-stakes |
| Exactly-once | Durable intent + sandbox reset; no magic distributed EO |
| Multi-app | Orchestrated sandbox net; transactional reset; clock control |
| Scale | Queue → K8s sandbox workers; obj store obs; PG meta; OLAP metrics |

---

## Coding protocol
Restate → examples → brute→better → code → tests (empty/edge/large) → complexity

Warm: sliding window · topo sort · rate limit · LRU · BFS+constraint · parse/stream · intervals · retry/backoff

---

## 4 stories
1. **Copilot trust** — tools/HITL/audit/kill switch + metrics  
2. **Payments** — idempotency, lock, recon closes silent success  
3. **GenAI bar** — standards + SOC2 lens  
4. **GS scale** −40% failover / −25% batch / 3×  

---

## HM signals
High agency · measure uplift not demos · research+prod · hire builders · compliance as constraint

**Why us:** leverage layer that makes agents competent at real work — not another chatbot.

---

## Ask (pick 3)
1. Fidelity tier decision for new gym?  
2. Biggest false signal: flake / weak verifier / contamination?  
3. 6-mo success for this seat?  
4. Partner labs consume what artifacts?  
5. Bangalore ownership vs SF/research?

---

## Avoid
Prompt-co · fine-tune-everything · LLM-judge = truth · exactly-once handwave · trash Scaler edtech · confuse with Scalar Labs · EM-only if room is IC

---

## Metrics card
10K+ txn/d · 65% triage · 35–40% resolve · 90→10 FRT · SLA −50% · incidents −30% · GS −40/−25/3× · Moneyview 5M+/mo · ISO/SOC2
