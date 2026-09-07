# URL Shortener — High-Level Design (Principal Engineer)

**Level:** Principal  
**Format:** Whiteboard-ready HLD with capacity math, **exhaustive trade-off catalog**, failure modes, interview script  
**Product analogy:** bit.ly / TinyURL / t.co  
**Target scale:** 100 M new links/day · 10 B redirects/day · multi-region · abuse-resistant

How to read this doc: **picks are in bold**. Every pick names what we give up and the condition that would flip it. Section 14 is the full catalog; deep dives in §7 argue the load-bearing ones.

| § | Contents |
|---|---|
| 0 | SLOs, thesis, **decision scoreboard** |
| 1–3 | Framing, requirements, capacity |
| 4–6 | Principles, architecture, API |
| 7 | Hard problems (generation, HTTP, cache, store, RYW, analytics, expiry, region, safety) |
| 8–11 | Failures, observability, evolution, cost |
| 12–13 | Interview timing and Q&A |
| 14 | **Complete trade-off catalog** (product, encoding, HTTP, data, privacy, safety, DR, bolt-ons, fail-policies, anti-patterns) |
| 15 | Why this is principal-shaped |

---

## 0. Executive Summary

### 0.1 Headline SLOs

| Metric | Target | Why it is the SLO |
|---|---|---|
| Redirect p99 (same region, cache hit) | **< 20 ms** | Every click is user-visible; this *is* the product |
| Redirect p99 (cache miss, origin) | **< 80 ms** | Still feels instant; DB/KV round-trip budget |
| Create (shorten) p99 | **< 200 ms** | Write path; uniqueness + durable persist |
| Create → first redirect (read-your-write) | **< 1 s, 99.9%** | User pastes the short link immediately |
| Availability (redirect path) | **99.99%** | Broken links destroy trust; SMS/email/print are unrecoverable |
| Availability (create path) | **99.9%** | Can degrade; existing links must keep working |
| Takedown (malware/phishing) global | **< 30 s** | Safety beats cache hit rate |
| RPO (mapping store) | **0** | A short code that 404s after ACK is a product-breaking bug |
| RTO (region loss, redirects) | **< 60 s** | Anycast failover + replica reads |

### 0.2 Architecture in one sentence

> **Two planes:** a thin, independently scaled **redirect data plane** (edge CDN → local cache → Redis → sharded KV) and a **control plane** (shorten, aliases, auth, billing) that never sits on the click path; clicks are fire-and-forget events; safety takedowns punch through every cache tier.

### 0.3 Core thesis (non-negotiable)

1. **The redirect is the product.** Optimize the click path as a CDN/KV problem, not as a CRUD app.
2. **Split control plane from data plane.** Create/manage/analytics must not share fate with redirects.
3. **Short codes are a one-way door.** Alphabet, length, and non-reuse policy are almost impossible to unwind once links are in the wild (email, SMS, QR, print).
4. **Safety overrides cache.** A phishing link must die globally in seconds even if it is cached at 200 PoPs.
5. **Analytics are eventually consistent.** Never increment a counter on the redirect hot path.

### 0.4 Decision scoreboard (every load-bearing pick)

This is the page you want on the whiteboard at minute 8. Detail and “when I’d flip” live in §7 and §14.

| Decision | **Pick** | We give up | Flip when |
|---|---|---|---|
| Product shape | **Link-mapping CDN + platform API** | Fancy attribution UX in v1 | The actual KPI is campaign analytics, not durable redirects |
| Process split | **Two planes** (redirect ≠ create) | Extra deployables, two SLO dashboards | Toy/internal tool, one team, <1k QPS |
| Code issuance | **Block allocator + base62** | Predictable codes unless permuted; allocator to run | Write QPS tiny → random+retry; org already has Snowflake |
| Hash(long URL) | **No** | Automatic global dedup | Internal wiki where dedup is the feature and URLs aren’t sensitive |
| Custom alias | **Strongly unique, same PK space** | Global coordination for vanity names | Aliases are per-domain (`go.acme.com/jobs`) |
| Alphabet | **base62, 7 chars, case-sensitive wire** | SMS dictation pain; `0/O` confusion | SMS-heavy UX → base58 drop lookalikes, accept 8 chars |
| HTTP status | **302 + Cache-Control, never 301** | Some browsers refetch; slightly more origin than 301 | Link is immutable forever (legal archive, no T&S, no retarget) |
| Edge TTL | **~30–60s + surrogate purge** | Up to TTL of stale redirect if purge lags | T&S SLO 5s → shorter TTL or kill-list push |
| Cache SoT | **KV/SQL is SoT; Redis is L2** | One more miss path | Never. Redis-as-SoT fails RPO 0 |
| Cache write | **Write-through L2 on create** | Extra Redis QPS on writes | Create volume dwarfs first-click rate (unusual) |
| Mapping store v0 | **Single-region Postgres** | Ceiling ~few-k writes/s, vertical | >5k durable writes/s sustained or multi-region writes |
| Mapping store v1 | **Sharded KV (Dynamo/PG shards), PK=code** | Unique alias needs care; less SQL convenience | — |
| Shard key | **hash(short_code)** | “My links” is a second index | Never shard redirect by owner |
| Click path writes | **None** (Kafka, droppable) | Lost clicks under overload | Billing-grade per-click invoices → at-least-once + recon, still async |
| Dedup same URL | **No (optional per-owner)** | More rows | User asked “one code per URL in my account” |
| Expiry | **Tombstone + quarantine, don’t delete** | Storage of dead codes | Storage cost dominates and legal allows purge |
| Code reuse | **No for ≥1 year (prefer never)** | Keyspace waste (irrelevant at 62^7) | Private, short-lived, never printed codes |
| Multi-region v1 | **Single-writer mappings, local redirect reads** | Create latency from far-away users | Regional ID blocks + global alias index |
| Alias uniqueness | **CP: refuse on conflict** | Create can 409 | Never merge two owners of `jobs` |
| Redirect CAP | **AP + stale-OK** | Retarget may lag | Wrong-URL risk > 404 risk (then fail closed on unknown) |
| Safety on create | **Sync blocklist; async full scan; fail closed if scanner stale** | Some false rejects; slower create | Internal-only shortener, trusted URLs |
| Takedown vs cache | **Purge is part of the API, not best-effort** | Takedown latency includes CDN | No public internet / no T&S obligation |
| Public create | **Auth + API keys** | Friction | Classic public bit.ly clone (then brutal rate limits) |
| Interstitial page | **No on default click path** | Less warning UX | Legal mandates warning for all redirects |
| Analytics PII | **Hash IP, bucket UA, short retain** | Weaker unique-user stats | Compliance forbids even hashed IP → drop it |
| Build vs buy | **Build if this is a platform other teams will call** | Ops cost | One team, no T&S, Bitly/Rebrandly SLA is enough |

---

## 1. Problem Framing

A Staff Engineer designs “POST a URL, GET a 302.” A Principal Engineer asks whether this is a **link-mapping CDN**, a **growth/attribution platform**, or a **branded domain product** — because those three systems share a short code and almost nothing else.

### 1.1 The five pre-design questions

| Question | Default for this design | If the answer were different |
|---|---|---|
| **Why are we building this?** | Durable, trustworthy redirects at global scale (internal `go/` links, SMS/email, partner APIs) | If the goal is *attribution dashboards*, the system is an event pipeline with a tiny mapping store on the side |
| **Who owns the blast radius?** | Every SMS, receipt, QR code, and third-party embed that already printed a link | Redirect downtime is not “an API blip”; it is broken physical artifacts |
| **What is the one-way door?** | Code alphabet/length, 301 vs 302, and whether expired codes are reused | Changing any of these after launch either breaks links or leaks old destinations |
| **What does success look like in two years?** | Other teams consume this as a **platform** (notifications, receipts, campaigns) via API + branded domains | If it stays a single-app toy, a 200-line service is enough — do not overbuild |
| **Org constraint?** | One platform team owns the mapping + redirect fleet; product teams own analytics UX | Do not let every squad stand up its own shortener (collision, abuse, no takedown) |

**What to say in an interview:**

> “Before boxes: this is not a hash function plus Postgres. It is a globally cached, strongly durable key-value mapping with a Zipfian read distribution, a safety SLA that fights caching, and a code space we cannot revise once QR codes are printed. I will split redirect from create, treat short-code policy as a one-way door, and keep click counting off the hot path.”

### 1.2 What we are actually solving

- Can a user create a link and click it **immediately** (read-your-write)?
- Can a viral link take **1M QPS** without melting origin?
- If we ACK a shorten, will that mapping **survive a region dying**?
- If Trust & Safety disables a phishing URL, is it dead **everywhere** in < 30 s — including browser and CDN caches?
- Can two users independently pick the custom alias `jobs` without a uniqueness race?

