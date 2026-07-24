# Data Profiler — Design Notes (Staff Engineer)

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
3. **Fail partial, resume successful.** One bad table must not discard an hour of
   work; transient failures must remain retryable.
4. **Portable documents over vendor dumps.** Emit a versioned schema so profiles
   from different engines are joinable on `type.kind`.
5. **Observable by default.** Structured JSON log events + per-table durations +
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
│ DatabaseAdapter  │◀── supports_concurrent_profiling contract
│ + SqlProfiling   │    (template method for stats SQL)
│   Mixin          │
└────────┬─────────┘
   ┌─────┴──────────────────────┐
   ▼         ▼         ▼        ▼
 SQLite   DuckDB   Snowflake  Databricks
```

### Why a mixin instead of four copy-pasted `profile_column_stats` methods

The first revision duplicated the same flow in every adapter. The staff-shaped
fix extracts a **template method** (`SqlProfilingMixin`):

| Hook | Responsibility |
|---|---|
| `build_sample_plan` | Dialect TABLESAMPLE / LIMIT |
| `approx_distinct_expr` | HLL vs exact distinct |
| `execute_query` | Cursor / connection execution |
| `profile_column_stats` (shared) | SELECT build → parse → optional histograms |

Adding Postgres later is mostly catalog SQL + a sample plan — not another 150
lines of stats orchestration.

### Concurrency contract

Adapters declare `supports_concurrent_profiling`.

- **False** (SQLite, DuckDB): orchestrator is strictly sequential. Shared file
  connections are not thread-safe; pretending otherwise causes silent corruption.
- **True** (Snowflake, Databricks): per-call cursors; tables may run in a thread
  pool sized by `concurrency`.

This is an explicit capability flag, not an engine-name allowlist.

## Portable type system

Profiles store both:

- `type.kind` — stable enum for comparison
- `type.native` — original vendor string
- `precision` / `scale` / `nullable` when known

**Comparability rule:** `NUMBER(p,0)` / `NUMERIC(p,0)` / `DECIMAL(p,0)` map to
`integer`. Snowflake’s default integer-ish type is `NUMBER(38,0)`; treating it as
`decimal` made cross-engine joins lie. Scale > 0 stays `decimal`.

JSON Schema: `schema/profile_schema.json` (draft 2020-12). Tests validate emitted
documents structurally (and via `jsonschema` when installed).

## Statistics & cost model

Per table:

1. Column metadata from catalog (`information_schema` / `PRAGMA` / Unity Catalog).
2. Row count — exact, estimated (`SHOW TABLES` + `RESULT_SCAN` on Snowflake), or
   deferred when sampling and exact count would double the scan cost.
3. **One aggregated SELECT** for nulls / min / max / distinct across columns.
4. Optional histograms (`stats_depth: full`), capped by `max_histogram_columns`.

### Sampling

| Engine | Mechanism | Skip when |
|---|---|---|
| SQLite | `ORDER BY RANDOM() LIMIT n` | `row_count <= sample_size` |
| DuckDB | `USING SAMPLE n` / `TABLESAMPLE SYSTEM (p)` | same |
| Snowflake | `TABLESAMPLE BERNOULLI/SYSTEM` | same (**aligned** with locals) |
| Databricks | `TABLESAMPLE (p PERCENT \| n ROWS)` | same |

When sampling: null ratios scale to full table when row count is known; distinct
is marked estimate. Approx distinct (`APPROX_COUNT_DISTINCT` /
`approx_count_distinct`) engages on samples or tables > 1M rows.

### Budgets

- `timeout_seconds_per_table` — soft wall-clock timeout per table (future-based).
- `max_retries` — retry transient adapter errors with exponential backoff.
- `max_tables` / schema filters — bound discovery on huge catalogs.
- `max_histogram_columns` — prevent O(N) warehouse queries on wide tables.

## Resume semantics

Checkpoint file contains:

```json
{ "fingerprint": "...", "completed": {...}, "failed": {...} }
```

- **Successes** are skipped on resume.
- **Failures are recorded but not skipped** — otherwise a throttled warehouse
  permanently omits a table.
- Fingerprint = hash(engine + connection hint + material config). Changing
  `sample_size` or target database invalidates the checkpoint instead of mixing
  incompatible profiles.
- Atomic write via temp file + replace.

## Security & secrets

- Connection secrets accepted via env vars or YAML.
- `redact()` strips password/token fields from the persisted `run.config`.
- Prefer env vars in real deployments; YAML examples use placeholders.

## Assumptions & privileges

**Snowflake:** `USAGE` on warehouse/db/schema, `SELECT` on tables,
`INFORMATION_SCHEMA` read. Estimated counts via `SHOW TABLES` + `RESULT_SCAN`
can lag. Optional `QUERY_TAG` for cost attribution.

**Databricks:** SQL warehouse token; Unity Catalog preferred. Identifiers quoted
with backticks. `approx_count_distinct` for large tables.

**DuckDB / SQLite:** local files; used for demos, CI, and adapter contract tests.

## Validation strategy

| Layer | What |
|---|---|
| Unit | Type mapping golden cases (`NUMBER(38,0)` → integer, timestamps, arrays) |
| Integration | Seeded SQLite/DuckDB assert min/max/null/distinct/histograms |
| Contract | Fake cursors for Snowflake/Databricks catalog SQL paths |
| Schema | Emitted JSON validated against portable schema |
| Demo | `demos/run_demo.py` exercises end-to-end locally |
| Runtime | Structured events: `run_started`, `table_finished`, metrics block |

## Non-goals (intentionally deferred)

- Continuous CDC / incremental column-level change detection
- PII classification / column allowlists (easy extension point on `ColumnMeta`)
- Pushing profiles into a metastore (the document *is* the integration surface)
- Distributed workers across machines (thread pool is enough for warehouse APIs)

## Extending to a new engine

1. Subclass `DatabaseAdapter` + `SqlProfilingMixin`.
2. Implement catalog methods + `build_sample_plan` + `execute_query`.
3. Set `supports_concurrent_profiling` honestly.
4. Register in `adapters/__init__.py`.
5. Add a fake-cursor contract test for list/columns SQL.
