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
import os
import re
from typing import Protocol
from urllib.parse import urlsplit

from app.agent_command_contract import ResearchAgentCommand
from app.agent_task_client import AgentTaskClaim, ArchivedExternalSnapshot
from app.config import load_settings
from app.execution_control import require_execution_active
from app.extraction_result import ExtractionResult
from app.extractor import extract_evidence_cards_detailed
from app.fetch_adapters import (
    CompositeFetchAdapter,
    WorkspaceFetchAdapter,
    build_default_fetch_adapter,
    run_research_fetch,
)
from app.llm_client import InvalidJsonFaultLlmClient, OpenAICompatibleLlmClient, QuoteTamperFaultLlmClient
from app.models import (
    ControlPack,
    ResearchColumn,
    ResearchIntent,
    ResearchPlan,
    ResearchSchema,
    ResearchSearchHit,
    ResearchReadWindow,
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
from app.search_adapters import (
    SeedSourceSearchAdapter,
    build_web_plus_seed_search_adapter,
    build_web_search_adapter,
    build_search_query_plan,
    run_research_search,
)
from app.task_snapshot_contract import ResearchAgentTaskSnapshot, require_trusted_claim_snapshot


# Keep completion usage inside the coordinator's frozen per-cell reservation.
# Extraction may return more cards, but the agent boundary deliberately admits
# only the six strongest deterministic cards for a single target cell.
MAX_EVIDENCE_CARDS_PER_CELL = 6

_OFFICIAL_PRODUCT_DOMAINS: dict[str, tuple[str, ...]] = {
    "postgresql": ("postgresql.org",),
    "mysql": ("dev.mysql.com", "docs.oracle.com"),
    "openai": ("platform.openai.com",),
    "anthropic": ("docs.anthropic.com",),
    "mongodb": ("mongodb.com",),
    "redis": ("redis.io",),
}

_OFFICIAL_PRODUCT_QUERY_HINTS = {
    "postgresql": "JSONB GIN jsonb_ops jsonb_path_ops operators indexing",
    "mysql": "JSON generated column multi-valued index JSON_TABLE indexing",
}
_OFFICIAL_PRODUCT_SEED_URLS = {
    "postgresql": (
        "https://www.postgresql.org/docs/17/datatype-json.html#JSON-INDEXING",
        "https://www.postgresql.org/docs/17/gin.html",
    ),
    "mysql": (
        "https://docs.oracle.com/cd/E17952_01/mysql-8.4-en/json.html",
        "https://docs.oracle.com/cd/E17952_01/mysql-8.4-en/create-index.html",
    ),
}

_OFFICIAL_SOURCE_MARKERS = (
    "official", "documentation", "docs", "primary source",
    "官方", "官方文档", "一手资料", "原始文档",
)


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

    def extract(self, task_input: ResearchTaskInput, plan: ResearchPlan, windows: list[object], *, llm_client: object | None) -> ExtractionResult:
        ...


@dataclass
class ExistingResearchToolchain:
    """Production adapter wired to the existing hardened Research adapters."""

    def search(self, task_input: ResearchTaskInput, plan: ResearchPlan, *, allow_external: bool) -> list[object]:
        retrieval_mode = str(plan.stop_contract.get("retrieval_mode", "")).upper()
        if retrieval_mode == "WEB_ONLY":
            adapter = build_web_search_adapter()
        elif retrieval_mode == "WEB_PLUS_SEEDS":
            adapter = build_web_plus_seed_search_adapter()
        else:
            adapter = SeedSourceSearchAdapter()
        return list(run_research_search(task_input, plan, adapter=adapter))

    def fetch(self, task_input: ResearchTaskInput, plan: ResearchPlan, hits: list[object], *, allow_external: bool) -> list[object]:
        adapter = build_default_fetch_adapter() if allow_external else CompositeFetchAdapter([WorkspaceFetchAdapter()], max_concurrency=1)
        return list(run_research_fetch(task_input, plan, list(hits), adapter=adapter))

    def read(self, task_input: ResearchTaskInput, plan: ResearchPlan, documents: list[object], *, allow_external: bool) -> list[object]:
        adapter = build_default_read_adapter() if allow_external else CompositeReadAdapter([WorkspaceReadAdapter()])
        return list(run_research_read(task_input, plan, fetched_documents=list(documents), adapter=adapter))

    def extract(self, task_input: ResearchTaskInput, plan: ResearchPlan, windows: list[object], *, llm_client: object | None) -> ExtractionResult:
        return extract_evidence_cards_detailed(task_input, plan, list(windows), llm_client=llm_client)


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
            and snapshot.schema_version in {"research-agent-task-snapshot.v2", "research-agent-task-snapshot.v3"}
            and snapshot.candidate_quorum == 2
            and snapshot.candidate_slot == 2
            and snapshot.high_risk is True
            and snapshot.quorum_group_key is not None
        )
        excluded_source_ids = snapshot.source_policy.get("excluded_source_ids")
        repair_reason_digest = snapshot.query_policy.get("repair_reason_digest")
        web_only_repair = (
            snapshot.source_policy.get("retrieval_mode") == "WEB_ONLY"
            and snapshot.source_policy.get("allow_external_search") is True
            and snapshot.source_policy.get("allow_external_fetch") is True
        )
        authoritative_repair_exclusions = (
            isinstance(excluded_source_ids, list)
            and all(isinstance(source_id, str) and bool(source_id.strip()) for source_id in excluded_source_ids)
            and (bool(excluded_source_ids) or web_only_repair)
        )
        counterfactual_repair = (
            snapshot.role == "COUNTERFACTUAL"
            and snapshot.schema_version in {"research-agent-task-snapshot.v2", "research-agent-task-snapshot.v3"}
            and snapshot.candidate_quorum == 1
            and snapshot.candidate_slot == 1
            and snapshot.high_risk is False
            and snapshot.quorum_group_key is None
            and snapshot.branch_id == "branch-counterfactual"
            and snapshot.logical_task_key is not None
            and snapshot.logical_task_key.startswith(("counterfactual:", "conflict-counterfactual:"))
            and isinstance(repair_reason_digest, str)
            and bool(repair_reason_digest.strip())
            and authoritative_repair_exclusions
        )
        if snapshot.role != "DEEP_CELL" and not counterfactual_slot and not counterfactual_repair:
            raise ValueError(
                "atomic DeepCellExecutor requires DEEP_CELL or an authoritative counterfactual quorum/repair task"
            )
        task_input, plan, allow_external = _build_task_scope(snapshot)
        plan = _scope_search_plan_to_domains(snapshot, plan)
        context = self.role_factory.create(snapshot.role, _execution_key(claim))
        def require_active() -> None:
            context.cancel_token.require_active()
            require_execution_active()

        llm_client = _build_execution_llm(require_active, context.profile) if self.enable_llm else None

        # Recovery inventory must be consulted before any provider permit. A prior lease may
        # already have spent the single search/fetch/read rounds reserved for this task.
        reused = _recoverable_external_windows(snapshot, self.permit_client)
        reuse_focus = _reuse_read_focus(snapshot)
        require_active()

        if not reused:
            context.toolbox.require("search")
            self.permit_client.require_permit(snapshot, "search")
            require_active()
            hits = self.toolchain.search(task_input, plan, allow_external=allow_external)
            hits = _prepend_explicit_allowed_urls(snapshot, plan, hits)
            hits = _filter_external_hits_by_domain(snapshot, hits, plan)
            hits = _interleave_hits_by_domain(hits)
            workspace_hits = _search_workspace_windows(snapshot, task_input, plan, self.permit_client)
            if workspace_hits:
                hits = workspace_hits + [hit for hit in hits if getattr(hit, "adapter", "") != "workspace"]
                hits = [
                    hit.model_copy(update={"rank": index}) if hasattr(hit, "model_copy") else hit
                    for index, hit in enumerate(hits[:int(plan.stop_contract.get("global_search_limit", 8))], start=1)
                ]
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
            reused_count = 0
        else:
            # Once this task owns an archived external snapshot, recovery is replay-only.
            # It performs no new search/fetch/read and therefore consumes no second provider
            # round or grant; extraction resumes from the immutable archived state.
            hits: list[object] = []
            documents: list[object] = []
            windows = [_recovered_window(snapshot, item, reuse_focus) for item in reused]
            reused_count = len(windows)

        require_active()
        windows, archived_external_windows = _archive_external_windows(snapshot, windows, self.permit_client)

        # Explicit local-eval crash point: all external content is already in the
        # server-owned archive, while extraction and completion have not started.
        # os._exit deliberately bypasses finally/consumer commit to exercise real
        # Kafka redelivery, lease reclaim, archive reuse and execution fencing.
        _maybe_crash_after_archive(
            archived_external_windows,
            enabled=(
                archived_external_windows > 0
                and load_settings().research_agent_fault_crash_after_archive
            ),
        )

        require_active()
        context.toolbox.require("extract")
        self.permit_client.require_permit(snapshot, "extract")
        require_active()
        extraction = self.toolchain.extract(task_input, plan, windows, llm_client=llm_client)
        cards = list(extraction.accepted_cards)
        context.usage.add("extract_calls")
        require_active()

        trusted_evidence = _trusted_evidence_payload(snapshot, cards, windows)[:MAX_EVIDENCE_CARDS_PER_CELL]
        candidates = _candidate_payload(snapshot, cards, trusted_evidence)
        termination = "CANDIDATES_PROPOSED" if candidates else "EVIDENCE_ONLY" if trusted_evidence else "NO_SUPPORTED_CANDIDATE"
        # DR-102: keep the reproducibility-safe extraction outcome in the trace
        # digest (reason codes and counts only, never the model response text).
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
                "rejected_cards": extraction.rejected_count,
                "trusted_evidence": len(trusted_evidence),
                "external_archived_windows": archived_external_windows,
                "reused_external_windows": reused_count,
                "candidates": len(candidates),
            },
            "extraction": {
                "termination_reason": extraction.termination_reason.value,
                "rejection_counts": extraction.rejection_counts(),
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
            "schema_version": "research-agent-completion.v2",
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
            "extraction_diagnostics": extraction.diagnostics(),
        })
