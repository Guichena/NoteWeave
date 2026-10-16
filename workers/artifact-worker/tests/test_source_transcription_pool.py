import asyncio
import hashlib
import json
from pathlib import Path
import shutil
import threading
import time
from types import SimpleNamespace

import pytest

from app import source_transcription as transcriptions
from app.config import Settings


@pytest.fixture
def sandbox(tmp_path, monkeypatch):
    transcriptions.shutdown_source_transcriptions()
    monkeypatch.setattr(transcriptions, "_sandbox_root", lambda: tmp_path)
    transcriptions.start_source_transcription_pool(SimpleNamespace(transcription_pool_size=1, transcription_queue_capacity=2))
    yield tmp_path
    transcriptions.shutdown_source_transcriptions()


def _request(number=1, *, task=""):
    return {"workspace_id": "workspace-00000001", "source_id": "source-00000001", "snapshot_id": f"snapshot-{number:08d}",
        "file_name": "录音.mp3", "mime_type": "audio/mpeg", "content": b"ID3-unit-fixture", "task_id": task}


def _tool(calls):
    def invoke(server, operation):
        calls.append(operation)
        output = Path(operation["tool_arguments"]["output_dir"])
        output.mkdir(parents=True, exist_ok=True)
        transcript = output / "transcript.json"
        transcript.write_text(json.dumps({"language": "zh", "duration": 2.0,
            "segments": [{"start": 1.0, "end": 2.0, "text": "原始文字稿"}]}, ensure_ascii=False), encoding="utf-8")
        return {"artifacts": {"json_files": [str(transcript)]}}
    return invoke


def _job(root, number=1, *, task=""):
    data = _request(number, task=task)
    directory = root / "inputs" / "source-transcriptions" / (data["snapshot_id"] + ("--" + task if task else ""))
    directory.mkdir(parents=True)
    path = directory / "source.mp3"
    path.write_bytes(data["content"])
    job = transcriptions.TranscriptionJob(data["workspace_id"], data["source_id"], data["snapshot_id"], data["file_name"],
        data["mime_type"], path, task, hashlib.sha256(data["content"]).hexdigest(), len(data["content"]))
    transcriptions._atomic_json(directory / "job.json", transcriptions._metadata(job))
    return job


def _wait(predicate, seconds=5):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        if predicate():
            return
        time.sleep(0.01)
    assert predicate()


def test_full_capacity_rejects_before_creating_media_and_thread_count_is_fixed(sandbox, monkeypatch):
    started, release = threading.Event(), threading.Event()
    calls = []
    def blocked(job):
        calls.append(job.snapshot_id)
        started.set()
        release.wait(5)
    monkeypatch.setattr(transcriptions, "run_transcription_job", blocked)
    try:
        transcriptions.accept_source_transcription(**_request(1))
        assert started.wait(2)
        transcriptions.accept_source_transcription(**_request(2))
        transcriptions.accept_source_transcription(**_request(3))
        with pytest.raises(transcriptions.TranscriptionCapacityExceeded):
            transcriptions.accept_source_transcription(**_request(4))
        assert not (sandbox / "inputs" / "source-transcriptions" / "snapshot-00000004").exists()
        assert len(transcriptions._pool.threads) == 1
        assert calls == ["snapshot-00000001"]
    finally:
        release.set()
    assert transcriptions.wait_for_source_transcriptions(5)


def test_active_and_queued_duplicate_requests_check_every_frozen_identity_and_hash(sandbox, monkeypatch):
    started, release = threading.Event(), threading.Event()
    monkeypatch.setattr(transcriptions, "run_transcription_job", lambda job: started.set() or release.wait(5))
    try:
        transcriptions.accept_source_transcription(**_request(1))
        assert started.wait(2)
        transcriptions.accept_source_transcription(**_request(2))
        assert transcriptions.accept_source_transcription(**_request(1))["duplicate"]
        assert transcriptions.accept_source_transcription(**_request(2))["duplicate"]
        for change in ({"workspace_id": "workspace-00000002"}, {"source_id": "source-00000002"},
                {"file_name": "另一录音.mp3"}, {"mime_type": "audio/wav"}, {"content": b"ID3-changed"}):
            with pytest.raises(ValueError, match="冻结身份或摘要"):
                transcriptions.accept_source_transcription(**dict(_request(2), **change))
    finally:
        release.set()
    assert transcriptions.wait_for_source_transcriptions(5)


