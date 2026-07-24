"""Portable profile models shared across database engines."""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any


class TypeKind(str, Enum):
    """Engine-agnostic logical type kinds for cross-DB comparison."""

    INTEGER = "integer"
    FLOAT = "float"
    DECIMAL = "decimal"
    BOOLEAN = "boolean"
    STRING = "string"
    BINARY = "binary"
    DATE = "date"
    TIME = "time"
    TIMESTAMP = "timestamp"
    JSON = "json"
    ARRAY = "array"
    MAP = "map"
    STRUCT = "struct"
    UNKNOWN = "unknown"


@dataclass
class PortableType:
    kind: TypeKind
    native: str
    nullable: bool | None = None
    precision: int | None = None
    scale: int | None = None
    max_length: int | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "kind": self.kind.value,
            "native": self.native,
            "nullable": self.nullable,
            "precision": self.precision,
            "scale": self.scale,
            "max_length": self.max_length,
        }


@dataclass
class HistogramBucket:
    label: str
    count: int

    def to_dict(self) -> dict[str, Any]:
        return {"label": self.label, "count": self.count}


@dataclass
class ColumnStats:
    min: Any = None
    max: Any = None
    null_count: int | None = None
    null_ratio: float | None = None
    distinct_count: int | None = None
    distinct_count_is_estimate: bool = False
    sampled_rows: int | None = None
    histogram: list[HistogramBucket] | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "min": _jsonable(self.min),
            "max": _jsonable(self.max),
            "null_count": self.null_count,
            "null_ratio": self.null_ratio,
            "distinct_count": self.distinct_count,
            "distinct_count_is_estimate": self.distinct_count_is_estimate,
            "sampled_rows": self.sampled_rows,
            "histogram": (
                {"buckets": [b.to_dict() for b in self.histogram]}
                if self.histogram is not None
                else None
            ),
        }


@dataclass
class ColumnProfile:
    name: str
    type: PortableType
    comment: str | None = None
    ordinal_position: int | None = None
    stats: ColumnStats = field(default_factory=ColumnStats)

    def to_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "type": self.type.to_dict(),
            "comment": self.comment,
            "ordinal_position": self.ordinal_position,
            "stats": self.stats.to_dict(),
        }


@dataclass
class TableRef:
    name: str
    schema: str | None = None
    catalog: str | None = None

    @property
    def fully_qualified_name(self) -> str:
        parts = [p for p in (self.catalog, self.schema, self.name) if p]
        return ".".join(parts)

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class ColumnMeta:
    name: str
    native_type: str
    portable_type: PortableType
    nullable: bool | None = None
    comment: str | None = None
    ordinal_position: int | None = None


@dataclass
class TableProfile:
    catalog: str | None
    schema: str | None
    name: str
    row_count: int | None
    columns: list[ColumnProfile]
    row_count_is_estimate: bool = False
    comment: str | None = None
    sampled: bool = False
    sample_size: int | None = None
    error: str | None = None
    duration_ms: float | None = None

    @property
    def fully_qualified_name(self) -> str:
        parts = [p for p in (self.catalog, self.schema, self.name) if p]
        return ".".join(parts)

    def to_dict(self) -> dict[str, Any]:
        return {
            "catalog": self.catalog,
            "schema": self.schema,
            "name": self.name,
            "fully_qualified_name": self.fully_qualified_name,
            "row_count": self.row_count,
            "row_count_is_estimate": self.row_count_is_estimate,
            "comment": self.comment,
            "sampled": self.sampled,
            "sample_size": self.sample_size,
            "columns": [c.to_dict() for c in self.columns],
            "error": self.error,
            "duration_ms": self.duration_ms,
        }


@dataclass
class RunMetrics:
    tables_total: int = 0
    tables_profiled: int = 0
    tables_failed: int = 0
    elapsed_seconds: float = 0.0

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class ProfileDocument:
    schema_version: str
    run: dict[str, Any]
    tables: list[TableProfile]

    def to_dict(self) -> dict[str, Any]:
        return {
            "schema_version": self.schema_version,
            "run": self.run,
            "tables": [t.to_dict() for t in self.tables],
        }


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _jsonable(value: Any) -> Any:
    if value is None:
        return None
    if isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, datetime):
        return value.isoformat()
    if isinstance(value, bytes):
        return f"<bytes:{len(value)}>"
    return str(value)
