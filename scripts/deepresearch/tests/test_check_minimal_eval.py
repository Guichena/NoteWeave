import copy
import importlib.util
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
MODULE_PATH = ROOT / "scripts/deepresearch/check_minimal_eval.py"
MANIFEST_PATH = ROOT / "experiments/deep-research/minimal-eval/manifest.json"
DATASET_PATH = ROOT / "experiments/deep-research/datasets/research-eval-v1.json"


def load_module():
    spec = importlib.util.spec_from_file_location("check_minimal_eval", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class MinimalEvalManifestTest(unittest.TestCase):
    def setUp(self):
        self.module = load_module()
        self.manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        self.dataset = json.loads(DATASET_PATH.read_text(encoding="utf-8"))

    @staticmethod
    def reset_runtime(manifest):
        for case in manifest["cases"]:
            case["runtime"] = {
                "status": "PENDING_RUNTIME",
                "run_id": None,
                "terminal_state": None,
                "evidence_file": None,
                "metrics": None,
            }

    def test_accepts_the_frozen_eight_case_resume_claim_suite(self):
        self.reset_runtime(self.manifest)
        summary = self.module.validate(self.manifest, self.dataset)

        self.assertEqual(8, summary["case_count"])
        self.assertEqual(
            {
                "CITATION_REJECTION": 1,
                "CONFLICT": 1,
                "MISSING": 2,
                "NORMAL": 2,
                "PROVIDER_FAILURE": 1,
                "WORKER_RECOVERY": 1,
            },
            summary["scenario_counts"],
        )
        self.assertEqual(8, summary["pending_runtime_count"])

    def test_rejects_a_runtime_pass_without_a_real_run_id(self):
        manifest = copy.deepcopy(self.manifest)
        self.reset_runtime(manifest)
        manifest["cases"][0]["runtime"]["status"] = "PASSED"

        with self.assertRaisesRegex(self.module.ManifestError, "run_id"):
            self.module.validate(manifest, self.dataset)

    def test_rejects_scenario_count_drift(self):
        manifest = copy.deepcopy(self.manifest)
        manifest["cases"][2]["scenario"] = "NORMAL"

        with self.assertRaisesRegex(self.module.ManifestError, "scenario counts"):
            self.module.validate(manifest, self.dataset)


if __name__ == "__main__":
    unittest.main()
