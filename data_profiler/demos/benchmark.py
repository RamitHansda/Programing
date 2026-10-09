"""Benchmark the profiler across config knobs on a synthetic wide catalog.

Produces the numbers quoted in DESIGN.md. DuckDB stands in for a warehouse: the
absolute times are not warehouse times, but the *shape* of the cost model
(sampling, stats depth, distinct scope, catalog prefetch) is the same, and it
runs anywhere without credentials.

    python demos/benchmark.py --tables 200 --columns 10 --rows 20000
"""

from __future__ import annotations

import argparse
import shutil
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

import duckdb  # noqa: E402

from data_profiler.adapters import create_adapter  # noqa: E402
from data_profiler.config import ProfilerConfig  # noqa: E402
from data_profiler.profiler import DataProfiler  # noqa: E402

SCENARIOS: list[tuple[str, dict]] = [
    ("exact, no sampling", {"sample_size": None, "stats_depth": "basic"}),
    ("sample 1k, distinct=sample", {"sample_size": 1000, "distinct_scope": "sample"}),
    ("sample 1k, distinct=table", {"sample_size": 1000, "distinct_scope": "table"}),
    ("sample 1k + histograms", {"sample_size": 1000, "stats_depth": "full"}),
    ("no catalog prefetch", {"sample_size": 1000, "prefetch_catalog": False}),
]


def seed(path: Path, tables: int, columns: int, rows: int) -> None:
    conn = duckdb.connect(str(path))
    for t in range(tables):
        cols = ", ".join(f"c{c} INTEGER" for c in range(columns))
        conn.execute(f"CREATE TABLE t{t} ({cols})")
        projection = ", ".join(f"(i * {c + 1}) % {rows // 2 + 1}" for c in range(columns))
        conn.execute(f"INSERT INTO t{t} SELECT {projection} FROM range({rows}) s(i)")
    conn.close()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tables", type=int, default=200)
    parser.add_argument("--columns", type=int, default=10)
    parser.add_argument("--rows", type=int, default=20_000)
    args = parser.parse_args()

    workdir = Path(tempfile.mkdtemp(prefix="dp-bench-"))
    db = workdir / "bench.duckdb"
    try:
        t0 = time.perf_counter()
        seed(db, args.tables, args.columns, args.rows)
        cells = args.tables * args.columns * args.rows
        print(
            f"seeded {args.tables} tables x {args.columns} columns x {args.rows} rows "
            f"({cells:,} cells) in {time.perf_counter() - t0:.1f}s\n"
        )
        print(f"{'scenario':<30} {'seconds':>8} {'tables/s':>9}  status")
        print("-" * 62)
        for label, kwargs in SCENARIOS:
            config = ProfilerConfig(concurrency=1, **kwargs)
            adapter = create_adapter("duckdb", config, database=str(db))
            started = time.perf_counter()
            doc = DataProfiler(adapter, config).run()
            elapsed = time.perf_counter() - started
            rate = doc.run["metrics"]["tables_profiled"] / elapsed
            print(f"{label:<30} {elapsed:>8.2f} {rate:>9.1f}  {doc.run['status']}")
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


if __name__ == "__main__":
    main()
