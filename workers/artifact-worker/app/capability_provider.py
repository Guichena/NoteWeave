from __future__ import annotations

from datetime import datetime, timezone
from threading import Lock

from app.registry import list_custom_mcp_servers, resolve_capability_binding


_provider_status_lock = Lock()
_provider_discovery_overrides: dict[str, str] = {}
_provider_status_overrides: dict[str, str] = {}
_provider_health_overrides: dict[str, str] = {}
_provider_approval_overrides: dict[str, str] = {}
_provider_probe_results: dict[str, str] = {}
_provider_last_checked_at: dict[str, str] = {}
_provider_last_discovered_at: dict[str, str] = {}

_BUILTIN_PROVIDER_CANDIDATE_CATALOG = [
    {
        "capability_name": "READ_WORKSPACE_DOC",
        "provider_id": "builtin-workspace",
        "display_name": "Workspace Reader",
        "endpoint_kind": "builtin",
        "discovery_status": "REGISTERED",
        "server_id": "builtin-workspace",
        "tool_name": "read_workspace_doc",
        "mapping_source": "builtin_mapping",
        "supported_routes": ["*"],
        "supported_actions": ["*"],
        "supported_skill_graphs": ["*"],
        "preference_rank": 100,
    },
    {
        "capability_name": "GENERATE_STRUCTURED_TEXT",
        "provider_id": "builtin-llm",
        "display_name": "Structured Text Generator",
        "endpoint_kind": "builtin",
        "discovery_status": "REGISTERED",
        "server_id": "builtin-llm",
        "tool_name": "generate_structured_text",
        "mapping_source": "builtin_mapping",
        "supported_routes": ["*"],
        "supported_actions": ["*"],
        "supported_skill_graphs": ["*"],
        "preference_rank": 100,
    },
    {
        "capability_name": "VERIFY_OUTPUT",
        "provider_id": "builtin-verifier",
        "display_name": "Artifact Verifier",
        "endpoint_kind": "builtin",
        "discovery_status": "REGISTERED",
        "server_id": "builtin-verifier",
        "tool_name": "verify_output",
        "mapping_source": "builtin_mapping",
        "supported_routes": ["*"],
        "supported_actions": ["*"],
        "supported_skill_graphs": ["*"],
        "preference_rank": 100,
    },
    {
        "capability_name": "READ_WEB_PAGE",
        "provider_id": "builtin-browser",
        "display_name": "Article Reader",
        "endpoint_kind": "browser",
        "discovery_status": "REGISTERED",
        "server_id": "builtin-browser",
        "tool_name": "read_article_page",
        "mapping_source": "capability_mapping",
        "supported_routes": ["WEB_URL", "VIDEO_URL"],
        "supported_actions": ["REPORT", "QUIZ", "STUDY_GUIDE", "WIKI_PAGE"],
        "supported_skill_graphs": ["generic_artifact_v1"],
        "preference_rank": 200,
    },
    {
        "capability_name": "READ_WEB_PAGE",
        "provider_id": "builtin-network",
        "display_name": "Web Reader",
        "endpoint_kind": "network",
        "discovery_status": "REGISTERED",
        "server_id": "builtin-network",
        "tool_name": "read_web_page",
        "mapping_source": "capability_mapping",
        "supported_routes": ["WEB_URL", "VIDEO_URL"],
        "supported_actions": ["*"],
        "supported_skill_graphs": ["*"],
        "preference_rank": 100,
    },
    {
        "capability_name": "EXTRACT_TRANSCRIPT",
        "provider_id": "builtin-bilibili-mcp",
        "display_name": "Bilibili Subtitle Provider",
        "endpoint_kind": "mcp",
        "discovery_status": "REGISTERED",
        "server_id": "builtin-bilibili-mcp",
        "tool_name": "get_subtitle",
        "mapping_source": "mcp_mapping",
        "supported_routes": ["VIDEO_URL"],
        "supported_actions": ["*"],
        "supported_skill_graphs": ["*"],
        "preference_rank": 200,
    },
    {
        "capability_name": "EXTRACT_TRANSCRIPT",
        "provider_id": "builtin-media",
        "display_name": "Generic Transcript Extractor",
        "endpoint_kind": "media",
        "discovery_status": "REGISTERED",
        "server_id": "builtin-media",
        "tool_name": "extract_transcript",
        "mapping_source": "builtin_mapping",
        "supported_routes": ["VIDEO_URL", "VIDEO_FILE"],
        "supported_actions": ["*"],
        "supported_skill_graphs": ["*"],
        "preference_rank": 100,
    },
    {
        "capability_name": "TRANSCRIBE_AUDIO",
        "provider_id": "builtin-asr",
        "display_name": "Video Audio Track Transcriber",
        "endpoint_kind": "asr",
        "discovery_status": "REGISTERED",
        "server_id": "builtin-asr",
        "tool_name": "transcribe_video_audio_track",
        "mapping_source": "asr_mapping",
        "supported_routes": ["VIDEO_FILE"],
        "supported_actions": ["*"],
        "supported_skill_graphs": ["*"],
        "preference_rank": 200,
    },
    {
        "capability_name": "TRANSCRIBE_AUDIO",
        "provider_id": "builtin-media",
        "display_name": "Audio Transcriber",
        "endpoint_kind": "media",
        "discovery_status": "REGISTERED",
        "server_id": "builtin-media",
        "tool_name": "transcribe_audio",
        "mapping_source": "builtin_mapping",
        "supported_routes": ["AUDIO_FILE", "VIDEO_FILE"],
        "supported_actions": ["*"],
        "supported_skill_graphs": ["*"],
        "preference_rank": 100,
    },
    {
        "capability_name": "CUSTOM_MCP_NETWORK",
        "provider_id": "custom-mcp",
        "display_name": "Custom MCP Provider",
        "endpoint_kind": "custom",
        "discovery_status": "REGISTERED",
        "server_id": "custom-mcp",
        "tool_name": "custom_network_tool",
        "mapping_source": "custom_mapping",
        "supported_routes": ["*"],
        "supported_actions": ["*"],
        "supported_skill_graphs": ["*"],
        "preference_rank": 100,
    },
    {
        "capability_name": "COMMIT_ARTIFACT_VERSION",
        "provider_id": "builtin-artifact-repo",
        "display_name": "Artifact Version Committer",
        "endpoint_kind": "builtin",
        "discovery_status": "REGISTERED",
        "server_id": "builtin-artifact-repo",
        "tool_name": "commit_artifact_version",
        "mapping_source": "builtin_mapping",
        "supported_routes": ["*"],
        "supported_actions": ["*"],
        "supported_skill_graphs": ["*"],
        "preference_rank": 100,
    },
    {
        "capability_name": "EXPORT_ARTIFACT_FILE",
        "provider_id": "builtin-export",
        "display_name": "Artifact File Exporter",
        "endpoint_kind": "builtin",
        "discovery_status": "REGISTERED",
        "server_id": "builtin-export",
        "tool_name": "export_artifact_file",
        "mapping_source": "builtin_mapping",
        "supported_routes": ["*"],
        "supported_actions": ["*"],
        "supported_skill_graphs": ["*"],
        "preference_rank": 100,
    },
    {
        "capability_name": "SAVE_WORKSPACE_SOURCE",
        "provider_id": "builtin-workspace",
        "display_name": "Workspace Source Saver",
        "endpoint_kind": "builtin",
        "discovery_status": "REGISTERED",
        "server_id": "builtin-workspace",
        "tool_name": "save_workspace_source",
        "mapping_source": "builtin_mapping",
        "supported_routes": ["*"],
        "supported_actions": ["*"],
        "supported_skill_graphs": ["*"],
        "preference_rank": 100,
    },
    {
        "capability_name": "WRITE_WIKI_PAGE",
        "provider_id": "builtin-workspace",
        "display_name": "Wiki Page Writer",
        "endpoint_kind": "builtin",
        "discovery_status": "REGISTERED",
        "server_id": "builtin-workspace",
        "tool_name": "write_wiki_page",
        "mapping_source": "builtin_mapping",
        "supported_routes": ["*"],
        "supported_actions": ["*"],
        "supported_skill_graphs": ["generic_artifact_v1"],
        "preference_rank": 100,
    },
    {
        "capability_name": "WRITE_NOTE_PAGE",
        "provider_id": "builtin-workspace",
        "display_name": "Note Page Writer",
        "endpoint_kind": "builtin",
        "discovery_status": "REGISTERED",
        "server_id": "builtin-workspace",
        "tool_name": "write_note_page",
        "mapping_source": "builtin_mapping",
        "supported_routes": ["*"],
        "supported_actions": ["*"],
        "supported_skill_graphs": ["generic_artifact_v1"],
        "preference_rank": 100,
    },
]


