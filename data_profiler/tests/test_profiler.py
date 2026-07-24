"""Integration-style tests against in-memory SQLite and DuckDB."""

from __future__ import annotations

import json
from pathlib import Path

import duckdb
import pytest

from data_profiler.adapters import create_adapter
from data_profiler.adapters.base import SamplePlan, StatsResult
from data_profiler.adapters.databricks_adapter import DatabricksAdapter
from data_profiler.adapters.snowflake_adapter import SnowflakeAdapter
from data_profiler.config import ProfilerConfig
from data_profiler.errors import ConfigurationError
from data_profiler.models import ColumnMeta, PortableType, TypeKind
from data_profiler.observability import config_fingerprint, redact
from data_profiler.persistence import ResumeState, validate_against_schema
from data_profiler.profiler import DataProfiler


@pytest.fixture
def sqlite_db(tmp_path: Path) -> Path:
    import sqlite3

    path = tmp_path / "t.sqlite"
    conn = sqlite3.connect(path)
    conn.executescript(
        """
        CREATE TABLE items (
          id INTEGER PRIMARY KEY,
          name TEXT,
          price REAL,
          qty INTEGER
        );
        INSERT INTO items VALUES
          (1, 'a', 10.5, 3),
          (2, 'b', NULL, 0),
          (3, 'c', 2.0, 9),
          (4, NULL, 7.5, NULL);
        """
    )
    conn.commit()
    conn.close()
    return path


@pytest.fixture
def duckdb_db(tmp_path: Path) -> Path:
    path = tmp_path / "t.duckdb"
    conn = duckdb.connect(str(path))
    conn.execute(
        """
        CREATE TABLE items (
          id INTEGER,
          name VARCHAR,
          price DOUBLE,
          qty INTEGER
        );
        INSERT INTO items VALUES
          (1, 'a', 10.5, 3),
          (2, 'b', NULL, 0),
          (3, 'c', 2.0, 9),
          (4, NULL, 7.5, NULL);
        """
    )
    conn.close()
    return path


def test_sqlite_profile_basic(sqlite_db: Path, tmp_path: Path):
    config = ProfilerConfig(sample_size=None, concurrency=1, stats_depth="basic")
    adapter = create_adapter("sqlite", config, database=str(sqlite_db))
    out = tmp_path / "out.json"
    doc = DataProfiler(adapter, config).run(output_path=str(out))

    assert doc.run["status"] == "completed"
    assert len(doc.tables) == 1
    table = doc.tables[0]
    assert table.row_count == 4
    by_name = {c.name: c for c in table.columns}
    assert by_name["price"].stats.min == 2.0
    assert by_name["price"].stats.max == 10.5
    assert by_name["price"].stats.null_count == 1
    assert by_name["name"].stats.distinct_count == 3
    payload = json.loads(out.read_text())
    validate_against_schema(payload)


def test_duckdb_profile_with_histograms(duckdb_db: Path, tmp_path: Path):
    config = ProfilerConfig(
        sample_size=None,
        concurrency=1,
        stats_depth="full",
        histogram_buckets=4,
    )
    adapter = create_adapter("duckdb", config, database=str(duckdb_db))
    doc = DataProfiler(adapter, config).run(output_path=str(tmp_path / "duck.json"))
    assert doc.run["status"] == "completed"
    price = next(c for c in doc.tables[0].columns if c.name == "price")
    assert price.type.kind.value == "float"
    assert price.stats.histogram is not None
    assert sum(b.count for b in price.stats.histogram) == 3


def test_resume_skips_only_successes(sqlite_db: Path, tmp_path: Path):
    state = tmp_path / "state.json"
    config = ProfilerConfig(
        sample_size=None,
        concurrency=1,
        resume_state_path=str(state),
    )
    adapter = create_adapter("sqlite", config, database=str(sqlite_db))
    first = DataProfiler(adapter, config).run(output_path=str(tmp_path / "a.json"))
    assert first.run["status"] == "completed"

    # Seed success checkpoint as a prior partial run would.
    fp = config_fingerprint("sqlite", config.to_dict(), str(sqlite_db))
    ResumeState(state, fingerprint=fp).mark_success(first.tables[0])
    assert state.exists()

    adapter2 = create_adapter("sqlite", config, database=str(sqlite_db))
    second = DataProfiler(adapter2, config).run()
    assert second.tables[0].row_count == 4


def test_resume_failures_are_retried(sqlite_db: Path, tmp_path: Path):
    state = tmp_path / "state.json"
    config = ProfilerConfig(sample_size=None, concurrency=1, resume_state_path=str(state))
    fp = config_fingerprint("sqlite", config.to_dict(), str(sqlite_db))
    rs = ResumeState(state, fingerprint=fp)
    from data_profiler.models import TableProfile

    rs.mark_failure(
        TableProfile(
            catalog=None,
            schema="main",
            name="items",
            row_count=None,
            columns=[],
            error="timeout",
        )
    )
    adapter = create_adapter("sqlite", config, database=str(sqlite_db))
    doc = DataProfiler(adapter, config).run()
    assert doc.tables[0].error is None
    assert doc.tables[0].row_count == 4


def test_table_filters(sqlite_db: Path):
    import sqlite3

    conn = sqlite3.connect(sqlite_db)
    conn.execute("CREATE TABLE other (id INTEGER)")
    conn.execute("INSERT INTO other VALUES (1)")
    conn.commit()
    conn.close()

    config = ProfilerConfig(sample_size=None, include_tables=["items"], concurrency=1)
    adapter = create_adapter("sqlite", config, database=str(sqlite_db))
    adapter.connect()
    try:
        tables = adapter.list_tables()
    finally:
        adapter.close()
    assert [t.name for t in tables] == ["items"]


def test_snowflake_sample_skips_small_tables():
    config = ProfilerConfig(sample_size=1000)
    adapter = SnowflakeAdapter(config, connection=object())
    plan = adapter.build_sample_plan(
        __import__("data_profiler.models", fromlist=["TableRef"]).TableRef(name="T", schema="PUBLIC"),
        row_count=50,
    )
    assert plan.sampled is False
    assert plan.clause == ""


def test_snowflake_approx_distinct_hook():
    config = ProfilerConfig(sample_size=1000)
    adapter = SnowflakeAdapter(config, connection=object())
    assert "APPROX_COUNT_DISTINCT" in adapter.approx_distinct_expr('"ID"')


def test_databricks_quoting_and_sample():
    config = ProfilerConfig(sample_percent=5.0)
    adapter = DatabricksAdapter(config, connection=object())
    assert adapter.quote_ident("my`table") == "`my``table`"
    plan = adapter.build_sample_plan(
        __import__("data_profiler.models", fromlist=["TableRef"]).TableRef(name="t"),
        row_count=1_000_000,
    )
    assert plan.sampled and "TABLESAMPLE" in plan.clause


def test_redact_secrets():
    assert redact({"password": "x", "sample_size": 1})["password"] == "***REDACTED***"


def test_stats_result_is_typed_return():
    # Contract: profile_column_stats returns StatsResult, not a bare dict.
    assert StatsResult(stats={}).sample_rows == 0
    assert SamplePlan().sampled is False


def test_invalid_engine():
    with pytest.raises(ConfigurationError):
        create_adapter("postgres", ProfilerConfig())
