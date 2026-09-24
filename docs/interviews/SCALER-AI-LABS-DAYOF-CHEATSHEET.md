# Scaler AI Labs / Evaratus — Day-of Cheat Sheet
**Thu 24 Sept 2026 · 12:00–18:00 IST · TODAY**

---

## Logistics (do this first)
| | |
|---|---|
| **When** | Thu 24 Sept 2026 · **12:00 – 18:00 IST** |
| **Where** | **Scaler School of Technology** · Electronic City, Bengaluru |
| **Address** | 14, 3rd Cross, Parappana Agrahara, Electronic City Rd, Electronics City Phase 1, Bengaluru **560100** |
| **Also listed as** | Surya Park II (same campus / InterviewBit–Scaler Bang office) |
| **Host** | **Pranav TR** · pranav.tr@scalerailabs.com (coord / Founder’s Office–side; not necessarily your coding interviewer) |
| **You** | ramit.ju.cse@gmail.com |
| **Arrive** | **11:40** at gate (security + find host). Electronic City traffic — leave home by **10:30–11:00** depending on start point |
| **Bring** | Laptop + charger, notebook, water, ID, phone silent, this cheatsheet |
| **Reply** | Calendar → **Yes** if not already |

> Badge may say **Evaratus** or Scaler AI Labs — same company. Not Scalar Labs (Polaris).

---

## Bar for you (10 YOE)
Not intern/SDE-1. Expect **Staff-shaped** evaluation: design judgment, ownership depth, eval/trust instincts — DSA is a filter, not the main signal. Lead as **builder who sets bar**; leadership only as amplifier unless they open EM track.

## Suggested 12:00–18:00 rhythm
*(Exact slots unknown — follow their agenda; use this for energy.)*

| Time | Likely | You (10 YOE weight) |
|---|---|---|
| 12:00–12:20 | Pranav welcome / agenda | 90-sec open; ask rundown |
| 12:20–13:20 | Coding | Still required — clarity, invariants, tests; medium-hard OK |
| 13:20–14:00 | Lunch | Peer signal; no rant |
| 14:00–15:30 | **System design (heaviest)** | Gym/eval platform; trade-offs unprompted |
| 15:30–16:30 | **Resume deep-dive** | Copilot + payments; failure modes + metrics |
| 16:30–17:20 | LLD / “change this” / architecture poke | Interfaces, versioning, multi-tenant |
| 17:20–17:50 | HM / senior / founder | Scope owned, hiring bar, pace under ambiguity |
| 17:50–18:00 | Your Qs | Strategic (partners, uplift, 6-mo success) |

---

## 90-sec open
Payments+settlement @ Skydo (10K+/day, idempotency/recon). Agentic support copilot: RAG + read-only tools + HITL → 65% triage, 35–40% resolve, FRT 90→10, SLA −50%; model never moves money. GS VP multi-TB risk compute. CIO: ISO/SOC2. Here for the **apparatus**: RL gyms, verifiers, held-out uplift — correctness instinct applied to AI.

---

## Company (30 sec)
Evaratus = enterprise/consumer workflow data + **RL gyms** + expert network → failure analysis → task design → verification → reward model → **held-out uplift** → ship only if Δ proven. Partners with frontier labs. Research: coding, verifiable jobs, life sciences, world models.

---

## Design spine (draw this)
**Task Spec → Gym Runtime → Trajectory → Verifier stack → Eval Gate (baseline vs candidate → Δ uplift)**

Verifiers: deterministic → rubric → LLM judge → expert sample  
Must name: **reward hacking · fidelity tiers · held-out contamination · flaky envs · PII scrub**

Bridge: “Skydo copilot inverted — real tickets+HITL vs gym+verifier; product is trust.”

Fidelity: mock → recorded replay → live sandbox → prod shadow · Train cheap · certify expensive

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

Warm (45 min before leave): sliding window · topo sort · rate limit · LRU · BFS+constraint · parse/stream · intervals · retry/backoff

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
5. How Bangalore eng pairs with SF / frontier lab partners?

**To Pranav (if natural):** What does a strong full-day candidate look like here? How is the AI Labs / SST campus collab set up day-to-day?

---

## Avoid
Prompt-co · fine-tune-everything · LLM-judge = truth · exactly-once handwave · trash Scaler edtech/SST · confuse with Scalar Labs · EM-only if room is IC · late arrival

---

## Metrics card
10K+ txn/d · 65% triage · 35–40% resolve · 90→10 FRT · SLA −50% · incidents −30% · GS −40/−25/3× · Moneyview 5M+/mo · ISO/SOC2

---

## After 18:00
Same-day thank-you to **pranav.tr@scalerailabs.com** — 2 concrete technical takeaways from the day + appreciation for hosting.
