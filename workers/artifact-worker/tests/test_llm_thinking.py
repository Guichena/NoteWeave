"""产物模型调用的思考模式：deepseek-v4 默认开思考，按 output_schema 输出 JSON 的调用应关闭。"""

import io
import json

from app import llm_client
from app.config import load_settings


def _capture(monkeypatch):
    bodies: list[dict] = []

    def respond(request, *, timeout):
        bodies.append(json.loads(request.data))
        reply = {
            "id": "c1", "object": "chat.completion", "created": 1, "model": "m",
            "choices": [{"index": 0, "message": {"role": "assistant", "content": "{}"}, "finish_reason": "stop"}],
        }
        return io.BytesIO(json.dumps(reply).encode())

    monkeypatch.setattr(llm_client, "credential_safe_urlopen", respond)
    return bodies


def test_deepseek_v4_disables_thinking_by_default(monkeypatch):
    bodies = _capture(monkeypatch)
    client = llm_client.OpenAICompatibleLlmClient(
        api_key="test-key", model="deepseek-v4.1-flash", base_url="http://127.0.0.1:9999/v1", max_retries=0)

    assert client.complete_json("outline", {}) == "{}"
    assert bodies[0]["thinking"] == {"type": "disabled"}


def test_other_models_do_not_receive_thinking_parameter(monkeypatch):
    bodies = _capture(monkeypatch)
    client = llm_client.OpenAICompatibleLlmClient(
        api_key="test-key", model="test-model", base_url="http://127.0.0.1:9999/v1", max_retries=0)

    client.complete_json("outline", {})
    assert "thinking" not in bodies[0]


def test_thinking_policy_is_configurable(monkeypatch):
    monkeypatch.setenv("NOTEWEAVE_ARTIFACT_LLM_API_KEY", "test-key")
    monkeypatch.setenv("NOTEWEAVE_ARTIFACT_LLM_MODEL", "deepseek-v4.1-flash")
    monkeypatch.setenv("NOTEWEAVE_ARTIFACT_LLM_THINKING", "model-default")
    load_settings.cache_clear() if hasattr(load_settings, "cache_clear") else None
    client = llm_client.build_default_llm_client()
    assert client.thinking is None
    assert llm_client.resolve_thinking("enabled", "test-model") == "enabled"
