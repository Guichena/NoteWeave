from __future__ import annotations

import hashlib
import html
import gzip
import http.client
import ipaddress
import logging
import os
import re
import socket
import ssl
import time
import urllib.error
import urllib.parse
from concurrent.futures import ThreadPoolExecutor
from typing import Protocol

from app.http_security import resolve_public_http_addresses
from app.models import (
    ResearchFetchedDocument,
    ResearchPlan,
    ResearchSearchHit,
    ResearchTaskInput,
    SourceScopeItem,
)
from app.source_profile import infer_source_domain, infer_source_quality
from app.config import load_settings
from app.rate_limit import FixedIntervalRateLimiter
from app.trace_security import sanitize_error_message


logger = logging.getLogger(__name__)


class FetchAdapter(Protocol):
    def can_fetch(self, task_input: ResearchTaskInput, hit: ResearchSearchHit) -> bool:
        """Return whether this adapter can fetch a normalized document for the hit."""

    def fetch_hit(
        self,
        task_input: ResearchTaskInput,
        plan: ResearchPlan,
        hit: ResearchSearchHit,
        fetch_index: int,
    ) -> ResearchFetchedDocument | None:
        """Return one normalized fetched document."""


class UrlSnapshotTransport(Protocol):
    def read(self, url: str, query: str, token_budget: int) -> dict[str, object]:
        """Fetch and normalize a URL snapshot."""


class WorkspaceFetchAdapter:
    def can_fetch(self, task_input: ResearchTaskInput, hit: ResearchSearchHit) -> bool:
        source_ids = {source.source_id for source in task_input.source_scope}
        return hit.adapter == "workspace" or (hit.source_id in source_ids and not hit.url)

    def fetch_hit(
        self,
        task_input: ResearchTaskInput,
        plan: ResearchPlan,
        hit: ResearchSearchHit,
        fetch_index: int,
    ) -> ResearchFetchedDocument | None:
        del plan
        source = _find_source(task_input.source_scope, hit.source_id)
        if source is None:
            return None
        if hit.source_snapshot_id and source.source_snapshot_id != hit.source_snapshot_id:
            return None
        snapshot_text = _workspace_snapshot_text(source, hit)
        source_domain = hit.source_domain or infer_source_domain(hit.url)
        if hit.source_quality and hit.source_quality != "GENERAL_WEB":
            source_quality = hit.source_quality
            source_quality_score = hit.source_quality_score
        else:
            source_quality = "WORKSPACE_SOURCE"
            source_quality_score = 0.98
        return ResearchFetchedDocument(
            fetch_id=f"fetch-{fetch_index}",
            hit_id=hit.hit_id,
            source_id=hit.source_id,
            source_snapshot_id=hit.source_snapshot_id or source.source_snapshot_id,
            source_window_id=hit.source_window_id or source.source_window_id,
            source_title=hit.source_title,
            query=hit.query,
            rank=hit.rank,
            url=hit.url,
            provider=hit.provider,
            adapter="workspace",
            search_angle=hit.search_angle,
            snapshot_text=snapshot_text,
            snapshot_status="WORKSPACE",
            snapshot_key="",
            fetch_status="WORKSPACE_READY",
            fetch_method="WORKSPACE",
            content_origin="WORKSPACE_TEXT",
            content_type_label="WORKSPACE_TEXT",
            fetch_error_reason="",
            fetch_failure_code="",
            fetch_attempts=["WORKSPACE"],
            transport_chain=["WORKSPACE"],
            transport_resolution="WORKSPACE",
            transport_fallback_reason="",
            transport_fallback_code="",
            transport_attempt_count=1,
            snapshot_archive_ready=False,
            source_domain=source_domain,
            source_quality=source_quality,
            source_quality_score=source_quality_score,
            source_type=source.source_type,
            author=source.author,
            institution=source.institution,
            published_at=source.published_at,
            updated_at=source.updated_at,
            freshness_status="DATED" if source.updated_at or source.published_at else "UNKNOWN",
        )


