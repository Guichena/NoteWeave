from __future__ import annotations

import pytest

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


def test_run_research_task_with_callbacks_should_fetch_emit_progress_and_complete() -> None:
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
    assert result_payload["evidence_cards"][0]["source_title"] == "Callback Source"
    assert result_payload["branch_decisions"][0]["decision"] == "NO_BRANCH"
    assert client.failures == []


def test_run_research_task_with_callbacks_should_report_failures_to_java() -> None:
    client = FakeCallbackClient(_build_task_input(), should_fail_fetch=True)

    with pytest.raises(RuntimeError):
        run_research_task_with_callbacks("task-r-callback", client)

    assert client.completed_results == []
    assert client.failures[0]["phase"] == "WORKER_EXECUTION"
    assert client.failures[0]["error_code"] == "RUNTIMEERROR"
