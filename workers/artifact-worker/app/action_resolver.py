from __future__ import annotations

from app.action_compat import resolve_action_compatibility
from app.content_runtime import describe_input_adapter_routes
from app.models import ActionResolutionDecision, ArtifactTaskInput
from app.registry import list_custom_actions


def resolve_requested_action(task_input: ArtifactTaskInput) -> ActionResolutionDecision:
    action_compatibility = resolve_action_compatibility(task_input)
    requested_action_key = action_compatibility.requested_action_key
    skill_key = action_compatibility.skill_key
    routes = sorted(set(describe_input_adapter_routes(task_input)))
    brief_blob = _build_brief_blob(task_input)
    structure_blob = _build_structure_blob(task_input)
    source_platforms = _extract_source_platforms(task_input)
    task_neighborhood = task_input.control_pack.task_neighborhood.strip().upper()
    resolved_from_skill = action_compatibility.resolved_from_skill

    if skill_key and resolved_from_skill:
        if action_compatibility.explicit_requested_action_key:
            if not action_compatibility.legacy_action_conflict:
                return ActionResolutionDecision(
                    requested_action_key=requested_action_key,
                    resolved_action_key=requested_action_key,
                    resolution_mode="EXPLICIT",
                    reason_code="explicit_action_requested",
                    route_basis=routes,
                    winning_signals=["EXPLICIT_REQUEST"],
                    candidate_scores=[],
                    notes=[
                        "Action Resolver preserved the explicitly requested production action.",
                        f"Skill key {skill_key} confirms the same action binding.",
                    ],
                )
            return ActionResolutionDecision(
                requested_action_key=requested_action_key,
                resolved_action_key=resolved_from_skill,
                resolution_mode="SKILL_BINDING",
                reason_code="skill_key_overrode_conflicting_action",
                route_basis=routes,
                winning_signals=["SKILL_KEY_BINDING", "LEGACY_ACTION_CONFLICT"],
                candidate_scores=[],
                notes=[
                    f"Action Resolver ignored conflicting legacy action {requested_action_key}.",
                    f"Skill key {skill_key} bound the request to action {resolved_from_skill}.",
                ],
            )
        return ActionResolutionDecision(
            requested_action_key=requested_action_key,
            resolved_action_key=resolved_from_skill,
            resolution_mode="SKILL_BINDING",
            reason_code="skill_key_bound_action",
            route_basis=routes,
            winning_signals=["SKILL_KEY_BINDING"],
            candidate_scores=[],
            notes=[f"Action Resolver bound skill key {skill_key} to action {resolved_from_skill}."],
        )

    if action_compatibility.explicit_requested_action_key:
        return ActionResolutionDecision(
            requested_action_key=requested_action_key,
            resolved_action_key=requested_action_key,
            resolution_mode="EXPLICIT",
            reason_code="explicit_action_requested",
            route_basis=routes,
            winning_signals=["EXPLICIT_REQUEST"],
            candidate_scores=[],
            notes=["Action Resolver preserved the explicitly requested production action."],
        )
    resolved_action_key, reason_code, notes = _infer_action_from_context(
        brief_blob=brief_blob,
        structure_blob=structure_blob,
        routes=routes,
        style_profile_key=task_input.input_payload.style_profile_key.strip().upper(),
        source_platforms=source_platforms,
        task_neighborhood=task_neighborhood,
    )
    winning_signals, candidate_scores = _build_action_resolution_metadata(
        resolved_action_key=resolved_action_key,
        reason_code=reason_code,
        brief_blob=brief_blob,
        structure_blob=structure_blob,
        routes=routes,
        style_profile_key=task_input.input_payload.style_profile_key.strip().upper(),
        source_platforms=source_platforms,
        task_neighborhood=task_neighborhood,
    )
    return ActionResolutionDecision(
        requested_action_key=requested_action_key,
        resolved_action_key=resolved_action_key,
        resolution_mode="AUTO",
        reason_code=reason_code,
        route_basis=routes,
        winning_signals=winning_signals,
        candidate_scores=candidate_scores,
        notes=notes,
    )


