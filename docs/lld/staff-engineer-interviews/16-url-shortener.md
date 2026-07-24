# LLD: URL shortener

## Interview-ready snapshot

**Say first (≈30s):** `ShortUrl` aggregate (long URL, code, owner, expiry, click count); pluggable **code generation strategy** (base62 counter vs random + collision retry vs hash-based); repository interface abstracts storage so the same domain logic works in-memory or backed by a real DB; redirect path is a hot, read-heavy lookup.

**Default assumptions:** Single-node in-memory store for the interview (map-backed `Repository`); codes are 6-8 chars base62; no auth unless probed; redirect is a 301/302 decision to mention but not implement.

| Phase | ~Time | Deliver |
|-------|------|---------|
| Align | 5 min | Custom alias support? Expiry? Analytics (click count) in scope? Distributed ID generation needed, or single-node OK? |
| Model | 8 min | `ShortUrl`, `UrlRepository`, `CodeGenerator` strategy. |
| API + flow | 7 min | `shorten(longUrl, opts)`, `resolve(code)`; walkthrough of collision handling. |
| Hard | 12 min | Collision avoidance at scale, custom-alias race, expiry cleanup, idempotent re-shortening of the same URL. |
| Close | 3 min | Sharding by code prefix, cache-aside for hot redirects, distributed ID generation (Snowflake/Zookeeper) as HLD follow-on. |

**Whiteboard order:** (1) `ShortUrl` fields (2) `CodeGenerator` strategy interface (3) `shorten()` sequence incl. collision retry (4) `resolve()` read path (5) expiry/analytics as optional extensions.

**Likely probes:** How do you guarantee code uniqueness under concurrent writes? Counter-based vs random codes — tradeoffs? What if two callers shorten the same long URL — same code or different?

**30s closer:** Code generation is a swappable strategy behind one interface; the repository owns uniqueness (atomic check-and-insert), so the domain logic doesn't need distributed locks for a single-node design — and the same seam lets you swap in a real datastore or a Snowflake-style ID service without touching callers.

---

## Interview prompt (typical)

Design a **URL shortener** (like bit.ly / TinyURL). Given a long URL, generate a short code; given a short code, resolve back to the original long URL and redirect. Support optional custom aliases and expiry.

## Clarifying questions (ask first)

- **Code generation**: sequential/counter-based (predictable, needs distributed counter at scale) vs random (needs collision check) vs hash-of-URL (needs collision handling too, but idempotent for the same input)?
- **Custom alias**: can users request `short.ly/my-brand`? Must be unique — race between two users requesting the same alias?
- **Expiry / TTL**: do short URLs expire? Who cleans up expired entries?
- **Analytics**: track click count / last-accessed? Synchronous (blocks redirect) or async (fire-and-forget)?
- **Scale target** (affects HLD framing, not the LLD interfaces): single node for this exercise, but mention what changes at scale (distributed unique ID generation, cache layer).

## Functional requirements

- `shorten(longUrl, options)` → returns a `ShortUrl` (code + short URL), where `options` may include a requested custom alias and/or expiry.
- `resolve(code)` → returns the long URL (or "not found"/"expired").
- Optional: `getStats(code)` → click count, created-at, expires-at.

## Non-functional requirements

- **Read-heavy**: `resolve()` must be fast (this is the hot path — every redirect).
- **Uniqueness**: no two active short URLs share the same code.
- **Idempotency (optional)**: re-shortening the same long URL by the same owner can return the existing code instead of minting a new one — clarify with interviewer, it changes the design.

## Domain model

| Kind | Type | Responsibility |
|------|------|----------------|
| **Aggregate root** | `ShortUrl` | Identity = `code`; holds `longUrl`, `ownerId`, `createdAt`, `expiresAt`, `clickCount`. |
| **Value object** | `ShortenOptions` | Optional custom alias, TTL/expiry, owner id. |
| **Strategy** | `CodeGenerator` | `generate(longUrl): String` — counter/base62, random+retry, or hash-based. |
| **Repository (port)** | `UrlRepository` | `save(ShortUrl)` (atomic if code exists → fail), `findByCode(code)`, `findByLongUrl(longUrl, ownerId)` (for idempotency). |
| **Application service** | `UrlShortenerService` | Orchestrates: validate → generate/retry on collision → persist → return. |

