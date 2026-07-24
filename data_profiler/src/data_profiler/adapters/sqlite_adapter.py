"""SQLite adapter — fully local, used for demos and unit tests."""

from __future__ import annotations

import sqlite3
from typing import Any, Sequence

from data_profiler.adapters.base import DatabaseAdapter
from data_profiler.adapters.sql_stats import (
    build_histogram_sql,
    build_stats_select,
    parse_stats_row,
    rows_to_histogram,
)
from data_profiler.config import ProfilerConfig
from data_profiler.models import ColumnMeta, ColumnStats, TableRef
from data_profiler.type_mapping import map_native_type


class SQLiteAdapter(DatabaseAdapter):
    engine_name = "sqlite"

    def __init__(self, config: ProfilerConfig, database: str = ":memory:"):
        super().__init__(config)
        self.database = database
        self._conn: sqlite3.Connection | None = None

    def connect(self) -> None:
        self._conn = sqlite3.connect(self.database)
        self._conn.row_factory = sqlite3.Row

    def close(self) -> None:
        if self._conn is not None:
            self._conn.close()
            self._conn = None

    @property
    def conn(self) -> sqlite3.Connection:
        if self._conn is None:
            raise RuntimeError("SQLiteAdapter is not connected")
        return self._conn

    def list_tables(self) -> list[TableRef]:
        rows = self.conn.execute(
            """
            SELECT name FROM sqlite_master
            WHERE type='table' AND name NOT LIKE 'sqlite_%'
            ORDER BY name
            """
        ).fetchall()
        tables = [TableRef(name=r["name"], schema="main") for r in rows]
        return _filter_tables(tables, self.config)

    def get_columns(self, table: TableRef) -> list[ColumnMeta]:
        rows = self.conn.execute(f"PRAGMA table_info({self.quote_ident(table.name)})").fetchall()
        cols: list[ColumnMeta] = []
        for r in rows:
            nullable = not bool(r["notnull"])
            portable = map_native_type(r["type"] or "TEXT", nullable=nullable)
            cols.append(
                ColumnMeta(
                    name=r["name"],
                    native_type=r["type"] or "TEXT",
                    portable_type=portable,
                    nullable=nullable,
                    ordinal_position=int(r["cid"]) + 1,
                )
            )
        return cols

    def get_row_count(self, table: TableRef) -> tuple[int | None, bool]:
        # SQLite has no free metadata estimate; always exact COUNT(*).
        row = self.conn.execute(f"SELECT COUNT(*) FROM {self.qualify(table)}").fetchone()
        return int(row[0]), False

    def profile_column_stats(
        self,
        table: TableRef,
        columns: Sequence[ColumnMeta],
        *,
        row_count: int | None,
    ) -> dict[str, ColumnStats]:
        if not columns:
            return {}

        sample_clause, sampled, sample_size, estimate = self._sample_clause(row_count)
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
            estimate_distinct=estimate or sampled,
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
                except sqlite3.Error:
                    # Non-numeric cast failures; skip histogram.
                    pass

        # Stash sampling metadata on first column for table-level use by caller.
        for s in stats.values():
            s.sampled_rows = sample_rows
        self._last_sample_meta = (sampled, sample_size if sampled else sample_rows)
        return stats

    def _sample_clause(
        self, row_count: int | None
    ) -> tuple[str, bool, int | None, bool]:
        cfg = self.config
        if cfg.sample_size is None and cfg.sample_percent is None:
            return "", False, None, False
        if cfg.sample_percent is not None:
            # Approximate percent sampling via ORDER BY RANDOM() LIMIT.
            if row_count is None:
                return "", False, None, False
            n = max(1, int(row_count * (cfg.sample_percent / 100.0)))
            if cfg.sample_size is not None:
                n = min(n, cfg.sample_size)
            return f"ORDER BY RANDOM() LIMIT {n}", True, n, True
        if cfg.sample_size is not None and row_count is not None and row_count > cfg.sample_size:
            return f"ORDER BY RANDOM() LIMIT {cfg.sample_size}", True, cfg.sample_size, True
        return "", False, None, False

    # Populated by profile_column_stats for orchestrator convenience.
    _last_sample_meta: tuple[bool, int | None] = (False, None)


def _filter_tables(tables: list[TableRef], config: ProfilerConfig) -> list[TableRef]:
    out = tables
    if config.include_schemas:
        allow = {s.lower() for s in config.include_schemas}
        out = [t for t in out if (t.schema or "").lower() in allow]
    if config.exclude_schemas:
        deny = {s.lower() for s in config.exclude_schemas}
        out = [t for t in out if (t.schema or "").lower() not in deny]
    if config.include_tables:
        allow_t = {s.lower() for s in config.include_tables}
        out = [t for t in out if t.name.lower() in allow_t or t.fully_qualified_name.lower() in allow_t]
    if config.exclude_tables:
        deny_t = {s.lower() for s in config.exclude_tables}
        out = [t for t in out if t.name.lower() not in deny_t and t.fully_qualified_name.lower() not in deny_t]
    if config.max_tables is not None:
        out = out[: config.max_tables]
    return out