This is **not** “pick MD5 and store it.” The hard problems are **code-space policy, cache vs takedown, uniqueness at write, and isolating the click path.**

---

## 2. Requirements

### 2.1 Functional

| Area | Requirements |
|---|---|
| **Shorten** | Create a short code for a long URL; optional custom alias; optional TTL/expiry |
| **Redirect** | `GET /{code}` → HTTP redirect to long URL |
| **Resolve** | Headless/API resolve (preview, QR, bots) without always following the browser redirect |
| **Lifecycle** | Disable, retarget (change destination), expire; never silently 404 a code we ACKed |
| **Ownership** | Authenticated create; list/edit my links; API keys for partners |
| **Analytics** | Click counts, referrer, geo, UA — **near-real-time, eventual** |
| **Safety** | Malware/phishing scan on create; takedown; blocklists; rate limits |
| **Branding (v2)** | Custom domains (`go.acme.com/x`) mapped to the same control plane |

### 2.2 Non-functional

| Property | Target |
|---|---|
| Redirect latency | p99 < 20 ms cached; < 80 ms origin |
| Create latency | p99 < 200 ms |
| Consistency (create → redirect) | Read-your-write for the creator; other regions < few seconds |
| Consistency (custom alias) | Strong — unique among active codes |
| Durability | Sync persist before create ACK (RPO 0) |
| Redirect availability | 99.99%; create may fail independently |
| Access pattern | Extremely Zipfian: a tiny fraction of codes take almost all traffic |
| Multi-tenant | Partner rate limits, per-domain quotas, abuse isolation |

### 2.3 Out of scope (v1)

- Full marketing-attribution suite (UTM studio, A/B destinations beyond simple retarget)
- Link-in-bio page builder
- Pixel-perfect preview cards for every social network (async, best-effort later)
- Active-active writes in every region on day one (see §7.8)
- User-defined redirect rules / geo-steering as a first-class v1 feature

---

## 3. Scale Assumptions (Capacity Model)

| Dimension | Number | Notes |
|---|---|---|
| New links / day | 100 M | ~1,160/s average; ~5–10k/s peak |
| Redirects / day | 10 B | ~115 k/s average; **~1 M/s peak** (campaigns, TV, viral) |
| Read:write | ~100:1 | Redirects dominate; design for reads |
| Active codes (5 years) | ~180 B | 100 M × 365 × 5; many never clicked |
| Hot codes | ≪ 0.01% | Classic Zipf; CDN + in-process cache exist for this |
| Avg long URL | ~200 B (p99 ~2 KB) | Cap length (e.g. 2–8 KB) |
| Short code | 7 chars base62 | See keyspace math |
| Metadata / row | ~500 B | code, URL, owner, flags, timestamps |
| Click event | ~100–200 B | code, ts, IP-hash, UA-class, referrer-class |

**Storage (5 years, uncompressed):**  
180 B × 500 B ≈ **90 TB**. Comfortable for a sharded KV / clustered SQL. Compression + cold-tiering drops working set further. **Do not pick Cassandra because “90 TB is big.”** It is not.

**Redirect bandwidth at 1 M QPS:**  
Tiny responses (headers + Location). Origin should see **far less** than 1 M QPS because of edge cache. Budget origin for **cache-miss + takedown-bypass** traffic, not for every click.

**Keyspace math (the slide you must put on the board):**

```
Alphabet: 0-9 a-z A-Z  →  62 symbols   (drop 0/O/l/I in UX if you want; then ~58)

62^6  ≈ 56.8 B     — too tight for 180 B lifetime codes
62^7  ≈ 3.52 T     — ~20× headroom at 180 B; default
62^8  ≈ 218 T      — if you include custom aliases + quarantine + growth

At 100 M/day, 62^7 lasts ~96 years of sequential issuance.
The constraint is not exhaustion. The constraints are collisions (random),
reserved words, custom aliases, and non-reuse quarantine.
```

**Back-of-envelope that changes the design:**  
If 1% of codes are “hot” enough to live in cache, that is still millions of keys — too big for one box’s RAM if you cache everything. **Cache by popularity, not by “all codes.”** Let the edge and an LRU do the Zipf work.

---

## 4. Design Principles

Every later decision traces to one of these.

| # | Principle | Implication |
|---|---|---|
| P1 | **Redirect path is sacred** | No authz DB, no analytics write, no JSON serializer on `GET /{code}` |
| P2 | **ACK means durable** | Create returns 201 only after the mapping is fsynced / replicated per the store’s durability config |
| P3 | **Eventual analytics, immediate safety** | Clicks may drop; takedowns may not |
| P4 | **Codes are never casually reused** | Expired/disabled codes enter a quarantine (months–years), not the free pool |
| P5 | **Fail open for reads, fail closed for writes and safety** | If Redis is down, read from KV; if KV is down, serve stale cache; if safety service is down on *create*, reject or quarantine the URL |
| P6 | **One-way doors get extra review** | Alphabet, length, redirect status, cookie/PII on click logs |

**Principle trade-offs (what each principle costs):**

| Principle | Cost we accept | If we dropped it |
|---|---|---|
| P1 sacred redirect | Two services, duplicate authz *flags* on the mapping row | One binary: product deploys page redirect |
| P2 ACK=durable | Create p99 includes fsync/replica; cannot return 201 from cache | Fast 201 then 404 — trust-destroying |
| P3 droppable clicks | Partner dashboards are ±ε; need recon for money | Redirect p99 dies with Kafka |
| P4 no casual reuse | Tombstones forever (cheap vs 62^7) | QR codes become attack surface |
| P5 fail-open reads | May serve stale destination for TTL | Redirect 5xx during Redis blips — worse UX than 1s stale |
| P6 one-way doors | Slow product debates up front | 18-month migration to change 301→302 that browsers ignore |

---

## 5. High-Level Architecture

### 5.1 Planes and seams

```
                         ┌─────────────────────────────────────────────────┐
                         │                     EDGE                        │
                         │  Anycast · TLS · WAF · DDoS · CDN (redirects)   │
                         │  API Gateway (control plane only)               │
                         └───────────────┬───────────────┬─────────────────┘
                                         │               │
                    GET /{code}          │               │  POST /v1/links
                    (data plane)         │               │  GET  /v1/links
                                         ▼               ▼
                         ┌───────────────────┐   ┌────────────────────┐
                         │  REDIRECT FLEET   │   │  CONTROL PLANE     │
                         │  stateless        │   │  Shorten Service   │
                         │  L1 process LRU   │   │  Alias / Auth /    │
                         │  coalesce misses  │   │  Billing / Admin   │
                         └─────────┬─────────┘   └─────────┬──────────┘
                                   │                       │
                    ┌──────────────┼──────────┐            │
                    ▼              ▼          ▼            ▼
              ┌──────────┐  ┌──────────┐  ┌─────────────────────────┐
              │ Redis    │  │ Mapping  │  │ Mapping Store (SoT)     │
              │ (L2)     │  │ Store    │  │ sharded by short_code   │
              │ hot KV   │  │ replica  │  │ + unique(alias)         │
              └──────────┘  └──────────┘  └────────────┬────────────┘
                    │                                  │
                    │ click event (async, droppable)   │ CDC / outbox
                    ▼                                  ▼
              ┌──────────────────────────────────────────────┐
              │  Click bus (Kafka) → analytics warehouse     │
              │  Safety scanner · takedown · audit           │
              └──────────────────────────────────────────────┘
```

**Seam rule:** the Redirect Fleet’s only synchronous dependencies are **cache + mapping store**. Safety verdicts are **flags on the mapping row** (and cache purge), not a runtime RPC to a scanner.

### 5.2 Why this boundary (Conway)

| Component | Owns | Team |
|---|---|---|
| Redirect fleet + edge cache policy | Latency, 99.99% availability, purge | Platform / traffic |
| Mapping store + code issuance | Durability, uniqueness, schema | Platform / datastore |
| Control plane API | Product features, auth, quotas | Link product |
| Safety | Blocklists, scanners, takedown SLO | Trust & Safety |
| Analytics | Click pipeline, dashboards | Data / growth |

Redirect engineers must be able to ship a cache or failover change **without** a product-API deploy. If those are one binary, you will page the wrong people and couple release cadence.

**Monolith vs two planes:**

| | One binary | **Two planes (pick)** | Three+ (redirect, create, alias, safety as services) |
|---|---|---|---|
| **Gain** | Simple ops, one deploy | Independent SLO, scale, and on-call | Max team autonomy |
| **Lose** | Product release pages clicks; mixed resource pools | Two artifacts, contract tests, flag schema sync | Distributed transactions on create; over-org |
| **Flip** | Internal tool, one squad | Default at this scale | Only after two planes have different owners *and* change rates |

