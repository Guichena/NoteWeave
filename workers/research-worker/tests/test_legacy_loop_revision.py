"""阶段 1F：旧循环查询、真实反证、分类和来源相关度的回归证据。"""

import json

import pytest

from app import legacy_counter_runtime as counter
from app.counter_checks import MAX_FACTS
from app.llm_client import FakeLlmClient
from app.models import (ResearchEvidenceCard, ResearchReadWindow, ResearchSearchHit, ResearchStateLedger,
                        ResearchTaskInput, ResearchLoopDecision, LocalVerifierResult, GlobalVerifierResult, ResumeCheckpointPayload, ResearchStateRow)
from app.loop_runtime import _augment_plan_for_next_round, _restore_resume_context
from app.planner import build_research_plan
from app.query_execution import record_query
from app.research_tools import ResearchRoundArtifacts
from app.search import run_seed_source_search
from app.verifier import run_local_verifier, run_global_verifier
from app.cell_recovery_runtime import plan_digest
from app.reporter import build_research_report_structure, write_research_report


def task(question="解释检索系统的设计依据", sources=None):
    return ResearchTaskInput.model_validate({
        "task_id": "legacy-task", "workspace_id": "workspace", "target_id": "run",
        "source_scope": sources or [], "retrieval_mode": "SOURCES_ONLY",
        "control_pack": {"pack_type": "research", "target_key": "DEFAULT", "task_neighborhood": "RESEARCH_DEFAULT"},
        "input_payload": {"question": question, "profile_key": "DEFAULT"}})


def matrix():
    return {"research_type": "CONCEPT_RESEARCH", "entities": [{"label": "检索系统"}],
            "dimensions": [{"key": "latency", "label": "查询延迟", "description": "延迟边界及影响因素",
                            "answer_format": "SHORT_TEXT", "high_risk": True, "search_intents": ["延迟边界"]},
                           {"key": "accuracy", "label": "准确率", "answer_format": "SHORT_TEXT"}]}


def window(source_id="new-source", text="独立报告确认延迟低于十毫秒"):
    return ResearchReadWindow(window_id="window-1", hit_id="hit", source_id=source_id,
                              source_title="独立报告", query="延迟测量边界", read_focus="测量条件",
                              window_text=text, retention_reason="原文", token_estimate=15)


def card():
    return ResearchEvidenceCard(evidence_id="ev-1", window_id="original", source_id="original-source",
                                source_title="原始报告", column_key="latency", claim_text="延迟低于十毫秒",
                                quote_text="原报告测得十毫秒以内", relation_type="SUPPORTS",
                                support_score=0.9, conflict_score=0)


class CounterLlm:
    def __init__(self, verdict="NOT_REFUTED", invalid_quote=False):
        self.calls = []
        self.verdict = verdict
        self.invalid_quote = invalid_quote

    def complete_json(self, purpose, payload):
        self.calls.append((purpose, payload))
        if purpose == "research.counter.facts":
            return json.dumps({"facts": [{"claim_text": "延迟低于十毫秒", "query": "查询延迟测量边界"}]})
        if purpose == "research.counter.verify":
            passages = payload["passages"]
            citations = [] if self.verdict == "NO_COUNTER_EVIDENCE_FOUND" else [{
                "window_id": passages[0]["window_id"] if passages else "unknown",
                "quote_text": "伪造引文" if self.invalid_quote else passages[0]["text"] if passages else "空"}]
            return json.dumps({"verdict": self.verdict, "citations": citations})
        return ""


def setup_counter(monkeypatch, windows=None, execute_search=True):
    def search(_task, plan):
        if execute_search:
            record_query(plan.query_set[0])
        return [ResearchSearchHit(hit_id="hit", source_id="new-source", source_title="独立报告",
                                  query=plan.query_set[0], rank=1, snippet="独立结果", confidence_score=0.8,
                                  retrieval_reason="实际检索", search_angle="counterfactual")]
    monkeypatch.setattr(counter, "run_research_search", search)
    monkeypatch.setattr(counter, "run_research_fetch", lambda *_: [])
    monkeypatch.setattr(counter, "run_research_read", lambda *_: [window()] if windows is None else windows)


