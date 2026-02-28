# PostgreSQL Internals — Staff Engineer Deep Dive

A reference for answering storage, indexing, and design questions at Staff Engineer level: how it works, why it’s designed that way, and how to articulate it clearly.

---

## Part 1: How Data Is Stored

### 1.1 Pages (Blocks)

**What it is**
- The unit of I/O and storage in PostgreSQL is a **page** (often called “block”). Default size is **8 KB** (configurable only at `initdb` via `-B`).
- Every relation (table, index, sequence, etc.) is a sequence of pages. There is no “row file” vs “page file”—the table *is* a sequence of 8 KB pages.

**Layout of a page (heap table)**
- **Page header** (~24 bytes): LSN (for WAL/recovery), checksum, flags, free-space pointers, etc.
- **Line pointers** (item identifiers): array of (offset, length) for each tuple on the page. Fixed size per slot (typically 4 bytes). They give a stable “slot” so that indexes can refer to a row by (block number, line pointer index) = **TID**.
- **Free space**: gap between the end of the line-pointer array and the start of the actual tuple data.
- **Tuples**: grow from the **end** of the page toward the beginning. New rows fill free space; when a row is updated (and not in-place), the old version stays until VACUUM, and the new version is written elsewhere (often another page).

**Why 8 KB**
- Balance between: (1) amortizing header/overhead, (2) not wasting memory and I/O for tiny rows, (3) compatibility with OS filesystem blocks and buffer pool management. Changing it affects on-disk format and isn’t supported after init.

**Staff-level answer**
- “Data is stored in 8 KB pages. Each page has a header, an array of line pointers (one per row), and then the row data. The line pointer gives (offset, length) so we can resolve a TID to a row without scanning the page. Pages are the unit of buffer pool and I/O, so we always read or write full pages.”

---

### 1.2 Heap Files and Segments

**What it is**
- A table is a **heap**: an unordered set of pages. Rows are placed wherever there is free space (e.g. via FSM — Free Space Map). There is no “physical sort” of the table by key.
- Files: one main file per relation (e.g. `base/<dbOID>/<relfilenode>`). When that file reaches 1 GB, PostgreSQL creates another **segment** (e.g. `relfilenode.1`, `relfilenode.2`). So one logical table is spread across multiple segment files.

**Why heap**
- Inserts are fast: append (or use free space from FSM). No need to maintain sorted order. Order is maintained only in indexes. This keeps write path simple and avoids expensive table rewrites on insert.

**Staff-level answer**
- “Tables are stored as heaps: pages are not ordered by key. We use a free-space map to find pages with room for inserts. When a file hits 1 GB we add a new segment file. Ordering is maintained in indexes, not in the table itself.”

---

### 1.3 Tuple (Row) Structure

**What it is**
- A **tuple** is one row. It has a **header** and then the column values.

**Tuple header (simplified)**
- **xmin**: transaction ID that inserted this row (for visibility).
- **xmax**: transaction ID that deleted it or locked it (0 if alive and not locked).
- **cid**: command ID within the transaction (for row order within a transaction).
- **ctid**: physical location (block, offset) — “self pointer.” Can change after VACUUM; indexes are updated on VACUUM when ctid changes.
- **infomask**: flags (e.g. “has nulls”, “has variable-width attrs”, “frozen”).
- **null bitmap**: one bit per nullable column (only if any column is null).

**Row data**
- Stored in attribute order. Fixed-width types (int, bigint, timestamp) at fixed offsets; variable-width (varchar, text) as length + bytes. Alignment and padding follow platform rules (e.g. 8-byte alignment for some types).

**Why this matters**
- Visibility is decided by comparing transaction IDs (xmin, xmax) with the snapshot of the current transaction. No separate “version store”: the heap itself holds all visible and dead versions until VACUUM.

**Staff-level answer**
- “Each row is a tuple with a header and payload. The header has xmin/xmax for MVCC visibility, a null bitmap, and metadata. The engine decides visibility by comparing xmin/xmax to the current snapshot. There’s no separate version store—old versions live in the heap until VACUUM.”

