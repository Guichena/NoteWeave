from __future__ import annotations

from threading import Event

import pytest

from app.agent_task_client import AgentTaskApiError, AgentTaskClaim


def _claim() -> AgentTaskClaim:
    return AgentTaskClaim("task-ma4h", 2, 7, "[]", "{}", "{}", "sha256:" + "1" * 64)


def test_execution_control_stop_is_irreversible() -> None:
    from app.execution_control import ExecutionControl, ExecutionStopped

    control = ExecutionControl()
    assert control.is_active
    assert control.stop("STALE_LEASE") is True
    assert control.stop("CANCELLED") is False
    assert control.stop_reason == "STALE_LEASE"
    with pytest.raises(ExecutionStopped, match="STALE_LEASE"):
        control.require_active()


def test_lease_keeper_rejects_interval_that_can_outlive_one_third_of_lease() -> None:
    from app.execution_control import ExecutionControl, LeaseKeeper

    with pytest.raises(ValueError, match="lease_seconds / 3"):
        LeaseKeeper(object(), _claim(), ExecutionControl(), lease_seconds=9, interval_seconds=3)


def test_lease_keeper_stops_immediately_when_heartbeat_reports_stale_lease() -> None:
    from app.execution_control import ExecutionControl, LeaseKeeper

    class StaleClient:
        def heartbeat(self, *_args, **_kwargs):
            raise AgentTaskApiError(409, "RESEARCH_AGENT_TASK_STALE_LEASE")

    control = ExecutionControl()
    keeper = LeaseKeeper(StaleClient(), _claim(), control, lease_seconds=30, interval_seconds=5)

    assert keeper.tick(now=10.0) is False
    assert control.stop_reason == "STALE_LEASE"


def test_lease_keeper_rejects_heartbeat_that_mutates_immutable_claim_scope() -> None:
    from app.execution_control import ExecutionControl, LeaseKeeper

    class MutatingClient:
        def heartbeat(self, claim, *, lease_seconds: int = 60):
            return AgentTaskClaim(
                claim.agent_task_id,
                claim.lease_epoch,
                claim.fencing_token,
                claim.target_cells_json,
                '{"llm_calls":999}',
                claim.task_snapshot_json,
                claim.snapshot_digest,
            )

    control = ExecutionControl()
    keeper = LeaseKeeper(MutatingClient(), _claim(), control, lease_seconds=30, interval_seconds=5)

    assert keeper.tick(now=10.0) is False
    assert control.stop_reason == "STALE_LEASE"


def test_lease_keeper_stops_after_unavailable_budget_but_not_before() -> None:
    from app.execution_control import ExecutionControl, LeaseKeeper

    class UnavailableClient:
        def heartbeat(self, *_args, **_kwargs):
            raise AgentTaskApiError(503, "RESEARCH_AGENT_API_UNAVAILABLE")

    control = ExecutionControl()
    keeper = LeaseKeeper(
        UnavailableClient(), _claim(), control, lease_seconds=30, interval_seconds=5,
        failure_budget_seconds=8,
    )

    assert keeper.tick(now=100.0) is True
    assert control.is_active
    assert keeper.tick(now=107.9) is True
    assert control.is_active
    assert keeper.tick(now=108.0) is False
    assert control.stop_reason == "HEARTBEAT_UNAVAILABLE"


def test_lease_keeper_start_heartbeats_synchronously_then_keeps_heartbeating() -> None:
    from app.execution_control import ExecutionControl, LeaseKeeper

    second_heartbeat = Event()

    class RecordingClient:
        def __init__(self) -> None:
            self.calls = 0

        def heartbeat(self, claim, *, lease_seconds: int = 60):
            self.calls += 1
            if self.calls == 2:
                second_heartbeat.set()
            return claim

    client = RecordingClient()
    keeper = LeaseKeeper(client, _claim(), ExecutionControl(), lease_seconds=4, interval_seconds=0.05)

    keeper.start()
    try:
        assert client.calls >= 1
        assert second_heartbeat.wait(timeout=1.0)
    finally:
        keeper.close()
    calls_after_close = client.calls
    assert not keeper.is_running
    assert client.calls == calls_after_close


def test_execution_control_scope_exposes_one_shared_irreversible_control() -> None:
    from app.execution_control import (
        ExecutionControl,
        ExecutionStopped,
        execution_control_scope,
        require_execution_active,
    )

    control = ExecutionControl()
    with execution_control_scope(control):
        require_execution_active()
        control.stop("CANCELLED")
        with pytest.raises(ExecutionStopped, match="CANCELLED"):
            require_execution_active()

    # Code paths outside a distributed execution remain usable.
    require_execution_active()


def test_lease_keeper_background_unexpected_error_is_fail_closed() -> None:
    from app.execution_control import ExecutionControl, ExecutionStopped, LeaseKeeper

    failed = Event()

    class ExplodingClient:
        def __init__(self) -> None:
            self.calls = 0

        def heartbeat(self, claim, *, lease_seconds: int = 60):
            self.calls += 1
            if self.calls == 1:
                return claim
            failed.set()
            raise RuntimeError("unexpected heartbeat bug")

    control = ExecutionControl()
    keeper = LeaseKeeper(ExplodingClient(), _claim(), control, lease_seconds=4, interval_seconds=0.05)
    keeper.start()
    try:
        assert failed.wait(timeout=1.0)
        with pytest.raises(ExecutionStopped, match="HEARTBEAT_FAILED"):
            control.require_active()
    finally:
        keeper.close()
