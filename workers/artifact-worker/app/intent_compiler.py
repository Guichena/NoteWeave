from __future__ import annotations

from app.action_compat import (
    normalize_skill_key,
    resolve_action_compatibility,
)
from app.artifact_skill_catalog import (
    resolve_artifact_skill_definition,
)
from app.models import ArtifactTaskInput, ExecutionSpec


def compile_execution_spec(task_input: ArtifactTaskInput) -> ExecutionSpec:
    action_compatibility = resolve_action_compatibility(task_input)
    skill_key = action_compatibility.skill_key
    skill_definition = None
    if skill_key:
        skill_definition = resolve_artifact_skill_definition(skill_key)
        _validate_required_inputs(task_input, skill_definition)
    requested_action_key = action_compatibility.requested_action_key
    resolved_action_key = action_compatibility.effective_action_key
    user_requirement = task_input.input_payload.user_requirement.strip()
    generation_brief = (
        task_input.input_payload.generation_brief.strip()
        or user_requirement
        or f"Generate artifact for {skill_key or resolved_action_key.lower()}."
    )
    inputs = dict(task_input.input_payload.inputs)
    focus_points = _extract_focus_points(user_requirement)
    style_directives = _build_style_directives(task_input, inputs, user_requirement)
    constraints = _build_constraints(task_input, user_requirement)
    notes = []
    if skill_key:
        notes.append(f"skill-first request resolved via skill key: {skill_key}")
        notes.append(f"artifact skill catalog binding: {resolved_action_key}")
        if action_compatibility.legacy_action_conflict:
            notes.append(
                "conflicting legacy action ignored: "
                f"{action_compatibility.explicit_requested_action_key} -> {resolved_action_key}"
            )
    if user_requirement:
        notes.append("execution spec compiled from user requirement")
    if skill_key == "resume_highlight":
        notes.append(
            "resume graph binding: source_digest -> fact_extractor -> impact_normalizer -> bullet_writer -> verifier -> repair"
        )
        if focus_points:
            notes.append("resume focus profile: " + " / ".join(focus_points[:3]))
    if not task_input.input_payload.generation_brief.strip() and user_requirement:
        notes.append("generation brief derived from user requirement")
    return ExecutionSpec(
        skill_key=skill_key,
        requested_action_key=requested_action_key,
        resolved_action_key=resolved_action_key,
        goal=user_requirement or generation_brief,
        audience=_infer_audience(user_requirement, skill_key),
        focus_points=focus_points,
        style_directives=style_directives,
        constraints=constraints,
        output_shape=_infer_output_shape(skill_key, resolved_action_key),
        generation_brief=generation_brief,
        inputs=inputs,
        notes=notes,
    )


def _validate_required_inputs(
    task_input: ArtifactTaskInput,
    skill_definition: object,
) -> None:
    required_keys = _read_required_input_keys(skill_definition.input_schema)
    for required_key in required_keys:
        if _has_required_input(task_input, skill_definition, required_key):
            continue
        if _is_url_required_key(skill_definition, required_key):
            raise ValueError(
                f"artifact skill '{skill_definition.skill_key}' requires a url input"
            )
        raise ValueError(f"artifact skill '{skill_definition.skill_key}' is missing required input: {required_key}")

    if not required_keys and skill_definition.requires_url_input and _has_url_input(task_input, skill_definition.url_input_keys):
        return
    if not required_keys and skill_definition.requires_url_input:
        raise ValueError(
            f"artifact skill '{skill_definition.skill_key}' requires a url input"
        )


def _read_required_input_keys(input_schema: dict[str, object]) -> list[str]:
    required_values = input_schema.get("required", [])
    if not isinstance(required_values, list):
        return []
    required_keys: list[str] = []
    for value in required_values:
        if not isinstance(value, str):
            continue
        normalized = value.strip()
        if normalized:
            required_keys.append(normalized)
    return list(dict.fromkeys(required_keys))


def _has_required_input(
    task_input: ArtifactTaskInput,
    skill_definition: object,
    required_key: str,
) -> bool:
    if _is_url_required_key(skill_definition, required_key):
        return _has_url_input(task_input, _build_url_candidate_keys(skill_definition, required_key))
    value = str(task_input.input_payload.inputs.get(required_key, "")).strip()
    return bool(value)


def _is_url_required_key(skill_definition: object, required_key: str) -> bool:
    normalized_required_key = required_key.strip().lower()
    return normalized_required_key == "url" or normalized_required_key in {
        key.strip().lower()
        for key in skill_definition.url_input_keys
    }


def _build_url_candidate_keys(
    skill_definition: object,
    required_key: str,
) -> list[str]:
    candidate_keys = [required_key]
    candidate_keys.extend(skill_definition.url_input_keys)
    normalized_keys: list[str] = []
    for key in candidate_keys:
        normalized = key.strip()
        if normalized and normalized not in normalized_keys:
            normalized_keys.append(normalized)
    return normalized_keys


def _has_url_input(
    task_input: ArtifactTaskInput,
    url_input_keys: list[str],
) -> bool:
    inputs = task_input.input_payload.inputs
    for key in url_input_keys:
        value = str(inputs.get(key, "")).strip()
        if value:
            return True
    for source in task_input.source_scope:
        if source.source_uri.strip():
            return True
        if source.source_type.upper() == "URL":
            return True
    return False


