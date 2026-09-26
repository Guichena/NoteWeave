from __future__ import annotations

import io
import json
import urllib.error

import pytest

pytestmark = pytest.mark.usefixtures("fake_default_llm")

from app.models import ResearchTaskInput
from app.planner import build_research_plan
from app.runner import run_research_task
from app.search_adapters import (
    _build_external_search_adapters,
    CompositeSearchAdapter,
    ExternalSearchAdapter,
    HttpGetSearchTransport,
    HttpJsonSearchTransport,
    HttpWikipediaSearchTransport,
    SeedSourceSearchAdapter,
    WorkspaceSearchAdapter,
    build_web_plus_seed_search_adapter,
    build_web_search_adapter,
    build_search_query_plan,
    build_default_search_adapter,
    run_research_search,
)


@pytest.mark.parametrize(
    "transport",
    [
        HttpJsonSearchTransport("serper", "secret", max_retries=3),
        HttpGetSearchTransport("custom", "secret", "https://search.example/api", max_retries=3),
    ],
)
def test_search_transport_should_not_retry_non_retryable_401(monkeypatch, transport) -> None:
    attempts = 0

    def reject(request, timeout):
        nonlocal attempts
        attempts += 1
        raise urllib.error.HTTPError(
            request.full_url, 401, "unauthorized", {}, io.BytesIO(b"unauthorized")
        )

    monkeypatch.setattr("app.search_adapters.credential_safe_urlopen", reject)

    assert transport.search("query", 2) == []
    assert attempts == 1
    assert transport.last_attempt_count == 1


@pytest.mark.parametrize(
    "transport",
    [
        HttpJsonSearchTransport("serper", "secret", max_retries=3),
        HttpGetSearchTransport("custom", "secret", "https://search.example/api", max_retries=3),
    ],
)
def test_search_transport_should_retry_tls_level_os_errors(monkeypatch, transport) -> None:
    attempts = 0

    class Response:
        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

        def read(self):
            return b'{"organic":[]}'

    def flaky_tls(_request, timeout):
        nonlocal attempts
        attempts += 1
        if attempts < 3:
            raise OSError("temporary TLS handshake failure")
        return Response()

    monkeypatch.setattr("app.search_adapters.credential_safe_urlopen", flaky_tls)
    monkeypatch.setattr("app.search_adapters.time.sleep", lambda _seconds: None)

    assert transport.search("query", 2) == []
    assert attempts == 3
    assert transport.last_attempt_count == 3


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
    assert hits[0].source_quality == "WORKSPACE_SOURCE"


def test_web_search_adapter_should_fail_explicitly_without_external_provider(monkeypatch) -> None:
    monkeypatch.delenv("NOTEWEAVE_RESEARCH_PUBLIC_SEARCH_ENABLED", raising=False)
    monkeypatch.delenv("NOTEWEAVE_RESEARCH_SEARCH_API_KEY", raising=False)
    monkeypatch.delenv("SERPER_API_KEY", raising=False)

    with pytest.raises(RuntimeError, match="RESEARCH_WEB_PROVIDER_UNAVAILABLE"):
        build_web_search_adapter()


def test_web_plus_seed_search_adapter_should_fail_explicitly_without_external_provider(monkeypatch) -> None:
    monkeypatch.delenv("NOTEWEAVE_RESEARCH_PUBLIC_SEARCH_ENABLED", raising=False)
    monkeypatch.delenv("NOTEWEAVE_RESEARCH_SEARCH_API_KEY", raising=False)
    monkeypatch.delenv("SERPER_API_KEY", raising=False)

    with pytest.raises(RuntimeError, match="RESEARCH_WEB_PROVIDER_UNAVAILABLE"):
        build_web_plus_seed_search_adapter()


def test_public_wikipedia_search_should_be_available_only_when_explicitly_enabled(monkeypatch) -> None:
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_PUBLIC_SEARCH_ENABLED", "true")
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_SEARCH_PROVIDER_CHAIN", "wikipedia")

    adapter = build_web_search_adapter()

    assert isinstance(adapter, CompositeSearchAdapter)
    assert len(adapter.adapters) == 1
    assert adapter.adapters[0].provider_name == "wikipedia"