def test_matrix_result_has_priority_and_preserves_column_meaning():
    input_task = task("比较不同产品")
    input_task.input_payload.research_intent.research_type = "PRODUCT_COMPARISON"
    plan = build_research_plan(input_task, matrix_plan=matrix())
    assert plan.research_type == "CONCEPT_RESEARCH"
    assert plan.research_schema.columns[0].high_risk
    assert plan.research_schema.columns[0].description == "延迟边界及影响因素"
    assert plan.query_specs[0].query == plan.query_set[0]
    assert all("::" not in q and len(q) <= 200 for q in plan.query_set)


@pytest.mark.parametrize("question", ["评估最近的研究进展", "review research", "study repository", "digital history"])
def test_uncertain_keyword_classification_uses_generic(question):
    plan = build_research_plan(task(question))
    assert plan.research_type == "GENERIC"
    assert "product" not in plan.state_columns


def test_planning_invalid_output_retries_once_then_records_fallback():
    llm = FakeLlmClient({"research.plan.matrix": "{}", "research.cell.queries": "{}"})
    plan = build_research_plan(task(), llm_client=llm)
    calls = [payload for purpose, payload in llm.calls if purpose == "research.plan.matrix"]
    assert len(calls) == 2
    assert calls[1]["validation_errors"]
    assert "PLANNER_FALLBACK_DETERMINISTIC" in plan.stop_contract["degradation_reasons"]
    assert "CELL_QUERY_LLM_OUTPUT_INVALID" in plan.stop_contract["degradation_reasons"]


def test_replan_uses_claim_and_actual_query_history():
    plan = build_research_plan(task(), matrix_plan=matrix())
    plan.executed_queries = ["已经检索过的查询"]
    artifacts = ResearchRoundArtifacts([], [], [], [card().model_copy(update={"relation_type": "CONFLICTS"})],
        ResearchStateLedger(), LocalVerifierResult(status="WARN"), [],
        GlobalVerifierResult(status="WARN", decision="WRITE_WITH_GUARDRAILS", summary="待修复"))
    llm = FakeLlmClient({"research.counter.queries": json.dumps({"strategy": "COUNTER_EVIDENCE",
        "queries": ["延迟边界例外", "不同版本延迟差异"]})})
    revised = _augment_plan_for_next_round(plan, ResearchLoopDecision(
        decision="COUNTERFACTUAL_RECHECK", reason="EVIDENCE_CONFLICT", round_no=1, should_continue=True),
        artifacts, llm_client=llm)
    payload = llm.calls[0][1]
    assert payload["repair_target"]["claims"][0]["claim_text"] == card().claim_text
    assert payload["tried_queries"] == plan.executed_queries
    assert revised.query_set == [s.query for s in revised.query_specs]
    assert revised.query_specs[0].search_lane == "COUNTERFACTUAL"
    assert revised.replan_history[-1]["planned_queries"] == revised.query_set


def test_real_counter_check_isolates_sources_and_preserves_exact_new_passages(monkeypatch):
    setup_counter(monkeypatch)
    plan = build_research_plan(task(), matrix_plan=matrix())
    llm = CounterLlm()
    counter.run_counter_checks(task(), plan, [card()], [window("original-source", card().quote_text)], llm, lambda: None)
    assert counter.counter_completed(plan.counter_checks[0])
    assert plan.counter_checks[0]["verdict"] == "NOT_REFUTED"
    passages = llm.calls[-1][1]["passages"]
    assert card().quote_text not in str(passages)
    assert passages[0]["window_id"].startswith("counter-")
    assert plan.executed_queries == ["查询延迟测量边界"]
    before = len(llm.calls)
    counter.run_counter_checks(task(), plan, [card()], [], llm, lambda: None)
    assert len(llm.calls) == before


@pytest.mark.parametrize("mode", ["no_llm", "original_source", "original_quote", "no_search", "fake_quote", "no_results", "tool_failure"])
def test_counter_cannot_pass_without_fresh_read_and_valid_model_judgement(monkeypatch, mode):
    windows = ([window("original-source")] if mode == "original_source" else
               [window(text=card().quote_text)] if mode == "original_quote" else [] if mode == "no_results" else None)
    setup_counter(monkeypatch, windows=windows, execute_search=mode != "no_search")
    if mode == "tool_failure":
        def failed(*_):
            raise RuntimeError("检索不可用")
        monkeypatch.setattr(counter, "run_research_search", failed)
    plan = build_research_plan(task(), matrix_plan=matrix())
    llm = None if mode == "no_llm" else CounterLlm(invalid_quote=mode == "fake_quote")
    counter.run_counter_checks(task(), plan, [card()], [window("original-source", card().quote_text)], llm, lambda: None)
    assert not counter.counter_completed(plan.counter_checks[0])
    result = run_local_verifier(task(), plan, ResearchStateLedger(), [], [], [card()])
    assert any(d.reason_code == "COUNTERFACTUAL_NOT_RUN" for d in result.decision_records)
    global_result = run_global_verifier(result, ResearchStateLedger(), [], [card()])
    assert "COUNTERFACTUAL_UNRESOLVED" in global_result.verification_facts["blocker_reason_codes"]


