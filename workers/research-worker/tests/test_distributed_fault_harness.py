import sys

import pytest

from app.distributed_fault_harness import FaultController


def test_fault_controller_acknowledges_only_a_successful_configured_command() -> None:
    controller = FaultController({
        "worker_crash": [sys.executable, "-c", "print('worker-stopped:{run_id}')"],
    }, timeout_seconds=5)

    result = controller.apply("worker_crash", "run-1")

    assert result == {
        "scenario": "WORKER_CRASH",
        "applied": True,
        "evidence": "worker-stopped:run-1",
    }


def test_fault_controller_does_not_claim_unconfigured_or_failed_injection() -> None:
    controller = FaultController({
        "lease_expiry": [sys.executable, "-c", "raise SystemExit(7)"],
    }, timeout_seconds=5)

    with pytest.raises(ValueError, match="no configured"):
        controller.apply("duplicate_delivery", "run-1")
    with pytest.raises(RuntimeError, match="exit code 7"):
        controller.apply("lease_expiry", "run-1")
