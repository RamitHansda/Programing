# Jumio — Engineering Manager Interview Prep (Round 1, BarRaiser)
**Date:** 17 Apr 2026, 6:30 PM IST · **Interviewer:** BarRaiser Expert · **Role:** Engineering Manager

---

## 1. Company Context — Know This Cold

**Jumio in one line:** End-to-end KYC/AML/identity verification platform — document verification, biometric (selfie/liveness) checks, AML screening, fraud detection for banks, fintechs, crypto, marketplaces.

**What matters to them technically**
- AI/ML at scale — computer vision for ID docs, face match, liveness, deepfake/spoof detection.
- High-volume, low-latency verification pipelines (millions of verifications globally, 24/7).
- Global compliance — GDPR, CCPA, SOC 2, ISO 27001, PCI DSS, regional KYC/AML laws.
- Data privacy & security (biometric data = PII of the highest sensitivity).
- Multi-region deployments, data residency.
- Competitors: Onfido, Veriff, Socure, Persona, Trulioo.

**Why you're a strong fit (your narrative)**
1. **Identity background** — Oracle IDCS (distributed identity management) is directly adjacent to what Jumio does.
2. **Compliance leadership** — Led ISO 27001 + SOC 2 Type II as CIO at Skydo. Few EM candidates bring this.
3. **Fintech + payments** — 10K+ international txns/day, reconciliation, idempotency, failure recovery — overlaps heavily with Jumio's customer base (banks, fintechs).
4. **Scale** — Multi-terabyte distributed compute at Goldman.
5. **People leadership** — Led 12 engineers at Skydo, 9 at Goldman, mentored 8 directly.

---

## 2. BarRaiser Round 1 EM — Format You Should Expect

BarRaiser is an **interview-as-a-service platform** that Jumio outsources R1 to. The interviewer is an independent senior engineer/EM, not a Jumio employee. They use an **AI Co-pilot ("BarRaiser Co-pilot") that structures the conversation and scores you against a rubric in real time.**

**Implications of rubric scoring:**
- Vague answers don't score. Specifics, numbers, tradeoffs do.
- Structure verbally ("three things I did — first… second… third…") — the rubric loves this.
- Speak at transcription pace. The Co-pilot is taking notes.
- Every story must end with "the result was X%, the lesson was Y." Both are explicit rubric checkpoints.

**Round 1 breakdown (60 min):**

| Segment | Time | What they're probing |
|---|---|---|
| Intro + career walk | 5–8 min | Communication, narrative arc |
| Behavioral / leadership (STAR) | 20–25 min | People mgmt, delivery, conflict, hiring, culture |
| Technical depth / system design (lightweight) | 15–20 min | Architectural judgment, hands-on credibility (expect "walk me through a system you own") |
| Management scenarios | 5–10 min | Prioritization, stakeholder mgmt |
| Your questions | 5 min | Curiosity, seniority |

### Specific BarRaiser rubric-style questions to pre-answer (from their own published guides)

1. "What programming languages and technologies have you used in your previous roles?"
2. "How do you ensure the quality of code produced by your team?"
3. "How do you handle prioritizing tasks among team members?"
4. "Describe a situation where you had to manage disagreements between team members."
5. "How do you align your role with the company's objectives?"
6. "How do you approach ongoing learning and improvement?" *(have a genuine recent example — e.g., deep dive into AI-assisted eng rollouts)*

### Likely technical mini-prompts (light in R1, deep in R2)
- "Walk me through the architecture of a system you own today" — **almost guaranteed**
- "Design an API rate limiter" (common BarRaiser warm-up)
- "SQL vs NoSQL for X use case — why?"
- "How would you re-architect a Spring Boot service that's become a bottleneck?"
- "How do you decide microservice vs monolith?"

---

## 2b. The Jumio EM JD — Decoded

Pulled from Jumio's Greenhouse posting. **This shapes what they care about:**

> *"T-shaped engineering leader, applying deep technical judgment across the full development lifecycle. Active contribution to design, guiding quality/testing, driving CI/CD excellence…"*

**Translation:** They want a hands-on EM, not a pure people manager. Talk code, design, CI/CD credibly — not just headcount and 1:1s.

**Must-haves (map these to your stories):**
| JD Requirement | Your Evidence |
|---|---|
| 10+ years eng, 2+ years EM | ✅ 10+ / 3.5+ |
| Java, Spring Boot, microservices | ✅ Oracle IDCS + Skydo |
| Docker, Git, Agile | ✅ |
| AWS (ECS, ECR, SQS, SNS, EventBridge, Lambda, KMS, VPC) | ✅ |
| Mentoring & team building | ✅ 12 at Skydo, 9 at Goldman |

