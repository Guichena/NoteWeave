from __future__ import annotations

from copy import deepcopy
import hashlib
from io import BytesIO

import pytest
from PIL import Image

from app.models import ArtifactTaskInput
from app.custom_mcp_executor import _build_tool_arguments
from app.system_mcp_registry import SYSTEM_BILIBILI_SERVER_ID, resolve_system_mcp_server
from app.video_material_bundle import frozen_video_input_digest
from app.video_subtitle_material import subtitle_only_bundle
from app.video_visual_material import merge_captured_video_frames


def _fixture(tmp_path, monkeypatch):
    monkeypatch.setenv("NOTEWEAVE_MCP_SANDBOX_ROOT", str(tmp_path))
    task = ArtifactTaskInput.model_validate({
        "task_id": "video-task-1", "workspace_id": "ws-1", "target_id": "artifact-1",
        "input_snapshot_id": "snapshot-1",
        "control_pack": {"pack_type": "artifact", "target_key": "bilibili_course_note_pdf",
                         "task_neighborhood": "ARTIFACT_SKILL_BILIBILI_COURSE_NOTE_PDF"},
        "input_payload": {"skill_key": "bilibili_course_note_pdf", "inputs": {
            "url": "https://www.bilibili.com/video/BV1234567890?p=2"}},
    })
    bundle = subtitle_only_bundle(
        bundle_id="video-material-video-task-1", bundle_version=1,
        workspace_id="ws-1", bvid="BV1234567890", part=2,
        duration_ms=5000, input_digest=frozen_video_input_digest(
            task.input_snapshot_id, task.input_payload.inputs),
        subtitle_source="MANUAL",
        srt_text="1\n00:00:00,500 --> 00:00:02,500\nVerified spoken concept.\n",
    )
    output = BytesIO()
    Image.new("RGB", (2, 2), "green").save(output, format="PNG")
    content = output.getvalue()
    path = tmp_path / "bilibili-render-pdf" / "frames" / "frame-1.png"
    path.parent.mkdir(parents=True)
    path.write_bytes(content)
    digest = hashlib.sha256(content).hexdigest()
    capture = {
        "normalized_video_id": "BV1234567890", "part": 2,
        "duration_ms": 5000, "coverage_gaps": [], "missing_requested_ms": [],
        "frames": [{"frame_id": "f-1", "part": 2, "at_ms": 1000,
                    "file_id": "frame-1", "checksum_sha256": digest, "dedupe_of": ""}],
        "files": [{"file_id": "frame-1", "role": "VIDEO_FRAME",
                   "media_type": "image/png", "size_bytes": len(content),
                   "checksum_sha256": digest, "path": str(path)}],
    }
    return task, bundle, capture, content


def test_capture_receipt_merges_timed_subtitle_and_stages_host_readable_frame(tmp_path, monkeypatch):
    task, subtitle, capture, content = _fixture(tmp_path, monkeypatch)
    merged = merge_captured_video_frames(task, subtitle, capture)
    assert merged.transcript_corrected == subtitle.transcript_corrected
    assert merged.frames[0].at_ms == 1000
    assert merged.knowledge_nodes[0].transcript_segment_ids == [
        subtitle.transcript_segments[0].segment_id]
    assert merged.knowledge_nodes[0].frame_ids == ["f-1"]
    assert merged.coverage_gaps == []
    assert (tmp_path / "bilibili-render-pdf" / "exports" / task.task_id
            / "frame-1.png").read_bytes() == content
    assert merge_captured_video_frames(task, subtitle, capture).content_digest() == merged.content_digest()
    assert "path" not in merged.model_dump_json()


def test_system_provider_maps_frame_operation_to_bounded_mcp_call():
    server = resolve_system_mcp_server(SYSTEM_BILIBILI_SERVER_ID)
    assert any(tool.capability_name == "CAPTURE_VIDEO_FRAMES"
               and tool.tool_name == "capture_bilibili_frames" for tool in server.tools)
    assert _build_tool_arguments({
        "tool_name": "capture_bilibili_frames",
        "input_locator": "https://www.bilibili.com/video/BV1234567890?p=2",
    }) == {
        "video_url": "https://www.bilibili.com/video/BV1234567890?p=2",
        "max_frames": 16, "interval_ms": 30_000,
    }


@pytest.mark.parametrize("change,expected", [
    (lambda receipt: receipt.update(part=1), "frozen subtitle video"),
    (lambda receipt: receipt["frames"][0].update(part=1), "another part"),
    (lambda receipt: receipt["files"][0].update(checksum_sha256="0" * 64), "frame"),
    (lambda receipt: receipt["files"][0].update(path="C:/outside.png"), "outside"),
    (lambda receipt: receipt.update(coverage_gaps=["NO_FRAMES"]), "coverage gaps"),
])
def test_capture_receipt_rejects_mismatched_identity_bytes_and_gap(
    tmp_path, monkeypatch, change, expected,
):
    task, subtitle, capture, _ = _fixture(tmp_path, monkeypatch)
    bad = deepcopy(capture)
    change(bad)
    with pytest.raises(ValueError, match=expected):
        merge_captured_video_frames(task, subtitle, bad)
    assert not (tmp_path / "bilibili-render-pdf" / "exports" / task.task_id
                / "frame-1.png").exists()
