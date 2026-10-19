"""单元格分级：缺口单元格保留低级别材料，成文可以注明未经核实后引用有出处的线索。"""

from __future__ import annotations

import json
from types import SimpleNamespace

from app.deep_cell_executor import _retained_cards, _retained_payload
from app.models import ResearchEvidenceCard
from app.narrative_report import NarrativeReportPolisher, _comparison_table_required


class FakeLlmClient:
    def __init__(self, responses: dict[str, str]) -> None:
        self.responses = responses
        self.payloads: list[dict[str, object]] = []

    def complete_json(self, purpose: str, payload: dict[str, object]) -> str:
        self.payloads.append(payload)
        return self.responses[purpose]


def _card(window_id: str, claim: str, support: float = 0.8) -> ResearchEvidenceCard:
    return ResearchEvidenceCard(
        evidence_id="ev-" + window_id, window_id=window_id, source_id="src-" + window_id,
        source_title="Title " + window_id, claim_text=claim, quote_text="Quote " + claim,
        relation_type="SUPPORTS", support_score=support, conflict_score=0.0,
        entity_id="entity-a", column_key="col-a")


def test_cards_dropped_only_for_source_tier_are_kept_as_sourced_material() -> None:
    raw = [_card("w1", "secondhand answer", support=0.0)]

    kept = _retained_cards(raw, [], [])

    assert [card.relation_type for card in kept] == ["WEAK_SUPPORT"]
    assert kept[0].support_score > 0


def test_cards_judged_not_answering_are_kept_as_related_material() -> None:
    primary = [_card("w1", "background only")]

    kept = _retained_cards(primary, primary, [])

    assert kept[0].relation_type == "WEAK_SUPPORT"
    assert kept[0].support_score == 0.0


def test_nothing_is_retained_when_the_cell_has_an_answer() -> None:
    card = _card("w1", "answer")

    assert _retained_cards([card], [card], [card]) == []


def _snapshot(quorum: int = 1) -> SimpleNamespace:
    return SimpleNamespace(
        candidate_quorum=quorum, task_id="task-1", lease_epoch=1, fencing_token=1,
        target_cells=[SimpleNamespace(cell_id="entity-a:col-a", expected_version=3)])


def _window(window_id: str) -> SimpleNamespace:
    return SimpleNamespace(window_id=window_id, snapshot_status="EXTERNAL_ARCHIVED", query="q", read_focus="focus")


def test_retained_material_becomes_one_weak_candidate_per_gap_cell() -> None:
    cards = _retained_cards([_card("w1", "first"), _card("w2", "second")], [], [])

    evidence, candidates = _retained_payload(_snapshot(), cards, [_window("w1"), _window("w2")], [], [])

    assert [item["relation_type"] for item in evidence] == ["WEAK_SUPPORT", "WEAK_SUPPORT"]
    assert len(candidates) == 1
    assert candidates[0]["cell_key"] == "entity-a:col-a"
    assert candidates[0]["base_cell_version"] == 3
    assert sorted(candidates[0]["evidence_keys"]) == sorted(item["evidence_key"] for item in evidence)


def test_quorum_tasks_and_cells_with_a_candidate_submit_no_weak_material() -> None:
    cards = _retained_cards([_card("w1", "first")], [], [])
    windows = [_window("w1")]

    assert _retained_payload(_snapshot(quorum=2), cards, windows, [], []) == ([], [])
    assert _retained_payload(_snapshot(), cards, windows, [], [{"cell_key": "entity-a:col-a"}]) == ([], [])


def test_weak_material_respects_the_completion_evidence_limit() -> None:
    cards = _retained_cards([_card("w1", "first")], [], [])
    full = [{"evidence_key": f"evidence:{index}"} for index in range(12)]

    assert _retained_payload(_snapshot(), cards, [_window("w1")], full, []) == ([], [])


def test_comparison_table_requirement_depends_on_subjects_not_product_names() -> None:
    cells = [{"entity_label": "Kafka"}, {"entity_label": "Pulsar"}]

    assert _comparison_table_required("比较 Kafka 与 Pulsar 的消息顺序", cells)
    assert not _comparison_table_required("比较 Kafka 与 Pulsar 的消息顺序", cells[:1])
    assert not _comparison_table_required("Kafka 的消息顺序保证", cells)


