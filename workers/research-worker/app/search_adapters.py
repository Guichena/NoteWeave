from __future__ import annotations

from dataclasses import dataclass
import hashlib
import html
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Protocol

from app.http_security import credential_safe_urlopen
from app.mcp_search_client import (
    SERPER_SEARCH_TOOL,
    TAVILY_SEARCH_TOOL,
    McpSearchClient,
    McpToolError,
    default_mcp_search_client,
)
from app.models import ResearchPlan, ResearchSearchHit, ResearchTaskInput
from app.query_specs import find_query_spec
from app.search import run_seed_source_search
from app.source_profile import infer_search_lane, infer_source_domain, infer_source_quality
from app.text_terms import terms as text_terms
from app.query_execution import record_query


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


class SeedSourceSearchAdapter:
    """Searches only the immutable seed samples supplied in the task snapshot."""

    def search(
        self,
        task_input: ResearchTaskInput,
        plan: ResearchPlan,
        remaining_budget: int | None = None,
    ) -> list[ResearchSearchHit]:
        query_plan = build_search_query_plan(plan, remaining_budget)
        for item in query_plan:
            record_query(item.query)
        hits = run_seed_source_search(
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
                    "source_quality": hit.source_quality,
                    "source_quality_score": hit.source_quality_score,
                    "search_lane": infer_search_lane(plan, hit.query),
                }
            )
            for hit in hits
        ]


# Backward-compatible import name. This adapter has never searched the full
# Workspace index; it only matches explicitly selected task seed snapshots.
WorkspaceSearchAdapter = SeedSourceSearchAdapter


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
        self.last_fatal_failure = ""

    def search(self, query: str, limit: int, page: int = 1) -> list[dict[str, object]]:
        self.last_fatal_failure = ""
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
                with credential_safe_urlopen(request, timeout=self.timeout_seconds) as response:
                    data = json.loads(response.read().decode("utf-8"))
                return _extract_raw_results(data, limit)
            except urllib.error.HTTPError as error:
                last_error = error
                self.last_fatal_failure = _fatal_provider_failure(error.code, _http_error_text(error))
                if self.last_fatal_failure or not _is_retryable_search_http_status(error.code):
                    break
                if attempt < self.max_retries:
                    time.sleep(min(2 ** (attempt - 1), 4))
            except (urllib.error.URLError, TimeoutError, OSError, ValueError, json.JSONDecodeError) as error:
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


class McpSearchTransport:
    """经搜索代理 MCP 服务调用 Serper 或 Tavily。

    Serper 支持翻页；Tavily 没有翻页参数，只返回第一页。
    """

    TOOLS = {"serper_mcp": SERPER_SEARCH_TOOL, "tavily_mcp": TAVILY_SEARCH_TOOL}

    def __init__(self, provider_name: str, client: McpSearchClient, max_retries: int = 3) -> None:
        self.provider_name = provider_name.strip().lower()
        if self.provider_name not in self.TOOLS:
            raise ValueError(f"unsupported MCP search provider: {provider_name}")
        self.client = client
        self.max_retries = max(1, max_retries)
        self.last_attempt_count = 0
        self.last_fatal_failure = ""

    def search(self, query: str, limit: int, page: int = 1) -> list[dict[str, object]]:
        self.last_fatal_failure = ""
        if limit <= 0:
            return []
        if self.provider_name == "tavily_mcp":
            if page > 1:
                return []
            arguments: dict[str, object] = {"query": query, "max_results": min(limit, 20)}
        else:
            arguments = {"q": query, "num": limit, "page": max(1, page), "autocorrect": False}
        self.last_attempt_count = 0
        for attempt in range(1, self.max_retries + 1):
            self.last_attempt_count = attempt
            try:
                data = self.client.call_tool(self.TOOLS[self.provider_name], arguments)
                return _extract_raw_results(data, limit)
            except McpToolError as error:
                self.last_fatal_failure = _fatal_provider_failure(None, str(error))
                if self.last_fatal_failure or not error.retryable:
                    break
            except urllib.error.HTTPError as error:
                self.last_fatal_failure = _fatal_provider_failure(error.code, _http_error_text(error))
                if self.last_fatal_failure or not _is_retryable_search_http_status(error.code):
                    break
            except (urllib.error.URLError, TimeoutError, OSError, ValueError, json.JSONDecodeError):
                pass
            if attempt < self.max_retries:
                time.sleep(min(2 ** (attempt - 1), 4))
        return []


