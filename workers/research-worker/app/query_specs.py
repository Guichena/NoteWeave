"""按检索词查找它在计划里登记的用途。"""

from __future__ import annotations

from app.models import ResearchPlan, ResearchQuerySpec


def find_query_spec(plan: ResearchPlan | None, query: str) -> ResearchQuerySpec | None:
    if plan is None:
        return None
    target = (query or "").strip()
    for spec in plan.query_specs:
        if spec.query == target:
            return spec
    for item in plan.stop_contract.get("query_spec_history", []):
        if isinstance(item, dict) and item.get("query") == target:
            return ResearchQuerySpec.model_validate(item)
    return None


def with_query_specs(plan: ResearchPlan, specs: list[ResearchQuerySpec]) -> ResearchPlan:
    """用结构化检索词替换计划里的查询，query_set 与之保持一致。"""
    return plan.model_copy(update={
        "query_specs": list(specs),
        "query_set": [spec.query for spec in specs],
    })
