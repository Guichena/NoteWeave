from __future__ import annotations

import html
import os
import re
import urllib.error
import urllib.request
from typing import Protocol

from app.models import ResearchPlan, ResearchReadWindow, ResearchSearchHit, ResearchTaskInput, SourceScopeItem


class ReadAdapter(Protocol):
    def can_read(self, task_input: ResearchTaskInput, hit: ResearchSearchHit) -> bool:
        """Return whether this adapter can open a read window for the hit."""

    def read_hit(
        self,
        task_input: ResearchTaskInput,
        plan: ResearchPlan,
        hit: ResearchSearchHit,
        window_index: int,
    ) -> ResearchReadWindow | None:
        """Open one bounded read window."""


class UrlSnapshotTransport(Protocol):
    def read(self, url: str, query: str, token_budget: int) -> dict[str, object]:
        """Fetch and normalize a URL snapshot."""


class WorkspaceReadAdapter:
    def can_read(self, task_input: ResearchTaskInput, hit: ResearchSearchHit) -> bool:
        source_ids = {source.source_id for source in task_input.source_scope}
        return hit.adapter == "workspace" or (hit.source_id in source_ids and not hit.url)

    def read_hit(
        self,
        task_input: ResearchTaskInput,
        plan: ResearchPlan,
        hit: ResearchSearchHit,
        window_index: int,
    ) -> ResearchReadWindow | None:
        source = _find_source(task_input.source_scope, hit.source_id)
        if source is None:
            return None
        window_text = _workspace_window_text(source, hit)
        focus = f"Read {hit.source_title} for claims that answer: {plan.normalized_question}"
        return ResearchReadWindow(
            window_id=f"window-{window_index}",
            hit_id=hit.hit_id,
            source_id=hit.source_id,
            source_title=hit.source_title,
            query=hit.query,
            read_focus=focus,
            window_text=window_text,
            retention_reason=(
                "kept by tool_response_retention_budget because the hit is inside "
                "the current workspace source snapshot"
            ),
            token_estimate=_estimate_tokens(window_text),
            url=hit.url,
            provider=hit.provider,
            adapter="workspace",
            snapshot_status="WORKSPACE",
            snapshot_key="",
        )


class UrlReadAdapter:
    def __init__(
        self,
        transport: UrlSnapshotTransport | None = None,
        token_budget: int = 800,
    ) -> None:
        self.transport = transport
        self.token_budget = token_budget

    def can_read(self, task_input: ResearchTaskInput, hit: ResearchSearchHit) -> bool:
        del task_input
        return bool(hit.url.strip()) and hit.adapter != "workspace"

    def read_hit(
        self,
        task_input: ResearchTaskInput,
        plan: ResearchPlan,
        hit: ResearchSearchHit,
        window_index: int,
    ) -> ResearchReadWindow | None:
        del task_input
        if not hit.url.strip():
            return None

        snapshot_key = ""
        snapshot_status = "FALLBACK"
        retention_reason = (
            "kept by tool_response_retention_budget as external url fallback; "
            "no url snapshot transport configured"
        )
        window_text = _fallback_url_text(hit)

        if self.transport is not None:
            snapshot = self.transport.read(hit.url, hit.query, self.token_budget)
            fetched_text = str(snapshot.get("text") or "").strip()
            if fetched_text:
                window_text = fetched_text
                snapshot_key = str(snapshot.get("snapshot_key") or "").strip()
                snapshot_status = "FETCHED"
                retention_reason = (
                    "kept by tool_response_retention_budget after url snapshot fetch"
                )

        focus = (
            f"Read external URL {hit.source_title} for claims that answer: "
            f"{plan.normalized_question}"
        )
        return ResearchReadWindow(
            window_id=f"window-{window_index}",
            hit_id=hit.hit_id,
            source_id=hit.source_id,
            source_title=hit.source_title,
            query=hit.query,
            read_focus=focus,
            window_text=window_text,
            retention_reason=retention_reason,
            token_estimate=_estimate_tokens(window_text),
            url=hit.url,
            provider=hit.provider,
            adapter="external_url",
            snapshot_status=snapshot_status,
            snapshot_key=snapshot_key,
        )


