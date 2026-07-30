# Data Profiler

Configurable multi-database profiling utility for **Snowflake**, **Databricks**,
**DuckDB**, and **SQLite**. Scans tables and writes a portable JSON/YAML profile
of table/column metadata and statistics.

Architecture details: [DESIGN.md](DESIGN.md)

---

## Run on a fresh machine

### Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Git | any recent | to clone the repo |
| Python | **3.9+** (3.10–3.12 recommended) | macOS system Python is often 3.9 — that works |

Check your Python:

```bash
python3 --version
```

Optional (macOS) — install a newer Python:

```bash
brew install python@3.12
```

### 1) Clone

```bash
git clone -b cursor/take-home-data-profiler-212b \
  https://github.com/RamitHansda/Programing.git
cd Programing/data_profiler
```

Or, if you already have the repo:

```bash
cd Programing/data_profiler
git fetch origin
git checkout cursor/take-home-data-profiler-212b
git pull
```

### 2) Create a virtual environment

**macOS / Linux**

```bash
python3 -m venv .venv
source .venv/bin/activate
```

**Windows (PowerShell)**

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
```

Confirm the venv is active (`which python` / `where python` should point inside `.venv`).

### 3) Install dependencies

Always upgrade packaging tools first (avoids editable-install errors on older pip):

```bash
python -m pip install -U pip setuptools wheel
pip install -e ".[dev]"
```

If `pip install -e .` still fails:

```bash
pip install ".[dev]"
# or run without installing:
#   export PYTHONPATH=src   (Windows: set PYTHONPATH=src)
```

### 4) Run the demo

Seeds local SQLite + DuckDB databases and profiles them end-to-end:

```bash
python demos/run_demo.py
```

Expected: both engines report `"status": "completed"`.  
Outputs: `demos/output/sqlite_profile.json` and `demos/output/duckdb_profile.json`.

### 5) Run tests

```bash
pytest -q
```

---

## CLI usage

```bash
# Seed demo DBs only (if you skipped run_demo.py)
python demos/seed_demo.py

# Profile SQLite
data-profiler --engine sqlite \
  --database demos/data/demo.sqlite \
  --config examples/config.yaml \
  -o demos/output/sqlite_profile.json -v

# Profile DuckDB
data-profiler --engine duckdb \
  --database demos/data/demo.duckdb \
  --stats-depth full \
  -o demos/output/duckdb_profile.json -v
```

### Useful flags

| Flag | Meaning |
|---|---|
| `--engine` | `sqlite` \| `duckdb` \| `snowflake` \| `databricks` |
| `--database` | Path for sqlite / duckdb files |
| `--config` | YAML knobs + connection block (`examples/config.yaml`) |
| `--sample-size` | Max rows used for column stats |
| `--stats-depth` | `basic` or `full` (enables histograms) |
| `--concurrency` | Parallel table workers (cloud engines) |
| `-o` / `--output` | Output path (`.json` or `.yaml`) |
| `-v` | Verbose structured logs |

---

## Snowflake / Databricks (optional)

```bash
pip install -e ".[snowflake,databricks]"
```

Set secrets via environment variables (preferred over YAML):

```bash
# Snowflake
export SNOWFLAKE_ACCOUNT=...
export SNOWFLAKE_USER=...
export SNOWFLAKE_PASSWORD=...
export SNOWFLAKE_WAREHOUSE=...
export SNOWFLAKE_DATABASE=...
data-profiler --engine snowflake --config examples/config.yaml -o snowflake.json

# Databricks
export DATABRICKS_SERVER_HOSTNAME=...
export DATABRICKS_HTTP_PATH=...
export DATABRICKS_TOKEN=...
data-profiler --engine databricks --config examples/config.yaml -o databricks.json
```

Windows PowerShell uses `$env:SNOWFLAKE_ACCOUNT="..."` instead of `export`.

---

## Troubleshooting

| Error | Fix |
|---|---|
| `requires a different Python: 3.9.x not in '>=3.10'` | Pull latest branch (`requires-python >=3.9`) or install Python 3.10+ |
| `setup.py or setup.cfg not found` / editable mode error | `python -m pip install -U pip setuptools wheel` then retry |
| `data-profiler: command not found` | Activate `.venv`, or re-run `pip install -e .` |
| DuckDB / package build fails on old OS | Use Python 3.10–3.12 from Homebrew / pyenv |

---

## Project layout

```
data_profiler/
  README.md                 ← you are here
  DESIGN.md                 ← architecture & assumptions
  examples/config.yaml
  schema/profile_schema.json
  demos/                    ← seed + end-to-end demo
  src/data_profiler/        ← library + CLI
  tests/
```
