"""Offline M3-A2-C2 research stop gate over already-archived D5/D6/D7 ledgers.

This is a *consistency check of archived digest ledgers*, not a fresh audit
of original GitHub artifact ZIP bytes. No provider requests or production changes.
"""
from __future__ import annotations
import argparse
import copy
import hashlib
import json
import pathlib
import re
import sys
from typing import Any

ROOT = pathlib.Path(__file__).resolve().parents[2]
PLAN_PATH = ".work/m3-a2/c2-stop-gate-v1.json"
SLICE_ORDER = ("D5", "D6", "D7")
KEYS = ("caseClass", "lane")
EXPECTED_CONTROL_KEYS = (
    ("V0", "WEB_EMBEDDED"), ("V2", "WEB_EMBEDDED"),
    ("V0", "TV"), ("V2", "TV"),
)
HEX = re.compile(r"[0-9a-f]{64}\Z")
SHA = re.compile(r"[0-9a-f]{40}\Z")
SNAPSHOT_KEYS = frozenset({"streamingData", "formats", "adaptiveFormats"})


def check(predicate: bool, reason: str) -> None:
    if not predicate:
        raise ValueError("C2 stop gate: " + reason)


def exact_int(value: Any, expected: int) -> bool:
    return type(value) is int and value == expected


def expected_plan() -> dict:
    """Frozen contract: deliberate changes require review and tests."""
    return {
        "schemaVersion": 1,
        "protocol": "m3-a2-c2-stop-gate-v1",
        "gateId": "m3-a2-c2-terminal-evidence-stop-2026-11-01-v1",
        "scope": "SAME_PINNED_REFERENCE_AND_FOUR_CONTROLS",
        "oracle": {
            "id": "YT_DLP", "version": "2026.10.21",
            "revision": "8cdf4fea48ecf4064387375301d7f294ebdb17c1",
        },
        "controls": [
            {"ordinal": 2, "caseClass": "V0", "lane": "WEB_EMBEDDED", "arm": "B"},
            {"ordinal": 3, "caseClass": "V2", "lane": "WEB_EMBEDDED", "arm": "B"},
            {"ordinal": 5, "caseClass": "V0", "lane": "TV", "arm": "B"},
            {"ordinal": 8, "caseClass": "V2", "lane": "TV", "arm": "B"},
        ],
        "evidence": [
            {
                "slice": "D5", "path": ".work/evidence/m3-a2/c2-d5-canonical-v1.json",
                "runId": 40911692927, "artifactId": 12914735795,
                "gitCommit": "3dbb570ab2ee53d185f31a5790dbc09e03acb4b7",
                "zipSha256": "d3716d8d725dc3f83813858496f4fa2e08efd02c51506693bc42a464aa547d81",
                "manifestSha256": "b66c4f6d2187db7eb2f4a05edec6f48f5c5781430ad2dba818ea7e4aa77965b4",
            }, {
                "slice": "D6", "path": ".work/evidence/m3-a2/c2-d6-canonical-v1.json",
                "runId": 41285812385, "artifactId": 13075800552,
                "gitCommit": "0cb6d82dc3f45c5e63f511527ca697522da21d06",
                "zipSha256": "2bab18a9563200f7ebe07c7fa2ca4b0e8699b44a674401122b73945bfb0fc5eb",
                "manifestSha256": "8fbf7428e69eb46a98c281a40888a56746448baf677d892c7b896e4188f90362",
            }, {
                "slice": "D7", "path": ".work/evidence/m3-a2/c2-d7-canonical-v1.json",
                "runId": 41305878254, "artifactId": 13083550309,
                "gitCommit": "4cad7091fcbf160464ca7efe7b0360ffa18f60d1",
                "zipSha256": "1261df13c5d5cd4d03704e92bf47c6f88b782303011cc2e933ca3efefaac86d1",
                "manifestSha256": "d2af52a0af5ae05841baf228fc825f80412e958e76f436624b6574215aa62167",
            },
        ],
        "gateOutcome": "STOP_C2_SAME_ENVIRONMENT_ONLY",
        "globalDecision": "NOT_MADE_M3_A2",
        "nextAllowed": "NEW_INDEPENDENT_EVIDENCE_AND_PREDECLARED_CONTROL_ONLY",
        "maxMediaProbesPerSlice": 0,
        "productionAdapterAllowed": False,
    }


def validate_plan(plan: Any) -> None:
    check(type(plan) is dict and plan == expected_plan(),
          "frozen plan or decision claim changed")


def _check_shape(snapshot: Any, values: tuple[str, str, str]) -> None:
    check(type(snapshot) is dict and set(snapshot) == SNAPSHOT_KEYS
          and all(type(snapshot[k]) is str for k in SNAPSHOT_KEYS)
          and tuple(snapshot[k] for k in ("streamingData", "formats", "adaptiveFormats")) == values,
          "D6 response shape drift")


