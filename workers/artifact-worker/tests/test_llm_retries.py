"""复测发现：部署配置了 NOTEWEAVE_ARTIFACT_LLM_MAX_RETRIES=2，但客户端写死 0 次重试。"""

from app import llm_client
from app.config import load_settings


def test_default_client_uses_configured_retries(monkeypatch):
    monkeypatch.setenv("NOTEWEAVE_ARTIFACT_LLM_API_KEY", "test-key")
    monkeypatch.setenv("NOTEWEAVE_ARTIFACT_LLM_MODEL", "test-model")
    monkeypatch.setenv("NOTEWEAVE_ARTIFACT_LLM_MAX_RETRIES", "3")
    load_settings.cache_clear() if hasattr(load_settings, "cache_clear") else None
    client = llm_client.build_default_llm_client()
    assert client.client.max_retries == 3


def test_retries_default_to_two(monkeypatch):
    monkeypatch.setenv("NOTEWEAVE_ARTIFACT_LLM_API_KEY", "test-key")
    monkeypatch.setenv("NOTEWEAVE_ARTIFACT_LLM_MODEL", "test-model")
    monkeypatch.delenv("NOTEWEAVE_ARTIFACT_LLM_MAX_RETRIES", raising=False)
    load_settings.cache_clear() if hasattr(load_settings, "cache_clear") else None
    client = llm_client.build_default_llm_client()
    assert client.client.max_retries == 2


def test_transport_reports_read_timeout_as_timeout(monkeypatch):
    import httpx
    import pytest

    def slow(request, *, timeout):
        raise TimeoutError("The read operation timed out")

    monkeypatch.setattr(llm_client, "credential_safe_urlopen", slow)
    transport = llm_client._CredentialSafeTransport(1)
    with pytest.raises(httpx.ReadTimeout):
        transport.handle_request(httpx.Request("POST", "https://llm.example/v1/chat/completions", content=b"{}"))
