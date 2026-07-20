# Client-Side Error Monitoring System — Design

Design a system (think **Sentry / Bugsnag / Rollbar / Datadog RUM**) that captures, deduplicates, stores, and alerts on errors happening **in the browser/mobile app on the user's device** — JS exceptions, unhandled promise rejections, network failures, React error-boundary crashes, native mobile crashes — so engineers can find and fix production issues before users report them.

---

## How to Approach This in an Interview

### 1. Clarify requirements (30–60 seconds)

| Ask | Why |
|-----|-----|
| **Scope:** Web (JS) only, or also mobile (iOS/Android crash reports) and hybrid (React Native)? | Native crashes need symbolication (dSYM/ProGuard), not just source maps. |
| **What counts as an "error"?** Uncaught JS exceptions, unhandled promise rejections, console.error, network errors (4xx/5xx), resource load failures (broken image/script), React error boundaries, custom `captureException()` calls? | Determines the SDK's hook surface. |
| **Volume:** DAU, errors/session, peak traffic (e.g. during a bad deploy, error rate can spike 100x)? | Drives ingestion capacity and the need for client-side sampling/rate-limiting. |
| **Fidelity needed:** Just message + stack trace, or full context — breadcrumbs (user actions, network calls, console logs), session replay, device/browser/OS, release/version, user identity? | More context = more useful for debugging, but more bandwidth/PII risk. |
| **Latency to alert:** Seconds (page a human during an incident) or minutes (daily triage)? | Determines whether you need a real-time streaming path vs. batch. |
| **Privacy/compliance:** Must scrub PII (emails, tokens, form values) before it leaves the device or before storage (GDPR)? | Affects SDK design (client-side scrubbing) and server-side retention policy. |
| **Grouping:** Should 10,000 occurrences of "the same bug" collapse into one actionable "issue," or is every event standalone? | This "fingerprinting/grouping" problem is the core hard part of the system — call it out early. |

**What to say:** *"I'll assume we're building a web-focused SDK + backend that captures uncaught exceptions, unhandled promise rejections, and manually-reported errors with stack traces and contextual breadcrumbs, deduplicates millions of raw events into a manageable set of grouped 'issues,' scrubs PII client-side, tolerates the SDK itself never crashing or slowing down the host page, and alerts on new/regressed/spiking issues within seconds."*

### 2. State the high-level idea (30 seconds)

**What to say:** *"A lightweight SDK sits in the client app, hooks into `window.onerror`, `unhandledrejection`, and framework-specific error boundaries, captures a stack trace plus breadcrumbs, scrubs PII, and beacons the event (batched, compressed, non-blocking) to an ingestion API. The backend deduplicates events into 'issues' using a fingerprint derived from the normalized stack trace, symbolicates minified stack traces via source maps, stores raw events + aggregated issue state, and runs alerting rules (new issue, regression, spike) on the ingest stream."*

### 3. The two hardest sub-problems (call these out explicitly)

1. **Never make things worse.** The monitoring SDK runs inside the app it's monitoring — a bug in the SDK (or the SDK itself throwing, blocking the main thread, or amplifying traffic during an incident) can take down the very app it's supposed to protect. Every design decision (async, sampling, circuit breakers, fail-silent) traces back to this constraint.
2. **Turn noise into signal.** A single bug can produce millions of near-identical raw events (every affected user, every page load). The system's core value is **grouping** those into one "issue," tracking its lifecycle (new → seen 50k times → resolved → regressed), not drowning engineers in raw events.

### 4. Walk through the event lifecycle

```
Browser exception
   → SDK captures (stack, breadcrumbs, context)
   → client-side scrub (PII) + sample (rate-limit)
   → local buffer, batch + compress
   → beacon (sendBeacon/fetch keepalive) to ingestion endpoint
   → ingestion gateway: auth, rate-limit, validate, ack fast
   → queue (Kafka)
   → processing: symbolicate (source map), normalize stack, compute fingerprint
   → group into issue (new fingerprint = new issue, else increment existing)
   → store raw event (sampled) + update issue aggregate
   → alerting engine evaluates rules on the stream
   → dashboard / Slack / PagerDuty
```

