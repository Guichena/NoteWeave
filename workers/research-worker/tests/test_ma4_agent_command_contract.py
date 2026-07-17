from __future__ import annotations

import ast
from pathlib import Path

import pytest
from pydantic import ValidationError


def test_agent_command_should_accept_only_minimal_versioned_identity() -> None:
    from app.agent_command_contract import ResearchAgentCommand

    command = ResearchAgentCommand.model_validate(
        {
            "schema_version": "research-agent-command.v1",
            "command_id": "command-1",
            "research_run_id": "run-1",
            "agent_task_id": "agent-task-1",
            "idempotency_key": "outbox-1",
            "delivery_attempt": 1,
        }
    )

    assert command.agent_task_id == "agent-task-1"
    assert command.delivery_attempt == 1
    assert command.model_dump(mode="json")["schema_version"] == "research-agent-command.v1"


@pytest.mark.parametrize(
    "payload",
    [
        {"schema_version": "v0", "command_id": "c", "research_run_id": "r", "agent_task_id": "t", "idempotency_key": "i"},
        {"schema_version": "research-agent-command.v1", "command_id": "c", "research_run_id": "r", "agent_task_id": "t", "idempotency_key": "i", "api_key": "secret"},
        {"schema_version": "research-agent-command.v1", "command_id": "c", "research_run_id": "r", "agent_task_id": "t", "idempotency_key": "i", "target_cells": ["must-claim-from-server"]},
    ],
)
def test_agent_command_should_reject_unknown_or_sensitive_payload_fields(payload) -> None:
    from app.agent_command_contract import ResearchAgentCommand

    with pytest.raises(ValidationError):
        ResearchAgentCommand.model_validate(payload)


def test_atomic_worker_source_should_have_no_legacy_submission_contract_or_route() -> None:
    app_dir = Path(__file__).resolve().parents[1] / "app"
    contract = (app_dir / "agent_command_contract.py").read_text(encoding="utf-8")
    client = (app_dir / "agent_task_client.py").read_text(encoding="utf-8")
    consumer = (app_dir / "agent_kafka_consumer.py").read_text(encoding="utf-8")

    contract_classes = {
        node.name for node in ast.walk(ast.parse(contract)) if isinstance(node, ast.ClassDef)
    }
    client_classes = {
        node.name for node in ast.walk(ast.parse(client)) if isinstance(node, ast.ClassDef)
    }
    client_functions = {
        node.name for node in ast.walk(ast.parse(client)) if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
    }

    assert "ResearchAgentExecutionSubmission" not in contract_classes
    assert "AgentExecutionReceipt" not in client_classes
    assert "submit" not in client_functions
    assert "ResearchAgentExecutionSubmission" not in consumer
    assert ".submit(" not in consumer
    assert "/submit" not in client
