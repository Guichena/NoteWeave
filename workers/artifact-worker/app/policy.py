from __future__ import annotations

from app.models import (
    ApprovalGateDecision,
    CapabilityUnionPolicyDecision,
    EvidenceGateDecision,
    ProductionAction,
    WritebackGateDecision,
)
from app.registry import resolve_capability_binding


WRITEBACK_CAPABILITY_MAP = {
    "ARTIFACT_VERSION": ["COMMIT_ARTIFACT_VERSION"],
    "EXPORT_FILE": ["EXPORT_ARTIFACT_FILE"],
    "SAVE_AS_SOURCE": ["SAVE_WORKSPACE_SOURCE"],
    "WIKI_PAGE": ["WRITE_WIKI_PAGE"],
    "NOTE_PAGE": ["WRITE_NOTE_PAGE"],
}


def resolve_writeback_capabilities(requested_mode: str) -> list[str]:
    normalized_mode = requested_mode.strip().upper() or "NONE"
    if normalized_mode == "NONE":
        return []
    return list(WRITEBACK_CAPABILITY_MAP.get(normalized_mode, []))


def evaluate_capability_union_policy(
    action: ProductionAction,
    capability_names: list[str],
    workspace_id: str,
    requested_writeback_mode: str = "NONE",
    resolved_bindings: list[dict[str, object]] | None = None,
) -> CapabilityUnionPolicyDecision:
    normalized_capabilities = list(dict.fromkeys(capability_names))
    blocked_capabilities: list[str] = []
    notes: list[str] = []
    reason_code = "allowed"
    external_network_capabilities: list[str] = []
    high_risk_external_network_capabilities: list[str] = []
    normalized_writeback_mode = requested_writeback_mode.strip().upper() or "NONE"
    resolved_binding_map = {
        str(binding.get("capability_name", "")): binding
        for binding in (resolved_bindings or [])
    }

    for capability_name in normalized_capabilities:
        binding = resolve_capability_binding(capability_name)
        selected_binding = resolved_binding_map.get(capability_name, {})
        selected_server_id = str(selected_binding.get("server_id", binding.server_id))
        selected_tool_name = str(selected_binding.get("tool_name", binding.tool_name))
        selected_scope_type = str(selected_binding.get("scope_type", binding.scope_type))
        selected_risk_level = str(selected_binding.get("risk_level", binding.risk_level))
        if "*" not in binding.allowed_actions and action.action_key not in binding.allowed_actions:
            blocked_capabilities.append(capability_name)
            reason_code = "action_scope_denied"
            continue

        if selected_server_id.startswith("custom") and not action.allow_custom_mcp:
            blocked_capabilities.append(capability_name)
            reason_code = "action_disallows_custom_mcp"
            continue

        if (
            selected_scope_type in {"WRITE", "EXPORT"}
            and not action.allowed_writeback_modes
        ):
            blocked_capabilities.append(capability_name)
            reason_code = "writeback_not_allowed"
            continue

        if selected_scope_type == "EXTERNAL_NETWORK":
            external_network_capabilities.append(capability_name)
            if selected_server_id.startswith("custom") or selected_risk_level == "HIGH":
                high_risk_external_network_capabilities.append(capability_name)

        notes.append(
            f"{capability_name}: {selected_scope_type} via {selected_server_id}/{selected_tool_name}"
        )

    if "READ_WORKSPACE_DOC" in normalized_capabilities and high_risk_external_network_capabilities:
        blocked_capabilities.extend(
            capability_name
            for capability_name in high_risk_external_network_capabilities
            if capability_name not in blocked_capabilities
        )
        reason_code = "workspace_material_external_network_union"

    decision = "ALLOW" if not blocked_capabilities else "DENY"
    return CapabilityUnionPolicyDecision(
        policy_key="default-capability-union-policy",
        action_scope=action.action_key,
        skill_scope=action.default_skill_graph_key,
        capability_scope=normalized_capabilities,
        workspace_scope=workspace_id,
        decision=decision,
        reason_code=reason_code,
        notes=notes,
        blocked_capabilities=blocked_capabilities,
    )


def evaluate_approval_gate(capability_names: list[str]) -> ApprovalGateDecision:
    required_capabilities: list[str] = []
    notes: list[str] = []

    for capability_name in capability_names:
        binding = resolve_capability_binding(capability_name)
        notes.append(
            f"{capability_name}: approval mode {binding.approval_mode} for {binding.server_id}"
        )
        if binding.approval_mode == "REQUIRED":
            required_capabilities.append(capability_name)

    if required_capabilities:
        return ApprovalGateDecision(
            decision="APPROVAL_REQUIRED",
            reason_code="high_risk_capability_requires_approval",
            required_capabilities=required_capabilities,
            notes=notes,
        )

    return ApprovalGateDecision(
        decision="NOT_REQUIRED",
        reason_code="all_loaded_capabilities_are_low_risk",
        required_capabilities=[],
        notes=notes,
    )


def evaluate_evidence_gate(
    action: ProductionAction,
    source_count: int,
    citation_density: str,
) -> EvidenceGateDecision:
    minimum_source_count = 1 if action.required_evidence_level != "NONE" else 0
    notes = [f"source_count={source_count}", f"required_evidence_level={action.required_evidence_level}"]

    if source_count < minimum_source_count:
        return EvidenceGateDecision(
            decision="DENY",
            reason_code="insufficient_workspace_sources",
            minimum_source_count=minimum_source_count,
            required_citation_density=citation_density,
            notes=notes,
        )

    return EvidenceGateDecision(
        decision="ENFORCE",
        reason_code="workspace_sources_available_for_citation",
        minimum_source_count=minimum_source_count,
        required_citation_density=citation_density,
        notes=notes,
    )


def evaluate_writeback_gate(
    action: ProductionAction,
    requested_mode: str,
) -> WritebackGateDecision:
    normalized_mode = requested_mode.strip().upper() or "NONE"
    required_capabilities = resolve_writeback_capabilities(normalized_mode)
    if normalized_mode == "NONE":
        return WritebackGateDecision(
            decision="SKIP",
            reason_code="no_writeback_requested",
            requested_mode="NONE",
            allowed_target="",
            execution_mode="NONE",
            required_capabilities=[],
            notes=[],
        )

    if normalized_mode not in WRITEBACK_CAPABILITY_MAP:
        return WritebackGateDecision(
            decision="DENY",
            reason_code="unknown_writeback_mode",
            requested_mode=normalized_mode,
            allowed_target="",
            execution_mode="NONE",
            required_capabilities=[],
            notes=[f"unsupported writeback mode: {normalized_mode}"],
        )

    if normalized_mode not in action.allowed_writeback_modes:
        return WritebackGateDecision(
            decision="DENY",
            reason_code="writeback_target_not_allowed_for_action",
            requested_mode=normalized_mode,
            allowed_target="",
            execution_mode="NONE",
            required_capabilities=required_capabilities,
            notes=[
                (
                    f"action {action.action_key} allows: "
                    + ", ".join(action.allowed_writeback_modes or ["NONE"])
                )
            ],
        )

    return WritebackGateDecision(
        decision="ALLOW",
        reason_code="writeback_preview_enabled",
        requested_mode=normalized_mode,
        allowed_target=normalized_mode,
        execution_mode="HOST_MANAGED_PREVIEW",
        required_capabilities=required_capabilities,
        notes=[
            "independent worker only prepares writeback intent; host system executes final writeback",
        ],
    )