---

### 1.4 TOAST (The Oversized-Attribute Storage Technique)

**What it is**
- A single heap page has limited space for one row (roughly ~1.6 KB usable after headers and line pointers). Large values (text, bytea, jsonb, large arrays, etc.) are moved out of the main table into a **TOAST table** (one per table that needs it).
- In the main table we store only a **TOAST pointer**: (toast table OID, chunk ID, etc.). The actual value is stored in the TOAST table in **chunks** (default 2 KB). TOAST can **compress** (LZ) and/or store out-of-line.

**TOAST strategies (per column)**
- **PLAIN**: no TOAST (e.g. for types that forbid it).
- **EXTENDED**: compress first; if still too big, move to TOAST (default for most large types).
- **EXTERNAL**: store in TOAST but do not compress (e.g. when compression is useless).
- **MAIN**: try to keep in main table; only move to TOAST if necessary (still allows compression in main).

**Why it matters**
- Keeps the main table row small so that many rows fit per page and full-table scans stay cache-friendly. Large content is fetched only when the column is read.

**Staff-level answer**
- “Rows are limited to about 1.6 KB on a page. Large attributes are moved to a separate TOAST table in chunks; the main row holds only a TOAST pointer. We can compress before storing. So the heap stays compact and we only touch TOAST when we actually read those columns.”

---

### 1.5 MVCC (Multi-Version Concurrency Control)

**What it is**
- Readers never block writers; writers never block readers. Each transaction sees a **snapshot** of the database (which transactions are visible). Row visibility is determined by **xmin** and **xmax** in the tuple header.

**Visibility rules (simplified)**
- Row is **visible** if: xmin is committed and committed before our snapshot, and (xmax is 0 or xmax is not committed or committed after our snapshot).
- “Committed” is determined by the **commit log** (pg_clog / pg_xact): whether that transaction ID is committed, aborted, or in progress.

**No “update in place” for row changes**
- UPDATE typically writes a **new** row version (with new xmin) and sets xmax on the old version. So we get multiple physical versions of the same logical row. DELETE sets xmax. Old versions stay until VACUUM.

**Snapshot**
- A snapshot is: “all txids < X are visible according to commit status, and my own changes are visible.” Implemented as snapshot data structures (e.g. xmin, xmax, xip list) passed through the system.

**Staff-level answer**
- “We use MVCC: every row has xmin/xmax. Visibility is decided by comparing those to the transaction’s snapshot and the commit log. UPDATE creates a new row version and marks the old one with xmax; nothing is updated in place. So we get reader/writer non-blocking, but we accumulate dead tuples until VACUUM.”

---

### 1.6 VACUUM and Space Reuse

**What it is**
- **VACUUM** (standard): scans pages, identifies dead tuples (not visible to any snapshot), and marks their space as **reusable** (does not return space to the OS in the general case). It updates the **free space map** (FSM) so that future inserts can use that space. It can also **freeze** old rows (replace xmin with FrozenTransactionId) to avoid transaction ID wraparound issues.
- **VACUUM FULL**: rewrites the table so that only live rows remain and returns space to the OS. It holds an exclusive lock and is effectively a full table rewrite; very expensive.

**Why it matters**
- Without VACUUM, dead tuples accumulate (bloat), full scans read useless data, and indexes point to dead rows. Autovacuum runs VACUUM periodically based on dead tuple counts and transaction age.

**Staff-level answer**
- “VACUUM marks dead tuple space as reusable and updates the FSM; it doesn’t usually return space to the OS. That keeps the heap and indexes from growing without bound. We also need VACUUM to freeze old rows and prevent transaction ID wraparound. VACUUM FULL rewrites the table and returns space but is heavy and locking.”

---

### 1.7 File Layout on Disk

**What it is**
- Data directory: `base/<database OID>/` — each table and index has a **relfilenode** (file name). System catalogs (pg_class, pg_attribute, etc.) live in `base/<db>/` as well.
- WAL: `pg_wal/` (or `pg_xlog/` on older versions). Each segment is 16 MB by default.
- Other: `global/` (cluster-wide catalogs), `pg_tblspc/` (tablespaces), etc.

