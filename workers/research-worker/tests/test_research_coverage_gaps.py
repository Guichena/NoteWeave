"""研究要点、检索预算和报告缺口在各阶段之间的回归测试。"""

import io
import json
from types import SimpleNamespace

import pytest

from app.cell_query_planner import CellColumn, CellQueryContext, ensure_authoritative_query, plan_cell_queries
from app.extractor import _window_excerpt
from app.deep_cell_executor import _extract_per_target_column, _prefer_primary_cards, _prioritize_authoritative_hits
from app.extraction_result import ExtractionResult, ExtractionTerminationReason
from app.llm_client import FakeLlmClient
from app.models import ResearchColumn, ResearchPlan, ResearchQuerySpec, ResearchReadWindow, ResearchSchema
from app.narrative_report import NarrativeReportPolisher
from app.planning_executor import validate_plan
from app.query_specs import with_query_specs
from app.repair_queries import plan_repair_queries
from app.search_adapters import CompositeSearchAdapter, DeepSeekSearchTransport
from app.synthesis_executor import SynthesisExecutor
from tests.test_controlled_role_executors import _claim, _command
from tests.test_cell_levels import _card
from tests.test_planning_executor import PLANNING_INPUT, _plan
from tests.test_search_network_failure import _NetworkDownTransport
from tests.test_search_provider_fatal_failure import _Transport, _adapter, _task_input


def test_all_mapped_requirements_reach_the_column_description_without_changing_input():
    raw = _plan(dimensions=[{"label": "Behaviour", "description": "Normal operation"}, {"label": "Types"}])
    raw["required_points"] = [
        {"point": "Ordering during normal operation", "dimension": "Behaviour"},
        {"point": "Ordering after consumer failure", "dimension": "Behaviour"},
        {"point": "Include official source URLs", "dimension": "Types"},
    ]

    result = validate_plan(raw, PLANNING_INPUT)

    description = result.plan["dimensions"][0]["description"]
    assert "Normal operation" in description
    assert "Ordering during normal operation" in description
    assert "Ordering after consumer failure" in description
    assert result.plan["dimensions"][0]["requirements"] == ["Ordering during normal operation", "Ordering after consumer failure"]
    assert raw["dimensions"][0]["description"] == "Normal operation"
    assert "description" not in result.plan["dimensions"][1]


def test_merging_entity_specific_dimensions_preserves_both_evidence_requirements():
    raw = _plan(entities=[{"label": "Atlas"}, {"label": "Boreal"}], dimensions=[
        {"label": "Atlas ordering", "description": "Ordering during normal operation"},
        {"label": "Boreal ordering", "description": "Ordering after consumer failure"},
        {"label": "Types"},
    ])

    result = validate_plan(raw, PLANNING_INPUT)

    description = result.plan["dimensions"][0]["description"]
    assert "Ordering during normal operation" in description
    assert "Ordering after consumer failure" in description


@pytest.mark.parametrize("budget", [1, 3])
def test_official_query_preserves_each_columns_query_and_respects_budget(budget):
    specs = [ResearchQuerySpec(query=f"Atlas aspect {index}", column_keys=[str(index)]) for index in range(budget)]
    context = CellQueryContext("Atlas behaviour", "Atlas", max_queries=budget,
                               authoritative_domains=("atlas.example",))

    result = ensure_authoritative_query(specs, context)

    assert len(result) == budget
    assert {key for spec in result for key in spec.column_keys} == {str(index) for index in range(budget)}
    assert all(any(spec.query.startswith(original.query) for spec in result) for original in specs)
    assert any("site:atlas.example" in spec.query for spec in result)


def test_rule_queries_use_the_planned_search_terms_for_foreign_language_sources():
    context = CellQueryContext("Atlas 的使用前提是什么？", "Atlas", columns=(
        CellColumn("prerequisite", "使用前提", search_intents=("Atlas prerequisites configuration",)),
    ))

    result = plan_cell_queries(context, None)

    targeted = [spec for spec in result.specs if spec.column_keys == ["prerequisite"]]
    assert targeted[0].query == "Atlas prerequisites configuration"


def test_deepseek_query_truncation_keeps_the_official_domain(monkeypatch):
    requests = []

    def respond(request, **kwargs):
        requests.append(json.loads(request.data))
        return io.BytesIO(b'{"results": []}')

    monkeypatch.setattr("app.search_adapters.credential_safe_urlopen", respond)
    query = "Atlas configuration and prerequisites " * 6 + "site:atlas.example"

    DeepSeekSearchTransport("test-key").search(query, 2)

    assert len(requests[0]["query"]) <= 128
    assert requests[0]["query"].startswith("Atlas")
    assert requests[0]["query"].endswith("site:atlas.example")


