from __future__ import annotations

from copy import deepcopy
import json
from types import SimpleNamespace

import pytest
from pydantic import ValidationError

import app.callback as callback_module
from app.llm_client import FakeLlmClient
from app.callback import ArtifactCallbackHttpError, _freeze_local_evidence_plan
from app.video_knowledge_plan import (
    VideoKnowledgePlanV1, build_local_evidence_plan, plan_video_knowledge,
)
from app.video_material_bundle import VideoMaterialBundleV1


def _bundle() -> VideoMaterialBundleV1:
    return VideoMaterialBundleV1.model_validate({
        "bundle_id": "bundle-1", "bundle_version": 1,
        "workspace_id": "workspace-1", "bvid": "BV1234567890", "part": 2,
        "duration_ms": 10_000, "input_digest": "a" * 64,
        "subtitle_source": "MANUAL", "transcript_original": "cache yizhi",
        "transcript_corrected": "cache 一致性",
        "transcript_segments": [{"segment_id": "s1", "part": 2,
                                 "start_ms": 1000, "end_ms": 4000,
                                 "original_text": "cache yizhi",
                                 "corrected_text": "cache 一致性"}],
        "files": [{"file_id": "file-1", "role": "VIDEO_FRAME",
                   "media_type": "image/png", "size_bytes": 120,
                   "checksum_sha256": "b" * 64}],
        "frames": [{"frame_id": "f1", "part": 2, "at_ms": 2500,
                    "file_id": "file-1", "checksum_sha256": "b" * 64}],
        "knowledge_nodes": [{"node_id": "time-bucket", "title": "Time bucket",
                             "start_ms": 1000, "end_ms": 4000,
                             "transcript_segment_ids": ["s1"], "frame_ids": ["f1"]}],
        "frame_observations": [{
            "schema_version": "frame-observation-v1", "task_id": "task-1",
            "file_id": "file-1", "checksum_sha256": "b" * 64,
            "media_type": "image/png", "width": 4, "height": 3,
            "observations": [{"kind": "TEXT", "text": "Cache diagram",
                              "confidence": 91, "uncertain": False}],
            "coverage_gaps": ["VISUAL_SEMANTICS_UNVERIFIED"],
        }], "coverage_gaps": [],
    })


def _plan(bundle: VideoMaterialBundleV1) -> dict[str, object]:
    return {
        "bundle_content_digest": bundle.content_digest(),
        "bvid": bundle.bvid, "part": bundle.part,
        "duration_ms": bundle.duration_ms,
        "nodes": [
            {"node_id": "root", "parent_id": "", "kind": "TOPIC",
             "title": "Cache", "start_ms": 0, "end_ms": 10_000},
            {"node_id": "concept", "parent_id": "root", "kind": "CONCEPT",
             "title": "Consistency", "start_ms": 1000, "end_ms": 4000,
             "transcript_segment_ids": ["s1"], "frame_ids": ["f1"],
             "terms": ["一致性", "Cache"],
             "claims": [{"text": "cache 一致性", "status": "EXTRACTED",
                         "evidence_refs": ["segment:s1"]},
                        {"text": "Cache diagram", "status": "EXTRACTED",
                         "evidence_refs": ["frame:f1"]}]},
        ],
    }


def test_planner_accepts_only_frozen_evidence_and_has_stable_digest() -> None:
    bundle = _bundle()
    response = json.dumps(_plan(bundle), ensure_ascii=False)
    client = FakeLlmClient({"video_knowledge_plan": response})

    plan = plan_video_knowledge(bundle, client)

    assert plan.nodes[1].parent_id == "root"
    assert plan.content_digest() == VideoKnowledgePlanV1.model_validate(_plan(bundle)).content_digest()
    assert client.calls[0][1]["bundle_content_digest"] == bundle.content_digest()
    assert client.calls[0][1]["frame_observations"][0]["observations"][0]["text"] == "Cache diagram"


@pytest.mark.parametrize("change,expected", [
    (lambda p: p.update(bundle_content_digest="0" * 64), "frozen Bundle"),
    (lambda p: p.update(part=1), "frozen Bundle"),
    (lambda p: p["nodes"][1].update(start_ms=5000, end_ms=6000), "outside its time range"),
    (lambda p: p["nodes"][1]["claims"][0].update(text="Cache is always coherent"), "absent"),
    (lambda p: p["nodes"][1]["claims"][0].update(evidence_refs=["segment:s1", "segment:unknown"]), "absent"),
    (lambda p: p["nodes"][1].update(terms=["imaginary term"]), "term is absent"),
    (lambda p: p["nodes"][1].update(frame_ids=[]), "absent"),
    (lambda p: p["nodes"][1].update(transcript_segment_ids=[]), "absent"),
])
def test_plan_rejects_changed_scope_or_ungrounded_claims(change, expected: str) -> None:
    bundle = _bundle()
    value = deepcopy(_plan(bundle))
    change(value)
    with pytest.raises(ValueError, match=expected):
        plan_video_knowledge(bundle, FakeLlmClient({"video_knowledge_plan": json.dumps(value)}))