def reset_capability_provider_status() -> None:
    with _provider_status_lock:
        _provider_status_overrides.clear()


def reset_capability_provider_discovery_status() -> None:
    with _provider_status_lock:
        _provider_discovery_overrides.clear()
        _provider_last_discovered_at.clear()


def reset_capability_provider_health_status() -> None:
    with _provider_status_lock:
        _provider_health_overrides.clear()
        _provider_last_checked_at.clear()


def reset_capability_provider_approval_status() -> None:
    with _provider_status_lock:
        _provider_approval_overrides.clear()


def reset_capability_provider_probe_results() -> None:
    with _provider_status_lock:
        _provider_probe_results.clear()


def set_capability_provider_status(
    capability_name: str,
    status: str,
    *,
    provider_id: str | None = None,
    tool_name: str | None = None,
) -> None:
    with _provider_status_lock:
        _provider_status_overrides[_override_key(capability_name, provider_id, tool_name)] = (
            status.strip().upper()
        )


def set_capability_provider_discovery_status(
    capability_name: str,
    status: str,
    *,
    provider_id: str | None = None,
    tool_name: str | None = None,
) -> None:
    with _provider_status_lock:
        _provider_discovery_overrides[_override_key(capability_name, provider_id, tool_name)] = (
            status.strip().upper()
        )


