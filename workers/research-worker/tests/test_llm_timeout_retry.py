"""模型等满时限没有响应时按超时处理，重试时把等待时间加倍；其他网络错误按连接失败处理，等待时间不变。"""

from __future__ import annotations

import io
import json

from app.llm_client import OpenAICompatibleLlmClient


def _client(max_attempts: int = 3) -> OpenAICompatibleLlmClient:
    return OpenAICompatibleLlmClient(
        api_key="test-only-key", model="test-model", base_url="http://127.0.0.1:9999/v1",
        timeout_seconds=60, max_attempts=max_attempts,
    )


def _provider(monkeypatch, failures: list[BaseException]) -> list[float]:
    timeouts: list[float] = []

    def respond(request, *, timeout, **kwargs):
        timeouts.append(timeout)
        if len(timeouts) <= len(failures):
            raise failures[len(timeouts) - 1]
        body = {"choices": [{"message": {"content": '{"answering_indices": [0]}'}, "finish_reason": "stop"}]}
        return io.BytesIO(json.dumps(body).encode())

    monkeypatch.setattr("app.llm_client.credential_safe_urlopen", respond)
    monkeypatch.setattr("app.llm_client.time.sleep", lambda _: None)
    return timeouts


def test_retry_after_a_timeout_waits_twice_as_long(monkeypatch) -> None:
    timeouts = _provider(monkeypatch, [TimeoutError("timed out")])
    client = _client()

    assert json.loads(client.complete_json("research.answer_check", {})) == {"answering_indices": [0]}
    assert timeouts == [60, 120]
    assert client.call_records[-1]["termination_reason"] == "SUCCESS"


def test_repeated_timeouts_stop_growing_at_four_times_the_limit(monkeypatch) -> None:
    timeouts = _provider(monkeypatch, [TimeoutError("timed out")] * 4)
    client = _client(max_attempts=4)

    assert client.complete_json("research.answer_check", {}) == ""
    assert timeouts == [60, 120, 240, 240]
    assert client.call_records[-1]["termination_reason"] == "PROVIDER_TIMEOUT"


def test_connection_failure_is_retried_with_the_same_limit(monkeypatch) -> None:
    timeouts = _provider(monkeypatch, [ConnectionResetError("reset"), ConnectionRefusedError("refused")])
    client = _client()

    assert json.loads(client.complete_json("research.answer_check", {})) == {"answering_indices": [0]}
    assert timeouts == [60, 60, 60]


def test_purpose_timeout_is_the_base_for_the_longer_retry(monkeypatch) -> None:
    timeouts = _provider(monkeypatch, [TimeoutError("timed out")])
    client = _client()
    client.purpose_options = {"research.synthesis": {"timeout_seconds": 240, "max_attempts": 2}}

    client.complete_json("research.synthesis", {})
    assert timeouts == [240, 480]
