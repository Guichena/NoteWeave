"""MA4G constrained DEEP_CELL execution producing one atomic completion.

Search/fetch/read/extract and provider permits happen before the database
transaction.  Evidence and candidates remain local and immutable until the
consumer submits the single completion envelope to the Backend.
"""

from __future__ import annotations

import hashlib
import unicodedata
from copy import copy
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from typing import Protocol

from app.agent_command_contract import ResearchAgentCommand
from app.agent_task_client import AgentTaskClaim
from app.config import load_settings
from app.execution_control import require_execution_active
from app.extractor import extract_evidence_cards
from app.fetch_adapters import (
    CompositeFetchAdapter,
    WorkspaceFetchAdapter,
    build_default_fetch_adapter,
    run_research_fetch,
)
from app.llm_client import OpenAICompatibleLlmClient
from app.models import (
    ControlPack,
    ResearchColumn,
    ResearchPlan,
    ResearchSchema,
    ResearchTaskInput,
    ResearchTaskInputPayload,
    SourceScopeItem,
)
from app.planner import build_research_plan
from app.read_adapters import CompositeReadAdapter, WorkspaceReadAdapter, build_default_read_adapter, run_research_read
from app.role_executor import RoleExecutorFactory, RoleProfile
from app.research_agent_completion_contract import (
    ResearchAgentCompletionEnvelope,
    build_completion_envelope,
    canonical_json_digest,
    require_worker_instance_id,
)
from app.search_adapters import SeedSourceSearchAdapter, build_default_search_adapter, run_research_search
from app.task_snapshot_contract import ResearchAgentTaskSnapshot, require_trusted_claim_snapshot


class PermitClient(Protocol):
    def require_permit(self, snapshot: ResearchAgentTaskSnapshot, tool_identity: str) -> None:
        ...

    def archive_external_snapshot(
        self, snapshot: ResearchAgentTaskSnapshot, *, window_id: str, source_id: str, source_title: str,
        source_url: str, provider: str, adapter: str, content_text: str,
    ) -> object:
        ...


class DeepCellToolchain(Protocol):
    def search(self, task_input: ResearchTaskInput, plan: ResearchPlan, *, allow_external: bool) -> list[object]:
        ...

    def fetch(self, task_input: ResearchTaskInput, plan: ResearchPlan, hits: list[object], *, allow_external: bool) -> list[object]:
        ...

    def read(self, task_input: ResearchTaskInput, plan: ResearchPlan, documents: list[object], *, allow_external: bool) -> list[object]:
        ...

    def extract(self, task_input: ResearchTaskInput, plan: ResearchPlan, windows: list[object], *, llm_client: object | None) -> list[object]:
        ...


@dataclass
class ExistingResearchToolchain:
    """Production adapter wired to the existing hardened Research adapters."""

    def search(self, task_input: ResearchTaskInput, plan: ResearchPlan, *, allow_external: bool) -> list[object]:
        adapter = build_default_search_adapter() if allow_external else SeedSourceSearchAdapter()
        return list(run_research_search(task_input, plan, adapter=adapter))

    def fetch(self, task_input: ResearchTaskInput, plan: ResearchPlan, hits: list[object], *, allow_external: bool) -> list[object]:
        adapter = build_default_fetch_adapter() if allow_external else CompositeFetchAdapter([WorkspaceFetchAdapter()], max_concurrency=1)
        return list(run_research_fetch(task_input, plan, list(hits), adapter=adapter))

    def read(self, task_input: ResearchTaskInput, plan: ResearchPlan, documents: list[object], *, allow_external: bool) -> list[object]:
        adapter = build_default_read_adapter() if allow_external else CompositeReadAdapter([WorkspaceReadAdapter()])
        return list(run_research_read(task_input, plan, fetched_documents=list(documents), adapter=adapter))

    def extract(self, task_input: ResearchTaskInput, plan: ResearchPlan, windows: list[object], *, llm_client: object | None) -> list[object]:
        return list(extract_evidence_cards(task_input, plan, list(windows), llm_client=llm_client))


