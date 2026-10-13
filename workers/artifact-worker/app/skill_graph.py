"""按图声明执行节点；正文仅由真实模型步骤生成，不提供内容模板或数量补齐。"""

from copy import deepcopy
import json

from app.content_runtime import build_canonical_content_objects
from app.models import ArtifactSectionDraft, ArtifactSkillNodePlan, ArtifactSkillNodeTrace, CanonicalContentObject
from app.node_contracts import node_contract_for_skill
from app.node_executor import NodeExecutor, NodeExecutionError
from app.repair_runtime import repair_failed_sections
from app.verification_runtime import verify_draft
from app.retrieval_runtime import execute_retrieve_node
from app.registry import resolve_skill_definition
from app.runtime_node_registry import ensure_runtime_node_contract_registered


def execute_skill_graph(task_input, plan, *, llm_client=None, canonical_content_objects=None, passage_search=None):
    content_objects = canonical_content_objects if canonical_content_objects is not None else build_canonical_content_objects(task_input)
    state = {"generation_context": _generation_context(task_input, plan)}
    traces = []
    verification_cache = {}
    evidence_traces = []
    repair_history = []

    def checked(inputs):
        key = json.dumps({name: inputs.get(name) for name in (
            "sections", "material_passages", "section_materials", "normalized_facts", "bullets")},
            sort_keys=True, ensure_ascii=False)
        if key in verification_cache:
            return {**deepcopy(verification_cache[key]), "llm_calls": 0, "execution_traces": [], "cached": True,
                    "repair_rounds": deepcopy(repair_history)}
        verdict = verify_draft(inputs, task_input, plan, llm_client)
        verification_cache[key] = deepcopy(verdict)
        evidence_traces.extend(verdict["execution_traces"])
        verdict["repair_rounds"] = deepcopy(repair_history)
        return verdict

    def retrieve(inputs, node):
        packs, passages, trace, _ = execute_retrieve_node(task_input, plan, content_objects, passage_search)
        for passage in passages:
            if passage.get("origin") != "HOST_FROZEN_CHUNK" or any(
                    passage["title"] == obj.title and passage["content"] in obj.plain_text for obj in content_objects):
                continue
            content_objects.append(CanonicalContentObject(
                cco_id=f"retrieved-{passage['chunk_id']}", kind="RETRIEVED_PASSAGE", title=passage["title"],
                plain_text=passage["content"], metadata={"chunk_id": passage["chunk_id"],
                    "source_snapshot_id": passage["source_snapshot_id"], "location": passage["location"]},
                source_trace=[f"workspace:{task_input.workspace_id}", f"source:{passage['source_id']}"]))
        # 正文只在公共片段表出现一次，各章节持有标识和自己的可用长度。
        assigned = [{key: value for key, value in pack.items() if key != "passages"} | {
            "passages": [{"chunk_id": item["chunk_id"], "title": item["title"],
                "selected_chars": len(item["content"]), "truncated": item["truncated"]}
                for item in pack["passages"]]} for pack in packs]
        return {"material_passages": passages, "section_materials": assigned, "retrieval_trace": trace}

    def verify(inputs, node):
        return {"verification": checked(inputs)}

    def repair(inputs, node):
        scoped = {**inputs, "verification": {**inputs["verification"], "repair_rounds": deepcopy(repair_history)}}
        output = repair_failed_sections(scoped, llm_client=llm_client, verify=checked,
                                       is_resume="normalized_facts" in inputs)
        repair_history[:] = deepcopy(output["repair_trace"]["rounds"])
        return output

    def tool(inputs, node):
        # 专用视频工具在冻结入口运行，通用图不能借工具节点绕过权限门控。
        raise ValueError(f"通用产物图未绑定可执行工具：{node.skill_key}")

    executor = NodeExecutor(llm_client, handlers={"RETRIEVE": retrieve, "VERIFY": verify, "REPAIR": repair, "TOOL": tool})
    def run_node(node):
        prior_rounds = len(repair_history)
        ensure_runtime_node_contract_registered(node, resolve_skill_definition(node.skill_key),
            error_prefix="执行节点声明无效")
        try:
            result = executor.execute(node, state)
        except NodeExecutionError as exc:
            exc.trace["completed_nodes"] = [trace.model_dump(mode="json") for trace in traces]
            raise
        state.update(result.output)
        status, checks = _verification_state(node, result.output)
        trace = result.trace
        if node.node_kind in {"VERIFY", "REPAIR"}:
            diagnostic = result.output["verification"] if node.node_kind == "VERIFY" else result.output["repair_trace"]
            trace["llm_calls"] = diagnostic.get("llm_calls", 0)
            trace["steps"] = diagnostic.get("execution_traces", [])
            trace["validation_errors"] = [error for step in trace["steps"]
                for error in step.get("validation_errors", [])]
            if trace["llm_calls"]:
                trace["purpose"] = "artifact.node.statement_support_judge" if node.node_kind == "VERIFY" else "artifact.node.section_repair"
        traces.append(ArtifactSkillNodeTrace(node_id=node.node_id, skill_key=node.skill_key,
            node_kind=node.node_kind, purpose=trace.get("purpose", ""), prompt_recipe_id=node.prompt_recipe_id,
            llm_calls=trace.get("llm_calls", 0), validation_errors=trace.get("validation_errors", []),
            input_fields=trace.get("input_fields", []), output_fields=trace.get("output_fields", []),
            execution_trace=trace, output_summary=f"节点 {node.node_id} 已按声明执行 {node.node_kind}",
            verification_status=status, verification_checks=checks,
            repaired=node.node_kind == "REPAIR" and any(
                row.get("repaired_sections") for row in result.output["repair_trace"].get("rounds", [])[prior_rounds:]),
            repair_actions=[f"章节修复完成：{heading}" for row in
                result.output.get("repair_trace", {}).get("rounds", [])[prior_rounds:]
                for heading in row.get("repaired_sections", [])]))

    for node in plan.node_sequence:
        run_node(node)

    sections = [ArtifactSectionDraft.model_validate(item) for item in state.get("sections", [])]
    verification = state.get("verification", {})
    if sections:
        final_check = checked(state)
        final_check["repair_rounds"] = state.get("repair_trace", {}).get("rounds", [])
        verification = final_check
        state["verification"] = verification
        if final_check.get("llm_calls", 0):
            skill = "resume_verifier" if "normalized_facts" in state else "evidence_guard"
            declaration = node_contract_for_skill(skill)
            traces.append(ArtifactSkillNodeTrace(node_id="final_verify", skill_key=skill,
                node_kind="VERIFY", prompt_recipe_id=declaration["prompt_recipe_id"],
                purpose="artifact.node.statement_support_judge", llm_calls=final_check["llm_calls"],
                input_fields=list(declaration["input_schema"]["properties"]), output_fields=["verification"],
                output_summary="后置变换的最终正文已重新执行证据判断", verification_status=final_check["status"],
                execution_trace={"status": "COMPLETED", "steps": final_check["execution_traces"]}))
        if final_check["status"] == "FAIL" and len(final_check["repair_rounds"]) < 2 \
                and state.get("repair_trace", {}).get("status") != "NOT_RUN":
            skill = "resume_local_repair" if "normalized_facts" in state else "generic_section_repair"
            final_node = ArtifactSkillNodePlan(node_id="final_repair", skill_key=skill,
                purpose="对后置变换的失败章节执行剩余修复轮次", **node_contract_for_skill(skill))
            run_node(final_node)
            sections = [ArtifactSectionDraft.model_validate(item) for item in state["sections"]]
            verification = checked(state)
            verification["repair_rounds"] = state["repair_trace"]["rounds"]
            state["verification"] = verification
    if not sections or verification.get("status") == "FAIL":
        failure = {"status": "FAILED", "reason_code": "ARTIFACT_NODE_CONTRACT_FAILED",
            "failed_checks": verification.get("failed_checks", []),
            "verification": verification, "repair_trace": state.get("repair_trace", {}),
            "completed_nodes": [trace.model_dump(mode="json") for trace in traces]}
        raise NodeExecutionError("产物节点契约检查未通过，未执行正文补齐", failure)
    # 最后一项生成步骤可能改变正文；最终门控由 runner 针对真实产物再次执行。
    purposes = [trace.purpose for trace in traces if trace.purpose]
    total_calls = sum(trace.llm_calls for trace in traces)
    verification["llm_calls"] = sum(trace.get("llm_calls", 0) for trace in evidence_traces)
    generation_trace = {"mode": "LLM_NODE_GRAPH", "provider": getattr(llm_client, "provider_name", "unknown"),
        "model": getattr(llm_client, "model_name", "unknown"), "attempted": bool(purposes), "applied": True,
        "generated_section_count": len(sections), "source_count": len(state.get("material_passages", [])),
        "llm_calls": total_calls, "purposes": purposes,
        "retrieval": state.get("retrieval_trace", {}),
        "state": {key: state[key] for key in ("facts", "normalized_facts", "bullets", "verification", "repair_trace") if key in state}}
    return sections, traces, generation_trace


