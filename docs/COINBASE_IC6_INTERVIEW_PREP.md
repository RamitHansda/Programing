# Coinbase IC6 Interview Prep: Tech Execution & Domain Round

Prepared from research on Coinbase’s process, IC6 expectations, and reported interview content. Use this to focus your prep; formats can vary by team and year.

---

## Staff Engineer (IC6): What to Mention & What to Maintain

### What to **Mention** (in interviews and in your narrative)

As Staff, they expect you to show **scope beyond code** and **ownership of outcomes**. Have 1–2 concrete stories ready.

| Area | What to mention |
|------|------------------|
| **Scope & impact** | Projects that spanned multiple teams or systems; “I owned the outcome for X, which affected Y and Z.” |
| **Ambiguity** | Times you had unclear requirements or conflicting priorities; how you clarified, got alignment, and drove a decision. |
| **Tech decisions** | Non-trivial choices (e.g. consistency vs latency, build vs buy); trade-offs considered and why you chose what you did. |
| **Cross-team** | Aligning with product, security, compliance, or other eng teams; unblocking others. |
| **Mentoring & leveling** | Helping others grow (design review, pairing, docs); raising the bar for the team. |
| **Operational ownership** | On-call, incidents, postmortems; improving reliability, monitoring, or runbooks. |
| **Standards & quality** | Introducing or maintaining patterns, docs, or practices that others follow. |

**In the room:** When answering design or coding questions, briefly tie your approach to “in production I’d also…” (monitoring, rollout, rollback, docs)—that signals Staff-level thinking.

### What to **Maintain** (in the role)

Staff engineers are expected to **maintain** these, not only do them once:

| Maintain | Why it matters at Staff |
|----------|-------------------------|
| **Technical standards** | Code review bar, naming, testing, and design patterns so the codebase stays consistent and reviewable. |
| **Documentation** | ADRs, runbooks, architecture diagrams, and “how we do X” so the team and new hires can operate without you. |
| **System health & observability** | Meaningful metrics, SLOs, alerts, and dashboards; you notice when something is degrading. |
| **Incident readiness** | Runbooks, playbooks, and blameless postmortems; you improve reliability over time. |
| **Clarity of direction** | Roadmap alignment, tech debt visibility, and “what we’re not doing and why.” |
| **Talent and culture** | Hiring bar, feedback, and psychological safety so the team can do its best work. |
| **Stakeholder trust** | Realistic commitments, clear communication, and follow-through so product/leadership can depend on eng. |

**One-liner:** As Staff you **mention** scope, impact, ambiguity, and ownership in your stories; you **maintain** standards, docs, system health, and team effectiveness so the org can scale.

---

## Part 1: Tech Execution Round (IC6)

### What This Round Is

- **Format:** 60–90 min **pair programming** (e.g. CoderPad, HackerRank). You code live with an engineer.
- **Level:** IC6 = Staff Engineer (8–15 yrs). They expect strong execution and judgment, not only correct answers.
- **Critical point:** Most candidates who don’t get an offer fail in the **pair programming** stages. This round is make-or-break.

### What They Actually Evaluate

From Coinbase’s own blog and candidate reports:

| They care about | They don’t only care about |
|------------------|----------------------------|
| **Process** – how you get to the solution | Getting the “optimal” LeetCode answer |
| **Production-grade code** – readable, maintainable, testable | One-liners or clever hacks |
| **Clarifying requirements** before coding | Jumping straight into code |
| **Using the debugger** and tooling when stuck | Changing random lines until it works |
| **Idiomatic code**, naming, structure | Just “it works” |
| **Tests / reasoning about correctness** | No tests or edge-case discussion |
| **Taking feedback** and iterating | Ignoring hints or defending bad code |

**Coinbase cultural tenets they watch for:** Clear communication, efficient execution, act like an owner, top talent, championship team, customer focus, repeatable innovation, positive energy, continuous learning.

### Example: How They Judge Two Solutions

**Problem (from Coinbase blog):**  
*Given a list of integers 1 to n-1 (unsorted), one integer appears twice. Find that integer.*

- **Candidate A:** Sorts, iterates, has a bug and wrong spec (e.g. “first duplicate” vs “the duplicate”). But: clear structure, docstring, error handling, tests, readable names. **Result: pass.**  
- **Candidate B:** Correct O(1) space math solution (`sum - n*(n+1)/2`) but sloppy formatting, typos, no tests, cryptic names. **Result: fail.**

Takeaway: **Code quality and process beat cleverness.**

