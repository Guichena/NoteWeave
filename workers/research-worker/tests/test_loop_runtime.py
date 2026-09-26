from __future__ import annotations

import json

import pytest

pytestmark = pytest.mark.usefixtures("fake_default_llm")

from app.loop_runtime import (
    evaluate_loop_decision,
    evaluate_premature_commitment_guard,
    _augment_plan_for_next_round,
    _restore_resume_context,
    run_research_loop,
    _normalize_restored_read_window,
)
from app.models import (
    GlobalVerifierResult,
    LocalVerifierResult,
    ResearchLoopDecision,
    ResearchReadWindow,
    ResearchEntityRow,
    ResearchStateBranch,
    ResearchStateCell,
    ResearchRequiredFindingProgress,
    ResearchStateLedger,
    ResearchTaskInput,
)
from app.planner import build_research_plan
from app.runner import run_research_task
from app.verifier_gate_policy import build_verifier_gate_policy_state
from app.research_tools import ResearchRoundArtifacts


def _build_task_input(sample_text: str = "Stable evidence supports verifier gated synthesis.") -> ResearchTaskInput:
    return ResearchTaskInput.model_validate(
        {
            "task_id": "task-loop-1",
            "workspace_id": "ws-1",
            "target_id": "run-loop-1",
            "source_scope": [
                {
                    "source_id": "src-loop",
                    "title": "Loop Evidence Source",
                    "summary": sample_text,
                    "sample_text": sample_text,
                }
            ],
            "control_pack": {
                "pack_type": "research",
                "target_key": "DEFAULT",
                "task_neighborhood": "RESEARCH_DEFAULT",
                "evidence_policy": ["Do not synthesize without evidence."],
            },
            "input_payload": {
                "question": "How should the research loop stop?",
                "profile_key": "DEFAULT",
            },
        }
    )


def test_loop_runtime_should_default_to_standard_loop_rounds(fake_default_llm) -> None:
    """P0-6: STANDARD profile 默认 4 轮 (从原来的 2 轮上调,给 cell retry 留空间)。

    DR-204 升级：无 Provider（``llm_client=None``）现在会被停止保护判定为
    ``PROVIDER_NOT_CONFIGURED`` 基础设施终止，因此本用例必须显式提供可用的
    LLM Provider 才能验证「默认轮次上限允许 ≥2 轮」这一性质。
    """
    task_input = _build_task_input(
        "However, this source reports a conflict risk that needs counterfactual verification."
    )
    plan = build_research_plan(task_input)

    result = run_research_loop(task_input, plan, llm_client=fake_default_llm)

    assert plan.stop_contract["max_loop_rounds"] == 4
    assert len(result.rounds) >= 2
    assert result.stop_reason_category != "INFRASTRUCTURE"
    assert result.final_decision.decision in {
        "WRITE_WITH_GUARDRAILS",
        "COUNTERFACTUAL_RECHECK",
        "SYNTHESIZE_REPORT",
    }


def test_restore_read_window_should_preserve_explicit_fetched_status() -> None:
    window = ResearchReadWindow(
        window_id="window-fetch-1",
        hit_id="hit-fetch-1",
        source_id="src-fetch-1",
        source_title="Fetched Source",
        query="query",
        read_focus="focus",
        window_text="fetched text",
        retention_reason="resume",
        token_estimate=20,
        url="https://example.com/fetched",
        adapter="external_url",
        snapshot_status="WORKSPACE",
        fetch_status="FETCHED",
        content_origin="FETCHED_SNAPSHOT",
        fetch_method="HTTP",
    )

    restored = _normalize_restored_read_window(window)

    assert restored.fetch_status == "FETCHED"
    assert restored.content_origin == "FETCHED_SNAPSHOT"
    assert restored.fetch_method == "HTTP"
    assert restored.fetch_error_reason == ""


