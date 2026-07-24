"""Config loading tests."""

from pathlib import Path

import pytest

from data_profiler.config import ProfilerConfig
from data_profiler.errors import ConfigurationError


def test_from_yaml(tmp_path: Path):
    path = tmp_path / "cfg.yaml"
    path.write_text(
        """
profiler:
  sample_size: 5000
  concurrency: 8
  stats_depth: full
  include_schemas: [public, analytics]
  timeout_seconds_per_table: 30
  max_histogram_columns: 5
""",
        encoding="utf-8",
    )
    cfg = ProfilerConfig.from_yaml(path)
    assert cfg.sample_size == 5000
    assert cfg.concurrency == 8
    assert cfg.include_histograms is True
    assert cfg.include_schemas == ["public", "analytics"]
    assert cfg.timeout_seconds_per_table == 30


def test_invalid_stats_depth():
    with pytest.raises(ConfigurationError):
        ProfilerConfig(stats_depth="deep")


def test_invalid_sample_percent():
    with pytest.raises(ConfigurationError):
        ProfilerConfig(sample_percent=0)
