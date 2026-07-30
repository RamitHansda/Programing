# Data Profiler — Design Notes

## Problem

Warehouses accumulate hundreds of tables. Downstream systems (catalog sync,
schema drift detection, LLM agents, quality monitors) need a **compact,
comparable** summary of table/column shape and basic stats — without paying for
a full warehouse scan on every run, and without coupling consumers to Snowflake
vs Databricks type dialects.

## Design principles

1. **Engine-agnostic core, dialect at the edges.** The orchestrator never embeds
   vendor SQL. Adapters own quoting, catalog access, sampling syntax, and approx
   distinct.
2. **Accuracy is a knob, not a constant.** Sampling, HLL distinct, estimated row
   counts, and histogram caps are explicit config — operators choose cost.
3. **A number that came from a sample says so.** Estimates and lower bounds are
   labelled in the document, not left for the consumer to infer.
4. **Fail partial, resume successful.** One bad table must not discard an hour of
   work; transient failures must remain retryable.
5. **Portable documents over vendor dumps.** Emit a versioned schema so profiles
   from different engines are joinable on `type.kind`.
6. **Observable by default.** Structured JSON log events + per-table durations +
   run metrics. Secrets never land in the profile document.

## Architecture

```
┌──────────────────┐
│ CLI / YAML / env │
└────────┬─────────┘
         ▼
┌──────────────────┐     fingerprint      ┌────────────────────┐
│  DataProfiler    │─────────────────────▶│ ResumeState        │
│  budgets/retries │                      │ success≠failure    │
│  concurrency     │                      └────────────────────┘
└────────┬─────────┘
         │ StatsResult (typed)
         ▼
┌──────────────────┐
│ DatabaseAdapter  │◀── is_thread_safe / supports_concurrent_profiling
│ + SqlProfiling   │    prefetch_catalog / cancel_active_query
│   Mixin          │    (template method for stats SQL)
└────────┬─────────┘
   ┌─────┴──────────────────────┐
   ▼         ▼         ▼        ▼
 SQLite   DuckDB   Snowflake  Databricks
```

### Why a mixin instead of four copy-pasted `profile_column_stats` methods

The first revision duplicated the same flow in every adapter. The fix extracts a
**template method** (`SqlProfilingMixin`):

| Hook | Responsibility |
|---|---|
| `build_sample_plan` | Dialect TABLESAMPLE / row limit |
| `approx_distinct_expr` + `supports_approx_distinct` | HLL vs exact distinct |
| `execute_query` | Cursor / connection execution |
| `profile_column_stats` (shared) | SELECT build → parse → distinct pass → histograms |

Adding Postgres later is mostly catalog SQL + a sample plan — not another 150
lines of stats orchestration.

### Sampling is a grammar problem, not a string append

A sample can bind to the table (`TABLESAMPLE`) or to the query
(`USING SAMPLE`, `ORDER BY RANDOM() LIMIT n`). Appending the second kind to an
aggregate query does not sample anything:

```sql
-- LIMIT applies to the single aggregate result row: a full scan, mislabelled
SELECT COUNT(*) - COUNT(x), MIN(x), MAX(x) FROM t ORDER BY RANDOM() LIMIT 100
```

`SamplePlan` therefore carries `table_suffix` and `row_limit` separately, and
`source()` renders the FROM target — wrapping row limits in a derived table so
both the aggregate query and the histogram query (which needs a `WHERE`) stay
correct. Adapters never build the FROM clause themselves.

### Threading: two capabilities, not one

| Flag | Meaning | Needed for |
|---|---|---|
| `is_thread_safe` | callable off-thread, one call at a time | per-table timeout watchdog |
| `supports_concurrent_profiling` | callable from several threads at once | parallel table workers |

Conflating them is a live bug source: a per-table timeout has to run the work on
a watchdog thread, so a timeout on a thread-confined adapter (raw `sqlite3`)
fails every table. SQLite and DuckDB are safe *serialized* (explicit lock, plus
`check_same_thread=False` / per-call `cursor()`), so timeouts work there while
parallel tables stay off — a single local file connection gains nothing from
parallel readers, and DuckDB already parallelizes one aggregate across cores.

