"""SQLite adapter — fully local, used for demos and unit tests.

Assumptions
-----------
- The connection is opened with ``check_same_thread=False`` and every use is
  serialized behind a lock, so the orchestrator's timeout watchdog can call in
  from another thread. Concurrent table profiling stays disabled: a single
  SQLite file connection gains nothing from parallel readers.
- ``ORDER BY RANDOM() LIMIT n`` is a true uniform sample but costs a full scan
  plus a sort. Acceptable locally; it is not a cost-reduction strategy.
"""

from __future__ import annotations

import sqlite3
import threading
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
    is_thread_safe = True
    supports_approx_distinct = False

    def __init__(self, config: ProfilerConfig, database: str = ":memory:"):
        super().__init__(config)
        self.database = database
        self._conn: sqlite3.Connection | None = None
        self._lock = threading.RLock()

    def connect(self) -> None:
        self._conn = sqlite3.connect(
            self.database,
            timeout=30.0,
            check_same_thread=False,
        )
        self._conn.row_factory = sqlite3.Row

    def close(self) -> None:
        with self._lock:
            if self._conn is not None:
                self._conn.close()
                self._conn = None

    @property
    def conn(self) -> sqlite3.Connection:
        if self._conn is None:
            raise RuntimeError("SQLiteAdapter is not connected")
        return self._conn

    def cancel_active_query(self, *, thread_id: int | None = None) -> None:
        # Connection-wide, which is correct here only because this adapter never
        # profiles two tables at once (supports_concurrent_profiling is False).
        # sqlite3.Connection.interrupt() is documented as callable from another
        # thread while a statement is running.
        conn = self._conn
        if conn is not None:
            conn.interrupt()

    def connection_hint(self) -> str | None:
        return self.database

    def list_tables(self) -> list[TableRef]:
        rows = self._query(
            """
            SELECT name FROM sqlite_master
            WHERE type='table' AND name NOT LIKE 'sqlite_%'
            ORDER BY name
            """
        )
        return filter_tables(
            [TableRef(name=r["name"], schema="main") for r in rows],
            self.config,
        )

    def get_columns(self, table: TableRef) -> list[ColumnMeta]:
        rows = self._query(f"PRAGMA table_info({self.quote_ident(table.name)})")
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
        rows = self._query(f"SELECT COUNT(*) AS n FROM {self.qualify(table)}")
        return int(rows[0][0]), False

    def build_sample_plan(self, table: TableRef, row_count: int | None) -> SamplePlan:
        cfg = self.config
        limit: int | None = None
        if cfg.sample_percent is not None:
            if row_count is not None:
                limit = max(1, int(row_count * (cfg.sample_percent / 100.0)))
                if cfg.sample_size is not None:
                    limit = min(limit, cfg.sample_size)
            else:
                limit = cfg.sample_size
        elif cfg.sample_size is not None:
            limit = cfg.sample_size

        if limit is None:
            return SamplePlan()
        if row_count is not None and row_count <= limit:
            return SamplePlan()
        return SamplePlan(
            row_limit=f"ORDER BY RANDOM() LIMIT {limit}",
            sampled=True,
            sample_size=limit,
            distinct_is_estimate=True,
        )

    def execute_query(self, sql: str, params: Sequence[Any] | None = None) -> list[tuple]:
        return [tuple(r) for r in self._query(sql, params)]

    def _query(self, sql: str, params: Sequence[Any] | None = None) -> list[sqlite3.Row]:
        with self._lock:
            return self.conn.execute(sql, params or []).fetchall()
