# LLD: Social media feed (post + timeline)

## Interview-ready snapshot

**Say first (≈30s):** `Post` aggregate + `Follow` graph (adjacency-ish, `userId → Set<followedUserId>`); feed generation is a pluggable **strategy**: pull/fan-out-on-read (merge followees' posts at request time) vs push/fan-out-on-write (precompute each follower's feed on post creation). Start with pull for the interview; discuss push as the celebrity-user follow-up.

**Default assumptions:** Single node, in-memory repositories; text posts with timestamp (media/likes/comments are stated as out-of-scope unless probed); feed is reverse-chronological unless ranking is explicitly asked.

| Phase | ~Time | Deliver |
|-------|------|---------|
| Align | 5 min | Chronological vs ranked feed; pull vs push fan-out; celebrity/hot-user problem in scope? |
| Model | 8 min | `Post`, `FollowGraph`, `FeedGenerator` strategy, `TimelineService`. |
| API + flow | 7 min | `createPost`, `follow/unfollow`, `getFeed(userId, page)`; walkthrough of pull-based merge. |
| Hard | 12 min | Celebrity fan-out cost, pagination over a merged stream, feed staleness/consistency, dedup on unfollow-then-refollow. |
| Close | 3 min | Hybrid fan-out (push for most, pull for celebrities), caching, ranking model as HLD follow-ons. |

**Whiteboard order:** (1) `Post` + `FollowGraph` (2) two fan-out strategies on the board side-by-side (3) `getFeed()` merge-and-paginate sequence (4) celebrity problem called out explicitly (5) pagination cursor design.

**Likely probes:** What happens when a celebrity with 10M followers posts? How do you paginate a feed that's being actively merged from many sources? How do you avoid re-scanning all of a user's followees on every page request?

**30s closer:** Fan-out strategy is isolated behind one interface so pull/push/hybrid are swappable per user tier; pagination uses a stable cursor (timestamp+postId) over the merged stream rather than offset, so it's correct even as new posts arrive mid-scroll.

---

## Interview prompt (typical)

Design the core of a **social media feed**: users can create posts, follow/unfollow other users, and fetch their home timeline — a paginated, reverse-chronological (or ranked) stream of posts from people they follow.

## Clarifying questions (ask first)

- **Ranking**: strictly reverse-chronological, or is there a ranking/scoring signal (engagement, recency decay)? (Chronological first; ranking is a pluggable follow-up.)
- **Fan-out model**: compute feed at read time (pull) or precompute per-follower on write (push)? Or hybrid based on follower count?
- **Celebrity problem**: explicitly ask if very-high-follower-count accounts are in scope — this changes the fan-out strategy.
- **Pagination**: cursor-based or offset-based? (Prefer cursor for a live, growing stream.)
- **Consistency**: is slight staleness (a few seconds) acceptable for the feed, given eventual consistency in push-based fan-out?

## Functional requirements

- `createPost(userId, content)` → new `Post`.
- `follow(userId, targetId)` / `unfollow(userId, targetId)`.
- `getFeed(userId, cursor, pageSize)` → paginated posts from followees, newest first.

## Non-functional requirements

- **Read-heavy**: feed reads vastly outnumber post writes for most users — optimize the read path.
- **Celebrity resilience**: a single post from a high-follower-count user must not cause an O(followers) synchronous write-time cost that blocks their `createPost()` call.
- **Pagination correctness**: no duplicate or skipped posts across pages even as new posts are created concurrently.

## Domain model

| Kind | Type | Responsibility |
|------|------|----------------|
| **Aggregate root** | `Post` | Identity = `postId`; holds `authorId`, `content`, `createdAt`. |
| **Aggregate root** | `FollowGraph` | `userId → Set<followedUserId>` and reverse index `userId → Set<followerId>`; owns follow/unfollow invariants. |
| **Value object** | `FeedCursor` | Opaque pagination token, e.g. `(timestamp, postId)` pair for stable ordering. |
| **Strategy** | `FeedGenerator` | `getFeed(userId, cursor, pageSize): Page<Post>` — pull (merge on read) vs push (precomputed timeline) implementation. |
| **Repository (port)** | `PostRepository`, `FollowRepository`, `TimelineRepository` (push-model only, precomputed per-user feed store) | Storage abstraction per aggregate. |

