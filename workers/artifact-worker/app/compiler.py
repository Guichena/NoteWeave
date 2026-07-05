from __future__ import annotations

from app.models import ArtifactExecutionPlan, ArtifactTaskInput


OUTLINE_PRESETS = {
    "REPORT": ["Problem", "Evidence Summary", "Recommendation"],
    "FAQ": ["Question Set", "Standard Answers", "Usage Notes"],
    "QUIZ": ["Knowledge Targets", "Questions", "Answers And Explanations"],
    "STUDY_GUIDE": ["Learning Goals", "Core Concepts", "Practice Route"],
    "WIKI": ["Overview", "Key Facts", "Linked Topics"],
    "NOTE": ["Topic Snapshot", "Key Excerpts", "Follow-up Questions"],
}


def build_execution_plan(task_input: ArtifactTaskInput) -> ArtifactExecutionPlan:
    action_key = task_input.input_payload.action_key.strip().upper() or "REPORT"
    style_profile_key = task_input.input_payload.style_profile_key.strip().upper() or "DEFAULT"
    outline = OUTLINE_PRESETS.get(action_key, ["Goal", "Content", "Output"])
    required_capabilities = [
        "source_bundle",
        "section_composer",
        "schema_gate",
        "local_repair",
    ]
    schema_gate_rules = [
        "Every output must preserve the requested outline.",
        "Every section must point to at least one workspace source when available.",
        "Forbidden patterns must be removed before export.",
    ]
    notes: list[str] = []
    if task_input.control_pack.structure_constraints:
        notes.append(
            "structure constraints: "
            + "; ".join(task_input.control_pack.structure_constraints)
        )
    if task_input.control_pack.forbidden_patterns:
        notes.append(
            "forbidden patterns: "
            + "; ".join(task_input.control_pack.forbidden_patterns)
        )
    return ArtifactExecutionPlan(
        action_key=action_key,
        style_profile_key=style_profile_key,
        required_capabilities=required_capabilities,
        outline=outline,
        schema_gate_status="PASSED",
        schema_gate_rules=schema_gate_rules,
        notes=notes,
    )
