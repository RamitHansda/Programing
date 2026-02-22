# Query Pagination for Large Datasets — Design (L6 / Staff Engineer)

Design for **efficient, consistent pagination over very large datasets**, suitable for high-throughput APIs (e.g. transaction history, order books, audit logs). This document covers strategies, cursor/keyset design, SQL formulation, consistency under concurrent writes, and indexing—aligned with fintech/crypto scale and auditability expectations (e.g. Coinbase-style domain round).

---

## 1. Requirements & Constraints

| Dimension | Target / Constraint |
|-----------|----------------------|
| **Scale** | Billions of rows; 10K–100K+ QPS for list endpoints. |
| **Latency** | p99 list API &lt; 100–200 ms. |
| **Consistency** | No duplicate/skip items across pages under concurrent inserts/updates; optional strong consistency for “current state” views. |
| **Ordering** | Stable, deterministic (e.g. by `created_at`, `id`, or composite). |
| **Compliance** | Audit trails, no silent data loss; cursor format may need to be opaque/tamper-evident for sensitive APIs. |

---

## 2. Pagination Strategies: Offset vs Cursor/Keyset

### 2.1 Offset-based (LIMIT/OFFSET)

**Idea:** Client asks for “page 2” with `LIMIT 20 OFFSET 20`.

**Pros:** Simple API (`?page=2&page_size=20`), easy to implement, can jump to arbitrary page.

**Cons:**

- **Cost grows with offset:** DB still must scan/skip `OFFSET` rows (e.g. `OFFSET 1_000_000` → scan 1M rows then return 20). Index helps only up to a point; deep pages are expensive and unstable.
- **Inconsistency under writes:** Between page 1 and page 2, if rows are inserted at the “beginning,” you get duplicates or skips. Deletions cause skips. Not acceptable for financial/audit lists.
- **Non-determinism:** Without a unique tie-breaker in `ORDER BY`, equal sort keys can reorder between queries.

**Verdict:** Use only for small datasets, admin UIs, or when “jump to page N” is a hard requirement and consistency is relaxed. **Not recommended for large, mutable datasets at L6 scale.**

### 2.2 Cursor-based (Keyset / “seek method”)

**Idea:** Client receives an **opaque cursor** that encodes the position “after the last item you saw.” Next request returns rows **strictly after** that position. No `OFFSET`; the DB uses a range condition on the sort key(s).

**Pros:**

- **Constant-time per page:** Query cost is independent of how “deep” the user is (index range scan from cursor position).
- **Stable under concurrent writes:** If you define “after” using a unique, monotonic key (or composite), new inserts at the “top” don’t duplicate or skip items already sent; you only get new items in later pages or in a refreshed first page.
- **Deterministic ordering:** Sort by unique column(s) (e.g. `(created_at, id)` or `id`) so order is stable.

**Cons:**

- No random access to “page 5” without storing all intermediate cursors; UI is “Next/Previous” or “Load more.”
- Cursor design and encoding (opaque, tamper-evident) requires care.

**Verdict:** **Default choice for large, mutable datasets** and for APIs that need consistent, scalable list endpoints.

---

## 3. Cursor / Keyset Design

### 3.1 What the cursor encodes

The cursor should encode **the sort key values of the last item** (or the first item for “previous page”) so the next query can use a strict inequality.

**Single-column sort (e.g. `id`):**

- Cursor = last `id` (e.g. `12345`).
- Next page: `WHERE id > 12345 ORDER BY id ASC LIMIT 20`.

**Composite sort (e.g. `created_at`, `id`):**

- Cursor = `(last_created_at, last_id)`.
- Next page: `WHERE (created_at, id) > (?, ?) ORDER BY created_at ASC, id ASC LIMIT 20`.

**Why include a unique column (`id`)?**  
`created_at` (or `updated_at`) can have duplicates. Without a unique tie-breaker, (1) order is non-deterministic, and (2) you can’t express “strictly after this row” unambiguously. So always use a composite like `(created_at, id)` for stable, keyset-safe pagination.

### 3.2 Opaque and tamper-evident cursors

- **Opaque:** Don’t expose raw `id` or timestamps in the URL; encode them in a cursor string (e.g. base64 of a binary struct or signed token). Prevents clients from guessing other users’ cursors or probing by id.
- **Tamper-evident / signed:** For sensitive or audit contexts, sign the cursor (e.g. HMAC with server secret). Reject requests with invalid signature. Ensures clients can’t forge or alter cursors to jump to arbitrary positions.

