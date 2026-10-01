from __future__ import annotations

import importlib.util
import pathlib
import sys
import unittest

CI = pathlib.Path(__file__).resolve().parents[2] / "ci"
spec = importlib.util.spec_from_file_location(
    "m2_g1_response_lab", CI / "m2_g1_response_lab.py",
)
assert spec and spec.loader
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)


class G1ResponseLabContractTests(unittest.TestCase):
    def test_redirect_is_bodyless_and_relative(self):
        plan = module.response_plan("/redirect")
        self.assertEqual(302, plan.status)
        self.assertEqual(0, plan.body_length)
        self.assertIn(("Location", "/binding"), plan.headers)

    def test_wrong_content_range_is_deliberately_invalid(self):
        plan = module.response_plan("/wrong-content-range")
        self.assertEqual(206, plan.status)
        self.assertIn(("Content-Range", "bytes 1-81810/81811"), plan.headers)
        self.assertEqual(0, plan.body_length)

    def test_overlong_body_is_chunked_and_one_byte_too_large(self):
        plan = module.response_plan("/overlong-body")
        self.assertEqual(206, plan.status)
        self.assertTrue(plan.chunked)
        self.assertEqual(module.RESOURCE_LENGTH + 1, plan.body_length)
        self.assertIn(("Content-Range", "bytes 0-81810/81811"), plan.headers)

    def test_binding_case_is_valid_exact_range(self):
        plan = module.response_plan("/binding")
        self.assertEqual(206, plan.status)
        self.assertFalse(plan.chunked)
        self.assertEqual(module.RESOURCE_LENGTH, plan.body_length)
        self.assertIn(("Content-Length", str(module.RESOURCE_LENGTH)), plan.headers)

    def test_unknown_path_is_not_a_silent_valid_case(self):
        with self.assertRaises(KeyError):
            module.response_plan("/unknown")


if __name__ == "__main__":
    unittest.main()
