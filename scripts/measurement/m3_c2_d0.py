"""M3-A2-C2-D0: bounded, metadata-only YouTube reference diagnostics.

Research only. The run command is the only network-capable entry point.
No JS/EJS, PO token, authentication, media read, or production integration.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys
import urllib.parse
from collections.abc import Mapping
from typing import Any, Callable

from m3_batch import derive_case_inputs_from_source
from m3_c2_contracts import DiagnosticCapture, MAX_METADATA_REQUESTS, classify_exception, validate_document
from m3_matrix import load_registry, validate_case_input
from m3_probe import _load_yt_dlp, LANE_TO_YTDLP_CLIENT

PLAN_ID = "m3-a2-c2-d0-nojs-2026-10-14-v1"
PAIRS = (("V0", "WEB_EMBEDDED"), ("V2", "WEB_EMBEDDED"), ("V0", "TV"), ("V2", "TV"))


class D0Error(ValueError):
    pass


def require(condition: bool, reason: str) -> None:
    if not condition:
        raise D0Error(reason)


def build_plan(registry: Mapping[str, Any], c1: Mapping[str, Any]) -> dict[str, Any]:
    require(
        c1["registryId"] == registry["registryId"]
        and c1["v1QualificationRequired"] is False
        and c1["fullMatrixAccepted"] is False,
        "source C1 plan drift",
    )
    cells = c1["cells"]
    require(tuple((c["caseClass"], c["lane"]) for c in cells) == PAIRS, "C1 cell order drift")
    for cell in cells:
        case = next((x for x in registry["cases"] if x["caseClass"] == cell["caseClass"]), None)
        require(
            case is not None
            and cell["caseRevision"] == case["caseRevision"]
            and cell["providerContentIdSha256"] == case["providerContentIdSha256"]
            and cell["profileId"] == registry["laneProfiles"][cell["lane"]],
            "C1 cell identity drift",
        )
    return {
        "schemaVersion": 1,
        "planId": PLAN_ID,
        "registryId": registry["registryId"],
        "c1PlanId": c1["planId"],
        "referenceOracle": dict(registry["referenceOracle"]),
        "metadataRequestLimitPerCell": MAX_METADATA_REQUESTS,
        "maxTotalMetadataRequests": len(cells) * MAX_METADATA_REQUESTS,
        "mode": {
            "jsRuntime": "DISABLED",
            "remoteComponents": "DISABLED",
            "poTokenProvider": "ABSENT",
            "credentialsUsed": False,
        },
        "cells": [
            {k: x[k] for k in ("caseClass", "caseRevision", "providerContentIdSha256", "lane", "profileId")}
            for x in cells
        ],
        "globalDecision": "NOT_MADE_M3_A2",
    }


def validate_plan(plan: Mapping[str, Any], registry: Mapping[str, Any], c1: Mapping[str, Any]) -> None:
    require(isinstance(plan, Mapping) and plan == build_plan(registry, c1), "frozen D0 plan mismatch")


def typed_network_category(error: Exception, types: Any) -> str:
    """Only recognized pinned yt-dlp network exceptions may supply HTTP status."""
    if isinstance(error, types.HTTPError):
        status = error.status
        if type(status) is int:
            if status == 403:
                return "HTTP_403"
            if status == 429:
                return "HTTP_429"
            if 400 <= status <= 599:
                return "HTTP_OTHER"
        return "UNCLASSIFIED"
    if isinstance(error, types.SSLError):
        return "TLS"
    if isinstance(error, types.TransportError):
        return "UNCLASSIFIED"
    return classify_exception(error)


class SilentLogger:
    def debug(self, _: Any) -> None:
        pass

    def info(self, _: Any) -> None:
        pass

    def warning(self, _: Any) -> None:
        pass

    def error(self, _: Any) -> None:
        pass


def observe_cell(
    *, registry: Mapping[str, Any], cell: Mapping[str, Any], raw_id: str,
    git_commit: str, runner_image: str, oracle_loader: Callable[[], Any] | None = None,
) -> dict[str, Any]:
    case_class, lane = cell["caseClass"], cell["lane"]
    validate_case_input(registry, case_class=case_class, case_revision=cell["caseRevision"], video_id=raw_id, lane=lane)
    require(cell["profileId"] == registry["laneProfiles"][lane], "client profile drift")
    yt_dlp, version, revision = (oracle_loader or _load_yt_dlp)()
    require(
        version == registry["referenceOracle"]["version"]
        and revision == registry["referenceOracle"]["revision"],
        "reference oracle drift",
    )
    if oracle_loader is None:
        from yt_dlp.networking import exceptions as network_types
    else:
        network_types = yt_dlp.networking_exceptions

    capture = DiagnosticCapture()
    parent = yt_dlp.YoutubeDL

    class InstrumentedYoutubeDL(parent):
        def urlopen(self, req):
            ordinal = capture.next_request()  # MUST precede network I/O.
            try:
                return super().urlopen(req)
            except Exception as exc:
                capture.failed_request_class(ordinal, typed_network_category(exc, network_types))
                raise

    # Parameter parity with immutable M3-A1 reference producer.
    params = {
        "logger": SilentLogger(), "quiet": True, "no_warnings": False,
        "skip_download": True, "noplaylist": True, "cachedir": False,
        "js_runtimes": {}, "remote_components": set(),
        "extractor_args": {"youtube": {
            "player_client": [LANE_TO_YTDLP_CLIENT[lane]],
            "formats": ["missing_pot"],
        }},
    }
    completed = False
    terminal_error = None
    url = "https://www.youtube.com/watch?v=" + urllib.parse.quote(raw_id, safe="")
    try:
        with InstrumentedYoutubeDL(params) as ydl:
            completed = isinstance(ydl.extract_info(url, download=False), Mapping)
    except Exception as exc:
        terminal_error = exc
    return capture.finish(
        registry=registry, case_class=case_class, lane=lane,
        git_commit=git_commit, runner_image=runner_image,
        extraction_completed=completed, terminal_error=terminal_error,
    )


def _write(path: pathlib.Path, value: Mapping[str, Any]) -> None:
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def run_d0(
    *, registry: Mapping[str, Any], c1: Mapping[str, Any], plan: Mapping[str, Any],
    ids: Mapping[str, str], git_commit: str, runner_image: str, output: pathlib.Path,
    oracle_loader: Callable[[], Any] | None = None,
) -> dict[str, Any]:
    validate_plan(plan, registry, c1)
    for case_class in ("V0", "V1", "V2"):
        case = next(x for x in registry["cases"] if x["caseClass"] == case_class)
        validate_case_input(
            registry, case_class=case_class,
            case_revision=case["caseRevision"], video_id=ids[case_class],
        )
    require(not output.exists() or not any(output.iterdir()), "output must be empty")
    docs = []
    for cell in plan["cells"]:
        doc = observe_cell(
            registry=registry, cell=cell, raw_id=ids[cell["caseClass"]],
            git_commit=git_commit, runner_image=runner_image, oracle_loader=oracle_loader,
        )
        validate_document(doc, registry)
        require(doc["gitCommit"] == git_commit and doc["runnerImage"] == runner_image, "provenance drift")
        docs.append(doc)
    require(len(docs) == 4, "exactly four controls required")
    count = sum(d["metadataRequestsUsed"] for d in docs)
    require(count <= plan["maxTotalMetadataRequests"], "combined metadata budget exceeded")
    summary = {
        "schemaVersion": 1, "planId": PLAN_ID,
        "gitCommit": git_commit, "runnerImage": runner_image,
        "controlCellsObserved": 4, "totalMetadataRequests": count,
        "totalMediaProbes": 0,
        "observations": [{
            "caseClass": d["case"]["caseClass"], "lane": d["client"]["lane"],
            "metadataRequestsUsed": d["metadataRequestsUsed"],
            "networkFailureClasses": [x["class"] for x in d["networkFailures"]],
            "terminalClass": d["terminalClass"],
            "extractionCompleted": d["extractionCompleted"],
        } for d in docs],
        "globalDecision": "NOT_MADE_M3_A2",
    }
    output.mkdir(parents=True, exist_ok=True)
    for doc in docs:
        _write(output / f"{doc['case']['caseClass'].lower()}-{doc['client']['lane'].lower()}.json", doc)
    _write(output / "summary-v1.json", summary)
    files = sorted(output.glob("*.json"))
    manifest = {
        "schemaVersion": 1, "planId": PLAN_ID,
        "files": [{"path": f.name, "sha256": hashlib.sha256(f.read_bytes()).hexdigest()} for f in files],
    }
    _write(output / "manifest-v1.json", manifest)
    for f in output.glob("*"):
        if f.is_file():
            data = f.read_bytes()
            require(
                not any(v.encode("utf-8") in data for v in ids.values()),
                "raw content ID retained in diagnostic",
            )
    return summary


def _json(path: pathlib.Path) -> Mapping[str, Any]:
    obj = json.loads(path.read_text(encoding="utf-8"))
    require(isinstance(obj, dict), "expected JSON object")
    return obj


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("command", choices=("validate-plan", "run"))
    p.add_argument("--registry", type=pathlib.Path, required=True)
    p.add_argument("--c1-plan", type=pathlib.Path, required=True)
    p.add_argument("--plan", type=pathlib.Path, required=True)
    p.add_argument("--source-file", type=pathlib.Path)
    p.add_argument("--git-commit")
    p.add_argument("--runner-image")
    p.add_argument("--output-dir", type=pathlib.Path)
    a = p.parse_args()
    try:
        registry, c1, plan = (_json(x) for x in (a.registry, a.c1_plan, a.plan))
        validate_plan(plan, registry, c1)
        if a.command == "validate-plan":
            print("C2-D0 frozen metadata diagnostic plan valid: 4 cells")
            return 0
        require(
            a.source_file is not None and a.git_commit is not None
            and a.runner_image is not None and a.output_dir is not None,
            "run parameters missing",
        )
        ids = derive_case_inputs_from_source(registry, a.source_file.read_text(encoding="utf-8"))
        summary = run_d0(
            registry=registry, c1=c1, plan=plan, ids=ids,
            git_commit=a.git_commit, runner_image=a.runner_image, output=a.output_dir,
        )
        print(
            f"C2-D0 controls={summary['controlCellsObserved']} "
            f"metadataRequests={summary['totalMetadataRequests']} mediaProbes=0 "
            "decision=NOT_MADE_M3_A2"
        )
        return 0
    except Exception:
        # Never print an untrusted exception, provider ID, URL, or credentials.
        print("C2-D0 failed closed (private details suppressed)", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
