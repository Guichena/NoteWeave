"""打印某轮某题的列定义、单元格结论、评分理由与最终报告，用于判断必答要点缺在哪一环。

用法：python3 case_dump.py <标签> <题目> [--report]
"""
import glob
import json
import subprocess
import sys

ROOT = "/data/workspace/noteweave-research-eval"


def query(sql):
    out = subprocess.run([f"{ROOT}/qraw.sh", sql], capture_output=True, text=True).stdout
    return [line.split("\t") for line in out.splitlines()]


def main(label, case, show_report):
    record = json.load(open(f"{ROOT}/runs/{label}/merged/{case}.json", encoding="utf-8"))
    run_id = record["run_id"]
    print("run", run_id, "seconds", record.get("wall_seconds"))
    for key, text, description in query(
            f"select column_key, label, replace(description, char(10), ' ') from research_column where research_run_id='{run_id}' order by id"):
        print(f"COL {key}: {text} | {description[:160]}")
    for key, status, value in query(
            f"select cell_key, cell_status, replace(replace(candidate_value, char(10), ' '), char(9), ' ') from research_cell where research_run_id='{run_id}' order by cell_key"):
        print(f"CELL {key} [{status}] {value[:260]}")
    files = sorted(glob.glob(f"{ROOT}/runs/{label}/quality-*.json"))
    for row in json.load(open(files[-1], encoding="utf-8"))["results"]:
        if row["case_key"] == case:
            judge = row["judge"]
            print("PASS", row["passed"], json.dumps(judge.get("scores"), ensure_ascii=False))
            print("MUST", json.dumps(judge.get("must_cover"), ensure_ascii=False))
            print("WHY", json.dumps(judge.get("rationales"), ensure_ascii=False)[:1200])
    if show_report:
        rows = query(f"select replace(final_report_markdown, char(10), '<NL>') from research_run where id='{run_id}'")
        print("REPORT\n" + (rows[0][0].replace("<NL>", "\n") if rows else "")[:8000])


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], "--report" in sys.argv)
