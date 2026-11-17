"""网关返回 200 但内容为空或不是 JSON 时重试；被长度上限截断的输出不重试。"""

from __future__ import annotations

import io
import json

import pytest

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


@pytest.mark.parametrize("model", ["deepseek-v4-flash", "deepseek-v4.1-flash", "deepseek-flash"])
@pytest.mark.parametrize("purpose", ["research.plan.matrix", "research.extract", "research.synthesis"])
def test_deepseek_bounded_json_uses_the_output_budget_for_content_instead_of_thinking(monkeypatch, model, purpose):
    requests = []

    def respond(request, **kwargs):
        body = json.loads(request.data)
        requests.append(body)
        direct = body.get("thinking") == {"type": "disabled"}
        content = '{"evidence_cards": []}' if direct else ""
        reply = {"choices": [{"message": {"content": content}, "finish_reason": "stop" if direct else "length"}]}
        return io.BytesIO(json.dumps(reply).encode())

    monkeypatch.setattr("app.llm_client.credential_safe_urlopen", respond)
    client = OpenAICompatibleLlmClient(api_key="test-key", model=model, base_url="http://127.0.0.1:9999/v1",
                                       purpose_options={purpose: {"max_tokens": 3000}})

    assert client.complete_json(purpose, {}) == '{"evidence_cards": []}'
    assert len(requests) == 1
    assert requests[0]["max_tokens"] == 3000


def test_thinking_can_be_explicitly_enabled_without_changing_other_purposes(monkeypatch):
    requests = _provider(monkeypatch, [("{}", "stop")])
    client = OpenAICompatibleLlmClient(api_key="test-key", model="deepseek-v4.1-flash",
                                       base_url="http://127.0.0.1:9999/v1",
                                       purpose_options={"research.extract": {"thinking": "enabled"}})
    client.complete_json("research.extract", {})
    client.complete_json("research.plan.matrix", {})

    assert json.loads(requests[0].data)["thinking"] == {"type": "enabled"}
    assert json.loads(requests[1].data)["thinking"] == {"type": "disabled"}


def test_internal_retries_cannot_exceed_the_remaining_role_budget(monkeypatch):
    requests = _provider(monkeypatch, [("", "stop")])
    client = _client()
    client.max_total_calls = 2
    client.purpose_options["research.plan.matrix"] = {"max_attempts": 3}
    assert client.complete_json("research.plan.matrix", {}) == ""
    assert len(requests) == 2
    assert client.complete_json("research.plan.matrix", {}) == ""
    assert len(requests) == 2
    assert client.usage_summary()["calls"][-1]["termination_reason"] == "CALL_BUDGET_EXHAUSTED"


def test_call_diagnostics_record_output_limit_and_reasoning_token_count(monkeypatch):
    def respond(request, **kwargs):
        return io.BytesIO(json.dumps({
            "choices": [{"message": {"content": ""}, "finish_reason": "length"}],
            "usage": {"completion_tokens": 3000, "completion_tokens_details": {"reasoning_tokens": 3000}},
        }).encode())

    monkeypatch.setattr("app.llm_client.credential_safe_urlopen", respond)
    client = _client()
    assert client.complete_json("research.extract", {}) == ""
    call = client.usage_summary()["calls"][-1]
    assert call["finish_reason"] == "length"
    assert call["reasoning_tokens"] == 3000
