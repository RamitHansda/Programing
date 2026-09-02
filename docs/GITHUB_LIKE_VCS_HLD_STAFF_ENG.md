# GitHub-like Version Control System — HLD + LLD + Interview Script

**Level:** Staff / Principal  
**Format:** Whiteboard-ready HLD + domain LLD (object relationships) + candidate spoken script  
**Scope:** Hosted Git + collaboration (repos, PRs, authz, webhooks)  
**Out of scope (v1):** Actions runner fleet, package registry, multi-writer active-active refs

| Part | Contents |
|------|----------|
| **A** | High-level design (services, sequences, scale) |
| **B** | Low-level design (aggregates, entities, relationships, invariants) |
| **C** | Candidate interview script |
| **D** | 45-minute timing cheat sheet |

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

### 6. Logical data model (HLD sketch)

Whiteboard sketch only — full aggregates, fields, multiplicities, and invariants are in **Part B**.

```
User ──┬── Org ── Team
       │
       └── Repository
              ├── visibility, default branch, settings
              ├── Ref ──────────────► ObjectId (SHA)
              ├── GitObject (blob/tree/commit/tag)  [by SHA]
              ├── PullRequest (base/head SHA, state)
              ├── Issue
              ├── WebhookSubscription
              └── BranchProtectionRule / required checks
```

| ID space | Form | Owned by |
|----------|------|----------|
| Product IDs | Opaque ULID / snowflake | Metadata DB |
| Git IDs | SHA-1 / SHA-256 (repo policy) | Object store (bytes) + refs (names → SHA) |

**Plane split reminder:** product rows store **SHAs and foreign keys**, never pack bytes. Git objects are content-addressed and shared by SHA within a repo (and optionally across forks via alternates — v2).

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

Object-level view (Part B): product aggregates **reference** Git via `RefName` + `ObjectId`; refs are mutable CAS pointers; Git objects are immutable content-addressed bytes.

---

## Part B — Low-Level Design (domain model & object relationships)

*Use this when the interviewer asks “what are the objects?” or “how do PR, ref, and commit relate?”*  
*Goal: clarity on **who owns what**, **1:1 / 1:N / N:M**, and **which aggregate is the consistency boundary** — not full Java for every service.*

### B.1 Three models (say this first)

| Layer | What you draw | Example |
|-------|---------------|---------|
| **Conceptual** | Business nouns | User, Org, Repo, Branch, PR, Commit |
| **Domain** | Aggregates / entities / VOs + invariants | `Repository`, `Ref`, `PullRequest`, `ObjectId` |
| **Physical** (if asked) | Tables / packs / keys | `repos`, `refs`, pack files, blob store |

v1 interview default: conceptual + domain. Persistence is one paragraph unless they push DB schema.

### B.2 Plane ownership (objects live in one place)

```
┌──────────────────────── PRODUCT PLANE (Metadata DB) ────────────────────────┐
│  User · Org · Team · Membership · Credential                                 │
│  Repository · RepoSettings · BranchProtectionRule · CollaboratorGrant        │
│  PullRequest · Review · CheckRun · Issue · WebhookSubscription · AppInstall  │
│  (columns hold ObjectId / RefName as *references*, not bytes)                │
└──────────────────────────────────────────────────────────────────────────────┘
                    │ repo_id → cell                │ SHA / ref name
                    ▼                               ▼
┌──────────────────────── GIT DATA PLANE ─────────────────────────────────────┐
│  RefStore:  RefName ──CAS──► ObjectId                                        │
│  ObjectStore: ObjectId ──► GitObject bytes (blob | tree | commit | tag)      │
│  PackIndex / Bitmap / MIDX (derived, rebuildable)                            │
└──────────────────────────────────────────────────────────────────────────────┘
```

**Rule:** if two writers can race on the same fact, that fact has exactly one owner aggregate (usually `Ref` CAS or `PullRequest` row with optimistic version).

---

### B.3 Aggregates, entities, value objects

