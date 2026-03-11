# Ibrahim Jarif — Interviewer-Specific Prep
**Round:** Tech Execution
**Interviewer:** Ibrahim Jarif, Staff Software Engineer @ Coinbase
**Prepared:** March 2026

---

## WHO IS IBRAHIM — PROFILE ANALYSIS

Before you prep a single answer, understand exactly who is sitting across from you.

### His Technical Identity
- **Maintainer of BadgerDB** — an LSM-tree based embedded key-value store (the same storage engine used inside DGraph). He has written and maintained database internals from scratch: memtables, SSTables, compaction strategies, bloom filters, block-level caches, WAL (Write-Ahead Logging).
- **Contributor to Dgraph** — a distributed graph database. He built: Multi-Tenancy, Learner Nodes (Raft-based consensus), Audit Logs, data streaming capabilities.
- **Coinbase Risk Platform** (since March 2022, now Staff) — he contributed to the "next-gen Risk Platform." This means real-time transaction risk scoring, fraud signal processing, financial data pipelines.
- **Performance engineering obsessive** — at Cohesity he improved a data processing app's performance by **1000x** and cut memory requirement by **50x**. At Dgraph: 5x DB init speed, 2x iteration speed. He does not accept "it was slow" as a root cause.
- **Go language specialist** — runs a Go workshop on goroutine handling, throttling, and graceful termination. His entire career has been Go.

### What This Means for Your Interview

Ibrahim will not be satisfied with system design at the service-boundary level. He will go **one or two layers deeper than you expect**. Expect questions like:

- "You said you used PostgreSQL `SELECT FOR UPDATE` — what happens to that lock during a network partition between your app and the DB?"
- "You used Redis SETNX for distributed locking — what's the failure mode if Redis restarts between SETNX and the TTL being set?"
- "Your reconciliation job runs hourly — how do you ensure it's idempotent? What's your deduplication key?"
- "Walk me through the exact write path for a transaction in your ledger — from HTTP request to committed row."

