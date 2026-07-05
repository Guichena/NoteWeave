from __future__ import annotations

from app.models import ResearchPlan, ResearchReadWindow, ResearchSearchHit, ResearchTaskInput


def open_read_windows(
    task_input: ResearchTaskInput,
    plan: ResearchPlan,
    search_hits: list[ResearchSearchHit],
) -> list[ResearchReadWindow]:
    """Open bounded, goal-conditioned reading windows from search hits."""
    del task_input
    retention_budget = int(plan.stop_contract.get("tool_response_retention_budget", 5))
    if retention_budget <= 0:
        return []

    windows: list[ResearchReadWindow] = []
    for hit in search_hits[:retention_budget]:
        focus = (
            f"Read {hit.source_title} for claims that answer: "
            f"{plan.normalized_question}"
        )
        windows.append(
            ResearchReadWindow(
                window_id=f"window-{len(windows) + 1}",
                hit_id=hit.hit_id,
                source_id=hit.source_id,
                source_title=hit.source_title,
                query=hit.query,
                read_focus=focus,
                window_text=hit.snippet,
                retention_reason=(
                    "kept by tool_response_retention_budget because the hit is "
                    "inside the current workspace scope"
                ),
                token_estimate=max(1, len(hit.snippet.split())),
            )
        )
    return windows
