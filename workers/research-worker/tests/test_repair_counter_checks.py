import json
from types import SimpleNamespace

import pytest

from app.cell_query_planner import CellColumn, CellQueryContext
from app.counter_checks import atomic_queries, judge_fact
from app.execution_report import validate_execution_report
from app.repair_queries import plan_repair_queries


class Llm:
    def __init__(self, outputs):
        self.outputs = iter(outputs)
        self.calls = []

    def complete_json(self, purpose, payload):
        if purpose == "research.answer_check":
            # 答案检查不消耗预设输出：全部结论都视为回答了该列
            return json.dumps({"answering_indices": list(range(len(payload["claims"])))})
        self.calls.append((purpose, payload))
        return json.dumps(next(self.outputs))

    def usage_summary(self):
        return {"provider_call_count": len(self.calls)}


def test_conflict_queries_target_actual_claims_and_reject_used_queries():
    llm = Llm([{"strategy": "COUNTER_EVIDENCE", "queries": ["Atlas limit 100"]},
               {"strategy": "COUNTER_EVIDENCE", "queries": ["Atlas concurrency limit exceptions errata"]}])
    context = CellQueryContext("capacity", "Atlas", (CellColumn("limit", "limit"),))
    target = {"repair_cause": "EVIDENCE_CONFLICT", "tried_queries": ["Atlas limit 100"],
              "claims": [{"claim_text": "Atlas limit is 100", "source_domain": "example.org"},
                         {"claim_text": "Atlas limit is 200", "source_domain": "example.net"}]}
    plan, strategy = plan_repair_queries(context, target, llm)
    assert strategy == "COUNTER_EVIDENCE"
    assert plan.specs[0].query != target["tried_queries"][0]
    assert llm.calls[0][0] == "research.counter.queries"
    assert llm.calls[0][1]["repair_target"]["claims"] == target["claims"]
    assert llm.calls[1][1]["validation_errors"]


def test_gap_cell_without_claims_cannot_be_repaired_with_counter_evidence():
    llm = Llm([{"strategy": "COUNTER_EVIDENCE", "queries": ["code coverage no reduction in defects counterexample"]},
               {"strategy": "NARROW", "queries": ["higher code coverage reduced post-release defects study"]}])
    context = CellQueryContext("coverage", "coverage threshold", (CellColumn("support", "支持证据与来源"),))
    plan, strategy = plan_repair_queries(context, {"repair_cause": "MARGINAL_GAIN_EXHAUSTED"}, llm)
    assert strategy == "NARROW"
    assert plan.specs[0].query == "higher code coverage reduced post-release defects study"
    assert "COUNTER_EVIDENCE" in llm.calls[1][1]["validation_errors"][0]


@pytest.mark.parametrize("cause,strategy", [("FETCH_FAILED", "SWITCH_SOURCE_TYPE"),
    ("PROVIDER_FAILED", "SWITCH_SOURCE_TYPE"), ("MARGINAL_GAIN_EXHAUSTED", "NARROW")])
def test_reason_specific_rule_fallback(cause, strategy):
    plan, chosen = plan_repair_queries(CellQueryContext("capacity", "Atlas", (CellColumn("limit", "capacity"),)),
                                      {"repair_cause": cause}, None)
    assert chosen == strategy
    assert plan.method == "RULE" and plan.degradation_reasons
    assert plan.specs


def test_decomposition_is_complete_and_generation_retries_with_errors():
    llm = Llm([{"facts": []}, {"facts": [{"claim_text": "Atlas supports 100 sessions", "query": "Atlas session exceptions"}]}])
    facts = atomic_queries("Atlas supports 100 sessions", llm, [])
    assert len(facts) == 1
    assert llm.calls[1][1]["validation_errors"]


def test_judgement_receives_only_one_fact_and_fresh_passages():
    fresh = SimpleNamespace(window_id="new", window_text="Atlas supports at most 50 sessions")
    llm = Llm([{"verdict": "REFUTED", "citations": [{"window_id": "new", "quote_text": fresh.window_text}]}])
    result = judge_fact("Atlas supports 100 sessions", [fresh], llm)
    assert result["verdict"] == "REFUTED"
    payload = llm.calls[0][1]
    assert "original_evidence" not in payload and "research_brief" not in payload
    assert payload["passages"] == [{"window_id": "new", "text": fresh.window_text}]


