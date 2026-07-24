"""Snowflake adapter.

Requires ``snowflake-connector-python`` and warehouse privileges to read
INFORMATION_SCHEMA and SELECT on target tables.

Assumptions
-----------
- Auth (password / key-pair / SSO) is supplied by the caller.
- ``estimate_row_counts`` uses ``SHOW TABLES`` + ``RESULT_SCAN(LAST_QUERY_ID())``
  — a documented Snowflake pattern; counts can lag recent loads.
- Large / sampled tables use ``APPROX_COUNT_DISTINCT`` (HLL).
- Query tagging: sessions set ``QUERY_TAG`` to the profiler run id when provided.
"""

from __future__ import annotations

import logging
import re
from contextlib import contextmanager
from typing import Any, Iterator, Sequence

from data_profiler.adapters.base import DatabaseAdapter, SamplePlan
from data_profiler.adapters.filters import filter_tables
from data_profiler.adapters.sql_profiling import SqlProfilingMixin
from data_profiler.config import ProfilerConfig
from data_profiler.errors import AdapterError
from data_profiler.models import ColumnMeta, TableRef
from data_profiler.type_mapping import map_native_type

logger = logging.getLogger(__name__)


class SnowflakeAdapter(SqlProfilingMixin, DatabaseAdapter):
    engine_name = "snowflake"
    supports_concurrent_profiling = True

    def __init__(
        self,
        config: ProfilerConfig,
        *,
        account: str | None = None,
        user: str | None = None,
        password: str | None = None,
        warehouse: str | None = None,
        database: str | None = None,
        schema: str | None = None,
        role: str | None = None,
        connection: Any | None = None,
        query_tag: str | None = None,
        **connect_kwargs: Any,
    ):
        super().__init__(config)
        self.connect_params = {
            "account": account,
            "user": user,
            "password": password,
            "warehouse": warehouse,
            "database": database,
            "schema": schema,
            "role": role,
            **connect_kwargs,
        }
        self.database = database
        self.query_tag = query_tag
        self._external_conn = connection
        self._conn: Any | None = None

    def connect(self) -> None:
        if self._external_conn is not None:
            self._conn = self._external_conn
            return
        try:
            import snowflake.connector  # type: ignore
        except ImportError as exc:
            raise ImportError(
                "Install snowflake extras: pip install 'data-profiler[snowflake]'"
            ) from exc
        params = {k: v for k, v in self.connect_params.items() if v is not None}
        self._conn = snowflake.connector.connect(**params)
        if self.query_tag:
            with self._cursor() as cur:
                cur.execute(f"ALTER SESSION SET QUERY_TAG = '{self._escape_literal(self.query_tag)}'")

    def close(self) -> None:
        if self._external_conn is None and self._conn is not None:
            self._conn.close()
        self._conn = None

    def connection_hint(self) -> str | None:
        account = self.connect_params.get("account")
        database = self.connect_params.get("database")
        if account or database:
            return f"{account}/{database}"
        return None

    @contextmanager
    def _cursor(self) -> Iterator[Any]:
        if self._conn is None:
            raise RuntimeError("SnowflakeAdapter is not connected")
        cur = self._conn.cursor()
        try:
            yield cur
        finally:
            cur.close()

    def list_tables(self) -> list[TableRef]:
        with self._cursor() as cur:
            cur.execute(
                """
                SELECT table_catalog, table_schema, table_name
                FROM information_schema.tables
                WHERE table_type = 'BASE TABLE'
                  AND table_schema NOT IN ('INFORMATION_SCHEMA')
                ORDER BY table_schema, table_name
                """
            )
            tables = [TableRef(catalog=r[0], schema=r[1], name=r[2]) for r in cur.fetchall()]
        return filter_tables(tables, self.config)

    def get_columns(self, table: TableRef) -> list[ColumnMeta]:
        with self._cursor() as cur:
            cur.execute(
                """
                SELECT column_name, data_type, is_nullable, ordinal_position, comment
                FROM information_schema.columns
                WHERE table_name = %s
                  AND table_schema = %s
                ORDER BY ordinal_position
                """,
                (table.name.upper(), (table.schema or "").upper()),
            )
            rows = cur.fetchall()
        cols: list[ColumnMeta] = []
        for name, dtype, is_nullable, ordinal, comment in rows:
            nullable = str(is_nullable).upper() == "YES"
            portable = map_native_type(dtype, nullable=nullable)
            cols.append(
                ColumnMeta(
                    name=name,
                    native_type=dtype,
                    portable_type=portable,
                    nullable=nullable,
                    comment=comment,
                    ordinal_position=int(ordinal) if ordinal is not None else None,
                )
            )
        return cols

    def get_row_count(self, table: TableRef) -> tuple[int | None, bool]:
        with self._cursor() as cur:
            if self.config.estimate_row_counts:
                # Escape LIKE metacharacters in the table name.
                pattern = self._escape_like(table.name)
                schema = self.quote_ident(table.schema or "PUBLIC")
                cur.execute(f"SHOW TABLES LIKE %s IN SCHEMA {schema}", (pattern,))
                cur.execute(
                    'SELECT "rows" FROM TABLE(RESULT_SCAN(LAST_QUERY_ID())) WHERE "name" = %s',
                    (table.name.upper(),),
                )
                row = cur.fetchone()
                if row and row[0] is not None:
                    return int(row[0]), True
            if self.config.skip_exact_count_when_sampling:
                plan = self.build_sample_plan(table, row_count=None)
                if plan.sampled:
                    return None, True
            cur.execute(f"SELECT COUNT(*) FROM {self.qualify(table)}")
            return int(cur.fetchone()[0]), False

    def get_table_comment(self, table: TableRef) -> str | None:
        with self._cursor() as cur:
            cur.execute(
                """
                SELECT comment FROM information_schema.tables
                WHERE table_name = %s AND table_schema = %s
                """,
                (table.name.upper(), (table.schema or "").upper()),
            )
            row = cur.fetchone()
            return row[0] if row else None

    def build_sample_plan(self, table: TableRef, row_count: int | None) -> SamplePlan:
        cfg = self.config
        if cfg.sample_percent is not None:
            return SamplePlan(
                clause=f"TABLESAMPLE BERNOULLI ({cfg.sample_percent})",
                sampled=True,
                distinct_is_estimate=True,
            )
        if cfg.sample_size is not None:
            # Align with other adapters: skip sampling when table is smaller.
            if row_count is not None and row_count <= cfg.sample_size:
                return SamplePlan()
            return SamplePlan(
                clause=f"TABLESAMPLE SYSTEM ({cfg.sample_size} ROWS)",
                sampled=True,
                sample_size=cfg.sample_size,
                distinct_is_estimate=True,
            )
        return SamplePlan()

    def approx_distinct_expr(self, quoted_col: str) -> str:
        return f"APPROX_COUNT_DISTINCT({quoted_col})"

    def execute_query(self, sql: str, params: Sequence[Any] | None = None) -> list[tuple]:
        try:
            with self._cursor() as cur:
                cur.execute(sql, params or ())
                rows = cur.fetchall()
                return [tuple(r) for r in rows]
        except Exception as exc:  # noqa: BLE001
            raise AdapterError(str(exc), transient=_is_transient(exc), cause=exc) from exc

    @staticmethod
    def _escape_like(value: str) -> str:
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    @staticmethod
    def _escape_literal(value: str) -> str:
        return value.replace("'", "''")


def _is_transient(exc: BaseException) -> bool:
    msg = str(exc).lower()
    return bool(re.search(r"timeout|temporar|retry|throttle|429|503|warehouse.*suspend", msg))