def test_generic_search_settings_should_apply_to_first_authenticated_provider(monkeypatch) -> None:
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_PUBLIC_SEARCH_ENABLED", "true")
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_SEARCH_PROVIDER_CHAIN", "wikipedia,serper")
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_SEARCH_API_KEY", "generic-search-secret")
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_SEARCH_BASE_URL", "https://search.example.test")
    for name in (
        "NOTEWEAVE_RESEARCH_SERPER_API_KEY",
        "NOTEWEAVE_RESEARCH_SERPER_BASE_URL",
        "SERPER_API_KEY",
        "SERPER_BASE_URL",
        "SEARCH_API_KEY",
        "SEARCH_API_BASE",
    ):
        monkeypatch.delenv(name, raising=False)

    adapters = _build_external_search_adapters()

    assert [adapter.provider_name for adapter in adapters] == ["wikipedia", "serper"]
    assert adapters[1].api_key == "generic-search-secret"
    assert adapters[1].transport.base_url == "https://search.example.test"


def test_wikipedia_transport_should_normalize_public_api_results(monkeypatch) -> None:
    payload = {
        "query": {
            "search": [{
                "pageid": 42,
                "title": "Event-driven architecture",
                "snippet": "An <span class=\"searchmatch\">event</span> flow.",
            }]
        }
    }

    class Response:
        def __enter__(self):
            return self

        def __exit__(self, *_):
            return None

        def read(self):
            return json.dumps(payload).encode("utf-8")

    monkeypatch.setattr("app.search_adapters.credential_safe_urlopen", lambda *_args, **_kwargs: Response())

    results = HttpWikipediaSearchTransport().search("event flow", 3)

    assert results == [{
        "title": "Event-driven architecture",
        "url": "https://en.wikipedia.org/?curid=42",
        "snippet": "An event flow.",
        "score": 0.78,
    }]


def test_research_search_should_enforce_web_plus_seed_mode_without_silent_seed_fallback(monkeypatch) -> None:
    monkeypatch.delenv("NOTEWEAVE_RESEARCH_PUBLIC_SEARCH_ENABLED", raising=False)
    monkeypatch.delenv("NOTEWEAVE_RESEARCH_SEARCH_API_KEY", raising=False)
    monkeypatch.delenv("SERPER_API_KEY", raising=False)
    task_input = _build_task_input().model_copy(update={"retrieval_mode": "WEB_PLUS_SEEDS"})

    with pytest.raises(RuntimeError, match="RESEARCH_WEB_PROVIDER_UNAVAILABLE"):
        run_research_search(task_input, build_research_plan(task_input))


def test_research_search_should_keep_sources_only_mode_off_the_external_chain(monkeypatch) -> None:
    def fail_if_external_is_built():
        raise AssertionError("SOURCES_ONLY must not inspect or build external providers")

    monkeypatch.setattr("app.search_adapters._build_external_search_adapters", fail_if_external_is_built)
    task_input = _build_task_input().model_copy(update={"retrieval_mode": "SOURCES_ONLY"})

    hits = run_research_search(task_input, build_research_plan(task_input))

    assert [hit.source_id for hit in hits] == ["src-workspace"]


def test_seed_source_adapter_only_matches_explicitly_selected_seed_snapshots() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)

    hits = SeedSourceSearchAdapter().search(task_input, plan)

    assert [hit.source_id for hit in hits] == ["src-workspace"]


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
    assert hits[1].provider_attempts == ["fake-web"]
    assert hits[1].provider_resolution == "fake-web"
    assert hits[1].url == "https://example.com/verify-loop"
    assert hits[1].source_domain == "example.com"
    assert transport.calls[0] == (plan.query_set[0], plan.stop_contract["execution_profile"]["per_query_result_limit"])


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
    assert all(hit.search_lane for hit in hits)
    assert all(hit.retrieval_reason for hit in hits)
    assert all(hit.confidence_score > 0 for hit in hits)
    assert any(hit.search_angle == "coverage_gap" for hit in hits)


def test_build_search_query_plan_should_select_budgeted_query_families_for_deep_profile() -> None:
    payload = _build_task_input().model_dump(mode="json")
    payload["input_payload"]["research_intent"] = {
        "research_goal": "Produce a deep evidence-backed summary.",
        "deliverable_format": "Research brief",
        "constraints": ["Use explicit source evidence."],
        "time_range": "Current cycle",
        "depth": "DEEP",
    }
    task_input = ResearchTaskInput.model_validate(payload)
    plan = build_research_plan(task_input)

    query_plan = build_search_query_plan(plan)

    families = [item.family for item in query_plan]
    execution_profile = plan.stop_contract["execution_profile"]

    assert len(query_plan) <= execution_profile["search_query_budget"]
    assert families[0] == "direct"
    assert "source_scoped" in families
    assert "intent" in families
    assert "deep_focus" in families
    assert "coverage_gap" in families
    assert families.count("constraints") <= execution_profile["query_family_budgets"]["constraints"]
    assert all(item.query in plan.query_set for item in query_plan)


