"""Read-only deterministic evidence audit executor."""

from __future__ import annotations

from app.agent_command_contract import ResearchAgentCommand
from app.agent_task_client import AgentTaskClaim
from app.deep_cell_executor import canonical_json_digest, require_worker_instance_id
from app.research_agent_completion_contract import build_completion_envelope
from app.task_snapshot_contract import require_trusted_claim_snapshot


class EvidenceAuditExecutor:
    def __init__(self, worker_instance_id: str) -> None:
        self.worker_instance_id = require_worker_instance_id(worker_instance_id)

    def __call__(self, command: ResearchAgentCommand, claim: AgentTaskClaim):
        snapshot = require_trusted_claim_snapshot(claim)
        if snapshot.role != "EVIDENCE_AUDIT" or command.research_run_id != snapshot.research_run_id:
            raise ValueError("EvidenceAuditExecutor requires an authoritative EVIDENCE_AUDIT snapshot")
        audit_input = snapshot.query_policy.get("audit_input")
        if not isinstance(audit_input, dict):
            raise ValueError("EVIDENCE_AUDIT snapshot requires immutable audit_input")
        input_digest = str(audit_input.get("input_digest") or "").strip()
        cells = [item for item in audit_input.get("cells", []) if isinstance(item, dict)]
        if not input_digest or not cells:
            raise ValueError("EVIDENCE_AUDIT input is incomplete")
        targets: list[dict[str, object]] = []
        for cell in cells:
            reasons: list[str] = []
            evidence = [item for item in cell.get("evidence", []) if isinstance(item, dict)]
            if not evidence:
                reasons.append("AUDIT_EVIDENCE_MISSING")
            if any(str(item.get("final_status") or "") not in {"QUALIFIED", "QUALIFIED_UNBOUND"} for item in evidence):
                reasons.append("AUDIT_EVIDENCE_NOT_QUALIFIED")
            if any(not str(item.get("source_domain") or "").strip() or not str(item.get("lineage_digest") or "").strip()
                   for item in evidence):
                reasons.append("AUDIT_PROVENANCE_INCOMPLETE")
            if any(str(item.get("typed_status") or "") == "CONTRADICTED" for item in evidence):
                reasons.append("AUDIT_TYPED_CLAIM_CONTRADICTED")
            if reasons:
                targets.append({
                    "cell_key": str(cell.get("cell_key") or ""),
                    "evidence_keys": sorted(str(item.get("evidence_key") or "") for item in evidence),
                    "reason_codes": sorted(set(reasons)),
                })
        status = "PASS" if not targets else "REPAIR_REQUIRED"
        role_result = {
            "result_schema_version": "research-evidence-audit-result.v1",
            "role": "EVIDENCE_AUDIT",
            "status": status,
            "input_digest": input_digest,
            "targets": targets,
            "reason_codes": sorted({reason for target in targets for reason in target["reason_codes"]}),
        }
        return _role_completion(claim, snapshot.snapshot_digest, self.worker_instance_id, role_result)


def _role_completion(
    claim,
    snapshot_digest: str,
    worker_instance_id: str,
    role_result: dict[str, object],
    *,
    llm_calls: int = 0,
):
    trace = {"schema_version": "research-agent-role-trace.v1", "role_result": role_result}
    return build_completion_envelope({
        "schema_version": "research-agent-completion.v2",
        "task_id": claim.agent_task_id,
        "worker_instance_id": worker_instance_id,
        "lease_epoch": claim.lease_epoch,
        "fencing_token": claim.fencing_token,
        "execution_key": f"role-result:{claim.agent_task_id}:{claim.lease_epoch}:{claim.fencing_token}",
        "task_snapshot_digest": snapshot_digest,
        "termination_reason": "ROLE_RESULT",
        "budget_usage": {"llm_calls": llm_calls, "search_calls": 0, "fetch_calls": 0, "read_calls": 0,
                         "extract_calls": 0, "evidence_cards": 0, "candidates_submitted": 0},
        "telemetry": {"search_hits": 0, "documents": 0, "windows": 0},
        "trace_digest": canonical_json_digest(trace),
        "evidence": [],
        "candidates": [],
        "role_result": role_result,
    })