def set_capability_provider_health_status(
    capability_name: str,
    status: str,
    *,
    provider_id: str | None = None,
    tool_name: str | None = None,
) -> None:
    with _provider_status_lock:
        _provider_health_overrides[_override_key(capability_name, provider_id, tool_name)] = (
            status.strip().upper()
        )


def set_capability_provider_approval_status(
    capability_name: str,
    status: str,
    *,
    provider_id: str | None = None,
    tool_name: str | None = None,
) -> None:
    with _provider_status_lock:
        _provider_approval_overrides[_override_key(capability_name, provider_id, tool_name)] = (
            status.strip().upper()
        )


def set_capability_provider_probe_result(
    capability_name: str,
    status: str,
    *,
    provider_id: str | None = None,
    tool_name: str | None = None,
) -> None:
    with _provider_status_lock:
        _provider_probe_results[_override_key(capability_name, provider_id, tool_name)] = (
            status.strip().upper()
        )


def get_capability_provider_status(
    capability_name: str,
    *,
    provider_id: str | None = None,
    tool_name: str | None = None,
) -> str:
    with _provider_status_lock:
        return _provider_status_overrides.get(
            _candidate_key(capability_name, provider_id, tool_name),
            _provider_status_overrides.get(capability_name, "AVAILABLE"),
        )


def get_capability_provider_discovery_status(
    capability_name: str,
    *,
    provider_id: str | None = None,
    tool_name: str | None = None,
) -> str:
    default_status = next(
        (
            candidate["discovery_status"]
            for candidate in _provider_candidate_catalog()
            if candidate["capability_name"] == capability_name
            and (provider_id is None or candidate["provider_id"] == provider_id)
            and (tool_name is None or candidate["tool_name"] == tool_name)
        ),
        "REGISTERED",
    )
    with _provider_status_lock:
        return _provider_discovery_overrides.get(
            _candidate_key(capability_name, provider_id, tool_name),
            _provider_discovery_overrides.get(capability_name, default_status),
        )


def get_capability_provider_health_status(
    capability_name: str,
    *,
    provider_id: str | None = None,
    tool_name: str | None = None,
) -> str:
    with _provider_status_lock:
        return _provider_health_overrides.get(
            _candidate_key(capability_name, provider_id, tool_name),
            _provider_health_overrides.get(capability_name, "HEALTHY"),
        )


def get_capability_provider_approval_status(
    capability_name: str,
    *,
    provider_id: str | None = None,
    tool_name: str | None = None,
) -> str:
    binding = resolve_capability_binding(capability_name)
    default_status = "APPROVED" if binding.approval_mode == "REQUIRED" else "NOT_REQUIRED"
    with _provider_status_lock:
        return _provider_approval_overrides.get(
            _candidate_key(capability_name, provider_id, tool_name),
            _provider_approval_overrides.get(capability_name, default_status),
        )


def get_capability_provider_last_checked_at(
    capability_name: str,
    *,
    provider_id: str | None = None,
    tool_name: str | None = None,
) -> str:
    with _provider_status_lock:
        return _provider_last_checked_at.get(
            _candidate_key(capability_name, provider_id, tool_name),
            _provider_last_checked_at.get(capability_name, ""),
        )


def get_capability_provider_last_discovered_at(
    capability_name: str,
    *,
    provider_id: str | None = None,
    tool_name: str | None = None,
) -> str:
    with _provider_status_lock:
        return _provider_last_discovered_at.get(
            _candidate_key(capability_name, provider_id, tool_name),
            _provider_last_discovered_at.get(capability_name, ""),
        )


