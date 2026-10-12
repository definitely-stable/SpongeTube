"""Independently audit a bounded M3-A2 V1 qualification failure bundle.

A failed V1 gate is not evidence that YouTube or a client family is generally
unusable. This verifier attests only to the exact predeclared negative sweep.
It neither contacts a provider nor relaxes the qualification gate.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys
import zipfile
from typing import Any, Mapping

from m3_matrix import (
    M3MatrixError,
    load_registry,
    validate_plan,
    validate_probe_against_registry,
    validate_qualification_document,
)

MAX_ENTRY_BYTES = 128 * 1024
MAX_TOTAL_BYTES = 2 * 1024 * 1024


class NegativeAuditError(ValueError):
    pass


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise NegativeAuditError(message)


def _read_json_bytes(raw: bytes, name: str) -> Mapping[str, Any]:
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeError, ValueError) as exc:
        raise NegativeAuditError(f"{name}: invalid UTF-8 JSON") from exc
    _require(isinstance(value, dict), f"{name}: expected JSON object")
    return value


def audit_bundle(
    bundle_path: pathlib.Path,
    registry: Mapping[str, Any],
    plan: Mapping[str, Any],
) -> dict[str, Any]:
    """Check exact 6-lane negative sweep, canonical evidence and provenance."""
    validate_plan(plan, registry)
    gate = plan["qualificationGates"]["V1"]
    lanes = list(gate["orderedLanes"])
    _require(lanes == list(registry["primaryLanes"]), "qualification lane order drift")
    _require(len(lanes) == 6 and len(set(lanes)) == 6, "expected six unique V1 lanes")
    v1 = next(case for case in registry["cases"] if case["caseClass"] == "V1")

    expected = {"batch-summary-v1.json"}
    for lane in lanes:
        expected.add(f"probes/v1-{lane.lower()}.json")
        expected.add(f"qualification/v1-{lane.lower()}.json")

    try:
        with zipfile.ZipFile(bundle_path) as archive:
            members = archive.infolist()
            names = [item.filename for item in members]
            _require(len(names) == len(set(names)), "duplicate ZIP member")
            _require(set(names) == expected, "unexpected/missing bundle member")
            _require(
                all(not item.is_dir() and item.file_size <= MAX_ENTRY_BYTES for item in members),
                "oversized/directory ZIP member",
            )
            _require(
                sum(item.file_size for item in members) <= MAX_TOTAL_BYTES,
                "bundle exceeds bounded evidence size",
            )
            data = {name: archive.read(name) for name in names}
    except (OSError, zipfile.BadZipFile, RuntimeError) as exc:
        raise NegativeAuditError("unreadable evidence bundle") from exc

    summary = _read_json_bytes(data["batch-summary-v1.json"], "batch-summary-v1.json")
    _require(summary.get("schemaVersion") == 1, "unexpected batch summary version")
    _require(summary.get("status") == "QUALIFICATION_NOT_MET", "not a negative sweep")
    _require(summary.get("registryId") == registry["registryId"], "registry mismatch")
    _require(summary.get("planId") == plan["planId"], "plan mismatch")
    _require(summary.get("executedProviderProbeCount") == 6, "wrong provider-probe count")
    _require(summary.get("matrixCellsExecuted") == 6, "wrong cell count")
    # Legacy v1 summary misnames the 12 unexecuted cells as 'remainingCellsExecuted'.
    _require(summary.get("remainingCellsExecuted") == 12, "wrong number of unexecuted cells")
    git_sha = summary.get("gitCommit")
    _require(
        isinstance(git_sha, str)
        and len(git_sha) == 40
        and all(c in "0123456789abcdef" for c in git_sha),
        "invalid exact source SHA",
    )
    attempts = summary.get("qualificationAttempts")
    _require(isinstance(attempts, list) and len(attempts) == 6, "incomplete attempt ledger")

    cells: list[dict[str, Any]] = []
    runner_images: set[str] = set()
    for i, lane in enumerate(lanes, 1):
        name = f"probes/v1-{lane.lower()}.json"
        raw = data[name]
        _require(
            raw == data[f"qualification/v1-{lane.lower()}.json"],
            f"{lane}: matrix/qualification artifact divergence",
        )
        artifact = _read_json_bytes(raw, name)
        try:
            validate_probe_against_registry(artifact, registry)
        except (M3MatrixError, ValueError) as exc:
            raise NegativeAuditError(f"{lane}: invalid registered probe") from exc
        case = artifact["videoCase"]
        _require(case["caseClass"] == "V1", f"{lane}: wrong case class")
        _require(case["caseRevision"] == v1["caseRevision"], f"{lane}: case revision drift")
        _require(
            case["providerContentIdSha256"] == v1["providerContentIdSha256"],
            f"{lane}: provider fingerprint drift",
        )
        _require(artifact["client"]["lane"] == lane, f"{lane}: lane drift")
        _require(artifact["client"]["profileId"] == registry["laneProfiles"][lane], f"{lane}: profile drift")
        _require(artifact["gitCommit"] == git_sha, f"{lane}: mixed source commits")
        runner_images.add(artifact["environment"]["runnerImage"])

        try:
            validate_qualification_document(artifact, registry, case_class="V1")
        except M3MatrixError as exc:
            failure_reason = str(exc)
        else:
            raise NegativeAuditError(f"{lane}: a qualified lane was declared negative")

        attempt = attempts[i - 1]
        _require(
            attempt.get("ordinal") == i
            and attempt.get("caseClass") == "V1"
            and attempt.get("lane") == lane
            and attempt.get("qualified") is False,
            f"{lane}: attempt ledger mismatch",
        )
        _require(attempt.get("reason") == failure_reason, f"{lane}: qualification reason drift")
        _require(
            attempt.get("outcome") == artifact["outcome"]["classification"]
            and attempt.get("mediaLayout") == artifact["delivery"]["mediaLayout"]
            and attempt.get("https") == artifact["capabilities"]["https"],
            f"{lane}: attempt/result mismatch",
        )

        cells.append({
            "lane": lane,
            "probeSha256": hashlib.sha256(raw).hexdigest(),
            "metadataRequestsUsed": artifact["budget"]["metadataRequestsUsed"],
            "mediaProbeCount": len(artifact["budget"]["mediaProbes"]),
            "mediaLayout": artifact["delivery"]["mediaLayout"],
            "https": artifact["capabilities"]["https"],
            "outcome": artifact["outcome"]["classification"],
            "qualification": "NOT_MET",
            "reason": failure_reason,
        })
    _require(len(runner_images) == 1, "mixed runner images")
    _require(
        sum(c["metadataRequestsUsed"] for c in cells) <= 6 * 32,
        "request budget inconsistent with frozen M3-A0",
    )

    return {
        "schemaVersion": 1,
        "status": "NEGATIVE_V1_QUALIFICATION_AUDITED",
        "registryId": registry["registryId"],
        "planId": plan["planId"],
        "gitCommit": git_sha,
        "runnerImage": next(iter(runner_images)),
        "bundleSha256": hashlib.sha256(bundle_path.read_bytes()).hexdigest(),
        "executedCells": 6,
        "unexecutedCells": 12,
        "totalMetadataRequests": sum(c["metadataRequestsUsed"] for c in cells),
        "totalMediaProbes": sum(c["mediaProbeCount"] for c in cells),
        "cells": cells,
        "completeMatrix": False,
        "globalDecision": "NOT_MADE_M3_A2",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=pathlib.Path, required=True)
    parser.add_argument("--registry", type=pathlib.Path, required=True)
    parser.add_argument("--plan", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    try:
        result = audit_bundle(
            args.bundle,
            load_registry(args.registry),
            json.loads(args.plan.read_text(encoding="utf-8")),
        )
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(
            f"M3-A2 negative sweep audited: {result['executedCells']} cells, "
            f"{result['totalMediaProbes']} media probes, no architecture decision"
        )
        return 0
    except (NegativeAuditError, M3MatrixError, OSError, ValueError, KeyError) as exc:
        print(f"M3-A2 negative audit failed closed: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
