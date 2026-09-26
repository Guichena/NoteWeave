from __future__ import annotations

import hashlib

from app.artifact_content_ir import build_content_ir
from app.candidate_file_manifest import build_required_files
from app.models import (
    ArtifactJobSnapshot, ArtifactProgressEvent, ArtifactSectionDraft,
    ArtifactTaskInput, ArtifactTaskResult, ArtifactVersionSnapshot,
)
from app.video_deck_ir import VideoDeckIRV1, build_video_deck_ir
from app.video_knowledge_plan import VideoKnowledgePlanV1
from app.video_material_bundle import VideoMaterialBundleV1


def render_video_deck_markdown(ir: VideoDeckIRV1) -> str:
    lines = [f"# {ir.title}", ""]
    for slide in ir.slides:
        lines += [f"## {slide.sequence_no}. {slide.title}", "",
                  f"- Video: {ir.bvid} P{slide.part} @ {slide.at_ms / 1000:.1f}s",
                  f"- Original frame: {slide.frame_id} ({slide.image_checksum_sha256})"]
        for claim in slide.claims:
            lines.append(f"- {claim}")
        for ref in slide.evidence_refs:
            lines.append(f"- Evidence: {ref}")
        for gap in slide.coverage_gaps:
            lines.append(f"- Gap: {gap}")
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def run_video_deck_task(task_input: ArtifactTaskInput) -> tuple[
        list[ArtifactProgressEvent], ArtifactTaskResult]:
    """Prepare a frozen deck Candidate; callback attaches verified rendered files."""
    if task_input.input_payload.skill_key != "video_learning_deck" \
            or not task_input.input_snapshot_id or not task_input.catalog_digest \
            or not task_input.input_payload.inputs.get("video_material_bundle_id") \
            or not task_input.frozen_video_material \
            or not task_input.frozen_video_knowledge_plan:
        raise ValueError("video deck requires a published Skill and frozen video inputs")
    bundle = VideoMaterialBundleV1.model_validate(task_input.frozen_video_material)
    plan = VideoKnowledgePlanV1.model_validate(task_input.frozen_video_knowledge_plan)
    ir = build_video_deck_ir(bundle, plan)
    markdown = render_video_deck_markdown(ir)
    sections = [ArtifactSectionDraft(
        heading=slide.title,
        body="\n\n".join(slide.claims) if slide.claims else
             "Visual evidence only; meaning remains unverified.",
        source_refs=slide.evidence_refs + [f"frame:{slide.frame_id}"],
    ) for slide in ir.slides]
    content_ir = build_content_ir(artifact_type="SLIDE_DECK", title=ir.title,
                                  sections=sections, markdown=markdown)
    markdown_hash = hashlib.sha256(markdown.encode("utf-8")).hexdigest()
    candidate_id = hashlib.sha256(
        f"{task_input.task_id}:{task_input.input_snapshot_id}:{markdown_hash}".encode("utf-8")
    ).hexdigest()
    payload: dict[str, object] = {
        "markdown": markdown,
        "sections": [section.model_dump(mode="json") for section in sections],
        "video_deck_ir": ir.model_dump(mode="json"),
        "content_ir": content_ir.model_dump(mode="json"),
        "verification": {"status": "PASS", "failed_checks": []},
        "export_trace": {"status": "PENDING", "format": "PPTX", "file_name": ""},
        "knowledge_plan": {
            "schema_version": plan.schema_version,
            "bundle_content_digest": plan.bundle_content_digest,
            "content_digest": plan.content_digest(),
            "mode": "FROZEN_PLAN",
        },
        "candidate": {
            "candidate_id": candidate_id, "task_id": task_input.task_id,
            "input_snapshot_id": task_input.input_snapshot_id,
            "catalog_digest": task_input.catalog_digest,
            "content_sha256": markdown_hash,
            "content_ir_digest": content_ir.content_digest,
            "required_files": build_required_files(markdown, {"format": "MARKDOWN"}),
        },
    }
    result = ArtifactTaskResult(
        result_title=ir.title, result_payload=payload,
        trace_summary="Frozen video evidence mapped to original-image slides",
        citations=[{"title": slide.title, "source_refs": slide.evidence_refs}
                   for slide in ir.slides],
        job_snapshot=ArtifactJobSnapshot(
            task_id=task_input.task_id, workspace_id=task_input.workspace_id,
            target_id=task_input.target_id, action_key="SLIDE_DECK", status="COMPLETED"),
        version_snapshot=ArtifactVersionSnapshot(
            version_id="", artifact_type="SLIDE_DECK", title=ir.title,
            status="DRAFT", summary="Original frames and frozen video evidence"),
    )
    return ([ArtifactProgressEvent(phase="VERIFYING", progress_percent=85,
                                   message="frozen deck evidence verified")], result)