def test_publisher_repair_keeps_an_unrestricted_query_for_other_copies_of_the_study():
    llm = FakeLlmClient({"research.replan.queries": json.dumps({
        "strategy": "SWITCH_SOURCE_TYPE",
        "queries": ["coverage defects study author manuscript site:publisher.example",
                    "coverage defects empirical results site:publisher.example"],
    })})
    context = CellQueryContext("coverage defects", "coverage", max_queries=2)

    plan, strategy = plan_repair_queries(context, {"repair_cause": "FETCH_FAILED"}, llm)

    assert strategy == "SWITCH_SOURCE_TYPE"
    assert len(plan.specs) == 2
    assert "site:" not in plan.specs[0].query
    assert "site:publisher.example" in plan.specs[1].query


@pytest.mark.parametrize("mode", ["fallback", "merge"])
def test_broad_query_does_not_consume_the_hit_budget_before_specific_queries(mode):
    class QueryTransport(_Transport):
        def __init__(self):
            super().__init__()
            self.queries = []

        def search(self, query, limit, page=1):
            self.queries.append(query)
            return [{"title": f"{query} result {index}", "url": f"https://atlas.example/{query}/{index}", "snippet": query}
                    for index in range(limit)]

    transport = QueryTransport()
    plan = with_query_specs(ResearchPlan(normalized_question="Atlas", stop_contract={
        "global_search_limit": 4, "execution_profile": {"search_query_budget": 2, "per_query_result_limit": 4},
    }), [ResearchQuerySpec(query="broad"), ResearchQuerySpec(query="failure")])
    search = CompositeSearchAdapter([_adapter("deepseek", transport)], mode=mode)

    hits = search.search(_task_input(), plan)

    assert transport.queries == ["broad", "failure"]
    assert len(hits) == 4
    assert {hit.query for hit in hits} == {"broad", "failure"}


@pytest.mark.parametrize("mode", ["fallback", "merge"])
def test_exhausted_backup_does_not_hide_a_retryable_network_failure(mode):
    search = CompositeSearchAdapter([
        _adapter("deepseek", _NetworkDownTransport()),
        _adapter("tavily_mcp", _Transport(fatal="QUOTA_EXHAUSTED")),
    ], mode=mode)

    with pytest.raises(RuntimeError, match="^RESEARCH_WEB_PROVIDER_UNREACHABLE$"):
        search.search(_task_input(), ResearchPlan(normalized_question="Atlas"))


def test_single_column_excerpt_includes_distant_command_and_prerequisite_passages():
    command = "Use atlas_backup --incremental=manifest to create a backup."
    prerequisite = "The summarize_changes setting must be enabled before creating the base backup."
    text = "# Atlas backup\n" + "General overview of the backup system.\n" * 80
    text += command + "\n" + "Unrelated background information.\n" * 140
    text += prerequisite + "\n" + "Other reference material.\n" * 80
    plan = ResearchPlan(normalized_question="How does Atlas incremental backup work?", research_schema=ResearchSchema(
        columns=[ResearchColumn(key="backup", label="Backup workflow", search_intents=[
            "atlas_backup --incremental manifest", "summarize_changes setting enabled base backup",
        ])]))
    window = ResearchReadWindow(window_id="w", hit_id="h", source_id="s", source_title="Atlas",
                                query="Atlas backup", read_focus="backup", window_text=text,
                                retention_reason="reference", token_estimate=2000)

    excerpt = _window_excerpt(plan, window)

    assert command in excerpt
    assert prerequisite in excerpt
    assert len(excerpt) < 5000


def test_report_keeps_known_gaps_and_only_accepts_unanswered_points_from_the_plan():
    point = "Ordering after consumer failure"
    llm = FakeLlmClient({"research.synthesis": json.dumps({
        "title": "Atlas", "executive_summary": "The normal ordering scope is verified.",
        "sections": [{"heading": "Findings", "paragraphs": [{
            "text": "Atlas preserves order within each partition.", "cell_keys": ["order"],
            "evidence_keys": ["evidence:order"],
        }]}],
        "limitations": [],
        "unanswered_points": [point, point, "An invented limitation"],
    })})

    report = NarrativeReportPolisher(llm).polish({
        "question": "How does Atlas preserve order?", "required_points": [point],
        "cells": [{"cell_key": "order", "candidate_value": "Order within each partition",
                   "evidence_keys": ["evidence:order"]}],
        "limitations": ["Only one source was verified."],
    })

    assert report.mode == "LLM"
    assert report.narrative["limitations"] == ["Only one source was verified.",
                                               f"Insufficient evidence to answer: {point}"]
    assert "An invented limitation" not in report.markdown


def test_synthesis_submits_report_gaps_to_the_backend():
    point = "Ordering after failure"
    claim = _claim("SYNTHESIS", {"allowed_tools": [], "synthesis_input": {
        "ledger_digest": "sha256:ledger", "audit_digest": "sha256:audit", "question": "Atlas ordering",
        "required_points": [point], "limitations": [],
        "cells": [{"cell_key": "cell-a", "candidate_value": "Order within a partition",
                   "evidence_keys": ["evidence-a"]}],
    }})
    llm = FakeLlmClient({"research.synthesis": json.dumps({
        "title": "Atlas", "executive_summary": "Partition ordering was verified.",
        "sections": [{"heading": "Findings", "paragraphs": [{
            "text": "Order is preserved within a partition.", "cell_keys": ["cell-a"],
            "evidence_keys": ["evidence-a"],
        }]}], "unanswered_points": [point],
    })})

    completion = SynthesisExecutor("worker-a", llm)(_command(), claim)

    assert completion.role_result["limitations"] == [f"Insufficient evidence to answer: {point}"]
    assert completion.role_result["limitations"] == completion.role_result["narrative"]["limitations"]


