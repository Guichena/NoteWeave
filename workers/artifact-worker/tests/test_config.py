from __future__ import annotations

import pytest

from app.config import Settings


def _set_valid_production_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("NOTEWEAVE_ENVIRONMENT", "production")
    monkeypatch.setenv("NOTEWEAVE_ARTIFACT_INTERNAL_AUTH_TOKEN", "a" * 32)
    monkeypatch.setenv("NOTEWEAVE_ARTIFACT_CALLBACK_SECRET", "b" * 32)
    monkeypatch.setenv("NOTEWEAVE_JAVA_BASE_URL", "https://noteweave.example")


def test_production_rejects_debug_routes(monkeypatch: pytest.MonkeyPatch) -> None:
    _set_valid_production_environment(monkeypatch)
    monkeypatch.setenv("NOTEWEAVE_DEBUG_ROUTES_ENABLED", "true")

    with pytest.raises(ValueError, match="production forbids artifact worker debug routes"):
        Settings()


def test_production_accepts_debug_routes_disabled(monkeypatch: pytest.MonkeyPatch) -> None:
    _set_valid_production_environment(monkeypatch)
    monkeypatch.setenv("NOTEWEAVE_DEBUG_ROUTES_ENABLED", "false")

    assert Settings().debug_routes_enabled is False
