"""Executor routing based only on the claimed, digest-verified task snapshot."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Callable

from app.agent_command_contract import ResearchAgentCommand
from app.agent_task_client import AgentTaskClaim
from app.research_agent_completion_contract import ResearchAgentCompletionEnvelope
from app.task_snapshot_contract import require_trusted_claim_snapshot


RoleExecutor = Callable[[ResearchAgentCommand, AgentTaskClaim], ResearchAgentCompletionEnvelope]


@dataclass(frozen=True)
class RoleCapability:
    role: str
    schedulable: bool
    executor: str
    snapshot_schemas: frozenset[str]
    completion_handler: str
    mutation_authority: str
    allowed_tools: frozenset[str]


ROLE_CAPABILITIES = {
    "DEEP_CELL": RoleCapability("DEEP_CELL", True, "DeepCellExecutor", frozenset({"v1", "v2", "v3"}),
                                "CANDIDATE", "CELL_CANDIDATE", frozenset({"search", "fetch", "read", "extract", "archive"})),
    "COUNTERFACTUAL": RoleCapability("COUNTERFACTUAL", True, "DeepCellExecutor", frozenset({"v2", "v3"}),
                                     "CANDIDATE_OR_QUORUM", "REPAIR_CANDIDATE", frozenset({"search", "fetch", "read", "extract", "archive"})),
    "EVIDENCE_AUDIT": RoleCapability("EVIDENCE_AUDIT", True, "EvidenceAuditExecutor", frozenset({"v3"}),
                                     "AUDIT_RESULT", "AUDIT_DECISION", frozenset()),
    "SYNTHESIS": RoleCapability("SYNTHESIS", True, "SynthesisExecutor", frozenset({"v3"}),
                                "ARTIFACT_CANDIDATE", "ARTIFACT_PROMOTION_ONLY", frozenset()),
    "WIDE_DISCOVERY": RoleCapability("WIDE_DISCOVERY", True, "DiscoveryExecutor", frozenset({"v3"}),
                                     "SCOPE_PROPOSAL", "PROPOSAL_ONLY", frozenset({"search", "read"})),
    "CELL_VERIFIER": RoleCapability("CELL_VERIFIER", False, "Internal", frozenset(), "DECISION", "NONE", frozenset()),
    "GLOBAL_VERIFIER": RoleCapability("GLOBAL_VERIFIER", False, "Internal", frozenset(), "DECISION", "NONE", frozenset()),
}


class AuthoritativeRoleRouter:
    def __init__(self, executors: dict[str, RoleExecutor], *, enabled_roles: set[str]) -> None:
        self.executors = {key.strip().upper(): value for key, value in executors.items()}
        self.enabled_roles = {role.strip().upper() for role in enabled_roles}

    def __call__(self, command: ResearchAgentCommand, claim: AgentTaskClaim) -> ResearchAgentCompletionEnvelope:
        snapshot = require_trusted_claim_snapshot(claim)
        role = snapshot.role.strip().upper()
        capability = ROLE_CAPABILITIES.get(role)
        if capability is None:
            raise ValueError(f"unregistered authoritative research role: {role}")
        schema = snapshot.schema_version.rsplit(".", maxsplit=1)[-1]
        if not capability.schedulable or schema not in capability.snapshot_schemas:
            raise ValueError(f"authoritative role capability does not support snapshot: {role}/{schema}")
        if role not in self.enabled_roles:
            raise ValueError(f"authoritative research role is feature-gated: {role}")
        executor = self.executors.get(role)
        if executor is None:
            raise ValueError(f"authoritative research role has no registered executor: {role}")
        requested_tools = snapshot.query_policy.get("allowed_tools", list(capability.allowed_tools))
        if not isinstance(requested_tools, list) or any(not isinstance(item, str) for item in requested_tools):
            raise ValueError("snapshot allowed_tools policy is invalid")
        effective_tools = capability.allowed_tools.intersection(item.strip().lower() for item in requested_tools)
        if role in {"EVIDENCE_AUDIT", "SYNTHESIS"} and effective_tools:
            raise ValueError(f"{role} snapshot must not grant tools")
        return executor(command, claim)