@pytest.mark.parametrize("verdict", ["REFUTED", "NOT_REFUTED"])
def test_factual_verdict_requires_exact_citation(verdict):
    llm = Llm([{"verdict": verdict, "citations": []}] * 2)
    assert judge_fact("some fact", [], llm) is None
    assert llm.calls[1][1]["validation_errors"]


def test_empty_results_are_not_proof_and_unavailable_model_does_not_pass():
    llm = Llm([{"verdict": "NO_COUNTER_EVIDENCE_FOUND", "citations": []}])
    assert judge_fact("some fact", [], llm)["verdict"] == "NO_COUNTER_EVIDENCE_FOUND"
    assert judge_fact("some fact", [], None) is None
    assert atomic_queries("some fact", None, []) is None


def test_report_cannot_claim_execution_without_query_or_refute_without_evidence():
    check = {"cell_key": "e:c", "original_claim": "fact", "claim_text": "fact", "verdict": "NOT_REFUTED",
             "queries": [], "refuting_evidence_keys": []}
    with pytest.raises(ValueError):
        validate_execution_report({"queries": [], "strategy": "COUNTER_EVIDENCE", "counter_checks": [check]})
    check.update(verdict="REFUTED", queries=["fact exception"])
    with pytest.raises(ValueError):
        validate_execution_report({"queries": ["fact exception"], "strategy": "COUNTER_EVIDENCE", "counter_checks": [check]})


def test_rule_queries_never_repeat_all_previously_tried_queries():
    context = CellQueryContext("capacity", "Atlas", (CellColumn("limit", "capacity"),))
    target = {"repair_cause": "FETCH_FAILED"}
    first, _ = plan_repair_queries(context, target, None)
    second, _ = plan_repair_queries(context, {**target, "tried_queries": [s.query for s in first.specs]}, None)
    assert not second.specs


def test_executor_isolates_each_fact_and_records_real_queries_and_refuting_evidence(monkeypatch):
    from app import deep_cell_executor
    from app.deterministic_fake_toolchain import DeterministicFakeToolchain
    from app.task_snapshot_contract import snapshot_digest
    from test_ma4d_deep_cell_executor import _snapshot, _claim, _command, _Permit

    llm = Llm([
        {"strategy": "COUNTER_EVIDENCE", "queries": ["Atlas official implementation limitations"]},
        {"facts": [{"claim_text": "The method is documented", "query": "Atlas undocumented method errata"},
                   {"claim_text": "The method is supported", "query": "Atlas unsupported version exceptions"}]},
        {"verdict": "REFUTED", "citations": [{"window_id": "fresh-1", "quote_text": "The method has no documentation"}]},
        {"verdict": "NOT_REFUTED", "citations": [{"window_id": "fresh-2", "quote_text": "The method is supported"}]},
    ])
    monkeypatch.setattr(deep_cell_executor, "_build_execution_llm", lambda *args: llm)

    class Toolchain(DeterministicFakeToolchain):
        def extract(self, task_input, plan, windows, *, llm_client):
            return super().extract(task_input, plan, windows, llm_client=None)

    class Permit(_Permit):
        def __init__(self):
            super().__init__()
            self.queries = []

        def search_workspace_windows(self, snapshot, *, queries, limit):
            self.queries.extend(queries)
            index = len(self.queries)
            texts = ["The method is documented.", "The method has no documentation", "The method is supported"]
            def window(window_id, text):
                return SimpleNamespace(source_id="source-1", source_snapshot_id="snapshot-1",
                    source_window_id=window_id, source_title="Trusted note", query=queries[0],
                    window_text=text, score_ppm=950000)
            original = window("original", texts[0])
            return [original] if index == 1 else [original, window(f"fresh-{index - 1}", texts[index - 1])]

    raw = _snapshot(source_scope=[{"source_id": "source-1", "title": "Trusted note",
        "sample_text": "The method is documented.", "source_snapshot_id": "snapshot-1", "source_window_id": "original"}])
    raw.pop("snapshot_digest")
    raw["budget"]["llm_calls"] = 32
    raw["query_policy"]["repair_target"] = {"cell_key": "entity-1:method", "repair_cause": "EVIDENCE_CONFLICT", "claims": []}
    raw["snapshot_digest"] = snapshot_digest(raw)
    permit = Permit()
    completion = deep_cell_executor.DeepCellExecutor(permit, "worker-a", toolchain=Toolchain())(_command(), _claim(raw))
    report = completion.extraction_diagnostics.execution_report
    assert [c["verdict"] for c in report["counter_checks"]] == ["REFUTED", "NOT_REFUTED"]
    assert all(query in report["queries"] for query in permit.queries)
    judged = [payload for purpose, payload in llm.calls if purpose == "research.counter.verify"]
    assert [p["passages"] for p in judged] == [
        [{"window_id": "fresh-1", "text": "The method has no documentation"}],
        [{"window_id": "fresh-2", "text": "The method is supported"}]]
    assert completion.budget_usage.search_calls == 3
    assert completion.budget_usage.llm_calls == 4
    assert report["counter_checks"][0]["refuting_evidence_keys"] == [completion.evidence[-1].evidence_key]
    assert completion.evidence[-1].relation_type == "CONFLICTS"


