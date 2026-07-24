"""Databricks SQL warehouse adapter.

Requires ``databricks-sql-connector`` and a SQL warehouse endpoint.

Assumptions
-----------
- Prefers Unity Catalog ``system.information_schema``; falls back to local
  ``information_schema``.
- Identifiers are quoted with backticks (Databricks SQL / Spark SQL style).
- Large / sampled tables use ``approx_count_distinct``.
- Privileges: SELECT on tables, USE CATALOG/SCHEMA, information_schema read.
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


class DatabricksAdapter(SqlProfilingMixin, DatabaseAdapter):
    engine_name = "databricks"
    supports_concurrent_profiling = True

    def __init__(
        self,
        config: ProfilerConfig,
        *,
        server_hostname: str | None = None,
        http_path: str | None = None,
        access_token: str | None = None,
        catalog: str | None = None,
        schema: str | None = None,
        connection: Any | None = None,
        **connect_kwargs: Any,
    ):
        super().__init__(config)
        self.connect_params = {
            "server_hostname": server_hostname,
            "http_path": http_path,
            "access_token": access_token,
            "catalog": catalog,
            "schema": schema,
            **connect_kwargs,
        }
        self.database = catalog
        self._external_conn = connection
        self._conn: Any | None = None

    def connect(self) -> None:
        if self._external_conn is not None:
            self._conn = self._external_conn
            return
        try:
            from databricks import sql as dbsql  # type: ignore
        except ImportError as exc:
            raise ImportError(
                "Install databricks extras: pip install 'data-profiler[databricks]'"
            ) from exc
        params = {k: v for k, v in self.connect_params.items() if v is not None}
        catalog = params.pop("catalog", None)
        schema = params.pop("schema", None)
        self._conn = dbsql.connect(**params)
        with self._cursor() as cur:
            if catalog:
                cur.execute(f"USE CATALOG {self.quote_ident(catalog)}")
            if schema:
                cur.execute(f"USE SCHEMA {self.quote_ident(schema)}")

    def close(self) -> None:
        if self._external_conn is None and self._conn is not None:
            self._conn.close()
        self._conn = None

    def connection_hint(self) -> str | None:
        host = self.connect_params.get("server_hostname")
        catalog = self.connect_params.get("catalog")
        if host or catalog:
            return f"{host}/{catalog}"
        return None

    def quote_ident(self, ident: str) -> str:
        return "`" + ident.replace("`", "``") + "`"

    @contextmanager
    def _cursor(self) -> Iterator[Any]:
        if self._conn is None:
            raise RuntimeError("DatabricksAdapter is not connected")
        cur = self._conn.cursor()
        try:
            yield cur
        finally:
            cur.close()

    def list_tables(self) -> list[TableRef]:
        with self._cursor() as cur:
            try:
                cur.execute(
                    """
                    SELECT table_catalog, table_schema, table_name
                    FROM system.information_schema.tables
                    WHERE table_type = 'BASE TABLE'
                      AND table_schema NOT IN ('information_schema')
                    ORDER BY table_catalog, table_schema, table_name
                    """
                )
            except Exception:
                cur.execute(
                    """
                    SELECT table_catalog, table_schema, table_name
                    FROM information_schema.tables
                    WHERE table_type = 'BASE TABLE'
                    ORDER BY table_schema, table_name
                    """
                )
            tables = [TableRef(catalog=r[0], schema=r[1], name=r[2]) for r in cur.fetchall()]
        return filter_tables(tables, self.config)

    def get_columns(self, table: TableRef) -> list[ColumnMeta]:
        with self._cursor() as cur:
            try:
                cur.execute(
                    """
                    SELECT column_name, full_data_type, is_nullable, ordinal_position, comment
                    FROM system.information_schema.columns
                    WHERE table_name = ?
                      AND table_schema = ?
                      AND (? IS NULL OR table_catalog = ?)
                    ORDER BY ordinal_position
                    """,
                    (table.name, table.schema, table.catalog, table.catalog),
                )
            except Exception:
                cur.execute(
                    """
                    SELECT column_name, data_type, is_nullable, ordinal_position, comment
                    FROM information_schema.columns
                    WHERE table_name = ?
                      AND table_schema = ?
                    ORDER BY ordinal_position
                    """,
                    (table.name, table.schema),
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
        if self.config.skip_exact_count_when_sampling:
            plan = self.build_sample_plan(table, row_count=None)
            if plan.sampled:
                return None, True
        with self._cursor() as cur:
            cur.execute(f"SELECT COUNT(*) FROM {self.qualify(table)}")
            return int(cur.fetchone()[0]), False

    def build_sample_plan(self, table: TableRef, row_count: int | None) -> SamplePlan:
        cfg = self.config
        if cfg.sample_percent is not None:
            return SamplePlan(
                clause=f"TABLESAMPLE ({cfg.sample_percent} PERCENT)",
                sampled=True,
                distinct_is_estimate=True,
            )
        if cfg.sample_size is not None:
            if row_count is not None and row_count <= cfg.sample_size:
                return SamplePlan()
            return SamplePlan(
                clause=f"TABLESAMPLE ({cfg.sample_size} ROWS)",
                sampled=True,
                sample_size=cfg.sample_size,
                distinct_is_estimate=True,
            )
        return SamplePlan()

    def approx_distinct_expr(self, quoted_col: str) -> str:
        return f"approx_count_distinct({quoted_col})"

    def execute_query(self, sql: str, params: Sequence[Any] | None = None) -> list[tuple]:
        try:
            with self._cursor() as cur:
                cur.execute(sql, params or None)
                rows = cur.fetchall() or []
                return [tuple(r) for r in rows]
        except Exception as exc:  # noqa: BLE001
            raise AdapterError(str(exc), transient=_is_transient(exc), cause=exc) from exc


def _is_transient(exc: BaseException) -> bool:
    msg = str(exc).lower()
    return bool(re.search(r"timeout|temporar|retry|throttle|429|503|rate.?limit", msg))
