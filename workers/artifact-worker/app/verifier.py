from __future__ import annotations

from app.models import ArtifactExecutionPlan, ArtifactTaskResult, ArtifactVerificationResult


def verify_artifact_output(
    result: ArtifactTaskResult,
    plan: ArtifactExecutionPlan,
    repaired_checks: list[str],
) -> ArtifactVerificationResult:
    markdown = result.result_payload.get("markdown", "")
    if not isinstance(markdown, str) or not markdown.strip():
        raise ValueError("artifact markdown must not be empty")
    if not result.result_title.strip():
        raise ValueError("artifact title must not be empty")

    passed_checks: list[str] = []
    warnings: list[str] = []

    for heading in plan.outline:
        if f"### {heading}" in markdown:
            passed_checks.append(f"section present: {heading}")
        else:
            warnings.append(f"section missing after repair: {heading}")

    status = "PASS" if not warnings else "WARN"
    return ArtifactVerificationResult(
        status=status,
        passed_checks=passed_checks,
        repaired_checks=repaired_checks,
        warnings=warnings,
    )
