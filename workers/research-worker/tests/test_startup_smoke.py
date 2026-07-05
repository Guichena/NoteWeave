import importlib

from app.config import load_settings
from app.main import health


def test_health_should_return_research_worker_type() -> None:
    response = health()

    assert response["status"] == "ok"
    assert response["worker_type"] == "research"


def test_kafka_consumer_module_should_import() -> None:
    module = importlib.import_module("app.kafka_consumer")

    assert hasattr(module, "run_research_kafka_consumer_forever")


def test_kafka_bootstrap_servers_should_be_overridable(monkeypatch) -> None:
    monkeypatch.setenv("NOTEWEAVE_KAFKA_BOOTSTRAP_SERVERS", "kafka-a:9092,kafka-b:9092")

    settings = load_settings()

    assert settings.kafka_bootstrap_servers == "kafka-a:9092,kafka-b:9092"
