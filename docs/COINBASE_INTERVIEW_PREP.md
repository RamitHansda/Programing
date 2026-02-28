# Coinbase Interview Prep: Domain & Technical Execution

Prep guide for **domain** and **technical execution** interviews at Coinbase (software engineering). Process and expectations are based on current public info and official blog posts; adjust for your specific role/team.

---

## 1. Process Overview

| Stage | What to expect |
|-------|----------------|
| **Application** | ~5% pass; they want high-impact work, clear communication, distributed systems or fintech experience. |
| **Recruiter screen** (~30 min) | Interest, motivation, crypto familiarity, role/level. Know mission and culture. |
| **CodeSignal** (70 min) | 4 questions: 1 warm-up + 3 LeetCode medium. Data structures & algorithms. |
| **Pair programming** (1–2 × 60 min) | **Where many fail.** Production-quality code + approach. Shared editor (CoderPad/HackerRank). |
| **System design** (1–2 × 60–90 min) | Scalable, secure, reliable systems; crypto/trading/financial context. |
| **EM / Behavioral** | Culture fit, ownership, past behavior. Often with hiring manager. |

**Timeline:** ~60–90 days. Teams can run their own funnels; you might do multiple team loops.

---

## 2. Domain Interview Prep

Domain discussions often happen in system design, EM, or recruiter conversations. Show you understand **crypto/finance, compliance, and reliability**.

### 2.1 Crypto & Trading Basics

- **Order book:** Bids/asks, price-time priority, matching engine.  
  **Use your doc:** `docs/lld/order-book/HLD.md` — know it cold (price-time, FIFO at same price, match flow, outputs).
- **Limit vs market orders:** Limit = price + size; market = fill at best available (often modeled as aggressive limit).
- **Spread, depth, liquidity:** Best bid/ask, depth by level, why liquidity matters for execution and slippage.
- **Settlement vs execution:** Execution = match; settlement = moving assets (on-chain or internal ledger). Know the difference.
- **Blockchain basics:** Transactions, blocks, consensus (high level), why immutability and finality matter for custody and compliance.

### 2.2 Security & Compliance

- **Custody:** Private keys, HSM, multi-sig, cold/hot wallets. “Not your keys, not your coins.”
- **Auditability:** Immutable logs, audit trails, who did what when. Align with pagination/consistency (cursor-based lists for history).
- **Regulation:** KYC/AML, sanctions, jurisdiction. They care that you think about compliance in design (e.g. logging, retention, access control).
- **Data integrity:** No silent data loss, consistent state under failures, idempotency for critical operations.

### 2.3 Scale & Reliability (Fintech Lens)

- **Consistency:** Strong consistency where money/balances are concerned; eventual consistency only where acceptable.
- **High availability:** Failover, no single point of failure, recovery time/objectives.
- **Latency:** Matching and critical paths need low latency; batch vs real-time trade-offs.

**Good one-liner:** “At Coinbase we’re building financial infrastructure; correctness, auditability, and security are non-negotiable.”

---

## 3. Technical Execution (Coding) Prep

Execution interviews judge **approach + production-quality code**, not just “does it pass tests.”

### 3.1 What They Evaluate

- **Clarity:** State problem, constraints, and approach before coding.
- **Communication:** Think out loud; say when you’re optimizing or handling edge cases.
- **Code quality:** Readable names, small functions, minimal duplication. Code they’d be okay merging.
- **Correctness:** Edge cases (empty input, one element, duplicates), then optimize.
- **Complexity:** Big-O time and space; justify choices.

### 3.2 Topic Mix

| Area | Examples |
|------|----------|
| **Arrays / Hashing** | Two sum, subarrays, frequency counts, sliding window |
| **Trees / Graphs** | BFS/DFS, traversal, path problems, level-order |
| **Linked lists** | Reverse, merge, cycle detection, dummy nodes |
| **Heaps / PQ** | Top K, merge K sorted, streaming median |
| **DP** | Classic (coin change, LCS, etc.) and 1D/2D |
| **Domain-flavored** | Ordering/events, rate limiting, idempotency, Merkle/hash chains (if they go there) |

**Level:** Mostly **medium**; some easy, occasional hard. Practice on LeetCode/CodeSignal with time limits.

### 3.3 Execution Habits

1. **Clarify:** Input format, range of values, uniqueness, allowed space/time.
2. **Examples:** Walk through 1–2 examples (including edge cases) before coding.
3. **Plan:** Brief approach (e.g. “two pointers,” “BFS”), then code.
4. **Code:** Write clean code first; refactor if you have time.
5. **Test:** Run your examples mentally or on the platform; mention other tests you’d add.

---

## 4. System Design Prep

Designs should be **scalable, secure, and compliant**, not just technically clever.

### 4.1 Recurring Themes

