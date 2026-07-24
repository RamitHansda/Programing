"""Persistence helpers: profile output formats and resume checkpoints."""

from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Any

import yaml

from data_profiler.models import ProfileDocument, TableProfile

logger = logging.getLogger(__name__)


def write_profile(doc: ProfileDocument, path: str | Path, fmt: str = "json") -> Path:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = doc.to_dict()

    if fmt == "json":
        path.write_text(json.dumps(payload, indent=2, default=str), encoding="utf-8")
    elif fmt == "yaml":
        path.write_text(yaml.safe_dump(payload, sort_keys=False), encoding="utf-8")
    elif fmt == "parquet":
        _write_parquet(payload, path)
    else:
        raise ValueError(f"Unsupported output format: {fmt}")

    logger.info("Wrote profile to %s (%s)", path, fmt)
    return path


def _write_parquet(payload: dict[str, Any], path: Path) -> None:
    try:
        import pyarrow as pa
        import pyarrow.parquet as pq
    except ImportError as exc:
        raise ImportError(
            "Parquet output requires pyarrow: pip install 'data-profiler[parquet]'"
        ) from exc

    # Flatten column-level stats into a tabular form for analytics consumers.
    rows: list[dict[str, Any]] = []
    run = payload.get("run", {})
    for table in payload.get("tables", []):
        for col in table.get("columns", []):
            stats = col.get("stats") or {}
            rows.append(
                {
                    "run_id": run.get("run_id"),
                    "engine": run.get("engine"),
                    "table_fqn": table.get("fully_qualified_name"),
                    "table_row_count": table.get("row_count"),
                    "column_name": col.get("name"),
                    "type_kind": (col.get("type") or {}).get("kind"),
                    "native_type": (col.get("type") or {}).get("native"),
                    "nullable": (col.get("type") or {}).get("nullable"),
                    "min": _stringify(stats.get("min")),
                    "max": _stringify(stats.get("max")),
                    "null_count": stats.get("null_count"),
                    "null_ratio": stats.get("null_ratio"),
                    "distinct_count": stats.get("distinct_count"),
                    "distinct_count_is_estimate": stats.get("distinct_count_is_estimate"),
                }
            )
    table = pa.Table.from_pylist(rows)
    pq.write_table(table, path)


def _stringify(value: Any) -> str | None:
    if value is None:
        return None
    return str(value)


class ResumeState:
    """Incremental checkpoint so a failed run can skip completed tables."""

    def __init__(self, path: str | Path | None):
        self.path = Path(path) if path else None
        self.completed: dict[str, dict[str, Any]] = {}
        if self.path and self.path.exists():
            raw = json.loads(self.path.read_text(encoding="utf-8"))
            self.completed = raw.get("completed", {})
            logger.info(
                "Loaded resume state with %d completed tables from %s",
                len(self.completed),
                self.path,
            )

    def has(self, fqn: str) -> bool:
        return fqn in self.completed

    def get_table(self, fqn: str) -> TableProfile | None:
        raw = self.completed.get(fqn)
        if not raw:
            return None
        return _table_from_dict(raw)

    def mark(self, profile: TableProfile) -> None:
        if self.path is None:
            return
        self.completed[profile.fully_qualified_name] = profile.to_dict()
        self.flush()

    def flush(self) -> None:
        if self.path is None:
            return
        self.path.parent.mkdir(parents=True, exist_ok=True)
        payload = {"completed": self.completed}
        tmp = self.path.with_suffix(self.path.suffix + ".tmp")
        tmp.write_text(json.dumps(payload, indent=2, default=str), encoding="utf-8")
        tmp.replace(self.path)

    def clear(self) -> None:
        self.completed = {}
        if self.path and self.path.exists():
            self.path.unlink()


def _table_from_dict(raw: dict[str, Any]) -> TableProfile:
    """Best-effort reconstruction for resume (stats already serialized)."""
    from data_profiler.models import (
        ColumnProfile,
        ColumnStats,
        HistogramBucket,
        PortableType,
        TypeKind,
    )

    columns: list[ColumnProfile] = []
    for c in raw.get("columns", []):
        t = c.get("type") or {}
        stats_raw = c.get("stats") or {}
        hist = None
        if stats_raw.get("histogram") and stats_raw["histogram"].get("buckets"):
            hist = [
                HistogramBucket(label=b["label"], count=b["count"])
                for b in stats_raw["histogram"]["buckets"]
            ]
        columns.append(
            ColumnProfile(
                name=c["name"],
                type=PortableType(
                    kind=TypeKind(t.get("kind", "unknown")),
                    native=t.get("native", ""),
                    nullable=t.get("nullable"),
                    precision=t.get("precision"),
                    scale=t.get("scale"),
                    max_length=t.get("max_length"),
                ),
                comment=c.get("comment"),
                ordinal_position=c.get("ordinal_position"),
                stats=ColumnStats(
                    min=stats_raw.get("min"),
                    max=stats_raw.get("max"),
                    null_count=stats_raw.get("null_count"),
                    null_ratio=stats_raw.get("null_ratio"),
                    distinct_count=stats_raw.get("distinct_count"),
                    distinct_count_is_estimate=bool(
                        stats_raw.get("distinct_count_is_estimate", False)
                    ),
                    sampled_rows=stats_raw.get("sampled_rows"),
                    histogram=hist,
                ),
            )
        )
    return TableProfile(
        catalog=raw.get("catalog"),
        schema=raw.get("schema"),
        name=raw["name"],
        row_count=raw.get("row_count"),
        columns=columns,
        row_count_is_estimate=bool(raw.get("row_count_is_estimate", False)),
        comment=raw.get("comment"),
        sampled=bool(raw.get("sampled", False)),
        sample_size=raw.get("sample_size"),
        error=raw.get("error"),
        duration_ms=raw.get("duration_ms"),
    )
