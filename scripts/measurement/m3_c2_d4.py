"""M3-A2-C2-D4: bounded typed YouTube extractor phase tracing.

Research only. Preserve all original D0-D3 source, decisions and evidence.
Only explicit 'run' / 'observe-one' may contact YouTube. Never inspect or
persist response bodies, exception messages, URLs or input/output arguments.
"""
from __future__ import annotations

import argparse
import functools
import hashlib
import json
import pathlib
import re
import subprocess
import sys
import types
from contextlib import contextmanager
from typing import Any, Callable, Mapping

import m3_c2_d3 as d3
from m3_batch import derive_case_inputs_from_source
from m3_matrix import load_registry

SAFE_DIAGNOSTIC_PHASES = frozenset({
    "READ_PLAN", "VALIDATE_PLAN", "REFERENCE_FIXTURE", "CHILD_DISPATCH",
    "LOAD_ORACLE", "BIND_STAGE_HOOKS", "RUN_REFERENCE",
    "VALIDATE_TRACE", "WRITE_EVIDENCE", "VERIFY_BUNDLE",
})

PROTOCOL = "m3-a2-c2-d4-typed-extractor-phases-v1"
PLAN_ID = "m3-a2-c2-d4-stage-trace-2026-10-26-v1"
MAX_STAGE_EVENTS = 96
MAX_METADATA_TOTAL = 128
HOOKS = {
    "_real_extract": "VIDEO_EXTRACT",
    "_initial_extract": "INITIAL_EXTRACT",
    "_download_initial_webpage": "WEBPAGE_FETCH",
    "_download_initial_data": "INITIAL_DATA",
    "_get_requested_clients": "CLIENT_SELECTION",
    "_extract_player_responses": "PLAYER_RESPONSES",
    "_extract_player_response": "PLAYER_RESPONSE",
    "_list_formats": "FORMAT_SELECTION",
}
OUTCOMES = frozenset({"ENTER", "RETURN", "RAISE"})


def require(ok: bool, reason: str) -> None:
    if not ok:
        raise ValueError(reason)


def build_plan(registry: Mapping[str, Any], d3_plan: Mapping[str, Any]) -> dict[str, Any]:
    # This function never alters the D3 plan nor the frozen case registry.
    require(d3_plan["planId"] == d3.PLAN_ID
            and d3_plan["registryId"] == registry["registryId"]
            and d3_plan["oracles"] == d3.ORACLES
            and d3_plan["globalDecision"] == "NOT_MADE_M3_A2",
            "D3 parent identity drift")
    controls = [dict(step) for step in d3_plan["execution"] if step["arm"] == "B"]
    require(len(controls) == 4
            and [(s["caseClass"], s["lane"]) for s in controls] == [
                ("V0", "WEB_EMBEDDED"), ("V2", "WEB_EMBEDDED"),
                ("V0", "TV"), ("V2", "TV")],
            "D4 requires exactly four pinned B controls")
    return {
        "schemaVersion": 1, "planId": PLAN_ID, "protocol": PROTOCOL,
        "registryId": registry["registryId"], "d3PlanId": d3_plan["planId"],
        "oracle": dict(d3.ORACLES["B"]), "mode": dict(d3_plan["mode"]),
        "steps": controls, "hookMap": dict(HOOKS),
        "maxStageEventsPerObservation": MAX_STAGE_EVENTS,
        "metadataRequestLimitPerObservation": d3.MAX_PER_OBSERVATION,
        "maxTotalMetadataRequests": MAX_METADATA_TOTAL,
        "maxTotalMediaProbes": 0, "globalDecision": "NOT_MADE_M3_A2",
    }


def validate_plan(plan: Mapping[str, Any], registry: Mapping[str, Any],
                  c1: Mapping[str, Any], d0: Mapping[str, Any],
                  d1_plan: Mapping[str, Any], d2_plan: Mapping[str, Any],
                  d3_plan: Mapping[str, Any]) -> None:
    d3.validate_plan(d3_plan, registry, c1, d0, d1_plan, d2_plan)
    require(type(plan) is dict and plan == build_plan(registry, d3_plan),
            "D4 plan must be exact, no silent profiles, runtime or budget changes")


