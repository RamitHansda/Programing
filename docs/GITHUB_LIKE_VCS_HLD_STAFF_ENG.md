# GitHub-like Version Control System — HLD + Interview Script

**Level:** Staff / Principal  
**Format:** Whiteboard-ready HLD with diagrams + candidate spoken script  
**Scope:** Hosted Git + collaboration (repos, PRs, authz, webhooks)  
**Out of scope (v1):** Actions runner fleet, package registry, multi-writer active-active refs

---

## Part A — High-Level Design

### 1. Requirements

#### Functional
- Git: clone / fetch / push (HTTPS + SSH)
- Branches, tags, branch protection
- PRs, reviews, merge (merge / squash / rebase)
- Users, orgs, teams, permissions
- Web UI + API, webhooks / CI status checks

#### Non-functional
- Multi-tenant, horizontally scalable
- Ref updates **linearizable** (no lost pushes)
- Objects **durable before** push ACK
- Product features must **not** block Git ACK
- Strong tenant isolation

#### Out of scope (v1)
- GitHub Actions runners
- Package registry / Marketplace
- Real-time collaborative editing
- Global multi-writer active-active refs

---

### 2. Core thesis (one sentence)

> **Git data plane** (packs + refs) + **Product plane** (ACL, PRs, API), joined by an **event bus**, fronted by an **Edge** (CDN ⊂ Edge; API Gateway for product HTTP).

---

### 3. System context diagram

```
┌──────────┐   HTTPS/SSH/API    ┌─────────────────────────────────────┐
│  Clients │ ─────────────────► │              EDGE                   │
│ git CLI  │                    │  TLS · WAF · LB · rate limits       │
│ IDE/Web  │                    │  CDN (static/public cache)          │
│ CI       │                    │  API Gateway (REST/GraphQL routes)  │
└──────────┘                    └──────────────┬──────────────────────┘
                                               │
                         ┌─────────────────────┼─────────────────────┐
                         │                     │                     │
                         ▼                     ▼                     ▼
              ┌──────────────────┐  ┌──────────────────┐  ┌─────────────────┐
              │  PRODUCT PLANE   │  │  GIT DATA PLANE  │  │  SSH Edge       │
              │  API / Web / PR  │  │  smart HTTP      │  │  → same packs   │
              └────────┬─────────┘  └────────┬─────────┘  └────────┬────────┘
                       │                     │                     │
                       │                     ▼                     │
                       │            ┌─────────────────┐            │
                       │            │ Objects + Refs  │◄───────────┘
                       │            │ (sharded cells) │
                       │            └────────┬────────┘
                       │                     │ RepoEvent (after ACK)
                       ▼                     ▼
              ┌──────────────────┐  ┌─────────────────┐
              │ Metadata DB      │  │ Async consumers │
              │ Cache · Search   │  │ webhooks/search │
              └──────────────────┘  │ PR update/notif │
                                    └─────────────────┘
```

---

### 4. Edge vs CDN vs API Gateway

**Common confusion:** Edge ≠ CDN. CDN is one function of Edge.

```
                    EDGE  =  front door
                    ┌──────────────────────────────────┐
                    │  TLS / WAF / DDoS / LB / geo      │
                    │                                  │
                    │  ┌────────────┐  ┌─────────────┐ │
                    │  │    CDN     │  │ API Gateway │ │
                    │  │ static &   │  │ product HTTP│ │
                    │  │ public     │  │ route/quota │ │
                    │  └────────────┘  └─────────────┘ │
                    │                                  │
                    │  Git HTTP = special route class  │
                    │  (long timeout, no full buffer)  │
                    └──────────────────────────────────┘
```

| Term | Meaning |
|------|---------|
| **CDN** | Cache layer only (static/public content near users) |
| **Edge** | Whole front door: TLS, WAF, LB, routing, limits — **includes** CDN |
| **API Gateway** | Product API routing, validation, per-route quotas (usually runs on Edge) |

