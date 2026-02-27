# Coinbase IC6 Interview Prep: Tech Execution & Domain Round

Prepared from research on Coinbase’s process, IC6 expectations, and reported interview content. Use this to focus your prep; formats can vary by team and year.

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
