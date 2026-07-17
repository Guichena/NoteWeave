from __future__ import annotations

from app.models import ResearchIntent


PRIMARY_FINDING_COLUMNS = [
    "source_title",
    "claim_text",
    "evidence_excerpt",
    "support_level",
    "verifier_note",
]

CONFLICT_FINDING_COLUMNS = [
    "source_title",
    "claim_text",
    "evidence_excerpt",
    "verifier_note",
]


def build_required_finding_contract(
    question: str,
    intent: ResearchIntent,
) -> list[dict[str, object]]:
    specs: list[dict[str, object]] = []
    goal_label = intent.research_goal.strip() or question.strip()
    if goal_label:
        specs.append({
            "requirement_id": "goal_finding",
            "requirement_type": "GOAL_FINDING",
            "label": f"Goal finding: {goal_label}",
            "completion_mode": "ROW_EVIDENCE",
            "required_columns": list(PRIMARY_FINDING_COLUMNS),
            "accepted_row_statuses": ["VERIFIED"],
            "target_row_count": 1,
        })

    for index, raw_constraint in enumerate(intent.constraints, start=1):
        constraint = raw_constraint.strip()
        if not constraint:
            continue
        normalized = constraint.lower()
        if any(token in normalized for token in ["verified", "evidence", "已验证", "证据"]):
            specs.append({
                "requirement_id": f"constraint_finding_{index}",
                "requirement_type": "CONSTRAINT_FINDING",
                "label": f"Evidence finding: {constraint}",
                "completion_mode": "ROW_EVIDENCE",
                "required_columns": list(PRIMARY_FINDING_COLUMNS),
                "accepted_row_statuses": ["VERIFIED"],
                "target_row_count": 1,
            })
            continue
        if any(token in normalized for token in ["counterfactual", "反证"]):
            specs.append({
                "requirement_id": f"constraint_conflict_{index}",
                "requirement_type": "CONFLICT_FINDING",
                "label": f"Conflict finding: {constraint}",
                "completion_mode": "ROW_EVIDENCE",
                "required_columns": list(CONFLICT_FINDING_COLUMNS),
                "accepted_row_statuses": ["CONFLICTED"],
                "target_row_count": 1,
            })
    return specs