def _check_manifest(doc: dict, spec: dict, slice_id: str, plan: dict) -> None:
    check(type(doc) is dict and set(doc) == {
        "schemaVersion", "protocol", "runId", "artifactId", "gitCommit",
        "runnerImage", "oracle", "zipSha256", "manifestSha256", "files",
        "observations", "totalMetadataRequests", "totalMediaProbes",
        "completedMetadata", "globalDecision", "independentAudit"},
        f"{slice_id} ledger fields drift")
    check(exact_int(doc["schemaVersion"], 1)
          and doc["protocol"] == f"m3-a2-c2-{slice_id.lower()}-independent-canonical-audit-v1"
          and all(doc[k] == spec[k] for k in (
              "runId", "artifactId", "gitCommit", "zipSha256", "manifestSha256"))
          and SHA.fullmatch(doc["gitCommit"]) is not None
          and type(doc["runnerImage"]) is str
          and re.fullmatch(r"[A-Za-z0-9._@-]{1,100}", doc["runnerImage"]) is not None
          and doc["oracle"] == plan["oracle"],
          f"{slice_id} canonical identity mismatch")
    check(type(doc["files"]) is dict and len(doc["files"]) == 5
          and "summary-v1.json" in doc["files"]
          and all(type(k) is str and not ("/" in k or "\\" in k or ".." in k)
                  and HEX.fullmatch(v) is not None
                  for k, v in doc["files"].items())
          and type(doc["independentAudit"]) is dict
          and bool(doc["independentAudit"])
          and all(type(k) is str and type(v) is str and v == "PASS"
                  for k, v in doc["independentAudit"].items()),
          f"{slice_id} manifest/evidence attestation drift")
    check(type(doc["observations"]) is list and len(doc["observations"]) == 4
          and exact_int(doc["totalMetadataRequests"], 16)
          and exact_int(doc["totalMediaProbes"], 0)
          and exact_int(doc["completedMetadata"], 0)
          and doc["globalDecision"] == "NOT_MADE_M3_A2",
          f"{slice_id} ledger budget or decision drift")


def _check_d5(obs: dict) -> None:
    check(type(obs) is dict and set(obs) == {
        "caseClass", "lane", "formatPopulation", "formatObservations",
        "raiseNoFormatsCalls", "reportDrmCalls", "metadataRequests",
        "http2xx", "completedMetadata", "mediaProbes"},
        "D5 observation schema drift")
    check(obs["formatPopulation"] == "EMPTY"
          and exact_int(obs["formatObservations"], 1)
          and exact_int(obs["raiseNoFormatsCalls"], 1)
          and exact_int(obs["reportDrmCalls"], 0)
          and exact_int(obs["metadataRequests"], 4)
          and exact_int(obs["http2xx"], 4)
          and obs["completedMetadata"] is False
          and exact_int(obs["mediaProbes"], 0),
          "D5 negative format path changed")


def _check_d6(obs: dict, control: dict) -> None:
    check(type(obs) is dict and set(obs) == {
        "caseClass", "lane", "referenceStepOrdinal", "preNormalizationApiResponses",
        "postNormalizationResponses", "selectedInputCandidateState",
        "selectedInputMatchesPost", "emittedFormatMappings", "poTokenSkipReportCalls",
        "formatPopulation", "raiseNoFormatsCalls", "reportDrmCalls",
        "metadataRequests", "http2xx", "completedMetadata", "mediaProbes"},
        "D6 observation schema drift")
    check(exact_int(obs["referenceStepOrdinal"], control["ordinal"])
          and type(obs["preNormalizationApiResponses"]) is list
          and len(obs["preNormalizationApiResponses"]) == 1
          and type(obs["postNormalizationResponses"]) is list
          and len(obs["postNormalizationResponses"]) == 2,
          "D6 response count/ordinal drift")
    _check_shape(obs["preNormalizationApiResponses"][0],
                 ("ABSENT", "ABSENT", "ABSENT"))
    _check_shape(obs["postNormalizationResponses"][0],
                 ("NULL", "ABSENT", "ABSENT"))
    _check_shape(obs["postNormalizationResponses"][1],
                 ("ONE", "ABSENT", "ABSENT"))
    check(obs["selectedInputCandidateState"] == "NO_CANDIDATES_IN_SELECTED_INPUT"
          and obs["selectedInputMatchesPost"] is True
          and exact_int(obs["emittedFormatMappings"], 0)
          and exact_int(obs["poTokenSkipReportCalls"], 0)
          and obs["formatPopulation"] == "EMPTY"
          and exact_int(obs["raiseNoFormatsCalls"], 1)
          and exact_int(obs["reportDrmCalls"], 0)
          and exact_int(obs["metadataRequests"], 4)
          and exact_int(obs["http2xx"], 4)
          and obs["completedMetadata"] is False
          and exact_int(obs["mediaProbes"], 0),
          "D6 candidate, terminal, or budget drift")


