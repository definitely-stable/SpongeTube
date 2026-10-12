"""Frozen M3-A2-C1 V0/V2 paired controls, independent of V1 qualification.

Research-only. Executes four existing matrix cells exactly once each and emits
valid m3-a-probe-v1 evidence. It cannot accept M3-A2 or select an adapter.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import shutil
import sys
from typing import Any, Mapping, Sequence

from m3_batch import (
    BatchInputs, M3BatchError, _preflight_inputs, _run_cell,
    _safe_cell_name, _scan_bundle_for_raw_ids, _write_json, _write_probe,
    derive_case_inputs_from_source,
)
from m3_matrix import (
    M3MatrixError, aggregate_probes, load_registry, render_markdown,
    validate_plan, validate_probe_against_registry,
)
from m3_probe import ProbeRuntimeError, run_live_probe

PAIRS: tuple[tuple[str, str], ...] = (
    ("V0", "WEB_EMBEDDED"),
    ("V2", "WEB_EMBEDDED"),
    ("V0", "TV"),
    ("V2", "TV"),
)
PLAN_ID = "m3-a2-v0-v2-control-pilot-2026-10-12-v1"
SINGLE_PROBE_METADATA_CAP = 32
SINGLE_PROBE_MEDIA_CAP = 8


class ControlPilotError(ValueError):
    pass


def _require(value: bool, message: str) -> None:
    if not value:
        raise ControlPilotError(message)


def build_control_plan(registry: Mapping[str, Any], matrix_plan: Mapping[str, Any]) -> dict[str, Any]:
    validate_plan(matrix_plan, registry)
    indexed = {(cell["caseClass"], cell["lane"]): cell for cell in matrix_plan["cells"]}
    _require(all(key in indexed for key in PAIRS), "controls are not all frozen matrix cells")
    return {
        "planVersion": 1,
        "planId": PLAN_ID,
        "registryId": registry["registryId"],
        "matrixPlanId": matrix_plan["planId"],
        "cellCount": len(PAIRS),
        "maxMetadataRequestsTotal": len(PAIRS) * SINGLE_PROBE_METADATA_CAP,
        "maxMediaProbesTotal": len(PAIRS) * SINGLE_PROBE_MEDIA_CAP,
        "cells": [dict(indexed[key]) for key in PAIRS],
        "v1QualificationRequired": False,
        "fullMatrixAccepted": False,
        "globalDecision": "NOT_MADE_M3_A2",
    }


def validate_control_plan(
    candidate: Mapping[str, Any], registry: Mapping[str, Any], matrix_plan: Mapping[str, Any]
) -> None:
    _require(isinstance(candidate, Mapping), "control plan must be an object")
    _require(
        candidate == build_control_plan(registry, matrix_plan),
        "control plan differs from exact frozen independent control expansion",
    )


def execute_controls(
    *,
    registry: Mapping[str, Any],
    matrix_plan: Mapping[str, Any],
    control_plan: Mapping[str, Any],
    inputs: BatchInputs,
    output_dir: pathlib.Path,
    probe_runner=run_live_probe,
) -> dict[str, Any]:
    """Provider calls are injectable; every call is one existing frozen cell."""
    validate_control_plan(control_plan, registry, matrix_plan)
    _preflight_inputs(registry, matrix_plan, inputs)

    output_dir.mkdir(parents=True, exist_ok=True)
    for relative in ("probes", "aggregate"):
        path = output_dir / relative
        if path.exists():
            shutil.rmtree(path)
        path.mkdir(parents=True)
    for name in ("control-summary-v1.json", "manifest-v1.json"):
        path = output_dir / name
        if path.exists():
            path.unlink()

    documents: list[Mapping[str, Any]] = []
    observations: list[dict[str, Any]] = []
    for ordinal, cell in enumerate(control_plan["cells"], start=1):
        artifact = _run_cell(
            registry=registry, cell=cell, inputs=inputs, probe_runner=probe_runner,
        )
        # _run_cell binds registry, oracle, profile, exact SHA and runner.
        validate_probe_against_registry(artifact, registry)
        _write_probe(output_dir / "probes" / _safe_cell_name(cell), artifact)
        documents.append(artifact)
        probes = artifact["budget"]["mediaProbes"]
        _require(
            len(probes) <= SINGLE_PROBE_MEDIA_CAP
            and all(int(p["byteLimit"]) <= 65536 for p in probes),
            "current C1 pilot exceeds bounded 64-KiB direct-media probe envelope",
        )
        observations.append({
            "ordinal": ordinal,
            "caseClass": cell["caseClass"],
            "lane": cell["lane"],
            "outcome": artifact["outcome"]["classification"],
            "mediaLayout": artifact["delivery"]["mediaLayout"],
            "https": artifact["capabilities"]["https"],
            "metadataRequestsUsed": artifact["budget"]["metadataRequestsUsed"],
            "mediaProbeCount": len(probes),
            "mediaBytesRead": sum(int(p["bytesRead"]) for p in probes),
        })
    _require(len(documents) == 4, "four canonical paired controls must execute")
    _require(
        sum(o["metadataRequestsUsed"] for o in observations)
        <= control_plan["maxMetadataRequestsTotal"],
        "control metadata budget exceeded",
    )
    _require(
        sum(o["mediaProbeCount"] for o in observations) <= control_plan["maxMediaProbesTotal"],
        "control media probe budget exceeded",
    )

    # Existing aggregator admits V0/V2 without V1 qualification. It will
    # leave 14 cells MISSING and can never make an architecture decision.
    report = aggregate_probes(
        registry, documents, require_complete=False, repo_root=output_dir,
    )
    _require(
        report["observedCellCount"] == 4
        and report["plannedCellCount"] == 18
        and report["complete"] is False
        and len(report["missingCells"]) == 14
        and report["globalDecision"] == "NOT_MADE_M3_A2",
        "controls were incorrectly promoted to full matrix",
    )
    _write_json(output_dir / "aggregate" / "control-matrix-v1.json", report)
    (output_dir / "aggregate" / "control-matrix-v1.md").write_text(
        render_markdown(report) + "\n", encoding="utf-8",
    )
    # An unresolved pair is a research observation, not a diagnosis that
    # egress, extractor, provider or video caused the unresolved state.
    by_lane: dict[str, list[Mapping[str, Any]]] = {}
    for row in observations:
        by_lane.setdefault(str(row["lane"]), []).append(row)
    pair_status = []
    for lane in ("WEB_EMBEDDED", "TV"):
        entries = by_lane[lane]
        _require(
            [e["caseClass"] for e in entries] == ["V0", "V2"],
            "paired-control order drift",
        )
        both_unresolved = all(
            e["mediaProbeCount"] == 0
            and e["mediaLayout"] == "UNKNOWN"
            and e["https"] == "UNKNOWN"
            for e in entries
        )
        pair_status.append({
            "lane": lane,
            "status": "BOTH_UNRESOLVED" if both_unresolved else "OBSERVATIONS_NOT_UNIFORMLY_UNKNOWN",
        })

    summary = {
        "schemaVersion": 1,
        "status": "CONTROL_PILOT_RECORDED",
        "gitCommit": inputs.git_commit,
        "runnerImage": inputs.runner_image,
        "registryId": registry["registryId"],
        "matrixPlanId": matrix_plan["planId"],
        "controlPlanId": control_plan["planId"],
        "executedProviderProbes": len(documents),
        "metadataRequestsUsed": sum(o["metadataRequestsUsed"] for o in observations),
        "mediaProbeCount": sum(o["mediaProbeCount"] for o in observations),
        "observations": observations,
        "pairStatus": pair_status,
        "fullMatrixAccepted": False,
        "v1Qualification": "NOT_EVALUATED",
        "globalDecision": "NOT_MADE_M3_A2",
    }
    _write_json(output_dir / "control-summary-v1.json", summary)
    files = sorted(path for path in output_dir.rglob("*") if path.is_file())
    manifest = {
        "schemaVersion": 1,
        "controlPlanId": control_plan["planId"],
        "files": [{
            "path": path.relative_to(output_dir).as_posix(),
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        } for path in files],
    }
    _write_json(output_dir / "manifest-v1.json", manifest)
    _scan_bundle_for_raw_ids(output_dir, inputs)
    return summary


def _read_json(path: pathlib.Path) -> Mapping[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    _require(isinstance(value, dict), f"{path.name}: JSON object required")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("validate-plan", "run"))
    parser.add_argument("--registry", type=pathlib.Path, required=True)
    parser.add_argument("--matrix-plan", type=pathlib.Path, required=True)
    parser.add_argument("--control-plan", type=pathlib.Path, required=True)
    parser.add_argument("--source-file", type=pathlib.Path)
    parser.add_argument("--git-commit")
    parser.add_argument("--runner-image")
    parser.add_argument("--output-dir", type=pathlib.Path)
    args = parser.parse_args()
    try:
        registry = load_registry(args.registry)
        matrix_plan = _read_json(args.matrix_plan)
        control_plan = _read_json(args.control_plan)
        validate_control_plan(control_plan, registry, matrix_plan)
        if args.command == "validate-plan":
            print("M3-A2-C1 frozen paired control plan valid: 4 cells")
            return 0
        _require(
            args.source_file is not None and bool(args.git_commit)
            and bool(args.runner_image) and args.output_dir is not None,
            "run requires pinned source, exact SHA, runner image and output",
        )
        # Never persist or print raw content IDs or pinned source text.
        source = args.source_file.read_text(encoding="utf-8")
        case_inputs = derive_case_inputs_from_source(registry, source)
        inputs = BatchInputs(
            v0_video_id=case_inputs["V0"],
            v1_video_id=case_inputs["V1"],
            v2_video_id=case_inputs["V2"],
            git_commit=args.git_commit,
            runner_image=args.runner_image,
            media_probe_mode="range-set",
        )
        summary = execute_controls(
            registry=registry, matrix_plan=matrix_plan, control_plan=control_plan,
            inputs=inputs, output_dir=args.output_dir,
        )
        print(
            f"M3-A2-C1 recorded: cells={summary['executedProviderProbes']}, "
            f"metadataRequests={summary['metadataRequestsUsed']}, "
            f"mediaProbes={summary['mediaProbeCount']} "
            "globalDecision=NOT_MADE_M3_A2"
        )
        return 0
    except (ControlPilotError, M3BatchError, M3MatrixError, ProbeRuntimeError, OSError, ValueError) as exc:
        # Never print provider-dependent error messages from live requests.
        print(f"M3-A2-C1 failed closed: {type(exc).__name__}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
