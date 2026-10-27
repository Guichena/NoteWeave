"""宿主机侧：取某几轮全部题目最后一次成文任务的输入快照，写成一个 JSON 列表，供容器内的诊断脚本使用。

用法：python3 gather_synth.py <输出文件> <标签>...
"""
import base64
import glob
import json
import subprocess
import sys

ROOT = "/data/workspace/noteweave-research-eval"


def query(sql):
    out = subprocess.run([f"{ROOT}/qraw.sh", sql], capture_output=True, text=True).stdout
    return [line.split("\t") for line in out.splitlines()]


def main(out_path, labels):
    items = []
    for label in labels:
        quality = json.load(open(sorted(glob.glob(f"{ROOT}/runs/{label}/quality-*.json"))[-1], encoding="utf-8"))
        for row in quality["results"]:
            rows = query("select replace(to_base64(execution_context_json), char(10), '') from research_agent_task "
                         f"where research_run_id='{row['run_id']}' and role='SYNTHESIS' order by created_at desc limit 1")
            if not rows or not rows[0][0]:
                continue
            context = json.loads(base64.b64decode(rows[0][0]))
            missing = [item["item"] for item in row["judge"].get("must_cover", []) if not item.get("covered")]
            items.append({"label": label, "case": row["case_key"], "passed": row["passed"], "missing": missing,
                          "synthesis_input": context["query_policy"]["synthesis_input"]})
    json.dump(items, open(out_path, "w", encoding="utf-8"), ensure_ascii=False)
    print("cases", len(items))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2:])