**What to say:** *"The critical property is that everything left of 'ingestion gateway' must be fire-and-forget from the browser's perspective — the SDK never blocks the UI thread waiting for a response, and if ingestion is down, events are dropped (or buffered briefly) rather than retried aggressively, because retry storms from millions of clients during an incident is itself a DDoS risk."*

### 5. Edge cases and follow-ups to mention

- **Error storms:** A bad deploy can cause every user's session to throw the same error repeatedly. Client-side rate-limiting (e.g. max N events per error-type per session) and server-side sampling protect both bandwidth and the pipeline.
- **Minified/bundled code:** Stack traces point to `bundle.min.js:1:48213`, meaningless to a human — requires **source maps** to symbolicate back to original file/line/function, uploaded at build time and matched by release version.
- **Cross-origin scripts:** `window.onerror` reports `"Script error."` with no detail for scripts loaded from a different origin, unless the script has `crossorigin` + the server sends `Access-Control-Allow-Origin` — a classic gotcha to mention.
- **Ad blockers / privacy extensions:** Many block requests to known error-tracking domains (`sentry.io`, etc.) — first-party proxying (send through your own domain, reverse-proxy to the vendor) mitigates this; also means captured data undercounts true error rate.
- **Duplicate/flaky grouping:** Naive grouping by exact message text breaks when messages embed dynamic data (user IDs, URLs); grouping purely by top stack frame breaks when the same bug manifests through different call paths. Fingerprinting needs to balance precision vs. recall (discussed in depth below).
- **Beacon reliability on page unload:** Regular `fetch`/`XHR` can be cancelled when the user navigates away mid-request — use `navigator.sendBeacon()` or `fetch(..., {keepalive: true})` so the browser guarantees delivery attempts even during unload.
- **Offline/flaky network (mobile):** Buffer events to local storage/IndexedDB and flush on reconnect, with a cap and TTL so a long-offline device doesn't replay a huge backlog.

Keeping this structure — **clarify → high-level idea → the two hard sub-problems → event lifecycle → edge cases** — mirrors how Staff-level candidates are expected to approach an ambiguous "design X monitoring system" prompt.

---

## 1. Problem Statement

Design a system that lets application developers **automatically detect, group, and get alerted on errors happening on end-user devices** (browser tabs, mobile apps) without requiring the user to file a bug report, such that:

- Errors are captured with enough context (stack trace, breadcrumbs, environment) to be **actionable** without reproduction.
- Millions of raw occurrences collapse into a small number of **grouped issues** ranked by frequency/impact/recency.
- The monitoring SDK itself is **safe** — negligible performance overhead, cannot crash or hang the host app, degrades gracefully under load.
- Engineers are alerted on **new** or **regressed** issues within seconds-to-minutes, not buried in a dashboard nobody checks.
- PII is scrubbed/controlled per compliance requirements (GDPR, CCPA).

---

## 2. Requirements & Constraints

### Functional Requirements

- Capture uncaught exceptions, unhandled promise rejections, manually-reported errors (`captureException`), and optionally console errors / network failures / resource load failures.
- Capture **stack traces**, **breadcrumbs** (recent user actions, console logs, network requests, navigation), and **context** (browser/OS/device, app release/version, user/session identifiers, custom tags).
- **Deduplicate/group** events into issues via fingerprinting.
- **Symbolicate** minified/obfuscated stack traces using uploaded source maps (web) or debug symbols (mobile: dSYM for iOS, ProGuard/R8 mapping for Android).
- Support **search/filter** by release, environment, user, error type, time range.
- **Alert** on new issues, regressions (a previously-resolved issue reoccurring), and spikes (rate anomaly).
- Support **release tracking** — tie issues to the deploy that introduced/fixed them; support "resolved in version X."
- Optional: **session replay** (lightweight DOM/event recording) to visually reproduce the user's steps leading to the error.

### Non-Functional Requirements

