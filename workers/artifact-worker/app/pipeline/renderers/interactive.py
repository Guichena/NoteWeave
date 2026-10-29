"""测验、闪卡与思维导图的展示数据及 Markdown 投影。"""


def render(ir):
    kind = ir["kind"]
    if kind == "QUIZ":
        return [{"heading": item["prompt"]["text"], "body": "\n".join([
            *(f"- {o['id']}：{o['text']}" for o in item["options"]),
            f"\n答案：{item['answer']}", f"\n解析：{item['explanation']['text']}"]), "source_refs": []}
            for item in ir["questions"]]
    if kind == "CARDS":
        return [{"heading": item["front"], "body": item["back"]["text"], "source_refs": []} for item in ir["cards"]]
    result = []
    def visit(node, depth):
        lines = [f"{'  ' * depth}- {node['label']}"]
        if node.get("description"):
            lines.append(f"{'  ' * (depth + 1)}- {node['description']['text']}")
        for child in node["children"]:
            lines.extend(visit(child, depth + 1))
        return lines
    for node in ir["root"]["children"]:
        result.append({"heading": node["label"], "body": "\n".join(visit(node, 0)), "source_refs": []})
    return result
