"""搜索代理 MCP 服务的最小客户端（Streamable HTTP，JSON-RPC 2.0）。

只用标准库，经 credential_safe_urlopen 发请求：拒绝重定向，避免把密钥转发到别的地址。
服务端可能返回会话 ID，也可能不返回；返回时后续请求带上，会话失效（404）时重新初始化一次。
"""

from __future__ import annotations

import itertools
import json
import os
import threading
import urllib.error
import urllib.request

from app.http_security import credential_safe_urlopen

PROTOCOL_VERSION = "2025-03-26"
SERPER_SEARCH_TOOL = "search_proxy_serper_search"
TAVILY_SEARCH_TOOL = "search_proxy_tavily_search"
TAVILY_EXTRACT_TOOL = "search_proxy_tavily_extract"


class McpToolError(RuntimeError):
    """工具调用失败；retryable 表示服务端暂时不可用（限流、容量不足、5xx）。"""

    def __init__(self, message: str, *, retryable: bool) -> None:
        super().__init__(message)
        self.retryable = retryable


class McpSearchClient:
    def __init__(self, url: str, api_key: str, timeout_seconds: float = 30) -> None:
        self.url = url.strip()
        self.api_key = api_key.strip()
        self.timeout_seconds = timeout_seconds
        self._session_id: str | None = None
        self._initialized = False
        self._lock = threading.Lock()
        self._ids = itertools.count(1)

    @property
    def configured(self) -> bool:
        return bool(self.url and self.api_key)

    def call_tool(self, name: str, arguments: dict[str, object]) -> dict[str, object]:
        """调用工具并返回解析后的 JSON 结果。"""
        self._ensure_initialized()
        try:
            response = self._rpc("tools/call", {"name": name, "arguments": arguments})
        except urllib.error.HTTPError as error:
            if error.code != 404 or not self._session_id:
                raise
            # 会话过期，重新初始化后重试一次
            with self._lock:
                self._initialized = False
                self._session_id = None
            self._ensure_initialized()
            response = self._rpc("tools/call", {"name": name, "arguments": arguments})
        if "error" in response:
            error = response.get("error") or {}
            raise McpToolError(str(error.get("message") or "MCP error"), retryable=False)
        result = response.get("result") or {}
        text = "".join(
            str(item.get("text") or "")
            for item in result.get("content") or []
            if isinstance(item, dict) and item.get("type") == "text"
        )
        if result.get("isError"):
            raise McpToolError(text[:300] or "MCP tool error", retryable=_is_retryable_tool_error(text))
        structured = result.get("structuredContent")
        if isinstance(structured, dict):
            return structured
        try:
            payload = json.loads(text)
        except ValueError as error:
            raise McpToolError("MCP tool returned non-JSON content", retryable=False) from error
        if not isinstance(payload, dict):
            raise McpToolError("MCP tool returned a non-object payload", retryable=False)
        return payload

    def _ensure_initialized(self) -> None:
        with self._lock:
            if self._initialized:
                return
            self._rpc("initialize", {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {},
                "clientInfo": {"name": "noteweave-research-worker", "version": "0.1"},
            })
            self._rpc("notifications/initialized", None, notify=True)
            self._initialized = True

    def _rpc(self, method: str, params: dict[str, object] | None, *, notify: bool = False) -> dict[str, object]:
        body: dict[str, object] = {"jsonrpc": "2.0", "method": method}
        if not notify:
            body["id"] = next(self._ids)
        if params is not None:
            body["params"] = params
        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
            "Authorization": f"Bearer {self.api_key}",
        }
        if self._session_id:
            headers["Mcp-Session-Id"] = self._session_id
        request = urllib.request.Request(
            self.url, data=json.dumps(body).encode("utf-8"), headers=headers, method="POST"
        )
        with credential_safe_urlopen(request, timeout=self.timeout_seconds) as response:
            session_id = response.headers.get("Mcp-Session-Id") if response.headers else None
            if session_id:
                self._session_id = session_id
            raw = response.read().decode("utf-8", "replace")
            content_type = (response.headers.get("Content-Type") or "") if response.headers else ""
        if notify:
            return {}
        return _decode_rpc_body(raw, content_type)


