from __future__ import annotations

import io
import json
import threading
import urllib.error
from concurrent.futures import ThreadPoolExecutor

import pytest

from app.llm_client import (
    FakeLlmClient,
    OpenAICompatibleLlmClient,
    QuoteTamperFaultLlmClient,
    bind_llm_session,
    routing_session_id,
)


def client():
    return OpenAICompatibleLlmClient(
        api_key="test-only-key", model="test-model", base_url="http://127.0.0.1:9999/v1",
        max_attempts=2,
    )


def capture_provider(monkeypatch, *, fail_first=False):
    requests = []
    lock = threading.Lock()

    def respond(request, **kwargs):
        with lock:
            requests.append(request)
            attempt = len(requests)
        if fail_first and attempt == 1:
            raise urllib.error.HTTPError(request.full_url, 503, "retry", {}, io.BytesIO(b"{}"))
        return io.BytesIO(json.dumps({"choices": [{"message": {"content": "{}"}}]}).encode())

    monkeypatch.setattr("app.llm_client.credential_safe_urlopen", respond)
    monkeypatch.setattr("app.llm_client.time.sleep", lambda _: None)
    return requests


def test_research_steps_and_http_retries_reuse_task_session(monkeypatch):
    requests = capture_provider(monkeypatch, fail_first=True)
    bound = bind_llm_session(client(), "research", "workspace-A", "run-A")
    assert bound.complete_json("research.extract", {"evidence": "first"}) == "{}"
    assert bound.complete_json("research.verify", {"evidence": "changed", "history": "compacted"}) == "{}"
    session = routing_session_id("research", "workspace-A", "run-A")
    assert len(requests) == 3
    assert [req.get_header("X-session-id") for req in requests] == [session] * 3
    assert all(req.full_url == "http://127.0.0.1:9999/v1/chat/completions" for req in requests)


def test_parallel_tasks_do_not_mutate_shared_client_or_each_other(monkeypatch):
    requests = capture_provider(monkeypatch)
    shared = client()
    a = bind_llm_session(shared, "research-agent", "workspace-A", "run-A:task-A")
    b = bind_llm_session(shared, "research-agent", "workspace-A", "run-A:task-B")
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(bound.complete_json, purpose, {}) for bound, purpose in [(a, "a"), (b, "b")]]
        assert [future.result() for future in futures] == ["{}", "{}"]
    actual = {json.loads(req.data)["messages"][1]["content"]: req.get_header("X-session-id") for req in requests}
    for payload, session in actual.items():
        task = "task-A" if json.loads(payload)["purpose"] == "a" else "task-B"
        assert session == routing_session_id("research-agent", "workspace-A", f"run-A:{task}")
    assert shared.session_id == "" and shared.call_records == []
    assert a.call_records is not b.call_records


def test_restart_rebind_uses_logical_task_not_attempt_identity():
    first = bind_llm_session(client(), "research-agent", "ws", "run:task")
    restarted = bind_llm_session(client(), "research-agent", "ws", "run:task")
    assert first.session_id == restarted.session_id
    assert first.session_id != bind_llm_session(client(), "research-agent", "other-ws", "run:task").session_id
    assert first.session_id != bind_llm_session(client(), "research-agent", "ws", "run:other-task").session_id
    assert "run:task" not in first.session_id


def test_fault_wrappers_keep_session_and_fakes_remain_compatible(monkeypatch):
    requests = capture_provider(monkeypatch)
    wrapper = QuoteTamperFaultLlmClient(client())
    bound = bind_llm_session(wrapper, "research-agent", "ws", "run:task")
    bound.complete_json("extract", {})
    assert requests[0].get_header("X-session-id") == routing_session_id("research-agent", "ws", "run:task")
    assert wrapper.delegate.session_id == ""
    fake = FakeLlmClient({})
    assert bind_llm_session(fake, "research", "ws", "run") is fake
    assert bind_llm_session(None, "research", "ws", "run") is None


@pytest.mark.parametrize("workspace,stable", [("", "task"), ("ws", " ")])
def test_incomplete_identity_cannot_create_shared_session(workspace, stable):
    with pytest.raises(ValueError):
        routing_session_id("research", workspace, stable)


def test_root_runner_binds_before_starting_research(monkeypatch):
    from types import SimpleNamespace
    import app.runner as runner

    shared = client()
    calls = []
    monkeypatch.setattr(runner, "build_default_llm_client", lambda: shared)
    monkeypatch.setattr(runner, "build_harness", lambda _: SimpleNamespace(plan=lambda *_, **__: (_ for _ in ()).throw(RuntimeError("stop-before-tools"))))
    original = runner.bind_llm_session

    def bind(*args):
        result = original(*args)
        calls.append(result.session_id)
        return result

    monkeypatch.setattr(runner, "bind_llm_session", bind)
    with pytest.raises(RuntimeError, match="stop-before-tools"):
        runner.run_research_task(SimpleNamespace(workspace_id="ws", target_id="run"))
    assert calls == [routing_session_id("research", "ws", "run")]
