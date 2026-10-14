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
