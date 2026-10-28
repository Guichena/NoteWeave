"""模型规划骨架后，确定性核对素材编号和数量。"""

from app.pipeline.schemas import schema_errors
from app.pipeline.stages import call_model


def plan(task, skill, options, evidence, client):
    text = {"type": "string", "minLength": 1}
    schema = {"type": "object", "properties": {"title": text,
        "units": {"type": "array", "minItems": 1, "maxItems": 80, "items": {"type": "object", "properties": {
            "id": text, "heading": text, "anchors": {"type": "array", "minItems": 1, "items": text}},
            "required": ["id", "heading", "anchors"], "additionalProperties": False}},
        "columns": {"type": "array", "minItems": 1, "maxItems": 30, "items": {"type": "object", "properties": {
            "id": text, "label": text}, "required": ["id", "label"], "additionalProperties": False}}},
        "required": ["title", "units"] + (["columns"] if skill.kind == "TABLE" else []), "additionalProperties": False}
    value = call_model(client, skill, "plan", {"requirement": task.input_payload.user_requirement,
                       "contract": skill.contract, "options": options, "evidence": evidence,
                       "control_pack": task.control_pack.model_dump(mode="json")}, schema)
    if schema_errors(value, schema):
        raise ValueError("产物骨架结构不合规")
    identifiers = {s["id"] for s in evidence["sentences"]}
    unit_ids = [u["id"] for u in value["units"]]
    if len(set(unit_ids)) != len(unit_ids) or any(set(u["anchors"]) - identifiers for u in value["units"]):
        raise ValueError("产物骨架使用了重复编号或不存在的素材编号")
    count = len(value["units"])
    bounds = skill.contract.get("sections", {})
    if count < bounds.get("min", 1) or count > bounds.get("max", 80) or ("count" in options and count != int(options["count"])):
        raise ValueError("产物骨架数量不符合契约或用户选项")
    if set(skill.contract.get("required_headings", [])) - {u["heading"] for u in value["units"]}:
        raise ValueError("产物骨架缺少必需章节")
    if skill.kind == "TREE" and count > skill.contract.get("tree_depth", {}).get("max_children", 20):
        raise ValueError("思维导图骨架节点数超出契约")
    if skill.kind == "TABLE":
        bounds = skill.contract.get("table_columns", {"min": 1, "max": 30})
        columns = value["columns"]
        if not bounds["min"] <= len(columns) <= bounds["max"] or len({c["id"] for c in columns}) != len(columns):
            raise ValueError("数据表骨架列定义不符合契约")
    return value