class DeepCellExecutor:
    """Callable supplied explicitly to the MA4 command consumer for DEEP_CELL only."""

    def __init__(self, permit_client: PermitClient, worker_instance_id: str, *, toolchain: DeepCellToolchain | None = None,
                 role_factory: RoleExecutorFactory | None = None, enable_llm: bool = True) -> None:
        self.permit_client = permit_client
        self.worker_instance_id = require_worker_instance_id(worker_instance_id)
        self.toolchain = toolchain or ExistingResearchToolchain()
        self.role_factory = role_factory or RoleExecutorFactory()
        self.enable_llm = enable_llm

    def __call__(self, command: ResearchAgentCommand, claim: AgentTaskClaim) -> ResearchAgentCompletionEnvelope:
        snapshot = require_trusted_claim_snapshot(claim)
        if command.research_run_id != snapshot.research_run_id:
            raise ValueError("command research run does not match claimed snapshot")
        counterfactual_slot = (
            snapshot.role == "COUNTERFACTUAL"
            and snapshot.schema_version == "research-agent-task-snapshot.v2"
            and snapshot.candidate_quorum == 2
            and snapshot.candidate_slot == 2
            and snapshot.high_risk is True
            and snapshot.quorum_group_key is not None
        )
        excluded_source_ids = snapshot.source_policy.get("excluded_source_ids")
        repair_reason_digest = snapshot.query_policy.get("repair_reason_digest")
        counterfactual_repair = (
            snapshot.role == "COUNTERFACTUAL"
            and snapshot.schema_version == "research-agent-task-snapshot.v2"
            and snapshot.candidate_quorum == 1
            and snapshot.candidate_slot == 1
            and snapshot.high_risk is False
            and snapshot.quorum_group_key is None
            and snapshot.branch_id == "branch-counterfactual"
            and snapshot.logical_task_key is not None
            and snapshot.logical_task_key.startswith("counterfactual:")
            and isinstance(repair_reason_digest, str)
            and bool(repair_reason_digest.strip())
            and isinstance(excluded_source_ids, list)
            and bool(excluded_source_ids)
            and all(isinstance(source_id, str) and bool(source_id.strip()) for source_id in excluded_source_ids)
        )
        if snapshot.role != "DEEP_CELL" and not counterfactual_slot and not counterfactual_repair:
            raise ValueError(
                "atomic DeepCellExecutor requires DEEP_CELL or an authoritative counterfactual quorum/repair task"
            )
        task_input, plan, allow_external = _build_task_scope(snapshot)
        context = self.role_factory.create(snapshot.role, _execution_key(claim))
        def require_active() -> None:
            context.cancel_token.require_active()
            require_execution_active()

        llm_client = _build_execution_llm(require_active, context.profile) if self.enable_llm else None

        require_active()
        context.toolbox.require("search")
        self.permit_client.require_permit(snapshot, "search")
        require_active()
        hits = self.toolchain.search(task_input, plan, allow_external=allow_external)
        context.usage.add("search_calls")

        require_active()
        context.toolbox.require("fetch")
        self.permit_client.require_permit(snapshot, "fetch")
        require_active()
        documents = self.toolchain.fetch(task_input, plan, hits, allow_external=allow_external)
        context.usage.add("fetch_calls")

        require_active()
        context.toolbox.require("read")
        self.permit_client.require_permit(snapshot, "read")
        require_active()
        windows = self.toolchain.read(task_input, plan, documents, allow_external=allow_external)
        context.usage.add("read_calls")

        require_active()
        windows, archived_external_windows = _archive_external_windows(snapshot, windows, self.permit_client)

        require_active()
        context.toolbox.require("extract")
        self.permit_client.require_permit(snapshot, "extract")
        require_active()
        cards = self.toolchain.extract(task_input, plan, windows, llm_client=llm_client)
        context.usage.add("extract_calls")
        require_active()

        trusted_evidence = _trusted_evidence_payload(snapshot, cards, windows)
        candidates = _candidate_payload(snapshot, cards, trusted_evidence)
        termination = "CANDIDATES_PROPOSED" if candidates else "EVIDENCE_ONLY" if trusted_evidence else "NO_SUPPORTED_CANDIDATE"
        trace = {
            "schema_version": "research-agent-deep-cell-trace.v1",
            "task_id": snapshot.task_id,
            "lease_epoch": snapshot.lease_epoch,
            "fencing_token": snapshot.fencing_token,
            "phases": ["search", "fetch", "read", "extract"],
            "counts": {
                "search_hits": len(hits),
                "documents": len(documents),
                "windows": len(windows),
                "extracted_cards": len(cards),
                "trusted_evidence": len(trusted_evidence),
                "external_archived_windows": archived_external_windows,
                "candidates": len(candidates),
            },
            "termination": termination,
        }
        budget_usage: dict[str, int] = {
            "search_calls": int(context.usage.values.get("search_calls", 0)),
            "fetch_calls": int(context.usage.values.get("fetch_calls", 0)),
            "read_calls": int(context.usage.values.get("read_calls", 0)),
            "extract_calls": int(context.usage.values.get("extract_calls", 0)),
            "evidence_cards": len(trusted_evidence),
            "candidates_submitted": len(candidates),
        }
        budget_usage["llm_calls"] = _llm_provider_call_count(llm_client)
        return build_completion_envelope({
            "schema_version": "research-agent-completion.v1",
            "task_id": claim.agent_task_id,
            "worker_instance_id": self.worker_instance_id,
            "lease_epoch": claim.lease_epoch,
            "fencing_token": claim.fencing_token,
            "execution_key": context.execution_id,
            "task_snapshot_digest": snapshot.snapshot_digest,
            "termination_reason": termination,
            "budget_usage": budget_usage,
            "telemetry": {"search_hits": len(hits), "documents": len(documents), "windows": len(windows)},
            "trace_digest": canonical_json_digest(trace),
            "evidence": trusted_evidence,
            "candidates": candidates,
        })
