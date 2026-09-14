# System Design Interview Steps — Must Not Miss

**Use this as the day-of clock.**  
Deeper principal framing: [`SYSTEM_DESIGN_PRINCIPAL_ENGINEER.md`](./SYSTEM_DESIGN_PRINCIPAL_ENGINEER.md).  
LLD (Java / object model) clock: [`lld/staff-engineer-interviews/INTERVIEW-RUNBOOK.md`](./lld/staff-engineer-interviews/INTERVIEW-RUNBOOK.md).

Most failed system design rounds are not “wrong technology.” They are **skipped steps**: jumping to boxes, never estimating load, never defining APIs or data ownership, never naming failure modes, never closing with trade-offs. This runbook is the ordered sequence you must complete even under time pressure.

---

## 0. The non-negotiable sequence (memorize this)

| # | Step | Why skipping kills you |
|---|------|------------------------|
| 1 | **Clarify + scope** | You solve the wrong problem; interviewer has to rescue you |
| 2 | **Functional requirements** | No shared contract for what “done” means |
| 3 | **Non-functional requirements (NFRs)** | Scale, latency, consistency, availability drive every later pick |
| 4 | **Back-of-envelope capacity** | Without numbers, sharding/caching/DB choices are vibes |
| 5 | **API / core contracts** | Proves you know the product surface, not just infra boxes |
| 6 | **Data model + ownership** | Who owns the entity? What’s the SoT? This is where Staff+ is won |
| 7 | **High-level architecture** | Boxes only *after* 1–6 so they map to real constraints |
| 8 | **Deep dive (1–2 hard problems)** | Generic diagrams without depth look mid-level |
| 9 | **Scaling & bottlenecks** | Show where the design breaks and how you fix it |
| 10 | **Failure modes + consistency** | Happy-path-only designs fail Staff/Principal bars |
| 11 | **Observability + SLOs** | How do you know it’s broken before users do? |
| 12 | **Trade-offs + close** | Explicit rejects + “what I’d do with more time” |

> **Rule:** If time collapses, **compress** steps — do not **delete** them. A 30-second capacity napkin + a named failure mode beats another Redis box.

---

## 1. First 60 seconds (say this, then ask)

Adapt nouns to the prompt:

1. “I’ll design this in layers: **requirements → capacity → API/data → architecture → deep dive → failures/trade-offs**. Interrupt anytime if you want depth elsewhere.”
2. “I’ll state **assumptions out loud** and treat them as defaults unless you correct them.”
3. Ask **2–3 clarifying questions that change the design** (not trivia). Prefer questions about:
   - **Read vs write ratio** / traffic shape
   - **Consistency** (cost of stale or incorrect data)
   - **Critical path** vs nice-to-have features
   - **Scale target** (users, QPS, data size, regions)
   - **Compliance / money / safety** constraints if relevant

**Bad clarifying Qs:** “Should we use Kafka?” / “Do you prefer SQL or NoSQL?”  
**Good clarifying Qs:** “Is a 5-second stale feed OK, or must the author see their own write immediately?” / “Is this a platform for other teams or a single product?”

---

## 2. Timed clocks

### 45-minute HLD (most common)

| Minutes | Phase | What must appear on the board |
|--------:|-------|-------------------------------|
| 0–5 | **Align** | Scope in/out, 2–3 design-changing Qs answered, assumptions listed |
| 5–8 | **FR + NFR** | Bulleted functional list + latency/throughput/consistency/availability targets |
| 8–12 | **Capacity** | QPS, storage, bandwidth napkin; call out the **dominant bottleneck** |
| 12–16 | **API + data** | 3–7 endpoints/events; entities + who owns writes |
| 16–28 | **Architecture** | Component diagram + request path for the primary use case |
| 28–38 | **Deep dive** | The hardest 1–2 problems (consistency, hot keys, fan-out, idempotency, etc.) |
| 38–42 | **Failures + scale** | One cascade failure + one scale break + mitigation |
| 42–45 | **Close** | Trade-offs you chose; what you’d deepen with more time |

### 60–90 minute HLD / Principal

| Extra time goes to | Do not steal time from |
|--------------------|------------------------|
| Design principles (3–4), evolution/migration, org/ownership, cost | Clarify, NFR, capacity, data ownership, failure modes |

Use the principal phase table in [`SYSTEM_DESIGN_PRINCIPAL_ENGINEER.md`](./SYSTEM_DESIGN_PRINCIPAL_ENGINEER.md) §3 when the bar is L7+.

---

## 3. Step-by-step playbook (what “done” looks like)

### Step 1 — Clarify + scope

**Produce:**
- In scope / out of scope (1 line each)
- 2–4 explicit assumptions

**Must say:** “Out of scope unless you want them: …”

### Step 2 — Functional requirements