**Example payload (conceptually):**  
`{ "ts": 1699123456, "id": 98765, "dir": "next" }` → base64 + HMAC → `eyJ0cyI6...` + signature.

### 3.3 Bidirectional (Next / Previous)

- **Next:** “Give me rows after cursor.”  
  `WHERE (created_at, id) > (cursor_ts, cursor_id) ORDER BY created_at ASC, id ASC LIMIT n`.
- **Previous:** “Give me rows before cursor.”  
  Encode direction in cursor; query: `WHERE (created_at, id) < (cursor_ts, cursor_id) ORDER BY created_at DESC, id DESC LIMIT n` then reverse the list before returning so client still sees chronological order.

Store in cursor: `(created_at, id, direction)`. Server uses the right inequality and sort order based on `direction`.

### 3.4 Cursor for “filtered” lists

If the list is filtered (e.g. `user_id = ?`, `status = 'completed'`), the cursor should still be **relative to the full sort order** (e.g. `(created_at, id)`), not “last id in filtered set.” The same index can support the predicate and the sort:

```sql
WHERE user_id = ? AND (created_at, id) > (?, ?)
ORDER BY created_at ASC, id ASC
LIMIT 20
```

So cursor = last `(created_at, id)` in the result set; filter stays in `WHERE` for every request.

---

## 4. SQL Query Formulation

### 4.1 Index-friendly pattern

Assume table `transactions (id, user_id, created_at, amount, ...)` and “list my transactions, newest first.”

**Index:**  
`(user_id, created_at DESC, id DESC)` — supports filter + sort + keyset in one range scan.

**Next page (newest first):**  
Cursor = `(last_created_at, last_id)` from previous response.

```sql
SELECT id, user_id, created_at, amount, ...
FROM transactions
WHERE user_id = ?
  AND (created_at, id) < (?, ?)   -- keyset: strictly before (for “newest first”)
ORDER BY created_at DESC, id DESC
LIMIT 20;
```

For “oldest first,” use `(created_at, id) > (?, ?)` and `ORDER BY created_at ASC, id ASC`.

### 4.2 Composite keyset in databases that don’t support tuple comparison

Some DBs don’t support `(a, b) > (x, y)`. Use a combined condition:

```sql
WHERE user_id = ?
  AND (
    created_at < :cursor_ts
    OR (created_at = :cursor_ts AND id < :cursor_id)
  )
ORDER BY created_at DESC, id DESC
LIMIT 20;
```

Ensure the index is still used (e.g. leading columns `user_id`, `created_at`; then `id` for the tie-break). Test with `EXPLAIN`.

### 4.3 Limit and max page size

- Enforce a **max page size** (e.g. 100) to avoid abuse and keep query cost bounded.
- Default page size (e.g. 20) improves UX and reduces accidental large responses.

---

## 5. Consistency Under Concurrent Writes

### 5.1 What can go wrong

- **Inserts:** New row with `created_at` between two pages can cause duplicate (if seen on both) or skip (if ordering shifts).
- **Updates:** Changing `created_at` or sort key can move a row across “page boundaries” → duplicate or skip.
- **Deletes:** A row that was on the “next” page disappears → skip (usually acceptable; client sees fewer items).

### 5.2 How keyset pagination helps

- **Stable position:** “Rows after (ts, id)” is a **fixed set** at query time. You’re not “skipping N rows” but “returning the next 20 rows after this key.” So inserts at the beginning (e.g. new transactions with newer `created_at`) don’t change the set of rows that are “after” the cursor the client already has.
- **No duplicate/skip from inserts “ahead”:** New rows with larger keys will appear in a **future** request (or when the client refetches from the start). They won’t be re-sent for an old cursor.
- **Updates that change sort key:** If you update `created_at` (or another sort column), a row can “move” and effectively be seen twice or disappear. Mitigation: (1) avoid updating sort keys when possible, or (2) use an immutable sort key (e.g. `id` only, or `created_at` that you never change). For audit tables, `created_at` is usually immutable.

### 5.3 Snapshot isolation / read consistency

