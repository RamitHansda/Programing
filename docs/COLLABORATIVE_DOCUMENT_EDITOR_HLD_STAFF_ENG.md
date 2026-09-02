# Distributed Collaborative Document Editor — High-Level Design (Staff Engineer Level)

**Level:** Staff / Principal  
**Format:** Whiteboard-ready HLD with diagrams, trade-offs, failure modes  
**Product analogy:** Google Docs / Notion / Figma text layer  
**Target scale:** 100 M docs · 10 M DAU · 500 K concurrent editors · < 100 ms local feel · < 300 ms remote visibility p99

---

## 0. Executive Summary

### 0.1 Headline SLOs

| Metric | Target | Measurement |
|---|---|---|
| Concurrent connected editors | 500 K | WebSocket connections alive |
| Concurrent editors per document (hot) | 200 (soft) / 500 (hard) | Presence + session caps |
| Local echo latency (keystroke → screen) | < 16 ms | Client-only; never wait on network |
| Remote visibility p99 (same region) | < 300 ms | Op applied locally → peer render |
| Remote visibility p99 (cross-region) | < 800 ms | Same, cross AZ/region |
| Doc open TTFB (warm) | < 200 ms | Metadata + first snapshot fetch |
| Doc open TTFB (cold, large) | < 1.5 s | Snapshot + catch-up ops |
| Durability ACK | < 200 ms p99 | Op accepted → durable log write |
| Availability (edit path) | 99.95 % | Regional WS + Collab Service |
| RPO (document content) | 0 | Sync write to durable op log before ACK |
| RTO (region failure) | < 5 min | Sticky failover + snapshot rebuild |

### 0.2 Architecture in one sentence

> **Clients apply ops optimistically with CRDTs; a per-document sticky Collab Service sequences presence and optional server-side validation, persists an append-only op log, and fans out deltas over WebSockets; durable snapshots + object storage keep cold/open fast; AuthZ and Search sit off the hot path.**

### 0.3 Core thesis (non-negotiable)

1. **Local-first UX** — typing must never block on the network.
2. **Convergence without locks** — concurrent edits from N peers converge to the same document.
3. **Durable before ACK** — an accepted op is recoverable after crash.
4. **Document affinity** — all live collaborators for a doc talk to one logical sequencer (shard).
5. **Hot path stays thin** — ACL checks cached, search/indexing/notifications async.

---

## 1. Problem Framing

Before boxes: **what are we actually solving?**

A collaborative editor answers:

- Can two people type in the same paragraph and both see a consistent result?
- If my laptop dies mid-edit, is the last keystroke recoverable?
- Can I open a 50-page doc in < 2 s and keep typing while history loads?
- Can 100 people co-edit a launch note without the room melting?
- Can we revoke access and stop further reads/writes immediately?

This is **not** a CRUD blog CMS. The hard problems are **concurrency, ordering, presence, offline, and fan-out**.

---

## 2. Requirements

### 2.1 Functional