def _infer_action_from_context(
    *,
    brief_blob: str,
    structure_blob: str,
    routes: list[str],
    style_profile_key: str,
    source_platforms: list[str],
    task_neighborhood: str,
) -> tuple[str, str, list[str]]:
    custom_candidate = _select_custom_action_candidate(
        brief_blob=brief_blob,
        structure_blob=structure_blob,
        routes=routes,
        style_profile_key=style_profile_key,
        source_platforms=source_platforms,
        task_neighborhood=task_neighborhood,
    )
    if custom_candidate is not None:
        return custom_candidate

    if _contains_any(brief_blob, ["简历", "resume", "亮点"]):
        return "RESUME_HIGHLIGHT", "keyword_resume_highlight", [
            "Detected resume-oriented phrasing in the generation brief.",
        ]
    if _contains_any(brief_blob, ["faq", "甯歌闂", "闂瓟"]):
        return "FAQ", "keyword_faq", [
            "Detected FAQ-oriented phrasing in the generation brief.",
        ]
    if _contains_any(brief_blob, ["quiz", "测验", "测试题", "自测"]):
        return "QUIZ", "keyword_quiz", [
            "Detected quiz-oriented phrasing in the generation brief.",
        ]
    if _contains_any(brief_blob, ["wiki", "知识页", "知识库页面"]):
        return "WIKI_PAGE", "keyword_wiki_page", [
            "Detected wiki-like phrasing in the generation brief.",
        ]
    if _contains_any(brief_blob, ["学习指南", "study guide", "学习路径"]):
        return "STUDY_GUIDE", "keyword_study_guide", [
            "Detected study-guide phrasing in the generation brief.",
        ]
    if _contains_any(brief_blob, ["课程笔记", "course notes"]):
        return "COURSE_NOTES", "keyword_course_notes", [
            "Detected course-note phrasing in the generation brief.",
        ]
    if _contains_any(brief_blob, ["纪要", "minutes", "会议录音", "meeting minutes"]):
        return "AUDIO_MINUTES", "keyword_audio_minutes", [
            "Detected meeting-minute phrasing in the generation brief.",
        ]
    if _contains_any(brief_blob, ["结构化笔记", "后续问题", "笔记"]):
        return "STRUCTURED_NOTE", "keyword_structured_note", [
            "Detected note-taking phrasing in the generation brief.",
        ]
    if _contains_any(brief_blob, ["视频总结", "video summary", "总结这个视频"]):
        return "VIDEO_SUMMARY", "keyword_video_summary", [
            "Detected video-summary phrasing in the generation brief.",
        ]

    route_set = set(routes)
    if "VIDEO_URL" in route_set:
        return "VIDEO_SUMMARY", "route_video_url_default_action", [
            "Detected a video URL source, defaulting to the video summary action.",
        ]
    if "AUDIO_FILE" in route_set:
        return "AUDIO_MINUTES", "route_audio_file_default_action", [
            "Detected an audio file source, defaulting to the audio minutes action.",
        ]
    if "VIDEO_FILE" in route_set:
        if style_profile_key == "TEACHING" or _contains_any(
            brief_blob,
            ["课程", "知识点", "复习题", "课堂"],
        ):
            return "COURSE_NOTES", "route_video_file_course_notes", [
                "Detected a teaching-oriented video file workload, defaulting to course notes.",
            ]
        return "VIDEO_SUMMARY", "route_video_file_video_summary", [
            "Detected a video file source, defaulting to the video summary action.",
        ]
    if "WEB_URL" in route_set and _contains_any(brief_blob, ["学习", "概念", "练习"]):
        return "STUDY_GUIDE", "route_web_url_study_guide", [
            "Detected a web-source study request, defaulting to the study guide action.",
        ]

    return "REPORT", "default_report_action", [
        "No stronger route or brief signal matched; defaulting to the report action.",
    ]


def _build_brief_blob(task_input: ArtifactTaskInput) -> str:
    fields = [
        task_input.input_payload.user_requirement,
        task_input.input_payload.generation_brief,
        *task_input.control_pack.structure_constraints,
        *task_input.control_pack.style_constraints,
        *(source.title for source in task_input.source_scope),
        *(source.summary for source in task_input.source_scope),
    ]
    return " ".join(field.strip().lower() for field in fields if field and field.strip())


def _build_structure_blob(task_input: ArtifactTaskInput) -> str:
    return " ".join(
        item.strip().lower()
        for item in task_input.control_pack.structure_constraints
        if item and item.strip()
    )