def list_capability_provider_candidates(capability_name: str) -> list[dict[str, object]]:
    return [
        dict(candidate)
        for candidate in _provider_candidate_catalog()
        if candidate["capability_name"] == capability_name
    ]


def list_capability_providers() -> list[dict[str, object]]:
    providers: list[dict[str, object]] = []
    for candidate in _provider_candidate_catalog():
        capability_name = candidate["capability_name"]
        provider_id = candidate["provider_id"]
        tool_name = candidate["tool_name"]
        binding = resolve_capability_binding(capability_name)
        providers.append(
            {
                **candidate,
                "candidate_key": _candidate_key(capability_name, provider_id, tool_name),
                "capability_name": capability_name,
                "server_id": candidate["server_id"],
                "tool_name": tool_name,
                "discovery_status": get_capability_provider_discovery_status(
                    capability_name,
                    provider_id=provider_id,
                    tool_name=tool_name,
                ),
                "provider_status": get_capability_provider_status(
                    capability_name,
                    provider_id=provider_id,
                    tool_name=tool_name,
                ),
                "health_status": get_capability_provider_health_status(
                    capability_name,
                    provider_id=provider_id,
                    tool_name=tool_name,
                ),
                "approval_status": get_capability_provider_approval_status(
                    capability_name,
                    provider_id=provider_id,
                    tool_name=tool_name,
                ),
                "last_checked_at": _provider_last_checked_at.get(
                    _candidate_key(capability_name, provider_id, tool_name),
                    _provider_last_checked_at.get(capability_name, ""),
                ),
                "last_discovered_at": _provider_last_discovered_at.get(
                    _candidate_key(capability_name, provider_id, tool_name),
                    "",
                ),
                "scope_type": binding.scope_type,
                "approval_mode": binding.approval_mode,
                "risk_level": binding.risk_level,
            }
        )
    return providers


def get_capability_provider_detail(candidate_key: str) -> dict[str, object]:
    normalized_candidate_key = candidate_key.strip()
    provider = next(
        (
            item
            for item in list_capability_providers()
            if str(item["candidate_key"]) == normalized_candidate_key
        ),
        None,
    )
    if provider is None:
        raise ValueError(f"capability provider not found: {candidate_key}")
    return provider


def list_capability_mappings() -> list[dict[str, object]]:
    return [
        {
            "mapping_key": provider["candidate_key"],
            "capability_name": provider["capability_name"],
            "provider_id": provider["provider_id"],
            "server_id": provider["server_id"],
            "tool_name": provider["tool_name"],
            "mapping_source": provider["mapping_source"],
            "endpoint_kind": provider["endpoint_kind"],
            "supported_routes": provider["supported_routes"],
            "supported_actions": provider["supported_actions"],
            "supported_skill_graphs": provider["supported_skill_graphs"],
            "discovery_status": provider["discovery_status"],
            "last_discovered_at": provider["last_discovered_at"],
        }
        for provider in list_capability_providers()
    ]


def get_capability_mapping_detail(mapping_key: str) -> dict[str, object]:
    normalized_mapping_key = mapping_key.strip()
    mapping = next(
        (
            item
            for item in list_capability_mappings()
            if str(item["mapping_key"]) == normalized_mapping_key
        ),
        None,
    )
    if mapping is None:
        raise ValueError(f"capability mapping not found: {mapping_key}")
    return mapping


def run_capability_provider_health_checks(
    capability_names: list[str] | None = None,
) -> dict[str, object]:
    checked_capabilities = capability_names or sorted(
        {candidate["capability_name"] for candidate in _provider_candidate_catalog()}
    )
    with _provider_status_lock:
        for capability_name in checked_capabilities:
            for candidate in list_capability_provider_candidates(capability_name):
                candidate_key = _candidate_key(
                    capability_name,
                    candidate["provider_id"],
                    candidate["tool_name"],
                )
                probe_result = _provider_probe_results.get(
                    candidate_key,
                    _provider_probe_results.get(capability_name, "HEALTHY"),
                )
                _provider_health_overrides[candidate_key] = probe_result
                _provider_last_checked_at[candidate_key] = _utc_now()
                if probe_result in {"DOWN", "UNAVAILABLE"}:
                    _provider_status_overrides[candidate_key] = "UNAVAILABLE"
                else:
                    _provider_status_overrides[candidate_key] = "AVAILABLE"
    resume_attempts = _wake_waiting_tasks_for_capabilities(checked_capabilities)
    snapshot = debug_provider_health_snapshot()
    snapshot["resumed_tasks"] = resume_attempts
    snapshot["summary"]["resume_attempt_count"] = len(resume_attempts)
    return snapshot


