"""所有搜索渠道都在网络层失败时不能当作没有资料继续研究，否则得到空报告；改为抛出让任务重试。"""

from __future__ import annotations

import socket
import urllib.error

import pytest

from app import search_adapters
from app.planner import build_research_plan
from app.search_adapters import CompositeSearchAdapter, DeepSeekSearchTransport, ExternalSearchAdapter
from tests.test_search_provider_fatal_failure import _Transport, _adapter, _task_input


class _NetworkDownTransport(_Transport):
    """模拟容器内域名解析失效：每次调用都在网络层失败。"""

    def __init__(self) -> None:
        super().__init__()
        self.last_network_failure = False

    def search(self, query: str, limit: int, page: int = 1) -> list[dict[str, object]]:
        del query, limit, page
        self.calls += 1
        self.last_network_failure = True
        return []


@pytest.mark.parametrize("mode", ["fallback", "merge"])
def test_every_provider_unreachable_raises_instead_of_returning_no_hits(mode) -> None:
    task_input = _task_input()
    composite = CompositeSearchAdapter([
        _adapter("deepseek", _NetworkDownTransport()),
        _adapter("tavily_mcp", _NetworkDownTransport()),
    ], mode=mode)

    with pytest.raises(RuntimeError, match="^RESEARCH_WEB_PROVIDER_UNREACHABLE$"):
        composite.search(task_input, build_research_plan(task_input))


def test_one_reachable_provider_with_empty_results_is_a_real_empty_search() -> None:
    task_input = _task_input()
    composite = CompositeSearchAdapter([
        _adapter("deepseek", _NetworkDownTransport()),
        _adapter("tavily_mcp", _Transport()),
    ], mode="fallback")

    assert composite.search(task_input, build_research_plan(task_input)) == []


def test_reachable_provider_results_are_kept_when_another_is_unreachable() -> None:
    task_input = _task_input()
    working = _Transport(results=[{"title": "JEP 444", "link": "https://openjdk.org/jeps/444",
                                   "snippet": "Virtual threads are final in JDK 21."}])
    composite = CompositeSearchAdapter([
        _adapter("deepseek", _NetworkDownTransport()),
        _adapter("tavily_mcp", working),
    ], mode="fallback")

    hits = composite.search(task_input, build_research_plan(task_input))

    assert [hit.url for hit in hits] == ["https://openjdk.org/jeps/444"]


def test_http_transport_marks_dns_failure_but_not_http_errors(monkeypatch) -> None:
    monkeypatch.setattr(search_adapters.time, "sleep", lambda _: None)
    transport = DeepSeekSearchTransport(api_key="test-only-key", max_retries=2)

    def dns_down(request, **kwargs):
        raise urllib.error.URLError(socket.gaierror(-3, "Temporary failure in name resolution"))

    monkeypatch.setattr(search_adapters, "credential_safe_urlopen", dns_down)
    assert transport.search("coverage threshold", 5) == []
    assert transport.last_network_failure is True

    def server_error(request, **kwargs):
        raise urllib.error.HTTPError(request.full_url, 503, "busy", {}, None)

    monkeypatch.setattr(search_adapters, "credential_safe_urlopen", server_error)
    assert transport.search("coverage threshold", 5) == []
    assert transport.last_network_failure is False


def test_adapter_counts_network_failures_per_query() -> None:
    task_input = _task_input()
    adapter = ExternalSearchAdapter(provider_name="deepseek", transport=_NetworkDownTransport(),
                                    requires_api_key=False)
    adapter.search(task_input, build_research_plan(task_input))
    assert adapter.network_failed_queries > 0
    assert adapter.responded_queries == 0
