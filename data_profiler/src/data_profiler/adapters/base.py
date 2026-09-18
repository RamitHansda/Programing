"""Database adapter contract and shared result types."""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any, Sequence

from data_profiler.config import ProfilerConfig
from data_profiler.models import ColumnMeta, ColumnStats, TableRef

SAMPLE_ALIAS = "__dp_sample"


@dataclass(frozen=True)
class SamplePlan:
    """How an adapter will sample a table for stats.

    Two mutually compatible shapes, because dialects put sampling in different
    grammatical positions:

    ``table_suffix``
        Binds to the table reference (``TABLESAMPLE ...``). Safe to combine with
        a ``WHERE`` clause on the same query.
    ``row_limit``
        A query-level clause (``USING SAMPLE ...``, ``ORDER BY RANDOM() LIMIT``).
        It must be wrapped in a derived table: appended to an aggregate query it
        would filter the single result row instead of the scanned rows, silently
        turning the sample into a full scan.

    ``source()`` is the only supported way to render the FROM target.
    """

    table_suffix: str = ""
    row_limit: str = ""
    sampled: bool = False
    sample_size: int | None = None
    distinct_is_estimate: bool = False

    def source(self, qualified_table: str, *, projection: str = "*") -> str:
        base = f"{qualified_table} {self.table_suffix}".strip()
        if not self.row_limit:
            return base
        return f"(SELECT {projection} FROM {base} {self.row_limit}) AS {SAMPLE_ALIAS}"


@dataclass
class StatsResult:
    """Typed return from profile_column_stats — no side-channel state."""

    stats: dict[str, ColumnStats] = field(default_factory=dict)
    sampled: bool = False
    sample_size: int | None = None
    sample_rows: int = 0
    row_count_from_stats: int | None = None


class DatabaseAdapter(ABC):
    """Engine-specific catalog access, dialect, and stats execution.

    Threading contract — two independent capabilities:

    ``is_thread_safe``
        Adapter methods may be invoked from a thread other than the one that
        opened the connection, one call at a time. The orchestrator needs this
        to enforce ``timeout_seconds_per_table``, which runs each table on a
        watchdog thread. Adapters that own a thread-confined handle (raw
        ``sqlite3`` default) must either serialize access themselves or leave
        this False and accept unenforced timeouts.
    ``supports_concurrent_profiling``
        Adapter methods may be invoked from several threads *simultaneously*.
        Implies ``is_thread_safe``.
    """

    engine_name: str
    supports_concurrent_profiling: bool = False
    is_thread_safe: bool = False

    def __init__(self, config: ProfilerConfig):
        self.config = config

    @abstractmethod
    def connect(self) -> None:
        ...

    @abstractmethod
    def close(self) -> None:
        ...

    @abstractmethod
    def list_tables(self) -> list[TableRef]:
        ...

    @abstractmethod
    def get_columns(self, table: TableRef) -> list[ColumnMeta]:
        ...

    @abstractmethod
    def get_row_count(self, table: TableRef) -> tuple[int | None, bool]:
        """Return (count, is_estimate)."""

    @abstractmethod
    def profile_column_stats(
        self,
        table: TableRef,
        columns: Sequence[ColumnMeta],
        *,
        row_count: int | None,
    ) -> StatsResult:
        """Compute per-column stats."""

    def get_table_comment(self, table: TableRef) -> str | None:
        return None

    def prefetch_catalog(self, tables: Sequence[TableRef]) -> None:
        """Bulk-load catalog metadata for ``tables`` in as few queries as possible.

        Warehouse round trips dominate wall clock on wide catalogs: profiling N
        tables one at a time costs O(N) metadata queries before a single
        statistic is computed. Adapters that can read ``information_schema`` for
        a whole schema at once override this and serve ``get_columns`` /
        ``get_row_count`` / ``get_table_comment`` from the cache, falling back to
        per-table queries on a cache miss.
        """
        return None

    def cancel_active_query(self, *, thread_id: int | None = None) -> None:
        """Best-effort abort of in-flight statements on this connection.

        Called from the orchestrator's watchdog thread when a table blows its
        time budget. Without it, a per-table timeout can only abandon the result
        — the worker thread stays parked on the socket and the run still waits.

        ``thread_id`` scopes the cancellation to statements issued by that
        thread, so timing out one table cannot kill its siblings when
        ``supports_concurrent_profiling`` is True. Adapters that can only cancel
        connection-wide must not set ``supports_concurrent_profiling``.
        """
        return None

    def connection_hint(self) -> str | None:
        """Stable, non-secret identifier for resume fingerprinting."""
        return getattr(self, "database", None)

    def quote_ident(self, ident: str) -> str:
        return '"' + ident.replace('"', '""') + '"'

    def qualify(self, table: TableRef) -> str:
        parts = [p for p in (table.catalog, table.schema, table.name) if p]
        return ".".join(self.quote_ident(p) for p in parts)

    def __enter__(self) -> DatabaseAdapter:
        self.connect()
        return self

    def __exit__(self, exc_type: Any, exc: Any, tb: Any) -> None:
        self.close()
