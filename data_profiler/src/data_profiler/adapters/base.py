"""Database adapter contract."""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any, Sequence

from data_profiler.config import ProfilerConfig
from data_profiler.models import ColumnMeta, ColumnStats, TableRef


class DatabaseAdapter(ABC):
    """Minimal interface each warehouse/file engine must implement.

    Adapters own engine-specific SQL dialect, identifier quoting, and
    metadata catalog access. The profiler orchestrator stays engine-agnostic.
    """

    engine_name: str

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
    ) -> dict[str, ColumnStats]:
        """Compute per-column stats, keyed by column name."""

    def get_table_comment(self, table: TableRef) -> str | None:
        return None

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
