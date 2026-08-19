# Razorpay Engineering Manager Interview - Prep Guide

> Source: Official Razorpay Engineering Blog + GeeksforGeeks + JoinTaro + candidate experiences

---

## Interview Structure (7 Rounds)

| Round | Duration | Who | Focus |
|---|---|---|---|
| 1. Telephone Screen | 1 hr | Hiring Manager | Role/culture fit, level fitment |
| 2. System Design (HLD) | 1 hr | Senior Engineer | High-level design, trade-offs |
| 3. Project Delivery & Execution | 1 hr | Engineering Leader | Past projects, delivery, program mgmt |
| 4. Low-Level Design (LLD) | 1 hr | Senior Engineer | OOP design, interfaces, pseudo-code |
| 5. Product Round | 1 hr | Product Manager | Product thinking, feature prioritization |
| 6. Hiring Manager Round | 1 hr | Direct supervisor | Culture fit, career, situational Qs |
| 7. HR Round | 1 hr | HR | Career journey, values, motivations |

---

## Round 2: System Design (HLD) - CONFIRMED QUESTIONS

These have been directly reported by candidates and the official Razorpay guide:

### Directly Confirmed Questions (Asked in EM/Senior Interviews)
1. **Design an Event Throttling Framework** *(Official Razorpay EM guide)*
2. **Design a Distributed Cache** *(Official Razorpay EM guide)*
3. **Design a Notification System** *(Multiple candidates, 2019–2024)*
   - Must cover: Push Notifications, SMS, Webhooks, Email
   - Success rates differ per channel
   - Handle channel failure (fallback strategy)
   - Identify and solve bottlenecks
   - Scale: 2K TPS baseline, burst to 10M+ events/day

### Razorpay-Domain HLD Topics (High Probability Given Their Tech Stack)
4. **Design a Payment Gateway / Payment Router**
   - Multi-gateway routing (10–15 gateways)
   - Gateway scoring: success rate (35%), latency P95 (25%), method affinity (15%), bank affinity (12%), amount fit (8%), time-of-day (5%)
   - Circuit breaker pattern (CLOSED → OPEN → HALF-OPEN states)
   - Retry with exponential backoff
   - Fallback on failure

5. **Design a Webhook Delivery System**
   - At-least-once delivery semantics
   - Retry for 24 hours with exponential backoff
   - Idempotency via unique event IDs (`x-razorpay-event-id`)
   - SLA: p99 delivery within 10 minutes
   - Scale: 4M → 10M+ daily events

6. **Design a Rate Limiter**
   - Handle 1500 RPS during flash sales (IPL, Diwali)
   - Algorithms: Fixed window, Sliding window, Token Bucket
   - Nginx as rate limiter (sidecar pattern)
   - Atomic counter + TTL per key
   - Thundering herd prevention

7. **Design a Calendar Conflict Detection System**
   - Reported on InterviewPal as an EM-level system design question

---

## Round 4: Low-Level Design (LLD) - CONFIRMED QUESTIONS

### Directly Confirmed Questions
1. **Design In-Memory SQL** *(Confirmed, Senior SDE 2020, SDE-2 2024)*
   - Functions: `addTable()`, `removeTable()`, `select * from table`, `delete from table`
   - Tip: Write partial working code, not just pseudocode
   - Focus: extensibility, modularity

2. **Design a Notification System (LLD variant)** *(Multiple candidates)*
   - Class design for channels, retry mechanism, channel registry

3. **Design a Parking Lot System** *(Reported by candidates)*

4. **Design a Rating System** *(SDE-2 candidate, 2024)*

5. **Implement a Messaging API** *(Official Razorpay EM guide)*

### Official Sample LLD Questions (From Razorpay's Guide)
6. Implement backend entities for **online cab booking**
7. Implement entities and interfaces for a **price comparison website**
8. Design **Zepto** (quick commerce) *(SDE-2 2024)*

---

## Round 5: Product Round - Preparation

The PM will give you a blank-slate product and ask you to drive the roadmap.

**Framework:**
1. Clarify who is the customer (persona)
2. Define the problem being solved
3. Prioritize features (MoSCoW / Impact vs Effort matrix)
4. Discuss trade-offs and risks
5. Define success metrics

**Products Razorpay Suggests Practicing:**
- Google Docs, Gmail, BookMyShow, Uber

**Razorpay-relevant Products to Practice:**
- Design Razorpay Dashboard (for merchants)
- Design a Payment Link product
- Design a Subscription/Recurring Payments feature
- Design a Fraud Detection Rule Engine

---

## Round 1 & 3: Behavioral & Execution Questions

### Telephone Screen - Expected Questions
- Explain the architecture of the most recent project(s) delivered by your team
- Explain some challenging situations in people management you encountered
- What are your interests? What should your next role look like?

