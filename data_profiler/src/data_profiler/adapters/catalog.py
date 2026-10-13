"""Helpers shared by adapters that read catalog metadata in bulk."""

from __future__ import annotations

from typing import Any


def catalog_key(catalog: Any, schema: Any, name: Any) -> str:
    """Mirror ``TableRef.fully_qualified_name`` so cache lookups line up."""
    return ".".join(str(p) for p in (catalog, schema, name) if p)
