"""同一深挖任务内按列并行抽取：结果顺序、收据调用次数、网关失败判断和预算回退都与串行一致。"""

from __future__ import annotations

import random
import threading
import time
from types import SimpleNamespace

import pytest

from app import deep_cell_executor
from app.deep_cell_executor import _extract_raw_cards, _merge_extractions
from app.extraction_result import ExtractionResult, ExtractionTerminationReason
from app.extractor import _build_provider_receipt
from app.llm_client import OpenAICompatibleLlmClient
from app.models import ResearchColumn, ResearchPlan, ResearchSchema


def _plan(columns: int) -> ResearchPlan:
    return ResearchPlan(normalized_question="Atlas", research_schema=ResearchSchema(
        columns=[ResearchColumn(key=f"col-{index}", label=f"Aspect {index}") for index in range(columns)]))


def _client(*, max_total_calls: int = 64, fail_columns: frozenset[str] = frozenset(),
            empty_columns: frozenset[str] = frozenset()) -> tuple[OpenAICompatibleLlmClient, list[str]]:
    """真实客户端，只把网络请求换成随机延迟的假响应；按列控制返回空内容（网关故障）。"""
    llm = OpenAICompatibleLlmClient(api_key="test-key", model="test-model", base_url="https://llm.example/v1",
                                    max_attempts=1, max_total_calls=max_total_calls)
    order: list[str] = []
    lock = threading.Lock()

    def create(**request):
        column = request["messages"][-1]["content"].split("column=", 1)[1].split('"', 1)[0]
        time.sleep(random.uniform(0, 0.03))
        with lock:
            order.append(column)
        content = "" if column in fail_columns else '{"cards": [], "column": "%s"}' % column
        return SimpleNamespace(model_dump=lambda: {
            "choices": [{"message": {"content": content}, "finish_reason": "stop"}],
            "usage": {"prompt_tokens": 10, "completion_tokens": 5}})

    llm.client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=create)))
    llm.empty_columns = empty_columns
    return llm, order


class _Toolchain:
    """每列调用一次模型；空响应按非法 JSON 处理，与真实抽取器的收据构造方式相同。"""

    def extract(self, task_input, plan, windows, *, llm_client):
        column = plan.research_schema.columns[0].key
        if column in getattr(llm_client, "empty_columns", frozenset()):
            return ExtractionResult(termination_reason=ExtractionTerminationReason.MISSING_EVIDENCE_CARDS,
                                    provider_receipt=_build_provider_receipt(llm_client, "", called=False))
        response = llm_client.complete_json("research.extract", {"task": f"column={column}"})
        reason = (ExtractionTerminationReason.MISSING_EVIDENCE_CARDS if response
                  else ExtractionTerminationReason.INVALID_JSON)
        return ExtractionResult(termination_reason=reason,
                                provider_receipt=_build_provider_receipt(llm_client, response, called=True))


def _run(monkeypatch, concurrency: int, llm, columns: int = 6):
    monkeypatch.setattr(deep_cell_executor, "_column_extract_concurrency", lambda: concurrency)
    return _extract_raw_cards(_Toolchain(), None, _plan(columns), [], llm, lambda: None)


def test_parallel_extraction_matches_serial_results_and_receipts(monkeypatch) -> None:
    serial_llm, _ = _client(empty_columns=frozenset({"col-2"}))
    serial_llm.complete_json("research.plan", {"task": 'column=planning"'})  # 抽取前已有一次调用
    serial, _ = _run(monkeypatch, 1, serial_llm)
    parallel_llm, order = _client(empty_columns=frozenset({"col-2"}))
    parallel_llm.complete_json("research.plan", {"task": 'column=planning"'})
    for _ in range(5):
        order.clear()
        parallel, _ = _run(monkeypatch, 3, parallel_llm)
        parallel_llm.call_records = parallel_llm.call_records[:1]
        assert [result.model_dump() for result in parallel] == [result.model_dump() for result in serial]
    assert [result.provider_receipt.call_count for result in serial] == [2, 3, 0, 4, 5, 6]
    assert _merge_extractions(parallel, []).model_dump() == _merge_extractions(serial, []).model_dump()


def test_parallel_extraction_does_not_increase_model_calls(monkeypatch) -> None:
    llm, _ = _client()
    _run(monkeypatch, 3, llm)
    assert llm.usage_summary()["provider_call_count"] == 6


def test_parallel_extraction_falls_back_to_serial_when_budget_is_tight(monkeypatch) -> None:
    llm, order = _client(max_total_calls=4)
    results, _ = _run(monkeypatch, 3, llm)
    # 串行时前四列拿到预算，后两列预算用尽
    assert order == ["col-0", "col-1", "col-2", "col-3"]
    assert [result.provider_receipt.call_count for result in results] == [1, 2, 3, 4, 4, 4]


def test_parallel_extraction_still_raises_on_gateway_outage_for_the_failing_column(monkeypatch) -> None:
    llm, _ = _client(fail_columns=frozenset({"col-4"}))
    with pytest.raises(RuntimeError, match="^RESEARCH_EXTRACTION_PROVIDER_UNAVAILABLE$"):
        _run(monkeypatch, 3, llm)


def test_last_call_record_is_tracked_per_thread() -> None:
    llm, _ = _client()
    seen: dict[str, str] = {}

    def call(column: str) -> None:
        llm.complete_json("research.extract", {"task": f"column={column}"})
        record = llm.last_call_record_in_thread()
        seen[column] = str(record["purpose"]) if record else ""

    threads = [threading.Thread(target=call, args=(f"c{index}",)) for index in range(4)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    assert seen == {f"c{index}": "research.extract" for index in range(4)}
    assert llm.has_headroom_for("research.extract", 60)
    assert not llm.has_headroom_for("research.extract", 61)