class UrlFetchAdapter:
    def __init__(
        self,
        transport: UrlSnapshotTransport | None = None,
        token_budget: int = 800,
    ) -> None:
        self.transport = transport
        self.token_budget = token_budget

    def can_fetch(self, task_input: ResearchTaskInput, hit: ResearchSearchHit) -> bool:
        del task_input
        return bool(hit.url.strip()) and hit.adapter != "workspace"

    def fetch_hit(
        self,
        task_input: ResearchTaskInput,
        plan: ResearchPlan,
        hit: ResearchSearchHit,
        fetch_index: int,
    ) -> ResearchFetchedDocument | None:
        del task_input
        del plan
        if not hit.url.strip():
            return None

        snapshot_key = ""
        snapshot_status = "FALLBACK"
        fetch_status = "FALLBACK_USED"
        fetch_method = "NO_TRANSPORT"
        content_origin = "SEARCH_SNIPPET_FALLBACK"
        content_type_label = "SEARCH_SNIPPET_FALLBACK"
        fetch_error_reason = "no url snapshot transport configured"
        fetch_failure_code = "NO_TRANSPORT"
        fetch_attempts = ["NO_TRANSPORT"]
        transport_chain = ["NO_TRANSPORT"]
        transport_resolution = "NO_TRANSPORT"
        transport_fallback_reason = fetch_error_reason
        transport_fallback_code = "NO_TRANSPORT"
        # Search-provider snippets are discovery metadata, not fetched evidence.
        # Keep the failed fetch record for observability, but never expose the
        # snippet as snapshot text that downstream extractors can cite.
        snapshot_text = ""
        untrusted_content = bool(hit.url)
        prompt_injection_detected = False
        prompt_injection_signals: list[str] = []
        author = ""
        institution = ""
        published_at = ""
        updated_at = ""
        reported_transport_attempt_count = 0

        if self.transport is not None:
            fetch_method = "TRANSPORT"
            fetch_error_reason = "configured fetch transport returned no readable text"
            fetch_failure_code = "EMPTY_FETCH_TEXT"
            fetch_attempts = []
            transport_chain = []
            transport_resolution = "TRANSPORT"
            transport_fallback_reason = fetch_error_reason
            transport_fallback_code = fetch_failure_code
            snapshot = self.transport.read(hit.url, hit.query, self.token_budget)
            untrusted_content = bool(snapshot.get("untrusted_content", True))
            prompt_injection_detected = bool(snapshot.get("prompt_injection_detected", False))
            prompt_injection_signals = [
                str(item).strip()
                for item in snapshot.get("prompt_injection_signals", [])
                if str(item).strip()
            ]
            author = str(snapshot.get("author") or "").strip()
            institution = str(snapshot.get("institution") or "").strip()
            published_at = str(snapshot.get("published_at") or "").strip()
            updated_at = str(snapshot.get("updated_at") or "").strip()
            reported_transport_attempt_count = int(snapshot.get("transport_attempt_count") or 0)
            fetched_text = str(snapshot.get("text") or "").strip()
            snapshot_status = str(snapshot.get("snapshot_status") or snapshot_status).strip() or snapshot_status
            snapshot_key = str(snapshot.get("snapshot_key") or snapshot_key).strip()
            fetch_status = str(snapshot.get("fetch_status") or fetch_status).strip() or fetch_status
            fetch_method = str(snapshot.get("fetch_method") or fetch_method).strip() or fetch_method
            content_origin = str(snapshot.get("content_origin") or content_origin).strip() or content_origin
            content_type_label = str(snapshot.get("content_type_label") or content_type_label).strip() or content_type_label
            fetch_error_reason = str(snapshot.get("fetch_error_reason") or "").strip()
            fetch_failure_code = str(snapshot.get("fetch_failure_code") or fetch_failure_code).strip()
            fetch_attempts = [
                str(item).strip()
                for item in snapshot.get("fetch_attempts", [])
                if str(item).strip()
            ] or [fetch_method]
            transport_chain = [
                str(item).strip()
                for item in snapshot.get("transport_chain", [])
                if str(item).strip()
            ] or list(fetch_attempts)
            transport_resolution = (
                str(snapshot.get("transport_resolution") or "").strip()
                or fetch_method
            )
            transport_fallback_reason = str(
                snapshot.get("transport_fallback_reason") or fetch_error_reason
            ).strip()
            transport_fallback_code = str(
                snapshot.get("transport_fallback_code") or fetch_failure_code
            ).strip()
            if fetched_text:
                snapshot_text = fetched_text
                if snapshot_status in {"", "FALLBACK", "FETCH_FAILED"}:
                    snapshot_status = "FETCHED"
                if fetch_status in {"", "FALLBACK_USED"}:
                    fetch_status = "FETCHED"
                fetch_method = fetch_method or "TRANSPORT"
                if content_origin in {"", "SEARCH_SNIPPET_FALLBACK"}:
                    content_origin = "FETCHED_SNAPSHOT"
                if not content_type_label or content_type_label == "SEARCH_SNIPPET_FALLBACK":
                    content_type_label = "WEBPAGE"
                fetch_error_reason = ""
                fetch_failure_code = ""
                if not transport_fallback_reason:
                    transport_fallback_code = ""
                if not transport_resolution:
                    transport_resolution = fetch_method

        source_domain = hit.source_domain or infer_source_domain(hit.url)
        if untrusted_content and not prompt_injection_signals:
            prompt_injection_signals = detect_prompt_injection(snapshot_text)
            prompt_injection_detected = bool(prompt_injection_signals)
        source_quality, source_quality_score = infer_source_quality(
            url=hit.url,
            provider=hit.provider,
            adapter=hit.adapter,
            title=hit.source_title,
            content_text=snapshot_text,
        )
        if hit.source_quality and hit.source_quality != "GENERAL_WEB" and source_quality == hit.source_quality:
            source_quality_score = hit.source_quality_score
        transport_attempt_count = max(
            reported_transport_attempt_count,
            _transport_attempt_count(transport_chain, fetch_attempts),
        )
        snapshot_archive_ready = _snapshot_archive_ready(
            snapshot_text=snapshot_text,
            snapshot_status=snapshot_status,
            snapshot_key=snapshot_key,
            fetch_status=fetch_status,
            content_origin=content_origin,
        )
        return ResearchFetchedDocument(
            fetch_id=f"fetch-{fetch_index}",
            hit_id=hit.hit_id,
            source_id=hit.source_id,
            source_title=hit.source_title,
            query=hit.query,
            rank=hit.rank,
            url=hit.url,
            provider=hit.provider,
            adapter="external_url",
            search_angle=hit.search_angle,
            snapshot_text=snapshot_text,
            snapshot_status=snapshot_status,
            snapshot_key=snapshot_key,
            fetch_status=fetch_status,
            fetch_method=fetch_method,
            content_origin=content_origin,
            content_type_label=content_type_label,
            fetch_error_reason=fetch_error_reason,
            fetch_failure_code=fetch_failure_code,
            fetch_attempts=fetch_attempts,
            transport_chain=transport_chain,
            transport_resolution=transport_resolution,
            transport_fallback_reason=transport_fallback_reason,
            transport_fallback_code=transport_fallback_code,
            transport_attempt_count=transport_attempt_count,
            snapshot_archive_ready=snapshot_archive_ready,
            source_domain=source_domain,
            source_quality=source_quality,
            source_quality_score=source_quality_score,
            untrusted_content=untrusted_content,
            prompt_injection_detected=prompt_injection_detected,
            prompt_injection_signals=prompt_injection_signals,
            source_type=_source_type_from_quality(source_quality),
            author=author,
            institution=institution,
            published_at=published_at,
            updated_at=updated_at,
            freshness_status="DATED" if published_at or updated_at else "UNKNOWN",
        )


