import json
from pathlib import Path

import pytest

from app import source_transcription
from app.source_transcription import (
    TranscriptionJob,
    accept_source_transcription,
    format_transcript,
    run_transcription_job,
)


@pytest.fixture()
def sandbox(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    source_transcription.shutdown_source_transcriptions()
    monkeypatch.setattr(source_transcription, "_sandbox_root", lambda: tmp_path)
    source_transcription.start_source_transcription_pool()
    yield tmp_path
    source_transcription.shutdown_source_transcriptions()


def _job(sandbox: Path) -> TranscriptionJob:
    media_dir = sandbox / "inputs" / "source-transcriptions" / "snapshot-00000001"
    media_dir.mkdir(parents=True)
    media = media_dir / "source.mp3"
    media.write_bytes(b"ID3fake")
    return TranscriptionJob("workspace-00000001", "source-00000001", "snapshot-00000001",
                            "组会录音.mp3", "audio/mpeg", media)


def test_transcript_lines_start_with_the_segment_time() -> None:
    text = format_transcript([
        {"start": 0.4, "end": 3.0, "text": " 先说结论 "},
        {"start": 65.2, "end": 70.0, "text": "缓存一致性用延迟双删"},
        {"start": 3725.0, "end": 3730.0, "text": "散会"},
        {"start": 3731.0, "end": 3732.0, "text": "  "},
    ])

    assert text == "[00:00] 先说结论\n[01:05] 缓存一致性用延迟双删\n[01:02:05] 散会"


def test_successful_transcription_calls_back_with_timestamped_text(sandbox: Path) -> None:
    job = _job(sandbox)
    calls: list[dict[str, object]] = []
    delivered: list[tuple[str, dict[str, object]]] = []

    def fake_tool(tool_name: str, arguments: dict[str, object]) -> dict[str, object]:
        calls.append({"tool_name": tool_name, "arguments": arguments})
        return {
            "title": job.file_name,
            "segments": [{"part": 1, "start_ms": 1000, "end_ms": 4000, "text": "今天讨论产物生成", "frames": []},
                         {"part": 1, "start_ms": 62000, "end_ms": 66000, "text": "结论是先做音频纪要", "frames": []}],
            "stats": {"duration_ms": 125000, "candidate_frames": 0, "kept_frames": 0, "transcript_origin": "ASR"},
        }

    # 预置迁移或重试时可能残留的旧中间文件，继续验证确认回调后的清理。
    legacy_output = source_transcription._output_root(job)
    legacy_output.mkdir(parents=True)
    (legacy_output / "source.json").write_text("{}", encoding="utf-8")

    payload = run_transcription_job(job, call_tool=fake_tool,
                                    post_callback=lambda snapshot, body: delivered.append((snapshot, body)) or True,
                                    llm_client=None)

    # HTTP 媒体工具只接收共享输入卷内的相对引用，不再接收 Worker 的输出目录。
    assert calls[0]["tool_name"] == "parse_media"
    assert calls[0]["arguments"] == {
        "file_ref": "source-transcriptions/snapshot-00000001/source.mp3", "title": job.file_name,
    }
    assert (sandbox / "inputs" / calls[0]["arguments"]["file_ref"]).resolve() == job.media_path.resolve()
    assert payload["status"] == "COMPLETED"
    assert payload["transcript_text"] == "[00:01] 今天讨论产物生成\n[01:02] 结论是先做音频纪要"
    assert payload["duration_seconds"] == 125.0
    assert payload["segment_count"] == 2
    assert payload["media_document"]["segments"][1]["start_ms"] == 62000
    assert delivered == [("snapshot-00000001", payload)]
    # 原件和中间文件都会清理
    assert not job.media_path.parent.exists()
    assert not legacy_output.exists()


def test_failed_transcription_still_calls_back_so_the_source_does_not_hang(sandbox: Path) -> None:
    job = _job(sandbox)
    delivered: list[dict[str, object]] = []

    def broken_tool(server: object, operation: dict[str, object]) -> dict[str, object]:
        raise ValueError("model download failed")

    payload = run_transcription_job(job, call_tool=broken_tool,
                                    post_callback=lambda snapshot, body: delivered.append(body) or True)

    assert payload["status"] == "FAILED"
    assert payload["error_code"] == "SOURCE_TRANSCRIPTION_FAILED"
    assert "model download failed" in str(payload["error_message"])
    assert delivered == [payload]


def test_submission_rejects_unsupported_media_and_bad_identifiers(sandbox: Path) -> None:
    with pytest.raises(ValueError, match="不支持的媒体类型"):
        accept_source_transcription(workspace_id="workspace-00000001", source_id="source-00000001",
                                    snapshot_id="snapshot-00000001", file_name="a.pdf",
                                    mime_type="application/pdf", content=b"%PDF")
    with pytest.raises(ValueError, match="snapshot_id"):
        accept_source_transcription(workspace_id="workspace-00000001", source_id="source-00000001",
                                    snapshot_id="../escape", file_name="a.mp3",
                                    mime_type="audio/mpeg", content=b"ID3")


def test_submission_stores_the_media_inside_the_sandbox(sandbox: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    seen: list[TranscriptionJob] = []
    monkeypatch.setattr(source_transcription, "run_transcription_job", lambda job: seen.append(job))

    result = accept_source_transcription(workspace_id="workspace-00000001", source_id="source-00000001",
                                         snapshot_id="snapshot-00000002", file_name="访谈.m4a",
                                         mime_type="audio/mp4", content=b"....ftypM4A ", run_async=False)

    assert result == {"accepted": True, "snapshot_id": "snapshot-00000002", "duplicate": False}
    assert seen[0].media_path == sandbox / "inputs" / "source-transcriptions" / "snapshot-00000002" / "source.m4a"
    assert seen[0].media_path.read_bytes() == b"....ftypM4A "


def test_unfinished_transcriptions_are_resumed_after_a_worker_restart(
    sandbox: Path, monkeypatch: pytest.MonkeyPatch,
) -> None:
    started: list[TranscriptionJob] = []
    monkeypatch.setattr(source_transcription, "run_transcription_job", lambda job: started.append(job))
    accept_source_transcription(workspace_id="workspace-00000001", source_id="source-00000001",
                                snapshot_id="snapshot-00000003", file_name="周会.wav",
                                mime_type="audio/wav", content=b"RIFF....WAVE", run_async=False)
    started.clear()
    # 模拟重启：线程记录清空，但沙箱里的原件和任务信息还在
    source_transcription.shutdown_source_transcriptions()
    checkpoint = sandbox / "inputs" / "source-transcriptions" / "snapshot-00000003" / "job.json"
    meta = json.loads(checkpoint.read_text(encoding="utf-8"))
    meta["next_attempt_at"] = 0
    checkpoint.write_text(json.dumps(meta), encoding="utf-8")
    (sandbox / "inputs" / "source-transcriptions" / "broken").mkdir(parents=True)

    recovered = source_transcription.recover_source_transcriptions()
    assert source_transcription.wait_for_source_transcriptions(timeout=5)

    assert recovered == ["snapshot-00000003"]
    assert [job.snapshot_id for job in started] == ["snapshot-00000003"]
    assert started[0].file_name == "周会.wav"
    assert started[0].media_path.read_bytes() == b"RIFF....WAVE"


def test_llm_proofreading_fixes_homophones_but_keeps_timestamps_and_line_count() -> None:
    from app.llm_client import FakeLlmClient
    from app.source_transcription import correct_transcript

    lines = ["[00:03] 第一，音频计要先给结论", "[00:09] 经过解析、切片、向良化和锁引四个阶段", "[00:16] 完成转写功能的连条"]
    fake = FakeLlmClient({"transcript_correction": json.dumps({"lines": [
        "[00:03] 第一，音频纪要先给结论",
        "[00:09] 经过解析、切片、向量化和索引四个阶段",
        "[99:99] 完成转写功能的联调",  # 改动了时间戳，这一行保留原文
    ]}, ensure_ascii=False)})

    corrected, changed = correct_transcript(lines, fake)

    assert corrected == ["[00:03] 第一，音频纪要先给结论", "[00:09] 经过解析、切片、向量化和索引四个阶段",
                         "[00:16] 完成转写功能的连条"]
    assert changed == 2
    assert fake.calls[0][0] == "transcript_correction"


def test_proofreading_output_with_a_different_line_count_is_ignored() -> None:
    from app.llm_client import FakeLlmClient
    from app.source_transcription import correct_transcript

    lines = ["[00:01] 甲", "[00:02] 乙"]
    merged = FakeLlmClient({"transcript_correction": json.dumps({"lines": ["[00:01] 甲乙"]}, ensure_ascii=False)})
    broken = FakeLlmClient({"transcript_correction": "not json"})

    assert correct_transcript(lines, merged) == (lines, 0)
    assert correct_transcript(lines, broken) == (lines, 0)