def _build_task_scope(snapshot: ResearchAgentTaskSnapshot) -> tuple[ResearchTaskInput, ResearchPlan, bool]:
    raw_query = snapshot.query_policy.get("query")
    raw_scope = snapshot.source_policy.get("source_scope")
    if not isinstance(raw_query, str) or not raw_query.strip() or not isinstance(raw_scope, list):
        raise ValueError("DEEP_CELL snapshot requires query_policy.query and source_policy.source_scope")
    source_scope = [SourceScopeItem.model_validate(item) for item in raw_scope]
    allow_external = bool(snapshot.source_policy.get("allow_external_search", False)) and bool(
        snapshot.source_policy.get("allow_external_fetch", False)
    )
    raw_mode = str(snapshot.source_policy.get("retrieval_mode", "")).strip().upper()
    retrieval_mode = raw_mode or (
        "WEB_PLUS_SEEDS" if allow_external and source_scope
        else "WEB_ONLY" if allow_external
        else "SOURCES_ONLY"
    )
    if retrieval_mode not in {"WEB_ONLY", "WEB_PLUS_SEEDS", "SOURCES_ONLY"}:
        raise ValueError("DEEP_CELL snapshot retrieval_mode is invalid")
    if retrieval_mode == "WEB_ONLY" and source_scope:
        raise ValueError("WEB_ONLY snapshot must not contain seed sources")
    if retrieval_mode != "WEB_ONLY" and not source_scope:
        raise ValueError(f"{retrieval_mode} snapshot requires seed sources")
    if (retrieval_mode != "SOURCES_ONLY") != allow_external:
        raise ValueError("DEEP_CELL snapshot retrieval_mode conflicts with external tool policy")
    columns: list[ResearchColumn] = []
    for target in snapshot.target_cells:
        entity, separator, column = target.cell_id.partition(":")
        if not separator or entity != snapshot.entity_id or not column.strip():
            raise ValueError("DEEP_CELL target cell is outside the snapshot entity scope")
        if column not in {item.key for item in columns}:
            columns.append(ResearchColumn(key=column, label=column, required=True))
    entity_label = str(snapshot.query_policy.get("entity_label") or "").strip()
    scoped_query = raw_query.strip()
    if entity_label:
        scoped_query = (
            f"{scoped_query}\n\nCurrent Table-as-State entity: {entity_label}. "
            "Extract claims and evidence only for this entity; do not use another compared entity's "
            "evidence to fill this entity's cells."
        )
    task_input = ResearchTaskInput(
        task_id=snapshot.task_id,
        workspace_id=snapshot.workspace_id,
        target_id=snapshot.research_run_id,
        source_scope=source_scope,
        control_pack=ControlPack.model_validate(snapshot.control_pack) if snapshot.control_pack is not None else ControlPack(
            pack_type="RESEARCH_AGENT",
            target_key=snapshot.research_run_id,
            task_neighborhood="MA4G_DEEP_CELL",
            evidence_policy=["server-snapshot-only"],
        ),
        input_payload=ResearchTaskInputPayload(
            question=scoped_query,
            profile_key="RESEARCH_AGENT",
            research_intent=(
                ResearchIntent.model_validate(snapshot.research_intent)
                if snapshot.research_intent is not None
                else ResearchIntent()
            ),
        ),
    )
    plan = build_research_plan(task_input).model_copy(update={
        "research_schema": ResearchSchema(columns=columns, research_type=snapshot.role, entity_type="TARGET", required_column_count=len(columns)),
        "state_columns": [column.key for column in columns],
        "plan_revision": snapshot.plan_revision,
        "research_type": snapshot.role,
    })
    plan.stop_contract["deep_cell_entity_id"] = snapshot.entity_id
    plan.stop_contract["retrieval_mode"] = retrieval_mode
    return task_input, plan, allow_external


