# Coinbase Domain Round: Focused Prep

Use this with **COINBASE_IC6_INTERVIEW_PREP.md** (Part 2) and **COINBASE_DOMAIN_SYSTEMS_HLD.md**. This doc is your **day-of** structure and talking-point cheat sheet for the domain/system design round.

---

## 1. Answer Framework (Use Every Time)

Run through this in order. Time-box: ~2 min requirements, ~5 min high-level, ~15–20 min deep dives, ~5 min scaling + monitoring.

| Step | What to say / do | Time |
|------|-------------------|------|
| **1. Clarify** | Scale (QPS, users, data size), latency SLA, consistency needs, compliance (audit, KYC/AML), regions. | 2 min |
| **2. High-level** | One diagram: clients → gateway/ingest → core service(s) → storage + events. Name 4–6 boxes. | 5 min |
| **3. Deep dive (pick 2–3)** | API/auth, core algorithm or data model, storage, queues, or security. Trade-offs in each. | 15–20 min |
| **4. Scale & fault tolerance** | Sharding, replication, failover, backfill/recovery. | 3 min |
| **5. Monitoring & compliance** | Metrics, SLOs, alerts, audit log, non-repudiation. | 3 min |

**Always tie back:** Security, auditability, recovery. Say “we’d have an immutable audit log” or “strong consistency for balances” so they hear fintech awareness.

---

## 2. Trade-offs Cheat Sheet

Have one sentence ready for each. They will ask “why X over Y?”

| Trade-off | Fintech-leaning answer |
|-----------|------------------------|
| **Consistency vs availability** | “For balances and orders we need strong consistency (single writer or distributed TX); for market data or search we can accept eventual consistency.” |
| **SQL vs NoSQL** | “PostgreSQL for orders, trades, ledger—ACID and audit; Cassandra/NoSQL for high write throughput and partition by user/symbol where we can tolerate eventual consistency.” |
| **Sync vs async** | “Order path: sync until we persist and ack; downstream (analytics, notifications) async via Kafka so we don’t block the critical path.” |
| **Security vs latency** | “Auth and signature verification at gateway—minimal added latency; heavy checks (AML, risk) async or in parallel so we don’t block the happy path.” |
| **Hot vs cold (custody)** | “Hot for frequent, low-value; cold for high-value, offline; policy and audit on both.” |

---

## 3. Six Systems: 60-Second Pitch + Must-Say Points

For each system, memorize the **one-line flow** and the **must-say** bullets. Use your HLD for diagrams and detail.

### 3.1 Cryptocurrency Exchange / Order Book

- **One-line:** Gateway (validate, idempotency) → Matching engine (price-time, in-memory) → Persistence + Ledger (atomic) + Event bus.
- **Must-say:** Price-time priority; single writer per symbol; **idempotency keys** to prevent duplicate orders; **atomic** balance updates on fill; **audit** via immutable order/trade log; recovery by replay from Kafka/DB.

### 3.2 Blockchain Transaction Indexing

- **One-line:** Chain adapters (per chain, normalize) → Kafka (partition by chain) → Indexers (by address, by tx) → Stores + Query API.
- **Must-say:** **Fault isolation per chain** (one chain’s delay doesn’t block others); **reorg handling** (rewind, reprocess); backfill from checkpoint; expose “indexed up to block N” for consistency; Cassandra/Postgres by tx, Elasticsearch for search.

### 3.3 Crypto Custody

- **One-line:** Policy/approval → KMS (key ref only) → HSM (hot/cold) for signing → Audit log for every use.
- **Must-say:** **Key never leaves HSM** (sign inside HSM); hot vs cold (online vs offline); **multi-sig** for large withdrawals; **audit** every sign (who, what, when, result); KMS maps wallet to key ref, not raw key.

### 3.4 Global Price Aggregation

- **One-line:** Exchange adapters (normalize) → Kafka → Aggregator (window, mid/median/VWAP) → Redis (latest) + API.
- **Must-say:** **Per-exchange isolation** (one adapter per exchange); handle **staleness** (mark unhealthy, exclude from aggregate); **regional** cache replicas for latency; normalization of symbols and timestamps.