**Relationships:** one `ShortUrl` per code; `UrlShortenerService` depends on `CodeGenerator` (strategy) and `UrlRepository` (port) — never talks to storage directly.

**Not modeled:** HTTP redirect handling, CDN/edge caching, rate limiting per user (mention as HLD extensions).

## Core invariants

- `code` is globally unique among **active** (non-expired) short URLs.
- `longUrl` must be validated (well-formed URI) before persisting.
- A custom alias request either succeeds atomically or fails with a clear "alias taken" error — never silently overwrites an existing mapping.

## Design patterns (where they matter)

| Pattern | Role |
|--------|------|
| **Strategy** | `CodeGenerator` — swap counter-based / random / hash-based generation without touching the service. |
| **Repository** | `UrlRepository` port isolates domain from storage (in-memory map for the interview; SQL/NoSQL in production) — enables testing with a fake. |
| **Factory method** (optional) | `ShortUrl.create(...)` centralizes validation + defaulting (e.g. default TTL) at construction. |

## Java shape (interfaces)

```java
public interface CodeGenerator {
    String generate(String longUrl);
}

public record ShortenOptions(Optional<String> customAlias, Optional<Duration> ttl, String ownerId) {}

public interface UrlRepository {
    /** Returns false if code already exists (atomic check-and-insert). */
    boolean saveIfAbsent(ShortUrl entry);
    Optional<ShortUrl> findByCode(String code);
    Optional<ShortUrl> findByLongUrlAndOwner(String longUrl, String ownerId);
}

public class UrlShortenerService {
    public ShortUrl shorten(String longUrl, ShortenOptions options) { ... }
    public Optional<String> resolve(String code) { ... } // increments click count as a side effect if in scope
}
```

## Concurrency model (staff answer)

- **Uniqueness enforcement** happens at the repository boundary via an atomic `saveIfAbsent` (e.g. `ConcurrentHashMap.putIfAbsent` for in-memory, or a unique index + `INSERT ... ON CONFLICT` for a real DB) — never "check-then-insert" as two separate steps in the service layer (race).
- **Collision retry loop**: for random/hash-based generators, `shorten()` retries with a new code (bounded attempts, e.g. 5) if `saveIfAbsent` returns false; counter-based generators avoid this by construction (single atomic increment source), at the cost of predictable codes.
- **Custom alias race**: two callers requesting the same alias simultaneously — only one `saveIfAbsent` wins; the other gets a clear rejection, not a corrupted entry.

## Failure modes

- **Collision storm** under random generation at high write volume — bound retries and fall back to a longer code length or counter-based generation; state the tradeoff explicitly.
- **Hot key**: one viral short URL gets massive `resolve()` traffic — mention read-through cache (LRU) in front of the repository as the first HLD lever.
- **Expired code accessed**: `resolve()` must distinguish "never existed" vs "expired" for a good error message, without leaking whether an alias was ever taken (minor security nuance, worth a one-liner).

## Testing strategy

- Round-trip: `shorten` then `resolve` returns the original long URL.
- Collision: force `CodeGenerator` to return a duplicate code once, assert the service retries and succeeds.
- Custom alias conflict: two `shorten()` calls with the same alias — exactly one succeeds.
- Expiry: `resolve()` on an expired code returns "not found"/"expired", not the long URL.
- Idempotency (if in scope): same owner shortening the same long URL twice returns the same code.

## Follow-ups

- **Distributed unique ID generation** at scale (Snowflake-style, or a Zookeeper/DB-backed counter range allocator per node) — this LLD's `CodeGenerator` strategy seam is exactly where that plugs in.
- **Caching layer** (Redis/LRU) in front of `resolve()` for the read-heavy hot path.
- **Sharding** by code prefix or hash for horizontal scale of the repository.
- **Analytics pipeline**: async click-event stream instead of synchronous counter increment, to keep the redirect path fast.
