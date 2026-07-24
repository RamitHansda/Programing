"""DuckDB adapter — local analytical engine with TABLESAMPLE support."""

from __future__ import annotations

from typing import Any, Sequence

import duckdb

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


class DuckDBAdapter(DatabaseAdapter):
    engine_name = "duckdb"

    def __init__(self, config: ProfilerConfig, database: str = ":memory:"):
        super().__init__(config)
        self.database = database
        self._conn: duckdb.DuckDBPyConnection | None = None
        self._last_sample_meta: tuple[bool, int | None] = (False, None)

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
        tables = [
            TableRef(catalog=r[0], schema=r[1], name=r[2]) for r in rows
        ]
        return _filter_tables(tables, self.config)

    def get_columns(self, table: TableRef) -> list[ColumnMeta]:
        # DuckDB's information_schema.columns has no comment column in all versions.
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
        if self.config.estimate_row_counts:
            # DuckDB does not expose cheap estimates like Snowflake; fall back.
            pass
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

    def profile_column_stats(
        self,
        table: TableRef,
        columns: Sequence[ColumnMeta],
        *,
        row_count: int | None,
    ) -> dict[str, ColumnStats]:
        if not columns:
            return {}

        sample_clause, sampled, sample_size = self._sample_clause(row_count)
        select_sql, aliases = build_stats_select(
            columns, self.config, quote_ident=self.quote_ident
        )
        sql = f"SELECT {select_sql} FROM {self.qualify(table)} {sample_clause}"
        row = self.conn.execute(sql).fetchone()
        sample_rows = int(row[-1]) if row is not None else 0
        stats = parse_stats_row(
            columns,
            aliases,
            list(row[:-1]) if row is not None else [],
            sample_rows=sample_rows,
            row_count=row_count if sampled else sample_rows,
            estimate_distinct=sampled,
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
                try:
                    hist_rows = self.conn.execute(hist_sql).fetchall()
                    stats[col.name].histogram = rows_to_histogram(
                        hist_rows, self.config.histogram_buckets
                    )
                except Exception:
                    pass

        for s in stats.values():
            s.sampled_rows = sample_rows
        self._last_sample_meta = (sampled, sample_size if sampled else sample_rows)
        return stats

    def _sample_clause(self, row_count: int | None) -> tuple[str, bool, int | None]:
        cfg = self.config
        if cfg.sample_percent is not None:
            pct = min(100.0, max(0.0, cfg.sample_percent))
            # DuckDB TABLESAMPLE SYSTEM (percent)
            return f"TABLESAMPLE SYSTEM ({pct})", True, None
        if cfg.sample_size is not None and row_count is not None and row_count > cfg.sample_size:
            # Prefer reservoir-style limit after randomize for bounded samples.
            return f"USING SAMPLE {cfg.sample_size}", True, cfg.sample_size
        return "", False, None
