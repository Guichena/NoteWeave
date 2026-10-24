"""规划列规范化、修复与裁剪不能丢失用户的显式要求。"""

from copy import deepcopy

import pytest

from app.planning_executor import validate_plan
from tests.test_planning_executor import PLANNING_INPUT, _SequenceLlm, _claim, _command, _executor, _plan


def requirement(dimension, *, entities=None, quote="JSON 索引能力"):
    return {"text": "核对 JSON 索引能力", "question_quote": quote, "dimension": dimension,
            "entities": entities or [], "completion_mode": "SINGLE", "minimum_count": 1, "count_expression": ""}


def strict_input(**overrides):
    return {**PLANNING_INPUT, "requirement_coverage_enabled": True, **overrides}


def test_shared_column_renaming_preserves_explicit_entity_bindings():
    raw = _plan(
        entities=[{"label": "PostgreSQL 17"}, {"label": "MySQL 8.4"}],
        dimensions=[{"label": "PostgreSQL 17 JSON 索引能力"}, {"label": "MySQL 8.4 JSON 索引能力"},
                    {"label": "查询条件"}],
        user_requirements=[requirement("PostgreSQL 17 JSON 索引能力", entities=["PostgreSQL 17"]),
                           requirement("MySQL 8.4 JSON 索引能力", entities=["MySQL 8.4"])],
    )
    original = deepcopy(raw)
    draft = validate_plan(raw, strict_input())
    assert draft.problems == []
    assert [item["dimension"] for item in draft.plan["user_requirements"]] == ["JSON 索引能力", "JSON 索引能力"]
    assert [item["entities"] for item in draft.plan["user_requirements"]] == [["PostgreSQL 17"], ["MySQL 8.4"]]
    assert raw == original


def test_exact_deduplication_aliases_are_rebound_before_compilation():
    raw = _plan(
        entities=[{"label": "Atlas DB"}, {"label": "AtlasDB"}],
        dimensions=[{"label": "JSON Indexes"}, {"label": "ＪＳＯＮ　Indexes"}, {"label": "Concurrency"}],
        required_points=[{"point": "支持的索引形式", "dimension": "ＪＳＯＮ　Indexes"}],
        user_requirements=[requirement("ＪＳＯＮ　Indexes", entities=["AtlasDB"])],
    )
    draft = validate_plan(raw, strict_input())
    assert draft.problems == []
    assert draft.plan["user_requirements"][0]["dimension"] == "JSON Indexes"
    assert draft.plan["user_requirements"][0]["entities"] == ["Atlas DB"]
    assert "支持的索引形式" in draft.plan["dimensions"][0]["requirements"]


def test_binding_whitespace_variants_need_not_be_duplicated_in_the_dimension_list():
    raw = _plan(entities=[{"label": "Atlas DB"}],
                dimensions=[{"label": "JSON Indexes"}, {"label": "Concurrency"}],
                user_requirements=[requirement("ＪＳＯＮIndexes", entities=["AtlasDB"])])
    draft = validate_plan(raw, strict_input())
    assert draft.problems == []
    assert draft.plan["user_requirements"][0]["dimension"] == "JSON Indexes"
    assert draft.plan["user_requirements"][0]["entities"] == ["Atlas DB"]


def test_shared_column_merge_uses_the_same_spacing_rules_as_backend_keys():
    raw = _plan(dimensions=[{"label": "PostgreSQL JSON Indexes"}, {"label": "MySQL JSONIndexes"}, {"label": "Usage"}],
                user_requirements=[requirement("PostgreSQLJSONIndexes", entities=["PostgreSQL"]),
                                   requirement("MySQL JSONIndexes", entities=["MySQL"])])
    draft = validate_plan(raw, strict_input())
    assert draft.problems == []
    assert [item["label"] for item in draft.plan["dimensions"]] == ["JSON Indexes", "Usage"]
    assert [item["dimension"] for item in draft.plan["user_requirements"]] == ["JSON Indexes", "JSON Indexes"]


@pytest.mark.parametrize("invalid_dimension", ["Sources", "不存在的维度"])
def test_unresolved_references_return_specific_errors_without_guessing(invalid_dimension):
    raw = _plan(user_requirements=[requirement(invalid_dimension)])
    raw["dimensions"].append({"label": "Sources"})
    draft = validate_plan(raw, strict_input(), final_attempt=True)
    assert draft.plan is None
    assert any("user_requirements[0].dimension" in problem and invalid_dimension in problem
               and "JSON 索引类型" in problem for problem in draft.problems)
    assert draft.repair_context["user_requirements"][0]["dimension"] == invalid_dimension
    assert "Sources" not in [item["label"] for item in draft.repair_context["dimensions"]]