def _extract_source_platforms(task_input: ArtifactTaskInput) -> list[str]:
    platforms: list[str] = []
    for source in task_input.source_scope:
        source_platform = str(source.source_metadata.get("platform", "")).strip().upper()
        if source_platform:
            platforms.append(source_platform)
        normalized_uri = source.source_uri.strip().lower()
        if "bilibili.com" in normalized_uri or "b23.tv" in normalized_uri:
            platforms.append("BILIBILI")
    return list(dict.fromkeys(platforms))


def _contains_any(text: str, candidates: list[str]) -> bool:
    return any(candidate.lower() in text for candidate in candidates)


def _select_custom_action_candidate(
    *,
    brief_blob: str,
    structure_blob: str,
    routes: list[str],
    style_profile_key: str,
    source_platforms: list[str],
    task_neighborhood: str,
) -> tuple[str, str, list[str]] | None:
    route_set = {route.upper() for route in routes}
    normalized_style_profile_key = style_profile_key.strip().upper()
    source_platform_set = {platform.upper() for platform in source_platforms}
    normalized_task_neighborhood = task_neighborhood.strip().upper()
    candidates: list[tuple[int, int, int, int, int, int, int, int, str, str, list[str]]] = []

    for action in list_custom_actions():
        matched_keywords = [
            keyword
            for keyword in action.resolver_keywords
            if keyword.lower() in brief_blob
        ]
        matched_routes = [
            route
            for route in action.preferred_routes
            if route.upper() in route_set
        ]
        matched_style_profiles = [
            profile_key
            for profile_key in action.preferred_style_profiles
            if profile_key == normalized_style_profile_key
        ]
        matched_source_platforms = [
            platform
            for platform in action.preferred_source_platforms
            if platform.upper() in source_platform_set
        ]
        matched_structure_keywords = [
            keyword
            for keyword in action.preferred_structure_keywords
            if keyword.lower() in structure_blob
        ]
        matched_task_neighborhoods = [
            neighborhood
            for neighborhood in action.preferred_task_neighborhoods
            if neighborhood == normalized_task_neighborhood
        ]
        signal_type_count = sum(
            1
            for signal in (
                matched_keywords,
                matched_routes,
                matched_style_profiles,
                matched_source_platforms,
                matched_structure_keywords,
                matched_task_neighborhoods,
            )
            if signal
        )
        if signal_type_count == 0:
            continue

        reason_code = _derive_custom_action_reason_code(
            matched_keywords=matched_keywords,
            matched_routes=matched_routes,
            matched_style_profiles=matched_style_profiles,
            matched_source_platforms=matched_source_platforms,
            matched_structure_keywords=matched_structure_keywords,
            matched_task_neighborhoods=matched_task_neighborhoods,
        )
        notes = _build_custom_action_resolution_notes(
            action_key=action.action_key,
            resolver_priority=action.resolver_priority,
            matched_keywords=matched_keywords,
            matched_routes=matched_routes,
            matched_style_profiles=matched_style_profiles,
            matched_source_platforms=matched_source_platforms,
            matched_structure_keywords=matched_structure_keywords,
            matched_task_neighborhoods=matched_task_neighborhoods,
        )
        candidates.append(
            (
                action.resolver_priority,
                signal_type_count,
                len(matched_keywords),
                len(matched_routes),
                len(matched_style_profiles),
                len(matched_source_platforms),
                len(matched_structure_keywords),
                len(matched_task_neighborhoods),
                action.action_key,
                reason_code,
                notes,
            )
        )

    if not candidates:
        return None

    candidates.sort(
        key=lambda item: (
            -item[0],
            -item[1],
            -item[2],
            -item[3],
            -item[4],
            -item[5],
            -item[6],
            -item[7],
            item[8],
        )
    )
    _, _, _, _, _, _, _, _, action_key, reason_code, notes = candidates[0]
    return action_key, reason_code, notes


