from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Protocol

from app.models import ResearchPlan, ResearchSearchHit, ResearchTaskInput
from app.search import run_workspace_search
from app.source_profile import infer_search_lane, infer_source_domain, infer_source_quality


@dataclass(frozen=True)
class SearchQueryPlanItem:
    query: str
    family: str
    lane: str
    priority: int


class SearchAdapter(Protocol):
    def search(
        self,
        task_input: ResearchTaskInput,
        plan: ResearchPlan,
        remaining_budget: int | None = None,
    ) -> list[ResearchSearchHit]:
        """Return normalized research search hits."""


class ExternalSearchTransport(Protocol):
    def search(self, query: str, limit: int, page: int = 1) -> list[dict[str, object]]:
        """Return provider-specific raw search results."""


class WorkspaceSearchAdapter:
    def search(
        self,
        task_input: ResearchTaskInput,
        plan: ResearchPlan,
        remaining_budget: int | None = None,
    ) -> list[ResearchSearchHit]:
        query_plan = build_search_query_plan(plan, remaining_budget)
        hits = run_workspace_search(
            task_input,
            plan,
            selected_queries=[item.query for item in query_plan],
        )
        hits = _prioritize_hits_for_recovery(plan, hits)
        if remaining_budget is not None:
            hits = hits[:max(0, remaining_budget)]
        return [
            hit.model_copy(
                update={
                    "provider": "workspace",
                    "adapter": "workspace",
                    "provider_attempts": ["workspace"],
                    "provider_resolution": "workspace",
                    "provider_fallback_reason": "",
                    "url": hit.url or "",
                    "source_domain": infer_source_domain(hit.url or ""),
                    "source_quality": "WORKSPACE_SOURCE",
                    "source_quality_score": 0.98,
                    "search_lane": infer_search_lane(plan, hit.query),
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
        max_retries: int = 3,
    ) -> None:
        self.provider_name = provider_name
        self.api_key = api_key
        self.base_url = base_url.strip() or _default_base_url(provider_name)
        self.timeout_seconds = timeout_seconds
        self.max_retries = max(1, max_retries)
        self.last_attempt_count = 0

    def search(self, query: str, limit: int, page: int = 1) -> list[dict[str, object]]:
        if not self.api_key.strip() or limit <= 0:
            return []
        payload = json.dumps(self._payload(query, limit, page)).encode("utf-8")
        last_error: Exception | None = None
        self.last_attempt_count = 0
        for attempt in range(1, self.max_retries + 1):
            self.last_attempt_count = attempt
            request = urllib.request.Request(
                self.base_url,
                data=payload,
                headers=self._headers(),
                method="POST",
            )
            try:
                with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                    data = json.loads(response.read().decode("utf-8"))
                return _extract_raw_results(data, limit)
            except urllib.error.HTTPError as error:
                last_error = error
                if not _is_retryable_search_http_status(error.code):
                    break
                if attempt < self.max_retries:
                    time.sleep(min(2 ** (attempt - 1), 4))
            except (urllib.error.URLError, TimeoutError, ValueError, json.JSONDecodeError) as error:
                last_error = error
                if attempt < self.max_retries:
                    time.sleep(min(2 ** (attempt - 1), 4))
        del last_error
        return []

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

    def _payload(self, query: str, limit: int, page: int) -> dict[str, object]:
        payload: dict[str, object] = {
            "q": query,
            "num": limit,
            "page": max(1, page),
            "autocorrect": False,
        }
        if self.provider_name.lower() == "serper":
            payload["page"] = max(1, page)
        return payload


class HttpGetSearchTransport:
    """Fallback GET-based transport for providers that accept querystring search."""

    def __init__(
        self,
        provider_name: str,
        api_key: str,
        base_url: str,
        timeout_seconds: int = 20,
        max_retries: int = 3,
    ) -> None:
        self.provider_name = provider_name
        self.api_key = api_key.strip()
        self.base_url = base_url.strip()
        self.timeout_seconds = timeout_seconds
        self.max_retries = max(1, max_retries)
        self.last_attempt_count = 0

    def search(self, query: str, limit: int, page: int = 1) -> list[dict[str, object]]:
        if not self.api_key or not self.base_url or limit <= 0:
            return []
        params = urllib.parse.urlencode(
            {
                "q": query,
                "num": limit,
                "page": max(1, page),
            }
        )
        url = f"{self.base_url}?{params}"
        last_error: Exception | None = None
        self.last_attempt_count = 0
        for attempt in range(1, self.max_retries + 1):
            self.last_attempt_count = attempt
            request = urllib.request.Request(
                url,
                headers={
                    "User-Agent": "NoteWeaveResearchWorker/0.1",
                    "X-API-KEY": self.api_key,
                    "Authorization": f"Bearer {self.api_key}",
                },
                method="GET",
            )
            try:
                with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                    data = json.loads(response.read().decode("utf-8"))
                return _extract_raw_results(data, limit)
            except urllib.error.HTTPError as error:
                last_error = error
                if not _is_retryable_search_http_status(error.code):
                    break
                if attempt < self.max_retries:
                    time.sleep(min(2 ** (attempt - 1), 4))
            except (urllib.error.URLError, TimeoutError, ValueError, json.JSONDecodeError) as error:
                last_error = error
                if attempt < self.max_retries:
                    time.sleep(min(2 ** (attempt - 1), 4))
        del last_error
        return []


class ExternalSearchAdapter:
    def __init__(
        self,
        provider_name: str = "serper",
        api_key: str = "",
        base_url: str = "",
        transport: ExternalSearchTransport | None = None,
        max_provider_pages: int = 2,
    ) -> None:
        self.provider_name = provider_name.strip() or "serper"
        self.api_key = api_key.strip()
        self.transport = transport or _build_transport(
            provider_name=self.provider_name,
            api_key=self.api_key,
            base_url=base_url,
        )
        self.max_provider_pages = max(1, max_provider_pages)
        self.last_attempt_count = 0

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
        self.last_attempt_count = 0
        query_plan = build_search_query_plan(plan, remaining_budget)
        per_query_result_limit = _per_query_result_limit(plan)
        for query_item in query_plan:
            if len(hits) >= budget:
                break
            query_limit = budget - len(hits)
            page_limit = min(query_limit, per_query_result_limit, 10)
            if page_limit <= 0:
                break
            page_budget = min(self.max_provider_pages, _pages_for_query_limit(page_limit))
            for page in range(1, page_budget + 1):
                if len(hits) >= budget:
                    break
                raw_results = _transport_search_page(
                    self.transport,
                    query=query_item.query,
                    limit=page_limit,
                    page=page,
                )
                self.last_attempt_count += max(
                    1, int(getattr(self.transport, "last_attempt_count", 1) or 1)
                )
                for raw_result in raw_results:
                    if len(hits) >= budget:
                        break
                    normalized_hit = self._normalize_raw_result(
                        raw_result,
                        plan=plan,
                        query=query_item.query,
                        rank=len(hits) + 1,
                    )
                    if normalized_hit is not None:
                        hits.append(normalized_hit)
        return [
            hit.model_copy(update={"provider_attempt_count": max(1, self.last_attempt_count)})
            for hit in _prioritize_hits_for_recovery(plan, hits)
        ]

    def _normalize_raw_result(
        self,
        raw_result: dict[str, object],
        plan: ResearchPlan,
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
        source_domain = infer_source_domain(url)
        source_quality, source_quality_score = infer_source_quality(
            url=url,
            provider=self.provider_name,
            adapter="external",
            title=title,
        )
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
            provider_attempts=[self.provider_name],
            provider_resolution=self.provider_name,
            provider_fallback_reason="",
            source_domain=source_domain,
            source_quality=source_quality,
            source_quality_score=source_quality_score,
            search_lane=infer_search_lane(plan, query),
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
        attempted_external_providers: list[str] = []
        provider_quota = max(1, global_limit // max(1, len(self.adapters)))
        overflow: list[ResearchSearchHit] = []
        for adapter_index, adapter in enumerate(self.adapters):
            if len(merged) >= global_limit:
                break
            adapter_hits = adapter.search(
                task_input,
                plan,
                remaining_budget=min(provider_quota, global_limit - len(merged)),
            )
            adapter_hits = _annotate_provider_orchestration(
                adapter_hits,
                attempted_external_providers=attempted_external_providers,
            )
            merged = _merge_hits(merged, adapter_hits, global_limit)
            provider_name = _adapter_provider_name(adapter)
            if provider_name:
                attempted_external_providers.append(provider_name)
        # If provider quotas leave capacity unused, let adapters contribute
        # overflow in a second pass without sacrificing the first diverse slot.
        if len(merged) < global_limit:
            for adapter in self.adapters:
                adapter_hits = adapter.search(
                    task_input,
                    plan,
                    remaining_budget=global_limit - len(merged),
                )
                overflow = _merge_hits(overflow, adapter_hits, global_limit)
            merged = _merge_hits(merged, overflow, global_limit)
        return _finalize_ranking(merged[:global_limit])


def build_default_search_adapter() -> SearchAdapter:
    external_adapters = _build_external_search_adapters()
    if not external_adapters:
        return WorkspaceSearchAdapter()

    return CompositeSearchAdapter([WorkspaceSearchAdapter(), *external_adapters])


def run_research_search(
    task_input: ResearchTaskInput,
    plan: ResearchPlan,
    adapter: SearchAdapter | None = None,
    remaining_budget: int | None = None,
) -> list[ResearchSearchHit]:
    search_adapter = adapter or build_default_search_adapter()
    return search_adapter.search(task_input, plan, remaining_budget=remaining_budget)


def build_search_query_plan(
    plan: ResearchPlan,
    remaining_budget: int | None = None,
) -> list[SearchQueryPlanItem]:
    queries = _queries_for_recovery(plan)
    unique_queries = list(dict.fromkeys(query.strip() for query in queries if query.strip()))
    if not unique_queries:
        unique_queries = [plan.normalized_question]

    recovery_mode = str(plan.stop_contract.get("recovery_mode", "")).strip().upper()
    if recovery_mode:
        return [
            SearchQueryPlanItem(
                query=query,
                family=_classify_query_family(query),
                lane=infer_search_lane(plan, query),
                priority=index,
            )
            for index, query in enumerate(unique_queries, start=1)
        ]

    execution_profile = _execution_profile(plan)
    query_budget = max(1, int(execution_profile.get("search_query_budget", 4)))
    if remaining_budget is not None:
        query_budget = min(query_budget, max(1, remaining_budget))
    query_family_order = [
        str(item).strip()
        for item in execution_profile.get("query_family_order", [])
        if str(item).strip()
    ]
    family_budgets = {
        str(key).strip(): max(0, int(value))
        for key, value in dict(execution_profile.get("query_family_budgets", {})).items()
        if str(key).strip()
    }

    grouped_queries: dict[str, list[str]] = {}
    for query in unique_queries:
        family = _classify_query_family(query)
        grouped_queries.setdefault(family, []).append(query)

    selected_queries: list[str] = []
    selected_families: dict[str, int] = {}
    for family in query_family_order:
        family_budget = family_budgets.get(family, 1)
        if family_budget <= 0:
            continue
        for query in grouped_queries.get(family, []):
            if len(selected_queries) >= query_budget:
                break
            if selected_families.get(family, 0) >= family_budget:
                break
            if query in selected_queries:
                continue
            selected_queries.append(query)
            selected_families[family] = selected_families.get(family, 0) + 1
        if len(selected_queries) >= query_budget:
            break

    if not selected_queries:
        selected_queries.append(unique_queries[0])
    if unique_queries[0] not in selected_queries and len(selected_queries) < query_budget:
        selected_queries.insert(0, unique_queries[0])
        selected_queries = selected_queries[:query_budget]

    return [
        SearchQueryPlanItem(
            query=query,
            family=_classify_query_family(query),
            lane=infer_search_lane(plan, query),
            priority=index,
        )
        for index, query in enumerate(selected_queries, start=1)
    ]


def _queries_for_recovery(plan: ResearchPlan) -> list[str]:
    query_set = list(plan.query_set) or [plan.normalized_question]
    recovery_mode = str(plan.stop_contract.get("recovery_mode", "")).strip().upper()
    target_queries = [
        query.strip()
        for query in plan.stop_contract.get("recovery_target_queries", [])
        if str(query).strip()
    ]
    target_sources = [
        source.strip()
        for source in plan.stop_contract.get("recovery_target_sources", [])
        if str(source).strip()
    ]
    if recovery_mode != "COUNTERFACTUAL_RECHECK" and target_queries:
        return list(dict.fromkeys(target_queries))
    if recovery_mode != "COUNTERFACTUAL_RECHECK":
        return query_set

    counterfactual_queries = [
        query
        for query in query_set
        if "counterfactual" in query.lower()
    ]
    source_targeted_counterfactual_queries = [
        query
        for query in counterfactual_queries
        if any(source.lower() in query.lower() for source in target_sources)
    ]
    if source_targeted_counterfactual_queries:
        return list(dict.fromkeys(source_targeted_counterfactual_queries))
    targeted_counterfactual_queries = [
        query
        for query in target_queries
        if "counterfactual" in query.lower()
    ]
    if targeted_counterfactual_queries:
        return list(dict.fromkeys(targeted_counterfactual_queries))
    if counterfactual_queries:
        return list(dict.fromkeys(counterfactual_queries))

    prioritized: list[str] = []
    for query in query_set:
        if any(source.lower() in query.lower() for source in target_sources):
            prioritized.append(query)
    if prioritized:
        return list(dict.fromkeys(prioritized))
    return query_set[:1]


def _execution_profile(plan: ResearchPlan) -> dict[str, object]:
    return dict(plan.stop_contract.get("execution_profile", {}))


def _per_query_result_limit(plan: ResearchPlan) -> int:
    execution_profile = _execution_profile(plan)
    return max(1, int(execution_profile.get("per_query_result_limit", 2)))


def _classify_query_family(query: str) -> str:
    normalized = (query or "").strip().lower()
    if not normalized:
        return "direct"
    if "counterfactual" in normalized:
        return "counterfactual"
    if "verified evidence search" in normalized:
        return "verified_evidence"
    if "direct answer with evidence" in normalized:
        return "direct_evidence"
    if "coverage gap" in normalized:
        return "coverage_gap"
    if ":: deep focus ::" in normalized:
        return "deep_focus"
    if ":: triangulation ::" in normalized:
        return "triangulation"
    if ":: research goal ::" in normalized:
        return "intent"
    if ":: deliverable ::" in normalized:
        return "deliverable"
    if ":: time range ::" in normalized:
        return "time_range"
    if ":: constraint ::" in normalized:
        return "constraints"
    if "::" in normalized:
        return "source_scoped"
    return "direct"


def _prioritize_hits_for_recovery(
    plan: ResearchPlan,
    hits: list[ResearchSearchHit],
) -> list[ResearchSearchHit]:
    target_sources = {
        source.strip().lower()
        for source in plan.stop_contract.get("recovery_target_sources", [])
        if str(source).strip()
    }
    target_queries = {
        query.strip().lower()
        for query in plan.stop_contract.get("recovery_target_queries", [])
        if str(query).strip()
    }
    target_requirement_types = {
        requirement_type.strip().upper()
        for requirement_type in plan.stop_contract.get("recovery_target_requirement_types", [])
        if str(requirement_type).strip()
    }
    recovery_mode = str(plan.stop_contract.get("recovery_mode", "")).strip().upper()
    if not target_sources and not target_queries and not target_requirement_types:
        return hits
    prioritized = sorted(
        hits,
        key=lambda hit: (
            (
                (1 if hit.source_title.strip().lower() in target_sources else 0)
                if recovery_mode == "COUNTERFACTUAL_RECHECK"
                else (0 if hit.source_title.strip().lower() in target_sources else 1)
            ),
            0 if hit.query.strip().lower() in target_queries else 1,
            _requirement_hit_priority(target_requirement_types, hit),
            0 if any(source in hit.query.lower() for source in target_sources) else 1,
            hit.rank,
        ),
    )
    return [
        hit.model_copy(update={"rank": index})
        for index, hit in enumerate(prioritized, start=1)
    ]


def _requirement_hit_priority(
    target_requirement_types: set[str],
    hit: ResearchSearchHit,
) -> int:
    if not target_requirement_types:
        return 1
    text = " ".join([hit.source_title, hit.snippet, hit.query]).lower()
    priorities: list[int] = []
    if "CONFLICT_FINDING" in target_requirement_types:
        priorities.append(
            0
            if hit.search_angle == "counterfactual"
            or any(token in text for token in ["counterfactual", "conflict", "opposing", "alternative", "反证", "冲突"])
            else 2
        )
    if "CONSTRAINT_FINDING" in target_requirement_types:
        priorities.append(
            0
            if hit.search_angle in {"coverage_gap", "source_scoped"}
            or any(token in text for token in ["evidence", "verified", "report", "study", "data", "source", "证据", "验证"])
            else 1
        )
    if "GOAL_FINDING" in target_requirement_types:
        priorities.append(0 if hit.search_angle == "direct" else 1)
    return min(priorities) if priorities else 1


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


def _adapter_provider_name(adapter: SearchAdapter) -> str:
    provider_name = str(getattr(adapter, "provider_name", "") or "").strip()
    if not provider_name or provider_name.lower() == "workspace":
        return ""
    return provider_name


def _annotate_provider_orchestration(
    hits: list[ResearchSearchHit],
    *,
    attempted_external_providers: list[str],
) -> list[ResearchSearchHit]:
    annotated: list[ResearchSearchHit] = []
    attempted_chain = list(dict.fromkeys(item.strip() for item in attempted_external_providers if item.strip()))
    for hit in hits:
        provider = str(hit.provider or "").strip() or "workspace"
        if hit.adapter == "workspace":
            annotated.append(
                hit.model_copy(
                    update={
                        "provider_attempts": ["workspace"],
                        "provider_resolution": "workspace",
                        "provider_fallback_reason": "",
                    }
                )
            )
            continue
        provider_attempts = list(dict.fromkeys([*attempted_chain, provider]))
        annotated.append(
            hit.model_copy(
                update={
                    "provider_attempts": provider_attempts,
                    "provider_resolution": provider,
                    "provider_fallback_reason": (
                        "prior providers returned no retained hits"
                        if attempted_chain
                        else ""
                    ),
                }
            )
        )
    return annotated


def _default_base_url(provider_name: str) -> str:
    if provider_name.lower() == "serper":
        return "https://google.serper.dev/search"
    return provider_name


def _is_retryable_search_http_status(status: int) -> bool:
    return status in {408, 429} or status >= 500


def _build_transport(
    provider_name: str,
    api_key: str,
    base_url: str,
) -> ExternalSearchTransport:
    normalized = provider_name.strip().lower()
    if normalized in {"serper", "searchapi", "serpapi", "custom_json"}:
        return HttpJsonSearchTransport(
            provider_name=provider_name,
            api_key=api_key,
            base_url=base_url,
        )
    return HttpGetSearchTransport(
        provider_name=provider_name,
        api_key=api_key,
        base_url=base_url,
    )


def _build_external_search_adapters() -> list[ExternalSearchAdapter]:
    provider_chain = [
        item.strip()
        for item in (
            os.getenv("NOTEWEAVE_RESEARCH_SEARCH_PROVIDER_CHAIN", "")
            or os.getenv("NOTEWEAVE_RESEARCH_SEARCH_PROVIDER", "")
        ).split(",")
        if item.strip()
    ]
    if not provider_chain:
        provider_chain = ["serper"]

    adapters: list[ExternalSearchAdapter] = []
    for index, provider_name in enumerate(provider_chain):
        api_key = _provider_env(
            provider_name,
            "API_KEY",
            fallbacks=[
                "NOTEWEAVE_RESEARCH_SEARCH_API_KEY",
                "SERPER_API_KEY",
                "SEARCH_API_KEY",
            ] if index == 0 else [],
        )
        if not api_key:
            continue
        base_url = _provider_env(
            provider_name,
            "BASE_URL",
            fallbacks=[
                "NOTEWEAVE_RESEARCH_SEARCH_BASE_URL",
                "SERPER_BASE_URL",
                "SEARCH_API_BASE",
            ] if index == 0 else [],
        )
        adapters.append(
            ExternalSearchAdapter(
                provider_name=provider_name,
                api_key=api_key,
                base_url=base_url,
                max_provider_pages=_max_provider_pages(),
            )
        )
    return adapters


def _provider_env(provider_name: str, suffix: str, fallbacks: list[str] | None = None) -> str:
    normalized = re.sub(r"[^A-Z0-9]+", "_", provider_name.strip().upper()).strip("_")
    keys = [f"NOTEWEAVE_RESEARCH_{normalized}_{suffix}"]
    if fallbacks:
        keys.extend(fallbacks)
    for key in keys:
        value = os.getenv(key, "").strip()
        if value:
            return value
    return ""


def _max_provider_pages() -> int:
    try:
        return max(1, min(5, int(os.getenv("NOTEWEAVE_RESEARCH_SEARCH_MAX_PAGES", "2"))))
    except ValueError:
        return 2


def _pages_for_query_limit(query_limit: int) -> int:
    if query_limit <= 10:
        return 1
    if query_limit <= 20:
        return 2
    return 3


def _transport_search_page(
    transport,
    *,
    query: str,
    limit: int,
    page: int,
):
    try:
        return transport.search(query, limit, page=page)
    except TypeError:
        return transport.search(query, limit)


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