### Tech Execution: Topics to Be Solid On

- **Data structures:** Arrays, hashmaps, sets, stacks, queues, trees, graphs.
- **Algorithms:** Recursion, sorting, two pointers, sliding window, BFS/DFS, basic DP.
- **Complexity:** Reason about time/space; naive but correct is often enough.
- **Fintech/crypto-flavored (if they lean that way):**  
  Transaction/ledger validation, rate limiting, duplicate or fraud-like detection, hash-based checks, event ordering.

### Practice Questions (Tech Execution Style)

Aim for **production-style** code: clear names, one responsibility per function, edge cases, 1–2 tests or at least “how I’d test this.”

1. **Find duplicate in 1..n-1 list** (official example)  
   - Clarify: exactly one duplicate? Return value vs index?  
   - Implement with clear structure and a simple test.

2. **Validate a chain of records**  
   - Each record has `id`, `prev_id`. Check that `prev_id` links correctly from first to last.  
   - Handle cycles, missing links, empty input.

3. **API rate limiter**  
   - N requests per user per second.  
   - Sliding window or fixed window; discuss trade-offs.

4. **Double-spend in a ledger**  
   - List of `(user_id, amount, +/−)`. Detect if any user’s balance goes negative.

5. **Max profit one buy/sell** (from price array)  
   - Classic greedy; write cleanly with a short comment on why it’s correct.

6. **Fraud-style pattern**  
   - Same user, multiple withdrawals in 1 minute exceeding $X.  
   - Sliding window + per-user aggregation.

7. **Duplicate in matrix**  
   - Find duplicate value(s) in a 2D structure; discuss time/space.

### Day-of Tips (Tech Execution)

- **Clarify first:** Input format, edge cases, return type, “first” vs “any” duplicate, etc.
- **Think out loud:** “I’ll use a set to track seen elements…” so they see your reasoning.
- **Start with a simple, correct solution** before optimizing.
- **Use the debugger** if you’re stuck; say “I’ll add a breakpoint here to check…”
- **Write 1–2 test cases** (or describe them) to show you care about correctness.
- **If you’ve seen the problem:** Say so; they may switch questions.
- **Cam on, good setup:** They expect camera on and a stable connection.

---

## Part 2: Domain Round

### What This Round Is

- **Format:** Often 60–90 min **system design / architecture** with a **domain** twist (crypto, exchange, custody, indexing, etc.).
- **Goal:** See if you can design **secure, scalable, compliant** systems that fit a fintech/crypto context.

### What They Evaluate

- **Trade-offs:** Consistency vs availability, SQL vs NoSQL, sync vs async, security vs latency.
- **Compliance & risk:** Auditability, encryption, secure logging, KYC/AML considerations.
- **Operational readiness:** Monitoring, alerting, failover, recovery, rollback.
- **Domain awareness:** Order books, custody, blockchain indexing, price feeds, fraud—without requiring deep crypto expertise (they value willingness to learn).

### How to Structure Your Answer

1. **Requirements & constraints**  
   Scale (QPS, data size), latency, consistency, compliance, regions.
2. **High-level architecture**  
   Services, data flow, main components (API, matching, storage, queues).
3. **Component deep dives**  
   APIs, storage, queues, caching, auth, security.
4. **Scaling & fault tolerance**  
   Sharding, replication, leader election, circuit breakers.
5. **Monitoring & auditability**  
   Logging, metrics, alerts, audit trail.

### Domain / System Design Question Bank

Use these as **practice prompts**. For each, run through the structure above and add: security, compliance, and observability.

#### 1. Cryptocurrency exchange order book

- **Goal:** Buy/sell orders, matching, atomic balance updates.
- **Discuss:** Data consistency and recovery, matching algorithm (price-time priority), concurrency, idempotency.
- **Tech ideas:** PostgreSQL/Cassandra for persistence, Redis for in-memory matching, Kafka/RabbitMQ for orders, event sourcing for audit, Prometheus/Grafana.

#### 2. Blockchain transaction indexing service

- **Goal:** Ingest, store, and query transactions for multiple chains.
- **Discuss:** Fault isolation per chain, real-time sync with nodes, query patterns, backfill/reprocessing.
- **Tech ideas:** Kafka Streams for ingestion, Elasticsearch for search, S3/Glacier for archive, gRPC/WebSocket to nodes.

#### 3. Crypto custody system