**Relationships:** `FollowGraph` links users to users (many-to-many); `FeedGenerator` reads `FollowGraph` + `PostRepository` (pull) or reads a precomputed `TimelineRepository` (push); `TimelineService` is the single façade callers use.

**Not modeled:** likes/comments, media storage, notification fan-out (separate LLD — see notification dispatcher).

## Core invariants

- A user never sees a post from someone they don't currently follow (unfollow must be reflected in the next feed read, even under a push model — requires either eager removal or a filter step).
- Pagination cursors are **stable**: re-requesting the same cursor never returns a different result, and new posts created after a page was fetched don't shift already-returned items.
- No duplicate posts within a single paginated feed traversal.

## Design patterns (where they matter)

| Pattern | Role |
|--------|------|
| **Strategy** | `FeedGenerator` — pull vs push vs hybrid, selected per user (e.g. by follower-count threshold) without changing `TimelineService`. |
| **Observer** (push model) | On `createPost`, notify a fan-out worker that pushes the new post into each follower's precomputed timeline — decouples post creation from timeline updates. |
| **Facade** | `TimelineService` hides whether a given user's feed is pull-computed or push-precomputed behind one `getFeed()` call. |

## Java shape (interfaces)

```java
public record FeedCursor(Instant afterTimestamp, String afterPostId) {}
public record Page<T>(List<T> items, Optional<FeedCursor> nextCursor) {}

public interface FeedGenerator {
    Page<Post> getFeed(String userId, Optional<FeedCursor> cursor, int pageSize);
}

// Pull: merges each followee's recent posts (e.g. k-way merge of sorted per-author streams).
public class PullFeedGenerator implements FeedGenerator { ... }

// Push: reads a precomputed, already-sorted timeline for the user.
public class PushFeedGenerator implements FeedGenerator { ... }

public class TimelineService {
    public Post createPost(String userId, String content);
    public void follow(String userId, String targetId);
    public Page<Post> getFeed(String userId, Optional<FeedCursor> cursor, int pageSize);
}
```

## Concurrency model (staff answer)

- **Pull model**: `getFeed` does a **k-way merge** across each followee's posts (sorted by time, e.g. via an index per author) — no shared mutable state to coordinate, but cost scales with followee count per request.
- **Push model**: `createPost` publishes an event; a fan-out worker (or async task) appends the post id to each follower's `TimelineRepository` entry — writes are async and idempotent (safe to retry/dedupe by postId).
- **Celebrity mitigation (hybrid)**: users above a follower-count threshold are excluded from push fan-out; their followers' feeds pull directly from the celebrity's post stream and merge it in at read time — bounds worst-case write cost to O(non-celebrity followers).
- **Cursor stability**: `(timestamp, postId)` composite cursor avoids "phantom shifts" from offset-based pagination when new posts are inserted ahead of the current page.

## Failure modes

- **Celebrity post fan-out storm**: naive push to millions of followers synchronously — mitigate with hybrid strategy or async, batched, rate-limited fan-out workers.
- **Unfollow race**: user unfollows mid-scroll — define whether already-fetched pages can still show the old followee's posts (acceptable staleness) or must be filtered live.
- **Duplicate posts across pages**: offset-based pagination + concurrent inserts — solved by cursor-based pagination keyed on immutable `(timestamp, postId)`.
- **Clock skew across authors**: two posts with the same timestamp — break ties deterministically with `postId` in the cursor/sort key.

## Testing strategy

- Follow graph correctness: follow/unfollow reflected in subsequent `getFeed` calls.
- Pull-model merge: interleave posts from multiple followees, assert strict reverse-chronological order.
- Pagination: fetch all pages via cursor, assert no duplicates/gaps even when new posts are inserted between page fetches.
- Celebrity path: assert `createPost` from a high-follower-count user returns quickly (no synchronous O(followers) work) if hybrid fan-out is implemented.
- Push-model fan-out idempotency: replay the same fan-out event twice, assert no duplicate timeline entries.

## Follow-ups

- **Ranking**: replace strict chronological order with a scoring function (recency decay + engagement) — same `FeedGenerator` interface, different implementation.
- **Caching**: cache each user's most recent feed page (especially for pull-model) to avoid re-merging on every request.
- **Media/likes/comments**: extend `Post` aggregate and add read-time enrichment (batch-fetch like counts) rather than embedding them in the feed merge.
- **Notification fan-out** on new posts — see the pub-sub/notification dispatcher LLDs in this same folder for the pattern.
