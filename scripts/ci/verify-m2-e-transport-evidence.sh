#!/usr/bin/env bash
# M2-E TRANSPORT evidence verifier.
set -euo pipefail

ANDROID_DIR="${1:?android evidence directory required}"
SCENARIO="${2:?resolved scenario required}"
ORIGIN="${3:?origin trace required}"
HARNESS="${4:?fault harness evidence required}"
ACTIVE_STATE="${5:?active Toxiproxy normalized state required}"
CLEAN_STATE="${6:?clean Toxiproxy normalized state required}"
OUTPUT="${7:?output directory required}"

RECOVERY_ORACLE="scripts/measurement/m2_recovery_oracle.py"
mkdir -p "$OUTPUT"

for file in   "$ANDROID_DIR/failure-decision-events.json"   "$ANDROID_DIR/recovery-budget-events.json"   "$ANDROID_DIR/fetch-events.jsonl"   "$ANDROID_DIR/case.json"   "$ANDROID_DIR/transport-result.json"   "$SCENARIO" "$ORIGIN" "$HARNESS" "$ACTIVE_STATE" "$CLEAN_STATE"; do
  test -s "$file"
done

cp "$SCENARIO" "$OUTPUT/resolved-scenario.json"
cp "$HARNESS" "$OUTPUT/fault-harness-events.json"
cp "$ACTIVE_STATE" "$OUTPUT/toxiproxy-active-state.json"
cp "$CLEAN_STATE" "$OUTPUT/toxiproxy-clean-state.json"
cp "$ORIGIN" "$OUTPUT/origin-requests.jsonl"
cp "$ANDROID_DIR/transport-result.json" "$OUTPUT/transport-result.json"

python3 -   "$SCENARIO"   "$HARNESS"   "$ACTIVE_STATE"   "$CLEAN_STATE"   "$ANDROID_DIR/failure-decision-events.json"   "$ANDROID_DIR/recovery-budget-events.json"   "$ANDROID_DIR/fetch-events.jsonl"   "$ANDROID_DIR/transport-result.json"   "$ORIGIN"   "$OUTPUT/transport-harness-summary.json" <<'PY'
from __future__ import annotations

import hashlib
import json
import pathlib
import sys

ROOT = pathlib.Path.cwd()
sys.path.insert(0, str(ROOT / "scripts" / "measurement"))

from m2_contracts import (
    M2ContractError,
    scan_evidence_privacy,
    scenario_sha256,
    validate_m2e_harness_binding,
    validate_scenario_semantics,
)
from schema_subset import validate_instance

scenario_path, harness_path, active_path, clean_path, failures_path, budget_path, fetch_path, result_path, origin_path, output_path = map(pathlib.Path, sys.argv[1:])


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


def canonical_hash(value):
    encoded = json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=False
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


scenario = load(scenario_path)
harness = load(harness_path)
active = load(active_path)
clean = load(clean_path)
failures = load(failures_path)
budget = load(budget_path)
result = load(result_path)

schema = load(ROOT / ".work" / "schemas" / "fault-harness-events-v1.schema.json")
validate_instance(schema, harness)
validate_scenario_semantics(scenario)
scan_evidence_privacy(scenario)
scan_evidence_privacy(harness)
scan_evidence_privacy(result)
scan_evidence_privacy(active)
scan_evidence_privacy(clean)

if scenario["primaryPlane"] != "TRANSPORT":
    raise M2ContractError("transport verifier requires primaryPlane TRANSPORT")
if len(scenario["transportFaults"]) != 1:
    raise M2ContractError("exactly one transport fault is required")
if any(scenario[field] for field in (
    "networkFaults", "providerFaults", "routeFaults", "storageFaults"
)):
    raise M2ContractError("canonical transport run contains a non-TRANSPORT fault")

expected_hash = scenario_sha256(scenario)
if harness["scenarioHash"] != expected_hash:
    raise M2ContractError("harness scenarioHash mismatch")
if failures["runId"] != harness["runId"] or failures["sessionId"] != harness["sessionId"]:
    raise M2ContractError("runtime/harness run identity mismatch")
if budget["runId"] != harness["runId"] or budget["sessionId"] != harness["sessionId"]:
    raise M2ContractError("budget/harness run identity mismatch")

meta = harness["harness"]
validate_m2e_harness_binding("TRANSPORT", meta["harnessId"], meta["toolId"])
if meta["toolVersion"] != "2.12.0":
    raise M2ContractError("unexpected Toxiproxy version")

fault = scenario["transportFaults"][0]
variant = scenario["variant"]
params = fault["parameters"]
mapping = {
    "TRANSPORT_READ_TIMEOUT": ("timeout", "DOWNSTREAM", {"timeout": 0}),
    "TRANSPORT_RESET": ("reset_peer", "DOWNSTREAM", {"timeout": 0}),
    "TRUNCATED_STREAM": ("limit_data", "DOWNSTREAM", {"bytes": params.get("limitBytes")}),
    "SLOW_CLOSE": ("slow_close", "DOWNSTREAM", {"delay": params.get("delayMs")}),
}
if variant not in mapping:
    raise M2ContractError(f"unsupported verifier variant {variant!r}")
toxic_type, direction, attributes = mapping[variant]
if any(isinstance(v, bool) or not isinstance(v, int) or v < 0 for v in attributes.values()):
    raise M2ContractError("invalid canonical transport attributes")

events = harness["events"]
if [e["sequence"] for e in events] != list(range(1, len(events) + 1)):
    raise M2ContractError("harness sequence is not contiguous")
times = [e["elapsedRealtimeNs"] for e in events]
if any(b < a for a, b in zip(times, times[1:])):
    raise M2ContractError("HOST_FAULT_MONOTONIC time regressed")