def _decode_rpc_body(raw: str, content_type: str) -> dict[str, object]:
    text = raw.strip()
    if "event-stream" in content_type or text.startswith(("event:", "data:")):
        data_lines = [line[5:].strip() for line in text.splitlines() if line.startswith("data:")]
        text = data_lines[-1] if data_lines else ""
    payload = json.loads(text) if text else {}
    return payload if isinstance(payload, dict) else {}


def _is_retryable_tool_error(text: str) -> bool:
    lowered = text.lower()
    return any(marker in lowered for marker in (
        "http 429", "http 500", "http 502", "http 503", "http 504", "capacity", "retry", "timeout",
    ))


# 搜索代理的 REST 接口与 Serper、Tavily 官方接口兼容：(路径, 鉴权方式)
_REST_ROUTES: dict[str, tuple[str, str]] = {
    SERPER_SEARCH_TOOL: ("/serper/search", "x-api-key"),
    TAVILY_SEARCH_TOOL: ("/tavily/search", "bearer"),
    TAVILY_EXTRACT_TOOL: ("/tavily/extract", "bearer"),
}


class SearchProxyRestClient:
    """搜索代理的 REST 客户端，接口与 McpSearchClient 相同，省去 MCP 的会话初始化。

    HTTP 错误原样抛出 HTTPError，由调用方按状态码与响应正文判断是否可重试、是否额度用尽。
    """

    def __init__(self, base_url: str, api_key: str, timeout_seconds: float = 30) -> None:
        self.url = base_url.strip().rstrip("/")
        self.api_key = api_key.strip()
        self.timeout_seconds = timeout_seconds

    @property
    def configured(self) -> bool:
        return bool(self.url and self.api_key)

    def call_tool(self, name: str, arguments: dict[str, object]) -> dict[str, object]:
        route = _REST_ROUTES.get(name)
        if route is None:
            raise McpToolError(f"unsupported search proxy tool: {name}", retryable=False)
        path, auth = route
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if auth == "x-api-key":
            headers["X-API-KEY"] = self.api_key
        else:
            headers["Authorization"] = f"Bearer {self.api_key}"
        request = urllib.request.Request(
            self.url + path, data=json.dumps(arguments).encode("utf-8"), headers=headers, method="POST"
        )
        with credential_safe_urlopen(request, timeout=self.timeout_seconds) as response:
            payload = json.loads(response.read().decode("utf-8") or "{}")
        if not isinstance(payload, dict):
            raise McpToolError("search proxy returned a non-object payload", retryable=False)
        return payload


_default_client: McpSearchClient | SearchProxyRestClient | None = None
_default_lock = threading.Lock()


def default_mcp_search_client() -> McpSearchClient | SearchProxyRestClient | None:
    """按环境变量构造进程内共享的搜索代理客户端；未配置地址或密钥时返回 None。

    配置了 NOTEWEAVE_RESEARCH_SEARCH_PROXY_BASE_URL 时用 REST 接口，否则用 MCP 接口；
    密钥优先取 NOTEWEAVE_RESEARCH_SEARCH_PROXY_API_KEY，未配置时沿用 MCP 的密钥。
    """
    global _default_client
    rest_url = os.getenv("NOTEWEAVE_RESEARCH_SEARCH_PROXY_BASE_URL", "").strip()
    mcp_url = os.getenv("NOTEWEAVE_RESEARCH_MCP_SEARCH_URL", "").strip()
    api_key = (os.getenv("NOTEWEAVE_RESEARCH_SEARCH_PROXY_API_KEY", "").strip()
               or os.getenv("NOTEWEAVE_RESEARCH_MCP_SEARCH_API_KEY", "").strip())
    url = rest_url or mcp_url
    if not url or not api_key:
        return None
    client_type = SearchProxyRestClient if rest_url else McpSearchClient
    with _default_lock:
        current = _default_client
        if (not isinstance(current, client_type) or current.url.rstrip("/") != url.rstrip("/")
                or current.api_key != api_key):
            _default_client = client_type(url, api_key)
        return _default_client