- **Repeatable read (snapshot):** Use a snapshot (e.g. PostgreSQL `REPEATABLE READ` or `START TRANSACTION ...` with snapshot) so that all pages of the same list request see the same logical snapshot. Prevents rows from appearing or disappearing between page 1 and page 2 **within the same session**. Not always needed if the client accepts “eventual” consistency across pages (e.g. new item might appear on next page or after refresh).
- **Read-your-writes:** For “list my recent transactions,” ensure the read replica or primary reflects the user’s own writes (e.g. read from primary for a short window after write, or use consistent read path). Otherwise the user might not see their just-submitted transaction on the first page.

### 5.4 Summary

| Scenario | Mitigation |
|----------|------------|
| Inserts at “beginning” | Keyset: “after (ts, id)” is stable; new rows don’t duplicate previous pages. |
| Deletes | Accept skips or return “deleted” placeholders if audit requires. |
| Updates to sort key | Prefer immutable sort keys; or accept rare duplicate/skip and document. |
| Cross-page consistency | Optional: snapshot isolation for multi-page read in same session. |
| Read-your-writes | Route to primary or use consistent read after write. |

---

## 6. Indexing for Very Large Datasets

### 6.1 Index that matches the query

- **Filter + sort + keyset:** Index should have columns in order: **equality filters** (e.g. `user_id`), then **sort columns** (e.g. `created_at DESC`, `id DESC`). That single index supports `WHERE user_id = ? AND (created_at, id) < (?, ?) ORDER BY created_at DESC, id DESC LIMIT n` with a range scan and no sort.
- **Covering index:** Include in the index all columns needed by the query (if supported: e.g. INCLUDE in PostgreSQL) so the query is index-only and avoids heap lookups. Reduces I/O for very large tables.

### 6.2 Partitioning (time or tenant)

- **Time-based partitioning:** e.g. by `created_at` (month/week). Enables partition pruning for “last 30 days” and keeps indexes smaller per partition. Keyset still works; the query planner can prune partitions that cannot contain rows “after” the cursor.
- **Tenant / user_id partitioning:** If every query is scoped by `user_id`, partitioning or sharding by `user_id` keeps hot data local and indexes smaller.

### 6.3 Avoiding over-indexing

- One (or a few) list patterns per table; design the index for the main list query. Secondary lists (e.g. “by status”) may need a separate index; weigh write cost vs read QPS.

### 6.4 Monitoring

- Track slow queries (e.g. p99 latency for list API), index usage, and scan/seek counts. Alert on degradation after schema or data distribution changes.

---

## 7. API Shape (REST-style)

- **Request:**  
  `GET /v1/transactions?limit=20`  
  `GET /v1/transactions?cursor=eyJ0cyI6...&limit=20`  
  Optional: `direction=next|prev`.
- **Response:**  
  - `data: [ ... ]`  
  - `next_cursor: "eyJ0cyI6..."` (present only if there is a next page)  
  - `prev_cursor: "..."` (optional, for “previous” page)  
  - No `total_count` by default (expensive at scale); offer as optional or estimated if product needs it.

---

## 8. L6-Level Discussion Points

- **Trade-offs:** Explain why cursor over offset (cost, consistency); when you might still use offset (admin, small datasets).
- **Cursor design:** Opaque + signed for security/compliance; composite key for deterministic order and stable keyset.
- **SQL and indexing:** One index per main list pattern; match filter + sort + keyset; partitioning for scale.
- **Consistency:** Keyset stability under inserts; immutable sort keys; optional snapshot for multi-page consistency; read-your-writes for UX.
- **Compliance/audit:** Cursor doesn’t replace audit logs; list API should be idempotent and logged; signed cursor supports non-repudiation of “what the client asked for.”

---

## 9. Summary Table

| Topic | Recommendation |
|-------|----------------|
| **Strategy** | Cursor/keyset for large, mutable datasets; offset only for small or admin. |
| **Cursor** | Encode last `(sort_key_1, sort_key_2, ...)`; use unique column (e.g. `id`) in sort; opaque + signed for sensitive APIs. |
| **SQL** | `WHERE filter AND (sort_cols) > or < (cursor) ORDER BY sort_cols LIMIT n`; index = filter cols + sort cols. |
| **Consistency** | Keyset gives stable “next page” under inserts; prefer immutable sort keys; optional snapshot for cross-page consistency. |
| **Indexing** | One index per list pattern (filter + sort); covering index if possible; partition by time/tenant for very large tables. |

This design gives you a clear, production-ready story for pagination at scale and aligns with fintech expectations for consistency, security, and auditability in an L6 domain round.