| Dimension | Target |
|-----------|--------|
| **SDK overhead** | <1–2ms added to page load; async, non-blocking; no measurable main-thread jank. |
| **SDK safety** | The SDK must never throw an uncaught exception itself, or amplify an outage (no unbounded retries). |
| **Ingestion throughput** | Support order-of-magnitude spikes (10–100x baseline) during incidents without falling over — must shed load gracefully (sampling), not crash. |
| **Alert latency** | New/regressed issue → notification within seconds to low minutes. |
| **Durability** | Aggregated issue counts must be accurate/durable even if individual raw events are sampled/dropped under load. |
| **Availability** | Ingestion path must stay up during the exact moments client apps are most broken (correlated failure risk — e.g. a bad deploy causing both app errors *and* a traffic spike to the monitoring service). |
| **Privacy** | Configurable PII scrubbing (client-side before send, and server-side as defense-in-depth); data retention limits. |
| **Multi-tenancy** | Isolate data/rate-limits per customer project (if built as a platform, like Sentry SaaS) or per team/app (if internal). |

**Explicit non-goals to state (scoping judgment):**

- Perfect capture of every single error under extreme load — sampling with statistically-sound estimated counts is an accepted trade-off, not a bug.
- Real user monitoring (RUM) performance metrics (Core Web Vitals, page load time) are a related-but-separate system, even though they often share the same SDK/beacon infrastructure.

---

## 3. Capacity Estimation

```
Assumptions:
  DAU: 10M users, avg 5 sessions/day, avg 10 page views/session
  Baseline error rate: ~0.5% of page views throw an error → 10M × 5 × 10 × 0.005
                       = 2.5M raw error events/day baseline ≈ 29 events/sec avg
  Incident spike: a bad deploy can 50–100x this for the affected cohort
                       → design for ~3,000 events/sec sustained burst capacity

Per-event payload: stack trace + breadcrumbs (last ~30–50 actions) + context
                       ≈ 5–20 KB per event (compressed ~2–5 KB)

Ingestion bandwidth (burst): 3,000 events/sec × 5 KB ≈ 15 MB/s
Daily raw volume (baseline): 2.5M events × 10 KB ≈ 25 GB/day (before sampling/compression)

Grouping: raw events collapse ~100–1000x into distinct issues
                       → ~2,500–25,000 distinct issues/day surfaced to engineers

Retention: raw events 30 days (or sampled after volume threshold), 
           aggregated issue metadata/counts kept indefinitely (much smaller)
```

**Takeaway to say out loud:** *"The raw event volume is bursty and can spike 50–100x during exactly the incidents we most need visibility into, so client-side sampling and server-side backpressure/load-shedding aren't optional extras — they're core to the design. The aggregated issue counts, not every raw event, are the durable source of truth for 'how bad is this.'"*

---

## 4. High-Level Architecture

