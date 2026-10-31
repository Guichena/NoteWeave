"""逐个取证任务打印每个单元格的候选组、最终选中的候选和单元格状态，用于判断答案是没找到还是没选上。

用法：python3 cell_clusters.py <标签> <题目>
"""
import json
import subprocess
import sys

ROOT = "/data/workspace/noteweave-research-eval"


def query(sql):
    out = subprocess.run([f"{ROOT}/qraw.sh", sql], capture_output=True, text=True).stdout
    return [line.split("\t") for line in out.splitlines()]


def main(label, case):
    record = json.load(open(f"{ROOT}/runs/{label}/merged/{case}.json", encoding="utf-8"))
    run_id = record["run_id"]
    labels = dict(query(f"select column_key, label from research_column where research_run_id='{run_id}'"))
    status = {key: (state, value) for key, state, value in query(
        "select cell_key, cell_status, replace(replace(coalesce(candidate_value, ''), char(10), ' '), char(9), ' ') "
        f"from research_cell where research_run_id='{run_id}'")}
    rows = query("select t.role, t.id, c.envelope_json from research_agent_completion c join research_agent_task t "
                 f"on t.id = c.research_agent_task_id where t.research_run_id='{run_id}' "
                 "and t.role in ('DEEP_CELL', 'COUNTERFACTUAL') order by t.created_at")
    for role, task_id, envelope in rows:
        data = json.loads(envelope)
        chosen = {item["cell_key"]: item["candidate_value"] for item in data.get("candidates", [])}
        clusters = (data.get("extraction_diagnostics") or {}).get("claim_clusters") or []
        print(f"## {role} {task_id[:8]} telemetry={data.get('telemetry')}")
        for cell in sorted(set(chosen) | {item["cell_key"] for item in clusters}):
            column = cell.split(":", 1)[1]
            state = status.get(cell, ("?", ""))
            print(f"  [{labels.get(column, column)[:30]}] final={state[0]}")
            for item in clusters:
                if item["cell_key"] == cell:
                    mark = "*" if item["claim_text"] == chosen.get(cell) else " "
                    print(f"    {mark} n={len(item['evidence_keys'])} c={item['confidence_ppm']} "
                          f"{item['claim_text'][:150]}".replace("\n", " "))
            if cell in chosen and not any(item["claim_text"] == chosen[cell] for item in clusters):
                print(f"    > weak/retained: {str(chosen[cell])[:150]}".replace("\n", " "))
        rejected = (data.get("extraction_diagnostics") or {}).get("rejection_counts")
        if rejected:
            print("  rejections:", rejected)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
