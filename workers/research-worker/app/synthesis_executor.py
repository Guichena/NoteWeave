"""Tool-free structured artifact candidate synthesis from an immutable audited ledger."""

from __future__ import annotations

from app.agent_command_contract import ResearchAgentCommand
from app.agent_task_client import AgentTaskClaim
from app.evidence_audit_executor import _role_completion
from app.llm_client import LlmClient, OpenAICompatibleLlmClient, QuoteTamperFaultLlmClient, bind_llm_session, build_default_llm_client
from app.narrative_report import NarrativeReportPolisher
from app.task_snapshot_contract import require_trusted_claim_snapshot
from app.deep_cell_executor import require_worker_instance_id
from app.research_agent_completion_contract import canonical_json_bytes


class _MeteredSynthesisClient:
    def __init__(self, delegate, client, snapshot):
        self.delegate, self.client, self.snapshot, self.calls = delegate, client, snapshot, 0

    def complete_json(self, purpose, payload):
        self.client.require_synthesis_call(self.snapshot, self.calls, purpose)
        self.calls += 1
        return self.delegate.complete_json(purpose, payload)

    def usage_summary(self):
        summary = getattr(self.delegate, "usage_summary", None)
        return summary() if callable(summary) else {}


class SynthesisExecutor:
    def __init__(self, worker_instance_id: str, llm_client: LlmClient | None = None, *, upload_client=None) -> None:
        self.worker_instance_id = require_worker_instance_id(worker_instance_id)
        self.llm_client = llm_client if llm_client is not None else build_default_llm_client()
        self.upload_client = upload_client

    def __call__(self, command: ResearchAgentCommand, claim: AgentTaskClaim):
        snapshot = require_trusted_claim_snapshot(claim)
        import time
        metric_started = time.monotonic()
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
        prior_calls = 0
        recoverable = synthesis_input.get("report_recovery_version") == 1
        if recoverable:
            if self.upload_client is None:
                raise ValueError("当前报告任务需要支持恢复的任务客户端")
            recovery = self.upload_client.recover_synthesis_result(snapshot)
            prior_calls = recovery["recorded_llm_calls"]
            if recovery["ready"]:
                return _role_completion(claim, snapshot.snapshot_digest, self.worker_instance_id,
                                        recovery["reference"], llm_calls=prior_calls)
            synthesis_input = {**synthesis_input, "narrative_llm_budget": max(0, int(synthesis_input.get("narrative_llm_budget", 0)) - prior_calls)}
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
        llm_client = bind_llm_session(
            self.llm_client, "research-agent", snapshot.workspace_id,
            f"{snapshot.research_run_id}:{snapshot.task_id}",
        )
        if synthesis_input.get("statement_support_version") == 1:
            limit = max(0, min(int(snapshot.budget.get("llm_calls", 0)), int(synthesis_input.get("narrative_llm_budget", 0))))
            if limit == 0:
                llm_client = None
            else:
                bounded = llm_client.delegate if isinstance(llm_client, QuoteTamperFaultLlmClient) else llm_client
                if isinstance(bounded, OpenAICompatibleLlmClient):
                    # 绑定后的客户端属于当前任务，重试由成文流程计数，避免内部重试另行消耗预算。
                    bounded.max_total_calls = min(bounded.max_total_calls or limit, limit)
                    bounded.max_attempts = 1
                    bounded.purpose_options = {**bounded.purpose_options,
                        **{purpose: {**bounded.purpose_options.get(purpose, {}), "max_attempts": 1}
                           for purpose in ("research.synthesis", "research.synthesis.answers", "research.synthesis.statement_support", "research.synthesis.requirement_coverage", "research.synthesis.repair_answers")}}
        metered = synthesis_input.get("report_call_meter_version") == 1 and llm_client is not None
        if metered:
            llm_client = _MeteredSynthesisClient(llm_client,self.upload_client,snapshot)
        narrative_result = NarrativeReportPolisher(llm_client).polish(synthesis_input)
        llm_calls = llm_client.calls if metered else narrative_result.llm_calls
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
            "limitations": list(dict.fromkeys([
                *synthesis_input.get("limitations", []), *narrative_result.narrative.get("limitations", []),
            ])),
        }
        if recoverable:
            from app.runtime_metrics import runtime_metrics
            metric = runtime_metrics(llm_client, [], metric_started)
            if prior_calls:
                metric.update(input_tokens=None, output_tokens=None, duration_ms=None, provider_call_count=None)
            role_result["runtime_metrics"] = metric
        if recoverable or len(canonical_json_bytes(role_result)) > 192 * 1024:
            if self.upload_client is None:
                raise ValueError("大报告需要支持分段持久化的任务客户端")
            role_result = (self.upload_client.upload_synthesis_result(snapshot, role_result, llm_calls=llm_calls)
                           if recoverable else self.upload_client.upload_synthesis_result(snapshot, role_result))
        return _role_completion(
            claim,
            snapshot.snapshot_digest,
            self.worker_instance_id,
            role_result,
            llm_calls=llm_calls + prior_calls,
        )
