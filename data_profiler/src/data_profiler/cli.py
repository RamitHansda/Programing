"""CLI entrypoint for the data profiler."""

from __future__ import annotations

import argparse
import json
import logging
import os
import sys
from pathlib import Path

import yaml

from data_profiler.adapters import create_adapter
from data_profiler.config import ProfilerConfig
from data_profiler.profiler import DataProfiler


def _build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="data-profiler",
        description="Profile table/column metadata and statistics across databases.",
    )
    p.add_argument(
        "--engine",
        required=True,
        choices=["sqlite", "duckdb", "snowflake", "databricks"],
        help="Database engine",
    )
    p.add_argument("--database", help="Path/name for sqlite or duckdb database")
    p.add_argument("--config", help="YAML config file (profiler knobs + connection)")
    p.add_argument("--output", "-o", default="profile.json", help="Output path")
    p.add_argument(
        "--format",
        choices=["json", "yaml", "parquet"],
        default=None,
        help="Output format (overrides config)",
    )
    p.add_argument("--sample-size", type=int, default=None)
    p.add_argument("--sample-percent", type=float, default=None)
    p.add_argument("--concurrency", type=int, default=None)
    p.add_argument("--stats-depth", choices=["basic", "full"], default=None)
    p.add_argument("--max-tables", type=int, default=None)
    p.add_argument("--resume-state", default=None, help="Checkpoint file for resume")
    p.add_argument("--include-schema", action="append", default=[])
    p.add_argument("--exclude-schema", action="append", default=[])
    p.add_argument("-v", "--verbose", action="store_true")
    return p


def _load_file_config(path: str | None) -> dict:
    if not path:
        return {}
    with open(path, encoding="utf-8") as fh:
        return yaml.safe_load(fh) or {}


def main(argv: list[str] | None = None) -> int:
    args = _build_parser().parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )

    file_cfg = _load_file_config(args.config)
    profiler_raw = dict(file_cfg.get("profiler") or {})
    connection = dict(file_cfg.get("connection") or {})

    # CLI overrides
    if args.sample_size is not None:
        profiler_raw["sample_size"] = args.sample_size
    if args.sample_percent is not None:
        profiler_raw["sample_percent"] = args.sample_percent
    if args.concurrency is not None:
        profiler_raw["concurrency"] = args.concurrency
    if args.stats_depth is not None:
        profiler_raw["stats_depth"] = args.stats_depth
    if args.max_tables is not None:
        profiler_raw["max_tables"] = args.max_tables
    if args.resume_state is not None:
        profiler_raw["resume_state_path"] = args.resume_state
    if args.format is not None:
        profiler_raw["output_format"] = args.format
    if args.include_schema:
        profiler_raw["include_schemas"] = args.include_schema
    if args.exclude_schema:
        profiler_raw["exclude_schemas"] = args.exclude_schema

    config = ProfilerConfig.from_dict(profiler_raw)

    engine = args.engine
    conn_kwargs = dict(connection)
    # Prefer CLI database path for local engines.
    if args.database:
        conn_kwargs["database"] = args.database
    # Environment variable fallbacks for cloud engines.
    if engine == "snowflake":
        conn_kwargs.setdefault("account", os.getenv("SNOWFLAKE_ACCOUNT"))
        conn_kwargs.setdefault("user", os.getenv("SNOWFLAKE_USER"))
        conn_kwargs.setdefault("password", os.getenv("SNOWFLAKE_PASSWORD"))
        conn_kwargs.setdefault("warehouse", os.getenv("SNOWFLAKE_WAREHOUSE"))
        conn_kwargs.setdefault("database", os.getenv("SNOWFLAKE_DATABASE"))
        conn_kwargs.setdefault("schema", os.getenv("SNOWFLAKE_SCHEMA"))
        conn_kwargs.setdefault("role", os.getenv("SNOWFLAKE_ROLE"))
    if engine == "databricks":
        conn_kwargs.setdefault("server_hostname", os.getenv("DATABRICKS_SERVER_HOSTNAME"))
        conn_kwargs.setdefault("http_path", os.getenv("DATABRICKS_HTTP_PATH"))
        conn_kwargs.setdefault("access_token", os.getenv("DATABRICKS_TOKEN"))
        conn_kwargs.setdefault("catalog", os.getenv("DATABRICKS_CATALOG"))
        conn_kwargs.setdefault("schema", os.getenv("DATABRICKS_SCHEMA"))

    if engine in {"sqlite", "duckdb"} and "database" not in conn_kwargs:
        print(
            f"--database is required for {engine} (or set connection.database in config)",
            file=sys.stderr,
        )
        return 2

    adapter = create_adapter(engine, config, **conn_kwargs)
    profiler = DataProfiler(adapter, config)
    doc = profiler.run(output_path=args.output)

    summary = {
        "run_id": doc.run["run_id"],
        "status": doc.run["status"],
        "metrics": doc.run["metrics"],
        "output": str(Path(args.output).resolve()),
    }
    print(json.dumps(summary, indent=2))
    return 0 if doc.run["status"] != "failed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