def _maybe_crash_after_archive(
    archived_external_windows: int, *, enabled: bool, exit_fn=None
) -> None:
    if not enabled or archived_external_windows <= 0:
        return
    (exit_fn or os._exit)(86)


def _filter_external_hits_by_domain(
    snapshot: ResearchAgentTaskSnapshot, hits: list[object], plan: ResearchPlan | None = None
) -> list[object]:
    allowed = _effective_allowed_external_domains(snapshot, plan)
    if not allowed:
        return list(hits)
    filtered: list[object] = []
    for hit in hits:
        if str(getattr(hit, "adapter", "")) == "workspace":
            filtered.append(hit)
            continue
        host = str(getattr(hit, "source_domain", "") or "").strip().lower().rstrip(".")
        if not host:
            host = (urlsplit(str(getattr(hit, "url", "") or "")).hostname or "").lower().rstrip(".")
        if any(host == domain or host.endswith("." + domain) for domain in allowed):
            filtered.append(hit)
    return filtered


def _interleave_hits_by_domain(hits: list[object]) -> list[object]:
    """Keep one publisher from monopolizing the bounded fetch/read context."""
    workspace_hits = [hit for hit in hits if str(getattr(hit, "adapter", "")) == "workspace"]
    buckets: dict[str, list[object]] = {}
    domain_order: list[str] = []
    for hit in hits:
        if str(getattr(hit, "adapter", "")) == "workspace":
            continue
        domain = str(getattr(hit, "source_domain", "") or "").strip().lower().rstrip(".")
        if not domain:
            domain = (urlsplit(str(getattr(hit, "url", "") or "")).hostname or "").lower().rstrip(".")
        key = domain or "unknown"
        if key not in buckets:
            buckets[key] = []
            domain_order.append(key)
        buckets[key].append(hit)
    interleaved: list[object] = []
    while any(buckets.values()):
        for domain in domain_order:
            if buckets[domain]:
                interleaved.append(buckets[domain].pop(0))
    combined = [*workspace_hits, *interleaved]
    return [
        hit.model_copy(update={"rank": index}) if hasattr(hit, "model_copy") else hit
        for index, hit in enumerate(combined, start=1)
    ]