**Staff-level answer**
- “Data lives under base/<dbOID>/ with one file per relation (relfilenode); tables can have multiple segment files when they exceed 1 GB. WAL is in pg_wal. We identify relations by OID in the catalogs and by relfilenode on disk.”

---

## Part 2: How Indexing Works

### 2.1 Indexes Are Separate Structures

**What it is**
- An index is a **separate relation**: its own pages and segment files. It does **not** reorder the table. It holds **index entries** (key → TID). The table remains a heap.

**TID (Tuple ID)**
- TID = (block number, line pointer index within block). Uniquely identifies one tuple slot. When we look up by index, we get TID(s), then fetch the corresponding heap page(s) to get the full row (unless we can do an index-only scan).

**Staff-level answer**
- “Indexes are separate on-disk structures. They store (key, TID). The table stays a heap. Lookup is: search index → get TID → read that heap page and resolve the line pointer to the tuple.”

---

### 2.2 B-Tree Index (Default) in Detail

**Structure**
- **Balanced** tree: all leaf pages are at the same depth. Internal pages contain (key, pointer) pairs; leaf pages contain (key, TID) pairs. Keys in leaves are in **sorted order** (left to right), so range scans are sequential.

**Operations**
- **Search**: start at root, compare key, follow pointer to child; repeat until leaf. In leaf, binary search or scan for key(s), collect TIDs.
- **Insert**: find leaf for key; if page has space, insert (key, TID). If page is full, **split**: create new page, redistribute keys, add new entry to parent. Splits can cascade up.
- **Delete**: find leaf, mark entry as dead (or remove). PostgreSQL often does “soft” delete (mark dead) and reclaims space during VACUUM. Unused pages can be recycled.

**Duplicates**
- B-tree allows duplicate keys. Entries are (key, TID); TID breaks ties so that all entries are unique. For “find all rows with key = X,” we find the first leaf entry for X and scan forward until key changes.

**Uniqueness**
- Unique index: before inserting (key, TID), we check for existing key. If found, insert fails (or triggers ON CONFLICT if used).

**Staff-level answer**
- “B-trees are balanced; leaves are sorted by key. We search by walking down the tree to the leaf, then scan for the key and collect TIDs. Insert may cause a page split. We support duplicates by storing (key, TID); uniqueness is enforced by checking for an existing key on insert.”

---

### 2.3 Index Scan vs Sequential Scan

**Index scan**
- Use index to find TIDs for matching rows; for each TID, fetch the heap page (random I/O if not cached). Good when a **small fraction** of rows match. If many rows match, random heap fetches can be slower than one sequential scan.

**Sequential scan**
- Read all heap pages in order. Good when a large fraction of rows are needed or when the table is small. No index overhead.

**Planner**
- The planner estimates selectivity and cost (index scan cost + heap fetch cost vs sequential scan cost) and chooses. It can also use bitmap index scan: collect all TIDs from the index, sort by block number, then read heap pages in order to reduce random I/O.

**Staff-level answer**
- “We use the index when selectivity is high enough that index lookup plus heap fetches are cheaper than a full table scan. For moderate selectivity we often use a bitmap index scan: get all TIDs, sort by block, then read heap in order to amortize I/O.”

---

### 2.4 Index-Only Scans

**What it is**
- If the index contains **all** columns needed by the query (“covering index”), we might not need to touch the heap. But we still need to know if the row is **visible** (MVCC). Visibility info is in the heap tuple, not in the index.

**Visibility map**
- Each heap relation has a **visibility map**: one bit per page. “All visible” means every tuple on that page is visible to everyone (e.g. after VACUUM). For such pages we can do an index-only scan without reading the heap. For other pages we must go to the heap to check xmin/xmax.

**Staff-level answer**
- “Index-only scan works when the index is covering and we can prove visibility without reading the heap. We use a visibility map: if a page is marked all-visible, we trust the index and skip the heap. Otherwise we have to fetch the heap to check xmin/xmax.”

