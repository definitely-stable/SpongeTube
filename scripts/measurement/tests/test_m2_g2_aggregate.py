from __future__ import annotations

import json
import pathlib
import shutil
import sys
import tempfile
import unittest

SCRIPT_DIR = pathlib.Path(__file__).resolve().parents[1]
REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(SCRIPT_DIR))

from m2_g2_aggregate import (  # noqa: E402
    EXPERIMENTS,
    G2AggregateError,
    collect,
    common_inputs,
    verify_index,
)
from m2_transport_pair_plan import (  # noqa: E402
    build_plan,
    load_object,
    planned_trial_schedule,
)


GIT_COMMIT = "a" * 40
CHECKOUT_COMMIT = "b" * 40
RESOURCE_LENGTH = 711_501


def trial_row(plan, planned):
    backend = planned["backendId"]
    return {
        "trialId": planned["trialId"],
        "orderingBlock": planned["orderingBlock"],
        "positionInBlock": planned["positionInBlock"],
        "backendId": backend,
        "backendVersion": (
            "control-v1"
            if backend == "HTTP_URL_CONNECTION_ROUTE_BOUND"
            else "platform-v1"
        ),
        "implementationId": backend.lower(),
        "eligibility": "ELIGIBLE",
        "comparison": plan["comparison"],
        "route": {"exactNetworkBound": True, "permitRouteEpoch": 1},
        "result": "SUCCESS",
        "requestCorrectness": {
            "range": "PASS",
            "contentRange": "PASS",
            "responseBounds": "PASS",
            "publishedBytes": "PASS",
        },
        "recovery": {
            "recoveryChainCount": 1,
            "ownerCount": 1,
            "originRequestCount": 1,
            "internalRetryVisibility": "OBSERVABLE",
            "internalRetryCount": 0,
        },
        "metrics": {
            "firstByteUs": 10_000,
            "completionUs": 100_000,
            "cancellationLatencyUs": None,
            "cpuTimeUs": 20_000,
            "maxRssBytes": 50_000_000,
            "bytesRequested": RESOURCE_LENGTH,
            "bytesReceived": RESOURCE_LENGTH,
            "bytesPublished": RESOURCE_LENGTH,
        },
        "negotiatedProtocol": "HTTP_1_1",
        "performanceSampleEligible": True,
        "limitations": [],
    }


