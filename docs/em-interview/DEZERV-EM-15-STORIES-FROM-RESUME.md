# Dezerv EM — 15 Must-Have Stories (from Ramit’s resume)

**How to use:** Each story is ~90–120 sec. Hit **S → T → A → R → Lesson**. End with a **number**. Map below shows which round to lead with.

| # | Story | Best for |
|---|---|---|
| 1 | Payments & settlement platform | Tech R1, HM |
| 2 | Reliability / incidents −30% | Tech R1, HM |
| 3 | Reconciliation ambiguity ladder | Tech R1, Stakeholder |
| 4 | Automated onboarding (hours → minutes) | Tech R1, Stakeholder, HM |
| 5 | Kill / phase roadmap (compliance vs speed) | Stakeholder, HM |
| 6 | ISO 27001 + SOC 2 as CIO | HM, Stakeholder |
| 7 | Mentoring 8 / raising design bar | People R2 |
| 8 | Hard feedback (corrosive senior IC) | People R2 |
| 9 | Hiring bar & team of 12 | People R2, HM |
| 10 | GenAI adoption standards | People R2, HM |
| 11 | Goldman risk platform + VP promotion | Tech R1, HM |
| 12 | Cross-functional with quants / global teams | Stakeholder, People R2 |
| 13 | MoneyView: millions of debit instructions | Tech R1, HM |
| 14 | Oracle IDCS + Excellence Award | Tech R1, People R2 |
| 15 | Failure you owned (partner webhook / estimate) | All rounds (must ask) |

**Metrics card (memorize):** team 12 · mentored 8 · 10K+ txn/day · incidents −30% · recon 0.6% → &lt;0.02% TPV · onboarding hours → minutes · ISO 27001 + SOC 2 · GS VP in &lt;1 year · team of 9 at GS

---

## 1. Payments & settlement platform (deep technical ownership)

**Resume hook:** *Designed and scaled core payments & settlement — 10K+/day — idempotency, reconciliation, failure recovery.*

**S:** At Skydo I owned the international payments and settlement platform. Money moves across customer, our ledger, banking partners, FX — partners are outside our DB transaction. Wrong state = customer money + regulatory risk.

**T:** Build a system that is correct under retries, timeouts, and concurrency — not just “call the bank API.”

**A:**
1. Durable intent + idempotency key *before* any side effect  
2. Distributed locks + fencing on settle/payout critical sections  
3. Async job system for partner calls (visible retries, not fire-and-forget)  
4. Reconciliation as first-class (intent → debit → FX → remittance ladder)

**R:** 10K+ international txns/day; recon gaps ~0.6% → &lt;0.02% of daily TPV; incidents ~−30%.

**Lesson:** In money systems, design for partial failure first. Happy path is easy.

**Dezerv bridge:** Same muscle as portfolio correctness, trade fills, and audit trails — silent wrongness kills trust.

---

## 2. Reliability program (observability + resilience)

**Resume hook:** *Drove observability, fault tolerance, resilience — production incidents ~−30%.*

**S:** Recurring settlement pain — concurrency races + flaky banking/FX partners. Failures weren’t just tickets; they were money and trust.

**T:** Cut recurring incidents systemically, not one bug at a time.

**A:** Three tracks in parallel — (1) correctness patterns on hot path (idempotency, locks, SLA-aware retries), (2) observability tied to *business* KPIs (lag, exception queues, unreconciled amount — not only CPU), (3) recon ladder so silent mismatches couldn’t hide.

**R:** ~30% fewer production incidents; on-call load dropped; recon gaps collapsed.

**Lesson:** Observability before clever optimization — you can’t fix what you can’t see.

**Dezerv bridge:** Wealth products fail on silent wrong NAV/holdings more than on downtime.

---

## 3. Resolving ambiguity (settlement vocabulary)

**Resume hook:** *Reconciliation + cross-functional financial operations.*

**S:** Finance said “rupees missing for customer Y.” Eng, Ops, Finance, and the bank each meant something different by “settled.” Slack fog for days.

**T:** Turn ambiguity into a decidable problem with shared language and tooling.