def test_configured_two_workers_never_exceed_two_concurrent_jobs(sandbox, monkeypatch):
    transcriptions.shutdown_source_transcriptions()
    transcriptions.start_source_transcription_pool(SimpleNamespace(transcription_pool_size=2, transcription_queue_capacity=1))
    release = threading.Event()
    lock = threading.Lock()
    active, peaks = [0], []
    def invoke(job):
        with lock:
            active[0] += 1
            peaks.append(active[0])
        release.wait(5)
        with lock:
            active[0] -= 1
    monkeypatch.setattr(transcriptions, "run_transcription_job", invoke)
    try:
        transcriptions.accept_source_transcription(**_request(1))
        transcriptions.accept_source_transcription(**_request(2))
        _wait(lambda: active[0] == 2)
        transcriptions.accept_source_transcription(**_request(3))
        assert len(transcriptions._pool.threads) == 2
        assert transcriptions._pool.pending.qsize() == 1
    finally:
        release.set()
    assert transcriptions.wait_for_source_transcriptions(5)
    assert max(peaks) == 2


def test_different_task_of_active_snapshot_returns_capacity_instead_of_overwriting(sandbox, monkeypatch):
    started, release = threading.Event(), threading.Event()
    monkeypatch.setattr(transcriptions, "run_transcription_job", lambda job: started.set() or release.wait(5))
    try:
        transcriptions.accept_source_transcription(**_request(1, task="task-00000001"))
        assert started.wait(2)
        with pytest.raises(transcriptions.TranscriptionCapacityExceeded):
            transcriptions.accept_source_transcription(**_request(1, task="task-00000002"))
        assert not (sandbox / "inputs" / "source-transcriptions" / "snapshot-00000001--task-00000002").exists()
    finally:
        release.set()


def test_sync_mode_runs_outside_registry_lock_and_uses_execution_limit(sandbox, monkeypatch):
    seen = []
    def invoke(job):
        assert transcriptions._running_lock.acquire(timeout=1)
        transcriptions._running_lock.release()
        seen.append(job.snapshot_id)
    monkeypatch.setattr(transcriptions, "run_transcription_job", invoke)
    assert transcriptions.accept_source_transcription(**_request(1), run_async=False)["accepted"]
    assert seen == ["snapshot-00000001"]
    assert transcriptions.wait_for_source_transcriptions(1)


def test_shutdown_keeps_queued_checkpoints_and_does_not_release_active_execution_early(sandbox, monkeypatch):
    started, release = threading.Event(), threading.Event()
    monkeypatch.setattr(transcriptions, "run_transcription_job", lambda job: started.set() or release.wait(5))
    try:
        transcriptions.accept_source_transcription(**_request(1))
        assert started.wait(2)
        transcriptions.accept_source_transcription(**_request(2))
        transcriptions.shutdown_source_transcriptions(timeout=0.01)
        assert "snapshot-00000001" in transcriptions._running
        assert "snapshot-00000002" not in transcriptions._running
        queued = sandbox / "inputs" / "source-transcriptions" / "snapshot-00000002"
        assert (queued / "source.mp3").is_file()
        assert json.loads((queued / "job.json").read_text(encoding="utf-8"))["state"] == "QUEUED"
        with pytest.raises(transcriptions.TranscriptionCapacityExceeded):
            transcriptions.accept_source_transcription(**_request(3))
    finally:
        release.set()
    assert transcriptions.wait_for_source_transcriptions(5)


def test_recovery_continues_backlog_larger_than_queue_without_creating_more_threads(sandbox, monkeypatch):
    started, release = threading.Event(), threading.Event()
    seen = []
    for number in range(1, 9):
        _job(sandbox, number)
    def invoke(job):
        if not seen:
            started.set()
            release.wait(5)
        seen.append(job.snapshot_id)
        shutil.rmtree(job.media_path.parent)
    monkeypatch.setattr(transcriptions, "run_transcription_job", invoke)
    try:
        transcriptions.recover_source_transcriptions()
        assert started.wait(2)
        assert len(transcriptions._running) <= 3
        assert len(transcriptions._pool.threads) == 1
    finally:
        release.set()
    _wait(lambda: len(seen) == 8)
    assert len(set(seen)) == 8
    assert transcriptions.wait_for_source_transcriptions(5)


def test_callback_failure_keeps_media_and_recovery_only_redelivers_frozen_payload(sandbox, monkeypatch):
    job = _job(sandbox, task="task-00000001")
    calls, delivered = [], []
    payload = transcriptions.run_transcription_job(job, call_tool=_tool(calls), post_callback=lambda sid, body: False, llm_client=None)
    assert payload["task_id"] == "task-00000001"
    assert job.media_path.is_file()
    assert (job.media_path.parent / "callback-pending.json").is_file()
    assert transcriptions._output_root(job).is_dir()
    monkeypatch.setattr(transcriptions, "build_default_llm_client", lambda: pytest.fail("恢复回调不能重新创建校对模型"))
    recovered = transcriptions._load_job(job.media_path.parent)
    again = transcriptions.run_transcription_job(recovered, call_tool=lambda *args: pytest.fail("恢复回调不能重新转写"),
        post_callback=lambda sid, body: delivered.append(body) or True)
    assert again == payload
    assert delivered == [payload]
    assert len(calls) == 1
    assert not job.media_path.parent.exists()


