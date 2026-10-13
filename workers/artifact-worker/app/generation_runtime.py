from __future__ import annotations

from app.json_repair import parse_json_payload
from app.llm_client import LlmClient
from app.models import (
    ArtifactExecutionPlan,
    ArtifactSectionDraft,
    ArtifactTaskInput,
    CanonicalContentObject,
)


class ArtifactConfigurationRequiredError(RuntimeError):
    error_code = "CONFIGURATION_REQUIRED"


def generate_artifact_sections(
    *,
    task_input: ArtifactTaskInput,
    plan: ArtifactExecutionPlan,
    canonical_content_objects: list[CanonicalContentObject],
    llm_client: LlmClient | None,
    section_materials: list[dict[str, object]] | None = None,
    retrieved_sources: list[dict[str, object]] | None = None,
    retrieval_trace: dict[str, object] | None = None,
) -> tuple[list[ArtifactSectionDraft], dict[str, object]]:
    if llm_client is None:
        raise ArtifactConfigurationRequiredError(
            "Artifact LLM is not configured; controlled artifact generation cannot start"
        )

    if section_materials is None:
        from app.retrieval_runtime import execute_retrieve_node
        section_materials, retrieved_sources, retrieval_trace, _ = execute_retrieve_node(
            task_input, plan, canonical_content_objects)
    source_payload = retrieved_sources or []
    raw_response = llm_client.complete_json(
        "artifact.generate",
        {
            "skill_key": task_input.input_payload.skill_key,
            "user_requirement": task_input.input_payload.user_requirement,
            "goal": plan.execution_spec.goal,
            "audience": plan.execution_spec.audience,
            "focus_points": plan.execution_spec.focus_points,
            "style_directives": plan.execution_spec.style_directives,
            "constraints": plan.execution_spec.constraints,
            "outline": plan.outline,
            "required_phrases": plan.required_phrases,
            "output_contract": plan.declared_output_contract,
            "output_language": plan.execution_spec.output_language,
            "prompt_recipe": plan.prompt_recipe.model_dump(mode="json"),
            "control_pack": {
                "style_constraints": task_input.control_pack.style_constraints,
                "structure_constraints": task_input.control_pack.structure_constraints,
                "terminology_policy": task_input.control_pack.terminology_policy,
                "forbidden_patterns": task_input.control_pack.forbidden_patterns,
                "evidence_policy": task_input.control_pack.evidence_policy,
            },
            "sources": source_payload,
            "section_materials": [{k: v for k, v in pack.items() if k != "passages"} | {
                "passages": [{"chunk_id": p["chunk_id"], "title": p["title"],
                    "selected_chars": len(p["content"]), "truncated": p["truncated"]} for p in pack["passages"]]
                } for pack in section_materials],
            "material_policy": "Each section must use only its assigned chunk_ids from sources, within selected_chars. Cite their supplied source titles.",
        },
    )
    parsed = parse_json_payload(raw_response)
    generated_sections = _parse_sections(
        parsed,
        allowed_source_titles={item["title"] for item in source_payload},
    )
    if not generated_sections:
        raise ArtifactConfigurationRequiredError(
            "Artifact LLM returned no usable content; extractive fallback is disabled"
        )
    trace = _trace(
        mode="LLM_GENERATION",
        provider=getattr(llm_client, "provider_name", "unknown"),
        model=getattr(llm_client, "model_name", "unknown"),
        attempted=True,
        applied=True,
        generated_section_count=len(generated_sections),
        source_count=len(source_payload),
    )
    trace["retrieval"] = retrieval_trace or {}
    return generated_sections, trace


def _parse_sections(
    parsed: object,
    *,
    allowed_source_titles: set[str],
) -> list[ArtifactSectionDraft]:
    if not isinstance(parsed, dict) or not isinstance(parsed.get("sections"), list):
        return []
    sections: list[ArtifactSectionDraft] = []
    for raw_section in parsed["sections"]:
        if not isinstance(raw_section, dict):
            continue
        heading = str(raw_section.get("heading") or "").strip()
        body = str(raw_section.get("body") or "").strip()
        if not heading or not body:
            continue
        raw_refs = raw_section.get("source_refs") or []
        refs = (
            [
                str(value).strip()
                for value in raw_refs
                if str(value).strip() in allowed_source_titles
            ]
            if isinstance(raw_refs, list)
            else []
        )
        sections.append(
            ArtifactSectionDraft(
                heading=heading,
                body=body,
                source_refs=list(dict.fromkeys(refs)),
            )
        )
    return sections


def _trace(
    *,
    mode: str,
    provider: str = "",
    model: str = "",
    attempted: bool,
    applied: bool,
    fallback_reason: str = "",
    generated_section_count: int = 0,
    source_count: int = 0,
) -> dict[str, object]:
    return {
        "mode": mode,
        "provider": provider,
        "model": model,
        "attempted": attempted,
        "applied": applied,
        "fallback_reason": fallback_reason,
        "generated_section_count": generated_section_count,
        "source_count": source_count,
    }
