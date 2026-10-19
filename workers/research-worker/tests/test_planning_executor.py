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


def test_validate_plan_should_limit_table_size_by_effort_tier() -> None:
    def dims(count: int) -> list[dict[str, object]]:
        return [{"label": f"维度{index}"} for index in range(count)]

    simple = validate_plan(_plan(effort_tier="SIMPLE", entities=[{"label": f"问题{index}"} for index in range(3)],
                                 dimensions=dims(2)), PLANNING_INPUT)
    assert simple.plan is None
    assert any("SIMPLE allows at most 2 entities" in problem for problem in simple.problems)

    simple_wide = validate_plan(_plan(effort_tier="SIMPLE", entities=[{"label": "Java 虚拟线程"}, {"label": "JDK"}],
                                      dimensions=dims(4)), PLANNING_INPUT)
    assert any("SIMPLE allows at most 6 cells" in problem for problem in simple_wide.problems)

    standard = validate_plan(_plan(effort_tier="STANDARD", dimensions=dims(7)), PLANNING_INPUT)
    assert any("STANDARD allows at most 12 cells" in problem for problem in standard.problems)

    complex_plan = validate_plan(_plan(effort_tier="COMPLEX", entities=[{"label": f"实体{index}"} for index in range(4)],
                                       dimensions=dims(6)), PLANNING_INPUT)
    assert complex_plan.plan is not None
    assert complex_plan.plan["effort_tier"] == "COMPLEX"


def test_planning_policy_should_forbid_sub_questions_as_entities() -> None:
    from types import SimpleNamespace

    from app.planning_executor import _call_model

    llm = _SequenceLlm(_plan())
    snapshot = SimpleNamespace(query_policy={}, source_policy={})
    _call_model(llm, snapshot, PLANNING_INPUT, [], [])

    policy_text = " ".join(llm.calls[0][1]["policy"])
    assert "never sub-questions" in policy_text
    assert "COMPLEX at most 24 cells" in policy_text


def test_router_should_dispatch_planning_and_reject_cell_targets() -> None:
    executor = _executor(_SequenceLlm(_plan()))
    router = AuthoritativeRoleRouter({"PLANNING": executor}, enabled_roles={"PLANNING"})

    assert router(_command(), _claim()).role_result["status"] == "PLANNED"
    # 带单元格目标的 PLANNING 快照在校验阶段就被拒绝
    with pytest.raises(TaskSnapshotTrustError):
        router(_command(), _claim(target_cells=[{"cell_id": "entity-1:answer", "expected_version": 1}]))


def test_final_attempt_repairs_an_oversized_plan_instead_of_falling_back() -> None:
    def dims(count: int) -> list[dict[str, object]]:
        return [{"label": f"维度{index}", "answer_format": "TEXT"} for index in range(count)]

    # 声明 SIMPLE 却规划了 2×6：最后一次升到能容纳的 STANDARD，不认识的格式按 SHORT_TEXT
    upgraded = validate_plan(_plan(effort_tier="SIMPLE", entities=[{"label": "OAuth 2.1"}, {"label": "OIDC"}],
                                   dimensions=dims(6)), PLANNING_INPUT, final_attempt=True)
    assert upgraded.plan is not None
    assert upgraded.plan["effort_tier"] == "STANDARD"
    assert {item["answer_format"] for item in upgraded.plan["dimensions"]} == {"SHORT_TEXT"}

    # 超出 COMPLEX 上限时截断多余的列
    trimmed = validate_plan(_plan(effort_tier="COMPLEX", entities=[{"label": f"实体{index}"} for index in range(5)],
                                  dimensions=dims(8)), PLANNING_INPUT, final_attempt=True)
    assert trimmed.plan is not None
    assert len(trimmed.plan["entities"]) * len(trimmed.plan["dimensions"]) <= 24


def test_second_oversized_plan_is_repaired_and_planned() -> None:
    oversized = _plan(effort_tier="SIMPLE", entities=[{"label": f"问题{index}"} for index in range(3)])
    llm = _SequenceLlm(oversized, oversized)

    completion = _executor(llm)(_command(), _claim())

    assert completion.role_result["status"] == "PLANNED"
    assert len(llm.calls) == 2


def test_echoed_request_is_rejected_with_a_specific_reason() -> None:
    draft = validate_plan({"purpose": "research.plan.matrix", "payload": {"policy": []}}, PLANNING_INPUT)

    assert draft.plan is None
    assert "repeated the request" in draft.problems[0]


