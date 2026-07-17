from __future__ import annotations

import re
from typing import Protocol

from app.fetch_adapters import run_research_fetch
from app.models import (
    ResearchFetchedDocument,
    ResearchPlan,
    ResearchReadWindow,
    ResearchSearchHit,
    ResearchTaskInput,
)
from app.source_profile import infer_read_strategy


class ReadAdapter(Protocol):
    def can_read(self, task_input: ResearchTaskInput, document: ResearchFetchedDocument) -> bool:
        """Return whether this adapter can open a read window for the fetched document."""

    def read_hit(
        self,
        task_input: ResearchTaskInput,
        plan: ResearchPlan,
        document: ResearchFetchedDocument,
        window_index: int,
    ) -> ResearchReadWindow | None:
        """Open one bounded read window from a fetched document."""


class WorkspaceReadAdapter:
    def can_read(self, task_input: ResearchTaskInput, document: ResearchFetchedDocument) -> bool:
        del task_input
        return document.adapter == "workspace"

    def read_hit(
        self,
        task_input: ResearchTaskInput,
        plan: ResearchPlan,
        document: ResearchFetchedDocument,
        window_index: int,
    ) -> ResearchReadWindow | None:
        del task_input
        read_strategy = infer_read_strategy(plan, _as_search_hit(document))
        requirement_targets = _requirement_target_context(plan, document.query)
        transport_attempt_count = _transport_attempt_count(document)
        snapshot_archive_ready = _snapshot_archive_ready(document)
        return ResearchReadWindow(
            window_id=f"window-{window_index}",
            hit_id=document.hit_id,
            source_id=document.source_id,
            source_title=document.source_title,
            query=document.query,
            query_family=requirement_targets["query_family"],
            read_focus=_read_focus(
                plan,
                document.source_title,
                external=False,
                target_labels=requirement_targets["labels"],
                target_columns=requirement_targets["columns"],
            ),
            window_text=document.snapshot_text,
            retention_reason=_retention_reason(
                plan,
                external=False,
                target_labels=requirement_targets["labels"],
            ),
            token_estimate=_estimate_tokens(document.snapshot_text),
            target_requirement_ids=requirement_targets["ids"],
            target_requirement_labels=requirement_targets["labels"],
            target_columns=requirement_targets["columns"],
            url=document.url,
            provider=document.provider,
            adapter="workspace",
            snapshot_status=document.snapshot_status,
            snapshot_key=document.snapshot_key,
            fetch_status=document.fetch_status,
            fetch_method=document.fetch_method,
            content_origin=document.content_origin,
            content_type_label=document.content_type_label,
            fetch_error_reason=document.fetch_error_reason,
            fetch_failure_code=document.fetch_failure_code,
            fetch_attempts=list(document.fetch_attempts),
            transport_chain=list(document.transport_chain),
            transport_resolution=document.transport_resolution,
            transport_fallback_reason=document.transport_fallback_reason,
            transport_fallback_code=document.transport_fallback_code,
            transport_attempt_count=transport_attempt_count,
            snapshot_archive_ready=snapshot_archive_ready,
            source_domain=document.source_domain,
            source_quality=document.source_quality,
            source_quality_score=document.source_quality_score,
            read_strategy=read_strategy,
            untrusted_content=document.untrusted_content,
            prompt_injection_detected=document.prompt_injection_detected,
            prompt_injection_signals=list(document.prompt_injection_signals),
            source_type=document.source_type,
            author=document.author,
            institution=document.institution,
            published_at=document.published_at,
            updated_at=document.updated_at,
            freshness_status=document.freshness_status,
        )


class UrlReadAdapter:
    def can_read(self, task_input: ResearchTaskInput, document: ResearchFetchedDocument) -> bool:
        del task_input
        return bool(document.url.strip()) and document.adapter != "workspace"

    def read_hit(
        self,
        task_input: ResearchTaskInput,
        plan: ResearchPlan,
        document: ResearchFetchedDocument,
        window_index: int,
    ) -> ResearchReadWindow | None:
        del task_input
        if not document.url.strip():
            return None
        read_strategy = infer_read_strategy(plan, _as_search_hit(document))
        fetched = document.fetch_status == "FETCHED" or document.content_origin == "FETCHED_SNAPSHOT"
        requirement_targets = _requirement_target_context(plan, document.query)
        transport_attempt_count = _transport_attempt_count(document)
        snapshot_archive_ready = _snapshot_archive_ready(document)
        return ResearchReadWindow(
            window_id=f"window-{window_index}",
            hit_id=document.hit_id,
            source_id=document.source_id,
            source_title=document.source_title,
            query=document.query,
            query_family=requirement_targets["query_family"],
            read_focus=_read_focus(
                plan,
                document.source_title,
                external=True,
                target_labels=requirement_targets["labels"],
                target_columns=requirement_targets["columns"],
            ),
            window_text=document.snapshot_text,
            retention_reason=_retention_reason(
                plan,
                external=True,
                fetched=fetched,
                failure_reason=document.fetch_error_reason,
                target_labels=requirement_targets["labels"],
            ),
            token_estimate=_estimate_tokens(document.snapshot_text),
            target_requirement_ids=requirement_targets["ids"],
            target_requirement_labels=requirement_targets["labels"],
            target_columns=requirement_targets["columns"],
            url=document.url,
            provider=document.provider,
            adapter="external_url",
            snapshot_status=document.snapshot_status,
            snapshot_key=document.snapshot_key,
            fetch_status=document.fetch_status,
            fetch_method=document.fetch_method,
            content_origin=document.content_origin,
            content_type_label=document.content_type_label,
            fetch_error_reason=document.fetch_error_reason,
            fetch_failure_code=document.fetch_failure_code,
            fetch_attempts=list(document.fetch_attempts),
            transport_chain=list(document.transport_chain),
            transport_resolution=document.transport_resolution,
            transport_fallback_reason=document.transport_fallback_reason,
            transport_fallback_code=document.transport_fallback_code,
            transport_attempt_count=transport_attempt_count,
            snapshot_archive_ready=snapshot_archive_ready,
            source_domain=document.source_domain,
            source_quality=document.source_quality,
            source_quality_score=document.source_quality_score,
            read_strategy=read_strategy,
        )


