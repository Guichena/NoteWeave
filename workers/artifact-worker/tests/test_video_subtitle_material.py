from __future__ import annotations

import pytest
from types import SimpleNamespace

from app.video_subtitle_material import (
    parse_srt_segments, subtitle_only_bundle, subtitle_bundle_from_provider,
)
from app.video_material_bundle import frozen_video_input_digest
from app.content_runtime import _build_text_from_acquisition_payload
from app.acquisition_runtime import _extract_provider_payload_text


SRT = """1
00:00:01,000 --> 00:00:02,500
cache yizhi

2
00:00:03,000 --> 00:00:04,750
write ordering
"""


def test_frozen_video_input_digest_uses_snapshot_and_canonical_inputs() -> None:
    inputs = {"url": "https://www.bilibili.com/video/BV1234567890?p=2", "language": "zh-CN"}
    digest = frozen_video_input_digest("snapshot-1", inputs)
    assert digest == frozen_video_input_digest("snapshot-1", dict(reversed(list(inputs.items()))))
    assert digest != frozen_video_input_digest("snapshot-2", inputs)
    assert digest != frozen_video_input_digest("snapshot-1", {**inputs, "language": "en"})
    with pytest.raises(ValueError, match="frozen input snapshot"):
        frozen_video_input_digest("", inputs)


def test_full_provider_subtitle_can_be_frozen_for_selected_part(tmp_path, monkeypatch) -> None:
    monkeypatch.setenv("NOTEWEAVE_MCP_SANDBOX_ROOT", str(tmp_path))
    subtitle = tmp_path / "part-2.srt"
    subtitle.write_text(SRT, encoding="utf-8")
    inputs = {"url": "https://www.bilibili.com/video/BV1234567890?p=2", "language": "zh-CN"}
    task = SimpleNamespace(task_id="task-1", workspace_id="ws-1", input_snapshot_id="snapshot-1",
                           input_payload=SimpleNamespace(skill_key="bilibili_course_note_pdf", inputs=inputs))
    provider = {
        "normalized_video_id": "BV1234567890", "metadata": {"duration": 5,
            "webpage_url": "https://www.bilibili.com/video/BV1234567890?p=2"},
        "acquisition_mode": "remote_cc_subtitle_fetch",
        "selected_subtitle_path": str(subtitle), "subtitle_preview": ["only one line"],
    }

    bundle = subtitle_bundle_from_provider(task, provider)
    assert bundle is not None
    assert bundle.part == 2
    assert bundle.duration_ms == 5000
    assert len(bundle.transcript_segments) == 2
    assert bundle.transcript_original == "cache yizhi\nwrite ordering"
    assert bundle.input_digest == frozen_video_input_digest("snapshot-1", inputs)
    assert bundle.coverage_gaps == ["NO_FRAMES"]
    assert subtitle_bundle_from_provider(task, {**provider, "metadata": {}}) is None
    assert subtitle_bundle_from_provider(task, {**provider, "metadata": {
        "duration": 5, "webpage_url": "https://www.bilibili.com/video/BV1234567890?p=1"}}) is None
    assert subtitle_bundle_from_provider(task, {**provider, "selected_subtitle_path": ""}) is None
    assert subtitle_bundle_from_provider(task, {**provider, "normalized_video_id": "BV9999999999"}) is None


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


def test_machine_subtitles_are_llm_proofread_but_manual_ones_are_untouched() -> None:
    import json as _json
    from app.llm_client import FakeLlmClient
    from app.video_subtitle_material import machine_subtitle_corrections, subtitle_only_bundle

    srt = "1\n00:00:01,000 --> 00:00:03,000\n全线管控和象量检索\n\n2\n00:00:04,000 --> 00:00:06,000\nRAG 架构\n"
    fixed = _json.dumps({"lines": ["[00:01] 权限管控和向量检索", "[00:04] RAG 架构"]}, ensure_ascii=False)
    client = FakeLlmClient({"transcript_correction": fixed})

    corrections = machine_subtitle_corrections(srt, part=1, duration_ms=10_000,
                                               subtitle_source="ASR", client=client)
    bundle = subtitle_only_bundle(bundle_id="b", bundle_version=1, workspace_id="w", bvid="BV1234567890",
                                  part=1, duration_ms=10_000, input_digest="a" * 64,
                                  subtitle_source="ASR", srt_text=srt, corrections=corrections)

    assert [s.corrected_text for s in bundle.transcript_segments] == ["权限管控和向量检索", "RAG 架构"]
    assert bundle.transcript_segments[0].original_text == "全线管控和象量检索"
    assert machine_subtitle_corrections(srt, part=1, duration_ms=10_000,
                                        subtitle_source="MANUAL", client=client) == {}


def test_subtitle_correction_keeps_cue_when_model_breaks_the_timestamp() -> None:
    import json as _json
    from app.llm_client import FakeLlmClient
    from app.video_subtitle_material import machine_subtitle_corrections

    srt = "1\n00:00:01,000 --> 00:00:03,000\n象量检索\n"
    client = FakeLlmClient({"transcript_correction": _json.dumps({"lines": ["[09:59] 向量检索"]})})
    assert machine_subtitle_corrections(srt, part=1, duration_ms=10_000,
                                        subtitle_source="ASR", client=client) == {}
