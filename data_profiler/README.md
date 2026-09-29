# Data Profiler

Configurable multi-database profiling utility for **Snowflake**, **Databricks**,
**DuckDB**, and **SQLite**. Scans tables and writes a portable JSON/YAML/Parquet
profile of table/column metadata and statistics.

Architecture and trade-offs: [DESIGN.md](DESIGN.md)

---

## Quick start

Requires Python 3.9+ (3.10–3.12 recommended).

```bash
cd data_profiler
python3 -m venv .venv && source .venv/bin/activate   # Windows: .\.venv\Scripts\Activate.ps1
python -m pip install -U pip setuptools wheel
pip install -e ".[dev]"
```

Seed two local databases and profile them end to end:

```bash
python demos/run_demo.py
```

Both engines should report `"status": "completed"` and write
`demos/output/sqlite_profile.json` and `demos/output/duckdb_profile.json`.

Tests and lint:

```bash
pytest -q
ruff check src tests demos
```

Cost model on a synthetic 200-table catalog:

```bash
python demos/benchmark.py --tables 200 --columns 10 --rows 20000
```

---

## CLI usage

```bash
# Seed demo DBs only (if you skipped run_demo.py)
python demos/seed_demo.py

# Profile SQLite with the example config
data-profiler --engine sqlite \
  --database demos/data/demo.sqlite \
  --config examples/config.yaml \
  -o demos/output/sqlite_profile.json -v

# Profile DuckDB with histograms
data-profiler --engine duckdb \
  --database demos/data/demo.duckdb \
  --stats-depth full \
  -o demos/output/duckdb_profile.json -v
```

### Accuracy vs. speed knobs

| Flag | Meaning |
|---|---|
| `--engine` | `sqlite` \| `duckdb` \| `snowflake` \| `databricks` |
| `--database` | Path for sqlite / duckdb files |
| `--config` | YAML knobs + connection block (`examples/config.yaml`) |
| `--sample-size` | Rows scanned for min/max/null stats (omit sampling with `sample_size: null`) |
| `--sample-percent` | Percentage sampling instead of a fixed row count |
| `--distinct-scope` | `table` (accurate cardinality) or `sample` (cheaper, lower bound) |
| `--stats-depth` | `basic` or `full` (adds histograms; the most expensive option) |
| `--concurrency` | Parallel table workers (cloud engines) |
| `--timeout-per-table` | Wall-clock budget per table; the in-flight query is cancelled |
| `--max-tables` | Cap discovery on huge catalogs |
| `--resume-state` | Checkpoint file; a re-run skips already-profiled tables |
| `-o` / `--output`, `--format` | Output path and `json` \| `yaml` \| `parquet` |
| `-v` | Verbose structured logs |

### Reading the output

Statistics say where they came from, which matters as soon as sampling is on:

```json
{
  "name": "order_id",
  "type": { "kind": "integer", "native": "NUMBER(38,0)", "nullable": false },
  "stats": {
    "min": 1042, "max": 998304,
    "min_max_from_sample": true,
    "null_count": 0, "null_ratio": 0.0,
    "distinct_count": 4821004,
    "distinct_count_is_estimate": true,
    "distinct_from_sample": false,
    "sampled_rows": 100000
  }
}
```

- `min_max_from_sample: true` — the true range is at least this wide.
- `distinct_from_sample: false` — cardinality was measured over the whole table
  (HLL when the engine supports it), so it is not capped by the sample size.
- Table-level `row_count` always describes the table, never the sample.

Portable schema: `src/data_profiler/schema/profile_schema.json`.

---

## Snowflake / Databricks

```bash
pip install -e ".[snowflake,databricks]"
```

Set secrets via environment variables (preferred over YAML — anything in the
config file is redacted from the output document, but env vars keep it off disk):

```bash
# Snowflake
export SNOWFLAKE_ACCOUNT=... SNOWFLAKE_USER=... SNOWFLAKE_PASSWORD=...
export SNOWFLAKE_WAREHOUSE=... SNOWFLAKE_DATABASE=...
data-profiler --engine snowflake --config examples/config.yaml -o snowflake.json

# Databricks
export DATABRICKS_SERVER_HOSTNAME=... DATABRICKS_HTTP_PATH=... DATABRICKS_TOKEN=...
data-profiler --engine databricks --config examples/config.yaml -o databricks.json
```

Windows PowerShell uses `$env:SNOWFLAKE_ACCOUNT="..."` instead of `export`.

Required privileges and engine-specific caveats (Snowflake `INFORMATION_SCHEMA`
scope and row-count staleness, Unity Catalog fallback) are documented in
[DESIGN.md](DESIGN.md#assumptions--privileges).

---

## Project layout

```
data_profiler/
  README.md                 ← you are here
  DESIGN.md                 ← architecture, cost model, assumptions
  examples/config.yaml
  demos/                    ← seed, end-to-end demo, benchmark
  src/data_profiler/        ← library + CLI
    schema/                 ← portable profile JSON Schema
  tests/
```
