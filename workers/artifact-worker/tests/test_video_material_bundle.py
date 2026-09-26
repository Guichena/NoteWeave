from __future__ import annotations

from copy import deepcopy
import hashlib
from io import BytesIO

import pytest
from PIL import Image
from pydantic import ValidationError

from app.video_material_bundle import VideoMaterialBundleV1


def _bundle() -> dict[str, object]:
    return {
        "bundle_id": "bundle-demo-1",
        "bundle_version": 1,
        "workspace_id": "workspace-demo-1",
        "bvid": "BV1234567890",
        "part": 2,
        "duration_ms": 10_000,
        "input_digest": "a" * 64,
        "subtitle_source": "MANUAL",
        "transcript_original": "cache yizhi",
        "transcript_corrected": "cache 一致性",
        "transcript_segments": [{
            "segment_id": "segment-1", "part": 2, "start_ms": 1000,
            "end_ms": 4000, "original_text": "cache yizhi",
            "corrected_text": "cache 一致性",
        }],
        "files": [{
            "file_id": "frame-file-1", "role": "VIDEO_FRAME",
            "media_type": "image/png", "size_bytes": 120,
            "checksum_sha256": "b" * 64,
        }],
        "frames": [{
            "frame_id": "frame-1", "part": 2, "at_ms": 2500,
            "file_id": "frame-file-1", "checksum_sha256": "b" * 64,
        }],
        "knowledge_nodes": [{
            "node_id": "node-1", "title": "Cache consistency",
            "start_ms": 1000, "end_ms": 4000,
            "transcript_segment_ids": ["segment-1"], "frame_ids": ["frame-1"],
        }],
        "coverage_gaps": [],
    }


def test_bundle_keeps_original_corrected_and_evidence_digest() -> None:
    first = VideoMaterialBundleV1.model_validate(_bundle())
    replay = VideoMaterialBundleV1.model_validate(deepcopy(_bundle()))

    assert first.transcript_segments[0].original_text != first.transcript_segments[0].corrected_text
    assert first.content_digest() == replay.content_digest()
    assert len(first.content_digest()) == 64


@pytest.mark.parametrize("change,expected", [
    (lambda value: value["frames"][0].update(part=1), "another part"),
    (lambda value: value["frames"][0].update(at_ms=11_000), "time range"),
    (lambda value: value["frames"][0].update(checksum_sha256="c" * 64), "file"),
    (lambda value: value["knowledge_nodes"][0].update(frame_ids=["unknown"]), "frame reference"),
    (lambda value: value["knowledge_nodes"][0].update(start_ms=5000, end_ms=6000), "transcript reference"),
])
def test_bundle_rejects_cross_part_time_and_broken_references(change, expected: str) -> None:
    value = _bundle()
    change(value)

    with pytest.raises(ValidationError, match=expected):
        VideoMaterialBundleV1.model_validate(value)


def test_no_subtitle_and_no_frame_are_explicit_gaps() -> None:
    value = _bundle()
    value.update(
        subtitle_source="NONE", transcript_original="", transcript_corrected="",
        transcript_segments=[], frames=[], files=[],
        knowledge_nodes=[{
            "node_id": "node-1", "title": "Unavailable material",
            "start_ms": 0, "end_ms": 1000,
            "missing": ["NO_SUBTITLE", "NO_FRAMES"],
        }],
        coverage_gaps=["NO_SUBTITLE", "NO_FRAMES"],
    )

    assert VideoMaterialBundleV1.model_validate(value).subtitle_source == "NONE"
    value["coverage_gaps"] = ["NO_SUBTITLE"]
    with pytest.raises(ValidationError, match="missing frames"):
        VideoMaterialBundleV1.model_validate(value)


def test_bundle_rejects_unrecognized_fields_and_conflicting_coverage() -> None:
    value = _bundle()
    value["untrusted_file_path"] = "C:/outside/frame.png"
    with pytest.raises(ValidationError, match="Extra inputs are not permitted"):
        VideoMaterialBundleV1.model_validate(value)
    del value["untrusted_file_path"]
    value["coverage_gaps"] = ["NO_FRAMES"]
    with pytest.raises(ValidationError, match="contradicts captured frames"):
        VideoMaterialBundleV1.model_validate(value)


@pytest.mark.parametrize("change,expected", [
    (lambda value: value.update(knowledge_nodes=[]), "every video frame"),
    (lambda value: value["knowledge_nodes"].append({
        "node_id": "node-2", "title": "cache CONSISTENCY", "start_ms": 1000,
        "end_ms": 4000, "frame_ids": ["frame-1"]}), "titles must be unique"),
    (lambda value: value["knowledge_nodes"].append({
        "node_id": "node-2", "title": "Another node", "start_ms": 1000,
        "end_ms": 4000, "frame_ids": ["frame-1"]}), "multiple knowledge nodes"),
])
def test_bundle_requires_one_unambiguous_knowledge_node_per_frame(change, expected) -> None:
    value = _bundle()
    change(value)
    with pytest.raises(ValidationError, match=expected):
        VideoMaterialBundleV1.model_validate(value)


def test_bundle_checks_frame_bytes_and_image_format_through_file_id() -> None:
    output = BytesIO()
    Image.new("RGB", (2, 2), "red").save(output, format="PNG")
    content = output.getvalue()
    value = _bundle()
    value["files"][0].update(size_bytes=len(content), checksum_sha256=hashlib.sha256(content).hexdigest())
    value["frames"][0]["checksum_sha256"] = value["files"][0]["checksum_sha256"]
    bundle = VideoMaterialBundleV1.model_validate(value)

    bundle.verify_file_bytes(lambda file_id: content)
    with pytest.raises(ValueError, match="bytes do not match"):
        bundle.verify_file_bytes(lambda file_id: b"wrong")
    value["files"][0]["media_type"] = "image/jpeg"
    with pytest.raises(ValueError, match="not decodable"):
        VideoMaterialBundleV1.model_validate(value).verify_file_bytes(lambda file_id: content)
