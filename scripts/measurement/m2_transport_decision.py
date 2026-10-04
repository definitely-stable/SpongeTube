#!/usr/bin/env python3
"""M2-G3 retained transport decision producer and independent verifier.

G3 consumes only the canonical M2-G2 evidence index. It does not rank emulator
timings or alter production transport behavior. With the current G2 evidence,
both required backends are technically eligible and no retained evidence
justifies selecting one.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys
from typing import Any, Mapping

SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[1]
SCHEMAS = REPO_ROOT / ".work" / "schemas"
sys.path.insert(0, str(SCRIPT_DIR))

from m2_contracts import M2ContractError, scan_evidence_privacy  # noqa: E402
from schema_subset import (  # noqa: E402
    SchemaContractError,
    validate_instance,
    validate_schema_definition,
)

G2_SCHEMA = SCHEMAS / "m2-g2-evidence-index-v1.schema.json"
DECISION_SCHEMA = SCHEMAS / "m2-g3-transport-decision-v1.schema.json"

EXPECTED_BACKENDS = (
    "HTTP_URL_CONNECTION_ROUTE_BOUND",
    "PLATFORM_HTTP_ENGINE",
)
EXPECTED_EXPERIMENT_CLAIMS = {
    "N0_CONTROL": "CONTROL_EQUIVALENT",
    "N2_HIGH_RTT_JITTER": "OBSERVED_EFFECT_EQUIVALENT",
    "N3_BURST_PACKET_LOSS": "OBSERVED_EFFECT_EQUIVALENT",
    "N5_BURST_LOSS": "INCONCLUSIVE_STOCHASTIC_EFFECT",
    "N6_TRANSPORT_RESET": "TRANSPORT_RESET_EQUIVALENT",
    "N6_DEFAULT_ROUTE_LOSS_RESTORE": "ROUTE_REPLACEMENT_EQUIVALENT",
}
REASON_CODES = (
    "G2_M2_ACC_10_PASS",
    "BOTH_REQUIRED_BACKENDS_TECHNICALLY_ELIGIBLE",
    "CORRECTNESS_EQUIVALENT",
    "RECOVERY_EQUIVALENT",
    "ROUTE_BINDING_EQUIVALENT",
    "NO_DISCRIMINATING_CORRECTNESS_RESILIENCE_EVIDENCE",
    "NO_PHYSICAL_DEVICE_EVIDENCE",
    "PERFORMANCE_SELECTION_FORBIDDEN",
    "N5_STOCHASTIC_EFFECT_INCONCLUSIVE",
)
REOPEN_TRIGGERS = (
    "PHYSICAL_DEVICE_EVIDENCE",
    "PROVIDER_REQUIREMENT",
    "MEASURED_COMPATIBILITY_OR_RESILIENCE_GAP",
)
LIMITATIONS = (
    "API36 emulator evidence supports correctness, resilience equivalence and directional observations only.",
    "No representative physical-device latency, battery or thermal claim is retained.",
    "Canonical N5 remains an inconclusive stochastic-effect observation and is not upgraded by G3.",
    "G3 introduces no production transport dependency and changes no runtime transport selection.",
)


class TransportDecisionError(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise TransportDecisionError(message)


def read_json(path: pathlib.Path) -> dict[str, Any]:
    require(path.is_file(), f"missing JSON: {path}")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise TransportDecisionError(f"{path}: invalid JSON: {error}") from error
    require(isinstance(value, dict), f"{path}: expected JSON object")
    return value


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_schema(path: pathlib.Path, document: Mapping[str, Any]) -> None:
    schema = read_json(path)
    try:
        validate_schema_definition(schema)
        validate_instance(schema, document, root_schema=schema)
    except SchemaContractError as error:
        raise TransportDecisionError(f"{path.name}: {error}") from error


def privacy(document: Any, label: str) -> None:
    try:
        scan_evidence_privacy(document)
    except M2ContractError as error:
        raise TransportDecisionError(f"{label}: privacy failure: {error}") from error


def validate_g2_source(
    source: Mapping[str, Any],
    *,
    expected_git_commit: str | None,
) -> None:
    validate_schema(G2_SCHEMA, source)
    privacy(source, "G2 evidence index")

    require(source["status"] == "PASS", "G2 aggregate is not PASS")
    require(source["gateId"] == "M2-ACC-10", "G2 gate identity drift")
    require(source["gitCommit"] == source["checkoutCommit"], "G2 source/checkout commit mismatch")
    if expected_git_commit is not None:
        require(
            source["gitCommit"] == expected_git_commit,
            "G2 aggregate does not belong to the expected source revision",
        )

    backend_ids = source["backendIds"]
    require(
        len(backend_ids) == len(set(backend_ids)) == 2
        and set(backend_ids) == set(EXPECTED_BACKENDS),
        "G2 candidate backend set drift",
    )
    identities = source["backendIdentities"]
    identity_ids = [row["backendId"] for row in identities]
    require(
        len(identity_ids) == len(set(identity_ids)) == 2
        and set(identity_ids) == set(EXPECTED_BACKENDS),
        "G2 backend identity set drift",
    )

    experiments = {row["experimentId"]: row for row in source["experiments"]}
    require(
        len(experiments) == len(source["experiments"])
        and set(experiments) == set(EXPECTED_EXPERIMENT_CLAIMS),
        "G2 required experiment set drift",
    )
    for experiment_id, expected_claim in EXPECTED_EXPERIMENT_CLAIMS.items():
        row = experiments[experiment_id]
        require(row["status"] == "PASS", f"{experiment_id}: owning evidence not PASS")
        require(
            row["resilienceClaim"] == expected_claim,
            f"{experiment_id}: resilience claim drift",
        )

    aggregate = source["aggregate"]
    require(aggregate["correctnessEquivalent"] is True, "G2 correctness equivalence missing")
    require(aggregate["recoveryEquivalent"] is True, "G2 recovery equivalence missing")
    require(aggregate["routeBindingEquivalent"] is True, "G2 route-binding equivalence missing")
    require(aggregate["technicalEligibility"] is True, "G2 technical eligibility missing")
    require(
        aggregate["pairedDirectionalMetricsAvailable"] is True,
        "G2 paired directional metrics missing",
    )
    require(
        aggregate["physicalDeviceEvidence"] is False,
        "G3 v1 expects the canonical emulator-only G2 evidence boundary",
    )
    require(
        aggregate["claimScope"] == "EMULATOR_DIRECTIONAL",
        "G2 claim scope is not emulator-directional",
    )
    require(
        aggregate["performanceSelectionAllowed"] is False,
        "G2 unexpectedly permits performance selection",
    )
    require(aggregate["selectedBackend"] is None, "G2 already selected a backend")
    require(
        aggregate["n5ResilienceEffect"] == "INCONCLUSIVE_STOCHASTIC_EFFECT",
        "G2 upgraded canonical N5 beyond retained evidence",
    )


def build_decision(
    source: Mapping[str, Any],
    *,
    source_path: pathlib.Path,
    expected_git_commit: str | None,
) -> dict[str, Any]:
    validate_g2_source(source, expected_git_commit=expected_git_commit)
    aggregate = source["aggregate"]
    decision = {
        "schemaVersion": 1,
        "status": "PASS",
        "source": {
            "schemaId": "m2-g2-evidence-index-v1",
            "runId": source["runId"],
            "gitCommit": source["gitCommit"],
            "checkoutCommit": source["checkoutCommit"],
            "gateId": source["gateId"],
            "requiredExperimentCount": source["requiredExperimentCount"],
        },
        "sourceArtifact": {
            "path": source_path.name,
            "sha256": sha256(source_path),
            "sizeBytes": source_path.stat().st_size,
        },
        "candidateBackends": list(EXPECTED_BACKENDS),
        "technicalEligibility": True,
        "equivalence": {
            "correctness": aggregate["correctnessEquivalent"],
            "recovery": aggregate["recoveryEquivalent"],
            "routeBinding": aggregate["routeBindingEquivalent"],
            "discriminatingCorrectnessResilienceEvidence": False,
        },
        "deviceEvidence": {
            "deviceClass": source["deviceClass"],
            "androidApi": source["androidApi"],
            "claimScope": aggregate["claimScope"],
            "physicalDeviceEvidence": aggregate["physicalDeviceEvidence"],
            "pairedDirectionalMetricsAvailable": aggregate["pairedDirectionalMetricsAvailable"],
            "performanceSelectionAllowed": aggregate["performanceSelectionAllowed"],
        },
        "n5ResilienceEffect": aggregate["n5ResilienceEffect"],
        "dependencyPolicy": {
            "externalTransportDependencyIntroduced": False,
            "conditionalCandidatesIntroduced": False,
            "selectionNeedsDependencyClearance": True,
        },
        "decision": {
            "state": "TECHNICALLY_ELIGIBLE_NO_SELECTION",
            "selectedBackend": None,
            "basis": "CORRECTNESS_AND_RESILIENCE_EQUIVALENCE",
            "reasonCodes": list(REASON_CODES),
            "reopenTriggers": list(REOPEN_TRIGGERS),
        },
        "nextAllowedAction": "M2_H_CANONICAL_ACCEPTANCE",
        "limitations": list(LIMITATIONS),
    }
    validate_schema(DECISION_SCHEMA, decision)
    privacy(decision, "G3 decision")
    return decision


def verify_decision(
    decision: Mapping[str, Any],
    source: Mapping[str, Any],
    *,
    source_path: pathlib.Path,
    expected_git_commit: str | None,
) -> None:
    validate_schema(DECISION_SCHEMA, decision)
    privacy(decision, "G3 decision")
    expected = build_decision(
        source,
        source_path=source_path,
        expected_git_commit=expected_git_commit,
    )
    require(decision == expected, "G3 decision differs from independently recomputed verdict")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    produce = subparsers.add_parser("produce")
    produce.add_argument("--g2-index", required=True, type=pathlib.Path)
    produce.add_argument("--expected-git-commit")
    produce.add_argument("--output", required=True, type=pathlib.Path)

    verify = subparsers.add_parser("verify")
    verify.add_argument("--g2-index", required=True, type=pathlib.Path)
    verify.add_argument("--decision", required=True, type=pathlib.Path)
    verify.add_argument("--expected-git-commit")

    return parser.parse_args()


def main() -> int:
    args = parse_args()
    source = read_json(args.g2_index)
    if args.command == "produce":
        document = build_decision(
            source,
            source_path=args.g2_index,
            expected_git_commit=args.expected_git_commit,
        )
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(document, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        return 0

    decision = read_json(args.decision)
    verify_decision(
        decision,
        source,
        source_path=args.g2_index,
        expected_git_commit=args.expected_git_commit,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
