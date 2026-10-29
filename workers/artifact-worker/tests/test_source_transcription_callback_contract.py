from __future__ import annotations

import hashlib
import io
import json
from pathlib import Path
from types import SimpleNamespace
from urllib.error import URLError

import pytest

from app import source_transcription


@pytest.fixture
def callback_job(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr(source_transcription, "_sandbox_root", lambda: tmp_path)
    monkeypatch.setattr(source_transcription, "load_settings", lambda: SimpleNamespace(
        java_base_url="http://backend.test/", internal_auth_token="callback-test-token"
    ))
    sleeps: list[float] = []
    monkeypatch.setattr(source_transcription.time, "sleep", sleeps.append)
    directory = tmp_path / "inputs" / "source-transcriptions" / "snapshot-00000001--task-00000001"
    directory.mkdir(parents=True)
    media = directory / "source.mp3"
    content = b"ID3-contract-media"
    media.write_bytes(content)
    job = source_transcription.TranscriptionJob(
        workspace_id="workspace-00000001", source_id="source-00000001", snapshot_id="snapshot-00000001",
        file_name="测试录音.mp3", mime_type="audio/mpeg", media_path=media, task_id="task-00000001",
        content_sha256=hashlib.sha256(content).hexdigest(), file_size=len(content),
    )
    # 保留旧中间文件场景：未确认回调时不得清理，确认后必须清理。
    legacy_output = source_transcription._output_root(job)
    legacy_output.mkdir(parents=True)
    (legacy_output / "source.json").write_text("{}", encoding="utf-8")
    return job, sleeps


def _transcribe(tool_name, arguments):
    assert tool_name == "parse_media"
    assert set(arguments) == {"file_ref", "title"}
    assert not Path(arguments["file_ref"]).is_absolute()
    return {
        "title": arguments["title"],
        "segments": [{"part": 1, "start_ms": 0, "end_ms": 2000, "text": "测试文字稿", "frames": []}],
        "stats": {"duration_ms": 2000, "candidate_frames": 0, "kept_frames": 0, "transcript_origin": "ASR"},
    }


def _responses(monkeypatch, data):
    calls = []

    def open_response(request, *, timeout):
        calls.append((request, timeout))
        return io.BytesIO(json.dumps({"data": data}).encode("utf-8"))

    monkeypatch.setattr(source_transcription.request, "urlopen", open_response)
    return calls


@pytest.mark.parametrize("data,discarded", [
    ({"snapshot_id": "snapshot-00000001", "accepted": True}, False),
    ({"snapshot_id": "snapshot-00000001", "accepted": True, "discarded": False}, False),
    ({"snapshot_id": "snapshot-00000001", "accepted": False, "discarded": True}, True),
])
def test_only_positive_acceptance_or_discard_acknowledgement_cleans_owned_files(
    callback_job, monkeypatch, data, discarded,
):
    job, sleeps = callback_job
    calls = _responses(monkeypatch, data)

    result = source_transcription.run_transcription_job(job, call_tool=_transcribe, llm_client=None)

    assert result["task_id"] == job.task_id
    assert result["status"] == "COMPLETED"
    assert (result.get("callback_disposition") == "DISCARDED") is discarded
    assert not job.media_path.parent.exists()
    assert not source_transcription._output_root(job).exists()
    assert len(calls) == 1
    request, timeout = calls[0]
    assert request.full_url == "http://backend.test/internal/worker/source-transcriptions/" + job.snapshot_id
    assert request.method == "POST"
    assert timeout == 30
    assert request.get_header("X-noteweave-internal-token") == "callback-test-token"
    assert json.loads(request.data)["task_id"] == job.task_id
    assert json.loads(request.data)["media_document"] == result["media_document"]
    assert sleeps == []


@pytest.mark.parametrize("data", [
    {"snapshot_id": "snapshot-00000001", "accepted": False},
    {"snapshot_id": "snapshot-00000001", "accepted": False, "discarded": False},
    {"snapshot_id": "snapshot-00000001"},
    {"snapshot_id": "snapshot-00000001", "discarded": True},
    {"snapshot_id": "another-snapshot", "accepted": True},
    {"snapshot_id": "snapshot-00000001", "accepted": 1},
    {"snapshot_id": "snapshot-00000001", "accepted": "true"},
    {"snapshot_id": "snapshot-00000001", "accepted": None},
    {"snapshot_id": "snapshot-00000001", "accepted": False, "discarded": 1},
    {"snapshot_id": "snapshot-00000001", "accepted": True, "discarded": True},
    {"snapshot_id": "snapshot-00000001", "accepted": True, "unexpected": True},
])
def test_non_boolean_wrong_snapshot_missing_or_ambiguous_flags_never_confirm_cleanup(
    callback_job, monkeypatch, data,
):
    job, sleeps = callback_job
    calls = _responses(monkeypatch, data)

    result = source_transcription.run_transcription_job(job, call_tool=_transcribe, llm_client=None)

    assert result["task_id"] == job.task_id
    assert "callback_disposition" not in result
    assert job.media_path.is_file()
    assert source_transcription._output_root(job).is_dir()
    checkpoint = json.loads((job.media_path.parent / "callback-pending.json").read_text(encoding="utf-8"))
    assert checkpoint["payload"] == result
    assert checkpoint["payload"]["task_id"] == job.task_id
    assert len(calls) == 6
    assert all(json.loads(request.data)["task_id"] == job.task_id for request, _ in calls)
    assert all(timeout == 30 for _, timeout in calls)
    assert sleeps and max(sleeps) <= 60


def test_network_failure_has_six_real_attempts_and_keeps_the_media_and_callback_checkpoint(
    callback_job, monkeypatch,
):
    job, sleeps = callback_job
    requests = []

    def network_failure(request, *, timeout):
        requests.append(request)
        raise URLError("测试网络故障")

    monkeypatch.setattr(source_transcription.request, "urlopen", network_failure)
    result = source_transcription.run_transcription_job(job, call_tool=_transcribe, llm_client=None)

    assert result["status"] == "COMPLETED"
    assert len(requests) == 6
    assert len({request.data for request in requests}) == 1
    assert all(json.loads(request.data)["task_id"] == job.task_id for request in requests)
    assert job.media_path.is_file()
    assert (job.media_path.parent / "callback-pending.json").is_file()
    assert json.loads((job.media_path.parent / "job.json").read_text(encoding="utf-8"))["state"] == "CALLBACK_PENDING"
    assert max(sleeps) <= 60


def test_pending_callback_cannot_be_reused_for_a_different_frozen_task(callback_job, monkeypatch):
    job, _ = callback_job
    calls = _responses(monkeypatch, {"snapshot_id": job.snapshot_id, "accepted": False})
    source_transcription.run_transcription_job(job, call_tool=_transcribe, llm_client=None)
    path = job.media_path.parent / "callback-pending.json"
    pending = json.loads(path.read_text(encoding="utf-8"))
    pending["payload"]["task_id"] = "task-00000002"
    path.write_text(json.dumps(pending), encoding="utf-8")

    def must_not_transcribe(*_args):
        raise AssertionError("回调检查点不能重新执行转写工具")

    with pytest.raises(ValueError, match="身份"):
        source_transcription.run_transcription_job(job, call_tool=must_not_transcribe, llm_client=None)
    assert len(calls) == 6
    assert job.media_path.is_file()
    assert path.is_file()