| Area | Requirements |
|---|---|
| **Editing** | Rich text (bold/italic/lists/headings), paragraphs, embeds (images/tables), comments, suggestions |
| **Collaboration** | Concurrent multi-user edit, live cursors/selections, presence (who's online), typing indicators |
| **History** | Version timeline, named versions, restore, per-op attribution (who changed what) |
| **Access** | Doc ACL (owner/editor/commenter/viewer), share links, org/workspace membership |
| **Offline** | Edit offline; sync & merge on reconnect |
| **Search** | Full-text search across workspace docs (near-real-time, eventual OK) |
| **Export** | PDF / DOCX / Markdown (async) |

### 2.2 Non-Functional

| Property | Target |
|---|---|
| Consistency model | Strong eventual (CRDT) / causal + total order at sequencer |
| Partition tolerance | Prefer AP on the editor path; never block typing |
| Horizontal scale | Shard by `documentId` |
| Multi-tenant isolation | Workspace-scoped authz + rate limits |
| Audit | Immutable op log retained ≥ 1 year (configurable) |

### 2.3 Out of Scope (v1)

- Real-time video/audio inside the doc
- Spreadsheet formula engine / CRDT sheet model
- Pixel-perfect Figma-style canvas
- Cross-org federated editing
- Mobile native offline CRDT store beyond IndexedDB MVP

---

## 3. Scale Assumptions (Capacity Model)

| Dimension | Number | Notes |
|---|---|---|
| Documents | 100 M | Most cold |
| DAU | 10 M | |
| Concurrent WS connections | 500 K | Peak |
| Ops/sec global | ~2 M peak | Burst around meetings |
| Avg ops/sec per active doc | 5–50 | Typing bursts higher |
| Hot docs ( > 20 editors) | ~5 K concurrent | Need sticky routing |
| Median doc size | 50 KB snapshot | |
| P99 doc size | 5–20 MB | Snapshot + chunked catch-up |
| Op size | 50–500 B | Inserts/embeds larger |

**Back-of-envelope:**  
500 K connections × 1 KB presence/meta ≈ manageable.  
Fan-out is the killer: 1 doc × 200 editors × 30 ops/s ≈ **6 K msgs/s for one doc**. Design for **document-local fan-out**, not global broadcast.

---

## 4. OT vs CRDT — Staff Decision

This is the first question interviewers expect.

### 4.1 Operational Transformation (OT)

- Server is **authoritative sequencer**.
- Clients transform concurrent ops against each other using OT functions.
- Classic Google Docs approach.

| Pros | Cons |
|---|---|
| Mature for rich text | Transform functions are hard & fragile |
| Central order is intuitive | Offline / multi-master painful |
| Smaller client complexity historically | Server must be always reachable for correctness |

### 4.2 CRDT (Conflict-free Replicated Data Types)

- Every op is commutative / associative under merge.
- Clients can merge without central transforms.
- Used by Figma (partial), Notion-ish stacks, Yjs, Automerge, Liveblocks.

| Pros | Cons |
|---|---|
| Natural offline + multi-device | Metadata overhead (IDs, clocks) |
| Simpler reasoning about convergence | Need tombstones / GC / compaction |
| Peer-friendly; server can be thin | Rich-text CRDTs still non-trivial |

### 4.3 Recommendation for this system

> **Use a document CRDT (e.g. Yjs / Automerge-style / custom RGA+LWW)** for content, with a **sticky Collab Sequencer** for (1) durable total order of op log, (2) presence fan-out, (3) ACL gate, (4) snapshot compaction.  
> Clients remain local-first; the sequencer is an optimization for durability & UX, not a lock manager.

**Why not pure peer-to-peer?** Enterprise needs ACL, audit, durable history, and predictable fan-out. A sequencer gives that without making OT transforms a career.

**Hybrid note:** Comments/suggestions can be separate CRDT maps or even last-write-wins records keyed by `commentId`.

---

## 5. System Context Diagram

```
┌──────────────┐  WSS / HTTPS   ┌────────────────────────────────────────────┐
│  Clients     │ ─────────────► │                 EDGE                        │
│  Web / Desk  │                │  TLS · WAF · Anycast · CDN (static editor) │
│  Mobile      │                │  Sticky WS routing by documentId hash      │
└──────────────┘                └─────────────────────┬──────────────────────┘
                                                      │
                    ┌─────────────────────────────────┼─────────────────────────┐
                    │                                 │                         │
                    ▼                                 ▼                         ▼
         ┌──────────────────┐              ┌──────────────────┐      ┌─────────────────┐
         │  API Gateway     │              │  WS Gateway      │      │  Auth / IdP     │
         │  REST/GraphQL    │              │  (conn mgmt)     │      │  JWT / session  │
         │  docs, ACL, meta │              │                  │      └─────────────────┘
         └────────┬─────────┘              └────────┬─────────┘
                  │                                  │
                  │                    gRPC / internal
                  │                                  │
                  ▼                                  ▼
         ┌────────────────────────────────────────────────────┐
         │              COLLAB CONTROL PLANE                   │
         │  Doc Meta Service · ACL Service · Presence Service  │
         └──────────────────────────┬─────────────────────────┘
                                    │
                                    ▼
         ┌────────────────────────────────────────────────────┐
         │           COLLAB DATA PLANE (hot path)              │
         │                                                     │
         │   Collab Service (sticky per documentId shard)      │
         │     • validate ACL (cached)                         │
         │     • append op to durable log                      │
         │     • fan-out to subscribers                        │
         │     • trigger snapshot compaction                   │
         └───────┬─────────────────────┬──────────────────────┘
                 │                     │
                 ▼                     ▼
      ┌──────────────────┐   ┌────────────────────┐
      │ Op Log Store     │   │ Snapshot Store     │
      │ (Kafka /         │   │ (S3 + Postgres     │
      │  segmented files │   │  pointer table)    │
      │  + Postgres idx) │   └────────────────────┘
      └────────┬─────────┘
               │ async
               ▼
      ┌──────────────────┐   ┌────────────────────┐
      │ Search Indexer   │   │ Notif / Webhooks   │
      │ (OpenSearch)     │   │ Analytics          │
      └──────────────────┘   └────────────────────┘
```

---

## 6. Component Map

| Layer | Components | Role |
|---|---|---|
| **Edge** | CDN, WAF, Anycast LB | TLS, static assets, sticky WS by `documentId` |
| **Gateway** | WS Gateway, API Gateway | Conn lifecycle, JWT, rate limits, protocol mux |
| **Control plane** | Doc Meta, ACL, Workspace, Presence | Metadata, permissions, who-is-online |
| **Data plane** | Collab Service (sharded) | Op ingest, order, fan-out, compaction triggers |
| **Durable storage** | Op Log, Snapshot Store, Meta DB | Source of truth + fast open |
| **Async** | Indexer, Notifier, Exporter, GC | Search, emails, PDF, tombstone cleanup |
| **Observability** | OTel, Prometheus, lag dashboards | Op lag, fan-out latency, shard hotness |

---

## 7. Collaboration Protocol (Hot Path)

### 7.1 Client editing model

```
User types 'a'
   │
   ▼
┌─────────────────────────────┐
│ Local CRDT apply (optimistic)│  ← screen updates immediately
│ Assign unique opId           │     (clientId + lamport/hlc + counter)
│ Update local cursor          │
└──────────────┬──────────────┘
               │ enqueue outbound
               ▼
┌─────────────────────────────┐
│ WS send: OP_BATCH            │  ← may coalesce keystrokes (10–30ms)
└──────────────┬──────────────┘
               │
               ▼
         Collab Service
               │
               ├─ ACL check (cached)
               ├─ Persist op(s) → Op Log (durability)
               ├─ ACK {opId, serverSeq}
               └─ Fan-out OP_BATCH to other subscribers
```

**Rule:** Never wait for ACK to paint local keystrokes. ACK only advances "durably synced" watermark for UI (cloud icon) and GC of outbound buffer.

### 7.2 Message types (sketch)

```text
HELLO         { docId, versionHint, auth }
SNAPSHOT_REQ  { fromSeq? }
SNAPSHOT      { seq, blobRef | inline, vectorClock }
OP_BATCH      { ops[], clientTs, baseSeq }
OP_ACK        { opIds[], serverSeq }
PRESENCE      { userId, cursor, selection, color }
AWARENESS     { typing, viewport }          // ephemeral, not durable
COMMENT_OP    { ... }                       // may share same pipe
NACK / RESYNC { reason, checkpoint }
```

### 7.3 Catch-up / reconnect

1. Client stores last `serverSeq` / vector clock.
2. On reconnect: `HELLO` with checkpoint.
3. Server sends **snapshot if lag > threshold** (e.g. > 5 K ops or > 30 s), else **op replay** from log.
4. Client merges; unresolved outbound buffer re-sent (idempotent by `opId`).

### 7.4 Idempotency

Every op has stable `opId`. Op Log unique on `(docId, opId)`. Retries safe. Critical for flaky mobile networks.

---

## 8. Data Model

### 8.1 Metadata (PostgreSQL)

```sql
CREATE TABLE workspaces (
  id           UUID PRIMARY KEY,
  name         TEXT NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE documents (
  id             UUID PRIMARY KEY,
  workspace_id   UUID NOT NULL REFERENCES workspaces(id),
  title          TEXT NOT NULL,
  owner_id       UUID NOT NULL,
  status         TEXT NOT NULL DEFAULT 'ACTIVE', -- ACTIVE|ARCHIVED|DELETED
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  latest_seq     BIGINT NOT NULL DEFAULT 0,
  snapshot_seq   BIGINT NOT NULL DEFAULT 0,
  snapshot_ref   TEXT,                            -- s3://...
  shard_key      INT NOT NULL                     -- for routing affinity
);

CREATE TABLE document_acl (
  doc_id     UUID NOT NULL,
  principal  TEXT NOT NULL,   -- user:uuid | group:uuid | link:token
  role       TEXT NOT NULL,   -- OWNER|EDITOR|COMMENTER|VIEWER
  PRIMARY KEY (doc_id, principal)
);

CREATE TABLE named_versions (
  id           UUID PRIMARY KEY,
  doc_id       UUID NOT NULL,
  seq          BIGINT NOT NULL,
  snapshot_ref TEXT NOT NULL,
  label        TEXT,
  created_by   UUID,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### 8.2 Op Log (append-only)

Two viable backends (staff pick depends on ops maturity):

**Option A — Kafka (recommended at scale)**  
- Topic `doc-ops` partitioned by `hash(docId)`.  
- Key = `docId` → per-doc total order.  
- Consumers: Collab fan-out already done in-memory; Kafka is durability + rebuild + async indexers.

**Option B — Segmented file log + Postgres index**  
- Like a mini BookKeeper / custom WAL per shard.  
- Lower cost at extreme volume; more engineering.

**Logical record:**

```json
{
  "docId": "...",
  "opId": "c12:88421",
  "serverSeq": 104422,
  "actorId": "user_9",
  "ts": "2026-04-01T12:00:00.123Z",
  "payload": { "type": "insert", "...": "CRDT bytes or JSON" }
}
```

### 8.3 Snapshots

- Periodic compaction: every N ops or M minutes → materialize CRDT state → write blob to S3 → update `documents.snapshot_*`.
- Open path: load snapshot + replay ops `(snapshot_seq, latest_seq]`.
- GC: truncate op log below `min(snapshot_seq, oldest_open_client_checkpoint)` with grace.

---

## 9. Collab Service Internals (the hard box)

### 9.1 Sticky ownership

```
documentId ──hash──► shard / cell
                     │
                     ▼
            Collab Pod (owner)
            ┌─────────────────────┐
            │ DocSession memory   │
            │  - subscriber set   │
            │  - presence map     │
            │  - recent op ring   │
            │  - ACL cache        │
            │  - seq watermark    │
            └─────────────────────┘
```

**Routing:** Edge / WS Gateway uses consistent hash of `documentId` to pick Collab shard. On pod death: rehash, clients reconnect, rebuild from Op Log + snapshot (RTO seconds).

**Lease:** Optional Redis/etcd lease per `docId` if multiple pods can race; prefer **partitioned ownership** so only one writer exists.

### 9.2 Why sticky?

Fan-out without a broker hop per keystroke. Presence is local. Ordering is trivial. Cross-pod chatty mesh dies under hot docs.

### 9.3 Hot document protection

| Control | Mechanism |
|---|---|
| Soft cap | Warn at 100 editors |
| Hard cap | Reject new editors at 500; allow viewers |
| Coalescing | Server may batch awareness; never drop content ops |
| Backpressure | Slow clients get snapshot resync instead of huge op queues |
| Isolation | Hot docs pinned to beefier shards |

---

## 10. Consistency & Ordering Model

| Concern | Choice |
|---|---|
| Content convergence | CRDT merge → same state regardless of delivery order |
| Durable history order | `serverSeq` total order per doc for audit/timeline |
| Causality on client | Hybrid Logical Clock or Lamport in op metadata |
| Read-your-writes | Sticky session + local state; after refresh, snapshot ≥ last ACK seq |
| Cross-doc transactions | Not supported (v1) |

**Staff phrasing:**  
> "We provide **strong eventual consistency** for content and a **per-document linearizable op log** for durability and history. Users perceive real-time because local apply is sync and remote apply is fast fan-out—not because we take distributed locks on characters."

---

## 11. Presence, Cursors, Awareness

- **Ephemeral:** do not write every cursor move to Op Log.
- Separate channel or same WS with `PRESENCE` frames at 10–20 Hz max, coalesced.
- Store in Collab Session memory + optional Redis for cross-crash UX (nice-to-have).
- TTL: remove after disconnect grace (e.g. 15–30 s) to avoid ghost cursors.

**Privacy:** viewers may see caret positions only if ACL allows; commenters may be limited.

---

## 12. Offline Support

```
Offline edit
  → local CRDT mutate
  → queue in IndexedDB
Reconnect
  → HELLO(checkpoint)
  → merge server snapshot/ops
  → replay outbound queue (idempotent opIds)
  → resolve UI conflicts only when semantic (rare with CRDT)
```

**Semantic conflicts still exist** (two people rewrite same sentence differently)—CRDT converges bytes, product may show "both edits" history. Suggestions mode can reduce pain.

---

## 13. AuthN / AuthZ

### 13.1 AuthN

- Short-lived JWT (access) + refresh; WS `HELLO` validates JWT.
- Rotate keys; reject expired mid-session with re-auth frame.

### 13.2 AuthZ

- Roles: `OWNER > EDITOR > COMMENTER > VIEWER`.
- Checked on: open, op ingest, export, ACL change.
- Cache ACL on Collab Session with version number; ACL Service bumps version on change → push `ACL_INVALIDATE` to session → force re-check / kick.

**Share links:** capability tokens with role + expiry; rate-limit anonymous.

**Immediate revoke:** owner removes user → ACL version bump → Collab drops subscriber within seconds.

---

## 14. APIs (Control Plane)

```http
POST   /v1/workspaces/{wid}/docs              # create
GET    /v1/docs/{docId}                       # metadata
PATCH  /v1/docs/{docId}                       # title, status
GET    /v1/docs/{docId}/acl
PUT    /v1/docs/{docId}/acl                   # replace/add principals
POST   /v1/docs/{docId}/versions              # named version from seq
GET    /v1/docs/{docId}/versions
POST   /v1/docs/{docId}/export                # async job
GET    /v1/search?q=&workspaceId=

WS     /v1/docs/{docId}/collab                # editing socket
```

GraphQL optional for meta; **collab stays WebSocket/binary**.

---

## 15. Sequence Diagrams

### 15.1 First open + first edits

```
Client                 WS GW              Collab(doc)         OpLog/S3         MetaDB
  │  HELLO(doc,jwt)      │                   │                  │               │
  │─────────────────────►│──────────────────►│                  │               │
  │                      │                   │── ACL+meta ─────────────────────►│
  │                      │                   │◄─────────────────────────────────│
  │                      │                   │── load snapshot ─►│               │
  │                      │                   │◄─ snapshot+ops ───│               │
  │◄──── SNAPSHOT ───────│◄──────────────────│                  │               │
  │  OP_BATCH            │                   │                  │               │
  │─────────────────────►│──────────────────►│── append ───────►│               │
  │◄──── OP_ACK ─────────│◄──────────────────│                  │               │
  │                      │  fanout peers ◄───│                  │               │
```

### 15.2 Region / pod failover

```
Pod A dies
  → LB detects
  → clients auto-reconnect (backoff)
  → Pod B acquires doc shard
  → rebuild: snapshot + ops since snapshot_seq
  → clients catch up from last ACK
  → at-least-once delivery; idempotent opIds prevent dup apply
```

---

## 16. Storage & Compaction Strategy

| Store | What | Retention |
|---|---|---|
| S3 snapshots | Materialized CRDT state | All named versions + rolling latest |
| Op Log | Fine-grained ops | ≥ 1 year or forever for regulated tenants |
| Postgres | Meta, ACL, pointers | Forever (soft delete) |
| Redis (optional) | Presence, ACL cache, rate limits | TTL minutes |
| OpenSearch | Title + extracted plaintext | Eventual, minutes lag OK |

**Compaction job:**  
`if latest_seq - snapshot_seq > 10_000 OR age > 5m` → build snapshot → upload → advance pointer → allow log truncation per policy.

**CRDT GC:** tombstone collection after all active clients advance past vector clock (or force snapshot + full resync for stragglers).

---

## 17. Multi-Region Strategy

### v1 (recommended)

- **Active-passive per document cell** (or active-active at metadata, single-writer per doc).
- Clients connect to nearest edge; sticky to home region of doc (derived from workspace or create-time region).
- Cross-region editors accept higher RTT; still local-first CRDT.

### v2 (hard mode)

- Multi-master CRDT without global sequencer; use Merkle / vector sync between regions.
- Higher engineering cost; only if geo latency SLOs demand it.

**Staff call:** Don't start with multi-master. Sticky single-writer per doc + CRDT clients gets 95% of the product value.

---

## 18. Failure Modes & Mitigations

| Failure | User Impact | Mitigation |
|---|---|---|
| Collab pod crash | Brief disconnect | Auto-reconnect + rebuild from log; RPO 0 if ACK⇒durable |
| Kafka / log unavailable | Edits stuck "syncing" | Local buffer; degrade gracefully; don't corrupt |
| Split brain two owners | Divergent seq | Lease/fencing token; reject stale owner writes |
| Hot doc thundering herd | Latency spike | Caps, batching, dedicated hot shards |
| Giant doc open | Slow TTFB | Chunked snapshot, progressive render, lazy embeds |
| ACL revoke lag | Unauthorized view window | Versioned ACL + push invalidate; short JWT |
| Clock skew | Weird timestamps | HLC; never trust wall clock for order |
| Poison op | Client crash loop | Schema validation; quarantine; force snapshot |

---

## 19. Security Hardening

- Per-tenant rate limits (ops/s, connections/doc, share-link opens).
- Max op size / batch size; reject pathological CRDT metadata blow-ups.
- Virus scan async on uploaded embeds.
- E2E encryption? Possible (client-side keys) but breaks server search & some features—product decision.
- Audit log of ACL changes and exports.
- Pen-test share-link enumeration (high entropy tokens).

---

## 20. Observability (what staff watches)

| Signal | Why |
|---|---|
| `collab_fanout_latency_ms{p99}` | Core UX |
| `op_persist_latency_ms` | Durability path |
| `ws_reconnect_rate` | Stability |
| `doc_subscriber_count` | Hot doc detection |
| `catchup_ops_replayed` | Snapshot policy tuning |
| `acl_invalidate_lag_ms` | Security |
| `op_log_lag` / consumer lag | Async health |
| `client_outbound_queue_depth` | Offline / network pain |

Tracing: one trace id per `OP_BATCH` across GW → Collab → log.

---

## 21. Capacity Sketch (one region)

Assumptions: 250 K concurrent connections, 1 M ops/s peak ingest.

| Tier | Sizing intuition |
|---|---|
| WS Gateway | ~100 pods × 3–5 K conn (or higher with tuned Go/Netty) |
| Collab Service | ~50–100 pods; shard by doc hash; CPU bound on fan-out |
| Kafka | 12+ partitions for `doc-ops` (more for throughput); RF=3 |
| Postgres | Meta primarily; primary + replicas; not on keystroke path |
| S3 | Snapshots; lifecycle to IA |

Optimize **fan-out** before adding pods: binary frames, delta compression, awareness coalescing.

---

## 22. Key Trade-offs (say these out loud)

1. **CRDT vs OT** — CRDT wins for offline & simplicity of transforms; pay metadata/GC cost.
2. **Sticky sequencer vs pure P2P** — Sequencer buys ACL, audit, durable order; P2P is not enough for enterprise.
3. **Snapshot frequency** — More snapshots = faster open, more S3 cost/write amp.
4. **Ephemeral presence vs durable** — Don't durable-write cursors.
5. **Single-region writer vs multi-master** — Start single-writer per doc; revisit only for geo SLOs.
6. **Rich text in CRDT** — Use a proven library (Yjs) unless you have a reason to invent RGA.
7. **Search freshness** — Eventual indexing; never block ACK on OpenSearch.

---

## 23. Rollout Plan

| Phase | Scope |
|---|---|
| **M1** | Plain text CRDT, 2–10 editors, single region, snapshots |
| **M2** | Rich text, comments, ACL, presence, offline queue |
| **M3** | Hot-doc controls, named versions, export, search |
| **M4** | Multi-region sticky home, compaction GC, compliance retention |
| **M5** | Suggestions mode, large-doc progressive load, E2EE optional |

---

## 24. Interview Script (90-second version)

> "I'd build a local-first collaborative editor on CRDTs so typing never waits on the network and concurrent edits converge without brittle OT transforms. Clients connect over WebSockets through an edge that stickily routes by `documentId` to a Collab Service shard. That service is the thin control point: cached ACL, durable append to a per-doc op log, ACK, and fan-out to subscribers. Presence is ephemeral in memory. We periodically compact to S3 snapshots so opens stay fast and the log can truncate. Search, notifications, and export are async consumers off the log. Consistency is strong-eventual for content with a linearizable per-doc history for audit. Multi-region starts as single-writer-per-doc with nearest-edge connectivity; multi-master only if latency data demands it."

---

## 25. Whiteboard Checklist

- [ ] Clarify FR/NFR + scale numbers  
- [ ] Call CRDT vs OT decision early  
- [ ] Draw Edge → WS GW → Collab (sticky) → OpLog + Snapshots  
- [ ] Show optimistic local apply + ACK watermark  
- [ ] Presence ≠ durable ops  
- [ ] ACL invalidate path  
- [ ] Offline reconnect / idempotent `opId`  
- [ ] Hot doc caps + fan-out math  
- [ ] Failure: pod death, split brain fencing  
- [ ] Trade-offs & phased rollout  

---

## 26. Appendix — Minimal CRDT Intuition (RGA sketch)

For interviews, a **Replicated Growable Array** intuition is enough:

- Each character is a unique ID `(clientId, counter)`.
- Inserts reference a `parentId` (after which char).
- Concurrent inserts after same parent ordered by `(clientId)` tie-break.
- Deletes are tombstones (mark deleted), not physical removal—until GC.

You do **not** need to implement full YATA/RGA on the whiteboard; show you know why IDs + tombstones buy convergence.

---

**Document status:** Staff-engineer HLD for interview + implementation north star.  
**Primary bets:** Local-first CRDT · sticky per-doc Collab · durable op log · snapshot open path · async everything else.
