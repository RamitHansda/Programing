"""Config loading tests."""

from pathlib import Path

from data_profiler.config import ProfilerConfig


def test_from_yaml(tmp_path: Path):
    path = tmp_path / "cfg.yaml"
    path.write_text(
        """
profiler:
  sample_size: 5000
  concurrency: 8
  stats_depth: full
  include_schemas: [public, analytics]
""",
        encoding="utf-8",
    )
    cfg = ProfilerConfig.from_yaml(path)
    assert cfg.sample_size == 5000
    assert cfg.concurrency == 8
    assert cfg.include_histograms is True
    assert cfg.include_schemas == ["public", "analytics"]


def test_invalid_stats_depth():
    try:
        ProfilerConfig(stats_depth="deep")
        assert False, "expected ValueError"
    except ValueError:
        pass
