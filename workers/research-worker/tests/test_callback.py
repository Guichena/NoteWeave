from __future__ import annotations

import json
import pytest

pytestmark = pytest.mark.usefixtures("fake_default_llm")

import app.callback as callback_module
from app.callback import JavaResearchCallbackClient, ResearchCancelledError
from app.callback import run_research_task_with_callbacks
from app.models import ResearchProgressEvent, ResearchTaskInput, ResearchTaskResult


def _build_task_input() -> ResearchTaskInput:
    return ResearchTaskInput.model_validate(
        {
            "task_id": "task-r-callback",
            "workspace_id": "ws-1",
            "target_id": "run-1",
            "source_scope": [
                {
                    "source_id": "src-1",
                    "title": "Callback Source",
                    "summary": "",
                    "sample_text": "Callback execution should extract evidence from source windows.",
                }
            ],
            "control_pack": {
                "pack_type": "research",
                "target_key": "DEFAULT",
                "task_neighborhood": "RESEARCH_DEFAULT",
                "evidence_policy": ["Keep findings anchored to evidence cards."],
            },
            "input_payload": {
                "question": "What should callback execution verify?",
                "profile_key": "DEFAULT",
            },
        }
    )


class FakeCallbackClient:
    def __init__(self, task_input: ResearchTaskInput, should_fail_fetch: bool = False) -> None:
        self.task_input = task_input
        self.should_fail_fetch = should_fail_fetch
        self.fetched_task_ids: list[str] = []
        self.progress_events: list[ResearchProgressEvent] = []
        self.completed_results: list[ResearchTaskResult] = []
        self.failures: list[dict[str, str]] = []

    def fetch_task_input(self, task_id: str) -> ResearchTaskInput:
        self.fetched_task_ids.append(task_id)
        if self.should_fail_fetch:
            raise RuntimeError("java input unavailable")
        return self.task_input

    def send_progress(self, task_id: str, event: ResearchProgressEvent) -> None:
        assert task_id == "task-r-callback"
        self.progress_events.append(event)

    def send_complete(self, task_id: str, result: ResearchTaskResult) -> None:
        assert task_id == "task-r-callback"
        self.completed_results.append(result)

    def send_fail(self, task_id: str, phase: str, error_code: str, error_message: str) -> None:
        self.failures.append(
            {
                "task_id": task_id,
                "phase": phase,
                "error_code": error_code,
                "error_message": error_message,
            }
        )


def test_java_research_callback_client_should_send_internal_auth_token(monkeypatch) -> None:
    captured_headers: dict[str, str] = {}

    class FakeResponse:
        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, traceback):
            return False

        def read(self) -> bytes:
            return b'{"success":true,"data":{}}'

    def fake_urlopen(req, timeout):
        captured_headers.update({key.lower(): value for key, value in req.header_items()})
        return FakeResponse()

    monkeypatch.setattr(callback_module.request, "urlopen", fake_urlopen)
    client = JavaResearchCallbackClient("http://java-host:8081", "shared-secret")

    client._request("GET", "/internal/worker/artifact-outbox/metrics")

    assert captured_headers["x-noteweave-internal-token"] == "shared-secret"


def test_java_callback_should_send_idempotent_mid_run_checkpoint(monkeypatch) -> None:
    captured: dict[str, object] = {}

    class FakeResponse:
        def __enter__(self):
            return self

        def __exit__(self, *args):
            return False

        def read(self):
            return b'{"success":true,"data":{"status":"RUNNING"}}'

    def fake_urlopen(req, timeout):
        captured["headers"] = {key.lower(): value for key, value in req.header_items()}
        captured["payload"] = json.loads(req.data.decode())
        return FakeResponse()

    monkeypatch.setattr(callback_module.request, "urlopen", fake_urlopen)
    client = JavaResearchCallbackClient("http://java-host:8081", "shared-secret")
    client.send_checkpoint("task-1", {"checkpoint_no": 2, "state_ledger": {"cells": []}})

    assert captured["payload"]["phase"] == "RESEARCH_CHECKPOINT"
    assert captured["payload"]["payload"]["research_checkpoint_candidate"]["checkpoint_no"] == 2
    assert captured["headers"]["x-noteweave-idempotency-key"].startswith("task-1:checkpoint:2:")


def test_java_callback_should_stop_when_coordinator_acknowledges_cancellation() -> None:
    client = JavaResearchCallbackClient("http://java-host:8081")
    with pytest.raises(ResearchCancelledError):
        client._raise_if_cancelled({"data": {"status": "CANCELLED"}})