def _build_task_scope(snapshot: ResearchAgentTaskSnapshot) -> tuple[ResearchTaskInput, ResearchPlan, bool]:
    raw_query = snapshot.query_policy.get("query")
    raw_scope = snapshot.source_policy.get("source_scope")
    if not isinstance(raw_query, str) or not raw_query.strip() or not isinstance(raw_scope, list):
        raise ValueError("DEEP_CELL snapshot requires query_policy.query and source_policy.source_scope")
    source_scope = [SourceScopeItem.model_validate(item) for item in raw_scope]
    columns: list[ResearchColumn] = []
    for target in snapshot.target_cells:
        entity, separator, column = target.cell_id.partition(":")
        if not separator or entity != snapshot.entity_id or not column.strip():
            raise ValueError("DEEP_CELL target cell is outside the snapshot entity scope")
        if column not in {item.key for item in columns}:
            columns.append(ResearchColumn(key=column, label=column, required=True))
    task_input = ResearchTaskInput(
        task_id=snapshot.task_id,
        workspace_id=snapshot.workspace_id,
        target_id=snapshot.research_run_id,
        source_scope=source_scope,
        control_pack=ControlPack(pack_type="RESEARCH_AGENT", target_key=snapshot.research_run_id,
                                 task_neighborhood="MA4G_DEEP_CELL", evidence_policy=["server-snapshot-only"]),
        input_payload=ResearchTaskInputPayload(question=raw_query.strip(), profile_key="RESEARCH_AGENT"),
    )
    plan = build_research_plan(task_input).model_copy(update={
        "research_schema": ResearchSchema(columns=columns, research_type=snapshot.role, entity_type="TARGET", required_column_count=len(columns)),
        "state_columns": [column.key for column in columns],
        "plan_revision": snapshot.plan_revision,
        "research_type": snapshot.role,
    })
    plan.stop_contract["deep_cell_entity_id"] = snapshot.entity_id
    allow_external = bool(snapshot.source_policy.get("allow_external_search", False)) and bool(
        snapshot.source_policy.get("allow_external_fetch", False)
    )
    return task_input, plan, allow_external


def _build_execution_llm(cancellation_checker, profile: RoleProfile):
    settings = load_settings()
    if not settings.llm_api_key.strip() or not settings.llm_model.strip():
        return None
    purpose_options = {
        purpose: dict(options)
        for purpose, options in settings.llm_purpose_options.items()
    }
    purpose_options.setdefault("research.extract", {}).setdefault("temperature", profile.temperature)
    max_total_calls = (
        min(settings.llm_max_total_calls, profile.max_llm_calls)
        if settings.llm_max_total_calls > 0
        else profile.max_llm_calls
    )
    client = OpenAICompatibleLlmClient(
        api_key=settings.llm_api_key, model=settings.llm_model, base_url=settings.llm_base_url,
        timeout_seconds=settings.llm_timeout_seconds, max_attempts=settings.llm_max_attempts,
        max_total_calls=max_total_calls, input_cost_per_million=settings.llm_input_cost_per_million,
        output_cost_per_million=settings.llm_output_cost_per_million, purpose_options=purpose_options,
        max_input_chars=settings.llm_max_input_chars, context_safety_buffer_chars=settings.llm_context_safety_buffer_chars,
    )
    client.set_cancellation_checker(cancellation_checker)
    return client


