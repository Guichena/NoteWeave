from __future__ import annotations

from app.content_runtime import describe_input_adapter_routes
from app.capability_provider import (
    get_capability_provider_discovery_status,
    get_capability_provider_last_checked_at,
    get_capability_provider_last_discovered_at,
    get_capability_provider_approval_status,
    get_capability_provider_health_status,
    get_capability_provider_status,
    list_capability_provider_candidates,
)
from app.registry import resolve_capability_binding


def resolve_capability_bindings(
    capability_names: list[str],
    task_input: object | None = None,
    action_key: str | None = None,
    skill_graph_key: str | None = None,
) -> list[dict[str, object]]:
    resolved: list[dict[str, object]] = []
    for capability_name in capability_names:
        resolved.append(
            _select_capability_binding(
                capability_name,
                task_input,
                action_key=action_key,
                skill_graph_key=skill_graph_key,
            )
        )
    return resolved


def resolve_capability_providers(
    capability_names: list[str],
    task_input: object | None = None,
    action_key: str | None = None,
    skill_graph_key: str | None = None,
) -> tuple[list[dict[str, object]], list[str], list[str]]:
    providers: list[dict[str, object]] = []
    unavailable_capabilities: list[str] = []
    pending_approval_capabilities: list[str] = []
    for capability_name in capability_names:
        selected_binding = _select_capability_binding(
            capability_name,
            task_input,
            action_key=action_key,
            skill_graph_key=skill_graph_key,
        )
        provider_status = get_capability_provider_status(
            capability_name,
            provider_id=selected_binding["provider_id"],
            tool_name=selected_binding["tool_name"],
        )
        health_status = get_capability_provider_health_status(
            capability_name,
            provider_id=selected_binding["provider_id"],
            tool_name=selected_binding["tool_name"],
        )
        approval_status = get_capability_provider_approval_status(
            capability_name,
            provider_id=selected_binding["provider_id"],
            tool_name=selected_binding["tool_name"],
        )
        discovery_status = get_capability_provider_discovery_status(
            capability_name,
            provider_id=selected_binding["provider_id"],
            tool_name=selected_binding["tool_name"],
        )
        providers.append(
            {
                "capability_name": capability_name,
                "provider_id": selected_binding["provider_id"],
                "server_id": selected_binding["server_id"],
                "tool_name": selected_binding["tool_name"],
                "discovery_status": discovery_status,
                "provider_status": provider_status,
                "health_status": health_status,
                "approval_status": approval_status,
                "last_checked_at": get_capability_provider_last_checked_at(
                    capability_name,
                    provider_id=selected_binding["provider_id"],
                    tool_name=selected_binding["tool_name"],
                ),
                "last_discovered_at": get_capability_provider_last_discovered_at(
                    capability_name,
                    provider_id=selected_binding["provider_id"],
                    tool_name=selected_binding["tool_name"],
                ),
                "selection_reason": selected_binding["selection_reason"],
                "route_basis": selected_binding["route_basis"],
                "action_basis": selected_binding["action_basis"],
                "skill_graph_basis": selected_binding["skill_graph_basis"],
            }
        )
        if not _is_discovery_ready(discovery_status) or provider_status != "AVAILABLE" or health_status in {"DOWN", "UNAVAILABLE"}:
            unavailable_capabilities.append(capability_name)
        elif selected_binding["approval_mode"] == "REQUIRED" and approval_status != "APPROVED":
            pending_approval_capabilities.append(capability_name)
    return providers, unavailable_capabilities, pending_approval_capabilities


def _select_capability_binding(
    capability_name: str,
    task_input: object | None = None,
    *,
    action_key: str | None = None,
    skill_graph_key: str | None = None,
) -> dict[str, object]:
    binding = resolve_capability_binding(capability_name)
    routes = (
        set(describe_input_adapter_routes(task_input))
        if task_input is not None
        else set()
    )
    route_basis = _derive_route_basis(routes)
    normalized_action_key = (action_key or "").strip().upper() or "*"
    normalized_skill_graph_key = skill_graph_key or "*"
    candidate_bindings = _matching_candidates(
        capability_name,
        routes,
        normalized_action_key,
        normalized_skill_graph_key,
    )
    preferred_candidate = candidate_bindings[0] if candidate_bindings else None
    selected_candidate = preferred_candidate
    selection_reason = "default_capability_binding"
    selected_server_id = binding.server_id
    selected_tool_name = binding.tool_name
    selected_provider_id = binding.server_id
    route_basis = ",".join(sorted(routes)) if routes else "UNSPECIFIED"

    if selected_candidate is not None:
        ready_candidates = [
            candidate
            for candidate in candidate_bindings
            if _is_candidate_runtime_ready(capability_name, candidate, binding.approval_mode)
        ]
        if ready_candidates:
            selected_candidate = ready_candidates[0]
        else:
            available_candidates = [
                candidate
                for candidate in candidate_bindings
                if _is_candidate_available_for_approval(capability_name, candidate)
            ]
            if available_candidates:
                selected_candidate = available_candidates[0]
            else:
                discovered_candidates = [
                    candidate
                    for candidate in candidate_bindings
                    if _is_discovery_ready(
                        get_capability_provider_discovery_status(
                            capability_name,
                            provider_id=candidate["provider_id"],
                            tool_name=candidate["tool_name"],
                        )
                    )
                ]
                if discovered_candidates:
                    selected_candidate = discovered_candidates[0]
        if selected_candidate is not None:
            selected_server_id = selected_candidate["server_id"]
            selected_tool_name = selected_candidate["tool_name"]
            selected_provider_id = selected_candidate["provider_id"]
            route_basis = _derive_route_basis(routes)
            selection_reason = _derive_selection_reason(
                capability_name,
                routes,
                preferred_candidate,
                selected_candidate,
            )

    return {
        "capability_name": binding.capability_name,
        "provider_id": selected_provider_id,
        "server_id": selected_server_id,
        "tool_name": selected_tool_name,
        "scope_type": binding.scope_type,
        "approval_mode": binding.approval_mode,
        "risk_level": binding.risk_level,
        "selection_reason": selection_reason,
        "route_basis": route_basis,
        "action_basis": normalized_action_key,
        "skill_graph_basis": normalized_skill_graph_key,
    }


