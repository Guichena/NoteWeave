from __future__ import annotations

import hashlib

from app.artifact_content_ir import build_content_ir
from app.candidate_file_manifest import build_required_files
from app.models import (
    ArtifactJobSnapshot, ArtifactProgressEvent, ArtifactSectionDraft,
    ArtifactTaskInput, ArtifactTaskResult, ArtifactVersionSnapshot,
)
from app.video_derived_text import derive_video_text, render_derived_markdown
from app.video_knowledge_plan import VideoKnowledgePlanV1
from app.video_material_bundle import VideoMaterialBundleV1


_ACTIONS = {"knowledge_blog": "KNOWLEDGE_BLOG", "interview_qa": "INTERVIEW_QA"}


def run_video_derived_text_task(
    task_input: ArtifactTaskInput,
) -> tuple[list[ArtifactProgressEvent], ArtifactTaskResult]:
    """Derive one independent Candidate using only Host-frozen video evidence."""
    skill_key = task_input.input_payload.skill_key
    if skill_key not in _ACTIONS or not task_input.input_snapshot_id \
            or not task_input.catalog_digest \
            or not task_input.input_payload.inputs.get("video_material_bundle_id") \
            or not task_input.frozen_video_material \
            or not task_input.frozen_video_knowledge_plan:
        raise ValueError("derived text requires a published Skill and frozen video inputs")
    bundle = VideoMaterialBundleV1.model_validate(task_input.frozen_video_material)
    plan = VideoKnowledgePlanV1.model_validate(task_input.frozen_video_knowledge_plan)
    ir = derive_video_text(bundle, plan, skill_key)
    markdown = render_derived_markdown(ir)
    ir.verify_against(bundle, plan, markdown)
    sections: list[ArtifactSectionDraft] = []
    if skill_key == "knowledge_blog":
        for section in ir.blog_sections:
            sections.append(ArtifactSectionDraft(
                heading=section.heading,
                body="\n\n".join(claim.text for claim in section.claims),
                source_refs=list(dict.fromkeys(
                    ref for claim in section.claims for ref in claim.evidence_refs)),
            ))
    else:
        for question in ir.interview_questions:
            sections.append(ArtifactSectionDraft(
                heading=question.question,
                body="\n\n".join(claim.text for claim in question.detailed_answer),
                source_refs=list(dict.fromkeys(
                    ref for claim in question.detailed_answer for ref in claim.evidence_refs)),
            ))
    action = _ACTIONS[skill_key]
    content_ir = build_content_ir(artifact_type=action, title=ir.title,
                                  sections=sections, markdown=markdown)
    content_sha256 = hashlib.sha256(markdown.encode("utf-8")).hexdigest()
    candidate_id = hashlib.sha256(
        f"{task_input.task_id}:{task_input.input_snapshot_id}:{content_sha256}".encode("utf-8")
    ).hexdigest()
    payload: dict[str, object] = {
        "markdown": markdown,
        "sections": [section.model_dump(mode="json") for section in sections],
        "derived_text_ir": ir.model_dump(mode="json"),
        "content_ir": content_ir.model_dump(mode="json"),
        "verification": {"status": "PASS", "failed_checks": []},
        "export_trace": {"status": "SKIPPED", "format": "MARKDOWN", "file_name": ""},
        "knowledge_plan": {
            "schema_version": plan.schema_version,
            "bundle_content_digest": plan.bundle_content_digest,
            "content_digest": plan.content_digest(),
            "mode": "FROZEN_PLAN",
        },
        "candidate": {
            "candidate_id": candidate_id,
            "task_id": task_input.task_id,
            "input_snapshot_id": task_input.input_snapshot_id,
            "catalog_digest": task_input.catalog_digest,
            "content_sha256": content_sha256,
            "content_ir_digest": content_ir.content_digest,
            "required_files": build_required_files(markdown, {"format": "MARKDOWN"}),
        },
    }
    result = ArtifactTaskResult(
        result_title=ir.title, result_payload=payload,
        trace_summary="Frozen video material and knowledge plan derived into verified text",
        citations=[{"title": section.heading, "source_refs": section.source_refs}
                   for section in sections],
        job_snapshot=ArtifactJobSnapshot(
            task_id=task_input.task_id, workspace_id=task_input.workspace_id,
            target_id=task_input.target_id, action_key=action, status="COMPLETED"),
        version_snapshot=ArtifactVersionSnapshot(
            version_id="", artifact_type=action, title=ir.title, status="DRAFT",
            summary="Derived from frozen video evidence"),
    )
    return ([ArtifactProgressEvent(phase="VERIFYING", progress_percent=85,
                                   message="frozen evidence and output contract verified"),
             ArtifactProgressEvent(phase="EXPORTING", progress_percent=100,
                                   message="Markdown candidate prepared")], result)
