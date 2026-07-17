"""Deterministic MA4G canary provider; it never performs network or LLM calls."""

from __future__ import annotations

from types import SimpleNamespace


class DeterministicFakeToolchain:
    """A reproducible workspace-only Search/Fetch/Read/Extract atomic fixture."""

    def search(self, _task_input, _plan, *, allow_external: bool):
        if allow_external:
            raise RuntimeError("fake canary never permits external search")
        return [SimpleNamespace(hit_id="fake-hit-1")]

    def fetch(self, _task_input, _plan, _hits, *, allow_external: bool):
        if allow_external:
            raise RuntimeError("fake canary never permits external fetch")
        return [SimpleNamespace(fetch_id="fake-fetch-1")]

    def read(self, task_input, _plan, _documents, *, allow_external: bool):
        if allow_external:
            raise RuntimeError("fake canary never permits external read")
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
            return []
        source = task_input.source_scope[0]
        sample = source.sample_text.strip()
        if not sample or not plan.state_columns:
            return []
        entity_id = str(plan.stop_contract.get("deep_cell_entity_id") or "")
        if not entity_id:
            return []
        if len(plan.state_columns) == 1:
            quotes = [sample]
        else:
            quotes = [line.strip() for line in sample.splitlines() if line.strip()]
            if len(quotes) < len(plan.state_columns) or len(set(quotes[:len(plan.state_columns)])) < len(plan.state_columns):
                raise ValueError("multi-target fake source requires one distinct trusted sample line per target")
        return [
            SimpleNamespace(
                evidence_id=f"fake-evidence-{index}", window_id="fake-window-1", source_id=source.source_id,
                source_title=source.title, quote_text=quotes[index - 1], claim_text=quotes[index - 1], relation_type="SUPPORTS",
                support_score=0.8, conflict_score=0.0, entity_id=entity_id, column_key=column_key,
            )
            for index, column_key in enumerate(plan.state_columns, start=1)
        ]
