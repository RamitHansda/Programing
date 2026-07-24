"""SQLite adapter — fully local, used for demos and unit tests."""

from __future__ import annotations

import sqlite3
from typing import Any, Sequence

from data_profiler.adapters.base import DatabaseAdapter, SamplePlan
from data_profiler.adapters.filters import filter_tables
from data_profiler.adapters.sql_profiling import SqlProfilingMixin
from data_profiler.config import ProfilerConfig
from data_profiler.models import ColumnMeta, TableRef
from data_profiler.type_mapping import map_native_type


class SQLiteAdapter(SqlProfilingMixin, DatabaseAdapter):
    engine_name = "sqlite"
    supports_concurrent_profiling = False

    def __init__(self, config: ProfilerConfig, database: str = ":memory:"):
        super().__init__(config)
        self.database = database
        self._conn: sqlite3.Connection | None = None

    def connect(self) -> None:
        self._conn = sqlite3.connect(self.database, timeout=30.0)
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

    def connection_hint(self) -> str | None:
        return self.database

    def list_tables(self) -> list[TableRef]:
        rows = self.conn.execute(
            """
            SELECT name FROM sqlite_master
            WHERE type='table' AND name NOT LIKE 'sqlite_%'
            ORDER BY name
            """
        ).fetchall()
        return filter_tables(
            [TableRef(name=r["name"], schema="main") for r in rows],
            self.config,
        )

    def get_columns(self, table: TableRef) -> list[ColumnMeta]:
        rows = self.conn.execute(
            f"PRAGMA table_info({self.quote_ident(table.name)})"
        ).fetchall()
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
        # SQLite COUNT(*) is cheap locally; always exact.
        row = self.conn.execute(f"SELECT COUNT(*) FROM {self.qualify(table)}").fetchone()
        return int(row[0]), False

    def build_sample_plan(self, table: TableRef, row_count: int | None) -> SamplePlan:
        cfg = self.config
        if cfg.sample_percent is not None:
            if row_count is None:
                # Without a known row count, approximate via a fixed limit if set.
                if cfg.sample_size is not None:
                    return SamplePlan(
                        clause=f"ORDER BY RANDOM() LIMIT {cfg.sample_size}",
                        sampled=True,
                        sample_size=cfg.sample_size,
                        distinct_is_estimate=True,
                    )
                return SamplePlan()
            n = max(1, int(row_count * (cfg.sample_percent / 100.0)))
            if cfg.sample_size is not None:
                n = min(n, cfg.sample_size)
            if row_count <= n:
                return SamplePlan()
            return SamplePlan(
                clause=f"ORDER BY RANDOM() LIMIT {n}",
                sampled=True,
                sample_size=n,
                distinct_is_estimate=True,
            )
        if cfg.sample_size is not None:
            if row_count is not None and row_count <= cfg.sample_size:
                return SamplePlan()
            # Unknown row_count: still bound the scan.
            if row_count is None or row_count > cfg.sample_size:
                return SamplePlan(
                    clause=f"ORDER BY RANDOM() LIMIT {cfg.sample_size}",
                    sampled=True,
                    sample_size=cfg.sample_size,
                    distinct_is_estimate=True,
                )
        return SamplePlan()

    def execute_query(self, sql: str, params: Sequence[Any] | None = None) -> list[tuple]:
        cur = self.conn.execute(sql, params or [])
        return [tuple(r) for r in cur.fetchall()]