### 5.3 Request flows

**Create (control plane):**

```
Client → API GW (auth, rate limit, WAF)
      → Shorten Service
         1. Validate URL (scheme, length, not-javascript, not-private-IP)
         2. Safety pre-check (blocklist / hash / async full scan)
         3. Allocate code (range counter or custom alias lock)
         4. INSERT mapping (durable)
         5. Optional write-through to Redis (new links are clicked immediately)
         6. Return short URL
      → async: full malware crawl, preview card, owner index
```

**Redirect (data plane):**

```
Client → Edge CDN
   HIT  → 302/301 + Location (+ Cache-Control / Surrogate-Key)
   MISS → Redirect fleet
            L1 local LRU
            L2 Redis
            L3 Mapping store
         → set caches
         → return redirect
         → async emit click event (best-effort)
```

No `UPDATE click_count` on this path. Ever.

---

## 6. API Surface

Keep the public click URL **ugly-simple**. Everything else is versioned control-plane HTTP.

```
# Data plane (no auth, tiny surface)
GET  /{code}                  → 302/307 Location: <long_url>
HEAD /{code}                  → same headers, no body

# Control plane
POST /v1/links
     { url, custom_alias?, expires_at?, domain? }
     Idempotency-Key: <client uuid>
     → 201 { id, code, short_url }

GET  /v1/links/{id}
PATCH /v1/links/{id}          → retarget, disable, extend TTL (If-Match)
POST /v1/links/{id}:disable
GET  /v1/links?cursor=

# Internal / T&S
POST /internal/takedown       { code|url, reason }  → persist flag + purge
```

**Idempotency on create:** partners retry. Same `Idempotency-Key` returns the same code; do not mint a second mapping. Store key → result next to the insert (unique constraint), same pattern as payments.

**Preview vs redirect:** some clients (Slack, iMessage) fetch `GET /{code}` with a bot UA. Decide explicitly: still 302 (they follow), or `GET /v1/links/{code}/preview` for cards. Do not put HTML interstitial on the default click path unless product insists — it destroys SMS UX.

**API / product-surface trade-offs:**

| Decision | Options | **Pick** | Give up |
|---|---|---|---|
| Click URL shape | `/{code}` vs `/s/{code}` vs `x.co/{code}` | **`/{code}` on a dedicated host** (`ln.example`) | Path `/s/` is safer if the same host also serves a website (collision with `www`, `api`) |
| Dedicated host vs path on www | Collision-free vs one cert | **Dedicated host** | Extra domain, cookie isolation (a feature) |
| Idempotency | None / **Idempotency-Key** / hash(body) | **Key header** (body-hash collides if user retries a *changed* URL) | Clients must store the key |
| List “my links” | Scan mapping by owner vs **owner index table** | **Secondary index / table** | Dual write on create |
| PATCH retarget | Allowed vs immutable | **Allowed + audit** | 301 users would be stuck anyway — another 301 reason |
| Versioning | `/v1` on control only | **Yes**; data plane unversioned | Click URLs cannot be `/v2/{code}` without breaking print |

---

## 7. Deep Dives — The Hard Problems

### 7.1 Short-code generation (the interview center of gravity)

Four real options. Pick with a reason, do not recite all four as equals.

| Approach | How | Pros | Cons | When |
|---|---|---|---|---|
| **A. Hash(long URL)** | `base62(sha256)[0:7]`, retry on collision | Deterministic; natural dedup | **Privacy leak** (probe if a URL exists); collisions; same URL always same code (may be unwanted); hash prefix is not a unique ID | Almost never for a public shortener |
| **B. Random 7-char + insert** | `secure_random` in alphabet; INSERT; retry on PK conflict | Dead simple; no counter hotspot | Retry under load; must use crypto-quality RNG; slightly more collisions as fill factor grows | Honest **v0 / v1** if write QPS is modest |
| **C. Range-allocated counter + base62** | Central allocator hands blocks of 10k–1M IDs; encode | Compact sequential-ish codes; no per-request coordinator; well-understood | Predictable codes (enumeration); need allocator HA; leftover unused IDs in a crashed box’s block | **Default at this scale** |
| **D. Snowflake → base62** | 64-bit ID, then encode | Unique without a URL-specific allocator; time-sortable internally | Codes look longer / less dense than a packed counter; clock issues | If you already run Snowflake as platform ID gen ([`UNIQUE_ID_GENERATOR_SYSTEM_DESIGN.md`](UNIQUE_ID_GENERATOR_SYSTEM_DESIGN.md)) |

**Recommendation:**

> **Issued IDs, not hashes.** Use **C (block allocator + base62)** for default codes. Use a **separate strongly-consistent unique index** for custom aliases. Do **not** hash the long URL: it couples two users’ links, enables existence oracles, and fights “two campaigns, same landing page, two codes.”

**Block allocator sketch:**

```
Allocator (HA, not on redirect path):
  next_block = atomic increment of high-water mark by BLOCK_SIZE (e.g. 100_000)
  lease to shorten-instance with TTL; heartbeat
  on instance crash: block may have unused IDs → accept gaps (P4: gaps are fine)

Shorten instance:
  pop next int from local block
  code = base62(id)  // left-pad to 7 chars if you want fixed width
  persist mapping
```

Gaps after crash are **required**, not a bug. Sequential IDs without gaps mean a single hot counter and a tempting enumeration attack.

**Enumeration / abuse:** sequential base62 is guessable (`…aaa`, `…aab`). Mitigations (layer, don’t pick one):

- Skip a secret permutation (Feistel / format-preserving encrypt of the integer before base62) so IDs are unique but not sequential-looking
- Rate-limit unknown-code 404s per IP / ASN
- Do not leak “this code existed but was disabled” vs “never existed” if that helps attackers (product call; T&S often wants distinct admin views)

**Custom aliases:** treat as a **different namespace** with a uniqueness constraint. Check reserved words (`www`, `admin`, `api`, `static`, `health`). Case-fold (`Jobs` = `jobs`). Unicode: **reject** or punycode-normalize — homograph phishing is a real shortener bug class.

**Alphabet / length / padding / obfuscation:**

| Decision | Options | **Pick** | Give up | Flip |
|---|---|---|---|---|
| Base | hex (16), base58, base62, base64url | **base62** | Lookalike chars (`0/O`, `1/l/I`) | SMS/print-heavy → **base58** (drop lookalikes), codes grow to 8 |
| Length | 6 / 7 / 8 | **7** | 6 is too tight at 180 B lifetime; 8 wastes UX | Custom aliases stay variable length |
| Padding | `base62(id)` vs left-pad to 7 | **Fixed width 7** for issued codes | Slightly leaks issuance era if not permuted | Variable length looks “shorter” early, then breaks clients that assumed 6 |
| Case | Sensitive vs fold | **Fold on input, store canonical lower for aliases; issued codes can use mixed** | Mixed issued codes are harder to read aloud | All-lower issued codes if support burden dominates (costs ~1 bit of alphabet if you drop A-Z → base36, then need 8 chars: 36^8 ≈ 2.8 T) |
| Sequential look | Raw counter vs Feistel/FPE before base62 | **FPE permutation of the integer** | Need a secret key; rotation is a one-way door | Skip FPE only if 404 rate-limits are enough and codes aren’t enumerable via API |
| Key pool | On-demand block vs pre-generated table of unused codes | **On-demand blocks** | Crash leaves gaps | Pre-gen if you must hand keys to offline printers in batches |

**Same-URL dedup policy:**

| Policy | Gain | Lose | **Pick** |
|---|---|---|---|
| Global: one code per long URL | Storage; “already shortened” | Existence oracle; two campaigns share a code; privacy | **No** |
| Per-owner unique (url, owner) | Cleaner dashboards | Cross-owner still duplicates | **Optional product flag** |
| Always new code | Isolation, retarget independence | More rows (cheap) | **Default** |

**Issued-code vs custom-alias uniqueness implementation:**

| | App check-then-insert | **DB unique constraint (pick)** | Distributed lock (etcd) then insert |
|---|---|---|---|
| Race | Two winners | One winner, one 409 | Works, extra dep |
| Ops | Looks simple, is wrong | Need a real unique index (harder on some KV stores) | Another SPOF on create |
| Flip | Never for aliases | Default | Only if the store cannot enforce uniqueness (then strongly consider a different store) |

### 7.2 301 vs 302 vs 307 — the second one-way door

This is the most common follow-up. Answer at the HTTP-semantics + **cache-control** level, not “301 is permanent.”