When a timeout is configured against an adapter that is not thread-safe, we log
that the budget cannot be enforced rather than corrupting the run.

## Portable type system

Profiles store both:

- `type.kind` — stable enum for comparison
- `type.native` — original vendor string
- `precision` / `scale` / `nullable` when known

**Comparability rule:** `NUMBER(p,0)` / `NUMERIC(p,0)` / `DECIMAL(p,0)` map to
`integer`. Snowflake’s default integer-ish type is `NUMBER(38,0)`; treating it as
`decimal` made cross-engine joins lie. Scale > 0 stays `decimal`.

JSON Schema: `src/data_profiler/schema/profile_schema.json` (draft 2020-12),
shipped as package data so validation also works from an installed wheel. Tests
validate emitted documents structurally and via `jsonschema`.

**Versioning.** `schema_version` is semver and the major is the compatibility
contract: additive fields bump the minor (adding the sample-provenance flags took
1.0.0 to 1.1.0), and readers accept any `1.x` document. Validating against an
exact string would turn every new optional statistic into a breaking change.
Every output format carries the same fields — the Parquet flatten includes the
provenance flags, because a consumer that cannot see `min_max_from_sample` will
read a sampled lower bound as a measured range.

## Statistics & cost model

Per table:

1. Column metadata — from the bulk catalog cache when available, else a
   per-table query.
2. Row count — exact `COUNT(*)`, or the catalog estimate
   (`INFORMATION_SCHEMA.TABLES.ROW_COUNT`) when `estimate_row_counts` is set.
3. **One aggregated SELECT** for nulls / min / max (+ distinct) across columns.
4. Optionally one full-table distinct pass (see below).
5. Optional histograms (`stats_depth: full`) — **one** additional query covering
   every numeric column, capped by `max_histogram_columns`.

So a table costs at most three queries regardless of how many columns it has.

### Histograms reuse bounds instead of rediscovering them

Step 3 already produced each column's min and max, so bucketing needs no
bounds-discovery CTE and no `FLOOR`/clamp arithmetic in SQL: the edges are
computed in Python and applied as conditional aggregates, so all numeric columns
are bucketed in a single scan rather than one scan per column. On a 10-numeric-
column table that is 1 query instead of 10, which matters most on a warehouse
where each one is a round trip plus queue time.

Two details worth knowing:

- Bucket edges are half-open, and the outer buckets are open-ended. Under
  sampling the histogram query draws its own sample, which can contain a value
  outside the bounds the first pass measured; with closed edges that row would
  land in no bucket and the counts would quietly not add up.
- Bounds are inlined as literals, so only values that arrive as real numbers
  (`int`, `float`, `Decimal`) are eligible; anything else skips the histogram
  rather than being interpolated into SQL.

### Sampling

| Engine | Fixed size | Percentage |
|---|---|---|
| SQLite | `ORDER BY RANDOM() LIMIT n` (derived table) | same, computed from row count |
| DuckDB | `USING SAMPLE reservoir(n ROWS)` | `USING SAMPLE p% (bernoulli)` |
| Snowflake | `SAMPLE BERNOULLI (n ROWS)` | `SAMPLE SYSTEM (p)` |
| Databricks | `TABLESAMPLE (n ROWS)` | `TABLESAMPLE (p PERCENT)` |

Sampling is skipped when a known `row_count <= sample_size`.

Two engine constraints worth stating, because both produce SQL that looks fine
and fails or misbehaves at runtime:

- Snowflake **rejects** fixed-size sampling with `SYSTEM`/`BLOCK` and caps it at
  1,000,000 rows, so fixed-size must use `BERNOULLI`. Percentages take the
  cheaper `SYSTEM` path.
- DuckDB percentage sampling defaults to vector-granularity system sampling and
  returns **zero rows** for small tables, so we ask for `bernoulli`.

SQLite's `ORDER BY RANDOM()` is a uniform sample but costs a full scan plus a
sort. It bounds the *statistics*, not the I/O; it is not a cost-reduction
strategy, and the local adapters exist for demos, CI and contract tests.

### Cardinality: why sampling distinct is the wrong trade

Distinct counted on a sample is not an estimate of the table, it is a **floor**:
a 1,000-row sample can never report more than 1,000 distinct values, no matter
how many the table holds. Extrapolating it needs an unseen-species estimator and
still degrades badly on skew.

