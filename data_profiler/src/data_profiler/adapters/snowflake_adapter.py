"""Snowflake adapter.

Requires ``snowflake-connector-python`` and warehouse privileges to read
INFORMATION_SCHEMA and SELECT on target tables.

Assumptions
-----------
- Auth (password / key-pair / SSO) is supplied by the caller.
- Catalog metadata is read in bulk from ``INFORMATION_SCHEMA``: one query for
  every column and one for every table in scope, instead of two per table.
  ``INFORMATION_SCHEMA.TABLES.ROW_COUNT`` also gives row counts for free, so
  ``estimate_row_counts`` needs neither ``SHOW TABLES`` nor ``RESULT_SCAN``.
  Catalog row counts can lag very recent loads by a few minutes.
- ``INFORMATION_SCHEMA`` views only cover the session's current database, and
  retain metadata for dropped objects for a period; both are acceptable here
  because discovery already comes from the same source.
- Fixed-size sampling must use ``BERNOULLI``/``ROW``: Snowflake rejects
  ``SAMPLE SYSTEM (<n> ROWS)`` and caps ``<n>`` at 1,000,000. Percentage
  sampling uses ``SYSTEM``/``BLOCK``, which is the cheaper of the two.
- Large / sampled tables use ``APPROX_COUNT_DISTINCT`` (HLL).
- Query tagging: sessions set ``QUERY_TAG`` when provided, for cost attribution.
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

MAX_FIXED_SAMPLE_ROWS = 1_000_000


class SnowflakeAdapter(SqlProfilingMixin, DatabaseAdapter):
    engine_name = "snowflake"
    supports_concurrent_profiling = True
    is_thread_safe = True
    supports_approx_distinct = True

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
        self._active_cursors: dict[int, set[Any]] = {}
        self._cursor_lock = threading.Lock()
        self._columns_cache: dict[str, list[ColumnMeta]] = {}
        self._table_meta: dict[str, dict[str, Any]] = {}

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
                cur.execute(
                    f"ALTER SESSION SET QUERY_TAG = '{self._escape_literal(self.query_tag)}'"
                )

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
                logger.debug("snowflake cursor cancel failed: %s", exc)

    @contextmanager
    def _cursor(self) -> Iterator[Any]:
        if self._conn is None:
            raise RuntimeError("SnowflakeAdapter is not connected")
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

    def prefetch_catalog(self, tables: Sequence[TableRef]) -> None:
        """Two queries for the whole run instead of two (or four) per table."""
        if not tables:
            return
        wanted = {t.fully_qualified_name for t in tables}
        schemas = sorted({(t.schema or "").upper() for t in tables if t.schema})
        schema_filter = ""
        params: tuple[Any, ...] = ()
        if schemas:
            placeholders = ", ".join(["%s"] * len(schemas))
            schema_filter = f"AND table_schema IN ({placeholders})"
            params = tuple(schemas)

        try:
            with self._cursor() as cur:
                cur.execute(
                    f"""
                    SELECT table_catalog, table_schema, table_name,
                           column_name, data_type, is_nullable, ordinal_position, comment
                    FROM information_schema.columns
                    WHERE 1=1 {schema_filter}
                    ORDER BY table_catalog, table_schema, table_name, ordinal_position
                    """,
                    params,
                )
                column_rows = cur.fetchall()
            with self._cursor() as cur:
                cur.execute(
                    f"""
                    SELECT table_catalog, table_schema, table_name, row_count, comment
                    FROM information_schema.tables
                    WHERE table_type = 'BASE TABLE' {schema_filter}
                    """,
                    params,
                )
                table_rows = cur.fetchall()
        except Exception as exc:  # noqa: BLE001 — degrade to per-table queries
            logger.warning("catalog prefetch failed (%s); falling back to per-table reads", exc)
            return

        by_table: dict[str, list[ColumnMeta]] = {}
        for catalog, schema, name, col, dtype, is_nullable, ordinal, comment in column_rows:
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
        for catalog, schema, name, row_count, comment in table_rows:
            key = catalog_key(catalog, schema, name)
            if key in wanted:
                self._table_meta[key] = {"row_count": row_count, "comment": comment}
        logger.info(
            "catalog prefetch: %d tables, %d with column metadata",
            len(self._table_meta),
            len(by_table),
        )

    def get_columns(self, table: TableRef) -> list[ColumnMeta]:
        cached = self._columns_cache.get(table.fully_qualified_name)
        if cached is not None:
            return cached
        with self._cursor() as cur:
            cur.execute(
                """
                SELECT column_name, data_type, is_nullable, ordinal_position, comment
                FROM information_schema.columns
                WHERE table_name = %s
                  AND table_schema = %s
                ORDER BY ordinal_position
                """,
                (table.name, table.schema or ""),
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
        if self.config.estimate_row_counts:
            cached = self._table_meta.get(table.fully_qualified_name, {}).get("row_count")
            if cached is not None:
                return int(cached), True
            estimated = self._catalog_row_count(table)
            if estimated is not None:
                return estimated, True
        if self.config.skip_exact_count_when_sampling:
            plan = self.build_sample_plan(table, row_count=None)
            if plan.sampled:
                return None, True
        # Snowflake answers COUNT(*) from micro-partition metadata, so this is
        # cheap even on large tables — it is not a second full scan.
        with self._cursor() as cur:
            cur.execute(f"SELECT COUNT(*) FROM {self.qualify(table)}")
            return int(cur.fetchone()[0]), False

    def _catalog_row_count(self, table: TableRef) -> int | None:
        try:
            with self._cursor() as cur:
                cur.execute(
                    """
                    SELECT row_count FROM information_schema.tables
                    WHERE table_name = %s AND table_schema = %s
                    """,
                    (table.name, table.schema or ""),
                )
                row = cur.fetchone()
        except Exception as exc:  # noqa: BLE001 — fall through to COUNT(*)
            logger.debug("catalog row_count lookup failed for %s: %s", table, exc)
            return None
        if row and row[0] is not None:
            return int(row[0])
        return None

    def get_table_comment(self, table: TableRef) -> str | None:
        meta = self._table_meta.get(table.fully_qualified_name)
        if meta is not None:
            return meta.get("comment")
        with self._cursor() as cur:
            cur.execute(
                """
                SELECT comment FROM information_schema.tables
                WHERE table_name = %s AND table_schema = %s
                """,
                (table.name, table.schema or ""),
            )
            row = cur.fetchone()
            return row[0] if row else None

    def build_sample_plan(self, table: TableRef, row_count: int | None) -> SamplePlan:
        cfg = self.config
        if cfg.sample_percent is not None:
            # SYSTEM/BLOCK samples whole micro-partitions: cheaper than BERNOULLI
            # and legal for percentages.
            return SamplePlan(
                table_suffix=f"SAMPLE SYSTEM ({cfg.sample_percent})",
                sampled=True,
                distinct_is_estimate=True,
            )
        if cfg.sample_size is not None:
            if row_count is not None and row_count <= cfg.sample_size:
                return SamplePlan()
            rows = min(cfg.sample_size, MAX_FIXED_SAMPLE_ROWS)
            if rows != cfg.sample_size:
                logger.warning(
                    "sample_size %d exceeds Snowflake's fixed-size limit; clamping to %d",
                    cfg.sample_size,
                    MAX_FIXED_SAMPLE_ROWS,
                )
            # Fixed-size sampling requires BERNOULLI/ROW; SYSTEM/BLOCK is rejected.
            return SamplePlan(
                table_suffix=f"SAMPLE BERNOULLI ({rows} ROWS)",
                sampled=True,
                sample_size=rows,
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
    def _escape_literal(value: str) -> str:
        return value.replace("'", "''")


def _is_transient(exc: BaseException) -> bool:
    msg = str(exc).lower()
    return bool(re.search(r"timeout|temporar|retry|throttle|429|503|warehouse.*suspend", msg))
