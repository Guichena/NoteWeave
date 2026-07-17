"""MA4H heartbeat-fault fixture with a marked deterministic external phase.

It deliberately faults the *consumer's* second and subsequent heartbeats while
the search phase is sleeping.  Permit calls remain real Backend calls, so the
fixture exercises the execution-control boundary rather than a fake task API.
"""

from __future__ import annotations

import os
import time
from pathlib import Path

import app.agent_kafka_consumer as consumer_module
from app.agent_task_client import AgentTaskApiError, JavaResearchAgentTaskClient
from app.config import load_settings
from app.deep_cell_executor import DeepCellExecutor
from app.deterministic_fake_toolchain import DeterministicFakeToolchain


_RealClient = JavaResearchAgentTaskClient


class FaultingHeartbeatClient(_RealClient):
    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self._heartbeat_calls = 0
        self._mode = os.environ.get("MA4H_HEARTBEAT_FAULT_MODE", "stale").strip().lower()
        if self._mode not in {"stale", "unavailable"}:
            raise RuntimeError("MA4H_HEARTBEAT_FAULT_MODE must be stale or unavailable")

    def heartbeat(self, claim, *, lease_seconds: int = 60):
        self._heartbeat_calls += 1
        # The first heartbeat is the real synchronous post-claim validation.
        if self._heartbeat_calls == 1:
            return super().heartbeat(claim, lease_seconds=lease_seconds)
        if self._mode == "stale":
            raise AgentTaskApiError(409, "RESEARCH_AGENT_TASK_STALE_LEASE")
        raise AgentTaskApiError(503, "RESEARCH_AGENT_API_UNAVAILABLE")


class PhaseMarkedSlowToolchain:
    def __init__(self, marker_path: str, delay_seconds: float) -> None:
        self.marker_path = Path(marker_path)
        self.delay_seconds = delay_seconds
        self.delegate = DeterministicFakeToolchain()

    def _record(self, phase: str) -> None:
        self.marker_path.parent.mkdir(parents=True, exist_ok=True)
        with self.marker_path.open("a", encoding="utf-8") as handle:
            handle.write(f"{phase}\n")
        print(f"MA4H_HEARTBEAT_FAULT_PHASE phase={phase}", flush=True)
        if phase == "search":
            time.sleep(self.delay_seconds)

    def search(self, task_input, plan, *, allow_external: bool):
        self._record("search")
        return self.delegate.search(task_input, plan, allow_external=allow_external)

    def fetch(self, task_input, plan, hits, *, allow_external: bool):
        self._record("fetch")
        return self.delegate.fetch(task_input, plan, hits, allow_external=allow_external)

    def read(self, task_input, plan, documents, *, allow_external: bool):
        self._record("read")
        return self.delegate.read(task_input, plan, documents, allow_external=allow_external)

    def extract(self, task_input, plan, windows, *, llm_client):
        self._record("extract")
        return self.delegate.extract(task_input, plan, windows, llm_client=llm_client)


def build_executor() -> DeepCellExecutor:
    settings = load_settings()
    marker = os.environ.get("MA4H_HEARTBEAT_PHASE_MARKER", "/control/heartbeat-phases")
    delay = float(os.environ.get("MA4H_HEARTBEAT_PHASE_DELAY_SECONDS", "5"))
    if delay < 3 or delay > 30:
        raise RuntimeError("MA4H heartbeat phase delay must be between 3 and 30 seconds")
    permit_client = _RealClient(
        settings.java_base_url,
        settings.internal_auth_token,
        settings.research_agent_worker_instance_id,
        completion_max_attempts=settings.research_agent_completion_max_attempts,
        heartbeat_request_timeout_seconds=settings.research_agent_heartbeat_request_timeout_seconds or 0.25,
    )
    return DeepCellExecutor(
        permit_client,
        settings.research_agent_worker_instance_id,
        toolchain=PhaseMarkedSlowToolchain(marker, delay),
        enable_llm=False,
    )


if __name__ == "__main__":
    # main() constructs the consumer client.  Replace only that construction;
    # the executor's permit client above intentionally remains authoritative.
    consumer_module.JavaResearchAgentTaskClient = FaultingHeartbeatClient
    consumer_module.main(build_executor())