| Kind | Type | Plane | Responsibility / mutable state |
|------|------|-------|--------------------------------|
| Aggregate root | `Organization` | Product | Name, plan, settings; owns teams & org-level roles |
| Aggregate root | `User` | Product | Profile; owns PATs, SSH keys, sessions |
| Entity | `Team` | Product | Belongs to Org; membership list |
| Entity | `Membership` | Product | `(principal, scope, role)` — user↔org/team/repo |
| Entity | `Credential` | Product | PAT / SSH public key / OIDC subject binding |
| Aggregate root | `Repository` | Product | Visibility, default branch, `cell_id`, settings; **does not** embed objects/refs |
| Entity | `BranchProtectionRule` | Product | Pattern, required checks, enforce admins, allow force-push? |
| Entity | `CollaboratorGrant` | Product | Direct user/team permission on repo |
| Aggregate root | `PullRequest` | Product | Number, base/head refs+SHAs, state, review decision |
| Entity | `Review` | Product | Reviewer, state (`APPROVED`/`CHANGES_REQUESTED`/`COMMENTED`) |
| Entity | `CheckRun` / `StatusCheck` | Product | Context name, SHA, state (`PENDING`/`SUCCESS`/`FAILURE`) |
| Aggregate root | `Issue` | Product | Independent of Git bytes; may link commits via SHA |
| Aggregate root | `WebhookSubscription` | Product | URL, secret, event filters, delivery cursor |
| Aggregate root | `Ref` (per name) | Git | `refs/heads/*`, `refs/tags/*`, `refs/pull/N/head` → `ObjectId` |
| Entity (content-addressed) | `GitObject` | Git | Immutable blob/tree/commit/tag bytes keyed by SHA |
| Value object | `ObjectId` | Both | SHA-1/256 hex; equality by value |
| Value object | `RefName` | Both | Canonical ref path string |
| Value object | `RepoId`, `UserId`, `OrgId`, `PullRequestId` | Product | Opaque product IDs |
| Value object | `AclVersion` | Product | Monotonic stamp for permission cache invalidation |
| Value object | `Capability` | Product | `git:read`, `git:write`, `admin`, `merge`, … |
| Domain service | `AuthZEvaluator` | Product | principal ∪ teams ∪ grants → capabilities + branch rules |
| Domain service | `MergeEngine` | Product→Git | Builds merge/squash/rebase commit; asks Ref CAS |
| Domain service | `ReceivePackPipeline` | Git | Quarantine → fsck → policy → durable write → ref CAS |
| Domain event | `RepoEvent` | Bus | After ACK: push, ref update, PR opened/merged, … |

**Intentionally not modeled as domain entities:** HTTP handlers, pack bitmaps (derived), CDN cache entries, notification templates.

---

### B.4 Relationship diagram (multiplicity)

```
Org 1 ───────────── * Team
Org 1 ───────────── * Membership (role: OWNER|MEMBER|BILLING)
User * ──────────── * Org          (via Membership)
User * ──────────── * Team         (via TeamMembership)
User 1 ──────────── * Credential

Org 1 ───────────── * Repository   (owner may also be User for personal repos)
User|Org 1 ──────── 1 Repository.owner

Repository 1 ────── * CollaboratorGrant ──► User|Team + Role
Repository 1 ────── * BranchProtectionRule
Repository 1 ────── * Issue
Repository 1 ────── * WebhookSubscription
Repository 1 ────── * PullRequest
Repository 1 ────── * Ref                 (Git plane; keyed by RefName)
Repository 1 ────── * GitObject           (Git plane; keyed by ObjectId)

PullRequest * ───── 1 Repository
PullRequest 1 ───── * Review
PullRequest 1 ───── * CheckRun            (keyed by (sha, context); often global per SHA)
PullRequest ──────► RefName base + head   (e.g. refs/heads/main, refs/heads/feature)
PullRequest ──────► ObjectId base_sha, head_sha, merge_base_sha?

Ref 1 ────────────► ObjectId              (points at commit or annotated tag)
Commit ───────────► Tree ObjectId
Commit * ───────── * parent Commit        (DAG; merge has 2+ parents)
Tree ───────────── * (mode, name, ObjectId)  → blob|tree
Tag ──────────────► ObjectId              (annotated tag object)

Fork (optional v1.5): Repository.fork_of ──► RepositoryId
  (object alternates: child may read parent packs; refs stay per-repo)
```

#### Cardinality cheat sheet (say out loud)

