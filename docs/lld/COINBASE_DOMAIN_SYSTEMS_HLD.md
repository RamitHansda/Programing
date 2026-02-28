# High-Level Designs: Coinbase Domain Systems

**Audience:** Interview prep and engineers designing crypto/fintech systems.  
**Reference:** Order-book detail → `docs/lld/order-book/HLD.md`.

---

## 1. Cryptocurrency Exchange / Order Book

### 1.1 Problem & Scope

**Goal:** Run a **central limit order book (CLOB)** that accepts buy/sell orders, matches them with **price-time priority**, and updates balances **atomically**. Includes order gateway, matching engine, persistence, and balance/ledger consistency.

**Out of scope here:** Clearing/settlement, margin, market-data distribution (covered in your order-book HLD for book mechanics).

### 1.2 Core Concepts

| Concept | Description |
|--------|-------------|
| **Bid / Ask** | Buy side (bids) and sell side (asks); best bid = highest buy, best ask = lowest sell. |
| **Price-time priority** | Best price first; at same price, FIFO. |
| **Matching engine** | Single-writer per symbol; matches incoming vs resting orders; emits trades + book updates. |
| **Atomicity** | Order acceptance, match, and balance updates must be consistent (e.g. single DB transaction or event-sourced ledger). |

### 1.3 High-Level Architecture

```
┌─────────────┐     ┌──────────────────┐     ┌─────────────────────────────────────┐
│   Clients   │────►│  API Gateway      │────►│  Order Gateway                       │
│   (REST/WS) │     │  Auth, rate limit │     │  Validate, idempotency key, assign ID│
└─────────────┘     └──────────────────┘     └──────────────────┬──────────────────┘
                                                                  │
┌─────────────┐     ┌──────────────────┐     ┌───────────────────▼───────────────────┐
│  Market     │◄────│  Market Data     │◄────│  Matching Engine (per symbol)          │
│  Data Feed  │     │  Service         │     │  In-memory book, single-threaded match  │
└─────────────┘     └──────────────────┘     └───────────────────┬───────────────────┘
                                                                  │
         ┌────────────────────────────────────────────────────────┼────────────────────┐
         │                          │                              │                    │
         ▼                          ▼                              ▼                    ▼
┌─────────────────┐    ┌─────────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│  Order Store     │    │  Trade / Fill Store  │    │  Balance /       │    │  Event Bus       │
│  (persistence)   │    │  (persistence)       │    │  Ledger Service  │    │  (Kafka)         │
│  PostgreSQL      │    │  PostgreSQL          │    │  Atomic debits   │    │  Orders, trades, │
└─────────────────┘    └─────────────────────┘    │  Credits         │    │  book events     │
                                                    └─────────────────┘    └─────────────────┘
```

### 1.4 Main Components

| Component | Responsibility |
|-----------|----------------|
| **API Gateway** | Auth (JWT/OAuth), rate limit, request validation. |
| **Order Gateway** | Idempotency (idempotency key → at-most-once), assign `orderId`, validate size/price. |
| **Matching Engine** | In-memory book (e.g. Redis or dedicated process); price-time match; emit fills + book deltas. |
| **Order / Trade Store** | Durable persistence (PostgreSQL); audit trail; replay for recovery. |
| **Balance / Ledger Service** | Per-user per-currency balances; atomic debit/credit on fill; strong consistency. |
| **Event Bus (Kafka)** | Orders, trades, book events for downstream (analytics, risk, market data). |

### 1.5 Data Flow

1. Client sends **place order** (side, price, qty, idempotency key).
2. Gateway validates, deduplicates, assigns ID, publishes to **order topic** or pushes to matching engine.
3. **Matching engine** runs price-time match; produces **trades** and **book updates**.
4. **Trades** → ledger service (atomic balance update); order/trade store (persist); event bus (publish).
5. **Book updates** → market data service → clients (WebSocket / REST).

### 1.6 Scaling & Fault Tolerance