- **Cryptocurrency exchange / order book:** Matching engine, persistence, APIs, market data.  
  **Use:** `docs/lld/order-book/HLD.md` for order book and matching; extend to APIs, storage, and scaling.
- **Transaction history / list APIs:** Large datasets, consistent pagination, auditability.  
  **Use:** `docs/QUERY_PAGINATION_LARGE_DATASETS_DESIGN.md` for cursor/keyset pagination, consistency, indexing.
- **Blockchain indexing / multi-chain:** Ingest, index, query by address/tx; fault isolation per chain.
- **Custody / key management:** Secure storage, access control, audit logs, HSM/signing flow.

### 4.2 Structure Your Answer

1. **Requirements:** Functional (e.g. place/cancel order, list trades), scale (QPS, data size), latency, consistency, compliance.
2. **High-level:** Services, data flow, where orders/trades are stored and how they’re consumed.
3. **Key components:** APIs, matching engine, DB/cache, queues (e.g. Kafka), monitoring.
4. **Data model:** Orders, trades, book state; primary keys, indexes, partitioning.
5. **Scaling & failure:** Sharding, replication, failover, backpressure.
6. **Security & compliance:** Auth, audit logging, retention, integrity checks.

### 4.3 Tech Stack (Mention When Relevant)

- **Matching / hot path:** In-memory (e.g. Redis or custom structure), single-writer per symbol.
- **Persistence:** PostgreSQL (transactions, consistency), Cassandra if they ask for very high write scale.
- **Streaming:** Kafka (orders, trades, events).
- **Monitoring:** Metrics (e.g. Prometheus), dashboards (e.g. Grafana), alerting.

---

## 5. Behavioral & Culture

**Tenets:** Clear communication, efficient execution, act like an owner, top talent.

### 5.1 STAR Stories to Have Ready

- **Ownership:** Shipped something end-to-end; fixed a problem outside your immediate scope.
- **Efficient execution:** Delivered impact under time or resource constraints; prioritized ruthlessly.
- **Clear communication:** Aligned stakeholders, wrote docs that unblocked others, simplified a complex system.
- **High standards:** Raised the bar (code review, design, testing, hiring).
- **Ambiguity:** Made progress with unclear requirements; got alignment.
- **Conflict / disagreement:** Resolved a technical or product disagreement constructively.
- **Failure / learning:** Mistake you owned and what you changed.

Use **STAR:** Situation → Task → Action → Result (and what you’d do differently if relevant).

### 5.2 Know the Company

- **Mission:** Increase economic freedom (e.g. open financial system).
- **Product:** Retail and institutional crypto products; custody, trading, staking, etc.
- **Why Coinbase:** Tie your motivation to mission, product, or technical challenges (scale, security, compliance).

---

## 6. Prep Checklist

### Week-by-week (adjust to your timeline)

- [ ] **Recruiter / domain:** Read Coinbase blog (culture, “how we interview”); rehearse “why Coinbase” and 2–3 crypto/trading talking points.
- [ ] **CodeSignal:** 10–15 LeetCode medium (arrays, trees, graphs, DP); do 1–2 timed CodeSignal-style sets (4 problems, ~70 min).
- [ ] **Execution:** 5–10 mock pair-programming sessions; focus on talking while coding and clean, readable code.
- [ ] **System design:** Design “exchange order book” and “transaction history API with pagination” using your HLD and pagination docs; add security/compliance bullet points.
- [ ] **Behavioral:** Write 4–5 STAR stories; practice out loud in 2–3 minutes each.

### Day before

- [ ] Re-read `docs/lld/order-book/HLD.md` and `docs/QUERY_PAGINATION_LARGE_DATASETS_DESIGN.md` (at least summaries).
- [ ] Review complexity of your main data structures (arrays, trees, graphs, heaps).
- [ ] Sleep well; have a quiet, stable setup for remote rounds.

---

## 7. Quick Reference: Your Docs

| Doc | Use in interview |
|-----|-------------------|
| `docs/lld/order-book/HLD.md` | Order book, price-time priority, matching engine, architecture. |
| `docs/QUERY_PAGINATION_LARGE_DATASETS_DESIGN.md` | List APIs, cursor pagination, consistency, scale, auditability. |
| `docs/lld/protocol-adapters/` | Good for “integrate multiple chains or protocols” style design. |

---

## 8. Links (for deeper prep)

- Coinbase blog: “How Coinbase interviews for engineering roles” / “How to interview at Coinbase”
- LeetCode: Medium tag; “Coinbase” or “crypto” for domain-flavored problems if available
- System design: “Design a cryptocurrency exchange,” “Design order book,” “Design transaction history API”

Good luck. Focus on **clarity, execution quality, and domain-aware design**; your existing design docs put you in a strong position for the system design and domain discussions.