**A:**
1. One-page problem statement (observe / don’t know / done looks like)  
2. Canonical state vocabulary: initiated → debited → converted → remitted → settled / failed / reversed  
3. Three-layer recon ladder instead of one opaque job

**R:** Unreconciled ~0.6% → &lt;0.02% TPV; same ladder became template for the next banking partner.

**Lesson:** Output of resolving ambiguity isn’t only the fix — it’s shared vocabulary so the next fog doesn’t land on Slack.

**Dezerv bridge:** Perfect for Sharad (stakeholders) and Utkarsh (systems) — Eng ↔ Ops ↔ Finance ↔ Product.

---

## 4. Automated onboarding (hours → minutes)

**Resume hook:** *Fully automated onboarding for Sole Prop, LLP, Pvt Ltd, Partnerships, SMEs — hours to minutes.*

**S:** Manual, multi-hour onboarding across entity types blocked growth and burned ops.

**T:** Automate end-to-end without breaking KYC/compliance.

**A:** Workflow orchestration + rule-based decisioning per entity type; integrations for KYC/docs; clear state machine; exception queues for edge cases instead of blocking the happy path.

**R:** Onboarding time hours → minutes across 5+ entity types; ops load down; conversion improved.

**Lesson:** Automate the 80% path hard; design explicit human-in-the-loop for the long tail — don’t fake “fully auto” by skipping controls.

**Dezerv bridge:** Maps directly to PMS/KYC onboarding, RM handoff, and funnel conversion.

---

## 5. Kill / phase a roadmap item (Product vs Compliance)

**Resume hook:** *Onboarding systems + compliance leadership + stakeholder management.*

**S:** Product pushed fast ship of a new business-entity onboarding path. Eng flagged KYC/compliance gaps. Ship raw → audit/trust risk. Block entirely → slow growth.

**T:** Don’t pick Product vs Eng. Quantify and decide what ships now vs later.

**A:** One decision memo: regulatory/trust risk vs time-to-revenue. Proposed feature-flagged beta for whitelisted cohort → full rollout only after audit-trail review. Aligned Product + Compliance in one meeting.

**R:** Beta ~2 weeks, full ~5 weeks, **zero** compliance findings next audit.

**Lesson:** EM job is making the tradeoff explicit — what ships, what waits, what gets killed — not tribal loyalty.

**Dezerv bridge:** Gold for Sharad + Krishna. Wealthtech has the same compliance-vs-speed tension.

---

## 6. ISO 27001 + SOC 2 as CIO (org / compliance leadership)

**Resume hook:** *CIO — led ISO 27001 and SOC 2 Type II; org-wide security and DLP.*

**S:** Enterprise deals blocked without certifications. Security was tribal, not systematic.

**T:** Land both certs and shift culture — not a binder for auditors.

**A:** Gap analysis; 40+ controls; DLP; access reviews; vendor risk; IR playbooks; evidence pipeline. Drove Eng + HR + Legal + Finance.

**R:** Both certifications landed; sales pipeline unblocked; security became ongoing practice.

**Lesson:** Compliance done well is a *platform* bet — reusable controls, not theatre.

**Dezerv bridge:** HNI PII, portfolio data, SEBI-adjacent trust — Krishna/Sharad love this story.

---

## 7. Mentoring 8 / raising the engineering bar

**Resume hook:** *Mentored 8 engineers in distributed systems & architecture; defined best practices across teams.*

**S:** Team of 12 with uneven architectural judgment. Money paths shipping with inconsistent design rigor.

**T:** Level people up without becoming the bottleneck or slowing delivery.

**A:** Design-doc-first for anything touching payments; ADRs for irreversible choices; paired senior↔mid reviews; 1:1 growth plans tied to *skills*, not vague “be more senior.”

**R:** Mentored 8 directly; promotions from that pipeline; design-review coverage on major changes ~30% → ~100%.

**Lesson:** Forums beat hero mentoring — make good judgment the default path.

**Dezerv bridge:** Tauseef round — culture of ownership + technical rigor in a flat org.

---

## 8. Hard conversation / behavior coaching

**Resume hook:** *Team leadership, culture, growing engineers.*

**S:** Strong IC — technically excellent — routinely dismissive in design/PR reviews. Eroding psychological safety.

