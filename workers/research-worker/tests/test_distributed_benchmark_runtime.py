import pytest

from app.benchmark import BenchmarkCase, BenchmarkProfile
from app.distributed_benchmark_runtime import (
    DistributedReplayConfig,
    DistributedResearchBenchmarkBoundary,
)


def _case() -> BenchmarkCase:
    return BenchmarkCase(
        case_key="distributed-case",
        question="Compare the systems",
        schema={},
        gold_version="v1",
        source_snapshot_digest="sha256:fixture",
        gold={},
        task_input={
            "workspace_id": "workspace-1",
            "source_scope": [],
            "input_payload": {
                "question": "Compare the systems",
                "profile_key": "deep-research",
                "research_intent": {"depth": "STANDARD", "research_type": "AUTO"},
            },
        },
    )


def _profile(*, provider_kind: str = "SIMULATED", faults: list[str] | None = None) -> BenchmarkProfile:
    return BenchmarkProfile(
        profile_key="distributed",
        execution_mode="DISTRIBUTED_DETERMINISTIC",
        provider_kind=provider_kind,
        provider="fixture",
        model="fixture-model",
        source_policy={"allow_external": False, "fault_scenarios": faults or []},
        budget={},
        random_policy={"temperature": 0, "seed": 7},
        rollout_no=1,
    )


class FakeDistributedBoundary(DistributedResearchBenchmarkBoundary):
    def __init__(self, *, faults: bool = False) -> None:
        super().__init__(DistributedReplayConfig(
            backend_base_url="http://backend",
            workspace_id="workspace-1",
            fault_control_url="http://fault-harness" if faults else "",
            poll_interval_seconds=0.001,
            timeout_seconds=1,
        ), sleeper=lambda _seconds: None)
        self.requests: list[tuple[str, str, object]] = []

    def _request_json(self, method, path, body, *, internal, absolute=False, auth_token=""):
        self.requests.append((method, path, body))
        if path.endswith("/research-runs"):
            return {"success": True, "data": {"researchRunId": "run-1", "status": "RUNNING"}}
        if path.endswith("/research-runs/run-1"):
            return {"success": True, "data": {"researchRunId": "run-1", "status": "COMPLETED"}}
        if path.endswith("/checkpoints"):
            return {"success": True, "data": {"checkpoint_id": "checkpoint-1", "checkpoint_seq": 1}}
        if "/scenarios/" in path:
            scenario = str(body["scenario"])
            return {"success": True, "data": {"scenario": scenario, "applied": True, "evidence": "docker-event"}}
        if path.endswith("/distributed-replay/runs/run-1"):
            return {"success": True, "data": {
                "schema_version": "research-distributed-replay-observation.v1",
                "result_classification": "DISTRIBUTED_DETERMINISTIC",
                "run": {"id": "run-1", "status": "COMPLETED"},
                "tasks": [{"task_id": "task-1", "lease_epoch": 2, "fencing_token": 3}],
                "executions": [{"usage_json": '{"llm_calls": 2, "input_tokens": 10}'}],
                "completions": [{"completion_id": "completion-1", "completion_digest": "sha256:x"}],
                "outbox": [{"outbox_id": "outbox-1", "status": "SENT"}],
                "checkpoints": [{"checkpoint_seq": 1}],
                "artifact": {"artifact_id": "artifact-1", "report_digest": "sha256:report"},
                "state_ledger": {"rows": [], "cells": []},
            }}
        raise AssertionError(path)


def test_distributed_boundary_uses_backend_and_persisted_observation() -> None:
    boundary = FakeDistributedBoundary()
    result = boundary.execute(_profile(), _case())

    assert result.result_payload["result_classification"] == "DISTRIBUTED_DETERMINISTIC"
    assert result.result_payload["tasks"][0]["fencing_token"] == 3
    assert result.usage["llm_calls"] == 2
    assert result.termination_reason == "COMPLETED"
    create_body = next(body for method, path, body in boundary.requests if method == "POST" and path.endswith("/research-runs"))
    assert "source_scope_source_ids" in create_body
    assert "sourceScopeSourceIds" not in create_body


def test_distributed_boundary_accepts_backend_snake_case_run_identifier() -> None:
    boundary = FakeDistributedBoundary()
    original = boundary._request_json

    def snake_case_response(method, path, body, *, internal, absolute=False, auth_token=""):
        response = original(method, path, body, internal=internal, absolute=absolute, auth_token=auth_token)
        if path.endswith("/research-runs"):
            data = dict(response["data"])
            data["research_run_id"] = data.pop("researchRunId")
            response = dict(response, data=data)
        return response

    boundary._request_json = snake_case_response
    result = boundary.execute(_profile(), _case())

    assert result.result_payload["run"]["id"] == "run-1"


def test_distributed_boundary_requires_acknowledged_fault_injection() -> None:
    boundary = FakeDistributedBoundary(faults=True)
    result = boundary.execute(_profile(faults=["WORKER_CRASH", "HYDRATED_RESUME"]), _case())

    assert [item["scenario"] for item in result.result_payload["fault_scenarios"]] == [
        "WORKER_CRASH", "HYDRATED_RESUME"
    ]


def test_real_provider_is_fail_closed_without_explicit_authorization(monkeypatch) -> None:
    monkeypatch.delenv("NOTEWEAVE_RESEARCH_ALLOW_REAL_PROVIDER_BENCHMARK", raising=False)

    with pytest.raises(RuntimeError, match="explicit authorization"):
        FakeDistributedBoundary().execute(_profile(provider_kind="REAL"), _case())


def test_fault_scenario_without_harness_is_not_mislabeled_as_injected() -> None:
    with pytest.raises(RuntimeError, match="FAULT_CONTROL_URL"):
        FakeDistributedBoundary().execute(_profile(faults=["LEASE_EXPIRY"]), _case())
