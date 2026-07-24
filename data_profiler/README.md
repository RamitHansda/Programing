# Data Profiler

Staff-oriented, configurable profiler that scans tables across **Snowflake**,
**Databricks**, **DuckDB**, and **SQLite**, then persists a portable profile of
table/column metadata and statistics.

## Quick start

```bash
cd data_profiler
python3 -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"

pytest -q
python demos/run_demo.py

data-profiler --engine sqlite --database demos/data/demo.sqlite \
  --config examples/config.yaml -o demos/output/sqlite_profile.json -v
```

## Cloud engines

```bash
pip install -e ".[snowflake,databricks]"

# Prefer env vars for secrets (never committed)
export SNOWFLAKE_ACCOUNT=... SNOWFLAKE_USER=... SNOWFLAKE_PASSWORD=...
export SNOWFLAKE_WAREHOUSE=... SNOWFLAKE_DATABASE=...
data-profiler --engine snowflake --config examples/config.yaml -o snowflake.json
```

## What you get

| Scope | Fields |
|---|---|
| Table | row count (exact/estimate), schema, comments, sampling metadata, duration |
| Column | portable + native type, min/max, nulls, distinct (exact/approx), optional histograms |
| Run | redacted config, metrics, status, resume fingerprint |

Outputs: JSON (source of truth), YAML, or Parquet (analytics flatten + run metadata).

## Architecture (short)

- **Adapters** isolate dialect; **`SqlProfilingMixin`** owns the shared stats plan.
- **`supports_concurrent_profiling`** declares thread-safety; locals stay sequential.
- **Resume** checkpoints *successful* tables only; failures remain retryable.
- **Budgets**: sample size, per-table timeout, histogram cap, max tables, retries.
- **Portable types**: `NUMBER(38,0)` → `integer` for cross-engine comparison.

Deep dive: [DESIGN.md](DESIGN.md). Schema: `schema/profile_schema.json`.

## Layout

```
data_profiler/
  DESIGN.md
  schema/profile_schema.json
  src/data_profiler/
    adapters/          # base, mixin, sqlite, duckdb, snowflake, databricks
    profiler.py        # orchestrator
    persistence.py     # output + resume
    observability.py   # structured logs + redaction
    ...
  demos/  examples/  tests/
```