def test_plan_rejects_missing_evidence_coverage_even_without_claims() -> None:
    bundle = _bundle()
    value = _plan(bundle)
    value["nodes"][1]["claims"] = []
    value["nodes"][1]["terms"] = []
    value["nodes"][1]["frame_ids"] = []
    with pytest.raises(ValueError, match="does not cover every"):
        VideoKnowledgePlanV1.model_validate(value).verify_against_bundle(bundle)


def test_unverified_claim_needs_explicit_gap_and_no_fake_citation() -> None:
    bundle = _bundle()
    value = _plan(bundle)
    value["nodes"][1]["claims"] = [
        {"text": "The diagram proves linearizability", "status": "UNVERIFIED"}]
    with pytest.raises(ValueError, match="explicit gap"):
        VideoKnowledgePlanV1.model_validate(value).verify_against_bundle(bundle)
    value["nodes"][1]["missing"] = ["UNVERIFIED_CLAIM"]
    VideoKnowledgePlanV1.model_validate(value).verify_against_bundle(bundle)
    value["nodes"][1]["claims"][0]["evidence_refs"] = ["frame:f1"]
    with pytest.raises(ValidationError, match="cite evidence or be unverified"):
        VideoKnowledgePlanV1.model_validate(value)


def test_plan_rejects_cycle_and_multiple_roots() -> None:
    bundle = _bundle()
    value = _plan(bundle)
    value["nodes"][0]["parent_id"] = "concept"
    with pytest.raises(ValidationError, match="one topic root"):
        VideoKnowledgePlanV1.model_validate(value)
    value["nodes"][0]["parent_id"] = ""
    value["nodes"][1]["parent_id"] = ""
    with pytest.raises(ValidationError, match="one topic root"):
        VideoKnowledgePlanV1.model_validate(value)


def test_outline_is_assembled_into_a_contract_complete_plan_keeping_only_real_quotes() -> None:
    bundle = _bundle()
    outline = {"title": "缓存", "concepts": [{
        "title": "一致性", "segment_ids": ["s1", "unknown"], "terms": ["一致性", "invented"],
        "claims": [{"segment_id": "s1", "quote": "cache 一致性"},
                   {"segment_id": "s1", "quote": "缓存永远强一致"}],
    }]}
    client = FakeLlmClient({"video_knowledge_plan": json.dumps(outline, ensure_ascii=False)})

    plan = plan_video_knowledge(bundle, client)

    concept = next(node for node in plan.nodes if node.kind == "CONCEPT")
    assert [claim.text for claim in concept.claims] == ["cache 一致性"]
    assert concept.terms == ["一致性"]
    assert concept.transcript_segment_ids == ["s1"] and concept.frame_ids == ["f1"]
    assert (concept.start_ms, concept.end_ms) == (1000, 4000)
    assert client.calls[0][1]["contract"] == "video-knowledge-outline-v1"


def test_outline_without_verifiable_claims_is_rejected_and_uncited_segments_stay_covered() -> None:
    bundle = _bundle()
    no_quotes = {"concepts": [{"title": "x", "segment_ids": ["s1"],
                               "claims": [{"segment_id": "s1", "quote": "not in the subtitle"}]}]}
    with pytest.raises(ValueError, match="no verifiable claim"):
        plan_video_knowledge(bundle, FakeLlmClient({"video_knowledge_plan": json.dumps(no_quotes)}))


def test_planner_rejects_unusable_response() -> None:
    bundle = _bundle()
    with pytest.raises(ValueError, match="no bounded plan"):
        plan_video_knowledge(bundle, FakeLlmClient({"video_knowledge_plan": ""}))
    with pytest.raises(ValueError, match="invalid JSON"):
        plan_video_knowledge(bundle, FakeLlmClient({"video_knowledge_plan": "{"}))


def test_local_evidence_plan_covers_frozen_material_without_semantic_claims() -> None:
    bundle = _bundle()
    plan = build_local_evidence_plan(bundle)

    assert [node.kind for node in plan.nodes] == [
        "TOPIC", "EVIDENCE_WINDOW", "EVIDENCE_WINDOW"]
    assert plan.nodes[1].frame_ids == ["f1"]
    assert plan.nodes[2].transcript_segment_ids == ["s1"]
    assert all(not node.claims and not node.terms for node in plan.nodes)
    assert plan.content_digest() == build_local_evidence_plan(bundle).content_digest()


def test_local_evidence_plan_marks_empty_bundle_coverage() -> None:
    value = _bundle().model_dump(mode="json")
    value.update(subtitle_source="NONE", transcript_original="", transcript_corrected="",
                 transcript_segments=[], frames=[], files=[], frame_observations=None,
                 knowledge_nodes=[], coverage_gaps=["NO_SUBTITLE", "NO_FRAMES"])
    plan = build_local_evidence_plan(VideoMaterialBundleV1.model_validate(value))
    assert plan.nodes[0].missing == ["NO_SUBTITLE", "NO_FRAMES"]


