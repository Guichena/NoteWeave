"""宿主机侧：某题每个任务存档的窗口数，以及其中含关键词的窗口，用来判断答案所在的网页是哪个任务抓到的。

用法：python3 task_windows.py <标签> <题目> <关键词,逗号分隔>
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


def main(label, case, keywords):
    run = json.load(open(f"{ROOT}/runs/{label}/merged/{case}.json", encoding="utf-8"))["run_id"]
    rows = query(f"select {b64('s.research_agent_task_id')}, {b64('t.role')}, {b64('s.source_url')}, "
                 f"{b64('s.content_text')} from research_external_snapshot s join research_agent_task t "
                 f"on t.id = s.research_agent_task_id where s.research_run_id = '{run}' order by s.created_at")
    tasks = {}
    for task_id, role, url, text in rows:
        entry = tasks.setdefault(task_id, {"role": role, "windows": 0, "hits": []})
        entry["windows"] += 1
        if any(keyword in text for keyword in keywords.split(",")):
            entry["hits"].append(url[:80])
    print("==", case, keywords)
    for task_id, entry in tasks.items():
        print(f"  {task_id[:8]} {entry['role']:<15} windows {entry['windows']:>2} hits {len(entry['hits'])} {entry['hits'][:3]}")


if __name__ == "__main__":
    main(*sys.argv[1:4])