def _execution_key(claim: AgentTaskClaim) -> str:
    return f"deep-cell:{claim.agent_task_id}:{claim.lease_epoch}:{claim.fencing_token}"


def _archive_external_windows(
    snapshot: ResearchAgentTaskSnapshot, windows: list[object], permit_client: PermitClient
) -> tuple[list[object], int]:
    """Upgrade only server-acknowledged external windows to the completion authority state."""
    result: list[object] = []
    archived = 0
    for window in windows:
        if not _external_archive_candidate(window):
            result.append(window)
            continue
        receipt = permit_client.archive_external_snapshot(
            snapshot,
            window_id=str(getattr(window, "window_id", "")),
            source_id=str(getattr(window, "source_id", "")),
            source_title=str(getattr(window, "source_title", "")),
            source_url=str(getattr(window, "url", "")),
            provider=str(getattr(window, "provider", "")),
            adapter=str(getattr(window, "adapter", "")),
            content_text=str(getattr(window, "window_text", "")),
        )
        if (getattr(receipt, "snapshot_status", "") != "EXTERNAL_ARCHIVED"
                or getattr(receipt, "task_id", "") != snapshot.task_id
                or getattr(receipt, "window_id", "") != getattr(window, "window_id", "")
                or getattr(receipt, "source_id", "") != getattr(window, "source_id", "")
                or not str(getattr(receipt, "snapshot_key", "")).strip()):
            raise ValueError("external archive receipt does not bind the read window")
        result.append(_with_archive_receipt(window, str(getattr(receipt, "snapshot_key"))))
        archived += 1
    return result, archived


def _external_archive_candidate(window: object) -> bool:
    return bool(
        getattr(window, "adapter", "") == "external_url"
        and getattr(window, "snapshot_status", "") != "WORKSPACE"
        and bool(getattr(window, "snapshot_archive_ready", False))
        and str(getattr(window, "window_id", "")).strip()
        and str(getattr(window, "source_id", "")).strip()
        and str(getattr(window, "source_title", "")).strip()
        and str(getattr(window, "url", "")).strip()
        and str(getattr(window, "provider", "")).strip()
        and str(getattr(window, "window_text", "")).strip()
    )


def _with_archive_receipt(window: object, snapshot_key: str) -> object:
    update = {"snapshot_status": "EXTERNAL_ARCHIVED", "snapshot_key": snapshot_key, "snapshot_archive_ready": True}
    model_copy = getattr(window, "model_copy", None)
    if callable(model_copy):
        return model_copy(update=update)
    clone = copy(window)
    for key, value in update.items():
        setattr(clone, key, value)
    return clone


def _trusted_evidence_payload(snapshot: ResearchAgentTaskSnapshot, cards: list[object],
                              windows: list[object]) -> list[dict[str, object]]:
    """Prepare only workspace or server-archived external evidence for atomic completion."""
    windows_by_id = {getattr(window, "window_id", ""): window for window in windows}
    payload: list[dict[str, object]] = []
    for card in cards:
        window = windows_by_id.get(getattr(card, "window_id", ""))
        status = getattr(window, "snapshot_status", "") if window is not None else ""
        if status not in {"WORKSPACE", "EXTERNAL_ARCHIVED"}:
            continue
        if not str(getattr(card, "evidence_id", "")).strip():
            continue
        relation = str(getattr(card, "relation_type", "")).upper()
        if relation not in {"SUPPORTS", "WEAK_SUPPORT", "CONFLICTS"}:
            continue
        item = {
            "evidence_key": _scoped_evidence_key(snapshot, card),
            "window_id": str(getattr(card, "window_id", "")),
            "source_id": str(getattr(card, "source_id", "")),
            "source_title": str(getattr(card, "source_title", "")),
            "search_query": str(getattr(window, "query", "")),
            "read_focus": str(getattr(window, "read_focus", "")),
            "quote_text": str(getattr(card, "quote_text", "")),
            "claim_text": str(getattr(card, "claim_text", "")),
            "relation_type": relation,
            "support_score_ppm": _score_ppm(getattr(card, "support_score", 0.0)),
            "conflict_score_ppm": _score_ppm(getattr(card, "conflict_score", 0.0)),
            "snapshot_status": status,
        }
        if all(str(item[field]).strip() for field in (
            "evidence_key", "window_id", "source_id", "source_title", "search_query", "read_focus", "quote_text", "claim_text"
        )):
            payload.append(item)
    return payload


