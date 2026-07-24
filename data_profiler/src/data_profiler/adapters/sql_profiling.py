"""Template-method mixin: one profile_column_stats implementation for SQL engines.

Adapters supply dialect hooks (sample clause, approx distinct, execute).
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
    build_histogram_sql,
    build_stats_select,
    exact_distinct,
    parse_stats_row,
    rows_to_histogram,
)
from data_profiler.models import ColumnMeta, TableRef
from data_profiler.type_mapping import supports_histogram

logger = logging.getLogger(__name__)


class SqlProfilingMixin:
    """Mixin expecting ``config``, ``quote_ident``, ``qualify`` from DatabaseAdapter."""

    config: Any

    def build_sample_plan(self, table: TableRef, row_count: int | None) -> SamplePlan:
        """Return dialect-specific TABLESAMPLE / LIMIT clause."""
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
        )
        sql = f"SELECT {select_sql} FROM {self.qualify(table)} {plan.clause}".strip()  # type: ignore[attr-defined]
        rows = self.execute_query(sql)
        if not rows:
            return StatsResult(sampled=plan.sampled, sample_size=plan.sample_size)

        row = list(rows[0])
        sample_rows = int(row[-1]) if row else 0
        stats = parse_stats_row(
            columns,
            aliases,
            row[:-1],
            sample_rows=sample_rows,
            row_count=row_count,
            estimate_distinct=use_approx,
            sampled=plan.sampled,
        )

        if self.config.include_histograms:
            self._attach_histograms(table, columns, stats, plan)

        return StatsResult(
            stats=stats,
            sampled=plan.sampled,
            sample_size=plan.sample_size if plan.sampled else sample_rows,
            sample_rows=sample_rows,
            row_count_from_stats=sample_rows if not plan.sampled else None,
        )

    def _attach_histograms(
        self,
        table: TableRef,
        columns: Sequence[ColumnMeta],
        stats: dict,
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
        for col in capped:
            hist_sql = build_histogram_sql(
                col,
                self.config,
                quote_ident=self.quote_ident,  # type: ignore[attr-defined]
                qualify_table=self.qualify(table),  # type: ignore[attr-defined]
                sample_clause=plan.clause,
            )
            if not hist_sql:
                continue
            try:
                hist_rows = self.execute_query(hist_sql)
                stats[col.name].histogram = rows_to_histogram(
                    hist_rows, self.config.histogram_buckets
                )
            except Exception as exc:  # noqa: BLE001 — histogram is best-effort
                logger.debug(
                    "histogram_skipped table=%s column=%s err=%s",
                    table.fully_qualified_name,
                    col.name,
                    exc,
                )
