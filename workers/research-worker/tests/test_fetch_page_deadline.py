"""研究提速：单页抓取硬上限与读取渠道回退上限。"""

from __future__ import annotations

import threading
import time

from app.fetch_access import fetch_blocked, reset_fetch_outcomes
from app.fetch_adapters import CompositeFetchAdapter, CompositeUrlSnapshotTransport, UrlFetchAdapter
from app.planner import build_research_plan
from tests.test_fetch_adapters import _build_task_input, _url_hit


class _SlowPagesTransport:
    """指定网址一直卡住（直到测试结束释放），其余网址立即读出正文。"""

    def __init__(self, slow: set[str]) -> None:
        self.slow = slow
        self.release = threading.Event()
        self.read_urls: list[str] = []
        self._lock = threading.Lock()

    def read(self, url: str, query: str, token_budget: int) -> dict[str, object]:
        del query, token_budget
        with self._lock:
            self.read_urls.append(url)
        if url in self.slow:
            self.release.wait(5)
        return {"text": f"Body of {url} about verifier loops.", "snapshot_key": "snapshot:" + url,
                "fetch_attempts": ["HTTP"]}


def test_slow_page_is_abandoned_and_backfilled_in_hit_order() -> None:
    reset_fetch_outcomes()
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    plan.stop_contract["tool_response_retention_budget"] = 2
    hits = [_url_hit(f"hit-{index}", index) for index in range(1, 5)]
    transport = _SlowPagesTransport({"https://example.com/hit-1"})
    adapter = CompositeFetchAdapter([UrlFetchAdapter(transport=transport)], max_concurrency=2,
                                    page_deadline_seconds=0.3)
    try:
        started = time.monotonic()
        documents = adapter.fetch(task_input, plan, hits)
        elapsed = time.monotonic() - started
    finally:
        transport.release.set()

    # 卡住的第一页被放弃，后面的命中补位，结果仍按命中顺序排列
    assert [document.hit_id for document in documents] == ["hit-2", "hit-3"]
    assert elapsed < 3
    assert "https://example.com/hit-4" not in transport.read_urls


def test_repeated_page_timeouts_push_the_site_to_the_back() -> None:
    reset_fetch_outcomes()
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    plan.stop_contract["tool_response_retention_budget"] = 1
    slow_url = "https://example.com/hit-1"
    transport = _SlowPagesTransport({slow_url})
    adapter = CompositeFetchAdapter([UrlFetchAdapter(transport=transport)], max_concurrency=1,
                                    page_deadline_seconds=0.2)
    try:
        for _ in range(2):
            adapter.fetch(task_input, plan, [_url_hit("hit-1", 1)])
    finally:
        transport.release.set()
    assert fetch_blocked(slow_url)
    reset_fetch_outcomes()


def test_without_page_deadline_fetch_keeps_previous_behavior() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    plan.stop_contract["tool_response_retention_budget"] = 2
    hits = [_url_hit(f"hit-{index}", index) for index in range(1, 4)]
    transport = _SlowPagesTransport(set())

    documents = CompositeFetchAdapter([UrlFetchAdapter(transport=transport)], max_concurrency=2).fetch(
        task_input, plan, hits)

    assert [document.hit_id for document in documents] == ["hit-1", "hit-2"]


class _FixedTransport:
    def __init__(self, result: dict[str, object], delay: float = 0.0) -> None:
        self.result = result
        self.delay = delay
        self.calls = 0

    def read(self, url: str, query: str, token_budget: int) -> dict[str, object]:
        del url, query, token_budget
        self.calls += 1
        if self.delay:
            time.sleep(self.delay)
        return dict(self.result)


def test_composite_transport_stops_trying_next_transport_after_deadline() -> None:
    slow_failure = _FixedTransport({"text": "", "fetch_failure_code": "FETCH_RETRY_EXHAUSTED",
                                    "fetch_attempts": ["DEEPSEEK_FETCH"]}, delay=0.15)
    fallback = _FixedTransport({"text": "late body", "fetch_attempts": ["HTTP"]})

    snapshot = CompositeUrlSnapshotTransport([slow_failure, fallback], deadline_seconds=0.1).read(
        "https://example.com/a", "q", 100)

    assert fallback.calls == 0
    assert "FETCH_PAGE_DEADLINE" in str(snapshot["fetch_failure_code"])
    assert not snapshot.get("text")


def test_composite_transport_without_deadline_still_falls_back() -> None:
    failure = _FixedTransport({"text": "", "fetch_failure_code": "FETCH_RETRY_EXHAUSTED",
                               "fetch_attempts": ["DEEPSEEK_FETCH"]})
    fallback = _FixedTransport({"text": "body", "fetch_attempts": ["HTTP"]})

    snapshot = CompositeUrlSnapshotTransport([failure, fallback]).read("https://example.com/a", "q", 100)

    assert snapshot["text"] == "body"
    assert fallback.calls == 1
