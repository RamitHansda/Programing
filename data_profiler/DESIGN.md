# Data Profiler — Design Notes

## Goal

Scan hundreds of tables across heterogeneous engines (Snowflake, Databricks SQL
warehouse, DuckDB, SQLite), collect compact table/column metadata + statistics,
and emit a **portable** profile document that downstream tooling can compare
across warehouses.

## Architecture

```
┌─────────────┐     ┌──────────────────┐     ┌────────────────────┐
│ CLI / demo  │────▶│  DataProfiler    │────▶│  Persistence       │
│ config YAML │     │  (orchestrator)  │     │  JSON/YAML/Parquet │
└─────────────┘     └────────┬─────────┘     │  + resume state    │
                             │               └────────────────────┘
                             ▼
                    ┌──────────────────┐
                    │ DatabaseAdapter  │
                    │  list_tables     │
                    │  get_columns     │
                    │  get_row_count   │
                    │  profile_stats   │
                    └────────┬─────────┘
           ┌─────────┬───────┴───────┬──────────┐
           ▼         ▼               ▼          ▼
        SQLite    DuckDB        Snowflake   Databricks
```

### Responsibilities

| Layer | Owns |
|---|---|
| `ProfilerConfig` | Accuracy/speed knobs (sample size, concurrency, stats depth, filters, resume path) |
| `DatabaseAdapter` | Dialect, catalog SQL, sampling syntax, approx distinct, quoting |
| `DataProfiler` | Discovery → parallel workers → error isolation → metrics |
| `models` + `schema/profile_schema.json` | Portable document shape |
| `type_mapping` | Native type strings → shared `TypeKind` |
| `persistence` | Serialization + crash-resume checkpoints |

## Portable type schema

Engines disagree on type names (`NUMBER` vs `DECIMAL` vs `NUMERIC`,
`TIMESTAMP_NTZ` vs `DATETIME`, `VARIANT` vs `JSON`). Profiles store both:

- `type.kind` — stable enum (`integer`, `float`, `decimal`, `string`, …)
- `type.native` — original vendor string
- optional `precision` / `scale` / `max_length` / `nullable`

This lets consumers answer “are these columns comparable?” without writing
per-engine switch statements. The JSON Schema lives in
`schema/profile_schema.json` and matches `ProfileDocument.to_dict()`.

## Statistics strategy

Per table:

1. Read column metadata from `information_schema` / `PRAGMA` / Unity Catalog.
2. Obtain row count (exact `COUNT(*)`, or Snowflake `SHOW TABLES` + `RESULT_SCAN`
   when `estimate_row_counts=true`).
3. Issue **one aggregated SELECT** for all columns (`MIN`/`MAX`/`COUNT`/`COUNT DISTINCT`
   or `APPROX_COUNT_DISTINCT` / `approx_count_distinct` on large/sampled scans).
4. Optionally compute numeric histograms (`stats_depth: full`).

### Sampling

| Engine | Mechanism |
|---|---|
| SQLite | `ORDER BY RANDOM() LIMIT n` (no native TABLESAMPLE) |
| DuckDB | `USING SAMPLE n` / `TABLESAMPLE SYSTEM (p)` |
| Snowflake | `TABLESAMPLE BERNOULLI (p)` / `TABLESAMPLE SYSTEM (n ROWS)` |
| Databricks | `TABLESAMPLE (p PERCENT)` / `TABLESAMPLE (n ROWS)` |

When sampling, null ratios are scaled to the full table row count; distinct
counts are marked `distinct_count_is_estimate=true`.

## Parallelism & resilience

- Tables are the unit of parallelism (`concurrency` workers).
- SQLite and DuckDB are forced sequential (shared local connections are not
  safely concurrent). Snowflake/Databricks open cursors per call and can run
  tables in parallel.
- Per-table failures are captured in `table.error`; other tables continue unless
  `fail_fast=true`.
- Optional `resume_state_path` checkpoints each completed table as JSON so a
  crashed run can skip work already done.

## Configurability knobs

See `examples/config.yaml`. Important trades:

- Larger `sample_size` / `sample_percent` → better accuracy, higher warehouse cost.
- `stats_depth: full` → histograms (extra queries per numeric column).
- `estimate_row_counts` → cheaper discovery on Snowflake; slightly stale counts.
- `max_tables` / schema filters → keep first runs bounded on large catalogs.

## Assumptions & privileges

**Common**

- Network reachability to the warehouse endpoint.
- `SELECT` on profiled tables; read access to catalog metadata.

**Snowflake**

- Preferred: role with `USAGE` on warehouse/database/schema and
  `SELECT` on tables; `INFORMATION_SCHEMA` read.
- `estimate_row_counts` uses `SHOW TABLES` + `RESULT_SCAN(LAST_QUERY_ID())`
  (documented Snowflake pattern). Counts can lag recently loaded data.
- Large-table distinct uses `APPROX_COUNT_DISTINCT` (HLL).

**Databricks**

- SQL warehouse token with access to target catalogs.
- Prefers `system.information_schema` (Unity Catalog); falls back to local
  `information_schema`.
- Large-table distinct uses `approx_count_distinct`.

**DuckDB / SQLite**

- Local file (or `:memory:`) access only; no network.
- Used for demos, CI, and adapter contract tests.

## Correctness & performance validation

1. **Unit tests** (`tests/test_type_mapping.py`) — cross-engine type normalization.
2. **Integration tests** (`tests/test_profiler.py`) — seed tiny SQLite/DuckDB DBs,
   assert min/max/null/distinct/histograms and portable schema version.
3. **Adapter SQL unit checks** — Snowflake/Databricks approx + sample clause builders
   without live credentials.
4. **Demo script** (`demos/run_demo.py`) — seeds ~7.5k rows across 5 tables, profiles
   both local engines, prints metrics and type comparison.
5. **Runtime metrics** — each run records `elapsed_seconds`, per-table `duration_ms`,
   and success/failure counts in the profile document (structured logs via stdlib
   logging).

## Extending to a new engine

1. Subclass `DatabaseAdapter`.
2. Implement catalog + stats methods (reuse `sql_stats` helpers where SQL is close).
3. Register in `adapters/__init__.py`.
4. Add a fixture test or mock connection test for dialect-specific SQL.