def test_first_round_without_search_hits_should_request_scope_expansion() -> None:
    task_input = ResearchTaskInput.model_validate(
        {
            "task_id": "task-loop-empty",
            "workspace_id": "ws-1",
            "target_id": "run-loop-empty",
            "source_scope": [],
            "control_pack": {
                "pack_type": "research",
                "target_key": "DEFAULT",
                "task_neighborhood": "RESEARCH_DEFAULT",
            },
            "input_payload": {
                "question": "What if there is no evidence?",
                "profile_key": "DEFAULT",
            },
        }
    )
    plan = build_research_plan(task_input)

    result = run_research_loop(task_input, plan)

    assert len(result.rounds) == 1
    assert result.final_decision.decision == "EXPAND_SOURCE_SCOPE"
    assert result.final_decision.should_continue is False


def test_conflicting_evidence_should_trigger_counterfactual_recheck_before_budget_exhaustion(monkeypatch) -> None:
    """P0-5: 需要 FakeLlmClient 产生 CONFLICTS 卡片才能触发 COUNTERFACTUAL_RECHECK。"""
    from app import llm_client as llm_client_module
    from app.llm_client import FakeLlmClient

    conflict_llm = FakeLlmClient(
        {
            "research.extract": (
                '{"evidence_cards":['
                '{"window_id":"window-1","column_key":"product","claim_text":"Source supports the claim","quote_text":"The source supports the claim","relation_type":"SUPPORTS","support_score":0.82,"conflict_score":0.02},'
                '{"window_id":"window-1","column_key":"product","claim_text":"However, conflict risk detected","quote_text":"However, this source reports a conflict risk and uncertainty","relation_type":"CONFLICTS","support_score":0.4,"conflict_score":0.7}'
                ']}'
            ),
            "research.verify.cell": (
                '{"status":"SUPPORTS","confidence":0.82,"reason":"grounded","suggested_revision":""}'
            ),
        }
    )
    monkeypatch.setattr(llm_client_module, "build_default_llm_client", lambda: conflict_llm)

    task_input = _build_task_input("The source supports the claim but contains conflict risk and uncertainty.")
    plan = build_research_plan(task_input)
    column_key = plan.research_schema.columns[0].key
    conflict_llm.responses_by_purpose["research.extract"] = (
        '{"evidence_cards":['
        f'{{"window_id":"window-1","column_key":"{column_key}","claim_text":"Source supports the claim","quote_text":"The source supports the claim","relation_type":"SUPPORTS","support_score":0.82,"conflict_score":0.02}},'
        f'{{"window_id":"window-1","column_key":"{column_key}","claim_text":"Conflict risk detected","quote_text":"contains conflict risk and uncertainty","relation_type":"CONFLICTS","support_score":0.4,"conflict_score":0.7}}'
        ']}'
    )

    result = run_research_loop(task_input, plan, llm_client=conflict_llm)

    assert result.rounds[0].branch_decision == "COUNTERFACTUAL_RECHECK"
    assert result.rounds[0].loop_decision.decision == "COUNTERFACTUAL_RECHECK"
    assert len(result.artifacts.search_hits) <= int(plan.stop_contract["global_search_limit"])
    assert result.rounds[0].loop_decision.should_continue is True
    assert len(result.rounds) >= 2
    assert result.artifacts.ledger.active_branch_id == "branch-counterfactual-1"
    assert any(
        decision.decision == "COUNTERFACTUAL_RECHECK"
        and decision.branch_status == "ACTIVE_BRANCH"
        and decision.result_evidence_ids == []
        for decision in result.artifacts.branch_decisions
    )
    assert any(
        branch.branch_reason == "COUNTERFACTUAL_EVIDENCE_MISSING"
        and branch.status == "ACTIVE_BRANCH"
        for branch in result.artifacts.ledger.branches
    )
    assert any(
        decision.reason_code == "CONFLICTING_EVIDENCE"
        for decision in result.artifacts.ledger.verifier_decisions
    )


