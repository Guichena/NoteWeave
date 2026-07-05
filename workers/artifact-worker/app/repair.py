from __future__ import annotations

from app.models import ArtifactExecutionPlan, ArtifactSectionDraft


def repair_sections(
    sections: list[ArtifactSectionDraft],
    plan: ArtifactExecutionPlan,
    forbidden_patterns: list[str],
) -> tuple[list[ArtifactSectionDraft], list[str]]:
    repaired_checks: list[str] = []
    repaired_sections: list[ArtifactSectionDraft] = []

    by_heading = {section.heading: section for section in sections}
    for heading in plan.outline:
        section = by_heading.get(heading)
        if section is None:
            repaired_sections.append(
                ArtifactSectionDraft(
                    heading=heading,
                    body=f"This section was inserted by local repair to satisfy the schema gate for {heading}.",
                    source_refs=[],
                )
            )
            repaired_checks.append(f"missing section repaired: {heading}")
            continue

        body = section.body
        for pattern in forbidden_patterns:
            if pattern and pattern in body:
                body = body.replace(pattern, "[removed]")
                repaired_checks.append(f"forbidden pattern removed: {pattern}")

        repaired_sections.append(
            ArtifactSectionDraft(
                heading=section.heading,
                body=body,
                source_refs=section.source_refs,
            )
        )

    return repaired_sections, repaired_checks
