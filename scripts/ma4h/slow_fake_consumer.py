"""MA4H SIGTERM fixture: a deterministic provider phase held behind a marker."""

from __future__ import annotations

import os
import time
from pathlib import Path

from app.agent_kafka_consumer import main
from app.agent_task_client import JavaResearchAgentTaskClient
from app.config import load_settings
from app.deep_cell_executor import DeepCellExecutor
from app.deterministic_fake_toolchain import DeterministicFakeToolchain


class MarkerDelayedToolchain:
    def __init__(self, delay_seconds: float, marker_path: str) -> None:
        self.delay_seconds = delay_seconds
        self.marker_path = Path(marker_path)
        self.delegate = DeterministicFakeToolchain()

    def _pause(self, phase: str) -> None:
        if phase == "search":
            self.marker_path.parent.mkdir(parents=True, exist_ok=True)
            self.marker_path.write_text("search-started\n", encoding="utf-8")
            print("MA4H_DRAIN_PHASE_STARTED phase=search", flush=True)
        time.sleep(self.delay_seconds)

    def search(self, task_input, plan, *, allow_external: bool):
        self._pause("search")
        return self.delegate.search(task_input, plan, allow_external=allow_external)

    def fetch(self, task_input, plan, hits, *, allow_external: bool):
        self._pause("fetch")
        return self.delegate.fetch(task_input, plan, hits, allow_external=allow_external)

    def read(self, task_input, plan, documents, *, allow_external: bool):
        self._pause("read")
        return self.delegate.read(task_input, plan, documents, allow_external=allow_external)

    def extract(self, task_input, plan, windows, *, llm_client):
        self._pause("extract")
        return self.delegate.extract(task_input, plan, windows, llm_client=llm_client)


def build_executor() -> DeepCellExecutor:
    settings = load_settings()
    delay = float(os.environ.get("MA4H_FAKE_PHASE_DELAY_SECONDS", "5"))
    marker = os.environ.get("MA4H_DRAIN_PHASE_MARKER", "/control/phase-started")
    if delay < 3 or delay > 30:
        raise RuntimeError("MA4H fake phase delay must be between 3 and 30 seconds")
    client = JavaResearchAgentTaskClient(
        settings.java_base_url,
        settings.internal_auth_token,
        settings.research_agent_worker_instance_id,
        completion_max_attempts=settings.research_agent_completion_max_attempts,
        heartbeat_request_timeout_seconds=(
            settings.research_agent_heartbeat_request_timeout_seconds or 0.25
        ),
    )
    return DeepCellExecutor(
        client,
        settings.research_agent_worker_instance_id,
        toolchain=MarkerDelayedToolchain(delay, marker),
        enable_llm=False,
    )


if __name__ == "__main__":
    main(build_executor())
