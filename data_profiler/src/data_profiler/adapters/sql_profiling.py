"""Template-method mixin: one profile_column_stats implementation for SQL engines.

Adapters supply dialect hooks (sample plan, approx distinct, execute).
Concrete adapters should be declared as
``class X(SqlProfilingMixin, DatabaseAdapter)`` so this mixin's
``profile_column_stats`` wins. Do **not** re-declare ``qualify`` /
``quote_ident`` here — that would shadow ``DatabaseAdapter`` implementations
via MRO.
"""

from __future__ import annotations

import logging
from typing import Any, Sequence

from data_profiler.adapters.base import SamplePlan, StatsResult
from data_profiler.adapters.sql_stats import (
    build_distinct_select,
    build_histogram_batch_sql,
    build_stats_select,
    exact_distinct,
    histogram_spec,
    parse_histogram_row,
    parse_stats_row,
)
from data_profiler.models import ColumnMeta, ColumnStats, TableRef
from data_profiler.type_mapping import supports_histogram

logger = logging.getLogger(__name__)


class SqlProfilingMixin:
    """Mixin expecting ``config``, ``quote_ident``, ``qualify`` from DatabaseAdapter."""

    config: Any
    supports_approx_distinct: bool = False

    def build_sample_plan(self, table: TableRef, row_count: int | None) -> SamplePlan:
        """Return a dialect-specific SamplePlan (table suffix and/or row limit)."""
        raise NotImplementedError

    def execute_query(self, sql: str, params: Sequence[Any] | None = None) -> list[tuple]:
        """Run SQL and return rows as sequences."""
        raise NotImplementedError

    def approx_distinct_expr(self, quoted_col: str) -> str:
        """Override for HLL / approx_count_distinct."""
        return exact_distinct(quoted_col)

    def should_use_approx_distinct(self, *, sampled: bool, row_count: int | None) -> bool:
        return sampled or (row_count is not None and row_count > 1_000_000)

    def max_histogram_columns(self) -> int:
        """Cost governor: cap extra histogram queries under stats_depth=full."""
        return int(getattr(self.config, "max_histogram_columns", 20) or 20)

    def profile_column_stats(
        self,
        table: TableRef,
        columns: Sequence[ColumnMeta],
        *,
        row_count: int | None,
    ) -> StatsResult:
        if not columns:
            return StatsResult()

        plan = self.build_sample_plan(table, row_count)
        qualified = self.qualify(table)  # type: ignore[attr-defined]
        # Cardinality from a sample is a floor, not an estimate: 1k rows of a 10M
        # row table can never report more than 1k distinct values. When the engine
        # has an approx aggregate, one extra full-table pass is both cheaper and
        # far more accurate than extrapolating from the sample.
        distinct_over_table = (
            plan.sampled
            and getattr(self.config, "distinct_scope", "table") == "table"
            and self._can_scan_for_distinct(row_count)
        )
        use_approx = self.should_use_approx_distinct(
            sampled=plan.sampled or plan.distinct_is_estimate,
            row_count=row_count,
        )
        distinct_fn = self.approx_distinct_expr if use_approx else exact_distinct

        select_sql, aliases = build_stats_select(
            columns,
            self.config,
            quote_ident=self.quote_ident,  # type: ignore[attr-defined]
            distinct_expr=distinct_fn,
            include_distinct=not distinct_over_table,
        )
        sql = f"SELECT {select_sql} FROM {plan.source(qualified)}"
        rows = self.execute_query(sql)
        if not rows:
            return StatsResult(sampled=plan.sampled, sample_size=plan.sample_size)

        row = list(rows[0])
        sample_rows = int(row[-1]) if row[-1] is not None else 0
        stats = parse_stats_row(
            columns,
            aliases,
            row[:-1],
            sample_rows=sample_rows,
            row_count=row_count,
            estimate_distinct=use_approx,
            sampled=plan.sampled,
            distinct_from_sample=plan.sampled,
        )

        if distinct_over_table:
            self._attach_table_distinct(qualified, columns, stats, row_count=row_count)

        if self.config.include_histograms:
            self._attach_histograms(table, qualified, columns, stats, plan)

        return StatsResult(
            stats=stats,
            sampled=plan.sampled,
            sample_size=plan.sample_size if plan.sampled else sample_rows,
            sample_rows=sample_rows,
            row_count_from_stats=sample_rows if not plan.sampled else None,
        )

    def _can_scan_for_distinct(self, row_count: int | None) -> bool:
        """Whether a full-table cardinality pass is affordable.

        With an approx aggregate it always is — HLL is one cheap pass at any
        size. Without one it means an exact COUNT(DISTINCT) over everything, so
        only run it on a table we know to be small: a sampled run must not
        silently turn into an unbounded scan.
        """
        if self.supports_approx_distinct:
            return True
        return row_count is not None and not self.should_use_approx_distinct(
            sampled=False, row_count=row_count
        )

    def _attach_table_distinct(
        self,
        qualified: str,
        columns: Sequence[ColumnMeta],
        stats: dict[str, ColumnStats],
        *,
        row_count: int | None,
    ) -> None:
        # Exact COUNT(DISTINCT) while the table is small enough to afford it;
        # HLL once it isn't, or when we don't know how big it is.
        approx = self.supports_approx_distinct and (
            row_count is None
            or self.should_use_approx_distinct(sampled=False, row_count=row_count)
        )
        select_sql, aliases = build_distinct_select(
            columns,
            quote_ident=self.quote_ident,  # type: ignore[attr-defined]
            distinct_expr=self.approx_distinct_expr if approx else exact_distinct,
        )
        try:
            rows = self.execute_query(f"SELECT {select_sql} FROM {qualified}")
        except Exception as exc:  # noqa: BLE001 — fall back to the sampled value
            logger.warning(
                "table_distinct_failed table=%s err=%s; keeping sample cardinality",
                qualified,
                exc,
            )
            return
        if not rows:
            return
        parse_stats_row(
            columns,
            aliases,
            list(rows[0]),
            sample_rows=row_count or 0,
            row_count=row_count,
            estimate_distinct=approx,
            sampled=False,
            distinct_from_sample=False,
            into=stats,
        )

    def _attach_histograms(
        self,
        table: TableRef,
        qualified: str,
        columns: Sequence[ColumnMeta],
        stats: dict[str, ColumnStats],
        plan: SamplePlan,
    ) -> None:
        numeric = [c for c in columns if supports_histogram(c.portable_type.kind)]
        capped = numeric[: self.max_histogram_columns()]
        if len(numeric) > len(capped):
            logger.info(
                "histogram_cap table=%s total_numeric=%d capped=%d",
                table.fully_qualified_name,
                len(numeric),
                len(capped),
            )
        specs = []
        for col in capped:
            spec = histogram_spec(col, stats[col.name], self.config.histogram_buckets)
            if spec is not None:
                specs.append(spec)
        if not specs:
            return

        select_sql, aliases = build_histogram_batch_sql(
            specs,
            quote_ident=self.quote_ident,  # type: ignore[attr-defined]
            source=plan.source(qualified),
        )
        try:
            rows = self.execute_query(f"SELECT {select_sql} FROM {plan.source(qualified)}")
        except Exception as exc:  # noqa: BLE001 — histograms are best-effort
            # Warn, not debug: a silent skip here made stats_depth=full look like
            # it worked while every histogram was quietly dropped.
            logger.warning(
                "histogram_failed table=%s columns=%d err=%s",
                table.fully_qualified_name,
                len(specs),
                exc,
            )
            return
        if not rows:
            return
        for column, buckets in parse_histogram_row(specs, aliases, list(rows[0])).items():
            stats[column].histogram = buckets