**T:** Keep the talent, fix the behavior, protect the team.

**A:** Private conversation with *receipts* (dates, threads, comments) framed as impact, not personality. 30/60/90 behavior goals. Shadowed next two design reviews; real-time feedback after.

**R:** Behavior shifted in ~6 weeks; engineer owned it in a 1:1; no attrition; team safety recovered.

**Lesson:** Feedback without specific examples feels like a character attack. Always bring receipts.

**If they ask pure underperformance:** same arc — written expectations → coaching → PIP only if coaching fails; document from day one.

---

## 9. Hiring & scaling the team of 12

**Resume hook:** *Led eng across 12 — hiring, team structure, delivery, culture.*

**S:** Needed to scale Skydo eng (and earlier ran Goldman loops) without diluting bar.

**T:** Hire for the team 12 months out — not only today’s ticket queue — with consistent senior signal.

**A:** Rubric: problem solving, systems design, collaboration, ownership. Written feedback *before* debrief (kill groupthink). Same-day debrief while signal is fresh. Structured onboarding + ownership of a domain early.

**R:** Team to 12 with strong ownership culture; no regret hires from recent loops I owned; delivery stayed predictable while growing.

**Lesson:** Process makes hiring fair *and* fast — bar is real only if the loop enforces it.

**Dezerv bridge:** JD wants someone who grows seniors and holds bar without being asked.

---

## 10. GenAI adoption across engineering

**Resume hook:** *Led GenAI adoption — standards for Claude, Cursor, Windsurf — speed + quality.*

**S:** AI coding tools arriving ad hoc — risk of IP/PII leakage and inconsistent quality.

**T:** Adopt AI as a force multiplier with guardrails, not vibes.

**A:** Defined what can go in prompts; IP/PII rules; PR norms when AI wrote the first draft; measured whether velocity *and* quality moved — not vanity license counts.

**R:** Faster delivery on boilerplate/tests; clearer review norms; fewer “AI mystery PRs.”

**Lesson:** Professionalize AI — accelerate where safe; keep human gates on financial correctness.

**Dezerv bridge:** Differentiator — you won’t fight AI culture; you’ll make it safe for wealthtech.

---

## 11. Goldman Sachs — market risk platform + VP in &lt;1 year

**Resume hook:** *Led 9 eng on distributed market risk (pricing, VaR, stress); multi-TB in-memory clusters; promoted VP in 1 year.*

**S:** Global risk workflows needed reliable aggregation across huge market-data surfaces. Latency and correctness both mattered.

**T:** Own delivery + architecture for distributed risk compute; earn trust of quants and global eng.

**A:** Improved sharding, replication, fault tolerance on multi-TB in-memory clusters; optimized ingestion/processing of petabyte-scale market data; partnered tightly with quants on modernization.

**R:** Platform supported pricing/VaR/stress workflows at global scale; promoted to **VP within 1 year**.

**Lesson:** In risk/finance systems, clarity of data contracts and failure modes beats local optimization.

**Dezerv bridge:** Shows you can lead seniors on data-intensive, correctness-critical systems — Wealth Monitor / portfolio analytics kinship.

---

## 12. Cross-functional collaboration (quants / global teams)

**Resume hook:** *Drove collaboration with quants and global engineering on risk platform modernization.*

**S:** Risk platform changes needed buy-in from quants (model correctness) and eng teams across regions (ops/ownership). Easy to ship a “tech win” that broke a risk workflow.

**T:** Align technical modernization with risk-domain correctness and multi-team ownership.

**A:** Joint design reviews with quants; explicit contracts on inputs/outputs and SLAs; phased rollout; written ADRs for irreversible compute changes; over-communicate cutovers.

**R:** Modernization landed without breaking global risk workflows; stronger trust from quant partners; leadership visibility that fed VP promotion.

**Lesson:** In specialist domains, Eng leads by translating — not by overruling domain experts.

**Dezerv bridge:** Sharad round — Product / Investments / Eng alignment is the wealth analog of Eng / Quants.

---

## 13. MoneyView — demand generation & reconciliation at millions/day

