"""Deterministic fail-closed validator for the JSON-Schema subset used by SpongeTube.

This is intentionally not a general JSON Schema implementation. Every supported
keyword is explicit. Repository verification first validates each checked-in schema
definition itself, so adding a new keyword cannot silently weaken validation.
"""

from __future__ import annotations

import datetime as _datetime
import re
from typing import Any


class SchemaContractError(ValueError):
    pass


_SUPPORTED_TYPES = {
    "null",
    "boolean",
    "integer",
    "number",
    "string",
    "array",
    "object",
}

_SUPPORTED_KEYWORDS = {
    "$schema",
    "$id",
    "$defs",
    "$ref",
    "title",
    "description",
    "type",
    "const",
    "enum",
    "minLength",
    "pattern",
    "format",
    "minimum",
    "maximum",
    "minItems",
    "items",
    "properties",
    "required",
    "additionalProperties",
}

_SUPPORTED_FORMATS = {"date-time"}


def _matches_type(value: Any, type_name: str) -> bool:
    if type_name == "null":
        return value is None
    if type_name == "boolean":
        return isinstance(value, bool)
    if type_name == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if type_name == "number":
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    if type_name == "string":
        return isinstance(value, str)
    if type_name == "array":
        return isinstance(value, list)
    if type_name == "object":
        return isinstance(value, dict)
    raise SchemaContractError(f"unsupported schema type: {type_name}")


def _json_equal(left: Any, right: Any) -> bool:
    """JSON-semantic equality for the subset used by contract const/enum values."""

    if isinstance(left, bool) or isinstance(right, bool):
        return isinstance(left, bool) and isinstance(right, bool) and left == right

    left_number = isinstance(left, (int, float)) and not isinstance(left, bool)
    right_number = isinstance(right, (int, float)) and not isinstance(right, bool)
    if left_number or right_number:
        return left_number and right_number and left == right

    if left is None or right is None:
        return left is right

    if isinstance(left, str) or isinstance(right, str):
        return isinstance(left, str) and isinstance(right, str) and left == right

    if isinstance(left, list) or isinstance(right, list):
        return (
            isinstance(left, list)
            and isinstance(right, list)
            and len(left) == len(right)
            and all(_json_equal(a, b) for a, b in zip(left, right))
        )

    if isinstance(left, dict) or isinstance(right, dict):
        return (
            isinstance(left, dict)
            and isinstance(right, dict)
            and left.keys() == right.keys()
            and all(_json_equal(left[key], right[key]) for key in left)
        )

    return left == right and type(left) is type(right)


def _schema_children(schema: dict[str, Any]):
    properties = schema.get("properties")
    if isinstance(properties, dict):
        yield from properties.values()

    definitions = schema.get("$defs")
    if isinstance(definitions, dict):
        yield from definitions.values()

    items = schema.get("items")
    if isinstance(items, dict):
        yield items

    additional = schema.get("additionalProperties")
    if isinstance(additional, dict):
        yield additional


def validate_schema_definition(
    schema: dict[str, Any],
    *,
    root_schema: dict[str, Any] | None = None,
    path: str = "$schema",
) -> None:
    """Reject unsupported/ill-formed schema constructs before instance validation."""

    if not isinstance(schema, dict):
        raise SchemaContractError(f"{path}: schema node must be an object")

    root = schema if root_schema is None else root_schema

    unknown = sorted(set(schema) - _SUPPORTED_KEYWORDS)
    if unknown:
        raise SchemaContractError(
            f"{path}: unsupported schema keyword(s): {', '.join(unknown)}"
        )

    declared_type = schema.get("type")
    if declared_type is not None:
        types = [declared_type] if isinstance(declared_type, str) else declared_type
        if not isinstance(types, list) or not types:
            raise SchemaContractError(f"{path}.type: expected non-empty string/list")
        for type_name in types:
            if not isinstance(type_name, str) or type_name not in _SUPPORTED_TYPES:
                raise SchemaContractError(
                    f"{path}.type: unsupported schema type {type_name!r}"
                )

    fmt = schema.get("format")
    if fmt is not None and fmt not in _SUPPORTED_FORMATS:
        raise SchemaContractError(f"{path}.format: unsupported format {fmt!r}")

    ref = schema.get("$ref")
    if ref is not None:
        _resolve_local_ref(root, ref, f"{path}.$ref")

    for index, child in enumerate(_schema_children(schema)):
        validate_schema_definition(
            child,
            root_schema=root,
            path=f"{path}::<child:{index}>",
        )