def verification(spec):
    row = {
        "schemaVersion": 1,
        "phase": spec["phase"],
        "status": "PASS",
        "claimScope": "API36_EMULATOR_DIRECTIONAL_ONLY",
        "trialCount": 4,
        "pairedBlockCount": 2,
        "correctnessEquivalent": True,
        "recoveryEquivalent": True,
        "routeBindingEquivalent": True,
        "selectedBackend": None,
    }
    eid = spec["id"]
    if eid == "N2_HIGH_RTT_JITTER":
        row.update(
            netemSeed=424242,
            effectPositiveTrialCount=0,
            resilienceClaim="OBSERVED_EFFECT_EQUIVALENT",
        )
    elif eid == "N3_BURST_PACKET_LOSS":
        row.update(
            netemSeed=None,
            effectPositiveTrialCount=4,
            resilienceClaim="OBSERVED_EFFECT_EQUIVALENT",
        )
    elif eid == "N5_BURST_LOSS":
        row.update(
            claimScope="CONFIGURATION_BOUND_STOCHASTIC_OBSERVATION",
            netemSeed=424242,
            effectPositiveTrialCount=0,
            resilienceClaim="INCONCLUSIVE_STOCHASTIC_EFFECT",
        )
    elif eid == "N6_TRANSPORT_RESET":
        row["resetEffectTrialCount"] = 4
    elif eid == "N6_DEFAULT_ROUTE_LOSS_RESTORE":
        row["oldRouteOriginSilent"] = True
    return row


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def create_bundle(root: pathlib.Path):
    evidence = root / "evidence"
    generated = root / "generated"
    inputs = common_inputs()

    for spec in EXPERIMENTS:
        scenario = load_object(REPO_ROOT / spec["scenarioPath"])
        token = spec["id"].lower().replace("_", "-")
        plan = build_plan(
            run_id=f"test-{token}",
            pair_id=f"pair-{token}",
            scenario=scenario,
            inputs=inputs,
            ordering_seed=20261001,
            block_count=2,
            playback_mode="SPONGE",
            connection_state="COLD",
        )
        artifact_root = (
            evidence
            / spec["evidenceDir"]
            / pathlib.PurePosixPath(spec["rootSuffix"])
        )
        artifact_root.mkdir(parents=True, exist_ok=True)
        (artifact_root / "source-head-commit.txt").write_text(
            GIT_COMMIT + "\n",
            encoding="utf-8",
        )
        (artifact_root / "checkout-commit.txt").write_text(
            CHECKOUT_COMMIT + "\n",
            encoding="utf-8",
        )
        (artifact_root / "cleanup.txt").write_text("ok\n", encoding="utf-8")
        write_json(artifact_root / "plan.json", plan)

        trials = {
            "schemaVersion": 1,
            "runId": plan["runId"],
            "pairId": plan["pairId"],
            "deviceClass": "ANDROID_EMULATOR",
            "androidApi": 36,
            "clockDomain": "ANDROID_MONOTONIC",
            "orderingProtocol": plan["orderingProtocol"],
            "orderingSeed": plan["orderingSeed"],
            "trials": [
                trial_row(plan, row)
                for row in planned_trial_schedule(plan)
            ],
            "limitations": ["TEST_FIXTURE"],
        }
        write_json(
            artifact_root / "output/transport-evaluation-trials-v1.json",
            trials,
        )
        write_json(
            artifact_root / "output/verification.json",
            verification(spec),
        )

        raw = artifact_root / "raw"
        raw.mkdir(parents=True, exist_ok=True)
        for row in trials["trials"]:
            write_json(
                raw / f"{row['trialId']}.json",
                {
                    "schemaVersion": 1,
                    "proof": {
                        "recoveryJitterProtocol": "SHA256_COUNTER_REJECTION_V1",
                        "recoveryJitterSeed": 424243,
                        "recoveryJitterSampleCount": 0,
                        "recoveryJitterSamples": [],
                        "recoveryBackoffs": [],
                    },
                },
            )
    return evidence, generated


def experiment_root(
    evidence: pathlib.Path,
    experiment_id: str,
) -> pathlib.Path:
    spec = next(spec for spec in EXPERIMENTS if spec["id"] == experiment_id)
    return (
        evidence
        / spec["evidenceDir"]
        / pathlib.PurePosixPath(spec["rootSuffix"])
    )