| Status | Browser behavior | Analytics | Retarget / takedown | Typical use |
|---|---|---|---|---|
| **301** | Cached aggressively, often **forever**, ignoring your TTL in older clients | You **lose** subsequent hits | Cannot reliably change destination or kill the link | Static, never-retarget, never-measure (rare for a product shortener) |
| **302** | Historically cached less; still not a promise | Hits origin (or edge) each time if you forbid store | Can retarget; takedown works if caches honor you | **Default for product shorteners** |
| **307** | Like 302 but **must not** change method (POST stays POST) | Same as 302 | Same | Correct if you care about method preservation |
| **303** | GET the Location even if original was POST | — | — | Rare for this product |

**Principal move:** do not rely on status code alone. **You own cache policy:**

```
HTTP/1.1 302 Found
Location: https://example.com/landing
Cache-Control: public, max-age=60
Surrogate-Control: max-age=60
Surrogate-Key: url:{code}
```

- Edge caches for **60s** (tune): viral QPS dies at the CDN.
- Takedown calls **purge by surrogate key** across PoPs, then sets mapping `status=disabled`.
- Browsers still may cache 301 forever — **that is why we do not issue 301** for links we might disable.

**Edge case:** a 60s TTL means a disabled phishing link can still redirect for up to 60s plus purge lag. If T&S SLO is 30s, TTL must be ≤ that, **or** purge must be synchronous in the takedown path (wait for purge ACK from major PoPs). State the tension: **hit rate vs kill time.** That is the job.

**TTL as a knob, not a constant:**

| Edge `max-age` | Origin offload | Takedown bound (without purge) | **Use when** |
|---|---|---|---|
| 0 (no-store) | None — origin sees every click | Immediate | Tiny scale, or legal “must log every hit at origin” |
| 5–15 s | Good for hot codes | Fits a 30 s SLO even if purge is slow | High T&S bar |
| **30–60 s (pick)** | Absorbs TV/viral QPS | Needs **purge** to hit 30 s SLO | Default public shortener |
| 5–60 min | Max offload | Cannot takedown fast | Immutable / internal / no abuse |
| 301 + year | Browser does your job | You cannot takedown | Almost never |

**307 vs 302 extra:** 302 is widely understood; some clients historically converted POST → GET (usually irrelevant for `GET /{code}`). **Pick 302** for GET clicks. If you ever redirect non-GET (you shouldn’t on the public path), use **307**.

### 7.3 Cache hierarchy (how you actually survive 1M QPS)

Junior design: “Redis in front of SQL.”  
Principal design: **three layers + coalescing**, sized for Zipf, with stampede control.

```
L0  CDN / edge     — 60s, surrogate keys, absorbs TV-spot traffic
L1  in-process LRU — microseconds; per-box; 10k–100k hot codes
L2  Redis cluster  — shared hot set; TTL aligned with safety budget
L3  Mapping store  — source of truth; sharded by code
```

**Negative caching:** cache 404 for **short** TTL (1–5s) to stop enumeration storms, but **never** negative-cache a code that was just created (see read-your-write).

**Thundering herd:** on L2 miss for a hot code, **singleflight / request coalescing** in the redirect process (one origin fetch, waiters share). Without this, a TTL expiry on a Super Bowl link equals a self-inflicted DDoS.

**What to cache:** `{code → (long_url, status, cache_gen)}`. If `status != active`, serve 410/404 from cache. Include a **generation / updated_at** so retarget invalidates correctly.

**Write-through on create:** the next click is almost certainly the creator testing the link. Put it in L2 on insert so the first click is not a cold miss in another AZ.

**Cache policy trade-offs:**

| Policy | Gain | Lose | **Pick** |
|---|---|---|---|
| Write-through L2 on create | Read-your-write; first click hits cache | Write path depends on Redis (must **not** fail create if Redis is down — best-effort fill) | **Yes, best-effort** |
| Write-around (fill on first read) | Create stays dumb | Creator’s first click misses; 404 window if replica lags | Only if create QPS >> first-click QPS |
| Cache only at CDN | Simplest origin | Origin stampede on every TTL; no write-through | Never as the only layer at this QPS |
| Cache entire mapping table in RAM | p99 tiny | 90 TB does not fit; cold codes waste RAM | No — Zipf LRU only |
| Negative cache 404 | Stops enumeration | Create then click can 404 | **1–5 s TTL**, skip if code is in “recently issued” Bloom |
| TTL jitter | Avoids synchronized expiry of one viral key across the fleet | Slightly messier math | **Yes** (±10%) |
| Singleflight coalescing | One L3 fetch per hot miss | Tiny complexity; waiters share errors | **Yes — non-negotiable at viral QPS** |
| `stale-while-revalidate` | Smooth origin load | Serves old Location while refreshing | **Yes for redirects**; **no** once `status=malware` (must not SWR a taken-down link) |

### 7.4 Mapping store and sharding

**Access pattern:** point lookup by `short_code` (redirect), point write on create, occasional update (retarget/disable). No joins on the hot path.

**Store options:**

| Store | Verdict |
|---|---|
| **DynamoDB / Cassandra / Scylla (PK = code)** | Excellent for this key-value shape; TTL native; multi-AZ |
| **MySQL/Postgres sharded by hash(code)** | Fine to a large scale; operationally familiar; unique alias is a SQL unique index |
| **Single primary Postgres** | Acceptable v0 (100 M/day is ~1k writes/s — a good PG handles that). **Say this.** Over-sharding on day one is a staff-minus failure. |
| **Redis as SoT** | No. Persistence/ops story is wrong for “ACK means forever.” Redis is L2. |

**Store trade-offs spelled out:**

| | Single PG (v0) | **PG/MySQL shards by hash(code)** | DynamoDB / Cassandra PK=code | Spanner / Cockroach |
|---|---|---|---|---|
| **Gain** | One box, SQL, unique indexes, cheap | Familiar + scale writes | Multi-AZ KV, TTL, predictable p99 | Global strong uniqueness |
| **Lose** | Vertical ceiling; one-region | Reshard pain; 2PC if you need cross-shard alias *and* mapping (so **don’t**: alias is the PK) | Unique *secondary* indexes cost $$ / eventual; CQL/PartiQL; ops culture | Cost; overkill if aliases are the only global unique |
| **Click path** | Fine | Fine | Fine | Fine |
| **Flip to this** | <~3–5k durable writes/s | Default v1 if you already run SQL | Default v1 if you already run AWS KV | Alias-global + multi-region writes from day one |

**Redis-as-SoT is not a delayed decision.** AOF/RDB is not a 5-year QR-code durability contract. Use Redis for L2 only.

**Encryption / URL at rest:**

| | Plaintext in SoT | **Encrypt long_url at rest (app or KMS envelope)** | Tokenize destination in a secrets store |
|---|---|---|---|
| **Gain** | Simple debug | Disk/backup leak is less catastrophic | Max isolation |
| **Lose** | Backup = all destinations | Search-by-URL hard; key rotation | Extra hop on redirect — **violates P1** |
| **Pick** | Internal only | **Public shortener: encrypt at rest, decrypt in redirect memory** | Never on the click path |

**Schema (logical):**

```text
mappings
  short_code        PK          -- canonical, case-normalized
  long_url          text        -- length-capped
  owner_id          bytes
  status            enum        -- active | disabled | malware | expired
  is_custom         bool
  expires_at        timestamptz null
  cache_gen         int         -- bump on every retarget/takedown
  created_at
  updated_at

unique (short_code)             -- trivial PK
-- custom aliases live in the same PK space
-- optional: unique (owner_id, long_url) only if product wants per-owner dedup

idempotency_keys
  key               PK
  owner_id
  response_code
  response_body
  created_at
```

**Click counts do not live on this row.** A hot code would serialize updates on one row and destroy the mapping store.

**Shard key:** `hash(short_code)`. Not `owner_id` — redirect must not fan out, and virality is per code not per user.

**Secondary indexes:** `owner_id` for “my links” is a **control-plane** query. Satisfy with a separate owner-index table or search cluster, not by scanning the mapping shards.

### 7.5 Read-your-write after create

User flow: create → copy → paste in a new tab **now**.

If create wrote to region A primary and redirect in region B reads a replica with 500ms lag, you get a **404 on a link you just issued**. That is a P0 product bug, not an “eventual consistency” shrug.

**Options:**

1. **Write-through to L2 in all regions** on create (or to a global cache) — practical if Redis/memorystore is global or replicated quickly.
2. **Sticky create+redirect in one region** via cookie/geo for a few seconds — fragile for copy-paste across devices.
3. **Synchronous replicate the new key** (or use a globally strongly consistent write: DynamoDB global tables with careful conflict rules, Spanner, etc.).
4. **Return the code only after replica ACK** in the regions that serve redirects.

