"""Orchestrator: discover tables, profile with budgets, persist results."""

from __future__ import annotations

import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from concurrent.futures import TimeoutError as FuturesTimeout
from dataclasses import dataclass

from data_profiler.adapters.base import DatabaseAdapter, StatsResult
from data_profiler.config import ProfilerConfig
from data_profiler.errors import AdapterError, TableTimeoutError
from data_profiler.models import (
    ColumnProfile,
    ColumnStats,
    ProfileDocument,
    RunMetrics,
    TableProfile,
    TableRef,
    utc_now_iso,
)
from data_profiler.observability import config_fingerprint, get_logger, log_event, redact
from data_profiler.persistence import ResumeState, write_profile

logger = get_logger(__name__)

SCHEMA_VERSION = "1.1.0"


@dataclass
class AttemptResult:
    """One profiling attempt, with retryability carried as data, not as a string."""

    profile: TableProfile
    transient: bool = False


class DataProfiler:
    def __init__(self, adapter: DatabaseAdapter, config: ProfilerConfig | None = None):
        self.adapter = adapter
        self.config = config or adapter.config
        fingerprint = config_fingerprint(
            adapter.engine_name,
            self.config.to_dict(),
            adapter.connection_hint(),
        )
        self.resume = ResumeState(
            self.config.resume_state_path,
            fingerprint=fingerprint,
        )

    def run(self, output_path: str | None = None) -> ProfileDocument:
        run_id = str(uuid.uuid4())
        started = time.perf_counter()
        started_at = utc_now_iso()
        log_event(
            logger,
            "run_started",
            run_id=run_id,
            engine=self.adapter.engine_name,
            concurrency=self.config.concurrency,
            sample_size=self.config.sample_size,
            stats_depth=self.config.stats_depth,
            supports_concurrent=self.adapter.supports_concurrent_profiling,
            timeout_enforceable=self._timeout_enforceable(),
        )

        self.adapter.connect()
        try:
            tables = self.adapter.list_tables()
            log_event(logger, "tables_discovered", run_id=run_id, count=len(tables))
            profiles = self._profile_tables(tables, run_id=run_id)
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
        status = (
            "completed"
            if failed == 0
            else ("failed" if metrics.tables_profiled == 0 else "partial")
        )
        doc = ProfileDocument(
            schema_version=SCHEMA_VERSION,
            run={
                "run_id": run_id,
                "engine": self.adapter.engine_name,
                "database": self.adapter.connection_hint(),
                "started_at": started_at,
                "finished_at": utc_now_iso(),
                "status": status,
                "config": redact(self.config.to_dict()),
                "metrics": metrics.to_dict(),
                "resume_fingerprint": self.resume.fingerprint,
            },
            tables=profiles,
        )

        if output_path:
            write_profile(doc, output_path, fmt=self.config.output_format)
            if status == "completed":
                self.resume.clear()

        log_event(
            logger,
            "run_finished",
            run_id=run_id,
            status=status,
            profiled=metrics.tables_profiled,
            failed=metrics.tables_failed,
            elapsed_seconds=metrics.elapsed_seconds,
        )
        return doc

    def _timeout_enforceable(self) -> bool:
        """A per-table timeout needs a watchdog thread, which needs a safe adapter."""
        return self.config.timeout_seconds_per_table is not None and self.adapter.is_thread_safe

    def _profile_tables(self, tables: list[TableRef], *, run_id: str) -> list[TableProfile]:
        results: list[TableProfile] = []
        pending: list[TableRef] = []
        for t in tables:
            cached = self.resume.get_successful(t.fully_qualified_name)
            if cached is not None:
                results.append(cached)
            else:
                pending.append(t)

        if not pending:
            return results

        if self.config.timeout_seconds_per_table is not None and not self.adapter.is_thread_safe:
            logger.warning(
                "%s is thread-confined; timeout_seconds_per_table cannot be enforced "
                "and will be ignored",
                self.adapter.engine_name,
            )

        if self.config.prefetch_catalog:
            t0 = time.perf_counter()
            self.adapter.prefetch_catalog(pending)
            log_event(
                logger,
                "catalog_prefetched",
                run_id=run_id,
                tables=len(pending),
                elapsed_seconds=round(time.perf_counter() - t0, 3),
            )

        use_pool = (
            self.adapter.supports_concurrent_profiling
            and self.config.concurrency > 1
            and len(pending) > 1
        )
        if not use_pool:
            for table in pending:
                profile = self._profile_one_with_retry(table, run_id=run_id)
                results.append(profile)
                self._checkpoint(profile)
                if profile.error and self.config.fail_fast:
                    break
            results.sort(key=lambda p: p.fully_qualified_name)
            return results

        workers = min(self.config.concurrency, len(pending))
        with ThreadPoolExecutor(max_workers=workers, thread_name_prefix="dp-table") as pool:
            futures = {
                pool.submit(self._profile_one_with_retry, table, run_id=run_id): table
                for table in pending
            }
            for fut in as_completed(futures):
                table = futures[fut]
                try:
                    profile = fut.result()
                except Exception as exc:  # noqa: BLE001
                    logger.exception("Unhandled error profiling %s", table.fully_qualified_name)
                    profile = self._error_profile(table, str(exc))
                results.append(profile)
                self._checkpoint(profile)
                if profile.error and self.config.fail_fast:
                    for other in futures:
                        other.cancel()
                    break
        results.sort(key=lambda p: p.fully_qualified_name)
        return results

    def _checkpoint(self, profile: TableProfile) -> None:
        # Only successful tables are skipped on resume; failures remain retryable.
        if profile.error is None:
            self.resume.mark_success(profile)
        else:
            self.resume.mark_failure(profile)

    def _profile_one_with_retry(self, table: TableRef, *, run_id: str) -> TableProfile:
        attempts = self.config.max_retries + 1
        last: AttemptResult | None = None
        for attempt in range(1, attempts + 1):
            last = self._profile_one(table, run_id=run_id, attempt=attempt)
            if last.profile.error is None:
                return last.profile
            if attempt < attempts and last.transient:
                log_event(
                    logger,
                    "table_retry",
                    run_id=run_id,
                    table=table.fully_qualified_name,
                    attempt=attempt,
                    error=last.profile.error,
                )
                time.sleep(min(2 ** (attempt - 1), 8))
                continue
            break
        assert last is not None
        return last.profile

    @staticmethod
    def _looks_transient(error: str | None) -> bool:
        """Fallback classification for exceptions that are not AdapterError."""
        if not error:
            return False
        msg = error.lower()
        return any(
            token in msg
            for token in ("timeout", "temporar", "retry", "throttle", "429", "503", "suspend")
        )

    def _error_profile(self, table: TableRef, error: str, duration_ms: float | None = None):
        return TableProfile(
            catalog=table.catalog,
            schema=table.schema,
            name=table.name,
            row_count=None,
            columns=[],
            error=error,
            duration_ms=duration_ms,
        )

    def _profile_one(self, table: TableRef, *, run_id: str, attempt: int = 1) -> AttemptResult:
        t0 = time.perf_counter()
        log_event(
            logger,
            "table_started",
            run_id=run_id,
            table=table.fully_qualified_name,
            attempt=attempt,
        )
        try:
            if self._timeout_enforceable():
                profile = self._profile_with_watchdog(
                    table, float(self.config.timeout_seconds_per_table)
                )
            else:
                profile = self._profile_one_body(table)
            profile.duration_ms = round((time.perf_counter() - t0) * 1000, 2)
            log_event(
                logger,
                "table_finished",
                run_id=run_id,
                table=table.fully_qualified_name,
                duration_ms=profile.duration_ms,
                row_count=profile.row_count,
                columns=len(profile.columns),
                error=profile.error,
            )
            return AttemptResult(profile)
        except AdapterError as exc:
            elapsed_ms = round((time.perf_counter() - t0) * 1000, 2)
            log_event(
                logger,
                "table_failed",
                run_id=run_id,
                table=table.fully_qualified_name,
                duration_ms=elapsed_ms,
                error=str(exc),
                transient=exc.transient,
            )
            return AttemptResult(
                self._error_profile(table, str(exc), elapsed_ms),
                transient=exc.transient,
            )
        except Exception as exc:  # noqa: BLE001
            logger.exception("Failed profiling %s", table.fully_qualified_name)
            return AttemptResult(
                self._error_profile(
                    table, str(exc), round((time.perf_counter() - t0) * 1000, 2)
                ),
                transient=self._looks_transient(str(exc)),
            )

    def _profile_with_watchdog(self, table: TableRef, timeout: float) -> TableProfile:
        """Run one table on a watchdog thread and abort it when the budget expires.

        The point of the budget is to bound wall clock, so on expiry we ask the
        adapter to cancel its in-flight statement and shut the executor down
        without waiting. Blocking on the abandoned thread — which is what a
        ``with ThreadPoolExecutor(...)`` block does on exit — would surface a
        timeout error while still paying the full runtime.
        """
        worker: dict[str, int] = {}

        def body() -> TableProfile:
            worker["thread_id"] = threading.get_ident()
            return self._profile_one_body(table)

        pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="dp-watchdog")
        try:
            future = pool.submit(body)
            try:
                return future.result(timeout=timeout)
            except FuturesTimeout as exc:
                self.adapter.cancel_active_query(thread_id=worker.get("thread_id"))
                raise TableTimeoutError(table.fully_qualified_name, timeout) from exc
        finally:
            pool.shutdown(wait=False)

    def _profile_one_body(self, table: TableRef) -> TableProfile:
        columns_meta = self.adapter.get_columns(table)
        row_count, is_estimate = self.adapter.get_row_count(table)
        comment = self.adapter.get_table_comment(table)
        result: StatsResult = self.adapter.profile_column_stats(
            table, columns_meta, row_count=row_count
        )

        # Prefer exact COUNT(*) from the unsampled stats query when we deferred it.
        if row_count is None and result.row_count_from_stats is not None:
            row_count = result.row_count_from_stats
            is_estimate = False
        elif (
            row_count is None
            and result.sampled
            and result.sample_size is not None
            and result.sample_rows < result.sample_size
        ):
            # SAMPLE/LIMIT returned fewer rows than requested → exact cardinality.
            row_count = result.sample_rows
            is_estimate = False
        elif row_count is None and result.sampled and result.sample_rows:
            is_estimate = True

        columns = [
            ColumnProfile(
                name=c.name,
                type=c.portable_type,
                comment=c.comment,
                ordinal_position=c.ordinal_position,
                stats=result.stats.get(c.name, ColumnStats()),
            )
            for c in columns_meta
        ]
        return TableProfile(
            catalog=table.catalog,
            schema=table.schema,
            name=table.name,
            row_count=row_count,
            columns=columns,
            row_count_is_estimate=is_estimate,
            comment=comment,
            sampled=result.sampled,
            sample_size=result.sample_size,
        )
