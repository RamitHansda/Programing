"""Profiler configuration knobs for accuracy vs. speed tradeoffs."""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

import yaml


@dataclass
class ProfilerConfig:
    """Runtime knobs shared by all adapters.

    Accuracy vs speed:
      - sample_size: max rows used for column stats (None = full scan)
      - sample_percent: alternative to sample_size for engines that support TABLESAMPLE
      - include_histograms: enables extra aggregation work
      - histogram_buckets: number of buckets for numeric histograms
      - stats_depth: "basic" (min/max/null/distinct) | "full" (+ histograms)
      - concurrency: parallel table workers
      - max_tables: optional cap for large warehouses
      - include_schemas / exclude_schemas / include_tables / exclude_tables: filters
      - estimate_row_counts: prefer cheap metadata estimates when available
      - resume_state_path: persist incremental progress for crash recovery
    """

    sample_size: int | None = 100_000
    sample_percent: float | None = None
    include_histograms: bool = False
    histogram_buckets: int = 10
    stats_depth: str = "basic"  # basic | full
    concurrency: int = 4
    max_tables: int | None = None
    include_schemas: list[str] = field(default_factory=list)
    exclude_schemas: list[str] = field(default_factory=list)
    include_tables: list[str] = field(default_factory=list)
    exclude_tables: list[str] = field(default_factory=list)
    estimate_row_counts: bool = False
    resume_state_path: str | None = None
    output_format: str = "json"  # json | yaml | parquet
    fail_fast: bool = False
    timeout_seconds_per_table: float | None = None

    def __post_init__(self) -> None:
        if self.stats_depth not in {"basic", "full"}:
            raise ValueError("stats_depth must be 'basic' or 'full'")
        if self.output_format not in {"json", "yaml", "parquet"}:
            raise ValueError("output_format must be json, yaml, or parquet")
        if self.concurrency < 1:
            raise ValueError("concurrency must be >= 1")
        if self.stats_depth == "full":
            self.include_histograms = True

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "ProfilerConfig":
        known = {f.name for f in cls.__dataclass_fields__.values()}  # type: ignore[attr-defined]
        return cls(**{k: v for k, v in data.items() if k in known})

    @classmethod
    def from_yaml(cls, path: str | Path) -> "ProfilerConfig":
        with open(path, encoding="utf-8") as fh:
            raw = yaml.safe_load(fh) or {}
        if "profiler" in raw:
            raw = raw["profiler"]
        return cls.from_dict(raw)