def _candidate_payload(snapshot: ResearchAgentTaskSnapshot, cards: list[object], evidence: list[dict[str, object]]) -> list[dict[str, object]]:
    evidence_keys = {str(item["evidence_key"]) for item in evidence}
    targets = {item.cell_id: item.expected_version for item in snapshot.target_cells}
    grouped: dict[tuple[str, str], dict[str, object]] = {}
    for card in cards:
        cell_id = f"{getattr(card, 'entity_id', '')}:{getattr(card, 'column_key', '')}"
        evidence_key = _scoped_evidence_key(snapshot, card)
        value = unicodedata.normalize("NFC", str(getattr(card, "claim_text", "")))
        if (cell_id not in targets or evidence_key not in evidence_keys or not value
                or str(getattr(card, "relation_type", "")).upper() != "SUPPORTS"):
            continue
        key = (cell_id, value)
        group = grouped.setdefault(key, {"evidence_keys": set(), "confidence_ppm": 0})
        group["evidence_keys"].add(evidence_key)  # type: ignore[union-attr]
        group["confidence_ppm"] = max(int(group["confidence_ppm"]), _score_ppm(getattr(card, "support_score", 0.0)))

    by_cell: dict[str, list[tuple[str, dict[str, object]]]] = {}
    for (cell_id, value), group in grouped.items():
        by_cell.setdefault(cell_id, []).append((value, group))
    proposals: list[dict[str, object]] = []
    for cell_id in sorted(by_cell):
        value, group = sorted(
            by_cell[cell_id], key=lambda item: (-int(item[1]["confidence_ppm"]), item[0])
        )[0]
        candidate_evidence = sorted(str(item) for item in group["evidence_keys"])  # type: ignore[union-attr]
        stable = _stable_hex(snapshot.task_id, str(snapshot.lease_epoch), str(snapshot.fencing_token),
                             cell_id, str(targets[cell_id]), value, *candidate_evidence)
        proposals.append({
            "candidate_key": "candidate:" + stable[:48],
            "cell_key": cell_id,
            "base_cell_version": targets[cell_id],
            "candidate_value": value,
            "evidence_keys": candidate_evidence,
            "confidence_ppm": int(group["confidence_ppm"]),
        })
    return proposals


def _score_ppm(value: object) -> int:
    """Convert an internal 0..1 score to an unambiguous integer boundary."""
    if isinstance(value, bool):
        raise ValueError("boolean evidence score is invalid")
    try:
        decimal_value = Decimal(str(value))
    except (InvalidOperation, ValueError) as exc:
        raise ValueError("evidence score must be finite and numeric") from exc
    if not decimal_value.is_finite() or decimal_value < 0 or decimal_value > 1:
        raise ValueError("evidence score must be between zero and one")
    return int((decimal_value * 1_000_000).quantize(Decimal("1"), rounding=ROUND_HALF_UP))


def _scoped_evidence_key(snapshot: ResearchAgentTaskSnapshot, card: object) -> str:
    """Prevent two task completions in one run from sharing provenance keys."""
    stable = _stable_hex(
        snapshot.task_id,
        str(snapshot.lease_epoch),
        str(snapshot.fencing_token),
        str(getattr(card, "evidence_id", "")),
        str(getattr(card, "window_id", "")),
        str(getattr(card, "source_id", "")),
        str(getattr(card, "entity_id", "")),
        str(getattr(card, "column_key", "")),
        str(getattr(card, "quote_text", "")),
        str(getattr(card, "claim_text", "")),
        str(getattr(card, "relation_type", "")).upper(),
    )
    return "evidence:" + stable[:48]


def _stable_hex(*parts: str) -> str:
    digest = hashlib.sha256()
    for part in parts:
        encoded = unicodedata.normalize("NFC", part).encode("utf-8")
        digest.update(len(encoded).to_bytes(4, "big"))
        digest.update(encoded)
    return digest.hexdigest()


def _llm_provider_call_count(llm_client: object | None) -> int:
    if llm_client is None:
        return 0
    summary = getattr(llm_client, "usage_summary", None)
    if not callable(summary):
        return 0
    payload = summary()
    if not isinstance(payload, dict):
        raise ValueError("LLM usage summary must be an object")
    value = payload.get("provider_call_count", 0)
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError("LLM provider call count must be a non-negative integer")
    return value
