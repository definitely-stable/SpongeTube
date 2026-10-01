#!/usr/bin/env python3
"""Build and independently verify a frozen M2-G2 paired execution plan.

The plan is created before Android results exist. It contains only immutable
comparison fingerprints and deterministic backend order. Runtime outcomes,
metrics, exclusions and winner decisions are deliberately not accepted here.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import sys
from collections import Counter
from typing import Any, Mapping

SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[1]
SCHEMA_PATH = REPO_ROOT / ".work" / "schemas" / "transport-pair-plan-v1.schema.json"
TRIALS_SCHEMA_PATH = REPO_ROOT / ".work" / "schemas" / "transport-evaluation-trials-v1.schema.json"
sys.path.insert(0, str(SCRIPT_DIR))

from m2_contracts import (  # noqa: E402
    M2ContractError,
    iter_scenario_faults,
    scan_evidence_privacy,
    scenario_sha256,
    validate_scenario_semantics,
)
from schema_subset import SchemaContractError, validate_instance  # noqa: E402


class TransportPairPlanError(ValueError):
    pass


BACKENDS = (
    "HTTP_URL_CONNECTION_ROUTE_BOUND",
    "PLATFORM_HTTP_ENGINE",
)
FROZEN_STOCHASTIC_SEEDS = {
    ("N2", "HIGH_RTT_JITTER"): 424_242,
    ("N5", "BURST_LOSS"): 424_242,
}
CANONICAL_G2_SCENARIO_HASHES = {
    ("N0", "CONTROL"): "e4d6044e7f5221dd4adc07f08d29822b7613e2174bde3ee31c623f1a787bd537",
    ("N2", "HIGH_RTT_JITTER"): "0a4b3ec961d3925bbe46d58697c9b0031d8f970ec45845ffe2e3c96a1e83b8ac",
    ("N3", "BURST_PACKET_LOSS"): "c8a2ec3ab7f337b287b20f0ed19de6e1e7f1cfb4c7dae295602a5d4f9137c990",
    ("N5", "BURST_LOSS"): "aed0b51ca0f0f5b0409918e1b621ef67d4c7655983bf839f58699c171977d670",
    ("N6", "TRANSPORT_RESET"): "12d6a0aebb9c021f89170dd429356bf29c8822f482f687cc00992cf26d316d45",
}
PORTABLE_ID = re.compile(r"^[a-z0-9][a-z0-9._-]{0,95}$")
REQUIRED_COMPARISON_INPUTS = (
    "work",
    "deviceState",
    "cacheState",
    "recoveryPolicy",
    "routePolicy",
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise TransportPairPlanError(message)


def load_object(path: pathlib.Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise TransportPairPlanError(f"{path}: invalid JSON: {error}") from error
    require(isinstance(value, dict), f"{path}: expected JSON object")
    return value


def _reject_non_integer_numbers(value: Any, path: str = "$") -> None:
    if isinstance(value, float):
        raise TransportPairPlanError(
            f"{path}: floating point is forbidden in fingerprint input; use integer units"
        )
    if isinstance(value, dict):
        for key, item in value.items():
            require(isinstance(key, str), f"{path}: object key is not a string")
            _reject_non_integer_numbers(item, f"{path}.{key}")
    elif isinstance(value, list):
        for index, item in enumerate(value):
            _reject_non_integer_numbers(item, f"{path}[{index}]")


def canonical_bytes(value: Any) -> bytes:
    _reject_non_integer_numbers(value)
    try:
        return json.dumps(
            value,
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=False,
            allow_nan=False,
        ).encode("utf-8")
    except (TypeError, ValueError) as error:
        raise TransportPairPlanError(f"non-canonical fingerprint input: {error}") from error


def fingerprint(value: Any) -> str:
    return hashlib.sha256(canonical_bytes(value)).hexdigest()


def scenario_is_stochastic(scenario: Mapping[str, Any]) -> bool:
    return any(fault.get("stochastic") is True for _, fault in iter_scenario_faults(scenario))


def validate_g2_scenario(scenario: Mapping[str, Any]) -> None:
    try:
        validate_scenario_semantics(scenario)
    except M2ContractError as error:
        raise TransportPairPlanError(str(error)) from error

    key = (scenario["scenarioFamily"], scenario["variant"])
    require(
        key in CANONICAL_G2_SCENARIO_HASHES,
        f"{key}: scenario is not in the canonical G2 set",
    )
    actual_hash = scenario_sha256(scenario)
    require(
        actual_hash == CANONICAL_G2_SCENARIO_HASHES[key],
        f"{key}: canonical G2 scenario hash drift: {actual_hash}",
    )
    stochastic = scenario_is_stochastic(scenario)
    seed = scenario.get("randomSeed")
    if key in FROZEN_STOCHASTIC_SEEDS:
        require(stochastic, f"{key}: canonical G2 scenario must remain stochastic")
        require(
            seed == FROZEN_STOCHASTIC_SEEDS[key],
            f"{key}: canonical G2 randomSeed drift: {seed!r}",
        )
    elif not stochastic:
        require(
            seed is None,
            f"{key}: deterministic G2 scenario must not carry a randomSeed",
        )


def _reject_raw_locators(value: Any, path: str) -> None:
    if isinstance(value, Mapping):
        for key, item in value.items():
            _reject_raw_locators(item, f"{path}.{key}")
    elif isinstance(value, list):
        for index, item in enumerate(value):
            _reject_raw_locators(item, f"{path}[{index}]")
    elif isinstance(value, str):
        require(
            "://" not in value,
            f"{path}: raw URL/locator must not influence a portable comparison fingerprint",
        )


def _validate_fingerprint_inputs(
    inputs: Mapping[str, Mapping[str, Any]],
) -> None:
    for name in REQUIRED_COMPARISON_INPUTS:
        require(name in inputs, f"missing comparison input {name}")
        require(isinstance(inputs[name], Mapping), f"{name} input must be an object")
        try:
            scan_evidence_privacy(inputs[name])
        except M2ContractError as error:
            raise TransportPairPlanError(
                f"{name} fingerprint input privacy failure: {error}"
            ) from error
        _reject_raw_locators(inputs[name], f"$.{name}")


def _comparison(
    scenario_hash: str,
    inputs: Mapping[str, Mapping[str, Any]],
    playback_mode: str,
    connection_state: str,
) -> dict[str, Any]:
    require(playback_mode in {"DIRECT", "SPONGE"}, "unsupported playback mode")
    require(
        connection_state == "COLD",
        "transport-pair-plan-v1 is COLD-only; WARM requires a separately frozen pre-warm protocol",
    )
    _validate_fingerprint_inputs(inputs)
    return {
        "workFingerprint": fingerprint(inputs["work"]),
        "scenarioHash": scenario_hash,
        "deviceStateFingerprint": fingerprint(inputs["deviceState"]),
        "playbackMode": playback_mode,
        "cacheStateFingerprint": fingerprint(inputs["cacheState"]),
        "recoveryPolicyFingerprint": fingerprint(inputs["recoveryPolicy"]),
        "routePolicyFingerprint": fingerprint(inputs["routePolicy"]),
        "connectionState": connection_state,
    }


def _first_backend(scenario_hash: str, ordering_seed: int) -> str:
    require(
        isinstance(ordering_seed, int)
        and not isinstance(ordering_seed, bool)
        and ordering_seed >= 0,
        "orderingSeed must be a non-negative integer",
    )
    # pairId is an evidence label, not randomization entropy. Relabeling a
    # frozen experiment must not alter backend order.
    material = f"{ordering_seed}:{scenario_hash}".encode("utf-8")
    bit = hashlib.sha256(material).digest()[0] & 1
    return BACKENDS[bit]


def _expected_order(
    scenario_hash: str,
    ordering_seed: int,
    block: int,
) -> tuple[str, str]:
    first = _first_backend(scenario_hash, ordering_seed)
    if block % 2 == 0:
        first = BACKENDS[1] if first == BACKENDS[0] else BACKENDS[0]
    second = BACKENDS[1] if first == BACKENDS[0] else BACKENDS[0]
    return first, second


def build_plan(
    *,
    run_id: str,
    pair_id: str,
    scenario: Mapping[str, Any],
    inputs: Mapping[str, Mapping[str, Any]],
    ordering_seed: int,
    block_count: int = 2,
    playback_mode: str = "SPONGE",
    connection_state: str = "COLD",
) -> dict[str, Any]:
    require(
        isinstance(run_id, str) and PORTABLE_ID.fullmatch(run_id) is not None,
        "runId must be a lowercase portable id (1..96 chars)",
    )
    require(
        isinstance(pair_id, str) and PORTABLE_ID.fullmatch(pair_id) is not None,
        "pairId must be a lowercase portable id (1..96 chars)",
    )
    require(
        isinstance(block_count, int)
        and not isinstance(block_count, bool)
        and block_count >= 2
        and block_count % 2 == 0,
        "counterbalanced G2 blockCount must be even and >= 2",
    )
    validate_g2_scenario(scenario)
    scenario_hash = scenario_sha256(scenario)
    comparison = _comparison(
        scenario_hash, inputs, playback_mode, connection_state
    )
    comparison_fingerprint = fingerprint(comparison)

    blocks: list[dict[str, Any]] = []
    for block in range(1, block_count + 1):
        order = _expected_order(scenario_hash, ordering_seed, block)
        trials = []
        for position, backend in enumerate(order, start=1):
            trials.append({
                "trialId": f"{pair_id}-b{block}-p{position}",
                "backendId": backend,
                "positionInBlock": position,
                "scenarioHash": scenario_hash,
                "comparisonFingerprint": comparison_fingerprint,
                "connectionState": connection_state,
            })
        blocks.append({"orderingBlock": block, "trials": trials})

    stochastic = scenario_is_stochastic(scenario)
    plan = {
        "schemaVersion": 1,
        "runId": run_id,
        "pairId": pair_id,
        "scenario": {
            "family": scenario["scenarioFamily"],
            "variant": scenario["variant"],
            "hash": scenario_hash,
            "randomSeed": scenario.get("randomSeed"),
            "stochastic": stochastic,
        },
        "orderingProtocol": "COUNTERBALANCED_PAIRS",
        "orderingSeed": ordering_seed,
        "backends": list(BACKENDS),
        "comparison": comparison,
        "comparisonFingerprint": comparison_fingerprint,
        "blockCount": block_count,
        "blocks": blocks,
        "resetPolicy": {
            # Re-create even deterministic harness state so previous backend
            # execution can never change the next trial's fault state/counters.
            "faultHarnessResetPerTrial": True,
            "cacheStateResetPerTrial": True,
            "transportSessionResetPerTrial": True,
            # N2/N5 must restart tc/netem with the same persisted seed before
            # EACH backend trial rather than continue one random stream.
            "stochasticStateResetPerTrial": stochastic,
        },
        "executionPolicy": {
            "retainAllPlannedTrials": True,
            "resultDependentReorderingForbidden": True,
            "correctnessBeforePerformance": True,
        },
        "limitations": [
            "Plan is frozen before Android execution and contains no result data.",
            "Emulator evidence may support correctness/resilience and directional observations only.",
            "transport-pair-plan-v1 is COLD-only; WARM requires a separately frozen pre-warm protocol.",
        ],
    }
    validate_plan(plan, scenario=scenario, inputs=inputs)
    return plan


def _validate_schema(plan: Mapping[str, Any]) -> None:
    schema = load_object(SCHEMA_PATH)
    try:
        validate_instance(schema, plan)
    except SchemaContractError as error:
        raise TransportPairPlanError(f"transport-pair-plan-v1: {error}") from error


def validate_plan(
    plan: Mapping[str, Any],
    *,
    scenario: Mapping[str, Any],
    inputs: Mapping[str, Mapping[str, Any]],
) -> None:
    _validate_schema(plan)
    try:
        scan_evidence_privacy(plan)
    except M2ContractError as error:
        raise TransportPairPlanError(f"pair plan privacy failure: {error}") from error
    validate_g2_scenario(scenario)
    require(
        PORTABLE_ID.fullmatch(plan["runId"]) is not None,
        "runId is not a portable G2 identifier",
    )
    require(
        PORTABLE_ID.fullmatch(plan["pairId"]) is not None,
        "pairId is not a portable G2 identifier",
    )

    require(plan["backends"] == list(BACKENDS), "G2 backend identity/order drift")
    require(plan["orderingProtocol"] == "COUNTERBALANCED_PAIRS", "ordering protocol drift")
    require(
        plan["blockCount"] == len(plan["blocks"])
        and plan["blockCount"] >= 2
        and plan["blockCount"] % 2 == 0,
        "pair plan must retain an even complete block set",
    )

    scenario_hash = scenario_sha256(scenario)
    stochastic = scenario_is_stochastic(scenario)
    expected_scenario = {
        "family": scenario["scenarioFamily"],
        "variant": scenario["variant"],
        "hash": scenario_hash,
        "randomSeed": scenario.get("randomSeed"),
        "stochastic": stochastic,
    }
    require(plan["scenario"] == expected_scenario, "resolved scenario identity drift")

    expected_comparison = _comparison(
        scenario_hash,
        inputs,
        plan["comparison"]["playbackMode"],
        plan["comparison"]["connectionState"],
    )
    require(plan["comparison"] == expected_comparison, "comparison fingerprint input drift")
    expected_fingerprint = fingerprint(expected_comparison)
    require(
        plan["comparisonFingerprint"] == expected_fingerprint,
        "comparisonFingerprint does not match canonical comparison",
    )

    reset = plan["resetPolicy"]
    require(reset["faultHarnessResetPerTrial"] is True, "fault harness must reset per trial")
    require(reset["cacheStateResetPerTrial"] is True, "cache state must reset per trial")
    require(
        reset["stochasticStateResetPerTrial"] is stochastic,
        "stochastic reset policy does not match scenario",
    )
    require(
        reset["transportSessionResetPerTrial"] is True,
        "transport session must reset before every v1 COLD trial",
    )

    expected_blocks = list(range(1, plan["blockCount"] + 1))
    require(
        [block["orderingBlock"] for block in plan["blocks"]] == expected_blocks,
        "ordering blocks must be contiguous and frozen before execution",
    )
    trial_ids: set[str] = set()
    first_positions: Counter[str] = Counter()
    for block in plan["blocks"]:
        block_id = block["orderingBlock"]
        rows = block["trials"]
        require(len(rows) == 2, f"block {block_id}: incomplete pair")
        require(
            {row["backendId"] for row in rows} == set(BACKENDS),
            f"block {block_id}: each backend must appear exactly once",
        )
        require(
            {row["positionInBlock"] for row in rows} == {1, 2},
            f"block {block_id}: positions must be 1 and 2",
        )
        expected_order = _expected_order(
            scenario_hash, plan["orderingSeed"], block_id
        )
        actual_order = tuple(
            row["backendId"] for row in sorted(rows, key=lambda row: row["positionInBlock"])
        )
        require(
            actual_order == expected_order,
            f"block {block_id}: backend order drifted after plan freeze",
        )
        first_positions[actual_order[0]] += 1
        for row in rows:
            expected_trial_id = f"{plan['pairId']}-b{block_id}-p{row['positionInBlock']}"
            require(
                row["trialId"] == expected_trial_id,
                f"block {block_id}: trialId does not match pair/position identity",
            )
            require(row["trialId"] not in trial_ids, "trialId must be globally unique")
            trial_ids.add(row["trialId"])
            require(row["scenarioHash"] == scenario_hash, f"{row['trialId']}: scenario drift")
            require(
                row["comparisonFingerprint"] == expected_fingerprint,
                f"{row['trialId']}: comparison fingerprint drift",
            )
            require(
                row["connectionState"] == expected_comparison["connectionState"],
                f"{row['trialId']}: COLD/WARM state mismatch within pair",
            )

    require(
        first_positions[BACKENDS[0]] == first_positions[BACKENDS[1]],
        "counterbalanced first-position counts are not equal",
    )




TRIAL_SCHEDULE_FIELDS = (
    "trialId",
    "orderingBlock",
    "positionInBlock",
    "backendId",
)


def planned_trial_schedule(plan: Mapping[str, Any]) -> list[dict[str, Any]]:
    """Canonical execution schedule derived only from the frozen plan."""
    schedule: list[dict[str, Any]] = []
    for block in plan["blocks"]:
        for row in sorted(block["trials"], key=lambda item: item["positionInBlock"]):
            schedule.append({
                "trialId": row["trialId"],
                "orderingBlock": block["orderingBlock"],
                "positionInBlock": row["positionInBlock"],
                "backendId": row["backendId"],
            })
    return schedule


def validate_observed_schedule(
    plan: Mapping[str, Any],
    observed_trials: list[Mapping[str, Any]],
    *,
    scenario: Mapping[str, Any],
    inputs: Mapping[str, Mapping[str, Any]],
) -> None:
    """Bind execution to the verified pre-result schedule before outcomes are interpreted."""
    # Never let a producer bind itself to a tampered plan merely because the
    # observed rows repeat the same tampering.
    validate_plan(plan, scenario=scenario, inputs=inputs)
    require(isinstance(observed_trials, list), "observed trial schedule must be a list")
    observed: list[dict[str, Any]] = []
    for index, row in enumerate(observed_trials):
        require(isinstance(row, Mapping), f"observed trial {index} must be an object")
        identity: dict[str, Any] = {}
        for field in TRIAL_SCHEDULE_FIELDS:
            require(field in row, f"observed trial {index} missing {field}")
            identity[field] = row[field]
        observed.append(identity)

    require(
        observed == planned_trial_schedule(plan),
        "observed execution schedule differs from frozen plan; "
        "trial deletion, duplication or result-dependent reordering is forbidden",
    )


def _validate_trials_schema(document: Mapping[str, Any]) -> None:
    schema = load_object(TRIALS_SCHEMA_PATH)
    try:
        validate_instance(schema, document)
    except SchemaContractError as error:
        raise TransportPairPlanError(
            f"transport-evaluation-trials-v1: {error}"
        ) from error


def validate_trials_against_plan(
    plan: Mapping[str, Any],
    trials_document: Mapping[str, Any],
    *,
    scenario: Mapping[str, Any],
    inputs: Mapping[str, Mapping[str, Any]],
) -> None:
    """Bind complete frozen-G0 result rows to the pre-execution G2 plan."""
    validate_plan(plan, scenario=scenario, inputs=inputs)
    _validate_trials_schema(trials_document)
    try:
        scan_evidence_privacy(trials_document)
    except M2ContractError as error:
        raise TransportPairPlanError(
            f"transport trials privacy failure: {error}"
        ) from error

    for field in ("runId", "pairId", "orderingProtocol", "orderingSeed"):
        require(
            trials_document[field] == plan[field],
            f"result evidence {field} does not match frozen pair plan",
        )

    planned_schedule = planned_trial_schedule(plan)
    result_schedule = [
        {field: row[field] for field in TRIAL_SCHEDULE_FIELDS}
        for row in trials_document["trials"]
    ]
    require(
        result_schedule == planned_schedule,
        "G0 trial rows differ from frozen schedule; deletion, duplication or "
        "result-dependent reordering is forbidden",
    )
    require(
        len(trials_document["trials"]) == len(planned_schedule),
        "G0 trial count differs from frozen pair plan",
    )
    for row in trials_document["trials"]:
        require(
            row["comparison"] == plan["comparison"],
            f"{row['trialId']}: comparison differs from frozen pair plan",
        )


def _inputs_from_args(args: argparse.Namespace) -> dict[str, dict[str, Any]]:
    return {
        "work": load_object(args.work),
        "deviceState": load_object(args.device_state),
        "cacheState": load_object(args.cache_state),
        "recoveryPolicy": load_object(args.recovery_policy),
        "routePolicy": load_object(args.route_policy),
    }


def _shared_inputs(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--scenario", required=True, type=pathlib.Path)
    parser.add_argument("--work", required=True, type=pathlib.Path)
    parser.add_argument("--device-state", required=True, type=pathlib.Path)
    parser.add_argument("--cache-state", required=True, type=pathlib.Path)
    parser.add_argument("--recovery-policy", required=True, type=pathlib.Path)
    parser.add_argument("--route-policy", required=True, type=pathlib.Path)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)

    build = commands.add_parser("build")
    _shared_inputs(build)
    build.add_argument("--run-id", required=True)
    build.add_argument("--pair-id", required=True)
    build.add_argument("--ordering-seed", required=True, type=int)
    build.add_argument("--blocks", type=int, default=2)
    build.add_argument("--playback-mode", choices=("DIRECT", "SPONGE"), default="SPONGE")
    build.add_argument("--connection-state", choices=("COLD",), default="COLD")
    build.add_argument("--output", required=True, type=pathlib.Path)

    verify = commands.add_parser("verify")
    _shared_inputs(verify)
    verify.add_argument("--plan", required=True, type=pathlib.Path)

    verify_results = commands.add_parser("verify-results")
    _shared_inputs(verify_results)
    verify_results.add_argument("--plan", required=True, type=pathlib.Path)
    verify_results.add_argument("--trials", required=True, type=pathlib.Path)

    args = parser.parse_args(argv)
    try:
        scenario = load_object(args.scenario)
        inputs = _inputs_from_args(args)
        if args.command == "build":
            plan = build_plan(
                run_id=args.run_id,
                pair_id=args.pair_id,
                scenario=scenario,
                inputs=inputs,
                ordering_seed=args.ordering_seed,
                block_count=args.blocks,
                playback_mode=args.playback_mode,
                connection_state=args.connection_state,
            )
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(
                json.dumps(plan, indent=2, sort_keys=True) + "\n",
                encoding="utf-8",
            )
        elif args.command == "verify":
            validate_plan(load_object(args.plan), scenario=scenario, inputs=inputs)
        else:
            validate_trials_against_plan(
                load_object(args.plan),
                load_object(args.trials),
                scenario=scenario,
                inputs=inputs,
            )
    except (TransportPairPlanError, OSError, KeyError, TypeError, ValueError) as error:
        print(f"M2-G2 pair-plan verification failed: {error}", file=sys.stderr)
        return 1
    print("M2-G2 paired execution plan verified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
