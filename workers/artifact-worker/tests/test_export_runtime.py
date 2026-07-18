from app.export_runtime import export_artifact_if_required
from app.models import ArtifactSectionDraft
from tests.test_runner import _build_media_task_input


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
    assert trace["file_name"] == "Course-Notes.pdf"
    assert trace["download_path"].endswith("/Course-Notes.pdf")


def test_non_pdf_skill_should_not_invoke_binary_export() -> None:
    task_input = _build_media_task_input("course_notes")

    trace = export_artifact_if_required(
        task_input=task_input,
        title="Course Notes",
        sections=[],
    )

    assert trace["status"] == "NOT_REQUIRED"
    assert trace["format"] == "MARKDOWN"
