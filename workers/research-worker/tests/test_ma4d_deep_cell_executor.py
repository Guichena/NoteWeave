from __future__ import annotations

import json
from types import SimpleNamespace

import pytest

from app.agent_command_contract import ResearchAgentCommand
from app.agent_task_client import AgentTaskClaim
from app.task_snapshot_contract import snapshot_digest

_TASK_ID = "00000000-0000-0000-0000-000000000001"
_SECOND_TASK_ID = "00000000-0000-0000-0000-000000000002"


def _snapshot(*, source_scope: object = None, task_id: str = _TASK_ID,
              target_cells: list[dict[str, object]] | None = None) -> dict[str, object]:
    payload: dict[str, object] = {
        "schema_version": "research-agent-task-snapshot.v1",
        "task_id": task_id,
        "research_run_id": "run-1",
        "workspace_id": "workspace-1",
        "role": "DEEP_CELL",
        "entity_id": "entity-1",
        "branch_id": "branch-main",
        "plan_revision": 2,
        "entity_set_version": 3,
        "lease_epoch": 2,
        "fencing_token": 7,
        "target_cells": target_cells or [{"cell_id": "entity-1:method", "expected_version": 3}],
        "budget": {"llm_calls": 2},
        "provider_key": "research-default",
        "source_policy": {
            "source_scope": source_scope if source_scope is not None else [
                {"source_id": "source-1", "title": "Trusted note", "sample_text": "The method is documented."}
            ],
            "allow_external_search": False,
            "allow_external_fetch": False,
        },
        "query_policy": {"query": "How does the method work?"},
    }
    return {**payload, "snapshot_digest": snapshot_digest(payload)}


def _counterfactual_quorum_snapshot() -> dict[str, object]:
    payload = _snapshot(source_scope=[
        {"source_id": "source-2", "title": "Independent note", "sample_text": "Independent evidence."}
    ])
    payload.pop("snapshot_digest")
    payload.update(
        schema_version="research-agent-task-snapshot.v2",
        role="COUNTERFACTUAL",
        branch_id="branch-quorum-2",
        logical_task_key="deep-cell:logical-1",
        quorum_group_key="quorum:group-1",
        candidate_quorum=2,
        candidate_slot=2,
        high_risk=True,
    )
    return {**payload, "snapshot_digest": snapshot_digest(payload)}


def _counterfactual_repair_snapshot() -> dict[str, object]:
    payload = _snapshot(source_scope=[
        {"source_id": "source-3", "title": "Repair note", "sample_text": "Independent repair evidence."}
    ])
    payload.pop("snapshot_digest")
    payload.update(
        schema_version="research-agent-task-snapshot.v2",
        role="COUNTERFACTUAL",
        branch_id="branch-counterfactual",
        logical_task_key="counterfactual:repair-1",
        candidate_quorum=1,
        candidate_slot=1,
        high_risk=False,
        source_policy={
            **payload["source_policy"],
            "excluded_source_ids": ["source-1", "source-2"],
        },
        query_policy={
            **payload["query_policy"],
            "repair_reason_digest": "sha256:repair-reason",
        },
    )
    return {**payload, "snapshot_digest": snapshot_digest(payload)}


def _claim(snapshot: dict[str, object]) -> AgentTaskClaim:
    target_ids = [item["cell_id"] for item in snapshot["target_cells"]]  # type: ignore[index]
    return AgentTaskClaim(str(snapshot["task_id"]), 2, 7, json.dumps(target_ids), "{\"llm_calls\":2}",
                          json.dumps(snapshot), str(snapshot["snapshot_digest"]))


def _command() -> ResearchAgentCommand:
    return ResearchAgentCommand(
        schema_version="research-agent-command.v1", command_id="command-1", research_run_id="run-1",
        agent_task_id=_TASK_ID, idempotency_key="idem-1", delivery_attempt=1,
    )


class _Permit:
    def __init__(self) -> None:
        self.calls: list[tuple[str, str]] = []

    def require_permit(self, snapshot, tool_identity: str) -> None:
        self.calls.append((snapshot.provider_key, tool_identity))


class _Toolchain:
    def __init__(self) -> None:
        self.calls: list[tuple[str, bool]] = []

    def search(self, _task_input, _plan, *, allow_external: bool):
        self.calls.append(("search", allow_external))
        return ["hit"]

    def fetch(self, _task_input, _plan, _hits, *, allow_external: bool):
        self.calls.append(("fetch", allow_external))
        return ["document"]

    def read(self, _task_input, _plan, _documents, *, allow_external: bool):
        self.calls.append(("read", allow_external))
        return ["window"]

    def extract(self, _task_input, _plan, _windows, *, llm_client):
        self.calls.append(("extract", llm_client is not None))
        return ["evidence-card"]


