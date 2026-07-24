"""DuckDB adapter — local analytical engine with TABLESAMPLE support."""

from __future__ import annotations

from typing import Any, Sequence

import duckdb

from data_profiler.adapters.base import DatabaseAdapter, SamplePlan
from data_profiler.adapters.filters import filter_tables
from data_profiler.adapters.sql_profiling import SqlProfilingMixin
from data_profiler.config import ProfilerConfig
from data_profiler.models import ColumnMeta, TableRef
from data_profiler.type_mapping import map_native_type


class DuckDBAdapter(SqlProfilingMixin, DatabaseAdapter):
    engine_name = "duckdb"
    supports_concurrent_profiling = False

    def __init__(self, config: ProfilerConfig, database: str = ":memory:"):
        super().__init__(config)
        self.database = database
        self._conn: duckdb.DuckDBPyConnection | None = None

    def connect(self) -> None:
        self._conn = duckdb.connect(self.database)

    def close(self) -> None:
        if self._conn is not None:
            self._conn.close()
            self._conn = None

    @property
    def conn(self) -> duckdb.DuckDBPyConnection:
        if self._conn is None:
            raise RuntimeError("DuckDBAdapter is not connected")
        return self._conn

    def connection_hint(self) -> str | None:
        return self.database

    def list_tables(self) -> list[TableRef]:
        rows = self.conn.execute(
            """
            SELECT table_catalog, table_schema, table_name
            FROM information_schema.tables
            WHERE table_type = 'BASE TABLE'
              AND table_schema NOT IN ('information_schema', 'pg_catalog')
            ORDER BY table_schema, table_name
            """
        ).fetchall()
        return filter_tables(
            [TableRef(catalog=r[0], schema=r[1], name=r[2]) for r in rows],
            self.config,
        )

    def get_columns(self, table: TableRef) -> list[ColumnMeta]:
        rows = self.conn.execute(
            """
            SELECT column_name, data_type, is_nullable, ordinal_position
            FROM information_schema.columns
            WHERE table_name = ?
              AND (? IS NULL OR table_schema = ?)
            ORDER BY ordinal_position
            """,
            [table.name, table.schema, table.schema],
        ).fetchall()
        comments = self._column_comments(table)
        cols: list[ColumnMeta] = []
        for name, dtype, is_nullable, ordinal in rows:
            nullable = None if is_nullable is None else str(is_nullable).upper() == "YES"
            portable = map_native_type(dtype, nullable=nullable)
            cols.append(
                ColumnMeta(
                    name=name,
                    native_type=dtype,
                    portable_type=portable,
                    nullable=nullable,
                    comment=comments.get(name),
                    ordinal_position=int(ordinal) if ordinal is not None else None,
                )
            )
        return cols

    def _column_comments(self, table: TableRef) -> dict[str, str | None]:
        try:
            rows = self.conn.execute(
                """
                SELECT column_name, comment
                FROM duckdb_columns()
                WHERE table_name = ?
                  AND (? IS NULL OR schema_name = ?)
                """,
                [table.name, table.schema, table.schema],
            ).fetchall()
            return {r[0]: r[1] for r in rows}
        except Exception:
            return {}

    def get_row_count(self, table: TableRef) -> tuple[int | None, bool]:
        if self.config.skip_exact_count_when_sampling and (
            self.config.sample_size is not None or self.config.sample_percent is not None
        ):
            # Prefer a cheap exact count only when we are not sampling; otherwise
            # the stats query already returns COUNT(*) over the sample.
            plan = self.build_sample_plan(table, row_count=None)
            if plan.sampled:
                return None, True
        row = self.conn.execute(f"SELECT COUNT(*) FROM {self.qualify(table)}").fetchone()
        return int(row[0]), False

    def get_table_comment(self, table: TableRef) -> str | None:
        try:
            row = self.conn.execute(
                """
                SELECT comment FROM duckdb_tables()
                WHERE table_name = ?
                  AND (? IS NULL OR schema_name = ?)
                """,
                [table.name, table.schema, table.schema],
            ).fetchone()
            return row[0] if row else None
        except Exception:
            return None

    def build_sample_plan(self, table: TableRef, row_count: int | None) -> SamplePlan:
        cfg = self.config
        if cfg.sample_percent is not None:
            pct = min(100.0, max(0.0, cfg.sample_percent))
            return SamplePlan(
                clause=f"TABLESAMPLE SYSTEM ({pct})",
                sampled=True,
                sample_size=None,
                distinct_is_estimate=True,
            )
        if cfg.sample_size is not None:
            if row_count is not None and row_count <= cfg.sample_size:
                return SamplePlan()
            return SamplePlan(
                clause=f"USING SAMPLE {cfg.sample_size}",
                sampled=True,
                sample_size=cfg.sample_size,
                distinct_is_estimate=True,
            )
        return SamplePlan()

    def execute_query(self, sql: str, params: Sequence[Any] | None = None) -> list[tuple]:
        if params:
            return [tuple(r) for r in self.conn.execute(sql, list(params)).fetchall()]
        return [tuple(r) for r in self.conn.execute(sql).fetchall()]
