"""M3-A2-C2-D3: frozen paired yt-dlp version experiment.

No JS, tokens, credentials, retries, media downloads or production adoption.
Only an explicit 'run' or 'observe-one' CLI can contact YouTube.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import pathlib
import re
import subprocess
import sys
import types
from collections.abc import Mapping
from typing import Any, Callable

import m3_c2_d1 as d1
import m3_c2_d2 as d2
from m3_batch import derive_case_inputs_from_source
from m3_matrix import load_registry, validate_case_input

PLAN_ID = "m3-a2-c2-d3-paired-oracles-2026-10-25-v1"
PROTOCOL = "m3-a2-c2-d3-paired-oracles-v1"
ORACLES = {
    "A": {"id": "YT_DLP", "version": "2026.08.19",
          "revision": "594bd50c2c78ac432f81600d309fdc4e0a92d82c"},
    "B": {"id": "YT_DLP", "version": "2026.10.21",
          "revision": "6ccd977303412151ee23352c6257dd658ffc1987"},
}
ORDER = (("A", "B"), ("B", "A"), ("B", "A"), ("A", "B"))
MAX_PER_OBSERVATION = 32
MAX_TOTAL = 8 * MAX_PER_OBSERVATION
CASE_KEYS = frozenset({"caseClass", "caseRevision", "providerContentIdSha256"})
CLIENT_KEYS = frozenset({"lane", "profileId"})


def require(ok: bool, reason: str) -> None:
    if not ok:
        raise ValueError(reason)


def cloned_registry(registry: Mapping[str, Any], arm: str) -> dict[str, Any]:
    require(arm in ORACLES, "unknown reference arm")
    result = copy.deepcopy(registry)
    result["referenceOracle"] = dict(ORACLES[arm])
    return result


def build_plan(registry: Mapping[str, Any], c1: Mapping[str, Any],
               d0: Mapping[str, Any], d1_plan: Mapping[str, Any],
               d2_plan: Mapping[str, Any]) -> dict[str, Any]:
    d2.validate_plan(d2_plan, registry, c1, d0, d1_plan)
    require(d2_plan["referenceOracle"] == ORACLES["A"], "reference A must match D2")
    execution = [
        {"ordinal": 2 * i + j + 1, "caseClass": cell["caseClass"],
         "lane": cell["lane"], "arm": arm}
        for i, (cell, arms) in enumerate(zip(d2_plan["cells"], ORDER))
        for j, arm in enumerate(arms)
    ]
    return {
        "schemaVersion": 1, "planId": PLAN_ID, "protocol": PROTOCOL,
        "registryId": registry["registryId"],
        "d2PlanId": d2_plan["planId"],
        "oracles": copy.deepcopy(ORACLES),
        "mode": copy.deepcopy(d2_plan["mode"]),
        "cells": copy.deepcopy(d2_plan["cells"]),
        "execution": execution,
        "metadataRequestLimitPerObservation": MAX_PER_OBSERVATION,
        "maxTotalMetadataRequests": MAX_TOTAL,
        "maxTotalMediaProbes": 0,
        "treatment": "FULL_REFERENCE_VERSION_NOT_SINGLE_EXTRACTOR_CHANGE",
        "globalDecision": "NOT_MADE_M3_A2",
    }


def validate_plan(plan: Mapping[str, Any], registry: Mapping[str, Any],
                  c1: Mapping[str, Any], d0: Mapping[str, Any],
                  d1_plan: Mapping[str, Any], d2_plan: Mapping[str, Any]) -> None:
    require(isinstance(plan, Mapping) and plan == build_plan(
        registry, c1, d0, d1_plan, d2_plan
    ), "D3 plan must exactly match frozen balanced paired reference protocol")


def validate_inputs(registry: Mapping[str, Any], ids: Mapping[str, str]) -> None:
    for case_class in ("V0", "V1", "V2"):
        case = next(x for x in registry["cases"] if x["caseClass"] == case_class)
        validate_case_input(registry, case_class=case_class,
                            case_revision=case["caseRevision"], video_id=ids[case_class])


def _observation_key(step: Mapping[str, Any]) -> str:
    return f"{step['ordinal']:02d}-{step['caseClass'].lower()}-{step['lane'].lower()}-{step['arm'].lower()}.json"


def validate_observation(doc: Any, registry: Mapping[str, Any],
                         plan: Mapping[str, Any], step: Mapping[str, Any],
                         git_commit: str, runner_image: str) -> None:
    require(type(doc) is dict, "D3 observation is not an object")
    require(set(doc) == {"schemaVersion", "protocol", "ordinal", "arm", "caseClass",
                         "lane", "observation"}, "D3 observation has unexpected fields")
    require(type(doc["schemaVersion"]) is int and doc["schemaVersion"] == 1
            and doc["protocol"] == PROTOCOL
            and doc["ordinal"] == step["ordinal"]
            and doc["arm"] == step["arm"]
            and doc["caseClass"] == step["caseClass"]
            and doc["lane"] == step["lane"], "observation identity drift")
    observation = doc["observation"]
    reference_registry = cloned_registry(registry, step["arm"])
    d2.validate_document_d2(observation, reference_registry)
    cell = next(x for x in plan["cells"] if x["caseClass"] == step["caseClass"]
                and x["lane"] == step["lane"])
    require(observation["case"] == {k: cell[k] for k in CASE_KEYS}
            and observation["client"] == {
                "lane": cell["lane"], "profileId": cell["profileId"],
            }, "case/client fingerprint drift")
    require(observation["referenceOracle"] == ORACLES[step["arm"]]
            and observation["gitCommit"] == git_commit
            and observation["runnerImage"] == runner_image,
            "oracle/runner/source provenance drift")
    require(observation["metadataRequestsUsed"] <= MAX_PER_OBSERVATION
            and observation["decision"] == "DIAGNOSTIC_ONLY_NO_PROVIDER_INFERENCE",
            "unbounded or promoted research outcome")


def observation_envelope(step: Mapping[str, Any], observation: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "schemaVersion": 1, "protocol": PROTOCOL,
        "ordinal": step["ordinal"], "arm": step["arm"],
        "caseClass": step["caseClass"], "lane": step["lane"],
        "observation": dict(observation),
    }


def _summary(records: list[dict[str, Any]], git_commit: str, runner_image: str) -> dict[str, Any]:
    groups = []
    for i in range(0, len(records), 2):
        pair = records[i:i + 2]
        by_arm = {r["arm"]: r["observation"] for r in pair}
        a, b = by_arm["A"], by_arm["B"]
        groups.append({
            "caseClass": pair[0]["caseClass"], "lane": pair[0]["lane"],
            "executionOrder": [r["arm"] for r in pair],
            "byArm": {
                role: {
                    "metadataRequestsUsed": by_arm[role]["metadataRequestsUsed"],
                    "extractionCompleted": by_arm[role]["extractionCompleted"],
                    "extractorStage": by_arm[role]["extractorStage"],
                    "extractorErrorType": by_arm[role]["extractorErrorType"],
                    "nestedCauseClass": by_arm[role]["nestedCauseClass"],
                    "responseHttpClasses": [
                        ev["httpClass"] for ev in by_arm[role]["responseEvents"]
                        if ev["kind"] == "RETURNED"],
                    "responseMimeFamilies": [
                        ev["contentTypeFamily"] for ev in by_arm[role]["responseEvents"]
                        if ev["kind"] == "RETURNED"],
                } for role in ("A", "B")
            },
            "completionDiffers": a["extractionCompleted"] != b["extractionCompleted"],
            "extractorStageDiffers": a["extractorStage"] != b["extractorStage"],
        })
    return {
        "schemaVersion": 1, "planId": PLAN_ID, "protocol": PROTOCOL,
        "gitCommit": git_commit, "runnerImage": runner_image,
        "oracles": copy.deepcopy(ORACLES), "observations": len(records),
        "pairedCells": groups,
        "totalMetadataRequests": sum(
            r["observation"]["metadataRequestsUsed"] for r in records),
        "totalMediaProbes": 0,
        "globalDecision": "NOT_MADE_M3_A2",
    }


def _serialize(value: Mapping[str, Any]) -> bytes:
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode("utf-8")


def _check_privacy(root: pathlib.Path, ids: Mapping[str, str]) -> None:
    for path in root.glob("*.json"):
        payload = path.read_bytes()
        require(not any(raw.encode() in payload for raw in ids.values()),
                "raw case identifier in portable evidence")


def run_d3(*, registry: Mapping[str, Any], c1: Mapping[str, Any], d0: Mapping[str, Any],
           d1_plan: Mapping[str, Any], d2_plan: Mapping[str, Any],
           plan: Mapping[str, Any], ids: Mapping[str, str],
           git_commit: str, runner_image: str, output: pathlib.Path,
           source_file: pathlib.Path | None = None,
           interpreters: Mapping[str, pathlib.Path] | None = None,
           observer: Callable[[Mapping[str, Any]], Mapping[str, Any]] | None = None) -> dict[str, Any]:
    validate_plan(plan, registry, c1, d0, d1_plan, d2_plan)
    validate_inputs(registry, ids)
    require(type(git_commit) is str and re.fullmatch(r"[0-9a-f]{40}", git_commit) is not None,
            "invalid git source")
    require(type(runner_image) is str and re.fullmatch(r"[A-Za-z0-9._@-]{1,100}", runner_image) is not None,
            "invalid runner")
    require(not output.exists() or not any(output.iterdir()), "output directory must be empty")
    if observer is None:
        require(source_file is not None and interpreters is not None
                and set(interpreters) == set(ORACLES), "both isolated environments required")
        require(all(x.is_file() for x in interpreters.values()), "interpreter missing")
        require(interpreters["A"].resolve() != interpreters["B"].resolve(),
                "same interpreter used for both versions")

    records: list[dict[str, Any]] = []
    output.mkdir(parents=True, exist_ok=True)
    for step in plan["execution"]:
        if observer is None:
            assert source_file is not None and interpreters is not None
            filepath = output / _observation_key(step)
            command = [
                str(interpreters[step["arm"]]), str(pathlib.Path(__file__).resolve()),
                "observe-one", "--registry", str(_DEFAULTS["registry"]),
                "--c1-plan", str(_DEFAULTS["c1-plan"]), "--d0-plan", str(_DEFAULTS["d0-plan"]),
                "--d1-plan", str(_DEFAULTS["d1-plan"]), "--d2-plan", str(_DEFAULTS["d2-plan"]),
                "--plan", str(_DEFAULTS["plan"]), "--source-file", str(source_file),
                "--git-commit", git_commit, "--runner-image", runner_image,
                "--ordinal", str(step["ordinal"]), "--output-file", str(filepath),
            ]
            try:
                completed = subprocess.run(command, capture_output=True, check=False,
                                           timeout=180)
            except (OSError, subprocess.TimeoutExpired) as exc:
                raise ValueError("isolated reference process failed closed") from None
            require(completed.returncode == 0, "isolated reference did not complete")
            record = json.loads(filepath.read_text(encoding="utf-8"))
        else:
            record = observation_envelope(step, observer(step))
            filepath = output / _observation_key(step)
            filepath.write_bytes(_serialize(record))
        validate_observation(record, registry, plan, step, git_commit, runner_image)
        require(sum(x["observation"]["metadataRequestsUsed"] for x in records)
                + record["observation"]["metadataRequestsUsed"] <= MAX_TOTAL,
                "aggregate metadata request budget exceeded")
        records.append(record)
    summary = _summary(records, git_commit, runner_image)
    (output / "summary-v1.json").write_bytes(_serialize(summary))
    (output / "manifest-v1.json").write_bytes(_serialize({
        "schemaVersion": 1, "planId": PLAN_ID,
        "files": [
            {"path": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
            for path in sorted(output.glob("*.json"))
        ],
    }))
    _check_privacy(output, ids)
    return summary


def verify_bundle(*, registry: Mapping[str, Any], plan: Mapping[str, Any],
                  ids: Mapping[str, str], root: pathlib.Path,
                  git_commit: str, runner_image: str) -> dict[str, Any]:
    require(type(git_commit) is str and re.fullmatch(r"[0-9a-f]{40}", git_commit) is not None
            and type(runner_image) is str and re.fullmatch(
                r"[A-Za-z0-9._@-]{1,100}", runner_image) is not None,
            "untrusted audit provenance")
    expected = {_observation_key(step) for step in plan["execution"]}
    require({p.name for p in root.iterdir()} == expected | {
        "summary-v1.json", "manifest-v1.json",
    }, "unexpected evidence file set")
    manifest = json.loads((root / "manifest-v1.json").read_text(encoding="utf-8"))
    entries = manifest.get("files")
    require(set(manifest) == {"schemaVersion", "planId", "files"}
            and type(manifest["schemaVersion"]) is int and manifest["schemaVersion"] == 1
            and manifest["planId"] == PLAN_ID and type(entries) is list
            and len(entries) == 9 and all(type(e) is dict
                                         and set(e) == {"path", "sha256"} for e in entries)
            and {e["path"] for e in entries} == expected | {"summary-v1.json"},
            "manifest schema drift")
    for entry in entries:
        require(type(entry["sha256"]) is str and re.fullmatch(
            r"[0-9a-f]{64}", entry["sha256"]) is not None
            and hashlib.sha256((root / entry["path"]).read_bytes()).hexdigest()
            == entry["sha256"], "manifest digest mismatch")
    records = []
    for step in plan["execution"]:
        record = json.loads((root / _observation_key(step)).read_text(encoding="utf-8"))
        validate_observation(record, registry, plan, step, git_commit, runner_image)
        records.append(record)
    require(sum(r["observation"]["metadataRequestsUsed"] for r in records) <= MAX_TOTAL,
            "aggregate request count exceeds ceiling")
    expected_summary = _summary(records, git_commit, runner_image)
    summary = json.loads((root / "summary-v1.json").read_text(encoding="utf-8"))
    require(summary == expected_summary, "aggregate does not match original observations")
    _check_privacy(root, ids)
    return summary


_DEFAULTS = {
    "registry": pathlib.Path(".work/m3-a2/case-registry-v1.json"),
    "c1-plan": pathlib.Path(".work/m3-a2/control-plan-v1.json"),
    "d0-plan": pathlib.Path(".work/m3-a2/c2-d0-plan-v1.json"),
    "d1-plan": pathlib.Path(".work/m3-a2/c2-d1-plan-v1.json"),
    "d2-plan": pathlib.Path(".work/m3-a2/c2-d2-plan-v1.json"),
    "plan": pathlib.Path(".work/m3-a2/c2-d3-plan-v1.json"),
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("validate-plan", "run", "observe-one", "verify"))
    for key in _DEFAULTS:
        parser.add_argument("--" + key, type=pathlib.Path, default=_DEFAULTS[key])
    parser.add_argument("--source-file", type=pathlib.Path)
    parser.add_argument("--python-a", type=pathlib.Path)
    parser.add_argument("--python-b", type=pathlib.Path)
    parser.add_argument("--git-commit")
    parser.add_argument("--runner-image")
    parser.add_argument("--output-dir", type=pathlib.Path)
    parser.add_argument("--output-file", type=pathlib.Path)
    parser.add_argument("--ordinal", type=int)
    args = parser.parse_args()
    try:
        registry = load_registry(args.registry)
        c1, d0, d1_plan, d2_plan, plan = [
            json.loads(getattr(args, name.replace("-", "_")).read_text(encoding="utf-8"))
            for name in ("c1-plan", "d0-plan", "d1-plan", "d2-plan", "plan")
        ]
        validate_plan(plan, registry, c1, d0, d1_plan, d2_plan)
        if args.command == "validate-plan":
            print("D3 pinned A/B reference plan: 4 pairs, 8 observations, metadata only")
            return 0
        require(args.source_file is not None and args.git_commit is not None
                and args.runner_image is not None, "missing source/provenance")
        ids = derive_case_inputs_from_source(registry, args.source_file.read_text(encoding="utf-8"))
        validate_inputs(registry, ids)
        if args.command == "observe-one":
            require(args.ordinal is not None and args.output_file is not None
                    and 1 <= args.ordinal <= len(plan["execution"]),
                    "invalid observation identity")
            require(not args.output_file.exists(), "observation file already exists")
            step = plan["execution"][args.ordinal - 1]
            import yt_dlp
            import yt_dlp.version
            from yt_dlp import utils
            from yt_dlp.networking import exceptions as network_types
            from yt_dlp.networking.common import Response
            oracle = ORACLES[step["arm"]]
            require(yt_dlp.version.__version__ == oracle["version"]
                    and yt_dlp.version.RELEASE_GIT_HEAD == oracle["revision"],
                    "pinned Python environment does not match planned oracle")
            fake_api = types.SimpleNamespace(
                YoutubeDL=yt_dlp.YoutubeDL, networking_exceptions=network_types,
                networking_response_type=Response, utils=utils,
            )
            cell = next(x for x in plan["cells"] if x["caseClass"] == step["caseClass"]
                        and x["lane"] == step["lane"])
            observation = d2.observe_cell(
                registry=cloned_registry(registry, step["arm"]), cell=cell,
                raw_id=ids[step["caseClass"]], git_commit=args.git_commit,
                runner_image=args.runner_image,
                oracle_loader=lambda: (fake_api, oracle["version"], oracle["revision"]),
            )
            envelope = observation_envelope(step, observation)
            validate_observation(envelope, registry, plan, step,
                                 args.git_commit, args.runner_image)
            args.output_file.parent.mkdir(parents=True, exist_ok=True)
            args.output_file.write_bytes(_serialize(envelope))
            return 0
        require(args.output_dir is not None, "missing evidence destination")
        if args.command == "run":
            result = run_d3(
                registry=registry, c1=c1, d0=d0, d1_plan=d1_plan, d2_plan=d2_plan,
                plan=plan, ids=ids, git_commit=args.git_commit, runner_image=args.runner_image,
                output=args.output_dir, source_file=args.source_file,
                interpreters={"A": args.python_a, "B": args.python_b}
                if args.python_a is not None and args.python_b is not None else None,
            )
        else:
            result = verify_bundle(
                registry=registry, plan=plan, ids=ids, root=args.output_dir,
                git_commit=args.git_commit, runner_image=args.runner_image,
            )
        print(f"D3 4 pairs, {result['totalMetadataRequests']} metadata requests, "
              "0 media reads; globalDecision=NOT_MADE_M3_A2")
        return 0
    except Exception:
        # Do not include provider identity, URLs, exception string or credentials.
        print("D3 failed closed (details suppressed)", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
