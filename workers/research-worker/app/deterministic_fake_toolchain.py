"""Deterministic MA4G canary provider; it never performs network or LLM calls."""

from __future__ import annotations

from types import SimpleNamespace

from app.extraction_result import ExtractionResult, ExtractionTerminationReason
from app.fetch_adapters import CompositeFetchAdapter, WorkspaceFetchAdapter, run_research_fetch
from app.models import ResearchEvidenceCard
from app.read_adapters import CompositeReadAdapter, WorkspaceReadAdapter, run_research_read


class DeterministicFakeToolchain:
    """A reproducible workspace-only Search/Fetch/Read/Extract atomic fixture."""

    def search(self, _task_input, _plan, *, allow_external: bool):
        if allow_external:
            raise RuntimeError("fake canary never permits external search")
        return [SimpleNamespace(hit_id="fake-hit-1")]

    def fetch(self, task_input, plan, hits, *, allow_external: bool):
        if allow_external:
            raise RuntimeError("fake canary never permits external fetch")
        workspace_hits = [hit for hit in hits if getattr(hit, "adapter", "") == "workspace"]
        if workspace_hits:
            return list(run_research_fetch(
                task_input,
                plan,
                workspace_hits,
                adapter=CompositeFetchAdapter([WorkspaceFetchAdapter()], max_concurrency=1),
            ))
        return [SimpleNamespace(fetch_id="fake-fetch-1")]

    def read(self, task_input, plan, documents, *, allow_external: bool):
        if allow_external:
            raise RuntimeError("fake canary never permits external read")
        workspace_documents = [
            document for document in documents
            if getattr(document, "adapter", "") == "workspace"
        ]
        if workspace_documents:
            return list(run_research_read(
                task_input,
                plan,
                fetched_documents=workspace_documents,
                adapter=CompositeReadAdapter([WorkspaceReadAdapter()]),
            ))
        if not task_input.source_scope:
            return []
        source = task_input.source_scope[0]
        return [SimpleNamespace(
            window_id="fake-window-1", snapshot_status="WORKSPACE", query=task_input.input_payload.question,
            read_focus="deterministic fake canary", source_id=source.source_id, source_title=source.title,
            window_text=source.sample_text,
        )]

    def extract(self, task_input, plan, windows, *, llm_client):
        if llm_client is not None:
            raise RuntimeError("fake canary must not construct an LLM client")
        if not windows or not task_input.source_scope:
            return ExtractionResult(termination_reason=ExtractionTerminationReason.NO_READ_WINDOWS)
        workspace_window = next(
            (
                window for window in windows
                if getattr(window, "snapshot_status", "") == "WORKSPACE"
                and getattr(window, "adapter", "") == "workspace"
            ),
            None,
        )
        source = next(
            (
                item for item in task_input.source_scope
                if workspace_window is not None
                and item.source_id == getattr(workspace_window, "source_id", "")
            ),
            task_input.source_scope[0],
        )
        sample = str(
            getattr(workspace_window, "window_text", "")
            if workspace_window is not None
            else source.sample_text
        ).strip()
        if not sample or not plan.state_columns:
            return ExtractionResult(termination_reason=ExtractionTerminationReason.MISSING_EVIDENCE_CARDS)
        entity_id = str(plan.stop_contract.get("deep_cell_entity_id") or "")
        if not entity_id:
            return ExtractionResult(termination_reason=ExtractionTerminationReason.MISSING_EVIDENCE_CARDS)
        if len(plan.state_columns) == 1:
            quotes = [sample]
        else:
            quotes = [line.strip() for line in sample.splitlines() if line.strip()]
            if len(quotes) < len(plan.state_columns) or len(set(quotes[:len(plan.state_columns)])) < len(plan.state_columns):
                raise ValueError("multi-target fake source requires one distinct trusted sample line per target")
        window_id = (
            str(getattr(workspace_window, "window_id", ""))
            if workspace_window is not None
            else "fake-window-1"
        )
        source_title = (
            str(getattr(workspace_window, "source_title", ""))
            if workspace_window is not None
            else source.title
        )
        cards = [
            ResearchEvidenceCard(
                evidence_id=f"fake-evidence-{index}",
                window_id=window_id,
                source_id=source.source_id,
                source_title=source_title,
                quote_text=quotes[index - 1],
                claim_text=quotes[index - 1],
                relation_type="SUPPORTS",
                support_score=0.8,
                conflict_score=0.0,
                entity_id=entity_id,
                column_key=column_key,
            )
            for index, column_key in enumerate(plan.state_columns, start=1)
        ]
        return ExtractionResult(
            accepted_cards=tuple(cards),
            termination_reason=ExtractionTerminationReason.ACCEPTED_CARDS,
        )
