import importlib
from pathlib import Path

from app.config import load_settings


ROOT = Path(__file__).resolve().parents[3]


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

    settings = load_settings()

    assert settings.kafka_consume_max_attempts == 5


def test_legacy_whole_run_consumer_should_remain_deleted() -> None:
    assert not (ROOT / "workers" / "research-worker" / "app" / "kafka_consumer.py").exists()
    assert not (ROOT / "workers" / "research-worker" / "app" / "callback.py").exists()


def test_health_only_http_worker_surface_should_remain_deleted() -> None:
    worker_root = ROOT / "workers" / "research-worker"
    dockerfile = (worker_root / "Dockerfile").read_text(encoding="utf-8")
    pyproject = (worker_root / "pyproject.toml").read_text(encoding="utf-8")

    assert not (worker_root / "app" / "main.py").exists()
    assert not (ROOT / "workers" / "scripts" / "start-research-api.ps1").exists()
    assert 'CMD ["python", "-m", "app.agent_kafka_consumer"]' in dockerfile
    assert "uvicorn" not in dockerfile
    assert "fastapi" not in pyproject
    assert "uvicorn" not in pyproject
