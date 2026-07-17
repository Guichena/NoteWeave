from __future__ import annotations

from threading import Event

import pytest


def test_drain_coordinator_rejects_new_execution_but_allows_registered_execution_until_grace() -> None:
    from app.drain_control import DrainCoordinator, DrainRequested
    from app.execution_control import ExecutionControl

    coordinator = DrainCoordinator(clock=lambda: 100.0, start_watchdog=False)
    active = ExecutionControl()
    coordinator.register("task-active", active)

    assert coordinator.request_drain(grace_seconds=10) is True
    assert active.is_active
    with pytest.raises(DrainRequested):
        coordinator.register("task-new", ExecutionControl())

    assert coordinator.expire_grace(now=109.9) == 0
    assert active.is_active
    assert coordinator.expire_grace(now=110.0) == 1
    assert active.stop_reason == "DRAIN_GRACE_EXPIRED"


def test_drain_request_is_idempotent_and_first_deadline_wins() -> None:
    from app.drain_control import DrainCoordinator

    coordinator = DrainCoordinator(clock=lambda: 20.0, start_watchdog=False)
    assert coordinator.request_drain(grace_seconds=10) is True
    assert coordinator.request_drain(grace_seconds=100) is False
    assert coordinator.deadline == 30.0


def test_drain_watchdog_stops_registered_execution_at_real_grace_deadline() -> None:
    from app.drain_control import DrainCoordinator
    from app.execution_control import ExecutionControl

    stopped = Event()

    class ObservedControl(ExecutionControl):
        def stop(self, reason: str) -> bool:
            changed = super().stop(reason)
            if changed:
                stopped.set()
            return changed

    control = ObservedControl()
    coordinator = DrainCoordinator()
    coordinator.register("task-1", control)
    coordinator.request_drain(grace_seconds=0.05)

    assert stopped.wait(timeout=1.0)
    assert control.stop_reason == "DRAIN_GRACE_EXPIRED"


def test_drain_aware_polling_never_polls_after_drain() -> None:
    from app.drain_control import DrainAwareKafkaMessages, DrainCoordinator

    class Consumer:
        def __init__(self) -> None:
            self.poll_calls = 0

        def poll(self, *, timeout_ms: int):
            self.poll_calls += 1
            return {}

    coordinator = DrainCoordinator(start_watchdog=False)
    coordinator.request_drain(grace_seconds=10)
    consumer = Consumer()
    messages = DrainAwareKafkaMessages(consumer, coordinator, poll_timeout_ms=50)

    with pytest.raises(StopIteration):
        next(messages)
    assert consumer.poll_calls == 0
