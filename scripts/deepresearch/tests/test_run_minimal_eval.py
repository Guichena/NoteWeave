import importlib.util
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
MODULE_PATH = ROOT / "scripts/deepresearch/run_minimal_eval.py"
MANIFEST_PATH = ROOT / "experiments/deep-research/minimal-eval/manifest.json"


def load_module():
    spec = importlib.util.spec_from_file_location("run_minimal_eval", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class MinimalEvalRunnerTest(unittest.TestCase):
    def setUp(self):
        self.module = load_module()
        self.manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))

    def test_default_selection_contains_only_direct_real_run_cases(self):
        cases = self.module.selected_cases(self.manifest, set())
        self.assertEqual(5, len(cases))
        self.assertEqual({"NORMAL", "MISSING", "CONFLICT"}, {case["scenario"] for case in cases})

    def test_refuses_to_fake_a_fault_injection_case(self):
        with self.assertRaisesRegex(self.module.EvalRunError, "manual injection"):
            self.module.selected_cases(self.manifest, {"min-worker-crash-after-fetch"})

    def test_explicit_fault_mode_selects_only_the_requested_fault_case(self):
        selected = self.module.selected_cases(
            self.manifest, {"min-provider-invalid-json"}, allow_fault_injection=True
        )
        self.assertEqual(["min-provider-invalid-json"], [case["case_key"] for case in selected])

    def test_rejects_unknown_case_key(self):
        with self.assertRaisesRegex(self.module.EvalRunError, "unknown case"):
            self.module.selected_cases(self.manifest, {"not-a-real-case"})

    def test_optional_legacy_projection_does_not_discard_a_terminal_run(self):
        class MissingProjectionClient:
            def request(self, method, path):
                raise self_module.EvalRunError("RESEARCH_EVIDENCE_MANIFEST_NOT_FOUND")

        self_module = self.module
        captured = self.module.capture_optional_endpoint(MissingProjectionClient(), "/evidence")

        self.assertFalse(captured["available"])
        self.assertIn("RESEARCH_EVIDENCE_MANIFEST_NOT_FOUND", captured["error"])


if __name__ == "__main__":
    unittest.main()