Warehouses already have the right primitive, and it is one pass:

| `distinct_scope` | Behaviour |
|---|---|
| `table` (default) | Distinct over the full table — HLL when the engine has it, exact `COUNT(DISTINCT)` while the table is small enough to afford it |
| `sample` | Distinct over the sample; cheaper, and flagged `distinct_from_sample` |

A sampled run never silently escalates into an unbounded exact-cardinality scan:
without an approx aggregate, the full-table pass runs only for tables we know to
be small.

Whatever the scope, min/max still come from the sample and are marked
`min_max_from_sample`, because a sampled range always understates the true one.
Null counts are extrapolated to the table when the row count is known.

### Round trips dominate on wide catalogs

Per-table catalog queries are the hidden cost of profiling hundreds of tables:
columns + comment + row count is 3–4 round trips per table before a single
statistic is computed, or ~2,000 queries for a 500-table schema. Adapters
implement `prefetch_catalog(tables)` to read `information_schema` once per run
and serve `get_columns` / `get_row_count` / `get_table_comment` from that cache,
falling back to per-table queries on a miss so a permission error degrades
instead of failing.

### Budgets

- `timeout_seconds_per_table` — wall-clock budget per table. On expiry the
  adapter cancels the in-flight statement and the run moves on; abandoning the
  future without cancelling would report a timeout and still pay the full cost.
- `max_retries` — retry adapter errors that report themselves as transient,
  with exponential backoff.
- `max_tables` / schema filters — bound discovery on huge catalogs.
- `max_histogram_columns` — prevent O(N) warehouse queries on wide tables.

## Resume semantics

The checkpoint is an append-only log: a fingerprint header, then one JSON record
per table outcome.

```json
{"fingerprint": "9f1c…"}
{"table": "DB.PUBLIC.ORDERS", "ok": true, "profile": {…}}
{"table": "DB.PUBLIC.EVENTS", "ok": false, "error": "…", "duration_ms": 812.4}
```

- **Successes** are skipped on resume; the profile is replayed from the log.
- **Failures are recorded but not skipped** — otherwise a throttled warehouse
  permanently omits a table.
- Fingerprint = hash(engine + connection hint + material config). Changing
  `sample_size`, `distinct_scope` or the target database invalidates the
  checkpoint instead of mixing incompatible profiles; the stale log is discarded
  rather than appended to.
- Replay is last-record-wins, and a crash mid-append can only damage the final
  line, which is dropped on load. Records are written by the thread that collects
  results, so the log needs no lock of its own.
- The older single-document format is still read, so an in-flight checkpoint from
  a previous version resumes rather than restarting.

**Why a log and not a document.** The first implementation rewrote the entire
accumulated state after every table, which is quadratic in catalog size — exactly
the dimension this tool is for. Measured on 200 tables x 20 columns: 286 MB
written to persist a 1.4 MB profile, and the run took 6.36s against 1.46s with
checkpointing off, i.e. resume cost 4x the profiling itself. Appending costs one
record per table: the same run now writes 1.7 MB and takes 1.52s, ~5% over the
unchecked baseline, and re-running with all 200 tables cached completes in 0.08s.

## Security & secrets

- Connection secrets accepted via env vars or YAML.
- `redact()` strips password/token fields from the persisted `run.config` and
  from structured log events.
- Prefer env vars in real deployments; YAML examples use placeholders.

## Assumptions & privileges

**Snowflake:** `USAGE` on warehouse/db/schema, `SELECT` on tables,
`INFORMATION_SCHEMA` read. `INFORMATION_SCHEMA` covers only the session's
current database and its row counts can lag very recent loads. `COUNT(*)` is
answered from micro-partition metadata, so an exact count is not a second scan.
Optional `QUERY_TAG` for cost attribution.

**Databricks:** SQL warehouse token; Unity Catalog preferred
(`system.information_schema`), with a fallback to the local `information_schema`
that is remembered after the first failed probe. Identifiers quoted with
backticks. Positional `?` parameters are native parameters in connector 3.0+.
`information_schema` carries no row count, so counts come from `COUNT(*)`, which
Delta answers from file statistics.

