"""有界章节修复：只接受失败章节的真实模型改写，每轮重新校验。"""

from copy import deepcopy
from types import SimpleNamespace
import re

from app.node_contracts import STATE_FIELDS
from app.node_executor import NodeExecutionError, NodeExecutor, validate_resume_bullets
from app.sectioned_runtime import paragraph_texts


def _schema(properties, required=None):
    return {"type": "object", "additionalProperties": False, "properties": properties,
            "required": list(properties) if required is None else required}


def _merge_sections(original, replacements, outline):
    merged = [deepcopy(replacements.get(section["heading"], section)) for section in original]
    original_names = {section["heading"] for section in original}
    merged.extend(deepcopy(section) for heading, section in replacements.items() if heading not in original_names)
    order = {heading: index for index, heading in enumerate(outline)}
    return sorted(merged, key=lambda section: order.get(section["heading"], len(order)))


def repair_failed_sections(inputs, *, llm_client, verify, is_resume=False):
    state = deepcopy(inputs)
    rounds, traces = deepcopy(state["verification"].get("repair_rounds", [])), []
    calls = 0
    verification = state["verification"]
    if verification["status"] != "FAIL":
        return _result(state, "SKIPPED", "全部必需检查已通过，无需修复", rounds, traces, calls, is_resume)
    for round_no in range(len(rounds) + 1, 3):
        failed_checks = list(verification.get("failed_checks", []))
        headings = list(dict.fromkeys(verification.get("failed_sections", [])))
        if not headings:
            headings = [section["heading"] for section in state["sections"]]
        original = deepcopy(state["sections"])
        original_citations = {row["heading"]: row["chunk_ids"] for row in state.get("citation_assignments", [])}
        failed_sections = [section for section in original if section["heading"] in headings]
        failed_sections.extend({"heading": heading, "body": "", "source_refs": []}
            for heading in verification.get("missing_sections", [])
            if heading not in {section["heading"] for section in original})
        known = {section["heading"] for section in failed_sections}
        if not failed_sections:
            return _result(state, "FAILED", "没有可按声明修复的失败章节", rounds, traces, calls, is_resume)
        repair_bullets = is_resume and "简历亮点" in known
        material_assignments = [pack for pack in state["section_materials"] if pack["heading"] in known]
        allowed = {}
        for pack in material_assignments:
            for passage in pack["passages"]:
                allowed[passage["chunk_id"]] = max(allowed.get(passage["chunk_id"], 0), passage["selected_chars"])
        materials = [{**passage, "content": passage["content"][:allowed[passage["chunk_id"]]]}
            for passage in state["material_passages"]
            if passage["chunk_id"] in allowed and allowed[passage["chunk_id"]] > 0]
        request = {"failed_sections": failed_sections, "failed_checks": failed_checks,
            "material_passages": materials, "generation_context": state["generation_context"],
            "section_materials": material_assignments,
            "failed_diagnostics": [section for section in verification.get("sections", []) if section["heading"] in known]}
        paragraph_constraints = verification.get("paragraph_constraints", {})
        if paragraph_constraints:
            request["failed_paragraphs"] = [{"heading": section["heading"], "paragraph_index": index,
                "text": paragraph_texts(section["body"])[index]}
                for section in failed_sections for index in paragraph_constraints.get(section["heading"], [])]
        if is_resume:
            request.update(normalized_facts=state["normalized_facts"], bullets=state["bullets"])
        properties = {key: STATE_FIELDS[key] for key in ("material_passages", "generation_context", "section_materials")}
        properties["failed_diagnostics"] = {"type": "array", "items": {"type": "object"}, "maxItems": 80}
        if paragraph_constraints:
            properties["failed_paragraphs"] = {"type": "array", "items": {"type": "object"}, "maxItems": 160}
        requested_schema = deepcopy(STATE_FIELDS["sections"])
        requested_schema["items"]["properties"]["body"] = {"type": "string"}
        properties.update(failed_sections=requested_schema,
                          failed_checks={"type": "array", "items": {"type": "string"}})
        if is_resume:
            properties.update(normalized_facts=STATE_FIELDS["normalized_facts"], bullets=STATE_FIELDS["bullets"])
        output = {"sections": STATE_FIELDS["sections"]}
        if repair_bullets:
            output["bullets"] = STATE_FIELDS["bullets"]

        def guard(value, projected):
            returned = [section["heading"] for section in value["sections"]]
            if len(returned) != len(set(returned)) or set(returned) != known:
                raise ValueError("修复只能返回全部失败章节，不能遗漏或改写已通过章节")
            by_heading = {section["heading"]: section for section in value["sections"]}
            for section in original:
                positions = paragraph_constraints.get(section["heading"])
                if positions is None:
                    continue
                before = re.split(r"(\n[ \t]*\n)", section["body"])
                after = re.split(r"(\n[ \t]*\n)", by_heading[section["heading"]]["body"])
                if len(before) != len(after) or before[1::2] != after[1::2]:
                    raise ValueError("全文一致性修复必须保留原段落分隔与空行格式")
                paragraph_index = 0
                for offset in range(0, len(before), 2):
                    allowed = before[offset].strip() and paragraph_index in positions
                    if before[offset].strip():
                        paragraph_index += 1
                    if not allowed and before[offset] != after[offset]:
                        raise ValueError("全文一致性修复只能修改已判有问题的段落，其他段落必须逐字保留")
                if not by_heading[section["heading"]]["body"].strip():
                    raise ValueError("全文一致性修复只能修改已判有问题的段落，其他段落必须逐字保留")
            candidate = _merge_sections(original, by_heading, state["generation_context"].get("outline", []))
            if is_resume:
                validate_resume_bullets({"sections": candidate,
                    "bullets": value.get("bullets", state["bullets"])}, state)

        node = SimpleNamespace(node_id=f"section_repair_round_{round_no}", node_kind="LLM_STEP",
            prompt_recipe_id="node.section_repair.v1", input_schema=_schema(properties), output_schema=_schema(output))
        record = {"round": round_no, "failed_checks": failed_checks, "repaired_sections": [],
                  "backtrack_nodes": verification.get("backtrack_nodes", [])}
        try:
            result = NodeExecutor(llm_client, output_guards={"node.section_repair.v1": guard}).execute(node, request)
            trace = {**result.trace, "round": round_no}
            replacements = {section["heading"]: section for section in result.output["sections"]}
            state["sections"] = _merge_sections(original, replacements, state["generation_context"].get("outline", []))
            if repair_bullets:
                state["bullets"] = result.output["bullets"]
            record["repaired_sections"] = [section["heading"] for section in original
                if section["heading"] in replacements and replacements[section["heading"]] != section]
            record["repaired_sections"].extend(heading for heading in replacements
                if heading not in {section["heading"] for section in original})
        except NodeExecutionError as exc:
            trace = {**exc.trace, "round": round_no}
            record.update(status="NOT_RUN" if trace.get("llm_calls", 0) == 0 else "FAILED",
                          validation_errors=trace.get("validation_errors", []))
            rounds.append(record)
            traces.append(trace)
            calls += trace.get("llm_calls", 0)
            if trace.get("reason_code") in {"ARTIFACT_NODE_LLM_UNAVAILABLE", "ARTIFACT_NODE_LLM_CALL_FAILED",
                    "ARTIFACT_NODE_LLM_UNAVAILABLE_OR_BUDGET_EXHAUSTED"}:
                return _result(state, "NOT_RUN", "模型不可用、调用失败或预算耗尽，修复未完成", rounds, traces, calls, is_resume)
            continue
        traces.append(trace)
        calls += trace.get("llm_calls", 0)
        verification = verify(state)
        state["verification"] = verification
        record["repaired_sections"] = [section["heading"] for section in state["sections"]
            if section not in original]
        record["repaired_sections"].extend(row["heading"] for row in state.get("citation_assignments", [])
            if row["heading"] not in record["repaired_sections"]
            and row["chunk_ids"] != original_citations.get(row["heading"]))
        calls += verification.get("llm_calls", 0)
        traces.extend(verification.get("execution_traces", []))
        record.update(status=verification["status"], remaining_failed_checks=verification.get("failed_checks", []))
        rounds.append(record)
        if verification["status"] != "FAIL":
            return _result(state, "REPAIRED", "失败章节已改写并通过重新校验", rounds, traces, calls, is_resume)
    return _result(state, "FAILED", "两轮修复后仍有必需检查失败，拒绝提交", rounds, traces, calls, is_resume)


def _result(state, status, reason, rounds, traces, calls, is_resume):
    verification = deepcopy(state["verification"])
    verification["repair_rounds"] = deepcopy(rounds)
    result = {"sections": state["sections"], "verification": verification,
        "repair_trace": {"status": status, "reason": reason,
            "failed_checks": verification.get("failed_checks", []), "rounds": rounds,
            "llm_calls": calls, "execution_traces": traces}}
    if is_resume:
        result["bullets"] = state["bullets"]
    if "citation_assignments" in state:
        result.update({key: deepcopy(state[key]) for key in ("citation_assignments", "consistency_trace")})
    return result