- **Goal:** Secure storage and controlled use of private keys.
- **Discuss:** Cold/hot separation, HSM/KMS, access control, multi-sig, audit trail.
- **Tech ideas:** Vault/HSM, multi-sig flows, audit logging, key rotation (e.g. Airflow jobs).

#### 4. Global price aggregation service

- **Goal:** Ingest and serve crypto prices from multiple exchanges in real time.
- **Discuss:** Deduplication, staleness, failure of one exchange, regional latency.
- **Tech ideas:** Kafka/Flink for streams, Redis for caching, load balancers, ClickHouse for analytics.

#### 5. Fraud detection and risk analysis

- **Goal:** Real-time detection of suspicious transaction patterns.
- **Discuss:** Feature store, model serving latency, stream vs batch.
- **Tech ideas:** Kafka/Flink/Spark Streaming, PostgreSQL + Elasticsearch for features, ML serving (e.g. TensorFlow Serving), dashboards.

#### 6. Secure API gateway for financial transactions

- **Goal:** Third-party trading/wallet APIs with strict security.
- **Discuss:** Auth (OAuth2/JWT), rate limiting, signing, tenant isolation, encryption.
- **Tech ideas:** API Gateway + WAF, KMS, Redis for sessions, SIEM/CloudTrail-style logging.

### Domain Concepts Worth Knowing (High Level)

- **Order book:** Bid/ask, price-time priority, matching engine.
- **Custody:** Hot/cold wallets, HSMs, multi-sig.
- **Blockchain basics:** Transactions, blocks, hashes, chain validation (helps for “indexing” and “integrity” discussions).
- **Compliance:** Audit logs, non-repudiation, KYC/AML as constraints on design.
- **Consistency:** Strong consistency for balances, eventual consistency where acceptable; how you’d achieve it (e.g. distributed transactions, saga, idempotency).

### Domain Round Tips

- **Ask clarifying questions:** Scale, latency SLA, compliance requirements, geographic scope.
- **Say “I don’t know” when you don’t:** Propose how you’d find out (RFC, spike, ask security team). Honesty > confident wrongness.
- **One deep example:** “For the database I’d lean toward PostgreSQL because of ACID and audit needs; I’ve used it for similar financial workloads…”
- **Always tie back to:** Security, auditability, and recovery.

---

## Recently Reported Questions (2024–2026)

*Compiled from 1Point3Acres, Glassdoor, Blind, Final Round AI, CodingInterview.com, Lodely, and interview prep guides. Use for domain + system design + coding prep; exact wording and round vary by team.*

### Domain / System Design (recently reported)

| Question / topic | Source / period | Notes |
|------------------|-----------------|--------|
| **Cryptocurrency exchange order book** | Recurring | Matching, atomic balance updates, consistency, recovery. |
| **Design a Crypto Exchange Order Flow System** | 1P3A Oct–Dec 2025 | End-to-end order flow, APIs, matching, persistence. |
| **Crypto Order System** | 1P3A Oct–Dec 2025 | Order lifecycle, validation, state. |
| **Crypto Order Event Processing** | 1P3A Oct–Dec 2025 | Event-driven order handling, ordering, idempotency. |
| **Design Coinbase Explore Realtime Market Data** | 1P3A Oct–Dec 2025 | Real-time prices, aggregation, websockets, caching. |
| **Blockchain transaction indexing service** | Recurring | Multi-chain ingest, query, fault isolation, backfill. |
| **Crypto custody system** | Recurring | Keys, HSM, cold/hot, multi-sig, audit. |
| **Design a Credit Approval Risk Engine** | 1P3A Oct–Dec 2025 | Risk scoring, rules, compliance, auditability. |
| **Global price aggregation service** | Recurring | Multi-exchange feeds, dedup, staleness, regional latency. |
| **Fraud detection and risk analysis** | Recurring | Real-time patterns, feature store, ML serving. |
| **Secure API gateway for financial transactions** | Recurring | Auth, rate limit, signing, tenant isolation. |
| **How long to send a signal from one computer to all others?** | Final Round AI (Coinbase) | Network topology, bandwidth, latency; estimation + assumptions. |
| **Cloud File System** | 1P3A Oct–Dec 2025 | Storage, consistency, metadata, scale. |
| **Task Management System** | 1P3A Oct–Dec 2025, 2026 OA | Tasks, state, concurrency; sometimes in OA. |
| **Bank System** | 1P3A Oct–Dec 2025 | Balances, transactions, consistency, audit. |
| **Query Pagination** | 1P3A Oct–Dec 2025 | Large lists, cursor/keyset, consistency (align with your pagination doc). |
| **In-Memory Database** | 1P3A Oct–Dec 2025 | Fast reads/writes, durability, recovery. |
| **NFT Feature Generation** | 1P3A Oct–Dec 2025 | Domain-specific feature pipeline. |
| **Food Delivery System** | 1P3A Oct–Dec 2025 | General LLD; orders, matching, status. |
| **Recipe Manager** | 1P3A Oct–Dec 2025 | CRUD, search, scale. |