def test_executor_marks_required_counter_as_not_run_when_model_is_unavailable():
    from app.deep_cell_executor import DeepCellExecutor
    from app.deterministic_fake_toolchain import DeterministicFakeToolchain
    from app.task_snapshot_contract import snapshot_digest
    from test_ma4d_deep_cell_executor import _snapshot, _claim, _command, _Permit

    raw = _snapshot()
    raw.pop("snapshot_digest")
    raw["query_policy"]["counter_required"] = True
    raw["snapshot_digest"] = snapshot_digest(raw)
    completion = DeepCellExecutor(_Permit(), "worker-a", toolchain=DeterministicFakeToolchain(), enable_llm=False)(_command(), _claim(raw))
    checks = completion.extraction_diagnostics.execution_report["counter_checks"]
    assert checks and all(check["verdict"] == "NOT_RUN" and not check["queries"] for check in checks)


def test_executor_caps_llm_calls_at_the_authoritative_snapshot_budget(monkeypatch):
    from app import deep_cell_executor
    from app.deterministic_fake_toolchain import DeterministicFakeToolchain
    from test_ma4d_deep_cell_executor import _snapshot, _claim, _command, _Permit

    limits = []
    def build(checker, profile):
        limits.append(profile.max_llm_calls)
        return None
    monkeypatch.setattr(deep_cell_executor, "_build_execution_llm", build)
    deep_cell_executor.DeepCellExecutor(_Permit(), "worker-a", toolchain=DeterministicFakeToolchain())(_command(), _claim(_snapshot()))
    assert limits == [2]


def test_rule_repair_queries_use_untried_column_intents_instead_of_gluing_the_entity_label() -> None:
    from app.cell_query_planner import CellColumn, CellQueryContext
    from app.repair_queries import plan_repair_queries

    column = CellColumn(key="dim-1", label="支持证据及来源", description="", search_intents=(
        "microservices faster deployments study", "microservices release frequency survey report",
        "microservices delivery speed case study"))
    tried = ("microservices faster deployments study", "microservices delivery speed productivity long-term",
             "microservices adoption empirical study deployment frequency productivity ACM",
             "site:acm.org microservices empirical study deployment frequency productivity")
    context = CellQueryContext(question="微服务架构是否提高了中型团队的长期交付速度？",
                               entity_label="中型团队微服务架构对长期交付速度的影响", columns=(column,), tried_queries=tried)

    plan, strategy = plan_repair_queries(context, {"repair_cause": "MARGINAL_GAIN_EXHAUSTED", "tried_queries": list(tried)}, None)
    queries = [spec.query for spec in plan.specs]

    assert strategy == "SWITCH_LANGUAGE"
    assert queries[0].startswith("微服务架构是否提高了中型团队的长期交付速度")
    assert "microservices release frequency survey report" in queries
    assert not any("中型团队微服务架构对长期交付速度的影响" in query for query in queries)
    assert not any("faster deployments study" in query and "empirical" not in query and "report" not in query
                   for query in queries)
