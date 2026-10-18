"""搜索渠道额度用尽或鉴权失败时，研究应立即失败并给出明确原因，而不是跑完整个流程得到空结论。"""

from __future__ import annotations

import pytest

from app.models import ResearchTaskInput
from app.planner import build_research_plan
from app.search_adapters import (
    CompositeSearchAdapter,
    ExternalSearchAdapter,
    _fatal_provider_failure,
)


def _task_input() -> ResearchTaskInput:
    return ResearchTaskInput.model_validate({
        "task_id": "task-fatal-1",
        "workspace_id": "ws-1",
        "target_id": "run-fatal-1",
        "source_scope": [],
        "control_pack": {
            "pack_type": "research",
            "target_key": "DEFAULT",
            "task_neighborhood": "RESEARCH_DEFAULT",
            "evidence_policy": ["Use traceable evidence only."],
        },
        "input_payload": {"question": "Which JDK release made virtual threads final?", "profile_key": "DEFAULT"},
    })


class _Transport:
    """按预设返回结果；fatal 非空时模拟额度用尽或鉴权失败。"""

    def __init__(self, results: list[dict[str, object]] | None = None, fatal: str = "") -> None:
        self.results = results or []
        self.fatal = fatal
        self.calls = 0
        self.last_attempt_count = 1
        self.last_fatal_failure = ""

    def search(self, query: str, limit: int, page: int = 1) -> list[dict[str, object]]:
        del query, page
        self.calls += 1
        self.last_fatal_failure = self.fatal
        return [] if self.fatal else self.results[:limit]


def _adapter(name: str, transport: _Transport) -> ExternalSearchAdapter:
    return ExternalSearchAdapter(provider_name=name, transport=transport, requires_api_key=False)


@pytest.mark.parametrize(("status", "text", "expected"), [
    (402, "", "QUOTA_EXHAUSTED"),
    (None, 'HTTP 402: {"message":"insufficient quota: total balance $0.000000"}', "QUOTA_EXHAUSTED"),
    (400, '{"message":"Not enough credits","statusCode":400}', "QUOTA_EXHAUSTED"),
    (401, "", "AUTH_FAILED"),
    (None, "HTTP 403: forbidden", "AUTH_FAILED"),
    (429, "rate limited", ""),
    (503, "no capacity available right now; please retry", ""),
    (400, "bad query", ""),
])
def test_fatal_failure_classification(status, text, expected) -> None:
    assert _fatal_provider_failure(status, text) == expected


def test_adapter_stops_sending_queries_after_a_fatal_failure() -> None:
    transport = _Transport(fatal="QUOTA_EXHAUSTED")
    adapter = _adapter("serper_mcp", transport)
    task_input = _task_input()

    hits = adapter.search(task_input, build_research_plan(task_input))

    assert hits == []
    assert transport.calls == 1
    assert adapter.last_fatal_failure == "QUOTA_EXHAUSTED"


def test_all_external_providers_unavailable_fails_the_research() -> None:
    task_input = _task_input()
    composite = CompositeSearchAdapter([
        _adapter("serper_mcp", _Transport(fatal="QUOTA_EXHAUSTED")),
        _adapter("tavily_mcp", _Transport(fatal="AUTH_FAILED")),
    ])

    with pytest.raises(RuntimeError, match="^RESEARCH_WEB_PROVIDER_UNAVAILABLE$"):
        composite.search(task_input, build_research_plan(task_input))


def test_one_working_provider_keeps_the_research_running() -> None:
    task_input = _task_input()
    working = _Transport(results=[{"title": "JEP 444", "link": "https://openjdk.org/jeps/444",
                                   "snippet": "Virtual threads are final in JDK 21."}])
    composite = CompositeSearchAdapter([
        _adapter("serper_mcp", _Transport(fatal="QUOTA_EXHAUSTED")),
        _adapter("tavily_mcp", working),
    ])

    hits = composite.search(task_input, build_research_plan(task_input))

    assert [hit.url for hit in hits] == ["https://openjdk.org/jeps/444"]


def test_empty_results_without_fatal_failure_do_not_fail_the_research() -> None:
    task_input = _task_input()
    composite = CompositeSearchAdapter([
        _adapter("serper_mcp", _Transport()),
        _adapter("tavily_mcp", _Transport()),
    ])

    assert composite.search(task_input, build_research_plan(task_input)) == []


def _hit(url: str) -> dict[str, object]:
    return {"title": url, "link": url, "snippet": "virtual threads JDK 21"}


def test_fallback_mode_stops_after_the_first_provider_with_results() -> None:
    task_input = _task_input()
    serper = _Transport(results=[_hit("https://openjdk.org/jeps/444")])
    tavily = _Transport(results=[_hit("https://example.com/b")])
    composite = CompositeSearchAdapter([_adapter("serper_mcp", serper), _adapter("tavily_mcp", tavily)], mode="fallback")

    hits = composite.search(task_input, build_research_plan(task_input))

    assert [hit.url for hit in hits] == ["https://openjdk.org/jeps/444"]
    assert tavily.calls == 0
    first_round_calls = serper.calls
    assert first_round_calls >= 1