class StageObserver:
    def __init__(self):
        self.events: list[dict[str, Any]] = []

    def event(self, stage: str, outcome: str) -> None:
        require(stage in HOOKS.values() and outcome in OUTCOMES,
                "unknown stage/outcome")
        require(len(self.events) < MAX_STAGE_EVENTS,
                "stage event ceiling reached (abort; no partial evidence)")
        self.events.append({
            "ordinal": len(self.events) + 1, "stage": stage, "outcome": outcome,
        })

    @contextmanager
    def installed(self, ie_class: type):
        """Patch pinned YoutubeIE methods only within an isolated child process.

        No function input/output inspection, introspection of exception objects,
        arbitrary logging, response mutation or additional network requests.
        """
        originals: dict[str, Any] = {}
        require(type(ie_class) is type and ie_class.__name__ == "YoutubeIE"
                and ie_class.__module__ == "yt_dlp.extractor.youtube._video",
                "unexpected target extractor class")
        for name in HOOKS:
            fn = vars(ie_class).get(name)
            require(type(fn) is types.FunctionType, "pinned hook missing")
            originals[name] = fn

        def wrap(original: Callable[..., Any], stage: str) -> Callable[..., Any]:
            @functools.wraps(original)
            def callback(instance: Any, *args: Any, **kwargs: Any) -> Any:
                self.event(stage, "ENTER")
                try:
                    value = original(instance, *args, **kwargs)
                except BaseException:
                    self.event(stage, "RAISE")
                    raise
                self.event(stage, "RETURN")
                return value
            return callback

        try:
            for name, original in originals.items():
                setattr(ie_class, name, wrap(original, HOOKS[name]))
            yield
        finally:
            for name, original in originals.items():
                setattr(ie_class, name, original)


def validate_trace(events: Any, completed: bool, extractor_stage: str) -> None:
    require(type(events) is list and len(events) <= MAX_STAGE_EVENTS,
            "invalid stage event count")
    stack: list[str] = []
    for ordinal, event in enumerate(events, 1):
        require(type(event) is dict and set(event) == {"ordinal", "stage", "outcome"}
                and type(event["ordinal"]) is int and event["ordinal"] == ordinal
                and type(event["stage"]) is str and event["stage"] in HOOKS.values()
                and type(event["outcome"]) is str and event["outcome"] in OUTCOMES,
                "untrusted extractor phase event")
        if event["outcome"] == "ENTER":
            stack.append(event["stage"])
        else:
            require(bool(stack) and stack.pop() == event["stage"],
                    "unbalanced or forged extractor phase exit")
    require(not stack, "incomplete stage trace")
    require(extractor_stage in d3.d1.EXTRACTOR_STAGES, "unrecognized extractor stage")
    if extractor_stage != "INITIALIZATION_FAILED":
        require(bool(events) and events[0]["stage"] == "VIDEO_EXTRACT"
                and events[0]["outcome"] == "ENTER", "YoutubeIE observer was not entered")
        require(events[-1]["stage"] == "VIDEO_EXTRACT", "YoutubeIE hook did not terminate")
        require(events[-1]["outcome"] == (
            "RAISE" if extractor_stage == "EXTRACT_INFO_RAISED" else "RETURN"),
            "typed extractor outcome contradicts trace")
    require(not completed or not any(x["outcome"] == "RAISE" for x in events),
            "unresolved stage exception in successful extraction")


def _summary(records: list[dict[str, Any]], git_commit: str,
             runner_image: str) -> dict[str, Any]:
    observations = []
    for record in records:
        trace = record["stageTrace"]
        ref = record["reference"]["observation"]
        first = next((event["stage"] for event in trace
                      if event["outcome"] == "RAISE"), "NONE")
        observations.append({
            "caseClass": record["caseClass"], "lane": record["lane"],
            "metadataRequests": ref["metadataRequestsUsed"],
            "extractionCompleted": ref["extractionCompleted"],
            "nestedCauseClass": ref["nestedCauseClass"],
            "firstRaisedPhase": first,
            "enteredPhases": [x["stage"] for x in trace if x["outcome"] == "ENTER"],
        })
    return {
        "schemaVersion": 1, "planId": PLAN_ID, "protocol": PROTOCOL,
        "gitCommit": git_commit, "runnerImage": runner_image,
        "oracle": dict(d3.ORACLES["B"]),
        "observations": observations, "controlCount": len(observations),
        "totalMetadataRequests": sum(x["metadataRequests"] for x in observations),
        "totalMediaProbes": 0, "globalDecision": "NOT_MADE_M3_A2",
    }