**Nice-to-haves (volunteer these — they're differentiators you uniquely have):**
| JD Nice-to-Have | Your Evidence — mention proactively |
|---|---|
| GDPR-compliant development | **ISO 27001 + SOC 2 Type II as CIO at Skydo** |
| AI/LLM in engineering workflows | **Led Claude / Cursor / Windsurf rollout at Skydo** |
| Terraform / IaC | ✅ on resume |

**JD-driven probes to prep for:**
- "Tell me about a production incident you owned, and what you changed systemically."
- "How have you driven CI/CD and engineering hygiene at your current team?"
- "How are you incorporating AI into your team's workflow?"
- "How do you manage technical debt alongside feature delivery?"
- "Walk me through how you handle post-mortems."

### Language-of-stack tip
When asked "what do you work in day to day?" — **lead with Java + Spring Boot + AWS**. That's Jumio's stack. Don't bury it behind Kotlin / Go / Python.

---

## 3. Your Opening Pitch (60–90 seconds) — Memorize the Shape

> "I'm an Engineering Manager with 10+ years across fintech, enterprise identity, and large-scale financial systems. Currently at Skydo I lead a team of 12 engineers owning an international payments & settlement platform doing 10K+ transactions a day, and I also serve as CIO where I led our ISO 27001 and SOC 2 Type II certifications.
>
> Before Skydo I was a VP at Goldman Sachs leading a 9-person team on distributed market risk aggregation on multi-terabyte in-memory clusters — promoted to VP within a year. Before that I spent ~3 years at Oracle as lead developer on Oracle Identity Cloud Service, which is where I built my foundation in distributed identity systems.
>
> I'm drawn to Jumio because it sits right at the intersection of everything I've done — identity, compliance, high-throughput distributed systems, and fintech customers. Happy to go deeper on any of it."

**Why this works:** Identity (Oracle) → scale (Goldman) → leadership + compliance (Skydo). Three beats, connects directly to Jumio.

---

## 4. STAR Stories — Pre-Loaded Answers

Use **S**ituation · **T**ask · **A**ction · **R**esult. Keep each to ~2 minutes. Always end with a quantified result and a lesson.

### Story A — Delivery under pressure & resilience
*"Tell me about a time you owned a critical production system."*
- **S:** Skydo's payments/settlement platform — 10K+ international txns/day; failures = regulatory + customer money risk.
- **T:** Recurring incidents from concurrent workflow races, flaky third-party FX/banking partners.
- **A:** Introduced distributed locking, rule-based decisioning, idempotency everywhere, retries with SLA-aware scheduling, structured observability (traces/metrics/alerts tied to business KPIs).
- **R:** ~30% reduction in production incidents; reconciliation discrepancies dropped materially; on-call load halved for the team.
- **Lesson:** Observability before optimization — you can't fix what you can't see in a distributed system.

### Story B — Leadership & growing engineers
*"How do you develop engineers?"*
- **S:** Skydo team of 12, mixed seniority, needed to level up architectural judgment.
- **T:** Raise the bar on system design and distributed-systems thinking without slowing delivery.
- **A:** Weekly architecture reviews, paired senior + mid engineers on ADRs, set up a "design doc first" norm for anything touching payments, 1:1s with growth plans tied to measurable skills (not vague "seniority").
- **R:** 8 engineers directly mentored; 2 promotions inside 18 months; design review coverage went from ~30% of major changes to ~100%.
- **Lesson:** You grow engineers by making the *forum* — reviews, ADRs, 1:1s — consistent, not by one-off mentorship.

### Story C — Conflict / difficult conversation
*"Tell me about a time you had a hard conversation with a report."*
- **S:** A strong IC who was technically excellent but routinely dismissive in reviews, blocking team trust.
- **T:** Preserve the talent, fix the behavior, protect the team.
- **A:** Private 1:1 with specific examples (dates, Slack threads, PR comments), framed around impact not personality; set 30/60/90 behavior goals; shadowed next two design reviews; gave real-time feedback after.
- **R:** Behavior shifted within 6 weeks; engineer later acknowledged it in a 1:1. No attrition.
- **Lesson:** Feedback without specific examples feels like a character attack. Always bring receipts.

### Story D — Hiring / raising the bar
*"How do you hire?"*
- **S:** Built Skydo engineering team from small to 12; also interview loops at Goldman.
- **T:** Consistent signal, avoid bias, hire for the team one year out (not today's gap).
- **A:** Standardized rubric across rounds (problem solving, systems design, collaboration, ownership); bar raiser on every loop; debrief same day while signal is fresh; written feedback required before debrief to avoid groupthink.
- **R:** Offer-accept rate high; no regret hires in the last 18 months of loops I ran.
- **Lesson:** Process is what makes hiring fair *and* fast.

### Story E — Cross-functional / stakeholder conflict
*"Engineering vs Product tension."*
- **S:** PM pushing to ship an onboarding feature for a new entity type; engineering flagged compliance/KYC gaps.
- **T:** Don't block growth, don't ship unsafe.
- **A:** Mapped risk explicitly (regulatory fine $ + time-to-fix), proposed phased release: feature-flagged beta for a whitelisted cohort, full rollout after audit trail review. Took it to product + compliance in one meeting instead of async debate.
- **R:** Shipped beta in 2 weeks, full rollout in 5, zero compliance findings at next audit.
- **Lesson:** The EM's job in these moments is to *quantify the tradeoff*, not pick a side.

### Story F — Strategic / org-level impact
*"Biggest impact in your career."*
- **S:** ISO 27001 + SOC 2 Type II at Skydo, as CIO.
- **T:** Unlock enterprise deals that required these; build real security posture, not theatre.
- **A:** Ran gap analysis, defined 40+ controls, drove DLP, access reviews, vendor risk program, incident response playbook, evidence collection pipeline. Coordinated across eng, HR, legal, finance.
- **R:** Both certifications achieved; unblocked enterprise sales pipeline; org-wide security culture shift (phishing test fail-rate dropped).
- **Lesson:** Compliance done well is a platform bet — the controls become reusable infrastructure.

### Story G — Failure / regret
*"Tell me about a failure."*
- Pick one: e.g., a Goldman migration or a Skydo rollout that had an incident.
- **Structure:** What went wrong → your role in it (own it, don't blame) → what you changed systemically → what you'd do differently.
- **Must-have:** Clear self-awareness. BarRaiser loves this signal.

### Story H — GenAI adoption (differentiator)
- Led Claude / Cursor / Windsurf rollout at Skydo — set standards, PR review norms, IP/security guardrails, measured impact on dev velocity.
- Great to mention proactively; Jumio is AI-heavy and will care.

---

## 5. Management Philosophy — Have Crisp Answers

Be ready in 30 seconds each:

- **How do you run 1:1s?** Weekly, 30 min, IC-driven agenda, rotating focus: delivery → growth → feedback → career. Notes in shared doc.
- **How do you measure team health?** DORA metrics + eNPS-style pulse + incident load + 1:1 signal. No single metric.
- **How do you decide tech vs delivery tradeoffs?** Explicit tech-debt budget per sprint (~20%), tracked like features. Never "we'll get to it."
- **How do you handle underperformance?** Clear written expectations → 30-day coaching → PIP only if coaching fails. Document everything from day 1.
- **Remote/hybrid team?** Default-async, docs-first, meetings are for decisions not status.
- **How do you manage up?** Weekly written summary to skip-level: delivered / risks / asks. No surprises.

---

## 6. Technical Depth — Likely Probes

Given your resume, they'll probe one of these deeply. Refresh the muscle on each:

### A. Identity verification system design (highest likelihood)
**Prompt:** *"Design an ID verification service — user uploads document + selfie, return verified/rejected in <5s."*

High-level blocks to mention:
- **Ingress:** API gateway, request validation, rate limit per tenant.
- **Upload pipeline:** Pre-signed S3/GCS uploads, antivirus scan, encryption at rest (KMS).
- **Orchestration:** Step-function / workflow engine — OCR → doc classification → authenticity checks → face detect → liveness → face match → AML screening.
- **ML serving:** Model registry, GPU inference pool, batching, A/B shadow traffic for new models.
- **Data:** PII vault with tokenization, biometric templates stored separately, strict data retention (GDPR right-to-delete).
- **Async fallback:** If synchronous SLA breached, fall back to webhook callback.
- **Observability:** Per-stage latency, per-model accuracy drift, fraud signal telemetry.
- **Multi-region:** Data residency zones (EU-only data stays EU), active-active with regional pinning.
- **Compliance hooks:** Full audit trail, immutable logs, reviewer queue for edge cases.

**Tradeoffs to volunteer:** sync vs async, monolith orchestrator vs step-functions, self-hosted ML vs managed, how to handle model rollback safely.

### B. Payments/settlement system (your home turf)
Idempotency keys, outbox pattern, reconciliation jobs, two-phase commit alternatives (saga), distributed locking, exactly-once illusions.

### C. High-throughput processing
Kafka partitioning, consumer lag, backpressure, ordering guarantees, DLQ strategies.

### D. Concepts worth 30-second refresh
- CAP / PACELC
- Saga vs 2PC
- Idempotency patterns
- Outbox / transactional inbox
- Event sourcing vs CRUD
- Leader election, distributed locks (Redis Redlock caveats, Zookeeper, etcd)
- Circuit breakers, bulkheads, backpressure
- Rate limiting (token bucket vs leaky bucket)
- Caching (write-through, write-back, cache-aside, TTL + stampede protection)
- ML in production: model registry, drift, shadow deploys, feature stores

---

## 7. Management Scenarios — Rapid-Fire

- *"Your top IC is burning out."* Reduce scope immediately, audit on-call rotation, 1:1 to understand, adjust quarterly goals, make it visible to skip-level. Never "push through."
- *"Team missed a deadline."* Post-mortem blameless, separate estimation issue from execution issue, fix the *process* not the people.
- *"Hiring freeze but scope grew."* Reprioritize ruthlessly, kill/defer work explicitly with stakeholders, document the tradeoff. Don't silently overload.
- *"Two senior engineers disagree on architecture."* Force a written ADR from each, neutral design review, decide + document the reversible vs irreversible parts of the decision, move on.
- *"Skip-level wants feature X in 2 weeks; team says 6."* Decompose scope. What's the smallest thing that delivers 80% of value in 2 weeks? Negotiate on scope, not on engineers working weekends.

---

## 8. Questions to Ask Them — Pick 3–4

Shows seniority. Avoid anything Googleable.

1. "What does the EM org structure look like at Jumio — how many reports typically, and is there an expectation of hands-on coding?"
2. "What's the biggest technical bet the team I'd manage is currently making? Where is it risky?"
3. "How is engineering velocity measured at Jumio, and what's currently broken or being improved?"
4. "With biometric data being the most sensitive PII, how does the team balance model iteration speed with compliance/audit constraints?"
5. "What's the make-or-break first 90 days look like for this role — what would a great EM have done by then?"
6. "Where does Jumio see its biggest competitive pressure right now — and how does that translate into engineering priorities?"
7. "Tell me about a recent post-mortem the team ran — what came out of it?" *(gauges culture)*

---

## 9. Pre-Interview Checklist (last 60 min)

- [ ] Zoom tested, laptop charged, backup hotspot.
- [ ] Water + notepad + pen.
- [ ] Resume printed or on second screen.
- [ ] This doc on second screen (but don't read from it — glance only).
- [ ] 5-minute walk + deep breath before joining.
- [ ] Join 3 min early.
- [ ] Smile in the first 10 seconds — sets the tone for BarRaiser scoring on "communication."

---

## 10. Common Traps — Don't Do These

- Rambling > 2.5 min on any single answer. Pause, let them redirect.
- "We" when it should be "I." EMs are evaluated on *personal* decisions — own yours clearly.
- No numbers. Every major story needs a metric.
- Trashing past employers or reports. Even about real bad actors — stay professional.
- Skipping the lesson/self-reflection at the end of a story.
- Over-engineering the system design. State the SLA and non-functional reqs *first*, then design to them.
- Forgetting to ask clarifying questions on system design before drawing boxes.

---

## 11. If They Ask "Why Jumio?"

> "Three reasons. First, identity is where I started — Oracle IDCS — and it's the problem space I find most durable; trust infrastructure only gets more important. Second, Jumio's customers look exactly like the ones I serve today at Skydo — fintechs, banks, regulated businesses — so I understand the buyer. Third, the compliance-plus-ML combination is rare; most companies do one or the other well. I've led compliance programs (ISO 27001, SOC 2) and scaled distributed data systems, so this is where my two halves come together."

---

## 12. If They Ask "Why Leave Skydo?"

Neutral, forward-looking. Example framing:
> "Skydo's been a great run — built the team, got the platform into a stable, high-throughput state, and shipped ISO 27001 + SOC 2. The next step in my career is scale — a larger global engineering org, a product that serves millions of end-users directly, and a problem space with harder ML and compliance constraints. Jumio fits that profile."

Never: money, boss issues, burnout, "looking for change."

---

Good luck. You're well-qualified — the job is to tell your story crisply and with numbers.