**He cares about:**
1. Storage-layer correctness (not just "I used a DB")
2. Concurrency and locking semantics (he's written concurrent KV store code)
3. Failure mode identification (what breaks, when, why, and how you recover)
4. Actual performance numbers and how you achieved them
5. Distributed systems edge cases: split-brain, partial failures, replication lag, lock expiry under failure

**He will not be impressed by:**
- "We used microservices and Kafka" without explaining consistency guarantees
- Architecture diagrams without discussing failure modes
- "We chose PostgreSQL because it's reliable" — he wants to know *why specifically* over alternatives for *your* workload

---

## IBRAHIM'S LIKELY QUESTION PATTERNS

Based on his career, he interviews through three lenses. Know which lens each question is coming from so you can calibrate depth.

### Lens 1: Storage & Data Model Depth
*Coming from his BadgerDB/Dgraph background — he wants to know if you understand your data layer, not just use it.*

### Lens 2: Distributed Systems Correctness
*Coming from his Raft/Dgraph background — he wants to know what your consistency model actually is, not just "we're consistent."*

### Lens 3: Risk Platform Relevance
*Coming from his Coinbase work — he wants to know if your execution experience maps to the scale and correctness requirements of a financial risk platform.*

---

---

# THE 10 QUESTIONS IBRAHIM WILL LIKELY ASK

---

## Q1: "Walk me through the exact write path for a transaction in your payments ledger — from API call to committed state."

**Why he asks this:** BadgerDB maintainer. He has written write paths from scratch. He wants to know if you understand the full write path or if you're describing what you assumed happens inside the DB.

**The depth he expects:** Not "it writes to PostgreSQL." He wants: request receipt → idempotency check → lock acquisition → ledger row write → Kafka emit → lock release → response.

---

**YOUR ANSWER:**

"Let me walk through this precisely. At Skydo, the transaction write path had six distinct steps, each with its own failure contract:

**Step 1: Idempotency gate**
Before anything touches the ledger, we check the idempotency key — a client-supplied UUID. Redis fast-path: `GET idempotency:{key}`. If it exists, return the cached response immediately. If not, set `idempotency:{key} = in_flight` with a TTL (say, 30 seconds). Redis is the fast path — the DB is the fallback for Redis restarts: a separate `idempotency_requests` table in PostgreSQL stores the key and the committed response after the transaction completes.

**Step 2: Input validation + authorization**
Validate amounts (no negative, no zero), check that the source account belongs to the authenticated user, verify the corridor is active for the requested currency pair.

**Step 3: Account lock**
`SELECT account_id, balance FROM accounts WHERE account_id = $1 FOR UPDATE`. Pessimistic lock — not optimistic. Reason: under high concurrency for the same account (e.g., automated bulk payments), optimistic retry loops create thundering herd. FOR UPDATE serializes writes without retries.

**Step 4: Ledger write — inside a single transaction**
```sql
BEGIN;
  INSERT INTO journal_entries (entry_id, account_id, type, amount, currency, txn_ref, created_at)
    VALUES ($1, $source, 'DEBIT', $amount, $currency, $txn_ref, now());
  INSERT INTO journal_entries (entry_id, account_id, type, amount, currency, txn_ref, created_at)
    VALUES ($2, $dest, 'CREDIT', $amount, $currency, $txn_ref, now());
  INSERT INTO transactions (txn_id, status, idempotency_key, ...) VALUES (...);
COMMIT;
```
Double-entry — every debit has a credit in the same transaction. COMMIT is synchronous. We use synchronous replication (PostgreSQL `synchronous_commit = on`) — the COMMIT does not return until the standby has confirmed the WAL record.

**Step 5: Post-commit — Kafka emit**
After the DB COMMIT returns, we emit a `transaction.committed` event to Kafka. This is not part of the DB transaction — it's a best-effort post-commit step. Idempotent Kafka consumer downstream handles the case where the emit fails and is retried.

**Step 6: Idempotency key persistence**
Update Redis: `idempotency:{key} = committed_response`. Update `idempotency_requests` table in PostgreSQL with the full response. Now if the same request is replayed, the response is served from Redis without re-entering the write path.

**Failure contract summary:**
- If Redis SETNX fails → we reject the request (safety over availability for financial writes)
- If the DB transaction fails → Redis idempotency key is cleaned up via TTL
- If Kafka emit fails after DB commit → the downstream reconciliation job catches the gap
- If the app crashes between DB COMMIT and Kafka emit → reconciliation detects the committed-but-not-emitted transaction on the next run"

---

**Depth traps Ibrahim will set:**

- *"What happens if the TTL on the Redis idempotency key expires before the DB transaction commits?"* → The in_flight key expires. If the client retries, they get through the Redis fast-path again and try to write. The DB-level `transactions` table has a unique constraint on `idempotency_key` — the second write will fail with a constraint violation, which we catch and return the original response. Two layers of deduplication.

- *"Why `synchronous_commit = on`? What's the latency cost?"* → Adds ~2-5ms round-trip for WAL sync to standby. Acceptable for a payments write path. The alternative (async replication) means a failover could lose the last few milliseconds of commits — in a financial system that means lost transactions. Not a trade-off I make.

- *"What's the blast radius if the FOR UPDATE lock is held too long?"* → Other transactions writing to the same account queue behind it. I set a `lock_timeout` (e.g., 500ms) — if the lock can't be acquired in that window, we return a 429 (retry-after) to the client rather than letting threads stack up. This prevents cascading latency.

---

## Q2: "You used Redis SETNX for distributed locking. Walk me through the failure modes."

**Why he asks this:** This is a textbook distributed systems question — but Ibrahim will probe edge cases that only someone who has implemented concurrent KV store operations would know to ask about.

---

**YOUR ANSWER:**

"Redis SETNX (`SET key value NX EX ttl`) has three failure modes I explicitly designed around:

**Failure 1: Redis crashes between SETNX and TTL set**
With old Redis (`SETNX` + separate `EXPIRE`), a crash between the two commands leaves an orphaned lock with no TTL — permanent deadlock. We used the atomic `SET key value NX EX ttl` (single command, available since Redis 2.6.12) — this is atomic; there's no window between lock acquisition and TTL setting.

**Failure 2: Worker crashes while holding the lock (TTL-based recovery)**
Worker A acquires lock, starts processing job J, crashes. The TTL expires — lock is released. Worker B picks up job J. The question is: was Worker A's partial work committed to the DB before the crash? Answer: the job handler updates the `job_state` table atomically. If Worker A committed job state as `IN_PROGRESS` and then crashed, Worker B sees `IN_PROGRESS`, checks the `started_at` timestamp — if it's stale beyond the TTL, it re-acquires and re-processes. Every job handler is idempotent — re-processing has the same outcome as the first processing.

**Failure 3: Redis network partition (client can't reach Redis)**
If the app can't reach Redis to acquire the lock, we fail open or fail closed depending on the job type. For financial jobs (payment retries, settlement runs) — we fail closed: don't process the job, let it be re-queued. For non-financial jobs (email notifications, reporting) — we fail open and process once with idempotency handled at the application layer.

**What I did not solve:** Redlock (multi-node Redis consensus for locks) — we evaluated it and decided it was more complexity than we needed. Our single-Redis instance is a Redis primary with synchronous replication to a standby. Failover time is ~30 seconds via Redis Sentinel. During those 30 seconds, lock operations fail — financial jobs are queued, not dropped. Acceptable for our SLA."

---

**Depth traps Ibrahim will set:**

- *"What about clock skew in a distributed environment? If your server clock drifts, does the TTL mean what you think it means?"* → The TTL is server-side on Redis — not client-side. Redis manages the expiry based on its own clock. The clock skew between the app server and Redis server is irrelevant to the TTL semantics. What clock skew does affect: if the app server clock is ahead, it might assume the lock has expired before Redis releases it. We don't use client-side TTL tracking — we always re-query Redis for lock state.

- *"Can two workers hold the same lock simultaneously under any failure condition you haven't accounted for?"* → Yes — if Redis undergoes a primary failover mid-operation and the standby promoted doesn't have the SETNX write (async replication window). This is the fundamental trade-off of single-node Redis locking: correctness guarantee degrades during failover. We accept this because: (a) job handlers are idempotent, (b) duplicate execution has no financial consequence — the idempotency layer at the DB level catches it.

---

## Q3: "At Goldman Sachs, you ran multi-terabyte in-memory distributed compute clusters for market risk. What was the consistency model? What broke and how did you debug it?"

**Why he asks this:** He contributed to Raft-based distributed systems (Dgraph's Learner Nodes). He knows exactly how hard consistency in distributed systems is. Your Goldman experience is the most technically interesting thing on your resume to him.

---

**YOUR ANSWER:**

"The platform was a distributed in-memory aggregation grid — multiple nodes, sharded by risk factor, with read replicas for query load. The consistency model was **eventual consistency on reads with strong consistency on writes** — writes went to the primary shard, replicated asynchronously to read replicas.

**What broke:** We had intermittent stale VaR numbers under market open load. The symptoms: quants reported that their risk dashboard was showing numbers from the previous calculation cycle, not the current one. Intermittent — couldn't reproduce reliably.

**How I debugged it — the investigation had three phases:**

*Phase 1: Instrument, don't guess.* After two weeks of engineers chasing hunches, I stopped the investigation and required everyone to instrument first. Added latency histograms at each aggregation stage — per data source, per node, per calculation step. Within 24 hours we had a correlation: failures happened at market open (peak ingestion) and were concentrated on 3 specific nodes.

*Phase 2: Isolate the failure path.* The 3 nodes were falling behind on replication during ingestion spikes — they were receiving more data than they could process and replicate synchronously. This created **replication lag** — reads on those replicas returned stale data.

*Phase 3: Find the root cause of the root cause.* Why was replication lagging? The sharding algorithm distributed by key count, not throughput. Three keys happened to be high-volume (major risk factors — EUR, USD, JPY) and had all landed on the same three nodes. The nodes weren't slow — they were just overloaded relative to the others.

**Three combined root causes:**
1. Sharding imbalance → uneven throughput distribution
2. Async replication with no staleness check on read path → stale reads were silent
3. Primary acknowledging writes before standby confirmed → during failover, small amount of data loss possible

**Fixes:** Sharding algorithm rewritten to balance by throughput. Staleness check on read path: reads check `last_updated_at` against a freshness SLA — if stale, route to primary. Synchronous replication for writes above a threshold (not all writes — too expensive — just the VaR calculation triggers).

**Process fix:** Added data freshness as a first-class SLO metric — not just 'is the system up' but 'how fresh is the data.' We'd been running without a freshness SLA. We found this problem because users complained. We should have found it from a metric."

---

**Depth traps Ibrahim will set:**

- *"You said you added a staleness check — what's the mechanics? How does the read path know when the replica is stale?"* → Each write to the primary includes a `sequence_number`. Read replicas maintain their `last_applied_sequence`. The read path checks: `primary_sequence - replica_last_applied_sequence > threshold` → route to primary. The threshold was tuned empirically: at what sequence gap did users actually see incorrect numbers?

- *"How did you handle the case where the primary is unavailable during your staleness fallback?"* → If the primary is unavailable, we serve the replica data with an explicit staleness indicator in the response — quants knew they were looking at potentially stale data. "Stale data with warning" is better than "no data" for a risk dashboard. We documented this in the operational runbook.

---

## Q4: "Walk me through how your reconciliation system works — specifically the matching algorithm."

**Why he asks this:** Risk Platform at Coinbase involves reconciliation of on-chain and internal state. He'll want to understand if you have production experience with reconciliation at depth, not just "we ran a batch job."

---

**YOUR ANSWER:**

"At Skydo, reconciliation operated at two levels:

**Level 1: Internal integrity (runs every hour)**
Assert that the double-entry ledger balances: sum of all DEBITs = sum of all CREDITs. This is a single SQL aggregation query:
```sql
SELECT SUM(CASE WHEN type='DEBIT' THEN amount ELSE -amount END)
FROM journal_entries
WHERE created_at BETWEEN $start AND $end;
```
If the result is non-zero — P0 alert immediately. I've never seen this fire in production. If it did, it means either a bug in the write path or DB-level corruption.

**Level 2: External reconciliation (runs T+1, per corridor)**
Match internal transaction records against settlement files from payment gateways and banks.

*Matching algorithm:*
1. Pull settlement file (CSV/SFTP from bank) — parse into `settlement_records` table with the gateway's external reference ID.
2. Join against our `transactions` table on `external_reference_id`.
3. Categorize every record into one of four states:
   - **Matched and amount agrees** → green, no action
   - **Matched but amount differs** → exception: possible FX rounding issue or fee discrepancy, queued for human review
   - **In our records, not in settlement file** → we sent money that the gateway didn't settle; exception, high priority
   - **In settlement file, not in our records** → the gateway settled something we have no record of; exception, highest priority (possible duplicate charge to customer)

4. Unmatched items go into a `suspense_account` (a real ledger account — all money must be accounted for somewhere). Alert triggers to operations team.
5. After human review confirms resolution, suspense account entries are cleared.

**Idempotency of the reconciliation job:** The job is re-runnable. We use the settlement file's hash as the job's external idempotency key — re-running for the same file produces the same output without duplicating records. This matters because settlement files can be large and jobs can fail mid-parse.

**What I'd add for Coinbase specifically:** A third level — reconciliation against on-chain state. For crypto withdrawals: for every withdrawal marked 'confirmed' in our ledger, independently verify the on-chain transaction via a blockchain node (not just the gateway's API). The gateway can lie; the chain cannot."

---

**Depth traps Ibrahim will set:**

- *"Your T+1 reconciliation means you have a 24-hour window where discrepancies are undetected. Is that acceptable?"* → For external reconciliation — yes, because bank settlement files are batch by definition. What's not acceptable is going 24 hours without internal integrity checks — hence the hourly internal balance assertion. For any transaction where we suspect a real-time issue (gateway timeout, status mismatch), we have a separate real-time reconciliation trigger that runs immediately rather than waiting for T+1.

- *"How do you handle FX rounding discrepancies in your amount-differs bucket?"* → We compute the expected settlement amount using the FX rate locked at time of transaction (stored in the journal entry). If the difference is within a configured tolerance band (e.g., ±0.01 of the transaction amount), it's auto-resolved as a rounding difference. Outside the band — human review. The tolerance band is configured per corridor because different banks round differently.

---

## Q5: "Tell me about a system you built that performed poorly in production. What was the root cause? How did you find it? What did you fix?"

**Why he asks this:** His career is defined by performance engineering. 1000x improvement at Cohesity. He wants to know if you can find performance problems, not just describe architectures.

---

**YOUR ANSWER:**

"At Goldman Sachs, we had a query performance problem on the market risk read path. Quants running ad-hoc risk queries at market open were experiencing latency spikes — queries that ran in 200ms normally were taking 8–12 seconds under load.

**Finding the root cause — three tools:**
1. PostgreSQL `pg_stat_statements` — identified the top 10 queries by total time. The worst offender was a report query with a 6-table join that was being called far more frequently than expected.
2. `EXPLAIN ANALYZE` on the query — showed a sequential scan on a 40-million-row table. The query was joining on a composite key that had a B-tree index, but the planner was choosing a seq scan because the query was returning ~30% of the table (above the planner's seq-scan preference threshold).
3. Cross-referenced with `pg_stat_bgwriter` — found very high `buffers_written` at market open, indicating checkpoint pressure. The write workload at market open was competing with read I/O for buffer pool.

**Root causes:**
1. The join query was not using the index because the result set size pushed the planner to seq scan — needed a partial index + query rewrite to reduce the result set first.
2. The checkpoint configuration was aggressive (`checkpoint_completion_target` too low) — causing I/O spikes that degraded read latency.
3. The report query itself was being called once per refresh for every quant dashboard — 50 quants × 5-second refresh = 600 queries/minute on a heavy query. Should have been a materialized result with push on update.

**Fixes:**
- Rewrote the query to filter on a date partition first, reducing the result set below the planner's seq-scan threshold.
- Tuned `checkpoint_completion_target = 0.9` — smoother checkpoint spread over more time.
- Materialized the report query — computed on write, served on read. P99 latency dropped from 8-12 seconds to under 200ms under load.

**What I learned:** Performance problems in production databases are almost never a single cause. The query was slow. The checkpoint I/O amplified it. The call frequency turned a slow query into a resource exhaustion event. You have to find all three to actually fix it — patching one leaves the problem latent."

---

**Depth traps Ibrahim will set:**

- *"Why did the planner choose a seq scan over the index at 30% selectivity?"* → PostgreSQL's cost model estimates that a seq scan is cheaper than random I/O index lookups when you're retrieving a large fraction of the table. The threshold depends on `random_page_cost` and `seq_page_cost` tuning. With SSDs, lowering `random_page_cost` to 1.1 (from the default 4.0) makes the planner prefer index scans at higher selectivity — that was part of the fix.

- *"How did you handle cache invalidation for the materialized report?"* → The materialized result was stored in Redis keyed by `report_type + time_bucket + user_context`. Invalidated on write: when a new risk calculation was committed, it emitted a Kafka event; the materialization service consumed the event and recomputed affected keys. Eventual consistency — there's a small window where the report shows stale data. Quants accepted this; the alternative (synchronous recompute on every write) was too expensive.

---

## Q6: "You mentioned you drove a team of 5 to 12 and made architectural decisions with financial-integrity implications. Tell me about a decision you made that later turned out to be wrong. How did you discover it? What did you change?"

**Why he asks this:** He's a practitioner who has made and learned from mistakes. He respects engineers who can be honest about failures. He's skeptical of candidates who only tell success stories. This is also a way to assess your self-awareness about technical trade-offs.

---

**YOUR ANSWER:**

"Early at Skydo, I pushed back on adopting managed Kafka (Confluent Cloud) in favour of running our own Kafka cluster on ECS. My reasoning at the time: we had specific broker configuration needs, I was concerned about vendor lock-in, and I thought the operational cost would be manageable.

**How I discovered I was wrong:** Three months into production, I did a quarterly review of where engineering time was going. Kafka operations — broker health, partition rebalancing, upgrading broker versions, tuning consumer group offsets after incidents — was consuming roughly 15-20% of one engineer's time. That engineer was one of my most senior people. The operational work had no leverage — it was maintenance, not capability.

**What I changed:** We migrated to Confluent Cloud in the quarter following that review. Migration took 3 weeks (dual-write to both, cutover consumer groups one at a time, verify, decommission self-managed). Total transition cost: 3 weeks. Net recovered capacity: ~15-20% of a senior engineer's time per quarter. In an 8-person team, that's material.

**The lesson, stated directly:** I had underweighted operational burden in my original build-vs-buy decision. I evaluated: 'can we run this ourselves?' (yes). I did not rigorously evaluate: 'what is the ongoing cost of running this ourselves, at what stage of our company, with what team size?' At Skydo's stage — pre-product-market-fit, trying to move fast — managed services are almost always the right answer unless you have specific requirements they can't meet.

**What I applied going forward:** Every infrastructure decision now includes an explicit operational burden estimate — not just build cost, but ongoing monthly ops cost in engineering hours. That number goes on the trade-off document."

---

## Q7: "How would you design a real-time transaction risk scoring system for a crypto exchange?"

**Why he asks this:** This is his Coinbase Risk Platform world. He wants to see if your thinking aligns with how risk systems are actually built at this scale. He is not looking for a textbook answer.

---

**YOUR ANSWER — walk this through like you're designing it together:**

"Before I design anything — clarifying questions:
- What's the latency budget? Does the risk score need to be synchronous (blocking the transaction) or asynchronous?
- What's the transaction volume? Coinbase processes millions of transactions — what's our peak TPS?
- What signals do we have available in real-time vs. requiring async enrichment?

**Assuming:** <100ms synchronous decision for blocking transactions, async enrichment for scoring beyond the first gate.

**Two-tier architecture:**

*Tier 1: Hard rules (synchronous, <10ms)*
These never miss. Deterministic, no ML.
- OFAC / sanctions address lookup → immediate reject
- User account suspended / KYC expired → reject
- Transaction amount exceeds user's verified daily limit → reject
- Destination address matches internal known-bad list → reject
Implementation: pre-loaded bloom filter in memory for address blacklist (fast O(1) lookups), backed by DB for verification. Redis for user limit counters (sliding window, atomic INCR with TTL).

*Tier 2: ML risk score (synchronous, <90ms)*
Not computed fresh on every request — that's too slow and expensive. We serve a **cached risk score per user**, refreshed asynchronously.
- Compute user risk scores in a Kafka stream processor (Flink or similar): consume transaction events, compute velocity features (# transactions in last hour, # distinct addresses, # new addresses), output risk_score per user_id.
- Cache score in Redis: `risk_score:{user_id}` with a 5-minute TTL.
- On transaction request: read cached score, apply threshold.
  - Score < LOW_THRESHOLD → approve
  - LOW ≤ score < HIGH_THRESHOLD → approve + flag for async review
  - Score ≥ HIGH_THRESHOLD → hold for manual review or 2FA step-up

*Tier 3: Post-transaction async enrichment (no SLA)*
- Chain analysis (Chainalysis / Elliptic API) — wallet address reputation
- Behavioral model re-scoring — update the user's risk score based on the completed transaction outcome
- False positive feedback loop — if a held transaction is cleared by manual review, that outcome feeds back into model training

**Key design decisions to verbalize:**
1. *Why cached score vs. fresh computation?* At Coinbase's volume, running a full ML inference on every transaction is too expensive. The user's risk profile doesn't change between transactions — it changes on a slower cadence. Cache is the right trade-off.
2. *What's the blast radius of a stale cache score?* If a user's risk score is cached as LOW but they just started doing suspicious behaviour, we have a 5-minute window where we might approve suspicious transactions. Mitigation: velocity counters in Tier 1 are real-time (Redis INCR) — they catch sudden velocity spikes even when the ML score is stale.
3. *How do you handle crypto-specific signals?* Crypto has on-chain signals traditional fintech doesn't: is the destination a known mixer? Is the address associated with a DeFi protocol that has been exploited? Is the user withdrawing to a newly-created wallet? These require real-time blockchain data enrichment — not just internal data."

---

## Q8: "You drove ISO 27001 and SOC 2 certifications — what were the hardest technical controls to implement?"

**Why he asks this:** He's at Coinbase, which operates under extremely heavy regulatory scrutiny (SEC, FinCEN, CFTC, state BitLicenses). He wants to know if your compliance execution was real or just checkbox work.

---

**YOUR ANSWER:**

"Three controls that required actual engineering, not just documentation:

**1. Encryption at rest with key rotation**
The trivial answer is 'we enabled PostgreSQL `pgcrypto`.' The hard part: key rotation. If your encryption key is stored next to your data, you have no separation of duty — a DB compromise exposes both. We moved to AWS KMS for key management: application-level encryption before the DB write, KMS key ARN stored in the DB, actual key never touches the DB. Key rotation: AWS KMS handles envelope encryption — the data encryption key (DEK) is encrypted by the key encryption key (KEK). Rotating the KEK means re-encrypting all DEKs, not all data. That's manageable.

**2. Immutable audit log on the ledger**
ISO 27001 requires tamper-evident audit trails. The naive approach is a `deleted_at` soft-delete column. The problem: a compromised DB admin can update or delete any row. Real immutability required: PostgreSQL row-level security with `INSERT`-only permissions on journal_entries for the application role. The application DB user has `INSERT` and `SELECT` — no `UPDATE`, no `DELETE`. The only user with write-beyond-insert access is a tightly controlled admin role used only during maintenance windows, with all usage logged to an external audit log (CloudTrail).

**3. DLP (Data Loss Prevention) controls**
SOC 2 Type II requires demonstrating that PII doesn't escape your trust boundary. Hardest part: payment gateway integrations that sent customer PII in callback URLs and webhook payloads. Some gateways were embedding customer names in query parameters — these appeared in application logs. We had to: audit every third-party integration for PII in transit, implement log scrubbing for known PII patterns (name, PAN, account number), and for gateways that couldn't be fixed on their end, build a proxy layer that stripped PII from payloads before they reached our logs.

**What I'd add for Coinbase specifically:** On-chain data is public by definition — every Ethereum transaction is visible. The privacy challenge at Coinbase is associating on-chain addresses with KYC'd users. The internal mapping (address → user) is the sensitive data, not the on-chain transaction itself. The DLP controls for that mapping are similar to what I built, but the threat model is different: you're protecting internal data from exposure, not preventing PII from reaching external logs."

---

## Q9: "Tell me about a technical program where you had to make a significant decision with incomplete information and a hard deadline."

**Why he asks this:** IC6 execution requires judgment calls under ambiguity. He wants to see your decision framework, not just the outcome.

---

**YOUR ANSWER — use the DynamoDB decision story, reframed for depth:**

"At Skydo, leadership mandated a migration from PostgreSQL to DynamoDB — driven by an investor wanting AWS alignment, with a timeline of 'start in the next sprint.'

What made this hard: I had enough information to know the migration was risky, but not enough to know the exact cost of proceeding. I knew DynamoDB couldn't do native multi-row transactions — but I didn't know exactly how much engineering work it would take to replicate our ledger's correctness guarantees on top of it.

**How I made the decision under incomplete information:**

Step 1: I bounded the unknown. I could not immediately quantify 'total cost of DynamoDB migration' — too large. But I could quantify the specific correctness requirements we'd need to replicate: ACID transactions, join-based reconciliation, row-level locking for balance checks. I then asked one engineer to spend 2 days estimating what replicating each of those requirements in DynamoDB would take. This bounded the unknowing into known-unknown vs. unknown-unknown.

Step 2: I separated reversible from irreversible decisions. Starting a migration is reversible — you can stop. Completing a migration and decommissioning the old DB is not. I framed the decision as: 'let's not argue about whether to start investigating DynamoDB. Let's argue about whether to commit to completing the migration.'

Step 3: I made the asymmetry explicit. If I'm wrong (DynamoDB is actually viable) — cost: a few weeks of engineering investigation that we could have saved. If leadership is wrong (DynamoDB has correctness gaps) — cost: a financial integrity incident that could result in regulatory action. The asymmetry argued for caution on the risky side.

Result: we agreed to migrate non-critical workloads to DynamoDB (event logs, session data) while keeping the ledger on PostgreSQL. This satisfied the AWS alignment goal without the correctness risk. Six months later — a gateway failure confirmed that the PostgreSQL idempotency and ACID guarantees caught a duplicate charge scenario. Zero money lost."

---

## Q10: "What's the most technically challenging thing you've shipped? Not the most impactful — the most technically hard."

**Why he asks this:** He wants to understand your ceiling. He works in distributed database internals — he has a high bar for what "technically hard" means. Don't oversell or undersell.

---

**YOUR ANSWER — pick the Goldman Sachs investigation story, framed as technical depth:**

"The Goldman Sachs stale VaR debugging investigation. Not because it was the largest system I've worked on, but because it required holding three separate failure modes in my head simultaneously and understanding the interaction between them.

The system was a multi-terabyte in-memory distributed compute cluster — sharded by risk factor, with async replication to read replicas. VaR numbers were going stale intermittently, only under load, non-reproducible in staging.

**What made it technically hard:** Each of the three root causes — sharding imbalance, async replication with no staleness check, and premature write acknowledgment before standby confirmation — was insufficient alone to produce the symptoms. You needed all three aligned: high load (causing sharding imbalance to manifest as node overload), which caused replication lag on specific nodes, which surfaced as stale reads because there was no staleness check on the read path. In testing, load wasn't high enough. In staging, shards were balanced differently. The bug only existed at production scale under market open conditions.

**The technically hard part:** Designing the investigation to find a combinatorial failure requires a different approach than debugging a single-cause bug. You can't just add one hypothesis and test it. You have to instrument comprehensively first — enough that you can *see* the interaction between all the variables. That required understanding the replication protocol at a level of depth that went beyond what was in the runbook. I had to read the database documentation on synchronous_commit modes, understand how the write acknowledgment handshake worked, and trace exactly what a 'commit' returning to the application actually guaranteed — and didn't guarantee.

**Why I consider it the hardest thing I've shipped:** The fix was straightforward. The diagnosis required understanding the system at a depth that the system's own operators hadn't needed before."

---

---

# IBRAHIM'S SIGNATURE PROBE PATTERNS

These are the specific follow-up questions Ibrahim is likely to ask based on his technical background. Memorize these.

| What You Say | What He Probes |
|---|---|
| "We used PostgreSQL `FOR UPDATE`" | "What's the lock scope? Table? Row? What's your `lock_timeout`? What happens on deadlock?" |
| "We used Redis for distributed locking" | "What happens during a Redis failover? Is your lock TTL the right granularity for the job duration?" |
| "We used Kafka for event streaming" | "How do you handle consumer lag? What's your rebalancing strategy? How do you handle duplicate delivery?" |
| "We ran a reconciliation job" | "How do you ensure the job is idempotent? What's the deduplication key? What happens if it fails midway?" |
| "We used synchronous replication" | "What's the RPO? What's the failover time? What's the write latency cost?" |
| "We used async replication" | "What's the staleness bound? How do you detect and surface staleness to consumers?" |
| "We sharded by X" | "How do you handle hot shards? What's your rebalancing strategy when the distribution becomes uneven?" |
| "We used BIGINT for money" | "What's the maximum value you can represent? Have you had overflow scenarios in testing?" |
| "We optimized the query" | "What's the specific query plan before and after? What was the cardinality of the filter columns?" |
| "We monitored with alerts" | "What's the metric? What's the alert threshold? How did you tune the threshold to avoid false positives?" |

---

# ONE-PAGER: WHAT TO CONVEY TO IBRAHIM

Ibrahim is a distributed systems engineer who has written database internals from scratch. He will not be impressed by architecture diagrams. He will be impressed by:

1. **You know the failure modes of the tools you use.** Not just "we used Redis" — but "here is what Redis guarantees, here is what it doesn't, here is how we compensated."

2. **You've debugged hard production problems.** The Goldman VaR story is your strongest card with him. Lead with it if given an open-ended question.

3. **You have a correctness mindset, not just a delivery mindset.** "We shipped it" is not the answer he wants. "We shipped it and here is why we know it's correct" is.

4. **You can quantify.** He improved a system by 1000x. He wants numbers: P99 latency, transaction volume, incident reduction %, migration time, replication lag in milliseconds.

5. **You understand that risk platform work (his world) is your world too.** Your experience with financial reconciliation, fraud prevention patterns, and regulatory compliance at Skydo maps directly to Coinbase's Risk Platform. Say this explicitly: *"What I built at Skydo — idempotency, reconciliation, real-time fraud checks — is the same correctness problem your Risk Platform is solving, at a different asset layer."*