### 3.5 Fraud Detection and Risk

- **One-line:** Kafka (tx events) → Feature store + stream (enrich) → Rules + ML score → Decision (block/review/allow) → Audit + downstream.
- **Must-say:** **Real-time** scoring on critical path; **rule engine** for hard blocks (e.g. sanctions); **audit** every decision (features + score) for compliance; partition by user_id for scale; explainability where required.

### 3.6 Secure API Gateway

- **One-line:** Edge + WAF → Auth → Signature verify → Rate limit → Route → Audit.
- **Must-say:** **Request signing** (HMAC/RSA) + **timestamp** to prevent replay; **rate limit** per key/user (Redis); **tenant isolation**; **audit** every request (non-repudiation); API keys in KMS, never log full key.

---

## 4. Domain Concepts to Define If Asked

Keep definitions to 1–2 sentences.

- **Order book:** Bids and asks; best bid = highest buy, best ask = lowest sell; **price-time priority** = best price first, then FIFO.
- **Matching engine:** Single-writer per symbol; matches incoming vs resting orders; emits trades and book updates.
- **Hot vs cold wallet:** Hot = online, fast, lower balance; cold = offline/HSM, high value, batch or manual.
- **HSM:** Hardware Security Module; keys stored and used inside device; key material never leaves.
- **Multi-sig:** M-of-N signatures required to authorize (e.g. withdrawal).
- **Idempotency key:** Client sends key with request; server deduplicates so same key = at-most-once processing (critical for orders).
- **Reorg (blockchain):** Chain tip changes; indexer must rewind and reprocess from last good block.
- **KYC/AML:** Compliance constraints; identity and anti–money laundering; design for auditability and blocking known bad actors.

---

## 5. Practice Prompts (Time Yourself)

For each prompt, run the full framework (requirements → architecture → 2–3 deep dives → scaling → monitoring) and hit security + audit.

1. **Design the order flow for a crypto exchange** (placement, matching, balance update).
2. **Design a system to index blockchain transactions** for multiple chains and support “all tx for address X.”
3. **Design a custody system** for secure storage and signing of private keys (hot and cold).
4. **Design a real-time price aggregation service** that combines feeds from multiple exchanges.
5. **Design a fraud detection pipeline** that scores transactions in real time and blocks or flags.
6. **Design a secure API gateway** for third-party trading and wallet access (auth, rate limit, signing).

---

## 6. Day-of Checklist (Domain Round)

- [ ] Can draw high-level diagram in 4–6 boxes and name data flow for **order book** and **one other** (custody or indexing or fraud).
- [ ] Can state **one trade-off** (consistency vs availability, SQL vs NoSQL, or security vs latency) in one sentence.
- [ ] Can define **order book**, **idempotency**, **hot/cold**, **HSM** in 1–2 sentences.
- [ ] Prepared **2–3 questions** for them (e.g. how they do custody, scale of order book, tech stack for indexing).
- [ ] If stuck: say “I’d clarify X with the team” or “I’d prototype and measure”; never make up crypto specifics.

---

## 7. Quick Reference: System ↔ Core Tech

| System | Storage / state | Events / async | Security / compliance |
|--------|------------------|----------------|------------------------|
| Order book | PostgreSQL (orders, trades, ledger) | Kafka (orders, trades, book) | Idempotency, audit log |
| Indexing | Cassandra/Postgres + Elasticsearch | Kafka (per chain) | Audit, reorg handling |
| Custody | KMS + HSM (no raw keys in app) | — | HSM, multi-sig, audit every sign |
| Price aggregation | Redis (latest), optional ClickHouse | Kafka/Flink | Staleness, source health |
| Fraud | Feature store (Postgres/Redis), model serving | Kafka, Flink/Spark | Audit decision + features |
| API gateway | Redis (rate limit, sessions) | — | Auth, signing, WAF, audit log |

Use this with **COINBASE_DOMAIN_SYSTEMS_HLD.md** for diagrams and component detail. Good luck.
