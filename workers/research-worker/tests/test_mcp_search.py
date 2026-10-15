"""搜索代理 MCP：客户端协议、Serper/Tavily 搜索通道与 Tavily 正文读取。"""

from __future__ import annotations

import io
import json
import urllib.error

import pytest

from app import fetch_adapters, mcp_search_client
from app.fetch_adapters import TavilyMcpUrlSnapshotTransport
from app.mcp_search_client import McpSearchClient, McpToolError
from app.search_adapters import McpSearchTransport, _build_external_search_adapters


class _FakeResponse:
    def __init__(self, body: str, headers: dict[str, str]) -> None:
        self._body = body.encode("utf-8")
        self.headers = headers

    def read(self, *_args):  # noqa: ANN201
        return self._body

    def __enter__(self):  # noqa: ANN204
        return self

    def __exit__(self, *_args):  # noqa: ANN204
        return False


class _FakeServer:
    """记录请求并按方法返回预设结果；tools/call 的结果按工具名取。"""

    def __init__(self, tool_results: dict[str, object], *, sse: bool = False, session_id: str = "") -> None:
        self.tool_results = tool_results
        self.sse = sse
        self.session_id = session_id
        self.requests: list[dict[str, object]] = []
        self.headers: list[dict[str, str]] = []

    def __call__(self, request, *, timeout):  # noqa: ANN001, ANN204
        del timeout
        body = json.loads(request.data.decode("utf-8"))
        self.requests.append(body)
        self.headers.append({key.lower(): value for key, value in request.header_items()})
        method = body["method"]
        if method == "notifications/initialized":
            return _FakeResponse("", {})
        if method == "initialize":
            result: object = {"protocolVersion": "2025-03-26", "capabilities": {"tools": {}}}
        else:
            outcome = self.tool_results[body["params"]["name"]]
            if isinstance(outcome, Exception):
                raise outcome
            result = outcome
        payload = json.dumps({"jsonrpc": "2.0", "id": body["id"], "result": result})
        headers = {"Content-Type": "text/event-stream" if self.sse else "application/json"}
        if self.session_id:
            headers["Mcp-Session-Id"] = self.session_id
        return _FakeResponse(f"event: message\ndata: {payload}\n\n" if self.sse else payload, headers)


def _text_result(payload: dict[str, object], *, is_error: bool = False) -> dict[str, object]:
    return {"content": [{"type": "text", "text": json.dumps(payload)}], "isError": is_error}


def _client(monkeypatch, server: _FakeServer) -> McpSearchClient:
    monkeypatch.setattr(mcp_search_client, "credential_safe_urlopen", server)
    return McpSearchClient("https://search.example.test/mcp", "proxy-secret")


def test_client_initializes_once_sends_bearer_and_reuses_session(monkeypatch) -> None:
    server = _FakeServer({"search_proxy_serper_search": _text_result({"organic": []})},
                         sse=True, session_id="session-1")
    client = _client(monkeypatch, server)

    client.call_tool("search_proxy_serper_search", {"q": "a"})
    client.call_tool("search_proxy_serper_search", {"q": "b"})

    assert [request["method"] for request in server.requests] == [
        "initialize", "notifications/initialized", "tools/call", "tools/call"]
    assert all(headers["authorization"] == "Bearer proxy-secret" for headers in server.headers)
    assert server.headers[-1]["mcp-session-id"] == "session-1"


def test_client_marks_capacity_errors_retryable(monkeypatch) -> None:
    server = _FakeServer({"search_proxy_tavily_search": {
        "content": [{"type": "text", "text": "HTTP 503: no capacity available right now; please retry"}],
        "isError": True}})
    client = _client(monkeypatch, server)

    with pytest.raises(McpToolError) as error:
        client.call_tool("search_proxy_tavily_search", {"query": "a"})
    assert error.value.retryable


def test_serper_transport_maps_organic_results_and_paging(monkeypatch) -> None:
    server = _FakeServer({"search_proxy_serper_search": _text_result({"organic": [
        {"title": "GIN Indexes", "link": "https://www.postgresql.org/docs/current/gin.html", "snippet": "jsonb_path_ops"},
    ]})})
    transport = McpSearchTransport("serper_mcp", _client(monkeypatch, server))

    results = transport.search("jsonb gin", 5, page=2)

    assert results[0]["link"] == "https://www.postgresql.org/docs/current/gin.html"
    assert server.requests[-1]["params"]["arguments"] == {"q": "jsonb gin", "num": 5, "page": 2, "autocorrect": False}


def test_tavily_transport_returns_first_page_only(monkeypatch) -> None:
    server = _FakeServer({"search_proxy_tavily_search": _text_result({"results": [
        {"title": "t", "url": "https://example.com/a", "content": "body", "score": 0.8},
    ]})})
    transport = McpSearchTransport("tavily_mcp", _client(monkeypatch, server))

    assert transport.search("q", 30)[0]["url"] == "https://example.com/a"
    assert server.requests[-1]["params"]["arguments"] == {"query": "q", "max_results": 20}
    calls = len(server.requests)
    assert transport.search("q", 5, page=2) == []
    assert len(server.requests) == calls


def test_transport_retries_retryable_errors_then_gives_up(monkeypatch) -> None:
    monkeypatch.setattr("app.search_adapters.time.sleep", lambda _seconds: None)
    server = _FakeServer({"search_proxy_serper_search": urllib.error.HTTPError(
        "https://search.example.test/mcp", 503, "busy", {}, io.BytesIO(b""))})
    transport = McpSearchTransport("serper_mcp", _client(monkeypatch, server), max_retries=3)

    assert transport.search("q", 5) == []
    assert transport.last_attempt_count == 3


