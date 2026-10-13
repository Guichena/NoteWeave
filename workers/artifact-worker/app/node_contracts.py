"""类型化节点契约与旧节点的显式迁移，不提供内容模板。"""

from copy import deepcopy

from jsonschema import Draft202012Validator


NODE_KINDS = frozenset({"LLM_STEP", "RETRIEVE", "VERIFY", "REPAIR", "TOOL"})
_CONTRACT_FIELDS = {"node_kind", "prompt_recipe_id", "input_schema", "output_schema"}
_TEXT = {"type": "string", "minLength": 1, "pattern": r"\S"}
_TEXTS = {"type": "array", "items": _TEXT, "maxItems": 80}
_PASSAGE = {
    "type": "object", "additionalProperties": False,
    "properties": {
        "chunk_id": _TEXT, "title": _TEXT, "content": _TEXT,
        "source_id": {"type": "string"}, "source_snapshot_id": {"type": "string"},
        "chunk_no": {"type": "integer", "minimum": 0}, "location": {"type": "string"},
        "score": {"type": "number"}, "kind": {"type": "string"},
        "origin": {"type": "string"}, "truncated": {"type": "boolean"},
    }, "required": ["chunk_id", "title", "content"],
}
_PASSAGES = {"type": "array", "items": _PASSAGE, "maxItems": 1600}
_SECTION = {
    "type": "object", "additionalProperties": False,
    "properties": {"heading": _TEXT, "body": _TEXT, "source_refs": _TEXTS},
    "required": ["heading", "body", "source_refs"],
}
_SECTIONS = {"type": "array", "items": _SECTION, "minItems": 1, "maxItems": 80}
_FACT = {
    "type": "object", "additionalProperties": False,
    "properties": {"fact_id": _TEXT, "text": _TEXT, "chunk_id": _TEXT, "quote": _TEXT},
    "required": ["fact_id", "text", "chunk_id", "quote"],
}
_NORMALIZED_FACT = deepcopy(_FACT)
_NORMALIZED_FACT["properties"]["metrics"] = _TEXTS
_NORMALIZED_FACT["required"].append("metrics")
_BULLET = {
    "type": "object", "additionalProperties": False,
    "properties": {"text": _TEXT, "fact_ids": {"type": "array", "items": _TEXT,
        "minItems": 1, "maxItems": 80, "uniqueItems": True}},
    "required": ["text", "fact_ids"],
}
_CONTEXT = {
    "type": "object", "additionalProperties": False,
    "properties": {
        **{name: {"type": "string"} for name in (
            "goal", "audience", "output_language", "user_requirement", "generation_brief")},
        **{name: _TEXTS for name in (
            "focus_points", "style_directives", "constraints", "outline", "style_constraints",
            "structure_constraints", "forbidden_patterns", "required_phrases")},
        # 此处仅接收宿主显式投影的配方与契约，不接收完整输入或权限数据。
        "prompt_recipe": {"type": "object"}, "output_contract": {"type": "object"},
    },
}
_SECTION_MATERIALS = {
    "type": "array", "maxItems": 80,
    "items": {"type": "object", "additionalProperties": False, "properties": {
        "heading": _TEXT, "passages": {"type": "array", "maxItems": 20,
            "items": {"type": "object", "additionalProperties": False,
                "properties": {"chunk_id": _TEXT, "title": _TEXT,
                    "selected_chars": {"type": "integer", "minimum": 0},
                    "truncated": {"type": "boolean"}},
                "required": ["chunk_id", "title", "selected_chars", "truncated"]}},
        "query": {"type": "string"}, "method": {"type": "string"},
        "budget_chars": {"type": "integer", "minimum": 0},
        "used_chars": {"type": "integer", "minimum": 0}, "degradation_reasons": _TEXTS,
    }, "required": ["heading", "passages"]},
}
STATE_FIELDS = {
    "generation_context": _CONTEXT, "material_passages": _PASSAGES,
    "section_materials": _SECTION_MATERIALS,
    "facts": {"type": "array", "items": _FACT, "minItems": 1, "maxItems": 160},
    "normalized_facts": {"type": "array", "items": _NORMALIZED_FACT, "minItems": 1, "maxItems": 160},
    "bullets": {"type": "array", "items": _BULLET, "minItems": 1, "maxItems": 20},
    "sections": _SECTIONS, "verification": {"type": "object"},
    "retrieval_trace": {"type": "object"},
    "repair_trace": {"type": "object", "additionalProperties": False,
        "properties": {"status": {"enum": ["SKIPPED", "NOT_RUN"]},
            "reason": {"type": "string"}, "failed_checks": {"type": "array"}},
        "required": ["status", "reason", "failed_checks"]},
}


def _schema(keys):
    return {"type": "object", "additionalProperties": False,
        "properties": {key: deepcopy(STATE_FIELDS[key]) for key in keys}, "required": list(keys)}


