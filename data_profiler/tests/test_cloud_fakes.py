"""Fake-cursor contract tests for cloud adapter SQL paths."""

from __future__ import annotations

from data_profiler.adapters.databricks_adapter import DatabricksAdapter
from data_profiler.adapters.snowflake_adapter import SnowflakeAdapter
from data_profiler.adapters.sql_stats import build_stats_select
from data_profiler.config import ProfilerConfig
from data_profiler.models import ColumnMeta, PortableType, TableRef, TypeKind


class FakeCursor:
    def __init__(self, results):
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


def test_snowflake_list_tables_uses_information_schema():
    cur = FakeCursor(
        results=[
            [("DB", "PUBLIC", "ORDERS"), ("DB", "PUBLIC", "USERS")],
        ]
    )
    adapter = SnowflakeAdapter(ProfilerConfig(max_tables=1), connection=FakeConn(cur))
    adapter.connect()
    tables = adapter.list_tables()
    assert len(tables) == 1
    assert "information_schema.tables" in cur.statements[0][0].lower()


def test_snowflake_get_columns_maps_number_to_integer():
    cur = FakeCursor(
        results=[
            [("ID", "NUMBER", "NO", 1, None), ("AMT", "NUMBER", "YES", 2, "money")],
        ]
    )
    # Force data_type strings through map — use NUMBER(38,0) style in rows.
    cur = FakeCursor(
        results=[
            [
                ("ID", "NUMBER(38,0)", "NO", 1, None),
                ("AMT", "NUMBER(18,2)", "YES", 2, "money"),
            ],
        ]
    )
    adapter = SnowflakeAdapter(ProfilerConfig(), connection=FakeConn(cur))
    adapter.connect()
    cols = adapter.get_columns(TableRef(name="ORDERS", schema="PUBLIC"))
    assert cols[0].portable_type.kind == TypeKind.INTEGER
    assert cols[1].portable_type.kind == TypeKind.DECIMAL
    assert cols[1].comment == "money"


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


def test_databricks_list_tables_falls_back():
    class FailThenSucceedCursor(FakeCursor):
        def __init__(self):
            super().__init__(results=[])
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
