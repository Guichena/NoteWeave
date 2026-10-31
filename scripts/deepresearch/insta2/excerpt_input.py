"""宿主机侧：为 excerpt_replay.py 准备输入，取某轮某题某一列的定义和该次运行的已存档窗口。

用法：python3 excerpt_input.py <标签> <题目> <列名前缀> <网址片段,逗号分隔> <实体> <关键词,逗号分隔>
输出 /tmp/excerpt_input.json，再 docker cp 进 Worker 容器。
"""
import base64
import json
import subprocess
import sys

ROOT = "/data/workspace/noteweave-research-eval"


def query(sql):
    out = subprocess.run([f"{ROOT}/qraw.sh", sql], capture_output=True, text=True).stdout
    return [[base64.b64decode(value).decode("utf-8") for value in line.split("\t")] for line in out.splitlines()]


def b64(expr):
    return f"replace(to_base64({expr}), char(10), '')"


def main(label, case, column_prefix, url_parts, entity, keywords):
    record = json.load(open(f"{ROOT}/runs/{label}/merged/{case}.json", encoding="utf-8"))
    run = record["run_id"]
    key, text_label, description, intents = query(
        f"select {b64('column_key')}, {b64('label')}, {b64('description')}, {b64('search_intents_json')} "
        f"from research_column where research_run_id='{run}' and label like '{column_prefix}%'")[0]
    url_filter = " or ".join(f"source_url like '%{part}%'" for part in url_parts.split(","))
    rows = query(f"select {b64('window_id')}, {b64('source_url')}, {b64('content_text')} from research_external_snapshot "
                 f"where research_run_id='{run}' and ({url_filter})")
    question = json.load(open(f"{ROOT}/runs/{label}/merged/{case}.json", encoding="utf-8")).get("question", "")
    windows = [{"window_id": f"{window}:{index}", "title": "t", "url": url, "text": text}
               for index, (window, url, text) in enumerate(rows)]
    json.dump({"column": {"key": key, "label": text_label, "description": description,
                          "search_intents": json.loads(intents)},
               "entity": entity, "question": question or case, "windows": windows,
               "keywords": keywords.split(",")}, open("/tmp/excerpt_input.json", "w", encoding="utf-8"), ensure_ascii=False)
    print(len(windows), "windows for column", text_label)


if __name__ == "__main__":
    main(*sys.argv[1:7])
