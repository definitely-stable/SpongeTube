"""D5: bounded post-format terminal discriminator on the pinned yt-dlp oracle.

No source/response/body/exception/URL content reaches evidence; never enable
JS, tokens, credentials or media I/O. Only run/observe-one permit provider I/O.
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
import m3_c2_d4 as d4
from m3_batch import derive_case_inputs_from_source
from m3_matrix import load_registry

PROTOCOL = "m3-a2-c2-d5-post-format-terminal-v1"
PLAN_ID = "m3-a2-c2-d5-terminal-2026-10-29-v1"
TERMINAL_METHODS = ("raise_no_formats", "report_drm")
FORMAT_FAMILIES = frozenset({"EMPTY", "NONEMPTY", "UNKNOWN"})
MAX_TERMINAL_CALLS = 8


def require(ok: bool, why: str) -> None:
    if not ok:
        raise ValueError(why)


def build_plan(registry: Mapping[str, Any], d4_plan: Mapping[str, Any]) -> dict[str, Any]:
    require(d4_plan["planId"] == d4.PLAN_ID
            and d4_plan["registryId"] == registry["registryId"]
            and d4_plan["oracle"] == d3.ORACLES["B"]
            and d4_plan["steps"] == d4.build_plan(registry, {
                "planId": d3.PLAN_ID, "registryId": registry["registryId"],
                "oracles": d3.ORACLES, "globalDecision": "NOT_MADE_M3_A2",
                "execution": [
                    {"ordinal": 1, "caseClass": "V0", "lane": "WEB_EMBEDDED", "arm": "A"},
                    *[dict(s) for s in d4_plan["steps"]],
                ],
                "mode": d4_plan["mode"],
            })["steps"],
            "D4 control identity drift")
    return {
        "schemaVersion": 1, "planId": PLAN_ID, "protocol": PROTOCOL,
        "registryId": registry["registryId"], "d4PlanId": d4_plan["planId"],
        "oracle": dict(d3.ORACLES["B"]), "mode": dict(d4_plan["mode"]),
        "steps": [dict(x) for x in d4_plan["steps"]],
        "terminalMethods": list(TERMINAL_METHODS),
        "formatResult": sorted(FORMAT_FAMILIES),
        "maxTerminalCallsPerObservation": MAX_TERMINAL_CALLS,
        "maxMetadataRequestsPerObservation": 32,
        "maxTotalMetadataRequests": 128,
        "maxTotalMediaProbes": 0,
        "globalDecision": "NOT_MADE_M3_A2",
    }


def validate_plan(plan: Mapping[str, Any], registry: Mapping[str, Any],
                  ancestors: list[Mapping[str, Any]]) -> None:
    require(len(ancestors) == 6, "must validate C1/D0/D1/D2/D3/D4")
    c1, d0, d1, d2, d3_plan, d4_plan = ancestors
    d4.validate_plan(d4_plan, registry, c1, d0, d1, d2, d3_plan)
    require(type(plan) is dict and plan == build_plan(registry, d4_plan),
            "D5 frozen terminal plan drift")


class TerminalProbe:
    """Wrap only method entry for inherited terminal APIs and one closed boolean."""
    def __init__(self):
        self.format_family = "UNKNOWN"
        self.format_observations = 0
        self.no_formats_calls = 0
        self.drm_calls = 0

    @contextmanager
    def installed(self, cls: type):
        require(type(cls) is type and cls.__name__ == "YoutubeIE"
                and cls.__module__ == "yt_dlp.extractor.youtube",
                "unexpected pinned extractor class")
        original_direct = vars(cls).get("_list_formats")
        require(type(original_direct) is types.FunctionType,
                "missing pinned direct _list_formats")
        inherited = {name: getattr(cls, name, None) for name in TERMINAL_METHODS}
        require(all(type(fn) is types.FunctionType for fn in inherited.values())
                and all(name not in vars(cls) for name in TERMINAL_METHODS),
                "unexpected inherited terminal method shape")
        # D4 wrapping is active for _list_formats; preserve its exact function.
        @functools.wraps(original_direct)
        def checked_formats(instance, *args, **kwargs):
            value = original_direct(instance, *args, **kwargs)
            require(self.format_observations < MAX_TERMINAL_CALLS,
                    "unbounded format observation")
            self.format_observations += 1
            # Only inspect arity/type and emptiness, never traverse data.
            category = "UNKNOWN"
            if type(value) is tuple and len(value) == 4:
                formats = value[2]
                if type(formats) in (list, tuple):
                    category = "EMPTY" if len(formats) == 0 else "NONEMPTY"
            self.format_family = category
            return value

        def marker(original, attribute):
            @functools.wraps(original)
            def bound(instance, *args, **kwargs):
                current = (self.no_formats_calls if attribute == "no_formats_calls"
                           else self.drm_calls)
                require(current < MAX_TERMINAL_CALLS, "terminal hook count exceeded")
                setattr(self, attribute, current + 1)
                return original(instance, *args, **kwargs)
            return bound

        # No persistent class patch if setup fails.
        try:
            setattr(cls, "_list_formats", checked_formats)
            setattr(cls, "raise_no_formats", marker(inherited["raise_no_formats"],
                                                    "no_formats_calls"))
            setattr(cls, "report_drm", marker(inherited["report_drm"], "drm_calls"))
            yield
        finally:
            setattr(cls, "_list_formats", original_direct)
            for name in TERMINAL_METHODS:
                if name in vars(cls):
                    delattr(cls, name)

    def document(self) -> dict[str, Any]:
        return {
            "formatPopulation": self.format_family,
            "formatObservations": self.format_observations,
            "raiseNoFormatsCalls": self.no_formats_calls,
            "reportDrmCalls": self.drm_calls,
        }


def envelope(step: Mapping[str, Any], d4_reference: Mapping[str, Any],
             terminal: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "schemaVersion": 1, "protocol": PROTOCOL,
        "caseClass": step["caseClass"], "lane": step["lane"],
        "referenceStepOrdinal": step["ordinal"],
        "reference": dict(d4_reference), "terminal": dict(terminal),
    }


def validate_document(doc: Any, registry: Mapping[str, Any],
                      d3_plan: Mapping[str, Any], step: Mapping[str, Any],
                      sha: str, runner: str) -> None:
    require(type(doc) is dict and set(doc) == {
        "schemaVersion", "protocol", "caseClass", "lane",
        "referenceStepOrdinal", "reference", "terminal"
    } and type(doc["schemaVersion"]) is int and doc["schemaVersion"] == 1
        and doc["protocol"] == PROTOCOL and doc["caseClass"] == step["caseClass"]
        and doc["lane"] == step["lane"] and doc["referenceStepOrdinal"] == step["ordinal"],
        "D5 identity or extra fields")
    d4.validate_document(doc["reference"], registry, d3_plan, step, sha, runner)
    t = doc["terminal"]
    require(type(t) is dict and set(t) == {
        "formatPopulation", "formatObservations",
        "raiseNoFormatsCalls", "reportDrmCalls"
    } and type(t["formatPopulation"]) is str
        and t["formatPopulation"] in FORMAT_FAMILIES
        and all(type(t[k]) is int and 0 <= t[k] <= MAX_TERMINAL_CALLS
                for k in ("formatObservations", "raiseNoFormatsCalls", "reportDrmCalls")),
        "forged terminal telemetry")
    stage = doc["reference"]["stageTrace"]
    returned = sum(1 for x in stage if x["stage"] == "FORMAT_SELECTION"
                   and x["outcome"] == "RETURN")
    require(returned == t["formatObservations"],
            "format observation contradicts D4 phase trace")
    require(t["formatObservations"] > 0 or t["formatPopulation"] == "UNKNOWN",
            "format status without a completed _list_formats call")
    require(t["formatObservations"] <= 1 or t["formatPopulation"] == "UNKNOWN",
            "ambiguous multiple format results")
    require(t["reportDrmCalls"] <= t["raiseNoFormatsCalls"],
            "report_drm must delegate to raise_no_formats in pinned version")
    require(t["reportDrmCalls"] == 0 or t["formatPopulation"] == "EMPTY",
            "DRM marker incompatible with non-empty formats")


def _filename(step: Mapping[str, Any]) -> str:
    return "d5-" + d4._filename(step)


def summary(records: list[dict[str, Any]], sha: str, runner: str) -> dict[str, Any]:
    observations = []
    for doc in records:
        t = doc["terminal"]
        inner = doc["reference"]["reference"]["observation"]
        observations.append({
            "caseClass": doc["caseClass"], "lane": doc["lane"],
            "formatPopulation": t["formatPopulation"],
            "raiseNoFormatsCalls": t["raiseNoFormatsCalls"],
            "reportDrmCalls": t["reportDrmCalls"],
            "metadataRequests": inner["metadataRequestsUsed"],
            "extractionCompleted": inner["extractionCompleted"],
        })
    return {
        "schemaVersion": 1, "planId": PLAN_ID, "protocol": PROTOCOL,
        "gitCommit": sha, "runnerImage": runner, "oracle": dict(d3.ORACLES["B"]),
        "controlCount": len(records), "observations": observations,
        "totalMetadataRequests": sum(o["metadataRequests"] for o in observations),
        "totalMediaProbes": 0, "globalDecision": "NOT_MADE_M3_A2",
    }


def run_d5(*, registry, d4_plan, d3_plan, plan, ids, git_commit, runner_image,
           output: pathlib.Path, source_file=None, interpreter=None, observer=None):
    require(plan == build_plan(registry, d4_plan), "wrong D5 plan")
    d3.validate_inputs(registry, ids)  # Before any provider request.
    require(type(git_commit) is str and re.fullmatch(r"[0-9a-f]{40}", git_commit)
            and type(runner_image) is str
            and re.fullmatch(r"[A-Za-z0-9._@-]{1,100}", runner_image),
            "source/runner provenance invalid")
    require(not output.exists() or not any(output.iterdir()), "output directory not empty")
    if observer is None:
        require(source_file is not None and source_file.is_file()
                and interpreter is not None and interpreter.is_file(),
                "pinned child/fixture absent")
    output.mkdir(parents=True, exist_ok=True)
    records = []
    for step in plan["steps"]:
        path = output / _filename(step)
        if observer is not None:
            doc = observer(step)
            path.write_bytes(d3._serialize(doc))
        else:
            cmd = [str(interpreter), str(pathlib.Path(__file__).resolve()),
                   "observe-one", "--source-file", str(source_file),
                   "--git-commit", git_commit, "--runner-image", runner_image,
                   "--ordinal", str(step["ordinal"]), "--output-file", str(path)]
            try:
                run = subprocess.run(cmd, capture_output=True, check=False, timeout=180)
            except (OSError, subprocess.TimeoutExpired):
                raise ValueError("D5 child did not complete safely") from None
            require(run.returncode == 0, "D5 child failed closed; no portable evidence")
            doc = json.loads(path.read_text(encoding="utf-8"))
        validate_document(doc, registry, d3_plan, step, git_commit, runner_image)
        records.append(doc)
        require(sum(x["reference"]["reference"]["observation"]["metadataRequestsUsed"]
                    for x in records) <= 128, "aggregate request count exceeded")
    result = summary(records, git_commit, runner_image)
    (output / "summary-v1.json").write_bytes(d3._serialize(result))
    (output / "manifest-v1.json").write_bytes(d3._serialize({
        "schemaVersion": 1, "planId": PLAN_ID,
        "files": [{"path": p.name, "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
                  for p in sorted(output.glob("*.json"))],
    }))
    d3._check_privacy(output, ids)
    return result


def verify_bundle(*, registry, d4_plan, d3_plan, plan, ids, git_commit,
                  runner_image, root: pathlib.Path):
    require(plan == build_plan(registry, d4_plan), "D5 plan drift")
    expected = {_filename(s) for s in plan["steps"]} | {"summary-v1.json"}
    require({p.name for p in root.iterdir()} == expected | {"manifest-v1.json"},
            "unexpected evidence set")
    man = json.loads((root / "manifest-v1.json").read_text(encoding="utf-8"))
    entries = man.get("files")
    require(type(man) is dict and set(man) == {"schemaVersion", "planId", "files"}
            and type(man["schemaVersion"]) is int and man["schemaVersion"] == 1
            and man["planId"] == PLAN_ID and type(entries) is list
            and len(entries) == 5 and all(type(e) is dict
                and set(e) == {"path", "sha256"} for e in entries)
            and {x["path"] for x in entries} == expected,
            "manifest invalid")
    for entry in entries:
        require(type(entry["sha256"]) is str
                and re.fullmatch("[0-9a-f]{64}", entry["sha256"])
                and hashlib.sha256((root / entry["path"]).read_bytes()).hexdigest()
                == entry["sha256"], "digest mismatch")
    records = []
    for step in plan["steps"]:
        doc = json.loads((root / _filename(step)).read_text(encoding="utf-8"))
        validate_document(doc, registry, d3_plan, step, git_commit, runner_image)
        records.append(doc)
    want = summary(records, git_commit, runner_image)
    actual = json.loads((root / "summary-v1.json").read_text(encoding="utf-8"))
    require(actual == want and want["totalMetadataRequests"] <= 128,
            "forged aggregate summary")
    d3._check_privacy(root, ids)
    return want


DEFAULTS = {
    "registry": pathlib.Path(".work/m3-a2/case-registry-v1.json"),
    "c1-plan": pathlib.Path(".work/m3-a2/control-plan-v1.json"),
    "d0-plan": pathlib.Path(".work/m3-a2/c2-d0-plan-v1.json"),
    "d1-plan": pathlib.Path(".work/m3-a2/c2-d1-plan-v1.json"),
    "d2-plan": pathlib.Path(".work/m3-a2/c2-d2-plan-v1.json"),
    "d3-plan": pathlib.Path(".work/m3-a2/c2-d3-plan-v1.json"),
    "d4-plan": pathlib.Path(".work/m3-a2/c2-d4-plan-v1.json"),
    "plan": pathlib.Path(".work/m3-a2/c2-d5-plan-v1.json"),
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("validate-plan", "validate-hooks",
                                             "run", "verify", "observe-one"))
    for key, default in DEFAULTS.items():
        parser.add_argument("--" + key, default=default, type=pathlib.Path)
    for key in ("source-file", "output-dir", "output-file", "interpreter"):
        parser.add_argument("--" + key, type=pathlib.Path)
    parser.add_argument("--git-commit")
    parser.add_argument("--runner-image")
    parser.add_argument("--ordinal", type=int)
    args = parser.parse_args()
    try:
        registry = load_registry(args.registry)
        ancestors = [json.loads(getattr(args, key.replace("-", "_")).read_text())
                     for key in ("c1-plan", "d0-plan", "d1-plan", "d2-plan",
                                 "d3-plan", "d4-plan")]
        plan = json.loads(args.plan.read_text())
        validate_plan(plan, registry, ancestors)
        if args.command == "validate-plan":
            print("D5 frozen four B controls: terminal telemetry; no provider I/O")
            return 0
        if args.command in ("validate-hooks", "observe-one"):
            import yt_dlp
            import yt_dlp.version
            from yt_dlp.extractor.youtube import YoutubeIE
            from yt_dlp import utils
            from yt_dlp.networking.common import Response
            from yt_dlp.networking import exceptions as network_types
            oracle = d3.ORACLES["B"]
            require(yt_dlp.version.__version__ == oracle["version"]
                    and yt_dlp.version.RELEASE_GIT_HEAD == oracle["revision"],
                    "installed oracle version/revision not pinned")
            if args.command == "validate-hooks":
                with d4.StageObserver().installed(YoutubeIE):
                    with TerminalProbe().installed(YoutubeIE):
                        pass
                print("D5 all terminal and D4 stage hooks validated offline")
                return 0
        require(args.source_file is not None and args.git_commit is not None
                and args.runner_image is not None, "missing provenance or source")
        ids = derive_case_inputs_from_source(
            registry, args.source_file.read_text(encoding="utf-8"))
        d3.validate_inputs(registry, ids)
        d3_plan, d4_plan = ancestors[-2:]
        if args.command == "observe-one":
            require(type(args.ordinal) is int and args.output_file is not None
                    and not args.output_file.exists(), "invalid observation destination")
            step = next((s for s in plan["steps"] if s["ordinal"] == args.ordinal), None)
            require(step is not None, "unapproved ordinal")
            cell = next(c for c in d3_plan["cells"] if c["caseClass"] == step["caseClass"]
                        and c["lane"] == step["lane"])
            api = types.SimpleNamespace(YoutubeDL=yt_dlp.YoutubeDL,
                                        networking_exceptions=network_types,
                                        networking_response_type=Response, utils=utils)
            stages, terminal = d4.StageObserver(), TerminalProbe()
            with stages.installed(YoutubeIE):
                with terminal.installed(YoutubeIE):
                    ref = d3.observe_versioned_cell(
                        registry=registry, cell=cell, arm="B",
                        raw_id=ids[step["caseClass"]], git_commit=args.git_commit,
                        runner_image=args.runner_image,
                        oracle_loader=lambda: (api, oracle["version"], oracle["revision"]))
            d4_doc = d4.envelope(step, d3.observation_envelope(step, ref), stages.events)
            doc = envelope(step, d4_doc, terminal.document())
            validate_document(doc, registry, d3_plan, step,
                              args.git_commit, args.runner_image)
            args.output_file.parent.mkdir(parents=True, exist_ok=True)
            args.output_file.write_bytes(d3._serialize(doc))
            return 0
        require(args.output_dir is not None, "missing output")
        if args.command == "run":
            result = run_d5(registry=registry, d4_plan=d4_plan, d3_plan=d3_plan,
                            plan=plan, ids=ids, git_commit=args.git_commit,
                            runner_image=args.runner_image, output=args.output_dir,
                            source_file=args.source_file, interpreter=args.interpreter)
        else:
            result = verify_bundle(registry=registry, d4_plan=d4_plan,
                                   d3_plan=d3_plan, plan=plan, ids=ids,
                                   git_commit=args.git_commit,
                                   runner_image=args.runner_image, root=args.output_dir)
        print(f"D5 verified {result['controlCount']} controls, "
              f"{result['totalMetadataRequests']} metadata attempts, no media")
        return 0
    except Exception:
        print("D5 failed closed; no provider-controlled details", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
