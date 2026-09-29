#!/usr/bin/env python3
"""Independent M2-G transport-evaluation contract oracle.

G0 owns comparison integrity, not a transport winner. The oracle proves that
paired trials differ only by backend identity/order, that exact-route and
RecoveryChain semantics remain comparable, and that claim scope is no stronger
than the retained device evidence.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys
from collections import Counter, defaultdict
from typing import Any, Mapping

SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[1]
SCHEMAS = REPO_ROOT / ".work" / "schemas"
sys.path.insert(0, str(SCRIPT_DIR))

from m2_contracts import M2ContractError, scan_evidence_privacy  # noqa: E402
from schema_subset import SchemaContractError, validate_instance  # noqa: E402


class TransportEvaluationError(ValueError):
    pass


BACKENDS = (
    "HTTP_URL_CONNECTION_ROUTE_BOUND",
    "PLATFORM_HTTP_ENGINE",
    "OKHTTP_5",
    "CRONET",
)

CORRECTNESS_FIELDS = (
    "range",
    "contentRange",
    "responseBounds",
    "publishedBytes",
)

TIMING_METRICS = (
    "firstByteUs",
    "completionUs",
    "cancellationLatencyUs",
    "cpuTimeUs",
    "maxRssBytes",
    "bytesRequested",
    "bytesReceived",
    "bytesPublished",
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise TransportEvaluationError(message)


def load_object(path: pathlib.Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise TransportEvaluationError(f"{path}: invalid JSON: {error}") from error
    require(isinstance(value, dict), f"{path}: expected object")
    return value


def _validate_schema(name: str, document: Mapping[str, Any]) -> None:
    schema = load_object(SCHEMAS / name)
    try:
        validate_instance(schema, document)
    except SchemaContractError as error:
        raise TransportEvaluationError(f"{name}: {error}") from error


def _privacy(document: Any, label: str) -> None:
    try:
        scan_evidence_privacy(document)
    except M2ContractError as error:
        raise TransportEvaluationError(f"{label}: privacy failure: {error}") from error


def _correctness_tuple(trial: Mapping[str, Any]) -> tuple[str, ...]:
    correctness = trial["requestCorrectness"]
    return tuple(correctness[field] for field in CORRECTNESS_FIELDS)


def _recovery_tuple(trial: Mapping[str, Any]) -> tuple[int, int, int]:
    recovery = trial["recovery"]
    return (
        recovery["recoveryChainCount"],
        recovery["ownerCount"],
        recovery["originRequestCount"],
    )


def _performance_metrics_present(trial: Mapping[str, Any]) -> bool:
    metrics = trial["metrics"]
    return any(metrics[name] is not None for name in TIMING_METRICS)


def _performance_sample_contract(trial: Mapping[str, Any]) -> bool:
    if trial["eligibility"] != "ELIGIBLE":
        return False
    if trial["result"] != "SUCCESS":
        return False
    if not trial["route"]["exactNetworkBound"]:
        return False
    if any(status != "PASS" for status in _correctness_tuple(trial)):
        return False
    recovery = trial["recovery"]
    if recovery["recoveryChainCount"] < 1 or recovery["ownerCount"] < 1:
        return False
    if recovery["internalRetryVisibility"] == "OPAQUE":
        return False
    if recovery["internalRetryVisibility"] == "PROVEN_ZERO":
        if recovery["internalRetryCount"] != 0:
            return False
    return True


def analyze_trials(document: Mapping[str, Any]) -> dict[str, Any]:
    _validate_schema("transport-evaluation-trials-v1.schema.json", document)
    _privacy(document, "transport trials")

    trials = document["trials"]
    trial_ids = [trial["trialId"] for trial in trials]
    require(len(trial_ids) == len(set(trial_ids)), "trialId must be unique")

    backends = sorted({trial["backendId"] for trial in trials})
    require(len(backends) >= 2, "comparison requires at least two backends")

    comparison = trials[0]["comparison"]
    for trial in trials[1:]:
        require(
            trial["comparison"] == comparison,
            f"{trial['trialId']}: comparison fingerprint drift; only backend/order may differ",
        )

    backend_eligibility: dict[str, str] = {}
    backend_trial_counts = Counter()
    backend_sample_counts = Counter()
    first_positions = Counter()
    blocks: dict[int, list[Mapping[str, Any]]] = defaultdict(list)

    for trial in trials:
        backend = trial["backendId"]
        backend_trial_counts[backend] += 1
        blocks[trial["orderingBlock"]].append(trial)
        if trial["positionInBlock"] == 1:
            first_positions[backend] += 1

        previous = backend_eligibility.setdefault(backend, trial["eligibility"])
        require(
            previous == trial["eligibility"],
            f"{backend}: eligibility changed within one pair",
        )

        eligibility = trial["eligibility"]
        result = trial["result"]
        route = trial["route"]
        recovery = trial["recovery"]

        if eligibility == "ELIGIBLE":
            require(
                route["exactNetworkBound"] is True,
                f"{trial['trialId']}: eligible backend did not prove exact route binding",
            )
            require(
                route["permitRouteEpoch"] is not None,
                f"{trial['trialId']}: eligible route-bound trial lacks permit route epoch",
            )
            require(result != "UNAVAILABLE", f"{trial['trialId']}: eligible trial unavailable")
        else:
            require(
                trial["performanceSampleEligible"] is False,
                f"{trial['trialId']}: ineligible/unavailable trial became performance sample",
            )

        if eligibility == "UNAVAILABLE_ON_DEVICE":
            require(result == "UNAVAILABLE", f"{trial['trialId']}: unavailable result mismatch")
            require(
                not _performance_metrics_present(trial),
                f"{trial['trialId']}: unavailable backend retained performance metrics",
            )
            require(
                recovery["recoveryChainCount"] == 0
                and recovery["ownerCount"] == 0
                and recovery["originRequestCount"] == 0,
                f"{trial['trialId']}: unavailable backend started recovery/network work",
            )
            require(
                all(status == "NOT_APPLICABLE" for status in _correctness_tuple(trial)),
                f"{trial['trialId']}: unavailable backend has correctness result",
            )

        if recovery["internalRetryVisibility"] == "PROVEN_ZERO":
            require(
                recovery["internalRetryCount"] == 0,
                f"{trial['trialId']}: PROVEN_ZERO requires internalRetryCount=0",
            )
        if recovery["internalRetryVisibility"] == "OPAQUE":
            require(
                recovery["internalRetryCount"] is None,
                f"{trial['trialId']}: opaque retry visibility cannot claim a retry count",
            )

        expected_sample = _performance_sample_contract(trial)
        require(
            trial["performanceSampleEligible"] is expected_sample,
            f"{trial['trialId']}: performanceSampleEligible disagrees with correctness/route/retry facts",
        )
        if expected_sample:
            backend_sample_counts[backend] += 1
            metrics = trial["metrics"]
            require(
                metrics["firstByteUs"] is not None and metrics["completionUs"] is not None,
                f"{trial['trialId']}: performance sample requires first-byte and completion durations",
            )
            require(
                metrics["bytesReceived"] is not None and metrics["bytesPublished"] is not None,
                f"{trial['trialId']}: performance sample requires byte accounting",
            )

    protocol = document["orderingProtocol"]
    if protocol == "COUNTERBALANCED_PAIRS":
        require(len(backends) == 2, "counterbalanced pairs currently require exactly two backends")
        for block_id, rows in blocks.items():
            require(len(rows) == 2, f"ordering block {block_id}: expected exactly two trials")
            require(
                {row["backendId"] for row in rows} == set(backends),
                f"ordering block {block_id}: each backend must appear exactly once",
            )
            require(
                {row["positionInBlock"] for row in rows} == {1, 2},
                f"ordering block {block_id}: positions must be 1 and 2",
            )
        first_position_counts = [first_positions[backend] for backend in backends]
        require(
            max(first_position_counts) - min(first_position_counts) <= 1,
            "counterbalanced ordering is biased toward one backend",
        )
    else:
        counts = [backend_trial_counts[backend] for backend in backends]
        require(len(set(counts)) == 1, "seeded balanced ordering requires equal backend counts")

    eligible_trials = [trial for trial in trials if trial["eligibility"] == "ELIGIBLE"]
    correctness_values = {_correctness_tuple(trial) for trial in eligible_trials}
    result_values = {trial["result"] for trial in eligible_trials}
    recovery_values = {_recovery_tuple(trial) for trial in eligible_trials}

    correctness_equivalent = len(correctness_values) <= 1 and len(result_values) <= 1
    recovery_equivalent = len(recovery_values) <= 1
    exact_route_equivalent = all(
        trial["route"]["exactNetworkBound"] for trial in eligible_trials
    )
    performance_claim_eligible = (
        bool(backends)
        and all(backend_eligibility[backend] == "ELIGIBLE" for backend in backends)
        and all(backend_sample_counts[backend] > 0 for backend in backends)
    )

    return {
        "backends": backends,
        "comparison": comparison,
        "backendEligibility": backend_eligibility,
        "backendTrialCounts": dict(backend_trial_counts),
        "backendSampleCounts": dict(backend_sample_counts),
        "invariants": {
            "work": True,
            "scenario": True,
            "deviceState": True,
            "playbackMode": True,
            "cacheState": True,
            "recoveryPolicy": True,
            "routePolicy": True,
            "connectionState": True,
            "exactRouteBinding": exact_route_equivalent,
            "orderingBalanced": True,
        },
        "comparisonResult": {
            "correctnessEquivalent": correctness_equivalent,
            "recoveryEquivalent": recovery_equivalent,
            "routeBindingEquivalent": exact_route_equivalent,
            "performanceClaimEligible": performance_claim_eligible,
        },
    }


def validate_summary(
    summary: Mapping[str, Any],
    trials_document: Mapping[str, Any],
) -> None:
    _validate_schema("transport-evaluation-summary-v1.schema.json", summary)
    _privacy(summary, "transport summary")
    computed = analyze_trials(trials_document)

    require(summary["runId"] == trials_document["runId"], "summary runId mismatch")
    require(summary["pairId"] == trials_document["pairId"], "summary pairId mismatch")
    require(
        summary["scenarioHash"] == computed["comparison"]["scenarioHash"],
        "summary scenarioHash mismatch",
    )
    require(summary["deviceClass"] == trials_document["deviceClass"], "deviceClass mismatch")
    require(summary["invariants"] == computed["invariants"], "summary invariant verdict drift")
    require(
        summary["comparison"] == computed["comparisonResult"],
        "summary comparison verdict drift",
    )

    rows = {row["backendId"]: row for row in summary["backendResults"]}
    require(set(rows) == set(computed["backends"]), "summary backend set mismatch")
    for backend in computed["backends"]:
        row = rows[backend]
        require(
            row["eligibility"] == computed["backendEligibility"][backend],
            f"{backend}: summary eligibility mismatch",
        )
        require(
            row["trialCount"] == computed["backendTrialCounts"][backend],
            f"{backend}: trial count mismatch",
        )
        require(
            row["performanceSampleCount"] == computed["backendSampleCounts"].get(backend, 0),
            f"{backend}: performance sample count mismatch",
        )

    if trials_document["deviceClass"] == "ANDROID_EMULATOR":
        require(
            summary["physicalDeviceEvidence"] is False,
            "emulator run cannot claim physical-device evidence",
        )
        require(
            summary["claimScope"] != "PHYSICAL_DEVICE_PERFORMANCE",
            "emulator run cannot claim physical-device performance",
        )

    if summary["claimScope"] == "PHYSICAL_DEVICE_PERFORMANCE":
        require(
            trials_document["deviceClass"] == "PHYSICAL_ANDROID"
            and summary["physicalDeviceEvidence"] is True,
            "physical performance claim requires physical Android evidence",
        )

    decision = summary["decision"]
    selected = decision["selectedBackend"]
    if decision["state"] == "NO_DECISION":
        require(selected is None, "NO_DECISION cannot select a backend")
    elif decision["state"] == "TECHNICALLY_ELIGIBLE":
        require(selected is None, "TECHNICALLY_ELIGIBLE records eligibility, not a winner")
        require(
            summary["comparison"]["correctnessEquivalent"]
            and summary["comparison"]["recoveryEquivalent"]
            and summary["comparison"]["routeBindingEquivalent"],
            "technical eligibility requires correctness, recovery and route-binding equivalence",
        )
    else:
        require(selected in rows, "selected backend missing from comparison")
        require(
            rows[selected]["eligibility"] == "ELIGIBLE",
            "selected backend is not eligible",
        )
        require(
            summary["comparison"]["correctnessEquivalent"]
            and summary["comparison"]["recoveryEquivalent"]
            and summary["comparison"]["routeBindingEquivalent"],
            "selection requires correctness/recovery/route equivalence",
        )
        require(
            "DEPENDENCY_POLICY_CLEAR" in decision["reasonCodes"],
            "selection requires dependency/distribution policy clearance",
        )

    if decision["basis"] == "PERFORMANCE":
        require(
            decision["state"] == "SELECTED",
            "performance basis is meaningful only for a selected backend",
        )
        require(
            summary["claimScope"] == "PHYSICAL_DEVICE_PERFORMANCE"
            and summary["physicalDeviceEvidence"] is True,
            "performance-based selection requires physical-device performance evidence",
        )
        require(
            summary["comparison"]["performanceClaimEligible"],
            "performance-based selection lacks comparable performance samples",
        )
        require(
            "PHYSICAL_DEVICE_EVIDENCE" in decision["reasonCodes"],
            "performance-based selection must cite physical-device evidence",
        )

    require(
        summary["gates"]["M2-ACC-10"] is (summary["status"] == "PASS"),
        "M2-ACC-10 must match summary PASS/FAIL status",
    )
    if summary["status"] == "PASS":
        require(
            all(summary["invariants"].values()),
            "PASS summary requires every comparison invariant",
        )


def verify_paths(trials_path: pathlib.Path, summary_path: pathlib.Path) -> None:
    trials = load_object(trials_path)
    summary = load_object(summary_path)
    validate_summary(summary, trials)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("trials", type=pathlib.Path)
    parser.add_argument("summary", type=pathlib.Path)
    args = parser.parse_args(argv)
    try:
        verify_paths(args.trials, args.summary)
    except (TransportEvaluationError, OSError, KeyError, TypeError, ValueError) as error:
        print(f"M2-G transport evaluation verification failed: {error}", file=sys.stderr)
        return 1
    print("M2-G transport evaluation evidence verified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
