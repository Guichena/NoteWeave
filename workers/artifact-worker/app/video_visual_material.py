from __future__ import annotations

import re
from pathlib import Path

from app.config import resolve_mcp_sandbox_root
from app.models import ArtifactTaskInput
from app.video_material_bundle import VideoMaterialBundleV1


SAFE_ID = re.compile(r"[A-Za-z0-9_-]{1,100}")


def merge_captured_video_frames(
    task_input: ArtifactTaskInput,
    subtitle_bundle: VideoMaterialBundleV1,
    capture: dict[str, object],
) -> VideoMaterialBundleV1:
    """Turn one verified capture receipt into a staged, immutable Bundle candidate.

    The time buckets are evidence containers, not semantic knowledge claims.
    No arbitrary MCP path is persisted in the Bundle or sent to the Host.
    """
    if capture.get("normalized_video_id") != subtitle_bundle.bvid \
            or capture.get("part") != subtitle_bundle.part \
            or capture.get("duration_ms") != subtitle_bundle.duration_ms:
        raise ValueError("captured frames do not match the frozen subtitle video and part")
    if task_input.workspace_id != subtitle_bundle.workspace_id \
            or not SAFE_ID.fullmatch(task_input.task_id):
        raise ValueError("captured frames do not match the Worker task scope")
    raw_frames = capture.get("frames")
    raw_files = capture.get("files")
    raw_gaps = capture.get("coverage_gaps")
    raw_missing = capture.get("missing_requested_ms")
    if not isinstance(raw_frames, list) or not isinstance(raw_files, list) \
            or not isinstance(raw_gaps, list) or not isinstance(raw_missing, list) \
            or len(raw_frames) > 32 or len(raw_files) > 32 \
            or len(raw_missing) > 32:
        raise ValueError("captured frame collections exceed the Bundle contract")
    if any(isinstance(at_ms, bool) or not isinstance(at_ms, int)
           or at_ms < 0 or at_ms >= subtitle_bundle.duration_ms
           for at_ms in raw_missing):
        raise ValueError("missing frame timestamps are outside the video")
    if (not raw_frames) != ("NO_FRAMES" in raw_gaps) \
            or bool(raw_missing) != ("FRAME_CAPTURE_PARTIAL" in raw_gaps):
        raise ValueError("captured frame coverage gaps contradict the receipt")

    sandbox = resolve_mcp_sandbox_root().resolve()
    export_root = sandbox / "bilibili-render-pdf" / "exports" / task_input.task_id
    if not export_root.resolve().is_relative_to(sandbox):
        raise ValueError("captured frame export is outside the Worker sandbox")
    export_root.mkdir(parents=True, exist_ok=True)
    file_bytes: dict[str, bytes] = {}
    files: list[dict[str, object]] = []
    total_bytes = 0
    for raw in raw_files:
        if not isinstance(raw, dict) or set(raw) != {
            "file_id", "role", "media_type", "size_bytes", "checksum_sha256", "path"
        }:
            raise ValueError("captured frame file manifest has unknown fields")
        file_id = str(raw["file_id"])
        if not SAFE_ID.fullmatch(file_id) or file_id in file_bytes:
            raise ValueError("captured frame file ID is invalid or duplicated")
        path = Path(str(raw["path"]))
        resolved = path.resolve()
        if path.is_symlink() or not resolved.is_file() \
                or not resolved.is_relative_to(sandbox):
            raise ValueError("captured frame file is outside the Worker sandbox")
        content = resolved.read_bytes()
        if len(content) > 16_000_000:
            raise ValueError("captured frame file exceeds the Host limit")
        total_bytes += len(content)
        if total_bytes > 100_000_000:
            raise ValueError("captured frame files exceed the Bundle limit")
        file_bytes[file_id] = content
        files.append({key: value for key, value in raw.items() if key != "path"})

    frames = [dict(raw) if isinstance(raw, dict) else raw for raw in raw_frames]
    for frame in frames:
        if not isinstance(frame, dict) or set(frame) != {
            "frame_id", "part", "at_ms", "file_id", "checksum_sha256", "dedupe_of"
        }:
            raise ValueError("captured frame entry has unknown fields")
    nodes = []
    for index, frame in enumerate(frames, start=1):
        at_ms = frame["at_ms"]
        if isinstance(at_ms, bool) or not isinstance(at_ms, int):
            raise ValueError("captured frame time is invalid")
        start_ms = max(0, at_ms - 500)
        end_ms = min(subtitle_bundle.duration_ms, max(start_ms + 1, at_ms + 501))
        evidence = [segment.segment_id for segment in subtitle_bundle.transcript_segments
                    if segment.start_ms <= end_ms and segment.end_ms >= start_ms]
        nodes.append({
            "node_id": f"frame-node-{index:03d}",
            "title": f"画面 {index:02d} · {at_ms // 60000:02d}:{at_ms // 1000 % 60:02d}",
            "start_ms": start_ms, "end_ms": end_ms,
            "transcript_segment_ids": evidence,
            "frame_ids": [frame["frame_id"]],
            "missing": [] if evidence else ["NO_TIMED_SUBTITLE_AT_FRAME"],
        })
    gaps = [gap for gap in subtitle_bundle.coverage_gaps if gap != "NO_FRAMES"]
    gaps.extend(str(gap) for gap in raw_gaps if str(gap) not in gaps)
    merged = VideoMaterialBundleV1.model_validate({
        **subtitle_bundle.model_dump(mode="json"),
        "frames": frames, "files": files, "knowledge_nodes": nodes,
        "coverage_gaps": gaps,
    })
    merged.verify_file_bytes(file_bytes.__getitem__)
    for file in merged.files:
        suffix = ".png" if file.media_type == "image/png" else ".jpg"
        destination = export_root / f"{file.file_id}{suffix}"
        content = file_bytes[file.file_id]
        if destination.is_symlink() or (destination.exists()
                and destination.read_bytes() != content):
            raise ValueError("captured frame export conflicts with existing bytes")
        if not destination.exists():
            destination.write_bytes(content)
    return merged
