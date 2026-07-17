"""G4-only delayed fake toolchain, keeping two Kafka members observable."""

from __future__ import annotations

import os
import time

from app.agent_kafka_consumer import run_research_agent_kafka_consumer_forever
from app.agent_task_client import JavaResearchAgentTaskClient
from app.config import load_settings
from app.deep_cell_executor import DeepCellExecutor
from app.deterministic_fake_toolchain import DeterministicFakeToolchain


class DelayedFakeToolchain:
    def __init__(self, delay: float) -> None:
        self.delay = delay
        self.delegate = DeterministicFakeToolchain()

    def _pause(self) -> None:
        time.sleep(self.delay)

    def search(self, task_input, plan, *, allow_external: bool):
        self._pause()
        return self.delegate.search(task_input, plan, allow_external=allow_external)

    def fetch(self, task_input, plan, hits, *, allow_external: bool):
        self._pause()
        return self.delegate.fetch(task_input, plan, hits, allow_external=allow_external)

    def read(self, task_input, plan, documents, *, allow_external: bool):
        self._pause()
        return self.delegate.read(task_input, plan, documents, allow_external=allow_external)

    def extract(self, task_input, plan, windows, *, llm_client):
        self._pause()
        return self.delegate.extract(task_input, plan, windows, llm_client=llm_client)


def main() -> None:
    settings = load_settings()
    delay = float(os.environ.get("MA4G_FAKE_PHASE_DELAY_SECONDS", "0.75"))
    if delay < 0.1 or delay > 2.0:
        raise RuntimeError("G4 fake phase delay must be between 0.1 and 2.0 seconds")
    worker_id = settings.research_agent_worker_instance_id.strip()
    client = JavaResearchAgentTaskClient(
        settings.java_base_url,
        settings.internal_auth_token,
        worker_id,
        completion_max_attempts=settings.research_agent_completion_max_attempts,
    )
    executor = DeepCellExecutor(
        client,
        worker_id,
        toolchain=DelayedFakeToolchain(delay),
        enable_llm=False,
    )
    print(f"MA4G_G4_DELAYED_WORKER_READY worker={worker_id} phase_delay={delay}", flush=True)
    run_research_agent_kafka_consumer_forever(executor)


if __name__ == "__main__":
    main()

