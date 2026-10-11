"""M3-A0 contract, budget, privacy and falsification tests."""

import copy
import json
import pathlib
import sys
import unittest

SCRIPT_DIR = pathlib.Path(__file__).resolve().parents[1]
REPO_ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from m3_contracts import (
    MAX_MEDIA_PROBE_BYTES,
    MAX_MEDIA_PROBES,
    M3ContractError,
    validate_probe_document,
    validate_probe_semantics,
)
from schema_subset import (
    SchemaContractError,
    validate_instance,
    validate_schema_definition,
)


SCHEMAS = REPO_ROOT / ".work" / "schemas"
EXAMPLES = SCHEMAS / "examples" / "m3"
SCHEMA_PATH = SCHEMAS / "m3-a-probe-v1.schema.json"
EXAMPLE_PATH = EXAMPLES / "m3-a-probe-v1.example.json"


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


def example():
    return load(EXAMPLE_PATH)


def viable():
    document = example()
    document["capabilities"]["https"] = "PRESENT"
    document["requirements"]["gvsPoToken"] = "NOT_REQUIRED"
    document["requirements"]["playerPoToken"] = "NOT_REQUIRED"
    document["requirements"]["challengeRuntime"] = "NOT_REQUIRED"
    document["delivery"]["startup"] = "PRESENT"
    document["outcome"]["classification"] = "VIABLE_ANONYMOUS"
    return document


def media_probe(
    probe_id="p0",
    *,
    byte_limit=MAX_MEDIA_PROBE_BYTES,
    bytes_read=1,
):
    return {
        "probeId": probe_id,
        "purpose": "RANGE",
        "byteLimit": byte_limit,
        "bytesRead": bytes_read,
    }


class M3A0SchemaContractTest(unittest.TestCase):
    def test_schema_and_contract_example_validate(self):
        schema = load(SCHEMA_PATH)
        validate_schema_definition(schema)
        self.assertEqual(
            "https://json-schema.org/draft/2020-12/schema",
            schema["$schema"],
        )
        validate_probe_document(example(), schema=schema)

    def test_missing_required_field_fails_closed(self):
        document = example()
        del document["videoCase"]
        with self.assertRaises(M3ContractError):
            validate_probe_document(document)

    def test_unknown_additional_field_fails_closed(self):
        document = example()
        document["providerUrl"] = "opaque"
        with self.assertRaises(M3ContractError):
            validate_probe_document(document)

    def test_unknown_observation_value_fails_schema(self):
        document = example()
        document["capabilities"]["dash"] = "MAYBE"
        with self.assertRaises(M3ContractError):
            validate_probe_document(document)

    def test_runner_must_be_github_hosted(self):
        document = example()
        document["environment"]["runnerKind"] = "LOCAL"
        with self.assertRaises(M3ContractError):
            validate_probe_document(document)

    def test_provider_content_identity_is_hash_only(self):
        document = example()
        document["videoCase"]["providerContentIdSha256"] = "dQw4w9WgXcQ"
        with self.assertRaises(M3ContractError):
            validate_probe_document(document)

    def test_schema_rejects_guardrail_relaxation(self):
        document = example()
        document["guardrails"]["fullDownloadAttempted"] = True
        with self.assertRaises(M3ContractError):
            validate_probe_document(document)


class M3A0BudgetTest(unittest.TestCase):
    def test_metadata_budget_is_bounded(self):
        document = example()
        document["budget"]["metadataRequestsUsed"] = 33
        with self.assertRaises(M3ContractError):
            validate_probe_document(document)

    def test_media_probe_cannot_exceed_one_mib(self):
        document = example()
        document["budget"]["mediaProbes"] = [
            media_probe(
                byte_limit=MAX_MEDIA_PROBE_BYTES + 1,
                bytes_read=1,
            )
        ]
        with self.assertRaises(M3ContractError):
            validate_probe_document(document)

    def test_bytes_read_cannot_exceed_declared_probe_limit(self):
        document = example()
        document["budget"]["mediaProbes"] = [
            media_probe(byte_limit=128, bytes_read=129)
        ]
        with self.assertRaises(M3ContractError):
            validate_probe_document(document)

    def test_media_probe_count_is_bounded(self):
        document = example()
        document["budget"]["mediaProbes"] = [
            media_probe(f"p{index}") for index in range(MAX_MEDIA_PROBES + 1)
        ]
        with self.assertRaises(M3ContractError):
            validate_probe_document(document)

    def test_duplicate_media_probe_id_fails(self):
        document = example()
        document["budget"]["mediaProbes"] = [
            media_probe("dup"),
            media_probe("dup"),
        ]
        with self.assertRaises(M3ContractError):
            validate_probe_document(document)