### Tech Execution / Pair Programming (recently reported)

| Question / topic | Source / period | Notes |
|------------------|-----------------|--------|
| **Find duplicate in 1..n-1 list** | Coinbase blog (official example) | Exactly one duplicate; they care about clarity, tests, structure. |
| **Validate blockchain transaction integrity** | CodingInterview, recurring | Chain of records with `hash`/`prev_hash`; verify linkage. |
| **Detect double spending in a ledger** | Recurring | Ledger of (user, amount, +/−); detect negative balance. |
| **API rate limiter** | Recurring | N requests per user per second; sliding vs fixed window. |
| **Max profit one buy/sell (price array)** | Recurring / OA 2025 | Classic greedy; clean code + correctness. |
| **Fraud-style: withdrawals in 1 min exceeding $X** | Recurring | Sliding window + per-user aggregation. |
| **Validate chain of records** | Your existing prep | `id`, `prev_id`; cycles, missing links, empty. |
| **Secure password hashing (salted)** | CodingInterview | Storage, salt, hash; security-aware coding. |
| **Block Mining (problem details)** | 1P3A 2026 | Mining/consensus-flavored; clarify problem statement. |
| **Mining Block** | 1P3A Oct–Dec 2025 | Similar theme. |
| **Interleave Iterator** | 1P3A Oct–Dec 2025 | Iterator pattern, multiple sequences. |
| **Log File Parser** | 1P3A Oct–Dec 2025 | Parsing, structure, edge cases. |

### Online Assessment (OA) question types (2025)

*Often 2–3 problems in ~90 min (HackerRank/CodeSignal).*

| Type | Example / LeetCode-style |
|------|---------------------------|
| Sliding window / array | Most profitable window (buy/sell once), longest substring with at most K distinct |
| Stack / string | Balanced parentheses, **with wildcards** (valid parenthesis string) |
| Graph | Detect cycle (e.g. course schedule), shortest path with edge failures (Dijkstra) |
| Heap / merge | Merge K sorted arrays (or lists) |
| DP | Buy/sell stock with K transactions |
| Math / modular | Power of large numbers, modular exponentiation, Sherlock and permutations |
| Iterator | Iterator pattern, skip iterator |
| Search / partition | Median of two sorted arrays, k-th element |

### IC6 domain round: what they're really testing

- **Trade-offs:** Consistency vs availability, SQL vs NoSQL, security vs latency.
- **Compliance & risk:** Auditability, encryption, secure logging, KYC/AML as constraints.
- **Operational readiness:** Monitoring, alerting, failover, recovery, rollback.
- **Domain awareness:** Order books, custody, indexing, price feeds, fraud—without requiring deep crypto; show willingness to learn and reason from first principles.

---

## Quick Checklist Before the Interview

### Tech Execution

- [ ] IDE and debugger comfortable (breakpoints, step-through).
- [ ] Can write 1–2 test cases or describe edge cases in 30 seconds.
- [ ] Practiced 3–5 problems with “production” style (names, structure, error handling).
- [ ] Read Coinbase culture tenets and thought of 1–2 examples where you demonstrated them.

### Domain Round

- [ ] Can outline: requirements → high-level architecture → 2–3 component deep dives → scaling → monitoring.
- [ ] Prepared 2–3 system designs (e.g. order book + one of custody/indexing/fraud).
- [ ] Thought about trade-offs: consistency vs availability, security vs latency.
- [ ] Prepared 2–3 questions to ask them (team, scale, tech stack, how they do custody/compliance).

---

## Resources

- [How Coinbase interviews for engineering roles](https://www.coinbase.com/blog/how-coinbase-interviews-for-engineering-roles) (official).
- [Coinbase culture tenets](https://blog.coinbase.com/culture-at-coinbase-f0e1c2a99aff).
- Coinbase Engineering Blog (scaling, APIs, security).
- *Designing Data-Intensive Applications* (consistency, replication, durability).

Good luck. Focus on **clear communication**, **production-quality execution**, and **structured domain thinking**; that aligns with what they say they value for IC6 and domain rounds.
