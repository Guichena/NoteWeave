import importlib.util
import json
import sys
import unittest
from datetime import datetime, timezone
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch


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

    def test_answer_policy_metrics_keep_mandatory_and_optional_counts_separate(self):
        run = record([])
        run["resources"]["requirements"] = {"available": True, "data": {"enabled": True,
            "requirement_answer_policy_version": 1, "required_count": 3, "satisfied_count": 2,
            "optional_count": 4, "optional_satisfied_count": 1}}
        metrics = self.score.structural_metrics(self.case, run)["requirement_coverage"]
        self.assertEqual(3, metrics["required_count"])
        self.assertEqual(4, metrics["optional_count"])
        self.assertEqual(1, metrics["requirement_answer_policy_version"])

    def test_chat_judge_selection_preserves_credentials_in_process_and_normalizes_endpoint(self):
        env = {"NOTEWEAVE_LLM_ENDPOINT":"https://chat.example/v1/chat/completions",
               "NOTEWEAVE_LLM_API_KEY":"fixture-key", "NOTEWEAVE_LLM_MODEL":"fixture-model"}
        self.score.configure_judge_from_chat(env)
        self.assertEqual("https://chat.example/v1",env["NOTEWEAVE_EVAL_JUDGE_BASE_URL"])
        self.assertEqual("fixture-key",env["NOTEWEAVE_EVAL_JUDGE_API_KEY"])
        self.assertEqual("fixture-model",env["NOTEWEAVE_EVAL_JUDGE_MODEL"])
        self.assertEqual("CHAT_MODEL",env["NOTEWEAVE_EVAL_JUDGE_CONFIG_SOURCE"])
        with self.assertRaises(ValueError):
            self.score.configure_judge_from_chat({"NOTEWEAVE_LLM_MODEL":"fixture-model"})

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

    def test_majority_of_judge_samples_decides_and_cache_reuses_verdicts(self):
        replies = iter([judge_json(), judge_json(**{"pass": False}), judge_json(**{"pass": False})])
        calls = []

        def call(messages):
            calls.append(messages)
            return next(replies)

        cache = {}
        result = self.score.score_case(self.case, record([cell("VERIFIED")]), call, samples=3, cache=cache)
        self.assertFalse(result["passed"])
        self.assertEqual((result["judge"]["samples"], result["judge"]["pass_votes"]), (3, 1))
        self.assertEqual(len(calls), 3)

        again = self.score.score_case(self.case, record([cell("VERIFIED")]), lambda _m: judge_json(), samples=3,
                                      cache=cache)
        self.assertFalse(again["passed"])
        changed = record([cell("VERIFIED")])
        changed["run_detail"]["final_report_markdown"] = "# 新报告"
        fresh = self.score.score_case(self.case, changed, lambda _m: judge_json(), samples=3, cache=cache)
        self.assertTrue(fresh["passed"])

    def test_incomplete_judge_samples_never_pass_and_cached_samples_can_be_resumed(self):
        replies = iter([judge_json(), "invalid", "invalid"])
        cache = {}
        partial = self.score.score_case(self.case, record([cell("VERIFIED")]), lambda _m: next(replies),
                                       samples=3, cache=cache)
        self.assertEqual("NOT_RUN", partial["judge"]["status"])
        self.assertEqual("JUDGE_SAMPLES_INCOMPLETE", partial["judge"]["reason"])
        self.assertEqual((1, 3), (partial["judge"]["samples"], partial["judge"]["required_samples"]))
        self.assertIsNone(partial["passed"])
        self.assertEqual(0, self.score.aggregate([partial], self.dataset)["judge_scored"])
        calls = []

        def resume(messages):
            calls.append(messages)
            return judge_json()

        complete = self.score.score_case(self.case, record([cell("VERIFIED")]), resume, samples=3, cache=cache)
        self.assertEqual(2, len(calls))
        self.assertEqual("SCORED", complete["judge"]["status"])
        self.assertEqual(3, complete["judge"]["samples"])
        self.assertTrue(complete["passed"])

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

    def test_runner_writes_to_the_selected_directory_without_overwriting_records(self):
        with TemporaryDirectory() as folder:
            runs_dir = Path(folder)
            with patch.object(self.runner, "wait_for_terminal", return_value={"status": "COMPLETED"}), \
                    patch.object(self.runner, "capture_optional_endpoint", return_value={"available": False}):
                args = (None, "workspace", self.case, "1.1.0", "run", datetime.now(timezone.utc), 5, 1, runs_dir)
                output = self.runner.capture_run(*args)
                saved = output.read_text(encoding="utf-8")
                self.assertEqual(runs_dir / "c.json", output)
                self.assertEqual("1.1.0", json.loads(saved)["dataset_version"])
                with self.assertRaises(FileExistsError):
                    self.runner.capture_run(*args)
                self.assertEqual(saved, output.read_text(encoding="utf-8"))

    def test_runner_rejects_an_existing_case_before_authentication_or_provider_calls(self):
        with TemporaryDirectory() as folder:
            case = self.dataset["cases"][0]
            (Path(folder) / f"{case['case_key']}.json").write_text("{}", encoding="utf-8")
            with patch.object(self.runner, "authenticate") as authenticate:
                with self.assertRaises(self.runner.EvalRunError):
                    self.runner.main(["--execute", "--runs-dir", folder, "--case", case["case_key"]])
                authenticate.assert_not_called()

    def test_scorer_rejects_mixed_dataset_versions_or_wrong_case_identifiers(self):
        dataset = {"dataset_version": "1.1.0", "cases": [self.case]}
        with TemporaryDirectory() as folder:
            path = Path(folder) / "c.json"
            for value in ({"dataset_version": "1.0.0", "case_key": "c"},
                          {"dataset_version": "1.1.0", "case_key": "wrong"}):
                path.write_text(json.dumps(value), encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "版本不一致"):
                    self.score.load_run_records(dataset, Path(folder))
            path.write_text(json.dumps({"dataset_version": "1.1.0", "case_key": "c"}), encoding="utf-8")
            self.assertEqual(1, len(self.score.load_run_records(dataset, Path(folder))))


if __name__ == "__main__":
    unittest.main()