DEEPSEEK_SEARCH_URL = "https://api.deepseek.com/v1/tools/search"
# DeepSeek 搜索的 query 最长 128 个字符
DEEPSEEK_MAX_QUERY_CHARS = 128


class DeepSeekSearchTransport(HttpJsonSearchTransport):
    """DeepSeek 搜索接口：POST /v1/tools/search，Bearer 鉴权，按请求计费，没有翻页。

    返回的 summary（约 600 到 800 字，供模型阅读）比 snippet 更完整，作为摘要使用；
    rerank_score 为 0 到 3，换算成 0 到 1 的置信度。
    """

    def __init__(self, api_key: str, base_url: str = "", timeout_seconds: int = 20, max_retries: int = 3) -> None:
        super().__init__("deepseek", api_key, base_url or DEEPSEEK_SEARCH_URL, timeout_seconds, max_retries)

    def search(self, query: str, limit: int, page: int = 1) -> list[dict[str, object]]:
        if page > 1:
            return []
        results = super().search(query[:DEEPSEEK_MAX_QUERY_CHARS], limit, page)
        mapped: list[dict[str, object]] = []
        seen: set[str] = set()
        for item in results:
            try:
                score = round(max(0.0, min(1.0, float(item.get("rerank_score") or 0) / 3)), 4)
            except (TypeError, ValueError):
                score = 0.0
            url = _safe_result_url(str(item.get("url") or ""))
            # 同一页面的不同分块去锚点后网址相同，只保留排在前面的一条；外语版本的文档与中英文版内容重复，直接跳过
            if not url or url in seen or _foreign_locale(url):
                continue
            seen.add(url)
            title = " ".join(str(item.get("title") or "").split()) or (urllib.parse.urlsplit(url).hostname or url)
            mapped.append({
                "title": title[:280],
                "url": url,
                "snippet": item.get("summary") or item.get("snippet") or "",
                "score": score,
                "date": item.get("date") or "",
            })
        return mapped

    def _headers(self) -> dict[str, str]:
        return {"Content-Type": "application/json", "Authorization": f"Bearer {self.api_key}"}

    def _payload(self, query: str, limit: int, page: int) -> dict[str, object]:
        del page
        return {"query": query, "count": max(1, min(limit, 50))}


_LOCALE_SEGMENT = re.compile(r"^([a-z]{2})[_-][a-z]{2}$", re.IGNORECASE)


def _foreign_locale(url: str) -> bool:
    """路径中出现 fr_fr、ko-kr 这类语言代码且语言既不是中文也不是英文时返回 True。"""
    for segment in urllib.parse.urlsplit(url).path.split("/"):
        match = _LOCALE_SEGMENT.match(segment)
        if match and match.group(1).lower() not in {"en", "zh"}:
            return True
    return False


def _safe_result_url(url: str) -> str:
    """把结果网址里的中文、空格等字符做百分号编码；归档接口要求标准 URI，标题也不能为空。"""
    # 去掉锚点：DeepSeek 会在网址后追加分块编号（如 #1、#870#758），出现两个 # 时 Java 的 URI 解析会失败，锚点对抓取也没有作用
    url = url.strip().split("#", 1)[0]
    if not url.lower().startswith(("http://", "https://")):
        return ""
    # Java 的 URI 解析不接受路径里的方括号和不成对的百分号，这两类也一并编码
    url = re.sub(r"%(?![0-9A-Fa-f]{2})", "%25", url)
    return urllib.parse.quote(url, safe=":/?#@!$&'()*+,;=%~")


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
                with credential_safe_urlopen(request, timeout=self.timeout_seconds) as response:
                    data = json.loads(response.read().decode("utf-8"))
                return _extract_raw_results(data, limit)
            except urllib.error.HTTPError as error:
                last_error = error
                if not _is_retryable_search_http_status(error.code):
                    break
                if attempt < self.max_retries:
                    time.sleep(min(2 ** (attempt - 1), 4))
            except (urllib.error.URLError, TimeoutError, OSError, ValueError, json.JSONDecodeError) as error:
                last_error = error
                if attempt < self.max_retries:
                    time.sleep(min(2 ** (attempt - 1), 4))
        del last_error
        return []


