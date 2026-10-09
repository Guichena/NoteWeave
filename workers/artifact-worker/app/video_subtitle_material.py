from __future__ import annotations

import hashlib
import math
import re
from urllib.parse import parse_qs, urlparse

from app.io_limits import ContentSizeLimitError, read_provider_text_in_sandbox
from app.models import ArtifactTaskInput
from app.video_material_bundle import (
    TranscriptSegment, VideoMaterialBundleV1, frozen_video_input_digest,
)


TIMECODE = re.compile(
    r"^(\d{2}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*"
    r"(\d{2}):(\d{2}):(\d{2})[,.](\d{3})(?:\s+.*)?$"
)
VIDEO_PATH = re.compile(r"^/video/(BV[0-9A-Za-z]{10})(?:/)?$")


def subtitle_bundle_from_provider(
    task_input: ArtifactTaskInput, provider_payload: dict[str, object],
) -> VideoMaterialBundleV1 | None:
    """Freeze only a complete sandbox subtitle with verified video metadata."""
    if task_input.input_payload.skill_key != "bilibili_course_note_pdf":
        return None
    url = str(task_input.input_payload.inputs.get("url", ""))
    parsed = urlparse(url)
    video = VIDEO_PATH.fullmatch(parsed.path)
    if parsed.scheme not in {"http", "https"} or parsed.hostname not in {
            "bilibili.com", "www.bilibili.com"} or video is None:
        return None
    if provider_payload.get("normalized_video_id") != video.group(1):
        return None
    parts = parse_qs(parsed.query).get("p", ["1"])
    if len(parts) != 1 or not parts[0].isdigit():
        return None
    part = int(parts[0])
    if part < 1 or part > 1000:
        return None
    metadata = provider_payload.get("metadata")
    if not isinstance(metadata, dict):
        return None
    if part > 1:
        observed_url = urlparse(str(metadata.get("webpage_url", "")))
        observed_video = VIDEO_PATH.fullmatch(observed_url.path)
        observed_parts = parse_qs(observed_url.query).get("p", [])
        if (observed_url.hostname not in {"bilibili.com", "www.bilibili.com"}
                or observed_video is None or observed_video.group(1) != video.group(1)
                or observed_parts != [str(part)]):
            return None
    duration = metadata.get("duration")
    if isinstance(duration, bool) or not isinstance(duration, (int, float)) \
            or not math.isfinite(duration) or duration <= 0:
        return None
    duration_ms = round(duration * 1000)
    if duration_ms < 1 or duration_ms > 86_400_000 or not task_input.input_snapshot_id:
        return None
    mode = str(provider_payload.get("acquisition_mode", ""))
    source = {
        "remote_cc_subtitle_fetch": "MANUAL",
        "remote_auto_subtitle_fetch": "AI_CAPTION",
        "remote_audio_transcription_fallback": "ASR",
        "no_subtitle_available": "NONE",
    }.get(mode)
    if source is None:
        return None
    if source == "NONE":
        srt = ""
    else:
        path = str(provider_payload.get("selected_subtitle_path", ""))
        if not path:
            return None
        try:
            srt = read_provider_text_in_sandbox(path)
        except (OSError, ContentSizeLimitError, ValueError):
            return None
    return subtitle_only_bundle(
        bundle_id=f"video-material-{task_input.task_id}", bundle_version=1,
        workspace_id=task_input.workspace_id, bvid=video.group(1), part=part,
        duration_ms=duration_ms,
        input_digest=frozen_video_input_digest(
            task_input.input_snapshot_id, task_input.input_payload.inputs),
        subtitle_source=source, srt_text=srt,
        corrections=machine_subtitle_corrections(srt, part=part, duration_ms=duration_ms,
                                                 subtitle_source=source),
    )


_CLOCK_PREFIX = re.compile(r"^\[(?:\d{2}:)?\d{2}:\d{2}\] ")


def machine_subtitle_corrections(
    srt_text: str, *, part: int, duration_ms: int, subtitle_source: str, client: object | None = None,
) -> dict[str, str]:
    """LLM-proofread ASR / auto-caption cues; manual subtitles are left exactly as published.

    original_text keeps the recognizer output and only corrected_text changes, so citations can
    still be traced back. Any failure keeps the original cue (same guard as source transcription).
    """
    if subtitle_source not in {"ASR", "AI_CAPTION"} or not srt_text.strip():
        return {}
    from app.llm_client import build_default_llm_client
    from app.source_transcription import correct_transcript
    corrector = client if client is not None else build_default_llm_client()
    if corrector is None:
        return {}
    try:
        segments = parse_srt_segments(srt_text, part=part, duration_ms=duration_ms)
        lines = []
        for segment in segments:
            seconds = segment.start_ms // 1000
            lines.append(f"[{seconds // 60:02d}:{seconds % 60:02d}] "
                         + segment.original_text.replace("\n", " "))
        corrected, _ = correct_transcript(lines, corrector)
    except Exception:  # 校对是锦上添花，任何异常都退回识别原文
        return {}
    result: dict[str, str] = {}
    for segment, line, fixed in zip(segments, lines, corrected):
        text = _CLOCK_PREFIX.sub("", fixed, count=1).strip()
        if fixed != line and text:
            result[segment.segment_id] = text
    return result


