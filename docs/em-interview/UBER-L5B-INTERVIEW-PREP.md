# Uber L5B (Staff Engineer) — Interview Prep Guide

**Ramit Hansda | April 2026**

---

## What L5B Actually Means

> **Key insight before anything else:** L5B at Uber is not a Senior Engineer role. It is Uber's equivalent of a **Staff Engineer** at most Big Tech companies. Uber bisected the old "Senior 2" level in 2022 — L5A maps to "Senior Engineer" (team-level impact), L5B maps to "Staff Engineer" (cross-team, org-level impact). The interview bar shifts accordingly.

**L5B vs L5A — the core difference:**

| Dimension | L5A (Senior) | L5B (Staff) |
|---|---|---|
| Scope | Team-level projects | Cross-team, multi-org projects |
| Design depth | One system end-to-end | Multiple systems + their interactions |
| Ambiguity handled | Well-defined problems | Ill-defined problems with competing constraints |
| Leadership | Technical lead of a feature | Technical direction for a domain |
| Industry equivalent | Senior Engineer | Staff Engineer (Google L6, Meta E6, Amazon P6) |

---

## Interview Loop Structure

The Uber L5B onsite loop is typically **4–5 rounds**, covering:

```
Phone Screen (Technical, 45–60 min)
       ↓ [if shortlisted]
Onsite Loop (4 rounds, same day or split across 2 days):
  R1: Coding Round 1 (DSA, 60 min)
  R2: Coding Round 2 (DSA or Machine Coding, 60 min)
  R3: System Design (60–75 min)  ← MOST IMPORTANT AT L5B
  R4: Behavioral / Scope & Impact (45–60 min)
```

> **Critical shift from L5A:** At L5B, the System Design round carries the most weight (~40–50% of the hiring signal). A strong coding performance cannot compensate for a weak system design. The bar is inverted compared to L5A where DSA was the eliminator.

---

## Round 1 & 2 — Coding (DSA)

### What Changes vs L5A

- LC Hard questions are **possible but not the norm**. Expect LC Medium-Hard.
- More emphasis on **communication of trade-offs** and correctness over raw speed.
- Interviewers probe: *Why this data structure? What's the time complexity? What breaks at scale?*
- Clean, readable code matters — L5B engineers are expected to set standards, not just solve problems.

### High-Probability Topic Areas

#### 1. Graphs — Shortest Path, Routing (Uber-specific, HIGH PRIORITY)
Uber's business is literally a routing problem. Expect graph questions framed in marketplace terms.

- **Dijkstra's algorithm** (weighted shortest path — ETA calculation)
- **BFS** (unweighted shortest path)
- **A\*** (heuristic search — real-time routing with traffic)
- Topological sort (dependency ordering, scheduling)

```java
// Dijkstra — staple for routing/ETA questions
public int[] dijkstra(int[][] graph, int src) {
    int n = graph.length;
    int[] dist = new int[n];
    Arrays.fill(dist, Integer.MAX_VALUE);
    dist[src] = 0;
    PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> a[0] - b[0]);
    pq.offer(new int[]{0, src});
    while (!pq.isEmpty()) {
        int[] cur = pq.poll();
        int d = cur[0], u = cur[1];
        if (d > dist[u]) continue;
        for (int[] edge : graph[u]) {
            int v = edge[0], w = edge[1];
            if (dist[u] + w < dist[v]) {
                dist[v] = dist[u] + w;
                pq.offer(new int[]{dist[v], v});
            }
        }
    }
    return dist;
}
```

#### 2. Sliding Window + Deque (confirmed Uber question)
**Problem:** Longest Continuous Subarray With Absolute Diff ≤ K (LC 1438)

