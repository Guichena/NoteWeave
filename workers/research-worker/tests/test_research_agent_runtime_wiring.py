from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]


def test_main_compose_should_start_the_incremental_agent_consumer_by_default() -> None:
    compose = (ROOT / "docker-compose.yml").read_text(encoding="utf-8")

    assert 'command: ["python", "-m", "app.agent_kafka_consumer"]' in compose
    assert "NOTEWEAVE_RESEARCH_AGENT_CONSUMER_ENABLED: ${NOTEWEAVE_RESEARCH_AGENT_CONSUMER_ENABLED:-true}" in compose
    assert "NOTEWEAVE_RESEARCH_AGENT_DEEP_CELL_EXECUTOR_ENABLED: ${NOTEWEAVE_RESEARCH_AGENT_DEEP_CELL_EXECUTOR_ENABLED:-true}" in compose
    assert "NOTEWEAVE_RESEARCH_AGENT_WORKER_INSTANCE_ID: ${NOTEWEAVE_RESEARCH_AGENT_WORKER_INSTANCE_ID:-research-agent-worker-1}" in compose
    assert "NOTEWEAVE_RESEARCH_AGENT_EXECUTION_MODE: ${NOTEWEAVE_RESEARCH_AGENT_EXECUTION_MODE:-INCREMENTAL_V1}" in compose
    assert '- "127.0.0.1:8081:8081"' in compose


def test_example_environment_should_describe_the_only_supported_execution_mode() -> None:
    example = (ROOT / ".env.example").read_text(encoding="utf-8")

    assert "NOTEWEAVE_RESEARCH_AGENT_CONSUMER_ENABLED=true" in example
    assert "NOTEWEAVE_RESEARCH_AGENT_DEEP_CELL_EXECUTOR_ENABLED=true" in example
    assert "NOTEWEAVE_RESEARCH_AGENT_WORKER_INSTANCE_ID=research-agent-worker-1" in example
    assert "NOTEWEAVE_RESEARCH_AGENT_EXECUTION_MODE=INCREMENTAL_V1" in example
