from __future__ import annotations

import json
from types import SimpleNamespace

import pytest

from app import custom_mcp_executor, llm_client
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


def test_mcp_executor_should_apply_configured_process_timeout(monkeypatch) -> None:
    captured_timeout: list[int] = []

    def fake_run(command, **kwargs):
        captured_timeout.append(kwargs["timeout"])
        call_message = json.loads(kwargs["input"].splitlines()[1])
        return SimpleNamespace(
            returncode=0,
            stderr="",
            stdout=json.dumps(
                {
                    "jsonrpc": "2.0",
                    "id": call_message["id"],
                    "result": {"structuredContent": {"subtitle_preview": ["bounded"]}},
                }
            ),
        )

    monkeypatch.setattr(custom_mcp_executor.subprocess, "run", fake_run)
    monkeypatch.setattr(
        custom_mcp_executor,
        "load_settings",
        lambda: SimpleNamespace(mcp_process_timeout_seconds=123),
    )
    server = SimpleNamespace(
        launch_transport="stdio",
        launch_command="python",
        launch_args=[],
        working_directory=".",
        launch_env={},
        server_id="test-mcp",
    )

    result = custom_mcp_executor._call_custom_mcp_tool(
        server,
        {
            "tool_name": "get_bilibili_subtitle",
            "input_locator": "https://www.bilibili.com/video/BV1Bounded",
        },
    )

    assert result == {"subtitle_preview": ["bounded"]}
    assert captured_timeout == [123]