**Routing at Edge**
- `/api/*`, GraphQL, Web → Product plane (gateway policies)
- `/{owner}/{repo}.git` → Git data plane (**special route class**: long timeouts, no full body buffer)
- SSH → sibling edge → same pack services

Gateway does **cheap** checks only (token present, throttle, route). Branch protection / merge policy live in AuthZ + Git receive path.

---

### 5. Internal HLD — services

```
                         EDGE / API GW
                              │
        ┌─────────────────────┼──────────────────────┐
        │                     │                      │
        ▼                     ▼                      ▼
┌───────────────┐   ┌─────────────────┐    ┌─────────────────┐
│ Identity/AuthN│   │ AuthZ / Policy  │    │ Repo Service    │
│ users, PAT,   │   │ roles, teams,   │    │ CRUD, settings  │
│ SSH keys, OIDC│   │ branch rules    │    │ → cell mapping  │
└───────┬───────┘   └────────┬────────┘    └────────┬────────┘
        │                    │                      │
        └────────────┬───────┴──────────┬───────────┘
                     ▼                  ▼
            ┌──────────────┐   ┌────────────────┐
            │ PR / Merge   │   │ Issues / Notif │
            │ diff, queue  │   │ labels, inbox  │
            └──────┬───────┘   └───────┬────────┘
                   │                   │
                   │         ┌─────────┴─────────┐
                   │         ▼                   ▼
                   │  ┌─────────────┐   ┌──────────────┐
                   │  │ Webhooks    │   │ Search       │
                   │  └─────────────┘   └──────────────┘
                   │
                   ▼
        ┌──────────────────────┐
        │   GIT FRONTENDS      │
        │ upload-pack          │
        │ receive-pack         │
        │ quarantine + hooks   │
        └──────────┬───────────┘
                   │
         ┌─────────┴──────────┐
         ▼                    ▼
┌─────────────────┐   ┌─────────────────┐
│ Ref Store (CAS) │   │ Object Store    │
│ branches/tags   │   │ packs · SSD/blob│
│ per-repo cell   │   │ content-address │
└─────────────────┘   └─────────────────┘
```

#### Service ownership

| Service | Owns |
|---------|------|
| Identity / AuthN | Users, sessions, OIDC/SAML, PATs, SSH keys |
| AuthZ / Policy | Repo roles, teams, ACL version stamps, branch protection evaluation |
| Repo Service | CRUD, transfer, settings, `repo_id → cell` mapping |
| PR / Merge | PR lifecycle, merge queue; calls Git plane for merge commits |
| Diff / Blame | CPU-heavy; cache by `(commit, path)` |
| Issues | Independent of object store |
| Webhooks / Apps | Signed delivery, retries, idempotency keys |
| Search | Async index from `RepoEvent` |
| Notifications | Inbox / email from events |
| Git Frontends | Smart HTTP/SSH pack protocol, quarantine, pre-receive |
| Ref Store | Linearizable CAS per repo |
| Object Store | Content-addressed packs; SSD hot + blob cold |

**Rule:** Metadata DB holds product rows + SHAs. Object bytes live **only** in the object store.

---

### 6. Logical data model

```
User ──┬── Org ── Team
       │
       └── Repository
              ├── visibility, default branch, settings
              ├── Ref ──────────────► SHA
              ├── Object (blob/tree/commit/tag)  [by SHA]
              ├── PullRequest (base/head SHA, state)
              ├── Issue
              ├── Webhook subscription
              └── Protection rules / required checks
```

- Product IDs: opaque ULID / snowflake
- Git IDs: SHA-1 / SHA-256 (repo policy)

---

### 7. Critical sequence — Push

