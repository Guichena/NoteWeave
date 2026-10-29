"""模型规划骨架后，确定性核对素材编号和数量。"""

from app.pipeline.schemas import schema_errors
from app.pipeline.stages import call_model

MAX_ATTEMPTS = 2


def _schema(skill):
    text = {"type": "string", "minLength": 1}
    return {"type": "object", "properties": {"title": text,
        "units": {"type": "array", "minItems": 1, "maxItems": 80, "items": {"type": "object", "properties": {
            "id": text, "heading": text, "anchors": {"type": "array", "minItems": 1, "items": text},
            "frame_ids": {"type": "array", "maxItems": 8, "uniqueItems": True, "items": text}},
            "required": ["id", "heading", "anchors"], "additionalProperties": False}},
        "columns": {"type": "array", "minItems": 1, "maxItems": 30, "items": {"type": "object", "properties": {
            "id": text, "label": text}, "required": ["id", "label"], "additionalProperties": False}}},
        "required": ["title", "units"] + (["columns"] if skill.kind == "TABLE" else []), "additionalProperties": False}


def _verify(value, skill, options, evidence):
    if schema_errors(value, _schema(skill)):
        raise ValueError("产物骨架结构不合规")
    identifiers = {s["id"] for s in evidence["sentences"]}
    unit_ids = [u["id"] for u in value["units"]]
    if len(set(unit_ids)) != len(unit_ids) or any(set(u["anchors"]) - identifiers for u in value["units"]):
        raise ValueError("产物骨架使用了重复编号或不存在的素材编号")
    sentences = {s["id"]: s for s in evidence["sentences"]}
    for unit in value["units"]:
        allowed = {f for a in unit["anchors"] for f in sentences[a].get("frame_ids", [])}
        if set(unit.get("frame_ids", [])) - allowed:
            raise ValueError("规划插图不属于该单元引用的素材")
        required = skill.contract.get("figure_required", [])
        if (required is True or unit["id"] in required or unit["heading"] in required) and not unit.get("frame_ids"):
            raise ValueError("产物骨架缺少契约要求的关键帧")
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


def plan(task, skill, options, evidence, client):
    schema = _schema(skill)
    inputs = {"requirement": task.input_payload.user_requirement,
              "contract": skill.contract, "options": options, "evidence": evidence,
              "control_pack": task.control_pack.model_dump(mode="json")}
    failure = ""
    for _ in range(MAX_ATTEMPTS):
        current = dict(inputs)
        if failure:
            current["repair_hint"] = (
                f"上一版骨架未通过校验：{failure}。请重新输出完整 JSON 骨架，"
                "并严格遵守契约里的数量、章节与关键帧要求。"
            )
        try:
            value = call_model(client, skill, "plan", current, schema)
            _verify(value, skill, options, evidence)
            return value
        except ValueError as exc:
            failure = str(exc)
    raise ValueError(failure)