def test_refuted_fact_is_reported_and_blocks_formal_write(monkeypatch):
    setup_counter(monkeypatch)
    input_task = task()
    plan = build_research_plan(input_task, matrix_plan=matrix())
    counter.run_counter_checks(input_task, plan, [card()], [], CounterLlm("REFUTED"), lambda: None)
    local = run_local_verifier(input_task, plan, ResearchStateLedger(), [], [], [card()])
    global_result = run_global_verifier(local, ResearchStateLedger(), [], [card()])
    assert global_result.status != "PASS"
    structure = build_research_report_structure(input_task, plan, ResearchStateLedger(), [], [], [card()], [], local, global_result)
    report = write_research_report(input_task, plan, ResearchStateLedger(), [], [], [card()], [], local, global_result,
                                   report_structure=structure)
    assert "REFUTED" in report and "独立报告确认延迟低于十毫秒" in report


def test_no_counter_evidence_is_reported_without_claiming_pass(monkeypatch):
    setup_counter(monkeypatch, windows=[])
    plan = build_research_plan(task(), matrix_plan=matrix())
    counter.run_counter_checks(task(), plan, [card()], [], CounterLlm("NO_COUNTER_EVIDENCE_FOUND"), lambda: None)
    assert plan.counter_checks[0]["verdict"] == "NO_COUNTER_EVIDENCE_FOUND"
    assert not counter.counter_completed(plan.counter_checks[0])


def test_counter_search_budget_and_checkpoint_state_are_preserved(monkeypatch):
    setup_counter(monkeypatch)
    plan = build_research_plan(task(), matrix_plan=matrix())
    plan.stop_contract["counter_search_calls"] = MAX_FACTS
    llm = CounterLlm()
    counter.run_counter_checks(task(), plan, [card()], [], llm, lambda: None)
    assert not llm.calls
    restored = type(plan).model_validate(plan.model_dump(mode="json"))
    assert restored.counter_checks == plan.counter_checks
    assert restored.stop_contract["counter_search_calls"] == MAX_FACTS


def test_legacy_completed_checkpoint_pass_is_rechecked():
    input_task = task()
    plan = build_research_plan(input_task, matrix_plan=matrix())
    input_task.input_payload.resume_checkpoint = ResumeCheckpointPayload.model_validate({
        "source_research_run_id": "older", "checkpoint_no": 1, "payload": {
            "plan": plan.model_dump(mode="json"), "state_ledger": ResearchStateLedger().model_dump(mode="json"),
            "local_verifier": {"status": "PASS"},
            "global_verifier": {"status": "PASS", "decision": "READY_TO_WRITE", "summary": "旧结果"},
            "loop_decision": {"decision": "SYNTHESIZE_REPORT", "reason": "OLD_PASS", "round_no": 1,
                              "should_continue": False}}})
    # 用模型校验恢复快照，与 API 输入保持一致。
    input_task = ResearchTaskInput.model_validate(input_task.model_dump(mode="json"))
    context = _restore_resume_context(input_task, plan)
    assert context.final_decision.decision == "WRITE_WITH_GUARDRAILS"
    assert context.artifacts.global_result.status != "PASS"


def test_query_history_accounting_does_not_defeat_repeated_plan_guard():
    plan = build_research_plan(task())
    changed = plan.model_copy(deep=True)
    changed.stop_contract["query_spec_history"] = [s.model_dump() for s in plan.query_specs]
    changed.stop_contract["counter_search_calls"] = 3
    assert plan_digest(changed) == plan_digest(plan)
    changed.query_specs[0].family = "counterfactual"
    assert plan_digest(changed) != plan_digest(plan)


