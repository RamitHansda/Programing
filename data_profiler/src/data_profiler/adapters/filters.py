"""Table discovery filters shared by all adapters."""

from __future__ import annotations

from data_profiler.config import ProfilerConfig
from data_profiler.models import TableRef


def filter_tables(tables: list[TableRef], config: ProfilerConfig) -> list[TableRef]:
    """Apply include/exclude schema & table filters, then optional max_tables cap."""
    out = tables
    if config.include_schemas:
        allow = {s.lower() for s in config.include_schemas}
        out = [t for t in out if (t.schema or "").lower() in allow]
    if config.exclude_schemas:
        deny = {s.lower() for s in config.exclude_schemas}
        out = [t for t in out if (t.schema or "").lower() not in deny]
    if config.include_tables:
        allow_t = {s.lower() for s in config.include_tables}
        out = [
            t
            for t in out
            if t.name.lower() in allow_t or t.fully_qualified_name.lower() in allow_t
        ]
    if config.exclude_tables:
        deny_t = {s.lower() for s in config.exclude_tables}
        out = [
            t
            for t in out
            if t.name.lower() not in deny_t and t.fully_qualified_name.lower() not in deny_t
        ]
    if config.max_tables is not None:
        out = out[: config.max_tables]
    return out