class HttpUrlSnapshotTransport:
    """Tiny dependency-free URL reader, enabled only when configured."""

    def __init__(self, timeout_seconds: int = 20) -> None:
        self.timeout_seconds = timeout_seconds

    def read(self, url: str, query: str, token_budget: int) -> dict[str, object]:
        del query
        request = urllib.request.Request(
            url,
            headers={"User-Agent": "NoteWeaveResearchWorker/0.1"},
            method="GET",
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                content_type = response.headers.get("Content-Type", "")
                raw = response.read(max(4096, token_budget * 12))
        except (urllib.error.URLError, TimeoutError, ValueError):
            return {}
        text = _decode_response(raw, content_type)
        return {"text": _truncate_text(_strip_html(text), token_budget)}


class CompositeReadAdapter:
    def __init__(self, adapters: list[ReadAdapter]) -> None:
        self.adapters = adapters

    def read(
        self,
        task_input: ResearchTaskInput,
        plan: ResearchPlan,
        search_hits: list[ResearchSearchHit],
    ) -> list[ResearchReadWindow]:
        retention_budget = int(plan.stop_contract.get("tool_response_retention_budget", 5))
        if retention_budget <= 0:
            return []

        windows: list[ResearchReadWindow] = []
        for hit in search_hits:
            if len(windows) >= retention_budget:
                break
            window = self._read_first_supported_hit(
                task_input,
                plan,
                hit,
                window_index=len(windows) + 1,
            )
            if window is not None:
                windows.append(window)
        return windows

    def _read_first_supported_hit(
        self,
        task_input: ResearchTaskInput,
        plan: ResearchPlan,
        hit: ResearchSearchHit,
        window_index: int,
    ) -> ResearchReadWindow | None:
        for adapter in self.adapters:
            if adapter.can_read(task_input, hit):
                return adapter.read_hit(task_input, plan, hit, window_index)
        return None


def build_default_read_adapter() -> CompositeReadAdapter:
    transport = None
    if os.getenv("NOTEWEAVE_RESEARCH_ENABLE_URL_READER", "").lower() in {"1", "true", "yes"}:
        transport = HttpUrlSnapshotTransport()
    return CompositeReadAdapter(
        [
            WorkspaceReadAdapter(),
            UrlReadAdapter(transport=transport),
        ]
    )


def run_research_read(
    task_input: ResearchTaskInput,
    plan: ResearchPlan,
    search_hits: list[ResearchSearchHit],
    adapter: CompositeReadAdapter | None = None,
) -> list[ResearchReadWindow]:
    read_adapter = adapter or build_default_read_adapter()
    return read_adapter.read(task_input, plan, search_hits)


def _find_source(source_scope: list[SourceScopeItem], source_id: str) -> SourceScopeItem | None:
    return next((source for source in source_scope if source.source_id == source_id), None)


def _workspace_window_text(source: SourceScopeItem, hit: ResearchSearchHit) -> str:
    return (
        source.sample_text.strip()
        or hit.snippet.strip()
        or source.summary.strip()
        or f"{source.title} is available in the workspace but has no parsed text window."
    )


def _fallback_url_text(hit: ResearchSearchHit) -> str:
    snippet = hit.snippet.strip() or "No snippet was returned by the external search provider."
    return f"{snippet}\n\nURL: {hit.url}"


def _estimate_tokens(text: str) -> int:
    word_count = len(text.split())
    cjk_count = len(re.findall(r"[\u4e00-\u9fff]", text))
    return max(1, word_count + cjk_count // 2)


def _decode_response(raw: bytes, content_type: str) -> str:
    charset_match = re.search(r"charset=([^\s;]+)", content_type, flags=re.IGNORECASE)
    charset = charset_match.group(1) if charset_match else "utf-8"
    try:
        return raw.decode(charset, errors="replace")
    except LookupError:
        return raw.decode("utf-8", errors="replace")


def _strip_html(text: str) -> str:
    without_script = re.sub(r"<(script|style).*?</\1>", " ", text, flags=re.IGNORECASE | re.DOTALL)
    without_tags = re.sub(r"<[^>]+>", " ", without_script)
    normalized = html.unescape(without_tags)
    return re.sub(r"\s+", " ", normalized).strip()


def _truncate_text(text: str, token_budget: int) -> str:
    max_chars = max(500, token_budget * 6)
    return text[:max_chars].strip()
