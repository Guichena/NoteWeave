"""拒绝自动读取的站点：抓取时排到最后，定向检索不再限定到这些站点。"""

from __future__ import annotations

from app.cell_query_planner import CellColumn, CellQueryContext, ensure_authoritative_query
from app.fetch_access import fetch_blocked, record_fetch_outcome
from app.fetch_adapters import CompositeFetchAdapter, UrlFetchAdapter
from app.models import ResearchQuerySpec
from app.planner import build_research_plan
from tests.test_fetch_adapters import _BlockedFirstTransport, _build_task_input, _url_hit


def test_publisher_sites_and_repeatedly_denied_hosts_count_as_blocked() -> None:
    assert fetch_blocked("https://dl.acm.org/doi/10.1145/2568225.2568271")
    assert fetch_blocked("www.sciencedirect.com")
    assert not fetch_blocked("https://arxiv.org/abs/2107.08195")

    record_fetch_outcome("https://paywall.example/a", False, "FETCH_REJECTED ; NON_RETRYABLE_HTTP_403")
    assert not fetch_blocked("https://paywall.example/b")
    record_fetch_outcome("https://paywall.example/c", False, "NON_RETRYABLE_HTTP_403")
    assert fetch_blocked("https://paywall.example/d")

    # 读到过正文的站点不算受阻；PDF 等其他失败也不计入
    record_fetch_outcome("https://mixed.example/a", True, "")
    record_fetch_outcome("https://mixed.example/b", False, "NON_RETRYABLE_HTTP_403")
    record_fetch_outcome("https://mixed.example/c", False, "NON_RETRYABLE_HTTP_403")
    record_fetch_outcome("https://pdf.example/a.pdf", False, "UNSUPPORTED_CONTENT_TYPE")
    record_fetch_outcome("https://pdf.example/b.pdf", False, "UNSUPPORTED_CONTENT_TYPE")
    assert not fetch_blocked("https://mixed.example/d")
    assert not fetch_blocked("https://pdf.example/c.pdf")


def test_hits_on_blocked_sites_are_fetched_last() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    plan.stop_contract["tool_response_retention_budget"] = 2
    blocked = _url_hit("acm", 1).model_copy(update={"url": "https://dl.acm.org/doi/10.1145/1"})
    hits = [blocked, _url_hit("hit-2", 2), _url_hit("hit-3", 3)]
    transport = _BlockedFirstTransport({"https://dl.acm.org/doi/10.1145/1"})

    documents = CompositeFetchAdapter([UrlFetchAdapter(transport=transport)]).fetch(task_input, plan, hits)

    assert [document.hit_id for document in documents] == ["hit-2", "hit-3"]
    assert "https://dl.acm.org/doi/10.1145/1" not in transport.read_urls


def test_site_scope_on_a_blocked_publisher_is_dropped_but_the_query_is_kept() -> None:
    context = CellQueryContext(question="结对编程是否提高软件质量？", entity_label="Pair programming",
                               columns=(CellColumn("support", "支持的实证"),),
                               authoritative_domains=("dl.acm.org", "ieeexplore.ieee.org"), max_queries=3)
    specs = [ResearchQuerySpec(query="pair programming defects empirical study site:dl.acm.org", family="direct"),
             ResearchQuerySpec(query="pair programming meta-analysis", family="direct")]

    queries = [spec.query for spec in ensure_authoritative_query(specs, context)]

    assert queries[0] == "pair programming defects empirical study"
    assert not any("dl.acm.org" in query for query in queries)
    assert any(query.endswith("site:ieeexplore.ieee.org") for query in queries)
