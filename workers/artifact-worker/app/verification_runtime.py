"""合并声明式契约与陈述证据诊断，不把历史失败当作最终结论。"""

import re
from types import SimpleNamespace

from app.composer import resolve_artifact_title
from app.models import ArtifactSectionDraft
from app.node_executor import validate_resume_bullets
from app.output_contract import evaluate_output_contract
from app.statement_verification import verify_statement_support


def verify_draft(inputs, task, plan, llm_client):
    raw_sections = inputs["sections"]
    sections = [ArtifactSectionDraft.model_validate(item) for item in raw_sections]
    passages = inputs["material_passages"]
    draft = SimpleNamespace(result_title=resolve_artifact_title(task, plan), result_payload={
        "markdown": "\n\n".join(f"## {s.heading}\n\n{s.body}" for s in sections), "sections": raw_sections,
        "canonical_content_objects": [{"plain_text": p["content"]} for p in passages]})
    checks = evaluate_output_contract(draft, plan)
    failed = [check.detail for check in checks if check.status == "FAIL"]
    warnings = [check.detail for check in checks if check.status == "WARN"]
    affected = []
    required = plan.declared_output_contract.get("sections", {}).get("required_headings", [])
    missing = [heading for heading in required if heading not in {s.heading for s in sections}]
    rules = {rule["name"]: rule for rule in plan.declared_output_contract.get("rules", [])}
    backtrack = []
    for check in checks:
        if check.status != "FAIL":
            continue
        metadata = check.metadata
        rule = rules.get(metadata.get("rule_name"), {})
        index = metadata.get("section_index", rule.get("parameters", {}).get("section_index"))
        match = re.match(r"sections\.(\d+)", str(metadata.get("path") or ""))
        if index is None and match:
            index = int(match.group(1))
        if metadata.get("path") == "sections" and metadata.get("validator") in {"contains", "minItems"} and missing:
            affected.extend(missing)
        elif isinstance(index, int) and 0 <= index < len(sections):
            affected.append(sections[index].heading)
        elif metadata.get("unsupported_metrics"):
            unsupported = [re.sub(r"\s+", "", value).casefold() for value in metadata["unsupported_metrics"]]
            affected.extend(section.heading for section in sections
                if any(value in re.sub(r"\s+", "", section.body).casefold() for value in unsupported))
        elif metadata.get("missing_focus_points") and sections:
            affected.append(sections[0].heading)
        else:
            affected.extend(s.heading for s in sections)
        if metadata.get("backtrack_to"):
            backtrack.append(metadata["backtrack_to"])
    known = {p["title"] for p in passages} | {p["chunk_id"] for p in passages}
    for section in sections:
        if any(ref not in known for ref in section.source_refs):
            failed.append(f"章节 {section.heading} 引用了检索素材中没有的资料")
            affected.append(section.heading)
        if any(pattern.strip() and pattern in section.body
               for pattern in inputs["generation_context"].get("forbidden_patterns", [])):
            failed.append(f"章节 {section.heading} 包含明确禁止的内容")
            affected.append(section.heading)
    if "normalized_facts" in inputs and "bullets" in inputs:
        try:
            validate_resume_bullets({"sections": raw_sections, "bullets": inputs["bullets"]}, inputs)
        except ValueError as exc:
            failed.append(str(exc))
            affected.extend(s.heading for s in sections)
    density = plan.declared_output_contract.get("citation", {}).get("density", "PER_SECTION")
    evidence = verify_statement_support(raw_sections, passages, llm_client=llm_client,
        citation_density=density, section_materials=inputs.get("section_materials"))
    affected.extend(section["heading"] for section in evidence["sections"]
                    if section["support"] != "SUPPORTED" and density == "PER_SECTION")
    contract_failed = bool(failed)
    failed.extend(evidence["failed_checks"])
    warnings.extend(evidence["warnings"])
    return {"status": "FAIL" if failed else "WARN" if warnings else "PASS",
        "statement_verification_version": "artifact-statement-verification.v1", "verification_mode": "TYPED_GRAPH_STATEMENTS",
        "contract_status": "FAIL" if contract_failed else "PASS", "evidence_status": evidence["status"],
        "failed_checks": failed, "warnings": warnings, "failed_sections": list(dict.fromkeys(affected)),
        "backtrack_nodes": list(dict.fromkeys(backtrack)), "repair_rounds": [],
        "missing_sections": missing,
        "checks": [check.model_dump(mode="json") for check in checks],
        **{key: evidence[key] for key in ("sections", "citation_recall", "citation_precision", "llm_calls", "execution_traces")}}
