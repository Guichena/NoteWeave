from __future__ import annotations

from app.fetch_adapters import run_research_fetch
from app.models import ResearchPlan, ResearchReadWindow, ResearchSearchHit, ResearchTaskInput
from app.read_adapters import run_research_read


def open_read_windows(
    task_input: ResearchTaskInput,
    plan: ResearchPlan,
    search_hits: list[ResearchSearchHit],
) -> list[ResearchReadWindow]:
    """Open bounded, goal-conditioned reading windows from search hits."""
    fetched_documents = run_research_fetch(task_input, plan, search_hits)
    return run_research_read(
        task_input,
        plan,
        fetched_documents=fetched_documents,
    )
