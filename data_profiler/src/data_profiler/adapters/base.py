"""Database adapter contract and shared result types."""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any, Sequence

from data_profiler.config import ProfilerConfig
from data_profiler.models import ColumnMeta, ColumnStats, TableRef


@dataclass(frozen=True)
class SamplePlan:
    """How an adapter will sample a table for stats."""

    clause: str = ""
    sampled: bool = False
    sample_size: int | None = None
    distinct_is_estimate: bool = False


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

    Thread-safety contract:
      - If ``supports_concurrent_profiling`` is False, the orchestrator will
        never call adapter methods from multiple threads.
      - If True, adapters MUST tolerate concurrent ``profile_column_stats`` /
        ``get_columns`` calls (typically via per-call cursors or pooled conns).
    """

    engine_name: str
    supports_concurrent_profiling: bool = False

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

    def connection_hint(self) -> str | None:
        """Stable, non-secret identifier for resume fingerprinting."""
        return getattr(self, "database", None)

    def quote_ident(self, ident: str) -> str:
        return '"' + ident.replace('"', '""') + '"'

    def qualify(self, table: TableRef) -> str:
        parts = [p for p in (table.catalog, table.schema, table.name) if p]
        return ".".join(self.quote_ident(p) for p in parts)

    def __enter__(self) -> "DatabaseAdapter":
        self.connect()
        return self

    def __exit__(self, exc_type: Any, exc: Any, tb: Any) -> None:
        self.close()