expected_ops = [
    "HARNESS_STARTED", "FAULT_ARMED", "FAULT_APPLIED",
    "FAULT_REMOVED", "HARNESS_STOPPED",
]
if [e["operation"] for e in events] != expected_ops:
    raise M2ContractError("unexpected harness lifecycle")
for event in events:
    if event["plane"] != "TRANSPORT":
        raise M2ContractError("transport harness emitted a non-TRANSPORT event")
    if event["faultId"] != fault["faultId"] or event["kind"] != fault["kind"]:
        raise M2ContractError("harness event does not match resolved fault")
    if event["scope"] != "MEDIA_DATA_ONLY":
        raise M2ContractError("transport fault scope is not media-only")

applied = events[2]
expected_applied_params = {"toxicType": toxic_type, **attributes}
if applied["direction"] != direction or applied["parameters"] != expected_applied_params:
    raise M2ContractError(
        f"applied toxic mismatch: {applied['parameters']!r} != {expected_applied_params!r}"
    )
if applied["observedStateHash"] != canonical_hash(active):
    raise M2ContractError("FAULT_APPLIED state hash does not match independent readback")
if active.get("toxics") != [{
    "name": "m2e-fault",
    "type": toxic_type,
    "stream": direction.lower(),
    "toxicityPpm": 1_000_000,
    "attributes": attributes,
}]:
    raise M2ContractError(f"active Toxiproxy state mismatch: {active!r}")
if clean != {"proxies": 0, "toxics": 0}:
    raise M2ContractError(f"Toxiproxy cleanup mismatch: {clean!r}")
if events[-1]["result"] != "OK":
    raise M2ContractError("harness did not stop cleanly")

for row in failures.get("failures", []):
    observation = row["observation"]
    if observation["plane"] != "TRANSPORT":
        raise M2ContractError(
            f"transport run produced runtime plane {observation['plane']}"
        )
    if row["classification"] not in {"TRANSIENT_TRANSPORT", "TERMINAL_TRANSPORT"}:
        raise M2ContractError(
            f"transport observation classified as {row['classification']}"
        )
    if row["decision"]["kind"] in {
        "REFRESH_DELIVERY_BINDING", "RERESOLVE_PROVIDER", "WAIT_UNTIL_PROVIDER",
    }:
        raise M2ContractError("pure transport fault triggered provider action")

fetch_events = [
    json.loads(line)
    for line in fetch_path.read_text(encoding="utf-8").splitlines()
    if line.strip()
]
attempts = sum(1 for event in fetch_events if event.get("event") == "ATTEMPT_STARTED")
if attempts != result["physicalAttempts"]:
    raise M2ContractError("transport result attempt count mismatches fetch evidence")

origin_rows = [
    json.loads(line)
    for line in origin_path.read_text(encoding="utf-8").splitlines()
    if line.strip()
]
data_rows = [
    row for row in origin_rows
    if row.get("plane") == "data" and row.get("method") == "GET"
]
if len(data_rows) != attempts:
    raise M2ContractError(
        f"origin data requests {len(data_rows)} != physical attempts {attempts}"
    )

if variant == "SLOW_CLOSE":
    if result["terminalReason"] != "SUCCESS" or not result["published"] or attempts != 1:
        raise M2ContractError("SLOW_CLOSE did not preserve a completed response")
    if failures.get("failures"):
        raise M2ContractError("SLOW_CLOSE success unexpectedly recorded failures")
else:
    if result["terminalReason"] != "BUDGET_EXHAUSTED":
        raise M2ContractError("persistent transport fault did not exhaust the bounded chain")
    if result["published"]:
        raise M2ContractError("failed transport run published incomplete media")
    if attempts != 4:
        raise M2ContractError(f"persistent transport fault used {attempts} attempts, expected 4")

summary = {
    "schemaVersion": 1,
    "runId": harness["runId"],
    "sessionId": harness["sessionId"],
    "scenarioHash": expected_hash,
    "primaryPlane": "TRANSPORT",
    "variant": variant,
    "status": "PASS",
    "checks": {
        "scenarioIdentity": True,
        "faultOwnership": True,
        "toolStateMatches": True,
        "runtimeFailureSeparation": True,
        "boundedRecovery": True,
        "incompleteMediaNotPublished": (not result["published"]) if variant != "SLOW_CLOSE" else True,
        "cleanupComplete": True,
        "privacyClean": True,
        "crossClockArithmeticAbsent": True,
    },
    "physicalAttempts": attempts,
}
pathlib.Path(output_path).write_text(
    json.dumps(summary, indent=2, sort_keys=True) + "\n",
    encoding="utf-8",
)
PY

case_id="$(jq -r '.caseId' "$ANDROID_DIR/case.json")"
variant="$(jq -r '.variant' "$SCENARIO")"

if [[ "$variant" != "SLOW_CLOSE" ]]; then
  python3 "$RECOVERY_ORACLE" verify     --failures "$ANDROID_DIR/failure-decision-events.json"     --budget "$ANDROID_DIR/recovery-budget-events.json"     --fetch "$ANDROID_DIR/fetch-events.jsonl"     --case "$ANDROID_DIR/case.json"     --origin "$ORIGIN"     --output "$OUTPUT/recovery-verification-summary.json"     --require-gate M2-ACC-05     --require-gate M2-ACC-06
else
  jq -e '
    .terminalReason == "SUCCESS" and
    .physicalAttempts == 1 and
    .published == true
  ' "$ANDROID_DIR/transport-result.json" >/dev/null
fi

printf 'M2-E transport evidence verified: %s (%s)\n' "$case_id" "$variant"