**Produce:** 4–8 bullets of user-visible behaviors.

**Must not:** Jump to Kafka/Redis before FR are spoken.

### Step 3 — Non-functional requirements

**Always cover these four**, even briefly:

| NFR | Phrase to force a number or stance |
|-----|------------------------------------|
| **Latency** | “p99 for the hot path is ___ ms” |
| **Throughput** | “peak QPS / events/sec is ___” |
| **Consistency** | “stale OK here; strong here; reason: ___” |
| **Availability / durability** | “availability target ___; RPO/RTO if writes matter” |

Add when relevant: multi-region, cost ceiling, security/compliance, idempotency, ordering.

### Step 4 — Back-of-envelope capacity (never skip)

**Minimum napkin (60–90 seconds):**
1. DAU / peak QPS (or events/sec)
2. Read:write ratio
3. Payload size → bandwidth
4. Storage growth / day and retention
5. **One sentence:** “This means the bottleneck is X, so I’ll design around X.”

**Rounding rules interviewers accept:** 10^5 ≈ 100K, day = 10^5 seconds, 1 KB ≈ 10^3 bytes. Precision theater wastes time; order-of-magnitude that drives architecture is enough.

**If you truly have no numbers:** state defaults (“I’ll assume 1M DAU, 10:1 read:write, 1 KB payloads”) and invite correction.

### Step 5 — API / contracts

**Produce:**
- Core write API(s)
- Core read API(s)
- Optional: events published (name + key fields)

**Must include:** idempotency key / client request id on money or “must not double” writes.

### Step 6 — Data model + ownership

**Produce:**
- Entities (boxes or table names)
- Primary keys / access patterns
- **Single writer** per entity (“Service A owns Order; others get events/projections”)

**Must call out:** SoT vs cache vs derived index.

### Step 7 — High-level architecture

**Draw order (do not reverse):**
1. Clients / edge
2. Entry (LB / gateway)
3. Services on the **critical path**
4. Data stores
5. Async paths (queue/stream) **off** the critical path unless required

**Narrate one happy-path request end-to-end** while drawing. Silent box-drawing is a common failure.

### Step 8 — Deep dive (choose by pain, not by comfort)

Pick the **hardest** problem for *this* prompt, not your favorite technology story.

| Prompt family | Likely deep dive |
|---------------|------------------|
| Feed / social | Fan-out, timeline consistency, celebrity / hot keys |
| Marketplace / delivery | Matching, inventory reservation, geo, ETA |
| Payments / ledger | Idempotency, double-entry, exactly-once effects |
| Chat / presence | Ordering, delivery guarantees, connection fan-out |
| Search | Index freshness, ranking, query fan-out |
| Upload / media | Chunking, dedup, CDN, virus scan async |
| Notifications | Channel fan-out, retries, provider outage |
| Metrics / logs | Cardinality, downsampling, alert noise |

**Deep-dive structure (use every time):**
1. State why it’s hard  
2. Option A (pros/cons)  
3. Option B (pros/cons)  
4. **Make the call** tied to an NFR/principle  
5. Name detection + mitigation for the main risk  

### Step 9 — Scaling & bottlenecks

**Must name at least one of:**
- Hot partition / hot key
- Write amplification
- Cross-shard / fan-out query
- Cache stampede
- Queue backlog under spike

Then: how you detect it and the first mitigation (shard key change, cache, CQRS read model, rate limit, backpressure).

### Step 10 — Failure modes + consistency

**Minimum set (pick 2–3 live):**
- Dependency down (DB / cache / queue / third party)
- Duplicate delivery / retry storm
- Partial write / dual-write inconsistency
- Region loss (if multi-region was in scope)
- Poison message / bad deploy spike

For each: **fail open vs fail closed**, user-visible impact, recovery.

### Step 11 — Observability + SLOs

**Produce in ~60 seconds:**
- Golden signals for the hot path (latency, error rate, saturation, traffic)
- One **business** or correctness metric (e.g. double-charge rate, failed deliveries, stale freshness)
- Alert that pages a human vs dashboards only

### Step 12 — Trade-offs + close

**Always end with:**
1. “The load-bearing trade-off I chose is ___ because ___.”
2. “I explicitly rejected ___ because ___.”
3. “With more time I’d deepen ___.”
4. Optional: “What constraint matters most to you for v2?”

---

## 4. Whiteboard checklist (tick mentally before you finish)

- [ ] Scope in/out spoken
- [ ] FR bullets on board
- [ ] NFR: latency, throughput, consistency, availability
- [ ] Capacity napkin + named bottleneck
- [ ] APIs or events listed
- [ ] Data ownership / SoT clear
- [ ] End-to-end happy path narrated
- [ ] One deep dive with **two options + a call**
- [ ] One scale break + mitigation
- [ ] One failure mode + fail-open/closed stance
- [ ] SLO / metric named
- [ ] Explicit trade-off in the close

