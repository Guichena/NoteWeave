from __future__ import annotations

import hashlib

import pytest
from pydantic import ValidationError

from app.video_derived_text import (
    VideoDerivedTextV1, derive_video_text, render_derived_markdown,
)
from app.video_knowledge_plan import VideoKnowledgePlanV1, build_local_evidence_plan
from app.video_material_bundle import VideoMaterialBundleV1
from app.artifact_skill_catalog import CATALOG_DIGEST
from app.models import ArtifactTaskInput
from app.runner import run_artifact_task
from app.callback import JavaArtifactCallbackClient, run_artifact_task_with_callbacks


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


@pytest.mark.parametrize("skill_key,action", [
    ("knowledge_blog", "KNOWLEDGE_BLOG"),
    ("interview_qa", "INTERVIEW_QA"),
])
def test_published_derived_skill_builds_an_independent_host_candidate(
        skill_key: str, action: str) -> None:
    bundle, plan = _source()
    task = ArtifactTaskInput.model_validate({
        "task_id": f"task-{skill_key}", "workspace_id": bundle.workspace_id,
        "target_id": f"job-{skill_key}", "input_snapshot_id": f"snapshot-{skill_key}",
        "catalog_digest": CATALOG_DIGEST,
        "control_pack": {"pack_type": "ARTIFACT", "target_key": skill_key,
                         "task_neighborhood": "ARTIFACT"},
        "input_payload": {"skill_key": skill_key,
                          "inputs": {"url": "https://www.bilibili.com/video/BV1234567890",
                                     "video_material_bundle_id": "bundle-row-1"}},
        "frozen_video_material": bundle.model_dump(mode="json"),
        "frozen_video_knowledge_plan": plan.model_dump(mode="json"),
    })
    events, result = run_artifact_task(task)
    assert result.job_snapshot.status == "COMPLETED"
    assert result.version_snapshot.artifact_type == action
    assert result.result_payload["content_ir"]["artifact_type"] == action
    assert result.result_payload["verification"]["status"] == "PASS"
    assert result.result_payload["candidate"]["required_files"][0]["role"] == "PRIMARY_MARKDOWN"
    assert events[-1].progress_percent == 100
    assert result.result_payload["derived_text_ir"]["plan_content_digest"] == plan.content_digest()
    task.frozen_video_knowledge_plan = build_local_evidence_plan(bundle).model_dump(mode="json")
    with pytest.raises(ValueError, match="no verified semantic claims"):
        run_artifact_task(task)


def test_callback_fetches_frozen_plan_and_attaches_bundle_to_candidate() -> None:
    bundle, plan = _source()
    task = ArtifactTaskInput.model_validate({
        "task_id": "task-blog", "workspace_id": bundle.workspace_id,
        "target_id": "job-blog", "input_snapshot_id": "snapshot-blog",
        "catalog_digest": CATALOG_DIGEST,
        "control_pack": {"pack_type": "ARTIFACT", "target_key": "knowledge_blog",
                         "task_neighborhood": "ARTIFACT"},
        "input_payload": {"skill_key": "knowledge_blog",
                          "inputs": {"url": "https://www.bilibili.com/video/BV1234567890",
                                     "language": "zh-CN",
                                     "video_material_bundle_id": "bundle-row-1"}},
    })

    class Host(JavaArtifactCallbackClient):
        def __init__(self):
            super().__init__("http://host.local")
            self.completed = []
            self.plan_reads = 0

        def fetch_task_input(self, task_id):
            return task

        def fetch_video_material(self, task_id, bundle_row_id=""):
            assert bundle_row_id == "bundle-row-1"
            return bundle

        def fetch_video_knowledge_plan(self, task_id, bundle_row_id, *, referenced=False):
            assert referenced is True
            self.plan_reads += 1
            return plan

        def send_progress(self, task_id, event):
            pass

        def send_complete(self, task_id, result):
            self.completed.append(result)

    host = Host()
    response = run_artifact_task_with_callbacks(task.task_id, host)
    assert response.status == "COMPLETED"
    assert host.plan_reads == 1
    assert len(host.completed) == 1
    assert host.completed[0].result_payload["candidate"]["video_material"]["id"] == \
        "bundle-row-1"
