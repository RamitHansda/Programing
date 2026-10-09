"""Shared SQL helpers for column-stat aggregation."""

from __future__ import annotations

from typing import Any, Callable, Sequence

from data_profiler.config import ProfilerConfig
from data_profiler.models import ColumnMeta, ColumnStats, HistogramBucket, TypeKind
from data_profiler.type_mapping import is_comparable_for_minmax, supports_histogram

DistinctExpr = Callable[[str], str]


def exact_distinct(quoted_col: str) -> str:
    return f"COUNT(DISTINCT {quoted_col})"


def build_stats_select(
    columns: Sequence[ColumnMeta],
    config: ProfilerConfig,
    *,
    quote_ident: Callable[[str], str],
    distinct_expr: DistinctExpr | None = None,
    include_distinct: bool = True,
) -> tuple[str, list[tuple[str, str]]]:
    """Build a single SELECT that aggregates stats for many columns.

    ``distinct_expr`` lets dialects swap in HLL / approx aggregates without
    duplicating the rest of the select list. ``include_distinct=False`` omits
    distinct counts, for callers that compute cardinality over the full table in
    a separate pass while min/max/nulls come from a sample.
    """
    distinct_fn = distinct_expr or exact_distinct
    pieces: list[str] = []
    aliases: list[tuple[str, str]] = []

    for col in columns:
        q = quote_ident(col.name)
        pieces.append(f"COUNT(*) - COUNT({q}) AS {quote_ident(col.name + '__nulls')}")
        aliases.append((col.name, "nulls"))

        if is_comparable_for_minmax(col.portable_type.kind):
            pieces.append(f"MIN({q}) AS {quote_ident(col.name + '__min')}")
            aliases.append((col.name, "min"))
            pieces.append(f"MAX({q}) AS {quote_ident(col.name + '__max')}")
            aliases.append((col.name, "max"))

        if include_distinct:
            pieces.append(f"{distinct_fn(q)} AS {quote_ident(col.name + '__distinct')}")
            aliases.append((col.name, "distinct"))

    pieces.append("COUNT(*) AS __dp_rows")
    return ", ".join(pieces), aliases


def build_distinct_select(
    columns: Sequence[ColumnMeta],
    *,
    quote_ident: Callable[[str], str],
    distinct_expr: DistinctExpr | None = None,
) -> tuple[str, list[tuple[str, str]]]:
    """Distinct-only select list, for a full-table cardinality pass."""
    distinct_fn = distinct_expr or exact_distinct
    pieces: list[str] = []
    aliases: list[tuple[str, str]] = []
    for col in columns:
        q = quote_ident(col.name)
        pieces.append(f"{distinct_fn(q)} AS {quote_ident(col.name + '__distinct')}")
        aliases.append((col.name, "distinct"))
    return ", ".join(pieces), aliases


def parse_stats_row(
    columns: Sequence[ColumnMeta],
    aliases: list[tuple[str, str]],
    row: Sequence[Any],
    *,
    sample_rows: int,
    row_count: int | None,
    estimate_distinct: bool,
    sampled: bool,
    distinct_from_sample: bool = True,
    into: dict[str, ColumnStats] | None = None,
) -> dict[str, ColumnStats]:
    if len(row) != len(aliases):
        raise ValueError(
            f"stats row width mismatch: got {len(row)} values for {len(aliases)} aliases"
        )
    by_col: dict[str, ColumnStats] = (
        into if into is not None else {c.name: ColumnStats() for c in columns}
    )
    for c in columns:
        by_col.setdefault(c.name, ColumnStats())
    for idx, (col_name, metric) in enumerate(aliases):
        val = row[idx]
        stats = by_col[col_name]
        if metric == "nulls":
            stats.null_count = int(val) if val is not None else None
            if stats.null_count is not None and sample_rows > 0:
                stats.null_ratio = stats.null_count / sample_rows
                if sampled and row_count is not None:
                    stats.null_count = int(round(stats.null_ratio * row_count))
        elif metric == "min":
            stats.min = val
            stats.min_max_from_sample = sampled
        elif metric == "max":
            stats.max = val
            stats.min_max_from_sample = sampled
        elif metric == "distinct":
            stats.distinct_count = int(val) if val is not None else None
            stats.distinct_count_is_estimate = estimate_distinct
            stats.distinct_from_sample = distinct_from_sample
        if metric != "distinct" or distinct_from_sample:
            stats.sampled_rows = sample_rows
    return by_col


def build_histogram_sql(
    column: ColumnMeta,
    config: ProfilerConfig,
    *,
    quote_ident: Callable[[str], str],
    source: str,
) -> str | None:
    """Equi-width histogram over ``source`` (already sample-aware).

    The raw index is clamped into ``[0, buckets - 1]`` in a separate step: the
    naive ``FLOOR((v - min) * buckets / (max - min))`` puts the maximum value in
    a ``buckets``-th bucket, yielding one extra bucket whose range extends past
    the data, and float division can land just short of the maximum on that same
    boundary. Clamping is expressed with CASE rather than LEAST() because SQLite
    spells that MIN(a, b).
    """
    if not supports_histogram(column.portable_type.kind):
        return None
    q = quote_ident(column.name)
    buckets = max(2, config.histogram_buckets)
    top = buckets - 1
    return f"""
    WITH base AS (
      SELECT {q} AS v FROM {source} WHERE {q} IS NOT NULL
    ),
    bounds AS (
      SELECT MIN(v) AS mn, MAX(v) AS mx FROM base
    ),
    raw AS (
      SELECT
        CASE
          WHEN b.mx = b.mn THEN 0
          ELSE CAST(FLOOR((base.v - b.mn) * {buckets} / (b.mx - b.mn)) AS INTEGER)
        END AS bucket_raw,
        b.mn AS mn, b.mx AS mx
      FROM base CROSS JOIN bounds b
    ),
    hist AS (
      SELECT
        CASE
          WHEN bucket_raw > {top} THEN {top}
          WHEN bucket_raw < 0 THEN 0
          ELSE bucket_raw
        END AS bucket,
        mn, mx
      FROM raw
    )
    SELECT bucket, COUNT(*) AS cnt, MIN(mn) AS mn, MAX(mx) AS mx
    FROM hist
    GROUP BY bucket
    ORDER BY bucket
    """


def rows_to_histogram(
    rows: Sequence[Sequence[Any]],
    buckets: int,
) -> list[HistogramBucket]:
    if not rows:
        return []
    buckets = max(2, buckets)
    mn = rows[0][2]
    mx = rows[0][3]
    out: list[HistogramBucket] = []
    for bucket, cnt, _, _ in rows:
        if mn == mx:
            label = f"[{mn}, {mn}]"
        else:
            width = (mx - mn) / buckets
            lo = mn + bucket * width
            hi = mx if bucket >= buckets - 1 else mn + (bucket + 1) * width
            closing = "]" if bucket >= buckets - 1 else ")"
            label = f"[{lo}, {hi}{closing}"
        out.append(HistogramBucket(label=label, count=int(cnt)))
    return out


NUMERIC_KINDS = {
    TypeKind.INTEGER,
    TypeKind.FLOAT,
    TypeKind.DECIMAL,
}
