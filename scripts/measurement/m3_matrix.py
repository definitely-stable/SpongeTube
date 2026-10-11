"""Deterministic planning and aggregation for SpongeTube M3-A2 evidence.

M3-A2 consumes only validated m3-a-probe-v1 artifacts. It does not contact
YouTube, choose a production adapter, or convert live-provider observations into
release gates.

Normative source: .work/milestones/M3.md
Case registry: .work/m3-a2/case-registry-v1.json
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys
from collections import Counter
from typing import Any, Iterable, Mapping, Sequence

from m3_contracts import M3ContractError, validate_probe_document


class M3MatrixError(ValueError):
    pass


REGISTRY_VERSION = 1
EXPECTED_CASE_CLASSES = ("V0", "V1", "V2")
EXPECTED_PRIMARY_LANES = (
    "WEB_EMBEDDED",
    "TV",
    "ANDROID_VR",
    "MWEB",
    "WEB",
    "WEB_SAFARI",
)
EXPECTED_DEFERRED_LANES = ("ANDROID", "IOS")
EXPECTED_LANE_PROFILES = {
    "WEB_EMBEDDED": "yt-dlp:web_embedded",
    "TV": "yt-dlp:tv",
    "ANDROID_VR": "yt-dlp:android_vr",
    "MWEB": "yt-dlp:mweb",
    "WEB": "yt-dlp:web",
    "WEB_SAFARI": "yt-dlp:web_safari",
    "ANDROID": "yt-dlp:android",
    "IOS": "yt-dlp:ios",
}
EXPECTED_PRODUCER_ID = "spongetube.m3-bounded-probe"
EXPECTED_PRODUCER_VERSION = "1"
EXPECTED_ORACLE_ID = "YT_DLP"
EXPECTED_ORACLE_VERSION = "2026.08.19"
EXPECTED_ORACLE_REVISION = "594bd50c2c78ac432f81600d309fdc4e0a92d82c"


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise M3MatrixError(message)


def _load_json(path: pathlib.Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise M3MatrixError(f"{path}: cannot load JSON: {exc}") from exc


def _sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _sha256_json(value: Any) -> str:
    encoded = json.dumps(
        value,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _case_map(registry: Mapping[str, Any]) -> dict[str, Mapping[str, Any]]:
    return {str(case["caseClass"]): case for case in registry["cases"]}


def validate_registry(registry: Mapping[str, Any]) -> None:
    _require(isinstance(registry, Mapping), "registry must be an object")
    _require(registry.get("registryVersion") == REGISTRY_VERSION, "unsupported registry version")
    registry_id = registry.get("registryId")
    _require(isinstance(registry_id, str) and registry_id, "registryId is required")

    frozen = registry.get("frozenAtGitCommit")
    _require(
        isinstance(frozen, str)
        and len(frozen) == 40
        and all(ch in "0123456789abcdef" for ch in frozen),
        "frozenAtGitCommit must be a lowercase 40-character SHA",
    )

    producer = registry.get("producer")
    _require(isinstance(producer, Mapping), "producer must be an object")
    _require(producer.get("toolId") == EXPECTED_PRODUCER_ID, "unexpected producer.toolId")
    _require(producer.get("version") == EXPECTED_PRODUCER_VERSION, "unexpected producer.version")
    _require(producer.get("requireSingleGitCommit") is True, "matrix must require one git commit")

    oracle = registry.get("referenceOracle")
    _require(isinstance(oracle, Mapping), "referenceOracle must be an object")
    _require(oracle.get("id") == EXPECTED_ORACLE_ID, "unexpected reference oracle")
    _require(oracle.get("version") == EXPECTED_ORACLE_VERSION, "unexpected oracle version")
    _require(oracle.get("revision") == EXPECTED_ORACLE_REVISION, "unexpected oracle revision")

    _require(
        tuple(registry.get("primaryLanes") or ()) == EXPECTED_PRIMARY_LANES,
        "primaryLanes must match frozen M3-A2 order",
    )
    _require(
        tuple(registry.get("deferredLanes") or ()) == EXPECTED_DEFERRED_LANES,
        "deferredLanes must match frozen M3-A2 order",
    )
    _require(
        registry.get("laneProfiles") == EXPECTED_LANE_PROFILES,
        "laneProfiles must match frozen M3-A1 client mapping",
    )

    cases = registry.get("cases")
    _require(isinstance(cases, list) and len(cases) == 3, "registry requires exactly three cases")
    _require(
        tuple(case.get("caseClass") for case in cases) == EXPECTED_CASE_CLASSES,
        "cases must be exactly V0, V1, V2 in order",
    )

    seen_revisions: set[str] = set()
    seen_fingerprints: set[str] = set()
    for case in cases:
        _require(isinstance(case, Mapping), "case entry must be an object")
        case_class = case.get("caseClass")
        revision = case.get("caseRevision")
        fingerprint = case.get("providerContentIdSha256")
        role = case.get("role")

        _require(case_class in EXPECTED_CASE_CLASSES, f"unknown case class {case_class!r}")
        _require(isinstance(revision, str) and revision, f"{case_class}: caseRevision required")
        _require(revision not in seen_revisions, f"duplicate case revision {revision}")
        seen_revisions.add(str(revision))
        _require(
            isinstance(fingerprint, str)
            and len(fingerprint) == 64
            and all(ch in "0123456789abcdef" for ch in fingerprint),
            f"{case_class}: invalid provider fingerprint",
        )
        _require(fingerprint not in seen_fingerprints, f"duplicate provider fingerprint for {case_class}")
        seen_fingerprints.add(str(fingerprint))
        _require(isinstance(role, str) and role, f"{case_class}: role required")

        selection = case.get("selectionEvidence")
        _require(isinstance(selection, Mapping), f"{case_class}: selectionEvidence required")
        _require(
            selection.get("sourceRepository") == "yt-dlp/yt-dlp",
            f"{case_class}: selection source repository drift",
        )
        _require(
            selection.get("sourceRevision") == EXPECTED_ORACLE_REVISION,
            f"{case_class}: selection source revision drift",
        )
        _require(
            selection.get("sourcePath") == "yt_dlp/extractor/youtube/_video.py",
            f"{case_class}: selection source path drift",
        )
        facts = selection.get("facts")
        _require(
            isinstance(facts, list)
            and facts
            and all(isinstance(item, str) and item for item in facts),
            f"{case_class}: selection facts required",
        )

        qualification = case.get("qualification")
        _require(isinstance(qualification, Mapping), f"{case_class}: qualification required")
        mode = qualification.get("mode")
        _require(mode in ("REFERENCE_FIXTURE", "PROBE_EVIDENCE"), f"{case_class}: invalid qualification mode")
        layouts = qualification.get("requiredMediaLayouts")
        _require(isinstance(layouts, list), f"{case_class}: requiredMediaLayouts must be an array")

        if case_class == "V1":
            _require(mode == "PROBE_EVIDENCE", "V1 must require probe evidence")
            _require(
                tuple(layouts) == ("SPLIT_AV", "MULTIPLE"),
                "V1 must require split/multiple A/V evidence",
            )
            evidence_path = qualification.get("evidencePath")
            _require(
                isinstance(evidence_path, str) and evidence_path.startswith(".work/evidence/m3-a2/qualification/"),
                "V1 qualification evidencePath is required",
            )
        else:
            _require(mode == "REFERENCE_FIXTURE", f"{case_class} must use pinned reference fixture qualification")
            _require(layouts == [], f"{case_class}: no media-layout qualification is required")


def load_registry(path: pathlib.Path) -> Mapping[str, Any]:
    value = _load_json(path)
    _require(isinstance(value, Mapping), f"{path}: registry must be an object")
    validate_registry(value)
    return value


def planned_cells(registry: Mapping[str, Any]) -> list[tuple[str, str]]:
    return [
        (case["caseClass"], lane)
        for case in registry["cases"]
        for lane in registry["primaryLanes"]
    ]


def build_plan(registry: Mapping[str, Any]) -> dict[str, Any]:
    cases = _case_map(registry)
    cells = []
    for case_class, lane in planned_cells(registry):
        case = cases[case_class]
        cells.append({
            "caseClass": case_class,
            "caseRevision": case["caseRevision"],
            "providerContentIdSha256": case["providerContentIdSha256"],
            "lane": lane,
            "profileId": registry["laneProfiles"][lane],
            "purpose": "matrix",
            "mediaProbeMode": "range-set",
        })
    return {
        "planVersion": 1,
        "planId": f"{registry['registryId']}:plan-v1",
        "registryId": registry["registryId"],
        "registryDigestSha256": _sha256_json(registry),
        "primaryLanes": list(registry["primaryLanes"]),
        "deferredLanes": list(registry["deferredLanes"]),
        "cells": cells,
        "qualificationGates": {
            case["caseClass"]: case["qualification"].get("evidencePath")
            for case in registry["cases"]
            if case["qualification"]["mode"] == "PROBE_EVIDENCE"
        },
    }


def validate_plan(plan: Mapping[str, Any], registry: Mapping[str, Any]) -> None:
    _require(isinstance(plan, Mapping), "matrix plan must be an object")
    expected = build_plan(registry)
    _require(plan == expected, "matrix plan does not exactly match deterministic registry expansion")


def _matching_reference(document: Mapping[str, Any], registry: Mapping[str, Any]) -> Mapping[str, Any] | None:
    oracle = registry["referenceOracle"]
    for reference in document.get("references") or []:
        if (
            isinstance(reference, Mapping)
            and reference.get("id") == oracle["id"]
            and reference.get("version") == oracle["version"]
            and reference.get("revision") == oracle["revision"]
            and reference.get("role") == "REFERENCE_ORACLE"
            and reference.get("productionDependency") is False
        ):
            return reference
    return None


def validate_probe_against_registry(
    document: Mapping[str, Any],
    registry: Mapping[str, Any],
    *,
    allow_deferred_lane: bool = False,
) -> Mapping[str, Any]:
    try:
        validate_probe_document(document)
    except M3ContractError as exc:
        raise M3MatrixError(str(exc)) from exc

    producer = document.get("producer") or {}
    _require(producer.get("toolId") == registry["producer"]["toolId"], "probe producer.toolId does not match registry")
    _require(producer.get("version") == registry["producer"]["version"], "probe producer.version does not match registry")
    _require(_matching_reference(document, registry) is not None, "probe does not contain the pinned reference oracle")

    video_case = document.get("videoCase") or {}
    case_class = video_case.get("caseClass")
    cases = _case_map(registry)
    _require(case_class in cases, f"probe uses unplanned case class {case_class!r}")
    case = cases[str(case_class)]
    _require(
        video_case.get("caseRevision") == case["caseRevision"],
        f"{case_class}: probe case revision does not match frozen registry",
    )
    _require(
        video_case.get("providerContentIdSha256") == case["providerContentIdSha256"],
        f"{case_class}: probe provider fingerprint does not match frozen registry",
    )

    client = document.get("client") or {}
    lane = client.get("lane")
    allowed = set(registry["primaryLanes"])
    if allow_deferred_lane:
        allowed.update(registry["deferredLanes"])
    _require(lane in allowed, f"probe lane {lane!r} is not planned for this matrix")
    _require(
        client.get("profileId") == registry["laneProfiles"][lane],
        f"probe profileId does not match frozen lane profile for {lane}",
    )
    _require(
        client.get("profileVersion") == registry["referenceOracle"]["version"],
        "probe profileVersion does not match pinned reference version",
    )
    return case


def validate_case_input(
    registry: Mapping[str, Any],
    *,
    case_class: str,
    case_revision: str,
    video_id: str,
    lane: str | None = None,
    purpose: str | None = None,
) -> Mapping[str, Any]:
    cases = _case_map(registry)
    _require(case_class in cases, f"unknown case class {case_class!r}")
    case = cases[case_class]
    _require(case_revision == case["caseRevision"], f"{case_class}: case revision mismatch")
    _require(video_id and not any(ch.isspace() for ch in video_id), "video_id must be non-empty and contain no whitespace")
    actual = _sha256_text(video_id)
    _require(
        actual == case["providerContentIdSha256"],
        f"{case_class}: raw provider ID does not match frozen fingerprint",
    )
    if lane is not None:
        if purpose == "matrix":
            _require(lane in registry["primaryLanes"], f"lane {lane!r} is not in the frozen primary matrix")
        else:
            _require(
                lane in (*registry["primaryLanes"], *registry["deferredLanes"]),
                f"unknown lane {lane!r}",
            )
    return case


def _qualification_path(repo_root: pathlib.Path, case: Mapping[str, Any]) -> pathlib.Path | None:
    qualification = case["qualification"]
    if qualification["mode"] != "PROBE_EVIDENCE":
        return None
    return repo_root / str(qualification["evidencePath"])


def validate_qualification_document(
    document: Mapping[str, Any],
    registry: Mapping[str, Any],
    *,
    case_class: str,
) -> None:
    cases = _case_map(registry)
    _require(case_class in cases, f"unknown case class {case_class}")
    case = cases[case_class]
    qualification = case["qualification"]
    _require(qualification["mode"] == "PROBE_EVIDENCE", f"{case_class} does not require probe qualification")
    validate_probe_against_registry(document, registry, allow_deferred_lane=True)

    video_case = document["videoCase"]
    _require(video_case["caseClass"] == case_class, "qualification artifact is for another case")
    allowed_layouts = set(qualification["requiredMediaLayouts"])
    _require(
        document["delivery"]["mediaLayout"] in allowed_layouts,
        f"{case_class}: qualification requires mediaLayout in {sorted(allowed_layouts)}",
    )


def ensure_case_qualified(
    registry: Mapping[str, Any],
    *,
    case_class: str,
    repo_root: pathlib.Path,
) -> None:
    case = _case_map(registry)[case_class]
    if case["qualification"]["mode"] == "REFERENCE_FIXTURE":
        return
    path = _qualification_path(repo_root, case)
    _require(path is not None and path.is_file(), f"{case_class}: qualification artifact is not retained at {path}")
    document = _load_json(path)
    _require(isinstance(document, Mapping), f"{path}: qualification document must be an object")
    validate_qualification_document(document, registry, case_class=case_class)


def _tri_state(value: Any) -> str:
    if value == "PRESENT":
        return "ACCEPTED"
    if value == "ABSENT":
        return "REJECTED"
    return "UNKNOWN"


def _requirement_state(value: Any) -> str:
    if value == "REQUIRED":
        return "ACCEPTED"
    if value in ("NOT_REQUIRED", "NOT_APPLICABLE"):
        return "REJECTED"
    return "UNKNOWN"


def _anonymous_state(outcome: str) -> str:
    if outcome == "VIABLE_ANONYMOUS":
        return "ACCEPTED"
    if outcome in ("TOKEN_GATED", "CHALLENGE_GATED", "DRM_GATED", "UNSUPPORTED_DELIVERY"):
        return "REJECTED"
    return "UNKNOWN"


def _cell_assumptions(document: Mapping[str, Any]) -> list[dict[str, str]]:
    capabilities = document["capabilities"]
    requirements = document["requirements"]
    delivery = document["delivery"]
    outcome = document["outcome"]["classification"]
    case_class = document["videoCase"]["caseClass"]
    lane = document["client"]["lane"]

    specs = (
        ("anonymousStartup", _anonymous_state(outcome), outcome),
        ("directHttps", _tri_state(capabilities["https"]), capabilities["https"]),
        ("range", _tri_state(delivery["range"]), delivery["range"]),
        ("gvsPoTokenRequired", _requirement_state(requirements["gvsPoToken"]), requirements["gvsPoToken"]),
        ("playerPoTokenRequired", _requirement_state(requirements["playerPoToken"]), requirements["playerPoToken"]),
        ("challengeRuntimeRequired", _requirement_state(requirements["challengeRuntime"]), requirements["challengeRuntime"]),
        ("sabrObserved", _tri_state(capabilities["sabr"]), capabilities["sabr"]),
        ("drmObserved", _tri_state(capabilities["drm"]), capabilities["drm"]),
    )
    return [
        {
            "caseClass": str(case_class),
            "lane": str(lane),
            "name": name,
            "status": status,
            "evidenceValue": str(value),
        }
        for name, status, value in specs
    ]


def _probe_digest(document: Mapping[str, Any]) -> str:
    return _sha256_json(document)


def aggregate_probes(
    registry: Mapping[str, Any],
    documents: Sequence[Mapping[str, Any]],
    *,
    require_complete: bool,
    repo_root: pathlib.Path,
) -> dict[str, Any]:
    cells: dict[tuple[str, str], Mapping[str, Any]] = {}
    git_commits: set[str] = set()

    for document in documents:
        case = validate_probe_against_registry(document, registry)
        case_class = str(case["caseClass"])
        ensure_case_qualified(registry, case_class=case_class, repo_root=repo_root)
        lane = str(document["client"]["lane"])
        key = (case_class, lane)
        _require(key not in cells, f"duplicate matrix cell {case_class}/{lane}")
        cells[key] = document
        git_commits.add(str(document["gitCommit"]))

    _require(
        len(git_commits) <= 1,
        "matrix artifacts must come from one exact SpongeTube git commit",
    )

    planned = planned_cells(registry)
    missing = [
        {"caseClass": case_class, "lane": lane}
        for case_class, lane in planned
        if (case_class, lane) not in cells
    ]
    if require_complete:
        _require(not missing, f"matrix is incomplete: {len(missing)} planned cells are missing")

    rows: list[dict[str, Any]] = []
    assumptions: list[dict[str, str]] = []
    for case_class, lane in planned:
        document = cells.get((case_class, lane))
        if document is None:
            rows.append({
                "caseClass": case_class,
                "lane": lane,
                "status": "MISSING",
            })
            continue
        rows.append({
            "caseClass": case_class,
            "caseRevision": document["videoCase"]["caseRevision"],
            "providerContentIdSha256": document["videoCase"]["providerContentIdSha256"],
            "lane": lane,
            "status": "OBSERVED",
            "probeId": document["probeId"],
            "probeDigestSha256": _probe_digest(document),
            "createdAtUtc": document["createdAtUtc"],
            "gitCommit": document["gitCommit"],
            "profileId": document["client"]["profileId"],
            "profileVersion": document["client"]["profileVersion"],
            "capabilities": dict(document["capabilities"]),
            "requirements": dict(document["requirements"]),
            "providerSignals": dict(document["providerSignals"]),
            "delivery": dict(document["delivery"]),
            "outcome": dict(document["outcome"]),
            "limitations": list(document["limitations"]),
        })
        assumptions.extend(_cell_assumptions(document))

    counts = Counter(item["status"] for item in assumptions)
    report = {
        "schemaVersion": 1,
        "reportId": f"{registry['registryId']}:matrix-v1",
        "registryId": registry["registryId"],
        "registryDigestSha256": _sha256_json(registry),
        "producer": dict(registry["producer"]),
        "referenceOracle": dict(registry["referenceOracle"]),
        "executionGitCommit": next(iter(git_commits), None),
        "plannedCellCount": len(planned),
        "observedCellCount": len(cells),
        "complete": not missing,
        "missingCells": missing,
        "cells": rows,
        "assumptions": assumptions,
        "assumptionCounts": {
            "ACCEPTED": counts.get("ACCEPTED", 0),
            "REJECTED": counts.get("REJECTED", 0),
            "UNKNOWN": counts.get("UNKNOWN", 0),
        },
        "globalDecision": "NOT_MADE_M3_A2",
        "limitations": [
            "Each observation is scoped to one dated lane/case probe.",
            "UNKNOWN is never coerced to ABSENT or REJECTED.",
            "Provider rate limiting or rejection in one run is not a global provider claim.",
            "M3-A2 does not select a production YouTube adapter or dependency set.",
        ],
    }
    return report


def render_markdown(report: Mapping[str, Any]) -> str:
    lines = [
        "# M3-A2 Compatibility Matrix",
        "",
        f"Registry: `{report['registryId']}`",
        f"Registry digest: `{report['registryDigestSha256']}`",
        f"Execution commit: `{report.get('executionGitCommit') or 'none'}`",
        f"Coverage: **{report['observedCellCount']}/{report['plannedCellCount']}**",
        f"Complete: **{'yes' if report['complete'] else 'no'}**",
        "",
        "| Case | Lane | Outcome | HTTPS | Layout | GVS POT | Player POT | Challenge | SABR | DRM | Range | Startup | Seek |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for cell in report["cells"]:
        if cell["status"] == "MISSING":
            lines.append(
                f"| {cell['caseClass']} | {cell['lane']} | MISSING | — | — | — | — | — | — | — | — | — | — |"
            )
            continue
        caps = cell["capabilities"]
        reqs = cell["requirements"]
        delivery = cell["delivery"]
        lines.append(
            "| {case} | {lane} | {outcome} | {https} | {layout} | {gvs} | {player} | {challenge} | {sabr} | {drm} | {range_} | {startup} | {seek} |".format(
                case=cell["caseClass"],
                lane=cell["lane"],
                outcome=cell["outcome"]["classification"],
                https=caps["https"],
                layout=delivery["mediaLayout"],
                gvs=reqs["gvsPoToken"],
                player=reqs["playerPoToken"],
                challenge=reqs["challengeRuntime"],
                sabr=caps["sabr"],
                drm=caps["drm"],
                range_=delivery["range"],
                startup=delivery["startup"],
                seek=delivery["seek"],
            )
        )

    lines.extend([
        "",
        "## Assumptions",
        "",
        f"- ACCEPTED: {report['assumptionCounts']['ACCEPTED']}",
        f"- REJECTED: {report['assumptionCounts']['REJECTED']}",
        f"- UNKNOWN: {report['assumptionCounts']['UNKNOWN']}",
        "",
        "All assumption statuses are scoped to the exact lane/case cell; M3-A2 makes no global architecture decision.",
        "",
    ])
    return "\n".join(lines)


def _load_probe_directory(path: pathlib.Path) -> list[Mapping[str, Any]]:
    _require(path.is_dir(), f"{path}: input directory not found")
    documents: list[Mapping[str, Any]] = []
    for probe_path in sorted(path.glob("*.json")):
        document = _load_json(probe_path)
        _require(isinstance(document, Mapping), f"{probe_path}: top-level JSON object required")
        documents.append(document)
    return documents


def _cmd_validate_registry(args: argparse.Namespace) -> int:
    registry = load_registry(pathlib.Path(args.registry))
    print(
        "M3-A2 registry valid: "
        f"id={registry['registryId']} cells={len(planned_cells(registry))}"
    )
    return 0


def _cmd_validate_plan(args: argparse.Namespace) -> int:
    registry = load_registry(pathlib.Path(args.registry))
    plan = _load_json(pathlib.Path(args.plan))
    _require(isinstance(plan, Mapping), "matrix plan must be an object")
    validate_plan(plan, registry)
    print(
        "M3-A2 plan valid: "
        f"id={plan['planId']} cells={len(plan['cells'])}"
    )
    return 0


def _cmd_preflight(args: argparse.Namespace) -> int:
    registry_path = pathlib.Path(args.registry)
    registry = load_registry(registry_path)
    case = validate_case_input(
        registry,
        case_class=args.case_class,
        case_revision=args.case_revision,
        video_id=args.video_id,
        lane=args.lane,
        purpose=args.purpose,
    )

    if args.purpose == "matrix":
        ensure_case_qualified(
            registry,
            case_class=args.case_class,
            repo_root=registry_path.resolve().parents[2],
        )
    else:
        _require(
            case["qualification"]["mode"] == "PROBE_EVIDENCE",
            f"{args.case_class}: qualification purpose is not applicable",
        )

    print(
        "M3-A2 preflight valid: "
        f"case={args.case_class} revision={args.case_revision} "
        f"fingerprint={case['providerContentIdSha256']}"
    )
    return 0


def _cmd_validate_qualification(args: argparse.Namespace) -> int:
    registry = load_registry(pathlib.Path(args.registry))
    document = _load_json(pathlib.Path(args.input))
    _require(isinstance(document, Mapping), "qualification input must be an object")
    validate_qualification_document(document, registry, case_class=args.case_class)
    print(f"M3-A2 qualification valid: case={args.case_class}")
    return 0


def _cmd_aggregate(args: argparse.Namespace) -> int:
    registry_path = pathlib.Path(args.registry)
    registry = load_registry(registry_path)
    documents = _load_probe_directory(pathlib.Path(args.input_dir))
    report = aggregate_probes(
        registry,
        documents,
        require_complete=bool(args.require_complete),
        repo_root=registry_path.resolve().parents[2],
    )
    output = pathlib.Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    if args.markdown:
        markdown = pathlib.Path(args.markdown)
        markdown.parent.mkdir(parents=True, exist_ok=True)
        markdown.write_text(render_markdown(report) + "\n", encoding="utf-8")
    print(
        "M3-A2 matrix aggregated: "
        f"{report['observedCellCount']}/{report['plannedCellCount']} "
        f"complete={str(report['complete']).lower()}"
    )
    return 0


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    validate = sub.add_parser("validate-registry")
    validate.add_argument("--registry", required=True)
    validate.set_defaults(func=_cmd_validate_registry)

    validate_plan_parser = sub.add_parser("validate-plan")
    validate_plan_parser.add_argument("--registry", required=True)
    validate_plan_parser.add_argument("--plan", required=True)
    validate_plan_parser.set_defaults(func=_cmd_validate_plan)

    preflight = sub.add_parser("preflight")
    preflight.add_argument("--registry", required=True)
    preflight.add_argument("--case-class", choices=EXPECTED_CASE_CLASSES, required=True)
    preflight.add_argument("--case-revision", required=True)
    preflight.add_argument("--video-id", required=True)
    preflight.add_argument("--lane", choices=tuple(EXPECTED_LANE_PROFILES), required=True)
    preflight.add_argument("--purpose", choices=("qualification", "matrix"), required=True)
    preflight.set_defaults(func=_cmd_preflight)

    qualify = sub.add_parser("validate-qualification")
    qualify.add_argument("--registry", required=True)
    qualify.add_argument("--case-class", choices=EXPECTED_CASE_CLASSES, required=True)
    qualify.add_argument("--input", required=True)
    qualify.set_defaults(func=_cmd_validate_qualification)

    aggregate = sub.add_parser("aggregate")
    aggregate.add_argument("--registry", required=True)
    aggregate.add_argument("--input-dir", required=True)
    aggregate.add_argument("--output", required=True)
    aggregate.add_argument("--markdown")
    aggregate.add_argument("--require-complete", action="store_true")
    aggregate.set_defaults(func=_cmd_aggregate)
    return parser


def main() -> int:
    args = _build_parser().parse_args()
    try:
        return args.func(args)
    except M3MatrixError as exc:
        print(f"M3-A2 failed closed: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
