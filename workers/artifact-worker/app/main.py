from fastapi import FastAPI
from pydantic import BaseModel

from app.config import load_settings
from app.models import ArtifactTaskInput
from app.runner import run_artifact_task

settings = load_settings()
app = FastAPI(title="NoteWeave Artifact Worker")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "worker_type": settings.worker_type}


class DebugArtifactRunResponse(BaseModel):
    events: list[dict[str, object]]
    result: dict[str, object]


@app.post("/debug/run-task", response_model=DebugArtifactRunResponse)
def debug_run_task(task_input: ArtifactTaskInput) -> DebugArtifactRunResponse:
    events, result = run_artifact_task(task_input)
    return DebugArtifactRunResponse(
        events=[event.model_dump(mode="json") for event in events],
        result=result.model_dump(mode="json"),
    )
