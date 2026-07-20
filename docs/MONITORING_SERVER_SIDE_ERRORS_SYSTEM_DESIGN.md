# Monitor Server-Side Errors — Designing a Real-Time Error Monitoring System (Principal Engineer Level)

**Target scale: 50,000 services emitting errors, 1M error events/sec at peak, sub-10-second time-to-alert, 13-month retention for aggregates / 30-day retention for raw events.**

This document covers the three angles the topic is usually split into: **(1) design of a monitoring system**, **(2) detailed design of the pieces that make it correct and cheap at scale**, and **(3) how the data gets visualized** so a human can act on it in seconds, not minutes.

---

## 0. Executive Summary (At a Glance)

### 0.1 What are we actually building?

Not a generic APM (that's traces + metrics + infra). This is specifically an **error-tracking / exception-monitoring system** — think Sentry, Rollbar, Bugsnag, or an internal equivalent built on top of the ELK/Prometheus stack. The job: **catch every unhandled exception and every explicitly reported error from every server process, group duplicates into one "Issue," tell someone the moment a new or regressed issue appears, and let an engineer go from alert → root-cause line of code in under a minute.**

### 0.2 One-sentence architecture

> **App SDKs capture exceptions with context → async, batched, non-blocking send → Ingest API → Kafka → Grouping/Fingerprint workers → Issue Store (Postgres) + Event Store (columnar, e.g. ClickHouse) + Blob Store (raw payload, S3) → Alerting engine (rules + anomaly detection) → Notification fan-out → Query/Dashboard service reads from Issue Store + pre-aggregated rollups for real-time visualization.**

### 0.3 Headline numbers

| | Sustained ingest | Peak (deploy-induced spike) | p99 SDK overhead | Time to alert |
|---|---|---|---|---|
| **Target** | **50K events/s** | **1M events/s (20×)** | **< 1 ms added latency to host request** | **< 10 s for new/regressed issue** |

A single bad deploy can turn a 1,000/s error rate into a 500,000/s error rate in seconds — the ingestion path has to absorb a **20–50× instantaneous spike** without falling over, because that spike *is the incident you're trying to catch.*

### 0.4 Storage choices at a glance

| Store | Purpose | Why | Key |
|---|---|---|---|
| Kafka | Durable ingest backbone, replay, decouples producers from grouping workers | High throughput, back-pressure absorption | `project_id` |
| Redis | Fingerprint dedup cache, rate-limit counters, rule-evaluation state | Sub-ms, TTL semantics | `{project}:{fingerprint}` |
| Postgres | Issue registry (one row per unique error), assignment, status, mute rules | Relational integrity, small cardinality (millions, not billions, of issues) | `issue_id` |
| ClickHouse | Raw event store for search/analytics, time-series rollups for graphs | Columnar scan, cheap high-cardinality aggregation | `(project_id, toDate(ts))` |
| S3 | Full event payload (stack trace, request body, breadcrumbs), source maps/debug symbols | Cheap, infinite, infrequently accessed after triage | `event_id` prefix |
| Elasticsearch (optional) | Free-text search across stack traces / log messages | Inverted index for "search for this string across all errors" | `event_id` |

### 0.5 Key design tradeoffs — one-line summary

| Decision | Chosen | Alternative | Why |
|---|---|---|---|
| Client-side behavior | **Async, sampled, circuit-broken SDK** | Sync HTTP call per exception | Never let monitoring take down the thing it monitors |
| Grouping | **Stack-trace fingerprint hash, server-side, versioned algorithm** | Group by exception message | Messages contain IDs/timestamps — same bug, thousands of "different" groups |
| Transport | **Kafka between ingest and processing** | Direct write to DB | Absorbs 20–50× deploy-spike without dropping events or falling over |
| Event store | **ClickHouse (columnar)** | Postgres / Elasticsearch only | Cheap high-cardinality rollups (rate by release, by server, by user) at scale |
| Alerting | **Rule engine + statistical baseline, evaluated on rollups not raw stream** | Evaluate every raw event | O(issues) not O(events); scales independent of traffic |
| Consistency | **Eventual (seconds) for dashboards, at-least-once for ingest** | Strong consistency | A monitoring system that blocks or loses writes under its own load defeats its purpose |

### 0.6 Failure scenarios — the short version

| Scenario | Detection | Automatic response | Why it doesn't melt the system |
|---|---|---|---|
| **Deploy causes 500× error spike** | Kafka producer buffer / ingest queue depth | Sampling kicks in at the SDK *and* at ingest; full count preserved via counters even when payloads are dropped | Alert still fires (count-based), storage doesn't fall over |
| **Grouping worker falls behind (Kafka lag)** | Consumer lag > threshold | Autoscale worker pool; lag alert to on-call for the monitoring system itself | Events sit safely in Kafka (7-day retention) — nothing is lost, just delayed |
| **Storage cluster (ClickHouse) degraded** | Write latency / error rate on writes | Buffer in Kafka, backpressure ingest, keep serving reads from cache/rollups | Dashboards show slightly stale data instead of erroring |
| **SDK bug causes a crash loop reporting the *same* crash on every retry** | Per-fingerprint rate exceeds cap | SDK-local circuit breaker: after N reports of the same fingerprint in T seconds, drop locally | Prevents a crash-loop from DoS-ing the monitoring pipeline itself |
| **Notification storm (1,000 new issues in one bad deploy)** | Alert-volume-per-minute threshold | Group into a single digest notification per project per minute | On-call gets one actionable page, not 1,000 |

---

## 1. Problem Framing

Before drawing boxes, ask: **what does a human need in the ten seconds after something breaks in production, and what would they need if this system didn't exist?**

Without an error-monitoring system, an on-call engineer finds out about a bug from: a customer support ticket, a dashboard metric drifting (hours later), or grepping logs across 500 pods by hand. Every one of those is slow and after-the-fact. The job of this system is to compress "an exception was thrown somewhere in the fleet" → "a human with full context is looking at the exact line of code" into single-digit seconds to minutes, **without becoming a new source of outages itself.**

Two personalities live inside this system, and most design mistakes come from treating them as one:

- **The write path** must be blazing fast, non-blocking, and gracefully lossy under extreme load — it runs *inside* every request path of every service in the company.
- **The read path** (triage, search, dashboards) can be slower and richer — a human is reading it, milliseconds don't matter, but *signal quality* (correct grouping, correct "new vs. regression" detection) matters enormously.

---

## 2. Requirements

### Functional

- Capture unhandled exceptions and explicitly-reported errors (`logger.error()`, `captureException()`) from server processes in any language.
- Attach rich context automatically: stack trace, request info, environment, release/version, user (if available), breadcrumbs (recent log lines / actions leading up to the error).
- **Group** duplicate occurrences of the same underlying bug into one **Issue**, regardless of which host/pod/instance threw it.
- Detect and flag: **new issue** (never seen before), **regression** (previously resolved, now recurring), **spike** (rate anomaly on an existing issue).
- Alert the right team via the right channel (Slack, PagerDuty, email) based on ownership rules.
- Let engineers search, filter (by release, environment, tag), assign, mute, and resolve issues.
- Visualize error rate over time, broken down by release/server/region, with drill-down to individual raw events.
- Symbolicate minified/obfuscated stack traces (source maps, debug symbols) so the trace shows real function names and line numbers.

### Non-Functional

| Property | Target |
|---|---|
| Sustained ingest | 50K events/s |
| Peak ingest (deploy spike) | 1M events/s (20×) |
| SDK overhead added to host request | p99 < 1 ms, never blocking |
| Time to alert (new/regression issue) | p99 < 10 s |
| Dashboard query latency | p99 < 500 ms for a 30-day rollup |
| Data loss tolerance | Acceptable to *sample* under extreme load; **counts must never be lost**, even if payloads are |
| Availability of ingest path | 99.99% (it sits in the hot path of every service) |
| Availability of query/dashboard path | 99.9% (best-effort is acceptable during an incident) |
| Retention — raw events | 30–90 days hot, cold-archived to S3 after |
| Retention — issue-level aggregates | 13 months (year-over-year comparison) |

### Out of Scope (v1)

- Client-side (browser/mobile) error tracking (different constraints: symbol servers per app version, offline queuing, network cost to the end user). Same architecture extends there later.
- Full distributed tracing (spans, service maps) — errors reference a `trace_id` for correlation but this system does not *own* tracing.
- APM-style code profiling / flame graphs of CPU usage.
- Automated root-cause suggestion via ML (hooks exist in the pipeline; the model is a v2).

---

## 3. Core Principles (Non-Negotiable)

### 3.1 The Monitoring System Must Never Cause the Outage It's Meant to Catch

This is the single most important constraint and it drives almost every design decision below.

- The SDK **never** makes a synchronous network call on the exception-handling path. It enqueues to an in-process buffer and a background thread/goroutine ships it.
- The SDK has a **local circuit breaker**: if the transport is failing, or if the same fingerprint is firing faster than a cap (e.g., > 20/s), it drops locally and increments a local dropped-count metric instead of retry-storming the ingest tier.
- If the in-process buffer is full (the host is in such a bad state it can't even flush errors), the SDK **drops silently** rather than blocking the request thread or growing unbounded memory.

### 3.2 Counts Are Sacred, Payloads Are Negotiable

Under a 500× spike, we cannot store 500× the storage and still answer "how many times did this happen" correctly — that number is the difference between "known bug, low priority" and "page everyone now."

- Every event increments an atomic counter *before* any sampling decision is made downstream.
- Sampling (dropping full payloads, keeping counts) happens progressively: SDK → ingest → grouping worker, each stage sampling harder only if the stage before it is overwhelmed.
- The **first N** and a statistically sampled trickle of full payloads per fingerprint per time window are always retained — enough to debug, not enough to bankrupt storage.

### 3.3 Grouping Correctness Is the Product

An error-monitoring system that shows 10,000 "unique" issues for one bug that fired 10,000 times (bad grouping) is worse than useless — it trains engineers to ignore it. An error-monitoring system that merges two unrelated bugs into one issue (over-aggressive grouping) hides real problems.

- Grouping is a **versioned, server-side algorithm** (never trust client-computed hashes — SDK bugs/version skew would silently fragment or merge issues).
- Grouping decisions are able to be **manually corrected** (merge two issues, split one) and the correction is remembered for future events with the same fingerprint.

### 3.4 Every Component Downstream of Ingest Is a Consumer, Not a Blocker

Ingest's only job is: validate lightly, assign an ID, write to Kafka, return `202`. Everything else — grouping, alerting, storage, symbolication — is an asynchronous consumer of that stream. This means grouping-logic bugs, alerting-rule bugs, or a slow storage backend can **never** back up into the ingest path and start rejecting writes from production services.

---

## 4. Back-of-the-Envelope Sizing

| Dimension | Value | Reasoning |
|---|---|---|
| Sustained events | 50,000/s | Baseline error rate across the fleet |
| Peak (deploy spike) | 1,000,000/s | 20× — a bad deploy that error-loops on every request |
| Avg raw payload | 4 KB | Stack trace + request context + breadcrumbs |
| Avg after truncation/sampling at peak | ~0.5 KB effective (mix of full + count-only) | Sampling reduces payload, not count |
| Kafka ingress (peak) | ~500 MB/s (sampled) vs. 4 GB/s (unsampled) | Sampling is what makes peak survivable |
| Kafka on-disk (24 h retention, RF 3) | ~40 TB (sustained) + burst headroom | Retention sized for consumer-lag recovery, not primary storage |
| Unique issues (fleet-wide, steady state) | ~50,000–200,000 active | Long-tail: most events map to a small number of hot issues |
| Postgres (issue registry) rows | Low millions total, all-time | One row per unique issue, not per event — small enough for a normal RDBMS |
| ClickHouse raw events (30 d hot) | 50K/s × 86,400 s/d × 30 d × ~0.3 KB compressed ≈ **~4 PB→ ~130 TB compressed** | Columnar + compression on repetitive stack-trace text gets 10–20× |
| Redis ops (dedup + rate-limit) | ~1M ops/s at peak | Sharded cluster, `{project}:{fingerprint}` hash tag |

**Cluster sketch** (order of magnitude, not a procurement doc):

- Kafka: 20 brokers, NVMe, sized to absorb 4 GB/s pre-sampling burst for at least 10 minutes.
- Ingest API: 100–150 pods, autoscaled on CPU + Kafka producer buffer depth.
- Grouping/fingerprint workers: 200 pods, autoscaled on consumer lag.
- Redis Cluster: 30 shards.
- ClickHouse: 20 nodes, replicated ×2, sharded by `project_id`.
- Postgres: 1 primary + 2 replicas (issue registry is low-write, read-heavy from the UI).

---

## 5. High-Level Design of a Monitoring System

This is the "draw the boxes" answer.

```
┌────────────┐    ┌──────────┐    ┌─────────────┐    ┌─────────────────┐
│ App process│    │   SDK    │    │  Ingest API │    │   Kafka topic   │
│ (exception)│───▶│(async,   │───▶│ (auth,      │───▶│  errors.raw     │
│            │    │ buffered,│    │  validate,  │    │  (partition by  │
└────────────┘    │ circuit- │    │  assign id) │    │   project_id)   │
                   │ broken)  │    └─────────────┘    └────────┬────────┘
                   └──────────┘                                │
                                                                 ▼
                                                     ┌───────────────────────┐
                                                     │  Grouping / Fingerprint│
                                                     │  Workers               │
                                                     │  - normalize stack     │
                                                     │  - hash → fingerprint  │
                                                     │  - dedup via Redis     │
                                                     │  - new/regress/spike?  │
                                                     └──────────┬────────────┘
                              ┌──────────────────────┬─────────┴────────┬───────────────────┐
                              ▼                       ▼                  ▼                   ▼
                     ┌────────────────┐    ┌────────────────┐  ┌────────────────┐  ┌──────────────────┐
                     │ Issue Registry │    │  Event Store   │  │  Payload Blob  │  │ Rollup / Metrics  │
                     │ (Postgres)     │    │ (ClickHouse)   │  │  Store (S3)    │  │ writer (per-min   │
                     │ one row/issue  │    │ one row/event  │  │ full context   │  │ counters)         │
                     └───────┬────────┘    └───────┬────────┘  └────────────────┘  └─────────┬─────────┘
                             │                      │                                         │
                             ▼                      ▼                                         ▼
                     ┌────────────────┐    ┌─────────────────┐                      ┌───────────────────┐
                     │ Alerting Engine│    │ Query / Search   │◀────────────────────│ Dashboard /        │
                     │ (rules +       │    │ Service          │                     │ Visualization UI   │
                     │ anomaly detect)│    └─────────────────┘                      └───────────────────┘
                     └───────┬────────┘
                             ▼
                     ┌────────────────┐
                     │ Notification   │──▶ Slack / PagerDuty / Email
                     │ Fan-out        │
                     └────────────────┘
```

### 5.1 The five logical stages

1. **Capture** — the SDK inside the running process.
2. **Ingest** — the stateless edge that accepts events and hands them to durable transport.
3. **Process** — grouping, fingerprinting, enrichment, sampling decisions.
4. **Store** — three specialized stores for three access patterns (issue metadata, searchable events, raw blobs).
5. **Act & Visualize** — alerting (push) and dashboards/search (pull).

Each stage is independently scalable and, critically, **stage N+1 being slow or down never blocks stage N** — that's what Kafka in the middle buys you.

---

## 6. Detailed Design of a Monitoring System

This is where correctness and cost efficiency actually get decided.

### 6.1 SDK Design (Capture Stage)

**Responsibilities:**

- Hook into the language's exception mechanism: uncaught-exception handler, unhandled-promise-rejection, signal handlers for crashes, or an explicit `captureException(err)` call.
- Enrich automatically: stack trace (with local variables if in "debug" mode), current release/version, environment, hostname/pod, request context (method, route, headers minus sensitive ones), `trace_id`/`span_id` if a tracer is active, and **breadcrumbs** — a ring buffer of the last ~50 log lines / notable actions in this request, so you see "what led up to this," not just the crash itself.
- **PII scrubbing at the source**, before anything leaves the process (see §12). Cheaper and safer to scrub in-process than to scrub centrally after the fact.
- Batch and compress. Default flush: every 2 seconds or 100 events, whichever first, gzip-compressed.
- **Local sampling config**, pushed down from the ingest service periodically (e.g., "sample fingerprint X at 1% because it's a known noisy issue") so repeat offenders don't even leave the host.

```
Pseudo-flow inside the SDK:

on_exception(err):
    if circuit_breaker.is_open(fingerprint_local_hash(err)):
        local_dropped_counter.increment()
        return
    event = enrich(err, breadcrumbs, context)
    event = scrub_pii(event)
    if not ring_buffer.offer(event):     # buffer full, host is unhealthy
        local_dropped_counter.increment()
        return
    # background flusher thread drains ring_buffer on interval/size trigger
```

**Why local fingerprinting for the circuit breaker but not for grouping?** The SDK's local hash is a cheap heuristic (e.g., exception type + top frame) purely to decide "should I even bother sending this again this second." The **authoritative** fingerprint used for grouping is computed server-side (§6.3) where we can run a versioned, carefully-tuned algorithm and re-process history if we improve it — something a shipped SDK binary can't do retroactively.

### 6.2 Ingest API

- Stateless HTTP/gRPC endpoint: `POST /api/{project_id}/store/`.
- Auth via a per-project write key (like a DSN in Sentry). Cheap to validate (in-memory cache of valid keys, refreshed periodically from Postgres).
- **Minimal validation only**: payload size cap, required fields present, project key active/not over quota. Anything expensive (parsing the full stack trace, running grouping logic) happens downstream — ingest must stay sub-5ms p99.
- Assigns a globally unique `event_id` (UUIDv7 — time-sortable, helps downstream compaction) and writes to `errors.raw`, partitioned by `project_id` so per-project ordering and per-project consumer scaling are both possible.
- **Quota enforcement**: each project has a rate-limit budget (token bucket in Redis). Over-quota events are countable but not payload-stored — this protects the shared platform from one team's runaway logging bug.
- Returns `202 Accepted` immediately after the Kafka write is acknowledged (not after downstream processing) — same "never lie about a synchronous guarantee you don't have" principle as any async pipeline.

### 6.3 Grouping / Fingerprinting (the heart of the system)

**The problem:** the same bug, thrown from 10,000 different requests, must become **one** Issue. A different bug that happens to share an exception class (`NullPointerException`) must **not** merge with it.

**Algorithm:**

1. **Normalize the stack trace.** Strip memory addresses, request-specific values embedded in the message (order IDs, timestamps, UUIDs — detected via regex/heuristics), and library/vendor frames that are noise (e.g., framework internals below the application's own code).
2. **Select grouping frames.** Take the top N application-owned frames (skip vendored/library frames — two different bugs both throwing through the same HTTP client library shouldn't merge just because the top frame is inside that library).
3. **Compute fingerprint** = `hash(exception_type + normalized_top_frames)`. This is a **stable, versioned** algorithm — bumping the version is a deliberate migration, not a side effect of an SDK upgrade.
4. **Look up fingerprint** in Redis (`fingerprint:{project}:{hash}` → `issue_id`, TTL refreshed on hit) for the hot path; fall back to Postgres on cache miss (new issue or cold issue).
5. **New fingerprint** → create a new Issue row (status = `unresolved`, `first_seen = now`). Flag as **new issue** for alerting.
6. **Existing fingerprint, issue currently `resolved`** → flip back to `unresolved`, flag as **regression** for alerting (this is a much higher-signal alert than "new issue" — it means something *thought fixed* came back).
7. **Existing fingerprint, `unresolved`** → increment counters, update `last_seen`, no new alert unless a **rate anomaly** trips (see §6.6).

**Custom grouping overrides:** teams can supply per-project rules (e.g., "always group by `error.code` for this service, ignore stack trace") because some codebases (dynamic languages, generated code) produce noisy stacks where the default heuristic under- or over-groups. This is exposed as a small rule DSL evaluated before the default algorithm.

**Manual merge/split:** an engineer can merge two issues (their fingerprints become aliases pointing to the same `issue_id`) or split one (a subset of events get re-fingerprinted under a new grouping rule). Both operations are logged and re-processable — grouping is a best-effort heuristic, not ground truth, and the UI must make correcting it a first-class, cheap action.

### 6.4 Enrichment & Symbolication

Production JS/TS is minified, Java/Kotlin can be obfuscated (ProGuard/R8 for Android, though that's client-side — server-side equivalents exist for compiled languages needing debug symbol resolution), Go binaries are stripped. A raw stack trace pointing at `chunk.a3f.js:1:48213` is useless to a human.

- **Async symbolication worker**, decoupled from the hot grouping path (symbolication is slow — can involve downloading a source map or debug symbol file, parsing it, and re-mapping every frame).
- Source maps / debug symbols are uploaded at build/deploy time, keyed by `(project_id, release_version)`, stored in S3, cached in a local worker LRU (same release's map gets requested thousands of times).
- If symbolication fails or the map is missing, the raw trace is still stored and shown — **never block storing an event on symbolication succeeding.**
- Symbolicated result is cached back onto the event so repeat views don't re-run the (expensive) mapping.

### 6.5 Storage Design

Three stores because three very different access patterns exist:

| Store | Access pattern | Row/record | Retention |
|---|---|---|---|
| **Postgres — Issue Registry** | Point lookups, list/filter for triage UI, low write rate (one write per new-issue or status-change, not per event) | One row per unique issue | Forever (historical record, tiny) |
| **ClickHouse — Event Store** | High write rate, aggregation queries (rate over time, group by release/tag), occasional point lookup by `event_id` | One row per raw event (or per sampled event) | 30–90 days hot, then drop or archive |
| **S3 — Blob Store** | Rare reads (only when an engineer opens a specific event to debug), write-once | Full JSON payload: complete stack trace, request body, breadcrumbs | 90 days, then delete (compliance/cost) |

**Issue Registry schema (Postgres):**

```sql
CREATE TABLE issue (
  id              bigint PRIMARY KEY,
  project_id      bigint NOT NULL,
  fingerprint     text NOT NULL,
  title           text NOT NULL,            -- e.g. "NullPointerException in OrderService.charge"
  status          text NOT NULL,             -- unresolved | resolved | muted | ignored
  first_seen      timestamptz NOT NULL,
  last_seen       timestamptz NOT NULL,
  event_count     bigint NOT NULL DEFAULT 0,
  affected_users  bigint NOT NULL DEFAULT 0, -- approx via HyperLogLog, updated async
  assigned_to     bigint REFERENCES engineer(id),
  level           text,                      -- error | warning | fatal
  UNIQUE (project_id, fingerprint)
);
CREATE INDEX ON issue (project_id, status, last_seen DESC);
```

`event_count` and `affected_users` are **not** updated per event with a row lock (that would make Postgres the bottleneck at 50K/s). They're updated by a **batched async aggregator** that flushes counters every few seconds from the in-memory/Redis rollup, using a single `UPDATE ... SET event_count = event_count + :delta`.

**Event Store schema (ClickHouse):**

```sql
CREATE TABLE error_event (
  event_id        UUID,
  project_id      UInt64,
  issue_id        UInt64,
  ts              DateTime64(3),
  release         LowCardinality(String),
  environment     LowCardinality(String),
  server          LowCardinality(String),
  exception_type  LowCardinality(String),
  message         String,
  user_id         Nullable(UInt64),
  trace_id        Nullable(String),
  blob_ref        String                     -- pointer into S3, not the payload itself
) ENGINE = MergeTree
  PARTITION BY toDate(ts)
  ORDER BY (project_id, issue_id, ts);
```

Partitioning by date makes retention trivial (`DROP PARTITION` for anything older than the window — near-instant, no row-by-row delete cost). Ordering by `(project_id, issue_id, ts)` makes the two dominant queries — "show me the trend for this issue" and "show me everything for this project in this time range" — efficient columnar range scans.

**Pre-aggregated rollups (also ClickHouse, materialized view or a separate writer):**

```sql
CREATE TABLE error_rollup_1m (
  project_id   UInt64,
  issue_id     UInt64,
  minute       DateTime,
  count        UInt64,
  release      LowCardinality(String)
) ENGINE = SummingMergeTree
  PARTITION BY toDate(minute)
  ORDER BY (project_id, issue_id, minute, release);
```

This table is what dashboards and the alerting engine actually query — a 30-day error-rate graph should never have to scan 130 TB of raw events; it scans a rollup table that's orders of magnitude smaller.

### 6.6 Alerting Engine

Alerting runs against **rollups and issue-state transitions**, not the raw event stream — this decouples alert-evaluation cost from traffic volume.

**Rule types:**

| Rule | Trigger | Example |
|---|---|---|
| **New issue** | Fingerprint never seen before | Any new issue in a `production` environment, any severity ≥ `error` |
| **Regression** | Previously `resolved` issue reoccurs | Always page — high signal |
| **Threshold** | Rate for an issue/project exceeds N events in T minutes | > 1,000 events/5 min on any single issue |
| **Anomaly (statistical baseline)** | Current rate exceeds N standard deviations from the trailing 7-day same-time-of-day baseline | Catches slow-building problems a static threshold misses |
| **Percentage of traffic** | Errors / total requests > X% | Ties error volume to actual traffic instead of an absolute number that's meaningless during a traffic spike |

**Notification fan-out & storm control:**

- Rules resolve to an **owning team** (via project → team ownership mapping, or per-issue assignment).
- Multiple triggers within a short window for the **same project** are **digested** into one notification ("14 new issues in `checkout-service` in the last 60s, top 3: …") instead of 14 separate pages — this is the same bulkhead-against-self-DoS principle as the SDK circuit breaker, just at the human-attention layer.
- Delivery channels (Slack, PagerDuty, email, webhook) are pluggable; delivery failures retry with backoff and fall back to a secondary channel after N failures (an alerting system whose own notifications silently fail is worse than no alerting system).

### 6.7 Sampling Ladder (What Actually Survives a 20× Spike)

| Stage | Decision | Effect |
|---|---|---|
| SDK | Local circuit breaker per local-hash, cap ~20/s | Stops crash-loops at the source |
| SDK | Config-pushed sampling for known-noisy fingerprints | Reduces chronic noise without losing new-issue detection |
| Ingest | Per-project quota (token bucket) | Protects shared platform from one project's flood; over-quota still countable, not payload-stored |
| Grouping worker | Per-fingerprint payload sampling once count/window exceeds threshold (e.g., keep first 100 + 1% after) | Full detail retained for triage, storage cost bounded |
| Grouping worker | **Count is incremented via a lock-free counter for every event regardless of sampling** | The number displayed to the user is always accurate — only the *stored payload* is sampled |

---

## 7. Visualize Data in a Monitoring System

Visualization is not an afterthought bolted onto storage — the **shape of the read queries determines the rollup schema** (§6.5), so this section closes the loop.

### 7.1 The Two Primary UI Surfaces

**Issue List (triage view)** — the default landing page. This is a **list read from Postgres** (small, fast, exactly the shape needed): issue title, status, sparkline of the last 24h rate (pulled from the 1-minute rollup table, downsampled to ~24 points), first/last seen, affected users, assignee. Sortable/filterable by status, level, release, assignee — all indexed columns.

**Issue Detail (drill-down view)** — once an engineer clicks in:
- Full stack trace (symbolicated), rendered with the original source lines shown around the failing line (fetched from the source map's embedded sources or a source-fetch service).
- Breadcrumbs timeline leading up to the crash.
- Tags breakdown: "this issue happened on release 2.3.1 (87%), release 2.3.0 (13%); on `us-east` (60%), `eu-west` (40%)" — computed via `GROUP BY` on the rollup or event table.
- Trend graph: event count over a selectable window (1h/24h/14d/90d), queried from the appropriately-grained rollup (1-minute for short windows, hourly/daily rollups for longer windows — see §7.3).
- A **sample of raw events** (not all — could be millions) with a "load more" that fetches from ClickHouse directly, each with a link to fetch the full blob from S3 on demand.

### 7.2 Real-Time Updates

Triage is often done *during* an incident — the list should update live, not on manual refresh.

- Dashboard clients hold an **SSE (Server-Sent Events) or WebSocket** connection to a thin **notification/streaming service** that subscribes to a lightweight Kafka topic of "issue state changed" events (new issue, regression, count crossed a threshold) — **not** the raw high-volume event stream, which would overwhelm a browser tab.
- On reconnect (e.g., after a network blip), the client does a normal REST fetch to resync state, then resumes streaming — the stream is an optimization, not the source of truth.

### 7.3 Rollup Tiers for Efficient Time-Series Rendering

Rendering a 90-day graph from 1-minute granularity means 129,600 points — wasteful to compute and to render. Standard multi-tier rollup, same idea as RRDtool/Prometheus recording rules:

| Window requested | Rollup table queried | Points returned |
|---|---|---|
| Last 1 hour | `error_rollup_1m` | ~60 |
| Last 24 hours | `error_rollup_1m` (downsampled) or `error_rollup_5m` | ~288 |
| Last 14 days | `error_rollup_1h` | ~336 |
| Last 90 days | `error_rollup_1d` | ~90 |

Each coarser tier is itself computed by aggregating the tier below it on a schedule (a small streaming job or scheduled `INSERT ... SELECT` in ClickHouse), not recomputed from raw events every time — this is exactly the CQRS/materialized-view pattern applied to observability data: **pay the aggregation cost once, serve every dashboard render from it.**

### 7.4 Cross-Cutting Views (Beyond a Single Issue)

- **Release health dashboard**: error rate for the new release vs. the previous release, side by side, in the first hour after a deploy — this is the graph an on-call engineer stares at during a rollout, and it's why every event and rollup row carries `release` as a first-class dimension, not a buried tag.
- **Project overview**: total error rate over time for a whole service, top-N issues by volume, top-N by "newly appeared this week."
- **Org-wide heatmap**: error volume by team/service, useful for a weekly reliability review, not for real-time triage — this can tolerate minutes of staleness and is served from hourly/daily rollups only.

### 7.5 Query Service Design

- A thin service in front of Postgres (issue metadata) + ClickHouse (time series/search), presenting one unified query API to the frontend so the UI doesn't need to know which backend serves which field.
- Query shapes are **pre-classified**: "list issues" → Postgres; "trend for issue X" → rollup table by window-size rule above; "search raw events for string Y" → Elasticsearch (if enabled) or a ClickHouse full-text/`LIKE` fallback for smaller scale.
- **Caching**: dashboard queries for "last 24h" are re-requested by every viewer of a busy project's dashboard; cache the rollup query result for a few seconds (short enough that a live incident still looks live, long enough to absorb a thundering herd of viewers during that same incident).

---

## 8. API Design

### 8.1 Event Submission (SDK → Ingest)

```
POST /api/{project_id}/store/
Headers:
  X-Api-Key: <project write key>
  Content-Encoding: gzip

{
  "event_id": "018f2e1a-...",           // UUIDv7, client-generated
  "timestamp": "2026-07-20T10:15:30Z",
  "level": "error",
  "exception": {
    "type": "NullPointerException",
    "value": "Cannot invoke charge() on null customer",
    "stacktrace": [ { "file": "OrderService.java", "function": "charge", "lineno": 142 }, ... ]
  },
  "release": "2.3.1",
  "environment": "production",
  "server_name": "checkout-pod-7f9c",
  "request": { "method": "POST", "url": "/v1/orders/42/charge", "headers": { ... } },
  "user": { "id": "u_123" },
  "breadcrumbs": [ { "ts": "...", "message": "validated payment method" }, ... ],
  "trace_id": "abc123..."
}
```

Returns `202 Accepted` with `{ "event_id": "..." }` — the ID the SDK already generated is echoed back, not reassigned, so client-side logs/links can reference it immediately without waiting on the server round-trip.

### 8.2 Triage API (Dashboard → Query Service)

```
GET  /v1/projects/{project_id}/issues?status=unresolved&sort=last_seen&release=2.3.1
GET  /v1/issues/{issue_id}
GET  /v1/issues/{issue_id}/events?cursor=&limit=50
GET  /v1/issues/{issue_id}/trend?window=24h
POST /v1/issues/{issue_id}:resolve
POST /v1/issues/{issue_id}:mute?until=<ts>
POST /v1/issues:merge   { "issue_ids": ["a","b"] }
```

### 8.3 Streaming (Live Triage)

```
WS /v1/projects/{project_id}/stream
Server pushes:
  { "type": "new_issue", "issue": { ... } }
  { "type": "regression", "issue_id": "...", "issue": { ... } }
  { "type": "count_update", "issue_id": "...", "count": 4821 }
```

---

## 9. Trade-Offs (With Rejected Alternatives)

### 9.1 Kafka vs. Direct DB Write from Ingest

**Chose Kafka.** A deploy-induced 20× spike must be absorbable without dropping writes or timing out producers. Writing directly to ClickHouse/Postgres from ingest couples ingest's availability to storage's availability — exactly the coupling we're trying to avoid (§3.4). **Why not skip the queue for simplicity:** it works fine at low scale, but the entire point of this system is to survive the worst moment (an incident), and that's precisely when write volume spikes hardest.

### 9.2 Server-Side Grouping vs. Client-Computed Fingerprint

**Chose server-side.** Client-computed fingerprints would fragment across SDK versions/language quirks and can never be improved retroactively for already-shipped SDKs. **Why not client-side:** it's tempting because it's cheaper (no server compute), but grouping quality is the product's core value — worth centralizing and versioning carefully.

### 9.3 ClickHouse vs. Elasticsearch vs. Postgres for the Event Store

**Chose ClickHouse for raw events + rollups, optional Elasticsearch for full-text search.**

- ClickHouse: cheapest per-byte for high-cardinality time-series aggregation, which is 90% of the read load (graphs, breakdowns).
- Elasticsearch: better free-text search UX ("find all errors mentioning this SQL query"), but far more expensive per byte and worse at numeric aggregation — used only as an optional add-on, not the primary store.
- Postgres alone: fine for the small Issue Registry, would fall over under 50K/s raw event writes without heavy sharding we'd be reinventing (same argument as read-replica limits in general read-scaling design).

### 9.4 Sampling: Count-Preserving vs. Uniform Random Drop

**Chose count-preserving (always count, sample payload only).** Uniform random drop at the ingest layer is simpler to implement but a system that under-reports the *number* of times a critical bug fired during an incident actively misleads the responder about severity. The extra complexity of a separate counter path is worth it because the number is the signal that drives "how bad is this, right now."

### 9.5 Alerting on Raw Stream vs. Rollups

**Chose rollups.** Evaluating every one of 1M events/s against every alert rule is O(events × rules) — doesn't scale independent of traffic. Evaluating against 1-minute rollups is O(issues × rules) on a schedule — bounded by issue cardinality, not request traffic, and issue cardinality grows far slower than traffic during an incident (a spike is usually a few issues firing a lot, not many new distinct issues).

### 9.6 Push (WebSocket) vs. Poll for Dashboard Freshness

**Chose push for state-change events, poll/fetch for bulk data.** Streaming every raw event to every open dashboard tab would be its own DoS vector during an incident (the exact moment many people have the dashboard open). Streaming only *state transitions* (new issue, regression, threshold crossed) keeps the socket payload tiny and lets the client re-fetch bulk data (counts, trends) via normal cached REST calls.

---

## 10. Failure Scenarios

### 10.1 Deploy Causes a 500× Spike on One Issue

**Detection:** per-fingerprint rate crosses threshold within the grouping worker; Kafka consumer lag begins climbing.

**Automatic response:** SDK-local circuit breakers cap emission per host; ingest quota shapes the fleet-wide rate further; grouping workers switch that fingerprint to count-only mode after the first ~100 full payloads. The **alert still fires immediately** — it's a threshold/regression rule evaluated on the rollup, and the rollup counter is incremented for every event regardless of payload sampling.

**Human response:** on-call sees "Issue #4821 — NullPointerException in OrderService.charge — 480,000 events in 3 minutes, first seen 2 minutes ago" — enough to roll back the deploy without needing all 480,000 payloads.

### 10.2 Grouping Worker Pool Falls Behind (Consumer Lag)

**Detection:** Kafka consumer-group lag alert on `errors.raw`.

**Automatic response:** HPA scales the worker pool on lag-based custom metric. Events wait safely in Kafka (retention sized for hours of headroom) — nothing is lost, alerts are simply delayed by the lag amount, which is itself monitored and alerted on for the monitoring system's own on-call (§11).

**Human response:** if autoscaling can't keep up (e.g., hit a pool size ceiling), on-call manually raises the ceiling or investigates a hot-partition problem (one project dominating a partition — same class of problem as a celebrity user in a notification system, same fix: partition key salting for outsized projects).

### 10.3 ClickHouse Cluster Degraded

**Detection:** write latency/error rate alert on the ClickHouse sink consumer.

**Automatic response:** sink consumer backs off and stops advancing its Kafka offset (events safely buffered upstream); dashboards fall back to serving from the last-good cached rollup query results, clearly marked "data may be delayed."

**Human response:** ClickHouse on-call investigates; once healthy, the sink consumer catches up from its last committed Kafka offset — no data loss, just a visible gap in real-time freshness during the outage.

### 10.4 A Single Project's SDK Bug Floods the Pipeline (Crash Loop)

**Detection:** per-project quota exceeded continuously for minutes; SDK-local circuit breaker metric spikes.

**Automatic response:** ingest quota throttles that project's traffic to its provisioned budget; other projects are fully isolated (per-project Kafka partitioning + quota tokens) and see no impact.

**Human response:** notify the owning team; if the crash loop is itself the incident (e.g., a bad deploy that error-loops on every single request), the alert this generates ("this issue: 1M events, 100% of this service's traffic") *is* the useful signal that something is badly wrong — the system correctly reports "everything is on fire" without lying about the count and without falling over from the fire.

### 10.5 Notification Storm (One Bad Deploy, 200 New Issues)

**Detection:** alert-volume-per-minute-per-project threshold crossed.

**Automatic response:** switch from individual notifications to a single digest per project per minute, ranked by event count, capped to top 10 shown inline with a link to the full list.

**Human response:** on-call gets one actionable message instead of 200 pages — this is the human-attention equivalent of the SDK's circuit breaker, applied one layer up the stack.

---

## 11. Observability & SLOs — Who Watches the Watchmen

A monitoring system that has no monitoring of its own is a blind spot precisely where you can least afford one.

### 11.1 SLIs

| SLI | Definition | Target |
|---|---|---|
| Ingest availability | 2xx / total on ingest API | ≥ 99.99% |
| End-to-end time-to-alert | `alert_fired_ts - event_received_ts` for new/regressed issues, p99 | < 10 s |
| Grouping lag | Kafka consumer-group lag on `errors.raw`, in seconds | < 5 s p99, page > 60 s |
| Dashboard query latency | p99, 24h rollup query | < 500 ms |
| Notification delivery success | delivered / attempted, per channel | ≥ 99.9% |
| Data loss (counts) | Difference between SDK-emitted count and stored count, sampled audit | 0 for the count path (payload sampling is expected and not "loss") |

### 11.2 Meta-Monitoring

- The monitoring system's own services emit errors and metrics into a **separate, smaller instance of the same system** (or a well-isolated tenant within it) — never self-referential in a way that a total outage of the pipeline also takes down the ability to diagnose that outage.
- Synthetic canary: a scheduled job fires a known test exception every minute and asserts it appears as a new event, correctly grouped, within the time-to-alert SLO — the single most valuable end-to-end health check for this whole system.

### 11.3 Dashboards & Alerts

- **Traffic:** ingest RPS, per-project breakdown (top 20), sampling rate applied.
- **Pipeline health:** consumer lag per stage, worker pool utilization, Redis/Kafka/ClickHouse cluster health.
- **Alert-path health:** notification delivery latency and failure rate per channel.
- **Page on:** ingest availability SLO burn, grouping lag > 60 s, ClickHouse write failure rate > 1%, canary check failing 3 consecutive runs.

---

## 12. Security & Compliance

- **PII scrubbing at the SDK**, before the event ever leaves the host: strip known-sensitive header names (`Authorization`, `Cookie`), redact fields matching configured patterns (credit-card-like digit runs, email addresses in non-email fields), and give teams a per-project deny-list of field names never to capture (e.g., `password`, `ssn`).
- **Defense in depth:** a second scrubbing pass at the ingest/grouping stage catches anything a misconfigured or outdated SDK missed, logged as a scrub-miss metric so the SDK-side rule can be improved.
- **Access control:** per-project write keys are write-only (cannot be used to read data back) and rotated on demand; read access to the triage UI/API is scoped by team/project membership.
- **Data residency:** for regulated projects, events can be pinned to a specific region's Kafka/ClickHouse/S3 deployment; cross-region replication for these projects is opt-in only.
- **Retention & right-to-erasure:** if an event's `user.id` is later subject to a deletion request, a scheduled job locates and redacts/removes matching event payloads in S3 and ClickHouse; issue-level aggregates (counts) are unaffected since they contain no PII themselves.

---

## 13. Rollout Plan

**Phase 1 — single project, backend-only, SDK for one language.**
- Validate SDK overhead is truly negligible under production load before asking any other team to adopt it.
- Manual dashboard only; no alerting yet — prove grouping quality first, since a system that groups badly will be actively distrusted once alerting is turned on.

**Phase 2 — alerting on, expand to a handful of high-traffic services.**
- Turn on new-issue and regression alerts only (highest signal, lowest noise). Threshold/anomaly rules come after teams trust the basics.

**Phase 3 — org-wide rollout, additional language SDKs.**
- Self-serve project onboarding (generate a write key, install SDK, done).
- Add source-map/symbol upload to the CI/CD pipeline so symbolication is automatic from day one for each team, not a manual step discovered during the first real incident.

**Phase 4 — client-side (browser/mobile) error tracking on the same backend.**
- Reuses ingest/grouping/storage/alerting/visualization wholesale; the only new work is a browser/mobile SDK with different constraints (offline queuing, much higher event volume per unique user, network-cost sensitivity).

**Kill switches (always available):**
- Per-project ingest disable (emergency stop for a runaway integration).
- Global sampling override (temporarily raise the sampling floor fleet-wide if storage is under unexpected pressure).
- Alerting mute-all for a project (during a known, already-being-fixed incident, so on-call isn't paged again for the same root cause).

---

## 14. Interview Prompts This Design Answers

1. *"How do you group 10,000 occurrences of the same bug into one issue?"* — Server-side, versioned fingerprint from normalized stack frames (§6.3); never trust client-computed hashes.

2. *"What happens when a bad deploy causes a 500× spike in errors — does your system fall over?"* — No: SDK circuit breaker → ingest quota → Kafka buffering → progressive payload sampling with count-preservation (§3.2, §6.7, §10.1). The spike is exactly the moment the alert must still fire correctly.

3. *"How is this different from a general APM / metrics system?"* — Metrics/APM answer "how healthy is the system in aggregate" (rates, latencies, resource usage). This system answers "what specific bug, with what exact stack trace and context, is causing that unhealthiness" — the unit of value is a groupable, triage-able Issue, not a time series.

4. *"How do you avoid alert fatigue?"* — Digest storms into one notification (§6.6, §10.5), separate new-issue/regression (high signal, always alert) from threshold/anomaly (tunable, can be muted per-project), and let teams mute known, already-tracked issues without losing the underlying count data.

5. *"Why three separate stores instead of one database?"* — Each has an access pattern the others are bad at: Postgres for small-cardinality relational issue metadata and status, ClickHouse for high-cardinality time-series aggregation at ingest scale, S3 for cheap rarely-read full payloads. Forcing all three into one store means either paying ClickHouse-scale costs for tiny issue-metadata reads, or paying Postgres-scale write limits for the raw event firehose.

6. *"How would this change for client-side (mobile/browser) errors?"* — Same backend from ingest onward; the SDK gets harder (must queue offline, must symbol-map per app version/build, must be far more conservative about bytes sent over a user's mobile data) — see Phase 4 rollout.

---

## 15. What I Would Build in Week 1

The MVP shape that earns the right to build everything else:

1. **One SDK, one language** — async capture, no sampling logic yet, straight to Kafka via a minimal ingest endpoint.
2. **A single grouping worker** — naive fingerprint (exception type + top frame only), writes to Postgres (skip ClickHouse initially; a single project's volume fits fine).
3. **A bare issue list page** — no dashboard graphs yet, just "list of issues, sorted by last seen, click to see the latest stack trace."
4. **One alert: new issue → Slack webhook.** No digesting, no anomaly detection.

Everything else in this document — sampling ladders, rollup tiers, symbolication, anomaly detection, real-time streaming — is a scaling and signal-quality problem that only shows up once the MVP is in front of real traffic and real engineers relying on it.

---

*Document ends. In an interview, be ready to draw section 5's pipeline and section 6.3's grouping algorithm on the whiteboard from memory — those two are where 80% of the follow-up questions come from.*
