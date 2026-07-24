# Data Profiler

Configurable utility that scans tables across **Snowflake**, **Databricks**,
**DuckDB**, and **SQLite**, then persists a compact, portable profile of
table/column metadata and statistics.

## Quick start (local demo)

```bash
cd data_profiler
python3 -m venv .venv
source .venv/bin/activate
pip install -e ".[dev]"

# Seed demo DBs + profile SQLite & DuckDB end-to-end
python demos/run_demo.py

# Or via CLI
python demos/seed_demo.py
data-profiler --engine sqlite --database demos/data/demo.sqlite \
  --config examples/config.yaml -o demos/output/sqlite_profile.json -v

data-profiler --engine duckdb --database demos/data/demo.duckdb \
  --sample-size 5000 --stats-depth full -o demos/output/duckdb_profile.json
```

Run tests:

```bash
pytest -q
```

## Cloud engines

Install extras and supply connection settings via YAML and/or env vars:

```bash
pip install -e ".[snowflake,databricks]"

export SNOWFLAKE_ACCOUNT=...
export SNOWFLAKE_USER=...
export SNOWFLAKE_PASSWORD=...
export SNOWFLAKE_WAREHOUSE=...
export SNOWFLAKE_DATABASE=...
data-profiler --engine snowflake --config examples/config.yaml -o snowflake.json

export DATABRICKS_SERVER_HOSTNAME=...
export DATABRICKS_HTTP_PATH=...
export DATABRICKS_TOKEN=...
data-profiler --engine databricks --config examples/config.yaml -o databricks.json
```

## What gets collected

**Per table:** row count (exact or estimated), full schema (names, portable +
native types, nullability, comments), sampling metadata, duration.

**Per column:** min, max, null count/ratio, distinct count (exact or approx),
optional histograms (`stats_depth: full`).

Output formats: JSON (default), YAML, or Parquet (`pip install -e ".[parquet]"`).

The portable document schema is defined in `schema/profile_schema.json`.

## Project layout

```
data_profiler/
  DESIGN.md                 # architecture & assumptions
  schema/profile_schema.json
  src/data_profiler/
    adapters/               # sqlite, duckdb, snowflake, databricks
    config.py
    models.py
    type_mapping.py
    profiler.py
    persistence.py
    cli.py
  demos/                    # seed + e2e demo
  examples/config.yaml
  tests/
```

## Design summary

- **Adapters** isolate dialect and catalog access behind one interface.
- **Orchestrator** lists tables, profiles with configurable concurrency, isolates
  per-table errors, and can **resume** from a checkpoint file.
- **Portable types** map vendor strings into a shared `TypeKind` so profiles from
  different engines are comparable.
- **Knobs** trade accuracy vs speed: sample size/percent, concurrency, stats depth,
  schema/table filters, estimate row counts.

See [DESIGN.md](DESIGN.md) for details.
