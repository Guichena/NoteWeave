from __future__ import annotations

import pytest

from app.video_subtitle_material import parse_srt_segments, subtitle_only_bundle
from app.content_runtime import _build_text_from_acquisition_payload
from app.acquisition_runtime import _extract_provider_payload_text


SRT = """1
00:00:01,000 --> 00:00:02,500
cache yizhi

2
00:00:03,000 --> 00:00:04,750
write ordering
"""


def _bundle(**changes):
    fields = {
        "bundle_id": "bundle-1", "bundle_version": 1,
        "workspace_id": "workspace-1", "bvid": "BV1234567890", "part": 2,
        "duration_ms": 5000, "input_digest": "a" * 64,
        "subtitle_source": "MANUAL", "srt_text": SRT,
    }
    fields.update(changes)
    return subtitle_only_bundle(**fields)


def test_complete_srt_is_timed_and_original_text_survives_correction() -> None:
    parsed = parse_srt_segments(SRT, part=2, duration_ms=5000)
    bundle = _bundle(corrections={parsed[0].segment_id: "cache 一致性"})

    assert [segment.part for segment in bundle.transcript_segments] == [2, 2]
    assert [(segment.start_ms, segment.end_ms) for segment in bundle.transcript_segments] == [
        (1000, 2500), (3000, 4750)]
    assert bundle.transcript_original == "cache yizhi\nwrite ordering"
    assert bundle.transcript_corrected == "cache 一致性\nwrite ordering"
    assert bundle.coverage_gaps == ["NO_FRAMES"]
    assert bundle.content_digest() == _bundle(corrections={parsed[0].segment_id: "cache 一致性"}).content_digest()


def test_subtitle_stage_rejects_wrong_part_time_and_unknown_correction() -> None:
    with pytest.raises(ValueError, match="outside the selected part"):
        _bundle(duration_ms=4000)
    with pytest.raises(ValueError, match="unknown cue"):
        _bundle(corrections={"different-part-cue": "forged"})
    with pytest.raises(ValueError, match="invalid time range"):
        parse_srt_segments("1\n00:00:01 --> 00:00:02\nno milliseconds", part=2, duration_ms=5000)


def test_subtitle_absence_is_explicit_and_cannot_carry_fake_cues() -> None:
    bundle = _bundle(subtitle_source="NONE", srt_text="")
    assert bundle.coverage_gaps == ["NO_FRAMES", "NO_SUBTITLE"]
    assert bundle.transcript_segments == []
    with pytest.raises(ValueError, match="cannot contain subtitles"):
        _bundle(subtitle_source="NONE")


def test_full_controlled_subtitle_is_used_before_six_line_preview(tmp_path, monkeypatch) -> None:
    monkeypatch.setenv("NOTEWEAVE_MCP_SANDBOX_ROOT", str(tmp_path))
    subtitle = tmp_path / "subtitles" / "part-2.srt"
    subtitle.parent.mkdir()
    subtitle.write_text(SRT, encoding="utf-8")
    payload = {
        "selected_subtitle_path": str(subtitle),
        "subtitle_preview": ["cache yizhi"],
        "acquisition_mode": "remote_subtitle",
    }

    text, metadata = _build_text_from_acquisition_payload(payload)
    assert "write ordering" in text
    assert _extract_provider_payload_text(payload) == text
    assert metadata["selected_subtitle_path"] == str(subtitle)

    outside = tmp_path.parent / f"{tmp_path.name}-outside.srt"
    outside.write_text(SRT, encoding="utf-8")
    payload["selected_subtitle_path"] = str(outside)
    fallback, metadata = _build_text_from_acquisition_payload(payload)
    assert fallback == "cache yizhi"
    assert _extract_provider_payload_text(payload) == "cache yizhi"
    assert metadata["material_gap"] == "FULL_SUBTITLE_FILE_UNAVAILABLE"


def test_preview_only_legacy_payload_reports_missing_full_file() -> None:
    text, metadata = _build_text_from_acquisition_payload({"subtitle_preview": ["one line"]})
    assert text == "one line"
    assert metadata["material_gap"] == "FULL_SUBTITLE_FILE_UNAVAILABLE"
