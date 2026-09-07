# What GS asks in Software Design and Architecture — **VP**

Research note for Virtual Panel 3 (Pradeep & Ralph, 8 Sept 2026).  
This is the **named Superday competency**, same calendar label as Analyst/Associate. The **prompt family is similar**; the **bar is not**.

**Honest evidence limit:** first-hand write-ups that both (a) say **VP** and (b) name **Software Design and Architecture** are scarce. Below separates **confirmed VP reports**, **the VP bar described by people who sat the same competency**, and **design prompts GS uses for senior SWE** (guides + Interview Query). Analyst/Associate SDA questions still get reused; at VP they expect a deeper, more open-ended version.

---

## 1. How VP is different in this round

Garvit Jindal sat **Design & Architecture** on a GS Bengaluru virtual panel (May 2026) and stated the firm’s own scaling of the round:

> Analyst / Senior Analyst → lighter. Associate → a real system-design interview. **VP → even more open-ended.**

SpaceComplexity’s GS design guide (calibrated to Glassdoor + LeetCode reports) puts the VP bar as:

| Level | What they score |
|---|---|
| Analyst | LLD: classes, SQL, light HLD |
| Associate | HLD + LLD, concurrency required |
| **VP** | **End-to-end distributed design. Trade-offs with numbers. Failure modes and compliance without being prompted.** |
| ED/MD | System-of-systems, cost, cross-team |

What that means in the room: they will still start with a parking lot, rate limiter, or “design notifications” — then they will not let you stop at boxes. They want **consistency boundary, duplicate/loss, shard if 2×, node death mid-write, audit, cost**. Generic “add Kafka and Redis” fails at VP even if it passes Associate.

A Controllers **VP Superday (April 2024, India)** listed the same three technical panels: DSA → Software Engineering Practices → **Software Design and Architecture** → HM. The candidate never reached SDA (failed Practices), which confirms VP loops **do** run this named round.

---

## 2. Confirmed VP first-hand (rare, use these first)