**Resume hook:** *Payment demand generation + reconciliation — millions of debit instructions daily; manual ops −60%.*

**S:** High-throughput debit pipelines with gateway flakiness and heavy manual ops on failures.

**T:** Scale throughput while cutting human firefighting.

**A:** Idempotent retries; SLA-based scheduling; fault-tolerant workflows; multi-gateway integration with resilient pipelines.

**R:** Millions of debit instructions/day; **manual operations −60%**.

**Lesson:** At volume, operability *is* the product — retries and SLAs must be designed, not bolted on.

**Dezerv bridge:** Short proof you owned money pipelines before Skydo — depth across fintech career, not one company.

---

## 14. Oracle IDCS — identity platform + Excellence Award + promotion

**Resume hook:** *Lead developer IDCS; SIM patch automation; Excellence Award; Senior MTS in 2 years.*

**S:** Enterprise identity for cloud customers — HA + compliance non-negotiable. Patching Cloud @ Customer caused painful downtime.

**T:** Deliver distributed identity capabilities and cut patch downtime.

**A:** Led features on Oracle Identity Cloud Service; designed SIM patch automation for Oracle Cloud @ Customer; drove PoCs in automation/identity security into product roadmap; collaborated with global teams.

**R:** Excellence Award; promoted to Senior MTS in 2 years; PoCs adopted into roadmap; lower downtime on patch cycles.

**Lesson:** Early career proof of ownership — you don’t wait for a manager title to lead technical outcomes.

**Dezerv bridge:** Identity/security instincts transfer to client/RM access, audit, and PII in wealth apps.

---

## 15. Failure you owned (must-have — never skip)

**Resume hook:** *Payments partner integrations / failure recovery.*  
*(Customize if you have a more accurate personal miss — keep the structure.)*

**S:** Early partner integration — I underestimated webhook retry / edge ID-format failure modes. Shipped with happy-path tests and light chaos testing.

**T:** Own the miss, contain blast radius, fix the system so it can’t recur.

**A:** Caught via reconciliation before customer impact ballooned; froze risky path; fixed dedupe for edge ID formats; then **systemic** changes — partner contract tests (replay + malformed IDs), idempotency checklist on money PRs, staging suite that replays production-shaped webhook storms.

**R:** No lasting customer money loss; ops firefighting ended; checklist prevented class of bugs on later partners.

**Lesson:** In integrations, the partner’s failure mode *is* your design input. Optimistic third-party assumptions are how EMs create incidents.

**Dezerv bridge:** Shows humility + systems thinking — interviewers trust this more than a perfect resume.

---

## Round cheat — which 3 to warm first

| Round | Lead with | Backup |
|---|---|---|
| **Utkarsh (Tech)** | 1 Payments deep dive → 2 Reliability → 3 Ambiguity | 11 Goldman, 13 MoneyView, 15 Failure |
| **Tauseef (People)** | 7 Mentoring → 8 Hard feedback → 9 Hiring | 10 AI, 12 Cross-functional, 15 Failure |
| **Sharad (Stakeholders)** | 5 Kill/phase → 4 Onboarding → 3 Ambiguity | 6 Compliance, 12 Quants, 10 AI |
| **Krishna (HM)** | 1 + 6 + 11 (tech + org + scale) | 5 Tradeoff, 9 Hiring, 15 Failure |

---

## 60-second “tell me about yourself” (resume spine)

> I’m Ramit — EM with 10+ years in fintech and financial systems. At Skydo I lead 12 engineers on international payments and settlement — 10K+ transactions a day — with hard guarantees on idempotency, reconciliation, and failure recovery. I also served as CIO and led ISO 27001 and SOC 2 Type II. Before that I was a VP at Goldman Sachs leading 9 engineers on distributed market-risk aggregation — multi-terabyte compute for VaR and stress — promoted to VP within a year. Earlier I was lead developer on Oracle Identity Cloud Service. I’m excited about Dezerv because wealth for affluent clients is a correctness-and-trust problem on a data-heavy product — and the EM seat is hands-on: decide what to build and kill, stay close to the code, grow seniors.

---

*Practice each story once out loud. If interrupted, finish the current beat, then follow their poke. Always end with metric + lesson.*