| From → To | Cardinality | Notes |
|-----------|-------------|-------|
| Repository → Ref | 1 : N | Names unique per repo; CAS unit = one ref |
| Repository → GitObject | 1 : N | Content-addressed; many refs can share one SHA |
| Ref → ObjectId | N : 1 | Many branch tips can point at same commit |
| PullRequest → Repository | N : 1 | PR number unique **within** repo |
| PullRequest → Reviews | 1 : N | Latest review per user often derived |
| User ↔ Team | N : M | Via membership join entity |
| CheckRun → ObjectId | N : 1 | Checks attach to a commit SHA, not to a branch name |

---

### B.5 Class / aggregate sketch (interview board)

```
┌──────────────────┐       ┌───────────────────┐
│ Organization     │◇──────│ Team              │
│ + id, name       │       │ + id, name        │
└────────┬─────────┘       │ + memberIds[]     │
         │                 └───────────────────┘
         │ owns
         ▼
┌──────────────────┐       ┌────────────────────────────┐
│ Repository       │◇──────│ BranchProtectionRule       │
│ + id, ownerId    │       │ + pattern (refs/heads/…)   │
│ + visibility     │       │ + requiredCheckContexts[]  │
│ + defaultBranch  │       │ + requireReviews, …        │
│ + cellId         │       └────────────────────────────┘
│ + aclVersion     │
└────────┬─────────┘
         │ references (by id / SHA only)
         │
    ┌────┴──────────────────────────────┐
    │                                   │
    ▼                                   ▼
┌──────────────────┐          ┌──────────────────┐
│ PullRequest      │          │ Ref (Git plane)  │
│ + number         │          │ + name: RefName  │
│ + state          │          │ + target: ObjectId│
│ + baseRef, head  │          │ + cas(old→new)   │
│ + baseSha,headSha│          └────────┬─────────┘
│ + authorId       │                   │ points to
└────────┬─────────┘                   ▼
         │◇ reviews           ┌──────────────────┐
         ▼                    │ GitObject        │
┌──────────────────┐          │ + id: ObjectId   │
│ Review           │          │ + type           │
│ + reviewerId     │          │ + payload bytes  │
│ + state          │          └──────────────────┘
└──────────────────┘                    ▲
                                        │ Commit.tree / parents
                              ┌─────────┴─────────┐
                              │ CommitPayload     │
                              │ tree, parents[]   │
                              │ author, message   │
                              └───────────────────┘
```

Composition (`◇` / filled diamond): child lifecycle tied to parent (teams under org, protection rules under repo, reviews under PR).  
Association / reference: PR **points at** SHAs and ref names; deleting a PR does **not** delete Git objects.

---

### B.6 Key fields (enough to reason about flows)

```text
Repository {
  id: RepoId
  owner: PrincipalId          // UserId | OrgId
  name: String                // unique within owner
  visibility: PUBLIC|PRIVATE|INTERNAL
  default_branch: RefName     // usually refs/heads/main
  cell_id: CellId
  acl_version: AclVersion
  settings: { allow_ff_only?, delete_branch_on_merge?, … }
}

Ref {
  repo_id: RepoId
  name: RefName               // refs/heads/main | refs/tags/v1 | refs/pull/42/head
  object_id: ObjectId
  // update = CAS(expected_old, new)
}

PullRequest {
  repo_id: RepoId
  number: Int                 // monotonic per repo
  state: OPEN|CLOSED|MERGED
  author_id: UserId
  title, body
  base_ref: RefName           // target branch
  head_ref: RefName           // source branch (or fork ref)
  base_sha: ObjectId          // snapshot at last sync / open
  head_sha: ObjectId
  merge_sha?: ObjectId        // set on success
  head_repo_id?: RepoId       // cross-fork PRs
  version: Long               // optimistic lock for product updates
}

BranchProtectionRule {
  repo_id: RepoId
  pattern: String             // "main" or "release/*"
  required_approving_reviews: Int
  required_check_contexts: [String]
  dismiss_stale_reviews: Bool
  require_linear_history: Bool
  allow_force_push: Bool
  allow_deletions: Bool
}

RepoEvent {
  event_id: IdempotencyKey
  repo_id: RepoId
  type: PUSH|REF_UPDATE|PR_OPENED|PR_MERGED|…
  actor_id: UserId
  ref?: RefName
  before?: ObjectId
  after?: ObjectId
  occurred_at
}
```

---

### B.7 Lifecycles