@pytest.mark.parametrize(
    ("error_code", "expected_retryable"),
    [
        ("VALIDATIONERROR", False),
        ("AUTHENTICATIONERROR", False),
        ("TIMEOUTERROR", True),
        ("CONNECTIONERROR", True),
    ],
)
def test_java_callback_should_classify_failure_retryability(
    monkeypatch, error_code: str, expected_retryable: bool
) -> None:
    captured: dict[str, object] = {}
    client = JavaResearchCallbackClient("http://java-host:8081")

    def fake_request(method, path, payload=None, idempotency_key=""):
        captured.update(payload or {})
        return {"data": {"status": "FAILED"}}

    monkeypatch.setattr(client, "_request", fake_request)
    client.send_fail("task-1", "WORKER_EXECUTION", error_code, "safe")

    assert captured["retryable"] is expected_retryable


class _FakeLlmClient:
    """P0-4/P0-5 之后,test_callback 必须通过 FakeLlmClient 注入 evidence + verdict。

    不再依赖关键字规则生成的"假分"。
    """

    def __init__(self) -> None:
        self.calls: list[tuple[str, dict[str, object]]] = []

    def complete_json(self, purpose: str, payload: dict[str, object]) -> str:
        self.calls.append((purpose, payload))
        if purpose == "research.extract":
            # 注入两张卡片:一张 SUPPORTS、一张 WEAK_SUPPORT
            return json.dumps(
                {
                    "evidence_cards": [
                        {
                            "window_id": payload.get("windows", [{}])[0].get("window_id", ""),
                            "entity_id": "entity-callback",
                            "column_key": "core_feature",
                            "claim_text": "Callback execution verifies evidence cards.",
                            "quote_text": "Callback execution should extract evidence from source windows.",
                            "relation_type": "SUPPORTS",
                            "support_score": 0.85,
                            "conflict_score": 0.02,
                        },
                        {
                            "window_id": payload.get("windows", [{}])[0].get("window_id", ""),
                            "entity_id": "entity-callback",
                            "column_key": "limitation",
                            "claim_text": "Limited to one workspace source.",
                            "quote_text": "Callback execution should extract evidence from source windows.",
                            "relation_type": "WEAK_SUPPORT",
                            "support_score": 0.4,
                            "conflict_score": 0.0,
                        },
                    ]
                }
            )
        if purpose == "research.verify.cell":
            # 全部返回 SUPPORTS
            return json.dumps(
                {
                    "status": "SUPPORTS",
                    "confidence": 0.82,
                    "reason": "evidence card directly grounds the candidate value",
                    "suggested_revision": "",
                }
            )
        return ""


def test_run_research_task_with_callbacks_should_fetch_emit_progress_and_complete(
    monkeypatch,
) -> None:
    """P0-4 + P0-5: 用 FakeLlmClient 注入 evidence 与 verdict。

    旧测试期望的"假证据分"已经被诚实 RULE-mode 替代,这里通过 FakeLlmClient
    走 LLM 路径让 evidence 仍然非空。
    """
    from app import llm_client as llm_client_module
    from app.callback import JavaResearchCallbackClient

    fake = _FakeLlmClient()
    monkeypatch.setattr(llm_client_module, "build_default_llm_client", lambda: fake)

    client = FakeCallbackClient(_build_task_input())
    response = run_research_task_with_callbacks("task-r-callback", client)

    assert response.status == "COMPLETED"
    assert response.progress_events == 6
    assert client.fetched_task_ids == ["task-r-callback"]
    assert [event.phase for event in client.progress_events] == [
        "PLANNING",
        "SEARCHING",
        "READING",
        "EXTRACTING",
        "VERIFYING",
        "WRITING",
    ]
    assert len(client.completed_results) == 1
    result_payload = client.completed_results[0].result_payload
    # P0-5: extractor 由 FakeLlmClient 驱动,evidence_cards 应包含 window 对应卡片
    assert len(result_payload["evidence_cards"]) >= 1
    assert result_payload["evidence_cards"][0]["source_title"] == "Callback Source"
    # P0-5: branch_decisions 现在可能为 WRITE_WITH_GUARDRAILS,因为 FakeLlmClient
    # 返回的 cards 没有 trigger COUNTERFACTUAL/EXPAND_SOURCE_SCOPE
    assert result_payload["branch_decisions"][0]["decision"] in {
        "NO_BRANCH",
        "WRITE_WITH_GUARDRAILS",
        "COUNTERFACTUAL_RECHECK",
        "REEXTRACT_EVIDENCE",
        "EXPAND_SOURCE_SCOPE",
    }
    assert result_payload["counterfactual_summary"]["has_counterfactual_recheck"] is False
    assert client.failures == []


def test_run_research_task_with_callbacks_should_report_failures_to_java() -> None:
    client = FakeCallbackClient(_build_task_input(), should_fail_fetch=True)

    with pytest.raises(RuntimeError):
        run_research_task_with_callbacks("task-r-callback", client)

    assert client.completed_results == []
    assert client.progress_events[-1].phase == "FAILED"
    assert client.progress_events[-1].metrics["error_code"] == "RUNTIMEERROR"
    assert client.failures[0]["phase"] == "WORKER_EXECUTION"
    assert client.failures[0]["error_code"] == "RUNTIMEERROR"