def test_budget_exhaustion_should_force_guardrailed_write_without_infinite_loop() -> None:
    task_input = _build_task_input("However, this source has unresolved conflict risk.")
    plan = build_research_plan(task_input)
    plan.stop_contract["max_loop_rounds"] = 1

    result = run_research_loop(task_input, plan)

    assert len(result.rounds) == 1
    assert result.final_decision.decision == "WRITE_WITH_GUARDRAILS"
    assert result.final_decision.should_continue is False
    assert result.final_decision.terminal_disposition in {"HUMAN_HANDOFF", "ABANDON"}
    assert result.final_decision.handoff_required is (
        result.final_decision.terminal_disposition == "HUMAN_HANDOFF"
    )
    assert any(
        action.startswith("handoff_condition:") or action.startswith("abandon_condition:")
        for action in result.final_decision.recovery_actions
    )


def test_web_only_no_hits_should_be_explicit_evidence_abandonment() -> None:
    plan = build_research_plan(_build_task_input())
    plan.stop_contract["source_scope_count"] = 0
    plan.stop_contract["retrieval_mode"] = "WEB_ONLY"
    decision = evaluate_loop_decision(
        plan=plan,
        round_no=1,
        search_hit_count=0,
        read_window_count=0,
        evidence_card_count=0,
        has_conflict=False,
        ledger=ResearchStateLedger(),
        global_result=GlobalVerifierResult(
            status="WARN", decision="WRITE_WITH_GUARDRAILS", summary="no evidence"
        ),
    )

    assert decision.should_continue is False
    assert decision.terminal_disposition == "ABANDON"
    assert decision.abandon_reason == "NO_SEARCH_HITS_WITHOUT_EVIDENCE"
    assert all("attach workspace sources" not in action for action in decision.recovery_actions)
    assert any("web search provider" in action for action in decision.recovery_actions)


def test_ready_result_should_be_explicit_verified_completion() -> None:
    plan = build_research_plan(_build_task_input())
    ledger = ResearchStateLedger(
        verified_row_count=1,
        entities=[ResearchEntityRow(entity_id="entity-1", display_name="Entity", source_ids=["src-1"])],
    )
    decision = evaluate_loop_decision(
        plan=plan,
        round_no=1,
        search_hit_count=1,
        read_window_count=1,
        evidence_card_count=1,
        has_conflict=False,
        ledger=ledger,
        global_result=GlobalVerifierResult(
            status="PASS", decision="READY_TO_WRITE", summary="ready"
        ),
    )

    assert decision.decision == "SYNTHESIZE_REPORT"
    assert decision.terminal_disposition == "VERIFIED_COMPLETE"
    assert decision.handoff_required is False


class _UngroundableQuoteLlm:
    """返回一张引文无法在窗口内定位的候选卡 -> ``NON_EXACT_QUOTE`` 拒绝。

    用于在真实 ``run_research_loop`` 上构造「有搜索命中与已读窗口、但零证据卡」的情境。
    """

    def complete_json(self, purpose: str, payload: dict) -> str:
        if purpose != "research.extract":
            return ""
        schema_columns = [
            str(item).strip()
            for item in payload.get("schema_columns", [])
            if str(item).strip()
        ] or ["claim"]
        windows = payload.get("windows") or []
        window_id = windows[0].get("window_id", "window-1") if windows else "window-1"
        return json.dumps(
            {
                "evidence_cards": [
                    {
                        "window_id": window_id,
                        "column_key": schema_columns[0],
                        "claim_text": "A claim whose quote cannot be grounded.",
                        "quote_text": "THIS QUOTE IS ABSENT FROM EVERY WINDOW",
                        "relation_type": "SUPPORTS",
                    }
                ]
            }
        )