### A. VP, 6 YOE, Bangalore — **offer**  
Source: [Aman Chowdhury consolidation](https://medium.com/@amanchowdhuryaa/goldman-sachs-recent-interview-experiences-consolidated-0cdc61f7cbde) (experience #8).

Superday round that mixed LLD + architecture (the design-shaped hour):

- ThreadPool
- Callable vs Runnable
- Microservices vs monolith
- **Snake & ladder LLD**
- **Parking lot system design**
- Message queues
- Spring vs Spring Boot

Hiring manager (next round, still design):

- **Design a notification service** — DB choice, **offline users**, project deep-dive

**Takeaway:** even at VP with an offer, India Superday SDA can still be **classic LLD + Java concurrency + one HLD** (parking lot / queues / notifications). Do not skip these because the role says lakehouse.

### B. Controllers VP Superday — India, April 2024  
Source: [LeetCode](https://leetcode.com/discuss/post/5044572/goldman-sachs-virtual-onsite-interview-c-f5ii/).

- Round labeled **Software Design and Architecture**, 60 min, two-interviewer panel  
- No questions published (candidate was already out)

**Takeaway:** format is the same as yours: named SDA hour on Superday.

### C. ScaleEngineer — Goldman Sachs **VP Software Engineer**, System Design round (60 min)

Listed as questions they use at this level:

- Design a **URL shortener** (Bitly)
- Design the backend for a **real-time chat** application
- Design a system to count **top-K trending items** in real time
- Design an **API rate limiter**
- Design a **real-time trading system** for an asset class
- Trade-offs of **distributed caching**
- Data **consistency** in a distributed financial system
- “Describe a **complex system you designed and scaled**” (resume HLD)

Plus VP-flavoured architecture discussion:

- Monolith vs microservices **in a financial context**
- Highly available / fault-tolerant **financial** system
- Database technologies and **use cases** (not “I like Postgres”)

---

## 3. Same competency, one level down — still asked of VPs

These were reported in **Software Design and Architecture** (or the adjacent Superday design slot). VP panels recycle them and then push harder.

| Question | How VP is supposed to go deeper |
|---|---|
| **Rate limiter** (algorithms, not client-only, **code sliding window**; OTP 3/5 min) | Redis atomicity, fail-open vs fail-closed, key explosion, multi-DC |
| **LRU cache** CoderPad | Thread-safety, then distributed cache + invalidation |
| **URL shortener** talk-only | ID allocation without a hotspot, 301 vs 302, abuse/SSRF |
| **WhatsApp sequential delivery** + **Kafka vs Redis Streams** | Per-conversation order, offline inbox as SoR, not Redis-as-ledger |
| **E-commerce / checkout** | Idempotency, saga, money types, double-submit |
| **Twitter** LLD then HLD (Aug 2025 Bangalore Associate) | Fan-out on write vs read, celebrity, Kafka |
| **Billing system HLD** | Ledger correctness, not “use Stripe” |
| **Authn/authz API** | Sessions, tokens, entitlement checks |
| **Load balancer, scale, DB, caching** | Consistent hashing, cache-aside vs write-through, when SQL vs KV vs TSDB |
| **Builder + threads, SOLID on a scenario** | Immutable product; don’t share a mutating builder |
| **Feature switch after N objects in a concurrent system** (Naman Bindra, 2025) | Atomics, volatile, thread pools — this is VP-shaped even if title wasn’t VP |

---

## 4. The VP “open-ended” prompt (highest signal)

Garvit’s Design & Architecture round (Associate Equities, but he describes the **VP version as this shape, more open**):

**Shape, not a leaked product:**

> A **stream of inputs**. Maintain **derived state**. **Multiple consumers** want different views. **Latency + consistency** constraints.

They then drill:

- Strong vs eventual consistency  
- In-memory vs persistent  
- Push vs pull consumers  
- Shard if volume **doubles**  
- **Node failure during a critical operation**  
- Kafka: ordering, partitions, consumer groups  
- Relational vs KV vs time-series  
- Write-through vs write-behind, invalidation  
- **What you would instrument in production**

He lost the round by giving **generic** failure modes. Equities wanted **message loss, duplication, partial fills, clock skew**. For a **lakehouse / platform VP**, the analogue they will respect: **duplicate events, late data, schema break, poison message, recon break, entitlement miss** — still framed as **software architecture**, not Spark tuning.

**Your Goldman VaR platform is this prompt.** Practice it as SDA, not as a data-eng lecture.

Other senior GS design prompts in the same family (Interview Query GS SWE + System Design Handbook + techinterview.org):

- Real-time **transaction stream** for fraud + reporting  
- Central **event ingestion** pipeline (validate, route, replay, audit)  
- **Market-data** pipeline, millions of events/sec  
- **Risk monitoring** across exchanges  
- Ingest market data and **compute positions** in real time  
- **Order management**: dropped or duplicated message — idempotency, ordering, **reconcile after failure**  
- **Payment / settlement** fault tolerance  
- **Compliance / audit log** (immutable, 18-month replay)  
- Secure **internal messaging** (delivery, sync, retention)  
- **Collateral valuations across regions** (data locality + audit) — Johnny Mai 2026 GS SWE guide, VP-flavoured  
- Payment-API datastore: **functional vs non-functional** (PCI, ACID, audit)

LLD they still use at senior levels (SpaceComplexity): **order book** (limit/market, partial fill, GTC/IOC/FOK) — classes + schema + concurrency. Unlikely as the *whole* VP hour unless they are an execution team; know the shape.

---

## 5. What the two interviewers are scoring (VP rubric)

From the same sources, collapsed:

1. **Resume architecture** — can you explain a real system in 5 minutes and survive “why this store / what failed / how did you know?”  
2. **Decomposition** — APIs, data model, components, not a cloud shopping list  
3. **Concurrency** — Java ThreadPool / atomics if they warm up that way; races on the design  
4. **Trade-offs with a number** — QPS, latency, what you give up  
5. **Failure** — duplicate, loss, timeout, poison, clock skew; **idempotency and recon**  
6. **Ops** — lag, freshness, error budget, not “we’ll add monitoring”  
7. **Finance instincts** — correctness and audit over availability; no floats for money  

They are **not** scoring: LeetCode, Agile ceremonies, or Iceberg vs Delta unless they re-scope the prompt to a data platform.

---

## 6. Practice list for *this* VP SDA hour (priority order)

1. Resume HLD: Goldman risk as **stream → derived state → many consumers** + node death + 2× volume  
2. Rate limiter (talk + sliding-window code) — still the most reported SDA question across levels  
3. Notification service (offline, DB, queues) — confirmed on a **VP offer** loop  
4. URL shortener or real-time chat — ScaleEngineer VP list  
5. Kafka vs Redis, cache invalidation, SQL vs KV vs TSDB — they pile these on after the design  
6. Parking lot / snake & ladder classes — confirmed on a **VP Bangalore offer** Superday  
7. Fraud / market-data / positions pipeline — if they open it up (likely on a data-platform team)  
8. ThreadPool, Callable vs Runnable, microservices vs monolith — same VP offer round  

Spoken scripts: `docs/interviews/GS-SDA-DAYOF-CHEATSHEET.md`.  
Broader question bank: `docs/interviews/GS-VP-LAKEHOUSE-SOFTWARE-DESIGN-ARCHITECTURE.md`.