_LEGACY_NODE_BINDINGS = {
    "workspace_material_digest": ("RETRIEVE", ["generation_context"],
        ["material_passages", "section_materials", "retrieval_trace"]),
    "resume_highlight_extractor": ("LLM_STEP", ["material_passages", "generation_context"], ["facts"]),
    "resume_impact_normalizer": ("LLM_STEP", ["facts", "material_passages", "generation_context"], ["normalized_facts"]),
    "resume_bullet_writer": ("LLM_STEP", ["normalized_facts", "material_passages", "generation_context"], ["sections", "bullets"]),
    "resume_verifier": ("VERIFY", ["sections", "bullets", "normalized_facts", "material_passages", "generation_context"], ["verification"]),
    "resume_local_repair": ("REPAIR", ["sections", "verification", "generation_context"], ["sections", "repair_trace"]),
    "generic_section_writer": ("LLM_STEP", ["material_passages", "section_materials", "generation_context"], ["sections"]),
    "quiz_designer": ("LLM_STEP", ["material_passages", "section_materials", "generation_context"], ["sections"]),
    "quiz_difficulty_normalizer": ("LLM_STEP", ["sections", "material_passages", "generation_context"], ["sections"]),
    "wiki_structure_enforcer": ("LLM_STEP", ["sections", "material_passages", "generation_context"], ["sections"]),
    "style_polisher": ("LLM_STEP", ["sections", "material_passages", "generation_context"], ["sections"]),
    "evidence_guard": ("VERIFY", ["sections", "material_passages", "generation_context"], ["verification"]),
    # 专用视频图由冻结证据执行入口处理，不转为文本生成图。
    "frozen_video_deck_layout": ("TOOL", ["generation_context"], []),
    "original_frame_pptx_renderer": ("TOOL", ["generation_context"], []),
    "frozen_video_deriver": ("TOOL", ["generation_context"], []),
}


def node_contract_for_skill(skill_key, runtime_skill_key=""):
    """只为注册过的旧执行技能提供明确契约，未知技能不能获得默认权限。"""
    runtime_key = runtime_skill_key or skill_key
    binding = _LEGACY_NODE_BINDINGS.get(runtime_key)
    if binding is None:
        raise ValueError(f"没有注册节点执行契约：{runtime_key}")
    kind, input_keys, output_keys = binding
    return {"node_kind": kind, "prompt_recipe_id": f"node.{runtime_key}.v1",
        "input_schema": _schema(input_keys), "output_schema": _schema(output_keys)}


def hydrate_legacy_node(values):
    """迁移旧图和检查点的节点字段，保留原节点标识与自定义技能绑定。"""
    if not isinstance(values, dict):
        return values
    result = deepcopy(values)
    present = _CONTRACT_FIELDS & set(result)
    if not present:
        skill_key = str(result.get("skill_key") or "")
        runtime_key = skill_key
        if skill_key not in _LEGACY_NODE_BINDINGS:
            from app.registry import resolve_skill_definition
            from app.runtime_node_registry import resolve_runtime_node_skill_key
            runtime_key = resolve_runtime_node_skill_key(resolve_skill_definition(skill_key))
        result.update(node_contract_for_skill(skill_key, runtime_key))
    elif present != _CONTRACT_FIELDS:
        raise ValueError("节点类型、配方和输入输出 Schema 必须完整声明")
    validate_node_contract(result)
    return result


def _reject_references(value, depth=0):
    if depth > 64:
        raise ValueError("节点 Schema 嵌套过深")
    if isinstance(value, dict):
        if {"$ref", "$dynamicRef"} & set(value):
            raise ValueError("节点 Schema 不允许外部或递归引用")
        for item in value.values():
            _reject_references(item, depth + 1)
    elif isinstance(value, list):
        for item in value:
            _reject_references(item, depth + 1)


def validate_node_contract(node):
    if node.get("node_kind") not in NODE_KINDS:
        raise ValueError("节点执行类型未注册")
    recipe = node.get("prompt_recipe_id")
    if not isinstance(recipe, str) or not recipe.startswith("node.") or not recipe.endswith(".v1"):
        raise ValueError("节点提示词配方未注册")
    runtime_key = recipe[len("node."):-len(".v1")]
    expected = node_contract_for_skill(runtime_key)
    if node["node_kind"] != expected["node_kind"]:
        raise ValueError("节点配方与执行类型不一致")
    for field in ("input_schema", "output_schema"):
        schema = node.get(field)
        if not isinstance(schema, dict) or schema.get("type") != "object" \
                or schema.get("additionalProperties") is not False \
                or not isinstance(schema.get("properties"), dict):
            raise ValueError("节点输入输出必须声明禁止未知字段的对象 Schema")
        if not set(schema["properties"]) <= set(STATE_FIELDS):
            raise ValueError("节点 Schema 引用了未注册的图状态字段")
        if set(schema["properties"]) != set(expected[field]["properties"]) \
                or not set(expected[field]["required"]) <= set(schema.get("required", [])):
            raise ValueError("节点 Schema 的读写字段与注册配方不一致")
        for key, base_schema in expected[field]["properties"].items():
            declared_schema = schema["properties"][key]
            if declared_schema == base_schema:
                continue
            # 定制字段只能叠加约束；完整基础 Schema 必须作为 allOf 的独立项保留。
            if not isinstance(declared_schema, dict) \
                    or not isinstance(declared_schema.get("allOf"), list) \
                    or base_schema not in declared_schema["allOf"]:
                raise ValueError(f"节点状态字段 {key} 的基础 Schema 不允许削弱")
        _reject_references(schema)
        Draft202012Validator.check_schema(schema)
