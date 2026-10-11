"""Host-side fail-closed validator for the M3-A YouTube feasibility protocol.

Normative source: `.work/milestones/M3.md`.

M3-A is a research/evidence boundary, not a production YouTube implementation.
This module validates portable `m3-a-probe-v1` evidence, enforces bounded probe
delivery, applies privacy redaction checks inherited from M2, and rejects
cross-field claims that are stronger than the recorded observations.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
from typing import Any, Mapping

from m2_contracts import M2ContractError, scan_evidence_privacy
from schema_subset import SchemaContractError, validate_instance, validate_schema_definition


class M3ContractError(ValueError):
    pass


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise M3ContractError(message)


SCHEMA_VERSION = 1
SCHEMA_NAME = "m3-a-probe-v1.schema.json"
MAX_METADATA_REQUESTS = 32
MAX_MEDIA_PROBE_BYTES = 1_048_576
MAX_MEDIA_PROBES = 8

CLIENT_LANES = (
    "WEB_EMBEDDED",
    "TV",
    "ANDROID_VR",
    "MWEB",
    "WEB",
    "WEB_SAFARI",
    "ANDROID",
    "IOS",
)
VIDEO_CASE_CLASSES = ("V0", "V1", "V2")
OBSERVATION_STATES = ("PRESENT", "ABSENT", "UNKNOWN")
REQUIREMENT_STATES = ("REQUIRED", "NOT_REQUIRED", "UNKNOWN", "NOT_APPLICABLE")
MEDIA_LAYOUTS = ("SPLIT_AV", "MUXED", "INTERLEAVED", "MULTIPLE", "UNKNOWN")
OUTCOMES = (
    "VIABLE_ANONYMOUS",
    "TOKEN_GATED",
    "CHALLENGE_GATED",
    "SABR_ONLY",
    "DRM_GATED",
    "PROVIDER_REJECTED",
    "UNSUPPORTED_DELIVERY",
    "INCONCLUSIVE",
)

REFERENCE_ROLES = {
    "YT_DLP": "REFERENCE_ORACLE",
    "YOUTUBE_JS": "REFERENCE_ORACLE",
    "BGUTILS": "TOKEN_CHALLENGE_REFERENCE",
    "NEWPIPE_EXTRACTOR": "COMPARISON_ONLY",
    "INVIDIOUS": "COMPARISON_ONLY",
}

_ANY_URL = re.compile(r"\\b[a-zA-Z][a-zA-Z0-9+.-]*://\\S+")


def _repo_root() -> pathlib.Path:
    return pathlib.Path(__file__).resolve().parents[2]


def _default_schema_path() -> pathlib.Path:
    return _repo_root() / ".work" / "schemas" / SCHEMA_NAME


def _load_json(path: pathlib.Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise M3ContractError(f"{path}: cannot load JSON: {exc}") from exc


def _validate_schema_and_instance(
    document: Mapping[str, Any],
    schema: Mapping[str, Any],
) -> None:
    try:
        validate_schema_definition(dict(schema))
        validate_instance(dict(schema), dict(document))
    except SchemaContractError as exc:
        raise M3ContractError(str(exc)) from exc


def _scan_privacy(document: Any, path: str = "$") -> None:
    try:
        scan_evidence_privacy(document)
    except M2ContractError as exc:
        raise M3ContractError(str(exc)) from exc

    def reject_urls(value: Any, value_path: str) -> None:
        if isinstance(value, Mapping):
            for key, item in value.items():
                reject_urls(item, f"{value_path}.{key}")
        elif isinstance(value, list):
            for index, item in enumerate(value):
                reject_urls(item, f"{value_path}[{index}]")
        elif isinstance(value, str):
            _require(
                _ANY_URL.search(value) is None,
                f"{value_path}: URLs are not retained in portable M3-A evidence",
            )

    reject_urls(document, path)


def _state(value: Any, field: str) -> str:
    _require(value in OBSERVATION_STATES, f"{field}: invalid observation {value!r}")
    return str(value)


def _requirement(value: Any, field: str) -> str:
    _require(value in REQUIREMENT_STATES, f"{field}: invalid requirement {value!r}")
    return str(value)


def validate_reference_oracles(references: Any) -> None:
    _require(isinstance(references, list), "references must be an array")
    seen: set[str] = set()
    for index, reference in enumerate(references):
        path = f"references[{index}]"
        _require(isinstance(reference, Mapping), f"{path}: object required")
        oracle_id = reference.get("id")
        _require(oracle_id in REFERENCE_ROLES, f"{path}: unknown reference {oracle_id!r}")
        _require(oracle_id not in seen, f"{path}: duplicate reference {oracle_id}")
        seen.add(str(oracle_id))
        expected_role = REFERENCE_ROLES[str(oracle_id)]
        _require(
            reference.get("role") == expected_role,
            f"{path}: {oracle_id} is {expected_role}, not {reference.get('role')!r}",
        )
        _require(
            reference.get("productionDependency") is False,
            f"{path}: M3-A references are never production dependencies",
        )
        _require(
            isinstance(reference.get("version"), str) and reference["version"],
            f"{path}: pinned version required",
        )
        _require(
            isinstance(reference.get("revision"), str) and reference["revision"],
            f"{path}: pinned revision required",
        )


def validate_budget(budget: Any) -> None:
    _require(isinstance(budget, Mapping), "budget must be an object")
    _require(
        budget.get("metadataRequestLimit") == MAX_METADATA_REQUESTS,
        f"metadataRequestLimit must be {MAX_METADATA_REQUESTS}",
    )
    used = budget.get("metadataRequestsUsed")
    _require(
        isinstance(used, int)
        and not isinstance(used, bool)
        and 0 <= used <= MAX_METADATA_REQUESTS,
        f"metadataRequestsUsed must be 0..{MAX_METADATA_REQUESTS}",
    )
    _require(
        budget.get("mediaProbeByteLimit") == MAX_MEDIA_PROBE_BYTES,
        f"mediaProbeByteLimit must be {MAX_MEDIA_PROBE_BYTES}",
    )

    probes = budget.get("mediaProbes")
    _require(isinstance(probes, list), "mediaProbes must be an array")
    _require(
        len(probes) <= MAX_MEDIA_PROBES,
        f"at most {MAX_MEDIA_PROBES} media probes are permitted",
    )

    seen: set[str] = set()
    for index, probe in enumerate(probes):
        path = f"budget.mediaProbes[{index}]"
        _require(isinstance(probe, Mapping), f"{path}: object required")
        probe_id = probe.get("probeId")
        _require(isinstance(probe_id, str) and probe_id, f"{path}: probeId required")
        _require(probe_id not in seen, f"{path}: duplicate probeId {probe_id}")
        seen.add(probe_id)

        byte_limit = probe.get("byteLimit")
        bytes_read = probe.get("bytesRead")
        _require(
            isinstance(byte_limit, int)
            and not isinstance(byte_limit, bool)
            and 0 <= byte_limit <= MAX_MEDIA_PROBE_BYTES,
            f"{path}.byteLimit exceeds the 1 MiB per-probe budget",
        )
        _require(
            isinstance(bytes_read, int)
            and not isinstance(bytes_read, bool)
            and 0 <= bytes_read <= byte_limit,
            f"{path}.bytesRead must be within the declared byteLimit",
        )


def _validate_outcome_claim(document: Mapping[str, Any]) -> None:
    capabilities = document.get("capabilities") or {}
    requirements = document.get("requirements") or {}
    provider_signals = document.get("providerSignals") or {}
    delivery = document.get("delivery") or {}
    outcome = (document.get("outcome") or {}).get("classification")

    _require(outcome in OUTCOMES, f"unknown outcome {outcome!r}")

    for name in ("https", "dash", "hls", "sabr", "drm"):
        _state(capabilities.get(name), f"capabilities.{name}")
    for name in (
        "range",
        "continuation",
        "descriptorExpiry",
        "startup",
        "seek",
        "sustainedPlayback",
    ):
        _state(delivery.get(name), f"delivery.{name}")
    for name in ("gvsPoToken", "playerPoToken", "challengeRuntime", "sessionContext"):
        _requirement(requirements.get(name), f"requirements.{name}")
    for name in ("rejection", "throttling", "retryAfter"):
        _state(provider_signals.get(name), f"providerSignals.{name}")

    lifetime = delivery.get("descriptorLifetimeSeconds")
    _require(
        lifetime is None
        or (
            isinstance(lifetime, int)
            and not isinstance(lifetime, bool)
            and lifetime >= 0
        ),
        "delivery.descriptorLifetimeSeconds must be null or a non-negative integer",
    )
    _state(delivery.get("descriptorRefresh"), "delivery.descriptorRefresh")
    if lifetime is not None:
        _require(
            delivery.get("descriptorExpiry") == "PRESENT",
            "descriptorLifetimeSeconds requires descriptorExpiry=PRESENT",
        )

    layout = delivery.get("mediaLayout")
    _require(layout in MEDIA_LAYOUTS, f"delivery.mediaLayout: invalid {layout!r}")

    if outcome == "VIABLE_ANONYMOUS":
        _require(
            capabilities.get("https") == "PRESENT",
            "VIABLE_ANONYMOUS requires observed HTTPS delivery",
        )
        _require(
            delivery.get("startup") == "PRESENT",
            "VIABLE_ANONYMOUS requires observed startup",
        )
        for field in ("gvsPoToken", "playerPoToken", "challengeRuntime"):
            _require(
                requirements.get(field) in ("NOT_REQUIRED", "NOT_APPLICABLE"),
                f"VIABLE_ANONYMOUS cannot leave {field} required or unknown",
            )
    elif outcome == "TOKEN_GATED":
        _require(
            "REQUIRED"
            in (
                requirements.get("gvsPoToken"),
                requirements.get("playerPoToken"),
            ),
            "TOKEN_GATED requires an observed required GVS or Player PO token",
        )
    elif outcome == "CHALLENGE_GATED":
        _require(
            requirements.get("challengeRuntime") == "REQUIRED",
            "CHALLENGE_GATED requires challengeRuntime=REQUIRED",
        )
    elif outcome == "SABR_ONLY":
        _require(
            capabilities.get("sabr") == "PRESENT",
            "SABR_ONLY requires sabr=PRESENT",
        )
        _require(
            capabilities.get("dash") == "ABSENT"
            and capabilities.get("hls") == "ABSENT",
            "SABR_ONLY requires DASH and HLS to be observed absent",
        )
    elif outcome == "DRM_GATED":
        _require(
            capabilities.get("drm") == "PRESENT",
            "DRM_GATED requires drm=PRESENT",
        )
    elif outcome == "PROVIDER_REJECTED":
        _require(
            provider_signals.get("rejection") == "PRESENT",
            "PROVIDER_REJECTED requires providerSignals.rejection=PRESENT",
        )
    elif outcome == "PROVIDER_RATE_LIMITED":
        _require(
            provider_signals.get("throttling") == "PRESENT",
            "PROVIDER_RATE_LIMITED requires providerSignals.throttling=PRESENT",
        )


def validate_probe_semantics(document: Mapping[str, Any]) -> None:
    """Cross-field M3-A rules that the repository JSON-Schema subset cannot express."""

    _require(isinstance(document, Mapping), "probe document must be an object")
    _require(
        document.get("schemaVersion") == SCHEMA_VERSION,
        "unsupported probe schema version",
    )

    client = document.get("client") or {}
    _require(
        client.get("lane") in CLIENT_LANES,
        f"unknown client lane {client.get('lane')!r}",
    )

    video_case = document.get("videoCase") or {}
    _require(
        video_case.get("caseClass") in VIDEO_CASE_CLASSES,
        f"unknown video case class {video_case.get('caseClass')!r}",
    )

    environment = document.get("environment") or {}
    _require(
        environment.get("runnerKind") == "GITHUB_HOSTED",
        "M3-A canonical probes require GitHub-hosted runners",
    )
    _require(
        environment.get("networkClass") == "PUBLIC_EGRESS_REDACTED",
        "M3-A evidence must not retain egress addressing",
    )

    validate_reference_oracles(document.get("references"))
    validate_budget(document.get("budget"))
    _validate_outcome_claim(document)

    guardrails = document.get("guardrails") or {}
    for field in (
        "accountCredentialsUsed",
        "ipRotationOrProxyEvasionUsed",
        "antiBotBypassImplemented",
        "requestRateCamouflageUsed",
        "fullDownloadAttempted",
    ):
        _require(
            guardrails.get(field) is False,
            f"guardrails.{field} must remain false in M3-A",
        )

    _scan_privacy(document)


def validate_probe_document(
    document: Mapping[str, Any],
    *,
    schema: Mapping[str, Any] | None = None,
) -> None:
    effective_schema = (
        schema if schema is not None else _load_json(_default_schema_path())
    )
    _validate_schema_and_instance(document, effective_schema)
    validate_probe_semantics(document)


def _cmd_validate(args: argparse.Namespace) -> int:
    path = pathlib.Path(args.input)
    document = _load_json(path)
    if not isinstance(document, Mapping):
        raise M3ContractError(f"{path}: top-level JSON value must be an object")
    validate_probe_document(document)
    print(f"M3-A probe valid: {path}")
    return 0


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    validate = sub.add_parser(
        "validate", help="validate one m3-a-probe-v1 artifact"
    )
    validate.add_argument("--input", required=True)
    validate.set_defaults(func=_cmd_validate)
    return parser


def main() -> int:
    parser = _build_parser()
    args = parser.parse_args()
    try:
        return args.func(args)
    except M3ContractError as exc:
        parser.error(str(exc))
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