def _build_action_resolution_metadata(
    *,
    resolved_action_key: str,
    reason_code: str,
    brief_blob: str,
    structure_blob: str,
    routes: list[str],
    style_profile_key: str,
    source_platforms: list[str],
    task_neighborhood: str,
) -> tuple[list[str], list[dict[str, object]]]:
    if reason_code.startswith("custom_action_"):
        ranked_candidates = _rank_custom_action_candidates(
            brief_blob=brief_blob,
            structure_blob=structure_blob,
            routes=routes,
            style_profile_key=style_profile_key,
            source_platforms=source_platforms,
            task_neighborhood=task_neighborhood,
        )
        candidate_scores = [
            {
                "action_key": str(candidate["action_key"]),
                "selected": str(candidate["action_key"]) == resolved_action_key,
                "reason_code": str(candidate["reason_code"]),
                "matched_signal_types": list(candidate["matched_signal_types"]),
                "matched_values": dict(candidate["matched_values"]),
                "score_breakdown": {
                    "resolver_priority": int(candidate["resolver_priority"]),
                    "signal_type_count": int(candidate["signal_type_count"]),
                    "keyword_count": int(candidate["keyword_count"]),
                    "route_count": int(candidate["route_count"]),
                    "style_profile_count": int(candidate["style_profile_count"]),
                    "source_platform_count": int(candidate["source_platform_count"]),
                    "structure_keyword_count": int(candidate["structure_keyword_count"]),
                    "task_neighborhood_count": int(candidate["task_neighborhood_count"]),
                },
            }
            for candidate in ranked_candidates
        ]
        selected_candidate = next(
            (
                candidate
                for candidate in candidate_scores
                if candidate["action_key"] == resolved_action_key
            ),
            {},
        )
        return list(selected_candidate.get("matched_signal_types", [])), candidate_scores

    return _derive_builtin_winning_signals(reason_code), []


def _derive_builtin_winning_signals(reason_code: str) -> list[str]:
    if reason_code.startswith("keyword_"):
        return ["BRIEF_KEYWORD"]
    if reason_code.startswith("route_") and "course_notes" in reason_code:
        return ["ROUTE", "STYLE_PROFILE"]
    if reason_code.startswith("route_") and "study_guide" in reason_code:
        return ["ROUTE", "BRIEF_KEYWORD"]
    if reason_code.startswith("route_"):
        return ["ROUTE"]
    if reason_code == "default_report_action":
        return ["DEFAULT_FALLBACK"]
    return []


def _rank_custom_action_candidates(
    *,
    brief_blob: str,
    structure_blob: str,
    routes: list[str],
    style_profile_key: str,
    source_platforms: list[str],
    task_neighborhood: str,
) -> list[dict[str, object]]:
    route_set = {route.upper() for route in routes}
    normalized_style_profile_key = style_profile_key.strip().upper()
    source_platform_set = {platform.upper() for platform in source_platforms}
    normalized_task_neighborhood = task_neighborhood.strip().upper()
    candidates: list[dict[str, object]] = []

    for action in list_custom_actions():
        matched_keywords = [
            keyword
            for keyword in action.resolver_keywords
            if keyword.lower() in brief_blob
        ]
        matched_routes = [
            route
            for route in action.preferred_routes
            if route.upper() in route_set
        ]
        matched_style_profiles = [
            profile_key
            for profile_key in action.preferred_style_profiles
            if profile_key == normalized_style_profile_key
        ]
        matched_source_platforms = [
            platform
            for platform in action.preferred_source_platforms
            if platform.upper() in source_platform_set
        ]
        matched_structure_keywords = [
            keyword
            for keyword in action.preferred_structure_keywords
            if keyword.lower() in structure_blob
        ]
        matched_task_neighborhoods = [
            neighborhood
            for neighborhood in action.preferred_task_neighborhoods
            if neighborhood == normalized_task_neighborhood
        ]
        signal_type_count = sum(
            1
            for signal in (
                matched_keywords,
                matched_routes,
                matched_style_profiles,
                matched_source_platforms,
                matched_structure_keywords,
                matched_task_neighborhoods,
            )
            if signal
        )
        if signal_type_count == 0:
            continue

        candidates.append(
            {
                "resolver_priority": action.resolver_priority,
                "signal_type_count": signal_type_count,
                "keyword_count": len(matched_keywords),
                "route_count": len(matched_routes),
                "style_profile_count": len(matched_style_profiles),
                "source_platform_count": len(matched_source_platforms),
                "structure_keyword_count": len(matched_structure_keywords),
                "task_neighborhood_count": len(matched_task_neighborhoods),
                "action_key": action.action_key,
                "reason_code": _derive_custom_action_reason_code(
                    matched_keywords=matched_keywords,
                    matched_routes=matched_routes,
                    matched_style_profiles=matched_style_profiles,
                    matched_source_platforms=matched_source_platforms,
                    matched_structure_keywords=matched_structure_keywords,
                    matched_task_neighborhoods=matched_task_neighborhoods,
                ),
                "matched_signal_types": _build_custom_action_signal_types(
                    matched_keywords=matched_keywords,
                    matched_routes=matched_routes,
                    matched_style_profiles=matched_style_profiles,
                    matched_source_platforms=matched_source_platforms,
                    matched_structure_keywords=matched_structure_keywords,
                    matched_task_neighborhoods=matched_task_neighborhoods,
                ),
                "matched_values": {
                    "keywords": matched_keywords,
                    "routes": matched_routes,
                    "style_profiles": matched_style_profiles,
                    "source_platforms": matched_source_platforms,
                    "structure_keywords": matched_structure_keywords,
                    "task_neighborhoods": matched_task_neighborhoods,
                },
            }
        )

    candidates.sort(
        key=lambda item: (
            -int(item["resolver_priority"]),
            -int(item["signal_type_count"]),
            -int(item["keyword_count"]),
            -int(item["route_count"]),
            -int(item["style_profile_count"]),
            -int(item["source_platform_count"]),
            -int(item["structure_keyword_count"]),
            -int(item["task_neighborhood_count"]),
            str(item["action_key"]),
        )
    )
    return candidates


