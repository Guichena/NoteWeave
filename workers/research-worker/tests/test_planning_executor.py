from __future__ import annotations

import json
from types import SimpleNamespace

import pytest

from app.agent_command_contract import ResearchAgentCommand
from app.agent_task_client import AgentTaskClaim
from app.authoritative_role_router import AuthoritativeRoleRouter
from app.planning_executor import PURPOSE, PlanningExecutor, validate_plan
from app.task_snapshot_contract import TaskSnapshotTrustError, snapshot_digest

TASK_ID = "00000000-0000-0000-0000-000000000009"
PLANNING_INPUT = {
    "schema_version": "research-plan-input.v1",
    "question": "比较 PostgreSQL 与 MySQL 的 JSON 索引能力",
    "max_rows": 20,
    "min_columns": 2,
    "max_columns": 8,
    "max_cells": 80,
    "answer_formats": ["BOOLEAN", "DATE", "LIST", "LONG_TEXT", "NUMBER", "SHORT_TEXT"],
    "input_digest": "sha256:plan-input",
}


def _command() -> ResearchAgentCommand:
    return ResearchAgentCommand(
        schema_version="research-agent-command.v1", command_id="command-1", research_run_id="run-1",
        agent_task_id=TASK_ID, idempotency_key="command-1",
    )


def _claim(*, target_cells: list[dict[str, object]] | None = None) -> AgentTaskClaim:
    payload: dict[str, object] = {
        "schema_version": "research-agent-task-snapshot.v3",
        "task_id": TASK_ID,
        "research_run_id": "run-1",
        "workspace_id": "workspace-1",
        "role": "PLANNING",
        "entity_id": "planning",
        "branch_id": "branch-main",
        "plan_revision": 1,
        "entity_set_version": 1,
        "lease_epoch": 2,
        "fencing_token": 7,
        "target_cells": target_cells or [],
        "budget": {"llm_calls": 2, "search_calls": 1},
        "provider_key": "research-default",
        "source_policy": {
            "source_scope": [{"source_id": "source-1", "title": "数据库笔记", "sample_text": "JSONB GIN"}],
            "allow_external_search": False,
            "allow_external_fetch": False,
            "retrieval_mode": "SOURCES_ONLY",
        },
        "query_policy": {
            "query": PLANNING_INPUT["question"],
            "research_brief": {"current_question": PLANNING_INPUT["question"], "conversation_summary": "之前聊过索引"},
            "allowed_tools": ["search"],
            "planning_input": PLANNING_INPUT,
        },
        "logical_task_key": "planning:run-1",
        "candidate_quorum": 1,
        "candidate_slot": 1,
        "high_risk": False,
        "research_intent": {"depth": "STANDARD", "research_type": "AUTO"},
        "control_pack": {"pack_type": "RESEARCH_AGENT", "target_key": "DEFAULT", "task_neighborhood": "RESEARCH_DEFAULT"},
    }
    digest = snapshot_digest(payload)
    encoded = json.dumps({**payload, "snapshot_digest": digest}, ensure_ascii=False)
    return AgentTaskClaim(TASK_ID, 2, 7, "[]", '{"llm_calls":2}', encoded, digest)


class _Permit:
    def __init__(self) -> None:
        self.calls: list[str] = []

    def require_permit(self, snapshot, tool_identity: str) -> None:
        self.calls.append(tool_identity)


class _Toolchain:
    def __init__(self) -> None:
        self.plans = []

    def search(self, task_input, plan, *, allow_external: bool):
        self.plans.append(plan)
        return [SimpleNamespace(source_title="PostgreSQL GIN 文档", snippet="jsonb_path_ops",
                                url="https://www.postgresql.org/docs/17/gin.html", source_domain="")]


class _SequenceLlm:
    def __init__(self, *responses: object) -> None:
        self.responses = list(responses)
        self.calls: list[tuple[str, dict[str, object]]] = []

    def complete_json(self, purpose: str, payload: dict[str, object]) -> str:
        self.calls.append((purpose, payload))
        response = self.responses.pop(0) if self.responses else ""
        return response if isinstance(response, str) else json.dumps(response, ensure_ascii=False)


def _plan(**overrides: object) -> dict[str, object]:
    plan: dict[str, object] = {
        "brief": {"objective": "比较两者的 JSON 索引能力", "scope": "官方文档", "time_range": "",
                  "deliverable": "对比表", "exclusions": ["其他数据库"]},
        "research_type": "TECH_SOLUTION_COMPARISON",
        "effort_tier": "STANDARD",
        "perspectives": [{"name": "开发者", "questions": ["支持哪些索引"]}],
        "entities": [{"label": "PostgreSQL", "aliases": ["Postgres"]}, {"label": "MySQL"}],
        "dimensions": [
            {"label": "JSON 索引类型", "description": "支持哪些索引", "answer_format": "LIST",
             "high_risk": False, "search_intents": ["JSON index types"]},
            {"label": "可加速的查询运算符", "description": "索引覆盖哪些运算符", "answer_format": "LIST",
             "high_risk": False, "search_intents": ["JSON operators index support"]},
        ],
        "authoritative_domains": ["postgresql.org", "dev.mysql.com"],
    }
    plan.update(overrides)
    return plan


