"""End-to-end demo: seed local DBs, profile them, print summaries."""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))
sys.path.insert(0, str(ROOT))

from data_profiler.adapters import create_adapter  # noqa: E402
from data_profiler.config import ProfilerConfig  # noqa: E402
from data_profiler.profiler import DataProfiler  # noqa: E402
from demos.seed_demo import seed_duckdb, seed_sqlite  # noqa: E402


def profile_engine(engine: str, database: Path, output: Path, **cfg_kwargs) -> dict:
    config = ProfilerConfig(
        sample_size=10_000,
        concurrency=2,
        stats_depth="full",
        histogram_buckets=5,
        resume_state_path=str(output.with_suffix(".state.json")),
        output_format="json",
        **cfg_kwargs,
    )
    adapter = create_adapter(engine, config, database=str(database))
    doc = DataProfiler(adapter, config).run(output_path=str(output))
    return {
        "engine": engine,
        "status": doc.run["status"],
        "metrics": doc.run["metrics"],
        "tables": [
            {
                "name": t.fully_qualified_name,
                "rows": t.row_count,
                "columns": len(t.columns),
                "duration_ms": t.duration_ms,
            }
            for t in doc.tables
        ],
        "output": str(output),
    }


def main() -> None:
    data_dir = Path(__file__).resolve().parent / "data"
    out_dir = Path(__file__).resolve().parent / "output"
    out_dir.mkdir(parents=True, exist_ok=True)

    sqlite_path = data_dir / "demo.sqlite"
    duckdb_path = data_dir / "demo.duckdb"
    seed_sqlite(sqlite_path)
    seed_duckdb(duckdb_path)

    summaries = [
        profile_engine("sqlite", sqlite_path, out_dir / "sqlite_profile.json"),
        profile_engine("duckdb", duckdb_path, out_dir / "duckdb_profile.json"),
    ]
    print(json.dumps(summaries, indent=2))
    print("\nPortable type comparison sample (orders.amount / products.price):")
    for path, table, col in [
        (out_dir / "sqlite_profile.json", "orders", "amount"),
        (out_dir / "duckdb_profile.json", "products", "price"),
    ]:
        doc = json.loads(path.read_text())
        for t in doc["tables"]:
            if t["name"] == table or t["name"].endswith("." + table):
                for c in t["columns"]:
                    if c["name"] == col:
                        engine = doc["run"]["engine"]
                        print(f"  {engine}.{t['fully_qualified_name']}.{col}: {c['type']}")


if __name__ == "__main__":
    main()
