"""Contract tests for cloud adapter SQL paths, using fake cursors.

We cannot reach a warehouse from CI, so the generated SQL is parsed with sqlglot
against each engine's dialect. That is what a recorded-statement fake alone will
never tell you: ``SAMPLE SYSTEM (<n> ROWS)`` looks fine as a string and is
rejected by Snowflake.
"""

from __future__ import annotations

import pytest

from data_profiler.adapters.base import SamplePlan
from data_profiler.adapters.databricks_adapter import DatabricksAdapter
from data_profiler.adapters.snowflake_adapter import MAX_FIXED_SAMPLE_ROWS, SnowflakeAdapter
from data_profiler.adapters.sql_stats import (
    HistogramSpec,
    build_histogram_batch_sql,
    build_stats_select,
)
from data_profiler.config import ProfilerConfig
from data_profiler.models import ColumnMeta, PortableType, TableRef, TypeKind

sqlglot = pytest.importorskip("sqlglot")


class FakeCursor:
    def __init__(self, results=()):
        self.results = list(results)
        self.statements: list[tuple] = []

    def execute(self, sql, params=None):
        self.statements.append((sql, params))

    def fetchall(self):
        if not self.results:
            return []
        return self.results.pop(0)

    def fetchone(self):
        rows = self.fetchall()
        return rows[0] if rows else None

    def cancel(self):
        self.statements.append(("CANCEL", None))

    def close(self):
        pass


class FakeConn:
    def __init__(self, cursor: FakeCursor):
        self._cursor = cursor

    def cursor(self):
        return self._cursor


def _col(name: str, kind: TypeKind, native: str) -> ColumnMeta:
    return ColumnMeta(
        name=name,
        native_type=native,
        portable_type=PortableType(kind=kind, native=native),
    )


COLUMNS = [
    _col("ID", TypeKind.INTEGER, "NUMBER(38,0)"),
    _col("AMOUNT", TypeKind.DECIMAL, "NUMBER(18,2)"),
    _col("NAME", TypeKind.STRING, "VARCHAR(50)"),
]


def _stats_sql(adapter, plan: SamplePlan) -> str:
    select_sql, _ = build_stats_select(
        COLUMNS,
        adapter.config,
        quote_ident=adapter.quote_ident,
        distinct_expr=adapter.approx_distinct_expr,
    )
    table = TableRef(name="ORDERS", schema="PUBLIC", catalog="DB")
    return f"SELECT {select_sql} FROM {plan.source(adapter.qualify(table))}"


def _assert_parses(sql: str, dialect: str) -> None:
    try:
        sqlglot.parse_one(sql, read=dialect)
    except Exception as exc:  # noqa: BLE001
        pytest.fail(f"{dialect} cannot parse generated SQL: {exc}\n{sql}")


@pytest.mark.parametrize(
    ("factory", "dialect"),
    [
        (lambda cfg: SnowflakeAdapter(cfg, connection=FakeConn(FakeCursor())), "snowflake"),
        (lambda cfg: DatabricksAdapter(cfg, connection=FakeConn(FakeCursor())), "databricks"),
    ],
)
@pytest.mark.parametrize(
    "cfg_kwargs",
    [
        {"sample_size": None},
        {"sample_size": 1000},
        {"sample_percent": 5.0},
    ],
)
def test_generated_stats_sql_parses_in_dialect(factory, dialect, cfg_kwargs):
    config = ProfilerConfig(stats_depth="full", **cfg_kwargs)
    adapter = factory(config)
    plan = adapter.build_sample_plan(TableRef(name="ORDERS", schema="PUBLIC"), row_count=10_000_000)
    _assert_parses(_stats_sql(adapter, plan), dialect)
    source = plan.source(adapter.qualify(TableRef(name="ORDERS", schema="PUBLIC")))
    select_sql, _ = build_histogram_batch_sql(
        [
            HistogramSpec(column="ID", low=1, high=1000, buckets=4),
            HistogramSpec(column="AMOUNT", low=0.5, high=99.25, buckets=4),
        ],
        quote_ident=adapter.quote_ident,
        source=source,
    )
    _assert_parses(f"SELECT {select_sql} FROM {source}", dialect)


def test_snowflake_fixed_size_sampling_uses_bernoulli():
    """Snowflake rejects fixed-size sampling with SYSTEM/BLOCK."""
    config = ProfilerConfig(sample_size=1000)
    adapter = SnowflakeAdapter(config, connection=FakeConn(FakeCursor()))
    plan = adapter.build_sample_plan(TableRef(name="T", schema="PUBLIC"), row_count=10_000_000)
    assert plan.table_suffix == "SAMPLE BERNOULLI (1000 ROWS)"
    assert "SYSTEM" not in plan.table_suffix


def test_snowflake_percentage_sampling_prefers_block_sampling():
    config = ProfilerConfig(sample_percent=2.5)
    adapter = SnowflakeAdapter(config, connection=FakeConn(FakeCursor()))
    plan = adapter.build_sample_plan(TableRef(name="T", schema="PUBLIC"), row_count=None)
    assert plan.table_suffix == "SAMPLE SYSTEM (2.5)"


def test_snowflake_clamps_fixed_sample_to_engine_limit():
    config = ProfilerConfig(sample_size=5_000_000)
    adapter = SnowflakeAdapter(config, connection=FakeConn(FakeCursor()))
    plan = adapter.build_sample_plan(TableRef(name="T", schema="PUBLIC"), row_count=10_000_000)
    assert plan.sample_size == MAX_FIXED_SAMPLE_ROWS
    assert f"({MAX_FIXED_SAMPLE_ROWS} ROWS)" in plan.table_suffix


