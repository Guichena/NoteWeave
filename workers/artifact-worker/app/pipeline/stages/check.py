"""结构、契约、引用依次校验，合并一次修复后删除仍不合规的单元。"""

from copy import deepcopy

from app.pipeline.evidence import evidence_errors, facts
from app.pipeline.rules import contract_errors, units
from app.pipeline.schemas import item_schema, schema_errors
from app.pipeline.stages import call_model


def check(ir, outline, skill, evidence, schema, client):
    value = deepcopy(ir)
    expected = [u["id"] for u in outline["units"]]
    fragment = item_schema(skill.kind, schema)

    def failures(current):
        failed = {}
        for index, item in enumerate(units(current)):
            identifier = expected[index]
            errors = schema_errors(item, fragment)
            if not errors:
                if item["id"] != identifier:
                    errors.append("写作单元编号与规划不一致")
                if skill.kind == "DOC" and item["heading"] != outline["units"][index]["heading"]:
                    errors.append("章节标题与规划不一致")
                if skill.kind == "DECK" and item["title"] != outline["units"][index]["heading"]:
                    errors.append("幻灯片标题与规划不一致")
                local_rules = {k: v for k, v in skill.contract.items()
                               if k in {"quiz_single_answer", "card_no_leak", "table_columns", "deck_bullets", "figure_required"}}
                if local_rules:
                    key = {"QUIZ": "questions", "CARDS": "cards", "TABLE": "rows", "DECK": "slides", "DOC": "sections"}[skill.kind]
                    errors.extend(contract_errors({**current, key: [item]}, local_rules))
                planned_frames = set(outline["units"][index].get("frame_ids", []))
                figures = [item["figure"]] if skill.kind == "DECK" and "figure" in item else [
                    block for block in item.get("blocks", []) if block.get("type") == "figure"]
                if any(figure["frame_id"] not in planned_frames for figure in figures):
                    errors.append("插图与规划选择的关键帧不一致")
                errors.extend(evidence_errors(item, evidence))
            if errors:
                failed[identifier] = errors
        if not failed:
            errors = schema_errors(current, schema) + contract_errors(current, skill.contract)
            if not any(facts(current)):
                errors.append("产物缺少带引用的事实内容")
            if errors:
                failed = {identifier: errors for identifier in expected}
        return failed

    failed = failures(value)
    stats = {"repair_calls": 0, "deleted_units": []}
    if failed:
        stats["repair_calls"] = 1
        repair_schema = {"type": "object", "properties": {"items": {"type": "array", "items": fragment}},
                         "required": ["items"], "additionalProperties": False, "$defs": schema.get("$defs", {})}
        try:
            response = call_model(client, skill, "check", {"content": value, "failures": failed,
                "outline": outline, "contract": skill.contract, "evidence": evidence}, repair_schema)
            repairs = {}
            for item in response.get("items", []):
                if isinstance(item, dict) and isinstance(item.get("id"), str) and item["id"] in failed:
                    if item["id"] in repairs:
                        raise ValueError("修复结果包含重复的单元编号")
                    repairs[item["id"]] = item
        except (ValueError, TypeError):
            repairs = {}
        for index, identifier in enumerate(expected):
            if identifier in failed:
                units(value)[index] = repairs.get(identifier, {})
        remaining = failures(value)
        removed = set(remaining)
        stats["deleted_units"] = [identifier for identifier in expected if identifier in removed]
        units(value)[:] = [item for identifier, item in zip(expected, units(value)) if identifier not in removed]
    final_errors = schema_errors(value, schema)
    if not final_errors:
        final_errors += contract_errors(value, skill.contract) + evidence_errors(value, evidence)
        if not any(facts(value)):
            final_errors.append("产物缺少带引用的事实内容")
    if final_errors:
        raise ValueError("删除失败单元后产物仍不满足交付契约：" + "; ".join(final_errors[:5]))
    return value, stats
