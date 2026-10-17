"""DeepSeek 搜索渠道：请求格式、结果映射、额度用尽识别与渠道链装配。"""

from __future__ import annotations

import io
import json
import urllib.error

from app.search_adapters import DeepSeekSearchTransport, _build_external_search_adapters


class _Response(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


def test_request_and_result_mapping(monkeypatch) -> None:
    seen = {}

    def fake_urlopen(request, timeout):
        seen["url"] = request.full_url
        seen["headers"] = dict(request.header_items())
        seen["body"] = json.loads(request.data.decode("utf-8"))
        return _Response(json.dumps({"results": [{
            "title": "JEP 491", "url": "https://openjdk.org/jeps/491", "snippet": "short",
            "summary": "JEP 491 lets virtual threads release carriers in synchronized blocks.",
            "rerank_score": 2.4, "authority": 4, "date": "2024-10-01T00:00:00Z"}]}).encode("utf-8"))

    monkeypatch.setattr("app.search_adapters.credential_safe_urlopen", fake_urlopen)
    rows = DeepSeekSearchTransport("key-1").search("JEP 491 " + "x" * 200, 5)

    assert seen["url"] == "https://api.deepseek.com/v1/tools/search"
    assert seen["headers"]["Authorization"] == "Bearer key-1"
    assert seen["body"] == {"query": ("JEP 491 " + "x" * 200)[:128], "count": 5}
    assert rows == [{"title": "JEP 491", "url": "https://openjdk.org/jeps/491",
                     "snippet": "JEP 491 lets virtual threads release carriers in synchronized blocks.",
                     "score": 0.8, "date": "2024-10-01T00:00:00Z"}]
    # 没有翻页
    assert DeepSeekSearchTransport("key-1").search("q", 5, page=2) == []


def test_insufficient_balance_is_a_fatal_failure(monkeypatch) -> None:
    def fake_urlopen(request, timeout):
        raise urllib.error.HTTPError(request.full_url, 402, "Payment Required", {},
                                     io.BytesIO(b'{"error":{"message":"Insufficient Balance"}}'))

    monkeypatch.setattr("app.search_adapters.credential_safe_urlopen", fake_urlopen)
    transport = DeepSeekSearchTransport("key-1")

    assert transport.search("q", 5) == []
    assert transport.last_fatal_failure == "QUOTA_EXHAUSTED"


def test_chain_includes_deepseek_only_when_its_key_is_set(monkeypatch) -> None:
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_SEARCH_PROVIDER_CHAIN", "deepseek")
    monkeypatch.delenv("NOTEWEAVE_RESEARCH_DEEPSEEK_SEARCH_API_KEY", raising=False)
    assert _build_external_search_adapters() == []

    monkeypatch.setenv("NOTEWEAVE_RESEARCH_DEEPSEEK_SEARCH_API_KEY", "key-1")
    adapters = _build_external_search_adapters()
    assert [adapter.provider_name for adapter in adapters] == ["deepseek"]
    assert isinstance(adapters[0].transport, DeepSeekSearchTransport)


def test_deepseek_fetch_returns_unescaped_text_through_the_tavily_shaped_client(monkeypatch) -> None:
    from app.fetch_adapters import DeepSeekFetchClient, DeepSeekUrlSnapshotTransport

    seen = {}

    def fake_urlopen(request, timeout):
        seen["body"] = json.loads(request.data.decode("utf-8"))
        return _Response(json.dumps({"title": "JEP 491", "content": r"\# JEP 491\: Synchronize pg\_basebackup"}).encode("utf-8"))

    monkeypatch.setattr("app.fetch_adapters.credential_safe_urlopen", fake_urlopen)
    monkeypatch.setattr("app.fetch_adapters.validate_public_http_url", lambda url: None)
    result = DeepSeekUrlSnapshotTransport(client=DeepSeekFetchClient("key-1")).read(
        "https://openjdk.org/jeps/491", "pinning", 2000)

    assert seen["body"] == {"url": "https://openjdk.org/jeps/491"}
    assert result["fetch_method"] == "DEEPSEEK_FETCH"
    assert result["text"] == "# JEP 491: Synchronize pg_basebackup"


def test_fetch_chain_tries_deepseek_before_tavily(monkeypatch) -> None:
    from app import fetch_adapters

    monkeypatch.setenv("NOTEWEAVE_RESEARCH_DEEPSEEK_SEARCH_API_KEY", "key-1")
    monkeypatch.setattr(fetch_adapters, "default_mcp_search_client", lambda: object())
    transport = fetch_adapters._build_default_url_snapshot_transport()

    names = [type(item).__name__ for item in transport.transports]
    assert names[:2] == ["DeepSeekUrlSnapshotTransport", "TavilyMcpUrlSnapshotTransport"]


def test_blank_titles_and_non_ascii_urls_are_normalized_for_the_archive(monkeypatch) -> None:
    def fake_urlopen(request, timeout):
        return _Response(json.dumps({"results": [
            {"title": "", "url": "https://baike.baidu.com/item/Java虚拟线程 x", "summary": "s"},
            {"title": "T", "url": "ftp://example.com/file", "summary": "s"}]}).encode("utf-8"))

    monkeypatch.setattr("app.search_adapters.credential_safe_urlopen", fake_urlopen)
    rows = DeepSeekSearchTransport("key-1").search("q", 5)

    assert len(rows) == 1
    assert rows[0]["title"] == "baike.baidu.com"
    assert rows[0]["url"] == "https://baike.baidu.com/item/Java%E8%99%9A%E6%8B%9F%E7%BA%BF%E7%A8%8B%20x"


def test_brackets_and_stray_percent_signs_are_encoded() -> None:
    from app.search_adapters import _safe_result_url

    assert _safe_result_url("https://example.com/a[1]/50%off?q=%E4%BD%A0") == \
        "https://example.com/a%5B1%5D/50%25off?q=%E4%BD%A0"