class CompositeReadAdapter:
    def __init__(self, adapters: list[ReadAdapter]) -> None:
        self.adapters = adapters

    def read(
        self,
        task_input: ResearchTaskInput,
        plan: ResearchPlan,
        fetched_documents: list[ResearchFetchedDocument],
    ) -> list[ResearchReadWindow]:
        windows: list[ResearchReadWindow] = []
        for document in fetched_documents:
            window = self._read_first_supported_document(
                task_input,
                plan,
                document,
                window_index=len(windows) + 1,
            )
            if window is not None:
                windows.append(window)
        return windows

    def _read_first_supported_document(
        self,
        task_input: ResearchTaskInput,
        plan: ResearchPlan,
        document: ResearchFetchedDocument,
        window_index: int,
    ) -> ResearchReadWindow | None:
        for adapter in self.adapters:
            if adapter.can_read(task_input, document):
                return adapter.read_hit(task_input, plan, document, window_index)
        return None


def build_default_read_adapter() -> CompositeReadAdapter:
    return CompositeReadAdapter(
        [
            WorkspaceReadAdapter(),
            UrlReadAdapter(),
        ]
    )


def run_research_read(
    task_input: ResearchTaskInput,
    plan: ResearchPlan,
    search_hits: list[ResearchSearchHit] | None = None,
    *,
    fetched_documents: list[ResearchFetchedDocument] | None = None,
    adapter: CompositeReadAdapter | None = None,
) -> list[ResearchReadWindow]:
    read_adapter = adapter or build_default_read_adapter()
    documents = fetched_documents
    if documents is None:
        documents = run_research_fetch(task_input, plan, search_hits or [])
    return read_adapter.read(task_input, plan, documents)


