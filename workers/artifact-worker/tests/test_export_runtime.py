from app.export_runtime import export_artifact_if_required
from app.video_material_bundle import VideoMaterialBundleV1, frozen_video_input_digest
from app.models import ArtifactSectionDraft
from tests.test_runner import _build_media_task_input
from pathlib import Path
from io import BytesIO
import hashlib
import pytest
from PIL import Image


def test_bilibili_pdf_skill_should_surface_compiled_export(monkeypatch) -> None:
    task_input = _build_media_task_input("course_notes")
    task_input.input_payload.skill_key = "bilibili_course_note_pdf"
    task_input.input_payload.inputs = {
        "url": "https://www.bilibili.com/video/BV1NoteWeaveDemo"
    }

    monkeypatch.setattr(
        "app.export_runtime._call_custom_mcp_tool",
        lambda server, operation: {
            "pdf_status": "COMPILED",
            "pdf_path": "runtime/exports/course-notes.pdf",
            "tex_path": "runtime/exports/course-notes.tex",
            "execution_mode": "controlled_template_render",
            "notes": ["compiled in test"],
        },
    )

    trace = export_artifact_if_required(
        task_input=task_input,
        title="Course Notes",
        sections=[ArtifactSectionDraft(heading="Overview", body="Grounded content")],
    )

    assert trace["status"] == "COMPILED"
    assert trace["file_name"] == "course-notes.pdf"
    assert trace["download_path"].endswith("/course-notes.pdf")


def test_non_pdf_skill_should_not_invoke_binary_export() -> None:
    task_input = _build_media_task_input("course_notes")

    trace = export_artifact_if_required(
        task_input=task_input,
        title="Course Notes",
        sections=[],
    )

    assert trace["status"] == "NOT_REQUIRED"
    assert trace["format"] == "MARKDOWN"


def test_pdf_bundle_branch_passes_only_verified_section_frames(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setenv("NOTEWEAVE_MCP_SANDBOX_ROOT", str(tmp_path))
    task_input = _build_media_task_input("course_notes")
    task_input.input_payload.skill_key = "bilibili_course_note_pdf"
    task_input.workspace_id = "ws-1"
    task_input.input_snapshot_id = "snapshot-1"
    task_input.input_payload.inputs = {
        "url": "https://www.bilibili.com/video/BV1234567890?p=2"
    }
    output = BytesIO()
    Image.new("RGB", (2, 2), "red").save(output, format="PNG")
    frame_path = tmp_path / "inputs" / "frame1.png"
    frame_path.parent.mkdir()
    frame_path.write_bytes(output.getvalue())
    checksum = hashlib.sha256(output.getvalue()).hexdigest()
    bundle = VideoMaterialBundleV1.model_validate({
        "bundle_id": "bundle-1", "bundle_version": 1, "workspace_id": "ws-1",
        "bvid": "BV1234567890", "part": 2, "duration_ms": 5000,
        "input_digest": frozen_video_input_digest(
            task_input.input_snapshot_id, task_input.input_payload.inputs),
        "subtitle_source": "NONE", "coverage_gaps": ["NO_SUBTITLE"],
        "files": [{"file_id": "frame1", "role": "VIDEO_FRAME", "media_type": "image/png",
                   "size_bytes": len(output.getvalue()), "checksum_sha256": checksum}],
        "frames": [{"frame_id": "f1", "part": 2, "at_ms": 1000,
                    "file_id": "frame1", "checksum_sha256": checksum}],
        "knowledge_nodes": [{"node_id": "n1", "title": "Overview",
                             "start_ms": 0, "end_ms": 2000,
                             "frame_ids": ["f1"]}],
    })
    observed = {}

    def fake_renderer(server, operation):
        observed.update(operation["tool_arguments"])
        return {"pdf_status": "COMPILED", "pdf_path": str(tmp_path / "course.pdf"),
                "tex_path": str(tmp_path / "course.tex")}

    monkeypatch.setattr("app.export_runtime._call_custom_mcp_tool", fake_renderer)
    trace = export_artifact_if_required(
        task_input=task_input, title="Course Notes",
        sections=[ArtifactSectionDraft(heading="Overview", body="Grounded content")],
        video_material=bundle, frame_files={"frame1": frame_path},
    )
    assert observed["video_part"] == 2
    assert observed["sections"][0]["image_refs"][0]["checksum_sha256"] == checksum
    assert trace["frame_file_ids"] == ["frame1"]

    bad = bundle.model_copy(update={"part": 1})
    with pytest.raises(ValueError, match="frozen Run input"):
        export_artifact_if_required(
            task_input=task_input, title="Course Notes",
            sections=[ArtifactSectionDraft(heading="Overview", body="Grounded content")],
            video_material=bad, frame_files={"frame1": frame_path},
        )
