import asyncio
import json

from fastapi import Request
from fastapi import HTTPException
import pytest

from app.acquisition_runtime import (
    configure_acquisition_runtime_store,
    list_acquisition_operations,
)
from app.capability_wait_queue import (
    configure_waiting_task_store,
    enqueue_waiting_task,
    get_waiting_task,
)
from app import capability_wait_queue
from app.artifact_repository import FileArtifactRepositoryBackend
from app.main import download_task_export, protect_debug_routes
from app.runner import run_artifact_task
from app import system_mcp_executor
from app.system_mcp_executor import recover_system_mcp_acquisition_operations
from tests.test_runner import _build_media_task_input, _build_mixed_context_task_input


def test_waiting_task_store_should_restore_records_after_reconfiguration(tmp_path) -> None:
    store_path = tmp_path / "waiting-tasks.json"
    task_input = _build_media_task_input("video_summary")

    try:
        configure_waiting_task_store(store_path)
        enqueue_waiting_task(
            task_input=task_input,
            unavailable_capabilities=["EXTRACT_TRANSCRIPT"],
        )
        configure_waiting_task_store(store_path)

        restored = get_waiting_task(task_input.task_id)
        assert restored is not None
        assert restored["status"] == "WAITING_FOR_CAPABILITY"
        assert restored["task_input"]["task_id"] == task_input.task_id
    finally:
        configure_waiting_task_store(None)


def test_waiting_task_store_should_claim_once_and_recover_interrupted_claim(tmp_path) -> None:
    store_path = tmp_path / "waiting-task-claim.json"
    task_input = _build_media_task_input("video_summary")

    try:
        configure_waiting_task_store(store_path)
        enqueue_waiting_task(
            task_input=task_input,
            unavailable_capabilities=["EXTRACT_TRANSCRIPT"],
        )
        capability_wait_queue._claim_waiting_task(task_input.task_id)

        with pytest.raises(ValueError, match="already being resumed"):
            capability_wait_queue._claim_waiting_task(task_input.task_id)

        configure_waiting_task_store(store_path)
        recovered = get_waiting_task(task_input.task_id)
    finally:
        configure_waiting_task_store(None)

    assert recovered is not None
    assert recovered["status"] == "WAITING_FOR_CAPABILITY"
    assert "resume_from_status" not in recovered


def test_file_repository_should_commit_version_and_retrieval_in_one_atomic_state(tmp_path) -> None:
    storage_path = tmp_path / "artifact-repository.json"
    backend = FileArtifactRepositoryBackend(storage_path)

    counts = backend.commit_result(
        {"version_id": "artifact-v1", "target_id": "artifact"},
        {
            "retrieval_entry_id": "retrieval-artifact-v1",
            "version_id": "artifact-v1",
            "target_id": "artifact",
        },
    )
    state = json.loads(storage_path.read_text(encoding="utf-8"))

    assert counts == (1, 1)
    assert [item["version_id"] for item in state["versions"]] == ["artifact-v1"]
    assert [item["version_id"] for item in state["retrieval_entries"]] == ["artifact-v1"]


def test_file_repository_atomic_replace_failure_should_preserve_previous_state(
    tmp_path,
    monkeypatch,
) -> None:
    storage_path = tmp_path / "artifact-repository.json"
    backend = FileArtifactRepositoryBackend(storage_path)
    original_state = storage_path.read_text(encoding="utf-8")
    monkeypatch.setattr(
        "app.artifact_repository.os.replace",
        lambda source, target: (_ for _ in ()).throw(PermissionError("locked")),
    )

    with pytest.raises(PermissionError, match="locked"):
        backend.commit_result(
            {"version_id": "artifact-v1", "target_id": "artifact"},
            {
                "retrieval_entry_id": "retrieval-artifact-v1",
                "version_id": "artifact-v1",
                "target_id": "artifact",
            },
        )

    assert storage_path.read_text(encoding="utf-8") == original_state