```
┌────────────────────────────────────────────────────────────────────────────┐
│                              CLIENT (Browser / Mobile App)                 │
│                                                                            │
│   ┌────────────────────────────────────────────────────────────────┐      │
│   │                     Monitoring SDK (embedded)                   │      │
│   │  ┌───────────────┐ ┌───────────────┐ ┌─────────────────────┐   │      │
│   │  │ Error Hooks    │ │ Breadcrumb     │ │ Context Enricher    │   │      │
│   │  │ window.onerror │ │ Collector       │ │ (release, user,      │   │      │
│   │  │ unhandled-     │ │ (console, XHR/  │ │  browser, tags)      │   │      │
│   │  │ rejection,     │ │  fetch, clicks, │ │                      │   │      │
│   │  │ ErrorBoundary  │ │  navigation)    │ │                      │   │      │
│   │  └───────┬───────┘ └───────┬───────┘ └──────────┬───────────┘   │      │
│   │          └─────────────────┴────────────────────┘               │      │
│   │                             │                                    │      │
│   │                    ┌────────▼─────────┐                          │      │
│   │                    │  PII Scrubber      │                        │      │
│   │                    │  + Client Sampler  │  (rate-limit/dedupe)   │      │
│   │                    └────────┬─────────┘                          │      │
│   │                             │                                    │      │
│   │                  ┌──────────▼──────────┐                        │      │
│   │                  │  Local Buffer/Queue   │  (disk-backed on      │      │
│   │                  │  (batch + compress)   │   mobile/offline)      │      │
│   │                  └──────────┬──────────┘                        │      │
│   └─────────────────────────────┼───────────────────────────────────┘      │
└─────────────────────────────────┼──────────────────────────────────────────┘
                                  │ sendBeacon / fetch(keepalive) — async, fire-and-forget
                                  ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                            INGESTION LAYER                                 │
│   ┌────────────────┐    ┌────────────────┐    ┌───────────────────────┐   │
│   │ Edge/CDN         │──▶│ Ingestion       │──▶│ Auth, per-project      │   │
│   │ (anycast, close   │  │ Gateway (LB'd)  │   │ rate-limit, schema      │   │
│   │  to user)         │  │ fast ACK        │   │ validation, load-shed   │   │
│   └────────────────┘    └───────┬────────┘    └───────────────────────┘   │
└──────────────────────────────────┼──────────────────────────────────────────┘
                                   ▼
                        ┌────────────────────┐
                        │  Message Queue       │  (Kafka — durable, backpressure buffer)
                        │  topic: raw-events    │
                        └─────────┬──────────┘
                                  ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                          PROCESSING PIPELINE                               │
│  ┌────────────────┐  ┌────────────────────┐  ┌──────────────────────┐    │
│  │ Symbolication    │─▶│ Normalization &     │─▶│ Fingerprinting /       │    │
│  │ (source maps /   │  │ PII re-scrub (       │  │ Grouping Service       │    │
│  │  dSYM/ProGuard)  │  │ defense in depth)   │  │ (new vs existing issue)│    │
│  └────────────────┘  └────────────────────┘  └──────────┬───────────┘    │
│                                                            │                │
│                    ┌───────────────────────────────────────┼──────────┐    │
│                    ▼                                       ▼          │    │
│         ┌────────────────────┐                  ┌────────────────────┐│    │
│         │ Raw Event Store      │                  │ Issue Aggregate     ││    │
│         │ (sampled, blob/      │                  │ Store (counts,      ││    │
│         │  columnar — S3 +     │                  │  first/last seen,   ││    │
│         │  ClickHouse)         │                  │  status) — RDBMS/   ││    │
│         └────────────────────┘                  │  wide-column store  ││    │
│                                                    └─────────┬──────────┘│    │
└──────────────────────────────────────────────────────────────┼──────────┘
                                                                ▼
                                              ┌────────────────────────────┐
                                              │  Alerting Engine             │
                                              │  (new issue / regression /   │
                                              │   spike detection — stream   │
                                              │   processing over the queue) │
                                              └─────────────┬──────────────┘
                                                             ▼
                                         ┌──────────────────────────────────┐
                                         │  Notification (Slack/PagerDuty/   │
                                         │  Email/Webhook) + Dashboard (UI)  │
                                         └──────────────────────────────────┘
```

---

## 5. Client-Side SDK Deep Dive

This is the part most unique to "client-side" monitoring (vs. generic backend/metrics monitoring), so it deserves the most depth.

### 5.1 Capture Hooks