**Pick for v1:** single-region **strong write** + **async replica** for DR, plus **write-through to a globally reachable L2**. Redirect miss that 404s can **hedge-read primary** for recently issued codes (e.g. `created_at` within 2s, or a Bloom of recent codes). Keep it simple: **global cache fill on create** solves 99% of this.

| Option | Latency | Correctness | Fragility | **Verdict** |
|---|---|---|---|---|
| Write-through global L2 | Low | High for hot path | Redis global replication lag still exists | **v1 default (best-effort + durable SoT)** |
| Sticky region cookie | Low if sticky | Breaks copy-paste across devices/CDNs | High | Reject as primary |
| Sync replicate to all regions before 201 | High create p99 | Strong | Cross-region write SLO | v2 if create is global and 201 must imply every PoP |
| Hedge-read primary on 404 | Extra p99 on cold 404s | Fixes the creator race | Primary becomes a 404 amplifier (enumeration!) | **Only if combined with “recently issued” filter**, not on all 404s |
| Client returns long URL too | Zero race for creator | Doesn’t help the *shared* short link | Clients ignore | Nice extra, not sufficient |

### 7.6 Analytics off the hot path

```
Redirect fleet  --best effort-->  local buffer  -->  Kafka (partition by code or by time)
                                      |
                                      +--> drop if buffer full (P3)
                                              |
                                              v
                                    stream jobs: counts, geo, UA
                                    warehouse: ClickHouse / BigQuery
                                    serving: pre-agg by code + hour
```

**Do not** `INCR` Redis on every click as SoT for billing-grade counts unless you accept loss and have a recon pipeline. Redis INCR is a **good approximate dashboard**; Kafka + warehouse is **the** analytics system.

**PII:** IPs and raw UAs are toxic. Hash/truncate IP, bucket UA, drop on a short retention. This is a one-way door with legal.

**Approximate vs exact:** HyperLogLog unique clicks are fine for dashboards. Finance/partner billing needs a defined reconciliation story (usually “at-least-once events + daily exact agg”).

**Analytics pipeline trade-offs:**

| Approach | Redirect p99 | Accuracy | Cost | **Pick** |
|---|---|---|---|---|
| `UPDATE click_count` on mapping row | Destroyed on hot keys | Exact until the row melts | Low until it isn’t | **Never** |
| Redis `INCR` as SoT | OK if async | Loss on flush/failover | Cheap | Dashboard only, labeled “approx” |
| **Local buffer → Kafka (pick)** | ~0 (drop under pressure) | At-least-once + consumer idempotency | Bus + warehouse | **Default** |
| Sample 1% of clicks | Tiny | Bad for rare codes | Cheapest | Extra layer under extreme load, not SoT |
| Dual-write Redis + Kafka | OK | Two systems diverge | Highest | Only if product demands live counters *and* warehouse |
| SQS per click | OK | At-least-once | $ at 10 B/day | Prefer Kafka/PubSub at this volume |
| Partition by `code` | Hot partition on viral code | Ordered per code | — | **Partition by `code % N` plus random salt, or by time** — do not pin a celebrity code to one partition |

**Bot / HEAD / prefetch counting:**

| Event | Count as click? | **Pick** |
|---|---|---|
| Human GET | Yes | Yes |
| `HEAD /{code}` | Often Slack/iMessage unfurl | **No** (or separate “preview” metric) |
| Known bot UA (Googlebot, Slackbot) | Inflates campaigns | **Exclude from “clicks”, keep in “fetches”** |
| Prefetch / `Purpose: prefetch` | Chrome may fetch without a click | **Exclude** |
| 302 from CDN edge | You never saw the body | **Log at edge if you need exact**; else accept undercount vs origin logs |

Trade-off: **edge-accurate analytics** means the CDN must emit logs (cost, delay, sampling). **Origin-accurate** undercounts cache hits. Principal default: **edge logs sampled + origin events for misses**; dashboard copy says “approximate, cache-adjusted.” Never pretend CDN-offloaded clicks were counted at origin.

### 7.7 Expiry, retarget, and non-reuse

- **TTL on the mapping row** + periodic sweeper (or native KV TTL **plus** an audit tombstone — native TTL that deletes the row makes “what was this QR code?” unanswerable).
- Prefer **status=expired tombstone** with `expires_at` for as long as you quarantine.
- **Retarget** (change `long_url`): bump `cache_gen`, purge surrogate key, write-through new value. Old 301 browser caches will **not** follow — another reason to avoid 301.
- **Reuse of codes:** after expiry, keep the code dead for a **quarantine** (e.g. 1 year). Printed QR codes outlive product managers. Reuse is how you redirect a hospital poster to a phishing domain.

| Expiry mechanism | Gain | Lose | **Pick** |
|---|---|---|---|
| Native KV TTL that **deletes** the row | Free GC | Cannot explain a printed QR; reuse races | No |
| **Tombstone `status=expired` (pick)** | Audit, non-reuse, cheap 410 | Table growth | **Yes**; compact cold tombstones to cheaper storage |
| Sweeper job only (no native TTL) | Full control | Expired links work until the job runs | Combine: `expires_at` checked **on read** + sweeper for GC |
| Check `expires_at` only in cache, not SoT | Fast | Inconsistent after cache miss | Always check SoT fields in the cached blob |

| Reuse policy | Keyspace | Safety | **Pick** |
|---|---|---|---|
| Reuse immediately | Max density | QR/email become hijackable | No |
| Quarantine 30 days | Fine | Misses annual campaigns | Weak |
| **Quarantine ≥ 1 year / never (pick)** | Irrelevant at 62^7 | Tombstone storage | **Default never for public; 1 year min if you must reclaim** |
| Reuse only non-custom, never vanity | Slightly denser | Vanity is the one people tattoo | Yes as a refinement |

**Retarget vs disable vs delete:**

| Op | Mapping | Cache | Analytics | **Pick** |
|---|---|---|---|---|
| Retarget | Update URL, bump `cache_gen` | Purge | New destination, same code | Allowed if product wants; **audit log** |
| Disable | `status=disabled` | Purge | 410 | **Default “delete” for users** |
| Hard delete row | Gone | Negative cache | History orphaned | **Internal GC only after quarantine** |

### 7.8 Multi-region

**Redirects** want **anycast + local reads**. **Creates** want **one uniqueness authority** for a given code.

```
v1 (recommended):
  Active-passive or active-read / single-writer for mappings
  Redirects: read local replica + global L2
  Failover: promote replica (RTO minutes) or multi-AZ in one region first

v2:
  Regional write for default (issued) codes if ID blocks are partitioned by region
    e.g. high bits = region, no cross-region uniqueness chat
  Custom aliases: still a global unique resource
    → global allocator, or per-domain uniqueness, or “alias is region-sticky”
```

**Custom aliases are the distributed-systems tax.** `go/jobs` must exist once. That is a **globally unique name**, same class as a DNS label. Do not pretend CRDTs will merge two owners of `jobs`. Use a single-writer alias index or a strongly consistent global table for `is_custom=true` only.

**Region failure:** redirects serve from replica (possibly stale retarget — acceptable vs 404). Creates fail or divert to another writer. Takedown must still reach all edge caches (multi-region purge).

| Topology | Redirect | Create uniqueness | Cost | **When** |
|---|---|---|---|---|
| **Single region multi-AZ (v0/v1 pick)** | Local, fast | Trivial | Lowest | Default until create latency or DR law requires more |
| Active-passive DR | Failover minutes | Same | Replica $$ | Board-level DR, RTO minutes |
| **Active-read / single-writer (v1 global)** | Anycast + replica reads | One writer | Replica + lag | Global users, one uniqueness authority |
| Regional writers for issued IDs (region bits in ID) | Local create | Issued codes unique by construction | Alias index still global | **v2 pick** for create p99 |
| Active-active writes all codes | Local everything | Conflict on aliases; CRDT cannot merge owners | Highest | **Don’t** |
| Multi-primary with last-write-wins on mapping | Looks easy | **Wrong URL wins** — nightmare | — | **Never** |

**DNS / traffic steering:**

| | GeoDNS to region | **Anycast edge (pick)** | Client-side region pin |
|---|---|---|---|
| Failover | DNS TTL (slow) | BGP, seconds | App logic |
| Cache | Per-region CDN | Global PoPs | — |
| Flip | Cheap multi-region without anycast | Default at this QPS | Mobile apps only |

### 7.9 Abuse, malware, and the cache conflict

At scale, **the shortener is an attack platform**: phishing, malware, spam SEO, botnet C2.

**On create:**

