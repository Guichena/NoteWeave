"""在某轮某题提交过的全部证据（结论与原文）里按正则查找，并看最终报告里有没有出现，判断要点缺在取证还是成文。

用法：python3 evidence_grep.py <标签> <题目> <正则>
"""
import json
import re
import subprocess
import sys

ROOT = "/data/workspace/noteweave-research-eval"


def query(sql):
    out = subprocess.run([f"{ROOT}/qraw.sh", sql], capture_output=True, text=True).stdout
    return [line.split("\t") for line in out.splitlines()]


def main(label, case, pattern):
    regex = re.compile(pattern, re.I)
    run_id = json.load(open(f"{ROOT}/runs/{label}/merged/{case}.json", encoding="utf-8"))["run_id"]
    labels = dict(query(f"select column_key, label from research_column where research_run_id='{run_id}'"))
    status = {key: state for key, state in query(
        f"select cell_key, cell_status from research_cell where research_run_id='{run_id}'")}
    rows = query("select c.envelope_json from research_agent_completion c join research_agent_task t "
                 f"on t.id = c.research_agent_task_id where t.research_run_id='{run_id}' "
                 "and t.role in ('DEEP_CELL', 'COUNTERFACTUAL')")
    cell_of = {}
    for (envelope,) in rows:
        data = json.loads(envelope)
        for candidate in data.get("candidates", []):
            for key in candidate.get("evidence_keys", []):
                cell_of[key] = candidate["cell_key"]
        for item in data.get("evidence", []):
            text = f"{item.get('claim_text', '')} || {item.get('quote_text', '')}"
            if regex.search(text):
                cell = cell_of.get(item["evidence_key"], "?")
                column = labels.get(cell.split(":", 1)[-1], cell)[:28]
                print(f"[{item.get('relation_type')}] [{column}] [{status.get(cell, '?')}] "
                      + " ".join(text.split())[:260])
    report = query(f"select replace(final_report_markdown, char(10), ' ') from research_run where id='{run_id}'")
    body = report[0][0].split("## Citation audit")[0] if report else ""
    found = [match.group(0) for match in regex.finditer(body)]
    print("REPORT mentions:", len(found), sorted(set(found))[:8])


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3])
