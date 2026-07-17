from app.config import load_settings
from app.main import app


def test_research_worker_imports() -> None:
    settings = load_settings()
    assert settings.worker_type == "research"
    assert app.title == "NoteWeave Research Worker"


def test_research_worker_should_use_only_its_namespaced_llm_configuration(monkeypatch) -> None:
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_LLM_API_KEY", "research-key")
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_LLM_BASE_URL", "https://research.example/v1")
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_LLM_MODEL", "research-model")
    monkeypatch.setenv("NOTEWEAVE_LLM_API_KEY", "backend-key-must-not-leak")
    monkeypatch.setenv("NOTEWEAVE_LLM_BASE_URL", "https://backend.example/v1")
    monkeypatch.setenv("NOTEWEAVE_LLM_MODEL", "backend-model")

    settings = load_settings()

    assert settings.llm_api_key == "research-key"
    assert settings.llm_base_url == "https://research.example/v1"
    assert settings.llm_model == "research-model"

    monkeypatch.delenv("NOTEWEAVE_RESEARCH_LLM_API_KEY")
    monkeypatch.delenv("NOTEWEAVE_RESEARCH_LLM_BASE_URL")
    monkeypatch.delenv("NOTEWEAVE_RESEARCH_LLM_MODEL")
    isolated_settings = load_settings()

    assert isolated_settings.llm_api_key == ""
    assert isolated_settings.llm_base_url == ""
    assert isolated_settings.llm_model == ""
