"""Integration-style tests against local SQLite and DuckDB databases."""

from __future__ import annotations

import json
import sqlite3
from pathlib import Path

import duckdb
import pytest

from data_profiler.adapters import create_adapter
from data_profiler.adapters.base import SamplePlan, StatsResult
from data_profiler.config import ProfilerConfig
from data_profiler.errors import ConfigurationError
from data_profiler.models import TableProfile
from data_profiler.observability import config_fingerprint, redact
from data_profiler.persistence import ResumeState, validate_against_schema
from data_profiler.profiler import DataProfiler

ROWS = [
    (1, "a", 10.5, 3),
    (2, "b", None, 0),
    (3, "c", 2.0, 9),
    (4, None, 7.5, None),
]


@pytest.fixture
def sqlite_db(tmp_path: Path) -> Path:
    path = tmp_path / "t.sqlite"
    conn = sqlite3.connect(path)
    conn.execute(
        "CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT, price REAL, qty INTEGER)"
    )
    conn.executemany("INSERT INTO items VALUES (?, ?, ?, ?)", ROWS)
    conn.commit()
    conn.close()
    return path


@pytest.fixture
def duckdb_db(tmp_path: Path) -> Path:
    path = tmp_path / "t.duckdb"
    conn = duckdb.connect(str(path))
    conn.execute("CREATE TABLE items (id INTEGER, name VARCHAR, price DOUBLE, qty INTEGER)")
    conn.executemany("INSERT INTO items VALUES (?, ?, ?, ?)", ROWS)
    conn.close()
    return path


def _wide_sqlite(path: Path, rows: int) -> None:
    conn = sqlite3.connect(path)
    conn.execute("CREATE TABLE big (id INTEGER, label TEXT)")
    conn.executemany(
        "INSERT INTO big VALUES (?, ?)", [(i, f"l{i % 97}") for i in range(rows)]
    )
    conn.commit()
    conn.close()


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
    assert by_name["price"].stats.min_max_from_sample is False
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


@pytest.mark.parametrize("engine", ["sqlite", "duckdb"])
def test_sampling_actually_bounds_the_scan(engine: str, tmp_path: Path):
    """A sample clause tacked onto an aggregate query is a silent full scan.

    Regression test: min/max/nulls must be computed over sample_size rows, not
    over the whole table, and the document must say how many rows it saw.
    """
    if engine == "sqlite":
        path = tmp_path / "big.sqlite"
        _wide_sqlite(path, 5_000)
    else:
        path = tmp_path / "big.duckdb"
        conn = duckdb.connect(str(path))
        conn.execute("CREATE TABLE big (id INTEGER, label VARCHAR)")
        conn.execute("INSERT INTO big SELECT i, 'l' || (i % 97) FROM range(5000) t(i)")
        conn.close()

    config = ProfilerConfig(sample_size=100, concurrency=1, distinct_scope="sample")
    adapter = create_adapter(engine, config, database=str(path))
    table = DataProfiler(adapter, config).run().tables[0]

    assert table.row_count == 5_000, "row count must still describe the whole table"
    assert table.sampled is True
    assert table.sample_size == 100
    ident = next(c for c in table.columns if c.name == "id")
    assert ident.stats.sampled_rows == 100
    assert ident.stats.min_max_from_sample is True
    assert ident.stats.distinct_from_sample is True
    # Sample-scoped cardinality is bounded by the sample, modulo approximate
    # aggregates that can overshoot a little; it cannot approach the table's 5000.
    assert ident.stats.distinct_count < 200
    assert ident.stats.max < 4_999, "max over a 2% sample should not hit the true maximum"