- Scheme allowlist (`https`, maybe `http`)
- Reject `javascript:`, `data:`, credentials-in-URL, link-to-localhost/metadata-IP (SSRF if you fetch the URL)
- Rate limit per API key / IP / ASN
- Sync blocklist (domains, URL hashes)
- Async headless fetch + Safe Browsing / commercial scanner; flip `status=malware` when it hits

**On redirect:**

- If `status != active` → 410 Gone (or interstitial **only** for suspected malware — product/legal)
- Do not proxy the destination (you are not an open proxy)

**Takedown path (must be a runbook, not a slide):**

1. Write `status=disabled|malware` to SoT (strong).
2. Bump `cache_gen`.
3. Purge L2 + CDN surrogate key; wait for purge SLO or fail the takedown API.
4. Optionally push an L1 “kill list” of recently taken-down codes to the fleet (small, versioned).
5. Audit log for legal.

If step 3 is “best effort,” you do not have a 30s safety SLO.

**Safety trade-offs:**

| Decision | Options | **Pick** | Give up |
|---|---|---|---|
| Scanner on create | Sync full crawl vs **sync blocklist + async crawl** vs none | **Blocklist sync, crawl async** | Brief window until crawl returns (mitigate: `pending_scan` + interstitial **or** delay 201) |
| 201 before crawl done | Fast UX vs hold 201 | **201 after blocklist only**; crawl may later malware-flag | User can share a still-pending link (T&S risk). Flip to hold 201 if legal requires clean-before-share |
| Scanner down | Fail open / **fail closed** / admit `pending_scan` | **Fail closed on public create**; internal tools may fail open | Create availability dips with T&S |
| Malware UX | Silent 410 vs **410** vs HTML warning | **410 for disabled; optional warning only if legal requires** | Interstitial destroys SMS “one tap” |
| Fetch destination to unfurl | Better cards vs SSRF | **SSRF-safe fetcher** (allowlist scheme, block link-local, time budget) or **don’t fetch on click path** | Cards are async, best-effort |
| Open redirect to `javascript:` / `data:` | — | **Reject at validate** | Some weird legacy URLs |
| `http://` destinations | Compatibility vs mixed-content | **Allow http with warning flag**; prefer https | Strict https-only breaks old posters |
| Proxy the destination | Hide referrer / strip cookies | **Never — you become an open proxy** | Some privacy products want this; that’s a different system |

**410 vs 404 vs 451 vs interstitial:**

| Status | Meaning | SEO / cache | **Pick** |
|---|---|---|---|
| 404 | Never existed (or you lie) | Crawlers retry | Unknown codes |
| **410** | Gone, don’t retry | Better for disabled | **Disabled / expired / malware** |
| 451 | Legal unavailable | Honest for court orders | Use when counsel says so |
| 200 + HTML interstitial | You can warn | Slow; breaks curl/SMS | Only mandated warning pages |

### 7.10 Rate limiting (layered)

| Layer | Limit | Fail mode |
|---|---|---|
| Edge / WAF | Global QPS, known-bad ASN | Fail closed on volumetric DDoS |
| Redirect 404s | Per IP unknown-code | Stop enumeration |
| Control plane | Per API key token bucket | Fail closed on create |
| Alias minting | Stricter | Homograph / land-grab |

Token bucket at the gateway; shared store (Redis + Lua or edge key-value) so N replicas cannot multiply the quota. Redirect **success** path should not need a heavy rate limiter per click — the CDN is the limiter. Limit **misses and 404s**.

| Algorithm | Burst | Memory | **Use** |
|---|---|---|---|
| Fixed window | 2× at boundary | Tiny | Crude WAF |
| Sliding log | Accurate | Heavy | Don’t at this QPS |
| Sliding counter | OK | Tiny | Good enough global cap |
| **Token bucket (pick)** | Controlled burst | Tiny | API keys, 404s |
| Leaky bucket | Smooth, adds latency | Queue | Redirects must not queue |

**Fail-open vs fail-closed on the limiter store:**

| Path | Redis limiter down | **Pick** |
|---|---|---|
| `GET /{code}` success | Don’t need limiter | CDN |
| `GET` 404 storm | Fail open → enumeration; fail closed → lock out | **Fail closed on 404s only** (cheap, local token bucket fallback) |
| `POST /v1/links` | Fail open → spam; fail closed → outage | **Fail closed** for public; fail open for first-party internal with huge default quota |

---

## 8. Failure Modes and Blast Radius

| Failure | User impact | Detection | Mitigation |
|---|---|---|---|
| Redis L2 down | Latency ↑ to L3; possible origin overload | Hit rate, L3 QPS | Fail open to KV; shed non-hot; CDN still holds virality |
| Mapping primary down | New creates fail; redirects from replica/cache | Error rate, replica lag | Redirects stay up (P5); creates 503 |
| Mapping split-brain / wrong failover | **Wrong URL for a code** — worst class | Checksum canaries, audit | Fence old primary; never dual-write aliases |
| CDN config ships `max-age=31536000` | Cannot takedown; stale retarget | Synthetic takedown probe | Config review; canary PoP |
| Cache stampede | Regional outage on one celebrity code | L3 QPS spike, p99 | Singleflight, staggered TTL jitter |
| Kafka down | Analytics gap | Consumer lag | Drop clicks (P3); do not block redirect |
| Safety scanner down | New malware URLs get codes | Scanner freshness | Fail closed on create **or** admit in `pending_scan` and show interstitial until clean — product choice; **do not fail open to the public internet** |
| Allocator exhausted / stuck | Cannot shorten | Block-lease metrics | Pre-alert at 80% of bit budget; hot-standby allocator |
| Clock / ID bug (if Snowflake) | Colliding codes | Unique constraint violations | Unique PK still saves you; page and halt that generator |

**Silent wrong-data is worse than downtime.** A 5-minute 404 is recoverable. Redirecting `go/payroll` to an attacker for 5 minutes is a company-level incident. Prefer serving **stale-but-known-good** over **guessing**.

**Canaries:** synthetic codes with known destinations, probed from multiple regions every few seconds. Alert on wrong `Location`, not only on 5xx.

---

## 9. Observability and SLOs

**Golden signals, split by plane:**

| Plane | RED / USE |
|---|---|
| Redirect | QPS, p50/p99 latency, 3xx/4xx/5xx, cache hit ratio (L0/L1/L2), coalesced misses |
| Create | QPS, p99, uniqueness conflicts, idempotency hits, safety reject rate |
| Safety | Takedown time (write → last PoP purge), scan latency, pending_scan age |
| Analytics | Event drop rate, bus lag (not a redirect SLO) |

**High-cardinality caution:** do not label metrics with `short_code` except in sampled traces. Use `hash(code)%1024` or “hot set” flags.

**Tracing:** create path yes; redirect path **sampled** (1 in N). Full traces at 1 M QPS is a denial of service on your tracer.

| Observability choice | Gain | Lose | **Pick** |
|---|---|---|---|
| Trace 100% redirects | Perfect debug | Tracer is the outage | **Sample 0.1–1%; always sample 5xx and takedowns** |
| Metric label `short_code` | Easy dashboards | Cardinality explosion | **Never; use hot-bucket** |
| Log every redirect at origin | Exact | Disk + PII | **Edge sampled logs** |
| Alert on analytics lag | Data team happy | Pages redirect on-call | **Analytics lag is a data SLO, not redirect** |

---

## 10. Evolution (v0 → platform)

Principals ship a staircase, not a cathedral.

| Stage | What exists | What you refuse |
|---|---|---|
| **v0** | One region, Postgres, random or counter codes, Redis, 302, click logs to files | Multi-region uniqueness science; 301; click_count column |
| **v1** | This doc: split fleets, CDN, block allocator, Kafka analytics, takedown+purge, idempotent API | Active-active custom aliases |
| **v2** | Branded domains, regional ID blocks, partner billing from warehouse | Per-tenant databases |
| **v3** | Geo/device routing, experiments on destination | Putting experiment logic **in** the redirect loop without a cached decision blob |

**Platform play:** notifications, receipts, and growth teams should **not** build shorteners. Offer `POST /v1/links` + domain CNAME + quota. That is how you stop five incompatible code spaces.

**Migration of code length:** if you ever go 7 → 8 chars, **old codes stay valid**. New issuances use the new width. Never rewrite existing codes.

---

## 11. Cost-Aware Notes

- **CDN egress** dominates at 10 B redirects if you accidentally serve bodies. Redirects are header-only — keep them that way.
- **Origin QPS** is a cost and a reliability knob: raise edge TTL until takedown SLO complains, not until finance complains.
- **Store cold codes** on cheaper storage; keep hot keys in Redis. Most of the 90 TB is never read.
- **Do not** keep raw click logs forever. Aggregate, then drop.

