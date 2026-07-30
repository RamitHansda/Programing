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


def bundled_schema_path() -> Path:
    """The portable schema ships as package data, so it resolves when installed too."""
    return Path(__file__).resolve().parent / "schema" / "profile_schema.json"


SUPPORTED_SCHEMA_MAJOR = 1


def validate_against_schema(payload: dict[str, Any], schema_path: str | Path | None = None) -> None:
    """Validate a profile document against the bundled JSON Schema when available.

    Uses ``jsonschema`` if installed; otherwise performs a lightweight structural check.

    Version policy: additive fields bump the minor version, and any document
    sharing our major version is accepted. Pinning the exact string would make
    every new optional statistic a breaking change for readers.
    """
    required_top = {"schema_version", "run", "tables"}
    missing = required_top - set(payload)
    if missing:
        raise ValueError(f"Profile missing keys: {sorted(missing)}")
    version = str(payload.get("schema_version", ""))
    major = version.split(".", 1)[0]
    if major != str(SUPPORTED_SCHEMA_MAJOR):
        raise ValueError(f"Unsupported schema_version: {payload.get('schema_version')}")
    if not isinstance(payload.get("tables"), list):
        raise ValueError("tables must be a list")

    if schema_path is None:
        schema_path = bundled_schema_path()
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
                    "table_row_count_is_estimate": table.get("row_count_is_estimate"),
                    "table_sampled": table.get("sampled"),
                    "table_sample_size": table.get("sample_size"),
                    "table_error": table.get("error"),
                    "column_name": col.get("name"),
                    "type_kind": (col.get("type") or {}).get("kind"),
                    "native_type": (col.get("type") or {}).get("native"),
                    "nullable": (col.get("type") or {}).get("nullable"),
                    "comment": col.get("comment"),
                    "min": _stringify(stats.get("min")),
                    "max": _stringify(stats.get("max")),
                    # Provenance travels with the numbers in every format: a
                    # Parquet consumer must not silently lose the distinction
                    # between a measurement and a lower bound.
                    "min_max_from_sample": stats.get("min_max_from_sample"),
                    "null_count": stats.get("null_count"),
                    "null_ratio": stats.get("null_ratio"),
                    "distinct_count": stats.get("distinct_count"),
                    "distinct_count_is_estimate": stats.get("distinct_count_is_estimate"),
                    "distinct_from_sample": stats.get("distinct_from_sample"),
                    "sampled_rows": stats.get("sampled_rows"),
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
    """Append-only checkpoint so a failed run can skip *successful* tables.

    Failures are recorded for observability but intentionally NOT skipped on
    resume — otherwise a transient warehouse error would permanently omit a table.

    State is keyed by a config/engine fingerprint so incompatible re-runs cannot
    silently reuse stale profiles.

    **Why a log rather than a document.** Rewriting the whole checkpoint after
    every table is quadratic in catalog size: 200 tables x 20 columns wrote 286 MB
    to persist a 1.4 MB profile and made the run 4x slower, and the example config
    enables checkpointing by default. The file is now one JSON record per line — a
    fingerprint header, then one line per table outcome — so each table costs one
    append and replaying the log on startup rebuilds the state, last record
    winning. A crash mid-append can only damage the final line, which is dropped
    on load.
    """

    def __init__(self, path: str | Path | None, *, fingerprint: str | None = None):
        self.path = Path(path) if path else None
        self.fingerprint = fingerprint or "none"
        self.completed: dict[str, dict[str, Any]] = {}
        self.failed: dict[str, dict[str, Any]] = {}
        if self.path and self.path.exists():
            self._load(fingerprint)

    def _load(self, fingerprint: str | None) -> None:
        assert self.path is not None
        text = self.path.read_text(encoding="utf-8")
        stored_fp, records = _parse_checkpoint(text, source=str(self.path))
        if stored_fp and fingerprint and stored_fp != fingerprint:
            logger.warning(
                "Resume state fingerprint mismatch (stored=%s current=%s); starting fresh",
                stored_fp,
                fingerprint,
            )
            # Do not append this run's records onto incompatible history.
            self.path.unlink()
            return
        for record in records:
            table = record.get("table")
            if not table:
                continue
            if record.get("ok"):
                self.completed[table] = record.get("profile") or {}
                self.failed.pop(table, None)
            else:
                self.failed[table] = {
                    "error": record.get("error"),
                    "duration_ms": record.get("duration_ms"),
                }
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
        fqn = profile.fully_qualified_name
        payload = profile.to_dict()
        self.completed[fqn] = payload
        self.failed.pop(fqn, None)
        self._append({"table": fqn, "ok": True, "profile": payload})

    def mark_failure(self, profile: TableProfile) -> None:
        if self.path is None:
            return
        fqn = profile.fully_qualified_name
        self.failed[fqn] = {"error": profile.error, "duration_ms": profile.duration_ms}
        self._append(
            {
                "table": fqn,
                "ok": False,
                "error": profile.error,
                "duration_ms": profile.duration_ms,
            }
        )

    def _append(self, record: dict[str, Any]) -> None:
        assert self.path is not None
        self.path.parent.mkdir(parents=True, exist_ok=True)
        new_file = not self.path.exists() or self.path.stat().st_size == 0
        with self.path.open("a", encoding="utf-8") as fh:
            if new_file:
                fh.write(json.dumps({"fingerprint": self.fingerprint}) + "\n")
            fh.write(json.dumps(record, default=str) + "\n")
            fh.flush()

    def clear(self) -> None:
        self.completed = {}
        self.failed = {}
        if self.path and self.path.exists():
            self.path.unlink()


def _parse_checkpoint(text: str, *, source: str) -> tuple[str | None, list[dict[str, Any]]]:
    """Read the append-only log, transparently accepting the older document format."""
    legacy = None
    try:
        legacy = json.loads(text)
    except ValueError:
        legacy = None
    if isinstance(legacy, dict) and ("completed" in legacy or "failed" in legacy):
        records: list[dict[str, Any]] = [
            {"table": table, "ok": True, "profile": profile}
            for table, profile in (legacy.get("completed") or {}).items()
        ]
        records += [
            {"table": table, "ok": False, **(info or {})}
            for table, info in (legacy.get("failed") or {}).items()
        ]
        return legacy.get("fingerprint"), records

    fingerprint: str | None = None
    records = []
    lines = text.splitlines()
    for index, line in enumerate(lines):
        line = line.strip()
        if not line:
            continue
        try:
            record = json.loads(line)
        except ValueError:
            # Only the final line can be torn by a crash mid-append.
            if index == len(lines) - 1:
                logger.warning("Discarding incomplete final checkpoint record in %s", source)
                break
            logger.warning("Skipping malformed checkpoint record %d in %s", index, source)
            continue
        if not isinstance(record, dict):
            continue
        if "table" not in record and "fingerprint" in record:
            fingerprint = record["fingerprint"]
            continue
        records.append(record)
    return fingerprint, records


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
                    distinct_from_sample=bool(stats_raw.get("distinct_from_sample", False)),
                    min_max_from_sample=bool(stats_raw.get("min_max_from_sample", False)),
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
