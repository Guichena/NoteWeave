from __future__ import annotations

from app.models import ResearchPlan


def build_recovery_targets(plan: ResearchPlan) -> dict[str, object]:
    requirement_ids = _string_list(plan.stop_contract.get("recovery_target_requirement_ids"))
    requirement_types = _string_list(plan.stop_contract.get("recovery_target_requirement_types"))
    requirement_labels = _string_list(plan.stop_contract.get("recovery_target_requirement_labels"))
    target_columns = _string_list(plan.stop_contract.get("recovery_target_columns"))
    target_queries = _string_list(plan.stop_contract.get("recovery_target_queries"))
    target_sources = _string_list(plan.stop_contract.get("recovery_target_sources"))
    return {
        "requirement_ids": requirement_ids,
        "requirement_types": requirement_types,
        "requirement_labels": requirement_labels,
        "target_columns": target_columns,
        "target_queries": target_queries,
        "target_sources": target_sources,
        "requirement_count": len(requirement_ids),
        "query_count": len(target_queries),
        "source_count": len(target_sources),
        "column_count": len(target_columns),
    }


def _string_list(value: object) -> list[str]:
    if not isinstance(value, list):
        return []
    return [str(item).strip() for item in value if str(item).strip()]
