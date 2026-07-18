from __future__ import annotations

from app.models import ArtifactExecutionPlan, ArtifactSectionDraft, ArtifactTaskInput


def render_markdown(
    task_input: ArtifactTaskInput,
    plan: ArtifactExecutionPlan,
    sections: list[ArtifactSectionDraft],
) -> str:
    source_titles = [item.title for item in task_input.source_scope[:3]]
    lines = [
        f"# {plan.action_display_name}",
        "",
        "## Generation Goal",
        f"- Skill: {plan.skill_key or task_input.input_payload.skill_key or 'legacy_action_request'}",
        f"- Goal: {plan.execution_spec.goal or task_input.input_payload.generation_brief or plan.action_display_name}",
        f"- Style profile: {plan.style_profile_key}",
        f"- Writeback gate: {plan.writeback_gate.decision} ({plan.writeback_gate.requested_mode})",
        "",
        "## Sections",
    ]

    for section in sections:
        lines.extend(
            [
                f"### {section.heading}",
                section.body,
            ]
        )
        if section.source_refs:
            lines.append(f"Source refs: {', '.join(section.source_refs)}")
        lines.append("")

    lines.extend(
        [
            "## Source Scope",
            f"- {', '.join(source_titles) if source_titles else 'workspace source pool'}",
        ]
    )

    if plan.notes:
        lines.extend(["", "## Control Notes"])
        for note in plan.notes:
            lines.append(f"- {note}")

    lines.extend(
        [
            "",
            "## Runtime Keywords",
            "- " + " / ".join(plan.required_phrases or ["Controlled Agentic Graph Harness"]),
        ]
    )

    return "\n".join(lines).strip() + "\n"
