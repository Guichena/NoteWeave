"""宿主机侧：取某轮题目名含指定片段的各题规划任务输入快照，每行一题，供 replay_topic_entity.py 使用。

用法：python3 dump_planning_contexts.py <输出文件> <标签> <题目名片段>
"""
import base64
import glob
import json
import subprocess
import sys

ROOT = "/data/workspace/noteweave-research-eval"


def main(out_path, label, fragment):
    with open(out_path, "w", encoding="utf-8") as out:
        for path in sorted(glob.glob(f"{ROOT}/runs/{label}/merged/*.json")):
            record = json.load(open(path, encoding="utf-8"))
            if fragment not in record["case_key"]:
                continue
            rows = subprocess.run([f"{ROOT}/qraw.sh", "select replace(to_base64(execution_context_json), char(10), '') "
                                   f"from research_agent_task where research_run_id='{record['run_id']}' "
                                   "and role='PLANNING' order by created_at limit 1"],
                                  capture_output=True, text=True).stdout.split()
            if rows:
                out.write(json.dumps({"case": record["case_key"], "context": json.loads(base64.b64decode(rows[0]))},
                                     ensure_ascii=False) + "\n")


if __name__ == "__main__":
    main(*sys.argv[1:4])