def test_deep_cell_should_permit_each_real_phase_in_order_and_build_no_result_completion() -> None:
    from app.deep_cell_executor import DeepCellExecutor

    permit = _Permit()
    toolchain = _Toolchain()
    completion = DeepCellExecutor(permit, "worker-a", toolchain=toolchain)(_command(), _claim(_snapshot()))

    assert permit.calls == [
        ("research-default", "search"),
        ("research-default", "fetch"),
        ("research-default", "read"),
        ("research-default", "extract"),
    ]
    assert [name for name, _ in toolchain.calls] == ["search", "fetch", "read", "extract"]
    assert all(not external for _name, external in toolchain.calls[:3])
    assert completion.worker_instance_id == "worker-a"
    assert completion.termination_reason == "NO_SUPPORTED_CANDIDATE"
    assert completion.budget_usage.evidence_cards == 0
    assert completion.task_snapshot_digest == _snapshot()["snapshot_digest"]
    assert completion.envelope_digest.startswith("sha256:")


def test_deep_cell_should_execute_an_authoritative_counterfactual_quorum_slot() -> None:
    from app.deep_cell_executor import DeepCellExecutor
    from app.deterministic_fake_toolchain import DeterministicFakeToolchain

    completion = DeepCellExecutor(
        _Permit(), "worker-a", toolchain=DeterministicFakeToolchain(), enable_llm=False
    )(_command(), _claim(_counterfactual_quorum_snapshot()))

    assert completion.termination_reason == "CANDIDATES_PROPOSED"
    assert completion.candidates[0].cell_key == "entity-1:method"
    assert completion.task_snapshot_digest == _counterfactual_quorum_snapshot()["snapshot_digest"]


def test_deep_cell_should_execute_an_authoritative_counterfactual_repair_task() -> None:
    from app.deep_cell_executor import DeepCellExecutor
    from app.deterministic_fake_toolchain import DeterministicFakeToolchain

    completion = DeepCellExecutor(
        _Permit(), "worker-a", toolchain=DeterministicFakeToolchain(), enable_llm=False
    )(_command(), _claim(_counterfactual_repair_snapshot()))

    assert completion.termination_reason == "CANDIDATES_PROPOSED"
    assert completion.candidates[0].cell_key == "entity-1:method"
    assert completion.task_snapshot_digest == _counterfactual_repair_snapshot()["snapshot_digest"]


def test_deep_cell_should_not_start_next_phase_after_execution_control_stops() -> None:
    from app.deep_cell_executor import DeepCellExecutor
    from app.execution_control import ExecutionControl, ExecutionStopped, execution_control_scope

    control = ExecutionControl()

    class StoppingToolchain(_Toolchain):
        def search(self, _task_input, _plan, *, allow_external: bool):
            self.calls.append(("search", allow_external))
            control.stop("STALE_LEASE")
            return ["hit"]

    permit = _Permit()
    toolchain = StoppingToolchain()
    with execution_control_scope(control):
        with pytest.raises(ExecutionStopped, match="STALE_LEASE"):
            DeepCellExecutor(permit, "worker-a", toolchain=toolchain, enable_llm=False)(
                _command(), _claim(_snapshot())
            )

    assert permit.calls == [("research-default", "search")]
    assert toolchain.calls == [("search", False)]


def test_deep_cell_should_reject_missing_authoritative_source_scope_before_permit_or_tool() -> None:
    from app.deep_cell_executor import DeepCellExecutor

    permit = _Permit()
    toolchain = _Toolchain()
    with pytest.raises(ValueError, match="source_scope"):
        DeepCellExecutor(permit, "worker-a", toolchain=toolchain)(_command(), _claim(_snapshot(source_scope="not-a-list")))
    assert permit.calls == []
    assert toolchain.calls == []


def test_deep_cell_should_reject_noncanonical_worker_before_permit_or_tool() -> None:
    from app.deep_cell_executor import DeepCellExecutor

    permit = _Permit()
    toolchain = _Toolchain()
    with pytest.raises(ValueError, match="worker_instance_id"):
        DeepCellExecutor(permit, "Worker-A", toolchain=toolchain, enable_llm=False)
    assert permit.calls == []
    assert toolchain.calls == []