class HttpWikipediaSearchTransport:
    """Credential-free public search used only when explicitly enabled."""

    def __init__(
        self,
        base_url: str = "https://en.wikipedia.org/w/api.php",
        timeout_seconds: int = 20,
        max_retries: int = 3,
    ) -> None:
        self.base_url = base_url.strip() or "https://en.wikipedia.org/w/api.php"
        self.timeout_seconds = timeout_seconds
        self.max_retries = max(1, max_retries)
        self.last_attempt_count = 0

    def search(self, query: str, limit: int, page: int = 1) -> list[dict[str, object]]:
        if not query.strip() or limit <= 0:
            return []
        params = urllib.parse.urlencode({
            "action": "query",
            "list": "search",
            "srsearch": query.strip(),
            "srlimit": min(50, limit),
            "sroffset": max(0, page - 1) * min(50, limit),
            "format": "json",
            "formatversion": 2,
            "utf8": 1,
        })
        request = urllib.request.Request(
            f"{self.base_url}?{params}",
            headers={"User-Agent": "NoteWeaveResearchWorker/0.1 (public-demo-search)"},
            method="GET",
        )
        self.last_attempt_count = 0
        for attempt in range(1, self.max_retries + 1):
            self.last_attempt_count = attempt
            try:
                with credential_safe_urlopen(request, timeout=self.timeout_seconds) as response:
                    data = json.loads(response.read().decode("utf-8"))
                raw_items = data.get("query", {}).get("search", [])
                return [
                    {
                        "title": str(item.get("title") or "").strip(),
                        "url": f"https://en.wikipedia.org/?curid={int(item.get('pageid'))}",
                        "snippet": re.sub(
                            r"\s+",
                            " ",
                            html.unescape(re.sub(r"<[^>]+>", " ", str(item.get("snippet") or ""))),
                        ).strip(),
                        "score": 0.78,
                    }
                    for item in raw_items[:limit]
                    if isinstance(item, dict) and item.get("pageid") is not None
                ]
            except urllib.error.HTTPError as error:
                if not _is_retryable_search_http_status(error.code):
                    break
            except (urllib.error.URLError, TimeoutError, OSError, ValueError, TypeError, json.JSONDecodeError):
                pass
            if attempt < self.max_retries:
                time.sleep(min(2 ** (attempt - 1), 4))
        return []