def test_callback_exception_keeps_success_and_failure_checkpoints(sandbox):
    for number, tool in ((1, _tool([])), (2, lambda *args: (_ for _ in ()).throw(ValueError("unit failure")))):
        job = _job(sandbox, number)
        def broken(sid, body):
            raise OSError("unit callback unavailable")
        payload = transcriptions.run_transcription_job(job, call_tool=tool, post_callback=broken, llm_client=None)
        assert payload["status"] == ("COMPLETED" if number == 1 else "FAILED")
        assert job.media_path.is_file()
        assert (job.media_path.parent / "callback-pending.json").is_file()


def test_discarded_acknowledgement_cleans_only_old_task_directory(sandbox):
    old = _job(sandbox, 1, task="task-00000001")
    new = _job(sandbox, 1, task="task-00000002")
    result = transcriptions.run_transcription_job(old, call_tool=_tool([]),
        post_callback=lambda sid, body: transcriptions.CallbackAcknowledgement(discarded=True), llm_client=None)
    assert result["callback_disposition"] == "DISCARDED"
    assert not old.media_path.parent.exists()
    assert new.media_path.is_file()


def test_recovery_rejects_external_media_and_directory_identity_before_reading(sandbox, monkeypatch):
    job = _job(sandbox)
    metadata = transcriptions._metadata(job)
    metadata["media_file"] = "../secret.mp3"
    transcriptions._atomic_json(job.media_path.parent / "job.json", metadata)
    called = []
    monkeypatch.setattr(transcriptions, "_hash_file", lambda path: called.append(path) or "a" * 64)
    with pytest.raises(ValueError, match="外部路径"):
        transcriptions._load_job(job.media_path.parent)
    assert not called
    metadata["media_file"] = "source.mp3"
    metadata["snapshot_id"] = "snapshot-00000002"
    transcriptions._atomic_json(job.media_path.parent / "job.json", metadata)
    with pytest.raises(ValueError, match="冻结任务身份"):
        transcriptions._load_job(job.media_path.parent)


def test_media_digest_and_callback_payload_identity_cannot_change_after_checkpoint(sandbox):
    job = _job(sandbox)
    transcriptions.run_transcription_job(job, call_tool=_tool([]), post_callback=lambda *args: False, llm_client=None)
    checkpoint = job.media_path.parent / "callback-pending.json"
    value = json.loads(checkpoint.read_text(encoding="utf-8"))
    value["payload"]["source_id"] = "source-00000002"
    checkpoint.write_text(json.dumps(value), encoding="utf-8")
    with pytest.raises(ValueError, match="身份或状态"):
        transcriptions.run_transcription_job(job, call_tool=lambda *args: pytest.fail("不可重新转写"), post_callback=lambda *args: True, llm_client=None)
    job.media_path.write_bytes(b"ID3-modified")
    with pytest.raises(ValueError, match="冻结摘要"):
        transcriptions._load_job(job.media_path.parent)


def test_identifiers_reject_trailing_newline_before_creating_directory(sandbox):
    with pytest.raises(ValueError, match="snapshot_id"):
        transcriptions.accept_source_transcription(**dict(_request(), snapshot_id="snapshot-00000001\n"))
    assert not list((sandbox / "inputs" / "source-transcriptions").glob("*")) if (sandbox / "inputs" / "source-transcriptions").exists() else True


def test_invalid_disk_json_does_not_terminate_recovery_thread(sandbox, monkeypatch):
    broken = sandbox / "inputs" / "source-transcriptions" / "broken"
    broken.mkdir(parents=True)
    (broken / "job.json").write_text("[]", encoding="utf-8")
    job = _job(sandbox)
    called = []
    def invoke(current):
        called.append(current.snapshot_id)
        shutil.rmtree(current.media_path.parent)
    monkeypatch.setattr(transcriptions, "run_transcription_job", invoke)
    transcriptions.recover_source_transcriptions()
    _wait(lambda: called == [job.snapshot_id])
    assert all(thread.is_alive() for thread in transcriptions._pool.threads)


def test_checkpoint_corruption_after_execution_releases_capacity_and_keeps_worker_alive(sandbox, monkeypatch):
    seen = []
    def corrupt(job):
        seen.append(job.snapshot_id)
        (job.media_path.parent / "job.json").write_text("[]", encoding="utf-8")
    monkeypatch.setattr(transcriptions, "run_transcription_job", corrupt)
    transcriptions.accept_source_transcription(**_request(1))
    assert transcriptions.wait_for_source_transcriptions(5)
    transcriptions.accept_source_transcription(**_request(2))
    assert transcriptions.wait_for_source_transcriptions(5)
    assert seen == ["snapshot-00000001", "snapshot-00000002"]
    assert all(thread.is_alive() for thread in transcriptions._pool.threads)