def _prepend_explicit_allowed_urls(
    snapshot: ResearchAgentTaskSnapshot, plan: ResearchPlan, hits: list[object]
) -> list[object]:
    allowed = _effective_allowed_external_domains(snapshot, plan)
    if not allowed:
        return list(hits)
    explicit: list[ResearchSearchHit] = []
    seen = {str(getattr(hit, "url", "")) for hit in hits}
    raw_urls = list(re.findall(r"https?://[^\s)'\"]+", plan.normalized_question))
    entity_label = str(snapshot.query_policy.get("entity_label") or "").casefold()
    for product, urls in _OFFICIAL_PRODUCT_SEED_URLS.items():
        if product in entity_label:
            raw_urls.extend(urls)
    for raw_url in raw_urls:
        url = raw_url.rstrip(".,;:]")
        host = (urlsplit(url).hostname or "").lower().rstrip(".")
        if not any(host == domain or host.endswith("." + domain) for domain in allowed) or url in seen:
            continue
        seen.add(url)
        digest = hashlib.sha256(url.encode("utf-8")).hexdigest()[:24]
        explicit.append(ResearchSearchHit(
            hit_id=f"explicit-url-{digest}", source_id=f"web-{digest}",
            source_title=host, query=plan.normalized_question, rank=len(explicit) + 1,
            snippet="Authoritative primary-source URL", confidence_score=1.0,
            retrieval_reason="explicit or entity-scoped official URL", search_angle="source_scoped",
            matched_fields=["url"], coverage_score=1.0, url=url,
            provider="user_supplied", adapter="external_url", source_domain=host,
            source_quality="PRIMARY", source_quality_score=1.0,
        ))
    combined = [*explicit, *hits]
    return [
        hit.model_copy(update={"rank": index}) if hasattr(hit, "model_copy") else hit
        for index, hit in enumerate(combined, start=1)
    ]