def test_deep_cell_should_build_atomic_evidence_and_candidate_without_any_early_result_write() -> None:
    from app.deep_cell_executor import DeepCellExecutor

    class EvidencePermit(_Permit):
        def __init__(self) -> None:
            super().__init__()
            self.result_write_calls: list[str] = []

        def append_workspace_evidence(self, _claim, evidence: list[dict[str, object]]):
            self.result_write_calls.append("evidence")
            raise AssertionError("DeepCellExecutor must not persist evidence before atomic completion")

        def append_candidate_batch(self, _claim, _execution_id, candidates: list[dict[str, object]]):
            self.result_write_calls.append("candidate")
            raise AssertionError("DeepCellExecutor must not persist candidates before atomic completion")

    class EvidenceToolchain(_Toolchain):
        def read(self, _task_input, _plan, _documents, *, allow_external: bool):
            self.calls.append(("read", allow_external))
            return [SimpleNamespace(window_id="window-1", snapshot_status="WORKSPACE", query="How does it work?", read_focus="method")]

        def extract(self, _task_input, _plan, _windows, *, llm_client):
            self.calls.append(("extract", llm_client is not None))
            return [SimpleNamespace(
                evidence_id="evidence-1", window_id="window-1", source_id="source-1", source_title="Trusted note",
                quote_text="The method is documented.", claim_text="The method is documented.", relation_type="SUPPORTS",
                support_score=0.9, conflict_score=0.0, entity_id="entity-1", column_key="method",
            )]

    permit = EvidencePermit()
    completion = DeepCellExecutor(permit, "worker-a", toolchain=EvidenceToolchain())(_command(), _claim(_snapshot()))

    assert permit.result_write_calls == []
    assert completion.termination_reason == "CANDIDATES_PROPOSED"
    assert completion.budget_usage.evidence_cards == 1
    assert completion.budget_usage.candidates_submitted == 1
    assert completion.telemetry.search_hits == 1
    assert completion.telemetry.documents == 1
    assert completion.telemetry.windows == 1
    assert completion.evidence[0].evidence_key.startswith("evidence:")
    assert completion.evidence[0].support_score_ppm == 900_000
    assert completion.evidence[0].snapshot_status == "WORKSPACE"
    assert completion.candidates[0].cell_key == "entity-1:method"
    assert completion.candidates[0].evidence_keys == (completion.evidence[0].evidence_key,)
    assert completion.candidates[0].confidence_ppm == 900_000


def test_deterministic_fake_toolchain_should_use_no_external_or_llm_provider(monkeypatch) -> None:
    from app.deep_cell_executor import DeepCellExecutor
    from app.deterministic_fake_toolchain import DeterministicFakeToolchain

    def forbidden_settings():
        raise AssertionError("fake toolchain must not load LLM settings")

    monkeypatch.setattr("app.deep_cell_executor.load_settings", forbidden_settings)
    completion = DeepCellExecutor(_Permit(), "worker-a", toolchain=DeterministicFakeToolchain(), enable_llm=False)(
        _command(), _claim(_snapshot())
    )

    assert completion.termination_reason == "CANDIDATES_PROPOSED"
    assert completion.budget_usage.search_calls == 1
    assert completion.budget_usage.extract_calls == 1
    assert completion.evidence[0].support_score_ppm == 800_000


def test_deep_cell_should_scope_stable_evidence_keys_per_task_in_the_same_run() -> None:
    from app.deep_cell_executor import DeepCellExecutor
    from app.deterministic_fake_toolchain import DeterministicFakeToolchain

    executor = DeepCellExecutor(_Permit(), "worker-a", toolchain=DeterministicFakeToolchain(), enable_llm=False)
    first_snapshot = _snapshot(task_id=_TASK_ID)
    second_snapshot = _snapshot(task_id=_SECOND_TASK_ID)
    first = executor(_command(), _claim(first_snapshot))
    exact_rebuild = executor(_command(), _claim(first_snapshot))
    second_command = _command().model_copy(update={"agent_task_id": _SECOND_TASK_ID})
    second = executor(second_command, _claim(second_snapshot))

    assert first.evidence[0].evidence_key != second.evidence[0].evidence_key
    assert first.evidence[0].evidence_key == exact_rebuild.evidence[0].evidence_key
    assert first.envelope_digest == exact_rebuild.envelope_digest
    assert first.evidence[0].evidence_key in first.candidates[0].evidence_keys
    assert second.evidence[0].evidence_key in second.candidates[0].evidence_keys


def test_deterministic_fake_completion_should_cover_every_target_for_multi_cell_atomic_cas() -> None:
    from app.deep_cell_executor import DeepCellExecutor
    from app.deterministic_fake_toolchain import DeterministicFakeToolchain

    snapshot = _snapshot(
        source_scope=[{
            "source_id": "source-1",
            "title": "Trusted note",
            "sample_text": "Method evidence line.\nResult evidence line.",
        }],
        target_cells=[
            {"cell_id": "entity-1:method", "expected_version": 3},
            {"cell_id": "entity-1:result", "expected_version": 5},
        ],
    )
    completion = DeepCellExecutor(
        _Permit(), "worker-a", toolchain=DeterministicFakeToolchain(), enable_llm=False
    )(_command(), _claim(snapshot))

    assert len(completion.evidence) == 2
    assert len(completion.candidates) == 2
    assert {candidate.cell_key for candidate in completion.candidates} == {
        "entity-1:method", "entity-1:result"
    }
    assert {candidate.base_cell_version for candidate in completion.candidates} == {3, 5}
    assert {item.quote_text for item in completion.evidence} == {
        "Method evidence line.", "Result evidence line."
    }
