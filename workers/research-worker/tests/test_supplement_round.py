"""DEEP_CELL 补查一轮：答案检查认为有要求没答到时，只针对这些要求再检索、读取并抽取一次。"""

from __future__ import annotations

import json
from types import SimpleNamespace

from app.deep_cell_executor import (
    DeepCellExecutor,
    _build_task_scope,
    _select_column_cards,
)
from app.extraction_result import ExtractionResult, ExtractionTerminationReason
from app.models import ResearchEvidenceCard
from app.task_snapshot_contract import require_trusted_claim_snapshot
from tests.test_ma4d_deep_cell_executor import _ArchivePermit, _claim, _external_snapshot


class _AnswerCheck:
    """答案检查：结论里提到 manifest 的只回答第一项要求，提到 summarize_wal 的回答第二项。"""

    def __init__(self) -> None:
        self.checks = 0

    def complete_json(self, purpose: str, payload: dict[str, object]) -> str:
        if purpose != "research.answer_check":
            return "not json"
        self.checks += 1
        claims = payload["claims"]
        covering = {0: [item["index"] for item in claims if "manifest" in item["claim"]],
                    1: [item["index"] for item in claims if "summarize_wal" in item["claim"]]}
        return json.dumps({"answering_indices": [item["index"] for item in claims],
                           "coverage": [{"requirement_index": index, "claim_indices": refs}
                                        for index, refs in covering.items() if refs]})


def _card(window_id: str, claim: str) -> ResearchEvidenceCard:
    return ResearchEvidenceCard(
        evidence_id=f"ev-{window_id}", window_id=window_id, source_id=f"src-{window_id}",
        source_title="PostgreSQL docs", claim_text=claim, quote_text=claim, relation_type="SUPPORTS",
        support_score=0.9, conflict_score=0.0, entity_id="entity-1", column_key="method")


class _Toolchain:
    def __init__(self) -> None:
        self.searches: list[list[str]] = []
        self.extracted_columns: list[list[str]] = []

    def search(self, task_input, plan, *, allow_external):
        self.searches.append([spec.query for spec in plan.query_specs])
        self.executed_queries = [spec.query for spec in plan.query_specs]
        return [SimpleNamespace(url="https://docs.example/read-already", query="q", adapter="external_url",
                                source_domain="docs.example", source_quality="OFFICIAL_DOC"),
                SimpleNamespace(url="https://docs.example/runtime-config-wal", query="q", adapter="external_url",
                                source_domain="docs.example", source_quality="OFFICIAL_DOC")]

    def fetch(self, task_input, plan, hits, *, allow_external):
        return [SimpleNamespace(url=hit.url) for hit in hits]

    def read(self, task_input, plan, documents, *, allow_external):
        return [SimpleNamespace(window_id=f"window-{index}", url=document.url, source_id=f"src-{index}",
                                source_title="PostgreSQL docs", provider="fake", adapter="external_url",
                                window_text="summarize_wal must be enabled", snapshot_status="FETCHED",
                                snapshot_archive_ready=True)
                for index, document in enumerate(documents, start=1)]

    def extract(self, task_input, plan, windows, *, llm_client):
        self.extracted_columns.append([column.key for column in plan.research_schema.columns])
        cards = tuple(_card(window.window_id, "summarize_wal must be enabled for incremental backups")
                      for window in windows)
        return ExtractionResult(accepted_cards=cards, termination_reason=ExtractionTerminationReason.ACCEPTED_CARDS)


def test_partly_answered_column_gets_one_targeted_round_and_keeps_its_first_answer() -> None:
    snapshot = require_trusted_claim_snapshot(_claim(_external_snapshot()))
    task_input, plan, _ = _build_task_scope(snapshot)
    column = plan.research_schema.columns[0]
    plan = plan.model_copy(update={"research_schema": plan.research_schema.model_copy(update={"columns": [
        column.model_copy(update={"requirements": ["Backup manifest requirement", "Server setting to enable"]})]})})
    llm = _AnswerCheck()
    first = [_card("w1", "A prior backup manifest must be provided")]
    raw = {column.key: list(first)}
    windows = [SimpleNamespace(window_id="w1", snapshot_status="EXTERNAL_ARCHIVED", query="q", read_focus="f",
                               url="https://docs.example/read-already")]
    uncovered: dict[str, list[str]] = {}
    cache: dict[str, tuple] = {}
    results = [ExtractionResult(accepted_cards=tuple(first), termination_reason=ExtractionTerminationReason.ACCEPTED_CARDS)]
    _select_column_cards(snapshot, plan, results, raw, windows, llm, lambda: None, [], uncovered_out=uncovered,
                         column_cache=cache)
    assert uncovered == {column.key: ["Server setting to enable"]}

    toolchain = _Toolchain()
    executor = DeepCellExecutor(_ArchivePermit(), "worker-a", toolchain=toolchain)
    context = executor.role_factory.create("DEEP_CELL", "execution-1")
    extra = executor._supplement_uncovered(snapshot, task_input, plan, context, None, lambda: None, uncovered,
                                           [SimpleNamespace(url="https://docs.example/read-already")], ["tried query"])

    # 只抓还没读过的网页，窗口编号加前缀避免与第一轮重复，只为没答全的列抽取
    assert [window.window_id for window in extra["windows"]] == ["s2-window-1"]
    assert all(window.snapshot_status == "EXTERNAL_ARCHIVED" for window in extra["windows"])
    assert toolchain.extracted_columns == [[column.key]]
    assert toolchain.searches and "tried query" not in toolchain.searches[0]
    assert context.usage.values["search_calls"] == 1 and context.usage.values["extract_calls"] == 1

    checks_before = llm.checks
    raw[column.key] = first + list(extra["raw_by_column"][column.key])
    _, cards, _ = _select_column_cards(snapshot, plan, results + extra["results"], raw, windows + extra["windows"],
                                       llm, lambda: None, [], column_cache=cache)
    assert llm.checks == checks_before + 1
    assert [card.claim_text for card in cards][0] == "A prior backup manifest must be provided"
    assert any("summarize_wal" in card.claim_text for card in cards)