### Project Delivery & Execution - Expected Questions
- Reflections on situations you learned the most from (especially failures)
- How did you identify and work around roadblocks and bottlenecks?
- Describe your approach to planning, executing, and communicating large projects
- Program management experience

### Hiring Manager Round - Expected Questions
- What are you specifically interested in doing?
- Why are you looking for a job change right now?
- Explain a situation where you felt overwhelmed with the project at hand
- Describe an argument or disagreement with a senior, and how it resolved

### HR Round - Focus Areas
- Hiring philosophy: How do you build a high-performance team?
- Coaching & Performance Management: How do you manage and grow your team?
- Culture inculcation: How do you set and drive team culture?
- People engagement and motivation strategies

**STAR Formula (use consistently):** Situation → Reaction/Thought Process → Action → Result/Impact

---

## Razorpay Culture Values (Know These Cold)

| Value | What They Mean in Practice |
|---|---|
| Transparency | Open communication, no hidden agendas |
| Challenge the Status Quo | Question processes, drive innovation |
| Drive with Autonomy | Own decisions, take initiative |
| Agility with Integrity | Move fast, don't cut ethical corners |
| Execution with Customer-First mindset | Always tie delivery to customer value |

---

## EM Role Expectations at Razorpay

1. **Org Building** - Curate the team, make the organization successful
2. **Pod Ownership** - Partner heavily with PM, ensure the right things get built
3. **Technical Management** - Scalable + robust software, right tech, thorough instrumentation/testing
4. **Execution Management** - Outcomes on time, high quality delivery
5. **People Management** - Hire, engage, retain top talent

---

## System Design Cheat Sheet: Key Patterns for Razorpay Context

### Event Throttling Framework
```
Requirements: Rate limit events per user/merchant/system
Components:
  - Token Bucket or Leaky Bucket per entity
  - Redis for distributed counters (INCR + EXPIRE)
  - Configurable rules: per-second, per-minute, per-day limits
  - Async queue for burst absorption (SQS/Kafka)
  - Dead-letter queue for dropped events
  - Admin API to update rules without restart

Trade-offs:
  - Fixed window vs sliding window (sliding: more accurate, higher cost)
  - In-memory vs Redis (Redis: distributed consistency, latency ~1ms)
```

### Distributed Cache
```
Requirements: Low latency reads, high availability, data consistency
Components:
  - Cache-aside pattern (app controls cache population)
  - Write-through or Write-behind for consistency
  - Consistent hashing for node distribution
  - Replication factor for HA
  - TTL-based eviction + LRU policy
  - Cache invalidation strategy (event-driven vs TTL)

Trade-offs:
  - Strong vs eventual consistency
  - Cache stampede prevention (mutex/probabilistic early expiration)
  - Cache penetration (bloom filter for non-existent keys)
  - Cache avalanche (staggered TTLs)
```

### Notification System at Scale
```
Requirements: Multi-channel, reliable, at-least-once delivery
Components:
  - Notification Request Service (API ingestion)
  - Channel Selector (priority: SMS > Email > Push based on urgency/success rate)
  - Per-channel workers (idempotent processors)
  - Retry queue with exponential backoff
  - Delivery status tracking (Cassandra or DynamoDB)
  - Dead-letter queue after N retries

Scale numbers:
  - 10M events/day = ~115 TPS average, burst to 2000+ TPS
  - p99 SLA: delivery within 10 minutes
  - Deduplication via event_id

Trade-offs:
  - At-least-once vs exactly-once (Razorpay uses at-least-once; client handles idempotency)
  - Channel failover order
  - Sync vs async delivery
```

---

## Key Questions to Ask Your Interviewers

- What is the biggest engineering challenge the team is facing right now?
- How do EMs contribute to the technical roadmap at Razorpay?
- How is success measured for an EM in the first 6 months?
- What does pod ownership look like in practice — how embedded are EMs with the PM?
- What does the engineering team structure look like (team size, reporting)?
- How are technical debt and reliability prioritized against feature work?

---

## Quick Prep Checklist

- [ ] Prepare 3–5 stories using STAR format: delivery failure, team conflict, hiring decision, technical trade-off, scaling challenge
- [ ] Revisit architecture of your most recent large system
- [ ] Practice HLD: Notification System (30-min verbal walkthrough)
- [ ] Practice HLD: Event Throttling / Rate Limiter
- [ ] Practice HLD: Distributed Cache
- [ ] Practice LLD: In-Memory SQL (write partial working Java/Python code)
- [ ] Know Razorpay's 5 culture values cold
- [ ] Prepare 5–6 sharp questions to ask each interviewer
- [ ] Research Razorpay's products: Payments, Payouts, Payment Pages, Subscriptions, Capital, RazorpayX