def test_loop_should_retry_extraction_when_read_windows_have_no_evidence() -> None:
    """DR-108 断言迁移（原 ``test_evaluate_loop_decision_should_search_more_when_reading_has_no_evidence``）。

    原断言（观察点：直接调用 ``evaluate_loop_decision`` + 空 ledger）锁定的性质：
    **存在搜索命中与已读窗口、但零证据卡时，循环必须继续并请求抽取恢复（``EXTRACT_AGAIN``），
    不得直接合成。** 该性质不是「决策函数的返回值形状」，而是可观察的循环行为，因此可迁移。

    收敛后「缺证据该做什么」的唯一权威是 ``CellRecoveryPolicy``（经
    ``CellRecoveryRuntime`` 编排），故观察点迁移到真实 ``run_research_loop``：同一情境
    （hits>0 / read>0 / cards=0 / 无冲突）下策略给出 ``RETRY_EXTRACTION``，循环决策族为
    ``EXTRACT_AGAIN`` 且仍继续。被断言的性质未被放宽。
    """
    task_input = _build_task_input()
    plan = build_research_plan(task_input)

    result = run_research_loop(task_input, plan, llm_client=_UngroundableQuoteLlm())

    first = result.rounds[0]
    assert first.search_hit_count > 0
    assert first.read_window_count > 0
    assert first.evidence_card_count == 0
    assert first.loop_decision.decision == "EXTRACT_AGAIN"
    assert first.loop_decision.should_continue is True
    # 决策族来自策略动作（唯一权威），不是 loop 本地的 read_window_count 规则。
    assert any(
        entry["action"] == "RETRY_EXTRACTION"
        and entry["reason_code"] == "NON_EXACT_QUOTE"
        for entry in result.cell_recovery_trace
    )


def test_evaluate_loop_decision_should_continue_when_required_findings_are_pending() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    ledger = ResearchStateLedger(
        required_finding_progress=[
            ResearchRequiredFindingProgress(
                requirement_id="goal_finding",
                requirement_type="GOAL_FINDING",
                label="Goal finding",
                status="PARTIAL",
                required_columns=["claim_text", "evidence_excerpt"],
            )
        ]
    )
    global_result = GlobalVerifierResult(
        status="WARN",
        decision="WRITE_WITH_GUARDRAILS",
        summary="required finding is still partial",
        recovery_actions=[],
    )

    decision = evaluate_loop_decision(
        plan=plan,
        round_no=1,
        search_hit_count=1,
        read_window_count=1,
        evidence_card_count=1,
        has_conflict=False,
        ledger=ledger,
        global_result=global_result,
    )

    assert decision.decision == "READ_MORE"
    assert decision.reason == "REQUIRED_FINDINGS_PARTIAL"
    assert decision.should_continue is True


def test_premature_commitment_guard_should_block_required_frozen_cell_and_active_branch() -> None:
    plan = build_research_plan(_build_task_input())
    ledger = ResearchStateLedger(
        entities=[ResearchEntityRow(entity_id="entity-1", display_name="Entity", source_ids=["src-1"])],
        cells=[
            ResearchStateCell(
                cell_id="entity-1:method",
                row_id="entity-1",
                entity_id="entity-1",
                column_key="method",
                candidate_value="unverified method",
                status="FROZEN",
                is_required=True,
            )
        ],
        branches=[
            ResearchStateBranch(
                branch_id="branch-counterfactual-1",
                branch_reason="CONFLICTING_EVIDENCE",
                hypothesis_summary="challenge the mainline",
                status="ACTIVE_BRANCH",
            )
        ],
    )

    guard = evaluate_premature_commitment_guard(plan, ledger, evidence_card_count=1)

    assert guard["blocked"] is True
    assert "REQUIRED_CELLS_NOT_VERIFIED" in guard["reason_codes"]
    assert "REQUIRED_CELL_FROZEN" in guard["reason_codes"]
    assert "ACTIVE_RECOVERY_BRANCH" in guard["reason_codes"]