def _resolve_local_ref(
    root_schema: dict[str, Any],
    ref: str,
    path: str,
) -> dict[str, Any]:
    prefix = "#/$defs/"
    if not isinstance(ref, str) or not ref.startswith(prefix):
        raise SchemaContractError(
            f"{path}: only local #/$defs/... references are supported"
        )

    name = ref[len(prefix):]
    if not name or "/" in name:
        raise SchemaContractError(f"{path}: unsupported local reference {ref!r}")

    definitions = root_schema.get("$defs")
    if not isinstance(definitions, dict) or name not in definitions:
        raise SchemaContractError(f"{path}: unresolved local reference {ref!r}")

    target = definitions[name]
    if not isinstance(target, dict):
        raise SchemaContractError(f"{path}: reference target must be an object")
    return target


def _validate_datetime(value: str, path: str) -> None:
    candidate = value
    if value.endswith("Z"):
        candidate = value[:-1] + "+00:00"
    try:
        parsed = _datetime.datetime.fromisoformat(candidate)
    except ValueError as exc:
        raise SchemaContractError(f"{path}: invalid date-time {value!r}") from exc
    if parsed.tzinfo is None:
        raise SchemaContractError(f"{path}: date-time must include a timezone")


def validate_instance(
    schema: dict[str, Any],
    value: Any,
    path: str = "$",
    *,
    root_schema: dict[str, Any] | None = None,
) -> None:
    root = schema if root_schema is None else root_schema

    if root_schema is None:
        validate_schema_definition(schema)

    ref = schema.get("$ref")
    if ref is not None:
        validate_instance(
            _resolve_local_ref(root, ref, f"{path}.$ref"),
            value,
            path,
            root_schema=root,
        )

    if "const" in schema and not _json_equal(value, schema["const"]):
        raise SchemaContractError(
            f"{path}: expected const {schema['const']!r}, got {value!r}"
        )

    if "enum" in schema and not any(
        _json_equal(value, candidate) for candidate in schema["enum"]
    ):
        raise SchemaContractError(
            f"{path}: {value!r} is not in enum {schema['enum']!r}"
        )

    declared_type = schema.get("type")
    if declared_type is not None:
        types = [declared_type] if isinstance(declared_type, str) else declared_type
        if not any(_matches_type(value, type_name) for type_name in types):
            raise SchemaContractError(
                f"{path}: value {value!r} does not match type {types!r}"
            )

    if isinstance(value, str):
        minimum_length = schema.get("minLength")
        if minimum_length is not None and len(value) < minimum_length:
            raise SchemaContractError(
                f"{path}: string shorter than minLength={minimum_length}"
            )
        pattern = schema.get("pattern")
        if pattern is not None and re.search(pattern, value) is None:
            raise SchemaContractError(
                f"{path}: string does not match pattern {pattern!r}"
            )
        fmt = schema.get("format")
        if fmt == "date-time":
            _validate_datetime(value, path)

    if isinstance(value, (int, float)) and not isinstance(value, bool):
        minimum = schema.get("minimum")
        if minimum is not None and value < minimum:
            raise SchemaContractError(f"{path}: {value} < minimum {minimum}")
        maximum = schema.get("maximum")
        if maximum is not None and value > maximum:
            raise SchemaContractError(f"{path}: {value} > maximum {maximum}")

    if isinstance(value, list):
        minimum_items = schema.get("minItems")
        if minimum_items is not None and len(value) < minimum_items:
            raise SchemaContractError(
                f"{path}: array has fewer than {minimum_items} items"
            )
        item_schema = schema.get("items")
        if item_schema is not None:
            for index, item in enumerate(value):
                validate_instance(
                    item_schema,
                    item,
                    f"{path}[{index}]",
                    root_schema=root,
                )

    if isinstance(value, dict):
        properties = schema.get("properties", {})
        for required in schema.get("required", []):
            if required not in value:
                raise SchemaContractError(
                    f"{path}: missing required property {required!r}"
                )

        additional = schema.get("additionalProperties", True)
        for key, item in value.items():
            if key in properties:
                validate_instance(
                    properties[key],
                    item,
                    f"{path}.{key}",
                    root_schema=root,
                )
            elif additional is False:
                raise SchemaContractError(
                    f"{path}: unexpected property {key!r}"
                )
            elif isinstance(additional, dict):
                validate_instance(
                    additional,
                    item,
                    f"{path}.{key}",
                    root_schema=root,
                )
