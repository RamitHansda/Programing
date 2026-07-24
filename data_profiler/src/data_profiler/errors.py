"""Domain errors with retry/classification hints for the orchestrator."""

from __future__ import annotations


class ProfilerError(Exception):
    """Base error for the profiler."""


class AdapterError(ProfilerError):
    """Engine/adapter failure while talking to a database."""

    def __init__(self, message: str, *, transient: bool = False, cause: BaseException | None = None):
        super().__init__(message)
        self.transient = transient
        self.__cause__ = cause


class ConfigurationError(ProfilerError):
    """Invalid or incomplete configuration."""


class TableTimeoutError(AdapterError):
    """A single table exceeded the configured timeout."""

    def __init__(self, table: str, timeout_seconds: float):
        super().__init__(
            f"Profiling {table} exceeded timeout of {timeout_seconds}s",
            transient=True,
        )
        self.table = table
        self.timeout_seconds = timeout_seconds