def _extract_focus_points(user_requirement: str) -> list[str]:
    focus_points: list[str] = []
    for phrase in _split_requirement_phrases(user_requirement):
        normalized_phrase = _strip_focus_prefixes(phrase)
        if (
            not normalized_phrase
            or _is_audience_phrase(normalized_phrase)
            or _is_style_directive_phrase(normalized_phrase)
            or _is_constraint_phrase(normalized_phrase)
        ):
            continue
        focus_points.append(normalized_phrase)
    return list(dict.fromkeys(focus_points[:6]))


def _build_style_directives(
    task_input: ArtifactTaskInput,
    inputs: dict[str, object],
    user_requirement: str,
) -> list[str]:
    directives = list(task_input.control_pack.style_constraints[:3])
    directives.extend(_extract_requirement_style_directives(user_requirement))
    language = str(inputs.get("language", "")).strip()
    if language:
        directives.append(f"language={language}")
    return list(dict.fromkeys(directives))


def _build_constraints(task_input: ArtifactTaskInput, user_requirement: str) -> list[str]:
    constraints = list(task_input.control_pack.structure_constraints[:4])
    constraints.extend(
        f"avoid={pattern}"
        for pattern in task_input.control_pack.forbidden_patterns[:3]
        if pattern.strip()
    )
    constraints.extend(_extract_requirement_constraints(user_requirement))
    return list(dict.fromkeys(constraints))


def _infer_output_shape(skill_key: str, resolved_action_key: str) -> str:
    if skill_key == "resume_highlight" or resolved_action_key == "RESUME_HIGHLIGHT":
        return "BULLET_LIST"
    if skill_key == "quiz_pack" or resolved_action_key == "QUIZ":
        return "QUIZ_PACK"
    if skill_key == "wiki_page" or resolved_action_key == "WIKI_PAGE":
        return "WIKI_ARTICLE"
    if skill_key == "mindmap_from_workspace":
        return "MINDMAP_MARKDOWN"
    if skill_key == "bilibili_course_note_pdf":
        return "COURSE_NOTE_PDF_REQUEST"
    return "STRUCTURED_ARTIFACT"


def _infer_audience(user_requirement: str, skill_key: str) -> str:
    requirement = user_requirement.lower()
    if "校招" in user_requirement or "面试" in user_requirement or "interview" in requirement:
        return "INTERVIEWER"
    if skill_key == "study_guide":
        return "LEARNER"
    if skill_key == "wiki_page":
        return "KNOWLEDGE_READER"
    if skill_key == "mindmap_from_workspace":
        return "VISUAL_LEARNER"
    return "GENERAL"


def _split_requirement_phrases(user_requirement: str) -> list[str]:
    normalized = (
        user_requirement
        .replace("，", ",")
        .replace("、", ",")
        .replace("。", ",")
        .replace("；", ",")
        .replace(";", ",")
    )
    return [segment.strip() for segment in normalized.split(",") if segment.strip()]


def _strip_focus_prefixes(phrase: str) -> str:
    normalized = phrase.strip()
    for prefix in ("强调", "突出", "聚焦", "关注", "围绕", "面向"):
        if normalized.startswith(prefix):
            normalized = normalized[len(prefix):].strip()
    return normalized


def _is_audience_phrase(phrase: str) -> bool:
    lowered = phrase.lower()
    return (
        "校招" in phrase
        or "面试" in phrase
        or "简历" in phrase
        or "interview" in lowered
        or "recruit" in lowered
    )


def _is_style_directive_phrase(phrase: str) -> bool:
    lowered = phrase.lower()
    return any(
        keyword in phrase or keyword in lowered
        for keyword in (
            "动词开头",
            "尽量量化",
            "量化",
            "强调结果",
            "结果导向",
            "bullet",
        )
    )


def _is_constraint_phrase(phrase: str) -> bool:
    lowered = phrase.lower()
    return any(
        keyword in phrase or keyword in lowered
        for keyword in (
            "条以内",
            "控制在",
            "限制为",
            "最多",
            "at most",
            "within",
        )
    )


def _extract_requirement_style_directives(user_requirement: str) -> list[str]:
    directives: list[str] = []
    for phrase in _split_requirement_phrases(user_requirement):
        normalized_phrase = phrase.strip()
        if not _is_style_directive_phrase(normalized_phrase):
            continue
        if "动词开头" in normalized_phrase:
            directives.append("动词开头")
        if "尽量量化" in normalized_phrase or "量化" in normalized_phrase:
            directives.append("尽量量化")
        if "强调结果" in normalized_phrase or "结果导向" in normalized_phrase:
            directives.append("强调结果")
    return list(dict.fromkeys(directives))


def _extract_requirement_constraints(user_requirement: str) -> list[str]:
    constraints: list[str] = []
    for phrase in _split_requirement_phrases(user_requirement):
        normalized_phrase = phrase.strip()
        if not _is_constraint_phrase(normalized_phrase):
            continue
        max_bullets = _extract_max_bullets(normalized_phrase)
        if max_bullets:
            constraints.append(f"max_bullets={max_bullets}")
    return list(dict.fromkeys(constraints))


def _extract_max_bullets(phrase: str) -> int:
    normalized = (
        phrase.replace("三", "3")
        .replace("三条", "3条")
        .replace("三点", "3点")
    )
    digits = ""
    for char in normalized:
        if char.isdigit():
            digits += char
        elif digits:
            break
    return int(digits) if digits else 0