class HttpUrlSnapshotTransport:
    """Tiny dependency-free URL reader, enabled only when configured."""

    BINARY_MIME_PREFIXES = (
        "application/pdf",
        "application/zip",
        "application/octet-stream",
        "image/",
        "audio/",
        "video/",
    )

    def __init__(self, timeout_seconds: int = 20, max_retries: int = 3, requests_per_second: float = 0.0) -> None:
        self.timeout_seconds = timeout_seconds
        self.max_retries = max(1, max_retries)
        self.rate_limiter = FixedIntervalRateLimiter(requests_per_second)

    def read(self, url: str, query: str, token_budget: int) -> dict[str, object]:
        try:
            validate_public_http_url(url)
        except ValueError as exc:
            return _blocked_url_result("HTTP", str(exc))
        raw = b""
        content_type = ""
        content_encoding = ""
        for attempt in range(1, self.max_retries + 1):
            self.rate_limiter.acquire()
            try:
                raw, response_headers, _final_url = _pinned_http_get(
                    url,
                    headers={
                        "User-Agent": (
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                            "AppleWebKit/537.36 (KHTML, like Gecko) "
                            "Chrome/124.0.0.0 Safari/537.36 NoteWeaveResearchWorker/0.1"
                        )
                    },
                    timeout_seconds=self.timeout_seconds,
                    token_budget=token_budget,
                )
                content_type = response_headers.get("Content-Type", "")
                content_encoding = response_headers.get("Content-Encoding", "")
                break
            except (OSError, TimeoutError, ValueError, http.client.HTTPException) as exc:
                retryable = _is_retryable_fetch_error(exc)
                if not retryable or attempt >= self.max_retries:
                    failure_code = (
                        _non_retryable_fetch_failure_code(exc)
                        if not retryable
                        else "FETCH_RETRY_EXHAUSTED"
                    )
                    return {
                        "snapshot_status": "FETCH_FAILED",
                        "fetch_status": "FALLBACK_USED",
                        "fetch_method": "HTTP",
                        "content_origin": "SEARCH_SNIPPET_FALLBACK",
                        "content_type_label": "UNKNOWN",
                        "fetch_error_reason": (
                            "http fetch rejected without retry"
                            if not retryable
                            else "http fetch failed after retries"
                        ),
                        "fetch_failure_code": failure_code,
                        "fetch_attempts": ["HTTP"],
                        "transport_fallback_code": failure_code,
                        "transport_attempt_count": attempt,
                    }
                time.sleep(min(2 ** (attempt - 1), 4))
        content_type_label = _content_type_label(content_type)
        if self._is_binary_response(content_type):
            return {
                "snapshot_status": "FETCH_FAILED",
                "fetch_status": "FALLBACK_USED",
                "fetch_method": "HTTP",
                "content_origin": "SEARCH_SNIPPET_FALLBACK",
                "content_type_label": content_type_label,
                "fetch_error_reason": f"unsupported binary content type: {content_type or 'unknown'}",
                "fetch_failure_code": "UNSUPPORTED_CONTENT_TYPE",
                "fetch_attempts": ["HTTP"],
                "transport_fallback_code": "UNSUPPORTED_CONTENT_TYPE",
            }
        if content_type and not _is_allowed_content_type(content_type):
            return {
                "snapshot_status": "FETCH_FAILED",
                "fetch_status": "FALLBACK_USED",
                "fetch_method": "HTTP",
                "content_origin": "SEARCH_SNIPPET_FALLBACK",
                "content_type_label": content_type_label,
                "fetch_error_reason": f"content type is not allowlisted: {content_type}",
                "fetch_failure_code": "UNSUPPORTED_CONTENT_TYPE",
                "fetch_attempts": ["HTTP"],
                "transport_fallback_code": "UNSUPPORTED_CONTENT_TYPE",
            }
        try:
            raw = _decompress_response(raw, content_encoding, max_bytes=2_000_000)
        except ValueError as exc:
            failure_code = (
                "UNSUPPORTED_CONTENT_ENCODING"
                if str(exc) == "unsupported content encoding"
                else "DECOMPRESSION_FAILED"
                if str(exc) == "invalid compressed response"
                else "DECOMPRESSED_SIZE_LIMIT"
            )
            return {
                "snapshot_status": "FETCH_FAILED",
                "fetch_status": "FALLBACK_USED",
                "fetch_method": "HTTP",
                "content_origin": "SEARCH_SNIPPET_FALLBACK",
                "content_type_label": content_type_label,
                "fetch_error_reason": str(exc),
                "fetch_failure_code": failure_code,
                "fetch_attempts": ["HTTP"],
                "transport_fallback_code": failure_code,
            }
        text = _decode_response(raw, content_type)
        cleaned_text = _select_query_relevant_text(_strip_html(text), query, token_budget)
        if not cleaned_text:
            return {
                "snapshot_status": "FETCH_FAILED",
                "fetch_status": "FALLBACK_USED",
                "fetch_method": "HTTP",
                "content_origin": "SEARCH_SNIPPET_FALLBACK",
                "content_type_label": content_type_label,
                "fetch_error_reason": "http fetch returned no readable text",
                "fetch_failure_code": "EMPTY_FETCH_TEXT",
                "fetch_attempts": ["HTTP"],
                "transport_fallback_code": "EMPTY_FETCH_TEXT",
            }
        injection_signals = detect_prompt_injection(cleaned_text)
        return {
            "text": cleaned_text,
            "snapshot_key": _snapshot_key(url, cleaned_text),
            "snapshot_status": "HTTP_FETCHED",
            "fetch_status": "FETCHED",
            "fetch_method": "HTTP",
            "content_origin": "FETCHED_SNAPSHOT",
            "content_type_label": content_type_label or "WEBPAGE",
            "fetch_error_reason": "",
            "fetch_failure_code": "",
            "fetch_attempts": ["HTTP"],
            "transport_attempt_count": attempt,
            "transport_fallback_code": "",
            "untrusted_content": True,
            "prompt_injection_detected": bool(injection_signals),
            "prompt_injection_signals": injection_signals,
            "updated_at": response_headers.get("Last-Modified", ""),
        }

    def _is_binary_response(self, content_type: str) -> bool:
        normalized = content_type.lower()
        return any(normalized.startswith(prefix) for prefix in self.BINARY_MIME_PREFIXES)


