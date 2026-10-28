"""按骨架单元并发写作，保持原始规划顺序。"""

from concurrent.futures import ThreadPoolExecutor

from app.pipeline.schemas import item_schema
from app.pipeline.stages import call_model


def draft(task, skill, options, evidence, outline, schema, client):
    fragment_schema = {"type": "object", "properties": {"item": item_schema(skill.kind, schema)},
                       "required": ["item"], "additionalProperties": False, "$defs": schema.get("$defs", {})}
    def write(unit):
        try:
            response = call_model(client, skill, "draft", {"requirement": task.input_payload.user_requirement,
                "options": options, "unit": unit, "columns": outline.get("columns", []),
                "evidence": evidence, "control_pack": task.control_pack.model_dump(mode="json")}, fragment_schema)
            return response.get("item", {})
        except ValueError:
            # 不合规响应与其他失败项合并，在校验阶段统一修复一次。
            return {}
    with ThreadPoolExecutor(max_workers=min(4, len(outline["units"]))) as executor:
        items = list(executor.map(write, outline["units"]))
    value = {"schema_version": 1, "kind": skill.kind, "title": outline["title"]}
    if skill.kind == "TREE":
        value["root"] = {"id": "root", "label": outline["title"], "children": items}
    else:
        value[{"DOC": "sections", "QUIZ": "questions", "CARDS": "cards", "TABLE": "rows"}[skill.kind]] = items
    if skill.kind == "TABLE":
        value["columns"] = outline["columns"]
    return value
