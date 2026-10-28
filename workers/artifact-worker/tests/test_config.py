"""退休配置移除后，生产传输约束仍然有效。"""

import pytest
from app.config import Settings


def production(monkeypatch):
    monkeypatch.setenv("NOTEWEAVE_ENVIRONMENT", "production")
    monkeypatch.setenv("NOTEWEAVE_ARTIFACT_INTERNAL_AUTH_TOKEN", "a" * 32)
    monkeypatch.setenv("NOTEWEAVE_ARTIFACT_CALLBACK_SECRET", "b" * 32)
    monkeypatch.setenv("NOTEWEAVE_JAVA_BASE_URL", "https://noteweave.example")


def test_production_transport_accepts_dedicated_credentials(monkeypatch):
    production(monkeypatch)
    assert Settings().artifact_consumer_enabled


@pytest.mark.parametrize(("name", "value", "error"), [
    ("NOTEWEAVE_ARTIFACT_CONSUMER_ENABLED", "false", "durable Artifact Kafka consumer"),
    ("NOTEWEAVE_JAVA_BASE_URL", "http://example", "HTTPS"),
    ("NOTEWEAVE_ARTIFACT_INTERNAL_AUTH_TOKEN", "short", "sufficient entropy"),
    ("NOTEWEAVE_ARTIFACT_CALLBACK_SECRET", "short", "sufficient entropy"),
    ("NOTEWEAVE_KAFKA_ARTIFACT_MAX_POLL_INTERVAL_SECONDS", "1", "greater than or equal"),
])
def test_production_transport_keeps_required_guards(monkeypatch, name, value, error):
    production(monkeypatch)
    monkeypatch.setenv(name, value)
    with pytest.raises(ValueError, match=error):
        Settings()