- **Matching:** One writer per symbol (single process or shard); replicate book via event replay for standby.
- **Ledger:** Strong consistency (single primary or distributed TX); partition by user_id for scale.
- **Recovery:** Replay order + trade events from Kafka/DB to rebuild book and verify balances; checkpointing.

### 1.7 Security & Compliance

- **Audit:** Immutable order/trade log; who, what, when.
- **Idempotency:** Prevents duplicate orders and double-spend at gateway.
- **Auth:** API keys / OAuth; scope per product (e.g. trade vs read-only).

### 1.8 Summary

Exchange = **Order Gateway** (validate, idempotency) → **Matching Engine** (price-time, in-memory) → **Persistence + Ledger** (atomic balances) + **Event Bus** (audit, market data). See `docs/lld/order-book/HLD.md` for book/matching detail.

---

## 2. Blockchain Transaction Indexing Service

### 2.1 Problem & Scope

**Goal:** Ingest blockchain data from multiple chains, **index** transactions (by address, tx hash, block), and serve **low-latency queries** (e.g. “all tx for address X”, “tx by hash”). Must support backfill, reorgs, and fault isolation per chain.

### 2.2 Core Concepts

| Concept | Description |
|--------|-------------|
| **Chain adapter** | Per-chain client (RPC/WebSocket) to nodes; normalizes blocks/txs into common schema. |
| **Fault isolation** | One chain’s delay or failure does not block others. |
| **Eventual consistency** | Index may lag behind chain tip; expose “indexed height” or “last block” for clarity. |

### 2.3 High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│  Blockchain Nodes (Bitcoin, Ethereum, etc.) — RPC / WebSocket / gRPC                     │
└───────────────────────────────────────────┬─────────────────────────────────────────────┘
                                            │
        ┌───────────────────────────────────┼───────────────────────────────────┐
        │                   │               │               │                     │
        ▼                   ▼               ▼               ▼                     ▼
┌───────────────┐   ┌───────────────┐ ┌───────────────┐ ┌───────────────┐  ┌───────────────┐
│  Chain        │   │  Chain        │ │  Chain        │ │  ...          │  │  Chain         │
│  Adapter A    │   │  Adapter B    │ │  Adapter C    │ │               │  │  Adapter N     │
│  (BTC)        │   │  (ETH)        │ │  (SOL)       │ │               │  │  (other)       │
└───────┬───────┘   └───────┬───────┘ └───────┬───────┘ └───────┬───────┘  └───────┬────────┘
        │                   │               │               │                     │
        └───────────────────┴───────────────┴───────────────┴─────────────────────┘
                                            │
                            Normalized: block_id, chain_id, tx_hash, from, to, value, ...
                                            │
                                            ▼
                            ┌───────────────────────────────┐
                            │  Ingestion / Stream Layer      │
                            │  Kafka (topic per chain or     │
                            │  partitioned by chain_id)      │
                            └───────────────┬───────────────┘
                                            │
                    ┌───────────────────────┼───────────────────────┐
                    ▼                       ▼                       ▼
            ┌───────────────┐       ┌───────────────┐       ┌───────────────┐
            │  Indexer      │       │  Indexer      │       │  Search        │
            │  (by address) │       │  (by tx/blk)  │       │  (Elasticsearch)│
            │  e.g. Cassandra│      │  e.g. PG/     │       │  full-text,     │
            │  partition by │       │  Cassandra   │       │  filters        │
            │  address      │       │  by tx_hash  │       │                 │
            └───────┬───────┘       └───────┬───────┘       └───────┬───────┘
                    │                       │                       │
                    └───────────────────────┼───────────────────────┘
                                            │
                                            ▼
                            ┌───────────────────────────────┐
                            │  Query API                    │
                            │  GET /tx/:hash,               │
                            │  GET /address/:id/transactions│
                            │  GET /block/:height           │
                            └───────────────────────────────┘
