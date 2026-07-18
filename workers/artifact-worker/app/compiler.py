from __future__ import annotations

from collections import deque

from app.action_resolver import resolve_requested_action
from app.capability_resolver import resolve_capability_bindings
from app.content_runtime import build_content_acquisition_plan
from app.intent_compiler import compile_execution_spec
from app.models import (
    ArtifactExecutionPlan,
    ArtifactRuntimePlan,
    ArtifactSkillNodePlan,
    ArtifactTaskInput,
)
from app.policy import (
    evaluate_approval_gate,
    evaluate_capability_union_policy,
    evaluate_evidence_gate,
    evaluate_writeback_gate,
    resolve_writeback_capabilities,
)
from app.runtime_node_registry import ensure_runtime_node_skill_registered
from app.registry import (
    resolve_prompt_recipe,
    resolve_production_action,
    resolve_skill_definition,
    resolve_skill_graph,
    resolve_style_profile,
)


def build_execution_plan(task_input: ArtifactTaskInput) -> ArtifactExecutionPlan:
    execution_spec = compile_execution_spec(task_input)
    action_resolution = resolve_requested_action(task_input)
    action_key = action_resolution.resolved_action_key
    action = resolve_production_action(action_key)

    style_profile_key = (
        task_input.input_payload.style_profile_key.strip().upper()
        or action.default_style_profile_key
    )
    prompt_recipe_id = (
        task_input.input_payload.prompt_recipe_id.strip()
        or action.default_prompt_recipe_id
    )
    style_profile = resolve_style_profile(style_profile_key)
    prompt_recipe = resolve_prompt_recipe(prompt_recipe_id)
    skill_graph = resolve_skill_graph(action.default_skill_graph_key)
    content_acquisition_plan = build_content_acquisition_plan(task_input)
    writeback_capabilities = resolve_writeback_capabilities(
        task_input.input_payload.writeback_mode
    )

    if skill_graph.action_type not in {action.action_key, "GENERIC"}:
        raise ValueError("schema gate rejected plan: action-skill-graph mismatch")
    compatible_action_keys = {action.action_key}
    if action.template_action_key:
        compatible_action_keys.add(action.template_action_key)
    if (
        "*" not in prompt_recipe.supported_actions
        and compatible_action_keys.isdisjoint(set(prompt_recipe.supported_actions))
    ):
        raise ValueError("schema gate rejected plan: prompt-recipe mismatch")
    if action.required_evidence_level != "NONE" and not content_acquisition_plan.source_plans:
        raise ValueError("schema gate rejected plan: source scope must not be empty")

    node_sequence = _resolve_node_sequence(skill_graph.graph_key)
    lazy_loaded_capabilities = _collect_required_capabilities(
        content_acquisition_plan.required_capabilities,
        writeback_capabilities,
        [node.skill_key for node in node_sequence],
        task_input.input_payload.requested_capabilities,
    )
    deferred_capabilities = _collect_deferred_capabilities(
        action.supported_capabilities,
        lazy_loaded_capabilities,
    )
    preliminary_resolved_bindings = resolve_capability_bindings(
        lazy_loaded_capabilities,
        task_input=task_input,
        action_key=action_key,
        skill_graph_key=skill_graph.graph_key,
    )
    capability_policy = evaluate_capability_union_policy(
        action,
        lazy_loaded_capabilities,
        task_input.workspace_id,
        requested_writeback_mode=task_input.input_payload.writeback_mode,
        resolved_bindings=preliminary_resolved_bindings,
    )
    if capability_policy.decision != "ALLOW":
        raise ValueError(
            "capability union policy rejected plan: "
            f"{capability_policy.reason_code}"
        )
    approval_gate = evaluate_approval_gate(lazy_loaded_capabilities)
    evidence_gate = evaluate_evidence_gate(
        action,
        len(content_acquisition_plan.source_plans),
        style_profile.citation_density.upper(),
    )
    if evidence_gate.decision == "DENY":
        raise ValueError(
            "evidence gate rejected plan: "
            f"{evidence_gate.reason_code}"
        )
    writeback_gate = evaluate_writeback_gate(
        action,
        task_input.input_payload.writeback_mode,
    )

    schema_gate_rules = [
        "Schema-gated execution plan must resolve known action, style, and skill graph.",
        "Schema-gated execution plan must resolve a prompt recipe compatible with the selected action.",
        "Schema-gated execution plan must only activate graph nodes backed by registered runtime executors.",
        "Schema-gated execution plan must preserve skill dependency ordering before execution.",
        *skill_graph.schema_contract,
    ]
    notes: list[str] = [
        f"style tone: {style_profile.tone}",
        f"structure mode: {style_profile.structure_mode}",
        f"prompt recipe: {prompt_recipe.recipe_id}",
    ]
    if task_input.control_pack.style_constraints:
        notes.append(
            "style constraints: "
            + "; ".join(task_input.control_pack.style_constraints)
        )
    if task_input.control_pack.structure_constraints:
        notes.append(
            "structure constraints: "
            + "; ".join(task_input.control_pack.structure_constraints)
        )
    if task_input.control_pack.forbidden_patterns:
        notes.append(
            "forbidden patterns: "
            + "; ".join(task_input.control_pack.forbidden_patterns)
        )
    runtime_plan = _build_runtime_plan(
        graph_key=skill_graph.graph_key,
        graph_name=skill_graph.graph_name,
        node_sequence=node_sequence,
        allowed_capabilities=lazy_loaded_capabilities,
        output_contract=action.output_sections,
    )

    return ArtifactExecutionPlan(
        plan_id=f"plan-{task_input.task_id}",
        skill_key=execution_spec.skill_key,
        action_key=action_key,
        action_display_name=action.display_name,
        artifact_type=action.artifact_type,
        action_origin=action.action_origin,
        template_action_key=action.template_action_key,
        action_resolution=action_resolution,
        execution_spec=execution_spec,
        style_profile_key=style_profile_key,
        style_profile_name=style_profile.profile_name,
        skill_graph_key=skill_graph.graph_key,
        runtime_plan=runtime_plan,
        prompt_recipe=prompt_recipe,
        content_acquisition_plan=content_acquisition_plan,
        required_capabilities=lazy_loaded_capabilities,
        lazy_loaded_capabilities=lazy_loaded_capabilities,
        deferred_capabilities=deferred_capabilities,
        outline=action.output_sections,
        schema_gate_status="PASSED",
        schema_gate_rules=schema_gate_rules,
        capability_union_policy=capability_policy,
        approval_gate=approval_gate,
        evidence_gate=evidence_gate,
        writeback_gate=writeback_gate,
        node_sequence=node_sequence,
        output_contract=action.output_sections,
        required_phrases=action.required_phrases,
        notes=notes,
    )


