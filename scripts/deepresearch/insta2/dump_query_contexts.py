"""宿主机侧：取 page_evidence.out 里 NONE 要点所在题目的全部取证任务，导出检索词规划的输入，供 replay_queries.py 重放。

用法：python3 exp/dump_query_contexts.py exp/page_evidence.out exp/query_contexts.json
"""
import ast
import glob
import json
import subprocess
import sys

ROOT = "/data/workspace/noteweave-research-eval"


def query(sql):
    out = subprocess.run([f"{ROOT}/qraw.sh", sql], capture_output=True, text=True).stdout
    return [line.split("\t") for line in out.splitlines()]


def main(source, out_path):
    wanted = []
    for line in open(source, encoding="utf-8"):
        parts = line.split()
        if parts[2:3] == ["NONE"] and (parts[0], parts[1]) not in wanted:
            wanted.append((parts[0], parts[1]))
    contexts = []
    for run, prefix in wanted:
        kind, label = run.split("-", 1)
        path = next(iter(glob.glob(f"{ROOT}/runs/final-{kind}-{label}/merged/{prefix}*.json")))
        record = json.load(open(path, encoding="utf-8"))
        for task_id, role, context_json in query(
                "select id, role, execution_context_json from research_agent_task "
                f"where research_run_id='{record['run_id']}' and role in ('DEEP_CELL','COUNTERFACTUAL') order by created_at"):
            context = json.loads(context_json)
            intent = context.get("research_intent")
            # 部分字段以 Python 字面量字符串存放
            intent = ast.literal_eval(intent) if isinstance(intent, str) else (intent or {})
            contexts.append({"run": run, "case": record["case_key"], "task_id": task_id, "role": role,
                             "policy": context.get("query_policy") or {}, "intent": intent})
    json.dump(contexts, open(out_path, "w", encoding="utf-8"), ensure_ascii=False)
    print(len(wanted), "cases", len(contexts), "tasks")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