def _scope_search_plan_to_domains(
    snapshot: ResearchAgentTaskSnapshot, plan: ResearchPlan
) -> ResearchPlan:
    domains = sorted(_effective_allowed_external_domains(snapshot, plan))
    if not domains:
        return plan
    base_queries = list(plan.query_set) or [plan.normalized_question]
    scoped = [
        f"{_domain_focused_query(query, domain)} site:{domain}"
        for query in base_queries
        for domain in domains
    ]
    stop_contract = dict(plan.stop_contract)
    execution_profile = dict(stop_contract.get("execution_profile", {}))
    execution_profile["search_query_budget"] = max(
        len(domains), int(execution_profile.get("search_query_budget", 4))
    )
    family_budgets = dict(execution_profile.get("query_family_budgets", {}))
    for family in (
        "direct", "intent", "deliverable", "time_range", "constraints",
        "deep_focus", "triangulation", "source_scoped",
    ):
        family_budgets[family] = max(len(domains), int(family_budgets.get(family, 0)))
    execution_profile["query_family_budgets"] = family_budgets
    stop_contract["execution_profile"] = execution_profile
    return plan.model_copy(update={"query_set": scoped, "stop_contract": stop_contract})


def _effective_allowed_external_domains(
    snapshot: ResearchAgentTaskSnapshot, plan: ResearchPlan | None = None
) -> set[str]:
    raw = snapshot.source_policy.get("allowed_external_domains")
    explicit = {
        str(value).strip().lower().rstrip(".")
        for value in raw
        if isinstance(value, str) and value.strip()
    } if isinstance(raw, list) else set()
    if explicit:
        return explicit

    query = str(snapshot.query_policy.get("query") or "")
    entity_label = str(snapshot.query_policy.get("entity_label") or "").casefold()
    intent = snapshot.research_intent if isinstance(snapshot.research_intent, dict) else {}
    intent_text = " ".join([
        str(intent.get("research_goal") or ""),
        " ".join(str(item) for item in intent.get("constraints", []) if str(item).strip()),
    ])
    haystack = f"{query} {intent_text}".casefold()
    if not any(marker.casefold() in haystack for marker in _OFFICIAL_SOURCE_MARKERS):
        return set()
    inferred: set[str] = set()
    if entity_label:
        for product, domains in _OFFICIAL_PRODUCT_DOMAINS.items():
            if product in entity_label:
                inferred.update(domains)
        if inferred:
            return inferred
    for product, domains in _OFFICIAL_PRODUCT_DOMAINS.items():
        if product in haystack:
            inferred.update(domains)
    return inferred


