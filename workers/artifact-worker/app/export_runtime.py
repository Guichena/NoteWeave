from __future__ import annotations

from pathlib import Path

from app.config import resolve_mcp_sandbox_root
from app.custom_mcp_executor import _call_custom_mcp_tool
from app.models import ArtifactTaskInput
from app.system_mcp_registry import SYSTEM_BILIBILI_SERVER_ID, resolve_system_mcp_server


def export_artifact_if_required(
    *,
    task_input: ArtifactTaskInput,
    title: str,
    sections: list[object],
) -> dict[str, object]:
    skill_key = task_input.input_payload.skill_key.strip().lower()
    if skill_key != "bilibili_course_note_pdf":
        return {
            "status": "NOT_REQUIRED",
            "format": "MARKDOWN",
            "file_name": "",
            "download_path": "",
            "notes": ["The selected skill does not require a binary PDF export."],
        }

    sandbox_root = resolve_mcp_sandbox_root()
    output_dir = sandbox_root / "bilibili-render-pdf" / "exports" / task_input.task_id
    output_stem = _safe_file_stem(title or task_input.target_id)
    server = resolve_system_mcp_server(SYSTEM_BILIBILI_SERVER_ID)
    result = _call_custom_mcp_tool(
        server,
        {
            "tool_name": "render_latex_pdf",
            "tool_arguments": {
                "title": title,
                "video_url": _resolve_video_url(task_input),
                "sections": [
                    {
                        "heading": str(getattr(section, "heading", "") or "Untitled Section"),
                        "body": str(getattr(section, "body", "") or ""),
                    }
                    for section in sections
                ],
                "output_dir": str(output_dir),
                "output_stem": output_stem,
            },
        },
    )
    pdf_status = str(result.get("pdf_status", "UNKNOWN"))
    # The controlled renderer may normalize the requested stem (including CJK
    # titles). Use the name it actually wrote, so the Host fetches those bytes.
    file_name = Path(str(result.get("pdf_path") or "")).name if pdf_status == "COMPILED" else ""
    return {
        "status": pdf_status,
        "format": "PDF",
        "file_name": file_name,
        "download_path": f"/tasks/{task_input.task_id}/exports/{file_name}" if file_name else "",
        "tex_path": str(result.get("tex_path", "")),
        "pdf_path": str(result.get("pdf_path", "")),
        "execution_mode": str(result.get("execution_mode", "SYSTEM_MCP_LATEX_EXPORT")),
        "notes": list(result.get("notes", [])),
    }


def _resolve_video_url(task_input: ArtifactTaskInput) -> str:
    for key in ("url", "video_url", "bilibili_url"):
        value = str(task_input.input_payload.inputs.get(key) or "").strip()
        if value:
            return value
    for source in task_input.source_scope:
        if "bilibili.com" in source.source_uri or "b23.tv" in source.source_uri:
            return source.source_uri
    return ""


def _safe_file_stem(value: str) -> str:
    normalized = "".join(
        character if character.isalnum() or character in {"-", "_"} else "-"
        for character in value.strip()
    ).strip("-_")
    return normalized[:80] or "bilibili-course-notes"