```
PullRequest:   OPEN ──► MERGED
                 └────► CLOSED
                 CLOSED ──► OPEN   (reopen; rare)

Review:        COMMENTED | APPROVED | CHANGES_REQUESTED
               (latest per reviewer wins for merge gate)

CheckRun:      PENDING ──► SUCCESS | FAILURE | CANCELLED
               (keyed by commit SHA + context; new push → new SHA → new checks)

Ref:           created → updated (CAS) → deleted
               force-push = CAS that is non-fast-forward (policy may forbid)

Repository:    ACTIVE ──► ARCHIVED ──► DELETED (soft)
```

---

### B.8 Invariants (tie behavior to types)

1. **Ref CAS:** `update(ref, old, new)` succeeds iff `current(ref) == old`; never last-writer-wins.
2. **ACK:** client push ACK only after objects durable **and** all advertised ref CAS succeed (or none — atomic per receive-pack batch policy).
3. **Reachability:** a successful ref update’s `new` ObjectId must exist in the object store (and pass fsck/quarantine).
4. **PR head sync:** while `PR.state == OPEN`, `refs/pull/N/head` tracks head tip; product `head_sha` updated from `RepoEvent` (may lag briefly if async — prefer sync update in PR service consumer with idempotency).
5. **Merge gate:** merge allowed only if AuthZ says `merge`, protection rules satisfied (reviews + CheckRuns on **head_sha**), and base ref CAS succeeds.
6. **ACL freshness:** cached AuthZ entries keyed by `(repo_id, principal_id, acl_version)`; membership/grant changes bump `Repository.acl_version`.
7. **Visibility:** private repo GitObject / Ref reads require `git:read`; public may skip auth but still rate-limit.
8. **No product bytes in Git store; no pack bytes in SQL.**

---

### B.9 Cross-object maps for critical flows

#### Push (which objects move)

```
Credential ──AuthN──► User
User + Repository + BranchProtectionRule ──AuthZ──► Capability
Pack bytes ──quarantine──► GitObject(s)   [new SHAs]
receive-pack advertised tips ──CAS──► Ref.object_id
success ──emit──► RepoEvent(PUSH, before, after)
RepoEvent ──► WebhookSubscription deliveries
           ──► Search index
           ──► open PullRequest.head_sha (if head matches)
           ──► invalidate CheckRun expectations for new SHA
```

#### Open PR

```
Repository + head Ref + base Ref
  ──create──► PullRequest(OPEN, base_sha, head_sha)
  ──create/update──► Ref(refs/pull/N/head → head_sha)
  ──emit──► RepoEvent(PR_OPENED)
DiffService(merge_base(base_sha, head_sha), trees)  // cached by (commit, path)
```

#### Merge PR

```
PullRequest(OPEN) + Reviews + CheckRuns(head_sha)
  ──policy──► ok
MergeEngine ──writes──► GitObject(merge|squash|rebase commit)
           ──CAS──► Ref(base_ref: base_sha → merge_sha)
           ──update──► PullRequest(MERGED, merge_sha)
           ──optional──► delete head Ref
           ──emit──► RepoEvent(PR_MERGED)
Busy main: MergeQueue serializes “next CAS onto default branch”
```

#### AuthZ resolution (object graph)

```
Principal
  ├─ direct CollaboratorGrant on Repository
  ├─ TeamMembership → Team → team grants on Repository
  └─ Org Membership → org-default / owner capabilities
       ──► effective Role ──► Capability set
Branch write? also match BranchProtectionRule against RefName
```

---

### B.10 Persistence mapping (only if asked)

| Domain | Physical |
|--------|----------|
| `User`, `Org`, `Team`, `Membership`, `Repository`, `PR`, `Review`, `Issue`, `WebhookSubscription`, `BranchProtectionRule` | Strongly consistent SQL (sharded by org or cell) |
| `Ref` | Ref service / DB with per-repo linearizable CAS (etcd/Spanner/SQL row lock — pick one and defend) |
| `GitObject` | Packfiles on SSD + content-addressed blob (S3); index by SHA |
| `RepoEvent` | Log/bus (Kafka); consumers idempotent on `event_id` |
| AuthZ cache | Redis/local: key `(repo_id, principal_id, acl_version)` |

Indexes that matter: `(owner_id, repo_name)`, `(repo_id, pr_number)`, `(repo_id, ref_name)`, object SHA primary key, `(sha, check_context)`.

---

### B.11 Patterns (only where they clarify variation)

