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
    # 契约要求关键帧的产物，资料里没有视频关键帧时规划必然失败；提前给出明确原因，不白跑模型
    required = skill.contract.get("figure_required", [])
    if (required is True or bool(required)) and not any(
            s.get("frame_ids") for s in evidence["sentences"]):
        raise ValueError("该产物需要带关键帧的视频资料，所选资料中没有可用的视频关键帧")
    schema = _schema(skill)
    inputs = {"requirement": task.input_payload.user_requirement,
              "contract": skill.contract, "options": options, "evidence": evidence,
              "control_pack": task.control_pack.model_dump(mode="json")}
    if task.input_payload.task_parameters:
        inputs["task_parameters"] = task.input_payload.task_parameters
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
            value = _attach_nearest_frames(value, skill, evidence)
            _verify(value, skill, options, evidence)
            return value
        except ValueError as exc:
            failure = str(exc)
    raise ValueError(failure)


def _attach_nearest_frames(value, skill, evidence):
    """契约要求插图、但模型没给某个单元配关键帧时，确定性地补上时间最近的关键帧。

    关键帧只出现在少数转写句子上，模型选的锚点句经常没有帧，整份视频讲义就因
    「骨架缺少关键帧」失败，重试也大多失败。这里把时间上最接近该单元锚点的、带帧的素材句
    追加为该单元的锚点，并选用它的第一张帧；锚点和帧都来自真实素材，校验规则不放宽。
    """
    required = skill.contract.get("figure_required", [])
    if not required or not isinstance(value, dict) or not isinstance(value.get("units"), list):
        return value
    sentences = {s["id"]: s for s in evidence.get("sentences", [])}
    framed = [s for s in evidence.get("sentences", []) if s.get("frame_ids")]
    if not framed:
        return value
    order = {s["id"]: index for index, s in enumerate(evidence.get("sentences", []))}

    def position(sentence):
        start = sentence.get("start_ms")
        return float(start) if isinstance(start, (int, float)) else float(order.get(sentence["id"], 0)) * 1000.0

    for unit in value["units"]:
        if not isinstance(unit, dict):
            continue
        needs = required is True or unit.get("id") in required or unit.get("heading") in required
        if not needs or unit.get("frame_ids"):
            continue
        anchors = [sentences[a] for a in unit.get("anchors", []) if a in sentences]
        if not anchors:
            continue
        own = [f for a in anchors for f in a.get("frame_ids", [])]
        if own:
            unit["frame_ids"] = own[:1]
            continue
        center = sum(position(a) for a in anchors) / len(anchors)
        same_source = [s for s in framed if s.get("source_id") == anchors[0].get("source_id")] or framed
        nearest = min(same_source, key=lambda s: abs(position(s) - center))
        unit["anchors"] = list(unit.get("anchors", [])) + [nearest["id"]]
        unit["frame_ids"] = nearest["frame_ids"][:1]
    return value