def test_provider_chain_builds_mcp_adapters_only_when_configured(monkeypatch) -> None:
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_SEARCH_PROVIDER_CHAIN", "serper_mcp,tavily_mcp")
    monkeypatch.delenv("NOTEWEAVE_RESEARCH_MCP_SEARCH_URL", raising=False)
    monkeypatch.delenv("NOTEWEAVE_RESEARCH_MCP_SEARCH_API_KEY", raising=False)
    assert _build_external_search_adapters() == []

    monkeypatch.setenv("NOTEWEAVE_RESEARCH_MCP_SEARCH_URL", "https://search.example.test/mcp")
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_MCP_SEARCH_API_KEY", "proxy-secret")
    adapters = _build_external_search_adapters()

    assert [adapter.provider_name for adapter in adapters] == ["serper_mcp", "tavily_mcp"]
    assert all(isinstance(adapter.transport, McpSearchTransport) for adapter in adapters)
    assert adapters[0].transport.client is adapters[1].transport.client


def test_tavily_extract_reads_matching_url_as_fetched_snapshot(monkeypatch) -> None:
    monkeypatch.setattr(fetch_adapters, "validate_public_http_url", lambda _url: None)
    url = "https://www.postgresql.org/docs/17/datatype-json.html"
    server = _FakeServer({"search_proxy_tavily_extract": _text_result({"results": [
        {"url": url, "raw_content": "jsonb supports GIN indexes with jsonb_ops and jsonb_path_ops."},
    ], "failed_results": []})})
    transport = TavilyMcpUrlSnapshotTransport(_client(monkeypatch, server))

    snapshot = transport.read(url, "jsonb GIN", 2000)

    assert snapshot["fetch_status"] == "FETCHED"
    assert snapshot["snapshot_status"] == "FETCHED"
    assert snapshot["fetch_method"] == "TAVILY_EXTRACT"
    assert "jsonb_path_ops" in snapshot["text"]


def test_tavily_extract_failure_falls_back_to_snippet(monkeypatch) -> None:
    monkeypatch.setattr(fetch_adapters, "validate_public_http_url", lambda _url: None)
    server = _FakeServer({"search_proxy_tavily_extract": _text_result(
        {"results": [], "failed_results": [{"url": "https://example.com"}]})})
    transport = TavilyMcpUrlSnapshotTransport(_client(monkeypatch, server))

    snapshot = transport.read("https://example.com", "q", 2000)

    assert snapshot["fetch_status"] == "FALLBACK_USED"
    assert snapshot["fetch_failure_code"] == "EMPTY_FETCH_TEXT"
    assert "text" not in snapshot


def test_tavily_extract_rejects_private_targets_before_calling_the_service(monkeypatch) -> None:
    server = _FakeServer({})
    transport = TavilyMcpUrlSnapshotTransport(_client(monkeypatch, server))

    snapshot = transport.read("http://127.0.0.1/admin", "q", 2000)

    assert snapshot["fetch_status"] != "FETCHED"
    assert server.requests == []


def test_rest_client_routes_tools_to_proxy_paths_with_matching_auth(monkeypatch) -> None:
    from app.mcp_search_client import SearchProxyRestClient

    seen: list[tuple[str, dict[str, str], dict[str, object]]] = []

    def fake_urlopen(request, *, timeout):  # noqa: ANN001, ANN202
        del timeout
        seen.append((request.full_url, {k.lower(): v for k, v in request.header_items()},
                     json.loads(request.data.decode("utf-8"))))
        return _FakeResponse(json.dumps({"organic": [], "results": []}), {"Content-Type": "application/json"})

    monkeypatch.setattr(mcp_search_client, "credential_safe_urlopen", fake_urlopen)
    client = SearchProxyRestClient("https://search.example.test/", "proxy-secret")

    client.call_tool("search_proxy_serper_search", {"q": "a"})
    client.call_tool("search_proxy_tavily_search", {"query": "a"})
    client.call_tool("search_proxy_tavily_extract", {"urls": ["https://example.com"]})

    assert [url for url, _, _ in seen] == [
        "https://search.example.test/serper/search",
        "https://search.example.test/tavily/search",
        "https://search.example.test/tavily/extract",
    ]
    assert seen[0][1]["x-api-key"] == "proxy-secret" and "authorization" not in seen[0][1]
    assert seen[1][1]["authorization"] == "Bearer proxy-secret"
    with pytest.raises(McpToolError):
        client.call_tool("search_proxy_exa_search", {"query": "a"})


def test_rest_base_url_takes_precedence_over_mcp(monkeypatch) -> None:
    from app.mcp_search_client import SearchProxyRestClient, default_mcp_search_client

    monkeypatch.setenv("NOTEWEAVE_RESEARCH_MCP_SEARCH_URL", "https://search.example.test/mcp")
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_MCP_SEARCH_API_KEY", "proxy-secret")
    monkeypatch.delenv("NOTEWEAVE_RESEARCH_SEARCH_PROXY_BASE_URL", raising=False)
    assert isinstance(default_mcp_search_client(), McpSearchClient)

    monkeypatch.setenv("NOTEWEAVE_RESEARCH_SEARCH_PROXY_BASE_URL", "https://search.example.test")
    client = default_mcp_search_client()
    assert isinstance(client, SearchProxyRestClient)
    assert client.api_key == "proxy-secret"
