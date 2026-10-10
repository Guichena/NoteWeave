from __future__ import annotations

import json
import sys
import time
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


_STDIO_FIXTURE_SERVER = """
import os
import time

import anyio
import mcp.types as types
from mcp.server.lowlevel import Server
from mcp.server.stdio import stdio_server

server = Server("fixture")


@server.list_tools()
async def list_tools():
    return [types.Tool(name=name, description=name, inputSchema={"type": "object"})
            for name in ("get_bilibili_subtitle", "fail", "slow")]


@server.call_tool()
async def call_tool(name, arguments):
    if name == "fail":
        raise ValueError("fixture tool exploded")
    if name == "slow":
        await anyio.sleep(30)
    return {"arguments": arguments, "fixture_env": os.environ.get("FIXTURE_ENV", ""), "cwd": os.getcwd()}


async def main():
    async with stdio_server() as (read_stream, write_stream):
        await server.run(read_stream, write_stream, server.create_initialization_options())


anyio.run(main)
"""


def _stdio_fixture_server(tmp_path) -> SimpleNamespace:
    script = tmp_path / "fixture_server.py"
    script.write_text(_STDIO_FIXTURE_SERVER, encoding="utf-8")
    return SimpleNamespace(
        launch_transport="stdio",
        launch_command=sys.executable,
        launch_args=[str(script)],
        working_directory=str(tmp_path),
        launch_env={"FIXTURE_ENV": "from-registration"},
        server_id="fixture-mcp",
    )


def test_mcp_executor_should_call_stdio_server_through_official_sdk(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(
        custom_mcp_executor, "load_settings", lambda: SimpleNamespace(mcp_process_timeout_seconds=60)
    )

    result = custom_mcp_executor._call_custom_mcp_tool(
        _stdio_fixture_server(tmp_path),
        {
            "tool_name": "get_bilibili_subtitle",
            "input_locator": "https://www.bilibili.com/video/BV1Bounded",
        },
    )

    assert result["arguments"] == {
        "video_url": "https://www.bilibili.com/video/BV1Bounded",
        "fallback_to_transcription": True,
        "allow_auto_subtitles": True,
    }
    assert result["fixture_env"] == "from-registration"
    assert result["cwd"] == str(tmp_path)


def test_mcp_executor_should_surface_tool_errors(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(
        custom_mcp_executor, "load_settings", lambda: SimpleNamespace(mcp_process_timeout_seconds=60)
    )

    with pytest.raises(ValueError, match="fixture tool exploded"):
        custom_mcp_executor._call_custom_mcp_tool(
            _stdio_fixture_server(tmp_path), {"tool_name": "fail", "tool_arguments": {}}
        )


def test_mcp_executor_should_apply_configured_process_timeout(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(
        custom_mcp_executor, "load_settings", lambda: SimpleNamespace(mcp_process_timeout_seconds=3)
    )
    started = time.monotonic()

    with pytest.raises(ValueError):
        custom_mcp_executor._call_custom_mcp_tool(
            _stdio_fixture_server(tmp_path), {"tool_name": "slow", "tool_arguments": {}}
        )

    assert time.monotonic() - started < 20
