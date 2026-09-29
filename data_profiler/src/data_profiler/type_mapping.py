"""Map engine-native type strings into portable TypeKind values."""

from __future__ import annotations

import re

from data_profiler.models import PortableType, TypeKind

_INTEGER = re.compile(
    r"^(tinyint|smallint|mediumint|int|integer|bigint|int\d+|uint\d+|hugeint|ubyte|ushort|uinteger|ubigint)$",
    re.I,
)
_FLOAT = re.compile(r"^(float|real|double|double precision|float\d+)$", re.I)
_DECIMAL = re.compile(r"^(decimal|numeric|number)(\s*\(.*\))?$", re.I)
_BOOLEAN = re.compile(r"^(bool|boolean)$", re.I)
_STRING = re.compile(
    r"^(char|nchar|varchar|nvarchar|character varying|text|string|clob|bpchar)(\s*\(.*\))?$",
    re.I,
)
_BINARY = re.compile(r"^(blob|bytea|binary|varbinary|bytes|raw)(\s*\(.*\))?$", re.I)
_DATE = re.compile(r"^date$", re.I)
_TIME = re.compile(r"^time(\s*\(.*\))?$", re.I)
_TIMESTAMP = re.compile(
    r"^(timestamp|datetime|datetime2|timestamptz|timestamp_ntz|timestamp_ltz|timestamp_tz)"
    r"(\s+(with|without)\s+time\s+zone)?(\s*\(.*\))?$",
    re.I,
)
_JSON = re.compile(r"^(json|jsonb|variant)$", re.I)
_ARRAY = re.compile(r"^((array|list)(<.*>)?|\w+(\[\])+)$", re.I)
_MAP = re.compile(r"^(map|object)(<.*>)?$", re.I)
_STRUCT = re.compile(r"^(struct|row|record)(<.*>)?$", re.I)


def map_native_type(native: str, nullable: bool | None = None) -> PortableType:
    """Normalize a vendor type string into a portable type descriptor.

    Cross-engine comparability rule: ``NUMBER(p,0)`` / ``NUMERIC(p,0)`` /
    ``DECIMAL(p,0)`` map to ``integer`` so Snowflake NUMBER columns line up
    with SQLite INTEGER / DuckDB BIGINT semantically.
    """
    raw = (native or "").strip()
    base = raw.split("(", 1)[0].strip()
    precision, scale, max_length = _parse_params(raw)
    kind = TypeKind.UNKNOWN

    if _INTEGER.match(base) or _INTEGER.match(raw):
        kind = TypeKind.INTEGER
    elif _DECIMAL.match(raw) or _DECIMAL.match(base):
        # Integral decimals compare better as integer across warehouses.
        if scale == 0:
            kind = TypeKind.INTEGER
        else:
            kind = TypeKind.DECIMAL
    elif _FLOAT.match(base) or _FLOAT.match(raw):
        kind = TypeKind.FLOAT
    elif _BOOLEAN.match(base):
        kind = TypeKind.BOOLEAN
    elif _STRING.match(raw) or _STRING.match(base):
        kind = TypeKind.STRING
    elif _BINARY.match(raw) or _BINARY.match(base):
        kind = TypeKind.BINARY
    elif _DATE.match(base):
        kind = TypeKind.DATE
    elif _TIME.match(raw) or _TIME.match(base):
        kind = TypeKind.TIME
    elif _TIMESTAMP.match(raw) or _TIMESTAMP.match(base):
        kind = TypeKind.TIMESTAMP
    elif _JSON.match(base):
        kind = TypeKind.JSON
    elif _ARRAY.match(raw) or base.upper().startswith("ARRAY") or raw.endswith("[]"):
        kind = TypeKind.ARRAY
    elif _MAP.match(raw) or base.upper().startswith("MAP"):
        kind = TypeKind.MAP
    elif _STRUCT.match(raw) or base.upper().startswith("STRUCT"):
        kind = TypeKind.STRUCT

    return PortableType(
        kind=kind,
        native=raw,
        nullable=nullable,
        precision=precision,
        scale=scale,
        max_length=max_length if kind == TypeKind.STRING else None,
    )


def is_comparable_for_minmax(kind: TypeKind) -> bool:
    return kind in {
        TypeKind.INTEGER,
        TypeKind.FLOAT,
        TypeKind.DECIMAL,
        TypeKind.DATE,
        TypeKind.TIME,
        TypeKind.TIMESTAMP,
        TypeKind.STRING,
        TypeKind.BOOLEAN,
    }


def supports_histogram(kind: TypeKind) -> bool:
    return kind in {TypeKind.INTEGER, TypeKind.FLOAT, TypeKind.DECIMAL}


def _parse_params(native: str) -> tuple[int | None, int | None, int | None]:
    match = re.search(r"\(([^)]+)\)", native)
    if not match:
        return None, None, None
    parts = [p.strip() for p in match.group(1).split(",")]
    try:
        nums = [int(p) for p in parts if p]
    except ValueError:
        return None, None, None
    if len(nums) == 1:
        return nums[0], None, nums[0]
    if len(nums) >= 2:
        return nums[0], nums[1], None
    return None, None, None