```

### 2.4 Main Components

| Component | Responsibility |
|-----------|----------------|
| **Chain adapters** | Connect to chain nodes; poll or subscribe (new blocks); normalize to internal schema; publish to Kafka. |
| **Kafka** | Buffer and partition by chain (and optionally by block); backpressure; replay for reindex. |
| **Indexers** | Consume Kafka; write to store keyed by address, tx_hash, block; handle reorg (rewind + reprocess). |
| **Stores** | Cassandra/PostgreSQL for by-tx/block; Elasticsearch for search; S3/Glacier for cold/archive. |
| **Query API** | REST/gRPC; route to correct store; expose “indexed up to block N” for consistency. |

### 2.5 Data Flow

1. **Ingest:** Adapter fetches new blocks (poll or WebSocket), normalizes txs, publishes to Kafka (partition by chain).
2. **Index:** Consumers read from Kafka, upsert by tx_hash and by address; on reorg, invalidate and reprocess from last good block.
3. **Query:** API looks up by hash (KV) or by address (index scan); optional search via Elasticsearch.

### 2.6 Scaling & Fault Tolerance

- **Per-chain isolation:** Separate adapter + topic per chain; one chain’s backlog doesn’t block others.
- **Sharding:** Partition by `chain_id`; within chain, partition by address or block range.
- **Backfill / recovery:** Replay Kafka or re-fetch from node from checkpoint; idempotent writes (upsert by tx_hash).

### 2.7 Security & Compliance

- **Audit:** Log which blocks/txs were indexed and when; checksums for integrity.
- **Access control:** Query API auth; rate limits; PII/sanctions filtering if applied at index time.

### 2.8 Summary

**Blockchain indexing** = **Chain adapters** (per chain, normalize) → **Kafka** (buffer, partition by chain) → **Indexers** (by address, by tx, by block) → **Stores** (Cassandra/ES/Postgres) + **Query API**. Design for reorgs, backfill, and fault isolation per chain.

---

## 3. Crypto Custody System

### 3.1 Problem & Scope

**Goal:** **Securely store** private keys and **control** how they are used (signing only under policy). Support **hot** (online signing) and **cold** (offline, high-value) wallets; **multi-sig** and **audit** for every key use.

### 3.2 Core Concepts

| Concept | Description |
|--------|-------------|
| **Hot wallet** | Keys available for signing in an online system; lower balance; fast withdrawals. |
| **Cold wallet** | Keys offline (HSM or air-gapped); high balance; manual or batch signing. |
| **HSM** | Hardware Security Module: store keys, sign inside HSM; keys never leave. |
| **Multi-sig** | M-of-N signatures required; key shards or separate signers. |

### 3.3 High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│  Application Layer (Withdrawal / Transfer Requests)                                      │
└───────────────────────────────────────────┬─────────────────────────────────────────────┘
                                            │
                                            ▼
                            ┌───────────────────────────────┐
                            │  Policy & Approval Engine      │
                            │  Amount limits, M-of-N,        │
                            │  KYC/AML checks, allow/deny     │
                            └───────────────┬───────────────┘
                                            │ approved
                                            ▼
                            ┌───────────────────────────────┐
                            │  Key Management Service (KMS)  │
                            │  Map wallet_id → key ref       │
                            │  (key material in HSM/Vault)   │
                            └───────────────┬───────────────┘
                                            │
            ┌───────────────────────────────┼───────────────────────────────┐
            │                               │                               │
            ▼                               ▼                               ▼
    ┌───────────────┐               ┌───────────────┐               ┌───────────────┐
    │  Hot Path     │               │  Cold Path    │               │  Audit Log    │
    │  HSM (online) │               │  HSM (offline)│               │  Immutable    │
    │  Sign tx      │               │  or air-gapped│               │  who, what,   │
    │  rate-limited │               │  batch sign   │               │  when, result  │
    └───────────────┘               └───────────────┘               └───────────────┘
            │                               │
            └───────────────────────────────┴───────────────────────────────┘
                                            │
                                            ▼
                            ┌───────────────────────────────┐
                            │  Signed Tx → Broadcast         │
                            │  (to chain node / network)     │
                            └───────────────────────────────┘
```

