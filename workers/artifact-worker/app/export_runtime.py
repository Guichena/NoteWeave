from __future__ import annotations

from pathlib import Path
from urllib.parse import parse_qs, urlparse

from app.config import resolve_mcp_sandbox_root
from app.custom_mcp_executor import _call_custom_mcp_tool
from app.models import ArtifactTaskInput
from app.system_mcp_registry import SYSTEM_BILIBILI_SERVER_ID, resolve_system_mcp_server
from app.video_material_bundle import VideoMaterialBundleV1, frozen_video_input_digest


def export_artifact_if_required(
    *,
    task_input: ArtifactTaskInput,
    title: str,
    sections: list[object],
    video_material: VideoMaterialBundleV1 | None = None,
    frame_files: dict[str, Path] | None = None,
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
    image_refs: list[list[dict[str, object]]] = [[] for _ in sections]
    if video_material is not None:
        image_refs = _resolve_section_frames(
            task_input, sections, video_material, frame_files or {}, sandbox_root)
    server = resolve_system_mcp_server(SYSTEM_BILIBILI_SERVER_ID)
    result = _call_custom_mcp_tool(
        server,
        {
            "tool_name": "render_latex_pdf",
            "tool_arguments": {
                "title": title,
                "video_url": _resolve_video_url(task_input),
                **({"video_part": video_material.part,
                    "video_duration_ms": video_material.duration_ms} if video_material else {}),
                "sections": [
                    {
                        "heading": str(getattr(section, "heading", "") or "Untitled Section"),
                        "body": str(getattr(section, "body", "") or ""),
                        "image_refs": image_refs[index],
                    }
                    for index, section in enumerate(sections)
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
        "frame_file_ids": [ref["file_id"] for group in image_refs for ref in group],
    }


def _resolve_section_frames(
    task_input: ArtifactTaskInput, sections: list[object],
    bundle: VideoMaterialBundleV1, frame_files: dict[str, Path], sandbox_root: Path,
) -> list[list[dict[str, object]]]:
    validate_frozen_video_scope(task_input, bundle)
    if set(frame_files) != {file.file_id for file in bundle.files}:
        raise ValueError("PDF material frame file set differs from frozen Bundle")
    paths: dict[str, Path] = {}
    for file_id, path in frame_files.items():
        resolved = Path(path).resolve()
        if not resolved.is_file() or not resolved.is_relative_to(sandbox_root.resolve()):
            raise ValueError("PDF material frame path is outside the Worker sandbox")
        paths[file_id] = resolved
    bundle.verify_file_bytes(lambda file_id: paths[file_id].read_bytes())
    by_frame = {frame.frame_id: frame for frame in bundle.frames}
    assigned: set[str] = set()
    references: list[list[dict[str, object]]] = []
    for section in sections:
        heading = str(getattr(section, "heading", "")).strip().casefold()
        refs = []
        for node in bundle.knowledge_nodes:
            if node.title.strip().casefold() != heading:
                continue
            for frame_id in node.frame_ids:
                frame = by_frame[frame_id]
                refs.append({
                    "file_id": frame.file_id, "path": str(paths[frame.file_id]),
                    "checksum_sha256": frame.checksum_sha256,
                    "part": frame.part, "at_ms": frame.at_ms,
                })
                assigned.add(frame_id)
        references.append(refs)
    if assigned != set(by_frame):
        raise ValueError("PDF knowledge nodes do not assign every frozen frame to a section")
    return references


def validate_frozen_video_scope(task_input: ArtifactTaskInput, bundle: VideoMaterialBundleV1) -> None:
    url = urlparse(_resolve_video_url(task_input))
    parts = parse_qs(url.query).get("p", ["1"])
    referenced_id = str(task_input.input_payload.inputs.get("video_material_bundle_id") or "").strip()
    if url.scheme not in {"http", "https"} or url.hostname not in {
            "bilibili.com", "www.bilibili.com"} \
            or len(parts) != 1 or not parts[0].isdigit() or not url.path.rstrip("/").endswith(
            "/" + bundle.bvid) or int(parts[0]) != bundle.part \
            or bundle.workspace_id != task_input.workspace_id \
            or (not referenced_id and bundle.input_digest != frozen_video_input_digest(
                task_input.input_snapshot_id, task_input.input_payload.inputs)):
        raise ValueError("PDF video material does not match its frozen Run input")


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
