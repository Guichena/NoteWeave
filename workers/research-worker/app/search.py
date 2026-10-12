from __future__ import annotations

from dataclasses import dataclass
from collections import Counter
import math
import re

from app.models import ResearchPlan, ResearchSearchHit, ResearchTaskInput, SourceScopeItem
from app.query_specs import find_query_spec
from app.text_terms import terms as text_terms
from app.source_profile import infer_source_quality


@dataclass
class _SearchCandidate:
    source: SourceScopeItem
    query: str
    search_angle: str
    snippet: str
    matched_fields: list[str]
    coverage_score: float
    confidence_score: float
    source_quality: str
    source_quality_score: float
    source_index: int


def run_seed_source_search(
    task_input: ResearchTaskInput,
    plan: ResearchPlan,
    selected_queries: list[str] | None = None,
) -> list[ResearchSearchHit]:
    """Match queries only against seed snapshots explicitly selected for this run.

    This is not Workspace index retrieval: sources absent from ``source_scope``
    are never discovered or searched here.
    """
    global_limit = int(plan.stop_contract.get("global_search_limit", 8))
    if (global_limit <= 0 or not task_input.source_scope
            or selected_queries == []
            or (not plan.query_set and plan.stop_contract.get("query_planning_method"))):
        return []

    corpus = _Bm25f(task_input.source_scope)
    candidates: list[_SearchCandidate] = []
    for source_index, source in enumerate(task_input.source_scope, start=1):
        query, search_angle, matched_fields, coverage_score = _select_best_query(
            plan,
            source,
            source_index,
            selected_queries=selected_queries,
            corpus=corpus,
        )
        snippet = _build_snippet(source, plan)
        source_quality, quality = infer_source_quality(url="", provider="workspace", adapter="workspace",
                                          title=source.title, content_text=source.sample_text or source.summary)
        confidence_score = round(coverage_score * quality, 4)
        candidates.append(
            _SearchCandidate(
                source=source,
                query=query,
                search_angle=search_angle,
                snippet=snippet,
                matched_fields=matched_fields,
                coverage_score=coverage_score,
                confidence_score=confidence_score,
                source_quality=source_quality, source_quality_score=quality,
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
                source_snapshot_id=source.source_snapshot_id,
                source_window_id=source.source_window_id,
                workspace_window_text=source.sample_text,
                source_title=source.title,
                query=candidate.query,
                rank=len(hits) + 1,
                snippet=candidate.snippet,
                confidence_score=candidate.confidence_score,
                retrieval_reason=_build_retrieval_reason(candidate),
                search_angle=candidate.search_angle,
                matched_fields=candidate.matched_fields,
                coverage_score=candidate.coverage_score,
                source_quality=candidate.source_quality,
                source_quality_score=candidate.source_quality_score,
            )
        )
    return hits


# Compatibility alias for older tests and callers. New code must use the seed
# terminology so Workspace ownership is not confused with evidence scope.
run_workspace_search = run_seed_source_search


def _select_best_query(
    plan: ResearchPlan,
    source: SourceScopeItem,
    source_index: int,
    selected_queries: list[str] | None = None,
    corpus=None,
) -> tuple[str, str, list[str], float]:
    corpus = corpus or _Bm25f([source])
    query_candidates = list(selected_queries if selected_queries is not None else plan.query_set)
    if not query_candidates:
        return plan.normalized_question, "direct", [], 0.0
    best_query = query_candidates[0]
    best_score = -1.0
    best_fields: list[str] = []
    for query in query_candidates:
        matched_fields, coverage_score = corpus.score(query, source)
        if coverage_score > best_score:
            best_query = query
            best_score = coverage_score
            best_fields = matched_fields
    spec = find_query_spec(plan, best_query)
    angle = spec.search_angle if spec is not None else "direct"
    return best_query, angle, best_fields, max(best_score, 0.0)


def _build_snippet(source: SourceScopeItem, plan: ResearchPlan) -> str:
    summary = source.summary.strip()
    if summary:
        return summary
    sample_text = source.sample_text.strip()
    if sample_text:
        return sample_text
    return (
        f"{source.title} is an explicitly selected seed with no summary. "
        f"Open it only for cautious evidence related to {plan.normalized_question}."
    )


_FIELD_WEIGHTS = {"title": 2.0, "summary": 1.5, "sample_text": 1.0}


def _frequencies(text):
    # 保留重复词项供 BM25F 计算词频；中文与现有词项切分保持一致。
    counts = Counter()
    for run in re.findall(r"[a-z0-9]+|[一-鿿]+", str(text or "").lower()):
        if re.fullmatch(r"[一-鿿]+", run):
            counts.update([run] if len(run) == 1 else [run[i:i + 2] for i in range(len(run) - 1)])
        elif len(run) >= 3:
            counts[run] += 1
    return counts


class _Bm25f:
    def __init__(self, sources):
        self.fields = {id(source): {key: _frequencies(getattr(source, key)) for key in _FIELD_WEIGHTS}
                       for source in sources}
        self.size = max(1, len(sources))
        self.averages = {key: max(1.0, sum(sum(fields[key].values()) for fields in self.fields.values()) / self.size)
                         for key in _FIELD_WEIGHTS}
        self.df = Counter()
        for fields in self.fields.values():
            self.df.update(set().union(*(set(values) for values in fields.values())))

    def score(self, query, source):
        query_terms = _terms(query)
        fields = self.fields[id(source)]
        matched = [key for key, counts in fields.items() if query_terms.intersection(counts)]
        score = 0.0
        for term in query_terms:
            weighted_tf = sum(weight * counts.get(term, 0) /
                              (0.25 + 0.75 * sum(counts.values()) / self.averages[key])
                              for key, weight in _FIELD_WEIGHTS.items() for counts in [fields[key]])
            idf = math.log1p((self.size - self.df[term] + 0.5) / (self.df[term] + 0.5))
            score += idf * weighted_tf * 2.2 / (weighted_tf + 1.2)
        # 有界归一化，无词项匹配时相关度必须为零。
        return matched, round(score / (score + 1.0), 4)


def _terms(text: str) -> set[str]:
    return text_terms(text)


def _build_retrieval_reason(candidate: _SearchCandidate) -> str:
    matched_fields = (
        ", ".join(candidate.matched_fields)
        if candidate.matched_fields
        else "fallback selected seed"
    )
    return (
        f"{candidate.search_angle} query matched selected seed fields: "
        f"{matched_fields}; coverage={candidate.coverage_score:.2f}"
    )
