"""表格缺口、引用归属与成文覆盖的回归测试。"""

from types import SimpleNamespace

import pytest

from app.cell_query_planner import CellColumn, CellQueryContext, plan_cell_queries
from app.deep_cell_executor import _entity_versions, _keep_answering_cards, _retained_cards, _version_mismatch
from app.narrative_report import _cited_text_supported, _normalize_narrative, _validated_cells
from tests.test_cell_levels import _card
from tests.test_cell_query_planner import _SequenceLlm
from tests.test_planning_executor import _executor, _claim, _command, _plan


def test_empty_first_planning_response_is_retried_before_fallback():
    llm = _SequenceLlm("", _plan())
    completion = _executor(llm)(_command(), _claim())
    assert completion.role_result["status"] == "PLANNED"
    assert len(llm.calls) == 2
    assert llm.calls[1][1]["previous_output_rejected"]


def test_planning_reviews_coverage_within_the_existing_two_call_budget():
    first = _plan()
    reviewed = _plan(dimensions=[{"label": "Ordering"}, {"label": "Failure recovery"}])
    llm = _SequenceLlm(first, reviewed)
    completion = _executor(llm)(_command(), _claim())
    assert len(llm.calls) == 2
    assert "candidate_plan_for_review" in llm.calls[1][1]
    assert [column["label"] for column in completion.role_result["plan"]["dimensions"]] == ["Ordering", "Failure recovery"]


def test_a_failed_plan_review_does_not_discard_the_usable_initial_plan():
    llm = _SequenceLlm(_plan(), "not json")
    completion = _executor(llm)(_command(), _claim())
    assert completion.role_result["status"] == "PLANNED"
    assert "PLANNING_REVIEW_INVALID" in completion.role_result["degradation_reasons"]


def test_query_plan_repairs_a_missing_column_even_when_other_queries_are_valid():
    context = CellQueryContext("Atlas ordering", "Atlas", columns=(CellColumn("normal", "Normal ordering"),
                                                                CellColumn("failure", "Failure ordering")))
    llm = _SequenceLlm({"queries": [{"query": "Atlas ordering guarantee", "column_keys": ["normal"]}]},
                       {"queries": [{"query": "Atlas ordering and failover", "column_keys": ["normal", "failure"]}]})
    result = plan_cell_queries(context, llm)
    assert result.method == "LLM"
    assert len(llm.calls) == 2
    assert "failure" in llm.calls[1][1]["previous_output_rejected"][-1]["reason"]
    assert result.specs[0].column_keys == ["normal", "failure"]


def test_rule_queries_cover_all_columns_before_a_broad_query_uses_the_budget():
    context = CellQueryContext("Atlas behaviour", "Atlas", max_queries=3,
                               columns=tuple(CellColumn(str(index), f"Aspect {index}") for index in range(3)))
    result = plan_cell_queries(context, None)
    assert {key for spec in result.specs for key in spec.column_keys} == {"0", "1", "2"}


@pytest.mark.parametrize("label", ["Rust 2024 edition", "Rust edition 2024", "PEP 703", "RFC 9110",
                                   "Git SHA-256 object format", "AES-128 encryption", "HTTP/2", "UTF-8 strings"])
def test_edition_years_and_specification_identifiers_are_not_runtime_versions(label):
    assert not _entity_versions(label)
    assert _version_mismatch(_entity_versions(label), SimpleNamespace(
        url="https://blog.rust-lang.org/2025/02/20/Rust-1.85.0/")) == 0


def test_runtime_versions_still_reject_other_documentation_versions():
    assert _version_mismatch(_entity_versions("PostgreSQL 17"), SimpleNamespace(
        url="https://www.postgresql.org/docs/16/backup.html")) == 1
    assert _entity_versions("OAuth 2.1") == {"2.1"}
    assert _entity_versions("TLS 1.3 handshake") == {"1.3"}


@pytest.mark.parametrize("coverage,complete", [
    ([{"requirement_index": 0, "claim_indices": [0]}, {"requirement_index": 1, "claim_indices": []}], False),
    ([{"requirement_index": 0, "claim_indices": [0]}, {"requirement_index": 1, "claim_indices": [0]}], True),
    ([{"requirement_index": True, "claim_indices": [0]}], False),
    ([], False),
])
def test_legacy_partial_column_answer_is_submitted_for_verification(coverage, complete):
    # 未启用需求覆盖时没有跨轮累计：直接回答了列中某一部分的事实照常提交核验，不降为线索
    snapshot = SimpleNamespace(query_policy={"entity_label": "Atlas"})
    column = SimpleNamespace(key="status", label="Status", description="Initial release status\nCurrent release status")
    card = _card("w", "Atlas was initially experimental")
    llm = _SequenceLlm({"answering_indices": [0], "coverage": coverage})
    partial = []
    accepted = _keep_answering_cards(snapshot, column, [card], llm, partial_out=partial)
    assert accepted == [card]
    assert partial == []
    assert _retained_cards([card], [card], accepted, partial) == []