class ExternalSearchAdapter:
    def __init__(
        self,
        provider_name: str = "serper",
        api_key: str = "",
        base_url: str = "",
        transport: ExternalSearchTransport | None = None,
        max_provider_pages: int = 2,
        requires_api_key: bool = True,
    ) -> None:
        self.provider_name = provider_name.strip() or "serper"
        self.api_key = api_key.strip()
        self.transport = transport or _build_transport(
            provider_name=self.provider_name,
            api_key=self.api_key,
            base_url=base_url,
        )
        self.max_provider_pages = max(1, max_provider_pages)
        self.requires_api_key = requires_api_key
        self.last_attempt_count = 0
        # 最近一次搜索因额度用尽或鉴权失败而完全没有结果时记录原因，否则为空
        self.last_fatal_failure = ""
        # 最近一条检索词遇到的额度或鉴权失败
        self.query_fatal_failure = ""

    def search(
        self,
        task_input: ResearchTaskInput,
        plan: ResearchPlan,
        remaining_budget: int | None = None,
    ) -> list[ResearchSearchHit]:
        del task_input
        self.last_fatal_failure = ""
        if self.requires_api_key and not self.api_key:
            return []
        budget = _resolve_budget(plan, remaining_budget)
        if budget <= 0:
            return []

        hits: list[ResearchSearchHit] = []
        self.last_attempt_count = 0
        for query_item in build_search_query_plan(plan, remaining_budget):
            if len(hits) >= budget:
                break
            query_hits = self.search_query(plan, query_item.query, budget - len(hits), rank_offset=len(hits))
            if self.query_fatal_failure:
                # 额度和鉴权问题与查询内容无关，继续发后续查询只会重复失败
                if not hits:
                    self.last_fatal_failure = self.query_fatal_failure
                break
            hits.extend(query_hits)
        return [
            hit.model_copy(update={"provider_attempt_count": max(1, self.last_attempt_count)})
            for hit in _prioritize_hits_for_recovery(plan, hits)
        ]

    def search_query(
        self,
        plan: ResearchPlan,
        query: str,
        query_limit: int,
        rank_offset: int = 0,
    ) -> list[ResearchSearchHit]:
        """执行一条检索词；遇到额度或鉴权失败时记录在 query_fatal_failure 并停止翻页。"""
        self.query_fatal_failure = ""
        page_limit = min(query_limit, _per_query_result_limit(plan), 10)
        if page_limit <= 0:
            return []
        hits: list[ResearchSearchHit] = []
        for page in range(1, min(self.max_provider_pages, _pages_for_query_limit(page_limit)) + 1):
            if len(hits) >= query_limit:
                break
            record_query(query)
            raw_results = _transport_search_page(self.transport, query=query, limit=page_limit, page=page)
            self.last_attempt_count += max(1, int(getattr(self.transport, "last_attempt_count", 1) or 1))
            fatal_failure = str(getattr(self.transport, "last_fatal_failure", "") or "")
            if fatal_failure and not raw_results:
                self.query_fatal_failure = fatal_failure
                break
            for raw_result in raw_results:
                if len(hits) >= query_limit:
                    break
                normalized_hit = self._normalize_raw_result(
                    raw_result, plan=plan, query=query, rank=rank_offset + len(hits) + 1)
                if normalized_hit is not None:
                    hits.append(normalized_hit)
        return hits

    def _normalize_raw_result(
        self,
        raw_result: dict[str, object],
        plan: ResearchPlan,
        query: str,
        rank: int,
    ) -> ResearchSearchHit | None:
        title = str(raw_result.get("title") or raw_result.get("name") or "").strip()
        url = str(raw_result.get("url") or raw_result.get("link") or "").strip()
        # 所有渠道的结果网址统一做百分号编码，含中文或空格的网址否则会被归档接口拒收
        url = _safe_result_url(url) if url.lower().startswith(("http://", "https://")) else url
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
        spec = find_query_spec(plan, query)
        search_angle = spec.search_angle if spec is not None else _search_angle(query)
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
    """组合多个搜索渠道。

    merge：每个外部渠道各分一部分名额，名额有剩余时再让所有渠道补一轮。
    fallback：外部渠道按优先级排列，每条检索词先交给第一个渠道，
    只有它失败或没有结果时才依次交给后面的渠道；额度用尽或鉴权失败的渠道在本次检索中不再调用。
    按调用计费的搜索服务用这种方式，每条检索词通常只计费一次。
    """

    def __init__(self, adapters: list[SearchAdapter], mode: str = "merge") -> None:
        self.adapters = adapters
        self.mode = mode if mode in {"merge", "fallback"} else "merge"

    def search(
        self,
        task_input: ResearchTaskInput,
        plan: ResearchPlan,
        remaining_budget: int | None = None,
    ) -> list[ResearchSearchHit]:
        global_limit = _resolve_budget(plan, remaining_budget)
        external_adapters = [adapter for adapter in self.adapters if isinstance(adapter, ExternalSearchAdapter)]
        if self.mode == "fallback":
            merged = self._search_with_fallback(task_input, plan, global_limit, remaining_budget)
        else:
            merged = self._search_with_merge(task_input, plan, global_limit)
        if not merged and external_adapters and all(
                getattr(adapter, "last_fatal_failure", "") for adapter in external_adapters):
            # 所有外部搜索渠道都因额度用尽或鉴权失败不可用，继续研究只会得到空结论。
            # 抛出后 Worker 上报投递失败，后端按该原因码让整次研究立即失败。
            raise RuntimeError("RESEARCH_WEB_PROVIDER_UNAVAILABLE")
        return _finalize_ranking(merged[:global_limit])

    def _search_with_fallback(
        self,
        task_input: ResearchTaskInput,
        plan: ResearchPlan,
        global_limit: int,
        remaining_budget: int | None,
    ) -> list[ResearchSearchHit]:
        merged: list[ResearchSearchHit] = []
        external_adapters: list[ExternalSearchAdapter] = []
        for adapter in self.adapters:
            if isinstance(adapter, ExternalSearchAdapter):
                external_adapters.append(adapter)
                continue
            merged = _merge_hits(merged, adapter.search(task_input, plan, remaining_budget=global_limit - len(merged)),
                                 global_limit)
        for adapter in external_adapters:
            adapter.last_fatal_failure = ""
            adapter.last_attempt_count = 0
            if adapter.requires_api_key and not adapter.api_key:
                adapter.last_fatal_failure = "AUTH_FAILED"
        retained = {adapter.provider_name: False for adapter in external_adapters}
        unavailable: set[str] = set()
        for query_item in build_search_query_plan(plan, remaining_budget):
            if len(merged) >= global_limit:
                break
            attempted: list[str] = []
            for adapter in external_adapters:
                if adapter.last_fatal_failure or adapter.provider_name in unavailable:
                    continue
                query_hits = adapter.search_query(plan, query_item.query, global_limit - len(merged),
                                                  rank_offset=len(merged))
                attempted.append(adapter.provider_name)
                if adapter.query_fatal_failure:
                    unavailable.add(adapter.provider_name)
                    if not retained[adapter.provider_name]:
                        adapter.last_fatal_failure = adapter.query_fatal_failure
                if not query_hits:
                    continue
                retained[adapter.provider_name] = True
                reason = f"{', '.join(attempted[:-1])} returned no results for this query" if len(attempted) > 1 else ""
                merged = _merge_hits(merged, [
                    hit.model_copy(update={
                        "provider_attempts": attempted,
                        "provider_resolution": adapter.provider_name,
                        "provider_fallback_reason": reason,
                        "provider_attempt_count": max(1, adapter.last_attempt_count),
                    })
                    for hit in _prioritize_hits_for_recovery(plan, query_hits)
                ], global_limit)
                break
        return merged

    def _search_with_merge(
        self,
        task_input: ResearchTaskInput,
        plan: ResearchPlan,
        global_limit: int,
    ) -> list[ResearchSearchHit]:
        merged: list[ResearchSearchHit] = []
        attempted_external_providers: list[str] = []
        provider_quota = max(1, global_limit // max(1, len(self.adapters)))
        overflow: list[ResearchSearchHit] = []
        for adapter in self.adapters:
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
                if getattr(adapter, "last_fatal_failure", ""):
                    continue
                adapter_hits = adapter.search(
                    task_input,
                    plan,
                    remaining_budget=global_limit - len(merged),
                )
                overflow = _merge_hits(overflow, adapter_hits, global_limit)
            merged = _merge_hits(merged, overflow, global_limit)
        return merged


def build_default_search_adapter() -> SearchAdapter:
    external_adapters = _build_external_search_adapters()
    if not external_adapters:
        return SeedSourceSearchAdapter()

    return CompositeSearchAdapter([SeedSourceSearchAdapter(), *external_adapters], mode=_search_chain_mode())


def build_web_search_adapter() -> SearchAdapter:
    """Build an external-only search adapter or fail instead of falling back to seeds."""
    external_adapters = _build_external_search_adapters()
    if not external_adapters:
        raise RuntimeError("RESEARCH_WEB_PROVIDER_UNAVAILABLE")
    return CompositeSearchAdapter(external_adapters, mode=_search_chain_mode())


def build_web_plus_seed_search_adapter() -> SearchAdapter:
    """Build strict Web + seed search; Web availability is part of the mode contract."""
    external_adapters = _build_external_search_adapters()
    if not external_adapters:
        raise RuntimeError("RESEARCH_WEB_PROVIDER_UNAVAILABLE")
    return CompositeSearchAdapter([SeedSourceSearchAdapter(), *external_adapters], mode=_search_chain_mode())


def _search_chain_mode() -> str:
    """NOTEWEAVE_RESEARCH_SEARCH_CHAIN_MODE：fallback（默认，按优先级逐条回退）或 merge。"""
    return os.getenv("NOTEWEAVE_RESEARCH_SEARCH_CHAIN_MODE", "fallback").strip().lower() or "fallback"


def run_research_search(
    task_input: ResearchTaskInput,
    plan: ResearchPlan,
    adapter: SearchAdapter | None = None,
    remaining_budget: int | None = None,
) -> list[ResearchSearchHit]:
    search_adapter = adapter or _adapter_for_retrieval_mode(task_input.retrieval_mode)
    return search_adapter.search(task_input, plan, remaining_budget=remaining_budget)


def _adapter_for_retrieval_mode(retrieval_mode: str) -> SearchAdapter:
    mode = retrieval_mode.strip().upper()
    if mode == "WEB_ONLY":
        return build_web_search_adapter()
    if mode == "WEB_PLUS_SEEDS":
        return build_web_plus_seed_search_adapter()
    if mode == "SOURCES_ONLY":
        return SeedSourceSearchAdapter()
    return build_default_search_adapter()


def build_search_query_plan(
    plan: ResearchPlan,
    remaining_budget: int | None = None,
) -> list[SearchQueryPlanItem]:
    if remaining_budget is not None and remaining_budget <= 0:
        return []
    if not plan.query_set and plan.stop_contract.get("query_planning_method"):
        return []
    if plan.query_specs:
        return _query_plan_from_specs(plan, remaining_budget)
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


def _query_plan_from_specs(
    plan: ResearchPlan,
    remaining_budget: int | None,
) -> list[SearchQueryPlanItem]:
    """结构化检索词已经按先宽后窄排好序，这里只按预算截断。"""
    query_budget = max(1, int(_execution_profile(plan).get("search_query_budget", len(plan.query_specs))))
    if remaining_budget is not None:
        query_budget = min(query_budget, max(1, remaining_budget))
    items: list[SearchQueryPlanItem] = []
    seen: set[str] = set()
    for spec in plan.query_specs:
        query = spec.query.strip()
        if not query or query in seen:
            continue
        seen.add(query)
        items.append(SearchQueryPlanItem(
            query=query, family=spec.family, lane=spec.search_lane, priority=len(items) + 1,
        ))
        if len(items) >= query_budget:
            break
    return items


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
    if provider_name.lower() in {"wikipedia", "mediawiki"}:
        return "https://en.wikipedia.org/w/api.php"
    return provider_name


_QUOTA_MARKERS = ("insufficient quota", "not enough credits", "quota exceeded", "out of credits",
                  "insufficient balance", "insufficient credits")


def _fatal_provider_failure(status: int | None, text: str) -> str:
    """识别与查询无关、重试也不会恢复的搜索失败：额度用尽或鉴权失败。"""
    lowered = text.lower()
    if status == 402 or "http 402" in lowered or any(marker in lowered for marker in _QUOTA_MARKERS):
        return "QUOTA_EXHAUSTED"
    if status in {401, 403} or "http 401" in lowered or "http 403" in lowered:
        return "AUTH_FAILED"
    return ""


def _http_error_text(error: urllib.error.HTTPError) -> str:
    try:
        return error.read().decode("utf-8", "replace")[:500]
    except Exception:  # noqa: BLE001
        return ""


def _is_retryable_search_http_status(status: int) -> bool:
    return status in {408, 429} or status >= 500


def _build_transport(
    provider_name: str,
    api_key: str,
    base_url: str,
) -> ExternalSearchTransport:
    normalized = provider_name.strip().lower()
    if normalized in {"wikipedia", "mediawiki"}:
        return HttpWikipediaSearchTransport(base_url=base_url or _default_base_url(provider_name))
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
    if _provider_env("deepseek_search", "API_KEY"):
        # 配置了 DeepSeek 密钥时它始终排在第一位，其他渠道只在它失败或没有结果时补位
        provider_chain = ["deepseek", *(item for item in provider_chain if item.lower() != "deepseek")]

    adapters: list[ExternalSearchAdapter] = []
    authenticated_provider_index = 0
    for provider_name in provider_chain:
        if provider_name.lower() in McpSearchTransport.TOOLS:
            client = default_mcp_search_client()
            if client is None:
                continue
            adapters.append(ExternalSearchAdapter(
                provider_name=provider_name.lower(),
                transport=McpSearchTransport(provider_name, client),
                max_provider_pages=_max_provider_pages(),
                requires_api_key=False,
            ))
            continue
        if provider_name.lower() == "deepseek":
            api_key = _provider_env("deepseek_search", "API_KEY")
            if not api_key:
                continue
            adapters.append(ExternalSearchAdapter(
                provider_name="deepseek",
                transport=DeepSeekSearchTransport(api_key, _provider_env("deepseek_search", "BASE_URL")),
                max_provider_pages=1,
                requires_api_key=False,
            ))
            continue
        if provider_name.lower() in {"wikipedia", "mediawiki"}:
            if os.getenv("NOTEWEAVE_RESEARCH_PUBLIC_SEARCH_ENABLED", "").strip().lower() not in {
                "1", "true", "yes", "on"
            }:
                continue
            adapters.append(ExternalSearchAdapter(
                provider_name="wikipedia",
                base_url=_provider_env(provider_name, "BASE_URL") or _default_base_url(provider_name),
                max_provider_pages=_max_provider_pages(),
                requires_api_key=False,
            ))
            continue
        use_generic_search_settings = authenticated_provider_index == 0
        authenticated_provider_index += 1
        api_key = _provider_env(
            provider_name,
            "API_KEY",
            fallbacks=[
                "NOTEWEAVE_RESEARCH_SEARCH_API_KEY",
                "SERPER_API_KEY",
                "SEARCH_API_KEY",
            ] if use_generic_search_settings else [],
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
            ] if use_generic_search_settings else [],
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
    return text_terms(text)


def _stable_key(value: str) -> str:
    return hashlib.sha256(value.strip().lower().encode("utf-8")).hexdigest()


def _normalize_text(value: str) -> str:
    return re.sub(r"\s+", " ", value.strip().lower())


def _normalize_url(value: str) -> str:
    return value.strip().lower().rstrip("/")