def _synthesis_input() -> dict[str, object]:
    return {
        "question": "比较 Kafka 与 Pulsar 的消息顺序",
        "cells": [{"cell_key": "pulsar:order", "candidate_value": "Pulsar 在 Exclusive 订阅下按顺序消费",
                   "evidence_keys": ["evidence:p1"], "guarded": False, "entity_label": "Pulsar",
                   "column_label": "顺序保证", "supporting_quotes": ["Exclusive subscription keeps order"]}],
        "leads": [{"cell_key": "kafka:order", "candidate_value": "Kafka 只保证分区内有序", "evidence_level": "SOURCED",
                   "evidence_keys": ["evidence:k1"], "entity_label": "Kafka", "column_label": "顺序保证",
                   "supporting_quotes": ["[Blog] Kafka only guarantees order within a partition"]}],
        "limitations": [],
    }


def test_writer_receives_leads_and_unmarked_lead_sentences_get_an_unverified_note() -> None:
    client = FakeLlmClient({"research.synthesis": json.dumps({
        "title": "Kafka 与 Pulsar 的消息顺序",
        "executive_summary": "Pulsar 在 Exclusive 订阅下按顺序消费 [evidence:p1] [evidence:k1]。",
        "sections": [{"heading": "直接回答", "paragraphs": [
            {"text": "Pulsar 在 Exclusive 订阅下按顺序消费 [evidence:p1]。", "cell_keys": ["pulsar:order"],
             "evidence_keys": ["evidence:p1"]},
            {"text": "Kafka 只保证分区内有序 [evidence:k1]。", "cell_keys": ["kafka:order"],
             "evidence_keys": ["evidence:k1"]},
        ]}],
        "comparison_table": {},
        "limitations": [],
    })})

    result = NarrativeReportPolisher(client).polish(_synthesis_input())

    assert client.payloads[0]["tentative_cells"][0]["cell_key"] == "kafka:order"
    assert result.mode == "LLM"
    # 摘要只保留已核验的引用
    assert "[evidence:k1]" not in result.narrative["executive_summary"]
    paragraph = result.narrative["sections"][0]["paragraphs"][1]
    assert paragraph["text"].startswith("Kafka 只保证分区内有序（未经核实） [evidence:k1]")
    # 必须给出的对比表里，没核验的位置用线索补上并标明待核实
    row = result.narrative["comparison_table"]["rows"][0]
    assert any("（待核实）" in cell and "[evidence:k1]" in cell for cell in row["cells"])


def test_lead_sentence_that_already_says_unverified_is_kept_as_written() -> None:
    client = FakeLlmClient({"research.synthesis": json.dumps({
        "title": "Kafka 与 Pulsar 的消息顺序",
        "executive_summary": "Pulsar 在 Exclusive 订阅下按顺序消费 [evidence:p1]。",
        "sections": [{"heading": "直接回答", "paragraphs": [
            {"text": "Pulsar 在 Exclusive 订阅下按顺序消费 [evidence:p1]。", "cell_keys": ["pulsar:order"],
             "evidence_keys": ["evidence:p1"]},
            {"text": "据第三方资料（未经核实），Kafka 只保证分区内有序 [evidence:k1]。", "cell_keys": ["kafka:order"],
             "evidence_keys": ["evidence:k1"]},
        ]}],
        "comparison_table": {},
        "limitations": [],
    })})

    result = NarrativeReportPolisher(client).polish(_synthesis_input())

    assert result.narrative["sections"][0]["paragraphs"][1]["text"] == "据第三方资料（未经核实），Kafka 只保证分区内有序 [evidence:k1]。"


def test_writer_receives_the_required_points_from_planning() -> None:
    client = FakeLlmClient({"research.synthesis": "not json"})
    synthesis_input = {**_synthesis_input(), "required_points": ["两者是否保证全局顺序", " ", "两者是否保证全局顺序"]}

    NarrativeReportPolisher(client).polish(synthesis_input)

    assert client.payloads[0]["required_points"] == ["两者是否保证全局顺序"]
    assert any("required_points" in rule for rule in client.payloads[0]["rules"])
