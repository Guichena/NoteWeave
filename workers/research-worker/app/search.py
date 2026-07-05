from __future__ import annotations

from app.models import ResearchPlan, ResearchSearchHit, ResearchTaskInput, SourceScopeItem


def run_workspace_search(
    task_input: ResearchTaskInput,
    plan: ResearchPlan,
) -> list[ResearchSearchHit]:
    """Resolve the query bundle against the current workspace source scope."""
    global_limit = int(plan.stop_contract.get("global_search_limit", 8))
    if global_limit <= 0 or not task_input.source_scope:
        return []

    hits: list[ResearchSearchHit] = []
    for source_index, source in enumerate(task_input.source_scope, start=1):
        if len(hits) >= global_limit:
            break
        query = _select_query(plan, source_index)
        snippet = _build_snippet(source, plan)
        hits.append(
            ResearchSearchHit(
                hit_id=f"hit-{len(hits) + 1}",
                source_id=source.source_id,
                source_title=source.title,
                query=query,
                rank=len(hits) + 1,
                snippet=snippet,
                confidence_score=_score_source_match(source, snippet),
                retrieval_reason=(
                    "workspace source matched the research query bundle by title, "
                    "summary, or explicit source scope"
                ),
            )
        )
    return hits


def _select_query(plan: ResearchPlan, source_index: int) -> str:
    if not plan.query_set:
        return plan.normalized_question
    query_index = min(source_index, len(plan.query_set) - 1)
    return plan.query_set[query_index]


def _build_snippet(source: SourceScopeItem, plan: ResearchPlan) -> str:
    summary = source.summary.strip()
    if summary:
        return summary
    sample_text = source.sample_text.strip()
    if sample_text:
        return sample_text
    return (
        f"{source.title} is in the workspace scope but has no summary. "
        f"Open it only for cautious evidence related to {plan.normalized_question}."
    )


def _score_source_match(source: SourceScopeItem, snippet: str) -> float:
    if source.summary.strip():
        return 0.82
    if source.sample_text.strip():
        return 0.68
    if snippet.strip():
        return 0.42
    return 0.2