class M3A0OutcomeTest(unittest.TestCase):
    def test_viable_anonymous_requires_observed_startup_and_no_token_or_challenge_gate(
        self,
    ):
        validate_probe_document(viable())

        for field in ("gvsPoToken", "playerPoToken", "challengeRuntime"):
            with self.subTest(field=field):
                document = viable()
                document["requirements"][field] = "UNKNOWN"
                with self.assertRaises(M3ContractError):
                    validate_probe_document(document)

        document = viable()
        document["delivery"]["startup"] = "UNKNOWN"
        with self.assertRaises(M3ContractError):
            validate_probe_document(document)

    def test_token_gated_requires_observed_required_token(self):
        document = example()
        document["outcome"]["classification"] = "TOKEN_GATED"
        with self.assertRaises(M3ContractError):
            validate_probe_document(document)

        document["requirements"]["gvsPoToken"] = "REQUIRED"
        validate_probe_document(document)

    def test_challenge_gated_requires_required_challenge_runtime(self):
        document = example()
        document["outcome"]["classification"] = "CHALLENGE_GATED"
        with self.assertRaises(M3ContractError):
            validate_probe_document(document)

        document["requirements"]["challengeRuntime"] = "REQUIRED"
        validate_probe_document(document)

    def test_sabr_only_is_a_strong_observation_claim(self):
        document = example()
        document["outcome"]["classification"] = "SABR_ONLY"
        document["capabilities"]["sabr"] = "PRESENT"
        document["capabilities"]["dash"] = "ABSENT"
        document["capabilities"]["hls"] = "ABSENT"
        validate_probe_document(document)

        broken = copy.deepcopy(document)
        broken["capabilities"]["dash"] = "UNKNOWN"
        with self.assertRaises(M3ContractError):
            validate_probe_document(broken)

    def test_drm_gated_requires_observed_drm(self):
        document = example()
        document["outcome"]["classification"] = "DRM_GATED"
        with self.assertRaises(M3ContractError):
            validate_probe_document(document)

        document["capabilities"]["drm"] = "PRESENT"
        validate_probe_document(document)


class M3A0ReferenceOracleTest(unittest.TestCase):
    def test_reference_roles_are_frozen(self):
        expected = {
            "YT_DLP": "REFERENCE_ORACLE",
            "YOUTUBE_JS": "REFERENCE_ORACLE",
            "BGUTILS": "TOKEN_CHALLENGE_REFERENCE",
            "NEWPIPE_EXTRACTOR": "COMPARISON_ONLY",
            "INVIDIOUS": "COMPARISON_ONLY",
        }
        for oracle_id, role in expected.items():
            with self.subTest(oracle=oracle_id):
                document = example()
                document["references"] = [
                    {
                        "id": oracle_id,
                        "version": "pinned-version",
                        "revision": "pinned-revision",
                        "role": role,
                        "productionDependency": False,
                    }
                ]
                validate_probe_document(document)

    def test_reference_cannot_be_promoted_to_production_dependency(self):
        document = example()
        document["references"][0]["productionDependency"] = True
        with self.assertRaises(M3ContractError):
            validate_probe_document(document)

    def test_reference_role_mismatch_fails(self):
        document = example()
        document["references"][0]["role"] = "COMPARISON_ONLY"
        with self.assertRaises(M3ContractError):
            validate_probe_document(document)

    def test_duplicate_reference_fails(self):
        document = example()
        document["references"].append(
            copy.deepcopy(document["references"][0])
        )
        with self.assertRaises(M3ContractError):
            validate_probe_document(document)


class M3A0PrivacyTest(unittest.TestCase):
    def test_signed_url_is_rejected_even_inside_free_text(self):
        document = example()
        document["limitations"] = [
            "saw https://example.invalid/videoplayback?sig=secret"
        ]
        with self.assertRaises(M3ContractError):
            validate_probe_document(document)

    def test_raw_ip_is_rejected_even_inside_free_text(self):
        document = example()
        document["limitations"] = ["egress 203.0.113.17"]
        with self.assertRaises(M3ContractError):
            validate_probe_document(document)

    def test_secret_class_key_is_rejected(self):
        document = example()
        # Semantic validation is callable independently so a future schema
        # extension cannot silently admit secret-class fields.
        document["requirements"]["poToken"] = "redacted-would-still-be-forbidden"
        with self.assertRaises(M3ContractError):
            validate_probe_semantics(document)


class M3A0SchemaSubsetTest(unittest.TestCase):
    def test_m3_schema_uses_supported_repository_subset(self):
        validate_schema_definition(load(SCHEMA_PATH))

    def test_raw_schema_validator_rejects_extra_property(self):
        schema = load(SCHEMA_PATH)
        document = example()
        document["client"]["rawUserAgent"] = "forbidden-extra-field"
        with self.assertRaises(SchemaContractError):
            validate_instance(schema, document)


if __name__ == "__main__":
    unittest.main()
