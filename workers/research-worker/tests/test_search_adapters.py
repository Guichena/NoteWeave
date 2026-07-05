from __future__ import annotations

from app.models import ResearchTaskInput
from app.planner import build_research_plan
from app.runner import run_research_task
from app.search_adapters import (
    CompositeSearchAdapter,
    ExternalSearchAdapter,
    WorkspaceSearchAdapter,
    build_default_search_adapter,
)


class FakeExternalTransport:
    def __init__(self, results_by_query: dict[str, list[dict[str, object]]]) -> None:
        self.results_by_query = results_by_query
        self.calls: list[tuple[str, int]] = []

    def search(self, query: str, limit: int) -> list[dict[str, object]]:
        self.calls.append((query, limit))
        return self.results_by_query.get(query, [])[:limit]


def _build_task_input() -> ResearchTaskInput:
    return ResearchTaskInput.model_validate(
        {
            "task_id": "task-search-adapter-1",
            "workspace_id": "ws-1",
            "target_id": "run-search-adapter-1",
            "source_scope": [
                {
                    "source_id": "src-workspace",
                    "title": "Workspace Evidence",
                    "summary": "Workspace evidence explains verifier driven research loops.",
                }
            ],
            "control_pack": {
                "pack_type": "research",
                "target_key": "DEFAULT",
                "task_neighborhood": "RESEARCH_DEFAULT",
                "evidence_policy": ["Use traceable evidence only."],
            },
            "input_payload": {
                "question": "How should research loops be verified?",
                "profile_key": "DEFAULT",
            },
        }
    )


def test_default_search_adapter_should_use_workspace_only_without_external_key(monkeypatch) -> None:
    monkeypatch.delenv("NOTEWEAVE_RESEARCH_SEARCH_API_KEY", raising=False)
    monkeypatch.delenv("SERPER_API_KEY", raising=False)
    task_input = _build_task_input()
    plan = build_research_plan(task_input)

    adapter = build_default_search_adapter()
    hits = adapter.search(task_input, plan)

    assert len(hits) == 1
    assert hits[0].source_id == "src-workspace"
    assert hits[0].adapter == "workspace"


def test_composite_search_adapter_should_merge_workspace_and_external_hits() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    transport = FakeExternalTransport(
        {
            plan.query_set[0]: [
                {
                    "title": "External Verification Article",
                    "url": "https://example.com/verify-loop",
                    "snippet": "External article describes verifier driven loop recovery.",
                    "score": 0.88,
                }
            ]
        }
    )
    adapter = CompositeSearchAdapter(
        [
            WorkspaceSearchAdapter(),
            ExternalSearchAdapter(
                provider_name="fake-web",
                api_key="test-key",
                transport=transport,
            ),
        ]
    )

    hits = adapter.search(task_input, plan)

    assert [hit.source_title for hit in hits] == [
        "Workspace Evidence",
        "External Verification Article",
    ]
    assert hits[0].adapter == "workspace"
    assert hits[1].adapter == "external"
    assert hits[1].provider == "fake-web"
    assert hits[1].url == "https://example.com/verify-loop"
    assert transport.calls[0] == (plan.query_set[0], 7)


def test_composite_search_adapter_should_dedupe_by_source_url_and_title() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    transport = FakeExternalTransport(
        {
            plan.query_set[0]: [
                {
                    "title": "External Verification Article",
                    "url": "https://example.com/verify-loop",
                    "snippet": "First version.",
                    "score": 0.86,
                },
                {
                    "title": "External Verification Article",
                    "url": "https://example.com/verify-loop",
                    "snippet": "Duplicate by URL.",
                    "score": 0.95,
                },
                {
                    "title": "Workspace Evidence",
                    "url": "",
                    "snippet": "Duplicate by title against workspace hit.",
                    "score": 0.93,
                },
            ]
        }
    )
    adapter = CompositeSearchAdapter(
        [
            WorkspaceSearchAdapter(),
            ExternalSearchAdapter(
                provider_name="fake-web",
                api_key="test-key",
                transport=transport,
            ),
        ]
    )

    hits = adapter.search(task_input, plan)

    assert [hit.source_title for hit in hits] == [
        "Workspace Evidence",
        "External Verification Article",
    ]
    assert hits[1].snippet == "Duplicate by URL."
    assert hits[1].confidence_score == 0.95


def test_search_adapter_hits_should_include_reason_angle_and_confidence() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    transport = FakeExternalTransport(
        {
            plan.query_set[0]: [
                {
                    "title": "Coverage Gap Note",
                    "url": "https://example.com/gap",
                    "snippet": "Coverage gap search result.",
                }
            ],
            plan.query_set[-2]: [
                {
                    "title": "Coverage Gap Note",
                    "url": "https://example.com/gap",
                    "snippet": "Coverage gap search result.",
                }
            ],
        }
    )
    adapter = CompositeSearchAdapter(
        [
            WorkspaceSearchAdapter(),
            ExternalSearchAdapter(
                provider_name="fake-web",
                api_key="test-key",
                transport=transport,
            ),
        ]
    )

    hits = adapter.search(task_input, plan)

    assert all(hit.search_angle for hit in hits)
    assert all(hit.retrieval_reason for hit in hits)
    assert all(hit.confidence_score > 0 for hit in hits)
    assert any(hit.search_angle == "coverage_gap" for hit in hits)


def test_empty_search_results_should_still_trigger_branch_recovery() -> None:
    task_input = ResearchTaskInput.model_validate(
        {
            "task_id": "task-search-empty",
            "workspace_id": "ws-1",
            "target_id": "run-search-empty",
            "source_scope": [],
            "control_pack": {
                "pack_type": "research",
                "target_key": "DEFAULT",
                "task_neighborhood": "RESEARCH_DEFAULT",
            },
            "input_payload": {
                "question": "What if no evidence exists?",
                "profile_key": "DEFAULT",
            },
        }
    )

    _events, result = run_research_task(task_input)

    assert result.result_payload["search_hits"] == []
    assert result.result_payload["branch_decisions"][0]["branch_reason"] == "NO_SEARCH_HITS"