| Pattern | Where |
|---------|--------|
| **CAS / optimistic concurrency** | `Ref` updates; `PullRequest.version` for metadata edits |
| **Content-addressed immutability** | `GitObject` — create new SHA, never mutate |
| **Strategy** | Merge methods: merge commit / squash / rebase |
| **Pipeline** | `ReceivePackPipeline` stages (quarantine → policy → durable → CAS → event) |
| **Outbox / event** | Persist `RepoEvent` with ACK path or immediate emit after commit (at-least-once + idempotent consumers) |
| **Cache aside + version stamp** | AuthZ (`AclVersion`) |

Avoid god `GitHubService`; keep AuthZ, Repo, PR, ReceivePack as separate application services over these aggregates.

---

### B.12 LLD one-liner

> Product aggregates (`Repository`, `PullRequest`, AuthZ grants) **reference** Git by `RefName` + `ObjectId`; the Git plane owns **immutable objects** and **linearizable refs**; relationships are mostly **1:N from Repository**, with PRs/reviews in SQL and commits as a **DAG of content-addressed objects** — never mixed into one mega-row.

---

## Part C — Candidate Interview Script

*Speak as the candidate. Draw while talking.*

### Opening

“I’d like to design a GitHub-like hosted version control system. Before I jump to boxes, I’ll clarify scope, state assumptions, then walk through API → high-level architecture → **domain objects and relationships** if useful → deep dives on the push path, storage, and scaling. Please interrupt if you want me to go deeper anywhere.”

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

### Step 4b — Domain model / object relationships (when asked, or ~2–3 min)

“Quick object model so relationships are clear: *(draw Part B §B.4 / §B.5)*

**Product plane:** `Org` has many `Team`s and `Repository`s. `User` links to orgs/teams via membership. `Repository` owns settings, collaborator grants, branch protection rules, issues, webhook subscriptions, and PRs.

**Git plane:** each `Repository` has many `Ref`s (name → SHA) and many immutable `GitObject`s. A `Commit` points at a `Tree` and parent commits — that’s the DAG. Refs are the **mutable** pointers; objects are **content-addressed and never updated**.

**PR bridge:** a `PullRequest` is a product row that **references** `base_ref` / `head_ref` and stores `base_sha` / `head_sha`. We also maintain `refs/pull/N/head`. Reviews and check runs hang off the PR / commit SHA — they don’t live inside the pack store.

**Consistency boundaries:** ref CAS is the Git write boundary; PR row (+ version) is the product write boundary. Merge creates a new commit object, then CAS’s the base branch, then marks the PR merged.”

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

“To summarize: I’d build a GitHub-like system as a **correctness-first Git data plane** plus a **product plane**, fronted by an **Edge that includes CDN and API gateway behavior**, with **ref CAS** as the source of truth for branches and **async fanout** for everything else. Product objects reference Git through **RefName + ObjectId**; packs never sit in SQL.

I’m happy to go deeper on object GC, LFS, search indexing, or the merge queue next — where would you like me to dig in?”

---

## Part D — 45-minute timing cheat sheet

| Time | What to do | What to draw |
|------|------------|--------------|
| 0–3 min | Clarify requirements + scope | Bullet list only |
| 3–5 min | Core thesis + Edge ≠ CDN | Edge box with CDN + API GW inside |
| 5–12 min | System context + service HLD | Part A §3 and §5 |
| 12–18 min | **Domain objects + relationships** (if interviewer cares / LLD lean) | Part B §B.4–B.5; skip if pure HLD |
| 18–28 min | Push sequence deep dive | Part A §7 (+ Part B §B.9 push map) |
| 28–36 min | Storage, cells, scale | Part A §11 |
| 36–42 min | PR/merge + AuthZ + consistency | Part A §9 + Part B merge map |
| 42–45 min | Trade-offs + close + ask where to go deeper | Trade-off table |

*If the round is pure HLD:* spend the 12–18 min slot on push instead, and only name aggregates verbally.  
*If the round is LLD-leaning:* shrink cells/scale and spend more time on Part B invariants + class sketch.

**Habits**
- Draw while talking; narrate each box as you add it
- Prefer **one deep correct path** (push + refs) over shallow coverage of every feature
- When drawing objects: say **cardinality** and **which plane owns the row/bytes**
- If stuck: “I’ll state an assumption and proceed — correct me if wrong”
- Park tangents: “Does that change the Git/product split or the ACK-before-fanout rule?”
