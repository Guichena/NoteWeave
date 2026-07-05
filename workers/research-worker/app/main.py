from fastapi import FastAPI
from pydantic import BaseModel

from app.config import load_settings
from app.callback import ResearchWorkerExecutionResponse, run_research_task_with_callbacks
from app.models import ResearchTaskInput
from app.runner import run_research_task

settings = load_settings()
app = FastAPI(title="NoteWeave Research Worker")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "worker_type": settings.worker_type}


class DebugResearchRunResponse(BaseModel):
    events: list[dict[str, object]]
    result: dict[str, object]


@app.post("/debug/run-task", response_model=DebugResearchRunResponse)
def debug_run_task(task_input: ResearchTaskInput) -> DebugResearchRunResponse:
    events, result = run_research_task(task_input)
    return DebugResearchRunResponse(
        events=[event.model_dump(mode="json") for event in events],
        result=result.model_dump(mode="json"),
    )


@app.post("/tasks/{task_id}/run", response_model=ResearchWorkerExecutionResponse)
def run_task_from_java(task_id: str) -> ResearchWorkerExecutionResponse:
    return run_research_task_with_callbacks(task_id)
