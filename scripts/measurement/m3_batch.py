"""One-run GitHub-hosted executor for the frozen SpongeTube M3-A2 matrix.

The batch consumes three raw public provider IDs only in memory, verifies them
against the frozen registry fingerprints before contacting the provider, runs
the V1/WEB_EMBEDDED qualification cell first, reuses that exact artifact as its
matrix cell, then executes the remaining 17 planned cells sequentially on the
same runner and aggregates the complete matrix.

Live provider outcomes are research observations, not release gates.
"""

from __future__ import annotations

import argparse
import json
import hashlib
import pathlib
import re
import shutil
import sys
from dataclasses import dataclass
from typing import Any, Callable, Mapping, Sequence

from m3_contracts import validate_probe_document
from m3_matrix import (
    M3MatrixError,
    V1_QUALIFICATION_LANE,
    aggregate_probes,
    load_registry,
    validate_case_input,
    validate_plan,
    validate_probe_against_registry,
    validate_qualification_document,
)
from m3_probe import ProbeRuntimeError, run_live_probe


QUALIFICATION_CASE = "V1"
QUALIFICATION_LANE = V1_QUALIFICATION_LANE


class M3BatchError(RuntimeError):
    pass


ProbeRunner = Callable[..., dict[str, Any]]

_SOURCE_PROVIDER_ID = re.compile(r"""(?P<quote>['"])(?P<id>[0-9A-Za-z_-]{11})(?P=quote)""")


@dataclass(frozen=True)
class BatchInputs:
    v0_video_id: str
    v1_video_id: str
    v2_video_id: str
    git_commit: str
    runner_image: str
    media_probe_mode: str = "range-set"

    def video_id_for_case(self, case_class: str) -> str:
        mapping = {
            "V0": self.v0_video_id,
            "V1": self.v1_video_id,
            "V2": self.v2_video_id,
        }
        try:
            return mapping[case_class]
        except KeyError as exc:
            raise M3BatchError(f"unplanned case class {case_class!r}") from exc


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise M3BatchError(message)


def derive_case_inputs_from_source(
    registry: Mapping[str, Any],
    source_text: str,
) -> dict[str, str]:
    candidates = {match.group("id") for match in _SOURCE_PROVIDER_ID.finditer(source_text)}
    _require(candidates, "pinned source contains no provider-ID candidates")

    by_digest: dict[str, list[str]] = {}
    for candidate in candidates:
        digest = hashlib.sha256(candidate.encode("utf-8")).hexdigest()
        by_digest.setdefault(digest, []).append(candidate)

    result: dict[str, str] = {}
    for case in registry["cases"]:
        case_class = str(case["caseClass"])
        digest = str(case["providerContentIdSha256"])
        matches = sorted(set(by_digest.get(digest, [])))
        _require(
            len(matches) == 1,
            f"{case_class}: pinned source must contain exactly one provider ID matching frozen fingerprint",
        )
        result[case_class] = matches[0]

    _require(set(result) == {"V0", "V1", "V2"}, "source derivation must resolve exactly V0/V1/V2")
    return result


def _scan_bundle_for_raw_ids(output_dir: pathlib.Path, inputs: BatchInputs) -> None:
    forbidden = (
        inputs.v0_video_id,
        inputs.v1_video_id,
        inputs.v2_video_id,
    )
    for path in output_dir.rglob("*"):
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        for raw_id in forbidden:
            _require(raw_id not in text, f"raw provider ID leaked into retained evidence file {path}")