def test_local_plan_freeze_reuses_host_plan_after_retry() -> None:
    bundle = _bundle()

    class Store:
        plan = None
        submissions = 0

        def fetch_video_knowledge_plan(self, task_id, bundle_row_id):
            if self.plan is None:
                raise ArtifactCallbackHttpError(404, "not frozen")
            return self.plan

        def publish_video_knowledge_plan(self, task_id, bundle_row_id, plan):
            self.submissions += 1
            self.plan = plan
            return {"id": "plan-1", "bundle_row_id": bundle_row_id,
                    "content_digest": plan.content_digest()}

    store = Store()
    first = SimpleNamespace(result_payload={})
    second = SimpleNamespace(result_payload={})
    _freeze_local_evidence_plan("task-1", first, store, bundle, "row-1")
    _freeze_local_evidence_plan("task-1", second, store, bundle, "row-1")

    assert store.submissions == 1
    assert second.result_payload["knowledge_plan"]["content_digest"] == \
        first.result_payload["knowledge_plan"]["content_digest"]


def test_local_plan_freeze_failure_keeps_pdf_path_available() -> None:
    class Store:
        def fetch_video_knowledge_plan(self, task_id, bundle_row_id):
            raise ArtifactCallbackHttpError(503, "temporary unavailable")

    result = SimpleNamespace(result_payload={})
    _freeze_local_evidence_plan("task-1", result, Store(), _bundle(), "row-1")
    assert result.result_payload["knowledge_plan_gap"] == "HOST_PLAN_LOOKUP_UNAVAILABLE"


def test_host_freezes_semantic_plan_when_model_is_configured(monkeypatch) -> None:
    bundle = _bundle()
    model = FakeLlmClient({"video_knowledge_plan": json.dumps(_plan(bundle))})
    monkeypatch.setattr(callback_module, "build_default_llm_client", lambda: model)

    class Store:
        plan = None

        def fetch_video_knowledge_plan(self, task_id, bundle_row_id):
            if self.plan is None:
                raise ArtifactCallbackHttpError(404, "not frozen")
            return self.plan

        def publish_video_knowledge_plan(self, task_id, bundle_row_id, plan):
            self.plan = plan
            return {"id": "plan-1"}

    store = Store()
    result = SimpleNamespace(result_payload={})
    _freeze_local_evidence_plan("task-1", result, store, bundle, "row-1")

    assert result.result_payload["knowledge_plan"]["mode"] == "FROZEN_PLAN"
    assert store.plan.nodes[1].claims[0].text == "cache 一致性"
    assert len(model.calls) == 1

    _freeze_local_evidence_plan("task-1", SimpleNamespace(result_payload={}),
                                store, bundle, "row-1")
    assert len(model.calls) == 1


def test_invalid_model_plan_is_not_published_as_semantic_content(monkeypatch) -> None:
    bundle = _bundle()
    monkeypatch.setattr(callback_module, "build_default_llm_client", lambda:
                        FakeLlmClient({"video_knowledge_plan": "{"}))

    class Store:
        def fetch_video_knowledge_plan(self, task_id, bundle_row_id):
            raise ArtifactCallbackHttpError(404, "not frozen")

        def publish_video_knowledge_plan(self, task_id, bundle_row_id, plan):
            pytest.fail("invalid semantic plan must not be frozen")

    result = SimpleNamespace(result_payload={})
    _freeze_local_evidence_plan("task-1", result, Store(), bundle, "row-1")

    assert result.result_payload["knowledge_plan_gap"] == "KNOWLEDGE_PLANNING_FAILED"
    assert "knowledge_plan" not in result.result_payload


def test_parent_material_completes_with_evidence_index_when_model_times_out(monkeypatch) -> None:
    bundle = _bundle()
    monkeypatch.setattr(callback_module, "_collect_parent_video_material", lambda *_: bundle)
    monkeypatch.setattr(callback_module, "_plan_frozen_video_material", lambda *_:
                        (_ for _ in ()).throw(ValueError("planner timed out")))

    class Store:
        plan = None
        completed = False

        def publish_parent_material(self, task_id, material):
            assert material is bundle
            return {"id": "material-1"}

        def publish_parent_plan(self, task_id, material_id, plan):
            self.plan = plan
            return {"id": "plan-1"}

        def complete_parent_material(self, task_id, material_id, plan_id):
            self.completed = True

    store = Store()
    result = SimpleNamespace(job_snapshot=SimpleNamespace(status="COMPLETED"))
    callback_module._emit_material_callbacks_for_result("task-1", [], result, store, None)

    assert store.completed
    assert all(not node.claims for node in store.plan.nodes)