```
Client          Edge/GW        AuthZ       Git Frontend      Object+Ref       Event Bus
  │               │             │               │                │               │
  │── push ──────►│             │               │                │               │
  │               │── authz ───►│               │                │               │
  │               │◄─ allow ────│               │                │               │
  │               │── receive-pack ────────────►│                │               │
  │               │             │               │─ quarantine ──►│               │
  │               │             │               │─ fsck/quota ───│               │
  │               │             │◄─ policy ─────│                │               │
  │               │             │── ok ────────►│                │               │
  │               │             │               │─ durable write►│               │
  │               │             │               │─ ref CAS ─────►│               │
  │               │             │               │◄─ success ─────│               │
  │◄──── ACK ─────│◄────────────│◄──────────────│                │               │
  │               │             │               │── RepoEvent ──────────────────►│
  │               │             │               │                │               │─ webhooks
  │               │             │               │                │               │─ search
  │               │             │               │                │               │─ PR head
```

**ACK condition:** objects durable **AND** ref CAS success. Everything else is async.

Ref update = CAS:

```text
UPDATE refs/heads/main FROM old_sha TO new_sha IF current == old_sha
```

If two pushes race, one CAS fails and the client retries — never silent last-writer-wins on refs.

---

### 8. Critical sequence — Fetch / Clone

```
Client → Edge → Git Frontend → read refs → negotiate wants/haves
       → build/stream pack (SSD hot pack or generate)
       → optional CDN only for allowed public content
```

Optimize with: protocol v2, pack bitmaps, MIDX, partial clone, geographic pack caches.

---

### 9. PR / Merge flow

```
Open PR:   Product DB row + refs/pull/N/head
Diff:      Diff service(merge-base, trees) + cache(commit, path)
Merge:     Git plane creates merge/squash/rebase commit
           → policy check → CAS onto base branch → close PR
Busy main: Merge Queue serializes merges
```

---

### 10. Consistency model

| Data | Consistency |
|------|-------------|
| Branch / tag refs | Linearizable CAS per repo |
| After own push | Read-your-writes |
| PR / ACL metadata | Strong (SQL) |
| Search / webhooks / notifications | Eventual |
| Multi-region (v1) | Primary per cell + regional affinity |

**Example SLOs**
- Push ACK success ≥ 99.9%
- Metadata API p99 < 100ms
- Webhook lag p99 < 30s (**separate** from Git SLO)

---

### 11. Scaling — cell architecture

```
                    Global Edge
                         │
         ┌───────────────┼───────────────┐
         ▼               ▼               ▼
     ┌───────┐       ┌───────┐       ┌───────┐
     │ Cell A│       │ Cell B│       │ Cell C│
     │ repos │       │ repos │       │ hot   │
     │ 1..N  │       │ ..    │       │ mono  │
     └───────┘       └───────┘       └───────┘
         │
         ├─ Git frontends (stateless)
         ├─ Ref DB/service (strong)
         ├─ Object packs: SSD hot + blob cold
         └─ Emits RepoEvent to shared bus
```

**Scale levers**
1. Shard by `repo_id → cell` (blast radius + capacity)
2. Ref service ≠ object bulk path
3. Tiered object storage (SSD hot / blob cold)
4. Bitmaps / MIDX / partial clone
5. Async fanout after ACK
6. Edge rate limits (stricter on `receive-pack`)
7. Dedicated cells for monorepos

---

### 12. Security

- Tenant isolation at AuthZ **and** storage routing
- Audit: auth, ACL changes, force-push, repo transfers
- Secrets scanning on push (async; optional block)
- Signed webhooks; least-privilege Apps
- Optional signed commits/tags; required status checks
- Abuse detection on clone/push anomalies

AuthZ check on every request:

```text
principal → effective roles (user ∪ teams ∪ org)
         → capability (git:read, git:write, admin, …)
         → branch rules if ref write
```

Cache ACL under `(repo_id, principal_id, acl_version)`; bump `acl_version` on membership change.

---

### 13. Explicit trade-offs

| Decision | Benefit | Cost |
|----------|---------|------|
| Split Git / Product planes | Protect Git correctness & latency | Two planes to operate |
| Async `RepoEvent` after ACK | Fast push | Briefly stale search/hooks |
| Shared Edge + special Git routes | One front door | Ingress complexity |
| Strong SQL for product metadata | Correct PRs/ACLs | Cross-region write cost |
| No multi-writer active-active refs (v1) | Simpler correctness | Regional failover model |

