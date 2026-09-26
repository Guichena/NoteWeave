from __future__ import annotations

import hashlib
import re

from app.video_material_bundle import TranscriptSegment, VideoMaterialBundleV1


TIMECODE = re.compile(
    r"^(\d{2}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*"
    r"(\d{2}):(\d{2}):(\d{2})[,.](\d{3})(?:\s+.*)?$"
)


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
