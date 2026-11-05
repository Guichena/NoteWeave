"""深挖任务内各列的答案核对并行执行：输出顺序与串行一致，调用预算在并行时也不会超出。"""

from __future__ import annotations

import io
import json
import threading
import time
from types import SimpleNamespace

from app.deep_cell_executor import _build_task_scope, _select_column_cards
from app.extraction_result import ExtractionResult, ExtractionTerminationReason
from app.llm_client import OpenAICompatibleLlmClient
from app.models import ResearchEvidenceCard
from app.task_snapshot_contract import require_trusted_claim_snapshot
from tests.test_ma4d_deep_cell_executor import _claim, _external_snapshot


class _SlowFirstColumnCheck:
    """第一列的核对最慢，并行时它最后返回；记录同时在途的调用数。"""

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.in_flight = 0
        self.max_in_flight = 0

    def complete_json(self, purpose: str, payload: dict[str, object]) -> str:
        with self.lock:
            self.in_flight += 1
            self.max_in_flight = max(self.max_in_flight, self.in_flight)
        try:
            label = str(payload["column"]["label"])
            time.sleep(0.15 if label.endswith("0") else 0.02)
            claims = payload["claims"]
            return json.dumps({"answering_indices": [item["index"] for item in claims],
                               "coverage": [{"requirement_index": 0,
                                             "claim_indices": [item["index"] for item in claims]}]})
        finally:
            with self.lock:
                self.in_flight -= 1


def _card(column_key: str, index: int) -> ResearchEvidenceCard:
    window_id = f"w-{column_key}-{index}"
    return ResearchEvidenceCard(
        evidence_id=f"ev-{column_key}-{index}", window_id=window_id, source_id=f"src-{window_id}",
        source_title="docs", claim_text=f"{column_key} fact {index}", quote_text=f"{column_key} fact {index}",
        relation_type="SUPPORTS", support_score=0.9, conflict_score=0.0, entity_id="entity-1", column_key=column_key)


def _multi_column_scope():
    snapshot = require_trusted_claim_snapshot(_claim(_external_snapshot()))
    _, plan, _ = _build_task_scope(snapshot)
    base = plan.research_schema.columns[0]
    columns = [base.model_copy(update={"key": f"col{index}", "label": f"Column {index}",
                                       "requirements": [f"requirement {index}"]}) for index in range(3)]
    plan = plan.model_copy(update={"research_schema": plan.research_schema.model_copy(update={
        "columns": columns, "required_column_count": len(columns)})})
    raw = {column.key: [_card(column.key, 1), _card(column.key, 2)] for column in columns}
    windows = [SimpleNamespace(window_id=card.window_id, snapshot_status="EXTERNAL_ARCHIVED", query="q",
                               read_focus="f", url=f"https://docs.example/{card.window_id}")
               for cards in raw.values() for card in cards]
    results = [ExtractionResult(accepted_cards=tuple(raw[column.key]),
                                termination_reason=ExtractionTerminationReason.ACCEPTED_CARDS) for column in columns]
    return snapshot, plan, results, raw, windows


def _run(monkeypatch, concurrency: str):
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_AGENT_COLUMN_CHECK_CONCURRENCY", concurrency)
    snapshot, plan, results, raw, windows = _multi_column_scope()
    llm = _SlowFirstColumnCheck()
    _, cards, evidence = _select_column_cards(snapshot, plan, results, raw, windows, llm, lambda: None, [])
    return [card.evidence_id for card in cards], [item["evidence_key"] for item in evidence], llm.max_in_flight


def test_parallel_column_checks_keep_serial_order(monkeypatch) -> None:
    serial_cards, serial_evidence, serial_in_flight = _run(monkeypatch, "1")
    parallel_cards, parallel_evidence, parallel_in_flight = _run(monkeypatch, "3")

    assert serial_in_flight == 1
    assert parallel_in_flight > 1
    assert parallel_cards == serial_cards
    assert parallel_evidence == serial_evidence
    assert [card.split("-")[1] for card in parallel_cards][:2] == ["col0", "col0"]


def test_call_budget_holds_under_parallel_calls(monkeypatch) -> None:
    requests: list[object] = []
    lock = threading.Lock()

    def respond(request, **kwargs):
        time.sleep(0.05)
        with lock:
            requests.append(request)
        body = {"choices": [{"message": {"content": "{}"}, "finish_reason": "stop"}]}
        return io.BytesIO(json.dumps(body).encode())

    monkeypatch.setattr("app.llm_client.credential_safe_urlopen", respond)
    client = OpenAICompatibleLlmClient(api_key="test-key", model="test-model",
                                       base_url="http://127.0.0.1:9999/v1", max_attempts=1, max_total_calls=3)
    threads = [threading.Thread(target=client.complete_json, args=("research.answer_check", {})) for _ in range(6)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    assert len(requests) == 3
    summary = client.usage_summary()
    assert summary["provider_call_count"] == 3
    assert sum(1 for call in summary["calls"] if call["termination_reason"] == "CALL_BUDGET_EXHAUSTED") == 3