---

### 2.5 Other Index Types (Concise)

- **Hash**: bucket array; key hashed to bucket; good for equality only. No ordering, no range.
- **GIN**: “inverted” index: one index entry per element (e.g. per array element, per lexeme in full text). Good for containment (array @>, tsvector @@).
- **GiST / SP-GiST**: Tree of “predicates”; flexible (geometry, ranges, full text). Different balance of tree shape vs search semantics.
- **BRIN**: Block range summary (e.g. min/max per range of pages). Tiny index; good when data is physically ordered (e.g. time). Eliminates whole ranges without reading them.

**Staff-level answer**
- “We choose by access pattern: B-tree for ordering and range; GIN for containment and full text; GiST for geometry and custom predicates; BRIN for very large, ordered data; hash for equality-only when we don’t need range.”

---

## Part 3: Staff-Level Talking Points and Trade-offs

### Storage

| Topic | One-line | Deeper point |
|-------|----------|--------------|
| Pages | Unit of I/O and storage (8 KB). | Everything is page-oriented; buffer pool and WAL work in pages. |
| Heap | Table is unordered set of pages. | Order is in indexes; heap is optimized for append and reuse. |
| Tuple | Header (xmin, xmax, etc.) + column values. | Visibility and locking are encoded in the tuple; no separate version store. |
| TOAST | Large values out-of-line in chunks. | Keeps main row small; optional compression; pointer in main row. |
| MVCC | Snapshots + xmin/xmax. | Non-blocking reads/writes; cost is bloat and need for VACUUM. |
| VACUUM | Reclaim dead space, freeze old xids. | Required for correctness (wraparound) and performance (bloat). |

### Indexing

| Topic | One-line | Deeper point |
|-------|----------|--------------|
| Index = separate structure | (key, TID) in own pages. | Table stays heap; index is a “sidecar” for fast lookup. |
| B-tree | Sorted, balanced; supports range and order. | Default; good for most OLTP; insert/delete cause splits/merges. |
| TID | (block, offset) into heap. | After VACUUM, TIDs can change; we need to update index or use something like index deduplication. |
| Index-only scan | Possible if covering + visibility. | Visibility map is what makes it safe without heap read. |
| Selectivity | Planner compares index+heap cost vs seq scan. | Wrong stats or bad selectivity can cause poor plan choice. |

### Common Follow-ups and Answers

**“Why not store the table in sorted order?”**  
We’d pay on every insert/update (rebalancing or splitting the whole table). Heap + index keeps inserts cheap and pushes ordering into indexes, which are smaller and optimized for that.

**“Why 8 KB pages?”**  
Balance of overhead, I/O efficiency, and buffer pool usage. Changing it would change on-disk format and isn’t supported after init.

**“What happens to indexes when we VACUUM?”**  
VACUUM can update index entries when ctid changes (when tuples move). It also removes index entries pointing to dead tuples. So both heap and index get cleaned.

**“When would you use VACUUM FULL?”**  
When we need to reclaim space to the OS and can afford downtime (exclusive lock, full rewrite). Usually we rely on regular VACUUM and avoid FULL unless we’ve removed a lot of data and measured bloat.

**“How do you debug a bad plan?”**  
Check statistics (ANALYZE), selectivity estimates (EXPLAIN (ANALYZE, BUFFERS)), and whether the right indexes exist. Wrong row estimates often lead to wrong join order or wrong choice between index vs sequential scan.

---

## Summary

- **Storage**: 8 KB pages, heap tables (unordered), tuples with xmin/xmax for MVCC, TOAST for large values, VACUUM to reclaim space and freeze.
- **Indexing**: Separate B-tree (or other type) storing (key, TID); lookup goes index → TID → heap (or index-only when covering + visible).
- **Staff-level**: You can explain *what* each component is, *why* it’s designed that way (trade-offs), and *how* it affects performance and operations (VACUUM, planning, index-only scans, bloat).

Use this doc as a single reference to answer PostgreSQL storage and indexing questions at Staff Engineer level.