def test_invalid_answer_check_keeps_the_source_without_verifying_the_cell():
    card = _card("w", "A documented finding")
    partial = []
    accepted = _keep_answering_cards(SimpleNamespace(query_policy={}), SimpleNamespace(label="Status"), [card],
                                     _SequenceLlm("not json"), partial_out=partial)
    assert accepted == []
    assert partial == [card]


def test_structured_requirements_survive_flattening_the_description_between_services():
    from app.deep_cell_executor import _column_labels

    specs = _column_labels({"column_specs": [{"key": "status", "label": "Status", "description": "Initial and current status"}],
                            "column_requirements": {"status": ["Initial status", "Current status"]}})
    column = SimpleNamespace(key="status", **specs["status"])
    llm = _SequenceLlm({"answering_indices": [0], "coverage": [{"requirement_index": 0, "claim_indices": [0]}]})
    partial = []
    card = _card("w", "Initial finding")
    assert _keep_answering_cards(SimpleNamespace(query_policy={}), column, [card], llm, partial_out=partial) == [card]
    assert [item["text"] for item in llm.calls[0][1]["requirements"]] == ["Initial status", "Current status"]
    assert partial == []


def test_answer_check_receives_source_versions_and_dates_for_current_status():
    card = _card("w", "The initial release was experimental")
    window = SimpleNamespace(window_id="w", url="https://atlas.example/docs/1.0/status",
                             source_title="Atlas 1.0", published_at="2020-01-01", updated_at="2020-02-01")
    llm = _SequenceLlm({"answering_indices": [0], "coverage": []})
    _keep_answering_cards(SimpleNamespace(query_policy={}), SimpleNamespace(label="Current maturity"), [card], llm,
                          partial_out=[], windows=[window])
    claim = llm.calls[0][1]["claims"][0]
    assert claim["source_url"] == window.url
    assert claim["published_at"] == "2020-01-01"
    assert llm.calls[0][1]["as_of_date"]


def test_list_excerpts_keep_several_distant_fact_sections_from_one_official_page():
    from app.extractor import _window_excerpt
    from app.models import ResearchColumn, ResearchPlan, ResearchReadWindow, ResearchSchema

    facts = [f"Atlas incompatible changes: item {index} changes the behaviour of feature {index}." for index in range(3)]
    text = "# Atlas edition changes\n" + ("Background prose.\n" * 140).join(facts)
    plan = ResearchPlan(normalized_question="Atlas incompatible changes", research_schema=ResearchSchema(
        columns=[ResearchColumn(key="changes", label="Breaking changes list", search_intents=["Atlas incompatible changes"])]))
    window = ResearchReadWindow(window_id="w", hit_id="h", source_id="s", source_title="Atlas edition",
                                query="Atlas changes", read_focus="changes", window_text=text,
                                retention_reason="reference", token_estimate=2000)
    excerpt = _window_excerpt(plan, window)
    assert all(fact in excerpt for fact in facts)


@pytest.mark.parametrize("metadata", ["Status: Final\nPython-Version: 9.2\nCreated: 2025-01-01\n",
                                     "- Status:\n- Final\n- Python-Version:\n- 9.2\n- Created:\n- 2025-01-01\n"])
def test_selecting_relevant_body_text_keeps_the_specification_status_and_version(metadata):
    from app.extractor import _with_document_metadata

    text = "# A proposal\n" + "Navigation text.\n" * 30 + metadata + "Other prose.\n" * 100
    excerpt = "The proposal describes an optional supported build."
    augmented = _with_document_metadata(text, excerpt)
    assert "Final" in augmented
    assert "9.2" in augmented
    assert excerpt in augmented
    assert "Status: Final" not in _with_document_metadata("No metadata here", excerpt)


@pytest.mark.parametrize("path", ["fr/docs/search", "de/search", "ja/search", "ko_kr/search"])
def test_foreign_language_copies_do_not_use_the_english_and_chinese_source_budget(path):
    from app.search_adapters import _foreign_locale

    assert _foreign_locale("https://atlas.example/" + path)


def _cells():
    return _validated_cells({"cells": [
        {"cell_key": "pg", "entity_label": "PostgreSQL 17", "column_label": "Indexes", "candidate_value": "GIN indexes",
         "evidence_keys": ["evidence:pg"], "evidence_quotes": [{"evidence_key": "evidence:pg", "quote": "GIN indexes in PostgreSQL 17."}]},
        {"cell_key": "mysql", "entity_label": "MySQL 8.4", "column_label": "Indexes", "candidate_value": "Virtual column indexes",
         "evidence_keys": ["evidence:mysql"], "evidence_quotes": [{"evidence_key": "evidence:mysql", "quote": "MySQL 8.4 indexes virtual columns."}]},
    ]})


