#!/usr/bin/env python3
"""Canonical M2-G2-F combined evidence gate.

Consumes owning G2-B/C/D/E artifacts produced for one exact source revision.
Each scenario remains an independent paired experiment. G2-F rebuilds and
verifies summary-v1 per scenario, then verifies cross-scenario provenance,
backend identity and common comparison inputs. It never selects a backend.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys
from typing import Any, Mapping

SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[1]
SCHEMAS = REPO_ROOT / ".work" / "schemas"
sys.path.insert(0, str(SCRIPT_DIR))

from m2_contracts import M2ContractError, scan_evidence_privacy  # noqa: E402
from m2_transport_evaluation_oracle import (  # noqa: E402
    TransportEvaluationError,
    analyze_trials,
    validate_summary,
)
from m2_transport_pair_plan import (  # noqa: E402
    BACKENDS,
    TransportPairPlanError,
    load_object as load_plan_object,
    recovery_jitter_sample,
    validate_trials_against_plan,
)
from schema_subset import (  # noqa: E402
    SchemaContractError,
    validate_instance,
    validate_schema_definition,
)

INDEX_SCHEMA = SCHEMAS / "m2-g2-evidence-index-v1.schema.json"
RECOVERY_JITTER_PROTOCOL = "SHA256_COUNTER_REJECTION_V1"
RECOVERY_JITTER_SEED = 424_243
ORDERING_SEED = 20_261_001
EXPECTED_BACKENDS = tuple(BACKENDS)

COMMON_INPUT_PATHS = {
    "work": "test-fixtures/network/m2/g2/work-f1-video-segment-1.json",
    "deviceState": "test-fixtures/network/m2/g2/device-api36-emulator.json",
    "cacheState": "test-fixtures/network/m2/g2/cache-empty-http-disabled.json",
    "recoveryPolicy": "test-fixtures/network/m2/g2/recovery-sponge-v2.json",
    "routePolicy": "test-fixtures/network/m2/g2/route-exact-default.json",
}

EXPERIMENTS = (
    {
        "id": "N0_CONTROL",
        "evidenceDir": "n0",
        "rootSuffix": "build/m2-g2-n0",
        "scenarioPath": "test-fixtures/network/m2/n0-control.json",
        "family": "N0",
        "variant": "CONTROL",
        "phase": "M2-G2-B-N0",
        "claim": "CONTROL_EQUIVALENT",
    },
    {
        "id": "N2_HIGH_RTT_JITTER",
        "evidenceDir": "n2",
        "rootSuffix": "build/m2-g2-network/n2",
        "scenarioPath": "test-fixtures/network/m2/n2-high-rtt-jitter.json",
        "family": "N2",
        "variant": "HIGH_RTT_JITTER",
        "phase": "M2-G2-C-N2",
        "claim": "OBSERVED_EFFECT_EQUIVALENT",
    },
    {
        "id": "N3_BURST_PACKET_LOSS",
        "evidenceDir": "n3",
        "rootSuffix": "build/m2-g2-network/n3",
        "scenarioPath": "test-fixtures/network/m2/n3-burst-packet-loss.json",
        "family": "N3",
        "variant": "BURST_PACKET_LOSS",
        "phase": "M2-G2-C-N3",
        "claim": "OBSERVED_EFFECT_EQUIVALENT",
    },
    {
        "id": "N5_BURST_LOSS",
        "evidenceDir": "n5",
        "rootSuffix": "build/m2-g2-network/n5",
        "scenarioPath": "test-fixtures/network/m2/n5-burst-loss.json",
        "family": "N5",
        "variant": "BURST_LOSS",
        "phase": "M2-G2-C-N5",
        "claim": "INCONCLUSIVE_STOCHASTIC_EFFECT",
    },
    {
        "id": "N6_TRANSPORT_RESET",
        "evidenceDir": "n6",
        "rootSuffix": "build/m2-g2-transport/n6",
        "scenarioPath": "test-fixtures/network/m2/n6-transport-reset.json",
        "family": "N6",
        "variant": "TRANSPORT_RESET",
        "phase": "M2-G2-D-N6",
        "claim": "TRANSPORT_RESET_EQUIVALENT",
    },
    {
        "id": "N6_DEFAULT_ROUTE_LOSS_RESTORE",
        "evidenceDir": "route",
        "rootSuffix": "build/m2-g2-route",
        "scenarioPath": "test-fixtures/network/m2/n6-default-route-loss-restore.json",
        "family": "N6",
        "variant": "DEFAULT_ROUTE_LOSS_RESTORE",
        "phase": "M2-G2-E-ROUTE_REPLACEMENT",
        "claim": "ROUTE_REPLACEMENT_EQUIVALENT",
    },
)

COMMON_COMPARISON_FIELDS = (
    "workFingerprint",
    "deviceStateFingerprint",
    "playbackMode",
    "cacheStateFingerprint",
    "recoveryPolicyFingerprint",
    "routePolicyFingerprint",
    "connectionState",
)

LIMITATIONS = (
    "API36 emulator evidence supports correctness/resilience equivalence and directional observations only.",
    "G2-F does not select a transport backend and makes no representative physical-device performance, battery or thermal claim.",
    "Canonical N5 BURST_LOSS remains a configuration-bound stochastic observation with an inconclusive resilience-effect claim.",
    "Different scenarioHash values remain separate paired experiments; G2-F never pools their timing rows into one synthetic comparison.",
)


class G2AggregateError(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise G2AggregateError(message)


def read_json(path: pathlib.Path) -> dict[str, Any]:
    require(path.is_file(), f"missing JSON evidence: {path}")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise G2AggregateError(f"{path}: invalid JSON: {error}") from error
    require(isinstance(value, dict), f"{path}: expected JSON object")
    return value


def read_commit(path: pathlib.Path, label: str) -> str:
    require(path.is_file(), f"missing {label}: {path}")
    value = path.read_text(encoding="utf-8").strip()
    require(
        len(value) == 40 and all(c in "0123456789abcdef" for c in value),
        f"{path}: invalid {label}",
    )
    return value


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def find_unique_dir(root: pathlib.Path, suffix: str) -> pathlib.Path:
    normalized = suffix.replace("\\", "/").rstrip("/")
    matches = [
        path for path in root.rglob("*")
        if path.is_dir() and path.as_posix().rstrip("/").endswith(normalized)
    ]
    require(
        len(matches) == 1,
        f"expected one directory ending {suffix!r} under {root}, found {len(matches)}",
    )
    return matches[0]


def common_inputs() -> dict[str, dict[str, Any]]:
    return {
        name: load_plan_object(REPO_ROOT / relative)
        for name, relative in COMMON_INPUT_PATHS.items()
    }


def validate_index_schema(document: Mapping[str, Any]) -> None:
    schema = read_json(INDEX_SCHEMA)
    validate_schema_definition(schema)
    validate_instance(schema, document, root_schema=schema)


def privacy(document: Any, label: str) -> None:
    try:
        scan_evidence_privacy(document)
    except M2ContractError as error:
        raise G2AggregateError(f"{label}: privacy failure: {error}") from error


def read_jsonl(path: pathlib.Path) -> list[dict[str, Any]]:
    require(path.is_file(), f"missing JSONL evidence: {path}")
    rows: list[dict[str, Any]] = []
    for line_number, line in enumerate(
        path.read_text(encoding="utf-8").splitlines(),
        start=1,
    ):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError as error:
            raise G2AggregateError(
                f"{path}:{line_number}: invalid JSONL row: {error}"
            ) from error
        require(
            isinstance(value, dict),
            f"{path}:{line_number}: expected JSON object row",
        )
        rows.append(value)
    return rows


def validate_origin_counts(
    root: pathlib.Path,
    *,
    experiment_id: str,
    plan: Mapping[str, Any],
    trials: Mapping[str, Any],
) -> None:
    trace = read_jsonl(root / "server" / "requests.jsonl")
    require(trace, f"{experiment_id}: origin trace is empty")
    prelude = trace[0]
    require(
        prelude.get("plane") == "control"
        and prelude.get("method") == "GET"
        and prelude.get("path") == "/__lab/config"
        and prelude.get("status") == 200
        and prelude.get("outcome") == "SUCCESS",
        f"{experiment_id}: Media Lab readiness prelude drift",
    )

    finalized = {row["trialId"]: row for row in trials["trials"]}
    schedule = planned_trial_schedule(plan)
    require(
        {row["trialId"] for row in schedule} == set(finalized),
        f"{experiment_id}: origin schedule/trials mismatch",
    )

    def validate_data_rows(
        trial_id: str,
        rows: list[dict[str, Any]],
    ) -> list[int]:
        request_ids: list[int] = []
        for row in rows:
            request_id = row.get("requestId")
            require(
                type(request_id) is int and request_id > 0,
                f"{experiment_id}/{trial_id}: invalid origin requestId",
            )
            require(
                row.get("plane") == "data"
                and row.get("method") == "GET"
                and row.get("path") == "/fixtures/F1/segment-0-00001.m4s",
                f"{experiment_id}/{trial_id}: unexpected origin trace row",
            )
            request_ids.append(request_id)
        require(
            len(request_ids) == len(set(request_ids)),
            f"{experiment_id}/{trial_id}: duplicate origin requestId",
        )
        return request_ids

    if experiment_id == "N0_CONTROL":
        data_rows = trace[1:]
        expected_total = sum(
            row["recovery"]["originRequestCount"]
            for row in finalized.values()
        )
        require(
            len(data_rows) == expected_total == len(schedule),
            "N0_CONTROL: origin request total drift",
        )
        trace_ids = set(validate_data_rows("all", data_rows))
        retained_ids: set[int] = set()
        for planned in schedule:
            trial_id = planned["trialId"]
            row = finalized[trial_id]
            require(
                row["recovery"]["originRequestCount"] == 1,
                f"N0_CONTROL/{trial_id}: expected one origin request",
            )
            raw = read_json(root / "raw" / f"{trial_id}.json")
            request_id = raw.get("proof", {}).get("originRequestId")
            require(
                type(request_id) is int and request_id > 0,
                f"N0_CONTROL/{trial_id}: raw originRequestId missing",
            )
            retained_ids.add(request_id)
        require(
            retained_ids == trace_ids and len(retained_ids) == len(schedule),
            "N0_CONTROL: raw/origin request correlation drift",
        )
        return

    previous_end = 1
    for planned in schedule:
        trial_id = planned["trialId"]
        trial_root = root / "trials" / trial_id
        start = int(
            (trial_root / "origin-before-count.txt")
            .read_text(encoding="utf-8")
            .strip()
        )
        end = int(
            (trial_root / "origin-after-count.txt")
            .read_text(encoding="utf-8")
            .strip()
        )
        require(
            start == previous_end and start < end <= len(trace),
            f"{experiment_id}/{trial_id}: origin partition drift",
        )
        partition = trace[start:end]
        request_ids = validate_data_rows(trial_id, partition)
        recovery = finalized[trial_id]["recovery"]
        require(
            recovery["originRequestCount"] == len(partition),
            f"{experiment_id}/{trial_id}: finalized originRequestCount drift",
        )
        raw = read_json(root / "raw" / f"{trial_id}.json")
        correlated = raw.get("proof", {}).get("correlatedOriginRequestIds")
        require(
            isinstance(correlated, list)
            and all(type(value) is int and value > 0 for value in correlated)
            and set(correlated).issubset(set(request_ids)),
            f"{experiment_id}/{trial_id}: raw/origin correlation drift",
        )
        previous_end = end

    require(
        previous_end == len(trace),
        f"{experiment_id}: unassigned origin trace rows remain",
    )


def validate_network_seed_readback(
    root: pathlib.Path,
    *,
    experiment_id: str,
    trial_ids: set[str],
) -> None:
    if experiment_id not in {"N2_HIGH_RTT_JITTER", "N5_BURST_LOSS"}:
        return
    for trial_id in sorted(trial_ids):
        state = read_json(
            root / "trials" / trial_id / "harness" / "netem-active-state.json"
        )
        require(
            state.get("randomSeed") == 424242,
            f"{experiment_id}/{trial_id}: active netem randomSeed drift",
        )
        require(
            state.get("scope") == "MEDIA_DATA_ONLY"
            and state.get("direction") == "DOWNSTREAM"
            and state.get("mediaPortScoped") is True
            and state.get("ipFamily") == "IPV4"
            and state.get("l4Protocol") == "TCP",
            f"{experiment_id}/{trial_id}: active netem scope/readback drift",
        )


def validate_jitter(raw_dir: pathlib.Path, trial_ids: set[str]) -> None:
    actual = {path.stem for path in raw_dir.glob("*.json")}
    require(actual == trial_ids, "raw trial set differs from retained trials-v1")
    for path in sorted(raw_dir.glob("*.json")):
        proof = read_json(path).get("proof")
        require(isinstance(proof, Mapping), f"{path.name}: proof missing")
        require(
            proof.get("recoveryJitterProtocol") == RECOVERY_JITTER_PROTOCOL,
            f"{path.name}: recovery jitter protocol drift",
        )
        require(
            proof.get("recoveryJitterSeed") == RECOVERY_JITTER_SEED,
            f"{path.name}: recovery jitter seed drift",
        )
        samples = proof.get("recoveryJitterSamples")
        require(isinstance(samples, list), f"{path.name}: recovery jitter samples missing")
        require(
            proof.get("recoveryJitterSampleCount") == len(samples),
            f"{path.name}: recovery jitter sample count drift",
        )
        backoffs = proof.get("recoveryBackoffs") or []
        require(
            isinstance(backoffs, list) and len(backoffs) == len(samples),
            f"{path.name}: recovery jitter/backoff lineage drift",
        )
        for index, (sample, backoff) in enumerate(zip(samples, backoffs)):
            require(isinstance(backoff, Mapping), f"{path.name}: malformed backoff")
            window = backoff.get("windowMs")
            require(type(window) is int and window >= 0, f"{path.name}: invalid backoff window")
            expected = recovery_jitter_sample(
                seed=RECOVERY_JITTER_SEED,
                sample_index=index,
                window_ms=window,
            )
            require(sample == expected, f"{path.name}: recovery jitter reference mismatch")
            require(backoff.get("delayMs") == sample, f"{path.name}: jitter/backoff delay mismatch")


def validate_verification(
    spec: Mapping[str, str],
    verification: Mapping[str, Any],
    *,
    trial_count: int,
    paired_blocks: int,
) -> str:
    eid = spec["id"]
    require(verification.get("schemaVersion") == 1, f"{eid}: verification schema drift")
    require(verification.get("phase") == spec["phase"], f"{eid}: verification phase drift")
    require(verification.get("status") == "PASS", f"{eid}: owning verifier not PASS")
    require(verification.get("trialCount") == trial_count, f"{eid}: trial count drift")
    require(verification.get("pairedBlockCount") == paired_blocks, f"{eid}: paired block count drift")
    require(verification.get("correctnessEquivalent") is True, f"{eid}: correctness not equivalent")
    require(verification.get("recoveryEquivalent") is True, f"{eid}: recovery not equivalent")
    require(verification.get("routeBindingEquivalent") is True, f"{eid}: route binding not equivalent")
    require(verification.get("selectedBackend") is None, f"{eid}: G2 producer selected a backend")

    claim = spec["claim"]
    if eid == "N2_HIGH_RTT_JITTER":
        require(verification.get("netemSeed") == 424242, "N2: canonical netem seed drift")
        require(verification.get("resilienceClaim") == claim, "N2: effect claim drift")
    elif eid == "N3_BURST_PACKET_LOSS":
        require(verification.get("resilienceClaim") == claim, "N3: effect claim drift")
        require(
            verification.get("effectPositiveTrialCount") == trial_count,
            "N3: every retained row must observe the blackout effect",
        )
    elif eid == "N5_BURST_LOSS":
        require(verification.get("netemSeed") == 424242, "N5: canonical netem seed drift")
        require(
            verification.get("resilienceClaim") == claim,
            "N5: stochastic effect must remain inconclusive",
        )
    elif eid == "N6_TRANSPORT_RESET":
        require(
            verification.get("resetEffectTrialCount") == trial_count,
            "N6 reset: injected effect missing",
        )
    elif eid == "N6_DEFAULT_ROUTE_LOSS_RESTORE":
        require(
            verification.get("oldRouteOriginSilent") is True,
            "N6 route: old route was not proven origin-silent",
        )
    return claim


def build_summary(trials: Mapping[str, Any], analysis: Mapping[str, Any]) -> dict[str, Any]:
    reason_codes = [
        "CORRECTNESS_EQUIVALENT",
        "RECOVERY_EQUIVALENT",
        "ROUTE_BINDING_EQUIVALENT",
    ]
    if analysis["pairedPerformanceBlockCount"] > 0:
        reason_codes.append("PAIRED_METRICS_AVAILABLE")
    reason_codes.append("NO_PERFORMANCE_CLAIM")
    summary = {
        "schemaVersion": 1,
        "runId": trials["runId"],
        "pairId": trials["pairId"],
        "scenarioHash": analysis["comparison"]["scenarioHash"],
        "status": "PASS",
        "deviceClass": trials["deviceClass"],
        "backendResults": [
            {
                "backendId": backend,
                "eligibility": analysis["backendEligibility"][backend],
                "trialCount": analysis["backendTrialCounts"][backend],
                "performanceSampleCount": analysis["backendSampleCounts"].get(backend, 0),
            }
            for backend in analysis["backends"]
        ],
        "pairedPerformanceBlockCount": analysis["pairedPerformanceBlockCount"],
        "invariants": analysis["invariants"],
        "comparison": analysis["comparisonResult"],
        "claimScope": "EMULATOR_DIRECTIONAL",
        "physicalDeviceEvidence": False,
        "decision": {
            "state": "TECHNICALLY_ELIGIBLE",
            "selectedBackend": None,
            "basis": "CORRECTNESS_AND_RESILIENCE",
            "reasonCodes": reason_codes,
        },
        "gates": {"M2-ACC-10": True},
        "limitations": [
            "API36 emulator evidence is directional only.",
            "No production transport backend is selected by G2.",
            "Representative performance requires later physical-device evidence.",
        ],
    }
    validate_summary(summary, trials)
    return summary


def common_comparison(comparison: Mapping[str, Any]) -> dict[str, Any]:
    return {field: comparison[field] for field in COMMON_COMPARISON_FIELDS}


def backend_identity(trials: Mapping[str, Any]) -> dict[str, tuple[str, str]]:
    identities: dict[str, tuple[str, str]] = {}
    for row in trials["trials"]:
        backend = row["backendId"]
        version = row["backendVersion"]
        implementation = row["implementationId"]
        require(isinstance(version, str) and version, f"{backend}: backendVersion missing")
        require(isinstance(implementation, str) and implementation, f"{backend}: implementationId missing")
        identity = (version, implementation)
        previous = identities.setdefault(backend, identity)
        require(previous == identity, f"{backend}: identity drift within experiment")
    return identities


def evidence_key(
    path: pathlib.Path,
    *,
    evidence_root: pathlib.Path,
    generated_root: pathlib.Path,
) -> str:
    if path.is_relative_to(evidence_root):
        return "evidence/" + path.relative_to(evidence_root).as_posix()
    if path.is_relative_to(generated_root):
        return "generated/" + path.relative_to(generated_root).as_posix()
    raise G2AggregateError(f"path outside canonical roots: {path}")


def resolve_key(
    key: str,
    *,
    evidence_root: pathlib.Path,
    generated_root: pathlib.Path,
) -> pathlib.Path:
    if key.startswith("evidence/"):
        root = evidence_root
        raw_relative = key.removeprefix("evidence/")
    elif key.startswith("generated/"):
        root = generated_root
        raw_relative = key.removeprefix("generated/")
    else:
        raise G2AggregateError(f"unsupported indexed path: {key}")

    relative = pathlib.PurePosixPath(raw_relative)
    require(
        raw_relative
        and not relative.is_absolute()
        and ".." not in relative.parts
        and "." not in relative.parts,
        f"unsafe indexed path: {key}",
    )
    candidate = root.joinpath(*relative.parts)
    require(candidate.is_relative_to(root), f"indexed path escaped root: {key}")
    return candidate


def evaluate_experiment(
    spec: Mapping[str, str],
    *,
    evidence_root: pathlib.Path,
    generated_root: pathlib.Path,
    git_commit: str,
    inputs: Mapping[str, Mapping[str, Any]],
) -> dict[str, Any]:
    eid = spec["id"]
    artifact_root = evidence_root / spec["evidenceDir"]
    require(artifact_root.is_dir(), f"missing G2 artifact root: {artifact_root}")
    root = find_unique_dir(artifact_root, spec["rootSuffix"])
    plan_path = root / "plan.json"
    trials_path = root / "output/transport-evaluation-trials-v1.json"
    verification_path = root / "output/verification.json"

    source_head = read_commit(root / "source-head-commit.txt", "source-head commit")
    checkout = read_commit(root / "checkout-commit.txt", "checkout commit")
    require(source_head == git_commit, f"{eid}: source-head commit mismatch")
    require(checkout == git_commit, f"{eid}: checkout commit mismatch")
    require(
        (root / "cleanup.txt").is_file()
        and (root / "cleanup.txt").read_text(encoding="utf-8").strip() == "ok",
        f"{eid}: owning workflow cleanup proof missing",
    )

    scenario = load_plan_object(REPO_ROOT / spec["scenarioPath"])
    plan = read_json(plan_path)
    trials = read_json(trials_path)
    verification = read_json(verification_path)
    require(plan.get("scenario", {}).get("family") == spec["family"], f"{eid}: scenario family drift")
    require(plan.get("scenario", {}).get("variant") == spec["variant"], f"{eid}: scenario variant drift")

    try:
        validate_trials_against_plan(plan, trials, scenario=scenario, inputs=inputs)
        analysis = analyze_trials(trials)
    except (
        TransportPairPlanError,
        TransportEvaluationError,
        SchemaContractError,
        KeyError,
        TypeError,
        ValueError,
    ) as error:
        raise G2AggregateError(f"{eid}: independent pair/oracle verification failed: {error}") from error

    require(
        tuple(analysis["backends"]) == tuple(sorted(EXPECTED_BACKENDS)),
        f"{eid}: backend set drift",
    )
    require(
        set(analysis["backendEligibility"].values()) == {"ELIGIBLE"},
        f"{eid}: both backends must be ELIGIBLE",
    )
    for field in ("correctnessEquivalent", "recoveryEquivalent", "routeBindingEquivalent"):
        require(analysis["comparisonResult"][field] is True, f"{eid}: {field} failed")
    require(analysis["pairedPerformanceBlockCount"] == 2, f"{eid}: incomplete paired structural sample set")
    require(
        trials.get("deviceClass") == "ANDROID_EMULATOR"
        and trials.get("androidApi") == 36,
        f"{eid}: canonical device drift",
    )
    require(
        trials.get("orderingProtocol") == "COUNTERBALANCED_PAIRS"
        and trials.get("orderingSeed") == ORDERING_SEED,
        f"{eid}: ordering contract drift",
    )

    claim = validate_verification(
        spec,
        verification,
        trial_count=len(trials["trials"]),
        paired_blocks=analysis["pairedPerformanceBlockCount"],
    )
    trial_ids = {row["trialId"] for row in trials["trials"]}
    validate_jitter(root / "raw", trial_ids)
    validate_network_seed_readback(
        root,
        experiment_id=eid,
        trial_ids=trial_ids,
    )
    validate_origin_counts(
        root,
        experiment_id=eid,
        plan=plan,
        trials=trials,
    )

    summary = build_summary(trials, analysis)
    generated = generated_root / eid.lower()
    generated.mkdir(parents=True, exist_ok=True)
    generated_trials = generated / "transport-evaluation-trials-v1.json"
    generated_summary = generated / "transport-evaluation-summary-v1.json"
    generated_trials.write_text(
        json.dumps(trials, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    generated_summary.write_text(
        json.dumps(summary, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    validate_summary(read_json(generated_summary), read_json(generated_trials))

    return {
        "spec": spec,
        "checkout": checkout,
        "analysis": analysis,
        "trials": trials,
        "verification": verification,
        "claim": claim,
        "identity": backend_identity(trials),
        "generatedTrials": generated_trials,
        "generatedSummary": generated_summary,
        "sourceVerification": verification_path,
    }


def artifact_index(
    evidence_root: pathlib.Path,
    generated_root: pathlib.Path,
) -> list[dict[str, Any]]:
    rows = []
    for root, prefix in ((evidence_root, "evidence"), (generated_root, "generated")):
        require(root.is_dir(), f"missing artifact root: {root}")
        for path in sorted(p for p in root.rglob("*") if p.is_file()):
            rows.append(
                {
                    "path": f"{prefix}/{path.relative_to(root).as_posix()}",
                    "sha256": sha256(path),
                    "sizeBytes": path.stat().st_size,
                }
            )
    require(rows, "G2-F artifact index is empty")
    return rows


def collect(
    *,
    evidence_root: pathlib.Path,
    generated_root: pathlib.Path,
    run_id: str,
    git_commit: str,
) -> dict[str, Any]:
    require(
        len(git_commit) == 40 and all(c in "0123456789abcdef" for c in git_commit),
        "git commit must be lowercase 40-hex",
    )
    generated_root.mkdir(parents=True, exist_ok=True)
    inputs = common_inputs()
    records = [
        evaluate_experiment(
            spec,
            evidence_root=evidence_root,
            generated_root=generated_root,
            git_commit=git_commit,
            inputs=inputs,
        )
        for spec in EXPERIMENTS
    ]

    common = common_comparison(records[0]["analysis"]["comparison"])
    identities = records[0]["identity"]
    checkout = records[0]["checkout"]
    for record in records[1:]:
        eid = record["spec"]["id"]
        require(
            common_comparison(record["analysis"]["comparison"]) == common,
            f"{eid}: common comparison fingerprint drift across scenarios",
        )
        require(
            record["identity"] == identities,
            f"{eid}: backend implementation/version drift across scenarios",
        )
        require(record["checkout"] == checkout, f"{eid}: mixed checkout revisions")

    experiments = []
    for record in records:
        spec = record["spec"]
        experiments.append(
            {
                "experimentId": spec["id"],
                "scenarioFamily": spec["family"],
                "scenarioVariant": spec["variant"],
                "scenarioHash": record["analysis"]["comparison"]["scenarioHash"],
                "status": "PASS",
                "resilienceClaim": record["claim"],
                "trialCount": len(record["trials"]["trials"]),
                "pairedBlockCount": record["analysis"]["pairedPerformanceBlockCount"],
                "trials": evidence_key(
                    record["generatedTrials"],
                    evidence_root=evidence_root,
                    generated_root=generated_root,
                ),
                "summary": evidence_key(
                    record["generatedSummary"],
                    evidence_root=evidence_root,
                    generated_root=generated_root,
                ),
                "verification": evidence_key(
                    record["sourceVerification"],
                    evidence_root=evidence_root,
                    generated_root=generated_root,
                ),
            }
        )

    index = {
        "schemaVersion": 1,
        "runId": run_id,
        "gitCommit": git_commit,
        "checkoutCommit": checkout,
        "status": "PASS",
        "gateId": "M2-ACC-10",
        "requiredExperimentCount": 6,
        "backendIds": list(EXPECTED_BACKENDS),
        "backendIdentities": [
            {
                "backendId": backend,
                "backendVersion": identities[backend][0],
                "implementationId": identities[backend][1],
            }
            for backend in EXPECTED_BACKENDS
        ],
        "commonComparison": common,
        "orderingProtocol": "COUNTERBALANCED_PAIRS",
        "orderingSeed": ORDERING_SEED,
        "deviceClass": "ANDROID_EMULATOR",
        "androidApi": 36,
        "experiments": experiments,
        "aggregate": {
            "correctnessEquivalent": True,
            "recoveryEquivalent": True,
            "routeBindingEquivalent": True,
            "technicalEligibility": True,
            "pairedDirectionalMetricsAvailable": True,
            "physicalDeviceEvidence": False,
            "claimScope": "EMULATOR_DIRECTIONAL",
            "performanceSelectionAllowed": False,
            "selectedBackend": None,
            "n5ResilienceEffect": "INCONCLUSIVE_STOCHASTIC_EFFECT",
        },
        "artifacts": artifact_index(evidence_root, generated_root),
        "limitations": list(LIMITATIONS),
    }
    privacy(index, "G2-F index")
    validate_index_schema(index)
    verify_index(
        index,
        evidence_root=evidence_root,
        generated_root=generated_root,
        expected_git_commit=git_commit,
    )
    return index


def verify_index(
    index: Mapping[str, Any],
    *,
    evidence_root: pathlib.Path,
    generated_root: pathlib.Path,
    expected_git_commit: str,
) -> None:
    validate_index_schema(index)
    privacy(index, "G2-F index")
    require(index.get("status") == "PASS", "G2-F index status is not PASS")
    require(index.get("gateId") == "M2-ACC-10", "G2-F gate id drift")
    require(index.get("gitCommit") == expected_git_commit, "G2-F source commit mismatch")
    require(index.get("requiredExperimentCount") == 6, "G2-F experiment count drift")
    require(index.get("backendIds") == list(EXPECTED_BACKENDS), "G2-F backend list drift")
    require(
        index.get("orderingProtocol") == "COUNTERBALANCED_PAIRS"
        and index.get("orderingSeed") == ORDERING_SEED,
        "G2-F ordering contract drift",
    )
    require(
        index.get("deviceClass") == "ANDROID_EMULATOR"
        and index.get("androidApi") == 36,
        "G2-F device identity drift",
    )

    artifacts = index.get("artifacts")
    require(isinstance(artifacts, list) and artifacts, "G2-F artifact index missing")
    by_path: dict[str, Mapping[str, Any]] = {}
    for row in artifacts:
        require(isinstance(row, Mapping), "G2-F malformed artifact row")
        key = str(row.get("path"))
        require(key not in by_path, f"duplicate artifact path: {key}")
        path = resolve_key(
            key,
            evidence_root=evidence_root,
            generated_root=generated_root,
        )
        require(path.is_file(), f"indexed G2 artifact missing: {key}")
        require(sha256(path) == row.get("sha256"), f"G2 artifact digest mismatch: {key}")
        require(path.stat().st_size == row.get("sizeBytes"), f"G2 artifact size mismatch: {key}")
        by_path[key] = row

    require(
        artifacts == artifact_index(evidence_root, generated_root),
        "G2-F artifact index does not cover the exact canonical file set",
    )

    experiments = index.get("experiments")
    require(isinstance(experiments, list), "G2-F experiments must be a list")
    expected_ids = [spec["id"] for spec in EXPERIMENTS]
    actual_ids = [
        row.get("experimentId")
        for row in experiments
        if isinstance(row, Mapping)
    ]
    require(actual_ids == expected_ids, "G2-F canonical experiment set/order drift")
    specs = {spec["id"]: spec for spec in EXPERIMENTS}

    common = None
    identities = None
    checkout_commits: set[str] = set()
    for row in experiments:
        require(isinstance(row, Mapping), "G2-F malformed experiment row")
        spec = specs[str(row["experimentId"])]
        eid = spec["id"]
        keys = (str(row["trials"]), str(row["summary"]), str(row["verification"]))
        for key in keys:
            require(key in by_path, f"{eid}: unindexed proof {key}")
        trials = read_json(
            resolve_key(keys[0], evidence_root=evidence_root, generated_root=generated_root)
        )
        summary = read_json(
            resolve_key(keys[1], evidence_root=evidence_root, generated_root=generated_root)
        )
        verification = read_json(
            resolve_key(keys[2], evidence_root=evidence_root, generated_root=generated_root)
        )
        try:
            validate_summary(summary, trials)
            analysis = analyze_trials(trials)
        except (
            TransportEvaluationError,
            SchemaContractError,
            KeyError,
            TypeError,
            ValueError,
        ) as error:
            raise G2AggregateError(
                f"{eid}: generated summary/oracle verification failed: {error}"
            ) from error
        require(
            summary["decision"]["state"] == "TECHNICALLY_ELIGIBLE"
            and summary["decision"]["selectedBackend"] is None,
            f"{eid}: summary decision drift",
        )
        require(
            summary["claimScope"] == "EMULATOR_DIRECTIONAL"
            and summary["physicalDeviceEvidence"] is False,
            f"{eid}: summary claim scope drift",
        )
        claim = validate_verification(
            spec,
            verification,
            trial_count=len(trials["trials"]),
            paired_blocks=analysis["pairedPerformanceBlockCount"],
        )
        require(row.get("resilienceClaim") == claim, f"{eid}: indexed resilience claim drift")
        require(row.get("scenarioHash") == analysis["comparison"]["scenarioHash"], f"{eid}: scenario hash drift")
        require(row.get("trialCount") == len(trials["trials"]), f"{eid}: indexed trial count drift")
        require(
            row.get("pairedBlockCount") == analysis["pairedPerformanceBlockCount"],
            f"{eid}: indexed paired block count drift",
        )

        current_common = common_comparison(analysis["comparison"])
        common = current_common if common is None else common
        require(current_common == common, f"{eid}: common comparison drift")
        current_identity = backend_identity(trials)
        identities = current_identity if identities is None else identities
        require(current_identity == identities, f"{eid}: backend identity drift")

        root = find_unique_dir(
            evidence_root / spec["evidenceDir"],
            spec["rootSuffix"],
        )
        require(
            read_commit(root / "source-head-commit.txt", "source-head commit")
            == expected_git_commit,
            f"{eid}: retained source commit drift",
        )
        checkout_commit = read_commit(
            root / "checkout-commit.txt",
            "checkout commit",
        )
        require(
            checkout_commit == expected_git_commit,
            f"{eid}: retained checkout commit drift",
        )
        checkout_commits.add(checkout_commit)

    require(
        len(checkout_commits) == 1
        and next(iter(checkout_commits)) == index.get("checkoutCommit")
        and index.get("checkoutCommit") == expected_git_commit,
        "G2-F checkout/source revision binding drift",
    )
    require(common == index.get("commonComparison"), "G2-F common comparison index drift")
    require(identities is not None, "G2-F backend identity missing")
    identity_rows = index.get("backendIdentities")
    require(
        isinstance(identity_rows, list)
        and len(identity_rows) == len(EXPECTED_BACKENDS)
        and len({row["backendId"] for row in identity_rows}) == len(EXPECTED_BACKENDS),
        "G2-F backend identity rows must contain each backend exactly once",
    )
    indexed = {
        row["backendId"]: (row["backendVersion"], row["implementationId"])
        for row in identity_rows
    }
    require(indexed == identities, "G2-F backend identity index drift")

    require(
        index.get("aggregate")
        == {
            "correctnessEquivalent": True,
            "recoveryEquivalent": True,
            "routeBindingEquivalent": True,
            "technicalEligibility": True,
            "pairedDirectionalMetricsAvailable": True,
            "physicalDeviceEvidence": False,
            "claimScope": "EMULATOR_DIRECTIONAL",
            "performanceSelectionAllowed": False,
            "selectedBackend": None,
            "n5ResilienceEffect": "INCONCLUSIVE_STOCHASTIC_EFFECT",
        },
        "G2-F aggregate verdict drift",
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)

    collect_parser = sub.add_parser("collect")
    collect_parser.add_argument("--evidence-root", required=True, type=pathlib.Path)
    collect_parser.add_argument("--generated-root", required=True, type=pathlib.Path)
    collect_parser.add_argument("--run-id", required=True)
    collect_parser.add_argument("--git-commit", required=True)
    collect_parser.add_argument("--output", required=True, type=pathlib.Path)

    verify_parser = sub.add_parser("verify-index")
    verify_parser.add_argument("--index", required=True, type=pathlib.Path)
    verify_parser.add_argument("--evidence-root", required=True, type=pathlib.Path)
    verify_parser.add_argument("--generated-root", required=True, type=pathlib.Path)
    verify_parser.add_argument("--expected-git-commit", required=True)

    args = parser.parse_args(argv)
    try:
        if args.command == "collect":
            index = collect(
                evidence_root=args.evidence_root,
                generated_root=args.generated_root,
                run_id=args.run_id,
                git_commit=args.git_commit,
            )
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(
                json.dumps(index, indent=2, sort_keys=True) + "\n",
                encoding="utf-8",
            )
            print(
                "M2-G2-F aggregate PASS: 6/6 canonical paired experiments; "
                "no backend selected"
            )
        else:
            verify_index(
                read_json(args.index),
                evidence_root=args.evidence_root,
                generated_root=args.generated_root,
                expected_git_commit=args.expected_git_commit,
            )
            print("M2-G2-F evidence index verified")
        return 0
    except (
        G2AggregateError,
        TransportEvaluationError,
        TransportPairPlanError,
        SchemaContractError,
        M2ContractError,
        OSError,
        KeyError,
        TypeError,
        ValueError,
        json.JSONDecodeError,
    ) as error:
        print(f"M2-G2-F AGGREGATE FAILURE: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
