"""对照评分缺的必答要点：规划的专家要点、各列要求，以及单元格最终的值，判断缺在规划、取证还是成文。

用法：python3 point_trace.py <标签> <数据集路径> [题目...]
"""
import glob
import json
import subprocess
import sys

ROOT = "/data/workspace/noteweave-research-eval"


def query(sql):
    out = subprocess.run([f"{ROOT}/qraw.sh", sql], capture_output=True, text=True).stdout
    return [line.split("\t") for line in out.splitlines()]


def main(label, dataset_path, cases):
    dataset = {item["case_key"]: item for item in json.load(open(dataset_path, encoding="utf-8"))["cases"]}
    files = sorted(glob.glob(f"{ROOT}/runs/{label}/quality-*.json"))
    results = {row["case_key"]: row for row in json.load(open(files[-1], encoding="utf-8"))["results"]}
    for case, row in sorted(results.items()):
        if cases and case not in cases or row["passed"]:
            continue
        missing = [item["item"] for item in row["judge"].get("must_cover", []) if not item.get("covered")]
        record = json.load(open(f"{ROOT}/runs/{label}/merged/{case}.json", encoding="utf-8"))
        run_id = record["run_id"]
        plans = query("select replace(plan_json, char(10), ' ') from research_matrix_plan where "
                      f"research_run_id='{run_id}' and plan_status='ACTIVE' order by created_at desc limit 1")
        plan = json.loads(plans[0][0]) if plans else {}
        if isinstance(plan, str):
            plan = json.loads(plan)
        print(f"## {case} missing={missing}")
        print("   question:", dataset.get(case, {}).get("question", "")[:160])
        for point in (plan.get("brief") or {}).get("required_points") or []:
            print("   point:", str(point)[:160])
        for key, label_text, description in query(
                "select column_key, label, replace(description, char(10), ' ') from research_column "
                f"where research_run_id='{run_id}' order by id"):
            print(f"   col {label_text[:40]} | {description[:200]}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], set(sys.argv[3:]))