class JinaUrlSnapshotTransport:
    """Jina Reader-backed transport for better webpage extraction."""

    def __init__(
        self,
        api_key: str,
        base_url: str = "https://r.jina.ai/",
        timeout_seconds: int = 30,
        max_retries: int = 3,
        requests_per_second: float = 0.0,
    ) -> None:
        self.api_key = api_key.strip()
        self.base_url = (base_url.strip() or "https://r.jina.ai/").rstrip("/") + "/"
        self.timeout_seconds = timeout_seconds
        self.max_retries = max(1, max_retries)
        self.rate_limiter = FixedIntervalRateLimiter(requests_per_second)

    def read(self, url: str, query: str, token_budget: int) -> dict[str, object]:
        if not self.api_key:
            return {}
        try:
            validate_public_http_url(url)
            validate_public_http_url(self.base_url)
        except ValueError as exc:
            return _blocked_url_result("JINA", str(exc))
        target_url = f"{self.base_url}{url}"
        for attempt in range(1, self.max_retries + 1):
            self.rate_limiter.acquire()
            try:
                raw, response_headers, _final_url = _pinned_http_get(
                    target_url,
                    headers={
                        "Authorization": f"Bearer {self.api_key}",
                        "X-Return-Format": "markdown",
                        "User-Agent": "NoteWeaveResearchWorker/0.1",
                    },
                    timeout_seconds=self.timeout_seconds,
                    token_budget=token_budget,
                )
                content_type = response_headers.get("Content-Type", "")
                text = _decode_response(raw, content_type)
                cleaned_text = _select_query_relevant_text(_strip_jina_wrapper(text), query, token_budget)
                if cleaned_text:
                    injection_signals = detect_prompt_injection(cleaned_text)
                    return {
                        "text": cleaned_text,
                        "snapshot_key": _snapshot_key(url, cleaned_text),
                        "snapshot_status": "JINA_FETCHED",
                        "fetch_status": "FETCHED",
                        "fetch_method": "JINA",
                        "content_origin": "FETCHED_SNAPSHOT",
                        "content_type_label": "WEBPAGE",
                        "fetch_error_reason": "",
                        "fetch_failure_code": "",
                        "fetch_attempts": ["JINA"],
                        "transport_attempt_count": attempt,
                        "transport_fallback_code": "",
                        "untrusted_content": True,
                        "prompt_injection_detected": bool(injection_signals),
                        "prompt_injection_signals": injection_signals,
                    }
            except (OSError, TimeoutError, ValueError, http.client.HTTPException) as exc:
                retryable = _is_retryable_fetch_error(exc)
                if not retryable or attempt >= self.max_retries:
                    failure_code = (
                        _non_retryable_fetch_failure_code(exc)
                        if not retryable
                        else "FETCH_RETRY_EXHAUSTED"
                    )
                    return {
                        "snapshot_status": "FETCH_FAILED",
                        "fetch_status": "FALLBACK_USED",
                        "fetch_method": "JINA",
                        "content_origin": "SEARCH_SNIPPET_FALLBACK",
                        "content_type_label": "WEBPAGE",
                        "fetch_error_reason": (
                            "jina fetch rejected without retry"
                            if not retryable
                            else "jina fetch failed after retries"
                        ),
                        "fetch_failure_code": failure_code,
                        "fetch_attempts": ["JINA"],
                        "transport_fallback_code": failure_code,
                        "transport_attempt_count": attempt,
                    }
                time.sleep(min(2 ** (attempt - 1), 4))
        return {
            "snapshot_status": "FETCH_FAILED",
            "fetch_status": "FALLBACK_USED",
            "fetch_method": "JINA",
            "content_origin": "SEARCH_SNIPPET_FALLBACK",
            "content_type_label": "WEBPAGE",
            "fetch_error_reason": "jina fetch returned no readable text",
            "fetch_failure_code": "EMPTY_FETCH_TEXT",
            "fetch_attempts": ["JINA"],
            "transport_fallback_code": "EMPTY_FETCH_TEXT",
        }


