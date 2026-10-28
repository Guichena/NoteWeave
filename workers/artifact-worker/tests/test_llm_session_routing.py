from __future__ import annotations

import io
import json
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace

import pytest

from app.llm_client import FakeLlmClient, OpenAICompatibleLlmClient, bind_llm_session, routing_session_id


def client():
    return OpenAICompatibleLlmClient(api_key="test-only-key", model="test", base_url="http://127.0.0.1:9999/v1")


def test_artifact_stages_and_resumed_job_reuse_session(monkeypatch):
    requests = []

    def respond(request, **kwargs):
        requests.append(request)
        return io.BytesIO(json.dumps({"choices": [{"message": {"content": "{}"}}]}).encode())

    monkeypatch.setattr("app.llm_client.credential_safe_urlopen", respond)
    first = bind_llm_session(client(), "artifact", "ws", "job-A")
    resumed = bind_llm_session(client(), "artifact", "ws", "job-A")
    first.complete_json("artifact.outline", {"content": "first"})
    first.complete_json("artifact.generate", {"content": "changed"})
    resumed.complete_json("artifact.revise", {"content": "resumed"})
    session = routing_session_id("artifact", "ws", "job-A")
    assert [req.get_header("X-session-id") for req in requests] == [session] * 3
    assert all(req.full_url == "http://127.0.0.1:9999/v1/chat/completions" for req in requests)


def test_parallel_jobs_do_not_change_shared_client(monkeypatch):
    requests = []

    def respond(request, **kwargs):
        requests.append(request)
        return io.BytesIO(b'{"choices":[{"message":{"content":"{}"}}]}')

    monkeypatch.setattr("app.llm_client.credential_safe_urlopen", respond)
    shared = client()
    clients = [bind_llm_session(shared, "artifact", "ws", job) for job in ("job-A", "job-B")]
    with ThreadPoolExecutor(max_workers=2) as pool:
        assert list(pool.map(lambda c: c.complete_json("artifact.generate", {}), clients)) == ["{}", "{}"]
    assert {r.get_header("X-session-id") for r in requests} == {c.session_id for c in clients}
    assert shared.session_id == ""
    assert clients[0].session_id != clients[1].session_id
    assert clients[0].session_id != routing_session_id("artifact", "other-ws", "job-A")


def test_fake_and_disabled_clients_remain_compatible():
    fake = FakeLlmClient({})
    assert bind_llm_session(fake, "artifact", "ws", "job") is fake
    assert bind_llm_session(None, "artifact", "ws", "job") is None
    with pytest.raises(ValueError):
        routing_session_id("artifact", "ws", " ")


def test_runner_binds_job_before_building_generation_plan(monkeypatch):
    import app.pipeline.runner as runner
    from tests.pipeline.test_pipeline import task
    from app.pipeline.skills import SkillCatalog

    calls = []
    original = runner.bind_llm_session

    def bind(*args):
        result = original(*args)
        calls.append(result.session_id)
        raise RuntimeError("stop-before-planning")

    monkeypatch.setattr(runner, "bind_llm_session", bind)
    with pytest.raises(RuntimeError, match="stop-before-planning"):
        value = task("briefing", SkillCatalog())
        value.workspace_id, value.task_id = "ws", "job"
        runner.run(value, client=client())
    assert calls == [routing_session_id("artifact", "ws", "job")]