def test_meta_dimensions_are_dropped_but_evidence_content_columns_stay() -> None:
    draft = validate_plan(_plan(dimensions=[
        {"label": "Incompatible changes list"}, {"label": "Migration tooling"},
        {"label": "Official quote and source"}, {"label": "规范原文引用"},
        {"label": "Relevant RFC references"}, {"label": "发布与改善原始引用"},
    ]), PLANNING_INPUT)
    assert [item["label"] for item in draft.plan["dimensions"]] == ["Incompatible changes list", "Migration tooling"]

    conflict = validate_plan(_plan(dimensions=[{"label": "支持的证据与来源"}, {"label": "反对的证据与来源"}]), PLANNING_INPUT)
    assert [item["label"] for item in conflict.plan["dimensions"]] == ["支持的证据与来源", "反对的证据与来源"]


def test_required_point_without_a_dimension_is_sent_back_then_filled_on_the_last_attempt() -> None:
    raw = _plan(dimensions=[{"label": "Dev server model"}, {"label": "HMR mechanism"}, {"label": "Failure fallback"}])
    raw["required_points"] = [
        {"point": "Whether the dev server bundles", "dimension": "Dev server model"},
        {"point": "HMR granularity and mechanism", "dimension": "HMR mechanism"},
        {"point": "Tool used for production builds", "dimension": "Production build"},
    ]

    first = validate_plan(raw, PLANNING_INPUT)
    assert first.plan is None
    assert any("Tool used for production builds" in problem for problem in first.problems)

    final = validate_plan(raw, PLANNING_INPUT, final_attempt=True)
    labels = [item["label"] for item in final.plan["dimensions"]]
    # 不回答任何要点的 Failure fallback 被替换为生产构建这一要点
    assert labels == ["Dev server model", "HMR mechanism", "Tool used for production builds"]
    # 要点随规划一起交给后端，成文时逐条作答
    assert final.plan["required_points"] == [
        "Whether the dev server bundles", "HMR granularity and mechanism", "Tool used for production builds"]


def test_plan_without_required_points_is_not_checked() -> None:
    draft = validate_plan(_plan(dimensions=[{"label": "A"}, {"label": "B"}]), PLANNING_INPUT)
    assert draft.plan is not None


def test_entity_names_are_removed_from_dimension_labels_and_duplicates_merge() -> None:
    """列名带研究对象名时交叉出的单元格（MySQL 的 PostgreSQL 索引类型）没有意义，去掉对象名并合并同名列。"""
    draft = validate_plan(_plan(
        entities=[{"label": "PostgreSQL 17", "aliases": ["PG"]}, {"label": "MySQL 8.4"}],
        dimensions=[
            {"label": "PostgreSQL 17 JSON 索引类型", "description": "PG 索引", "answer_format": "LIST",
             "high_risk": False, "search_intents": ["PostgreSQL JSON index types"]},
            {"label": "MySQL 8.4 JSON 索引类型", "description": "MySQL 索引", "answer_format": "LIST",
             "high_risk": False, "search_intents": ["MySQL JSON index types"]},
            {"label": "索引代价", "description": "写入开销", "answer_format": "LIST",
             "high_risk": False, "search_intents": ["index write overhead"]},
            {"label": "Upgrade path", "description": "升级路径", "answer_format": "LIST",
             "high_risk": False, "search_intents": ["upgrade"]},
        ],
        not_applicable=[{"entity": "MySQL 8.4", "dimension": "PostgreSQL 17 JSON 索引类型"},
                        {"entity": "MySQL 8.4", "dimension": "索引代价"}],
    ), PLANNING_INPUT)

    assert draft.problems == []
    labels = [item["label"] for item in draft.plan["dimensions"]]
    assert labels == ["JSON 索引类型", "索引代价", "Upgrade path"]
    assert draft.plan["dimensions"][0]["search_intents"] == ["PostgreSQL JSON index types", "MySQL JSON index types"]
    # 原来针对带对象名列的不适用标记不再沿用，其他标记保留
    assert draft.plan["not_applicable"] == [{"entity": "MySQL 8.4", "dimension": "索引代价"}]


def test_single_entity_dimension_labels_are_kept() -> None:
    draft = validate_plan(_plan(
        entities=[{"label": "PostgreSQL 17"}],
        dimensions=[
            {"label": "PostgreSQL 17 增量备份命令", "description": "命令", "answer_format": "LIST",
             "high_risk": False, "search_intents": ["pg_basebackup incremental"]},
            {"label": "合并工具", "description": "pg_combinebackup", "answer_format": "LIST",
             "high_risk": False, "search_intents": ["pg_combinebackup"]},
        ],
    ), PLANNING_INPUT)

    assert [item["label"] for item in draft.plan["dimensions"]] == ["PostgreSQL 17 增量备份命令", "合并工具"]