| Cost lever | Cheaper choice | More expensive choice | **Pick** |
|---|---|---|---|
| Bodies on 302 | **Headers only** | Interstitial HTML | Headers |
| Edge TTL | Longer | Shorter | **30–60s** bounded by T&S |
| Store tiering | **Hot Redis + cold object/SQL** | All SSD KV | Tier |
| Click logs | **Agg then drop** | Raw forever | Agg |
| Multi-region writes | **Single writer** | Active-active | Single |
| Build vs buy | Buy Bitly Enterprise | Full platform | **Build if other teams will depend**; buy if this is a checkbox |

**Build vs buy (say this out loud):**

| | Buy (Bitly / Rebrandly / Firebase Dynamic Links) | **Build (this design)** |
|---|---|---|
| **Gain** | Time-to-market, their T&S | Code-space control, branded domains, data residency, no vendor on the click path |
| **Lose** | Vendor on every SMS; takedown SLO is theirs; cost at 10 B clicks; data gravity | You own abuse, on-call, keyspace forever |
| **Pick** | Internal marketing, low volume | **Platform other teams call, or compliance/residency/T&S bar** |

---

## 12. Interview Execution

### 12.1 45-minute timing

| Min | Produce |
|---|---|
| 0–5 | Frame: two planes, one-way doors (code space, 301), Zipf, safety vs cache |
| 5–8 | Capacity math on the board (62^7, 100 M/day, 10 B redirects, 90 TB) |
| 8–12 | Principles + component diagram (edge, redirect fleet, control plane, KV, Kafka) |
| 12–22 | Deep dive 1: **code generation** (reject hash; blocks + base62; custom alias uniqueness) |
| 22–32 | Deep dive 2: **redirect path** (302 + Cache-Control, L0–L3, stampede, takedown purge) |
| 32–40 | Walk **trade-off catalog**: 301, hash vs ID, click counter, Redis-as-SoT, multi-region aliases, fail-open |
| 40–43 | Evolution + what you’d add with another hour |
| 43–45 | Their probes — answer with “pick / give up / flip when” |

### 12.2 Spoken opener (≈ 45 seconds)

> “I’d treat this as a globally cached KV mapping, not a CRUD app. The click path is the product: p99 tens of milliseconds, 99.99% availability, independently scalable from create. Short-code alphabet and 301-vs-302 are one-way doors because of QR codes and browser caches. I’ll issue codes from reserved ID blocks encoded in base62, store mappings in a sharded KV with Redis plus CDN in front, and push clicks to Kafka so we never write counters on redirect. The interesting tension is cache hit rate versus a 30-second phishing takedown — I’ll use short edge TTLs and surrogate-key purge rather than 301.”

### 12.3 What not to do

- Jump to “MD5 the URL” without discussing collisions and existence oracles
- Put `click_count++` in the redirect transaction
- Design Cassandra + Kafka + CQRS for 1k writes/s
- Ignore abuse
- Claim 301 “because it’s faster” without owning lost analytics and unkillable links
- Use Redis as the source of truth
- List three options and not pick — always **pick / give up / flip when**

---

## 13. Follow-up Q&A (crisp answers)

**Q: Same long URL → same short code?**  
A: Default **no**. Dedup is a privacy and product leak. Optional **per-owner** dedup if the dashboard wants it.

**Q: How do you handle Unicode / punycode in aliases?**  
A: Normalize NFKC, reject mixed-script confusables, store ASCII/punycode only. Homographs are a T&S bug.

**Q: Database vs cache consistency after retarget?**  
A: SoT is the store. Caches are TTL + explicit purge + `cache_gen`. Redirect never “wins” over SoT on conflict.

**Q: Can we use 301 for anonymous unused links to save origin?**  
A: Only if they are **immutable forever** (no retarget, no takedown). That is incompatible with a public shortener. Edge-cache **302** instead.

**Q: Unique ID service vs local blocks?**  
A: Per-request RPC to an ID service adds a SPOF on create. Blocks (or embedded Snowflake) keep issuance local. See [`UNIQUE_ID_GENERATOR_SYSTEM_DESIGN.md`](UNIQUE_ID_GENERATOR_SYSTEM_DESIGN.md) if IDs are a platform primitive; still encode to base62 for the public code.

**Q: How would you do A/B destinations?**  
A: Precompute a small **decision blob** (weights, sticky cookie) and cache it **with** the mapping. Do not call an experiment service on each click. Sticky assignment stored on first hit as a click event, not as a mapping write storm.

**Q: What’s the CAP choice?**  
A: Redirects: **AP with stale-OK** (serve last known mapping). Alias create: **CP** (refuse rather than double-allocate `jobs`). Takedown: **linearizable flag** then best-effort cache invalidation with a bounded TTL safety net.

**Q: Why not put everything in Redis with AOF?**  
A: AOF/RDB is a cache durability story, not a 5-year QR-code contract. Backup, restore, accidental `FLUSHALL`, and “we ran out of memory and evicted mappings” are unrecoverable. **Give up:** one fewer system. **Flip:** never for SoT; Redis stays L2.

**Q: Why not 301 plus a short CDN TTL?**  
A: You do not control the **browser** cache for 301. CDN TTL is irrelevant once Chrome stored the mapping. **Give up:** some extra origin hits. **Flip:** immutable archive links with no T&S and no retarget.

**Q: Sequential IDs vs random — isn’t sequential an enumeration bug?**  
A: Yes. **Pick FPE/Feistel** on the integer before base62, plus 404 rate limits. Raw sequential is simpler and leaks volume. Random+retry is fine at low fill factor. **Flip to random** if you hate running an allocator and write QPS is modest.

**Q: Should create wait for the malware crawl?**  
A: **No by default** (blocklist is sync). You give up a short pending window. **Flip to wait** if legal says a shared link must be clean before 201.

---

## 14. Complete trade-off catalog

Use this as a checklist. Format for every line: **pick → give up → flip when**. Load-bearing arguments are in §7; this section is the rest of the design space so nothing is implicit.

### 14.1 Product and org

| Topic | Options | **Pick** | Give up | Flip when |
|---|---|---|---|---|
| What are we building? | Mapping CDN / attribution warehouse / branded-domain SaaS | **Mapping CDN + API platform** | Full marketing suite in v1 | KPI is dashboards, not durable clicks |
| Who is the customer? | Public internet / first-party / partners | **Partners + first-party; public optional** | Viral consumer brand | The product *is* consumer bit.ly |
| Auth on create | Open / **API keys** / end-user login | **API keys + user login** | Friction | Classic public shortener (then IP limits are the product) |
| Multi-tenant isolation | Shared table + `owner_id` / schema-per-tenant / cell-per-tenant | **Shared + quota** | Noisy neighbor risk | Huge enterprise cell with data-residency contract |
| Conway | One team owns all / **platform owns mapping+redirect, product owns UX** | **Split ownership** | More meetings | One squad total |
| Branded domains v1 | Yes / **v2** | **v2** | Enterprise deals wait | That’s the actual sale |

### 14.2 Code space and encoding

| Topic | Options | **Pick** | Give up | Flip when |
|---|---|---|---|---|
| Issuance | Hash / random / **blocks** / Snowflake | **Blocks + base62** | Allocator ops | See §7.1 |
| Hash algorithm | MD5 / SHA / BLAKE | **Don’t hash URLs** | Dedup | Internal doc store |
| Collision strategy | Retry / longer code / overwrite | **Retry insert** (random) or **no collision** (issued IDs) | Overwrite is data loss | Never overwrite |
| Reserved words | None / **blocklist** | **Blocklist** | Some codes unused | — |
| Homographs | Allow Unicode / **reject** / NFKC+mixed-script reject | **Reject mixed-script; NFKC** | Some vanity | i18n product with linguists |
| Slug charset for aliases | `[a-z0-9-]` vs full base62 | **`[a-z0-9-]` for vanity** | Mixed-case vanity | Brand wants `GoHuskies` |
| Checksum digit | Extra char vs none | **None** | Typos hit 404 | Printed medical QR → add checksum, codes get longer |
| Secret in the URL | Signed codes (`code + MAC`) | **Unsigned opaque codes** | Can’t detect tamper without DB | Capability URLs for unlisted-but-unguessable (different product) |

### 14.3 HTTP, cache, CDN