def test_global_warn_should_not_synthesize_before_round_budget_is_exhausted() -> None:
    plan = build_research_plan(_build_task_input())
    ledger = ResearchStateLedger(
        entities=[ResearchEntityRow(entity_id="entity-1", display_name="Entity", source_ids=["src-1"])],
    )
    global_result = GlobalVerifierResult(
        status="WARN",
        decision="WRITE_WITH_GUARDRAILS",
        summary="independent verifier is not ready",
    )

    decision = evaluate_loop_decision(
        plan=plan,
        round_no=1,
        search_hit_count=1,
        read_window_count=1,
        evidence_card_count=1,
        has_conflict=False,
        ledger=ledger,
        global_result=global_result,
    )

    assert decision.decision == "READ_MORE"
    assert decision.reason == "GLOBAL_VERIFIER_NOT_READY"


def test_adaptive_evidence_horizon_should_change_next_round_targets_and_budget() -> None:
    plan = build_research_plan(_build_task_input())
    global_result = GlobalVerifierResult(
        status="WARN",
        decision="WRITE_WITH_GUARDRAILS",
        summary="expand uncertain cell",
    )
    artifacts = ResearchRoundArtifacts(
        search_hits=[],
        fetched_documents=[],
        read_windows=[],
        evidence_cards=[],
        ledger=ResearchStateLedger(),
        local_result=LocalVerifierResult(status="WARN"),
        branch_decisions=[],
        global_result=global_result,
        evidence_horizon_decisions=[
            {
                "entity_id": "entity-1",
                "column_key": "method",
                "action": "EXPAND",
                "target_window_count": 4,
            }
        ],
    )
    decision = ResearchLoopDecision(
        decision="READ_MORE",
        reason="PREMATURE_COMMITMENT_BLOCKED",
        round_no=1,
        should_continue=True,
    )

    revised = _augment_plan_for_next_round(plan, decision, artifacts)

    assert revised.stop_contract["evidence_horizon_target_columns"] == ["method"]
    assert revised.stop_contract["evidence_horizon_window_budget"] == 4
    assert "method" in revised.stop_contract["recovery_target_columns"]
    assert revised.plan_revision == plan.plan_revision + 1


def test_resume_context_should_restore_saved_plan_and_horizon_state() -> None:
    task_input = _build_task_input()
    saved_plan = build_research_plan(task_input).model_copy(update={
        "query_set": ["saved-query", "saved-counterfactual-query"],
        "plan_revision": 3,
        "replan_history": [{"revision": 3, "decision": "READ_MORE"}],
        "stop_contract": {
            **build_research_plan(task_input).stop_contract,
            "evidence_horizon_target_columns": ["method"],
            "evidence_horizon_window_budget": 4,
        },
    })
    payload = task_input.model_dump(mode="json")
    payload["input_payload"]["resume_checkpoint"] = {
        "source_research_run_id": "run-source",
        "checkpoint_no": 1,
        "snapshot_type": "RESEARCH_LOOP_CHECKPOINT",
        "final_loop_decision": "READ_MORE",
        "payload": {
            "plan": saved_plan.model_dump(mode="json", by_alias=True),
            "state_ledger": ResearchStateLedger().model_dump(mode="json", by_alias=True),
            "local_verifier": LocalVerifierResult(status="WARN").model_dump(mode="json"),
            "global_verifier": GlobalVerifierResult(
                status="WARN", decision="WRITE_WITH_GUARDRAILS", summary="continue"
            ).model_dump(mode="json"),
            "loop_decision": ResearchLoopDecision(
                decision="READ_MORE", reason="GLOBAL_VERIFIER_NOT_READY", round_no=1, should_continue=True
            ).model_dump(mode="json"),
            "evidence_horizon_decisions": [{"column_key": "method", "action": "EXPAND"}],
        },
    }
    resumed_input = ResearchTaskInput.model_validate(payload)

    context = _restore_resume_context(resumed_input, build_research_plan(resumed_input))

    assert context is not None
    assert context.plan.plan_revision == 3
    assert context.plan.query_set == ["saved-query", "saved-counterfactual-query"]
    assert context.plan.stop_contract["evidence_horizon_window_budget"] == 4
    assert context.artifacts.evidence_horizon_decisions[0]["action"] == "EXPAND"


