"""Structured logging helpers and secret redaction."""

from __future__ import annotations

import hashlib
import json
import logging
from typing import Any

SECRET_KEYS = {
    "password",
    "token",
    "access_token",
    "secret",
    "private_key",
    "private_key_passphrase",
    "client_secret",
}


def get_logger(name: str) -> logging.Logger:
    return logging.getLogger(name)


def redact(value: Any) -> Any:
    """Recursively redact secret-looking keys from nested dicts."""
    if isinstance(value, dict):
        out = {}
        for k, v in value.items():
            if str(k).lower() in SECRET_KEYS or str(k).lower().endswith("_password"):
                out[k] = "***REDACTED***"
            else:
                out[k] = redact(v)
        return out
    if isinstance(value, list):
        return [redact(v) for v in value]
    return value


def config_fingerprint(engine: str, config: dict[str, Any], connection_hint: str | None) -> str:
    """Stable fingerprint so resume state cannot mix incompatible runs."""
    payload = {
        "engine": engine,
        "connection_hint": connection_hint,
        "config": {
            k: config.get(k)
            for k in (
                "sample_size",
                "sample_percent",
                "stats_depth",
                "histogram_buckets",
                "distinct_scope",
                "include_schemas",
                "exclude_schemas",
                "include_tables",
                "exclude_tables",
                "estimate_row_counts",
                "max_tables",
            )
        },
    }
    raw = json.dumps(payload, sort_keys=True, default=str)
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:16]


def log_event(logger: logging.Logger, event: str, **fields: Any) -> None:
    """Emit a single-line structured event (JSON) for log aggregation."""
    payload = {"event": event, **redact(fields)}
    logger.info("%s", json.dumps(payload, default=str, sort_keys=True))