If any box is empty and you have 2 minutes left, fill the empty box — do not polish another service.

---

## 5. Common skips (and the fix in one line)

| What people skip | One-line recovery |
|------------------|-------------------|
| Capacity estimation | “Assume 10K peak QPS, 1 KB, ~10 MB/s — bottleneck is DB reads, so cache + read replicas.” |
| Consistency stance | “Feed can be stale 5s; checkout inventory is strong — reservation lease.” |
| API surface | “CreateX(idempotency_key), GetX(id), ListX(cursor) — that’s v1.” |
| Data ownership | “Orders service is SoT; search is a projection via CDC.” |
| Idempotency | “Client key stored with unique constraint; retries return the first result.” |
| Failure modes | “If queue is down, fail create closed; if cache is down, serve origin with SLO burn.” |
| Trade-off close | “I chose eventual fan-out for cost; I’d revisit if celebrity write latency dominates.” |
| Drawing before clarifying | Stop. Erase nothing — add requirements above the diagram and re-anchor. |

---

## 6. Staff vs Principal: same steps, different altitude

| Step | Staff signal | Principal signal |
|------|--------------|------------------|
| Clarify | Good questions | Reframes the problem / challenges the prompt |
| Principles | Implicit in choices | Named 3–4 principles before boxes |
| Architecture | Correct scalable design | Seams by **team + data ownership**, not tech labels |
| Deep dive | Solid option comparison | Decision reversible? org precedent? 2-year evolution? |
| Close | Trade-offs | Migration/phasing + what you’d stop building |

Do **not** skip Steps 1–12 at principal level — add altitude *on top* of them.

---

## 7. Micro-scripts

**Opener (Staff):**  
> “Before boxes: I’ll lock functional scope, NFRs (latency, QPS, consistency, availability), a quick capacity napkin, then API + data ownership, then architecture and one deep dive on the hardest constraint.”

**Opener (Principal):**  
> “Before boxes: why this system, blast radius, reversible vs one-way door, and org constraints. I’ll set 3–4 design principles, then capacity → seams → deep dive → evolution and ops.”

**When stuck:**  
> “Let me pause and name the hardest constraint I see: ___. I’ll design to that first.”

**When interviewer says “go deeper”:**  
> “I’ll hold the rest of the diagram and deep-dive ___ with two options and a recommendation.”

**When time is nearly gone:**  
> “I’ll skip more boxes and close with failure mode + the main trade-off so the design is complete.”

---

## 8. After the round (self-check)

1. Did I speak NFRs **before** technology names?
2. Did capacity change at least one architectural choice?
3. Did I name **who owns** the core entity?
4. Did I compare **two** approaches in the deep dive and pick one?
5. Did I state at least one **failure mode** and one **explicit reject**?

If any answer is no, that step was missed — drill it on the next mock.

---

## 9. Related docs in this repo

| Doc | Use when |
|-----|----------|
| [`SYSTEM_DESIGN_PRINCIPAL_ENGINEER.md`](./SYSTEM_DESIGN_PRINCIPAL_ENGINEER.md) | L7+ mindset, principles, seams, deep-dive framework |
| [`lld/staff-engineer-interviews/INTERVIEW-RUNBOOK.md`](./lld/staff-engineer-interviews/INTERVIEW-RUNBOOK.md) | Object-model / LLD rounds |
| [`URL_SHORTENER_HLD_PRINCIPAL_ENGINEER.md`](./URL_SHORTENER_HLD_PRINCIPAL_ENGINEER.md) | Worked example with timing + trade-off catalog |
| [`GITHUB_LIKE_VCS_HLD_STAFF_ENG.md`](./GITHUB_LIKE_VCS_HLD_STAFF_ENG.md) | Full candidate script + 45-min cheat sheet |
| [`EV_CHARGING_SYSTEM_HLD_STAFF_ENG.md`](./EV_CHARGING_SYSTEM_HLD_STAFF_ENG.md) | Another timed Staff HLD walkthrough |
| [`interviews/GS-SDA-DAYOF-CHEATSHEET.md`](./interviews/GS-SDA-DAYOF-CHEATSHEET.md) | Compressed day-of clarify → design loop |

---

## 10. One-page tear sheet (print / phone lock screen)

```
1 Clarify+scope → 2 FR → 3 NFR (lat/QPS/consist/avail)
4 Capacity napkin → name bottleneck
5 API (+idempotency) → 6 Data ownership/SoT
7 Architecture + narrate happy path
8 Deep dive: A vs B → pick → risk
9 Scale break → 10 Failure (open/closed)
11 SLO/metric → 12 Trade-off + close

NEVER skip: NFR, capacity, ownership, deep-dive call, failure, trade-off
If short on time: compress, don't delete
```