def test_verifier_gate_policy_should_request_read_more_for_partial_required_findings() -> None:
    local_result = LocalVerifierResult(
        status="WARN",
        warnings=["required findings are still missing requirement-ready rows"],
        recovery_actions=["complete the missing required finding rows before final synthesis"],
    )
    global_result = GlobalVerifierResult(
        status="WARN",
        decision="WRITE_WITH_GUARDRAILS",
        summary="required finding is still partial",
        recovery_actions=["open targeted evidence windows for the missing required findings"],
    )
    ledger = ResearchStateLedger(
        unresolved_questions=["Missing targeted evidence windows."],
        required_finding_progress=[
            ResearchRequiredFindingProgress(
                requirement_id="goal_finding",
                requirement_type="GOAL_FINDING",
                label="Goal finding",
                status="PARTIAL",
                required_columns=["claim_text", "evidence_excerpt"],
            )
        ],
    )
    final_decision = ResearchLoopDecision(
        decision="READ_MORE",
        reason="REQUIRED_FINDINGS_PARTIAL",
        round_no=1,
        should_continue=True,
        recovery_actions=["open targeted evidence windows for the missing required findings"],
    )

    policy = build_verifier_gate_policy_state(
        ledger=ledger,
        local_result=local_result,
        global_result=global_result,
        final_decision=final_decision,
    )

    assert policy.loop_gate_action == "READ_MORE"
    assert policy.report_gate_action == "ALLOW_GUARDED_WRITE"
    assert policy.should_continue_loop is True
    assert policy.requires_read_more is True
    assert policy.requires_counterfactual_recheck is False
    assert "REQUIRED_FINDINGS_PARTIAL" in policy.blocking_reason_codes


def test_loop_runtime_should_record_internal_tool_traces() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)

    result = run_research_loop(task_input, plan)

    phases = {trace.phase for trace in result.artifacts.tool_traces}

    assert "TOOL_SEARCH_WEB" in phases
    assert "TOOL_FETCH_SOURCES" in phases
    assert "TOOL_OPEN_READ_WINDOWS" in phases
    assert "TOOL_EXTRACT_EVIDENCE" in phases
    assert "TOOL_UPDATE_STATE_LEDGER" in phases
    assert "TOOL_VERIFY_LOCAL" in phases
    assert "TOOL_OPEN_COUNTERFACTUAL_BRANCH" in phases
    assert "TOOL_VERIFY_GLOBAL" in phases
    fetch_trace = next(trace for trace in result.artifacts.tool_traces if trace.phase == "TOOL_FETCH_SOURCES")
    read_trace = next(trace for trace in result.artifacts.tool_traces if trace.phase == "TOOL_OPEN_READ_WINDOWS")
    search_trace = next(trace for trace in result.artifacts.tool_traces if trace.phase == "TOOL_SEARCH_WEB")
    assert "provider_resolution_summary" in search_trace.outputs
    assert "provider_attempt_chain_summary" in search_trace.outputs
    assert search_trace.outputs["selected_query_count"] >= 1
    assert "direct" in search_trace.outputs["selected_query_family_summary"]
    assert "fetch_status_summary" in fetch_trace.outputs
    assert "transport_resolution_summary" in fetch_trace.outputs
    assert "fetch_status_summary" in read_trace.outputs
    assert "fetch_method_summary" in read_trace.outputs
    assert "transport_resolution_summary" in read_trace.outputs
    assert "transport_attempt_chain_summary" in read_trace.outputs


def test_runner_should_include_loop_runtime_summary() -> None:
    task_input = _build_task_input()

    _events, result = run_research_task(task_input)

    assert result.result_payload["loop_rounds"][0]["round_no"] == 1
    assert result.result_payload["loop_decision"]["decision"] in {
        "SYNTHESIZE_REPORT",
        "WRITE_WITH_GUARDRAILS",
    }
    assert result.result_payload["tool_traces"]
    assert result.result_payload["state_ledger"]["cells"]
    assert result.result_payload["state_ledger"]["verifier_decisions"]
