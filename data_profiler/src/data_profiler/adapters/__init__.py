"""Adapter factory / registry."""

from __future__ import annotations

from typing import Any

from data_profiler.adapters.base import DatabaseAdapter
from data_profiler.adapters.databricks_adapter import DatabricksAdapter
from data_profiler.adapters.duckdb_adapter import DuckDBAdapter
from data_profiler.adapters.snowflake_adapter import SnowflakeAdapter
from data_profiler.adapters.sqlite_adapter import SQLiteAdapter
from data_profiler.config import ProfilerConfig
from data_profiler.errors import ConfigurationError

ADAPTERS = {
    "sqlite": SQLiteAdapter,
    "duckdb": DuckDBAdapter,
    "snowflake": SnowflakeAdapter,
    "databricks": DatabricksAdapter,
}


def create_adapter(engine: str, config: ProfilerConfig, **kwargs: Any) -> DatabaseAdapter:
    key = engine.lower().strip()
    if key not in ADAPTERS:
        raise ConfigurationError(
            f"Unsupported engine '{engine}'. Choose from: {sorted(ADAPTERS)}"
        )
    return ADAPTERS[key](config, **kwargs)
