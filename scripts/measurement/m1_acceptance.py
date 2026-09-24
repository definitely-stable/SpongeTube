#!/usr/bin/env python3
"""Canonical M1-G acceptance aggregator.

Consumes already-produced owning-slice evidence. It does not implement runtime
producers or replace the independent storage/fetch/bridge/recovery verifiers.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import pathlib
import sys
import xml.etree.ElementTree as ET
from typing import Any

from m1_oracle import compare_coverage_semantics
from schema_subset import validate_instance, validate_schema_definition

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
SCHEMAS = REPO_ROOT / ".work" / "schemas"
RUN_SCHEMA = SCHEMAS / "m1-run-manifest-v1.schema.json"
INDEX_SCHEMA = SCHEMAS / "m1-acceptance-index-v1.schema.json"
VERIFICATION = REPO_ROOT / ".work" / "VERIFICATION.md"
FIXTURE_MANIFEST = REPO_ROOT / "test-fixtures" / "media" / "manifest.json"

GATES = tuple(f"M1-ACC-{i:02d}" for i in range(1, 17))
POSITIVE_SEEDS = ("S0", "S10", "S30", "S60", "S120")
NEGATIVE_SEEDS = (
    "S30_VIDEO_HOLE",
    "S30_AUDIO_HOLE",
    "S30_MISSING_INIT",
    "S30_PARTIAL_TAIL",
    "S30_WRONG_REPRESENTATION",
)
ALL_SEEDS = POSITIVE_SEEDS + NEGATIVE_SEEDS

ACC07_TESTS = (
    "cancellingOneJoinedConsumerDoesNotCancelRemainingConsumer",
    "cancellingAwaitingConsumerReleasesOnlyItsLease",
    "lastConsumerCancellationCancelsOwnerAndRemovesRegistryEntry",
    "replacementOwnerWaitsUntilCancellingPhysicalAttemptIsTerminal",
    "cancellingBarrierHandsLateSuccessToReplacementWithoutRefetch",
    "cancellingBarrierPreservesTerminalFailureWithoutResettingBudget",
)

LIMITATIONS = (
    "API 36 emulator evidence validates deterministic M1 correctness and recovery, not representative physical-device performance.",
    "No p95/p99 latency, battery, thermal, transport-winner or production performance claim is made by M1 acceptance.",
)


class AcceptanceError(ValueError):
    pass


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_json(path: pathlib.Path) -> dict[str, Any]:
    if not path.is_file():
        raise AcceptanceError(f"missing JSON evidence: {path}")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise AcceptanceError(f"{path}: expected JSON object")
    return value


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AcceptanceError(message)


def find_unique_file(root: pathlib.Path, suffix: str) -> pathlib.Path:
    normalized = suffix.replace("\\", "/")
    matches = [
        path for path in root.rglob("*")
        if path.is_file() and path.as_posix().endswith(normalized)
    ]
    require(len(matches) == 1, f"expected one file ending {suffix!r} under {root}, found {len(matches)}")
    return matches[0]


def find_unique_dir(root: pathlib.Path, name: str) -> pathlib.Path:
    matches = [path for path in root.rglob(name) if path.is_dir()]
    if root.name == name:
        matches.insert(0, root)
    unique = list(dict.fromkeys(matches))
    require(len(unique) == 1, f"expected one directory {name!r} under {root}, found {len(unique)}")
    return unique[0]


def evidence_key(root: pathlib.Path, path: pathlib.Path) -> str:
    return path.relative_to(root).as_posix()


def require_pass(path: pathlib.Path) -> dict[str, Any]:
    payload = read_json(path)
    require(payload.get("status") == "PASS", f"{path}: status is not PASS")
    return payload


def verify_acc07(host_root: pathlib.Path) -> pathlib.Path:
    xml_files = sorted(host_root.rglob("TEST-*FetchBrokerTest*.xml"))
    require(len(xml_files) == 1, f"expected one FetchBrokerTest XML, found {len(xml_files)}")
    xml_path = xml_files[0]
    root = ET.parse(xml_path).getroot()
    failed = []
    passed_names: set[str] = set()
    for case in root.iter("testcase"):
        name = str(case.attrib.get("name", ""))
        if case.find("failure") is not None or case.find("error") is not None:
            failed.append(name)
        else:
            passed_names.add(name.rstrip("()"))
    require(not failed, f"FetchBrokerTest contains failures: {failed}")
    missing = [
        name for name in ACC07_TESTS
        if not any(actual == name or actual.startswith(name) for actual in passed_names)
    ]
    require(not missing, f"ACC-07 state-machine tests missing/not passed: {missing}")
    return xml_path


def verify_m1c(smoke_root: pathlib.Path) -> tuple[list[pathlib.Path], list[pathlib.Path]]:
    root = find_unique_dir(smoke_root, "m1-c-evidence")
    positive_proofs: list[pathlib.Path] = []
    negative_proofs: list[pathlib.Path] = []
    for seed in ALL_SEEDS:
        runtime_path = root / "device" / seed / "runtime-coverage.json"
        verified = root / "verified" / seed
        oracle_path = verified / "oracle-coverage.json"
        seed_path = verified / "seed-manifest.json"
        committed = verified / "committed-extents.json"
        files = verified / "verified-extent-files.json"
        for path in (runtime_path, oracle_path, seed_path, committed, files):
            require(path.is_file() and path.stat().st_size > 0, f"missing M1-C evidence: {path}")
        errors = compare_coverage_semantics(read_json(runtime_path), read_json(oracle_path))
        require(not errors, f"{seed}: retained runtime/oracle mismatch: {errors}")
        target = positive_proofs if seed in POSITIVE_SEEDS else negative_proofs
        target.extend((runtime_path, oracle_path, seed_path, committed, files))
    return positive_proofs, negative_proofs


def collect(
    *,
    evidence_root: pathlib.Path,
    generated_root: pathlib.Path,
    run_id: str,
    git_commit: str,
) -> dict[str, Any]:
    require(len(git_commit) == 40 and all(c in "0123456789abcdef" for c in git_commit),
            "git commit must be lowercase 40-hex")

    host = evidence_root / "host"
    acc15 = evidence_root / "acc15"
    smoke = evidence_root / "smoke"
    recovery = evidence_root / "recovery"
    compat23 = evidence_root / "compat23"
    compat34 = evidence_root / "compat34"

    storage_summary_path = find_unique_file(smoke, "m1-b-evidence/verification-summary.json")
    storage = require_pass(storage_summary_path)
    require(storage.get("gateCounts") == {
        "M1-ACC-01": 1, "M1-ACC-02": 6, "M1-ACC-03": 2
    }, "M1-B canonical gate counts mismatch")

    positive_m1c, negative_m1c = verify_m1c(smoke)

    d_summary_path = find_unique_file(smoke, "m1-d-evidence/verified/verification-summary.json")
    d_summary = require_pass(d_summary_path)

    e_summary_path = find_unique_file(smoke, "m1-e-evidence/verified/verification-summary.json")
    e_summary = require_pass(e_summary_path)
    cases = e_summary.get("cases")
    require(isinstance(cases, dict) and set(cases) == {"E1","E2","E3","E4","E5","E6"},
            "M1-E canonical case set mismatch")

    acc07_xml = verify_acc07(host)

    recovery_map = {
        "M1-ACC-11": ("m1-f-process-death", "PROCESS_DEATH"),
        "M1-ACC-12": ("m1-f-short", "N4R-SHORT"),
        "M1-ACC-13": ("m1-f-exhaust", "N4R-EXHAUST"),
        "M1-ACC-14": ("m1-f-restore", "N4R-RESTORE"),
    }
    recovery_proofs: dict[str, pathlib.Path] = {}
    for gate, (case_dir, scenario) in recovery_map.items():
        path = find_unique_file(
            recovery,
            f"cases/{case_dir}/verified/recovery-summary-v2.json",
        )
        payload = require_pass(path)
        require(payload.get("scenarioId") == scenario, f"{gate}: recovery scenario mismatch")
        require(payload.get("coverage", {}).get("exactMatch") is True,
                f"{gate}: independent coverage mismatch")
        recovery_proofs[gate] = path

    acc15_summary_path = find_unique_file(acc15, "verification-summary.json")
    acc15_summary = require_pass(acc15_summary_path)
    require(acc15_summary.get("gateId") == "M1-ACC-15" and acc15_summary.get("caseCount") == 4,
            "ACC-15 summary contract mismatch")

    for api, root in ((23, compat23), (34, compat34)):
        path = find_unique_file(root, f"api-{api}/device/device-api.txt")
        require(path.is_file(), f"missing API {api} compatibility identity")
        require(path.read_text(encoding="utf-8").strip() == str(api),
                f"API {api} compatibility artifact identity mismatch")

    # Bind the accepted seed matrix and normative scenario into m1-run-manifest-v1.
    generated_root.mkdir(parents=True, exist_ok=True)
    seed_entries = []
    c_root = find_unique_dir(smoke, "m1-c-evidence") / "verified"
    for seed in ALL_SEEDS:
        path = c_root / seed / "seed-manifest.json"
        seed_entries.append({"seedId": seed, "sha256": sha256(path)})
    seed_matrix = {"schemaVersion": 1, "seeds": seed_entries}
    seed_matrix_path = generated_root / "seed-matrix-v1.json"
    seed_matrix_path.write_text(json.dumps(seed_matrix, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    scenario = {
        "schemaVersion": 1,
        "scenarioId": "M1-CANONICAL-16-MUST",
        "gateIds": list(GATES),
        "verificationContractSha256": sha256(VERIFICATION),
    }
    scenario_path = generated_root / "scenario-v1.json"
    scenario_path.write_text(json.dumps(scenario, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    manifest = {
        "schemaVersion": 1,
        "runId": run_id,
        "sessionId": f"{run_id}-aggregate",
        "createdAtUtc": dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z"),
        "gitCommit": git_commit,
        "build": {"workflow": "M1 Acceptance", "buildType": "benchmark"},
        "fixture": {"id": "F1", "sha256": sha256(FIXTURE_MANIFEST)},
        "scenario": {"id": scenario["scenarioId"], "hash": sha256(scenario_path)},
        "seed": {"seedId": "CANONICAL-MATRIX", "manifestSha256": sha256(seed_matrix_path)},
        "playbackMode": "SPONGE",
        "device": {"canonicalApi": 36, "compatibilityApis": [23, 34]},
        "runtime": {
            "media3": "1.11.1",
            "verificationContractSha256": scenario["verificationContractSha256"],
        },
    }
    run_schema = read_json(RUN_SCHEMA)
    validate_schema_definition(run_schema)
    validate_instance(run_schema, manifest, root_schema=run_schema)
    manifest_path = generated_root / "m1-run-manifest-v1.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    def key(path: pathlib.Path) -> str:
        if path.is_relative_to(evidence_root):
            return evidence_key(evidence_root, path)
        return "generated/" + path.relative_to(generated_root).as_posix()

    gate_proofs: dict[str, list[pathlib.Path]] = {
        "M1-ACC-01": [storage_summary_path],
        "M1-ACC-02": [storage_summary_path],
        "M1-ACC-03": [storage_summary_path],
        "M1-ACC-04": positive_m1c,
        "M1-ACC-05": negative_m1c,
        "M1-ACC-06": [d_summary_path],
        "M1-ACC-07": [acc07_xml],
        "M1-ACC-08": [e_summary_path],
        "M1-ACC-09": [e_summary_path],
        "M1-ACC-10": [e_summary_path],
        "M1-ACC-11": [recovery_proofs["M1-ACC-11"]],
        "M1-ACC-12": [recovery_proofs["M1-ACC-12"]],
        "M1-ACC-13": [recovery_proofs["M1-ACC-13"]],
        "M1-ACC-14": [recovery_proofs["M1-ACC-14"]],
        "M1-ACC-15": [acc15_summary_path],
        "M1-ACC-16": positive_m1c + negative_m1c,
    }

    selected_roots = (
        find_unique_dir(smoke, "m1-b-evidence"),
        find_unique_dir(smoke, "m1-c-evidence"),
        find_unique_dir(smoke, "m1-d-evidence"),
        find_unique_dir(smoke, "m1-e-evidence"),
        acc15,
        find_unique_dir(recovery, "cases"),
        find_unique_dir(host, "test-results"),
        find_unique_dir(compat23, "api-23"),
        find_unique_dir(compat34, "api-34"),
        generated_root,
    )
    artifact_paths: dict[str, pathlib.Path] = {}
    for root in selected_roots:
        require(root.exists(), f"missing canonical evidence root: {root}")
        for path in root.rglob("*"):
            if path.is_file():
                artifact_paths[key(path)] = path

    gates = [
        {"gateId": gate, "status": "PASS", "proofs": [key(p) for p in gate_proofs[gate]]}
        for gate in GATES
    ]
    index = {
        "schemaVersion": 1,
        "runId": run_id,
        "gitCommit": git_commit,
        "status": "PASS",
        "gateCount": 16,
        "manifest": key(manifest_path),
        "gates": gates,
        "artifacts": [
            {"path": name, "sha256": sha256(path), "sizeBytes": path.stat().st_size}
            for name, path in sorted(artifact_paths.items())
        ],
        "compatibility": {"api23": "PASS", "api34": "PASS"},
        "limitations": list(LIMITATIONS),
    }
    verify_index(index, evidence_root=evidence_root, generated_root=generated_root,
                 expected_git_commit=git_commit)
    index_schema = read_json(INDEX_SCHEMA)
    validate_schema_definition(index_schema)
    validate_instance(index_schema, index, root_schema=index_schema)
    return index


def resolve_index_path(path: str, evidence_root: pathlib.Path, generated_root: pathlib.Path) -> pathlib.Path:
    if path.startswith("generated/"):
        return generated_root / path.removeprefix("generated/")
    return evidence_root / path


def verify_index(
    index: dict[str, Any],
    *,
    evidence_root: pathlib.Path,
    generated_root: pathlib.Path,
    expected_git_commit: str,
) -> None:
    require(index.get("status") == "PASS", "acceptance status is not PASS")
    require(index.get("gitCommit") == expected_git_commit, "acceptance git commit mismatch")
    gates = index.get("gates")
    require(isinstance(gates, list), "gates must be a list")
    ids = [row.get("gateId") for row in gates if isinstance(row, dict)]
    require(len(ids) == 16 and len(set(ids)) == 16 and set(ids) == set(GATES),
            "canonical acceptance must contain exactly unique 16/16 gates")
    require(all(row.get("status") == "PASS" for row in gates), "non-PASS MUST gate")

    index_schema = read_json(INDEX_SCHEMA)
    validate_schema_definition(index_schema)
    validate_instance(index_schema, index, root_schema=index_schema)

    artifacts = index.get("artifacts")
    require(isinstance(artifacts, list) and artifacts, "artifact index is empty")
    by_path: dict[str, dict[str, Any]] = {}
    for row in artifacts:
        path = str(row.get("path"))
        require(path not in by_path, f"duplicate artifact path {path}")
        actual = resolve_index_path(path, evidence_root, generated_root)
        require(actual.is_file(), f"indexed artifact missing: {path}")
        require(sha256(actual) == row.get("sha256"), f"artifact digest mismatch: {path}")
        require(actual.stat().st_size == row.get("sizeBytes"), f"artifact size mismatch: {path}")
        by_path[path] = row

    manifest_path = str(index.get("manifest"))
    require(manifest_path in by_path, "manifest is not indexed")
    manifest = read_json(resolve_index_path(manifest_path, evidence_root, generated_root))
    run_schema = read_json(RUN_SCHEMA)
    validate_schema_definition(run_schema)
    validate_instance(run_schema, manifest, root_schema=run_schema)
    require(manifest.get("gitCommit") == expected_git_commit, "manifest git commit mismatch")
    require(manifest.get("runId") == index.get("runId"), "manifest/index runId mismatch")
    require(manifest.get("sessionId") == f"{index.get('runId')}-aggregate",
            "manifest sessionId mismatch")
    require(manifest.get("fixture", {}).get("sha256") == sha256(FIXTURE_MANIFEST),
            "manifest fixture hash mismatch")

    for row in gates:
        proofs = row.get("proofs")
        require(isinstance(proofs, list) and proofs, f"{row.get('gateId')}: no proofs")
        for proof in proofs:
            require(proof in by_path, f"{row.get('gateId')}: unindexed proof {proof}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)

    collect_parser = sub.add_parser("collect")
    collect_parser.add_argument("--evidence-root", required=True, type=pathlib.Path)
    collect_parser.add_argument("--generated-root", required=True, type=pathlib.Path)
    collect_parser.add_argument("--run-id", required=True)
    collect_parser.add_argument("--git-commit", required=True)
    collect_parser.add_argument("--output", required=True, type=pathlib.Path)

    verify_parser = sub.add_parser("verify-index")
    verify_parser.add_argument("--index", required=True, type=pathlib.Path)
    verify_parser.add_argument("--evidence-root", required=True, type=pathlib.Path)
    verify_parser.add_argument("--generated-root", required=True, type=pathlib.Path)
    verify_parser.add_argument("--expected-git-commit", required=True)

    args = parser.parse_args(argv)
    try:
        if args.command == "collect":
            index = collect(
                evidence_root=args.evidence_root,
                generated_root=args.generated_root,
                run_id=args.run_id,
                git_commit=args.git_commit,
            )
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(index, indent=2, sort_keys=True) + "\n", encoding="utf-8")
            print("M1 canonical acceptance PASS: 16/16 MUST gates")
        else:
            index = read_json(args.index)
            verify_index(
                index,
                evidence_root=args.evidence_root,
                generated_root=args.generated_root,
                expected_git_commit=args.expected_git_commit,
            )
            print("M1 acceptance index verified")
        return 0
    except (AcceptanceError, OSError, ValueError, json.JSONDecodeError, ET.ParseError) as error:
        print(f"M1 ACCEPTANCE FAILURE: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