def _domain_focused_query(query: str, domain: str) -> str:
    """Prefer the comparison clause naming the allowlisted publisher."""
    labels = {
        part for part in domain.casefold().split(".")
        if part and part not in {"com", "org", "net", "www", "docs", "platform"}
    }
    clauses = [
        clause.strip(" ,")
        for clause in re.split(r"\s+(?:and|versus|vs\.?)\s+|[;；]", query, flags=re.IGNORECASE)
        if clause.strip(" ,")
    ]
    matches = [clause for clause in clauses if any(label in clause.casefold() for label in labels)]
    if matches:
        focused = max(matches, key=len)
        return focused if "official documentation" in focused.casefold() else f"{focused} official documentation"
    product = next((name for name, domains in _OFFICIAL_PRODUCT_DOMAINS.items() if domain in domains), "")
    if not product or product not in query.casefold():
        return query
    version_match = re.search(rf"\b{re.escape(product)}\s+([0-9]+(?:\.[0-9]+)*)", query, re.IGNORECASE)
    technical_terms = list(dict.fromkeys(re.findall(
        r"\b(?:JSON_TABLE|JSONB?|GIN|GiST|BTREE|multi-valued|generated columns?)\b",
        query,
        re.IGNORECASE,
    )))
    version = version_match.group(1) if version_match else ""
    focus = " ".join([
        product,
        version,
        *technical_terms,
        _OFFICIAL_PRODUCT_QUERY_HINTS.get(product, "index query"),
        "official documentation",
    ])
    return " ".join(focus.split())


def _search_workspace_windows(
    snapshot: ResearchAgentTaskSnapshot,
    task_input: ResearchTaskInput,
    plan: ResearchPlan,
    permit_client: PermitClient,
) -> list[ResearchSearchHit]:
    if not task_input.source_scope:
        return []
    search = getattr(permit_client, "search_workspace_windows", None)
    if not callable(search):
        return []
    limit = max(1, min(16, int(plan.stop_contract.get("global_search_limit", 8))))
    query_plan = build_search_query_plan(plan, remaining_budget=8)
    queries = [item.query for item in query_plan[:8]] or [plan.normalized_question]
    raw_hits = search(snapshot, queries=queries, limit=limit)
    scope = {(source.source_id, source.source_snapshot_id) for source in task_input.source_scope}
    result: list[ResearchSearchHit] = []
    for raw in raw_hits:
        source_id = str(getattr(raw, "source_id", ""))
        snapshot_id = str(getattr(raw, "source_snapshot_id", ""))
        window_id = str(getattr(raw, "source_window_id", ""))
        window_text = str(getattr(raw, "window_text", ""))
        if (source_id, snapshot_id) not in scope or not window_id or not window_text:
            raise ValueError("workspace window search returned evidence outside the task snapshot")
        score_ppm = int(getattr(raw, "score_ppm", 0))
        result.append(ResearchSearchHit(
            hit_id=f"workspace-{window_id}",
            source_id=source_id,
            source_snapshot_id=snapshot_id,
            source_window_id=window_id,
            workspace_window_text=window_text,
            source_title=str(getattr(raw, "source_title", "")),
            query=str(getattr(raw, "query", "")),
            rank=len(result) + 1,
            snippet=window_text[:500],
            confidence_score=max(0.0, min(1.0, score_ppm / 1_000_000)),
            retrieval_reason="lease-bound search matched a frozen workspace source window",
            search_angle="source_scoped",
            matched_fields=["source_window"],
            coverage_score=max(0.0, min(1.0, score_ppm / 1_000_000)),
            provider="workspace",
            adapter="workspace",
            provider_attempts=["workspace"],
            provider_resolution="workspace",
            source_quality="WORKSPACE_SOURCE",
            source_quality_score=0.98,
        ))
    return result


