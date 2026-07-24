"""Integration-style tests against in-memory SQLite and DuckDB."""

from __future__ import annotations

import json
from pathlib import Path

import duckdb
import pytest

from data_profiler.adapters import create_adapter
from data_profiler.adapters.snowflake_adapter import SnowflakeAdapter
from data_profiler.adapters.databricks_adapter import DatabricksAdapter
from data_profiler.config import ProfilerConfig
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
    assert by_name["name"].stats.distinct_count == 3  # a,b,c (null excluded)
    assert out.exists()
    payload = json.loads(out.read_text())
    assert payload["schema_version"] == "1.0.0"


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
    assert sum(b.count for b in price.stats.histogram) == 3  # non-null prices


def test_resume_skips_completed(sqlite_db: Path, tmp_path: Path):
    from data_profiler.persistence import ResumeState

    state = tmp_path / "state.json"
    config = ProfilerConfig(
        sample_size=None,
        concurrency=1,
        resume_state_path=str(state),
    )
    adapter = create_adapter("sqlite", config, database=str(sqlite_db))
    first = DataProfiler(adapter, config).run(output_path=str(tmp_path / "a.json"))
    assert first.run["status"] == "completed"
    # Successful runs clear state; seed a checkpoint as if a prior partial run left it.
    ResumeState(state).mark(first.tables[0])
    assert state.exists()
    adapter2 = create_adapter("sqlite", config, database=str(sqlite_db))
    second = DataProfiler(adapter2, config).run()
    assert second.tables[0].row_count == 4
    assert second.tables[0].fully_qualified_name == first.tables[0].fully_qualified_name


def test_table_filters(sqlite_db: Path):
    import sqlite3

    conn = sqlite3.connect(sqlite_db)
    conn.execute("CREATE TABLE other (id INTEGER)")
    conn.execute("INSERT INTO other VALUES (1)")
    conn.commit()
    conn.close()

    config = ProfilerConfig(
        sample_size=None,
        include_tables=["items"],
        concurrency=1,
    )
    adapter = create_adapter("sqlite", config, database=str(sqlite_db))
    adapter.connect()
    try:
        tables = adapter.list_tables()
    finally:
        adapter.close()
    assert [t.name for t in tables] == ["items"]


def test_snowflake_adapter_builds_approx_sql():
    config = ProfilerConfig(sample_size=1000, sample_percent=None)
    adapter = SnowflakeAdapter(config, connection=object())
    from data_profiler.models import ColumnMeta, PortableType, TypeKind

    cols = [
        ColumnMeta(
            name="id",
            native_type="NUMBER",
            portable_type=PortableType(TypeKind.INTEGER, "NUMBER"),
        )
    ]
    sql, aliases = adapter._build_select(cols, use_approx=True)
    assert "APPROX_COUNT_DISTINCT" in sql
    assert ("id", "distinct") in aliases


def test_databricks_sample_clause():
    config = ProfilerConfig(sample_percent=5.0)
    adapter = DatabricksAdapter(config, connection=object())
    clause, sampled, _ = adapter._sample_clause(row_count=1_000_000)
    assert sampled
    assert "TABLESAMPLE" in clause
