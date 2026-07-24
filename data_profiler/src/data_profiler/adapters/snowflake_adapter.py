"""Snowflake adapter.

Requires `snowflake-connector-python` and warehouse privileges to read
INFORMATION_SCHEMA / ACCOUNT_USAGE (optional) and SELECT on target tables.

Assumptions / notes:
  - Uses INFORMATION_SCHEMA for column metadata and comments.
  - Row counts prefer APPROXIMATE values from ACCOUNT_USAGE.TABLE_STORAGE_METRICS
    or SHOW TABLES when estimate_row_counts=True; otherwise COUNT(*).
  - Distinct counts use APPROX_COUNT_DISTINCT (HLL) when sampling or on large tables.
  - Sampling uses TABLESAMPLE BERNOULLI / SYSTEM with optional RESULT_SCAN patterns
    for SHOW commands.
  - Network connectivity and key-pair/password auth are caller responsibilities.
"""

from __future__ import annotations

import logging
from typing import Any, Sequence

from data_profiler.adapters.base import DatabaseAdapter
from data_profiler.adapters.sql_stats import (
    build_histogram_sql,
    build_stats_select,
    parse_stats_row,
    rows_to_histogram,
)
from data_profiler.adapters.sqlite_adapter import _filter_tables
from data_profiler.config import ProfilerConfig
from data_profiler.models import ColumnMeta, ColumnStats, TableRef
from data_profiler.type_mapping import map_native_type

logger = logging.getLogger(__name__)


class SnowflakeAdapter(DatabaseAdapter):
    engine_name = "snowflake"

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
        self._external_conn = connection
        self._conn: Any | None = None
        self._last_sample_meta: tuple[bool, int | None] = (False, None)

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

    def close(self) -> None:
        if self._external_conn is None and self._conn is not None:
            self._conn.close()
        self._conn = None

    @property
    def conn(self) -> Any:
        if self._conn is None:
            raise RuntimeError("SnowflakeAdapter is not connected")
        return self._conn

    def quote_ident(self, ident: str) -> str:
        return '"' + ident.replace('"', '""') + '"'

    def list_tables(self) -> list[TableRef]:
        cur = self.conn.cursor()
        try:
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
        finally:
            cur.close()
        return _filter_tables(tables, self.config)

    def get_columns(self, table: TableRef) -> list[ColumnMeta]:
        cur = self.conn.cursor()
        try:
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
        finally:
            cur.close()
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
        cur = self.conn.cursor()
        try:
            if self.config.estimate_row_counts:
                # SHOW TABLES + RESULT_SCAN is a common Snowflake pattern for cheap counts.
                cur.execute(f"SHOW TABLES LIKE '{table.name}' IN SCHEMA {self.quote_ident(table.schema or 'PUBLIC')}")
                cur.execute(
                    "SELECT \"rows\" FROM TABLE(RESULT_SCAN(LAST_QUERY_ID())) WHERE \"name\" = %s",
                    (table.name.upper(),),
                )
                row = cur.fetchone()
                if row and row[0] is not None:
                    return int(row[0]), True
            cur.execute(f"SELECT COUNT(*) FROM {self.qualify(table)}")
            return int(cur.fetchone()[0]), False
        finally:
            cur.close()

    def get_table_comment(self, table: TableRef) -> str | None:
        cur = self.conn.cursor()
        try:
            cur.execute(
                """
                SELECT comment FROM information_schema.tables
                WHERE table_name = %s AND table_schema = %s
                """,
                (table.name.upper(), (table.schema or "").upper()),
            )
            row = cur.fetchone()
            return row[0] if row else None
        finally:
            cur.close()

    def profile_column_stats(
        self,
        table: TableRef,
        columns: Sequence[ColumnMeta],
        *,
        row_count: int | None,
    ) -> dict[str, ColumnStats]:
        if not columns:
            return {}

        sample_clause, sampled, sample_size = self._sample_clause()
        # Prefer HLL for distinct when sampling or table is large.
        use_approx = sampled or (row_count is not None and row_count > 1_000_000)
        select_sql, aliases = self._build_select(columns, use_approx=use_approx)
        sql = f"SELECT {select_sql} FROM {self.qualify(table)} {sample_clause}"
        cur = self.conn.cursor()
        try:
            cur.execute(sql)
            row = cur.fetchone()
        finally:
            cur.close()

        sample_rows = int(row[-1]) if row is not None else 0
        stats = parse_stats_row(
            columns,
            aliases,
            list(row[:-1]) if row is not None else [],
            sample_rows=sample_rows,
            row_count=row_count if sampled else sample_rows,
            estimate_distinct=use_approx,
        )

        if self.config.include_histograms:
            for col in columns:
                hist_sql = build_histogram_sql(
                    col,
                    self.config,
                    quote_ident=self.quote_ident,
                    qualify_table=self.qualify(table),
                    sample_clause=sample_clause,
                )
                if not hist_sql:
                    continue
                cur = self.conn.cursor()
                try:
                    cur.execute(hist_sql)
                    hist_rows = cur.fetchall()
                    stats[col.name].histogram = rows_to_histogram(
                        hist_rows, self.config.histogram_buckets
                    )
                except Exception as exc:
                    logger.debug("histogram skipped for %s.%s: %s", table.name, col.name, exc)
                finally:
                    cur.close()

        self._last_sample_meta = (sampled, sample_size if sampled else sample_rows)
        return stats

    def _build_select(
        self, columns: Sequence[ColumnMeta], *, use_approx: bool
    ) -> tuple[str, list[tuple[str, str]]]:
        if not use_approx:
            return build_stats_select(columns, self.config, quote_ident=self.quote_ident)

        from data_profiler.type_mapping import is_comparable_for_minmax

        pieces: list[str] = []
        aliases: list[tuple[str, str]] = []
        for col in columns:
            q = self.quote_ident(col.name)
            pieces.append(f"COUNT(*) - COUNT({q}) AS {self.quote_ident(col.name + '__nulls')}")
            aliases.append((col.name, "nulls"))
            if is_comparable_for_minmax(col.portable_type.kind):
                pieces.append(f"MIN({q}) AS {self.quote_ident(col.name + '__min')}")
                aliases.append((col.name, "min"))
                pieces.append(f"MAX({q}) AS {self.quote_ident(col.name + '__max')}")
                aliases.append((col.name, "max"))
            pieces.append(
                f"APPROX_COUNT_DISTINCT({q}) AS {self.quote_ident(col.name + '__distinct')}"
            )
            aliases.append((col.name, "distinct"))
        pieces.append("COUNT(*) AS __sample_rows")
        return ", ".join(pieces), aliases

    def _sample_clause(self) -> tuple[str, bool, int | None]:
        cfg = self.config
        if cfg.sample_percent is not None:
            return f"TABLESAMPLE BERNOULLI ({cfg.sample_percent})", True, None
        if cfg.sample_size is not None:
            # Snowflake SAMPLE with row count.
            return f"TABLESAMPLE SYSTEM ({cfg.sample_size} ROWS)", True, cfg.sample_size
        return "", False, None