def _narrative(text="PostgreSQL 17 uses GIN indexes [evidence:pg].", refs=None):
    return {"title": "Index findings", "executive_summary": "Verified findings.",
            "sections": [{"heading": "Findings", "paragraphs": [{"text": text,
                         "cell_keys": ["pg", "mysql"], "evidence_keys": refs or ["evidence:pg"]}]}]}


@pytest.mark.parametrize("summary", ["PostgreSQL 999 supports 999999 indexes [evidence:pg].",
                                     "A fabricated statement [evidence:unknown]."])
def test_summary_cannot_keep_a_claim_after_removing_invalid_citations(summary):
    narrative = _narrative()
    narrative["executive_summary"] = summary
    result = _normalize_narrative(narrative, _cells(), [])
    assert result["executive_summary"] == "PostgreSQL 17 uses GIN indexes [evidence:pg]."
    assert "999" not in result["executive_summary"]
    assert "unknown" not in result["executive_summary"]
    assert result["sections"][0]["paragraphs"][0]["text"] == narrative["sections"][0]["paragraphs"][0]["text"]


@pytest.mark.parametrize("text,refs", [
    ("PostgreSQL indexes virtual columns [evidence:mysql].", ["evidence:mysql"]),
    ("The supported version is 17 [evidence:mysql].", ["evidence:mysql"]),
])
def test_paragraph_cannot_borrow_a_different_entity_or_citations_facts(text, refs):
    result = _normalize_narrative(_narrative(text, refs), _cells(), [])
    # 借来的对象或数字不保留，这句换成所引单元格核验过的结论
    assert result["sections"][0]["paragraphs"][0]["text"] == "Virtual column indexes [evidence:mysql]."
    assert "PostgreSQL indexes" not in str(result) and "version is 17" not in str(result)


def test_paragraph_with_an_unknown_citation_is_not_kept():
    with pytest.raises(ValueError):
        _normalize_narrative(_narrative("A fabricated finding [evidence:unknown] [evidence:pg].", ["evidence:pg"]),
                             _cells(), [])


def test_sentence_borrowing_another_citations_number_is_replaced_by_that_cells_verified_value():
    text = "PostgreSQL 17 uses GIN [evidence:pg]. MySQL supports version 17 [evidence:mysql]."
    result = _normalize_narrative(_narrative(text, ["evidence:pg", "evidence:mysql"]), _cells(), [])
    paragraph = result["sections"][0]["paragraphs"][0]
    # 17 只在 PostgreSQL 的原文里，MySQL 一句借用了它；这句换成 MySQL 单元格核验过的结论，其余照常保留
    assert paragraph["text"] == "PostgreSQL 17 uses GIN [evidence:pg]. Virtual column indexes [evidence:mysql]."
    assert paragraph["cell_keys"] == ["pg", "mysql"]
    assert paragraph["evidence_keys"] == ["evidence:pg", "evidence:mysql"]


def test_unsupported_sentence_is_dropped_when_its_cells_value_is_already_written():
    text = ("PostgreSQL 17 uses GIN [evidence:pg]. Virtual column indexes [evidence:mysql]. "
            "MySQL supports version 17 [evidence:mysql].")
    result = _normalize_narrative(_narrative(text, ["evidence:pg", "evidence:mysql"]), _cells(), [])
    paragraph = result["sections"][0]["paragraphs"][0]
    assert paragraph["text"] == "PostgreSQL 17 uses GIN [evidence:pg]. Virtual column indexes [evidence:mysql]."


def test_missing_body_coverage_is_repaired_with_the_actual_verified_finding():
    result = _normalize_narrative(_narrative(), _cells(), [])
    paragraphs = [paragraph for section in result["sections"] for paragraph in section["paragraphs"]]
    assert paragraphs[0]["cell_keys"] == ["pg"]
    assert paragraphs[1]["cell_keys"] == ["mysql"]
    assert "Virtual column indexes" in paragraphs[1]["text"]


def test_table_row_name_must_match_the_evidence_entity():
    narrative = _narrative()
    narrative["comparison_table"] = {"columns": ["Product", "Finding"], "rows": [
        {"cells": ["PostgreSQL 17", "Virtual columns [evidence:mysql]"],
         "cell_keys": ["pg", "mysql"], "evidence_keys": ["evidence:mysql"]}]}
    result = _normalize_narrative(narrative, _cells(), [])
    assert result["comparison_table"] == {}


