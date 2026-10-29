from __future__ import annotations

import json

import pytest

from app import llm_client
from app.io_limits import (
    ContentSizeLimitError,
    MAX_LLM_RESPONSE_BYTES,
    MAX_PROVIDER_PAYLOAD_BYTES,
    MAX_PROVIDER_TEXT_BYTES,
    read_text_file_limited,
    validate_json_payload_size,
)
from app.llm_client import OpenAICompatibleLlmClient


def test_provider_payload_and_text_file_limits_should_reject_oversized_content(tmp_path) -> None:
    with pytest.raises(ContentSizeLimitError, match="provider payload exceeds"):
        validate_json_payload_size(
            {"content": "x" * (MAX_PROVIDER_PAYLOAD_BYTES + 1)},
            label="provider payload",
        )

    text_path = tmp_path / "oversized-subtitle.txt"
    text_path.write_bytes(b"x" * (MAX_PROVIDER_TEXT_BYTES + 1))
    with pytest.raises(ContentSizeLimitError, match="provider text file exceeds"):
        read_text_file_limited(text_path)


def test_llm_client_should_fallback_on_oversized_http_response(monkeypatch) -> None:
    class FakeResponse:
        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, traceback):
            return False

        def read(self, size: int) -> bytes:
            assert size == MAX_LLM_RESPONSE_BYTES + 1
            return b"x" * size

    monkeypatch.setattr(llm_client, "credential_safe_urlopen", lambda req, timeout: FakeResponse())
    client = OpenAICompatibleLlmClient("api-key", "artifact-model")

    assert client.complete_json("artifact.generate", {}) == ""


def test_llm_client_should_send_sdk_request_through_credential_safe_urlopen(monkeypatch) -> None:
    captured: dict[str, object] = {}

    class FakeResponse:
        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, traceback):
            return False

        def read(self, size: int) -> bytes:
            return b'{"choices":[{"message":{"content":"{\\"sections\\":[]}"}}]}'

    def fake_urlopen(req, timeout):
        captured["url"] = req.full_url
        captured["authorization"] = req.get_header("Authorization")
        captured["body"] = json.loads(req.data.decode("utf-8"))
        captured["timeout"] = timeout
        return FakeResponse()

    monkeypatch.setattr(llm_client, "credential_safe_urlopen", fake_urlopen)
    client = OpenAICompatibleLlmClient("api-key", "artifact-model", base_url="https://llm.example/v1", timeout_seconds=9)

    assert client.complete_json("artifact.generate", {}) == '{"sections":[]}'
    assert captured["url"] == "https://llm.example/v1/chat/completions"
    assert captured["authorization"] == "Bearer api-key"
    assert captured["body"]["model"] == "artifact-model"
    assert captured["body"]["response_format"] == {"type": "json_object"}
    assert captured["timeout"] == 9


def test_llm_client_should_fallback_on_provider_http_error(monkeypatch) -> None:
    def reject(req, timeout):
        raise llm_client.urllib.error.HTTPError(req.full_url, 401, "unauthorized", {}, None)

    monkeypatch.setattr(llm_client, "credential_safe_urlopen", reject)
    client = OpenAICompatibleLlmClient("api-key", "artifact-model")

    assert client.complete_json("artifact.generate", {}) == ""


def test_llm_redirect_handler_should_reject_credential_forwarding() -> None:
    request = llm_client.urllib.request.Request(
        "https://llm.example/v1/chat/completions",
        headers={"Authorization": "Bearer secret"},
    )

    with pytest.raises(llm_client.urllib.error.URLError, match="redirect rejected"):
        llm_client.RejectCredentialRedirects().redirect_request(
            request,
            None,
            307,
            "Temporary Redirect",
            {},
            "https://other.example/v1/chat/completions",
        )