**DuckDB / SQLite:** local files; used for demos, CI, and adapter contract tests.

## Validation strategy

| Layer | What |
|---|---|
| Unit | Type mapping golden cases (`NUMBER(38,0)` → integer, timestamps, arrays) |
| Integration | Seeded SQLite/DuckDB assert min/max/null/distinct/histograms |
| Sampling | Sampled runs must read `sample_size` rows, not the table, and still report the table's row count |
| Budgets | Timeout returns within the budget, cancels the query, and is skipped (not fatal) on thread-confined adapters |
| Concurrency | A fake concurrent adapter must actually overlap, and a non-concurrent one must not |
| Dialect | Generated stats/histogram SQL parsed with `sqlglot` per engine dialect |
| Contract | Fake cursors for Snowflake/Databricks catalog SQL and prefetch caching |
| Schema | Emitted JSON validated against the portable schema; minor-version compatibility asserted |
| Format parity | Parquet output carries the same provenance flags as JSON |
| Query count | Histograms for N numeric columns must still be one query |
| Checkpoint | Append-only (one record per table), tolerates a torn final record, reads the legacy format |
| Demo | `demos/run_demo.py` exercises end-to-end locally; CI runs it |
| Runtime | Structured events: `run_started`, `catalog_prefetched`, `table_finished`, metrics block |

**Testing SQL we cannot run in CI.** Fake cursors prove which statements were
issued, not that an engine accepts them — `SAMPLE SYSTEM (100000 ROWS)` records
perfectly and Snowflake rejects it. Parsing generated SQL with `sqlglot` against
each dialect closes that gap without credentials. Real warehouse verification
still needs a demo account; that is the one layer this repo cannot self-host.

### Measured cost model

`python demos/benchmark.py --tables 200 --columns 10 --rows 20000`
(200 tables x 10 columns x 20k rows = 40M cells, DuckDB, single worker):

| Scenario | Seconds | Tables/s |
|---|---|---|
| exact, no sampling | 2.23 | 90 |
| sample 1k, `distinct_scope=sample` | 0.97 | 207 |
| sample 1k, `distinct_scope=table` | 2.33 | 86 |
| sample 1k + histograms (`stats_depth=full`) | 3.95 | 51 |
| sample 1k, `prefetch_catalog=false` | 3.33 | 60 |
| sample 1k + resume checkpointing | 2.29 | 88 |

Reading it: sampling buys ~2.5x when you accept sample-scoped cardinality, and
the full-table distinct pass spends that saving to make distinct counts true —
on a warehouse that pass is an HLL aggregate rather than an exact scan, so the
trade is better there than these local numbers suggest. Histograms remain the
most expensive option (they add ~1.7s here, down from ~2.9s before the batching
described above), which is why `max_histogram_columns` still exists. Catalog
prefetch saves ~30% even locally where round trips are nearly free; against a
warehouse it removes ~3 network round trips per table. Resume checkpointing is
near-free after moving to an append-only log.

Absolute numbers are DuckDB-on-a-laptop numbers. What transfers to a warehouse is
the ranking and the query counts per table: 1 query for basic stats, 2 with a
full-table distinct pass, 3 with histograms — plus 2 catalog queries for the
entire run.

## Non-goals (intentionally deferred)

- Predicate pushdown / partition-scoped profiling (profile the last N days of a
  partitioned table). The natural shape is a per-table predicate in config,
  applied inside `SamplePlan.source()`.
- Continuous CDC / incremental column-level change detection
- PII classification / column allowlists (easy extension point on `ColumnMeta`)
- Pushing profiles into a metastore (the document *is* the integration surface)
- Distributed workers across machines (thread pool is enough for warehouse APIs)

## Extending to a new engine

1. Subclass `DatabaseAdapter` + `SqlProfilingMixin`.
2. Implement catalog methods + `build_sample_plan` + `execute_query`.
3. Set `is_thread_safe` / `supports_concurrent_profiling` / `supports_approx_distinct`
   honestly, and implement `cancel_active_query` if the driver can cancel.
4. Optionally implement `prefetch_catalog` for bulk metadata.
5. Register in `adapters/__init__.py`.
6. Add a fake-cursor contract test plus a `sqlglot` dialect check for the
   generated SQL.