def test_table_product_columns_also_constrain_the_citation_entity():
    narrative = _narrative()
    narrative["comparison_table"] = {"columns": ["Aspect", "PostgreSQL 17", "MySQL 8.4"], "rows": [
        {"cells": ["Indexes", "Virtual columns [evidence:mysql]", "GIN [evidence:pg]"],
         "cell_keys": ["pg", "mysql"], "evidence_keys": ["evidence:mysql", "evidence:pg"]}]}
    assert _normalize_narrative(narrative, _cells(), [])["comparison_table"] == {}


def test_missing_comparison_cell_does_not_borrow_another_products_evidence():
    narrative = _narrative()
    table = {"columns": ["Aspect", "PostgreSQL 17", "MySQL 8.4"], "rows": [
        {"cells": ["Indexes", "GIN [evidence:pg]", "-"],
         "cell_keys": ["pg"], "evidence_keys": ["evidence:pg"]}]}
    narrative["comparison_table"] = table
    assert _normalize_narrative(narrative, _cells(), [])["comparison_table"] == table


@pytest.mark.parametrize("text", ["PostgreSQL有999种索引 [evidence:pg]。", "PostgreSQL在999.2版支持该功能 [evidence:pg]。"])
def test_chinese_adjacent_numbers_use_the_same_boundary_rules_as_java(text):
    cells = _cells()
    assert not _cited_text_supported(text, cells, ["evidence:pg"], cells)


def test_version_prefix_is_not_a_new_numeric_fact():
    cells = _validated_cells({"cells": [{"cell_key": "version", "entity_label": "Atlas", "candidate_value": "Version 1.2",
                                       "evidence_keys": ["evidence:version"], "evidence_quotes": [
                                           {"evidence_key": "evidence:version", "quote": "Atlas Version 1.2 supports it."}]}]})
    assert _cited_text_supported("Atlas在v1.2版支持该功能 [evidence:version]。", cells, ["evidence:version"], cells)


@pytest.mark.parametrize("quote", ["Atlasv1.28以降で利用できます", "Atlasv1.28."])
def test_versions_touching_product_names_are_not_truncated_to_the_last_component(quote):
    cells = _validated_cells({"cells": [{"cell_key": "version", "entity_label": "Atlas", "candidate_value": "Atlas v1.28 supports it",
                                       "evidence_keys": ["evidence:version"], "evidence_quotes": [
                                           {"evidence_key": "evidence:version", "quote": quote}]}]})
    assert _cited_text_supported("Atlas v1.28支持该功能 [evidence:version]。", cells, ["evidence:version"], cells)
    assert not _cited_text_supported("Atlas有28个选项 [evidence:version]。", cells, ["evidence:version"], cells)


def test_sentence_final_numbers_are_checked_before_english_punctuation():
    cells = _validated_cells({"cells": [{"cell_key": "count", "candidate_value": "Count is 5.", "evidence_keys": ["evidence:count"],
                                       "evidence_quotes": [{"evidence_key": "evidence:count", "quote": "Count is 5."}]}]})
    assert _cited_text_supported("Count is 5. [evidence:count]", cells, ["evidence:count"], cells)
    assert not _cited_text_supported("Count is 99. [evidence:count]", cells, ["evidence:count"], cells)


def test_cell_value_is_the_claim_the_answer_check_ranks_most_direct():
    from app.deep_cell_executor import _candidate_payload, _trusted_evidence_payload

    detail = _card("w1", "Failed upstreams are skipped for fail_timeout seconds", support=0.95)
    answer = _card("w2", "Round robin is the default load balancing method", support=0.8)
    llm = _SequenceLlm({"answering_indices": [1, 0], "coverage": [{"requirement_index": 0, "claim_indices": [1]}]})

    ranked = _keep_answering_cards(SimpleNamespace(query_policy={}), SimpleNamespace(label="Load balancing method"),
                                   [detail, answer], llm)

    assert ranked == [answer, detail]
    snapshot = SimpleNamespace(task_id="task-1", lease_epoch=1, fencing_token=1,
                               target_cells=[SimpleNamespace(cell_id="entity-a:col-a", expected_version=1)])
    windows = [SimpleNamespace(window_id=window_id, snapshot_status="EXTERNAL_ARCHIVED", query="q", read_focus="f",
                               url=f"https://docs.example/{window_id}", source_domain="docs.example", lineage_digest="")
               for window_id in ("w1", "w2")]
    candidates = _candidate_payload(snapshot, ranked, _trusted_evidence_payload(snapshot, ranked, windows),
                                    windows=windows)
    # 按证据数与抽取可信度挑时会选中可信度更高的旁支事实
    assert [item["candidate_value"] for item in candidates] == [answer.claim_text]
