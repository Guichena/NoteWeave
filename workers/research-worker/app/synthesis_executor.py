"""Tool-free structured artifact candidate synthesis from an immutable audited ledger."""

from __future__ import annotations

from app.agent_command_contract import ResearchAgentCommand
from app.agent_task_client import AgentTaskClaim
from app.evidence_audit_executor import _role_completion
from app.llm_client import LlmClient, build_default_llm_client
from app.narrative_report import NarrativeReportPolisher
from app.task_snapshot_contract import require_trusted_claim_snapshot
from app.deep_cell_executor import require_worker_instance_id


class SynthesisExecutor:
    def __init__(self, worker_instance_id: str, llm_client: LlmClient | None = None) -> None:
        self.worker_instance_id = require_worker_instance_id(worker_instance_id)
        self.llm_client = llm_client if llm_client is not None else build_default_llm_client()

    def __call__(self, command: ResearchAgentCommand, claim: AgentTaskClaim):
        snapshot = require_trusted_claim_snapshot(claim)
        if snapshot.role != "SYNTHESIS" or command.research_run_id != snapshot.research_run_id:
            raise ValueError("SynthesisExecutor requires an authoritative SYNTHESIS snapshot")
        synthesis_input = snapshot.query_policy.get("synthesis_input")
        if not isinstance(synthesis_input, dict):
            raise ValueError("SYNTHESIS snapshot requires immutable synthesis_input")
        ledger_digest = str(synthesis_input.get("ledger_digest") or "").strip()
        audit_digest = str(synthesis_input.get("audit_digest") or "").strip()
        cells = [item for item in synthesis_input.get("cells", []) if isinstance(item, dict)]
        if not ledger_digest or not audit_digest or not cells:
            raise ValueError("SYNTHESIS input barrier is incomplete")
        claims: list[dict[str, object]] = []
        for cell in sorted(cells, key=lambda item: str(item.get("cell_key") or "")):
            cell_key = str(cell.get("cell_key") or "").strip()
            value = str(cell.get("candidate_value") or "").strip()
            evidence_keys = sorted({str(item).strip() for item in cell.get("evidence_keys", []) if str(item).strip()})
            if not cell_key or not value or not evidence_keys:
                raise ValueError("SYNTHESIS cell lacks verified value or accepted evidence")
            guarded = bool(cell.get("guarded", False))
            rendered = f"{value} (limited by unresolved evidence)" if guarded else value
            claims.append({"claim_text": rendered, "cell_key": cell_key, "evidence_keys": evidence_keys, "guarded": guarded})
        narrative_result = NarrativeReportPolisher(self.llm_client).polish(synthesis_input)
        llm_calls = 1 if self.llm_client is not None else 0
        role_result = {
            "result_schema_version": "research-synthesis-candidate.v2",
            "role": "SYNTHESIS",
            "status": "CANDIDATE",
            "ledger_digest": ledger_digest,
            "audit_digest": audit_digest,
            "claims": claims,
            "narrative": narrative_result.narrative,
            "narrative_mode": narrative_result.mode,
            # Java freezes role_result with Map.copyOf, which intentionally
            # rejects null values. Keep the wire contract total for LLM mode.
            "narrative_fallback_reason": narrative_result.reason or "",
            "markdown": narrative_result.markdown,
            "limitations": list(synthesis_input.get("limitations", [])),
        }
        return _role_completion(
            claim,
            snapshot.snapshot_digest,
            self.worker_instance_id,
            role_result,
            llm_calls=llm_calls,
        )