@pytest.mark.parametrize("engine", ["sqlite", "duckdb"])
def test_distinct_scope_table_beats_sample(engine: str, tmp_path: Path):
    """Cardinality from a sample is a floor; distinct_scope=table measures the table."""
    if engine == "sqlite":
        path = tmp_path / "big.sqlite"
        _wide_sqlite(path, 5_000)
    else:
        path = tmp_path / "big.duckdb"
        conn = duckdb.connect(str(path))
        conn.execute("CREATE TABLE big (id INTEGER, label VARCHAR)")
        conn.execute("INSERT INTO big SELECT i, 'l' || (i % 97) FROM range(5000) t(i)")
        conn.close()

    config = ProfilerConfig(sample_size=100, concurrency=1, distinct_scope="table")
    adapter = create_adapter(engine, config, database=str(path))
    table = DataProfiler(adapter, config).run().tables[0]
    by_name = {c.name: c for c in table.columns}
    assert by_name["id"].stats.distinct_count == 5_000
    assert by_name["label"].stats.distinct_count == 97
    assert by_name["id"].stats.distinct_from_sample is False
    # min/max still come from the sample, and still say so.
    assert by_name["id"].stats.min_max_from_sample is True


@pytest.mark.parametrize("buckets", [2, 4, 9])
def test_histogram_bucket_count_matches_config(buckets: int, tmp_path: Path):
    """FLOOR bucketing puts the max value in an extra out-of-range bucket."""
    path = tmp_path / "h.duckdb"
    conn = duckdb.connect(str(path))
    conn.execute("CREATE TABLE t (v INTEGER)")
    conn.execute("INSERT INTO t SELECT i FROM range(100) s(i)")
    conn.close()

    config = ProfilerConfig(
        sample_size=None,
        concurrency=1,
        stats_depth="full",
        histogram_buckets=buckets,
    )
    adapter = create_adapter("duckdb", config, database=str(path))
    hist = DataProfiler(adapter, config).run().tables[0].columns[0].stats.histogram
    assert hist is not None
    assert len(hist) == buckets
    assert sum(b.count for b in hist) == 100


def test_histograms_survive_sampling(tmp_path: Path):
    """The sample clause used to be spliced in ahead of WHERE, so this failed silently."""
    path = tmp_path / "hs.duckdb"
    conn = duckdb.connect(str(path))
    conn.execute("CREATE TABLE t (v INTEGER)")
    conn.execute("INSERT INTO t SELECT i FROM range(5000) s(i)")
    conn.close()

    config = ProfilerConfig(
        sample_size=500,
        concurrency=1,
        stats_depth="full",
        histogram_buckets=5,
    )
    adapter = create_adapter("duckdb", config, database=str(path))
    column = DataProfiler(adapter, config).run().tables[0].columns[0]
    assert column.stats.histogram, "sampled runs must still produce histograms"
    assert sum(b.count for b in column.stats.histogram) == 500


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


def test_parquet_output_is_readable(sqlite_db: Path, tmp_path: Path):
    pq = pytest.importorskip("pyarrow.parquet")
    config = ProfilerConfig(sample_size=None, concurrency=1, output_format="parquet")
    adapter = create_adapter("sqlite", config, database=str(sqlite_db))
    out = tmp_path / "p.parquet"
    DataProfiler(adapter, config).run(output_path=str(out))
    table = pq.read_table(out)
    assert table.num_rows == 4  # one row per profiled column
    assert set(table.column_names) >= {"table_fqn", "column_name", "type_kind", "min", "max"}
    assert b"data_profiler_run_json" in table.schema.metadata


def test_redact_secrets():
    assert redact({"password": "x", "sample_size": 1})["password"] == "***REDACTED***"


def test_stats_result_is_typed_return():
    # Contract: profile_column_stats returns StatsResult, not a bare dict.
    assert StatsResult(stats={}).sample_rows == 0
    assert SamplePlan().sampled is False


def test_sample_plan_wraps_row_limits_in_a_derived_table():
    plain = SamplePlan()
    assert plain.source('"t"') == '"t"'
    suffixed = SamplePlan(table_suffix="TABLESAMPLE (10 PERCENT)", sampled=True)
    assert suffixed.source("`t`") == "`t` TABLESAMPLE (10 PERCENT)"
    limited = SamplePlan(row_limit="ORDER BY RANDOM() LIMIT 5", sampled=True)
    assert limited.source('"t"') == '(SELECT * FROM "t" ORDER BY RANDOM() LIMIT 5) AS __dp_sample'
    assert "SELECT \"c\" FROM" in limited.source('"t"', projection='"c"')


def test_invalid_engine():
    with pytest.raises(ConfigurationError):
        create_adapter("postgres", ProfilerConfig())
