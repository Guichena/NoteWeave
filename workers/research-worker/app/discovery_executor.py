"""Scope-proposal-only wide discovery executor."""

from __future__ import annotations

import hashlib
import re

from app.agent_command_contract import ResearchAgentCommand
from app.agent_task_client import AgentTaskClaim
from app.deep_cell_executor import require_worker_instance_id
from app.evidence_audit_executor import _role_completion
from app.task_snapshot_contract import require_trusted_claim_snapshot


class DiscoveryExecutor:
    def __init__(self, worker_instance_id: str) -> None:
        self.worker_instance_id = require_worker_instance_id(worker_instance_id)

    def __call__(self, command: ResearchAgentCommand, claim: AgentTaskClaim):
        snapshot = require_trusted_claim_snapshot(claim)
        if snapshot.role != "WIDE_DISCOVERY" or command.research_run_id != snapshot.research_run_id:
            raise ValueError("DiscoveryExecutor requires an authoritative WIDE_DISCOVERY snapshot")
        discovery_input = snapshot.query_policy.get("discovery_input")
        if not isinstance(discovery_input, dict):
            raise ValueError("WIDE_DISCOVERY snapshot requires immutable discovery_input")
        input_digest = str(discovery_input.get("input_digest") or "").strip()
        question = str(discovery_input.get("question") or "").strip()
        if not input_digest or not question:
            raise ValueError("WIDE_DISCOVERY input is incomplete")
        proposals: list[dict[str, object]] = []
        for finding in discovery_input.get("required_findings", []):
            label = str(finding).strip()
            if not label:
                continue
            proposals.append({
                "proposal_key": _stable_key("dimension", label),
                "proposal_type": "DIMENSION",
                "candidate_key": _stable_key("finding", label),
                "label": label,
                "search_query": f"{question} {label}"[:4096],
                "source_lead": "",
                "source_domain": "",
                "lineage_digest": "",
                "rationale": "required finding is not represented by the current matrix",
                "confidence_ppm": 850_000,
            })
        for source in snapshot.source_policy.get("source_scope", []):
            if not isinstance(source, dict):
                continue
            title = str(source.get("title") or source.get("source_title") or source.get("source_id") or "").strip()
            source_id = str(source.get("source_id") or "").strip()
            if title and source_id:
                proposals.append({
                    "proposal_key": _stable_key("source", source_id),
                    "proposal_type": "SOURCE_LEAD",
                    "candidate_key": _stable_key("source", source_id),
                    "label": title,
                    "search_query": question[:4096],
                    "source_lead": source_id,
                    "source_domain": str(source.get("source_domain") or ""),
                    "lineage_digest": str(source.get("lineage_digest") or ""),
                    "rationale": "trusted scope source may reveal an omitted entity or dimension",
                    "confidence_ppm": 600_000,
                })
        role_result = {
            "result_schema_version": "research-discovery-proposal.v1",
            "role": "WIDE_DISCOVERY",
            "status": "PROPOSED",
            "input_digest": input_digest,
            "plan_revision": int(discovery_input.get("plan_revision") or 0),
            "entity_set_version": int(discovery_input.get("entity_set_version") or 0),
            "proposals": proposals[:20],
        }
        return _role_completion(claim, snapshot.snapshot_digest, self.worker_instance_id, role_result)


def _stable_key(prefix: str, value: str) -> str:
    normalized = re.sub(r"\s+", " ", value.strip().lower())
    return f"{prefix}-" + hashlib.sha256(normalized.encode("utf-8")).hexdigest()[:20]