def _build_custom_action_signal_types(
    *,
    matched_keywords: list[str],
    matched_routes: list[str],
    matched_style_profiles: list[str],
    matched_source_platforms: list[str],
    matched_structure_keywords: list[str],
    matched_task_neighborhoods: list[str],
) -> list[str]:
    signal_types: list[str] = []
    if matched_keywords:
        signal_types.append("BRIEF_KEYWORD")
    if matched_routes:
        signal_types.append("ROUTE")
    if matched_style_profiles:
        signal_types.append("STYLE_PROFILE")
    if matched_source_platforms:
        signal_types.append("SOURCE_PLATFORM")
    if matched_structure_keywords:
        signal_types.append("STRUCTURE")
    if matched_task_neighborhoods:
        signal_types.append("WORKSPACE_CONTEXT")
    return signal_types


def _derive_custom_action_reason_code(
    *,
    matched_keywords: list[str],
    matched_routes: list[str],
    matched_style_profiles: list[str],
    matched_source_platforms: list[str],
    matched_structure_keywords: list[str],
    matched_task_neighborhoods: list[str],
) -> str:
    signal_type_count = sum(
        1
        for signal in (
            matched_keywords,
            matched_routes,
            matched_style_profiles,
            matched_source_platforms,
            matched_structure_keywords,
            matched_task_neighborhoods,
        )
        if signal
    )
    if signal_type_count >= 2:
        return "custom_action_multi_signal_match"
    if matched_keywords:
        return "custom_action_keyword_match"
    if matched_routes:
        return "custom_action_route_match"
    if matched_source_platforms:
        return "custom_action_source_platform_match"
    if matched_structure_keywords:
        return "custom_action_structure_match"
    if matched_task_neighborhoods:
        return "custom_action_workspace_context_match"
    return "custom_action_style_match"


def _build_custom_action_resolution_notes(
    *,
    action_key: str,
    resolver_priority: int,
    matched_keywords: list[str],
    matched_routes: list[str],
    matched_style_profiles: list[str],
    matched_source_platforms: list[str],
    matched_structure_keywords: list[str],
    matched_task_neighborhoods: list[str],
) -> list[str]:
    notes = [f"Resolver priority: {resolver_priority}."]
    if matched_keywords:
        notes.append(
            f"Matched custom action resolver keywords for {action_key}: {', '.join(matched_keywords)}."
        )
    if matched_routes:
        notes.append(
            f"Matched custom action preferred routes for {action_key}: {', '.join(matched_routes)}."
        )
    if matched_style_profiles:
        notes.append(
            "Matched custom action preferred style profiles for "
            f"{action_key}: {', '.join(matched_style_profiles)}."
        )
    if matched_source_platforms:
        notes.append(
            "Matched custom action preferred source platforms for "
            f"{action_key}: {', '.join(matched_source_platforms)}."
        )
    if matched_structure_keywords:
        notes.append(
            "Matched custom action preferred structure keywords for "
            f"{action_key}: {', '.join(matched_structure_keywords)}."
        )
    if matched_task_neighborhoods:
        notes.append(
            "Matched custom action preferred task neighborhoods for "
            f"{action_key}: {', '.join(matched_task_neighborhoods)}."
        )
    notes.append(
        "Custom action resolver selected the highest-scoring candidate across priority, "
        "keyword, route, style, source platform, structure, and workspace-context signals."
    )
    return notes