```java
public int longestSubarray(int[] nums, int limit) {
    Deque<Integer> maxDeque = new ArrayDeque<>(); // decreasing
    Deque<Integer> minDeque = new ArrayDeque<>(); // increasing
    int left = 0, result = 0;
    for (int right = 0; right < nums.length; right++) {
        while (!maxDeque.isEmpty() && nums[maxDeque.peekLast()] <= nums[right])
            maxDeque.pollLast();
        while (!minDeque.isEmpty() && nums[minDeque.peekLast()] >= nums[right])
            minDeque.pollLast();
        maxDeque.addLast(right);
        minDeque.addLast(right);
        while (nums[maxDeque.peekFirst()] - nums[minDeque.peekFirst()] > limit) {
            left++;
            if (maxDeque.peekFirst() < left) maxDeque.pollFirst();
            if (minDeque.peekFirst() < left) minDeque.pollFirst();
        }
        result = Math.max(result, right - left + 1);
    }
    return result;
}
```

#### 3. Heaps / Priority Queues
- Merge K sorted lists (LC 23)
- K closest points to origin (LC 973)
- Find median from data stream (LC 295) — two-heap pattern
- Meeting Rooms II/III (LC 253/2402) — direct map to scheduling problems

#### 4. Dynamic Programming
- Maximum profit with at most 2 transactions (LC 123) — state machine DP
- Coin Change (LC 322)
- Longest Increasing Subsequence (LC 300)
- Weighted job scheduling — directly relevant to Uber driver scheduling

#### 5. Backtracking / Combinatorics
- All combinations summing to target N
- Unique paths with constraints (grids)

### L5B-Specific Communication Template
At L5B, interviewers expect you to front-load your reasoning:

```
1. "Let me make sure I understand the problem — [restate it precisely]"
2. "Constraints: N = ?, value range = ?, memory constraints = ?"
3. "At Uber scale this might mean [X million requests/day] — does that affect our approach?"
4. "Brute force is O(?) — here's why we can do better"
5. "The key insight is [pattern]: [explain why it applies here]"
6. "Let me code it up and we can walk through a test case together"
7. "Edge cases: [list 3-4 specific ones before they ask]"
8. "For a Staff-level code review, I'd also add [unit tests / error handling / metrics]"
```

---

## Round 3 — System Design (HIGHEST WEIGHT)

### The L5B Expectation Gap

Most candidates prepare like L5A: draw boxes, talk about microservices, mention Kafka. L5B interviewers are looking for something fundamentally different:

| L5A System Design | L5B System Design |
|---|---|
| "Here's how I'd build this one system" | "Here's how this system fits into Uber's broader platform" |
| Identifies the happy path | Identifies where the system will fail at scale — before being asked |
| Chooses a database | Defends the database choice with data: QPS, consistency model, access pattern |
| Mentions fault tolerance | Specifies: what fails, how it fails, what the user experiences, how you recover |
| One design | Two designs with explicit trade-offs |

### Uber System Design Framework (for L5B)

Use this exact sequence. Don't skip steps. Every step signals seniority.

```
STEP 1 — SCOPE (5 min)
  - Clarify: what is the core user journey? What is out of scope?
  - Quantify: DAU, QPS, data volume, latency SLO, availability SLO
  - Uber primitive check: which of {rider, driver, merchant, courier, trip, region, city} are the entities?

STEP 2 — API SURFACE (3 min)
  - Define the 2-4 core endpoints with request/response schemas
  - Identify which are read-heavy vs write-heavy

STEP 3 — HIGH-LEVEL DESIGN (10 min)
  - Draw the data flow end-to-end
  - Name the services, not generic "service A → service B"
  - Call out the hardest 2-3 engineering problems immediately

STEP 4 — DEEP DIVE (20 min)
  - Pick 2 components to go very deep on
  - For each: data model, partitioning strategy, failure modes, recovery mechanism

STEP 5 — CROSS-CUTTING CONCERNS (10 min)
  - Geographic distribution: how does this work in 50 cities simultaneously?
  - Observability: what are the golden signals? What do alerts look like?
  - Failure modes: partial outage, cascading failure, thundering herd
  - Trade-offs you explicitly chose NOT to make (and why)

STEP 6 — L5B SIGNAL: ORGANIZATIONAL CONTEXT (5 min)
  - How does this system evolve over 18 months?
  - What other teams depend on this? What contracts do you need?
  - What would you change if you had 10x the team?
```

