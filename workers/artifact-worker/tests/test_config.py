from __future__ import annotations

import pytest

from app.config import Settings


def _set_valid_production_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("NOTEWEAVE_ENVIRONMENT", "production")
    monkeypatch.setenv("NOTEWEAVE_ARTIFACT_INTERNAL_AUTH_TOKEN", "a" * 32)
    monkeypatch.setenv("NOTEWEAVE_ARTIFACT_CALLBACK_SECRET", "b" * 32)
    monkeypatch.setenv("NOTEWEAVE_JAVA_BASE_URL", "https://noteweave.example")
    monkeypatch.setenv("NOTEWEAVE_ARTIFACT_REPOSITORY_BACKEND", "file")


def test_production_rejects_debug_routes(monkeypatch: pytest.MonkeyPatch) -> None:
    _set_valid_production_environment(monkeypatch)
    monkeypatch.setenv("NOTEWEAVE_DEBUG_ROUTES_ENABLED", "true")

    with pytest.raises(ValueError, match="production forbids artifact worker debug routes"):
        Settings()


def test_production_accepts_debug_routes_disabled(monkeypatch: pytest.MonkeyPatch) -> None:
    _set_valid_production_environment(monkeypatch)
    monkeypatch.setenv("NOTEWEAVE_DEBUG_ROUTES_ENABLED", "false")

    assert Settings().debug_routes_enabled is False


def test_production_rejects_memory_artifact_repository(monkeypatch: pytest.MonkeyPatch) -> None:
    _set_valid_production_environment(monkeypatch)
    monkeypatch.setenv("NOTEWEAVE_ARTIFACT_REPOSITORY_BACKEND", "memory")

    with pytest.raises(ValueError, match="file artifact repository backend"):
        Settings()


def test_production_requires_consumer_and_poll_budget_above_mcp_timeout(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _set_valid_production_environment(monkeypatch)
    monkeypatch.setenv("NOTEWEAVE_ARTIFACT_CONSUMER_ENABLED", "false")
    with pytest.raises(ValueError, match="durable Artifact Kafka consumer"):
        Settings()

    monkeypatch.setenv("NOTEWEAVE_ARTIFACT_CONSUMER_ENABLED", "true")
    monkeypatch.setenv("NOTEWEAVE_MCP_PROCESS_TIMEOUT_SECONDS", "4200")
    monkeypatch.setenv("NOTEWEAVE_KAFKA_ARTIFACT_MAX_POLL_INTERVAL_SECONDS", "4200")
    with pytest.raises(ValueError, match="max poll interval"):
        Settings()