def parse_srt_segments(srt_text: str, *, part: int, duration_ms: int) -> list[TranscriptSegment]:
    """Parse a complete timed subtitle file; never use a short preview as source material."""
    if part < 1 or duration_ms <= 0:
        raise ValueError("video part and duration are required")
    blocks = re.split(r"\n\s*\n", srt_text.replace("\r\n", "\n").strip())
    segments: list[TranscriptSegment] = []
    for block in blocks:
        lines = [line.strip() for line in block.splitlines() if line.strip()]
        if not lines:
            continue
        if lines[0].upper() == "WEBVTT":
            continue
        if lines[0].isdigit():
            lines = lines[1:]
        if len(lines) < 2:
            raise ValueError("subtitle cue is missing time or text")
        time = TIMECODE.fullmatch(lines[0])
        if time is None:
            raise ValueError("subtitle cue has an invalid time range")
        start_ms = _millis(*time.groups()[:4])
        end_ms = _millis(*time.groups()[4:])
        if start_ms >= end_ms or end_ms > duration_ms:
            raise ValueError("subtitle cue is outside the selected part")
        original = "\n".join(lines[1:]).strip()
        if not original:
            raise ValueError("subtitle cue text is empty")
        segment_id = hashlib.sha256(
            f"{part}:{start_ms}:{end_ms}:{original}".encode("utf-8")
        ).hexdigest()[:24]
        segments.append(TranscriptSegment(
            segment_id=segment_id, part=part, start_ms=start_ms, end_ms=end_ms,
            original_text=original, corrected_text=original,
        ))
    if len({segment.segment_id for segment in segments}) != len(segments):
        raise ValueError("subtitle file contains duplicate cues")
    return sorted(segments, key=lambda segment: (segment.start_ms, segment.end_ms))


def subtitle_only_bundle(
    *, bundle_id: str, bundle_version: int, workspace_id: str, bvid: str,
    part: int, duration_ms: int, input_digest: str, subtitle_source: str,
    srt_text: str, corrections: dict[str, str] | None = None,
) -> VideoMaterialBundleV1:
    """Build a replayable subtitle stage with an explicit visual-coverage gap."""
    if subtitle_source == "NONE":
        if srt_text.strip() or corrections:
            raise ValueError("subtitle-free bundle cannot contain subtitles or corrections")
        segments: list[TranscriptSegment] = []
    else:
        segments = parse_srt_segments(srt_text, part=part, duration_ms=duration_ms)
        if not segments:
            raise ValueError("subtitle source returned no timed cues")
    replacements = corrections or {}
    unknown = replacements.keys() - {segment.segment_id for segment in segments}
    if unknown:
        raise ValueError("subtitle correction references an unknown cue")
    for segment in segments:
        if segment.segment_id in replacements:
            corrected = replacements[segment.segment_id].strip()
            if not corrected:
                raise ValueError("subtitle correction cannot erase a cue")
            segment.corrected_text = corrected
    return VideoMaterialBundleV1(
        bundle_id=bundle_id,
        bundle_version=bundle_version,
        workspace_id=workspace_id,
        bvid=bvid,
        part=part,
        duration_ms=duration_ms,
        input_digest=input_digest,
        subtitle_source=subtitle_source,
        transcript_original="\n".join(segment.original_text for segment in segments),
        transcript_corrected="\n".join(segment.corrected_text for segment in segments),
        transcript_segments=segments,
        frames=[],
        files=[],
        knowledge_nodes=[],
        coverage_gaps=["NO_FRAMES"] + (["NO_SUBTITLE"] if not segments else []),
    )


def _millis(hours: str, minutes: str, seconds: str, milliseconds: str) -> int:
    minute = int(minutes)
    second = int(seconds)
    if minute > 59 or second > 59:
        raise ValueError("subtitle cue has an invalid clock value")
    return ((int(hours) * 60 + minute) * 60 + second) * 1000 + int(milliseconds)
