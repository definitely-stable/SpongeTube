#!/usr/bin/env python3
"""Canonical M2-H acceptance aggregator.

Consumes fresh same-source owning-slice evidence. It does not implement missing
runtime behavior and it never upgrades emulator-only transport evidence into a
production performance selection.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import shutil
import sys
from typing import Any, Callable, Mapping

SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[1]
SCHEMAS = REPO_ROOT / ".work" / "schemas"
M2_MILESTONE = REPO_ROOT / ".work" / "milestones" / "M2.md"
VERIFICATION = REPO_ROOT / ".work" / "VERIFICATION.md"
INDEX_SCHEMA = SCHEMAS / "m2-acceptance-index-v1.schema.json"
sys.path.insert(0, str(SCRIPT_DIR))

from m2_contracts import M2ContractError, scan_evidence_privacy  # noqa: E402
from schema_subset import (  # noqa: E402
    SchemaContractError,
    validate_instance,
    validate_schema_definition,
)

GATES = tuple(f"M2-ACC-{i:02d}" for i in range(1, 11))
LIMITATIONS = (
    "M2 canonical acceptance proves deterministic correctness, recovery, route/privacy and fault-attribution behavior; it does not establish representative physical-device performance.",
    "Transport evidence remains API36 emulator directional evidence; no production backend is selected by M2.",
    "Canonical N5 remains INCONCLUSIVE_STOCHASTIC_EFFECT and is not upgraded into a positive resilience-effect claim.",
    "Live YouTube/provider behavior is not an M2 acceptance oracle; provider compatibility remains a later product-risk track.",
)

EXPECTED_PROVIDER_VARIANTS = {
    "HTTP_403_BARE": "N8",
    "HTTP_429_RETRY_AFTER_DELAY_SECONDS": "N9",
    "HTTP_429_RETRY_AFTER_HTTP_DATE": "N9",
    "BINDING_EXPIRY_REFRESH": "N10",
}
EXPECTED_F2_CASES = {"F2_DEFAULT_ROUTE_LOSS_RESTORE"}
EXPECTED_F3_CASES = {
    "F3_VPN_CONTINUITY_RESTORE",
    "F3_VPN_CONTINUITY_DIRECT_OVERRIDE",
}


class M2AcceptanceError(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise M2AcceptanceError(message)


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_json(path: pathlib.Path) -> dict[str, Any]:
    require(path.is_file(), f"missing JSON evidence: {path}")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise M2AcceptanceError(f"{path}: invalid JSON: {error}") from error
    require(isinstance(value, dict), f"{path}: expected JSON object")
    return value


def validate_schema(name: str, document: Mapping[str, Any]) -> None:
    schema = read_json(SCHEMAS / name)
    try:
        validate_schema_definition(schema)
        validate_instance(schema, document, root_schema=schema)
    except SchemaContractError as error:
        raise M2AcceptanceError(f"{name}: {error}") from error


def privacy(document: Any, label: str) -> None:
    try:
        scan_evidence_privacy(document)
    except M2ContractError as error:
        raise M2AcceptanceError(f"{label}: privacy failure: {error}") from error


def find_files(root: pathlib.Path, name: str) -> list[pathlib.Path]:
    require(root.is_dir(), f"missing evidence root: {root}")
    return sorted(path for path in root.rglob(name) if path.is_file())


def find_unique_file(
    root: pathlib.Path,
    name: str,
    predicate: Callable[[pathlib.Path], bool] | None = None,
) -> pathlib.Path:
    matches = find_files(root, name)
    if predicate is not None:
        matches = [path for path in matches if predicate(path)]
    require(
        len(matches) == 1,
        f"expected exactly one {name!r} under {root}, found {len(matches)}",
    )
    return matches[0]


def require_source_marker(root: pathlib.Path, expected_git_commit: str) -> pathlib.Path:
    path = find_unique_file(root, "source-sha.txt")
    actual = path.read_text(encoding="utf-8").strip()
    require(actual == expected_git_commit, f"{root}: source SHA {actual!r} != {expected_git_commit}")
    return path


def load_summary(path: pathlib.Path, schema_name: str) -> dict[str, Any]:
    document = read_json(path)
    validate_schema(schema_name, document)
    privacy(document, str(path))
    require(document.get("status") == "PASS", f"{path}: status is not PASS")
    return document


def copy_retained(
    source: pathlib.Path,
    retained_root: pathlib.Path,
    relative: str,
) -> pathlib.Path:
    target = retained_root / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)
    require(sha256(source) == sha256(target), f"copy digest mismatch: {relative}")
    require(source.stat().st_size == target.stat().st_size, f"copy size mismatch: {relative}")
    return target


def collect(
    *,
    smoke_root: pathlib.Path,
    transport_root: pathlib.Path,
    network_root: pathlib.Path,
    f2_root: pathlib.Path,
    f3_root: pathlib.Path,
    g3_root: pathlib.Path,
    retained_root: pathlib.Path,
    run_id: str,
    git_commit: str,
) -> dict[str, Any]:
    require(
        len(git_commit) == 40 and all(c in "0123456789abcdef" for c in git_commit),
        "git commit must be lowercase 40-hex",
    )
    require(run_id, "run id required")
    retained_root.mkdir(parents=True, exist_ok=True)

    source_markers = {
        "smoke": require_source_marker(smoke_root, git_commit),
        "m2-e-transport": require_source_marker(transport_root, git_commit),
        "m2-e-network": require_source_marker(network_root, git_commit),
        "m2-f2": require_source_marker(f2_root, git_commit),
        "m2-f3": require_source_marker(f3_root, git_commit),
    }

    # M2-B: component route observation proof on the canonical API36 smoke run.
    b_path = find_unique_file(smoke_root, "route-verification-summary.json")
    b = load_summary(b_path, "route-verification-summary-v1.schema.json")
    require(b["androidApi"] == 36, "M2-B canonical route proof must be API36")

    # M2-C: Android + Media Lab proof of failure separation and bounded lineage.
    c_path = find_unique_file(smoke_root, "recovery-verification-summary.json")
    c = load_summary(c_path, "recovery-verification-summary-v1.schema.json")
    for gate in ("M2-ACC-05", "M2-ACC-06"):
        require(c["gates"][gate]["status"] == "PASS", f"M2-C did not PASS {gate}")

    # M2-D: all four canonical provider scenarios must remain independently verified.
    d_paths = find_files(smoke_root, "provider-verification-summary.json")
    require(len(d_paths) == 4, f"expected four M2-D provider summaries, found {len(d_paths)}")
    d_by_variant: dict[str, tuple[pathlib.Path, dict[str, Any]]] = {}
    for path in d_paths:
        document = load_summary(path, "provider-verification-summary-v1.schema.json")
        require(document["evidenceSource"] == "ANDROID_MEDIA_LAB", f"{path}: not Android Media Lab evidence")
        variant = document["variant"]
        require(variant in EXPECTED_PROVIDER_VARIANTS, f"{path}: unexpected provider variant {variant!r}")
        require(variant not in d_by_variant, f"duplicate provider variant {variant}")
        require(
            document["scenarioFamily"] == EXPECTED_PROVIDER_VARIANTS[variant],
            f"{path}: provider family/variant mismatch",
        )
        require(document["gates"]["M2-ACC-08"]["status"] == "PASS", f"{path}: M2-ACC-08 not PASS")
        d_by_variant[variant] = (path, document)
    require(set(d_by_variant) == set(EXPECTED_PROVIDER_VARIANTS), "canonical M2-D scenario set drift")
    n10 = d_by_variant["BINDING_EXPIRY_REFRESH"][1]
    for gate in ("M2-ACC-05", "M2-ACC-06", "M2-ACC-07", "M2-ACC-08"):
        require(n10["gates"][gate]["status"] == "PASS", f"N10 did not PASS {gate}")

    # M2-E: all canonical transport and network cases must prove 01/02/09.
    e_docs: list[tuple[str, pathlib.Path, dict[str, Any]]] = []
    for owner, root, plane, expected_count in (
        ("transport", transport_root, "TRANSPORT", 4),
        ("network", network_root, "NETWORK", 3),
    ):
        paths = find_files(root, "fault-verification-summary.json")
        require(len(paths) == expected_count, f"M2-E {owner}: expected {expected_count} summaries, found {len(paths)}")
        for path in paths:
            document = load_summary(path, "fault-verification-summary-v1.schema.json")
            require(document["primaryPlane"] == plane, f"{path}: primary plane drift")
            for gate in ("M2-ACC-01", "M2-ACC-02", "M2-ACC-09"):
                require(document["gates"][gate] is True, f"{path}: {gate} not true")
            require(all(document["checks"].values()), f"{path}: fault fidelity check failed")
            e_docs.append((owner, path, document))
    require(len(e_docs) == 7, "canonical M2-E proof set must contain seven cases")

    # M2-F: exact-route recovery and both VPN-continuity paths.
    f2_paths = find_files(f2_root, "f4-verification-summary.json")
    require(len(f2_paths) == 1, f"expected one F2 summary, found {len(f2_paths)}")
    f2 = load_summary(f2_paths[0], "m2-f-verification-summary-v1.schema.json")
    require({f2["caseKind"]} == EXPECTED_F2_CASES, "F2 case identity drift")

    f3_paths = find_files(f3_root, "f4-verification-summary.json")
    require(len(f3_paths) == 2, f"expected two F3 summaries, found {len(f3_paths)}")
    f3_docs: dict[str, tuple[pathlib.Path, dict[str, Any]]] = {}
    for path in f3_paths:
        document = load_summary(path, "m2-f-verification-summary-v1.schema.json")
        case_kind = document["caseKind"]
        require(case_kind not in f3_docs, f"duplicate F3 case {case_kind}")
        f3_docs[case_kind] = (path, document)
    require(set(f3_docs) == EXPECTED_F3_CASES, "F3 canonical case set drift")
    for document in [f2, *(row[1] for row in f3_docs.values())]:
        for gate in ("M2-ACC-04", "M2-ACC-05", "M2-ACC-06"):
            require(document["gates"][gate] == "PASS", f"{document['caseKind']}: {gate} not PASS")
    for case_kind, (_, document) in f3_docs.items():
        require(document["gates"]["M2-ACC-03"] == "PASS", f"{case_kind}: route privacy not PASS")

    # M2-G: retained G3 decision must bind the exact fresh G2 aggregate.
    g3_path = find_unique_file(g3_root, "m2-g3-transport-decision-v1.json")
    g2_path = find_unique_file(g3_root, "m2-g2-evidence-index-v1.json")
    g3 = load_summary(g3_path, "m2-g3-transport-decision-v1.schema.json")
    g2 = load_summary(g2_path, "m2-g2-evidence-index-v1.schema.json")
    require(g3["source"]["gitCommit"] == git_commit, "G3 source git commit mismatch")
    require(g3["source"]["checkoutCommit"] == git_commit, "G3 checkout commit mismatch")
    require(g2["gitCommit"] == git_commit and g2["checkoutCommit"] == git_commit, "G2 source revision mismatch")
    require(g3["sourceArtifact"]["sha256"] == sha256(g2_path), "G3 -> G2 SHA-256 binding mismatch")
    require(g3["sourceArtifact"]["sizeBytes"] == g2_path.stat().st_size, "G3 -> G2 size binding mismatch")
    require(g3["decision"]["state"] == "TECHNICALLY_ELIGIBLE_NO_SELECTION", "M2-G decision state drift")
    require(g3["decision"]["selectedBackend"] is None, "M2-G unexpectedly selected a backend")
    require(g3["deviceEvidence"]["physicalDeviceEvidence"] is False, "M2-G physical-device boundary drift")
    require(g3["deviceEvidence"]["performanceSelectionAllowed"] is False, "M2-G performance selection unexpectedly allowed")
    require(g3["n5ResilienceEffect"] == "INCONCLUSIVE_STOCHASTIC_EFFECT", "M2-G N5 claim upgraded")
    require(g2["aggregate"]["selectedBackend"] is None, "G2 selected a backend")
    require(g2["aggregate"]["performanceSelectionAllowed"] is False, "G2 performance selection unexpectedly allowed")
    require(g2["aggregate"]["n5ResilienceEffect"] == "INCONCLUSIVE_STOCHASTIC_EFFECT", "G2 N5 claim upgraded")

    retained: dict[str, pathlib.Path] = {}
    def keep(source: pathlib.Path, relative: str) -> str:
        target = copy_retained(source, retained_root, relative)
        retained[relative] = target
        return relative

    for owner, path in source_markers.items():
        keep(path, f"source/{owner}-source-sha.txt")

    b_key = keep(b_path, "m2-b/route-verification-summary.json")
    c_key = keep(c_path, "m2-c/recovery-verification-summary.json")

    d_keys: dict[str, str] = {}
    for variant, (path, _) in sorted(d_by_variant.items()):
        d_keys[variant] = keep(path, f"m2-d/{variant}/provider-verification-summary.json")

    e_keys: list[str] = []
    for owner, path, document in sorted(e_docs, key=lambda row: (row[0], row[2]["scenarioHash"])):
        token = path.parent.parent.name if path.parent.name == "verified" else path.parent.name
        e_keys.append(keep(path, f"m2-e/{owner}/{token}/fault-verification-summary.json"))

    f2_key = keep(f2_paths[0], "m2-f/F2_DEFAULT_ROUTE_LOSS_RESTORE/f4-verification-summary.json")
    f3_keys = {
        case_kind: keep(path, f"m2-f/{case_kind}/f4-verification-summary.json")
        for case_kind, (path, _) in sorted(f3_docs.items())
    }
    g2_key = keep(g2_path, "m2-g/m2-g2-evidence-index-v1.json")
    g3_key = keep(g3_path, "m2-g/m2-g3-transport-decision-v1.json")

    gates = [
        {"gateId": "M2-ACC-01", "status": "PASS", "owners": ["M2-E"], "proofs": list(e_keys)},
        {"gateId": "M2-ACC-02", "status": "PASS", "owners": ["M2-E"], "proofs": list(e_keys)},
        {
            "gateId": "M2-ACC-03",
            "status": "PASS",
            "owners": ["M2-B", "M2-F"],
            "proofs": [b_key, *f3_keys.values()],
        },
        {
            "gateId": "M2-ACC-04",
            "status": "PASS",
            "owners": ["M2-F"],
            "proofs": [f2_key, *f3_keys.values()],
        },
        {
            "gateId": "M2-ACC-05",
            "status": "PASS",
            "owners": ["M2-C", "M2-D", "M2-F"],
            "proofs": [c_key, d_keys["BINDING_EXPIRY_REFRESH"], f2_key, *f3_keys.values()],
        },
        {
            "gateId": "M2-ACC-06",
            "status": "PASS",
            "owners": ["M2-C", "M2-D", "M2-F"],
            "proofs": [c_key, d_keys["BINDING_EXPIRY_REFRESH"], f2_key, *f3_keys.values()],
        },
        {
            "gateId": "M2-ACC-07",
            "status": "PASS",
            "owners": ["M2-D"],
            "proofs": [d_keys["BINDING_EXPIRY_REFRESH"]],
        },
        {
            "gateId": "M2-ACC-08",
            "status": "PASS",
            "owners": ["M2-D"],
            "proofs": [d_keys[variant] for variant in sorted(d_keys)],
        },
        {"gateId": "M2-ACC-09", "status": "PASS", "owners": ["M2-E"], "proofs": list(e_keys)},
        {
            "gateId": "M2-ACC-10",
            "status": "PASS",
            "owners": ["M2-G"],
            "proofs": [g2_key, g3_key],
        },
    ]

    index = {
        "schemaVersion": 1,
        "runId": run_id,
        "gitCommit": git_commit,
        "status": "PASS",
        "gateCount": 10,
        "contracts": {
            "m2MilestoneSha256": sha256(M2_MILESTONE),
            "verificationSha256": sha256(VERIFICATION),
        },
        "gates": gates,
        "artifacts": [
            {
                "path": name,
                "sha256": sha256(path),
                "sizeBytes": path.stat().st_size,
            }
            for name, path in sorted(retained.items())
        ],
        "transportDecision": {
            "state": g3["decision"]["state"],
            "selectedBackend": g3["decision"]["selectedBackend"],
            "physicalDeviceEvidence": g3["deviceEvidence"]["physicalDeviceEvidence"],
            "performanceSelectionAllowed": g3["deviceEvidence"]["performanceSelectionAllowed"],
            "n5ResilienceEffect": g3["n5ResilienceEffect"],
        },
        "limitations": list(LIMITATIONS),
    }
    verify_index(index, retained_root=retained_root, expected_git_commit=git_commit)
    return index


def verify_index(
    index: Mapping[str, Any],
    *,
    retained_root: pathlib.Path,
    expected_git_commit: str,
) -> None:
    validate_schema("m2-acceptance-index-v1.schema.json", index)
    privacy(index, "M2 acceptance index")
    require(index["status"] == "PASS", "M2 acceptance status is not PASS")
    require(index["gitCommit"] == expected_git_commit, "M2 acceptance git commit mismatch")
    require(index["gateCount"] == 10, "M2 acceptance gate count mismatch")
    require(
        index["contracts"]["m2MilestoneSha256"] == sha256(M2_MILESTONE),
        "M2 milestone contract hash mismatch",
    )
    require(
        index["contracts"]["verificationSha256"] == sha256(VERIFICATION),
        "M2 verification contract hash mismatch",
    )

    gates = index["gates"]
    ids = [row["gateId"] for row in gates]
    require(
        len(ids) == 10 and len(set(ids)) == 10 and set(ids) == set(GATES),
        "canonical M2 acceptance must contain exactly unique 10/10 gates",
    )
    require(all(row["status"] == "PASS" for row in gates), "non-PASS M2 acceptance gate")
    for row in gates:
        require(len(row["owners"]) == len(set(row["owners"])), f"{row['gateId']}: duplicate owner")
        require(len(row["proofs"]) == len(set(row["proofs"])), f"{row['gateId']}: duplicate proof")

    artifacts = index["artifacts"]
    by_path: dict[str, Mapping[str, Any]] = {}
    for row in artifacts:
        path = row["path"]
        require(path not in by_path, f"duplicate artifact path {path}")
        actual = retained_root / path
        require(actual.is_file(), f"indexed artifact missing: {path}")
        require(sha256(actual) == row["sha256"], f"artifact digest mismatch: {path}")
        require(actual.stat().st_size == row["sizeBytes"], f"artifact size mismatch: {path}")
        by_path[path] = row
    for gate in gates:
        for proof in gate["proofs"]:
            require(proof in by_path, f"{gate['gateId']}: unindexed proof {proof}")

    decision = index["transportDecision"]
    require(decision["state"] == "TECHNICALLY_ELIGIBLE_NO_SELECTION", "transport decision state drift")
    require(decision["selectedBackend"] is None, "canonical M2 acceptance selected a backend")
    require(decision["physicalDeviceEvidence"] is False, "physical-device evidence boundary drift")
    require(decision["performanceSelectionAllowed"] is False, "performance selection boundary drift")
    require(decision["n5ResilienceEffect"] == "INCONCLUSIVE_STOCHASTIC_EFFECT", "N5 claim boundary drift")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)

    collect_parser = commands.add_parser("collect")
    collect_parser.add_argument("--smoke-root", required=True, type=pathlib.Path)
    collect_parser.add_argument("--transport-root", required=True, type=pathlib.Path)
    collect_parser.add_argument("--network-root", required=True, type=pathlib.Path)
    collect_parser.add_argument("--f2-root", required=True, type=pathlib.Path)
    collect_parser.add_argument("--f3-root", required=True, type=pathlib.Path)
    collect_parser.add_argument("--g3-root", required=True, type=pathlib.Path)
    collect_parser.add_argument("--retained-root", required=True, type=pathlib.Path)
    collect_parser.add_argument("--run-id", required=True)
    collect_parser.add_argument("--git-commit", required=True)
    collect_parser.add_argument("--output", required=True, type=pathlib.Path)

    verify_parser = commands.add_parser("verify-index")
    verify_parser.add_argument("--index", required=True, type=pathlib.Path)
    verify_parser.add_argument("--retained-root", required=True, type=pathlib.Path)
    verify_parser.add_argument("--expected-git-commit", required=True)

    args = parser.parse_args(argv)
    try:
        if args.command == "collect":
            document = collect(
                smoke_root=args.smoke_root,
                transport_root=args.transport_root,
                network_root=args.network_root,
                f2_root=args.f2_root,
                f3_root=args.f3_root,
                g3_root=args.g3_root,
                retained_root=args.retained_root,
                run_id=args.run_id,
                git_commit=args.git_commit,
            )
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(
                json.dumps(document, indent=2, sort_keys=True) + "\n",
                encoding="utf-8",
            )
            print("M2 canonical acceptance PASS: 10/10 normative gates")
        else:
            document = read_json(args.index)
            verify_index(
                document,
                retained_root=args.retained_root,
                expected_git_commit=args.expected_git_commit,
            )
            print("M2 acceptance index verified")
        return 0
    except (
        M2AcceptanceError,
        M2ContractError,
        SchemaContractError,
        OSError,
        KeyError,
        TypeError,
        ValueError,
        json.JSONDecodeError,
    ) as error:
        print(f"M2 ACCEPTANCE FAILURE: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
