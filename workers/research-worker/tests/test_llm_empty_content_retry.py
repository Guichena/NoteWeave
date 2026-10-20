"""网关返回 200 但内容为空或不是 JSON 时重试；被长度上限截断的输出不重试。"""

from __future__ import annotations

import io
import json

from app.llm_client import OpenAICompatibleLlmClient


def _client() -> OpenAICompatibleLlmClient:
    return OpenAICompatibleLlmClient(
        api_key="test-only-key", model="test-model", base_url="http://127.0.0.1:9999/v1", max_attempts=3,
    )


def _provider(monkeypatch, replies: list[tuple[str, str]]) -> list[object]:
    requests: list[object] = []

    def respond(request, **kwargs):
        requests.append(request)
        content, finish = replies[min(len(requests), len(replies)) - 1]
        body = {"choices": [{"message": {"content": content}, "finish_reason": finish}]}
        return io.BytesIO(json.dumps(body).encode())

    monkeypatch.setattr("app.llm_client.credential_safe_urlopen", respond)
    monkeypatch.setattr("app.llm_client.time.sleep", lambda _: None)
    return requests


def test_empty_or_non_json_content_is_retried(monkeypatch) -> None:
    requests = _provider(monkeypatch, [("", "stop"), ("not json", "stop"), ('{"evidence_cards": []}', "stop")])

    assert _client().complete_json("research.extract", {}) == '{"evidence_cards": []}'
    assert len(requests) == 3


def test_last_attempt_returns_whatever_came_back(monkeypatch) -> None:
    requests = _provider(monkeypatch, [("", "stop")])

    assert _client().complete_json("research.extract", {}) == ""
    assert len(requests) == 3


def test_truncated_output_is_not_retried(monkeypatch) -> None:
    requests = _provider(monkeypatch, [('{"sections": [{"title": "a"', "length")])

    assert _client().complete_json("research.synthesize", {}).startswith('{"sections"')
    assert len(requests) == 1