### 3.4 Main Components

| Component | Responsibility |
|-----------|----------------|
| **Policy & approval** | Rules (amount, velocity, M-of-N); integrate KYC/AML; approve or reject. |
| **KMS** | Map wallet/key ID to HSM key reference; never expose raw private key. |
| **HSM (hot)** | Online signing; rate limits; key rotation via HSM APIs. |
| **HSM (cold)** | Offline or air-gapped; batch signing; manual or scheduled. |
| **Vault (optional)** | Secrets for API keys, not primary key material; can wrap HSM. |
| **Audit log** | Every sign request (who, wallet, amount, chain, result); immutable, append-only. |

### 3.5 Data Flow

1. App requests **withdrawal** (amount, destination, asset).
2. **Policy engine** checks limits, M-of-N, sanctions; if ok, creates **signing request**.
3. **KMS** resolves wallet → HSM key; sends **hash of tx** to HSM (never full key).
4. **HSM** signs; returns signature; **audit log** records request + result.
5. System assembles signed tx and broadcasts to chain.

### 3.6 Scaling & Fault Tolerance

- **HSM redundancy:** Multiple HSM appliances; failover; no single point of failure for hot path.
- **Cold:** Manual process; high availability less critical; focus on procedure and audit.

### 3.7 Security & Compliance

- **Key never leaves HSM:** Sign inside HSM; only hash or blinded data sent in.
- **Multi-sig:** M-of-N for large withdrawals; separate operators/systems.
- **Audit:** Non-repudiation; retention per regulation; alert on anomalies.

### 3.8 Summary

**Custody** = **Policy/approval** → **KMS** (key ref only) → **HSM (hot/cold)** for signing → **Audit log** for every use. Design for separation of hot/cold, HSM-centric key storage, and full auditability.

---

## 4. Global Price Aggregation Service

### 4.1 Problem & Scope

**Goal:** Ingest **crypto prices** from **multiple exchanges** (REST/WebSocket), **aggregate** (e.g. mid, VWAP, median), and **serve** low-latency prices to internal and external clients. Handle exchange outages, staleness, and regional latency.

### 4.2 Core Concepts

| Concept | Description |
|--------|-------------|
| **Source** | Each exchange’s feed (e.g. best bid/ask, last trade); different formats and latency. |
| **Normalization** | Map symbols and formats to internal pair (e.g. BTC-USD); timestamps in one clock. |
| **Aggregation** | Combine sources into one “consensus” price (mid, median, volume-weighted). |

### 4.3 High-Level Architecture

```
┌─────────────┐ ┌─────────────┐ ┌─────────────┐     ┌─────────────┐
│  Exchange A │ │  Exchange B │ │  Exchange C  │ ... │  Exchange N │
│  WS / REST  │ │  WS / REST  │ │  WS / REST  │     │  WS / REST  │
└──────┬──────┘ └──────┬──────┘ └──────┬──────┘     └──────┬──────┘
       │               │               │                    │
       └───────────────┴───────────────┴────────────────────┘
                                       │
                                       ▼
                       ┌───────────────────────────────┐
                       │  Adapters (per exchange)       │
                       │  Normalize symbol, price,      │
                       │  timestamp; health check        │
                       └───────────────┬───────────────┘
                                       │
                                       ▼
                       ┌───────────────────────────────┐
                       │  Stream Layer (Kafka / Flink)  │
                       │  Topic per pair or exchange    │
                       └───────────────┬───────────────┘
                                       │
                                       ▼
                       ┌───────────────────────────────┐
                       │  Aggregator                   │
                       │  Time window (e.g. 1s);       │
                       │  mid / median / VWAP;         │
                       │  drop stale or outlier        │
                       └───────────────┬───────────────┘
                                       │
               ┌───────────────────────┼───────────────────────┐
               ▼                       ▼                       ▼
       ┌───────────────┐       ┌───────────────┐       ┌───────────────┐
       │  Cache        │       │  Time-series  │       │  Query API    │
       │  Redis        │       │  DB (optional)│       │  REST / WS    │
       │  latest price │       │  ClickHouse   │       │  /prices/:pair │
       └───────┬───────┘       └───────────────┘       └───────┬───────┘
               │                                               │
               └───────────────────────────────┬───────────────┘
                                               │
                                               ▼
                               ┌───────────────────────────────┐
                               │  Clients (internal, API)      │
                               └───────────────────────────────┘
```

