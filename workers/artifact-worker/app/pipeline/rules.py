"""Skill 可引用的具名契约规则。"""


def units(ir):
    return ir["root"]["children"] if ir["kind"] == "TREE" else ir[
        {"DOC": "sections", "QUIZ": "questions", "CARDS": "cards", "TABLE": "rows", "DECK": "slides"}[ir["kind"]]]


def _sections(ir, value):
    return [] if value["min"] <= len(ir["sections"]) <= value["max"] else ["章节数量不符合契约"]


def _headings(ir, value):
    headings = {v["heading"] for v in ir["sections"]}
    return [f"缺少必需章节：{heading}" for heading in value if heading not in headings]


def _quiz(ir, value):
    errors = []
    for item in ir["questions"]:
        ids = [v["id"] for v in item["options"]]
        texts = [v["text"].strip() for v in item["options"]]
        if len(ids) != len(set(ids)) or len(texts) != len(set(texts)) or ids.count(item["answer"]) != 1:
            errors.append(f"{item['id']}：单选题必须有唯一且属于选项的答案")
    return errors


def _cards(ir, value):
    return [f"{v['id']}：闪卡正面包含答案" for v in ir["cards"] if v["back"]["text"].strip().casefold() in v["front"].casefold()]


def _tree(ir, value):
    depths = []
    errors = []
    def visit(node, depth):
        children = node["children"]
        if not children:
            depths.append(depth)
        else:
            if not value.get("min_children", 1) <= len(children) <= value.get("max_children", 20):
                errors.append(f"{node['id']}：子节点数量不符合契约")
            for child in children:
                visit(child, depth + 1)
    visit(ir["root"], 1)
    if not depths or min(depths) < value["min"] or max(depths) > value["max"]:
        errors.append("思维导图层级不符合契约")
    return errors


def _table(ir, value):
    n = len(ir["columns"])
    errors = []
    if not value["min"] <= n <= value["max"] or len({c["id"] for c in ir["columns"]}) != n:
        errors.append("数据表列定义不符合契约")
    errors.extend(f"{row['id']}：数据表单元格不完整" for row in ir["rows"] if len(row["cells"]) != n
                  or any(not cell["text"].strip() for cell in row["cells"]))
    return errors


def _deck(ir, value):
    return [f"{slide['id']}：幻灯片要点数量不符合契约" for slide in ir["slides"]
            if not value["min"] <= len(slide["bullets"]) <= value["max"]]


def _figures(ir, value):
    return [f"{item['id']}：缺少必需的关键帧插图" for item in units(ir)
            if (value is True or item["id"] in value or item.get("heading", item.get("title")) in value)
            and not (item.get("figure") if ir["kind"] == "DECK"
                     else any(block["type"] == "figure" for block in item["blocks"]))]


RULES = {"sections": ({"DOC"}, _sections), "required_headings": ({"DOC"}, _headings),
         "quiz_single_answer": ({"QUIZ"}, _quiz), "card_no_leak": ({"CARDS"}, _cards),
         "tree_depth": ({"TREE"}, _tree), "table_columns": ({"TABLE"}, _table),
         "deck_bullets": ({"DECK"}, _deck), "figure_required": ({"DOC", "DECK"}, _figures)}


def validate_contract(kind, contract):
    if not isinstance(contract, dict):
        raise ValueError("Skill 契约必须是对象")
    for key, value in contract.items():
        if key not in RULES or kind not in RULES[key][0]:
            raise ValueError(f"契约规则未注册或不适用于形态：{key}")
        if key in {"sections", "tree_depth", "table_columns", "deck_bullets"}:
            allowed = {"min", "max", "min_children", "max_children"} if key == "tree_depth" else {"min", "max"}
            if not isinstance(value, dict) or set(value) - allowed or not {"min", "max"} <= value.keys() \
                    or any(type(v) is not int or v < 1 for v in value.values()) or value["min"] > value["max"] \
                    or value.get("min_children", 1) > value.get("max_children", 20):
                raise ValueError(f"契约上下限无效：{key}")
        elif key == "required_headings" or (key == "figure_required" and value is not True):
            if not isinstance(value, list) or not value or any(not isinstance(v, str) or not v.strip() for v in value) or len(value) != len(set(value)):
                raise ValueError("必需章节标题无效")
        elif value is not True:
            raise ValueError(f"契约开关必须为 true：{key}")


def contract_errors(ir, contract):
    return [error for key, value in contract.items() for error in RULES[key][1](ir, value)]
