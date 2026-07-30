"""Shared SQL helpers for column-stat aggregation."""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
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
    distinct_from_sample: bool = False,
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


@dataclass(frozen=True)
class HistogramSpec:
    """Equi-width bucketing for one column, using bounds we already measured."""

    column: str
    low: Any
    high: Any
    buckets: int

    @property
    def degenerate(self) -> bool:
        return self.low == self.high

    def edges(self) -> list[tuple[Any, Any]]:
        width = (self.high - self.low) / self.buckets
        last = self.buckets - 1
        return [
            (self.low + i * width, self.high if i == last else self.low + (i + 1) * width)
            for i in range(self.buckets)
        ]


def histogram_spec(column: ColumnMeta, stats: ColumnStats, buckets: int) -> HistogramSpec | None:
    """Derive a bucketing plan from stats already computed, or None if not possible."""
    if not supports_histogram(column.portable_type.kind):
        return None
    low, high = _numeric(stats.min), _numeric(stats.max)
    if low is None or high is None or high < low:
        return None
    return HistogramSpec(column=column.name, low=low, high=high, buckets=max(2, buckets))


def build_histogram_batch_sql(
    specs: Sequence[HistogramSpec],
    *,
    quote_ident: Callable[[str], str],
    source: str,
) -> tuple[str, list[tuple[str, int]]]:
    """One scan that buckets every numeric column at once.

    The first pass already told us each column's min and max, so there is no need
    to rediscover the bounds in SQL — which is what forced a separate CTE query
    per column, i.e. one extra scan per numeric column per table. Conditional
    aggregates over known edges collapse all of them into a single scan, and
    computing the edges in Python removes the FLOOR/clamp arithmetic that put the
    maximum value in an out-of-range bucket.

    The outer buckets are deliberately open-ended (no lower bound on the first,
    no upper bound on the last). Under sampling this query re-draws its own
    sample, which can contain a value outside the bounds the first pass measured;
    with closed edges that row would fall into no bucket at all and the counts
    would quietly not add up.
    """
    pieces: list[str] = []
    aliases: list[tuple[str, int]] = []
    for spec in specs:
        q = quote_ident(spec.column)
        if spec.degenerate:
            pieces.append(f"COUNT({q}) AS {quote_ident(spec.column + '__h0')}")
            aliases.append((spec.column, 0))
            continue
        for idx, (low, high) in enumerate(spec.edges()):
            if idx == 0:
                predicate = f"{q} < {_literal(high)}"
            elif idx == spec.buckets - 1:
                predicate = f"{q} >= {_literal(low)}"
            else:
                predicate = f"{q} >= {_literal(low)} AND {q} < {_literal(high)}"
            pieces.append(
                f"SUM(CASE WHEN {predicate} THEN 1 ELSE 0 END)"
                f" AS {quote_ident(spec.column + f'__h{idx}')}"
            )
            aliases.append((spec.column, idx))
    return ", ".join(pieces), aliases


def parse_histogram_row(
    specs: Sequence[HistogramSpec],
    aliases: list[tuple[str, int]],
    row: Sequence[Any],
) -> dict[str, list[HistogramBucket]]:
    if len(row) != len(aliases):
        raise ValueError(
            f"histogram row width mismatch: got {len(row)} values for {len(aliases)} aliases"
        )
    by_spec = {spec.column: spec for spec in specs}
    counts: dict[str, dict[int, int]] = {spec.column: {} for spec in specs}
    for idx, (column, bucket) in enumerate(aliases):
        value = row[idx]
        counts[column][bucket] = int(value) if value is not None else 0

    out: dict[str, list[HistogramBucket]] = {}
    for column, buckets in counts.items():
        spec = by_spec[column]
        if spec.degenerate:
            out[column] = [
                HistogramBucket(label=f"[{spec.low}, {spec.high}]", count=buckets.get(0, 0))
            ]
            continue
        out[column] = [
            HistogramBucket(
                label=f"[{low}, {high}{']' if idx == spec.buckets - 1 else ')'}",
                count=buckets.get(idx, 0),
            )
            for idx, (low, high) in enumerate(spec.edges())
        ]
    return out


def _numeric(value: Any) -> Any:
    """Accept only real numbers as histogram bounds; they are inlined as literals."""
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, (int, float, Decimal)):
        return value
    return None


def _literal(value: Any) -> str:
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, int):
        return str(value)
    return repr(float(value))


NUMERIC_KINDS = {
    TypeKind.INTEGER,
    TypeKind.FLOAT,
    TypeKind.DECIMAL,
}