def _executor(llm, toolchain=None, permit=None) -> PlanningExecutor:
    return PlanningExecutor(permit or _Permit(), "worker-a", toolchain=toolchain or _Toolchain(),
                            llm_factory=lambda _context: llm)


def test_planning_should_probe_then_return_validated_plan_role_result() -> None:
    llm = _SequenceLlm(_plan())
    permit = _Permit()
    toolchain = _Toolchain()

    completion = _executor(llm, toolchain, permit)(_command(), _claim())

    result = completion.role_result
    assert result["role"] == "PLANNING"
    assert result["status"] == "PLANNED"
    assert result["input_digest"] == "sha256:plan-input"
    assert [entity["label"] for entity in result["plan"]["entities"]] == ["PostgreSQL", "MySQL"]
    assert completion.termination_reason == "ROLE_RESULT"
    assert completion.budget_usage.search_calls == 1
    assert completion.budget_usage.llm_calls == 1
    assert permit.calls == ["search"]
    # 探测检索使用问题关键词组成的短查询，不使用整段问题
    probe_plan = toolchain.plans[0]
    assert len(probe_plan.query_specs) == 1
    assert len(probe_plan.query_specs[0].query) < len(PLANNING_INPUT["question"]) + 1
    purpose, payload = llm.calls[0]
    assert purpose == PURPOSE
    assert payload["probe_results"][0]["domain"] == "www.postgresql.org"
    assert "input_digest" not in payload["planning_input"]
    assert payload["seed_sources"] == ["数据库笔记"]


def test_invalid_plan_should_be_repaired_once_with_the_rejection_reasons() -> None:
    llm = _SequenceLlm(_plan(dimensions=[{"label": "唯一维度"}]), _plan())

    completion = _executor(llm)(_command(), _claim())

    assert completion.role_result["status"] == "PLANNED"
    assert len(llm.calls) == 2
    assert any("at least 2" in problem for problem in llm.calls[1][1]["previous_output_rejected"])


def test_two_invalid_plans_should_fall_back() -> None:
    llm = _SequenceLlm("not json", _plan(entities=[]))

    completion = _executor(llm)(_command(), _claim())

    assert completion.role_result["status"] == "FALLBACK"
    assert "plan" not in completion.role_result
    assert completion.role_result["degradation_reasons"] == ["PLANNING_LLM_OUTPUT_INVALID"]


def test_missing_llm_should_fall_back_without_searching() -> None:
    permit = _Permit()
    executor = PlanningExecutor(permit, "worker-a", toolchain=_Toolchain(), llm_factory=lambda _context: None)

    completion = executor(_command(), _claim())

    assert completion.role_result["status"] == "FALLBACK"
    assert completion.role_result["degradation_reasons"] == ["PLANNING_LLM_UNAVAILABLE"]
    assert permit.calls == []
    assert completion.budget_usage.search_calls == 0


def test_probe_failure_should_not_block_planning() -> None:
    class _BrokenToolchain:
        def search(self, *_args, **_kwargs):
            raise RuntimeError("provider down")

    llm = _SequenceLlm(_plan())

    completion = _executor(llm, _BrokenToolchain())(_command(), _claim())

    assert completion.role_result["status"] == "PLANNED"
    assert "PLANNING_PROBE_SEARCH_FAILED" in completion.role_result["degradation_reasons"]
    assert llm.calls[0][1]["probe_results"] == []


def test_validate_plan_should_enforce_cell_limits_and_dedupe_labels() -> None:
    draft = validate_plan(_plan(
        entities=[{"label": f"实体{index}"} for index in range(11)],
        dimensions=[{"label": f"维度{index}"} for index in range(8)],
    ), PLANNING_INPUT)
    assert draft.plan is None
    assert any("must not exceed 80" in problem for problem in draft.problems)

    deduped = validate_plan(_plan(entities=[{"label": "MySQL"}, {"label": " mysql "}]), PLANNING_INPUT)
    assert [entity["label"] for entity in deduped.plan["entities"]] == ["MySQL"]


def test_router_should_dispatch_planning_and_reject_cell_targets() -> None:
    executor = _executor(_SequenceLlm(_plan()))
    router = AuthoritativeRoleRouter({"PLANNING": executor}, enabled_roles={"PLANNING"})

    assert router(_command(), _claim()).role_result["status"] == "PLANNED"
    # 带单元格目标的 PLANNING 快照在校验阶段就被拒绝
    with pytest.raises(TaskSnapshotTrustError):
        router(_command(), _claim(target_cells=[{"cell_id": "entity-1:answer", "expected_version": 1}]))