| Source | Hook | Notes |
|--------|------|-------|
| Uncaught exceptions | `window.onerror` / global `error` event | Gives message, filename, line/col, and (in modern browsers) an `Error` object with `.stack`. |
| Unhandled promise rejections | `window.onunhandledrejection` | Easy to miss — a huge fraction of real-world JS bugs are un-caught async rejections. |
| React errors | `ErrorBoundary` (`componentDidCatch`) or React 19 `onCaughtError`/`onUncaughtError` root options | Catches render-tree errors that don't otherwise reach `window.onerror`. |
| Manual reporting | `Sdk.captureException(err)`, `Sdk.captureMessage(msg)` | For errors caught-and-handled by app code but still worth tracking (e.g. failed API call after retries). |
| Console errors | Monkey-patch `console.error` | Optional — noisy signal, often used as a secondary/breadcrumb source rather than a primary error. |
| Network failures | Wrap `fetch`/`XMLHttpRequest` | Captures 4xx/5xx and timeouts as breadcrumbs and/or standalone events. |
| Resource load failures | `error` events on `<script>`/`<img>`/`<link>` (capture phase, since these don't bubble) | Catches broken CDN assets, ad-blocked resources. |
| Native mobile crashes | OS-level crash handler (`NSSetUncaughtExceptionHandler` on iOS, `Thread.setDefaultUncaughtExceptionHandler` on Android) | Needs to persist the crash report to disk immediately (process is dying) and upload on next launch. |

**Design principle — the SDK must never itself throw:** every hook, scrubber, and serializer is wrapped in its own `try/catch`; if breadcrumb collection fails, the event is still sent (possibly with fewer breadcrumbs) rather than lost.

### 5.2 Stack Trace Capture & the Minification Problem

- Modern JS engines expose `.stack` as a string; the SDK parses it into structured frames (`function`, `file`, `line`, `col`).
- Production bundles are minified/bundled — a frame like `t.onClick (bundle.min.js:1:48213)` is useless to a human.
- **Source maps** (`.map` files generated at build time) map minified positions back to original source. The SDK does **not** ship source maps to the client (that would leak source code); instead:
  1. Build pipeline uploads source maps to the monitoring backend, tagged with the **release version**.
  2. Client SDK tags every event with the same release version (e.g. via `SENTRY_RELEASE` injected at build time).
  3. Server-side symbolication service looks up the matching source map by release + file, resolves original file/line/function/surrounding source lines.
- Mobile equivalent: iOS dSYM files (debug symbols) and Android ProGuard/R8 mapping files, uploaded per build, matched by build ID/version code.

### 5.3 Breadcrumbs & Context

- **Breadcrumbs:** a rolling in-memory ring buffer (e.g. last 50–100 entries) of: navigation events, clicks, console logs, XHR/fetch calls (URL, status, duration — not full body by default), state-management actions (Redux/Vuex, via optional integration). Attached to the event at capture time so engineers see "what led up to this."
- **Context:** browser/OS/device, viewport size, app release/version, environment (prod/staging), user identifier (configurable — anonymized ID by default), custom tags set by the app (e.g. feature flags active, A/B variant, subscription tier).
- **Session Replay (optional, higher-fidelity products):** records DOM mutations + input events (with input values masked by default) as a compact event stream, reconstructable into a video-like replay. Significant bandwidth/privacy trade-off — usually sampled at a much lower rate than error capture itself (e.g. only replay sessions that had an error).

### 5.4 Client-Side Privacy (PII Scrubbing)

- Scrub known-sensitive patterns (emails, credit-card-looking numbers, auth tokens/headers, cookies) from breadcrumbs, URLs (query params), and request/response bodies **before** the event leaves the device — defense should start at the source, not rely solely on server-side redaction.
- Configurable deny-lists (field names like `password`, `ssn`) and regex-based scrubbers; allow the app to register a `beforeSend(event)` hook to redact custom fields.
- Never capture full request/response bodies by default; opt-in only, with the same scrubbing applied.

### 5.5 Client-Side Sampling, Rate-Limiting & Circuit Breaking

- **Per-error-type rate limit within a session:** e.g. cap at 10 occurrences of the identical error per session — the 11th+ is counted locally and reported as an aggregate "+N more" rather than sent individually. Prevents one runaway `setInterval` throwing every 100ms from flooding ingestion.
- **Global sampling rate:** configurable `sampleRate` (0.0–1.0); useful for very high-traffic apps to control cost/volume while keeping statistically-valid estimated totals (server multiplies observed count by `1/sampleRate`).
- **Backend-driven dynamic sampling:** the SDK can fetch (or receive via response headers) an updated sample rate from the server, so the backend can tell all clients "reduce sampling to 1%" during an ingestion overload event — a critical safety valve during correlated-failure incidents.
- **Circuit breaker on the transport:** if the ingestion endpoint is consistently failing/timing out, back off (exponential) and eventually stop trying for a cooldown window, rather than retry-storming a service that's already struggling — especially important since a bad deploy that breaks the app is often correlated with a traffic spike to the monitoring service itself.

### 5.6 Transport

- Preferred: `navigator.sendBeacon(url, blob)` — browser guarantees best-effort delivery even during page unload, non-blocking, no response needed.
- Fallback: `fetch(url, {method: 'POST', keepalive: true, body})` when payload exceeds `sendBeacon`'s size limit (~64KB) or on environments without `sendBeacon` (older browsers, some mobile WebViews).
- Batch multiple events per request (debounced, e.g. flush every 5s or when buffer hits N events) to amortize connection overhead; compress with gzip/brotli.
- Offline/mobile: persist unsent batches to `IndexedDB`/local disk; flush on reconnect (`navigator.onLine` / OS network callback), with a max buffer size and TTL to bound memory/disk and avoid replaying a huge stale backlog.
- **First-party proxying** (send to `yourapp.com/monitoring-proxy` which forwards to the vendor) to avoid ad-blocker domain blocklists and reduce third-party-cookie/CSP friction.

---

## 6. Server-Side Deep Dive

### 6.1 Ingestion Gateway

- Stateless, horizontally scaled behind a load balancer; terminates TLS close to the user (anycast/CDN edge) to minimize latency for the fire-and-forget beacon.
- Responsibilities: authenticate (per-project API key/DSN), validate payload schema, enforce **per-project rate limits** (multi-tenant fairness — one noisy customer/app shouldn't starve others), decompress, and **ACK immediately** after handing off to the queue — no synchronous processing on this path.
- Under overload: shed load by rejecting (with a clear `429` + `Retry-After`, or silently dropping if the client won't meaningfully retry) rather than queuing unbounded and cascading the backpressure upstream. This is where the "dynamic sample rate" signal back to clients (5.5) originates.

### 6.2 Queue as the Shock Absorber

- Kafka (or similar) sits between ingestion and processing specifically to absorb the 50–100x burst scenario (§3) without ingestion having to synchronously wait on slower downstream stages (symbolication, grouping, storage writes).
- Partition by project/tenant ID to preserve per-tenant ordering (useful for correctly computing "first seen"/sequence within a project) while still parallelizing across tenants.
- Consumer lag is a key operational signal — if processing falls behind during a storm, alerting latency degrades gracefully (issues still get created, just a bit later) rather than the system falling over.

### 6.3 Fingerprinting & Grouping — the Core Algorithm

**Goal:** collapse "the same underlying bug, manifesting across thousands of users/sessions" into one **issue**, without merging genuinely different bugs (precision) or splintering one bug into many issues (recall).

**Default fingerprint strategy:**

1. Normalize the stack trace: strip line/column numbers that vary between builds but not the bug itself when using function-level fingerprints, or keep them when they matter; strip memory addresses, timestamps, and other non-deterministic tokens from the error message.
2. Take the **top N stack frames** (e.g. top 5–10), preferring **in-app frames** over third-party/vendor library frames (a bug's signature is usually best captured by *your* code's call path, not the internals of a library it happens to call into).
3. Hash `(exception type, normalized message template, list of {function, file} for the selected frames)` → fingerprint.
4. Look up the fingerprint in the issue index:
   - **Exists** → increment count, update `last_seen`, attach this occurrence's context, possibly flip status from "resolved" back to "regressed" if it was previously marked fixed.
   - **New** → create a new issue, trigger a "new issue" alert.

**Refinements to mention:**

- **Message templating:** replace dynamic tokens (`user 12345 not found` → `user {id} not found`) before hashing, e.g. via regex heuristics (numbers, UUIDs, emails) so the same bug with different data doesn't fragment into many issues.
- **Custom fingerting rules:** let app owners override grouping for known-noisy patterns (e.g. group all "Network request failed" errors regardless of stack, or explicitly split two errors that hash identically but are actually distinct).
- **Fuzzy/similarity grouping (advanced):** for issues where naive hashing over/under-groups, some systems use edit-distance or embedding-similarity on stack traces to suggest merges, surfaced to a human for confirmation rather than fully automated (auto-merging wrong issues destroys trust in the tool).
- **Grouping is inherently a precision/recall trade-off** — call this out explicitly as a design tension with no perfect answer, only tunable heuristics plus a manual merge/split UI as an escape hatch.

### 6.4 Storage Strategy

| Data | Store | Why |
|------|-------|-----|
| Raw events (sampled) | Columnar store (ClickHouse) or blob store (S3) + index | High write throughput, good for time-range scans/search/faceted filtering; raw payload often not read except when investigating a specific occurrence. |
| Issue aggregates (count, first/last seen, status, assignee) | RDBMS or wide-column store (Postgres / Cassandra / DynamoDB) | Relatively low cardinality (thousands–tens of thousands of issues per project vs. millions of raw events), needs strong read consistency for the dashboard/triage UI. |
| Breadcrumbs/context blobs | Object storage (S3), referenced by event ID | Large, rarely accessed after triage, cheap cold storage with lifecycle rules (e.g. move to Glacier/delete after 90 days). |
| Source maps / debug symbols | Object storage, keyed by release/build ID | Read-heavy during symbolication, write-once at build/deploy time; can be cached aggressively. |
| Search index (full-text over messages/tags) | Elasticsearch/OpenSearch | Powers the "search issues" UI. |

- **Retention tiers:** keep full raw events for a short window (e.g. 30–90 days), then downsample to "N sample events per issue" for long-tail history, while issue aggregate counts/trends are kept indefinitely (they're much smaller and are what most dashboards actually query).

### 6.5 Alerting Engine

Runs as a stream processor consuming the same event stream (or the grouping service's output) to detect:

- **New issue:** first occurrence of a fingerprint in a project → alert immediately (highest signal, lowest noise if grouping is tuned well).
- **Regression:** a fingerprint previously marked "resolved" reoccurs → alert (a shipped fix didn't actually fix it, or a regression was reintroduced).
- **Spike/anomaly:** occurrence rate for an issue (or overall error rate) exceeds a threshold or deviates from a rolling baseline (e.g. z-score over a trailing window, or simple ">Nx the rate from the same time yesterday") → alert, useful for catching a bad deploy fast.
- **Threshold rules:** user-defined, e.g. "alert if `PaymentFailedException` occurs >50 times in 5 minutes."

Delivery: dedupe/coalesce alerts (don't re-page for the same ongoing spike every minute — use alert-state machines with cooldowns, similar to general metrics alerting), route via Slack/PagerDuty/email/webhook, and always link back to the specific issue in the dashboard.

### 6.6 Release Tracking & Deploy Correlation

- Every event is tagged with a **release/version** (injected at build time, e.g. git SHA or semantic version).
- The backend correlates issue first-seen timestamps against deploy events (via a `/releases` API called by CI/CD) to answer "did release 2.14.0 introduce this?" and to support "resolve in next release" workflows and automatic regression detection when the same fingerprint reappears in a later release after being marked resolved.

---

## 7. Scalability & High Availability

- **Ingestion gateways** and the **queue** are the load-bearing components during incidents — scale them independently and generously (more headroom than "steady state" would suggest) since their peak load correlates with the peak load of every client app they serve.
- **Multi-region ingestion** with regional queues, replicated or independently processed, reduces latency and blast radius (a regional outage shouldn't lose data from unaffected regions) — trade-off: cross-region deduplication/grouping needs either a global grouping service or eventual reconciliation.
- **Backpressure propagates as sampling instructions**, not as unbounded queuing — when consumer lag grows, push a lower `sampleRate` to clients (via response headers/remote config) so the system degrades gracefully rather than falling further behind or OOMing.
- **Multi-tenant isolation:** per-project rate limits and quota enforcement at the gateway prevent one tenant's error storm from degrading ingestion for others; separate Kafka partitions/consumer groups per large tenant if needed.
- The **symbolication and grouping services** are stateless/horizontally scalable consumers of the queue; the **issue aggregate store** is the main stateful bottleneck — mitigate with sharding by project ID and read replicas for the dashboard's read-heavy query pattern.

---

## 8. Failure Modes & Mitigations

| Failure | Impact | Mitigation |
|---------|--------|------------|
| Ingestion service down | Events lost during the outage | Client-side local buffering with a bounded TTL/size cushions brief outages; beyond that, accept loss (fail-open) rather than have clients retry-storm — some data loss during an outage is an acceptable trade-off vs. amplifying it. |
| Traffic spike during incident (correlated with the app outage the tool exists to detect) | Ingestion/queue overload, delayed alerts exactly when they matter most | Queue as shock absorber, aggressive autoscaling on ingestion, dynamic client-side sample-rate throttling, priority lane for "new issue" signal vs. bulk raw-event volume. |
| SDK bug/hang | Could make the *host app* worse, defeating the purpose | Wrap every SDK operation in try/catch, run heavy work (serialization, compression) off the main thread where possible (Web Worker), hard time-budget per operation, extensive testing of the SDK itself as a top release-quality bar. |
| Source map upload missing/mismatched for a release | Stack traces stay minified/unreadable | Fail visibly in CI (fail the deploy or warn loudly) if source maps aren't uploaded for a release; keep raw minified trace as fallback so at least *something* is captured. |
| Grouping mis-fires (fragments one bug into many issues, or merges unrelated ones) | Noisy/confusing triage UI, alert fatigue, or missed alerts | Manual merge/split UI, custom fingerprinting rules, periodically review "similar issues" suggestions; treat this as a tunable heuristic requiring ongoing curation, not a solved problem. |
| PII leak (scrubber misses a field) | Compliance/privacy incident | Defense-in-depth (client scrub + server-side re-scrub + audit logging of raw payloads), configurable field-level redaction, regular audits, and the ability to purge/redact after the fact. |
| Ad blockers / privacy tools blocking the vendor's domain | Silent under-counting of real error rate | First-party proxy endpoint on the app's own domain forwarding to the backend; monitor delivery rate as its own health metric. |

---

## 9. Comparison: Build vs. Buy

| Aspect | Build in-house | Buy (Sentry / Bugsnag / Rollbar / Datadog RUM) |
|--------|-----------------|--------------------------------------------------|
| Time to value | Slow — grouping/fingerprinting alone is a multi-quarter investment to get right | Fast — SDK integration in hours |
| Grouping quality | Starts naive, improves with iteration and real-world tuning | Years of production tuning across many customers/languages |
| Cost at scale | Infra + eng time, but no per-event vendor pricing | Usage-based pricing can get expensive at very high event volume |
| Compliance/data residency | Full control (useful for strict regulatory environments) | Depends on vendor's regional hosting options |
| Customization | Full control over fingerprinting rules, storage, retention | Configurable within the vendor's model, less flexible for bespoke needs |

**What to say if asked "would you build this?":** *"Unless there's a specific data-residency, cost-at-extreme-scale, or deep-customization reason, I'd default to buying — the hard part (grouping quality, SDK safety across every browser/OS quirk) has been tuned over years by vendors, and it's rarely the differentiated part of the product. It's still valuable to understand the internals, both to integrate the vendor well (source maps, release tracking, PII scrubbing config) and to know when in-house makes sense (e.g. a regulated environment that can't send data to a third party, or extreme volume where usage-based pricing dominates infra cost)."*

---

## 10. Summary

1. The SDK's first job is **do no harm** — async, sandboxed, self-limiting (rate limits, circuit breakers, dynamic sampling), so a bug in the monitored app (or an incident-driven traffic spike) never becomes a bug in — or an amplifier of — the monitoring tool itself.
2. Capture happens at every layer where the platform gives a hook: `window.onerror`, unhandled rejections, framework error boundaries, manual capture, plus breadcrumbs and context for debuggability without reproduction.
3. **Fingerprinting/grouping** is the core hard problem — turning millions of raw events into a small, ranked list of actionable issues — and is fundamentally a tunable precision/recall trade-off, not a solved algorithm.
4. The backend pipeline mirrors a generic high-throughput ingestion system (gateway → queue → stream processing → storage → alerting) with the queue acting as the essential shock absorber for the bursty, incident-correlated traffic pattern unique to error monitoring.
5. Privacy (client + server-side PII scrubbing), release/version correlation, and graceful degradation under load are first-class design concerns, not afterthoughts.
