"""Profiler configuration knobs for accuracy vs. speed tradeoffs."""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

import yaml

from data_profiler.errors import ConfigurationError


@dataclass
class ProfilerConfig:
    """Runtime knobs shared by all adapters.

    Accuracy vs speed:
      - sample_size / sample_percent: bound scan cost for column stats
      - include_histograms / stats_depth: extra aggregation work
      - concurrency: parallel table workers (honored only if adapter allows)
      - max_tables / schema-table filters: bound discovery surface
      - estimate_row_counts: prefer cheap metadata estimates when available
      - resume_state_path: persist successful tables for crash recovery
      - timeout_seconds_per_table: soft wall-clock budget per table
      - max_histogram_columns: cost governor for stats_depth=full
      - distinct_scope: cardinality over the whole table vs. over the sample
      - prefetch_catalog: bulk catalog reads instead of per-table queries
      - skip_exact_count_when_sampling: drop row counts to save a COUNT(*)
    """

    sample_size: int | None = 100_000
    sample_percent: float | None = None
    include_histograms: bool = False
    histogram_buckets: int = 10
    max_histogram_columns: int = 20
    stats_depth: str = "basic"  # basic | full
    concurrency: int = 4
    max_tables: int | None = None
    include_schemas: list[str] = field(default_factory=list)
    exclude_schemas: list[str] = field(default_factory=list)
    include_tables: list[str] = field(default_factory=list)
    exclude_tables: list[str] = field(default_factory=list)
    estimate_row_counts: bool = False
    # Off by default: the engines we target answer COUNT(*) from metadata or file
    # statistics, so skipping it saves little and costs every consumer a row
    # count — which also disables null-count extrapolation and the
    # "table smaller than the sample" shortcut.
    skip_exact_count_when_sampling: bool = False
    # "table": one extra full-table approx-distinct pass when sampling, because
    # cardinality measured on a sample is bounded by the sample size.
    # "sample": cheaper, but distinct counts are lower bounds.
    distinct_scope: str = "table"
    prefetch_catalog: bool = True
    resume_state_path: str | None = None
    output_format: str = "json"  # json | yaml | parquet
    fail_fast: bool = False
    timeout_seconds_per_table: float | None = None
    max_retries: int = 1

    def __post_init__(self) -> None:
        if self.stats_depth not in {"basic", "full"}:
            raise ConfigurationError("stats_depth must be 'basic' or 'full'")
        if self.output_format not in {"json", "yaml", "parquet"}:
            raise ConfigurationError("output_format must be json, yaml, or parquet")
        if self.distinct_scope not in {"table", "sample"}:
            raise ConfigurationError("distinct_scope must be 'table' or 'sample'")
        if self.concurrency < 1:
            raise ConfigurationError("concurrency must be >= 1")
        if self.sample_size is not None and self.sample_size < 1:
            raise ConfigurationError("sample_size must be >= 1 (or null to disable sampling)")
        if self.sample_percent is not None and not (0 < self.sample_percent <= 100):
            raise ConfigurationError("sample_percent must be in (0, 100]")
        if self.timeout_seconds_per_table is not None and self.timeout_seconds_per_table <= 0:
            raise ConfigurationError("timeout_seconds_per_table must be > 0")
        if self.max_retries < 0:
            raise ConfigurationError("max_retries must be >= 0")
        if self.stats_depth == "full":
            self.include_histograms = True

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> ProfilerConfig:
        known = {f.name for f in cls.__dataclass_fields__.values()}  # type: ignore[attr-defined]
        return cls(**{k: v for k, v in data.items() if k in known})

    @classmethod
    def from_yaml(cls, path: str | Path) -> ProfilerConfig:
        with open(path, encoding="utf-8") as fh:
            raw = yaml.safe_load(fh) or {}
        if "profiler" in raw:
            raw = raw["profiler"]
        return cls.from_dict(raw)
