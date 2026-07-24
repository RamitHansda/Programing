"""Unit tests for portable type mapping."""

from data_profiler.models import TypeKind
from data_profiler.type_mapping import map_native_type


def test_sqlite_and_snowflake_integers_map_alike():
    assert map_native_type("INTEGER").kind == TypeKind.INTEGER
    assert map_native_type("BIGINT").kind == TypeKind.INTEGER
    # Snowflake NUMBER(38,0) should compare as integer, not decimal.
    assert map_native_type("NUMBER(38,0)").kind == TypeKind.INTEGER
    assert map_native_type("NUMBER(38,0)").precision == 38
    assert map_native_type("NUMBER(38,0)").scale == 0


def test_decimal_with_scale_stays_decimal():
    pt = map_native_type("DECIMAL(10,2)")
    assert pt.kind == TypeKind.DECIMAL
    assert pt.precision == 10
    assert pt.scale == 2


def test_string_variants():
    for native in ["TEXT", "VARCHAR(255)", "STRING", "NVARCHAR(100)"]:
        pt = map_native_type(native, nullable=True)
        assert pt.kind == TypeKind.STRING
        assert pt.nullable is True


def test_timestamp_and_json():
    assert map_native_type("TIMESTAMP_NTZ").kind == TypeKind.TIMESTAMP
    assert map_native_type("TIMESTAMP WITH TIME ZONE").kind == TypeKind.TIMESTAMP
    assert map_native_type("VARIANT").kind == TypeKind.JSON
    assert map_native_type("JSONB").kind == TypeKind.JSON


def test_complex_types():
    assert map_native_type("ARRAY<INTEGER>").kind == TypeKind.ARRAY
    assert map_native_type("VARCHAR[]").kind == TypeKind.ARRAY
    assert map_native_type("MAP<STRING, INT>").kind == TypeKind.MAP
    assert map_native_type("STRUCT<a:INT>").kind == TypeKind.STRUCT
