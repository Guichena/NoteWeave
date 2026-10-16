import importlib.util
import json
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
SCRIPTS = ROOT / "scripts/deepresearch"
DATASET_PATH = ROOT / "experiments/deep-research/quality-eval/dataset.json"


def load_module(name):
    sys.path.insert(0, str(SCRIPTS))
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def cell(status, column="维度", claims=1, sources=1):
    return {
        "cell_key": f"e:{column}", "entity_label": "E", "column_label": column, "cell_status": status,
        "candidate_value": "v", "independent_source_count": sources, "confidence_ppm": 800000, "conflicts": [],
        "equivalent_claims": [{"evidence_key": f"k{i}", "claim_text": "c", "source_domain": "d"} for i in range(claims)],
    }


def record(cells, queries=4, high_risk=()):
    return {
        "run_id": "run-1",
        "run_detail": {
            "status": "COMPLETED",
            "final_report_markdown": "# 报告",
            "research_process_summary": {"search_read_timeline": {"all_search_queries": ["q"] * queries}},
        },
        "resources": {
            "plan": {"available": True, "data": {
                "rows": [{"entity_key": "e1", "label": "PostgreSQL 17"}],
                "columns": [{"label": label, "high_risk": True} for label in high_risk],
            }},
            "cells": {"available": cells is not None, "data": cells},
            "counter-checks": {"available": True, "data": [{"verdict": "NOT_RUN"}, {"verdict": "REFUTED"}]},
        },
    }


def judge_json(**overrides):
    payload = {
        "scores": {dim: {"score": 0.9, "rationale": "ok"} for dim in
                   ("factual_accuracy", "citation_accuracy", "completeness", "source_quality", "tool_efficiency")},
        "must_cover": [{"item": "a", "covered": True}],
        "pass": True,
        "summary": "s",
    }
    payload.update(overrides)
    return json.dumps(payload, ensure_ascii=False)


class QualityEvalTest(unittest.TestCase):
    def setUp(self):
        self.score = load_module("score_quality_eval")
        self.runner = load_module("run_quality_eval")
        self.dataset = json.loads(DATASET_PATH.read_text(encoding="utf-8"))
        self.case = {"case_key": "c", "question_type": "COMPARISON", "question": "q",
                     "must_cover": ["a"], "expected_entities": ["PostgreSQL 17", "MySQL 8.4"]}

    def test_dataset_has_twenty_unique_cases_with_must_cover(self):
        cases = self.dataset["cases"]
        self.assertEqual(20, len(cases))
        self.assertEqual(len(cases), len({case["case_key"] for case in cases}))
        self.assertTrue(all(case["question_type"] in self.dataset["question_types"] for case in cases))
        self.assertTrue(all(len(case["must_cover"]) >= 3 for case in cases))

    def test_effective_cells_require_verified_claims_and_two_sources_on_high_risk_columns(self):
        cells = [cell("VERIFIED"), cell("VERIFIED", claims=0), cell("GAP"),
                 cell("VERIFIED", column="风险", sources=1), cell("VERIFIED", column="风险2", sources=2)]
        metrics = self.score.structural_metrics(self.case, record(cells, queries=4, high_risk=("风险", "风险2")))

        self.assertEqual(5, metrics["planned_cells"])
        self.assertEqual(2, metrics["effective_cells"])
        self.assertEqual(0.4, metrics["effective_cell_rate"])
        self.assertEqual(2.0, metrics["queries_per_effective_cell"])
        self.assertEqual(0.5, metrics["expected_entity_coverage"])
        self.assertEqual(1, metrics["counter_check_not_run"])

    def test_missing_cells_endpoint_is_reported_instead_of_counted_as_zero(self):
        metrics = self.score.structural_metrics(self.case, record(None))
        self.assertFalse(metrics["cells_available"])
        self.assertNotIn("planned_cells", metrics)

    def test_unconfigured_judge_is_not_run_and_never_passes(self):
        result = self.score.score_case(self.case, record([cell("VERIFIED")]), None)
        self.assertEqual("NOT_RUN", result["judge"]["status"])
        self.assertIsNone(result["passed"])

    def test_invalid_judge_output_is_retried_once_with_the_error(self):
        outputs = iter(["不是 JSON", judge_json()])
        seen = []

        def call(messages):
            seen.append(messages)
            return next(outputs)

        result = self.score.score_case(self.case, record([cell("VERIFIED")]), call)
        self.assertEqual("SCORED", result["judge"]["status"])
        self.assertTrue(result["passed"])
        self.assertIn("不合规", seen[1][-1]["content"])

    def test_two_invalid_outputs_become_not_run(self):
        out_of_range = judge_json(scores={"factual_accuracy": 1.5})
        result = self.score.score_case(self.case, record([cell("VERIFIED")]), lambda _messages: out_of_range)
        self.assertEqual("JUDGE_OUTPUT_INVALID", result["judge"]["reason"])

    def test_low_citation_accuracy_fails_even_when_judge_passes(self):
        scores = {dim: {"score": 0.9} for dim in
                  ("factual_accuracy", "completeness", "source_quality", "tool_efficiency")}
        scores["citation_accuracy"] = {"score": 0.5}
        result = self.score.score_case(self.case, record([cell("VERIFIED")]), lambda _m: judge_json(scores=scores))
        self.assertFalse(result["passed"])

    def test_aggregate_uses_micro_average_for_effective_cell_rate(self):
        results = [
            self.score.score_case(self.case, record([cell("VERIFIED")]), lambda _m: judge_json()),
            self.score.score_case(self.case, record([cell("GAP"), cell("GAP"), cell("VERIFIED")]), None),
            self.score.score_case(self.case, record(None), None),
        ]
        summary = self.score.aggregate(results, self.dataset)

        self.assertEqual(0.5, summary["effective_cell_rate"])
        self.assertEqual(1, summary["judge_scored"])
        self.assertEqual(1, summary["runs_without_cells"])
        self.assertEqual(1.0, summary["by_question_type"]["COMPARISON"]["pass_rate"])
        self.assertIn("| c |", self.score.render_markdown(summary))

    def test_runner_selects_requested_cases_and_rejects_unknown(self):
        self.assertEqual(20, len(self.runner.selected_cases(self.dataset, set())))
        with self.assertRaises(self.runner.EvalRunError):
            self.runner.selected_cases(self.dataset, {"no-such-case"})


if __name__ == "__main__":
    unittest.main()
