import json

from app.agent_command_contract import ResearchAgentCommand
from app.agent_task_client import AgentTaskClaim
from app.evidence_audit_executor import EvidenceAuditExecutor
from app.discovery_executor import DiscoveryExecutor
from app.synthesis_executor import SynthesisExecutor
from app.llm_client import FakeLlmClient
from app.task_snapshot_contract import snapshot_digest


TASK_ID = "00000000-0000-0000-0000-000000000001"


def _command() -> ResearchAgentCommand:
    return ResearchAgentCommand(
        schema_version="research-agent-command.v1",
        command_id="command-1",
        research_run_id="run-1",
        agent_task_id=TASK_ID,
        idempotency_key="command-1",
    )


def _claim(role: str, query_policy: dict[str, object]) -> AgentTaskClaim:
    target_cells = [] if role == "WIDE_DISCOVERY" else [{"cell_id": "cell-a", "expected_version": 3}]
    payload: dict[str, object] = {
        "schema_version": "research-agent-task-snapshot.v3",
        "task_id": TASK_ID,
        "research_run_id": "run-1",
        "workspace_id": "workspace-1",
        "role": role,
        "entity_id": "canonical-ledger",
        "branch_id": "branch-main",
        "plan_revision": 1,
        "entity_set_version": 1,
        "lease_epoch": 2,
        "fencing_token": 7,
        "target_cells": target_cells,
        "budget": {"llm_calls": 0},
        "provider_key": "research-internal",
        "source_policy": {"allow_workspace_sources": True},
        "query_policy": query_policy,
        "logical_task_key": f"{role.lower()}:1",
        "candidate_quorum": 1,
        "candidate_slot": 1,
        "high_risk": False,
        "research_intent": {"depth": "STANDARD", "research_type": "AUTO"},
        "control_pack": {
            "pack_type": "RESEARCH_AGENT",
            "target_key": "DEFAULT",
            "task_neighborhood": "RESEARCH_DEFAULT",
        },
    }
    digest = snapshot_digest(payload)
    encoded = json.dumps({**payload, "snapshot_digest": digest}, ensure_ascii=False)
    target_json = "[]" if role == "WIDE_DISCOVERY" else '["cell-a"]'
    return AgentTaskClaim(TASK_ID, 2, 7, target_json, '{"llm_calls":0}', encoded, digest)


def test_audit_blocks_contradicted_typed_evidence_without_tools() -> None:
    claim = _claim("EVIDENCE_AUDIT", {
        "allowed_tools": [],
        "audit_input": {
            "input_digest": "sha256:audit",
            "cells": [{
                "cell_key": "cell-a",
                "evidence": [{
                    "evidence_key": "evidence-a",
                    "final_status": "QUALIFIED",
                    "source_domain": "example.com",
                    "lineage_digest": "sha256:lineage",
                    "typed_status": "CONTRADICTED",
                }],
            }],
        },
    })

    completion = EvidenceAuditExecutor("worker-a")(_command(), claim)

    assert completion.role_result["status"] == "REPAIR_REQUIRED"
    assert completion.role_result["targets"][0]["reason_codes"] == [
        "AUDIT_TYPED_CLAIM_CONTRADICTED"
    ]
    assert completion.budget_usage.llm_calls == 0
    assert completion.budget_usage.search_calls == 0


def test_synthesis_is_an_exact_tool_free_rendering_of_frozen_cells() -> None:
    claim = _claim("SYNTHESIS", {
        "allowed_tools": [],
        "synthesis_input": {
            "ledger_digest": "sha256:ledger",
            "audit_digest": "sha256:audit",
            "cells": [{
                "cell_key": "cell-a",
                "candidate_value": "Latency is 50 ms",
                "evidence_keys": ["evidence-a"],
                "guarded": False,
            }],
            "limitations": [],
        },
    })

    completion = SynthesisExecutor("worker-a")(_command(), claim)

    assert completion.role_result["claims"] == [{
        "claim_text": "Latency is 50 ms",
        "cell_key": "cell-a",
        "evidence_keys": ["evidence-a"],
        "guarded": False,
    }]
    assert "[evidence-a]" in completion.role_result["markdown"]
    assert completion.budget_usage.llm_calls == 0
    assert completion.budget_usage.search_calls == 0


def test_synthesis_keeps_canonical_claim_while_adding_narrative() -> None:
    claim = _claim("SYNTHESIS", {
        "allowed_tools": [],
        "synthesis_input": {
            "ledger_digest": "sha256:ledger",
            "audit_digest": "sha256:audit",
            "cells": [{
                "cell_key": "cell-a",
                "candidate_value": "Latency is 50 ms",
                "evidence_keys": ["evidence-a"],
                "guarded": False,
            }],
            "limitations": [],
        },
    })
    llm = FakeLlmClient({"research.synthesis": json.dumps({
        "title": "Performance report",
        "executive_summary": "Verified performance findings.",
        "sections": [{
            "heading": "Latency",
            "paragraphs": [{
                "text": "The measured latency is 50 ms.",
                "cell_keys": ["cell-a"],
                "evidence_keys": ["evidence-a"],
            }],
        }],
        "limitations": [],
    })})

    completion = SynthesisExecutor("worker-a", llm)(_command(), claim)

    assert completion.role_result["claims"][0]["claim_text"] == "Latency is 50 ms"
    assert completion.role_result["result_schema_version"] == "research-synthesis-candidate.v2"
    assert completion.role_result["narrative_mode"] == "LLM"
    assert completion.role_result["narrative_fallback_reason"] == ""
    assert "The measured latency is 50 ms." in completion.role_result["markdown"]
    assert completion.budget_usage.llm_calls == 1


def test_wide_discovery_emits_proposals_without_cell_mutation_payload() -> None:
    claim = _claim("WIDE_DISCOVERY", {
        "allowed_tools": [],
        "discovery_input": {
            "input_digest": "sha256:discovery",
            "question": "Compare systems",
            "required_findings": ["licensing"],
            "plan_revision": 3,
            "entity_set_version": 4,
        },
    })

    completion = DiscoveryExecutor("worker-a")(_command(), claim)

    assert completion.candidates == ()
    assert completion.evidence == ()
    assert completion.role_result["status"] == "PROPOSED"
    assert completion.role_result["plan_revision"] == 3
    assert completion.role_result["proposals"][0]["proposal_type"] == "DIMENSION"
