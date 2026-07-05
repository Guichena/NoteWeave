from __future__ import annotations

import re
from dataclasses import dataclass

from app.models import ResearchPlan, ResearchSearchHit, ResearchTaskInput, SourceScopeItem


@dataclass
class _SearchCandidate:
    source: SourceScopeItem
    query: str
    search_angle: str
    snippet: str
    matched_fields: list[str]
    coverage_score: float
    confidence_score: float
    source_index: int


def run_workspace_search(
    task_input: ResearchTaskInput,
    plan: ResearchPlan,
) -> list[ResearchSearchHit]:
    """Resolve the query bundle against the current workspace source scope."""
    global_limit = int(plan.stop_contract.get("global_search_limit", 8))
    if global_limit <= 0 or not task_input.source_scope:
        return []

    candidates: list[_SearchCandidate] = []
    for source_index, source in enumerate(task_input.source_scope, start=1):
        query, search_angle, matched_fields, coverage_score = _select_best_query(
            plan,
            source,
            source_index,
        )
        snippet = _build_snippet(source, plan)
        confidence_score = _score_source_match(source, snippet, coverage_score)
        candidates.append(
            _SearchCandidate(
                source=source,
                query=query,
                search_angle=search_angle,
                snippet=snippet,
                matched_fields=matched_fields,
                coverage_score=coverage_score,
                confidence_score=confidence_score,
                source_index=source_index,
            )
        )

    ranked_candidates = sorted(
        candidates,
        key=lambda candidate: (-candidate.confidence_score, candidate.source_index),
    )[:global_limit]

    hits: list[ResearchSearchHit] = []
    for candidate in ranked_candidates:
        source = candidate.source
        hits.append(
            ResearchSearchHit(
                hit_id=f"hit-{len(hits) + 1}",
                source_id=source.source_id,
                source_title=source.title,
                query=candidate.query,
                rank=len(hits) + 1,
                snippet=candidate.snippet,
                confidence_score=candidate.confidence_score,
                retrieval_reason=_build_retrieval_reason(candidate),
                search_angle=candidate.search_angle,
                matched_fields=candidate.matched_fields,
                coverage_score=candidate.coverage_score,
            )
        )
    return hits


def _select_best_query(
    plan: ResearchPlan,
    source: SourceScopeItem,
    source_index: int,
) -> tuple[str, str, list[str], float]:
    if not plan.query_set:
        return plan.normalized_question, "direct", [], 0.0
    best_query = _select_query(plan, source_index)
    best_score = -1.0
    best_fields: list[str] = []
    best_index = source_index
    for query_index, query in enumerate(plan.query_set):
        matched_fields, coverage_score = _score_query_coverage(query, source)
        if coverage_score > best_score:
            best_query = query
            best_score = coverage_score
            best_fields = matched_fields
            best_index = query_index
    return best_query, _search_angle(best_query, best_index), best_fields, max(best_score, 0.0)


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


def _score_source_match(source: SourceScopeItem, snippet: str, coverage_score: float) -> float:
    if source.summary.strip():
        base_score = 0.82
    elif source.sample_text.strip():
        base_score = 0.68
    elif snippet.strip():
        base_score = 0.42
    else:
        base_score = 0.2
    return min(0.98, round(base_score + coverage_score * 0.16, 4))


def _score_query_coverage(query: str, source: SourceScopeItem) -> tuple[list[str], float]:
    query_terms = _terms(query)
    if not query_terms:
        return [], 0.0

    field_hits: list[str] = []
    matched_terms: set[str] = set()
    for field_name, field_text in [
        ("title", source.title),
        ("summary", source.summary),
        ("sample_text", source.sample_text),
    ]:
        overlap = query_terms.intersection(_terms(field_text))
        if overlap:
            field_hits.append(field_name)
            matched_terms.update(overlap)
    score = len(matched_terms) / len(query_terms)
    if source.title.strip() and source.title.lower() in query.lower():
        score += 0.2
    return field_hits, min(1.0, round(score, 4))


def _terms(text: str) -> set[str]:
    terms = {
        token
        for token in re.findall(r"[a-zA-Z0-9]+", text.lower())
        if len(token) >= 3
    }
    for chunk in re.findall(r"[\u4e00-\u9fff]+", text):
        if len(chunk) == 1:
            terms.add(chunk)
            continue
        for index in range(len(chunk) - 1):
            terms.add(chunk[index:index + 2])
    return terms


def _search_angle(query: str, query_index: int) -> str:
    query_lower = query.lower()
    if "counterfactual" in query_lower:
        return "counterfactual"
    if "coverage gap" in query_lower:
        return "coverage_gap"
    if "::" in query:
        return "source_scoped"
    if query_index == 0:
        return "direct"
    return "source_scoped"


def _build_retrieval_reason(candidate: _SearchCandidate) -> str:
    matched_fields = (
        ", ".join(candidate.matched_fields)
        if candidate.matched_fields
        else "fallback source scope"
    )
    return (
        f"{candidate.search_angle} query matched workspace source fields: "
        f"{matched_fields}; coverage={candidate.coverage_score:.2f}"
    )