def test_final_expert_point_repair_does_not_replace_an_explicit_column():
    raw = _plan(dimensions=[{"label": "用户要求"}, {"label": "可替换的补充列"}],
                required_points=[{"point": "专家必查内容", "dimension": "缺少的列"}],
                user_requirements=[requirement("用户要求")])
    draft = validate_plan(raw, strict_input(max_columns=2), final_attempt=True)
    assert draft.problems == []
    assert [item["label"] for item in draft.plan["dimensions"]] == ["用户要求", "专家必查内容"]
    assert draft.plan["user_requirements"][0]["dimension"] == "用户要求"


def test_full_table_cannot_replace_mandatory_columns_to_hide_a_missing_point():
    raw = _plan(dimensions=[{"label": "用户要求 A"}, {"label": "用户要求 B"}],
                required_points=[{"point": "专家必查内容", "dimension": "缺少的列"}],
                user_requirements=[requirement("用户要求 A"), requirement("用户要求 B")])
    draft = validate_plan(raw, strict_input(max_columns=2), final_attempt=True)
    assert draft.plan is None
    assert any("专家必查内容" in problem for problem in draft.problems)
    assert [item["label"] for item in draft.repair_context["dimensions"]] == ["用户要求 A", "用户要求 B"]


def test_expert_point_is_added_when_all_existing_columns_are_explicit_and_room_remains():
    raw = _plan(dimensions=[{"label": "用户要求 A"}, {"label": "用户要求 B"}],
                required_points=[{"point": "专家必查内容", "dimension": "缺少的列"}],
                user_requirements=[requirement("用户要求 A"), requirement("用户要求 B")])
    draft = validate_plan(raw, strict_input(max_columns=3), final_attempt=True)
    assert draft.problems == []
    assert {item["label"] for item in draft.plan["dimensions"]} == {"用户要求 A", "用户要求 B", "专家必查内容"}
    assert len(draft.plan["user_requirements"]) == 2


def test_oversized_explicit_plan_is_rejected_without_losing_rows_or_columns():
    raw = _plan(effort_tier="COMPLEX", entities=[{"label": f"对象 {index}"} for index in range(4)],
                dimensions=[{"label": f"维度 {index}"} for index in range(8)],
                user_requirements=[requirement("维度 7")])
    draft = validate_plan(raw, strict_input(), final_attempt=True)
    assert draft.plan is None
    assert any("24 cells" in problem for problem in draft.problems)
    assert len(draft.repair_context["entities"]) == 4
    assert len(draft.repair_context["dimensions"]) == 8


def test_model_repair_gets_the_failed_plan_and_exact_invalid_binding(monkeypatch):
    monkeypatch.setitem(PLANNING_INPUT, "requirement_coverage_enabled", True)
    bad = _plan(user_requirements=[requirement("Sources")])
    good = _plan(user_requirements=[requirement("JSON 索引类型")])
    llm = _SequenceLlm(bad, good)
    completion = _executor(llm)(_command(), _claim())
    assert completion.role_result["status"] == "PLANNED"
    assert completion.budget_usage.llm_calls == 2
    repair = llm.calls[1][1]
    assert repair["candidate_plan_for_repair"]["user_requirements"][0]["dimension"] == "Sources"
    assert any("user_requirements[0].dimension" in problem for problem in repair["previous_output_rejected"])
    attempts = completion.role_result["planning_diagnostics"]["attempts"]
    assert [item["status"] for item in attempts] == ["REJECTED", "ACCEPTED"]
    assert attempts[0]["requirement_bindings"] == [{"index": 0, "dimension": "Sources"}]
    assert "question_quote" not in str(attempts)


def test_explicit_requirements_are_protected_even_when_optional_in_the_protocol():
    raw = _plan(effort_tier="COMPLEX", entities=[{"label": f"对象 {index}"} for index in range(4)],
                dimensions=[{"label": f"维度 {index}"} for index in range(8)],
                user_requirements=[requirement("维度 7")])
    draft = validate_plan(raw, PLANNING_INPUT, final_attempt=True)
    assert draft.plan is None
    assert len(draft.repair_context["entities"]) == 4
    assert len(draft.repair_context["dimensions"]) == 8


def test_unrepaired_invalid_binding_still_falls_back_and_retains_diagnostics(monkeypatch):
    monkeypatch.setitem(PLANNING_INPUT, "requirement_coverage_enabled", True)
    raw = _plan(user_requirements=[requirement("不存在的维度")])
    completion = _executor(_SequenceLlm(raw, raw, raw))(_command(), _claim())
    assert completion.role_result["status"] == "FALLBACK"
    assert "plan" not in completion.role_result
    assert [item["status"] for item in completion.role_result["planning_diagnostics"]["attempts"]] == ["REJECTED"] * 3