### 4.4 Main Components

| Component | Responsibility |
|-----------|----------------|
| **Adapters** | Per-exchange client; subscribe or poll; normalize to (pair, price, bid, ask, volume, ts); publish to Kafka. |
| **Stream layer** | Kafka (or Flink); buffer; optional windowing. |
| **Aggregator** | Per pair: collect prices in window; compute mid/median/VWAP; exclude stale or outlier; publish result. |
| **Cache** | Redis: latest aggregated price per pair; TTL; regional replicas for latency. |
| **Query API** | REST/WebSocket; serve from cache; optional history from time-series DB. |

### 4.5 Data Flow

1. **Ingest:** Adapters receive ticks from exchanges → normalize → Kafka.
2. **Aggregate:** Consumer reads stream; for each pair and window, compute single price; write to cache (and optional DB).
3. **Serve:** API reads from Redis (or DB for history); clients get current (and optionally historical) price.

### 4.6 Scaling & Fault Tolerance

- **Per-exchange isolation:** One adapter per exchange; failure of one doesn’t stop others.
- **Staleness:** Mark source unhealthy if no update for N seconds; exclude from aggregation or weight down.
- **Regional:** Cache replicas per region; read from nearest.

### 4.7 Security & Compliance

- **Integrity:** Prefer signed/authenticated feeds where available; log source and timestamp for audit.
- **Availability:** No single exchange dependency; fallback and clear “last updated” to clients.

### 4.8 Summary

**Price aggregation** = **Exchange adapters** (normalize) → **Kafka** → **Aggregator** (window, mid/median/VWAP) → **Redis** (latest) + **API**. Design for multi-source, staleness, and regional low latency.

---

## 5. Fraud Detection and Risk Analysis

### 5.1 Problem & Scope

**Goal:** **Real-time** (and optionally batch) detection of **suspicious patterns**: velocity (many tx in short time), amount anomalies, device/geo changes, known bad actors. Score transactions and **block or flag** before settlement; support **audit** and **model updates**.

### 5.2 Core Concepts

| Concept | Description |
|--------|-------------|
| **Features** | Inputs to the model: amount, velocity, user history, device, IP, geo, etc. |
| **Scoring** | Model outputs risk score (e.g. 0–1); threshold to block, review, or allow. |
| **Stream vs batch** | Real-time: score per tx as it arrives; batch: periodic review and model retrain. |

