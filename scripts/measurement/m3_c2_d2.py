"""M3-A2-C2-D2: bounded, typed MIME and nested extractor diagnostics.

Research only. This module preserves D1 evidence and the fixed no-JS/no-token
experiment. The explicit 'run' command is the ONLY provider-facing entry point.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import socket
import ssl
import sys
import urllib.parse
from collections.abc import Mapping
from email.message import Message
from typing import Any, Callable

import m3_c2_d1 as d1
from m3_batch import derive_case_inputs_from_source
from m3_c2_d0 import SilentLogger, typed_network_category
from m3_matrix import load_registry, validate_case_input
from m3_probe import LANE_TO_YTDLP_CLIENT, _load_yt_dlp

PLAN_ID = "m3-a2-c2-d2-mime-causes-2026-10-25-v1"
PROTOCOL = "m3-a2-c2-d2-mime-causes-v1"
CAUSE_CLASSES = frozenset({
    "NONE", "UNKNOWN", "YTDLP_EXTRACTOR_ERROR", "HTTP_403",
    "HTTP_429", "HTTP_OTHER", "TLS", "CONNECT_OR_DNS", "TIMEOUT",
    "OTHER_NETWORK",
})
D2_KEYS = frozenset({"nestedCauseClass"})


def build_plan(registry: Mapping[str, Any], c1: Mapping[str, Any],
               d0: Mapping[str, Any], original_d1: Mapping[str, Any]) -> dict[str, Any]:
    d1.validate_plan(original_d1, registry, c1, d0)
    return {
        **dict(original_d1),
        "planId": PLAN_ID,
        "d1PlanId": original_d1["planId"],
        "diagnosticProtocol": PROTOCOL,
    }


def validate_plan(plan: Mapping[str, Any], registry: Mapping[str, Any],
                  c1: Mapping[str, Any], d0: Mapping[str, Any],
                  original_d1: Mapping[str, Any]) -> None:
    d1.require(isinstance(plan, Mapping) and plan == build_plan(registry, c1, d0, original_d1),
               "D2 must only extend the exact frozen D1 plan")
    d1.require(tuple((c["caseClass"], c["lane"]) for c in plan["cells"]) == d1.PAIRS,
               "D2 case order drift")


def safe_mime_family(response: Any, response_type: type) -> str:
    """Inspect only the pinned response type and an email.message.Message header object."""
    if not isinstance(response, response_type):
        return "UNKNOWN"
    try:
        headers = response.headers
        if not isinstance(headers, Message):
            return "UNKNOWN"
        values = headers.get_all("Content-Type", [])
        if not isinstance(values, list) or len(values) != 1:
            return "UNKNOWN"
        # Never serialize any raw header value.
        return d1.content_type_family(values[0])
    except Exception:
        return "UNKNOWN"


def _direct_cause_class(error: BaseException, utils: Any, network_types: Any) -> str:
    # Types, not messages, identify observations. Generic status/code attributes
    # and network warnings are never treated as provider evidence.
    if isinstance(error, utils.ExtractorError):
        return "YTDLP_EXTRACTOR_ERROR"
    if isinstance(error, network_types.HTTPError):
        status = error.status
        if type(status) is int:
            return ("HTTP_403" if status == 403 else "HTTP_429" if status == 429
                    else "HTTP_OTHER" if 400 <= status <= 599 else "UNKNOWN")
        return "UNKNOWN"
    if isinstance(error, (network_types.SSLError, ssl.SSLError)):
        return "TLS"
    if isinstance(error, (TimeoutError, socket.timeout)):
        return "TIMEOUT"
    if isinstance(error, (socket.gaierror, ConnectionError)):
        return "CONNECT_OR_DNS"
    if isinstance(error, network_types.TransportError):
        return "OTHER_NETWORK"
    return "UNKNOWN"


def nested_cause_class(error: BaseException | None, utils: Any,
                       network_types: Any) -> str:
    """Bounded traversal of trusted Python and pinned yt-dlp exception links.

    Only __cause__/__context__ and the known yt-dlp ExtractorError.cause and
    DownloadError/ExtractorError.exc_info[1] are inspected. An unrecognized
    source remains UNKNOWN; a DownloadError string never proves JS/token need.
    """
    if error is None:
        return "NONE"
    queue: list[BaseException] = []
    visited: set[int] = {id(error)}

    def children(current: BaseException) -> list[BaseException]:
        result: list[BaseException] = []
        if isinstance(current, (utils.DownloadError, utils.ExtractorError)):
            try:
                details = current.exc_info
            except AttributeError:
                details = None
            if isinstance(details, tuple) and len(details) == 3 and isinstance(details[1], BaseException):
                result.append(details[1])
        if isinstance(current, utils.ExtractorError):
            try:
                inner = current.cause
            except AttributeError:
                inner = None
            if isinstance(inner, BaseException):
                result.append(inner)
        for attr in ("__cause__", "__context__"):
            inner = getattr(current, attr, None)
            if isinstance(inner, BaseException):
                result.append(inner)
        return result

    queue.extend(children(error))
    if not queue:
        return "NONE"
    steps = 0
    while queue and steps < 8:
        current = queue.pop(0)
        if id(current) in visited:
            continue
        visited.add(id(current))
        steps += 1
        cls = _direct_cause_class(current, utils, network_types)
        if cls != "UNKNOWN":
            return cls
        queue.extend(children(current))
    return "UNKNOWN"


class ResponseCaptureD2(d1.ResponseCapture):
    def returned(self, ordinal: int, response: Any, response_type: type) -> None:
        recognized = isinstance(response, response_type)
        try:
            status = d1.http_class(response.status) if recognized else "UNKNOWN"
        except Exception:
            status = "UNKNOWN"
        self._record({
            "ordinal": ordinal,
            "kind": "RETURNED",
            "httpClass": status,
            "contentTypeFamily": safe_mime_family(response, response_type),
        })

    def finish_d2(self, *, nested_class: str, **kwargs: Any) -> dict[str, Any]:
        result = super().finish_d1(**kwargs)
        result["protocol"] = PROTOCOL
        result["nestedCauseClass"] = nested_class
        validate_document_d2(result, kwargs["registry"])
        return result


def validate_document_d2(doc: Mapping[str, Any], registry: Mapping[str, Any]) -> None:
    d1.require(isinstance(doc, dict) and set(doc) == d1.BASE_KEYS | {
        "responseEvents", "extractorStage", "extractorErrorType",
    } | D2_KEYS, "D2 document has extra/missing fields")
    d1.require(doc["protocol"] == PROTOCOL, "wrong D2 protocol")
    nested = doc["nestedCauseClass"]
    d1.require(type(nested) is str and nested in CAUSE_CLASSES, "forged nested class")
    previous = {k: v for k, v in doc.items() if k not in D2_KEYS}
    previous["protocol"] = d1.PROTOCOL
    d1.validate_document_d1(previous, registry)
    if doc["extractorErrorType"] == "NONE":
        d1.require(nested == "NONE", "nested error without terminal exception")


def observe_cell(*, registry: Mapping[str, Any], cell: Mapping[str, Any],
                 raw_id: str, git_commit: str, runner_image: str,
                 oracle_loader: Callable[[], Any] | None = None) -> dict[str, Any]:
    d1.require(type(git_commit) is str and re.fullmatch(r"[0-9a-f]{40}", git_commit) is not None,
               "invalid exact source revision")
    d1.require(type(runner_image) is str and re.fullmatch(r"[A-Za-z0-9._@-]{1,100}", runner_image) is not None,
               "invalid runner")
    case_class, lane = cell["caseClass"], cell["lane"]
    validate_case_input(registry, case_class=case_class,
                        case_revision=cell["caseRevision"], video_id=raw_id, lane=lane)
    d1.require(cell["profileId"] == registry["laneProfiles"][lane], "profile drift")
    yt_dlp, version, revision = (oracle_loader or _load_yt_dlp)()
    d1.require(version == registry["referenceOracle"]["version"]
               and revision == registry["referenceOracle"]["revision"], "oracle drift")
    if oracle_loader is None:
        from yt_dlp.networking import exceptions as network_types
        from yt_dlp.networking.common import Response
        from yt_dlp import utils
        response_type = Response
    else:
        network_types = yt_dlp.networking_exceptions
        response_type = yt_dlp.networking_response_type
        utils = yt_dlp.utils

    capture = ResponseCaptureD2()

    class InstrumentedYoutubeDL(yt_dlp.YoutubeDL):
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
        error_type = d1.extractor_exception_type(exc, utils)
    return capture.finish_d2(
        nested_class=nested_cause_class(terminal_error, utils, network_types),
        registry=registry, case_class=case_class, lane=lane,
        git_commit=git_commit, runner_image=runner_image,
        extraction_completed=completed, terminal_error=terminal_error,
        stage=stage, error_type=error_type,
    )


def _summary(documents: list[dict[str, Any]], git_commit: str, runner_image: str) -> dict[str, Any]:
    summary = d1._summary(documents, git_commit, runner_image)
    summary["planId"] = PLAN_ID
    for observation, doc in zip(summary["observations"], documents):
        observation["nestedCauseClass"] = doc["nestedCauseClass"]
        observation["responseMimeFamilies"] = [
            e["contentTypeFamily"] for e in doc["responseEvents"] if e["kind"] == "RETURNED"
        ]
    return summary


def run_d2(*, registry: Mapping[str, Any], c1: Mapping[str, Any],
           d0: Mapping[str, Any], d1_plan: Mapping[str, Any], plan: Mapping[str, Any],
           ids: Mapping[str, str], git_commit: str, runner_image: str,
           output: pathlib.Path, oracle_loader: Callable[[], Any] | None = None) -> dict[str, Any]:
    validate_plan(plan, registry, c1, d0, d1_plan)
    for case_class in ("V0", "V1", "V2"):
        case = next(x for x in registry["cases"] if x["caseClass"] == case_class)
        validate_case_input(registry, case_class=case_class,
                            case_revision=case["caseRevision"], video_id=ids[case_class])
    d1.require(not output.exists() or not any(output.iterdir()), "output must be empty")
    docs = []
    for cell in plan["cells"]:
        doc = observe_cell(
            registry=registry, cell=cell, raw_id=ids[cell["caseClass"]],
            git_commit=git_commit, runner_image=runner_image, oracle_loader=oracle_loader,
        )
        validate_document_d2(doc, registry)
        d1.require(doc["gitCommit"] == git_commit and doc["runnerImage"] == runner_image,
                   "mixed provenance")
        docs.append(doc)
    d1.require(len(docs) == 4 and sum(d["metadataRequestsUsed"] for d in docs)
               <= plan["maxTotalMetadataRequests"], "D2 budget exceeded")
    output.mkdir(parents=True, exist_ok=True)
    for doc in docs:
        d1._write(output / f"{doc['case']['caseClass'].lower()}-{doc['client']['lane'].lower()}.json", doc)
    summary = _summary(docs, git_commit, runner_image)
    d1._write(output / "summary-v1.json", summary)
    d1._write(output / "manifest-v1.json", {
        "schemaVersion": 1, "planId": PLAN_ID,
        "files": [{"path": p.name, "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
                  for p in sorted(output.glob("*.json"))],
    })
    for path in output.glob("*.json"):
        d1.require(not any(raw.encode() in path.read_bytes() for raw in ids.values()),
                   "raw provider ID in artifact")
    return summary


def verify_bundle(*, registry: Mapping[str, Any], plan: Mapping[str, Any],
                  ids: Mapping[str, str], root: pathlib.Path) -> dict[str, Any]:
    expected = {f"{c.lower()}-{lane.lower()}.json" for c, lane in d1.PAIRS}
    d1.require({p.name for p in root.iterdir()} == expected | {
        "summary-v1.json", "manifest-v1.json",
    }, "unexpected evidence files")
    manifest = d1._read(root / "manifest-v1.json")
    d1.require(set(manifest) == {"schemaVersion", "planId", "files"}
               and type(manifest["schemaVersion"]) is int and manifest["schemaVersion"] == 1
               and manifest["planId"] == PLAN_ID, "manifest schema drift")
    records = manifest["files"]
    d1.require(type(records) is list and len(records) == 5
               and all(type(x) is dict and set(x) == {"path", "sha256"} for x in records)
               and {x["path"] for x in records} == expected | {"summary-v1.json"},
               "manifest entries mismatch")
    for entry in records:
        d1.require(type(entry["sha256"]) is str and re.fullmatch(r"[0-9a-f]{64}", entry["sha256"]) is not None
                   and hashlib.sha256((root / entry["path"]).read_bytes()).hexdigest() == entry["sha256"],
                   "manifest digest mismatch")
    docs = []
    for cell in plan["cells"]:
        doc = d1._read(root / f"{cell['caseClass'].lower()}-{cell['lane'].lower()}.json")
        validate_document_d2(doc, registry)
        d1.require(doc["case"] == {
            "caseClass": cell["caseClass"], "caseRevision": cell["caseRevision"],
            "providerContentIdSha256": cell["providerContentIdSha256"],
        } and doc["client"] == {"lane": cell["lane"], "profileId": cell["profileId"]},
                   "case binding drift")
        docs.append(doc)
    d1.require(len({(d["gitCommit"], d["runnerImage"]) for d in docs}) == 1,
               "mixed git/runner provenance")
    d1.require(sum(d["metadataRequestsUsed"] for d in docs) <= plan["maxTotalMetadataRequests"],
               "aggregate request budget exceeded")
    summary = d1._read(root / "summary-v1.json")
    d1.require(summary == _summary(docs, docs[0]["gitCommit"], docs[0]["runnerImage"])
               and summary["globalDecision"] == "NOT_MADE_M3_A2",
               "summary or scientific decision mismatch")
    for path in root.glob("*.json"):
        d1.require(not any(raw.encode() in path.read_bytes() for raw in ids.values()),
                   "raw provider ID retained")
    return summary


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("command", choices=("validate-plan", "run", "verify"))
    for arg in ("registry", "c1-plan", "d0-plan", "d1-plan", "plan"):
        p.add_argument("--" + arg, type=pathlib.Path, required=True)
    p.add_argument("--source-file", type=pathlib.Path)
    p.add_argument("--git-commit")
    p.add_argument("--runner-image")
    p.add_argument("--output-dir", type=pathlib.Path)
    args = p.parse_args()
    try:
        registry = load_registry(args.registry)
        c1, d0, original_d1, plan = (
            d1._read(path) for path in (args.c1_plan, args.d0_plan, args.d1_plan, args.plan)
        )
        validate_plan(plan, registry, c1, d0, original_d1)
        if args.command == "validate-plan":
            print("C2-D2 exact frozen four-control plan: PASS")
            return 0
        d1.require(args.source_file is not None and args.output_dir is not None
                   and args.git_commit is not None and args.runner_image is not None,
                   "missing provenance/input")
        ids = derive_case_inputs_from_source(registry, args.source_file.read_text(encoding="utf-8"))
        if args.command == "run":
            result = run_d2(
                registry=registry, c1=c1, d0=d0, d1_plan=original_d1, plan=plan,
                ids=ids, git_commit=args.git_commit, runner_image=args.runner_image,
                output=args.output_dir,
            )
        else:
            result = verify_bundle(registry=registry, plan=plan, ids=ids, root=args.output_dir)
            d1.require(result["gitCommit"] == args.git_commit
                       and result["runnerImage"] == args.runner_image, "unexpected provenance")
        print(f"C2-D2 controls={result['controlCellsObserved']} "
              f"requests={result['totalMetadataRequests']} "
              "media=0 decision=NOT_MADE_M3_A2")
        return 0
    except Exception:
        # Never print untrusted exception messages or provider material.
        print("C2-D2 failed closed (private details suppressed)", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
