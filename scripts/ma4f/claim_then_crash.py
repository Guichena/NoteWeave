"""MA4F fixture: claim one real Kafka command, then crash before any tool/write."""

from __future__ import annotations

import json
import os
import sys

sys.path.insert(0, "/app")

from app.agent_command_contract import ResearchAgentCommand
from app.agent_kafka_consumer import create_research_agent_kafka_consumer
from app.agent_task_client import JavaResearchAgentTaskClient
from app.config import load_settings
from app.kafka_consumer import _message_value
from app.task_snapshot_contract import require_trusted_claim_snapshot


def main() -> None:
    settings = load_settings()
    worker_id = settings.research_agent_worker_instance_id.strip()
    if not worker_id:
        raise RuntimeError("claim-then-crash fixture requires worker instance id")
    client = JavaResearchAgentTaskClient(settings.java_base_url, settings.internal_auth_token, worker_id)
    consumer = create_research_agent_kafka_consumer()
    for message in consumer:
        command = ResearchAgentCommand.model_validate(json.loads(_message_value(message)))
        claim = client.claim(command.agent_task_id, lease_seconds=settings.research_agent_lease_seconds)
        require_trusted_claim_snapshot(claim)
        print(json.dumps({
            "event": "MA4F_CLAIM_THEN_CRASH",
            "task_id": claim.agent_task_id,
            "worker_instance_id": worker_id,
            "lease_epoch": claim.lease_epoch,
            "fencing_token": claim.fencing_token,
        }, sort_keys=True), flush=True)
        os._exit(70)
    raise RuntimeError("Kafka consumer ended before receiving a command")


if __name__ == "__main__":
    main()
