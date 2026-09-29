#!/usr/bin/env python3
"""Canonical M2-F route/recovery evidence assembler and independent oracle.

The Android producers own only runtime facts. The host prepare step binds those
facts to the frozen M2 scenario/run contracts and the verify step independently
replays route/recovery evidence, proves persisted-media invariance and emits a
content-addressed evidence index.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import shutil
import sys
from typing import Any, Iterable, Mapping

SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[1]
SCHEMAS = REPO_ROOT / ".work" / "schemas"
FIXTURE_MANIFEST = REPO_ROOT / "test-fixtures" / "media" / "manifest.json"
sys.path.insert(0, str(SCRIPT_DIR))

from m2_contracts import (  # noqa: E402
    M2ContractError,
    scan_evidence_privacy,
    scenario_sha256,
    validate_persisted_identity_invariance,
    validate_run_manifest_semantics,
    validate_scenario_semantics,
)
from m2_recovery_oracle import (  # noqa: E402
    RecoveryOracleError,
    read_jsonl,
    verify_recovery,
)
from m2_route_oracle import RouteOracleError, verify_route_events  # noqa: E402
from schema_subset import SchemaContractError, validate_instance  # noqa: E402


class M2FOracleError(ValueError):
    pass


CASE_CONFIGS: dict[str, dict[str, Any]] = {
    "F2_DEFAULT_ROUTE_LOSS_RESTORE": {
        "phase": "M2-F2",
        "family": "N6",
        "variant": "DEFAULT_ROUTE_LOSS_RESTORE",
        "caseScenarioId": None,
        "transition": "ROUTE_EPOCH_CHANGED",
        "targetPath": "/fixtures/F1/segment-1-00001.m4s",
        "fault": {
            "faultId": "default-route-loss-restore",
            "plane": "ROUTE",
            "kind": "DEFAULT_ROUTE_LOSS_RESTORE",
            "stochastic": False,
            "parameters": {
                "replacementDefault": "NON_VPN",
                "resume": "NON_VPN",
            },
        },
    },
    "F3_VPN_CONTINUITY_RESTORE": {
        "phase": "M2-F3",
        "family": "N7",
        "variant": "VPN_CONTINUITY_RESTORE",
        "caseScenarioId": "VPN_RESTORE",
        "transition": "VPN_LOST",
        "targetPath": "/fixtures/F1/segment-1-00001.m4s",
        "fault": {
            "faultId": "vpn-continuity-restore",
            "plane": "ROUTE",
            "kind": "VPN_DEFAULT_ROUTE_LOSS",
            "stochastic": False,
            "parameters": {
                "replacementDefault": "NON_VPN",
                "resume": "VPN",
            },
        },
    },
    "F3_VPN_CONTINUITY_DIRECT_OVERRIDE": {
        "phase": "M2-F3",
        "family": "N7",
        "variant": "VPN_CONTINUITY_DIRECT_OVERRIDE",
        "caseScenarioId": "DIRECT_OVERRIDE",
        "transition": "VPN_LOST",
        "targetPath": "/fixtures/F1/segment-1-00002.m4s",
        "fault": {
            "faultId": "vpn-continuity-direct-override",
            "plane": "ROUTE",
            "kind": "VPN_DEFAULT_ROUTE_LOSS",
            "stochastic": False,
            "parameters": {
                "replacementDefault": "NON_VPN",
                "resume": "EXPLICIT_DIRECT_OVERRIDE",
            },
        },
    },
}

SOURCE_ARTIFACTS = (
    ("case.json", "CASE", "m2-f-route-case-v1"),
    ("scenario.json", "CONTRACT", "m2-scenario-v1"),
    ("run-manifest.json", "CONTRACT", "m2-run-manifest-v1"),
    ("route-events.json", "RUNTIME", "route-events-v1"),
    ("recovery-budget-events.json", "RUNTIME", "recovery-budget-events-v1"),
    ("failure-decision-events.json", "RUNTIME", "failure-decision-events-v2"),
    ("fetch-events.jsonl", "RUNTIME", "fetch-events-v4"),
    ("origin-trace.jsonl", "RUNTIME", "media-lab-request-trace-v1"),
)

ORACLE_ARTIFACTS = (
    ("route-verification-summary.json", "ORACLE", "route-verification-summary-v1"),
    ("recovery-verification-summary.json", "ORACLE", "recovery-verification-summary-v1"),
    ("f4-verification-summary.json", "ORACLE", "m2-f-verification-summary-v1"),
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise M2FOracleError(message)


def load_object(path: pathlib.Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise M2FOracleError(f"{path}: invalid JSON: {error}") from error
    require(isinstance(value, dict), f"{path}: expected JSON object")
    return value


def write_json(path: pathlib.Path, value: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )


def file_sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_json_sha256(value: Any) -> str:
    payload = json.dumps(
        value,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def build_scenario(case_kind: str) -> dict[str, Any]:
    config = CASE_CONFIGS[case_kind]
    return {
        "schemaVersion": 1,
        "scenarioFamily": config["family"],
        "variant": config["variant"],
        "primaryPlane": "ROUTE",
        "deliveryFaults": [],
        "transportFaults": [],
        "networkFaults": [],
        "providerFaults": [],
        "routeFaults": [config["fault"]],
        "storageFaults": [],
        "randomSeed": None,
        "requiresActualDefaultNetwork": True,
    }


def semantic_sequence(case_kind: str, case: Mapping[str, Any]) -> list[str]:
    if case_kind == "F2_DEFAULT_ROUTE_LOSS_RESTORE":
        require(
            case.get("restoredRouteEpoch", 0) > case.get("initialRouteEpoch", 0),
            "F2 semantic sequence requires an advancing route epoch",
        )
        return [
            "SESSION:DIRECT_DEFAULT_ALLOWED",
            "PERMIT:E1:ROUTE_READY",
            "OWNER:1",
            "FAIL:E1:TRANSIENT_TRANSPORT:SCHEDULE_BACKOFF",
            "PAUSE:NONE:NO_USABLE_DEFAULT",
            "PERMIT:E2:ROUTE_READY",
            "OWNER:2",
            "TERMINAL:SUCCESS",
        ]

    require(
        case.get("directReplacementEpoch", 0) > case.get("initialVpnEpoch", 0),
        "F3 semantic sequence requires VPN -> newer direct replacement",
    )
    prefix = [
        "SESSION:VPN_CONTINUITY_REQUIRED",
        "PERMIT:E1:ROUTE_READY",
        "OWNER:1",
        "FAIL:E1:TRANSIENT_TRANSPORT:SCHEDULE_BACKOFF",
        "PAUSE:E2:VPN_CONTINUITY_REQUIRED",
    ]
    if case_kind == "F3_VPN_CONTINUITY_RESTORE":
        require(
            case.get("resumeEpoch", 0) > case.get("directReplacementEpoch", 0),
            "VPN restore must advance beyond the direct replacement epoch",
        )
        return prefix + [
            "PERMIT:E3:ROUTE_READY",
            "OWNER:2",
            "TERMINAL:SUCCESS",
        ]
    require(
        case.get("resumeEpoch") == case.get("directReplacementEpoch"),
        "direct override must reuse the already-current direct epoch",
    )
    return prefix + [
        "PERMIT:E2:EXPLICIT_DIRECT_OVERRIDE",
        "OWNER:2",
        "TERMINAL:SUCCESS",
    ]


def _validate_schema(name: str, value: Mapping[str, Any]) -> None:
    schema = load_object(SCHEMAS / name)
    try:
        validate_instance(schema, value)
    except SchemaContractError as error:
        raise M2FOracleError(f"{name}: {error}") from error


def _validate_case(config: Mapping[str, Any], case: Mapping[str, Any]) -> None:
    require(case.get("phase") == config["phase"], "case phase mismatch")
    expected_id = config["caseScenarioId"]
    if expected_id is not None:
        require(case.get("scenarioId") == expected_id, "case scenarioId mismatch")
    require(case.get("status") == "PASS", "runtime case did not pass")
    require(case.get("deviceApi") == 36, "canonical M2-F evidence requires API 36")
    require(case.get("mediaPathUsesAdbReverse") is False, "media used adb reverse")
    require(case.get("monitorStopped") is True, "route monitor did not stop")
    require(isinstance(case.get("runId"), str) and case["runId"], "case runId missing")
    require(
        isinstance(case.get("sessionId"), str) and case["sessionId"],
        "case sessionId missing",
    )
    require(
        isinstance(case.get("persistedExtentsBefore"), list),
        "complete persistedExtentsBefore missing",
    )
    require(
        isinstance(case.get("persistedExtentsAfter"), list),
        "complete persistedExtentsAfter missing",
    )


def prepare(
    evidence_dir: pathlib.Path,
    case_kind: str,
    git_commit: str,
    created_at_utc: str,
) -> None:
    config = CASE_CONFIGS[case_kind]
    case = load_object(evidence_dir / "case.json")
    _validate_case(config, case)
    require(
        len(git_commit) == 40 and all(ch in "0123456789abcdef" for ch in git_commit),
        "git commit must be a 40-character lowercase SHA",
    )

    scenario = build_scenario(case_kind)
    try:
        validate_scenario_semantics(scenario)
    except M2ContractError as error:
        raise M2FOracleError(f"scenario semantics: {error}") from error
    _validate_schema("m2-scenario-v1.schema.json", scenario)

    manifest = {
        "schemaVersion": 1,
        "runId": case["runId"],
        "sessionId": case["sessionId"],
        "createdAtUtc": created_at_utc,
        "gitCommit": git_commit,
        "fixture": {
            "id": "F1",
            "manifestSha256": file_sha256(FIXTURE_MANIFEST),
        },
        "scenario": {
            "family": scenario["scenarioFamily"],
            "variant": scenario["variant"],
            "primaryPlane": scenario["primaryPlane"],
            "hash": scenario_sha256(scenario),
        },
        "playbackMode": "DIRECT",
        "mediaPath": "ANDROID_DEFAULT_NETWORK",
        "transport": {
            "backendId": "http-range-fetch-executor",
            "backendVersion": None,
        },
        "faultHarnesses": [
            {
                "plane": "ROUTE",
                "harnessId": "sponge-android-route-harness",
                "harnessVersion": "1",
            }
        ],
        "policies": [
            {
                "policyId": "sponge-recovery-v2",
                "version": "2",
            }
        ],
        "device": {
            "api": case["deviceApi"],
            "kind": "ANDROID_EMULATOR",
        },
        "runtime": {
            "executor": "HttpRangeFetchExecutor",
            "routeBinding": "Network.openConnection",
            "evidenceProducer": "M2-F4",
        },
        "clockDomains": [
            "ANDROID_MONOTONIC",
            "HOST_MEDIA_LAB_MONOTONIC",
        ],
        "limitations": [
            "Correctness and route/privacy evidence on the API 36 emulator; "
            "not a representative physical-device performance claim."
        ],
    }
    _validate_schema("m2-run-manifest-v1.schema.json", manifest)
    try:
        validate_run_manifest_semantics(manifest, scenario)
    except M2ContractError as error:
        raise M2FOracleError(f"run manifest semantics: {error}") from error

    write_json(evidence_dir / "scenario.json", scenario)
    write_json(evidence_dir / "run-manifest.json", manifest)


def _identity_values(document: Mapping[str, Any], keys: Iterable[str]) -> set[str]:
    values = set()
    for key in keys:
        value = document.get(key)
        if isinstance(value, str) and value:
            values.add(value)
    return values


def _verify_cross_identity(
    case: Mapping[str, Any],
    manifest: Mapping[str, Any],
    route: Mapping[str, Any],
    budget: Mapping[str, Any],
    failure: Mapping[str, Any],
    fetch: list[Mapping[str, Any]],
) -> None:
    run_id = case["runId"]
    session_id = case["sessionId"]
    for label, document in (
        ("manifest", manifest),
        ("route", route),
        ("budget", budget),
        ("failure", failure),
    ):
        require(document.get("runId") == run_id, f"{label} runId mismatch")
        require(document.get("sessionId") == session_id, f"{label} sessionId mismatch")
    require(
        all(row.get("sessionId") == session_id for row in fetch),
        "fetch evidence sessionId mismatch",
    )

    chain_id = case.get("recoveryChainId")
    require(isinstance(chain_id, str) and chain_id, "case recoveryChainId missing")
    budget_chain_ids = {
        row.get("recoveryChainId")
        for row in budget.get("events") or []
        if isinstance(row, Mapping)
    }
    require(budget_chain_ids == {chain_id}, "budget recoveryChainId mismatch")
    failure_chain_ids = {
        row.get("recoveryChainId")
        for row in failure.get("failures") or []
        if isinstance(row, Mapping)
    }
    require(
        not failure_chain_ids or failure_chain_ids == {chain_id},
        "failure recoveryChainId mismatch",
    )


def _verify_origin(case_kind: str, origin: list[Mapping[str, Any]]) -> None:
    expected_path = CASE_CONFIGS[case_kind]["targetPath"]
    matching = [
        row for row in origin
        if row.get("plane") == "data"
        and row.get("method") == "GET"
        and row.get("path") == expected_path
    ]
    require(len(matching) == 1, f"{case_kind}: expected one target origin GET")
    require(matching[0].get("status") == 206, f"{case_kind}: origin status mismatch")
    require(
        isinstance(matching[0].get("bodyBytesWritten"), int)
        and matching[0]["bodyBytesWritten"] > 0,
        f"{case_kind}: origin wrote no media bytes",
    )


def _verify_route_privacy(
    case_kind: str,
    case: Mapping[str, Any],
    route: Mapping[str, Any],
) -> str:
    if not case_kind.startswith("F3_"):
        return "NOT_APPLICABLE"

    direct_epoch = case["directReplacementEpoch"]
    resume_epoch = case["resumeEpoch"]
    evaluations = [
        row for row in route.get("policyEvaluations") or []
        if isinstance(row, Mapping)
    ]
    pauses = [
        row for row in evaluations
        if row.get("routeEpoch") == direct_epoch
        and row.get("decision") == "PAUSE"
        and row.get("reason") == "VPN_CONTINUITY_REQUIRED"
    ]
    require(pauses, "VPN -> direct replacement was not paused")

    if case_kind == "F3_VPN_CONTINUITY_RESTORE":
        require(resume_epoch > direct_epoch, "VPN restore did not create a newer epoch")
        allowed_direct = [
            row for row in evaluations
            if row.get("routeEpoch") == direct_epoch and row.get("decision") == "ALLOW"
        ]
        require(not allowed_direct, "direct replacement was automatically allowed")
        resumed = [
            row for row in evaluations
            if row.get("routeEpoch") == resume_epoch
            and row.get("decision") == "ALLOW"
            and row.get("reason") == "ROUTE_READY"
        ]
        require(resumed, "restored VPN route was not allowed")
    else:
        require(resume_epoch == direct_epoch, "override did not reuse direct epoch")
        resumed = [
            row for row in evaluations
            if row.get("routeEpoch") == direct_epoch
            and row.get("decision") == "ALLOW"
            and row.get("reason") == "EXPLICIT_DIRECT_OVERRIDE"
        ]
        require(resumed, "explicit direct override permit missing")
        require(
            case.get("directPauseRouteEventWatermark")
            == case.get("resumeRouteEventWatermark"),
            "override required an unrelated route event to wake the gate",
        )
    return "PASS"


def observed_semantic_sequence(
    case_kind: str,
    case: Mapping[str, Any],
    route: Mapping[str, Any],
    budget: Mapping[str, Any],
    failure: Mapping[str, Any],
) -> list[str]:
    """Build the normalized semantic sequence from verified runtime artifacts.

    Concrete epoch numbers are mapped to E1/E2/E3 labels so equivalent runs
    hash identically, but every token is sourced from the runtime evidence
    rather than copied from the expected case template.
    """
    budget_events = [
        row for row in budget.get("events") or []
        if isinstance(row, Mapping)
    ]
    permits = [
        row.get("permit")
        for row in budget_events
        if row.get("kind") == "ATTEMPT_PERMIT_GRANTED"
        and isinstance(row.get("permit"), Mapping)
    ]
    owners = [
        row for row in budget_events
        if row.get("kind") == "OWNER_STARTED"
    ]
    terminals = [
        row for row in budget_events
        if row.get("kind") == "CHAIN_TERMINATED"
    ]
    failures = [
        row for row in failure.get("failures") or []
        if isinstance(row, Mapping)
    ]
    evaluations = [
        row for row in route.get("policyEvaluations") or []
        if isinstance(row, Mapping)
    ]

    require(len(permits) == 2, "semantic replay requires exactly two permits")
    require(len(owners) == 2, "semantic replay requires exactly two owners")
    require(len(failures) == 1, "semantic replay requires exactly one failure")
    require(len(terminals) == 1, "semantic replay requires one terminal event")

    first_failure = failures[0]
    action = first_failure.get("action")
    require(isinstance(action, Mapping), "semantic replay failure action missing")
    terminal_reason = terminals[0].get("terminalReason")
    require(isinstance(terminal_reason, str), "semantic replay terminal reason missing")

    def epoch_label(epoch: Any) -> str:
        if case_kind == "F2_DEFAULT_ROUTE_LOSS_RESTORE":
            mapping = {
                case.get("initialRouteEpoch"): "E1",
                case.get("restoredRouteEpoch"): "E2",
            }
        else:
            mapping = {
                case.get("initialVpnEpoch"): "E1",
                case.get("directReplacementEpoch"): "E2",
            }
            resume_epoch = case.get("resumeEpoch")
            if resume_epoch != case.get("directReplacementEpoch"):
                mapping[resume_epoch] = "E3"
        require(epoch in mapping, f"semantic replay observed unexpected route epoch {epoch}")
        return mapping[epoch]

    first_permit = permits[0]
    second_permit = permits[1]
    first_epoch = epoch_label(first_permit.get("routeEpoch"))
    second_epoch = epoch_label(second_permit.get("routeEpoch"))
    failure_epoch = epoch_label(first_failure.get("routeEpoch"))
    require(first_epoch == "E1", "first permit did not use the initial protected route")
    require(failure_epoch == "E1", "first failure did not retain the initial route")

    if case_kind == "F2_DEFAULT_ROUTE_LOSS_RESTORE":
        pause = next(
            (
                row for row in evaluations
                if row.get("decision") == "PAUSE"
                and row.get("reason") == "NO_USABLE_DEFAULT"
                and row.get("routeEpoch") is None
            ),
            None,
        )
        require(pause is not None, "semantic replay missing NO_USABLE_DEFAULT pause")
        session = "SESSION:DIRECT_DEFAULT_ALLOWED"
        pause_token = "PAUSE:NONE:NO_USABLE_DEFAULT"
    else:
        direct_epoch = case.get("directReplacementEpoch")
        pause = next(
            (
                row for row in evaluations
                if row.get("routeEpoch") == direct_epoch
                and row.get("decision") == "PAUSE"
                and row.get("reason") == "VPN_CONTINUITY_REQUIRED"
            ),
            None,
        )
        require(pause is not None, "semantic replay missing VPN continuity pause")
        session = "SESSION:VPN_CONTINUITY_REQUIRED"
        pause_token = "PAUSE:E2:VPN_CONTINUITY_REQUIRED"

    return [
        session,
        f"PERMIT:{first_epoch}:{first_permit.get('reason')}",
        f"OWNER:{owners[0].get('ownerOrdinal')}",
        (
            f"FAIL:{failure_epoch}:{first_failure.get('classification')}:"
            f"{action.get('kind')}"
        ),
        pause_token,
        f"PERMIT:{second_epoch}:{second_permit.get('reason')}",
        f"OWNER:{owners[1].get('ownerOrdinal')}",
        f"TERMINAL:{terminal_reason}",
    ]


def _artifact_entry(
    root: pathlib.Path,
    relative: str,
    role: str,
    format_name: str,
) -> dict[str, Any]:
    path = root / relative
    require(path.is_file(), f"missing evidence artifact {relative}")
    return {
        "path": relative,
        "role": role,
        "format": format_name,
        "sha256": file_sha256(path),
    }


def verify(
    evidence_dir: pathlib.Path,
    case_kind: str,
    origin_path: pathlib.Path,
    expected_git_commit: str | None,
) -> dict[str, Any]:
    config = CASE_CONFIGS[case_kind]
    case = load_object(evidence_dir / "case.json")
    scenario = load_object(evidence_dir / "scenario.json")
    manifest = load_object(evidence_dir / "run-manifest.json")
    route = load_object(evidence_dir / "route-events.json")
    budget = load_object(evidence_dir / "recovery-budget-events.json")
    failure = load_object(evidence_dir / "failure-decision-events.json")
    fetch = read_jsonl(evidence_dir / "fetch-events.jsonl")
    portable_origin_path = evidence_dir / "origin-trace.jsonl"
    try:
        if origin_path.resolve() != portable_origin_path.resolve():
            shutil.copyfile(origin_path, portable_origin_path)
    except OSError as error:
        raise M2FOracleError(f"cannot retain origin trace: {error}") from error
    origin = read_jsonl(portable_origin_path)

    _validate_case(config, case)
    _validate_schema("m2-scenario-v1.schema.json", scenario)
    _validate_schema("m2-run-manifest-v1.schema.json", manifest)
    try:
        validate_scenario_semantics(scenario)
        validate_run_manifest_semantics(manifest, scenario)
    except M2ContractError as error:
        raise M2FOracleError(f"contract verification failed: {error}") from error

    expected_scenario = build_scenario(case_kind)
    require(
        scenario == expected_scenario,
        "resolved scenario differs from canonical M2-F case definition",
    )
    if expected_git_commit is not None:
        require(
            manifest.get("gitCommit") == expected_git_commit,
            "run manifest is not bound to the expected git commit",
        )

    _verify_cross_identity(case, manifest, route, budget, failure, fetch)

    try:
        route_summary = verify_route_events(route, expected_api=36)
    except RouteOracleError as error:
        raise M2FOracleError(f"route oracle failed: {error}") from error

    try:
        recovery_summary = verify_recovery(
            failure,
            budget,
            fetch,
            forbid_rechain_after_failure=True,
        )
    except RecoveryOracleError as error:
        raise M2FOracleError(f"recovery oracle failed: {error}") from error

    require(
        recovery_summary["gates"]["M2-ACC-05"]["status"] == "PASS",
        "M2-ACC-05 is not PASS",
    )
    require(
        recovery_summary["gates"]["M2-ACC-06"]["status"] == "PASS",
        "M2-ACC-06 is not PASS",
    )

    try:
        validate_persisted_identity_invariance(
            case["persistedExtentsBefore"],
            case["persistedExtentsAfter"],
            config["transition"],
        )
    except M2ContractError as error:
        raise M2FOracleError(f"M2-ACC-04 failed: {error}") from error

    privacy_status = _verify_route_privacy(case_kind, case, route)
    _verify_origin(case_kind, origin)

    for document in (case, scenario, manifest, route, budget, failure):
        try:
            scan_evidence_privacy(document)
        except M2ContractError as error:
            raise M2FOracleError(f"portable evidence privacy failure: {error}") from error
    for row in fetch:
        try:
            scan_evidence_privacy(row)
        except M2ContractError as error:
            raise M2FOracleError(f"fetch evidence privacy failure: {error}") from error
    for row in origin:
        try:
            scan_evidence_privacy(row)
        except M2ContractError as error:
            raise M2FOracleError(f"origin evidence privacy failure: {error}") from error

    expected_sequence = semantic_sequence(case_kind, case)
    observed_sequence = observed_semantic_sequence(
        case_kind,
        case,
        route,
        budget,
        failure,
    )
    require(
        observed_sequence == expected_sequence,
        "runtime semantic replay differs from the canonical case sequence",
    )
    semantic_digest = canonical_json_sha256(observed_sequence)
    scenario_hash = scenario_sha256(scenario)

    write_json(evidence_dir / "route-verification-summary.json", route_summary)
    write_json(evidence_dir / "recovery-verification-summary.json", recovery_summary)

    summary = {
        "schemaVersion": 1,
        "runId": case["runId"],
        "sessionId": case["sessionId"],
        "caseKind": case_kind,
        "scenarioHash": scenario_hash,
        "semanticDigest": semantic_digest,
        "status": "PASS",
        "routeEventCount": route_summary["eventCount"],
        "physicalAttemptCount": recovery_summary["physicalAttemptCount"],
        "gates": {
            "M2-ACC-03": privacy_status,
            "M2-ACC-04": "PASS",
            "M2-ACC-05": "PASS",
            "M2-ACC-06": "PASS",
        },
        "checks": [
            "m2-scenario-v1 schema and semantic ownership verified",
            "m2-run-manifest-v1 bound to canonical scenario and actual default network",
            "runId/sessionId/recoveryChainId joined across portable artifacts",
            "route-events-v1 replayed by independent route oracle",
            "failure/budget/fetch evidence replayed by independent recovery oracle",
            "complete pre-existing committed extent identity preserved",
            "runtime route/recovery sequence replayed, normalized and digested",
            "portable evidence privacy scan passed",
        ],
        "limitations": [
            "M2-F proves route/privacy/recovery correctness on the API 36 emulator; "
            "transport selection and representative physical-device performance belong to M2-G."
        ],
    }
    _validate_schema("m2-f-verification-summary-v1.schema.json", summary)
    write_json(evidence_dir / "f4-verification-summary.json", summary)

    entries = [
        _artifact_entry(evidence_dir, path, role, format_name)
        for path, role, format_name in SOURCE_ARTIFACTS + ORACLE_ARTIFACTS
    ]
    index = {
        "schemaVersion": 1,
        "runId": case["runId"],
        "sessionId": case["sessionId"],
        "scenarioHash": scenario_hash,
        "semanticDigest": semantic_digest,
        "artifacts": entries,
    }
    _validate_schema("m2-f-evidence-index-v1.schema.json", index)
    try:
        scan_evidence_privacy(index)
    except M2ContractError as error:
        raise M2FOracleError(f"evidence index privacy failure: {error}") from error
    write_json(evidence_dir / "evidence-index.json", index)
    return summary


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)

    prepare_parser = commands.add_parser("prepare")
    prepare_parser.add_argument("--evidence-dir", required=True, type=pathlib.Path)
    prepare_parser.add_argument("--case-kind", required=True, choices=sorted(CASE_CONFIGS))
    prepare_parser.add_argument("--git-commit", required=True)
    prepare_parser.add_argument("--created-at-utc", required=True)

    verify_parser = commands.add_parser("verify")
    verify_parser.add_argument("--evidence-dir", required=True, type=pathlib.Path)
    verify_parser.add_argument("--case-kind", required=True, choices=sorted(CASE_CONFIGS))
    verify_parser.add_argument("--origin", required=True, type=pathlib.Path)
    verify_parser.add_argument("--expected-git-commit")

    args = parser.parse_args(argv)
    try:
        if args.command == "prepare":
            prepare(
                args.evidence_dir,
                args.case_kind,
                args.git_commit,
                args.created_at_utc,
            )
            print(f"{args.case_kind}: canonical scenario/run manifest prepared")
        else:
            summary = verify(
                args.evidence_dir,
                args.case_kind,
                args.origin,
                args.expected_git_commit,
            )
            print(
                f"{args.case_kind}: M2-F4 PASS "
                f"scenario={summary['scenarioHash']} "
                f"semantic={summary['semanticDigest']}"
            )
    except (M2FOracleError, OSError, KeyError, TypeError, ValueError) as error:
        print(f"M2-F4 verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
