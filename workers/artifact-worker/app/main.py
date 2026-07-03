from fastapi import FastAPI

from app.config import load_settings

settings = load_settings()
app = FastAPI(title="NoteWeave Artifact Worker")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "worker_type": settings.worker_type}