def test_fallback_mode_moves_on_when_the_first_provider_is_empty_and_skips_the_second_pass() -> None:
    task_input = _task_input()
    serper = _Transport()
    tavily = _Transport(results=[_hit("https://example.com/b")])
    composite = CompositeSearchAdapter([_adapter("serper_mcp", serper), _adapter("tavily_mcp", tavily)], mode="fallback")

    hits = composite.search(task_input, build_research_plan(task_input))
    serper_calls, tavily_calls = serper.calls, tavily.calls

    assert [hit.url for hit in hits] == ["https://example.com/b"]
    # 第二轮补充会让两个渠道再各被调用一遍；fallback 模式不应发生
    merge = CompositeSearchAdapter([_adapter("serper_mcp", _Transport()), _adapter("tavily_mcp", _Transport(
        results=[_hit("https://example.com/b")]))])
    merge.search(task_input, build_research_plan(task_input))
    assert serper_calls < merge.adapters[0].transport.calls


def test_unknown_mode_falls_back_to_merge() -> None:
    assert CompositeSearchAdapter([], mode="other").mode == "merge"


class _PerQueryTransport(_Transport):
    """前 empty_calls 次调用没有结果（或 fatal），之后正常返回。"""

    def __init__(self, results, empty_calls: int, fatal_after: int | None = None) -> None:
        super().__init__(results)
        self.empty_calls = empty_calls
        self.fatal_after = fatal_after

    def search(self, query: str, limit: int, page: int = 1) -> list[dict[str, object]]:
        del page
        self.calls += 1
        self.last_fatal_failure = ""
        if self.fatal_after is not None and self.calls > self.fatal_after:
            self.last_fatal_failure = "QUOTA_EXHAUSTED"
            return []
        if self.calls <= self.empty_calls:
            return []
        return [_hit(f"https://deepseek.example/{query[:20].replace(' ', '-')}-{self.calls}")][:limit]


@pytest.fixture
def three_queries(monkeypatch) -> None:
    from app.search_adapters import SearchQueryPlanItem

    items = [SearchQueryPlanItem(query=f"virtual threads q{index}", family="direct", lane="general", priority=index)
             for index in range(1, 4)]
    monkeypatch.setattr("app.search_adapters.build_search_query_plan", lambda plan, remaining_budget=None: items)


def test_fallback_is_decided_per_query_so_the_backup_only_fills_queries_the_primary_missed(three_queries) -> None:
    task_input = _task_input()
    plan = build_research_plan(task_input)
    deepseek = _PerQueryTransport([], empty_calls=1)
    tavily = _Transport(results=[_hit("https://example.com/backup")])
    composite = CompositeSearchAdapter([_adapter("deepseek", deepseek), _adapter("tavily_mcp", tavily)], mode="fallback")

    hits = composite.search(task_input, plan)

    # 第一条检索词 DeepSeek 没有结果，由 Tavily 补位；其余检索词仍只用 DeepSeek
    assert tavily.calls == 1
    assert deepseek.calls == 3
    backup = [hit for hit in hits if hit.provider == "tavily_mcp"]
    assert backup and backup[0].provider_attempts == ["deepseek", "tavily_mcp"]
    assert "deepseek" in backup[0].provider_fallback_reason
    assert any(hit.provider == "deepseek" and hit.provider_attempts == ["deepseek"] for hit in hits)


def test_primary_that_runs_out_of_quota_mid_search_is_not_called_again(three_queries) -> None:
    task_input = _task_input()
    deepseek = _PerQueryTransport([], empty_calls=0, fatal_after=1)
    tavily = _Transport(results=[_hit("https://example.com/backup")])
    composite = CompositeSearchAdapter([_adapter("deepseek", deepseek), _adapter("tavily_mcp", tavily)], mode="fallback")

    hits = composite.search(task_input, build_research_plan(task_input))

    assert deepseek.calls == 2
    assert tavily.calls == 2
    assert {hit.provider for hit in hits} == {"deepseek", "tavily_mcp"}


def test_deepseek_goes_first_whenever_its_key_is_configured(monkeypatch) -> None:
    from app.search_adapters import _build_external_search_adapters

    monkeypatch.setenv("NOTEWEAVE_RESEARCH_SEARCH_PROVIDER_CHAIN", "wikipedia")
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_PUBLIC_SEARCH_ENABLED", "true")
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_DEEPSEEK_SEARCH_API_KEY", "key-1")

    assert [adapter.provider_name for adapter in _build_external_search_adapters()] == ["deepseek", "wikipedia"]
