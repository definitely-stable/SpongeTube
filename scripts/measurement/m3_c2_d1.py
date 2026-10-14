"""M3-A2-C2-D1: typed response/stage observations, no-JS metadata-only research.

This is a new, frozen experiment. It neither rewrites D0/C1 evidence nor
modifies a production adapter. Only the explicit 'run' CLI contacts a provider.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import sys
import urllib.parse
from collections.abc import Mapping
from typing import Any, Callable

from m3_batch import derive_case_inputs_from_source
from m3_c2_contracts import (
    DiagnosticCapture, MAX_METADATA_REQUESTS, PROTOCOL as C2_PROTOCOL,
    validate_document,
)
from m3_c2_d0 import (
    SilentLogger, build_plan as build_d0_plan, typed_network_category,
    validate_plan as validate_d0_plan,
)
from m3_matrix import load_registry, validate_case_input
from m3_probe import LANE_TO_YTDLP_CLIENT, _load_yt_dlp

PLAN_ID = "m3-a2-c2-d1-responses-2026-10-14-v1"
PROTOCOL = "m3-a2-c2-d1-response-stages-v1"
PAIRS = (("V0", "WEB_EMBEDDED"), ("V2", "WEB_EMBEDDED"),
         ("V0", "TV"), ("V2", "TV"))
HTTP_CLASSES = frozenset({
    "HTTP_1XX", "HTTP_2XX", "HTTP_3XX", "HTTP_4XX", "HTTP_5XX", "UNKNOWN",
})
CONTENT_TYPES = frozenset({"JSON", "HTML", "MEDIA", "TEXT_OTHER", "OTHER", "UNKNOWN"})
EXTRACTOR_STAGES = frozenset({
    "MAPPING_RETURNED", "NON_MAPPING_RETURNED", "EXTRACT_INFO_RAISED", "INITIALIZATION_FAILED",
})
EXTRACTOR_TYPES = frozenset({
    "NONE", "YTDLP_EXTRACTOR_ERROR", "YTDLP_DOWNLOAD_ERROR", "OTHER_EXCEPTION",
})
BASE_KEYS = frozenset({
    "schemaVersion", "protocol", "case", "client", "gitCommit", "runnerImage",
    "referenceOracle", "mode", "metadataRequestLimit", "metadataRequestsUsed",
    "networkFailures", "extractionCompleted", "terminalClass", "decision",
})


class D1Error(ValueError):
    pass


def require(value: bool, code: str) -> None:
    if not value:
        raise D1Error(code)


def build_plan(registry: Mapping[str, Any], c1: Mapping[str, Any],
               d0: Mapping[str, Any]) -> dict[str, Any]:
    validate_d0_plan(d0, registry, c1)
    return {
        **dict(d0),
        "planId": PLAN_ID,
        "d0PlanId": d0["planId"],
        "diagnosticProtocol": PROTOCOL,
    }


def validate_plan(plan: Mapping[str, Any], registry: Mapping[str, Any],
                  c1: Mapping[str, Any], d0: Mapping[str, Any]) -> None:
    require(isinstance(plan, Mapping) and plan == build_plan(registry, c1, d0),
            "D1 plan must be exactly frozen D0 control set and budgets")
    require(tuple((c["caseClass"], c["lane"]) for c in plan["cells"]) == PAIRS,
            "D1 paired control order drift")


def http_class(status: object) -> str:
    if type(status) is not int or not 100 <= status <= 599:
        return "UNKNOWN"
    return f"HTTP_{status // 100}XX"


def content_type_family(value: object) -> str:
    # Raw untrusted header values never leave this function.
    if not isinstance(value, str):
        return "UNKNOWN"
    media_type = value.split(";", 1)[0].strip().lower()
    if media_type in ("application/json", "application/ld+json") or media_type.endswith("+json"):
        return "JSON"
    if media_type in ("text/html", "application/xhtml+xml"):
        return "HTML"
    if media_type.startswith(("audio/", "video/")) or media_type in (
        "application/octet-stream", "application/vnd.apple.mpegurl",
        "application/x-mpegurl", "application/dash+xml",
    ):
        return "MEDIA"
    if media_type.startswith("text/"):
        return "TEXT_OTHER"
    return "OTHER" if media_type else "UNKNOWN"


def extractor_exception_type(error: BaseException, utils: Any) -> str:
    # Exact exception hierarchy from the frozen reference, never its message.
    if isinstance(error, utils.ExtractorError):
        return "YTDLP_EXTRACTOR_ERROR"
    if isinstance(error, utils.DownloadError):
        return "YTDLP_DOWNLOAD_ERROR"
    return "OTHER_EXCEPTION"


class ResponseCapture(DiagnosticCapture):
    def __init__(self) -> None:
        super().__init__()
        self.response_events: list[dict[str, Any]] = []

    def _record(self, event: dict[str, Any]) -> None:
        ordinal = event["ordinal"]
        require(type(ordinal) is int and 1 <= ordinal <= self.request_attempts,
                "response outside counted request budget")
        require(not any(e["ordinal"] == ordinal for e in self.response_events),
                "duplicate request completion")
        self.response_events.append(event)

    def returned(self, ordinal: int, response: Any, response_type: type) -> None:
        # A generic object with fake .status/.headers is not HTTP evidence.
        recognized = isinstance(response, response_type)
        status = http_class(response.status) if recognized else "UNKNOWN"
        headers = response.headers if recognized else None
        family = content_type_family(headers.get("Content-Type")) if isinstance(headers, Mapping) else "UNKNOWN"
        self._record({
            "ordinal": ordinal, "kind": "RETURNED",
            "httpClass": status, "contentTypeFamily": family,
        })

    def failed(self, ordinal: int, error: Exception, network_types: Any) -> None:
        category = typed_network_category(error, network_types)
        self.failed_request_class(ordinal, category)
        self._record({"ordinal": ordinal, "kind": "EXCEPTION", "class": category})

    def finish_d1(self, *, stage: str, error_type: str,
                  registry: Mapping[str, Any], case_class: str, lane: str,
                  git_commit: str, runner_image: str, extraction_completed: bool,
                  terminal_error: BaseException | None) -> dict[str, Any]:
        result = super().finish(
            registry=registry, case_class=case_class, lane=lane,
            git_commit=git_commit, runner_image=runner_image,
            extraction_completed=extraction_completed, terminal_error=terminal_error,
        )
        result["protocol"] = PROTOCOL
        result["responseEvents"] = sorted(self.response_events, key=lambda e: e["ordinal"])
        result["extractorStage"] = stage
        result["extractorErrorType"] = error_type
        validate_document_d1(result, registry)
        return result


def validate_document_d1(doc: Mapping[str, Any], registry: Mapping[str, Any]) -> None:
    require(isinstance(doc, dict) and set(doc) == BASE_KEYS | {
        "responseEvents", "extractorStage", "extractorErrorType",
    }, "invalid D1 document key set")
    base = {k: doc[k] for k in BASE_KEYS}
    base["protocol"] = C2_PROTOCOL
    validate_document(base, registry)

    stage, error_type = doc["extractorStage"], doc["extractorErrorType"]
    require(type(stage) is str and stage in EXTRACTOR_STAGES, "untrusted extractor stage")
    require(type(error_type) is str and error_type in EXTRACTOR_TYPES, "untrusted extractor type")
    if stage == "MAPPING_RETURNED":
        require(doc["extractionCompleted"] is True and error_type == "NONE",
                "mapping outcome inconsistent")
    elif stage == "NON_MAPPING_RETURNED":
        require(doc["extractionCompleted"] is False and error_type == "NONE"
                and doc["terminalClass"] == "UNCLASSIFIED",
                "non-mapping outcome inconsistent")
    else:
        require(doc["extractionCompleted"] is False and error_type != "NONE",
                "exception stage inconsistent")

    events = doc["responseEvents"]
    n = doc["metadataRequestsUsed"]
    require(isinstance(events, list) and len(events) == n,
            "one safe outcome required per counted request")
    errors = {x["ordinal"]: x["class"] for x in doc["networkFailures"]}
    for ordinal, item in enumerate(events, 1):
        require(isinstance(item, dict) and type(item.get("ordinal")) is int
                and item["ordinal"] == ordinal, "request order/identity drift")
        if item.get("kind") == "RETURNED":
            require(set(item) == {"ordinal", "kind", "httpClass", "contentTypeFamily"}
                    and type(item["httpClass"]) is str and item["httpClass"] in HTTP_CLASSES
                    and type(item["contentTypeFamily"]) is str
                    and item["contentTypeFamily"] in CONTENT_TYPES
                    and ordinal not in errors, "invalid trusted response observation")
        elif item.get("kind") == "EXCEPTION":
            require(set(item) == {"ordinal", "kind", "class"}
                    and type(item["class"]) is str and errors.get(ordinal) == item["class"],
                    "exception/transport mismatch")
        else:
            raise D1Error("unrecognized request outcome")
    require(sum(1 for e in events if e["kind"] == "EXCEPTION") == len(errors),
            "network exceptions incomplete")


def observe_cell(*, registry: Mapping[str, Any], cell: Mapping[str, Any],
                 raw_id: str, git_commit: str, runner_image: str,
                 oracle_loader: Callable[[], Any] | None = None) -> dict[str, Any]:
    require(type(git_commit) is str and re.fullmatch(r"[0-9a-f]{40}", git_commit) is not None,
            "untrusted git revision")
    require(type(runner_image) is str and re.fullmatch(r"[A-Za-z0-9._@-]{1,100}", runner_image) is not None,
            "untrusted runner")
    case_class, lane = cell["caseClass"], cell["lane"]
    validate_case_input(registry, case_class=case_class,
                        case_revision=cell["caseRevision"], video_id=raw_id, lane=lane)
    require(cell["profileId"] == registry["laneProfiles"][lane], "client profile drift")
    yt_dlp, version, revision = (oracle_loader or _load_yt_dlp)()
    require(version == registry["referenceOracle"]["version"]
            and revision == registry["referenceOracle"]["revision"], "oracle provenance drift")
    if oracle_loader is None:
        from yt_dlp.networking import exceptions as network_types
        from yt_dlp.networking.common import Response
        from yt_dlp import utils
        response_type = Response
    else:
        network_types = yt_dlp.networking_exceptions
        response_type = yt_dlp.networking_response_type
        utils = yt_dlp.utils

    capture = ResponseCapture()
    parent = yt_dlp.YoutubeDL

    class InstrumentedYoutubeDL(parent):
        def urlopen(self, req):
            ordinal = capture.next_request()
            try:
                response = super().urlopen(req)
            except Exception as exc:
                capture.failed(ordinal, exc, network_types)
                raise
            capture.returned(ordinal, response, response_type)
            return response

    params = {
        "logger": SilentLogger(), "quiet": True, "no_warnings": False,
        "skip_download": True, "noplaylist": True, "cachedir": False,
        "js_runtimes": {}, "remote_components": set(),
        "extractor_args": {"youtube": {
            "player_client": [LANE_TO_YTDLP_CLIENT[lane]],
            "formats": ["missing_pot"],
        }},
    }
    stage = "INITIALIZATION_FAILED"
    error_type = "NONE"
    terminal_error: BaseException | None = None
    completed = False
    url = "https://www.youtube.com/watch?v=" + urllib.parse.quote(raw_id, safe="")
    try:
        with InstrumentedYoutubeDL(params) as ydl:
            stage = "EXTRACT_INFO_RAISED"
            result = ydl.extract_info(url, download=False)
            completed = isinstance(result, Mapping)
            stage = "MAPPING_RETURNED" if completed else "NON_MAPPING_RETURNED"
    except Exception as exc:
        terminal_error = exc
        error_type = extractor_exception_type(exc, utils)
    return capture.finish_d1(
        registry=registry, case_class=case_class, lane=lane,
        git_commit=git_commit, runner_image=runner_image,
        extraction_completed=completed, terminal_error=terminal_error,
        stage=stage, error_type=error_type,
    )


def _read(path: pathlib.Path) -> Mapping[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    require(isinstance(value, dict), "expected JSON object")
    return value


def _write(path: pathlib.Path, value: Mapping[str, Any]) -> None:
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def _summary(documents: list[dict[str, Any]], git_commit: str, runner_image: str) -> dict[str, Any]:
    return {
        "schemaVersion": 1, "planId": PLAN_ID, "gitCommit": git_commit,
        "runnerImage": runner_image, "controlCellsObserved": len(documents),
        "totalMetadataRequests": sum(d["metadataRequestsUsed"] for d in documents),
        "totalMediaProbes": 0,
        "observations": [{
            "caseClass": d["case"]["caseClass"], "lane": d["client"]["lane"],
            "metadataRequestsUsed": d["metadataRequestsUsed"],
            "returnedResponses": sum(e["kind"] == "RETURNED" for e in d["responseEvents"]),
            "responseStatusClasses": [e["httpClass"] for e in d["responseEvents"] if e["kind"] == "RETURNED"],
            "extractorStage": d["extractorStage"],
            "extractorErrorType": d["extractorErrorType"],
        } for d in documents],
        "globalDecision": "NOT_MADE_M3_A2",
    }


def run_d1(*, registry: Mapping[str, Any], c1: Mapping[str, Any],
           d0: Mapping[str, Any], plan: Mapping[str, Any],
           ids: Mapping[str, str], git_commit: str, runner_image: str,
           output: pathlib.Path, oracle_loader: Callable[[], Any] | None = None) -> dict[str, Any]:
    validate_plan(plan, registry, c1, d0)
    for case_class in ("V0", "V1", "V2"):
        case = next(x for x in registry["cases"] if x["caseClass"] == case_class)
        validate_case_input(registry, case_class=case_class,
                            case_revision=case["caseRevision"], video_id=ids[case_class])
    require(not output.exists() or not any(output.iterdir()), "evidence output must be empty")
    docs = []
    for cell in plan["cells"]:
        doc = observe_cell(
            registry=registry, cell=cell, raw_id=ids[cell["caseClass"]],
            git_commit=git_commit, runner_image=runner_image,
            oracle_loader=oracle_loader,
        )
        validate_document_d1(doc, registry)
        require(doc["gitCommit"] == git_commit and doc["runnerImage"] == runner_image,
                "provenance drift")
        docs.append(doc)
    require(len(docs) == 4 and sum(d["metadataRequestsUsed"] for d in docs)
            <= plan["maxTotalMetadataRequests"], "D1 request budget or cells drift")
    output.mkdir(parents=True, exist_ok=True)
    for doc in docs:
        _write(output / f"{doc['case']['caseClass'].lower()}-{doc['client']['lane'].lower()}.json", doc)
    summary = _summary(docs, git_commit, runner_image)
    _write(output / "summary-v1.json", summary)
    _write(output / "manifest-v1.json", {
        "schemaVersion": 1, "planId": PLAN_ID,
        "files": [{"path": p.name, "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
                  for p in sorted(output.glob("*.json"))],
    })
    for path in output.glob("*.json"):
        contents = path.read_bytes()
        require(not any(raw.encode() in contents for raw in ids.values()),
                "forbidden provider ID in portable evidence")
    return summary


def verify_bundle(*, registry: Mapping[str, Any], plan: Mapping[str, Any],
                  ids: Mapping[str, str], root: pathlib.Path) -> dict[str, Any]:
    expected = {f"{c.lower()}-{lane.lower()}.json" for c, lane in PAIRS}
    actual = {p.name for p in root.iterdir()}
    require(actual == expected | {"summary-v1.json", "manifest-v1.json"},
            "unexpected portable evidence files")
    manifest = _read(root / "manifest-v1.json")
    require(set(manifest) == {"schemaVersion", "planId", "files"}
            and type(manifest["schemaVersion"]) is int and manifest["schemaVersion"] == 1
            and manifest["planId"] == PLAN_ID, "invalid D1 manifest")
    declared = manifest["files"]
    require(type(declared) is list and len(declared) == 5, "manifest coverage incomplete")
    require({x["path"] for x in declared} == expected | {"summary-v1.json"},
            "manifest file list drift")
    for entry in declared:
        require(set(entry) == {"path", "sha256"} and type(entry["sha256"]) is str,
                "unexpected manifest fields")
        file = root / entry["path"]
        require(hashlib.sha256(file.read_bytes()).hexdigest() == entry["sha256"],
                "content digest mismatch")
    docs = []
    for cell in plan["cells"]:
        name = f"{cell['caseClass'].lower()}-{cell['lane'].lower()}.json"
        doc = _read(root / name)
        validate_document_d1(doc, registry)
        require(doc["case"] == {
            "caseClass": cell["caseClass"], "caseRevision": cell["caseRevision"],
            "providerContentIdSha256": cell["providerContentIdSha256"],
        } and doc["client"] == {
            "lane": cell["lane"], "profileId": cell["profileId"],
        }, "control identity/profiles drift")
        docs.append(doc)
    require(len({(d["gitCommit"], d["runnerImage"]) for d in docs}) == 1,
            "mixed SHA or runner")
    summary = _read(root / "summary-v1.json")
    require(summary == _summary(docs, docs[0]["gitCommit"], docs[0]["runnerImage"]),
            "independent summary mismatch")
    require(summary["controlCellsObserved"] == 4 and summary["totalMediaProbes"] == 0
            and summary["globalDecision"] == "NOT_MADE_M3_A2",
            "invalid global scientific decision")
    for path in root.glob("*.json"):
        require(not any(raw.encode() in path.read_bytes() for raw in ids.values()),
                "raw provider ID persisted")
    return summary


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("command", choices=("validate-plan", "run", "verify"))
    for arg in ("registry", "c1-plan", "d0-plan", "plan"):
        p.add_argument("--" + arg, type=pathlib.Path, required=True)
    p.add_argument("--source-file", type=pathlib.Path)
    p.add_argument("--git-commit")
    p.add_argument("--runner-image")
    p.add_argument("--output-dir", type=pathlib.Path)
    a = p.parse_args()
    try:
        registry = load_registry(a.registry)
        c1, d0, plan = _read(a.c1_plan), _read(a.d0_plan), _read(a.plan)
        validate_plan(plan, registry, c1, d0)
        if a.command == "validate-plan":
            print("C2-D1 frozen no-JS typed response plan: PASS")
            return 0
        require(a.source_file is not None and a.output_dir is not None,
                "source and output required for evidence operation")
        ids = derive_case_inputs_from_source(registry, a.source_file.read_text(encoding="utf-8"))
        if a.command == "run":
            require(a.git_commit is not None and a.runner_image is not None,
                    "exact SHA and runner image required")
            summary = run_d1(
                registry=registry, c1=c1, d0=d0, plan=plan, ids=ids,
                git_commit=a.git_commit, runner_image=a.runner_image, output=a.output_dir,
            )
        else:
            summary = verify_bundle(registry=registry, plan=plan, ids=ids, root=a.output_dir)
            require(summary["gitCommit"] == a.git_commit and summary["runnerImage"] == a.runner_image,
                    "audit against unexpected provenance")
        print(f"C2-D1 controls={summary['controlCellsObserved']} "
              f"requests={summary['totalMetadataRequests']} "
              "media=0 decision=NOT_MADE_M3_A2")
        return 0
    except Exception:
        # Never print untrusted URL, exception text, case IDs or provider response.
        print("C2-D1 failed closed (details suppressed)", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