def test_bm25f_ranks_relevance_above_presence_of_summary():
    input_task = task("向量索引查询延迟", [
        {"source_id": "unrelated", "title": "烹饪技巧", "summary": "这份内容非常详细但与检索问题无关"},
        {"source_id": "match", "title": "向量索引查询延迟", "sample_text": "向量索引的查询延迟受维度影响"}])
    plan = build_research_plan(input_task)
    hits = run_seed_source_search(input_task, plan)
    assert hits[0].source_id == "match"
    unrelated = next(h for h in hits if h.source_id == "unrelated")
    assert unrelated.confidence_score == unrelated.coverage_score == 0
    assert 0 < hits[0].confidence_score <= hits[0].coverage_score < 1


def test_bm25f_title_weight_and_quality_profile_change_scores():
    query = "vector latency"
    content = "vector latency measurements follow reproducible procedures " * 10
    input_task = task(query, [
        {"source_id": "title", "title": query, "sample_text": content},
        {"source_id": "body", "title": "measurements", "sample_text": content},
        {"source_id": "placeholder", "title": query, "sample_text": "placeholder"}])
    hits = {h.source_id: h for h in run_seed_source_search(input_task, build_research_plan(input_task))}
    assert hits["title"].coverage_score > hits["body"].coverage_score
    assert hits["placeholder"].source_quality_score < hits["title"].source_quality_score
    assert hits["placeholder"].confidence_score < hits["title"].confidence_score


def test_duplicate_claims_share_counter_check_without_repeating_search(monkeypatch):
    setup_counter(monkeypatch)
    plan = build_research_plan(task(), matrix_plan=matrix())
    second = card().model_copy(update={"evidence_id": "ev-2", "source_id": "second-original"})
    llm = CounterLlm()
    counter.run_counter_checks(task(), plan, [card(), second], [], llm, lambda: None)
    assert len(plan.counter_checks) == 2
    assert all(counter.counter_completed(r) for r in plan.counter_checks)
    assert plan.stop_contract["counter_search_calls"] == 1
    assert len(llm.calls) == 2


def test_empty_query_budget_does_not_materialize_seed_hits():
    from app.search_adapters import SeedSourceSearchAdapter
    input_task = task("vector latency", [{"source_id": "seed", "title": "vector latency"}])
    plan = build_research_plan(input_task)
    assert SeedSourceSearchAdapter().search(input_task, plan, remaining_budget=0) == []
    assert run_seed_source_search(input_task, plan, selected_queries=[]) == []


def test_counter_aggregated_verdict_must_match_atomic_verdicts(monkeypatch):
    setup_counter(monkeypatch)
    plan = build_research_plan(task(), matrix_plan=matrix())
    counter.run_counter_checks(task(), plan, [card()], [], CounterLlm("REFUTED"), lambda: None)
    plan.counter_checks[0]["verdict"] = "NOT_REFUTED"
    assert not counter.counter_completed(plan.counter_checks[0])


def test_high_risk_verified_row_without_counter_is_kept_out_of_formal_findings():
    input_task = task()
    plan = build_research_plan(input_task, matrix_plan=matrix())
    ledger = ResearchStateLedger(rows=[ResearchStateRow(row_id="row", evidence_id=card().evidence_id,
        source_title="原始报告", claim_text=card().claim_text, row_status="VERIFIED")])
    local = run_local_verifier(input_task, plan, ledger, [], [], [card()])
    global_result = run_global_verifier(local, ledger, [], [card()])
    structure = build_research_report_structure(input_task, plan, ledger, [], [], [card()], [], local, global_result)
    assert structure["verified_findings"] == []
    assert structure["recovery_status"]["guardrailed_rows"][0]["claim_text"] == card().claim_text


@pytest.mark.parametrize("constraint", ["必须进行反证并提供证据", "Check conflict evidence before synthesis"])
def test_report_section_and_counter_angle_cannot_satisfy_counter_constraint(constraint):
    from app.verifier import _evaluate_constraint_requirement
    input_task = task()
    plan = build_research_plan(input_task)
    plan.report_sections.append("Conflicts And Uncertainty")
    hit = ResearchSearchHit(hit_id="counter-angle", source_id="source", source_title="资料",
        query="标记为反证的查询", rank=1, snippet="原始摘要", confidence_score=0.8,
        retrieval_reason="仅有用途标记", search_angle="counterfactual")
    result = _evaluate_constraint_requirement(constraint=constraint, requirement_id="counter",
        plan=plan, ledger=ResearchStateLedger(conflicted_row_count=1), search_hits=[hit],
        evidence_cards=[], branch_decisions=[])
    assert result.status == "WARN"
    assert result.evidence_anchor == "counter_checks"