---

### 14. Phased delivery

| Phase | Outcome |
|-------|---------|
| **1 MVP** | Auth, repo CRUD, smart HTTP push/fetch, basic ACL — **prove ACK invariant** |
| **2 Collab** | PR, diff, comments, branch protection |
| **3 Scale** | Cells, bitmaps, event bus, search |
| **4 Enterprise** | SSO, audit, merge queue, admin APIs |
| **5 Ecosystem** | Apps, Checks, LFS |

---

### 15. One-liner summary

A GitHub-like system is a **correctness-first Git data plane** and a **feature-rich product plane**, meeting at an **event bus**, entered through an **Edge that routes and protects traffic** — where CDN caches what it can, and Git writes never depend on cache or webhook success.

---

## Part B — Candidate Interview Script

*Speak as the candidate. Draw while talking.*

### Opening

“I’d like to design a GitHub-like hosted version control system. Before I jump to boxes, I’ll clarify scope, state assumptions, then walk through API → high-level architecture → deep dives on the push path, storage, and scaling. Please interrupt if you want me to go deeper anywhere.”

### Step 1 — Clarify requirements

“Functional requirements I’m assuming:

- Host Git repos: clone, fetch, push over HTTPS and SSH
- Branches/tags, PRs with review and merge (merge/squash/rebase)
- Users, orgs, teams, repo permissions, branch protection
- Web UI + API, webhooks for CI

Non-functional:

- Multi-tenant, internet scale
- Strong consistency on ref updates — no lost pushes
- High durability for Git objects
- Push should stay fast; search/webhooks can lag slightly

Out of scope unless you want them: GitHub Actions runners, package registry, real-time collaborative editing.

Does that match what you want, or should I adjust scale targets?”

*If interviewer is vague:*  
“I’ll assume ~100M repos, hot repos with heavy concurrent reads, and design for horizontal scale with clear bottlenecks called out.”

### Step 2 — Back-of-envelope (brief)

“Quick capacity intuition: Git traffic is dominated by **pack bytes**, not QPS. Metadata QPS is manageable with a SQL cluster and cache; the hard parts are **large object transfer**, **hot repos**, and **correct concurrent writes to refs**. I’ll optimize the architecture around that.”

### Step 3 — Core insight

“My central design choice:

**Split the system into a Git data plane and a product plane.**

- **Git data plane:** smart HTTP/SSH, packs, objects, refs
- **Product plane:** identity, ACLs, PRs, issues, search, webhooks

They connect through an **async event bus** after a successful push.  
That way product features can’t block or corrupt the Git ACK path.

Also — when I say **Edge**, I don’t mean only CDN. CDN is one edge function. Edge is the front door: TLS, WAF, load balancing, rate limits. An **API gateway** for REST/GraphQL can sit there. Git smart-HTTP may share that ingress but needs a **different route class** — long timeouts, no full body buffering. SSH is a sibling edge path.”

### Step 4 — High-level walkthrough

“End-to-end shape: *(draw Part A §3 diagram)*

1. Client hits **Edge**
2. Edge routes:
   - API/Web → Product services
   - `git` HTTP → Git frontends
   - SSH → Git SSH terminator → same pack services
3. **Git plane** talks to **object store + ref store**, sharded by `repo_id` into cells
4. **Product plane** uses a strongly consistent **metadata DB** plus cache
5. After push ACK, Git emits `RepoEvent` → webhooks, search, PR head update, notifications

I would **not** store Git blobs in the relational DB — only SHAs and product metadata.”

### Step 5 — Deep dive: Push

“The write path is the heart of the design: *(draw Part A §7 sequence)*

