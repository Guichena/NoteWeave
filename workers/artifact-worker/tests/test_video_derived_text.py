from __future__ import annotations

import hashlib

import pytest
from pydantic import ValidationError

from app.video_derived_text import (
    VideoDerivedTextV1, derive_video_text, render_derived_markdown,
)
from app.video_knowledge_plan import VideoKnowledgePlanV1, build_local_evidence_plan
from app.video_material_bundle import VideoMaterialBundleV1


def _source() -> tuple[VideoMaterialBundleV1, VideoKnowledgePlanV1]:
    bundle = VideoMaterialBundleV1.model_validate({
        "bundle_id": "bundle-1", "bundle_version": 1,
        "workspace_id": "workspace-1", "bvid": "BV1234567890", "part": 1,
        "duration_ms": 5000, "input_digest": "a" * 64,
        "subtitle_source": "MANUAL", "transcript_original": "cache consistency",
        "transcript_corrected": "cache consistency",
        "transcript_segments": [{"segment_id": "s1", "part": 1, "start_ms": 1000,
                                 "end_ms": 4000, "original_text": "cache consistency",
                                 "corrected_text": "cache consistency"}],
        "frames": [], "files": [], "knowledge_nodes": [], "coverage_gaps": ["NO_FRAMES"],
    })
    plan = VideoKnowledgePlanV1.model_validate({
        "bundle_content_digest": bundle.content_digest(),
        "bvid": bundle.bvid, "part": bundle.part, "duration_ms": bundle.duration_ms,
        "nodes": [
            {"node_id": "root", "kind": "TOPIC", "title": "Cache", "start_ms": 0,
             "end_ms": 5000},
            {"node_id": "concept", "parent_id": "root", "kind": "CONCEPT",
             "title": "Consistency", "start_ms": 1000, "end_ms": 4000,
             "transcript_segment_ids": ["s1"], "terms": ["cache"],
             "claims": [{"text": "cache consistency", "status": "EXTRACTED",
                         "evidence_refs": ["segment:s1"]}]},
        ],
    })
    return bundle, plan


def test_blog_and_qa_use_same_frozen_claims_but_independent_ir() -> None:
    bundle, plan = _source()
    blog = derive_video_text(bundle, plan, "knowledge_blog")
    qa = derive_video_text(bundle, plan, "interview_qa")
    blog_markdown = render_derived_markdown(blog)
    qa_markdown = render_derived_markdown(qa)

    blog.verify_against(bundle, plan, blog_markdown)
    qa.verify_against(bundle, plan, qa_markdown)
    assert blog.content_digest != qa.content_digest
    assert blog.terms == qa.terms == ["cache"]
    assert blog.blog_sections[0].claims == qa.interview_questions[0].detailed_answer
    assert "### Short answer" in qa_markdown
    assert "### Detailed answer" in qa_markdown
    assert "### Related knowledge" in qa_markdown
    assert "Evidence: segment:s1" in blog_markdown


def test_local_evidence_index_cannot_be_published_as_semantic_output() -> None:
    bundle, _ = _source()
    with pytest.raises(ValueError, match="no verified semantic claims"):
        derive_video_text(bundle, build_local_evidence_plan(bundle), "knowledge_blog")


def test_forged_claim_or_rendering_is_rejected() -> None:
    bundle, plan = _source()
    blog = derive_video_text(bundle, plan, "knowledge_blog")
    tampered = blog.model_copy(deep=True)
    tampered.blog_sections[0].claims[0].text = "unsupported fact"
    forged_markdown = render_derived_markdown(tampered)
    tampered.markdown_sha256 = hashlib.sha256(forged_markdown.encode("utf-8")).hexdigest()
    tampered.content_digest = tampered.digest()
    tampered = VideoDerivedTextV1.model_validate(tampered.model_dump(mode="json"))
    with pytest.raises(ValueError, match="outside the frozen plan"):
        tampered.verify_against(bundle, plan, forged_markdown)
    with pytest.raises(ValueError, match="differs from frozen inputs"):
        blog.verify_against(bundle, plan, "changed rendering")
    with pytest.raises(ValidationError, match="digest mismatch"):
        VideoDerivedTextV1.model_validate({**blog.model_dump(mode="json"),
                                            "content_digest": "0" * 64})


def test_each_artifact_fails_independently_on_invalid_shape() -> None:
    bundle, plan = _source()
    blog = derive_video_text(bundle, plan, "knowledge_blog")
    fields = blog.model_dump(mode="json")
    fields["blog_sections"] = []
    with pytest.raises(ValidationError, match="invalid artifact shape"):
        VideoDerivedTextV1.model_validate(fields)
    derive_video_text(bundle, plan, "interview_qa").verify_against(
        bundle, plan, render_derived_markdown(derive_video_text(bundle, plan, "interview_qa")))