def test_file_repository_invalid_json_should_report_state_path(tmp_path) -> None:
    storage_path = tmp_path / "artifact-repository.json"
    backend = FileArtifactRepositoryBackend(storage_path)
    storage_path.write_text("{broken", encoding="utf-8")

    with pytest.raises(ValueError, match="artifact repository state is unreadable") as exc_info:
        backend.info()

    assert str(storage_path) in str(exc_info.value)


def test_acquisition_runtime_store_should_restore_operations_after_reconfiguration(tmp_path) -> None:
    store_path = tmp_path / "acquisition-runtime.json"

    try:
        configure_acquisition_runtime_store(store_path)
        _, result = run_artifact_task(_build_mixed_context_task_input())
        before_restart = list_acquisition_operations(task_id=result.job_snapshot.task_id)
        configure_acquisition_runtime_store(store_path)
        after_restart = list_acquisition_operations(task_id=result.job_snapshot.task_id)

        assert before_restart
        assert after_restart == before_restart
    finally:
        configure_acquisition_runtime_store(None)


def test_debug_routes_should_be_disabled_by_default() -> None:
    request = Request(
        {
            "type": "http",
            "method": "GET",
            "path": "/debug/artifact-skills",
            "raw_path": b"/debug/artifact-skills",
            "query_string": b"",
            "headers": [],
            "server": ("test", 80),
            "client": ("test", 123),
            "scheme": "http",
        }
    )

    response = asyncio.run(protect_debug_routes(request, lambda _: None))

    assert response.status_code == 404


def test_export_download_should_reject_path_traversal() -> None:
    with pytest.raises(HTTPException) as exc_info:
        download_task_export("..", "secret.pdf")

    assert exc_info.value.status_code == 404


def test_restart_recovery_should_redispatch_waiting_system_mcp_operation(monkeypatch) -> None:
    submitted: list[tuple[str, str]] = []
    monkeypatch.setattr(
        "app.system_mcp_executor.list_acquisition_operations",
        lambda: [
            {
                "request_id": "recover-request-1",
                "server_id": "builtin-bilibili-mcp",
                "status": "WAITING_FOR_PROVIDER",
                "callback_status": "WAITING_FOR_PROVIDER",
            }
        ],
    )
    monkeypatch.setattr(
        "app.system_mcp_executor.submit_system_mcp_acquisition_operation",
        lambda request_id, java_base_url=None: submitted.append((request_id, java_base_url)),
    )

    result = recover_system_mcp_acquisition_operations(java_base_url="http://java-host:8081")

    assert result["recovered_count"] == 1
    assert submitted == [("recover-request-1", "http://java-host:8081")]


def test_system_mcp_success_ack_transport_failure_should_not_be_rewritten_as_provider_failure(
    monkeypatch,
) -> None:
    ack_statuses: list[str] = []
    request_id = "system-mcp-success-ack-failure"
    monkeypatch.setattr(
        system_mcp_executor,
        "peek_acquisition_operation",
        lambda _: {
            "request_id": request_id,
            "server_id": "builtin-bilibili-mcp",
            "tool_name": "get_subtitle",
            "callback_token": "callback-token",
        },
    )
    monkeypatch.setattr(system_mcp_executor, "resolve_system_mcp_server", lambda _: object())
    monkeypatch.setattr(
        system_mcp_executor,
        "_call_custom_mcp_tool",
        lambda server, operation: {"selected_subtitle_path": "subtitle.srt"},
    )

    def fail_ack_transport(*, java_base_url, payload):
        ack_statuses.append(str(payload["final_status"]))
        raise RuntimeError("host ack transport unavailable")

    monkeypatch.setattr(system_mcp_executor, "_send_host_ack", fail_ack_transport)
    system_mcp_executor._executor_threads[request_id] = object()

    system_mcp_executor._run_system_mcp_acquisition_operation(
        request_id,
        "http://java-host:8081",
    )

    assert ack_statuses == ["ACKNOWLEDGED"]
    assert request_id not in system_mcp_executor._executor_threads
