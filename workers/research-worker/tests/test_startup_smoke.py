import importlib
from pathlib import Path

from app.config import load_settings
from app.main import health


ROOT = Path(__file__).resolve().parents[3]


def test_health_should_return_research_worker_type() -> None:
    response = health()

    assert response["status"] == "ok"
    assert response["worker_type"] == "research"


def test_agent_kafka_consumer_module_should_import() -> None:
    module = importlib.import_module("app.agent_kafka_consumer")

    assert hasattr(module, "run_research_agent_kafka_consumer_forever")


def test_start_script_should_launch_the_only_incremental_agent_consumer() -> None:
    script = (ROOT / "workers" / "scripts" / "start-research-consumer.ps1").read_text(encoding="utf-8")

    assert "python -m app.agent_kafka_consumer" in script
    assert "python -m app.kafka_consumer" not in script


def test_kafka_bootstrap_servers_should_be_overridable(monkeypatch) -> None:
    monkeypatch.setenv("NOTEWEAVE_KAFKA_BOOTSTRAP_SERVERS", "kafka-a:9092,kafka-b:9092")

    settings = load_settings()

    assert settings.kafka_bootstrap_servers == "kafka-a:9092,kafka-b:9092"


def test_kafka_consume_retry_settings_should_be_overridable(monkeypatch) -> None:
    monkeypatch.setenv("NOTEWEAVE_KAFKA_CONSUME_MAX_ATTEMPTS", "5")
    monkeypatch.setenv("NOTEWEAVE_KAFKA_CONSUME_RAISE_ON_FAILURE", "true")

    settings = load_settings()

    assert settings.kafka_consume_max_attempts == 5
    assert settings.kafka_consume_raise_on_failure is True