| Topic | Options | **Pick** | Give up | Flip when |
|---|---|---|---|---|
| Status | 301 / **302** / 307 / meta-refresh | **302** | Browser-level “permanent” speed | Immutable archive |
| `Cache-Control` | no-store / **30–60s public** / year | **30–60s** | Stale window | T&S 5s → 5–15s |
| `Vary` | None / User-Agent / Cookie | **None** on click path | Geo/A-B harder | A/B via **cached decision blob**, not Vary-UA (cardinality bomb) |
| CDN | None / **yes** / only static | **Yes for GET /{code}** | Purge machinery | QPS < few k |
| Purge | TTL only / **surrogate key + wait** | **Wait on takedown API** | Takedown p99 includes CDN | No public abuse |
| Cookie on redirect | Session / none | **None** | Personalization | Logged-in preview on control plane only |
| HSTS / HTTPS | Optional HTTP / **HTTPS redirector** | **HTTPS listener; destination may be http** | Old HTTP short URLs need 301 to HTTPS **on the short host** (that 301 is OK — host, not mapping) | — |
| HTTP/2, keepalive | Off / **on** | **On** | — | — |
| CORS | Open / closed | **Closed on data plane** (navigations, not XHR) | JS apps use control-plane resolve API | — |

### 14.4 Data, consistency, durability

| Topic | Options | **Pick** | Give up | Flip when |
|---|---|---|---|---|
| SoT | Redis / **SQL or KV** | **SQL/KV** | — | Never Redis SoT |
| Replication | Async replica / sync quorum | **Sync in-AZ; async cross-region** | Cross-region 201 lag | Create must be globally visible before 201 |
| Isolation | Read-committed / serializable | **Unique constraint is the isolation** | — | — |
| Backup | Daily / **PITR** | **PITR** | $ | Toy |
| Restore drill | None / **game day** | **Game day** | Time | — |
| Long URL max | 2 KB / **8 KB** / unlimited | **8 KB hard cap** | Some tracking URLs | Partners with huge tokens → object store pointer (extra hop — avoid) |
| Soft vs hard delete | Hard / **soft** | **Soft + quarantine** | Storage | Legal purge requests → hard delete + cache purge + tombstone “redacted” |
| CDC | None / **outbox/CDC to search & owner index** | **CDC** | Dual-write bugs if you skip | Tiny v0: dual write in one TX |

### 14.5 Analytics and privacy

| Topic | Options | **Pick** | Give up | Flip when |
|---|---|---|---|---|
| Click SoT | Row counter / Redis / **event log** | **Event log** | Live exactness | — |
| Delivery | At-most / **at-least** / exactly | **At-least + idempotent agg** | Dupes until agg | Exactly-once is Kafka-internal, not end-to-end |
| Unique users | Raw IP / **HMAC(IP, rotating key)** / none | **HMAC + 30–90d key rotation** | True uniques across rotation | GDPR “no IP-derived” → drop |
| Geo | MaxMind on origin / **edge geo header** | **Edge** | Origin undercount | No CDN |
| Retention | Forever / **raw 30d, agg years** | **30d raw** | Forensic depth | Legal hold |
| Real-time dashboard | 1s / **~1 min** / daily | **1 min** | Instant vanity metrics | Trading-style product (it isn’t) |

### 14.6 Safety, abuse, legal

| Topic | Options | **Pick** | Give up | Flip when |
|---|---|---|---|---|
| Open redirector risk | Allow any host / **blocklist + scheme allowlist** | **Allowlist schemes, blocklist hosts, no IP literals** | Some destinations | — |
| SSRF on unfurl | Fetch inline / **isolated fetcher** / no fetch | **Isolated, async** | Slow cards | No previews |
| Captcha on create | Always / **on risk** / never | **On risk score** | UX | Severe abuse |
| ToS / malware interstitial | Always / **never default** / malware only | **410; interstitial only if required** | Warning UX | Regulation |
| Law enforcement takedown | Best effort / **runbook + 451** | **Runbook** | — | — |
| Logging redirects for LE | Full / **sampled + mapping audit** | **Mapping audit always; clicks sampled** | Perfect LE trail | Court order expands retention |
| Child-safety / CSAM | None / **hash-match + report** | **On create crawl** | Create latency/cost | Mandatory for public |
| Rate limit identity | IP / **API key + IP + ASN** | **Layered** | Complexity | — |

### 14.7 Multi-region, DR, failover

| Topic | Options | **Pick** | Give up | Flip when |
|---|---|---|---|---|
| Writer | **Single region** / regional ID blocks / multi-primary | **Single writer v1** | Far create p99 | v2 regional blocks |
| Read | Primary only / **local replica + L2** | **Local + L2** | Stale retarget | — |
| Conflict | LWW / CRDT / **don’t** | **Don’t dual-write mappings** | Active-active myth | — |
| RPO | Minutes / **0 in-region** | **0** | Sync cost | — |
| RTO redirects | Hours / **<60s anycast** | **<60s** | BGP/anycast | Single-region OK for v0 |
| Clock | NTP / HLC | **NTP for logs; IDs from allocator not clocks** | — | Snowflake path needs clock policy |

### 14.8 Features people will ask you to bolt on

| Feature | Naive design | **Principal shape** | Give up |
|---|---|---|---|
| A/B destinations | RPC to experiment service on click | **Cached decision blob on the mapping** | Live experiment updates wait TTL/purge |
| Geo-steer | MaxMind on every click | **Cached dest-by-country map** (tiny) | Fine-grained IP lists |
| Device-steer | Parse UA on origin | **Same, cached; Vary: UA is a last resort** | Cache fragmentation |
| Password-protected links | Check password on redirect | **Cookie after control-plane unlock; mapping flag `locked`** — extra hop first time | SMS one-tap |
| Expiring “secret” links | TTL only | **TTL + unguessable issued code (FPE)** | Not a security boundary vs determined attacker |
| QR codes | PNG on click path | **Async generate, store in object storage, CDN** | — |
| Preview cards | HTML on `GET /{code}` | **Separate preview API; bots still 302** | Unfurl quality |
| Webhooks on click | Sync HTTP to customer | **Kafka → webhook worker, at-least-once** | Latency on their side not yours |
| GraphQL control plane | One graph | **REST for v1; GraphQL later** | — |
| gRPC | Faster create | **JSON REST** — partners, curl, browsers | Microservice-internal gRPC OK |

### 14.9 Failure-policy matrix (explicit)

| Dependency | Redirect | Create | Takedown |
|---|---|---|---|
| CDN | Serve stale / go to origin | N/A | Purge fails → **takedown API errors** (do not lie “ok”) |
| Redis L2 | **Fail open to L3** | **Best-effort write-through skip** | Delete key + origin |
| Mapping primary | **Replica / stale cache** | **503** | **Must succeed on primary** |
| Kafka | **Drop events** | N/A | N/A |
| Safety scanner | Use last flag on row | **Fail closed public** | N/A |
| Allocator | N/A | **503** | N/A |
| WAF | **Fail closed volumetric** | Fail closed | Allow T&S IPs |

### 14.10 Interview anti-patterns (hidden trade-offs you forgot to name)

| If you say… | You silently picked… | Name it |
|---|---|---|
| “MD5 the URL” | Global dedup + existence oracle | Privacy vs storage |
| “301 is faster” | Unkillable links | Speed vs T&S |
| “Redis is the DB” | Eviction as data loss | Latency vs RPO |
| “We’ll increment clicks in SQL” | Hot-row death | Exactness vs availability |
| “Active-active all regions” | Alias split-brain | Latency vs uniqueness |
| “Cache forever, purge if needed” | Purge is never 100% (browsers) | Hit rate vs correctness |
| “Fail open everywhere” | Abuse and malware | Availability vs safety |
| “Fail closed everywhere” | Redirect 5xx on Redis blip | Safety vs the actual product |
| “We’ll shard later” | Wrong shard key baked into access patterns | Speed of v0 vs migration |
| “UUID as the short code” | 22-char base62 URLs | Uniqueness vs UX |

---

## 15. Why this is a Principal design

| Staff-shaped answer | Principal-shaped answer |
|---|---|
| Redis + SQL + base62 | Two planes, Zipf at the edge, SoT vs cache, org ownership |
| “Use 302 for analytics” | 302 **and** `Cache-Control` **and** surrogate purge vs 30s T&S SLO |
| Hash the URL | Reject hash (oracle, collision, unwanted dedup); issued IDs + alias index |
| Click counter column | Event bus; droppable; warehouse is SoT for analytics |
| “We’ll shard later” | v0 is one PG if math says so; shard key chosen now (`code`, not `owner`) |
| Multi-region as a box on the diagram | Uniqueness partitioned: issued IDs regional, aliases global |
| Happy-path availability | Wrong `Location` as the nightmare scenario; canaries on `Location` |

The shortener is a **small API** and a **large operational contract**: printed codes, weaponized links, and a cache you do not fully control (the browser). Design the contract first; the boxes follow.

**The trade-off habit:** do not list two options and smile. For each, say **what you optimize, what you sacrifice, and the metric that would make you reverse the call.** That is the whole interview.
