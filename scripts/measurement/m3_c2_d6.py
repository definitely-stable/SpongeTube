"""D6: closed structural format-supply observations on pinned yt-dlp.

Research only: no JS/EJS, PO-token provider, credentials, media probes, retries,
proxy changes, raw provider response fields or URLs in retained evidence.
The observation hooks only inspect built-in dict/list shapes; they do not
consume provider iterators except in the original extractor's own iteration.
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
from typing import Any, Mapping

import m3_c2_d3 as d3
import m3_c2_d4 as d4
import m3_c2_d5 as d5
from m3_batch import derive_case_inputs_from_source
from m3_matrix import load_registry

PROTOCOL = "m3-a2-c2-d6-format-supply-v1"
PLAN_ID = "m3-a2-c2-d6-format-supply-2026-11-01-v1"
METHODS = ("_extract_player_response", "_extract_player_responses",
           "_list_formats", "_extract_formats_and_subtitles",
           "_report_pot_format_skipped")
SHAPES = frozenset({"ABSENT", "NULL", "EMPTY", "ONE", "TWO_TO_EIGHT",
                    "NINE_TO_64", "OVER_64", "UNKNOWN"})
MAX_RESPONSES = 8
MAX_FORMAT_YIELDS = 256
MAX_POT_REPORTS = 32
REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]


def require(condition: bool, why: str) -> None:
    if not condition:
        raise ValueError(why)


def build_plan(registry: Mapping[str, Any], d5_plan: Mapping[str, Any]) -> dict[str, Any]:
    require(d5_plan["planId"] == d5.PLAN_ID
            and d5_plan["registryId"] == registry["registryId"]
            and d5_plan["oracle"] == d3.ORACLES["B"]
            and d5_plan["mode"] == {
                "jsRuntime": "DISABLED", "remoteComponents": "DISABLED",
                "poTokenProvider": "ABSENT", "credentialsUsed": False}
            and d5_plan["maxTotalMetadataRequests"] == 128
            and d5_plan["maxTotalMediaProbes"] == 0,
            "D5 ancestor drift")
    return {
        "schemaVersion": 1, "planId": PLAN_ID, "protocol": PROTOCOL,
        "registryId": registry["registryId"], "d5PlanId": d5.PLAN_ID,
        "oracle": dict(d3.ORACLES["B"]), "mode": dict(d5_plan["mode"]),
        "steps": [dict(s) for s in d5_plan["steps"]],
        "observedMethods": list(METHODS),
        "shapeVocabulary": sorted(SHAPES),
        "maxResponsesPerStage": MAX_RESPONSES,
        "maxFormatYields": MAX_FORMAT_YIELDS,
        "maxPoSkipReports": MAX_POT_REPORTS,
        "maxMetadataRequestsPerObservation": 32,
        "maxTotalMetadataRequests": 128, "maxTotalMediaProbes": 0,
        "globalDecision": "NOT_MADE_M3_A2",
    }


def validate_plan(plan: Any, registry: Mapping[str, Any], ancestors: list) -> None:
    require(len(ancestors) == 7, "C1/D0-D5 ancestors required")
    d5.validate_plan(ancestors[-1], registry, ancestors[:-1])
    require(type(plan) is dict and plan == build_plan(registry, ancestors[-1]),
            "D6 predeclared plan drift")


def bucket(value: Any) -> str:
    """Never traverse values or stringify response objects."""
    if type(value) is not list:
        return "UNKNOWN"
    n = len(value)
    if n == 0:
        return "EMPTY"
    if n == 1:
        return "ONE"
    if n <= 8:
        return "TWO_TO_EIGHT"
    if n <= 64:
        return "NINE_TO_64"
    return "OVER_64"


def shape(value: Any) -> dict[str, str]:
    if type(value) is not dict:
        return {"streamingData": "UNKNOWN", "formats": "UNKNOWN",
                "adaptiveFormats": "UNKNOWN"}
    if "streamingData" not in value:
        return {"streamingData": "ABSENT", "formats": "ABSENT",
                "adaptiveFormats": "ABSENT"}
    sd = value["streamingData"]
    if sd is None:
        return {"streamingData": "NULL", "formats": "ABSENT",
                "adaptiveFormats": "ABSENT"}
    if type(sd) is not dict:
        return {"streamingData": "UNKNOWN", "formats": "UNKNOWN",
                "adaptiveFormats": "UNKNOWN"}
    def family(key):
        if key not in sd:
            return "ABSENT"
        if sd[key] is None:
            return "NULL"
        return bucket(sd[key])
    return {"streamingData": "EMPTY" if len(sd) == 0 else "ONE",
            "formats": family("formats"), "adaptiveFormats": family("adaptiveFormats")}


class ShapeProbe:
    """Side-effect-free typed snapshots with strict bounded capacity.

    PRE_NORMALIZATION observes _extract_player_response's return, not the
    original wire JSON. Webpage initial_pr is unobserved in this category.
    POST_NORMALIZATION may contain a synthetic streamingData field added
    by yt-dlp and is NEVER labelled as source-supplied.
    """
    def __init__(self):
        self.pre = []
        self.post = []
        self.selected = []
        self.yields = 0
        self.po_reports = 0
        self.selection_calls = 0

    @contextmanager
    def installed(self, cls: type):
        require(type(cls) is type and cls.__name__ == "YoutubeIE"
                and cls.__module__ == "yt_dlp.extractor.youtube",
                "wrong pinned YoutubeIE class")
        originals = {name: vars(cls).get(name) for name in METHODS}
        require(all(type(fn) is types.FunctionType for fn in originals.values()),
                "pinned methods missing or not direct")
        def observe_list(items, destination):
            require(type(items) is list and len(items) <= MAX_RESPONSES
                    and len(destination) == 0, "response list shape or count drift")
            destination.extend(shape(item) for item in items)
        @functools.wraps(originals["_extract_player_response"])
        def one(instance, *args, **kwargs):
            value = originals["_extract_player_response"](instance, *args, **kwargs)
            require(len(self.pre) < MAX_RESPONSES, "pre-normalization ceiling")
            self.pre.append(shape(value))
            return value
        @functools.wraps(originals["_extract_player_responses"])
        def many(instance, *args, **kwargs):
            value = originals["_extract_player_responses"](instance, *args, **kwargs)
            require(type(value) is tuple and len(value) == 2,
                    "player response result shape drift")
            observe_list(value[0], self.post)
            return value
        @functools.wraps(originals["_list_formats"])
        def list_formats(instance, *args, **kwargs):
            require(self.selection_calls == 0 and len(args) >= 4,
                    "format-selection call shape drift")
            self.selection_calls += 1
            observe_list(args[3], self.selected)
            return originals["_list_formats"](instance, *args, **kwargs)
        @functools.wraps(originals["_extract_formats_and_subtitles"])
        def formats(instance, *args, **kwargs):
            # A generator wrapper preserves the original consumer and order:
            # no lookahead or second pass over the upstream generator.
            for item in originals["_extract_formats_and_subtitles"](instance, *args, **kwargs):
                if type(item) is dict and "format_id" in item:
                    require(self.yields < MAX_FORMAT_YIELDS, "yield ceiling")
                    self.yields += 1
                yield item
        @functools.wraps(originals["_report_pot_format_skipped"])
        def pot(instance, *args, **kwargs):
            require(self.po_reports < MAX_POT_REPORTS, "PO skip-report ceiling")
            self.po_reports += 1
            return originals["_report_pot_format_skipped"](instance, *args, **kwargs)
        wrappers = {
            "_extract_player_response": one,
            "_extract_player_responses": many,
            "_list_formats": list_formats,
            "_extract_formats_and_subtitles": formats,
            "_report_pot_format_skipped": pot,
        }
        try:
            for name, wrapper in wrappers.items():
                setattr(cls, name, wrapper)
            yield
        finally:
            for name, original in originals.items():
                setattr(cls, name, original)

    def document(self) -> dict[str, Any]:
        return {
            "preNormalizationApiResponses": list(self.pre),
            "postNormalizationResponses": list(self.post),
            "selectedInputResponses": list(self.selected),
            "selectionCalls": self.selection_calls,
            "emittedFormatMappings": self.yields,
            "poTokenSkipReportCalls": self.po_reports,
        }


def envelope(step: Mapping[str, Any], d5_doc: Mapping[str, Any],
             structural: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "schemaVersion": 1, "protocol": PROTOCOL,
        "referenceStepOrdinal": step["ordinal"], "caseClass": step["caseClass"],
        "lane": step["lane"], "reference": dict(d5_doc),
        "structural": dict(structural),
    }


def validate_document(doc: Any, registry: Mapping[str, Any],
                      d3_plan: Mapping[str, Any], step: Mapping[str, Any],
                      sha: str, runner: str) -> None:
    require(type(doc) is dict and set(doc) == {
        "schemaVersion", "protocol", "referenceStepOrdinal", "caseClass",
        "lane", "reference", "structural"
    } and type(doc["schemaVersion"]) is int and doc["schemaVersion"] == 1
        and doc["protocol"] == PROTOCOL and doc["referenceStepOrdinal"] == step["ordinal"]
        and doc["caseClass"] == step["caseClass"] and doc["lane"] == step["lane"],
        "D6 forbidden document fields or identity drift")
    d5.validate_document(doc["reference"], registry, d3_plan, step, sha, runner)
    s = doc["structural"]
    require(type(s) is dict and set(s) == {
        "preNormalizationApiResponses", "postNormalizationResponses",
        "selectedInputResponses", "selectionCalls",
        "emittedFormatMappings", "poTokenSkipReportCalls",
    }, "untrusted D6 structural payload")
    for key in ("preNormalizationApiResponses", "postNormalizationResponses",
                "selectedInputResponses"):
        samples = s[key]
        require(type(samples) is list and len(samples) <= MAX_RESPONSES,
                "response count exceeds bound")
        for sample in samples:
            require(type(sample) is dict and set(sample) == {
                "streamingData", "formats", "adaptiveFormats"}
                and all(type(v) is str and v in SHAPES for v in sample.values()),
                "unrecognized shape fields or categories")
            if sample["streamingData"] in ("ABSENT", "NULL"):
                require(sample["formats"] == "ABSENT"
                        and sample["adaptiveFormats"] == "ABSENT",
                        "impossible absent streaming-data shape")
    require(type(s["selectionCalls"]) is int
            and s["selectionCalls"] in (0, 1)
            and type(s["emittedFormatMappings"]) is int
            and 0 <= s["emittedFormatMappings"] <= MAX_FORMAT_YIELDS
            and type(s["poTokenSkipReportCalls"]) is int
            and 0 <= s["poTokenSkipReportCalls"] <= MAX_POT_REPORTS,
            "unbounded or forged D6 counters")
    terminal = doc["reference"]["terminal"]
    require(s["selectionCalls"] == terminal["formatObservations"]
            and (s["selectionCalls"] == 0
                 or len(s["selectedInputResponses"]) == len(s["postNormalizationResponses"])),
            "D6 vs D5 format selection contradiction")
    if terminal["formatPopulation"] == "EMPTY":
        require(s["emittedFormatMappings"] == 0, "empty D5 but emitted formats")
    if s["selectionCalls"] == 0:
        require(s["emittedFormatMappings"] == 0
                and not s["selectedInputResponses"], "unobserved selection cannot emit")



def selected_population(samples: list[Mapping[str, str]]) -> str:
    """Only selected extractor input; never infer wire/source absence."""
    if not samples:
        return "UNKNOWN"
    categories = [sample[key] for sample in samples
                  for key in ("formats", "adaptiveFormats")]
    if any(x in ("ONE", "TWO_TO_EIGHT", "NINE_TO_64", "OVER_64")
           for x in categories):
        return "PRESENT"
    if any(x == "UNKNOWN" for x in categories):
        return "UNKNOWN"
    return "NO_CANDIDATES_IN_SELECTED_INPUT"


def summary(records: list[dict[str, Any]], sha: str, runner: str) -> dict[str, Any]:
    entries = []
    for x in records:
        obs = x["reference"]["reference"]["reference"]["observation"]
        s = x["structural"]
        entries.append({
            "caseClass": x["caseClass"], "lane": x["lane"],
            "preApiResponseCount": len(s["preNormalizationApiResponses"]),
            "postResponseCount": len(s["postNormalizationResponses"]),
            "preApiResponseObservation": ("PRE_NORMALIZATION_API_RETURN"
                                          if s["preNormalizationApiResponses"]
                                          else "UNOBSERVED"),
            "selectedInputCandidateState":
                selected_population(s["selectedInputResponses"]),
            "emittedFormatMappings": s["emittedFormatMappings"],
            "poTokenSkipReportCalls": s["poTokenSkipReportCalls"],
            "d5FormatPopulation": x["reference"]["terminal"]["formatPopulation"],
            "metadataRequests": obs["metadataRequestsUsed"],
        })
    return {
        "schemaVersion": 1, "planId": PLAN_ID, "protocol": PROTOCOL,
        "gitCommit": sha, "runnerImage": runner, "oracle": dict(d3.ORACLES["B"]),
        "controlCount": len(records), "observations": entries,
        "totalMetadataRequests": sum(e["metadataRequests"] for e in entries),
        "totalMediaProbes": 0, "globalDecision": "NOT_MADE_M3_A2",
    }


def filename(step: Mapping[str, Any]) -> str:
    return "d6-" + d5._filename(step)


def run_d6(*, registry, d5_plan, d3_plan, plan, ids, git_commit, runner_image,
           output: pathlib.Path, source_file=None, interpreter=None, observer=None):
    require(plan == build_plan(registry, d5_plan), "D6 plan drift")
    d3.validate_inputs(registry, ids)
    require(type(git_commit) is str and re.fullmatch(r"[0-9a-f]{40}", git_commit)
            and type(runner_image) is str
            and re.fullmatch(r"[A-Za-z0-9._@-]{1,100}", runner_image),
            "invalid commit or runner provenance")
    require(not output.exists() or not any(output.iterdir()), "output not empty")
    if observer is None:
        require(source_file is not None and source_file.is_file()
                and interpreter is not None and interpreter.is_file(),
                "reference source/interpreter missing")
    output.mkdir(parents=True, exist_ok=True)
    records = []
    for step in plan["steps"]:
        path = output / filename(step)
        if observer is not None:
            doc = observer(step)
            path.write_bytes(d3._serialize(doc))
        else:
            command = [str(interpreter), str(pathlib.Path(__file__).resolve()),
                       "observe-one", "--source-file", str(source_file),
                       "--git-commit", git_commit, "--runner-image", runner_image,
                       "--ordinal", str(step["ordinal"]), "--output-file", str(path)]
            try:
                proc = subprocess.run(command, capture_output=True, check=False, timeout=180)
            except (OSError, subprocess.TimeoutExpired):
                raise ValueError("D6 child failed closed") from None
            require(proc.returncode == 0, "D6 child failed; no evidence")
            doc = json.loads(path.read_text())
        validate_document(doc, registry, d3_plan, step, git_commit, runner_image)
        records.append(doc)
        require(sum(x["reference"]["reference"]["reference"]["observation"]
                    ["metadataRequestsUsed"] for x in records) <= 128,
                "aggregate metadata budget exceeded")
    result = summary(records, git_commit, runner_image)
    (output / "summary-v1.json").write_bytes(d3._serialize(result))
    (output / "manifest-v1.json").write_bytes(d3._serialize({
        "schemaVersion": 1, "planId": PLAN_ID,
        "files": [{"path": p.name, "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
                  for p in sorted(output.glob("*.json"))],
    }))
    d3._check_privacy(output, ids)
    return result


def verify_bundle(*, registry, d5_plan, d3_plan, plan, ids, git_commit,
                  runner_image, root: pathlib.Path):
    require(plan == build_plan(registry, d5_plan), "D6 plan drift")
    expected = {filename(s) for s in plan["steps"]} | {"summary-v1.json"}
    require(root.is_dir() and {p.name for p in root.iterdir()}
            == expected | {"manifest-v1.json"}, "unexpected D6 bundle members")
    man = json.loads((root / "manifest-v1.json").read_text())
    entries = man.get("files") if type(man) is dict else None
    require(type(man) is dict and set(man) == {"schemaVersion", "planId", "files"}
            and type(man["schemaVersion"]) is int and man["schemaVersion"] == 1
            and man["planId"] == PLAN_ID and type(entries) is list
            and len(entries) == 5 and all(type(e) is dict
            and set(e) == {"path", "sha256"} for e in entries)
            and {e["path"] for e in entries} == expected, "invalid D6 manifest")
    for e in entries:
        require(type(e["sha256"]) is str
                and re.fullmatch(r"[0-9a-f]{64}", e["sha256"])
                and hashlib.sha256((root / e["path"]).read_bytes()).hexdigest()
                == e["sha256"], "digest mismatch")
    records = []
    for step in plan["steps"]:
        doc = json.loads((root / filename(step)).read_text())
        validate_document(doc, registry, d3_plan, step, git_commit, runner_image)
        records.append(doc)
    result = summary(records, git_commit, runner_image)
    require(result["totalMetadataRequests"] <= 128
            and json.loads((root / "summary-v1.json").read_text()) == result,
            "summary forgery or budget exceeded")
    d3._check_privacy(root, ids)
    return result


DEFAULTS = {**d5.DEFAULTS,
            "d5-plan": pathlib.Path(".work/m3-a2/c2-d5-plan-v1.json"),
            "plan": pathlib.Path(".work/m3-a2/c2-d6-plan-v1.json")}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("validate-plan", "validate-hooks",
                                              "run", "verify", "observe-one"))
    for key, value in DEFAULTS.items():
        parser.add_argument("--" + key, type=pathlib.Path, default=value)
    for name in ("source-file", "interpreter", "output-dir", "output-file"):
        parser.add_argument("--" + name, type=pathlib.Path)
    parser.add_argument("--git-commit")
    parser.add_argument("--runner-image")
    parser.add_argument("--ordinal", type=int)
    args = parser.parse_args()
    try:
        registry = load_registry(args.registry)
        ancestors = [json.loads(getattr(args, key.replace("-", "_")).read_text())
                     for key in ("c1-plan", "d0-plan", "d1-plan", "d2-plan",
                                 "d3-plan", "d4-plan", "d5-plan")]
        plan = json.loads(args.plan.read_text())
        validate_plan(plan, registry, ancestors)
        if args.command == "validate-plan":
            print("D6 exact four controls, only offline plan validation")
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
                    "pinned yt-dlp package/revision mismatch")
            if args.command == "validate-hooks":
                cls = YoutubeIE
                with d4.StageObserver().installed(cls):
                    with d5.TerminalProbe().installed(cls):
                        with ShapeProbe().installed(cls):
                            pass
                print("D6 exact five hooks and D4/D5 nested restoration PASS; 0 network")
                return 0
        require(args.source_file is not None and args.git_commit is not None
                and args.runner_image is not None, "provenance/source missing")
        ids = derive_case_inputs_from_source(
            registry, args.source_file.read_text(encoding="utf-8"))
        d3.validate_inputs(registry, ids)
        d3_plan, d4_plan = ancestors[4:6]
        d5_plan = ancestors[-1]
        if args.command == "observe-one":
            require(type(args.ordinal) is int and args.output_file is not None
                    and not args.output_file.exists(), "unknown observation destination")
            step = next((s for s in plan["steps"] if s["ordinal"] == args.ordinal), None)
            require(step is not None, "unplanned ordinal")
            cell = next(c for c in d3_plan["cells"] if c["caseClass"] == step["caseClass"]
                        and c["lane"] == step["lane"])
            api = types.SimpleNamespace(
                YoutubeDL=yt_dlp.YoutubeDL, networking_exceptions=network_types,
                networking_response_type=Response, utils=utils)
            stage, term, structure = d4.StageObserver(), d5.TerminalProbe(), ShapeProbe()
            with stage.installed(YoutubeIE):
                with term.installed(YoutubeIE):
                    with structure.installed(YoutubeIE):
                        obs = d3.observe_versioned_cell(
                            registry=registry, cell=cell, arm="B",
                            raw_id=ids[step["caseClass"]], git_commit=args.git_commit,
                            runner_image=args.runner_image,
                            oracle_loader=lambda: (api, d3.ORACLES["B"]["version"],
                                                   d3.ORACLES["B"]["revision"]))
            d4_doc = d4.envelope(step, d3.observation_envelope(step, obs), stage.events)
            d5_doc = d5.envelope(step, d4_doc, term.document())
            doc = envelope(step, d5_doc, structure.document())
            validate_document(doc, registry, d3_plan, step,
                              args.git_commit, args.runner_image)
            args.output_file.parent.mkdir(parents=True, exist_ok=True)
            args.output_file.write_bytes(d3._serialize(doc))
            return 0
        require(args.output_dir is not None, "output missing")
        if args.command == "run":
            result = run_d6(
                registry=registry, d5_plan=d5_plan, d3_plan=d3_plan, plan=plan,
                ids=ids, git_commit=args.git_commit, runner_image=args.runner_image,
                output=args.output_dir, source_file=args.source_file,
                interpreter=args.interpreter)
        else:
            result = verify_bundle(
                registry=registry, d5_plan=d5_plan, d3_plan=d3_plan, plan=plan,
                ids=ids, git_commit=args.git_commit, runner_image=args.runner_image,
                root=args.output_dir)
        print(f"D6 verified {result['controlCount']} controls; "
              "globalDecision=NOT_MADE_M3_A2")
        return 0
    except Exception:
        print("D6 failed closed; no provider-controlled details", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
