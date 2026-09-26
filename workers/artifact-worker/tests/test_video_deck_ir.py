from __future__ import annotations

import hashlib
from io import BytesIO

import pytest
from PIL import Image

from app.video_deck_ir import VideoDeckIRV1, build_video_deck_ir
from app.video_deck_render import render_original_video_deck, verify_original_video_deck
from app.video_knowledge_plan import VideoKnowledgePlanV1, build_local_evidence_plan
from app.video_material_bundle import VideoMaterialBundleV1


def _frozen_source(frame_bytes: bytes | None = None) -> tuple[VideoMaterialBundleV1, VideoKnowledgePlanV1]:
    image_digest = hashlib.sha256(frame_bytes).hexdigest() if frame_bytes else "b" * 64
    bundle = VideoMaterialBundleV1.model_validate({
        "bundle_id": "deck-bundle", "bundle_version": 1,
        "workspace_id": "workspace-1", "bvid": "BV1234567890", "part": 2,
        "duration_ms": 10_000, "input_digest": "a" * 64,
        "subtitle_source": "MANUAL", "transcript_original": "cache consistency",
        "transcript_corrected": "cache consistency",
        "transcript_segments": [{"segment_id": "s1", "part": 2, "start_ms": 1000,
                                 "end_ms": 4000, "original_text": "cache consistency",
                                 "corrected_text": "cache consistency"}],
        "files": [{"file_id": "file-1", "role": "VIDEO_FRAME", "media_type": "image/png",
                   "size_bytes": len(frame_bytes) if frame_bytes else 100,
                   "checksum_sha256": image_digest}],
        "frames": [{"frame_id": "f1", "part": 2, "at_ms": 2500,
                    "file_id": "file-1", "checksum_sha256": image_digest}],
        "knowledge_nodes": [{"node_id": "n1", "title": "Frame evidence",
                             "start_ms": 1000, "end_ms": 4000,
                             "transcript_segment_ids": ["s1"], "frame_ids": ["f1"]}],
        "coverage_gaps": [],
    })
    plan = VideoKnowledgePlanV1.model_validate({
        "bundle_content_digest": bundle.content_digest(), "bvid": bundle.bvid,
        "part": bundle.part, "duration_ms": bundle.duration_ms,
        "nodes": [
            {"node_id": "root", "kind": "TOPIC", "title": "Cache", "start_ms": 0,
             "end_ms": 10_000},
            {"node_id": "concept", "parent_id": "root", "kind": "CONCEPT",
             "title": "Consistency", "start_ms": 1000, "end_ms": 4000,
             "transcript_segment_ids": ["s1"], "frame_ids": ["f1"],
             "terms": ["cache"],
             "claims": [{"text": "cache consistency", "status": "EXTRACTED",
                         "evidence_refs": ["segment:s1"]}]},
        ],
    })
    return bundle, plan


def test_slide_order_and_picture_are_bound_to_frozen_evidence() -> None:
    bundle, plan = _frozen_source()
    deck = build_video_deck_ir(bundle, plan)
    assert deck.slides[0].sequence_no == 1
    assert deck.slides[0].frame_id == "f1"
    assert deck.slides[0].file_id == "file-1"
    assert deck.slides[0].image_checksum_sha256 == "b" * 64
    assert deck.slides[0].claims == ["cache consistency"]
    assert deck.slides[0].evidence_refs == ["segment:s1"]
    deck.verify_against(bundle, plan)
    assert deck.content_digest == build_video_deck_ir(bundle, plan).content_digest


def test_forged_picture_reference_is_rejected_even_with_new_digest() -> None:
    bundle, plan = _frozen_source()
    deck = build_video_deck_ir(bundle, plan)
    forged = deck.model_copy(deep=True)
    forged.slides[0].file_id = "other-file"
    forged.content_digest = forged.digest()
    forged = VideoDeckIRV1.model_validate(forged.model_dump(mode="json"))
    with pytest.raises(ValueError, match="does not match frozen"):
        forged.verify_against(bundle, plan)


def test_evidence_window_only_plan_cannot_publish_learning_deck() -> None:
    bundle, _ = _frozen_source()
    with pytest.raises(ValueError, match="verified semantic claims"):
        build_video_deck_ir(bundle, build_local_evidence_plan(bundle))


def test_original_image_deck_opens_and_preserves_complete_frame(
        tmp_path, monkeypatch) -> None:
    output = BytesIO()
    Image.new("RGB", (640, 360), "navy").save(output, format="PNG")
    frame_bytes = output.getvalue()
    bundle, plan = _frozen_source(frame_bytes)
    ir = build_video_deck_ir(bundle, plan)
    monkeypatch.setenv("NOTEWEAVE_MCP_SANDBOX_ROOT", str(tmp_path))

    path = render_original_video_deck(
        "task-deck-1", ir, bundle, plan, lambda _: frame_bytes)
    assert path.is_file()
    assert path.read_bytes().startswith(b"PK")
    verify_original_video_deck(path, ir)
    with pytest.raises(ValueError, match="manifest"):
        render_original_video_deck("task-deck-2", ir, bundle, plan, lambda _: b"wrong")