def test_build_search_query_plan_should_prioritize_recovery_target_queries() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    plan.stop_contract["recovery_mode"] = "READ_MORE"
    plan.stop_contract["recovery_target_queries"] = [
        "How should research loops be verified? :: verified evidence search :: Evidence finding",
        "How should research loops be verified? :: direct answer with evidence :: Goal finding",
    ]

    query_plan = build_search_query_plan(plan)

    assert [item.family for item in query_plan] == ["verified_evidence", "direct_evidence"]
    assert [item.query for item in query_plan] == plan.stop_contract["recovery_target_queries"]


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


def test_external_search_adapter_should_only_run_targeted_counterfactual_queries_during_recovery() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    plan.query_set.extend(
        [
            "How should research loops be verified? :: counterfactual recheck against Workspace Evidence",
            "How should research loops be verified? :: broad unrelated expansion",
        ]
    )
    plan.stop_contract["recovery_mode"] = "COUNTERFACTUAL_RECHECK"
    plan.stop_contract["recovery_target_sources"] = ["Workspace Evidence"]
    transport = FakeExternalTransport(
        {
            "How should research loops be verified? :: counterfactual recheck against Workspace Evidence": [
                {
                    "title": "Counterfactual Recovery Result",
                    "url": "https://example.com/counterfactual",
                    "snippet": "Targeted counterfactual evidence.",
                    "score": 0.91,
                }
            ]
        }
    )
    adapter = ExternalSearchAdapter(
        provider_name="fake-web",
        api_key="test-key",
        transport=transport,
    )

    hits = adapter.search(task_input, plan)

    assert len(hits) == 1
    assert hits[0].source_title == "Counterfactual Recovery Result"
    assert transport.calls == [
        (
            "How should research loops be verified? :: counterfactual recheck against Workspace Evidence",
            plan.stop_contract["execution_profile"]["per_query_result_limit"],
        )
    ]


def test_composite_search_adapter_should_record_provider_fallback_chain() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    primary_transport = FakeExternalTransport({})
    backup_transport = FakeExternalTransport(
        {
            plan.query_set[0]: [
                {
                    "title": "Backup Provider Result",
                    "url": "https://backup.example.com/verify-loop",
                    "snippet": "Backup provider found the verifier loop article.",
                    "score": 0.82,
                }
            ]
        }
    )
    adapter = CompositeSearchAdapter(
        [
            WorkspaceSearchAdapter(),
            ExternalSearchAdapter(
                provider_name="primary-web",
                api_key="test-key",
                transport=primary_transport,
            ),
            ExternalSearchAdapter(
                provider_name="backup-web",
                api_key="test-key",
                transport=backup_transport,
            ),
        ]
    )

    hits = adapter.search(task_input, plan)

    external_hit = next(hit for hit in hits if hit.provider == "backup-web")
    assert external_hit.provider_attempts == ["primary-web", "backup-web"]
    assert external_hit.provider_resolution == "backup-web"
    assert external_hit.provider_fallback_reason == "prior providers returned no retained hits"
    assert primary_transport.calls[0] == (plan.query_set[0], plan.stop_contract["execution_profile"]["per_query_result_limit"])
    assert backup_transport.calls[0] == (plan.query_set[0], plan.stop_contract["execution_profile"]["per_query_result_limit"])


def test_external_search_adapter_should_prioritize_requirement_target_queries_during_read_more() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    plan.stop_contract["recovery_mode"] = "READ_MORE"
    plan.stop_contract["recovery_target_requirement_types"] = ["CONSTRAINT_FINDING"]
    plan.stop_contract["recovery_target_queries"] = [
        "How should research loops be verified? :: verified evidence search :: Evidence finding"
    ]
    transport = FakeExternalTransport(
        {
            "How should research loops be verified? :: verified evidence search :: Evidence finding": [
                {
                    "title": "Verified Evidence Result",
                    "url": "https://example.com/verified-evidence",
                    "snippet": "Verified evidence article.",
                    "score": 0.9,
                }
            ]
        }
    )
    adapter = ExternalSearchAdapter(
        provider_name="fake-web",
        api_key="test-key",
        transport=transport,
    )

    hits = adapter.search(task_input, plan)

    assert len(hits) == 1
    assert hits[0].source_title == "Verified Evidence Result"
    assert transport.calls == [
        (
            "How should research loops be verified? :: verified evidence search :: Evidence finding",
            plan.stop_contract["execution_profile"]["per_query_result_limit"],
        )
    ]
