from __future__ import annotations

import json

from app.cell_query_planner import (
    PURPOSE,
    CellColumn,
    CellQueryContext,
    plan_cell_queries,
)
from app.models import ResearchPlan
from app.query_specs import with_query_specs
from app.search_adapters import build_search_query_plan


class _SequenceLlm:
    def __init__(self, *responses: object) -> None:
        self.responses = list(responses)
        self.calls: list[tuple[str, dict[str, object]]] = []

    def complete_json(self, purpose: str, payload: dict[str, object]) -> str:
        self.calls.append((purpose, payload))
        response = self.responses.pop(0) if self.responses else ""
        return response if isinstance(response, str) else json.dumps(response, ensure_ascii=False)


def _context(**overrides: object) -> CellQueryContext:
    values: dict[str, object] = {
        "question": "PostgreSQL 和 MySQL 的 JSON 索引能力有什么区别？",
        "entity_label": "PostgreSQL 17",
        "columns": (
            CellColumn("json_index", "JSON 索引类型"),
            CellColumn("answer", "Answer"),
        ),
    }
    values.update(overrides)
    return CellQueryContext(**values)  # type: ignore[arg-type]


def test_valid_model_output_should_become_structured_specs() -> None:
    llm = _SequenceLlm({"queries": [
        {"query": "PostgreSQL JSON index", "intent": "direct", "column_keys": ["json_index", "answer"], "language": "en"},
        {"query": "PostgreSQL GIN jsonb_path_ops operator classes", "intent": "verify",
         "column_keys": ["json_index", "unknown_column"]},
        {"query": "PostgreSQL GIN index limitations jsonb", "intent": "counter", "column_keys": []},
    ]})

    plan = plan_cell_queries(_context(), llm)

    assert plan.method == "LLM"
    assert plan.degradation_reasons == []
    assert [spec.family for spec in plan.specs] == ["direct", "verified_evidence", "counterfactual"]
    assert [spec.search_lane for spec in plan.specs] == ["BALANCED", "DEEP_FOCUS", "COUNTERFACTUAL"]
    # 不在本任务列中的列键会被丢弃
    assert plan.specs[1].column_keys == ["json_index"]
    purpose, payload = llm.calls[0]
    assert purpose == PURPOSE
    assert payload["entity"] == "PostgreSQL 17"
    assert payload["columns"][0] == {
        "key": "json_index", "label": "JSON 索引类型", "description": "", "search_intents": [],
    }


def test_invalid_queries_should_be_rejected_and_repaired_once() -> None:
    llm = _SequenceLlm(
        {"queries": [
            {"query": "PostgreSQL :: counterfactual evidence check", "intent": "counter"},
            {"query": "x" * 201, "intent": "direct"},
            {"query": "PostgreSQL JSON index", "intent": "direct"},
        ]},
        {"queries": [{"query": "PostgreSQL jsonb GIN index", "intent": "direct", "column_keys": ["json_index", "answer"]}]},
    )

    plan = plan_cell_queries(_context(tried_queries=("PostgreSQL JSON index",)), llm)

    assert plan.method == "LLM"
    assert [spec.query for spec in plan.specs] == ["PostgreSQL jsonb GIN index"]
    assert len(llm.calls) == 2
    rejected = llm.calls[1][1]["previous_output_rejected"]
    reasons = {item["reason"] for item in rejected}
    assert "query contains '::' or line breaks" in reasons
    assert "query is longer than 200 characters" in reasons
    assert "query repeats a tried query" in reasons


def test_duplicate_queries_in_one_output_should_keep_only_the_first() -> None:
    llm = _SequenceLlm({"queries": [
        {"query": "PostgreSQL JSON index types", "intent": "direct", "column_keys": ["json_index", "answer"]},
        {"query": "PostgreSQL JSON index types", "intent": "verify"},
    ]})

    plan = plan_cell_queries(_context(), llm)

    assert [spec.query for spec in plan.specs] == ["PostgreSQL JSON index types"]


def test_unusable_model_output_twice_should_fall_back_to_rules() -> None:
    llm = _SequenceLlm("not json", {"queries": []})

    plan = plan_cell_queries(_context(), llm)

    assert plan.method == "RULE"
    assert plan.degradation_reasons == ["CELL_QUERY_LLM_OUTPUT_INVALID"]
    assert len(llm.calls) == 2


def test_model_exception_should_fall_back_without_a_second_call() -> None:
    class _BrokenLlm:
        calls = 0

        def complete_json(self, purpose: str, payload: dict[str, object]) -> str:
            _BrokenLlm.calls += 1
            raise RuntimeError("provider down")

    plan = plan_cell_queries(_context(), _BrokenLlm())

    assert plan.method == "RULE"
    assert plan.degradation_reasons == ["CELL_QUERY_LLM_CALL_FAILED"]
    assert _BrokenLlm.calls == 1


def test_rule_plan_should_combine_entity_with_meaningful_column_labels() -> None:
    plan = plan_cell_queries(_context(time_range="2024"), None)

    queries = [spec.query for spec in plan.specs]
    assert plan.method == "RULE"
    assert plan.degradation_reasons == ["CELL_QUERY_LLM_UNAVAILABLE"]
    assert queries[0].startswith("PostgreSQL 17")
    assert "PostgreSQL 17 JSON 索引类型" in queries
    # 通用列名对检索没有帮助，不生成单独的查询
    assert not any(query.endswith("Answer") for query in queries)
    assert "PostgreSQL 17 2024" in queries
    assert all("::" not in query and "?" not in query and "？" not in query for query in queries)


def test_rule_plan_without_entity_should_use_question_keywords() -> None:
    plan = plan_cell_queries(_context(entity_label="", columns=()), None)

    assert len(plan.specs) == 1
    assert "PostgreSQL" in plan.specs[0].query
    assert "？" not in plan.specs[0].query


def test_counterfactual_role_rule_plan_should_include_a_counter_query() -> None:
    plan = plan_cell_queries(_context(role="COUNTERFACTUAL"), None)

    assert "counterfactual" in [spec.family for spec in plan.specs]


def test_search_query_plan_should_follow_spec_order_metadata_and_budget() -> None:
    llm = _SequenceLlm({"queries": [
        {"query": "PostgreSQL JSON index", "intent": "direct", "column_keys": ["json_index", "answer"]},
        {"query": "PostgreSQL GIN jsonb_path_ops", "intent": "verify"},
        {"query": "PostgreSQL GIN limitations", "intent": "counter"},
    ]})
    specs = plan_cell_queries(_context(), llm).specs
    plan = with_query_specs(ResearchPlan(
        normalized_question="question",
        stop_contract={"execution_profile": {"search_query_budget": 2}},
    ), specs)

    items = build_search_query_plan(plan)

    assert [item.query for item in items] == ["PostgreSQL JSON index", "PostgreSQL GIN jsonb_path_ops"]
    assert [item.family for item in items] == ["direct", "verified_evidence"]
    assert [item.lane for item in items] == ["BALANCED", "DEEP_FOCUS"]
    assert [item.priority for item in items] == [1, 2]
