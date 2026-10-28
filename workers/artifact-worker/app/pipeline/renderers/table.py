"""数据表同时输出 Markdown 与标准 CSV。"""

import csv
from io import StringIO


def render(ir):
    def escape(text):
        return text.replace("|", "\\|").replace("\n", "<br>")
    labels = [c["label"] for c in ir["columns"]]
    rows = [[c["text"] for c in row["cells"]] for row in ir["rows"]]
    markdown = ["| " + " | ".join(map(escape, labels)) + " |", "| " + " | ".join("---" for _ in labels) + " |"]
    markdown.extend("| " + " | ".join(map(escape, row)) + " |" for row in rows)
    output = StringIO(newline="")
    writer = csv.writer(output)
    writer.writerow(labels)
    writer.writerows(rows)
    return [{"heading": ir["title"], "body": "\n".join(markdown), "source_refs": []}], output.getvalue().encode("utf-8-sig")