### 5.3 High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│  Transaction Sources (Payments, Withdrawals, Transfers)                                  │
└───────────────────────────────────────────┬─────────────────────────────────────────────┘
                                            │
                                            ▼
                            ┌───────────────────────────────┐
                            │  Event Bus (Kafka)             │
                            │  topic: transactions          │
                            └───────────────┬────────────────┘
                                            │
                    ┌───────────────────────┼───────────────────────┐
                    ▼                       ▼                       ▼
            ┌───────────────┐       ┌───────────────┐       ┌───────────────┐
            │  Feature      │       │  Feature      │       │  Rule Engine   │
            │  Store        │       │  Computation  │       │  (optional)    │
            │  (user history│       │  (stream)     │       │  hard blocks   │
            │   velocity…)  │       │  Flink/Spark  │       │  e.g. sanctions│
            └───────┬───────┘       └───────┬───────┘       └───────┬───────┘
                    │                       │                       │
                    │    ┌──────────────────┘                       │
                    │    │  features per tx                         │
                    ▼    ▼                                          │
            ┌───────────────────────────────┐                       │
            │  Model Serving                 │                       │
            │  (TensorFlow Serving /        │◄──────────────────────┘
            │   custom) score 0–1            │   rule result
            └───────────────┬───────────────┘
                            │
                            ▼
            ┌───────────────────────────────┐
            │  Decision Service             │
            │  if score > block_threshold    │
            │    → block; else if > review  │
            │    → queue for human; else OK │
            └───────────────┬───────────────┘
                            │
                            ▼
            ┌───────────────────────────────┐
            │  Downstream: Ledger /         │
            │  Notifications / Audit /      │
            │  Case Management              │
            └───────────────────────────────┘
```

### 5.4 Main Components

| Component | Responsibility |
|-----------|----------------|
| **Event bus** | Kafka: all transactions; exactly-once or at-least-once; retention for replay. |
| **Feature store** | Precomputed or on-demand: user velocity, history, device, geo; PostgreSQL/Redis or dedicated (Feast). |
| **Feature computation** | Stream (Flink/Spark): enrich tx with features from store or real-time aggregates. |
| **Rule engine** | Hard rules (e.g. sanctions list, max amount); fast; runs before or in parallel to ML. |
| **Model serving** | Score each tx (e.g. TF Serving); low latency; versioned models. |
| **Decision service** | Map score + rules → block / review / allow; emit result to downstream and audit. |

### 5.5 Data Flow

1. **Tx** published to Kafka.
2. **Features** looked up (user, device) and/or computed in stream (velocity, amount).
3. **Rules** run (e.g. block if on sanctions list).
4. **Model** scores tx; **decision service** applies thresholds; result (block/review/allow) written and sent downstream.
5. **Audit:** tx + features + score + decision stored for compliance and retraining.

### 5.6 Scaling & Fault Tolerance

- **Stream:** Partition by user_id; scale consumers; checkpoint for exactly-once or at-least-once.
- **Model:** Replicate serving instances; A/B or shadow mode for new models.

### 5.7 Security & Compliance

- **Audit:** Every decision logged with features and score; retention; explainability where required.
- **PII:** Features may contain PII; encrypt at rest; access control; minimize exposure in logs.

### 5.8 Summary

**Fraud detection** = **Kafka** (tx events) → **Feature store + stream** (enrich) → **Rules** + **ML scoring** → **Decision service** (block/review/allow) → **Audit + downstream**. Design for low latency, auditability, and clear block/review thresholds.

---

## 6. Secure API Gateway for Financial Transactions

### 6.1 Problem & Scope

**Goal:** Expose **trading and wallet APIs** to third parties (and internal clients) with **strict security**: authentication, authorization, **rate limiting**, **request signing**, **audit**, and **tenant isolation**. Protect backend services from abuse and ensure non-repudiation.

### 6.2 Core Concepts

| Concept | Description |
|--------|-------------|
| **Auth** | Verify identity: API key + secret, OAuth2, or mTLS. |
| **Signing** | HMAC or RSA over request (e.g. timestamp + method + path + body); verify before processing. |
| **Rate limit** | Per API key / user / IP: N requests per second/minute; sliding or token bucket. |
| **Tenant isolation** | One tenant’s data and limits never leak to another; enforce at gateway + backend. |

### 6.3 High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│  Clients (Partners, Internal Services) — REST / WebSocket                                │
└───────────────────────────────────────────┬─────────────────────────────────────────────┘
                                            │
                                            ▼
                            ┌───────────────────────────────┐
                            │  Edge (CDN / LB)              │
                            │  DDoS mitigation, TLS term    │
                            └───────────────┬───────────────┘
                                            │
                                            ▼
                            ┌───────────────────────────────┐
                            │  WAF (Web Application Firewall)│
                            │  Block known attacks, inject   │
                            └───────────────┬───────────────┘
                                            │
                                            ▼
┌───────────────────────────────────────────────────────────────────────────────────────────┐
│  API Gateway                                                                              │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐        │
│  │  Auth       │ │  Signature  │ │  Rate       │ │  Routing    │ │  Audit Log  │        │
│  │  API key /  │ │  Verify     │ │  Limit      │ │  to service │ │  request id,│        │
│  │  OAuth/JWT  │ │  HMAC/RSA   │ │  per key/IP │ │  version    │ │  key, path, │        │
│  └──────┬──────┘ └──────┬──────┘ └──────┬──────┘ └──────┬──────┘ └──────┬──────┘        │
│         └───────────────┴───────────────┴───────────────┴──────────────┘                │
└───────────────────────────────────────────┬─────────────────────────────────────────────┘
                                            │
                    ┌───────────────────────┼───────────────────────┐
                    ▼                       ▼                       ▼
            ┌───────────────┐       ┌───────────────┐       ┌───────────────┐
            │  Trading      │       │  Wallet       │       │  Market Data   │
            │  Service      │       │  Service      │       │  Service       │
            └───────────────┘       └───────────────┘       └───────────────┘
```