1. Authenticate and authorize write
2. `receive-pack` accepts the pack into **quarantine**
3. Integrity checks: fsck, size, quota
4. Policy: branch protection, required signatures, CODEOWNERS
5. Persist objects durably
6. **Ref compare-and-swap**: update branch only if `current == expected_old`
7. ACK the client
8. Emit events asynchronously

I’d ACK only after durable objects + successful CAS.  
If two pushes race, one CAS fails and the client retries — we never silently last-writer-wins on refs.”

### Step 6 — Deep dive: Storage & scale

“Objects are content-addressed and packed. Hot packs on SSD, cold on blob storage. Refs are small but must be strongly consistent — I’d keep a dedicated ref service per cell.

Scaling levers: *(draw Part A §11)*

- **Shard by repo → cell** for blast radius and capacity
- Pack bitmaps / protocol v2 / partial clone for large repos
- Read replicas and caches for fetch/clone
- CDN for static assets and, selectively, public content — not for private push
- Stricter rate limits on push than on read

For monorepos, I’d call out dedicated cells and sparse/partial clone rather than pretending one general node shape fits all.”

### Step 7 — PRs & merge

“PRs live in the product DB, with Git refs like `refs/pull/N/head`. Diff/blame are computed in a dedicated service and cached by commit+path.

Merge goes back through the Git plane: create merge/squash/rebase commit, then CAS onto the base branch under protection rules. For busy default branches I’d add a **merge queue** so merges serialize cleanly instead of constant CAS conflicts.”

### Step 8 — AuthZ

“Every request resolves principal → effective roles from user/team/org → capability. Branch rules apply on ref writes. I’d cache ACLs with a version stamp and bump the version on membership changes so we don’t serve stale permissions for long.”

### Step 9 — Consistency & failure

“Consistency model I’d state explicitly:

- Ref updates: linearizable per repo
- Read-your-writes after your own push
- Search and webhooks: eventual
- v1 multi-region: regional affinity with a sync primary per cell — not multi-writer active-active

Failure handling: quarantine rejects bad packs; CAS conflicts are expected; webhook delivery is at-least-once with idempotency keys so retries don’t double-fire side effects.”

### Step 10 — Trade-offs

“Trade-offs I’m consciously making:

| Choice | Why | Cost |
|--------|-----|------|
| Split Git vs product planes | Protect correctness & push latency | More moving parts |
| Async webhooks/search | Fast ACK | Briefly stale product views |
| Strong SQL for metadata | Correct PRs/ACLs | Cross-region write latency |
| Shared edge, special Git routes | One front door | Careful ingress config |
| No active-active ref writers in v1 | Simpler correctness | Failover is regional |

If you want, I can alternatively sketch multi-region active-active — but I’d warn it heavily complicates ref semantics.”

### Step 11 — Close

“To summarize: I’d build a GitHub-like system as a **correctness-first Git data plane** plus a **product plane**, fronted by an **Edge that includes CDN and API gateway behavior**, with **ref CAS** as the source of truth for branches and **async fanout** for everything else.

I’m happy to go deeper on object GC, LFS, search indexing, or the merge queue next — where would you like me to dig in?”

---

## Part C — 45-minute timing cheat sheet

| Time | What to do | What to draw |
|------|------------|--------------|
| 0–3 min | Clarify requirements + scope | Bullet list only |
| 3–5 min | Core thesis + Edge ≠ CDN | Edge box with CDN + API GW inside |
| 5–15 min | System context + service HLD | Part A §3 and §5 |
| 15–25 min | Push sequence deep dive | Part A §7 |
| 25–35 min | Storage, cells, scale | Part A §11 |
| 35–40 min | PR/merge + AuthZ + consistency | Short flows |
| 40–45 min | Trade-offs + close + ask where to go deeper | Trade-off table |

**Habits**
- Draw while talking; narrate each box as you add it
- Prefer **one deep correct path** (push + refs) over shallow coverage of every feature
- If stuck: “I’ll state an assumption and proceed — correct me if wrong”
- Park tangents: “Does that change the Git/product split or the ACK-before-fanout rule?”
