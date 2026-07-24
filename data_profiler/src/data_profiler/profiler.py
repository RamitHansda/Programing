"""Orchestrator: list tables, profile in parallel, persist results."""

from __future__ import annotations

import logging
import time
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Any

from data_profiler.adapters.base import DatabaseAdapter
from data_profiler.config import ProfilerConfig
from data_profiler.models import (
    ColumnProfile,
    ProfileDocument,
    RunMetrics,
    TableProfile,
    TableRef,
    utc_now_iso,
)
from data_profiler.persistence import ResumeState, write_profile

logger = logging.getLogger(__name__)

SCHEMA_VERSION = "1.0.0"


class DataProfiler:
    def __init__(self, adapter: DatabaseAdapter, config: ProfilerConfig | None = None):
        self.adapter = adapter
        self.config = config or adapter.config
        self.resume = ResumeState(self.config.resume_state_path)

    def run(self, output_path: str | None = None) -> ProfileDocument:
        run_id = str(uuid.uuid4())
        started = time.perf_counter()
        started_at = utc_now_iso()
        logger.info(
            "Starting profile run %s on engine=%s concurrency=%s sample_size=%s",
            run_id,
            self.adapter.engine_name,
            self.config.concurrency,
            self.config.sample_size,
        )

        self.adapter.connect()
        try:
            tables = self.adapter.list_tables()
            logger.info("Discovered %d tables", len(tables))
            profiles = self._profile_tables(tables)
        finally:
            self.adapter.close()

        elapsed = time.perf_counter() - started
        failed = sum(1 for p in profiles if p.error)
        metrics = RunMetrics(
            tables_total=len(tables),
            tables_profiled=len(profiles) - failed,
            tables_failed=failed,
            elapsed_seconds=round(elapsed, 3),
        )
        status = "completed" if failed == 0 else ("failed" if metrics.tables_profiled == 0 else "partial")
        doc = ProfileDocument(
            schema_version=SCHEMA_VERSION,
            run={
                "run_id": run_id,
                "engine": self.adapter.engine_name,
                "database": getattr(self.adapter, "database", None),
                "started_at": started_at,
                "finished_at": utc_now_iso(),
                "status": status,
                "config": self.config.to_dict(),
                "metrics": metrics.to_dict(),
            },
            tables=profiles,
        )

        if output_path:
            write_profile(doc, output_path, fmt=self.config.output_format)
            if status == "completed":
                self.resume.clear()

        logger.info(
            "Finished run %s status=%s profiled=%d failed=%d elapsed=%.2fs",
            run_id,
            status,
            metrics.tables_profiled,
            metrics.tables_failed,
            metrics.elapsed_seconds,
        )
        return doc

    def _profile_tables(self, tables: list[TableRef]) -> list[TableProfile]:
        pending = [t for t in tables if not self.resume.has(t.fully_qualified_name)]
        results: list[TableProfile] = []
        for t in tables:
            cached = self.resume.get_table(t.fully_qualified_name)
            if cached is not None:
                results.append(cached)

        if not pending:
            return results

        workers = min(self.config.concurrency, len(pending))
        # Local file engines share a single connection that is not safely
        # concurrent; profile sequentially. Cloud adapters open cursors per call.
        if self.adapter.engine_name in {"sqlite", "duckdb"} or workers == 1:
            for table in pending:
                profile = self._profile_one(table)
                results.append(profile)
                self.resume.mark(profile)
                if profile.error and self.config.fail_fast:
                    break
            return results

        with ThreadPoolExecutor(max_workers=workers) as pool:
            futures = {pool.submit(self._profile_one_isolated, table): table for table in pending}
            for fut in as_completed(futures):
                table = futures[fut]
                try:
                    profile = fut.result()
                except Exception as exc:  # noqa: BLE001
                    logger.exception("Unhandled error profiling %s", table.fully_qualified_name)
                    profile = TableProfile(
                        catalog=table.catalog,
                        schema=table.schema,
                        name=table.name,
                        row_count=None,
                        columns=[],
                        error=str(exc),
                    )
                results.append(profile)
                self.resume.mark(profile)
                if profile.error and self.config.fail_fast:
                    for other in futures:
                        other.cancel()
                    break
        # Stable order by FQN
        results.sort(key=lambda p: p.fully_qualified_name)
        return results

    def _profile_one_isolated(self, table: TableRef) -> TableProfile:
        """Clone connection-bound work for thread pool engines.

        DuckDB/Snowflake/Databricks: each worker uses the shared adapter with
        its own cursor. For engines that need per-thread connections, adapters
        can override later; for this take-home, DuckDB file DBs serialize via
        the connection, and cloud adapters open cursors per call.
        """
        return self._profile_one(table)

    def _profile_one(self, table: TableRef) -> TableProfile:
        t0 = time.perf_counter()
        logger.info("Profiling %s", table.fully_qualified_name)
        try:
            columns_meta = self.adapter.get_columns(table)
            row_count, is_estimate = self.adapter.get_row_count(table)
            comment = self.adapter.get_table_comment(table)
            stats_by_col = self.adapter.profile_column_stats(
                table, columns_meta, row_count=row_count
            )
            sampled, sample_size = getattr(
                self.adapter, "_last_sample_meta", (False, None)
            )
            from data_profiler.models import ColumnStats

            columns = [
                ColumnProfile(
                    name=c.name,
                    type=c.portable_type,
                    comment=c.comment,
                    ordinal_position=c.ordinal_position,
                    stats=stats_by_col.get(c.name, ColumnStats()),
                )
                for c in columns_meta
            ]
            duration_ms = round((time.perf_counter() - t0) * 1000, 2)
            return TableProfile(
                catalog=table.catalog,
                schema=table.schema,
                name=table.name,
                row_count=row_count,
                columns=columns,
                row_count_is_estimate=is_estimate,
                comment=comment,
                sampled=bool(sampled),
                sample_size=sample_size,
                duration_ms=duration_ms,
            )
        except Exception as exc:  # noqa: BLE001
            logger.exception("Failed profiling %s", table.fully_qualified_name)
            return TableProfile(
                catalog=table.catalog,
                schema=table.schema,
                name=table.name,
                row_count=None,
                columns=[],
                error=str(exc),
                duration_ms=round((time.perf_counter() - t0) * 1000, 2),
            )