### 6.4 Main Components

| Component | Responsibility |
|-----------|----------------|
| **Edge / LB** | TLS termination, DDoS protection; optional CDN for read-heavy. |
| **WAF** | Block SQLi, XSS, known bad patterns; allowlist/blocklist. |
| **Auth** | Validate API key (store in KMS or secrets); issue/validate JWT; scope (e.g. trade vs read). |
| **Signature verification** | Recompute HMAC/RSA from request; compare with header; reject on mismatch; prevent replay (timestamp window). |
| **Rate limiter** | Redis or in-memory; key = api_key or user_id; sliding window or token bucket; 429 when exceeded. |
| **Routing** | Path → backend service; versioning (e.g. /v1/orders). |
| **Audit log** | Request ID, API key (hashed), path, method, timestamp, status; immutable; SIEM/CloudTrail-style. |

### 6.5 Data Flow

1. Client sends request with **API key** (header) and **signature** (header: timestamp + body hash signed).
2. **Gateway:** Auth (key valid, not revoked) → Verify signature and timestamp (replay window) → Rate limit → Route.
3. **Backend** processes; response returned; **audit** logs request + response code.

### 6.6 Scaling & Fault Tolerance

- **Gateway:** Stateless; horizontal scale behind LB; rate limit state in Redis (cluster).
- **Circuit breaker:** If backend is down, fail fast and log; don’t hold connections.

### 6.7 Security & Compliance

- **Secrets:** API keys in KMS/Vault; rotate; never log full key.
- **Non-repudiation:** Signature verification + audit log; prove request came from key holder.
- **Encryption:** TLS in transit; sensitive fields encrypted at rest in backend.

### 6.8 Summary

**Secure API gateway** = **Edge + WAF** → **Gateway** (auth → **signature verify** → **rate limit** → route) → **Backend**; **audit** every request. Design for auth, signing, rate limits, and tenant isolation.

---

## Quick Reference

| System | Core flow |
|--------|-----------|
| **Exchange / order book** | Gateway → Matching engine → Persistence + Ledger + Events |
| **Blockchain indexing** | Chain adapters → Kafka → Indexers → Stores → Query API |
| **Custody** | Policy → KMS → HSM (hot/cold) → Audit |
| **Price aggregation** | Exchange adapters → Kafka → Aggregator → Cache → API |
| **Fraud detection** | Kafka (tx) → Features + Rules → ML score → Decision → Audit |
| **API gateway** | Auth → Signature → Rate limit → Route → Audit |

All designs assume **monitoring, alerting, and operational runbooks**; call out **auditability and compliance** in interviews.
