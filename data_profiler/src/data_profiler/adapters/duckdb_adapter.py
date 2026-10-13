"""DuckDB adapter — local analytical engine with native sampling.

Assumptions
-----------
- ``DuckDBPyConnection.cursor()`` returns an independent, thread-safe duplicate,
  so this adapter is safe to drive from the timeout watchdog thread. Parallel
  table profiling is still left off by default: DuckDB already parallelizes a
  single aggregate across cores, so table-level threads mostly add contention.
- Percentage sampling uses ``bernoulli``; DuckDB's default system sampling works
  on ~2048-row vectors and returns zero rows for small tables.
"""

from __future__ import annotations

import logging
import threading
from typing import Any, Sequence

import duckdb

from data_profiler.adapters.base import DatabaseAdapter, SamplePlan
from data_profiler.adapters.catalog import catalog_key
from data_profiler.adapters.filters import filter_tables
from data_profiler.adapters.sql_profiling import SqlProfilingMixin
from data_profiler.config import ProfilerConfig
from data_profiler.models import ColumnMeta, TableRef
from data_profiler.type_mapping import map_native_type

logger = logging.getLogger(__name__)


class DuckDBAdapter(SqlProfilingMixin, DatabaseAdapter):
    engine_name = "duckdb"
    supports_concurrent_profiling = False
    is_thread_safe = True
    supports_approx_distinct = True

    def __init__(self, config: ProfilerConfig, database: str = ":memory:"):
        super().__init__(config)
        self.database = database
        self._conn: duckdb.DuckDBPyConnection | None = None
        self._lock = threading.RLock()
        self._columns_cache: dict[str, list[ColumnMeta]] = {}
        self._table_comments: dict[str, str | None] = {}

    def connect(self) -> None:
        self._conn = duckdb.connect(self.database)

    def close(self) -> None:
        with self._lock:
            if self._conn is not None:
                self._conn.close()
                self._conn = None

    @property
    def conn(self) -> duckdb.DuckDBPyConnection:
        if self._conn is None:
            raise RuntimeError("DuckDBAdapter is not connected")
        return self._conn

    def cancel_active_query(self, *, thread_id: int | None = None) -> None:
        # Connection-wide; safe because this adapter profiles one table at a time.
        conn = self._conn
        interrupt = getattr(conn, "interrupt", None)
        if interrupt is not None:
            try:
                interrupt()
            except Exception as exc:  # noqa: BLE001 — nothing to do if it fails
                logger.debug("duckdb interrupt failed: %s", exc)

    def connection_hint(self) -> str | None:
        return self.database

    def list_tables(self) -> list[TableRef]:
        rows = self._query(
            """
            SELECT table_catalog, table_schema, table_name
            FROM information_schema.tables
            WHERE table_type = 'BASE TABLE'
              AND table_schema NOT IN ('information_schema', 'pg_catalog')
            ORDER BY table_schema, table_name
            """
        )
        return filter_tables(
            [TableRef(catalog=r[0], schema=r[1], name=r[2]) for r in rows],
            self.config,
        )

    def prefetch_catalog(self, tables: Sequence[TableRef]) -> None:
        if not tables:
            return
        wanted = {t.fully_qualified_name for t in tables}
        comments = self._all_column_comments()
        by_table: dict[str, list[ColumnMeta]] = {}
        for catalog, schema, name, col, dtype, is_nullable, ordinal in self._query(
            """
            SELECT table_catalog, table_schema, table_name,
                   column_name, data_type, is_nullable, ordinal_position
            FROM information_schema.columns
            WHERE table_schema NOT IN ('information_schema', 'pg_catalog')
            ORDER BY table_catalog, table_schema, table_name, ordinal_position
            """
        ):
            key = catalog_key(catalog, schema, name)
            if key not in wanted:
                continue
            nullable = None if is_nullable is None else str(is_nullable).upper() == "YES"
            by_table.setdefault(key, []).append(
                ColumnMeta(
                    name=col,
                    native_type=dtype,
                    portable_type=map_native_type(dtype, nullable=nullable),
                    nullable=nullable,
                    comment=comments.get((catalog_key(catalog, schema, name), col)),
                    ordinal_position=int(ordinal) if ordinal is not None else None,
                )
            )
        self._columns_cache.update(by_table)
        self._table_comments.update(self._all_table_comments())
        logger.debug("prefetched catalog for %d tables", len(by_table))

    def get_columns(self, table: TableRef) -> list[ColumnMeta]:
        cached = self._columns_cache.get(table.fully_qualified_name)
        if cached is not None:
            return cached
        rows = self._query(
            """
            SELECT column_name, data_type, is_nullable, ordinal_position
            FROM information_schema.columns
            WHERE table_name = ?
              AND (? IS NULL OR table_schema = ?)
            ORDER BY ordinal_position
            """,
            [table.name, table.schema, table.schema],
        )
        comments = self._all_column_comments()
        cols: list[ColumnMeta] = []
        for name, dtype, is_nullable, ordinal in rows:
            nullable = None if is_nullable is None else str(is_nullable).upper() == "YES"
            cols.append(
                ColumnMeta(
                    name=name,
                    native_type=dtype,
                    portable_type=map_native_type(dtype, nullable=nullable),
                    nullable=nullable,
                    comment=comments.get((table.fully_qualified_name, name)),
                    ordinal_position=int(ordinal) if ordinal is not None else None,
                )
            )
        return cols

    def _all_column_comments(self) -> dict[tuple[str, str], str | None]:
        try:
            rows = self._query(
                """
                SELECT database_name, schema_name, table_name, column_name, comment
                FROM duckdb_columns()
                WHERE comment IS NOT NULL
                """
            )
        except Exception:  # noqa: BLE001 — comments are optional metadata
            return {}
        return {(catalog_key(r[0], r[1], r[2]), r[3]): r[4] for r in rows}

    def _all_table_comments(self) -> dict[str, str | None]:
        try:
            rows = self._query(
                """
                SELECT database_name, schema_name, table_name, comment
                FROM duckdb_tables()
                WHERE comment IS NOT NULL
                """
            )
        except Exception:  # noqa: BLE001
            return {}
        return {catalog_key(r[0], r[1], r[2]): r[3] for r in rows}

    def get_table_comment(self, table: TableRef) -> str | None:
        if not self._table_comments:
            self._table_comments = self._all_table_comments()
        return self._table_comments.get(table.fully_qualified_name)

    def get_row_count(self, table: TableRef) -> tuple[int | None, bool]:
        if self.config.skip_exact_count_when_sampling:
            plan = self.build_sample_plan(table, row_count=None)
            if plan.sampled:
                return None, True
        rows = self._query(f"SELECT COUNT(*) FROM {self.qualify(table)}")
        return int(rows[0][0]), False

    def build_sample_plan(self, table: TableRef, row_count: int | None) -> SamplePlan:
        cfg = self.config
        if cfg.sample_percent is not None:
            pct = min(100.0, max(0.0, cfg.sample_percent))
            return SamplePlan(
                row_limit=f"USING SAMPLE {pct}% (bernoulli)",
                sampled=True,
                sample_size=None,
                distinct_is_estimate=True,
            )
        if cfg.sample_size is not None:
            if row_count is not None and row_count <= cfg.sample_size:
                return SamplePlan()
            return SamplePlan(
                row_limit=f"USING SAMPLE reservoir({cfg.sample_size} ROWS)",
                sampled=True,
                sample_size=cfg.sample_size,
                distinct_is_estimate=True,
            )
        return SamplePlan()

    def approx_distinct_expr(self, quoted_col: str) -> str:
        return f"approx_count_distinct({quoted_col})"

    def execute_query(self, sql: str, params: Sequence[Any] | None = None) -> list[tuple]:
        return [tuple(r) for r in self._query(sql, params)]

    def _query(self, sql: str, params: Sequence[Any] | None = None) -> list[tuple]:
        with self._lock:
            cur = self.conn.cursor()
            try:
                if params:
                    return cur.execute(sql, list(params)).fetchall()
                return cur.execute(sql).fetchall()
            finally:
                cur.close()