def _build_runtime_plan(
    *,
    graph_key: str,
    graph_name: str,
    node_sequence: list[ArtifactSkillNodePlan],
    allowed_capabilities: list[str],
    output_contract: list[str],
) -> ArtifactRuntimePlan:
    verifier_policies: list[str] = []
    repair_policies: list[str] = []
    for node in node_sequence:
        skill_definition = resolve_skill_definition(node.skill_key)
        verifier_policies.extend(skill_definition.verifier_policy)
        repair_policies.extend(skill_definition.repair_policy)
    return ArtifactRuntimePlan(
        graph_key=graph_key,
        graph_name=graph_name,
        activated_nodes=[node.skill_key for node in node_sequence],
        allowed_capabilities=list(allowed_capabilities),
        verifier_policies=list(dict.fromkeys(verifier_policies)),
        repair_policies=list(dict.fromkeys(repair_policies)),
        output_contract=list(output_contract),
        notes=[
            "runtime plan derived from skill graph template and node dependency ordering",
            "allowed capabilities resolved after schema gate and capability union policy evaluation",
        ],
    )


def _resolve_node_sequence(graph_key: str) -> list[ArtifactSkillNodePlan]:
    graph = resolve_skill_graph(graph_key)
    node_map = {node.node_id: node for node in graph.nodes}
    indegree = {node.node_id: 0 for node in graph.nodes}
    adjacency: dict[str, list[str]] = {node.node_id: [] for node in graph.nodes}

    for edge in graph.edges:
        if edge.edge_type != "PREREQUISITE":
            continue
        if edge.from_node not in node_map or edge.to_node not in node_map:
            raise ValueError("schema gate rejected plan: graph edge references missing node")
        indegree[edge.to_node] += 1
        adjacency[edge.from_node].append(edge.to_node)

    queue = deque(node.node_id for node in graph.nodes if indegree[node.node_id] == 0)
    ordered_nodes: list[ArtifactSkillNodePlan] = []

    while queue:
        node_id = queue.popleft()
        node = node_map[node_id]
        skill_definition = resolve_skill_definition(node.skill_key)
        ensure_runtime_node_skill_registered(
            skill_definition,
            graph_key=graph.graph_key,
            node_skill_key=node.skill_key,
            error_prefix="schema gate rejected plan",
        )
        ordered_nodes.append(
            ArtifactSkillNodePlan(
                node_id=node.node_id,
                skill_key=node.skill_key,
                purpose=node.purpose,
            )
        )
        for neighbor in adjacency[node_id]:
            indegree[neighbor] -= 1
            if indegree[neighbor] == 0:
                queue.append(neighbor)

    if len(ordered_nodes) != len(graph.nodes):
        raise ValueError("schema gate rejected plan: cyclic skill dependency graph")
    return ordered_nodes


def _collect_required_capabilities(
    acquisition_capabilities: list[str],
    writeback_capabilities: list[str],
    skill_keys: list[str],
    requested_capabilities: list[str],
) -> list[str]:
    capability_names: list[str] = []
    skill_capabilities: list[str] = []
    for skill_key in skill_keys:
        skill_capabilities.extend(resolve_skill_definition(skill_key).required_capabilities)
    if "READ_WORKSPACE_DOC" in skill_capabilities:
        capability_names.append("READ_WORKSPACE_DOC")
        skill_capabilities = [
            capability_name
            for capability_name in skill_capabilities
            if capability_name != "READ_WORKSPACE_DOC"
        ]
    capability_names.extend(acquisition_capabilities)
    capability_names.extend(writeback_capabilities)
    capability_names.extend(skill_capabilities)
    capability_names.extend(requested_capabilities)
    return list(dict.fromkeys(capability_names))


def _collect_deferred_capabilities(
    action_capabilities: list[str],
    lazy_loaded_capabilities: list[str],
) -> list[str]:
    lazy_loaded = set(lazy_loaded_capabilities)
    return [capability for capability in action_capabilities if capability not in lazy_loaded]