def test_snowflake_sample_skips_small_tables():
    config = ProfilerConfig(sample_size=1000)
    adapter = SnowflakeAdapter(config, connection=FakeConn(FakeCursor()))
    plan = adapter.build_sample_plan(TableRef(name="T", schema="PUBLIC"), row_count=50)
    assert plan.sampled is False
    assert plan.source('"T"') == '"T"'


def test_snowflake_approx_distinct_hook():
    config = ProfilerConfig(sample_size=1000)
    adapter = SnowflakeAdapter(config, connection=FakeConn(FakeCursor()))
    assert "APPROX_COUNT_DISTINCT" in adapter.approx_distinct_expr('"ID"')


def test_snowflake_list_tables_uses_information_schema():
    cur = FakeCursor(results=[[("DB", "PUBLIC", "ORDERS"), ("DB", "PUBLIC", "USERS")]])
    adapter = SnowflakeAdapter(ProfilerConfig(max_tables=1), connection=FakeConn(cur))
    adapter.connect()
    tables = adapter.list_tables()
    assert len(tables) == 1
    assert "information_schema.tables" in cur.statements[0][0].lower()


def test_snowflake_get_columns_maps_number_to_integer():
    cur = FakeCursor(
        results=[
            [
                ("ID", "NUMBER(38,0)", "NO", 1, None),
                ("AMT", "NUMBER(18,2)", "YES", 2, "money"),
            ]
        ]
    )
    adapter = SnowflakeAdapter(ProfilerConfig(), connection=FakeConn(cur))
    adapter.connect()
    cols = adapter.get_columns(TableRef(name="ORDERS", schema="PUBLIC"))
    assert cols[0].portable_type.kind == TypeKind.INTEGER
    assert cols[1].portable_type.kind == TypeKind.DECIMAL
    assert cols[1].comment == "money"


def test_snowflake_prefetch_replaces_per_table_catalog_queries():
    """Two catalog queries for the run, not two per table."""
    tables = [TableRef(catalog="DB", schema="PUBLIC", name=f"T{i}") for i in range(3)]
    columns = [
        ("DB", "PUBLIC", f"T{i}", "ID", "NUMBER(38,0)", "NO", 1, "pk") for i in range(3)
    ]
    table_meta = [("DB", "PUBLIC", f"T{i}", 1234 + i, f"table {i}") for i in range(3)]
    cur = FakeCursor(results=[columns, table_meta])
    adapter = SnowflakeAdapter(ProfilerConfig(estimate_row_counts=True), connection=FakeConn(cur))
    adapter.connect()
    adapter.prefetch_catalog(tables)
    assert len(cur.statements) == 2

    for i, table in enumerate(tables):
        cols = adapter.get_columns(table)
        assert [c.name for c in cols] == ["ID"]
        assert adapter.get_row_count(table) == (1234 + i, True)
        assert adapter.get_table_comment(table) == f"table {i}"
    assert len(cur.statements) == 2, "cached metadata must not re-query"


def test_snowflake_cancel_is_scoped_to_the_calling_thread():
    cur = FakeCursor()
    adapter = SnowflakeAdapter(ProfilerConfig(), connection=FakeConn(cur))
    adapter.connect()
    with adapter._cursor():
        adapter.cancel_active_query(thread_id=-1)
        assert ("CANCEL", None) not in cur.statements
        import threading

        adapter.cancel_active_query(thread_id=threading.get_ident())
        assert ("CANCEL", None) in cur.statements


def test_databricks_quoting_and_sample():
    config = ProfilerConfig(sample_percent=5.0)
    adapter = DatabricksAdapter(config, connection=FakeConn(FakeCursor()))
    assert adapter.quote_ident("my`table") == "`my``table`"
    plan = adapter.build_sample_plan(TableRef(name="t"), row_count=1_000_000)
    assert plan.sampled and "TABLESAMPLE" in plan.table_suffix


def test_databricks_list_tables_falls_back():
    class FailThenSucceedCursor(FakeCursor):
        def __init__(self):
            super().__init__()
            self.calls = 0

        def execute(self, sql, params=None):
            self.statements.append((sql, params))
            self.calls += 1
            if self.calls == 1 and "system.information_schema" in sql:
                raise RuntimeError("no unity catalog")
            self.results = [[("hive_metastore", "default", "t1")]]

    cur = FailThenSucceedCursor()
    adapter = DatabricksAdapter(ProfilerConfig(), connection=FakeConn(cur))
    adapter.connect()
    tables = adapter.list_tables()
    assert tables[0].name == "t1"
    assert any("information_schema.tables" in s[0].lower() for s in cur.statements)
    # A failed Unity probe must not be repeated for every later query.
    assert adapter._use_unity_catalog is False


def test_build_stats_select_pluggable_distinct():
    cols = [_col("id", TypeKind.INTEGER, "INT")]
    sql, aliases = build_stats_select(
        cols,
        ProfilerConfig(),
        quote_ident=lambda x: f'"{x}"',
        distinct_expr=lambda q: f"APPROX_COUNT_DISTINCT({q})",
    )
    assert "APPROX_COUNT_DISTINCT" in sql
    assert ("id", "distinct") in aliases


def test_build_stats_select_can_omit_distinct():
    cols = [_col("id", TypeKind.INTEGER, "INT")]
    sql, aliases = build_stats_select(
        cols,
        ProfilerConfig(),
        quote_ident=lambda x: f'"{x}"',
        include_distinct=False,
    )
    assert "DISTINCT" not in sql
    assert ("id", "distinct") not in aliases