def envelope(step: Mapping[str, Any], reference: Mapping[str, Any],
             trace: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "schemaVersion": 1, "protocol": PROTOCOL,
        "referenceStepOrdinal": step["ordinal"], "caseClass": step["caseClass"],
        "lane": step["lane"], "reference": dict(reference),
        "stageTrace": list(trace),
    }


def validate_document(document: Any, registry: Mapping[str, Any],
                      d3_plan: Mapping[str, Any], step: Mapping[str, Any],
                      git_commit: str, runner_image: str) -> None:
    require(type(document) is dict and set(document) == {
        "schemaVersion", "protocol", "referenceStepOrdinal", "caseClass",
        "lane", "reference", "stageTrace"}, "D4 document has forbidden fields")
    require(type(document["schemaVersion"]) is int
            and document["schemaVersion"] == 1 and document["protocol"] == PROTOCOL
            and document["referenceStepOrdinal"] == step["ordinal"]
            and document["caseClass"] == step["caseClass"]
            and document["lane"] == step["lane"],
            "D4 identity drift")
    d3.validate_observation(document["reference"], registry, d3_plan, step,
                            git_commit, runner_image)
    validate_trace(document["stageTrace"],
                   document["reference"]["observation"]["extractionCompleted"],
                   document["reference"]["observation"]["extractorStage"])


def _filename(step: Mapping[str, Any]) -> str:
    return f"{step['ordinal']:02d}-{step['caseClass'].lower()}-{step['lane'].lower()}-b.json"