def _build_execution_llm(cancellation_checker, profile: RoleProfile):
    settings = load_settings()
    if settings.research_agent_fault_invalid_json:
        client = InvalidJsonFaultLlmClient()
        client.set_cancellation_checker(cancellation_checker)
        return client
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
    if settings.research_agent_fault_tamper_quote:
        client = QuoteTamperFaultLlmClient(client)
    client.set_cancellation_checker(cancellation_checker)
    return client


def _execution_key(claim: AgentTaskClaim) -> str:
    return f"deep-cell:{claim.agent_task_id}:{claim.lease_epoch}:{claim.fencing_token}"


def _recoverable_external_windows(
    snapshot: ResearchAgentTaskSnapshot, permit_client: PermitClient
) -> list[ArchivedExternalSnapshot]:
    """Ask the server for the external pages this task already archived before re-fetching them.

    The inventory is an optional permit-client capability: deployments without the recovery
    endpoint keep paying for a full re-fetch instead of silently claiming reuse they cannot
    prove. An empty archive is indistinguishable from "nothing was saved yet", so the normal
    first-execution path never changes shape.
    """
    inventory = getattr(permit_client, "archived_external_snapshot_inventory", None)
    if not callable(inventory):
        return []
    rows = inventory(snapshot)
    if not isinstance(rows, list):
        raise ValueError("external archive inventory must be a list")
    return [
        row for row in rows
        if isinstance(row, ArchivedExternalSnapshot)
        and str(row.source_url).strip()
        and str(row.content_text).strip()
        and str(row.snapshot_key).strip()
    ]


def _reuse_read_focus(snapshot: ResearchAgentTaskSnapshot) -> str:
    """Derive a deterministic, non-empty read focus for a recovered window.

    Recovered provenance must be reproducible: it is re-derived from the frozen task snapshot
    (column targets only) instead of being invented per execution, so two workers recovering
    the same task reach byte-identical evidence keys.
    """
    columns = sorted({
        target.cell_id.partition(":")[2].strip()
        for target in snapshot.target_cells
        if target.cell_id.partition(":")[2].strip()
    }) or ["recovery"]
    return "archive-reuse:" + "/".join(columns)[:120]


def _recovered_window(
    snapshot: ResearchAgentTaskSnapshot, item: ArchivedExternalSnapshot, focus: str
) -> ResearchReadWindow:
    """Rebuild the read window from the server-owned archive instead of paying for it again."""
    content = str(item.content_text)
    query = str(snapshot.query_policy.get("query", "")).strip()
    return ResearchReadWindow(
        window_id=str(item.window_id),
        hit_id="archive-reuse:" + str(item.window_id),
        source_id=str(item.source_id),
        source_title=str(item.source_title),
        query=query or "archive reuse",
        read_focus=focus,
        window_text=content,
        retention_reason="recovered from this task's server-archived external snapshot",
        token_estimate=max(1, len(content) // 4),
        url=str(item.source_url),
        provider=str(item.provider),
        adapter=str(item.adapter),
        snapshot_status="EXTERNAL_ARCHIVED",
        snapshot_key=str(item.snapshot_key),
        fetch_status="ARCHIVE_REUSE",
        fetch_method="ARCHIVE_REUSE",
        content_origin="EXTERNAL_TEXT",
        content_type_label="EXTERNAL_TEXT",
        transport_chain=["archive-reuse"],
        transport_resolution=str(item.provider),
        snapshot_archive_ready=True,
    )


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
        and getattr(window, "snapshot_status", "") not in {"WORKSPACE", "EXTERNAL_ARCHIVED"}
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
        # A canonical candidate may only bind evidence whose claim_text is exactly
        # the selected candidate value. Other Evidence from the same Cell remains
        # available to conflict detection, but binding it here makes Java's
        # qualification gate correctly reject the candidate as a claim mismatch.
        candidate_evidence = sorted(
            {str(item) for item in group["evidence_keys"]}  # type: ignore[union-attr]
        )
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
