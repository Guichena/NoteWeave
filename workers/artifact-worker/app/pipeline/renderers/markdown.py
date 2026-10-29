"""DOC 正文的 Markdown 投影。"""


def render(ir):
    result = []
    for section in ir["sections"]:
        blocks = []
        for block in section["blocks"]:
            if block["type"] == "figure":
                blocks.append(block["caption"]["text"])
            else:
                prefix = "- " if block["type"] == "list" else ""
                blocks.append("\n".join(prefix + s["text"] for s in block["statements"]))
        result.append({"heading": section["heading"], "body": "\n\n".join(blocks), "source_refs": []})
    return result