def _check_d7(obs: dict, control: dict) -> None:
    check(type(obs) is dict and set(obs) == {
        "caseClass", "lane", "ordinal", "parsedEmbeddedInitialObservation",
        "parsedEmbeddedStreamingData", "parsedEmbeddedManifestState",
        "apiReturnStreamingData", "apiReturnManifestState",
        "postNormalizationStreamingData", "selectedInputManifestState",
        "selectedInputCandidateState", "metadataRequests", "http2xx",
        "mediaProbes", "emittedFormatMappings", "poTokenSkipReportCalls",
        "formatPopulation", "raiseNoFormatsCalls"},
        "D7 observation schema drift")
    check(exact_int(obs["ordinal"], control["ordinal"])
          and obs["parsedEmbeddedInitialObservation"] == "PARSED_RETURN"
          and obs["parsedEmbeddedStreamingData"] == "ABSENT"
          and obs["parsedEmbeddedManifestState"] == "NO_MANIFEST_FIELDS_OBSERVED"
          and obs["apiReturnStreamingData"] == "ABSENT"
          and obs["apiReturnManifestState"] == "NO_MANIFEST_FIELDS_OBSERVED"
          and type(obs["postNormalizationStreamingData"]) is list
          and obs["postNormalizationStreamingData"] == ["NULL", "ONE"]
          and obs["selectedInputManifestState"] == "NO_MANIFEST_FIELDS_OBSERVED"
          and obs["selectedInputCandidateState"] == "NO_CANDIDATES_IN_SELECTED_INPUT"
          and exact_int(obs["metadataRequests"], 4)
          and exact_int(obs["http2xx"], 4)
          and exact_int(obs["mediaProbes"], 0)
          and exact_int(obs["emittedFormatMappings"], 0)
          and exact_int(obs["poTokenSkipReportCalls"], 0)
          and obs["formatPopulation"] == "EMPTY"
          and exact_int(obs["raiseNoFormatsCalls"], 1),
          "D7 initial-source, manifest, or budget drift")


def build_checkpoint(plan: dict, ledgers: list[dict]) -> dict:
    """Derive narrow actionable status; never reclassify M3-A2 or product."""
    validate_plan(plan)
    check(type(ledgers) is list and len(ledgers) == 3,
          "exact D5-D7 evidence required")
    for sl, spec, doc in zip(SLICE_ORDER, plan["evidence"], ledgers):
        check(spec["slice"] == sl, "slice order mismatch")
        _check_manifest(doc, spec, sl, plan)
    check(all(doc["runnerImage"] == ledgers[0]["runnerImage"] for doc in ledgers),
          "different runner invalidates frozen comparison")
    for idx, control in enumerate(plan["controls"]):
        expected_id = (control["caseClass"], control["lane"])
        check(expected_id == EXPECTED_CONTROL_KEYS[idx], "unknown control")
        observations = [doc["observations"][idx] for doc in ledgers]
        for sl, observation in zip(SLICE_ORDER, observations):
            check(type(observation) is dict
                  and tuple(observation[k] for k in KEYS) == expected_id,
                  f"{sl} control alignment drift")
        _check_d5(observations[0])
        _check_d6(observations[1], control)
        _check_d7(observations[2], control)
        check(all(obs["metadataRequests"] == observations[0]["metadataRequests"]
                  for obs in observations)
              and all(obs["mediaProbes"] == 0 for obs in observations),
              "cross-slice request/media contradiction")
    return {
        "schemaVersion": 1,
        "protocol": plan["protocol"],
        "gateId": plan["gateId"],
        "scope": plan["scope"],
        "verifiedAgainst": [
            {"slice": spec["slice"], "runId": spec["runId"],
             "artifactId": spec["artifactId"],
             "zipSha256": spec["zipSha256"]}
            for spec in plan["evidence"]
        ],
        "controlCount": 4,
        "metadataRequestsPerSlice": 16,
        "mediaProbesPerSlice": 0,
        "gateOutcome": plan["gateOutcome"],
        "globalDecision": plan["globalDecision"],
        "productionAdapterAllowed": False,
        "unsupportedConclusions": [
            "WIRE_STREAMING_DATA_ABSENT",
            "JS_OR_PO_TOKEN_REQUIRED",
            "YOUTUBE_PROVIDER_WIDE_NO_GO",
            "M3_A2_COMPLETE",
            "M3_A3_PATH_SELECTED",
            "M3_B_PRODUCTION_AUTHORIZED",
        ],
        "nextAllowed": plan["nextAllowed"],
        "requiresIndependentNewInformation": True,
        "policyIssue52StillOpen": True,
    }


def load_checkpoint(root: pathlib.Path = ROOT) -> dict:
    check(root.is_dir(), "repository root absent")
    plan = json.loads((root / PLAN_PATH).read_text(encoding="utf-8"))
    validate_plan(plan)
    ledgers = [
        json.loads((root / spec["path"]).read_text(encoding="utf-8"))
        for spec in plan["evidence"]
    ]
    return build_checkpoint(plan, ledgers)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("verify", "render"))
    parser.add_argument("--root", type=pathlib.Path, default=ROOT)
    args = parser.parse_args()
    try:
        result = load_checkpoint(args.root)
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError):
        print("C2 stop gate failed closed; do not promote research claims", file=sys.stderr)
        return 2
    if args.command == "render":
        print(json.dumps(result, sort_keys=True, indent=2))
    else:
        print("C2 archived-ledger consistency PASS; "
              "same-environment STOP; M3-A2 NOT_MADE; production BLOCKED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
