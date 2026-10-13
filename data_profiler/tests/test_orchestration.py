"""Orchestrator behaviour: budgets, concurrency, retry classification.

These paths had no coverage, which is how a per-table timeout shipped that both
broke thread-confined adapters and failed to bound wall clock.
"""

from __future__ import annotations

import sqlite3
import threading
import time
from pathlib import Path

import pytest

from data_profiler.adapters.base import DatabaseAdapter, StatsResult
from data_profiler.config import ProfilerConfig
from data_profiler.errors import AdapterError
from data_profiler.models import ColumnMeta, PortableType, TableRef, TypeKind
from data_profiler.profiler import DataProfiler


class FakeAdapter(DatabaseAdapter):
    """Minimal adapter with scriptable latency and failures."""

    engine_name = "sqlite"  # keeps documents valid against the portable schema

    def __init__(
        self,
        config: ProfilerConfig,
        *,
        tables: int = 1,
        delay: float = 0.0,
        fail_with: BaseException | None = None,
        thread_safe: bool = True,
        concurrent: bool = False,
    ):
        super().__init__(config)
        self._tables = tables
        self._delay = delay
        self._fail_with = fail_with
        self.is_thread_safe = thread_safe
        self.supports_concurrent_profiling = concurrent
        self.attempts = 0
        self.cancelled = threading.Event()
        self.cancel_thread_ids: list[int | None] = []
        self.active = 0
        self.max_active = 0
        self._lock = threading.Lock()

    def connect(self) -> None:
        return None

    def close(self) -> None:
        return None

    def list_tables(self) -> list[TableRef]:
        return [TableRef(name=f"t{i}", schema="main") for i in range(self._tables)]

    def get_columns(self, table: TableRef) -> list[ColumnMeta]:
        with self._lock:
            self.attempts += 1
            self.active += 1
            self.max_active = max(self.max_active, self.active)
        try:
            if self._fail_with is not None:
                raise self._fail_with
            deadline = time.perf_counter() + self._delay
            while time.perf_counter() < deadline:
                if self.cancelled.is_set():
                    break
                time.sleep(0.01)
            return [
                ColumnMeta(
                    name="id",
                    native_type="INTEGER",
                    portable_type=PortableType(kind=TypeKind.INTEGER, native="INTEGER"),
                )
            ]
        finally:
            with self._lock:
                self.active -= 1

    def get_row_count(self, table: TableRef) -> tuple[int | None, bool]:
        return 1, False

    def profile_column_stats(self, table, columns, *, row_count) -> StatsResult:
        return StatsResult()

    def cancel_active_query(self, *, thread_id: int | None = None) -> None:
        self.cancel_thread_ids.append(thread_id)
        self.cancelled.set()


def test_timeout_bounds_wall_clock_and_cancels_the_query():
    config = ProfilerConfig(
        sample_size=None, concurrency=1, timeout_seconds_per_table=0.3, max_retries=0
    )
    adapter = FakeAdapter(config, delay=10.0)
    started = time.perf_counter()
    doc = DataProfiler(adapter, config).run()
    elapsed = time.perf_counter() - started

    assert "exceeded timeout" in (doc.tables[0].error or "")
    assert elapsed < 3.0, f"timeout must not wait out the query (took {elapsed:.1f}s)"
    assert adapter.cancelled.is_set(), "adapter should be asked to cancel"
    assert adapter.cancel_thread_ids and adapter.cancel_thread_ids[0] is not None, (
        "cancellation must be scoped to the worker thread so siblings survive"
    )


def test_timeout_is_skipped_for_thread_confined_adapters(caplog: pytest.LogCaptureFixture):
    config = ProfilerConfig(
        sample_size=None, concurrency=1, timeout_seconds_per_table=5.0, max_retries=0
    )
    adapter = FakeAdapter(config, thread_safe=False)
    main_thread = threading.get_ident()
    seen: list[int] = []
    original = adapter.get_columns

    def record(table):
        seen.append(threading.get_ident())
        return original(table)

    adapter.get_columns = record  # type: ignore[method-assign]
    doc = DataProfiler(adapter, config).run()

    assert doc.run["status"] == "completed"
    assert seen == [main_thread], "thread-confined adapters must stay on the calling thread"
    assert any("cannot be enforced" in r.message for r in caplog.records)


def test_sqlite_tolerates_a_configured_timeout(tmp_path: Path):
    """The documented example config sets a timeout; that used to fail every table."""
    path = tmp_path / "t.sqlite"
    conn = sqlite3.connect(path)
    conn.execute("CREATE TABLE items (id INTEGER, name TEXT)")
    conn.execute("INSERT INTO items VALUES (1, 'a')")
    conn.commit()
    conn.close()

    from data_profiler.adapters import create_adapter

    config = ProfilerConfig(sample_size=None, concurrency=1, timeout_seconds_per_table=30.0)
    adapter = create_adapter("sqlite", config, database=str(path))
    doc = DataProfiler(adapter, config).run()
    assert doc.run["status"] == "completed"
    assert doc.tables[0].row_count == 1


def test_tables_run_in_parallel_when_the_adapter_allows_it():
    config = ProfilerConfig(sample_size=None, concurrency=4)
    adapter = FakeAdapter(config, tables=8, delay=0.2, concurrent=True)
    doc = DataProfiler(adapter, config).run()
    assert doc.run["status"] == "completed"
    assert adapter.max_active > 1, "concurrency must actually overlap table work"
    assert adapter.max_active <= 4


def test_sequential_when_adapter_forbids_concurrency():
    config = ProfilerConfig(sample_size=None, concurrency=4)
    adapter = FakeAdapter(config, tables=6, delay=0.05, concurrent=False)
    DataProfiler(adapter, config).run()
    assert adapter.max_active == 1


def test_transient_adapter_errors_are_retried_without_string_matching():
    config = ProfilerConfig(sample_size=None, concurrency=1, max_retries=2)
    adapter = FakeAdapter(
        config,
        fail_with=AdapterError("warehouse says no", transient=True),
    )
    doc = DataProfiler(adapter, config).run()
    assert adapter.attempts == 3
    assert doc.run["status"] == "failed"


def test_permanent_adapter_errors_are_not_retried():
    config = ProfilerConfig(sample_size=None, concurrency=1, max_retries=2)
    adapter = FakeAdapter(config, fail_with=AdapterError("column not found", transient=False))
    DataProfiler(adapter, config).run()
    assert adapter.attempts == 1


def test_catalog_prefetch_is_invoked_once_for_pending_tables():
    config = ProfilerConfig(sample_size=None, concurrency=1)
    adapter = FakeAdapter(config, tables=3)
    calls: list[int] = []
    adapter.prefetch_catalog = lambda tables: calls.append(len(tables))  # type: ignore[method-assign]
    DataProfiler(adapter, config).run()
    assert calls == [3]
