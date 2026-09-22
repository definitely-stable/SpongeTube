"""Small deterministic validator for the JSON-Schema subset used by M1 evidence contracts.

This is intentionally not a general JSON Schema implementation. It supports only the
keywords used by the checked-in M1 schemas so Verify can reject accidental contract
drift without adding a Python package dependency.
"""

from __future__ import annotations

import re
from typing import Any


class SchemaContractError(ValueError):
    pass


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


def validate_instance(schema: dict[str, Any], value: Any, path: str = "$") -> None:
    if "const" in schema and value != schema["const"]:
        raise SchemaContractError(
            f"{path}: expected const {schema['const']!r}, got {value!r}"
        )

    if "enum" in schema and value not in schema["enum"]:
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

    if isinstance(value, int) and not isinstance(value, bool):
        minimum = schema.get("minimum")
        if minimum is not None and value < minimum:
            raise SchemaContractError(f"{path}: {value} < minimum {minimum}")

    if isinstance(value, list):
        minimum_items = schema.get("minItems")
        if minimum_items is not None and len(value) < minimum_items:
            raise SchemaContractError(
                f"{path}: array has fewer than {minimum_items} items"
            )
        item_schema = schema.get("items")
        if item_schema is not None:
            for index, item in enumerate(value):
                validate_instance(item_schema, item, f"{path}[{index}]")

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
                validate_instance(properties[key], item, f"{path}.{key}")
            elif additional is False:
                raise SchemaContractError(
                    f"{path}: unexpected property {key!r}"
                )
            elif isinstance(additional, dict):
                validate_instance(additional, item, f"{path}.{key}")
