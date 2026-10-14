"""Privacy-closed *offline* contract for M3-A2-C2 metadata failure diagnosis.

This module NEVER contacts YouTube. It defines a typed observation protocol that
can later be connected to an explicitly frozen, bounded metadata producer.
Raw URLs, provider identifiers, exception messages, headers and IPs have no
representation in the artifact. It does not modify m3-a-probe-v1.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import socket
import ssl
import sys
import urllib.error
from dataclasses import dataclass, field
from typing import Any, Mapping

from m3_matrix import M3MatrixError, validate_registry

PROTOCOL = "m3-a2-c2-metadata-diagnostic-v1"
MAX_METADATA_REQUESTS = 32
CATEGORIES = frozenset({
    "HTTP_403", "HTTP_429", "HTTP_OTHER", "CONNECT_OR_DNS",
    "TLS", "TIMEOUT", "JS_RUNTIME_UNAVAILABLE",
    "EXTRACTOR_ERROR", "UNCLASSIFIED",
})
# EJS unavailability may be emitted ONLY by an explicit versioned reference
# signal, never inferred from a missing runtime, generic exception or warning.
OBSERVABLE_CLASSES = CATEGORIES - {"JS_RUNTIME_UNAVAILABLE"}
TERMINAL_CATEGORIES = OBSERVABLE_CLASSES | {"NONE"}
_ALLOWED_HOST_TOKEN = re.compile(r"[A-Za-z0-9._@-]{1,100}\Z")
_SHA40 = re.compile(r"[0-9a-f]{40}\Z")


class C2ContractError(ValueError):
    pass


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise C2ContractError(message)


def _exact_keys(document: Mapping[str, Any], keys: set[str], location: str) -> None:
    _require(set(document) == keys, f"{location}: disallowed or missing keys")


def _status_class(status: object) -> str | None:
    if isinstance(status, int) and not isinstance(status, bool) and 400 <= status <= 599:
        return "HTTP_403" if status == 403 else "HTTP_429" if status == 429 else "HTTP_OTHER"
    return None


def classify_exception(error: BaseException) -> str:
    """Reduce exception types/statuses only. NEVER inspect str(error)."""
    current: BaseException | None = error
    visited: set[int] = set()
    # Follow safe causal nesting, e.g. yt-dlp wrapper -> network error.
    for _ in range(5):
        if current is None or id(current) in visited:
            break
        visited.add(id(current))
        # An arbitrary exception may expose a .status or .code attribute.
        # Only a recognized HTTP response exception is evidence of HTTP status.
        # Future transport libraries need explicit, separately tested adapters.
        if isinstance(current, urllib.error.HTTPError):
            return _status_class(current.code) or "UNCLASSIFIED"
        if isinstance(current, (TimeoutError, socket.timeout)):
            return "TIMEOUT"
        if isinstance(current, ssl.SSLError):
            return "TLS"
        if isinstance(current, (socket.gaierror, ConnectionError)):
            return "CONNECT_OR_DNS"
        if isinstance(current, urllib.error.URLError):
            reason = current.reason
            if isinstance(reason, BaseException):
                current = reason
                continue
            return "CONNECT_OR_DNS"
        cause = getattr(current, "__cause__", None)
        context = getattr(current, "__context__", None)
        current = cause if isinstance(cause, BaseException) else (
            context if isinstance(context, BaseException) else None
        )
    return "UNCLASSIFIED"


@dataclass
class DiagnosticCapture:
    """In-memory strictly typed observer. No exception or URL is stored."""
    request_attempts: int = 0
    network_failures: list[dict[str, Any]] = field(default_factory=list)

    def next_request(self) -> int:
        if self.request_attempts >= MAX_METADATA_REQUESTS:
            raise C2ContractError("frozen metadata request budget exhausted BEFORE I/O")
        self.request_attempts += 1
        return self.request_attempts

    def failed_request(self, ordinal: int, error: BaseException) -> None:
        _require(
            isinstance(ordinal, int) and not isinstance(ordinal, bool)
            and 1 <= ordinal <= self.request_attempts,
            "failed request ordinal is outside observed request attempts",
        )
        _require(
            not any(item["ordinal"] == ordinal for item in self.network_failures),
            "duplicate request failure",
        )
        self.network_failures.append({
            "ordinal": ordinal,
            "class": classify_exception(error),
        })

    def failed_request_class(self, ordinal: int, category: str) -> None:
        """Accept only trusted, already type-reduced transport evidence.

        The caller must establish the transport exception class before invoking
        this method. A raw exception message or arbitrary status is never passed.
        """
        _require(
            type(ordinal) is int and 1 <= ordinal <= self.request_attempts,
            "failed request ordinal is outside observed request attempts",
        )
        _require(
            type(category) is str and category in OBSERVABLE_CLASSES,
            "unapproved network diagnostic class",
        )
        _require(
            not any(item["ordinal"] == ordinal for item in self.network_failures),
            "duplicate request failure",
        )
        self.network_failures.append({"ordinal": ordinal, "class": category})

    def finish(
        self,
        *,
        registry: Mapping[str, Any],
        case_class: str,
        lane: str,
        git_commit: str,
        runner_image: str,
        extraction_completed: bool,
        terminal_error: BaseException | None = None,
    ) -> dict[str, Any]:
        _require(
            not (extraction_completed and terminal_error is not None),
            "successful extraction cannot have terminal error",
        )
        # Only an explicit, separately pinned reference signal may assert
        # runtime unavailability. Generic no-JS configuration alone cannot.
        if extraction_completed:
            terminal = "NONE"
        elif terminal_error is not None:
            # A generic exception is not proof of extractor-specific failure.
            # Preserve UNKNOWN rather than inventing an upstream cause.
            terminal = classify_exception(terminal_error)
        else:
            terminal = "UNCLASSIFIED"

        case = next(
            (value for value in registry["cases"] if value["caseClass"] == case_class),
            None,
        )
        _require(case is not None, "case is absent from frozen registry")
        _require(lane in registry["primaryLanes"], "lane is not a frozen primary lane")
        document = {
            "schemaVersion": 1,
            "protocol": PROTOCOL,
            "case": {
                "caseClass": case_class,
                "caseRevision": case["caseRevision"],
                "providerContentIdSha256": case["providerContentIdSha256"],
            },
            "client": {
                "lane": lane,
                "profileId": registry["laneProfiles"][lane],
            },
            "gitCommit": git_commit,
            "runnerImage": runner_image,
            "referenceOracle": dict(registry["referenceOracle"]),
            "mode": {
                "jsRuntime": "DISABLED",
                "remoteComponents": "DISABLED",
                "poTokenProvider": "ABSENT",
                "credentialsUsed": False,
            },
            "metadataRequestLimit": MAX_METADATA_REQUESTS,
            "metadataRequestsUsed": self.request_attempts,
            "networkFailures": sorted(self.network_failures, key=lambda x: x["ordinal"]),
            "extractionCompleted": extraction_completed,
            "terminalClass": terminal,
            "decision": "DIAGNOSTIC_ONLY_NO_PROVIDER_INFERENCE",
        }
        validate_document(document, registry)
        return document


def validate_document(document: Mapping[str, Any], registry: Mapping[str, Any]) -> None:
    """Fail-closed validation of EXACTLY the explicitly typed v1 vocabulary."""
    try:
        validate_registry(registry)
    except (M3MatrixError, ValueError) as exc:
        raise C2ContractError("invalid frozen case registry") from exc
    _require(isinstance(document, Mapping), "diagnostic must be JSON object")
    _exact_keys(document, {
        "schemaVersion", "protocol", "case", "client", "gitCommit",
        "runnerImage", "referenceOracle", "mode", "metadataRequestLimit",
        "metadataRequestsUsed", "networkFailures", "extractionCompleted",
        "terminalClass", "decision",
    }, "$")
    _require(document["schemaVersion"] == 1 and type(document["schemaVersion"]) is int, "wrong schema version")
    _require(document["protocol"] == PROTOCOL, "wrong diagnostic protocol")
    _require(isinstance(document["case"], dict), "invalid case object")
    _exact_keys(document["case"], {"caseClass", "caseRevision", "providerContentIdSha256"}, "$.case")
    matches = [x for x in registry["cases"] if x["caseClass"] == document["case"]["caseClass"]]
    _require(len(matches) == 1, "unplanned case class")
    c = matches[0]
    _require(document["case"]["caseRevision"] == c["caseRevision"], "case revision drift")
    _require(
        document["case"]["providerContentIdSha256"] == c["providerContentIdSha256"],
        "case fingerprint drift",
    )
    _require(isinstance(document["client"], dict), "invalid client object")
    _exact_keys(document["client"], {"lane", "profileId"}, "$.client")
    lane = document["client"]["lane"]
    _require(isinstance(lane, str) and lane in registry["primaryLanes"], "unplanned lane")
    _require(document["client"]["profileId"] == registry["laneProfiles"][lane], "profile drift")
    _require(
        isinstance(document["gitCommit"], str) and bool(_SHA40.fullmatch(document["gitCommit"])),
        "git commit must be exact lowercase SHA",
    )
    _require(
        isinstance(document["runnerImage"], str)
        and bool(_ALLOWED_HOST_TOKEN.fullmatch(document["runnerImage"])),
        "runner image contains disallowed characters",
    )
    _require(document["referenceOracle"] == registry["referenceOracle"], "reference oracle drift")
    _require(isinstance(document["mode"], dict), "invalid mode object")
    _exact_keys(
        document["mode"],
        {"jsRuntime", "remoteComponents", "poTokenProvider", "credentialsUsed"},
        "$.mode",
    )
    _require(
        document["mode"] == {
            "jsRuntime": "DISABLED",
            "remoteComponents": "DISABLED",
            "poTokenProvider": "ABSENT",
            "credentialsUsed": False,
        },
        "unapproved runtime/token/account experiment",
    )
    _require(
        type(document["metadataRequestLimit"]) is int
        and document["metadataRequestLimit"] == MAX_METADATA_REQUESTS,
        "metadata request ceiling drift",
    )
    n = document["metadataRequestsUsed"]
    _require(type(n) is int and 0 <= n <= MAX_METADATA_REQUESTS, "request count outside budget")
    failures = document["networkFailures"]
    _require(isinstance(failures, list) and len(failures) <= n, "invalid failure observations")
    ordinals: list[int] = []
    for item in failures:
        _require(isinstance(item, dict), "failure must be typed object")
        _exact_keys(item, {"ordinal", "class"}, "network failure")
        ordinal = item["ordinal"]
        _require(type(ordinal) is int and 1 <= ordinal <= n, "failure ordinal out of range")
        _require(isinstance(item["class"], str) and item["class"] in OBSERVABLE_CLASSES, "unknown/unsafe failure class")
        ordinals.append(ordinal)
    _require(ordinals == sorted(set(ordinals)), "failure records must be unique and ordered")
    _require(type(document["extractionCompleted"]) is bool, "invalid extraction completion")
    _require(isinstance(document["terminalClass"], str) and document["terminalClass"] in TERMINAL_CATEGORIES, "unknown terminal failure class")
    if document["extractionCompleted"]:
        _require(document["terminalClass"] == "NONE", "complete extraction cannot have terminal failure")
    else:
        _require(document["terminalClass"] != "NONE", "incomplete extraction must retain unknown/failure")
    _require(
        document["decision"] == "DIAGNOSTIC_ONLY_NO_PROVIDER_INFERENCE",
        "metadata failure diagnostics must not claim a production/provider decision",
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("validate",))
    parser.add_argument("--registry", type=pathlib.Path, required=True)
    parser.add_argument("--input", type=pathlib.Path, required=True)
    args = parser.parse_args()
    try:
        registry = json.loads(args.registry.read_text(encoding="utf-8"))
        artifact = json.loads(args.input.read_text(encoding="utf-8"))
        validate_document(artifact, registry)
        print("M3-A2-C2 typed diagnostic contract: PASS (offline only)")
        return 0
    except (OSError, ValueError, TypeError, KeyError, C2ContractError) as exc:
        print("M3-A2-C2 contract validation: FAIL (no private data emitted)", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