def test_fetch_priority_covers_other_queries_before_more_results_from_the_broad_query():
    snapshot = SimpleNamespace(query_policy={"authoritative_domains": ["atlas.example"]})
    hits = [SimpleNamespace(query=query, url=f"https://atlas.example/docs/{index}",
                            source_domain="atlas.example", source_quality="OFFICIAL_DOC", adapter="external_url")
            for index, query in enumerate(["broad", "broad", "broad", "command", "prerequisites"])]

    ordered = _prioritize_authoritative_hits(snapshot, hits)

    assert [hit.query for hit in ordered[:3]] == ["broad", "command", "prerequisites"]
    assert len(ordered) == len(hits)


@pytest.mark.parametrize("url", ["https://mirror.example/docs/17/backup", "https://atlas.example/docs/16/backup",
                                 "https://atlas.example/files/documentation/pdf/19/atlas-19-US.pdf"])
def test_repair_does_not_promote_mirrors_or_another_version_to_required_official_evidence(url):
    snapshot = SimpleNamespace(query_policy={"authoritative_domains": ["atlas.example"], "entity_label": "Atlas 17",
                                            "research_brief": {"current_question": "请引用官方文档"},
                                            "repair_target": {"cell_key": "entity-a:col-a"}})
    windows = [SimpleNamespace(window_id="w", url=url, adapter="external_url", source_domain=url.split('/')[2],
                               source_quality="OFFICIAL_DOC")]

    assert _prefer_primary_cards(snapshot, [_card("w", "a related fact")], windows) == []


@pytest.mark.parametrize("columns", [1, 2])
def test_extraction_gateway_outage_raises_for_task_retry_instead_of_marking_cells_as_gaps(columns):
    extraction = ExtractionResult(termination_reason=ExtractionTerminationReason.INVALID_JSON)
    toolchain = SimpleNamespace(extract=lambda *args, **kwargs: extraction)
    llm = SimpleNamespace(usage_summary=lambda: {"calls": [{"purpose": "research.extract", "attempt_count": 2,
                                                           "termination_reason": "RETRY_EXHAUSTED"}]})
    plan = ResearchPlan(normalized_question="Atlas", research_schema=ResearchSchema(
        columns=[ResearchColumn(key=str(index), label="Aspect") for index in range(columns)]))

    with pytest.raises(RuntimeError, match="^RESEARCH_EXTRACTION_PROVIDER_UNAVAILABLE$"):
        _extract_per_target_column(toolchain, SimpleNamespace(query_policy={}), None, plan, [], llm, lambda: None)


@pytest.mark.parametrize("reason,attempts,finish", [("CALL_BUDGET_EXHAUSTED", 0, ""), ("SUCCESS", 1, "stop"),
                                                   ("SUCCESS", 1, "length")])
def test_budget_exhaustion_and_a_valid_empty_extraction_do_not_become_provider_outages(reason, attempts, finish):
    from app.deep_cell_executor import _require_extraction_provider_available

    llm = SimpleNamespace(usage_summary=lambda: {"calls": [{"purpose": "research.extract", "attempt_count": attempts,
                                                           "termination_reason": reason, "finish_reason": finish}]})
    termination = (ExtractionTerminationReason.INVALID_JSON if attempts == 0 or finish == "length"
                   else ExtractionTerminationReason.MISSING_EVIDENCE_CARDS)
    _require_extraction_provider_available(ExtractionResult(termination_reason=termination), llm)


@pytest.mark.parametrize("reason,retryable", [
    ("RESEARCH_WEB_PROVIDER_UNREACHABLE", True),
    ("RESEARCH_EXTRACTION_PROVIDER_UNAVAILABLE", True),
    ("RESEARCH_WEB_PROVIDER_UNAVAILABLE", False),
    ("unrelated failure", False),
])
def test_consumer_retries_only_the_transient_provider_reason_codes(reason, retryable):
    from app.agent_kafka_consumer import _is_retryable_agent_error

    assert _is_retryable_agent_error(RuntimeError(reason)) is retryable


@pytest.mark.parametrize("path", ["message-id/user-question", "search/?q=backup", "forums/backup"])
def test_official_host_discussions_and_search_results_are_not_official_documentation(path):
    from app.deep_cell_executor import _source_tier

    snapshot = SimpleNamespace(query_policy={"authoritative_domains": ["atlas.example"]})
    item = SimpleNamespace(url=f"https://atlas.example/{path}", source_domain="atlas.example",
                           source_quality="OFFICIAL_DOC", adapter="external_url")
    assert _source_tier(snapshot, item) == 2
