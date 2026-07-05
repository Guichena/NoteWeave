from __future__ import annotations

import hashlib
import json
import os
import re
import urllib.error
import urllib.request
from typing import Protocol

from app.models import ResearchPlan, ResearchSearchHit, ResearchTaskInput
from app.search import run_workspace_search


class SearchAdapter(Protocol):
    def search(
        self,
        task_input: ResearchTaskInput,
        plan: ResearchPlan,
        remaining_budget: int | None = None,
    ) -> list[ResearchSearchHit]:
        """Return normalized research search hits."""


class ExternalSearchTransport(Protocol):
    def search(self, query: str, limit: int) -> list[dict[str, object]]:
        """Return provider-specific raw search results."""


class WorkspaceSearchAdapter:
    def search(
        self,
        task_input: ResearchTaskInput,
        plan: ResearchPlan,
        remaining_budget: int | None = None,
    ) -> list[ResearchSearchHit]:
        hits = run_workspace_search(task_input, plan)
        if remaining_budget is not None:
            hits = hits[:max(0, remaining_budget)]
        return [
            hit.model_copy(
                update={
                    "provider": "workspace",
                    "adapter": "workspace",
                    "url": hit.url or "",
                }
            )
            for hit in hits
        ]


class HttpJsonSearchTransport:
    """Small Serper-compatible transport kept dependency-free for worker startup."""

    def __init__(
        self,
        provider_name: str,
        api_key: str,
        base_url: str = "",
        timeout_seconds: int = 20,
    ) -> None:
        self.provider_name = provider_name
        self.api_key = api_key
        self.base_url = base_url.strip() or _default_base_url(provider_name)
        self.timeout_seconds = timeout_seconds

    def search(self, query: str, limit: int) -> list[dict[str, object]]:
        if not self.api_key.strip() or limit <= 0:
            return []
        payload = json.dumps({"q": query, "num": limit, "autocorrect": False}).encode("utf-8")
        request = urllib.request.Request(
            self.base_url,
            data=payload,
            headers=self._headers(),
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                data = json.loads(response.read().decode("utf-8"))
        except (urllib.error.URLError, TimeoutError, ValueError, json.JSONDecodeError):
            return []
        return _extract_raw_results(data, limit)

    def _headers(self) -> dict[str, str]:
        if self.provider_name.lower() == "serper":
            return {
                "Content-Type": "application/json",
                "X-API-KEY": self.api_key,
            }
        return {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {self.api_key}",
        }


class ExternalSearchAdapter:
    def __init__(
        self,
        provider_name: str = "serper",
        api_key: str = "",
        base_url: str = "",
        transport: ExternalSearchTransport | None = None,
    ) -> None:
        self.provider_name = provider_name.strip() or "serper"
        self.api_key = api_key.strip()
        self.transport = transport or HttpJsonSearchTransport(
            provider_name=self.provider_name,
            api_key=self.api_key,
            base_url=base_url,
        )

    def search(
        self,
        task_input: ResearchTaskInput,
        plan: ResearchPlan,
        remaining_budget: int | None = None,
    ) -> list[ResearchSearchHit]:
        del task_input
        if not self.api_key:
            return []
        budget = _resolve_budget(plan, remaining_budget)
        if budget <= 0:
            return []

        hits: list[ResearchSearchHit] = []
        for query in plan.query_set or [plan.normalized_question]:
            if len(hits) >= budget:
                break
            query_limit = budget - len(hits)
            for raw_result in self.transport.search(query, query_limit):
                if len(hits) >= budget:
                    break
                normalized_hit = self._normalize_raw_result(
                    raw_result,
                    query=query,
                    rank=len(hits) + 1,
                )
                if normalized_hit is not None:
                    hits.append(normalized_hit)
        return hits

    def _normalize_raw_result(
        self,
        raw_result: dict[str, object],
        query: str,
        rank: int,
    ) -> ResearchSearchHit | None:
        title = str(raw_result.get("title") or raw_result.get("name") or "").strip()
        url = str(raw_result.get("url") or raw_result.get("link") or "").strip()
        snippet = str(
            raw_result.get("snippet")
            or raw_result.get("description")
            or raw_result.get("content")
            or ""
        ).strip()
        if not title and not snippet and not url:
            return None
        if not title:
            title = url or f"{self.provider_name} result"
        confidence_score = _normalize_score(raw_result.get("score"), default=0.74)
        search_angle = _search_angle(query)
        matched_fields = _matched_fields(query, title, snippet, url)
        coverage_score = _coverage_score(query, title, snippet)
        stable_key = _stable_key(url or title or snippet)
        return ResearchSearchHit(
            hit_id=f"external-{stable_key[:12]}",
            source_id=f"web-{stable_key[:16]}",
            source_title=title,
            query=query,
            rank=rank,
            snippet=snippet or url or title,
            confidence_score=confidence_score,
            retrieval_reason=(
                f"{self.provider_name} external search matched {search_angle} query; "
                "result normalized for later read snapshot"
            ),
            search_angle=search_angle,
            matched_fields=matched_fields,
            coverage_score=coverage_score,
            url=url,
            provider=self.provider_name,
            adapter="external",
        )


class CompositeSearchAdapter:
    def __init__(self, adapters: list[SearchAdapter]) -> None:
        self.adapters = adapters

    def search(
        self,
        task_input: ResearchTaskInput,
        plan: ResearchPlan,
        remaining_budget: int | None = None,
    ) -> list[ResearchSearchHit]:
        global_limit = _resolve_budget(plan, remaining_budget)
        merged: list[ResearchSearchHit] = []
        for adapter in self.adapters:
            if len(merged) >= global_limit:
                break
            adapter_hits = adapter.search(
                task_input,
                plan,
                remaining_budget=global_limit - len(merged),
            )
            merged = _merge_hits(merged, adapter_hits, global_limit)
        return _finalize_ranking(merged[:global_limit])


def build_default_search_adapter() -> SearchAdapter:
    api_key = (
        os.getenv("NOTEWEAVE_RESEARCH_SEARCH_API_KEY", "").strip()
        or os.getenv("SERPER_API_KEY", "").strip()
        or os.getenv("SEARCH_API_KEY", "").strip()
    )
    if not api_key:
        return WorkspaceSearchAdapter()

    provider_name = os.getenv("NOTEWEAVE_RESEARCH_SEARCH_PROVIDER", "serper")
    base_url = (
        os.getenv("NOTEWEAVE_RESEARCH_SEARCH_BASE_URL", "").strip()
        or os.getenv("SERPER_BASE_URL", "").strip()
        or os.getenv("SEARCH_API_BASE", "").strip()
    )
    return CompositeSearchAdapter(
        [
            WorkspaceSearchAdapter(),
            ExternalSearchAdapter(
                provider_name=provider_name,
                api_key=api_key,
                base_url=base_url,
            ),
        ]
    )


def run_research_search(
    task_input: ResearchTaskInput,
    plan: ResearchPlan,
    adapter: SearchAdapter | None = None,
) -> list[ResearchSearchHit]:
    search_adapter = adapter or build_default_search_adapter()
    return search_adapter.search(task_input, plan)


def _merge_hits(
    existing_hits: list[ResearchSearchHit],
    incoming_hits: list[ResearchSearchHit],
    global_limit: int,
) -> list[ResearchSearchHit]:
    merged = list(existing_hits)
    keys_by_index = [_dedupe_keys(hit) for hit in merged]
    for incoming_hit in incoming_hits:
        matched_index = _find_duplicate_index(incoming_hit, keys_by_index)
        if matched_index is None:
            if len(merged) < global_limit:
                merged.append(incoming_hit)
                keys_by_index.append(_dedupe_keys(incoming_hit))
            continue
        existing_hit = merged[matched_index]
        if _should_replace(existing_hit, incoming_hit):
            merged[matched_index] = incoming_hit
            keys_by_index[matched_index] = _dedupe_keys(incoming_hit)
    return merged


def _should_replace(existing_hit: ResearchSearchHit, incoming_hit: ResearchSearchHit) -> bool:
    if existing_hit.adapter == "workspace" and incoming_hit.adapter != "workspace":
        return False
    if existing_hit.adapter != "workspace" and incoming_hit.adapter == "workspace":
        return True
    if incoming_hit.confidence_score > existing_hit.confidence_score:
        return True
    return (
        incoming_hit.confidence_score == existing_hit.confidence_score
        and existing_hit.search_angle == "direct"
        and incoming_hit.search_angle != "direct"
    )


def _find_duplicate_index(
    incoming_hit: ResearchSearchHit,
    keys_by_index: list[set[str]],
) -> int | None:
    incoming_keys = _dedupe_keys(incoming_hit)
    for index, existing_keys in enumerate(keys_by_index):
        if incoming_keys.intersection(existing_keys):
            return index
    return None


def _dedupe_keys(hit: ResearchSearchHit) -> set[str]:
    keys = set()
    if hit.source_id.strip():
        keys.add(f"source:{hit.source_id.strip().lower()}")
    if hit.url.strip():
        keys.add(f"url:{_normalize_url(hit.url)}")
    if hit.source_title.strip():
        keys.add(f"title:{_normalize_text(hit.source_title)}")
    return keys


def _finalize_ranking(hits: list[ResearchSearchHit]) -> list[ResearchSearchHit]:
    return [
        hit.model_copy(
            update={
                "rank": index,
                "hit_id": f"hit-{index}",
            }
        )
        for index, hit in enumerate(hits, start=1)
    ]


def _resolve_budget(plan: ResearchPlan, remaining_budget: int | None) -> int:
    if remaining_budget is not None:
        return max(0, remaining_budget)
    return max(0, int(plan.stop_contract.get("global_search_limit", 8)))


def _default_base_url(provider_name: str) -> str:
    if provider_name.lower() == "serper":
        return "https://google.serper.dev/search"
    return provider_name


def _extract_raw_results(data: dict[str, object], limit: int) -> list[dict[str, object]]:
    for key in ["organic", "results", "items"]:
        raw_items = data.get(key)
        if isinstance(raw_items, list):
            return [
                item
                for item in raw_items[:limit]
                if isinstance(item, dict)
            ]
    return []


def _normalize_score(score: object, default: float) -> float:
    try:
        value = float(score)
    except (TypeError, ValueError):
        value = default
    return min(0.99, max(0.05, round(value, 4)))


def _search_angle(query: str) -> str:
    query_lower = query.lower()
    if "counterfactual" in query_lower:
        return "counterfactual"
    if "coverage gap" in query_lower:
        return "coverage_gap"
    if "::" in query:
        return "source_scoped"
    return "direct"


def _matched_fields(query: str, title: str, snippet: str, url: str) -> list[str]:
    query_terms = _terms(query)
    fields: list[str] = []
    for field_name, field_value in [
        ("title", title),
        ("snippet", snippet),
        ("url", url),
    ]:
        if query_terms.intersection(_terms(field_value)):
            fields.append(field_name)
    return fields or ["provider_result"]


def _coverage_score(query: str, title: str, snippet: str) -> float:
    query_terms = _terms(query)
    if not query_terms:
        return 0.0
    result_terms = _terms(f"{title} {snippet}")
    return round(len(query_terms.intersection(result_terms)) / len(query_terms), 4)


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


def _stable_key(value: str) -> str:
    return hashlib.sha256(value.strip().lower().encode("utf-8")).hexdigest()


def _normalize_text(value: str) -> str:
    return re.sub(r"\s+", " ", value.strip().lower())


def _normalize_url(value: str) -> str:
    return value.strip().lower().rstrip("/")
