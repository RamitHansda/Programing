"""Persistence helpers: profile output formats and resume checkpoints."""

from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Any

import yaml

from data_profiler.models import (
    ColumnProfile,
    ColumnStats,
    HistogramBucket,
    PortableType,
    ProfileDocument,
    TableProfile,
    TypeKind,
)

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


def validate_against_schema(payload: dict[str, Any], schema_path: str | Path | None = None) -> None:
    """Validate a profile document against the bundled JSON Schema when available.

    Uses ``jsonschema`` if installed; otherwise performs a lightweight structural check.
    """
    required_top = {"schema_version", "run", "tables"}
    missing = required_top - set(payload)
    if missing:
        raise ValueError(f"Profile missing keys: {sorted(missing)}")
    if payload.get("schema_version") != "1.0.0":
        raise ValueError(f"Unsupported schema_version: {payload.get('schema_version')}")
    if not isinstance(payload.get("tables"), list):
        raise ValueError("tables must be a list")

    if schema_path is None:
        schema_path = Path(__file__).resolve().parents[2] / "schema" / "profile_schema.json"
    schema_path = Path(schema_path)
    if not schema_path.exists():
        return
    try:
        import jsonschema  # type: ignore
    except ImportError:
        return
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    jsonschema.validate(payload, schema)


def _write_parquet(payload: dict[str, Any], path: Path) -> None:
    try:
        import pyarrow as pa
        import pyarrow.parquet as pq
    except ImportError as exc:
        raise ImportError(
            "Parquet output requires pyarrow: pip install 'data-profiler[parquet]'"
        ) from exc

    # Analytics-oriented flatten. Full fidelity remains in JSON/YAML.
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
                    "table_error": table.get("error"),
                    "column_name": col.get("name"),
                    "type_kind": (col.get("type") or {}).get("kind"),
                    "native_type": (col.get("type") or {}).get("native"),
                    "nullable": (col.get("type") or {}).get("nullable"),
                    "comment": col.get("comment"),
                    "min": _stringify(stats.get("min")),
                    "max": _stringify(stats.get("max")),
                    "null_count": stats.get("null_count"),
                    "null_ratio": stats.get("null_ratio"),
                    "distinct_count": stats.get("distinct_count"),
                    "distinct_count_is_estimate": stats.get("distinct_count_is_estimate"),
                }
            )
    table = pa.Table.from_pylist(rows)
    # Keep run metadata as parquet file metadata for lineage.
    metadata = {
        b"data_profiler_schema_version": str(payload.get("schema_version", "")).encode(),
        b"data_profiler_run_json": json.dumps(run, default=str).encode(),
    }
    table = table.replace_schema_metadata(metadata)
    pq.write_table(table, path)


def _stringify(value: Any) -> str | None:
    if value is None:
        return None
    return str(value)


class ResumeState:
    """Incremental checkpoint so a failed run can skip *successful* tables.

    Failures are recorded for observability but intentionally NOT skipped on
    resume — otherwise a transient warehouse error would permanently omit a table.

    State is keyed by a config/engine fingerprint so incompatible re-runs cannot
    silently reuse stale profiles.
    """

    def __init__(self, path: str | Path | None, *, fingerprint: str | None = None):
        self.path = Path(path) if path else None
        self.fingerprint = fingerprint or "none"
        self.completed: dict[str, dict[str, Any]] = {}
        self.failed: dict[str, dict[str, Any]] = {}
        if self.path and self.path.exists():
            raw = json.loads(self.path.read_text(encoding="utf-8"))
            stored_fp = raw.get("fingerprint")
            if stored_fp and fingerprint and stored_fp != fingerprint:
                logger.warning(
                    "Resume state fingerprint mismatch (stored=%s current=%s); starting fresh",
                    stored_fp,
                    fingerprint,
                )
            else:
                self.completed = raw.get("completed", {})
                self.failed = raw.get("failed", {})
                if stored_fp:
                    self.fingerprint = stored_fp
                logger.info(
                    "Loaded resume state successes=%d failures=%d from %s",
                    len(self.completed),
                    len(self.failed),
                    self.path,
                )

    def get_successful(self, fqn: str) -> TableProfile | None:
        raw = self.completed.get(fqn)
        if not raw:
            return None
        return table_from_dict(raw)

    def mark_success(self, profile: TableProfile) -> None:
        if self.path is None:
            return
        self.completed[profile.fully_qualified_name] = profile.to_dict()
        self.failed.pop(profile.fully_qualified_name, None)
        self.flush()

    def mark_failure(self, profile: TableProfile) -> None:
        if self.path is None:
            return
        self.failed[profile.fully_qualified_name] = {
            "error": profile.error,
            "duration_ms": profile.duration_ms,
        }
        self.flush()

    def flush(self) -> None:
        if self.path is None:
            return
        self.path.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "fingerprint": self.fingerprint,
            "completed": self.completed,
            "failed": self.failed,
        }
        tmp = self.path.with_suffix(self.path.suffix + ".tmp")
        tmp.write_text(json.dumps(payload, indent=2, default=str), encoding="utf-8")
        tmp.replace(self.path)

    def clear(self) -> None:
        self.completed = {}
        self.failed = {}
        if self.path and self.path.exists():
            self.path.unlink()


def table_from_dict(raw: dict[str, Any]) -> TableProfile:
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