def _write_json(path: pathlib.Path, value: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def _write_probe(path: pathlib.Path, artifact: Mapping[str, Any]) -> None:
    validate_probe_document(artifact)
    _write_json(path, artifact)


def _preflight_inputs(
    registry: Mapping[str, Any],
    plan: Mapping[str, Any],
    inputs: BatchInputs,
) -> None:
    _require(
        len(inputs.git_commit) == 40
        and all(ch in "0123456789abcdef" for ch in inputs.git_commit),
        "git_commit must be a lowercase 40-character SHA",
    )
    _require(bool(inputs.runner_image), "runner_image is required")
    _require(
        inputs.media_probe_mode == "range-set",
        "M3-A2 canonical batch requires media_probe_mode=range-set",
    )

    revisions = {
        case["caseClass"]: case["caseRevision"]
        for case in registry["cases"]
    }
    for case_class in ("V0", "V1", "V2"):
        validate_case_input(
            registry,
            case_class=case_class,
            case_revision=revisions[case_class],
            video_id=inputs.video_id_for_case(case_class),
        )

    cells = plan.get("cells")
    _require(isinstance(cells, list) and len(cells) == 18, "canonical plan must contain 18 cells")
    qualification_cells = [
        cell
        for cell in cells
        if cell.get("caseClass") == QUALIFICATION_CASE
        and cell.get("lane") == QUALIFICATION_LANE
    ]
    _require(
        len(qualification_cells) == 1,
        "canonical plan must contain exactly one V1/WEB_EMBEDDED cell",
    )


def _run_cell(
    *,
    registry: Mapping[str, Any],
    cell: Mapping[str, Any],
    inputs: BatchInputs,
    probe_runner: ProbeRunner,
) -> dict[str, Any]:
    case_class = str(cell["caseClass"])
    lane = str(cell["lane"])
    artifact = probe_runner(
        video_id=inputs.video_id_for_case(case_class),
        case_class=case_class,
        case_revision=str(cell["caseRevision"]),
        lane=lane,
        git_commit=inputs.git_commit,
        runner_image=inputs.runner_image,
        media_probe_mode=inputs.media_probe_mode,
    )
    validate_probe_against_registry(artifact, registry)
    _require(
        artifact["gitCommit"] == inputs.git_commit,
        f"{case_class}/{lane}: artifact gitCommit drift",
    )
    _require(
        artifact["environment"]["runnerImage"] == inputs.runner_image,
        f"{case_class}/{lane}: runner image drift",
    )
    _require(
        artifact["videoCase"]["providerContentIdSha256"]
        == cell["providerContentIdSha256"],
        f"{case_class}/{lane}: provider fingerprint drift",
    )
    _require(
        artifact["client"]["profileId"] == cell["profileId"],
        f"{case_class}/{lane}: profile drift",
    )
    return artifact


def _safe_cell_name(cell: Mapping[str, Any]) -> str:
    return f"{cell['caseClass'].lower()}-{str(cell['lane']).lower()}.json"


def _materialize_qualification_for_aggregator(
    *,
    root: pathlib.Path,
    registry: Mapping[str, Any],
    artifact: Mapping[str, Any],
) -> pathlib.Path:
    case = next(item for item in registry["cases"] if item["caseClass"] == QUALIFICATION_CASE)
    relative = pathlib.PurePosixPath(case["qualification"]["evidencePath"])
    target = root.joinpath(*relative.parts)
    _write_probe(target, artifact)
    return target


def execute_batch(
    *,
    registry: Mapping[str, Any],
    plan: Mapping[str, Any],
    inputs: BatchInputs,
    output_dir: pathlib.Path,
    probe_runner: ProbeRunner = run_live_probe,
) -> dict[str, Any]:
    validate_plan(plan, registry)
    _preflight_inputs(registry, plan, inputs)

    output_dir.mkdir(parents=True, exist_ok=True)
    probes_dir = output_dir / "probes"
    qualification_dir = output_dir / "qualification"
    aggregate_dir = output_dir / "aggregate"
    staging_root = output_dir / ".staging-root"

    for managed in (probes_dir, qualification_dir, aggregate_dir, staging_root):
        if managed.exists():
            shutil.rmtree(managed)
    summary_path = output_dir / "batch-summary-v1.json"
    if summary_path.exists():
        summary_path.unlink()

    probes_dir.mkdir(parents=True, exist_ok=True)
    qualification_dir.mkdir(parents=True, exist_ok=True)
    aggregate_dir.mkdir(parents=True, exist_ok=True)

    cells: Sequence[Mapping[str, Any]] = plan["cells"]
    qualification_cell = next(
        cell
        for cell in cells
        if cell["caseClass"] == QUALIFICATION_CASE
        and cell["lane"] == QUALIFICATION_LANE
    )

    # Run qualification before all other provider requests. The exact same
    # artifact is reused as the V1/WEB_EMBEDDED matrix cell, avoiding a hidden
    # nineteenth provider experiment.
    qualification = _run_cell(
        registry=registry,
        cell=qualification_cell,
        inputs=inputs,
        probe_runner=probe_runner,
    )
    qualification_path = qualification_dir / "v1-web-embedded.json"
    _write_probe(qualification_path, qualification)

    try:
        validate_qualification_document(
            qualification,
            registry,
            case_class=QUALIFICATION_CASE,
        )
    except M3MatrixError as exc:
        summary = {
            "schemaVersion": 1,
            "status": "QUALIFICATION_NOT_MET",
            "gitCommit": inputs.git_commit,
            "qualificationCell": {
                "caseClass": QUALIFICATION_CASE,
                "lane": QUALIFICATION_LANE,
                "outcome": qualification["outcome"]["classification"],
                "mediaLayout": qualification["delivery"]["mediaLayout"],
                "providerContentIdSha256": qualification["videoCase"]["providerContentIdSha256"],
            },
            "remainingCellsExecuted": 0,
            "reason": str(exc),
        }
        _write_json(summary_path, summary)
        if staging_root.exists():
            shutil.rmtree(staging_root)
        _scan_bundle_for_raw_ids(output_dir, inputs)
        raise M3BatchError(
            "V1/WEB_EMBEDDED qualification did not establish SPLIT_AV or MULTIPLE; "
            "remaining matrix cells were not executed"
        ) from exc

    _materialize_qualification_for_aggregator(
        root=staging_root,
        registry=registry,
        artifact=qualification,
    )
    _write_probe(
        probes_dir / _safe_cell_name(qualification_cell),
        qualification,
    )

    documents: list[Mapping[str, Any]] = [qualification]
    execution_order: list[dict[str, Any]] = [{
        "ordinal": 1,
        "caseClass": QUALIFICATION_CASE,
        "lane": QUALIFICATION_LANE,
        "reusedQualification": True,
        "outcome": qualification["outcome"]["classification"],
    }]

    ordinal = 1
    for cell in cells:
        if cell is qualification_cell:
            continue
        ordinal += 1
        artifact = _run_cell(
            registry=registry,
            cell=cell,
            inputs=inputs,
            probe_runner=probe_runner,
        )
        _write_probe(probes_dir / _safe_cell_name(cell), artifact)
        documents.append(artifact)
        execution_order.append({
            "ordinal": ordinal,
            "caseClass": cell["caseClass"],
            "lane": cell["lane"],
            "reusedQualification": False,
            "outcome": artifact["outcome"]["classification"],
        })

    report = aggregate_probes(
        registry,
        documents,
        require_complete=True,
        repo_root=staging_root,
    )
    _require(report["complete"] is True, "aggregator did not produce a complete matrix")
    _require(report["observedCellCount"] == 18, "aggregator did not retain exactly 18 cells")

    matrix_json = aggregate_dir / "matrix-v1.json"
    matrix_md = aggregate_dir / "matrix-v1.md"
    _write_json(matrix_json, report)

    from m3_matrix import render_markdown

    matrix_md.write_text(render_markdown(report) + "\n", encoding="utf-8")

    summary = {
        "schemaVersion": 1,
        "status": "COMPLETE",
        "gitCommit": inputs.git_commit,
        "runnerImage": inputs.runner_image,
        "registryId": registry["registryId"],
        "planId": plan["planId"],
        "qualificationCell": {
            "caseClass": QUALIFICATION_CASE,
            "lane": QUALIFICATION_LANE,
            "reusedAsMatrixCell": True,
            "outcome": qualification["outcome"]["classification"],
            "mediaLayout": qualification["delivery"]["mediaLayout"],
        },
        "executedProviderProbeCount": len(documents),
        "matrixCellCount": report["observedCellCount"],
        "executionOrder": execution_order,
        "globalDecision": report["globalDecision"],
    }
    _write_json(summary_path, summary)
    if staging_root.exists():
        shutil.rmtree(staging_root)
    _scan_bundle_for_raw_ids(output_dir, inputs)
    return summary


def _load_plan(path: pathlib.Path) -> Mapping[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise M3BatchError(f"{path}: cannot load plan: {exc}") from exc
    _require(isinstance(value, Mapping), "matrix plan must be a JSON object")
    return value


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry", required=True)
    parser.add_argument("--plan", required=True)
    parser.add_argument("--v0-video-id")
    parser.add_argument("--v1-video-id")
    parser.add_argument("--v2-video-id")
    parser.add_argument(
        "--source-file",
        help="Pinned yt-dlp source file used to derive V0/V1/V2 raw IDs from frozen SHA-256 fingerprints",
    )
    parser.add_argument("--git-commit", required=True)
    parser.add_argument("--runner-image", required=True)
    parser.add_argument("--media-probe-mode", default="range-set", choices=("range-set",))
    parser.add_argument("--output-dir", required=True)
    return parser


def main() -> int:
    args = _build_parser().parse_args()
    try:
        registry = load_registry(pathlib.Path(args.registry))
        plan = _load_plan(pathlib.Path(args.plan))

        explicit_ids = (args.v0_video_id, args.v1_video_id, args.v2_video_id)
        if args.source_file:
            _require(
                not any(explicit_ids),
                "--source-file cannot be combined with explicit provider IDs",
            )
            source_path = pathlib.Path(args.source_file)
            try:
                source_text = source_path.read_text(encoding="utf-8")
            except OSError as exc:
                raise M3BatchError(f"{source_path}: cannot read pinned source: {exc}") from exc
            derived = derive_case_inputs_from_source(registry, source_text)
            v0_video_id = derived["V0"]
            v1_video_id = derived["V1"]
            v2_video_id = derived["V2"]
        else:
            _require(
                all(explicit_ids),
                "provide --source-file or all three explicit provider IDs",
            )
            v0_video_id = str(args.v0_video_id)
            v1_video_id = str(args.v1_video_id)
            v2_video_id = str(args.v2_video_id)

        summary = execute_batch(
            registry=registry,
            plan=plan,
            inputs=BatchInputs(
                v0_video_id=v0_video_id,
                v1_video_id=v1_video_id,
                v2_video_id=v2_video_id,
                git_commit=args.git_commit,
                runner_image=args.runner_image,
                media_probe_mode=args.media_probe_mode,
            ),
            output_dir=pathlib.Path(args.output_dir),
        )
        print(
            "M3-A2 batch complete: "
            f"probes={summary['executedProviderProbeCount']} "
            f"cells={summary['matrixCellCount']} "
            f"decision={summary['globalDecision']}"
        )
        return 0
    except (M3BatchError, M3MatrixError, ProbeRuntimeError) as exc:
        print(f"M3-A2 batch failed closed: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