def run_capability_provider_discovery_scan(
    capability_names: list[str] | None = None,
) -> dict[str, object]:
    checked_capabilities = capability_names or sorted(
        {candidate["capability_name"] for candidate in _provider_candidate_catalog()}
    )
    with _provider_status_lock:
        for capability_name in checked_capabilities:
            for candidate in list_capability_provider_candidates(capability_name):
                candidate_key = _candidate_key(
                    capability_name,
                    candidate["provider_id"],
                    candidate["tool_name"],
                )
                _provider_discovery_overrides[candidate_key] = "DISCOVERED"
                _provider_last_discovered_at[candidate_key] = _utc_now()
    resume_attempts = _wake_waiting_tasks_for_capabilities(checked_capabilities)
    snapshot = debug_provider_discovery_snapshot()
    snapshot["resumed_tasks"] = resume_attempts
    snapshot["summary"]["resume_attempt_count"] = len(resume_attempts)
    return snapshot


def debug_list_capability_providers() -> list[dict[str, object]]:
    return list_capability_providers()


def debug_capability_mapping_snapshot() -> dict[str, object]:
    mappings = list_capability_mappings()
    return {
        "mappings": mappings,
        "summary": {
            "total": len(mappings),
            "capability_count": len({mapping["capability_name"] for mapping in mappings}),
        },
    }


def debug_provider_discovery_snapshot() -> dict[str, object]:
    providers = list_capability_providers()
    discovered_count = sum(
        1 for provider in providers if provider["discovery_status"] in {"REGISTERED", "DISCOVERED"}
    )
    undiscovered_count = sum(
        1 for provider in providers if provider["discovery_status"] not in {"REGISTERED", "DISCOVERED"}
    )
    return {
        "providers": providers,
        "summary": {
            "total": len(providers),
            "discovered_count": discovered_count,
            "undiscovered_count": undiscovered_count,
        },
    }


def debug_provider_health_snapshot() -> dict[str, object]:
    providers = list_capability_providers()
    unhealthy_count = sum(
        1 for provider in providers if provider["health_status"] != "HEALTHY"
    )
    return {
        "providers": providers,
        "summary": {
            "total": len(providers),
            "unhealthy_count": unhealthy_count,
        },
    }


def _provider_candidate_catalog() -> list[dict[str, object]]:
    return _BUILTIN_PROVIDER_CANDIDATE_CATALOG + _custom_provider_candidates()


def _custom_provider_candidates() -> list[dict[str, object]]:
    candidates: list[dict[str, object]] = []
    for server in list_custom_mcp_servers():
        for tool in server.tools:
            candidates.append(
                {
                    "capability_name": tool.capability_name,
                    "provider_id": server.server_id,
                    "display_name": server.display_name,
                    "endpoint_kind": server.endpoint_kind,
                    "discovery_status": "REGISTERED",
                    "server_id": server.server_id,
                    "tool_name": tool.tool_name,
                    "mapping_source": "custom_mcp_registration",
                    "supported_routes": list(tool.supported_routes),
                    "supported_actions": list(tool.supported_actions),
                    "supported_skill_graphs": list(tool.supported_skill_graphs),
                    "preference_rank": tool.preference_rank,
                    "selection_reason_hint": tool.selection_reason_hint,
                    "registration_origin": server.registration_origin,
                    "server_notes": list(server.server_notes),
                    "tool_notes": list(tool.notes),
                    "output_kind": tool.output_kind,
                }
            )
    return candidates


def _override_key(
    capability_name: str,
    provider_id: str | None,
    tool_name: str | None,
) -> str:
    if provider_id is None and tool_name is None:
        return capability_name
    return _candidate_key(capability_name, provider_id, tool_name)


def _candidate_key(
    capability_name: str,
    provider_id: str | None,
    tool_name: str | None,
) -> str:
    return f"{capability_name}:{provider_id or '*'}:{tool_name or '*'}"


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _wake_waiting_tasks_for_capabilities(capability_names: list[str]) -> list[dict[str, object]]:
    from app.capability_wait_queue import wake_waiting_tasks_for_capability

    deduped_attempts: dict[str, dict[str, object]] = {}
    for capability_name in capability_names:
        for attempt in wake_waiting_tasks_for_capability(capability_name):
            deduped_attempts[str(attempt["task_id"])] = attempt
    return list(deduped_attempts.values())