### High-Priority System Design Questions at Uber L5B

#### 1. Design Uber's Real-Time Driver Location Tracking System

**This is the most commonly reported L5B system design question.**

Entities: `driver`, `location_update`, `rider`, `trip`
Scale: 5M drivers worldwide, GPS pings every 4 seconds → ~1.25M writes/sec

**Key components:**
- **Ingestion:** GPS updates via WebSocket or HTTP polling → Kafka partitioned by `driver_id` or geohash
- **Location store:** Redis (TTL-based, hot data) for current location + Cassandra for historical
- **Geospatial indexing:** Geohash or H3 (Uber's own hexagonal grid) for spatial lookup
- **Consumers:** Dispatch (nearest driver), ETA service, Maps service, Safety monitoring

**The problems they want you to discuss:**
- **Stale location:** GPS drops, driver goes underground. What does the system show?
- **Thundering herd:** 100K drivers all reconnect after an outage at the same time.
- **Consistency vs latency:** The rider sees driver location 4s behind reality. Is that acceptable?
- **Battery/data optimization:** How do you reduce GPS ping frequency without degrading UX?

**Your angle (from Skydo experience):** You've built event-driven systems with strict state consistency. Map `driver_location_updated` to your payment event model — same patterns: correlation IDs, event ordering, exactly-once semantics.

---

#### 2. Design Surge Pricing (Dynamic Pricing Engine)

Entities: `region`, `supply`, `demand`, `surge_multiplier`, `trip_request`
Scale: pricing must recompute per region every 30–60 seconds

**Key components:**
- **Supply aggregator:** Count available drivers per geohash cell (sliding window)
- **Demand aggregator:** Count trip requests per geohash cell (sliding window)
- **Pricing engine:** `surge = f(demand/supply ratio, time_of_day, special_events)`
- **Multiplier publisher:** Push to a distributed cache (Redis) consumed by the trip-request API

**The problems they want you to discuss:**
- **Oscillation:** High surge → drivers flood the zone → surge drops → drivers leave → surge spikes again. Damping mechanism?
- **Consistency on acceptance:** Once a rider accepts a price, that price must be honored even if surge recalculates. How do you lock it?
- **Fairness + auditability:** Uber faced regulatory scrutiny. How do you log and explain every price calculation?
- **Cold start:** A new city with no historical data. How does the pricing engine bootstrap?

**Your angle:** You built a Pricing Engine at Skydo (fee calculation per transaction). Same architecture: event-driven computation, versioned outputs, immutable audit trail.

---

#### 3. Design the Uber Dispatch System (Trip Matching)

Entities: `rider`, `driver`, `trip_request`, `match`, `city`
Scale: 25M trips/day globally, <10s matching latency SLO

**Key components:**
- **Request queue:** Trip requests partitioned by city/region
- **Driver pool:** In-memory data grid (or Redis Sorted Set) of available drivers, indexed by geolocation
- **Matching algorithm:** Nearest driver, but weighted by ETA, acceptance rate, rating
- **Assignment:** Optimistic locking or compare-and-swap to prevent double-assignment

**The problems they want you to discuss:**
- **Fairness vs efficiency:** Nearest driver is not always the fairest. How do you balance?
- **Driver cancellation:** Driver accepts then cancels. How do you re-match without starvation?
- **Regional isolation:** If dispatch for Mumbai fails, does Bangalore go down too? No — explain cell-based isolation.
- **Batching vs real-time:** Surge periods with thousands of simultaneous requests. Batching improves match quality but adds latency.

---

#### 4. Design a Real-Time Notification Service (Payments/Trips)

This maps directly to your experience at Skydo.

**Your one-line answer:**
> "I actually built this at Skydo for payment state transitions. Let me walk you through exactly how we architected it — and where the edge cases hit us in production."

Then walk the architecture from `docs/em-interview/payment-platform-em-explainer.md` — but reframe it in Uber terms (trip events instead of payment events).

---

### System Design Anti-Patterns at L5B (Things That Fail Interviews)

1. **Generic "use Kafka" without justification.** Always say: "I'd use Kafka here because [specific ordering/durability/fan-out requirement]."
2. **No failure modes discussed.** L5B engineers are expected to volunteer failure scenarios before the interviewer asks.
3. **Single-region design.** Uber operates in 50+ countries. Always address geographic distribution.
4. **Ignoring the ops model.** Who owns this in production? What's the on-call story? This signals Staff-level thinking.
5. **Designing for 1x load.** State your load assumptions upfront and design for 10x with clear scaling knobs.

---

## Round 4 — Behavioral / Scope & Impact

### What L5B Behavioral Looks For

The behavioral round at L5B is not about "tell me about a time you disagreed." It is a **scope and impact interview** — interviewers are calibrating: *Is this person operating at Staff level or Senior level?*

Staff-level signals:
- You influenced decisions **beyond your own team** without direct authority
- You identified a problem **before someone handed it to you**
- You made a technical decision that **changed how multiple teams work**
- You mentored or unblocked engineers in a lasting, structural way — not just day-to-day

Senior-level signals (what you want to avoid demonstrating):
- Impact contained to your own sprint or team
- You needed clear requirements before executing
- Your influence came from your manager's sponsorship, not your own technical credibility

### Your Story Bank — Mapped to L5B Dimensions

#### Cross-team impact without authority
**Story: Reconciliation Framework at Skydo**
> "Finance, Ops, Engineering, and our Banking Partner each had different mental models of what 'settled' meant. I couldn't mandate anything — I had to earn alignment. I wrote a one-pager, ran it by each team, and introduced 7 canonical states with entry/exit conditions that became the shared vocabulary across all four groups. No one asked me to do this — I identified the cross-team coordination problem and solved it."

**L5B signal:** You defined a standard that changed how four external teams operated. That's org-level impact.

---

#### Identified a systemic problem before it was assigned to you
**Story: Duplicate Payout Incident → Idempotency Standard**
> "After the duplicate payout incident, I didn't just fix the bug. I ran a cross-team 'Pattern Review' and discovered idempotency was handled three different ways across five services — none of them consistently. I wrote the Idempotency Standard, built a reusable library, and added it to the Production Readiness Review. Two teams I didn't manage adopted it independently. That's the signal that you've built something that scales beyond your own org."

**L5B signal:** Systemic diagnosis + standard-setting + adoption by teams you don't own.

---

#### Technical decision that changed how multiple teams work
**Story: Temporal Adoption at Skydo**
> "We evaluated four orchestration options — Temporal, Cadence, AWS Step Functions, and a custom Postgres + SQS approach. I ran a 5-gate evaluation framework and made the case for Temporal. But the real cross-team decision was: I made Temporal the standard for *all* new workflow orchestration at Skydo, not just my team's. I ran a 2-week internal workshop, built a reference workflow + test harness as the canonical pattern, and had engineers teach it to other teams. Payout-related incidents dropped 40% QoQ, and three other teams adopted it to replace their bespoke schedulers."

**L5B signal:** You drove a technical platform decision and created adoption infrastructure, not just a one-team solution.

---

#### Mentorship that changed trajectory, not just helped in the moment
**Story: Goldman Sachs — Promoting 4 engineers to Senior 2 (L5B equivalent)**
> "I ran 1:1s structured around capability gaps, not status updates. For each engineer, I identified the one dimension holding them back — usually it was either system design depth or stakeholder communication. I gave them stretch projects with explicit mentorship contracts: 'I'll co-design this with you for 2 weeks, then you present it to the senior team solo.' Four engineers promoted to Senior 2 in 2 years. The strongest signal of effectiveness: they now mentor others the same way."

**L5B signal:** Multiplied technical capability across the org, not just supported day-to-day delivery.

---

### Uber Values — Your Story Map

| Uber Value | Story | Metric |
|---|---|---|
| **Go Get It** (Ownership, not assigned) | Reconciliation framework — identified cross-team problem myself | 0.6% → 0.02% unreconciled TPV |
| **Build With Heart** (Customer obsession) | Automated onboarding — cut time from hours to minutes | 5+ entity types onboarded |
| **Surprise and Delight** | GenAI standard — raised entire org's engineering velocity | 90% adoption, 25% PR cycle time ↓ |
| **Stand for Safety** | ISO 27001 + SOC 2 as CIO — security posture from scratch | Certified, zero incidents |
| **Be Fearlessly Open** | Duplicate payout post-mortem — owned failure publicly, changed system | 0 recurrences in 18 months |
| **Grow Together** | Promoted 4 to Senior 2 at Goldman, 8 mentored at Skydo | Measurable career trajectory changes |
| **Make Magic** (Cross-team leverage) | Temporal adoption became org-wide standard | 3K LOC retired, 3 teams adopted |

---

## Your Edge — Where Your Background Wins

### You Have Production Experience in Uber's Exact Problem Space

| Uber Problem | Your Experience | Talking Point |
|---|---|---|
| High-throughput event processing | 10K+ cross-border txns/day, Kafka pipelines | "We processed FX events from 3 providers with ordering guarantees across 5 services — here's the partitioning strategy" |
| Idempotency at scale | Built idempotency layer at every service boundary | "Every money-moving endpoint binds to a business-level intent ID, not a transport ID — here's why that distinction matters" |
| Distributed workflow orchestration | Temporal adoption, replaced 3K LOC of bespoke schedulers | "Multi-day KYC workflows with human-in-the-loop steps — exactly the kind of problem Temporal is designed for" |
| Reconciliation / state consistency | 3-layer reconciliation ladder across 4 external parties | "I define reconciliation as a ladder, not a job — let me show you why that changes the debugging model" |
| Real-time pricing computation | Pricing Engine at Skydo — fees per transaction, FX rate | "We computed fees in real-time against live FX rates with versioned outputs and an immutable audit trail" |
| Compliance as an async pipeline | Compliance service with ops dashboard, async approval | "Compliance was event-driven so it never blocked throughput — flagged transactions waited in the pipeline, not in the serving path" |
| Geo-distributed infra | Multi-cloud (GCP + AWS), ECS vs EKS trade-offs at Skydo | "We chose ECS for operational simplicity, but designed the deployment model to be portable — here's the abstraction layer" |

### Your Goldman Sachs Experience Adds Scale Credibility

At L5B, interviewers look for evidence you've operated at large scale:
- Multi-terabyte in-memory distributed compute clusters
- Petabyte-scale market data ingestion
- P99 latency optimization (7s → 1.1s via GC pause analysis)
- Cross-functional coordination with quants, global teams

Lead with Goldman when the conversation is about scale. Lead with Skydo when the conversation is about payments architecture or startup velocity.

---

## System Design Deep-Dive: Uber Primitives You Must Know

### H3 — Uber's Hexagonal Geospatial Indexing

Uber open-sourced H3, their hexagonal hierarchical spatial index. You don't need to implement it, but you need to know:
- Why hexagons over squares: more uniform distance from center to edge, better for approximating circular regions
- How to use it: every location maps to an H3 cell at a given resolution (coarser = larger hex)
- For system design: partition driver locations by H3 cell → enables efficient "all drivers in this hex" queries
- **Mention it in design:** "I'd use H3 for geospatial partitioning — it's what Uber's own systems use and it avoids the hotspot problem you get with lat/lon grid buckets"

### CAP Theorem Applied to Uber Systems

| System | Consistency | Availability | Partition Tolerance | Uber's Choice |
|---|---|---|---|---|
| Driver location | Eventual | High | Yes | AP — stale location is acceptable |
| Trip state machine | Strong | Medium | Yes | CP — double-booking is not acceptable |
| Surge pricing | Eventual | High | Yes | AP — 30s stale multiplier is fine |
| Payment/settlement | Strong | Medium | Yes | CP — money movement requires consistency |
| Notification delivery | Eventual | High | Yes | AP — at-least-once delivery acceptable |

This table demonstrates Staff-level thinking: you don't give one CAP answer for a system — you give per-component answers with business justification.

### Consistent Hashing — for Partitioning and Load Balancing

At L5B you should be able to explain:
- Why consistent hashing over modulo hashing (reduces reshuffling when nodes change)
- Virtual nodes to handle heterogeneous node capacity
- Application: Kafka partition assignment by `driver_id`, Redis cluster sharding

### Saga Pattern vs 2PC

Uber's systems can't use distributed 2PC (too slow, single point of failure). You must know Sagas:
- **Choreography-based Saga:** each service listens on a Kafka topic and emits the next event. Compensating transactions handle failures.
- **Orchestration-based Saga:** a central orchestrator (like Temporal) drives the workflow and handles compensations explicitly.
- **Your angle:** "We used orchestration-based Saga at Skydo via Temporal — it gave us full observability of workflow state, which is critical for financial audits."

---

## Coding Practice List for L5B

> **See also:** `UBER-C1-C2-QUESTIONS-BANK.md` — a compiled list of every confirmed question asked in C1 and C2 rounds, sourced from real 2024–2026 interview reports across all levels.

| # | Problem | LC # | Pattern | L5B Priority |
|---|---|---|---|---|
| 1 | Network Delay Time | 743 | Dijkstra | MUST — routing |
| 2 | Cheapest Flights Within K Stops | 787 | Bellman-Ford / modified Dijkstra | MUST — routing |
| 3 | Find Median from Data Stream | 295 | Two heaps | MUST — pricing/analytics |
| 4 | Sliding Window Maximum | 239 | Monotonic deque | MUST |
| 5 | Longest Subarray abs diff ≤ K | 1438 | Sliding window + deque | MUST (reported question) |
| 6 | Maximum Profit with 2 transactions | 123 | State machine DP | HIGH — pricing |
| 7 | Course Schedule II | 210 | Topological sort | HIGH |
| 8 | Word Ladder | 127 | BFS | HIGH |
| 9 | Trapping Rain Water | 42 | Two pointer / monotonic stack | HIGH |
| 10 | Merge K Sorted Lists | 23 | Heap | HIGH |
| 11 | Meeting Rooms III | 2402 | Two heaps | HIGH (machine coding) |
| 12 | LRU Cache | 146 | LinkedHashMap | HIGH (machine coding) |
| 13 | Design In-Memory File System | 588 | Trie + OOP | MEDIUM |
| 14 | Design Twitter | 355 | Heap + HashMap | MEDIUM |
| 15 | Largest Rectangle in Histogram | 84 | Monotonic stack | MEDIUM |

---

## Prep Sprint Plan

### Day 1 — DSA Graphs + Routing
- [ ] LC 743 (Network Delay Time — Dijkstra)
- [ ] LC 787 (Cheapest Flights — Bellman-Ford)
- [ ] LC 127 (Word Ladder — BFS)
- [ ] LC 210 (Course Schedule II — topological sort)
- [ ] Review: When Dijkstra fails (negative weights → Bellman-Ford)

### Day 2 — DSA Heaps + Sliding Window
- [ ] LC 295 (Median from stream — two heaps)
- [ ] LC 239 (Sliding Window Maximum)
- [ ] LC 1438 (Abs diff ≤ K)
- [ ] LC 23 (Merge K sorted lists)
- [ ] LC 123 (Max profit 2 transactions — state DP)

### Day 3 — System Design: Location + Dispatch
- [ ] Design driver location tracking (from scratch, 45 min, write it out)
- [ ] Deep-dive: H3 geohashing, Kafka partitioning strategy
- [ ] Deep-dive: CAP analysis per component (write the table)
- [ ] Practice: Explain the system to a non-technical audience in 2 min

### Day 4 — System Design: Pricing + Notifications
- [ ] Design surge pricing engine (from scratch, 45 min)
- [ ] Map Skydo Pricing Engine → Uber Surge Pricing (same architecture, different domain)
- [ ] Map Skydo Notification Service → Uber trip notification
- [ ] Review: Saga patterns, Temporal orchestration story

### Day 5 — Machine Coding + Behavioral
- [ ] Code TrainScheduler from scratch in 45 min (no reference)
- [ ] Code a Rate Limiter (sliding window, OOP)
- [ ] Practice 4 STAR stories with timing (2 min each, out loud)
- [ ] Review Uber Values table — make sure every value has a story + metric

### Day 6 — Full Mock Loop
- [ ] 60-min timed mock: 1 LC Medium-Hard (graph/heap)
- [ ] 75-min timed mock: system design (choose surge pricing or dispatch)
- [ ] 45-min timed mock: behavioral — have someone probe your stories
- [ ] Review edge cases for all coded problems

---

## On Interview Day

### Opening Move (any round)
> "Before we start — just to set context: I'm an Engineering Manager at Skydo where I own the cross-border payments platform at 10K+ transactions/day. Prior to management I was IC for 8+ years — Goldman Sachs VP for a distributed market risk platform, and individual contributor at Moneyview, Oracle. I'm very comfortable going deep technically. What format would you like to take?"

### System Design Opening Move
> "I want to make sure I design for the right scale and trade-offs. Can I spend 2 minutes clarifying scope and constraints before I draw anything?"

Then ask:
1. "What's the target DAU and peak QPS?"
2. "What's the latency SLO — is this a user-facing API or backend pipeline?"
3. "What's our consistency requirement — can we tolerate eventual consistency or do we need strong consistency?"
4. "Is this global (multi-region) or city-scoped initially?"

### If You're Stuck on Coding
> "I want to think through this carefully rather than rush. I know a brute-force O(N²) approach — let me code that first to confirm correctness, and then we can optimize together. That's how I'd approach an unfamiliar problem in production too."

### After Each Round — Questions to Ask
- "What does the team's biggest unsolved technical problem look like right now?"
- "How does the Bengaluru team collaborate with teams in SF and Amsterdam on cross-cutting platform decisions?"
- "What distinguishes the engineers who have the most impact at L5B from those who are on the path there?"

---

## Quick Reference Crib Sheet

| Round | Your #1 Goal | Common Miss | Your Edge |
|---|---|---|---|
| Coding | Communicate reasoning, not just the answer | Going silent and coding without narration | Clean, named code — you're setting the quality bar |
| System Design | Volunteer failure modes before asked | Single-region, generic "use Kafka" design | Real Skydo payment architecture = Uber-scale equivalent |
| Behavioral | Demonstrate cross-team, org-level impact | Stories scoped to single team or sprint | Reconciliation framework, Idempotency Standard, Temporal rollout |

| Story | Theme | Metric |
|---|---|---|
| Skydo reconciliation ladder | Ambiguity → cross-team standard | 0.6% → 0.02% unreconciled |
| Goldman VaR latency | Systems diagnosis under pressure | P99 7s → 1.1s |
| Temporal rollout | Platform decision + adoption | 3K LOC, 40% incident drop |
| GenAI standard | Org-wide new domain, fast ramp | 90% adoption, 25% PR cycle ↓ |
| Duplicate payout → Idempotency Standard | Incident → systemic fix | 0 recurrences, 3 bugs caught early |
| ISO 27001/SOC 2 as CIO | Cross-functional ownership | Certified, org-wide security posture |

---

*L5B is not the "best senior engineer in the room." It's the engineer who makes the room better — across teams, across domains, sustainably.*
