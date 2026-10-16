"""证据资格审计与当前候选簇的语义冲突判定。"""

from __future__ import annotations

from app.agent_command_contract import ResearchAgentCommand
from app.agent_task_client import AgentTaskClaim
from app.deep_cell_executor import canonical_json_digest, require_worker_instance_id
from app.research_agent_completion_contract import build_completion_envelope
from app.task_snapshot_contract import require_trusted_claim_snapshot
from app.claim_clustering import equivalent
from app.counter_checks import model_json
from app.deep_cell_executor import _build_execution_llm, _llm_provider_call_count
from app.role_executor import RoleProfileCatalog
from app.execution_control import require_execution_active
from dataclasses import replace
from itertools import combinations


class EvidenceAuditExecutor:
    def __init__(self, worker_instance_id: str, *, llm_client=None, enable_llm=True) -> None:
        self.worker_instance_id = require_worker_instance_id(worker_instance_id)
        self.llm_client = llm_client
        self.enable_llm = enable_llm

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
        has_semantics = audit_input.get("schema_version") == "research-evidence-audit-input.v2"
        pairs = sum(len(cell.get("clusters", [])) * (len(cell.get("clusters", [])) - 1) // 2 for cell in cells)
        llm = self.llm_client
        if pairs and llm is None and self.enable_llm:
            profile = RoleProfileCatalog().resolve("EVIDENCE_AUDIT")
            limit = min(profile.max_llm_calls, int(snapshot.budget.get("llm_calls", 0)))
            llm = _build_execution_llm(require_execution_active, replace(profile, max_llm_calls=limit)) if limit else None
        targets: list[dict[str, object]] = []
        checks = []
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
            for left, right in combinations(cell.get("clusters", []), 2):
                require_execution_active()
                equality = equivalent(left["claim_text"], right["claim_text"], llm, force_model=True)
                def validate(raw):
                    if not isinstance(raw, dict) or set(raw) != {"verdict"} or raw["verdict"] not in {"CONTRADICTORY", "NOT_CONTRADICTORY"}:
                        raise ValueError("语义判定只能包含合法的 verdict")
                result = model_json(llm, "research.audit.contradiction", {
                    "policy": "Decide whether these claims about one cell are mutually exclusive under the same "
                              "subject, time, conditions and scope. Differences in wording or compatible details alone "
                              "do not constitute contradiction. Do not infer facts absent from the claims.",
                    "cell_key": cell["cell_key"], "column_description": cell.get("column_description", ""),
                    "left_claim": left["claim_text"], "right_claim": right["claim_text"],
                    "output_schema": {"verdict": "CONTRADICTORY|NOT_CONTRADICTORY"}}, validate)
                verdict = result["verdict"] if result and equality is not None else "NOT_RUN"
                if verdict == "CONTRADICTORY" and equality is True:
                    verdict = "NOT_RUN"
                if verdict == "NOT_RUN": reasons.append("SEMANTIC_CHECK_NOT_RUN")
                if verdict == "CONTRADICTORY": reasons.append("SEMANTIC_CONTRADICTION")
                checks.append({"cell_key": cell["cell_key"], "left_cluster_key": left["cluster_key"],
                    "right_cluster_key": right["cluster_key"], "verdict": verdict,
                    "equivalence_verdict": "EQUIVALENT" if equality is True else "NOT_EQUIVALENT" if equality is False else "NOT_RUN"})
            if reasons:
                targets.append({
                    "cell_key": str(cell.get("cell_key") or ""),
                    "evidence_keys": sorted(str(item.get("evidence_key") or "") for item in evidence),
                    "reason_codes": sorted(set(reasons)),
                })
        status = "BLOCKED" if any(c["verdict"] == "NOT_RUN" for c in checks) else "PASS" if not targets else "REPAIR_REQUIRED"
        role_result = {
            "result_schema_version": "research-evidence-audit-result.v2" if has_semantics else "research-evidence-audit-result.v1",
            "role": "EVIDENCE_AUDIT",
            "status": status,
            "input_digest": input_digest,
            "targets": targets,
            "reason_codes": sorted({reason for target in targets for reason in target["reason_codes"]}),
        }
        if has_semantics:
            role_result["semantic_checks"] = checks
        require_execution_active()
        return _role_completion(claim, snapshot.snapshot_digest, self.worker_instance_id, role_result, llm_calls=_llm_provider_call_count(llm))


def _role_completion(
    claim,
    snapshot_digest: str,
    worker_instance_id: str,
    role_result: dict[str, object],
    *,
    llm_calls: int = 0,
    search_calls: int = 0,
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
        "budget_usage": {"llm_calls": llm_calls, "search_calls": search_calls, "fetch_calls": 0, "read_calls": 0,
                         "extract_calls": 0, "evidence_cards": 0, "candidates_submitted": 0},
        "telemetry": {"search_hits": 0, "documents": 0, "windows": 0},
        "trace_digest": canonical_json_digest(trace),
        "evidence": [],
        "candidates": [],
        "role_result": role_result,
    })