def test_configured_pool_and_queue_have_finite_validated_limits(monkeypatch):
    for name, value in (("NOTEWEAVE_TRANSCRIPTION_POOL_SIZE", "0"), ("NOTEWEAVE_TRANSCRIPTION_POOL_SIZE", "9"),
            ("NOTEWEAVE_TRANSCRIPTION_QUEUE_CAPACITY", "0"), ("NOTEWEAVE_TRANSCRIPTION_QUEUE_CAPACITY", "65")):
        with monkeypatch.context() as context:
            context.setenv(name, value)
            with pytest.raises(ValueError):
                Settings()


def test_capacity_exception_is_http_429_with_retry_header_and_task_id_passthrough(monkeypatch):
    from app import main
    observed = []
    def unavailable(**kwargs):
        observed.append(kwargs)
        raise transcriptions.TranscriptionCapacityExceeded("unit capacity")
    monkeypatch.setattr(main, "accept_source_transcription", unavailable)
    class Body:
        async def stream(self): yield b"ID3-unit"
    with pytest.raises(main.HTTPException) as response:
        asyncio.run(main.submit_source_transcription(Body(), "workspace-00000001", "source-00000001", "snapshot-00000001",
            "audio/mpeg", "录音.mp3", "task-00000001"))
    assert response.value.status_code == 429
    assert response.value.headers == {"Retry-After": "5"}
    assert response.value.detail["code"] == "SOURCE_TRANSCRIPTION_CAPACITY_EXHAUSTED"
    assert observed[0]["task_id"] == "task-00000001"


def test_lifecycle_stops_pool_even_when_consumer_start_fails(monkeypatch):
    from app import main
    calls = []
    monkeypatch.setattr(main, "recover_system_mcp_acquisition_operations", lambda **kwargs: None)
    monkeypatch.setattr(main, "start_source_transcription_pool", lambda settings: calls.append("pool-start"))
    monkeypatch.setattr(main, "recover_source_transcriptions", lambda: [])
    monkeypatch.setattr(main, "shutdown_source_transcriptions", lambda: calls.append("pool-stop"))
    class Consumer:
        def __init__(self, settings): pass
        def start(self): raise ValueError("unit consumer unavailable")
        def stop(self): calls.append("consumer-stop")
    monkeypatch.setattr(main, "ArtifactKafkaConsumerRuntime", Consumer)
    async def run():
        async with main.artifact_worker_lifespan(main.app):
            pytest.fail("初始化失败不能进入服务生命周期")
    with pytest.raises(ValueError, match="unit consumer"):
        asyncio.run(run())
    assert calls == ["pool-start", "consumer-stop", "pool-stop"]


def test_http_stream_rejects_over_limit_before_accepting_job(monkeypatch):
    from app import main
    monkeypatch.setattr(main, "_MAX_TRANSCRIPTION_BYTES", 4)
    accepted = []
    monkeypatch.setattr(main, "accept_source_transcription", lambda **kwargs: accepted.append(kwargs))
    class Body:
        async def stream(self):
            yield b"12"
            yield b"345"
    with pytest.raises(main.HTTPException) as error:
        asyncio.run(main.submit_source_transcription(Body(), "workspace-00000001", "source-00000001", "snapshot-00000001", "audio/mpeg"))
    assert error.value.status_code == 413
    assert not accepted


def test_lifecycle_always_stops_pool_when_consumer_shutdown_raises(monkeypatch):
    from app import main
    calls = []
    monkeypatch.setattr(main, "recover_system_mcp_acquisition_operations", lambda **kwargs: None)
    monkeypatch.setattr(main, "start_source_transcription_pool", lambda settings: calls.append("pool-start"))
    monkeypatch.setattr(main, "recover_source_transcriptions", lambda: [])
    monkeypatch.setattr(main, "shutdown_source_transcriptions", lambda: calls.append("pool-stop"))
    class Consumer:
        def __init__(self, settings): pass
        def start(self): pass
        def stop(self):
            calls.append("consumer-stop")
            raise ValueError("unit stop unavailable")
    monkeypatch.setattr(main, "ArtifactKafkaConsumerRuntime", Consumer)
    async def run():
        async with main.artifact_worker_lifespan(main.app):
            pass
    with pytest.raises(ValueError, match="unit stop"):
        asyncio.run(run())
    assert calls == ["pool-start", "consumer-stop", "pool-stop"]
