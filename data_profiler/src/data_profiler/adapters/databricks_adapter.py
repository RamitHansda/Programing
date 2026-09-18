"""Databricks SQL warehouse adapter.

Requires ``databricks-sql-connector>=3.0`` and a SQL warehouse endpoint.

Assumptions
-----------
- Prefers Unity Catalog ``system.information_schema``; falls back to the local
  ``information_schema``. Catalog metadata is read in bulk (one columns query,
  one tables query) rather than per table.
- Positional ``?`` parameter markers are native parameters, supported by
  connector 3.0+ against Databricks Runtime with server-side binding.
- Identifiers are quoted with backticks (Databricks SQL / Spark SQL style).
- Large / sampled tables use ``approx_count_distinct``.
- Privileges: SELECT on tables, USE CATALOG/SCHEMA, information_schema read.
- ``information_schema.tables`` carries no row count, so counts come from
  ``COUNT(*)``, which Delta answers from file statistics.
"""

from __future__ import annotations

import logging
import re
import threading
from contextlib import contextmanager
from typing import Any, Iterator, Sequence

from data_profiler.adapters.base import DatabaseAdapter, SamplePlan
from data_profiler.adapters.catalog import catalog_key
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
    is_thread_safe = True
    supports_approx_distinct = True

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
        self._active_cursors: dict[int, set[Any]] = {}
        self._cursor_lock = threading.Lock()
        self._use_unity_catalog = True
        self._columns_cache: dict[str, list[ColumnMeta]] = {}
        self._table_comments: dict[str, str | None] = {}

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

    def cancel_active_query(self, *, thread_id: int | None = None) -> None:
        with self._cursor_lock:
            if thread_id is None:
                cursors = [c for group in self._active_cursors.values() for c in group]
            else:
                cursors = list(self._active_cursors.get(thread_id, ()))
        for cur in cursors:
            try:
                cur.cancel()
            except Exception as exc:  # noqa: BLE001 — best effort
                logger.debug("databricks cursor cancel failed: %s", exc)

    def quote_ident(self, ident: str) -> str:
        return "`" + ident.replace("`", "``") + "`"

    @contextmanager
    def _cursor(self) -> Iterator[Any]:
        if self._conn is None:
            raise RuntimeError("DatabricksAdapter is not connected")
        cur = self._conn.cursor()
        ident = threading.get_ident()
        with self._cursor_lock:
            self._active_cursors.setdefault(ident, set()).add(cur)
        try:
            yield cur
        finally:
            with self._cursor_lock:
                group = self._active_cursors.get(ident)
                if group is not None:
                    group.discard(cur)
                    if not group:
                        self._active_cursors.pop(ident, None)
            cur.close()

    def _catalog_schema(self, table_name: str = "tables") -> str:
        prefix = "system.information_schema" if self._use_unity_catalog else "information_schema"
        return f"{prefix}.{table_name}"

    def list_tables(self) -> list[TableRef]:
        query = """
            SELECT table_catalog, table_schema, table_name
            FROM {source}
            WHERE table_type = 'BASE TABLE'
            ORDER BY table_catalog, table_schema, table_name
        """
        try:
            rows = self._fetch(query.format(source="system.information_schema.tables"))
        except Exception as exc:  # noqa: BLE001 — pre-Unity workspaces
            logger.info("Unity Catalog information_schema unavailable (%s); using local", exc)
            self._use_unity_catalog = False
            rows = self._fetch(query.format(source="information_schema.tables"))
        tables = [TableRef(catalog=r[0], schema=r[1], name=r[2]) for r in rows]
        return filter_tables(tables, self.config)

    def prefetch_catalog(self, tables: Sequence[TableRef]) -> None:
        if not tables:
            return
        wanted = {t.fully_qualified_name for t in tables}
        schemas = sorted({t.schema for t in tables if t.schema})
        if not schemas:
            return
        placeholders = ", ".join(["?"] * len(schemas))
        try:
            column_rows = self._fetch(
                f"""
                SELECT table_catalog, table_schema, table_name,
                       full_data_type, column_name, is_nullable, ordinal_position, comment
                FROM {self._catalog_schema('columns')}
                WHERE table_schema IN ({placeholders})
                ORDER BY table_catalog, table_schema, table_name, ordinal_position
                """,
                schemas,
            )
            table_rows = self._fetch(
                f"""
                SELECT table_catalog, table_schema, table_name, comment
                FROM {self._catalog_schema('tables')}
                WHERE table_schema IN ({placeholders})
                """,
                schemas,
            )
        except Exception as exc:  # noqa: BLE001 — degrade to per-table reads
            logger.warning("catalog prefetch failed (%s); falling back to per-table reads", exc)
            return

        by_table: dict[str, list[ColumnMeta]] = {}
        for catalog, schema, name, dtype, col, is_nullable, ordinal, comment in column_rows:
            key = catalog_key(catalog, schema, name)
            if key not in wanted:
                continue
            nullable = str(is_nullable).upper() == "YES"
            by_table.setdefault(key, []).append(
                ColumnMeta(
                    name=col,
                    native_type=dtype,
                    portable_type=map_native_type(dtype, nullable=nullable),
                    nullable=nullable,
                    comment=comment,
                    ordinal_position=int(ordinal) if ordinal is not None else None,
                )
            )
        self._columns_cache.update(by_table)
        for catalog, schema, name, comment in table_rows:
            key = catalog_key(catalog, schema, name)
            if key in wanted:
                self._table_comments[key] = comment
        logger.info("catalog prefetch: %d tables with column metadata", len(by_table))

    def get_columns(self, table: TableRef) -> list[ColumnMeta]:
        cached = self._columns_cache.get(table.fully_qualified_name)
        if cached is not None:
            return cached
        try:
            rows = self._fetch(
                f"""
                SELECT column_name, full_data_type, is_nullable, ordinal_position, comment
                FROM {self._catalog_schema('columns')}
                WHERE table_name = ?
                  AND table_schema = ?
                  AND (? IS NULL OR table_catalog = ?)
                ORDER BY ordinal_position
                """,
                (table.name, table.schema, table.catalog, table.catalog),
            )
        except Exception as exc:  # noqa: BLE001 — full_data_type is Unity-only
            logger.debug("falling back to data_type for %s: %s", table, exc)
            rows = self._fetch(
                """
                SELECT column_name, data_type, is_nullable, ordinal_position, comment
                FROM information_schema.columns
                WHERE table_name = ?
                  AND table_schema = ?
                ORDER BY ordinal_position
                """,
                (table.name, table.schema),
            )

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

    def get_table_comment(self, table: TableRef) -> str | None:
        return self._table_comments.get(table.fully_qualified_name)

    def get_row_count(self, table: TableRef) -> tuple[int | None, bool]:
        if self.config.skip_exact_count_when_sampling:
            plan = self.build_sample_plan(table, row_count=None)
            if plan.sampled:
                return None, True
        rows = self._fetch(f"SELECT COUNT(*) FROM {self.qualify(table)}")
        return int(rows[0][0]), False

    def build_sample_plan(self, table: TableRef, row_count: int | None) -> SamplePlan:
        cfg = self.config
        if cfg.sample_percent is not None:
            return SamplePlan(
                table_suffix=f"TABLESAMPLE ({cfg.sample_percent} PERCENT)",
                sampled=True,
                distinct_is_estimate=True,
            )
        if cfg.sample_size is not None:
            if row_count is not None and row_count <= cfg.sample_size:
                return SamplePlan()
            return SamplePlan(
                table_suffix=f"TABLESAMPLE ({cfg.sample_size} ROWS)",
                sampled=True,
                sample_size=cfg.sample_size,
                distinct_is_estimate=True,
            )
        return SamplePlan()

    def approx_distinct_expr(self, quoted_col: str) -> str:
        return f"approx_count_distinct({quoted_col})"

    def execute_query(self, sql: str, params: Sequence[Any] | None = None) -> list[tuple]:
        try:
            return self._fetch(sql, params)
        except Exception as exc:  # noqa: BLE001
            raise AdapterError(str(exc), transient=_is_transient(exc), cause=exc) from exc

    def _fetch(self, sql: str, params: Sequence[Any] | None = None) -> list[tuple]:
        with self._cursor() as cur:
            cur.execute(sql, list(params) if params else None)
            rows = cur.fetchall() or []
            return [tuple(r) for r in rows]


def _is_transient(exc: BaseException) -> bool:
    msg = str(exc).lower()
    return bool(re.search(r"timeout|temporar|retry|throttle|429|503|rate.?limit", msg))