def _estimate_tokens(text: str) -> int:
    word_count = len(text.split())
    cjk_count = len(re.findall(r"[\u4e00-\u9fff]", text))
    return max(1, word_count + cjk_count // 2)


def _read_focus(
    plan: ResearchPlan,
    source_title: str,
    external: bool,
    *,
    target_labels: list[str],
    target_columns: list[str],
) -> str:
    recovery_mode = str(plan.stop_contract.get("recovery_mode", "")).strip().upper()
    requirement_focus = _requirement_focus_suffix(target_labels, target_columns)
    if recovery_mode == "COUNTERFACTUAL_RECHECK":
        prefix = "Read external URL" if external else "Read"
        return (
            f"{prefix} {source_title} for counterfactual recheck against the current conclusion: "
            f"{plan.normalized_question}{requirement_focus}"
        )
    if recovery_mode == "READ_MORE":
        prefix = "Expand external reading for" if external else "Expand reading for"
        return (
            f"{prefix} {source_title} to recover one more verifier-grounded window for: "
            f"{plan.normalized_question}{requirement_focus}"
        )
    if recovery_mode == "EXTRACT_AGAIN":
        prefix = "Reuse external window for" if external else "Reuse window for"
        return (
            f"{prefix} {source_title} and retry grounded evidence extraction for: "
            f"{plan.normalized_question}{requirement_focus}"
        )
    if external:
        return f"Read external URL {source_title} for claims that answer: {plan.normalized_question}{requirement_focus}"
    return f"Read {source_title} for claims that answer: {plan.normalized_question}{requirement_focus}"


def _retention_reason(
    plan: ResearchPlan,
    external: bool,
    fetched: bool = False,
    failure_reason: str = "",
    target_labels: list[str] | None = None,
) -> str:
    recovery_mode = str(plan.stop_contract.get("recovery_mode", "")).strip().upper()
    requirement_suffix = _requirement_retention_suffix(target_labels or [])
    if recovery_mode == "COUNTERFACTUAL_RECHECK":
        return (
            "kept by tool_response_retention_budget as a counterfactual recovery window "
            f"for verifier-scoped conflict recheck{requirement_suffix}"
        )
    if recovery_mode == "READ_MORE":
        return (
            "kept by tool_response_retention_budget as a recovery follow-up window "
            f"to extend verifier coverage{requirement_suffix}"
        )
    if recovery_mode == "EXTRACT_AGAIN":
        return (
            "kept by tool_response_retention_budget as a retained recovery window "
            f"for evidence re-extraction{requirement_suffix}"
        )
    if external and fetched:
        return f"kept by tool_response_retention_budget after url snapshot fetch{requirement_suffix}"
    if external:
        failure_suffix = failure_reason or "no url snapshot transport configured"
        return (
            "kept by tool_response_retention_budget as external url fallback; "
            f"{failure_suffix}{requirement_suffix}"
        )
    return (
        "kept by tool_response_retention_budget because the hit is inside "
        f"the current workspace source snapshot{requirement_suffix}"
    )


def _requirement_focus_suffix(labels: list[str], columns: list[str]) -> str:
    parts: list[str] = []
    if labels:
        parts.append("prioritize " + "; ".join(labels[:2]))
    if columns:
        parts.append("fill columns " + ", ".join(columns[:3]))
    return f" | {'; '.join(parts)}" if parts else ""


def _requirement_retention_suffix(labels: list[str]) -> str:
    if not labels:
        return ""
    return " while targeting " + "; ".join(labels[:2])


def _requirement_target_context(
    plan: ResearchPlan,
    query: str,
) -> dict[str, object]:
    recovery_ids = _string_list(plan.stop_contract.get("recovery_target_requirement_ids"))
    recovery_labels = _string_list(plan.stop_contract.get("recovery_target_requirement_labels"))
    recovery_columns = _string_list(plan.stop_contract.get("recovery_target_columns"))
    query_family = _query_family(query)
    if recovery_ids or recovery_labels or recovery_columns:
        return {
            "ids": recovery_ids,
            "labels": recovery_labels,
            "columns": recovery_columns,
            "query_family": query_family,
        }

    contract = _required_finding_contract(plan)
    matched_requirements = [
        requirement
        for requirement in contract
        if str(requirement.get("label", "")).strip()
        and str(requirement.get("label", "")).strip().lower() in query.lower()
    ]
    if not matched_requirements:
        requirement_type = _family_requirement_type(query_family)
        if requirement_type:
            matched_requirements = [
                requirement
                for requirement in contract
                if str(requirement.get("requirement_type", "")).strip().upper() == requirement_type
            ]
    ids = [
        str(requirement.get("requirement_id", "")).strip()
        for requirement in matched_requirements
        if str(requirement.get("requirement_id", "")).strip()
    ]
    labels = [
        str(requirement.get("label", "")).strip()
        for requirement in matched_requirements
        if str(requirement.get("label", "")).strip()
    ]
    columns: list[str] = []
    for requirement in matched_requirements:
        for column in _string_list(requirement.get("required_columns")):
            if column not in columns:
                columns.append(column)
    return {
        "ids": ids,
        "labels": labels,
        "columns": columns,
        "query_family": query_family,
    }


def _required_finding_contract(plan: ResearchPlan) -> list[dict[str, object]]:
    raw = plan.stop_contract.get("required_finding_contract")
    if not isinstance(raw, list):
        return []
    return [dict(item) for item in raw if isinstance(item, dict)]


def _string_list(value: object) -> list[str]:
    if not isinstance(value, list):
        return []
    return [str(item).strip() for item in value if str(item).strip()]


def _query_family(query: str) -> str:
    normalized = (query or "").strip().lower()
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


def _family_requirement_type(query_family: str) -> str:
    if query_family == "direct_evidence":
        return "GOAL_FINDING"
    if query_family == "verified_evidence":
        return "CONSTRAINT_FINDING"
    if query_family == "counterfactual":
        return "CONFLICT_FINDING"
    return ""


def _as_search_hit(document: ResearchFetchedDocument) -> ResearchSearchHit:
    return ResearchSearchHit(
        hit_id=document.hit_id,
        source_id=document.source_id,
        source_title=document.source_title,
        query=document.query,
        rank=document.rank,
        snippet=document.snapshot_text[:200],
        confidence_score=document.source_quality_score,
        retrieval_reason=document.fetch_status,
        search_angle=document.search_angle,
        url=document.url,
        provider=document.provider,
        adapter=document.adapter,
        source_domain=document.source_domain,
        source_quality=document.source_quality,
        source_quality_score=document.source_quality_score,
    )


def _transport_attempt_count(document: ResearchFetchedDocument) -> int:
    attempts = [item for item in document.transport_chain if item] or [item for item in document.fetch_attempts if item]
    return len(dict.fromkeys(attempts))


def _snapshot_archive_ready(document: ResearchFetchedDocument) -> bool:
    return bool(
        document.snapshot_text.strip()
        and document.snapshot_key.strip()
        and document.fetch_status == "FETCHED"
        and document.content_origin == "FETCHED_SNAPSHOT"
        and document.snapshot_status not in {"WORKSPACE", "FALLBACK", "FETCH_FAILED"}
    )
