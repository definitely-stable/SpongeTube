from __future__ import annotations

import copy
import json
import pathlib
import sys
import tempfile
import unittest

TEST_DIR = pathlib.Path(__file__).resolve().parent
SCRIPT_DIR = TEST_DIR.parent
sys.path.insert(0, str(TEST_DIR))
sys.path.insert(0, str(SCRIPT_DIR))

from m2_acceptance import M2AcceptanceError, collect, verify_index  # noqa: E402
from m2_transport_decision import build_decision  # noqa: E402
from test_m2_transport_decision import source_document  # noqa: E402

GIT_COMMIT = "a" * 40


def write_json(path: pathlib.Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def gate(status="PASS"):
    return {"status": status, "checks": ["synthetic proof"]}


def route_summary():
    return {
        "schemaVersion": 1,
        "runId": "m2-b-device",
        "sessionId": "m2-b-device-session",
        "androidApi": 36,
        "status": "PASS",
        "eventCount": 3,
        "policyEvaluationCount": 1,
        "epochsObserved": 1,
        "sourcesObserved": ["MONITOR_LIFECYCLE", "BOOTSTRAP_ACTIVE_NETWORK"],
        "casesPassed": ["synthetic"],
        "limitations": ["synthetic test fixture"],
    }


def recovery_summary():
    return {
        "schemaVersion": 1,
        "runId": "m2-c-device",
        "sessionId": "m2-c-device-session",
        "policyId": "sponge-recovery-v2",
        "status": "PASS",
        "chainCount": 1,
        "physicalAttemptCount": 2,
        "chargedAttemptCount": 2,
        "failureCount": 1,
        "originRequestCount": 2,
        "terminalCounts": {"SUCCESS": 1},
        "classificationCounts": {"TRANSIENT_TRANSPORT": 1},
        "decisionCounts": {"RETRY": 1},
        "actionCounts": {"REMOTE_ATTEMPT": 2},
        "gates": {
            "M2-ACC-05": gate(),
            "M2-ACC-06": gate(),
        },
        "limitations": ["synthetic test fixture"],
    }


def provider_summary(family: str, variant: str):
    return {
        "schemaVersion": 1,
        "caseId": variant.lower(),
        "runId": f"run-{variant.lower()}",
        "sessionId": f"session-{variant.lower()}",
        "policyId": "sponge-recovery-v2",
        "status": "PASS",
        "evidenceSource": "ANDROID_MEDIA_LAB",
        "scenarioFamily": family,
        "variant": variant,
        "chainCount": 1,
        "physicalAttemptCount": 2,
        "remoteAttemptChargeCount": 2,
        "originRequestCount": 2,
        "refreshOperationCount": 1 if family == "N10" else 0,
        "refreshChargeCount": 1 if family == "N10" else 0,
        "joinedRefreshCount": 0,
        "alreadyAdvancedCount": 0,
        "providerWaitCount": 1 if family == "N9" else 0,
        "bindingRevisions": ["binding-1", "binding-2"] if family == "N10" else ["binding-1"],
        "gates": {
            "M2-ACC-05": gate(),
            "M2-ACC-06": gate(),
            "M2-ACC-07": gate(),
            "M2-ACC-08": gate(),
        },
        "limitations": ["synthetic test fixture"],
    }


def fault_summary(plane: str, token: str):
    return {
        "schemaVersion": 1,
        "runId": f"run-{token}",
        "sessionId": f"session-{token}",
        "scenarioHash": "b" * 64,
        "status": "PASS",
        "primaryPlane": plane,
        "checks": {
            "scenarioIdentity": True,
            "faultOwnership": True,
            "toolStateMatches": True,
            "mediaPathTraversed": True,
            "controlPathUnimpaired": True,
            "seedBound": True,
            "cleanupComplete": True,
            "privacyClean": True,
            "crossClockArithmeticAbsent": True,
        },
        "gates": {
            "M2-ACC-01": True,
            "M2-ACC-02": True,
            "M2-ACC-09": True,
        },
        "limitations": ["synthetic test fixture"],
    }


def f_summary(case_kind: str, privacy: str):
    return {
        "schemaVersion": 1,
        "runId": f"run-{case_kind.lower()}",
        "sessionId": f"session-{case_kind.lower()}",
        "caseKind": case_kind,
        "scenarioHash": "c" * 64,
        "semanticDigest": "d" * 64,
        "status": "PASS",
        "routeEventCount": 3,
        "physicalAttemptCount": 2,
        "gates": {
            "M2-ACC-03": privacy,
            "M2-ACC-04": "PASS",
            "M2-ACC-05": "PASS",
            "M2-ACC-06": "PASS",
        },
        "checks": ["synthetic proof"],
        "limitations": ["synthetic test fixture"],
    }


def create_bundle(root: pathlib.Path):
    smoke = root / "smoke"
    transport = root / "transport"
    network = root / "network"
    f2 = root / "f2"
    f3 = root / "f3"
    g3 = root / "g3"
    retained = root / "retained"

    for evidence_root in (smoke, transport, network, f2, f3):
        evidence_root.mkdir(parents=True, exist_ok=True)
        (evidence_root / "source-sha.txt").write_text(GIT_COMMIT + "\n", encoding="utf-8")

    write_json(smoke / "m2-b-route" / "route-verification-summary.json", route_summary())
    write_json(smoke / "m2-c-evidence" / "recovery-verification-summary.json", recovery_summary())

    providers = {
        "N8_BARE_403": ("N8", "HTTP_403_BARE"),
        "N9_429_DELAY_SECONDS": ("N9", "HTTP_429_RETRY_AFTER_DELAY_SECONDS"),
        "N9_429_HTTP_DATE": ("N9", "HTTP_429_RETRY_AFTER_HTTP_DATE"),
        "N10_BINDING_EXPIRED_REFRESH": ("N10", "BINDING_EXPIRY_REFRESH"),
    }
    for scenario, (family, variant) in providers.items():
        write_json(
            smoke / "m2-d-provider" / scenario / "provider-verification-summary.json",
            provider_summary(family, variant),
        )

    for token in ("read-timeout", "reset", "truncated", "slow-close"):
        write_json(
            transport / token / "verified" / "fault-verification-summary.json",
            fault_summary("TRANSPORT", token),
        )
    for token in ("n2", "n3", "n5"):
        write_json(
            network / token / "verified" / "fault-verification-summary.json",
            fault_summary("NETWORK", token),
        )

    write_json(
        f2 / "evidence" / "f4-verification-summary.json",
        f_summary("F2_DEFAULT_ROUTE_LOSS_RESTORE", "NOT_APPLICABLE"),
    )
    write_json(
        f3 / "evidence" / "VPN_RESTORE" / "f4-verification-summary.json",
        f_summary("F3_VPN_CONTINUITY_RESTORE", "PASS"),
    )
    write_json(
        f3 / "evidence" / "DIRECT_OVERRIDE" / "f4-verification-summary.json",
        f_summary("F3_VPN_CONTINUITY_DIRECT_OVERRIDE", "PASS"),
    )

    source = source_document()
    g2_path = g3 / "source" / "m2-g2-evidence-index-v1.json"
    write_json(g2_path, source)
    decision = build_decision(
        source,
        source_path=g2_path,
        expected_git_commit=GIT_COMMIT,
    )
    write_json(g3 / "output" / "m2-g3-transport-decision-v1.json", decision)

    return smoke, transport, network, f2, f3, g3, retained


class M2AcceptanceTest(unittest.TestCase):
    def test_collects_exact_ten_gate_acceptance(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            smoke, transport, network, f2, f3, g3, retained = create_bundle(root)
            index = collect(
                smoke_root=smoke,
                transport_root=transport,
                network_root=network,
                f2_root=f2,
                f3_root=f3,
                g3_root=g3,
                retained_root=retained,
                run_id="m2-h-test",
                git_commit=GIT_COMMIT,
            )
            self.assertEqual("PASS", index["status"])
            self.assertEqual(10, index["gateCount"])
            self.assertEqual(
                {f"M2-ACC-{i:02d}" for i in range(1, 11)},
                {row["gateId"] for row in index["gates"]},
            )
            self.assertIsNone(index["transportDecision"]["selectedBackend"])
            self.assertFalse(index["transportDecision"]["performanceSelectionAllowed"])
            self.assertTrue(all(row["sourceArtifact"] for row in index["artifacts"]))
            self.assertEqual(
                sorted(index["gates"][0]["proofs"]),
                index["gates"][0]["proofs"],
            )
            verify_index(index, retained_root=retained, expected_git_commit=GIT_COMMIT)

    def test_rejects_source_revision_drift(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            smoke, transport, network, f2, f3, g3, retained = create_bundle(root)
            (network / "source-sha.txt").write_text("e" * 40 + "\n", encoding="utf-8")
            with self.assertRaisesRegex(M2AcceptanceError, "source SHA"):
                collect(
                    smoke_root=smoke,
                    transport_root=transport,
                    network_root=network,
                    f2_root=f2,
                    f3_root=f3,
                    g3_root=g3,
                    retained_root=retained,
                    run_id="m2-h-test",
                    git_commit=GIT_COMMIT,
                )

    def test_rejects_missing_provider_scenario(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            smoke, transport, network, f2, f3, g3, retained = create_bundle(root)
            target = next(smoke.rglob("N8_BARE_403/provider-verification-summary.json"))
            target.unlink()
            with self.assertRaises(M2AcceptanceError):
                collect(
                    smoke_root=smoke,
                    transport_root=transport,
                    network_root=network,
                    f2_root=f2,
                    f3_root=f3,
                    g3_root=g3,
                    retained_root=retained,
                    run_id="m2-h-test",
                    git_commit=GIT_COMMIT,
                )

    def test_rejects_tampered_retained_proof(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            smoke, transport, network, f2, f3, g3, retained = create_bundle(root)
            index = collect(
                smoke_root=smoke,
                transport_root=transport,
                network_root=network,
                f2_root=f2,
                f3_root=f3,
                g3_root=g3,
                retained_root=retained,
                run_id="m2-h-test",
                git_commit=GIT_COMMIT,
            )
            proof = retained / index["gates"][0]["proofs"][0]
            proof.write_text(proof.read_text(encoding="utf-8") + " ", encoding="utf-8")
            with self.assertRaisesRegex(M2AcceptanceError, "digest mismatch"):
                verify_index(index, retained_root=retained, expected_git_commit=GIT_COMMIT)

    def test_rejects_semantic_tamper_even_when_index_digest_is_recomputed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            smoke, transport, network, f2, f3, g3, retained = create_bundle(root)
            index = collect(
                smoke_root=smoke,
                transport_root=transport,
                network_root=network,
                f2_root=f2,
                f3_root=f3,
                g3_root=g3,
                retained_root=retained,
                run_id="m2-h-test",
                git_commit=GIT_COMMIT,
            )
            relative = "m2-d/HTTP_403_BARE/provider-verification-summary.json"
            proof = retained / relative
            document = json.loads(proof.read_text(encoding="utf-8"))
            document["gates"]["M2-ACC-08"]["status"] = "NOT_EXERCISED"
            write_json(proof, document)
            for row in index["artifacts"]:
                if row["path"] == relative:
                    import hashlib
                    row["sha256"] = hashlib.sha256(proof.read_bytes()).hexdigest()
                    row["sizeBytes"] = proof.stat().st_size
                    break
            with self.assertRaisesRegex(M2AcceptanceError, "retained M2-ACC-08 not PASS"):
                verify_index(index, retained_root=retained, expected_git_commit=GIT_COMMIT)

    def test_canonicalizes_fault_proof_order_independent_of_scenario_hash(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            smoke, transport, network, f2, f3, g3, retained = create_bundle(root)
            fault_paths = sorted(transport.rglob("fault-verification-summary.json"))
            first = json.loads(fault_paths[0].read_text(encoding="utf-8"))
            last = json.loads(fault_paths[-1].read_text(encoding="utf-8"))
            first["scenarioHash"] = "f" * 64
            last["scenarioHash"] = "0" * 64
            write_json(fault_paths[0], first)
            write_json(fault_paths[-1], last)
            index = collect(
                smoke_root=smoke,
                transport_root=transport,
                network_root=network,
                f2_root=f2,
                f3_root=f3,
                g3_root=g3,
                retained_root=retained,
                run_id="m2-h-order-test",
                git_commit=GIT_COMMIT,
            )
            self.assertEqual(
                sorted(index["gates"][0]["proofs"]),
                index["gates"][0]["proofs"],
            )
            verify_index(index, retained_root=retained, expected_git_commit=GIT_COMMIT)

    def test_rejects_duplicate_gate_in_index(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            smoke, transport, network, f2, f3, g3, retained = create_bundle(root)
            index = collect(
                smoke_root=smoke,
                transport_root=transport,
                network_root=network,
                f2_root=f2,
                f3_root=f3,
                g3_root=g3,
                retained_root=retained,
                run_id="m2-h-test",
                git_commit=GIT_COMMIT,
            )
            broken = copy.deepcopy(index)
            broken["gates"][-1]["gateId"] = "M2-ACC-09"
            with self.assertRaisesRegex(M2AcceptanceError, "unique 10/10"):
                verify_index(broken, retained_root=retained, expected_git_commit=GIT_COMMIT)


if __name__ == "__main__":
    unittest.main()
