import importlib.util
import json
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
SCRIPTS = ROOT / "scripts/retrieval"
EVAL_DIR = ROOT / "experiments/retrieval"


def load_module(name):
    sys.path.insert(0, str(SCRIPTS))
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def evidence(*items):
    return {"evidence": {"evidence": [
        {"rank": rank, "title": title, "excerpt": excerpt} for rank, (title, excerpt) in enumerate(items, start=1)
    ]}}


FACT_A = {"doc": "qa-rag", "any_of": [["RRF K 60"]]}
FACT_B = {"doc": "wiki", "any_of": [["TTL 600 秒"], ["600s"]]}


class RetrievalEvalTest(unittest.TestCase):
    def setUp(self):
        self.score = load_module("score_retrieval_eval")
        self.runner = load_module("run_retrieval_eval")
        self.dataset = json.loads((EVAL_DIR / "dataset.json").read_text(encoding="utf-8"))

    def test_corpus_digest_matches_frozen_dataset(self):
        for item in self.dataset["corpus"]:
            self.assertEqual(item["sha256"], self.runner.corpus_digest(EVAL_DIR / item["path"]), item["path"])
        self.runner.verify_corpus(self.dataset)

    def test_every_non_wiki_fact_occurs_in_its_corpus_document(self):
        texts = {item["key"]: self.score.normalize((EVAL_DIR / item["path"]).read_text(encoding="utf-8"))
                 for item in self.dataset["corpus"]}
        for set_name in ("first_turn", "multi_turn"):
            for case in self.dataset["sets"][set_name]["cases"]:
                for fact in case["facts"]:
                    self.assertTrue(self.score.fact_matches(fact, texts[fact["doc"]]), case["case_key"])

    def test_dataset_shape(self):
        sets = self.dataset["sets"]
        self.assertEqual(20, len(sets["first_turn"]["cases"]))
        answerability = [case["answerability"] for case in sets["multi_turn"]["cases"]]
        self.assertEqual({"ANSWERABLE": 6, "PARTIAL": 3, "UNANSWERABLE": 3},
                         {name: answerability.count(name) for name in set(answerability)})
        self.assertTrue(all(len(case["turns"]) >= 2 for case in sets["multi_turn"]["cases"]))
        self.assertTrue(all(len({fact["doc"] for fact in case["facts"]}) >= 2
                            for case in sets["wiki_cross_page"]["cases"]))

    def test_matching_ignores_whitespace_and_backticks(self):
        self.assertTrue(self.score.fact_matches(FACT_A, self.score.normalize("融合使用 `RRF  K\n60`")))

    def test_ranking_metrics_credit_each_fact_once(self):
        record = evidence(("无关", "别的内容"), ("QA", "RRF K 60"), ("QA 重复", "RRF K 60"), ("Wiki", "缓存 600s"))
        metrics = self.score.ranking_metrics([FACT_A, FACT_B], self.score.ranked_evidence(record))

        self.assertEqual(1.0, metrics["recall_at_10"])
        self.assertEqual(0.5, metrics["mrr"])
        ideal = 1 + 1 / 1.584962500721156
        expected = (1 / 1.584962500721156 + 1 / 2.321928094887362) / ideal
        self.assertAlmostEqual(expected, metrics["ndcg_at_10"], places=3)

    def test_only_top_ten_evidence_counts(self):
        items = [("x", "无关")] * 10 + [("QA", "RRF K 60")]
        metrics = self.score.ranking_metrics([FACT_A], self.score.ranked_evidence(evidence(*items)))
        self.assertEqual(0.0, metrics["recall_at_10"])
        self.assertEqual(0.0, metrics["mrr"])

    def test_unanswerable_case_has_no_ranking_metrics_and_checks_sufficiency(self):
        case = {"case_key": "u", "answerability": "UNANSWERABLE", "turns": ["a", "b"], "facts": []}
        result = self.score.score_case(case, {**evidence(), "evidence_sufficiency": "INSUFFICIENT", "latency_ms": 900})
        self.assertNotIn("recall_at_10", result)
        self.assertTrue(result["sufficiency"]["correct"])

    def test_missing_sufficiency_field_is_not_counted(self):
        case = {"case_key": "p", "answerability": "PARTIAL", "turns": ["a", "b"], "facts": [FACT_A]}
        results = [self.score.score_case(case, evidence(("QA", "RRF K 60")))]
        summary = self.score.summarize(results)
        self.assertEqual(0, summary["sufficiency_judged"])
        self.assertIsNone(summary["sufficiency_accuracy"])

    def test_missing_runs_are_reported_and_latency_percentiles_computed(self):
        case = {"case_key": "a", "question_type": "EXACT", "turns": ["q"], "facts": [FACT_A]}
        results = [
            self.score.score_case(case, None),
            self.score.score_case(case, {**evidence(("QA", "RRF K 60")), "latency_ms": 100}),
            self.score.score_case(case, {**evidence(), "latency_ms": 300}),
        ]
        summary = self.score.summarize(results)
        self.assertEqual(1, summary["missing_runs"])
        self.assertEqual(2, summary["ranked_cases"])
        self.assertEqual(0.5, summary["recall_at_10"])
        self.assertEqual(100, summary["latency_p50_ms"])
        self.assertEqual(300, summary["latency_p95_ms"])

    def test_report_renders_every_set(self):
        report = self.score.score_dataset(self.dataset, EVAL_DIR / "no-such-runs")
        markdown = self.score.render_markdown(report)
        for name in ("first_turn", "multi_turn", "wiki_cross_page"):
            self.assertIn(f"## {name}", markdown)
        self.assertEqual(20, report["sets"]["first_turn"]["overall"]["missing_runs"])

    def test_runner_rejects_unknown_set(self):
        with self.assertRaises(self.runner.EvalRunError):
            self.runner.selected_sets(self.dataset, {"nope"})


if __name__ == "__main__":
    unittest.main()