def _generation_context(task, plan):
    spec = plan.execution_spec
    recipe = plan.prompt_recipe
    return {"goal": spec.goal, "audience": spec.audience, "focus_points": spec.focus_points,
        "style_directives": spec.style_directives, "constraints": spec.constraints,
        "output_language": spec.output_language, "outline": plan.outline,
        "user_requirement": task.input_payload.user_requirement, "generation_brief": spec.generation_brief,
        "style_constraints": task.control_pack.style_constraints,
        "structure_constraints": task.control_pack.structure_constraints,
        "forbidden_patterns": task.control_pack.forbidden_patterns,
        "output_contract": plan.declared_output_contract, "required_phrases": plan.required_phrases,
        "prompt_recipe": {"recipe_id": recipe.recipe_id, "generation_mode": recipe.generation_mode,
            "system_intent": recipe.system_intent, "section_guidance": recipe.section_guidance,
            "node_guidance": recipe.node_guidance, "citation_policy": recipe.citation_policy}}


def _markdown(sections):
    return "\n\n".join(f"## {section.heading}\n\n{section.body}" for section in sections)


def _verification_state(node, output):
    if node.node_kind == "VERIFY":
        verdict = output["verification"]
        checks = [check.get("detail", "") for check in verdict.get("checks", []) if check.get("detail")]
        return verdict["status"], checks
    if node.node_kind == "REPAIR":
        return output["repair_trace"]["status"], [output["repair_trace"]["reason"]]
    if node.node_kind == "RETRIEVE":
        reasons = output["retrieval_trace"].get("degradation_reasons", [])
        return "WARN" if reasons else "PASS", ["冻结资料范围", "章节正文预算", "片段去重"]
    return "PASS", ["声明输入字段隔离", "输出 Schema 与注册规则校验通过"]