def _matching_candidates(
    capability_name: str,
    routes: set[str],
    action_key: str,
    skill_graph_key: str,
) -> list[dict[str, object]]:
    candidates = list_capability_provider_candidates(capability_name)
    matching_candidates = [
        candidate
        for candidate in candidates
        if _candidate_matches_context(candidate, routes, action_key, skill_graph_key)
    ]
    if not matching_candidates:
        matching_candidates = candidates
    return sorted(matching_candidates, key=lambda item: item["preference_rank"], reverse=True)


def _is_candidate_runtime_ready(
    capability_name: str,
    candidate: dict[str, object],
    approval_mode: str,
) -> bool:
    if not _is_candidate_available_for_approval(capability_name, candidate):
        return False
    approval_status = get_capability_provider_approval_status(
        capability_name,
        provider_id=candidate["provider_id"],
        tool_name=candidate["tool_name"],
    )
    if approval_mode == "REQUIRED" and approval_status != "APPROVED":
        return False
    return True


def _is_candidate_available_for_approval(capability_name: str, candidate: dict[str, object]) -> bool:
    discovery_status = get_capability_provider_discovery_status(
        capability_name,
        provider_id=candidate["provider_id"],
        tool_name=candidate["tool_name"],
    )
    if not _is_discovery_ready(discovery_status):
        return False
    provider_status = get_capability_provider_status(
        capability_name,
        provider_id=candidate["provider_id"],
        tool_name=candidate["tool_name"],
    )
    health_status = get_capability_provider_health_status(
        capability_name,
        provider_id=candidate["provider_id"],
        tool_name=candidate["tool_name"],
    )
    return provider_status == "AVAILABLE" and health_status not in {"DOWN", "UNAVAILABLE"}


def _derive_selection_reason(
    capability_name: str,
    routes: set[str],
    preferred_candidate: dict[str, object] | None,
    selected_candidate: dict[str, object],
) -> str:
    if preferred_candidate is not None and selected_candidate != preferred_candidate:
        return "fallback_provider_selected"
    custom_hint = str(selected_candidate.get("selection_reason_hint", "")).strip()
    if custom_hint:
        return custom_hint
    if capability_name == "READ_WEB_PAGE" and selected_candidate["provider_id"] == "builtin-browser":
        return "action_skill_graph_preferred_provider"
    if capability_name == "EXTRACT_TRANSCRIPT" and "VIDEO_URL" in routes:
        return "preferred_bilibili_subtitle_provider"
    if capability_name == "TRANSCRIBE_AUDIO" and "VIDEO_FILE" in routes:
        return "video_file_audio_track_transcription"
    if capability_name == "TRANSCRIBE_AUDIO" and "AUDIO_FILE" in routes:
        return "audio_file_transcription"
    if capability_name == "READ_WEB_PAGE" and routes.intersection({"WEB_URL", "VIDEO_URL"}):
        return "external_web_reader"
    return "default_capability_binding"


def _derive_route_basis(routes: set[str]) -> str:
    if "VIDEO_URL" in routes:
        return "VIDEO_URL"
    if "VIDEO_FILE" in routes:
        return "VIDEO_FILE"
    if "AUDIO_FILE" in routes:
        return "AUDIO_FILE"
    if "WEB_URL" in routes:
        return "WEB_URL"
    return ",".join(sorted(routes)) if routes else "UNSPECIFIED"


def _is_discovery_ready(discovery_status: str) -> bool:
    return discovery_status in {"REGISTERED", "DISCOVERED"}


def _candidate_matches_context(
    candidate: dict[str, object],
    routes: set[str],
    action_key: str,
    skill_graph_key: str,
) -> bool:
    route_match = (
        not routes
        or "*" in candidate["supported_routes"]
        or routes.intersection(set(candidate["supported_routes"]))
    )
    action_match = (
        "*" in candidate["supported_actions"]
        or action_key in candidate["supported_actions"]
    )
    skill_graph_match = (
        "*" in candidate["supported_skill_graphs"]
        or skill_graph_key in candidate["supported_skill_graphs"]
    )
    return route_match and action_match and skill_graph_match
