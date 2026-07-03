from app.config import load_settings
from app.main import app


def test_artifact_worker_imports() -> None:
    settings = load_settings()
    assert settings.worker_type == "artifact"
    assert app.title == "NoteWeave Artifact Worker"