class CompositeUrlSnapshotTransport:
    def __init__(self, transports: list[UrlSnapshotTransport]) -> None:
        self.transports = transports

    def read(self, url: str, query: str, token_budget: int) -> dict[str, object]:
        failure_methods: list[str] = []
        failure_reasons: list[str] = []
        failure_codes: list[str] = []
        for transport in self.transports:
            snapshot = transport.read(url, query, token_budget)
            failure_methods.extend(
                str(item).strip()
                for item in snapshot.get("fetch_attempts", [])
                if str(item).strip()
            )
            if str(snapshot.get("text") or "").strip():
                transport_chain = list(dict.fromkeys(failure_methods))
                snapshot["fetch_attempts"] = transport_chain
                snapshot["transport_chain"] = transport_chain
                snapshot["transport_resolution"] = str(
                    snapshot.get("transport_resolution")
                    or snapshot.get("fetch_method")
                    or "TRANSPORT"
                ).strip() or "TRANSPORT"
                snapshot["transport_fallback_reason"] = " ; ".join(
                    dict.fromkeys(reason for reason in failure_reasons if reason)
                )
                snapshot["transport_fallback_code"] = " ; ".join(
                    dict.fromkeys(code for code in failure_codes if code)
                )
                return snapshot
            reason = str(snapshot.get("fetch_error_reason") or "").strip()
            if reason:
                failure_reasons.append(reason)
            failure_code = str(snapshot.get("fetch_failure_code") or "").strip()
            if failure_code:
                failure_codes.append(failure_code)
        if not failure_methods:
            failure_methods = ["TRANSPORT"]
        joined_reasons = " ; ".join(dict.fromkeys(failure_reasons))
        joined_codes = " ; ".join(dict.fromkeys(failure_codes))
        return {
            "snapshot_status": "FETCH_FAILED",
            "fetch_status": "FALLBACK_USED",
            "fetch_method": "COMPOSITE_FALLBACK",
            "content_origin": "SEARCH_SNIPPET_FALLBACK",
            "content_type_label": "SEARCH_SNIPPET_FALLBACK",
            "fetch_error_reason": joined_reasons
            or "all configured fetch transports returned no readable text",
            "fetch_failure_code": joined_codes or "ALL_TRANSPORTS_FAILED",
            "fetch_attempts": list(dict.fromkeys(failure_methods)),
            "transport_chain": list(dict.fromkeys(failure_methods)),
            "transport_resolution": "COMPOSITE_FALLBACK",
            "transport_fallback_reason": joined_reasons
            or "all configured fetch transports returned no readable text",
            "transport_fallback_code": joined_codes or "ALL_TRANSPORTS_FAILED",
        }


class CompositeFetchAdapter:
    def __init__(self, adapters: list[FetchAdapter], max_concurrency: int = 1) -> None:
        self.adapters = adapters
        self.max_concurrency = max(1, max_concurrency)

    def fetch(
        self,
        task_input: ResearchTaskInput,
        plan: ResearchPlan,
        search_hits: list[ResearchSearchHit],
    ) -> list[ResearchFetchedDocument]:
        retention_budget = int(plan.stop_contract.get("tool_response_retention_budget", 5))
        if retention_budget <= 0:
            return []

        selected_hits = _ordered_hits(plan, search_hits)[:retention_budget]
        if self.max_concurrency <= 1 or len(selected_hits) <= 1:
            return [
                document
                for index, hit in enumerate(selected_hits, start=1)
                if (document := self._fetch_first_supported_hit(task_input, plan, hit, index)) is not None
            ]
        with ThreadPoolExecutor(max_workers=min(self.max_concurrency, len(selected_hits))) as executor:
            futures = [
                executor.submit(self._fetch_first_supported_hit, task_input, plan, hit, index)
                for index, hit in enumerate(selected_hits, start=1)
            ]
            return [document for future in futures if (document := future.result()) is not None]

    def _fetch_first_supported_hit(
        self,
        task_input: ResearchTaskInput,
        plan: ResearchPlan,
        hit: ResearchSearchHit,
        fetch_index: int,
    ) -> ResearchFetchedDocument | None:
        try:
            for adapter in self.adapters:
                if adapter.can_fetch(task_input, hit):
                    return adapter.fetch_hit(task_input, plan, hit, fetch_index)
        except Exception as exc:
            logger.warning(
                "Research fetch adapter failed for hit_id=%s source_id=%s: %s",
                hit.hit_id,
                hit.source_id,
                sanitize_error_message(exc),
            )
        return None


def build_default_fetch_adapter() -> CompositeFetchAdapter:
    settings = load_settings()
    transport = _build_default_url_snapshot_transport()
    return CompositeFetchAdapter(
        [
            WorkspaceFetchAdapter(),
            UrlFetchAdapter(transport=transport),
        ],
        max_concurrency=settings.fetch_max_concurrency,
    )


def run_research_fetch(
    task_input: ResearchTaskInput,
    plan: ResearchPlan,
    search_hits: list[ResearchSearchHit],
    adapter: CompositeFetchAdapter | None = None,
) -> list[ResearchFetchedDocument]:
    fetch_adapter = adapter or build_default_fetch_adapter()
    return fetch_adapter.fetch(task_input, plan, search_hits)


