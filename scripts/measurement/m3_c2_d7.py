"""D7: bounded parsed embedded/API/selected manifest provenance, research only.

Instrument the pinned extractor without reading/writing response values. Never
record URLs, cookies, identifiers, signatures, reasons, status text or exceptions.
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
import m3_c2_d6 as d6
from m3_batch import derive_case_inputs_from_source
from m3_matrix import load_registry

PROTOCOL = "m3-a2-c2-d7-manifest-provenance-v1"
PLAN_ID = "m3-a2-c2-d7-manifest-provenance-2026-11-01-v1"
MAX = 8
FIELDS = frozenset({"streamingData", "formats", "adaptiveFormats", "hls", "dash"})
PRESENCE = frozenset({"ABSENT", "NULL", "EMPTY", "STRING_PRESENT", "OTHER", "UNKNOWN"})
DEFAULTS = {**d6.DEFAULTS,
            "d6-plan": pathlib.Path(".work/m3-a2/c2-d6-plan-v1.json"),
            "plan": pathlib.Path(".work/m3-a2/c2-d7-plan-v1.json")}


def require(ok: bool, why: str) -> None:
    if not ok:
        raise ValueError(why)


def build_plan(registry: Mapping[str, Any], d6_plan: Mapping[str, Any]) -> dict:
    require(d6_plan["planId"] == d6.PLAN_ID
            and d6_plan["registryId"] == registry["registryId"]
            and d6_plan["steps"] == d6.build_plan(
                registry, json.loads((d6.REPO_ROOT / ".work/m3-a2/c2-d5-plan-v1.json").read_text()))["steps"]
            and d6_plan["maxTotalMetadataRequests"] == 128
            and d6_plan["maxTotalMediaProbes"] == 0
            and d6_plan["mode"]["jsRuntime"] == "DISABLED"
            and d6_plan["mode"]["poTokenProvider"] == "ABSENT",
            "D7 D6 ancestor drift")
    return {
        "schemaVersion": 1, "planId": PLAN_ID, "protocol": PROTOCOL,
        "registryId": registry["registryId"], "d6PlanId": d6.PLAN_ID,
        "oracle": dict(d6_plan["oracle"]), "mode": dict(d6_plan["mode"]),
        "steps": [dict(s) for s in d6_plan["steps"]],
        "observedMethods": [
            "_search_json", "_extract_player_response",
            "_extract_player_responses", "_list_formats"],
        "maxResponsesPerStage": MAX, "maxMetadataRequestsPerObservation": 32,
        "maxTotalMetadataRequests": 128, "maxTotalMediaProbes": 0,
        "globalDecision": "NOT_MADE_M3_A2",
    }


def validate_plan(plan: Any, registry: Mapping[str, Any], ancestors: list) -> None:
    require(len(ancestors) == 8, "C1 and D0-D6 ancestor plans required")
    d6.validate_plan(ancestors[-1], registry, ancestors[:-1])
    require(type(plan) is dict and plan == build_plan(registry, ancestors[-1]),
            "D7 frozen plan drift")


def field(sd: Any, key: str) -> str:
    if type(sd) is not dict:
        return "UNKNOWN"
    if key not in sd:
        return "ABSENT"
    val = sd[key]
    if val is None:
        return "NULL"
    if type(val) is not str:
        return "OTHER"
    return "EMPTY" if not val else "STRING_PRESENT"


def snapshot(response: Any) -> dict[str, str]:
    base = d6.shape(response)
    if type(response) is dict and "streamingData" in response:
        sd = response["streamingData"]
        if sd is None:
            manifest = {"hls": "ABSENT", "dash": "ABSENT"}
        elif type(sd) is dict:
            manifest = {"hls": field(sd, "hlsManifestUrl"),
                        "dash": field(sd, "dashManifestUrl")}
        else:
            manifest = {"hls": "UNKNOWN", "dash": "UNKNOWN"}
    elif type(response) is dict:
        manifest = {"hls": "ABSENT", "dash": "ABSENT"}
    else:
        manifest = {"hls": "UNKNOWN", "dash": "UNKNOWN"}
    return {**base, **manifest}


class ManifestProbe:
    """Observe parsed source initial response, API return, normalized and selected.

    _search_json is inherited from InfoExtractor and called with the exact
    _YT_INITIAL_PLAYER_RESPONSE_RE pattern on the pinned initial player path.
    Only its *returned Python object* is inspected; not raw webpage/network JSON.
    """
    def __init__(self):
        self.initial = []
        self.api = []
        self.normalized = []
        self.selected = []
        self.calls = 0

    @contextmanager
    def installed(self, cls):
        require(type(cls) is type and cls.__name__ == "YoutubeIE"
                and cls.__module__ == "yt_dlp.extractor.youtube"
                and "_search_json" not in vars(cls),
                "D7 class identity or inherited parser drift")
        parser = getattr(cls, "_search_json", None)
        originals = {name: vars(cls).get(name) for name in (
            "_extract_player_response", "_extract_player_responses", "_list_formats")}
        require(type(parser) is types.FunctionType
                and all(type(fn) is types.FunctionType for fn in originals.values())
                and type(getattr(cls, "_YT_INITIAL_PLAYER_RESPONSE_RE", None)) is str,
                "D7 pinned inherited/direct hooks missing")

        def add(items, destination):
            require(type(items) is list and len(items) <= MAX
                    and not destination, "D7 response shape/count drift")
            destination.extend(snapshot(x) for x in items)

        @functools.wraps(parser)
        def search(instance, *args, **kwargs):
            value = parser(instance, *args, **kwargs)
            if args and args[0] == instance._YT_INITIAL_PLAYER_RESPONSE_RE:
                require(not self.initial, "D7 repeated initial parser observation")
                self.initial.append(snapshot(value))
            return value

        @functools.wraps(originals["_extract_player_response"])
        def one(instance, *args, **kwargs):
            value = originals["_extract_player_response"](instance, *args, **kwargs)
            require(len(self.api) < MAX, "D7 API response ceiling")
            self.api.append(snapshot(value))
            return value

        @functools.wraps(originals["_extract_player_responses"])
        def many(instance, *args, **kwargs):
            value = originals["_extract_player_responses"](instance, *args, **kwargs)
            require(type(value) is tuple and len(value) == 2, "D7 normalized tuple drift")
            add(value[0], self.normalized)
            return value

        @functools.wraps(originals["_list_formats"])
        def select(instance, *args, **kwargs):
            require(self.calls == 0 and len(args) >= 4,
                    "D7 selected-input signature drift")
            self.calls += 1
            add(args[3], self.selected)
            return originals["_list_formats"](instance, *args, **kwargs)

        try:
            setattr(cls, "_search_json", search)
            setattr(cls, "_extract_player_response", one)
            setattr(cls, "_extract_player_responses", many)
            setattr(cls, "_list_formats", select)
            yield
        finally:
            for name, fn in originals.items():
                setattr(cls, name, fn)
            if "_search_json" in vars(cls):
                delattr(cls, "_search_json")

    def document(self):
        return {"parsedEmbeddedInitial": list(self.initial),
                "apiReturns": list(self.api),
                "postNormalization": list(self.normalized),
                "selectedInput": list(self.selected),
                "selectionCalls": self.calls}


def envelope(step, d6_doc, provenance):
    return {"schemaVersion": 1, "protocol": PROTOCOL,
            "referenceStepOrdinal": step["ordinal"], "caseClass": step["caseClass"],
            "lane": step["lane"], "reference": d6_doc, "provenance": provenance}


def validate_document(doc, registry, d3_plan, step, sha, runner):
    require(type(doc) is dict and set(doc) == {
        "schemaVersion", "protocol", "referenceStepOrdinal", "caseClass",
        "lane", "reference", "provenance"
    } and type(doc["schemaVersion"]) is int and doc["schemaVersion"] == 1
        and doc["protocol"] == PROTOCOL and doc["referenceStepOrdinal"] == step["ordinal"]
        and doc["caseClass"] == step["caseClass"] and doc["lane"] == step["lane"],
        "D7 document identity or unclosed fields")
    d6.validate_document(doc["reference"], registry, d3_plan, step, sha, runner)
    p = doc["provenance"]
    require(type(p) is dict and set(p) == {
        "parsedEmbeddedInitial", "apiReturns", "postNormalization",
        "selectedInput", "selectionCalls"}, "D7 unclosed provenance")
    for name in ("parsedEmbeddedInitial", "apiReturns", "postNormalization", "selectedInput"):
        items = p[name]
        require(type(items) is list and len(items) <= (1 if name == "parsedEmbeddedInitial" else MAX),
                "D7 observation bound")
        for s in items:
            require(type(s) is dict and set(s) == FIELDS
                    and all(type(s[k]) is str and s[k] in d6.SHAPES
                            for k in ("streamingData", "formats", "adaptiveFormats"))
                    and all(type(s[k]) is str and s[k] in PRESENCE for k in ("hls", "dash")),
                    "D7 unknown response field/value")
            if s["streamingData"] in ("ABSENT", "NULL"):
                require(s["formats"] == s["adaptiveFormats"] == "ABSENT"
                        and s["hls"] == s["dash"] == "ABSENT",
                        "D7 impossible absent manifests/formats")
    d = doc["reference"]["structural"]
    require(type(p["selectionCalls"]) is int
            and p["selectionCalls"] == d["selectionCalls"]
            and len(p["apiReturns"]) == len(d["preNormalizationApiResponses"])
            and len(p["postNormalization"]) == len(d["postNormalizationResponses"])
            and len(p["selectedInput"]) == len(d["selectedInputResponses"]),
            "D7 vs D6 stage/selection drift")
    for k, ancestor in (("apiReturns", "preNormalizationApiResponses"),
                        ("postNormalization", "postNormalizationResponses"),
                        ("selectedInput", "selectedInputResponses")):
        for a, b in zip(p[k], d[ancestor]):
            require(all(a[key] == b[key]
                        for key in ("streamingData", "formats", "adaptiveFormats")),
                    "D7 vs D6 candidate-shape contradiction")


def manifest_status(items):
    if not items:
        return "UNOBSERVED"
    values = [s[k] for s in items for k in ("hls", "dash")]
    if "STRING_PRESENT" in values:
        return "PRESENT"
    if "UNKNOWN" in values or "OTHER" in values:
        return "UNKNOWN"
    return "NO_MANIFEST_FIELDS_OBSERVED"


def summary(records, sha, runner):
    entries = []
    for doc in records:
        p = doc["provenance"]
        nested = doc["reference"]
        obs = nested["reference"]["reference"]["reference"]["observation"]
        entries.append({
            "caseClass": doc["caseClass"], "lane": doc["lane"],
            "parsedEmbeddedInitialObservation": (
                "PARSED_RETURN" if p["parsedEmbeddedInitial"] else "UNOBSERVED"),
            "parsedEmbeddedManifestState": manifest_status(p["parsedEmbeddedInitial"]),
            "apiReturnManifestState": manifest_status(p["apiReturns"]),
            "selectedInputManifestState": manifest_status(p["selectedInput"]),
            "selectedInputCandidateState":
                d6.selected_population(nested["structural"]["selectedInputResponses"]),
            "metadataRequests": obs["metadataRequestsUsed"],
        })
    return {"schemaVersion": 1, "planId": PLAN_ID, "protocol": PROTOCOL,
            "gitCommit": sha, "runnerImage": runner, "oracle": dict(d3.ORACLES["B"]),
            "controlCount": len(entries), "observations": entries,
            "totalMetadataRequests": sum(x["metadataRequests"] for x in entries),
            "totalMediaProbes": 0, "globalDecision": "NOT_MADE_M3_A2"}


def filename(step):
    return "d7-" + d6.filename(step)


def run_d7(*, registry, d6_plan, d3_plan, plan, ids, git_commit, runner_image,
           output, source_file=None, interpreter=None, observer=None):
    require(plan == build_plan(registry, d6_plan), "D7 plan drift")
    d3.validate_inputs(registry, ids)
    require(type(git_commit) is str and re.fullmatch(r"[0-9a-f]{40}", git_commit)
            and type(runner_image) is str
            and re.fullmatch(r"[A-Za-z0-9._@-]{1,100}", runner_image),
            "D7 provenance invalid")
    require(not output.exists() or not any(output.iterdir()), "D7 output not empty")
    if observer is None:
        require(source_file is not None and source_file.is_file()
                and interpreter is not None and interpreter.is_file(), "D7 child absent")
    output.mkdir(parents=True, exist_ok=True)
    records, requests = [], 0
    for step in plan["steps"]:
        path = output / filename(step)
        if observer is not None:
            doc = observer(step)
        else:
            command = [str(interpreter), str(pathlib.Path(__file__).resolve()),
                       "observe-one", "--source-file", str(source_file),
                       "--git-commit", git_commit, "--runner-image", runner_image,
                       "--ordinal", str(step["ordinal"]), "--output-file", str(path)]
            try:
                proc = subprocess.run(command, capture_output=True, timeout=180, check=False)
            except (OSError, subprocess.TimeoutExpired):
                raise ValueError("D7 child failed closed") from None
            require(proc.returncode == 0, "D7 child unsuccessful")
            doc = json.loads(path.read_text())
        validate_document(doc, registry, d3_plan, step, git_commit, runner_image)
        requests += doc["reference"]["reference"]["reference"]["reference"]["observation"]["metadataRequestsUsed"]
        require(requests <= 128, "D7 metadata budget exceeded")
        if observer is not None:
            path.write_bytes(d3._serialize(doc))
        records.append(doc)
    result = summary(records, git_commit, runner_image)
    (output / "summary-v1.json").write_bytes(d3._serialize(result))
    (output / "manifest-v1.json").write_bytes(d3._serialize({
        "schemaVersion": 1, "planId": PLAN_ID,
        "files": [{"path": p.name, "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
                  for p in sorted(output.glob("*.json"))],
    }))
    d3._check_privacy(output, ids)
    return result


def verify_bundle(*, registry, d6_plan, d3_plan, plan, ids, git_commit,
                  runner_image, root):
    require(plan == build_plan(registry, d6_plan), "D7 plan drift")
    expected = {filename(s) for s in plan["steps"]} | {"summary-v1.json"}
    require(root.is_dir() and {p.name for p in root.iterdir()}
            == expected | {"manifest-v1.json"}, "D7 unexpected evidence files")
    manifest = json.loads((root / "manifest-v1.json").read_text())
    require(type(manifest) is dict
            and set(manifest) == {"schemaVersion", "planId", "files"}
            and type(manifest["schemaVersion"]) is int and manifest["schemaVersion"] == 1
            and manifest["planId"] == PLAN_ID
            and type(manifest["files"]) is list and len(manifest["files"]) == 5
            and all(type(x) is dict and set(x) == {"path", "sha256"}
                    for x in manifest["files"])
            and {x["path"] for x in manifest["files"]} == expected,
            "D7 manifest shape")
    for entry in manifest["files"]:
        require(type(entry["sha256"]) is str
                and re.fullmatch(r"[0-9a-f]{64}", entry["sha256"])
                and hashlib.sha256((root / entry["path"]).read_bytes()).hexdigest()
                == entry["sha256"], "D7 digest mismatch")
    records = []
    for step in plan["steps"]:
        doc = json.loads((root / filename(step)).read_text())
        validate_document(doc, registry, d3_plan, step, git_commit, runner_image)
        records.append(doc)
    result = summary(records, git_commit, runner_image)
    require(result["totalMetadataRequests"] <= 128
            and json.loads((root / "summary-v1.json").read_text()) == result,
            "D7 aggregate or budget mismatch")
    d3._check_privacy(root, ids)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("validate-plan", "validate-hooks",
                                              "run", "verify", "observe-one"))
    for k, v in DEFAULTS.items():
        parser.add_argument("--" + k, type=pathlib.Path, default=v)
    for k in ("source-file", "interpreter", "output-dir", "output-file"):
        parser.add_argument("--" + k, type=pathlib.Path)
    parser.add_argument("--git-commit")
    parser.add_argument("--runner-image")
    parser.add_argument("--ordinal", type=int)
    args = parser.parse_args()
    try:
        registry = load_registry(args.registry)
        ancestors = [json.loads(getattr(args, k.replace("-", "_")).read_text())
                     for k in ("c1-plan", "d0-plan", "d1-plan", "d2-plan",
                               "d3-plan", "d4-plan", "d5-plan", "d6-plan")]
        plan = json.loads(args.plan.read_text())
        validate_plan(plan, registry, ancestors)
        if args.command == "validate-plan":
            print("D7 frozen four-control structural plan PASS")
            return 0
        if args.command in ("validate-hooks", "observe-one"):
            import yt_dlp
            import yt_dlp.version
            from yt_dlp.extractor.youtube import YoutubeIE
            from yt_dlp import utils
            from yt_dlp.networking.common import Response
            from yt_dlp.networking import exceptions as net_types
            oracle = d3.ORACLES["B"]
            require(yt_dlp.version.__version__ == oracle["version"]
                    and yt_dlp.version.RELEASE_GIT_HEAD == oracle["revision"],
                    "D7 pinned oracle drift")
            if args.command == "validate-hooks":
                with d4.StageObserver().installed(YoutubeIE):
                    with d5.TerminalProbe().installed(YoutubeIE):
                        with d6.ShapeProbe().installed(YoutubeIE):
                            with ManifestProbe().installed(YoutubeIE):
                                pass
                print("D7 exact parser and upstream hooks validated offline")
                return 0
        require(args.source_file is not None and args.git_commit is not None
                and args.runner_image is not None, "D7 missing source/provenance")
        ids = derive_case_inputs_from_source(
            registry, args.source_file.read_text(encoding="utf-8"))
        d3.validate_inputs(registry, ids)
        d3_plan = ancestors[4]
        if args.command == "observe-one":
            require(type(args.ordinal) is int and args.output_file is not None
                    and not args.output_file.exists(), "D7 destination or ordinal missing")
            step = next((x for x in plan["steps"] if x["ordinal"] == args.ordinal), None)
            require(step is not None, "D7 unplanned ordinal")
            cell = next(c for c in d3_plan["cells"] if c["caseClass"] == step["caseClass"]
                        and c["lane"] == step["lane"])
            api = types.SimpleNamespace(
                YoutubeDL=yt_dlp.YoutubeDL, networking_exceptions=net_types,
                networking_response_type=Response, utils=utils)
            stage, term, shapes, manifests = (
                d4.StageObserver(), d5.TerminalProbe(), d6.ShapeProbe(), ManifestProbe())
            with stage.installed(YoutubeIE):
                with term.installed(YoutubeIE):
                    with shapes.installed(YoutubeIE):
                        with manifests.installed(YoutubeIE):
                            obs = d3.observe_versioned_cell(
                                registry=registry, cell=cell, arm="B",
                                raw_id=ids[step["caseClass"]], git_commit=args.git_commit,
                                runner_image=args.runner_image,
                                oracle_loader=lambda: (api, d3.ORACLES["B"]["version"],
                                                       d3.ORACLES["B"]["revision"]))
            a = d4.envelope(step, d3.observation_envelope(step, obs), stage.events)
            b = d5.envelope(step, a, term.document())
            c = d6.envelope(step, b, shapes.document())
            doc = envelope(step, c, manifests.document())
            validate_document(doc, registry, d3_plan, step, args.git_commit, args.runner_image)
            args.output_file.parent.mkdir(parents=True, exist_ok=True)
            args.output_file.write_bytes(d3._serialize(doc))
            return 0
        require(args.output_dir is not None, "D7 output directory missing")
        if args.command == "run":
            result = run_d7(
                registry=registry, d6_plan=ancestors[-1], d3_plan=d3_plan,
                plan=plan, ids=ids, git_commit=args.git_commit,
                runner_image=args.runner_image, output=args.output_dir,
                source_file=args.source_file, interpreter=args.interpreter)
        else:
            result = verify_bundle(
                registry=registry, d6_plan=ancestors[-1], d3_plan=d3_plan,
                plan=plan, ids=ids, git_commit=args.git_commit,
                runner_image=args.runner_image, root=args.output_dir)
        print("D7 four controls validated, globalDecision=NOT_MADE_M3_A2")
        return 0
    except Exception:
        print("D7 failed closed; provider-controlled details suppressed", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