class G2AggregateTest(unittest.TestCase):
    def test_collects_six_scenario_summaries_without_selecting_backend(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence, generated = create_bundle(pathlib.Path(tmp))
            index = collect(
                evidence_root=evidence,
                generated_root=generated,
                run_id="g2-test",
                git_commit=GIT_COMMIT,
            )
            self.assertEqual("PASS", index["status"])
            self.assertEqual(6, len(index["experiments"]))
            self.assertIsNone(index["aggregate"]["selectedBackend"])
            self.assertFalse(index["aggregate"]["performanceSelectionAllowed"])
            self.assertEqual(
                "INCONCLUSIVE_STOCHASTIC_EFFECT",
                index["aggregate"]["n5ResilienceEffect"],
            )
            verify_index(
                index,
                evidence_root=evidence,
                generated_root=generated,
                expected_git_commit=GIT_COMMIT,
            )

    def test_rejects_mixed_source_revision(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence, generated = create_bundle(pathlib.Path(tmp))
            (
                experiment_root(evidence, "N2_HIGH_RTT_JITTER")
                / "source-head-commit.txt"
            ).write_text("c" * 40 + "\n", encoding="utf-8")
            with self.assertRaisesRegex(
                G2AggregateError,
                "source-head commit mismatch",
            ):
                collect(
                    evidence_root=evidence,
                    generated_root=generated,
                    run_id="g2-test",
                    git_commit=GIT_COMMIT,
                )

    def test_rejects_mixed_checkout_revision(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence, generated = create_bundle(pathlib.Path(tmp))
            (
                experiment_root(evidence, "N6_TRANSPORT_RESET")
                / "checkout-commit.txt"
            ).write_text("c" * 40 + "\n", encoding="utf-8")
            with self.assertRaisesRegex(
                G2AggregateError,
                "mixed checkout revisions",
            ):
                collect(
                    evidence_root=evidence,
                    generated_root=generated,
                    run_id="g2-test",
                    git_commit=GIT_COMMIT,
                )

    def test_rejects_cross_scenario_backend_identity_drift(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence, generated = create_bundle(pathlib.Path(tmp))
            path = (
                experiment_root(evidence, "N3_BURST_PACKET_LOSS")
                / "output/transport-evaluation-trials-v1.json"
            )
            trials = json.loads(path.read_text(encoding="utf-8"))
            for row in trials["trials"]:
                if row["backendId"] == "PLATFORM_HTTP_ENGINE":
                    row["backendVersion"] = "platform-v2"
            write_json(path, trials)
            with self.assertRaisesRegex(
                G2AggregateError,
                "backend implementation/version drift across scenarios",
            ):
                collect(
                    evidence_root=evidence,
                    generated_root=generated,
                    run_id="g2-test",
                    git_commit=GIT_COMMIT,
                )

    def test_rejects_backend_selection_in_owning_verification(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence, generated = create_bundle(pathlib.Path(tmp))
            path = (
                experiment_root(evidence, "N0_CONTROL")
                / "output/verification.json"
            )
            value = json.loads(path.read_text(encoding="utf-8"))
            value["selectedBackend"] = "PLATFORM_HTTP_ENGINE"
            write_json(path, value)
            with self.assertRaisesRegex(G2AggregateError, "selected a backend"):
                collect(
                    evidence_root=evidence,
                    generated_root=generated,
                    run_id="g2-test",
                    git_commit=GIT_COMMIT,
                )

    def test_rejects_n5_effect_claim_upgrade(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence, generated = create_bundle(pathlib.Path(tmp))
            path = (
                experiment_root(evidence, "N5_BURST_LOSS")
                / "output/verification.json"
            )
            value = json.loads(path.read_text(encoding="utf-8"))
            value["resilienceClaim"] = "OBSERVED_EFFECT_EQUIVALENT"
            write_json(path, value)
            with self.assertRaisesRegex(
                G2AggregateError,
                "stochastic effect must remain inconclusive",
            ):
                collect(
                    evidence_root=evidence,
                    generated_root=generated,
                    run_id="g2-test",
                    git_commit=GIT_COMMIT,
                )

    def test_rejects_recovery_jitter_seed_drift(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence, generated = create_bundle(pathlib.Path(tmp))
            raw = next(
                (
                    experiment_root(evidence, "N2_HIGH_RTT_JITTER")
                    / "raw"
                ).glob("*.json")
            )
            value = json.loads(raw.read_text(encoding="utf-8"))
            value["proof"]["recoveryJitterSeed"] = 424242
            write_json(raw, value)
            with self.assertRaisesRegex(
                G2AggregateError,
                "recovery jitter seed drift",
            ):
                collect(
                    evidence_root=evidence,
                    generated_root=generated,
                    run_id="g2-test",
                    git_commit=GIT_COMMIT,
                )

    def test_rejects_missing_required_experiment(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence, generated = create_bundle(pathlib.Path(tmp))
            shutil.rmtree(evidence / "route")
            with self.assertRaisesRegex(
                G2AggregateError,
                "missing G2 artifact root",
            ):
                collect(
                    evidence_root=evidence,
                    generated_root=generated,
                    run_id="g2-test",
                    git_commit=GIT_COMMIT,
                )


if __name__ == "__main__":
    unittest.main()