def _ordered_hits(
    plan: ResearchPlan,
    search_hits: list[ResearchSearchHit],
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
    if (
        not target_sources
        and not target_queries
        and not target_requirement_types
        and recovery_mode not in {"READ_MORE", "COUNTERFACTUAL_RECHECK"}
    ):
        return search_hits
    return sorted(
        search_hits,
        key=lambda hit: (
            (
                (1 if hit.source_title.strip().lower() in target_sources else 0)
                if recovery_mode == "COUNTERFACTUAL_RECHECK"
                else (0 if hit.source_title.strip().lower() in target_sources else 1)
            ),
            0 if hit.query.strip().lower() in target_queries else 1,
            _requirement_hit_priority(target_requirement_types, hit),
            0 if recovery_mode == "READ_MORE" and hit.adapter == "workspace" else 1,
            0 if recovery_mode == "COUNTERFACTUAL_RECHECK" and hit.search_angle == "counterfactual" else 1,
            hit.rank,
        ),
    )


def _find_source(source_scope: list[SourceScopeItem], source_id: str) -> SourceScopeItem | None:
    return next((source for source in source_scope if source.source_id == source_id), None)


def _workspace_snapshot_text(source: SourceScopeItem, hit: ResearchSearchHit) -> str:
    return (
        hit.workspace_window_text.strip()
        or
        source.sample_text.strip()
        or hit.snippet.strip()
        or source.summary.strip()
        or f"{source.title} is available in the workspace but has no parsed text window."
    )


def _read_response_bytes(response: object, token_budget: int) -> bytes:
    max_bytes = min(2_000_000, max(65_536, token_budget * 120))
    chunks: list[bytes] = []
    total = 0
    while total < max_bytes:
        read_size = min(8192, max_bytes - total)
        chunk = response.read(read_size)
        if not chunk:
            break
        chunks.append(chunk)
        total += len(chunk)
    return b"".join(chunks)


_PROMPT_INJECTION_PATTERNS: tuple[tuple[str, str], ...] = (
    ("IGNORE_PRIOR_INSTRUCTIONS", r"\b(ignore|disregard|override)\b.{0,40}\b(previous|prior|system|developer)\b.{0,20}\b(instruction|prompt)s?\b"),
    ("TOOL_POLICY_OVERRIDE", r"\b(call|invoke|use|run)\b.{0,30}\b(tool|function|shell|terminal|browser)\b"),
    ("SECRET_EXFILTRATION", r"\b(reveal|print|send|exfiltrate|leak)\b.{0,30}\b(secret|token|api.?key|password|system prompt)\b"),
    ("ROLE_IMPERSONATION", r"\b(system|developer)\s*:\s*"),
    ("IGNORE_PRIOR_INSTRUCTIONS_ZH", r"(忽略|无视|覆盖|绕过).{0,20}(之前|此前|系统|开发者).{0,12}(指令|提示词|规则)"),
    ("TOOL_POLICY_OVERRIDE_ZH", r"(调用|执行|运行|使用).{0,16}(工具|函数|命令|终端|浏览器)"),
    ("SECRET_EXFILTRATION_ZH", r"(泄露|输出|打印|发送|窃取).{0,16}(密钥|令牌|密码|系统提示词|API.?Key)"),
    ("ROLE_IMPERSONATION_ZH", r"(系统|开发者)\s*[：:]"),
)


def detect_prompt_injection(text: str) -> list[str]:
    normalized = " ".join(str(text).split()).lower()
    return [
        signal
        for signal, pattern in _PROMPT_INJECTION_PATTERNS
        if re.search(pattern, normalized, flags=re.IGNORECASE)
    ]


def validate_public_http_url(url: str) -> None:
    parsed = urllib.parse.urlsplit(str(url).strip())
    if parsed.scheme.lower() not in {"http", "https"}:
        raise ValueError("URL_BLOCKED_SCHEME")
    if parsed.username or parsed.password:
        raise ValueError("URL_BLOCKED_CREDENTIALS")
    hostname = (parsed.hostname or "").strip().rstrip(".")
    if not hostname:
        raise ValueError("URL_BLOCKED_MISSING_HOST")
    if hostname.lower() in {"localhost", "localhost.localdomain", "metadata.google.internal"}:
        raise ValueError("URL_BLOCKED_LOCAL_HOST")
    _resolve_public_addresses(parsed)


def _resolve_public_addresses(
    parsed: urllib.parse.SplitResult,
) -> list[ipaddress.IPv4Address | ipaddress.IPv6Address]:
    return resolve_public_http_addresses(parsed)


class _PinnedHTTPSConnection(http.client.HTTPSConnection):
    def __init__(self, connect_ip: str, server_hostname: str, port: int, timeout: int) -> None:
        super().__init__(server_hostname, port=port, timeout=timeout, context=ssl.create_default_context())
        self._connect_ip = connect_ip

    def connect(self) -> None:
        raw_socket = socket.create_connection(
            (self._connect_ip, self.port),
            self.timeout,
            self.source_address,
        )
        self.sock = self._context.wrap_socket(raw_socket, server_hostname=self.host)


def _pinned_http_get(
    url: str,
    *,
    headers: dict[str, str],
    timeout_seconds: int,
    token_budget: int,
    max_redirects: int = 5,
) -> tuple[bytes, dict[str, str], str]:
    current_url = str(url).strip()
    current_headers = dict(headers)
    for _redirect_no in range(max_redirects + 1):
        validate_public_http_url(current_url)
        parsed = urllib.parse.urlsplit(current_url)
        addresses = _resolve_public_addresses(parsed)
        connect_ip = str(addresses[0])
        port = parsed.port or (443 if parsed.scheme.lower() == "https" else 80)
        host_header = parsed.hostname or ""
        if parsed.port and parsed.port not in {80, 443}:
            host_header = f"{host_header}:{parsed.port}"
        request_headers = {**current_headers, "Host": host_header, "Connection": "close"}
        path = urllib.parse.urlunsplit(("", "", parsed.path or "/", parsed.query, ""))
        connection: http.client.HTTPConnection
        if parsed.scheme.lower() == "https":
            connection = _PinnedHTTPSConnection(connect_ip, parsed.hostname or "", port, timeout_seconds)
        else:
            connection = http.client.HTTPConnection(connect_ip, port=port, timeout=timeout_seconds)
        try:
            connection.request("GET", path, headers=request_headers)
            response = connection.getresponse()
            response_headers = {str(key).title(): str(value) for key, value in response.getheaders()}
            if response.status in {301, 302, 303, 307, 308}:
                location = response_headers.get("Location", "").strip()
                if not location:
                    raise ValueError("URL_REDIRECT_WITHOUT_LOCATION")
                next_url = urllib.parse.urljoin(current_url, location)
                validate_public_http_url(next_url)
                if _redirect_changes_origin(current_url, next_url):
                    _strip_sensitive_headers(current_headers)
                current_url = next_url
                continue
            if response.status >= 400:
                raise ValueError(f"HTTP_STATUS_{response.status}")
            return _read_response_bytes(response, token_budget), response_headers, current_url
        finally:
            connection.close()
    raise ValueError("URL_REDIRECT_LIMIT_EXCEEDED")


def _redirect_changes_origin(current_url: str, next_url: str) -> bool:
    return _url_origin(current_url) != _url_origin(next_url)


def _url_origin(url: str) -> tuple[str, str, int]:
    parsed = urllib.parse.urlsplit(url)
    scheme = parsed.scheme.lower()
    hostname = (parsed.hostname or "").lower().rstrip(".")
    port = parsed.port or (443 if scheme == "https" else 80)
    return scheme, hostname, port


def _strip_sensitive_headers(headers: dict[str, str]) -> None:
    for key in list(headers):
        if key.lower() in {"authorization", "x-api-key"}:
            headers.pop(key, None)


def _blocked_url_result(method: str, reason: str) -> dict[str, object]:
    return {
        "snapshot_status": "FETCH_BLOCKED",
        "fetch_status": "FALLBACK_USED",
        "fetch_method": method,
        "content_origin": "SEARCH_SNIPPET_FALLBACK",
        "content_type_label": "BLOCKED_URL",
        "fetch_error_reason": reason,
        "fetch_failure_code": "SSRF_BLOCKED",
        "fetch_attempts": [method],
        "transport_fallback_code": "SSRF_BLOCKED",
        "untrusted_content": True,
    }


def _is_retryable_fetch_error(exc: BaseException) -> bool:
    match = re.fullmatch(r"HTTP_STATUS_(\d{3})", str(exc).strip())
    if match is None:
        return True
    status = int(match.group(1))
    return status in {408, 429} or status >= 500


def _non_retryable_fetch_failure_code(exc: BaseException) -> str:
    match = re.fullmatch(r"HTTP_STATUS_(\d{3})", str(exc).strip())
    return f"NON_RETRYABLE_HTTP_{match.group(1)}" if match else "NON_RETRYABLE_FETCH_ERROR"


def _source_type_from_quality(source_quality: str) -> str:
    normalized = str(source_quality).upper()
    if "PRIMARY" in normalized or "OFFICIAL" in normalized:
        return "PRIMARY"
    if "ACADEMIC" in normalized or "GOVERNMENT" in normalized:
        return "AUTHORITATIVE_SECONDARY"
    if "COMMUNITY" in normalized or "USER" in normalized:
        return "USER_GENERATED"
    return "SECONDARY"


def _decode_response(raw: bytes, content_type: str) -> str:
    candidates = _charset_candidates(raw, content_type)
    decoded: list[tuple[int, str]] = []
    for charset in candidates:
        try:
            text = raw.decode(charset, errors="replace")
        except LookupError:
            continue
        decoded.append((_decode_penalty(text), text))
        if charset.lower().replace("_", "-") == "utf-8" and "\ufffd" not in text:
            return text
    if not decoded:
        return raw.decode("utf-8", errors="replace")
    decoded.sort(key=lambda item: item[0])
    return decoded[0][1]


def _charset_candidates(raw: bytes, content_type: str) -> list[str]:
    candidates: list[str] = []
    header_match = re.search(r"charset=([^\s;]+)", content_type, flags=re.IGNORECASE)
    if header_match:
        candidates.append(header_match.group(1).strip('"\''))
    meta_match = re.search(
        rb"charset\s*=\s*['\"]?([a-zA-Z0-9_\-]+)",
        raw[:4096],
        flags=re.IGNORECASE,
    )
    if meta_match:
        candidates.append(meta_match.group(1).decode("ascii", errors="ignore"))
    candidates.extend(["utf-8", "gb18030"])
    return list(dict.fromkeys(item for item in candidates if item))


def _decode_penalty(text: str) -> int:
    replacement_penalty = text.count("\ufffd") * 100
    control_penalty = sum(1 for char in text if ord(char) < 32 and char not in "\t\n\r")
    return replacement_penalty + control_penalty


def _content_type_label(content_type: str) -> str:
    normalized = content_type.lower().strip()
    if not normalized:
        return "UNKNOWN"
    if normalized.startswith("application/pdf"):
        return "PDF"
    if normalized.startswith("text/plain"):
        return "TEXT"
    if normalized.startswith("text/html") or "html" in normalized:
        return "WEBPAGE"
    if normalized.startswith("image/") or normalized.startswith("audio/") or normalized.startswith("video/"):
        return "UNSUPPORTED_BINARY"
    if normalized.startswith("application/"):
        return "UNSUPPORTED_BINARY"
    return "UNKNOWN"


def _is_allowed_content_type(content_type: str) -> bool:
    normalized = content_type.lower().split(";", 1)[0].strip()
    return normalized.startswith("text/") or normalized in {
        "application/json",
        "application/xml",
        "application/xhtml+xml",
        "application/ld+json",
    }


def _decompress_response(raw: bytes, content_encoding: str, *, max_bytes: int) -> bytes:
    normalized = str(content_encoding).lower().strip()
    if not normalized or normalized == "identity":
        return raw
    if normalized != "gzip":
        raise ValueError("unsupported content encoding")
    try:
        decompressed = gzip.decompress(raw)
    except (gzip.BadGzipFile, EOFError, OSError) as exc:
        raise ValueError("invalid compressed response") from exc
    if len(decompressed) > max_bytes:
        raise ValueError("decompressed size limit")
    return decompressed


def _strip_html(text: str) -> str:
    without_script = re.sub(r"<(script|style).*?</\1>", " ", text, flags=re.IGNORECASE | re.DOTALL)
    without_tags = re.sub(r"<[^>]+>", " ", without_script)
    normalized = html.unescape(without_tags)
    return re.sub(r"\s+", " ", normalized).strip()


def _truncate_text(text: str, token_budget: int) -> str:
    max_chars = max(500, token_budget * 6)
    return text[:max_chars].strip()


def _select_query_relevant_text(text: str, query: str, token_budget: int) -> str:
    max_chars = max(500, token_budget * 6)
    if len(text) <= max_chars:
        return text.strip()
    folded = text.casefold()
    # URLs in a source-scoped question are repeated throughout Markdown navigation
    # and reference links.  Treating their host/path fragments as research terms
    # makes a link-heavy page header outscore the article body.
    query_without_urls = re.sub(r"https?://\S+", " ", query.casefold())
    stop = {
        "and", "articles", "compare", "content", "from", "http", "https",
        "primary", "reports", "sources", "the", "uploads", "using", "which",
        "with", "www",
    }
    terms = {
        term for term in re.findall(r"[a-z0-9][a-z0-9._-]{2,}", query_without_urls)
        if term not in stop and not term.startswith("www.") and not any(char.isdigit() for char in term)
    }
    starts = {0}
    # Keep candidate generation deterministic and fair across terms.  The old
    # global 160-start cutoff depended on set iteration order and could exhaust
    # itself on one common word before considering a distinctive research term.
    for term in sorted(terms, key=lambda item: (-len(item), item)):
        offset = 0
        term_matches = 0
        while term_matches < 32 and len(starts) < 512:
            index = folded.find(term, offset)
            if index < 0:
                break
            starts.add(max(0, min(len(text) - max_chars, index - max_chars // 4)))
            offset = index + len(term)
            term_matches += 1
    if len(starts) == 1:
        return _truncate_text(text, token_budget)

    boilerplate = ("cookie", "privacy", "advertisement", "manage preferences", "search articles")

    def score(start: int) -> tuple[int, int, int, int]:
        candidate = folded[start:start + max_chars]
        coverage = sum(term in candidate for term in terms)
        # Cap repetition so menus and reference lists cannot win merely by
        # repeating the article title or host in dozens of links.
        relevance = coverage * 120 + sum(
            min(candidate.count(term), 2) * min(len(term), 16) for term in terms
        )
        link_count = candidate.count("](") + candidate.count("http://") + candidate.count("https://")
        reference_count = candidate.count("#ref-") + candidate.count("references")
        penalty = (
            sum(candidate.count(term) * 30 for term in boilerplate)
            + link_count * 30
            + reference_count * 40
        )
        prose_chars = len(re.findall(r"[a-z\u4e00-\u9fff]", candidate))
        return relevance - penalty, coverage, prose_chars, -start

    best_start = max(starts, key=score)
    return text[best_start:best_start + max_chars].strip()


def _strip_jina_wrapper(text: str) -> str:
    cleaned = text.strip()
    if not cleaned:
        return ""
    cleaned = re.sub(r"^Title:\s*.*?$", " ", cleaned, flags=re.IGNORECASE | re.MULTILINE)
    cleaned = re.sub(r"^URL Source:\s*.*?$", " ", cleaned, flags=re.IGNORECASE | re.MULTILINE)
    cleaned = re.sub(r"^Markdown Content:\s*", " ", cleaned, flags=re.IGNORECASE | re.MULTILINE)
    return re.sub(r"\s+", " ", cleaned).strip()


def _snapshot_key(url: str, text: str) -> str:
    return hashlib.sha256(f"{url}\n{text[:800]}".encode("utf-8")).hexdigest()[:16]


def _transport_attempt_count(transport_chain: list[str], fetch_attempts: list[str]) -> int:
    attempts = [item for item in transport_chain if item] or [item for item in fetch_attempts if item]
    return len(dict.fromkeys(attempts))


def _snapshot_archive_ready(
    *,
    snapshot_text: str,
    snapshot_status: str,
    snapshot_key: str,
    fetch_status: str,
    content_origin: str,
) -> bool:
    return bool(
        snapshot_text.strip()
        and snapshot_key.strip()
        and fetch_status == "FETCHED"
        and content_origin == "FETCHED_SNAPSHOT"
        and snapshot_status not in {"WORKSPACE", "FALLBACK", "FETCH_FAILED"}
    )


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


def _build_default_url_snapshot_transport() -> UrlSnapshotTransport | None:
    settings = load_settings()
    enable_http_reader = settings.research_enable_url_reader
    jina_api_key = (
        settings.research_jina_api_key.strip()
        or os.getenv("JINA_API_KEY", "").strip()
    )
    jina_base_url = (
        settings.research_jina_base_url.strip()
        or os.getenv("JINA_BASE_URL", "").strip()
        or "https://r.jina.ai/"
    )

    transports: list[UrlSnapshotTransport] = []
    if jina_api_key:
        transports.append(
            JinaUrlSnapshotTransport(
                api_key=jina_api_key,
                base_url=jina_base_url,
                timeout_seconds=settings.fetch_timeout_seconds,
                max_retries=settings.fetch_max_retries,
                requests_per_second=settings.fetch_requests_per_second,
            )
        )
    if enable_http_reader:
        transports.append(HttpUrlSnapshotTransport(
            timeout_seconds=settings.fetch_timeout_seconds,
            max_retries=settings.fetch_max_retries,
            requests_per_second=settings.fetch_requests_per_second,
        ))
    if not transports:
        return None
    if len(transports) == 1:
        return transports[0]
    return CompositeUrlSnapshotTransport(transports)