def run_d4(*, registry: Mapping[str, Any], d3_plan: Mapping[str, Any],
           plan: Mapping[str, Any], ids: Mapping[str, str],
           git_commit: str, runner_image: str, output: pathlib.Path,
           source_file: pathlib.Path | None = None,
           interpreter: pathlib.Path | None = None,
           observer: Callable[[Mapping[str, Any]], dict[str, Any]] | None = None) -> dict[str, Any]:
    require(plan == build_plan(registry, d3_plan), "invalid D4 plan")
    d3.validate_inputs(registry, ids)  # All IDs BEFORE any provider I/O.
    require(type(git_commit) is str and re.fullmatch(r"[0-9a-f]{40}", git_commit) is not None
            and type(runner_image) is str and re.fullmatch(
                r"[A-Za-z0-9._@-]{1,100}", runner_image) is not None,
            "untrusted commit or runner")
    require(not output.exists() or not any(output.iterdir()), "output must be empty")
    if observer is None:
        require(interpreter is not None and interpreter.is_file()
                and source_file is not None and source_file.is_file(),
                "pinned child Python/fixture absent")
    output.mkdir(parents=True, exist_ok=True)
    records = []
    for step in plan["steps"]:
        dest = output / _filename(step)
        if observer is None:
            assert interpreter is not None and source_file is not None
            command = [
                str(interpreter), str(pathlib.Path(__file__).resolve()),
                "observe-one", "--source-file", str(source_file),
                "--git-commit", git_commit, "--runner-image", runner_image,
                "--ordinal", str(step["ordinal"]), "--output-file", str(dest),
            ]
            try:
                result = subprocess.run(command, capture_output=True,
                                        check=False, timeout=180)
            except (OSError, subprocess.TimeoutExpired):
                raise ValueError("isolated D4 observer failed closed") from None
            if result.returncode != 0:
                # Read only the static D4-generated phase code, never print or
                # retain the untrusted child's stdout/stderr or exception text.
                detail = result.stderr.decode("ascii", errors="ignore")
                phases = re.findall(r"D4_CLOSED_PHASE:([A-Z_]+)", detail)
                trusted = next((v for v in reversed(phases)
                                if v in SAFE_DIAGNOSTIC_PHASES), "UNKNOWN")
                print("D4_CHILD_CLOSED_PHASE:" + trusted, file=sys.stderr)
                raise ValueError("child failed; no portable evidence")
            doc = json.loads(dest.read_text(encoding="utf-8"))
        else:
            doc = observer(step)
            dest.write_bytes(d3._serialize(doc))
        validate_document(doc, registry, d3_plan, step, git_commit, runner_image)
        records.append(doc)
        require(sum(x["reference"]["observation"]["metadataRequestsUsed"]
                    for x in records) <= MAX_METADATA_TOTAL, "request budget exceeded")
    summary = _summary(records, git_commit, runner_image)
    (output / "summary-v1.json").write_bytes(d3._serialize(summary))
    (output / "manifest-v1.json").write_bytes(d3._serialize({
        "schemaVersion": 1, "planId": PLAN_ID,
        "files": [{"path": p.name, "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
                  for p in sorted(output.glob("*.json"))],
    }))
    d3._check_privacy(output, ids)
    return summary


def verify_bundle(*, registry: Mapping[str, Any], d3_plan: Mapping[str, Any],
                  plan: Mapping[str, Any], ids: Mapping[str, str],
                  git_commit: str, runner_image: str, root: pathlib.Path) -> dict[str, Any]:
    require(plan == build_plan(registry, d3_plan), "forged D4 plan")
    expected = {_filename(step) for step in plan["steps"]} | {"summary-v1.json"}
    require({p.name for p in root.iterdir()} == expected | {"manifest-v1.json"},
            "unexpected D4 evidence set")
    manifest = json.loads((root / "manifest-v1.json").read_text(encoding="utf-8"))
    entries = manifest.get("files")
    require(type(manifest) is dict and set(manifest) == {
        "schemaVersion", "planId", "files"}
        and type(manifest["schemaVersion"]) is int and manifest["schemaVersion"] == 1
        and manifest["planId"] == PLAN_ID and type(entries) is list
        and len(entries) == 5 and
        all(type(e) is dict and set(e) == {"path", "sha256"} for e in entries)
        and {e["path"] for e in entries} == expected,
        "manifest records mismatch")
    for item in entries:
        require(type(item["sha256"]) is str
                and re.fullmatch(r"[0-9a-f]{64}", item["sha256"]) is not None
                and hashlib.sha256((root / item["path"]).read_bytes()).hexdigest()
                == item["sha256"], "manifest SHA-256 mismatch")
    records = []
    for step in plan["steps"]:
        doc = json.loads((root / _filename(step)).read_text(encoding="utf-8"))
        validate_document(doc, registry, d3_plan, step, git_commit, runner_image)
        records.append(doc)
    summary = json.loads((root / "summary-v1.json").read_text(encoding="utf-8"))
    require(summary == _summary(records, git_commit, runner_image)
            and summary["totalMetadataRequests"] <= MAX_METADATA_TOTAL,
            "derived summary or aggregate budget mismatch")
    d3._check_privacy(root, ids)
    return summary


DEFAULTS = {
    "registry": pathlib.Path(".work/m3-a2/case-registry-v1.json"),
    "c1-plan": pathlib.Path(".work/m3-a2/control-plan-v1.json"),
    "d0-plan": pathlib.Path(".work/m3-a2/c2-d0-plan-v1.json"),
    "d1-plan": pathlib.Path(".work/m3-a2/c2-d1-plan-v1.json"),
    "d2-plan": pathlib.Path(".work/m3-a2/c2-d2-plan-v1.json"),
    "d3-plan": pathlib.Path(".work/m3-a2/c2-d3-plan-v1.json"),
    "plan": pathlib.Path(".work/m3-a2/c2-d4-plan-v1.json"),
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("validate-plan", "validate-hooks", "run", "verify", "observe-one"))
    for name, default in DEFAULTS.items():
        parser.add_argument("--" + name, type=pathlib.Path, default=default)
    parser.add_argument("--source-file", type=pathlib.Path)
    parser.add_argument("--interpreter", type=pathlib.Path)
    parser.add_argument("--git-commit")
    parser.add_argument("--runner-image")
    parser.add_argument("--ordinal", type=int)
    parser.add_argument("--output-file", type=pathlib.Path)
    parser.add_argument("--output-dir", type=pathlib.Path)
    args = parser.parse_args()
    phase = "READ_PLAN"
    try:
        registry = load_registry(args.registry)
        c1, d0, d1_plan, d2_plan, d3_plan, plan = [
            json.loads(getattr(args, key.replace("-", "_")).read_text(encoding="utf-8"))
            for key in ("c1-plan", "d0-plan", "d1-plan", "d2-plan", "d3-plan", "plan")
        ]
        phase = "VALIDATE_PLAN"
        validate_plan(plan, registry, c1, d0, d1_plan, d2_plan, d3_plan)
        if args.command == "validate-plan":
            print("D4 four fixed B stage traces, 128 metadata attempts max, 0 media")
            return 0
        if args.command == "validate-hooks":
            phase = "LOAD_ORACLE"
            from yt_dlp.extractor.youtube._video import YoutubeIE
            import yt_dlp.version
            oracle = d3.ORACLES["B"]
            require(yt_dlp.version.__version__ == oracle["version"]
                    and yt_dlp.version.RELEASE_GIT_HEAD == oracle["revision"],
                    "unexpected installed oracle")
            phase = "BIND_STAGE_HOOKS"
            target_ok = (type(YoutubeIE) is type and YoutubeIE.__name__ == "YoutubeIE"
                         and YoutubeIE.__module__ == "yt_dlp.extractor.youtube._video")
            print("D4_SAFE_HOOK_TARGET:" + ("MATCH" if target_ok else "MISMATCH"))
            # Only static trusted hook labels and booleans; do not print
            # actual type, module strings, function names or source objects.
            for hook_name, label in HOOKS.items():
                direct = type(vars(YoutubeIE).get(hook_name)) is types.FunctionType
                print("D4_SAFE_HOOK_" + label + ":" + ("DIRECT" if direct else "NOT_DIRECT"))
            with StageObserver().installed(YoutubeIE):
                pass  # zero provider I/O
            print("D4 exact eight-hook binding PASS; no network")
            return 0
        phase = "REFERENCE_FIXTURE"
        require(args.source_file is not None and args.git_commit is not None
                and args.runner_image is not None, "missing required input")
        ids = derive_case_inputs_from_source(
            registry, args.source_file.read_text(encoding="utf-8"))
        d3.validate_inputs(registry, ids)
        if args.command == "observe-one":
            phase = "LOAD_ORACLE"
            require(type(args.ordinal) is int and args.output_file is not None
                    and not args.output_file.exists(), "invalid observation")
            step = next((s for s in plan["steps"] if s["ordinal"] == args.ordinal), None)
            require(step is not None, "unapproved observation")
            import yt_dlp
            import yt_dlp.version
            from yt_dlp import utils
            from yt_dlp.networking import exceptions as network_types
            from yt_dlp.networking.common import Response
            from yt_dlp.extractor.youtube._video import YoutubeIE
            oracle = d3.ORACLES["B"]
            require(yt_dlp.version.__version__ == oracle["version"]
                    and yt_dlp.version.RELEASE_GIT_HEAD == oracle["revision"],
                    "pinned package/release drift")
            api = types.SimpleNamespace(
                YoutubeDL=yt_dlp.YoutubeDL, networking_exceptions=network_types,
                networking_response_type=Response, utils=utils)
            cell = next(c for c in d3_plan["cells"]
                        if c["caseClass"] == step["caseClass"] and c["lane"] == step["lane"])
            tracer = StageObserver()
            phase = "BIND_STAGE_HOOKS"
            with tracer.installed(YoutubeIE):
                phase = "RUN_REFERENCE"
                original = d3.observe_versioned_cell(
                    registry=registry, cell=cell, arm="B",
                    raw_id=ids[step["caseClass"]], git_commit=args.git_commit,
                    runner_image=args.runner_image,
                    oracle_loader=lambda: (api, oracle["version"], oracle["revision"]))
            phase = "VALIDATE_TRACE"
            ref = d3.observation_envelope(step, original)
            record = envelope(step, ref, tracer.events)
            validate_document(record, registry, d3_plan, step,
                              args.git_commit, args.runner_image)
            phase = "WRITE_EVIDENCE"
            args.output_file.parent.mkdir(parents=True, exist_ok=True)
            args.output_file.write_bytes(d3._serialize(record))
            return 0
        require(args.output_dir is not None, "missing output dir")
        if args.command == "run":
            phase = "CHILD_DISPATCH"
            result = run_d4(
                registry=registry, d3_plan=d3_plan, plan=plan, ids=ids,
                git_commit=args.git_commit, runner_image=args.runner_image,
                output=args.output_dir, source_file=args.source_file,
                interpreter=args.interpreter)
        else:
            phase = "VERIFY_BUNDLE"
            result = verify_bundle(
                registry=registry, d3_plan=d3_plan, plan=plan, ids=ids,
                git_commit=args.git_commit, runner_image=args.runner_image,
                root=args.output_dir)
        print(f"D4 {result['controlCount']} cells, "
              f"{result['totalMetadataRequests']} metadata attempts, "
              "0 media probes; decision=NOT_MADE_M3_A2")
        return 0
    except Exception:
        # Never print exception text or provider-controlled values.
        print("D4_CLOSED_PHASE:" + phase, file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
