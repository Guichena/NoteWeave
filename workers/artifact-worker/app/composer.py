from __future__ import annotations

from app.models import ArtifactExecutionPlan, ArtifactSectionDraft, ArtifactTaskInput


def compose_sections(
    task_input: ArtifactTaskInput,
    plan: ArtifactExecutionPlan,
) -> list[ArtifactSectionDraft]:
    source_titles = [item.title for item in task_input.source_scope[:3]]
    source_line = ", ".join(source_titles) if source_titles else "workspace source pool"

    sections: list[ArtifactSectionDraft] = []
    for heading in plan.outline:
        sections.append(
            ArtifactSectionDraft(
                heading=heading,
                body=(
                    f"This section addresses {heading.lower()} using material compiled from "
                    f"{source_line}."
                ),
                source_refs=source_titles[:1] or [],
            )
        )
    return sections


def render_markdown(
    task_input: ArtifactTaskInput,
    plan: ArtifactExecutionPlan,
    sections: list[ArtifactSectionDraft],
) -> str:
    source_titles = [item.title for item in task_input.source_scope[:3]]
    lines = [
        f"# {plan.action_key.title()} Draft",
        "",
        "## Generation Goal",
        f"- Action: {plan.action_key}",
        f"- Style profile: {plan.style_profile_key}",
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

    return "\n".join(lines).strip() + "\n"
