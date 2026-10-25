"""对照多轮评测：评分、交付状态、单元格、耗时与预算消耗放进同一张表。

用法：python3 compare_eval.py <标签> [<标签> ...]；每个标签对应 runs/<标签>/，需已运行 score_eval.sh。
"""
import glob
import json
import os
import statistics
import subprocess
import sys

ROOT = "/data/workspace/noteweave-research-eval"


def consumed(run_ids):
    if not run_ids:
        return {}
    ids = ",".join("'%s'" % run_id for run_id in run_ids)
    sql = ("select research_run_id, coalesce(sum(json_extract(consumed_json,'$.llm_calls')),0), "
           "coalesce(sum(json_extract(consumed_json,'$.search_calls')),0), count(*) "
           f"from research_budget_reservation where research_run_id in ({ids}) group by research_run_id")
    out = subprocess.run([f"{ROOT}/q.sh", sql], capture_output=True, text=True).stdout
    result = {}
    for line in out.splitlines()[1:]:
        run_id, llm, search, tasks = line.split("\t")
        result[run_id] = (int(float(llm)), int(float(search)), int(tasks))
    return result


def load(label):
    base = f"{ROOT}/runs/{label}"
    reports = sorted(glob.glob(f"{base}/quality-*.json"))
    quality = json.load(open(reports[-1], encoding="utf-8")) if reports else {"results": []}
    scored = {item["case_key"]: item for item in quality.get("results", [])}
    rows = []
    for path in sorted(glob.glob(f"{base}/merged/*.json")):
        record = json.load(open(path, encoding="utf-8"))
        detail = record["run_detail"]
        cells = (record.get("resources", {}).get("cells") or {}).get("data") or []
        cells = cells if isinstance(cells, list) else cells.get("cells", [])
        judge = (scored.get(record["case_key"]) or {}).get("judge") or {}
        rows.append({
            "case": record["case_key"], "run_id": record["run_id"], "wall": record.get("wall_seconds") or 0,
            "status": detail.get("status"), "terminal": detail.get("completion_terminal_state") or "",
            "verified": sum(1 for cell in cells if cell.get("cell_status") == "VERIFIED"), "cells": len(cells),
            "passed": (scored.get(record["case_key"]) or {}).get("passed"),
            "votes": judge.get("pass_votes"),
            "missed": [item["item"] for item in judge.get("must_cover", []) if not item.get("covered")],
            "scores": judge.get("scores", {}),
        })
    usage = consumed([row["run_id"] for row in rows])
    for row in rows:
        row["llm"], row["search"], row["tasks"] = usage.get(row["run_id"], (0, 0, 0))
    return quality, rows


def summary(label, quality, rows):
    passed = [row for row in rows if row["passed"]]
    walls = sorted(row["wall"] for row in rows)
    cells = sum(row["cells"] for row in rows)
    verified = sum(row["verified"] for row in rows)
    terminals = {}
    for row in rows:
        terminals[row["terminal"] or row["status"]] = terminals.get(row["terminal"] or row["status"], 0) + 1
    dims = quality.get("dimension_means", {})
    p90 = walls[min(len(walls) - 1, int(len(walls) * 0.9))] if walls else 0
    return (f"| {label} | {len(passed)}/{len(rows)} | {verified}/{cells} ({verified / cells if cells else 0:.2f}) | "
            f"{dims.get('factual_accuracy', 0):.2f} / {dims.get('citation_accuracy', 0):.2f} / {dims.get('completeness', 0):.2f} | "
            f"{statistics.median(walls) if walls else 0:.0f} / {p90:.0f} | "
            f"{statistics.mean(row['llm'] for row in rows) if rows else 0:.0f} / {statistics.mean(row['search'] for row in rows) if rows else 0:.1f} | "
            f"{', '.join(f'{key}:{value}' for key, value in sorted(terminals.items()))} |")


def main(labels):
    loaded = {label: load(label) for label in labels}
    print("| 轮次 | 通过 | 有效单元格 | 事实/引用/完整性均分 | 耗时中位/P90 秒 | 每题模型调用/搜索轮 | 交付状态 |")
    print("|---|---|---|---|---|---|---|")
    for label, (quality, rows) in loaded.items():
        print(summary(label, quality, rows))
    print()
    cases = sorted({row["case"] for _, rows in loaded.values() for row in rows})
    print("| 题目 | " + " | ".join(labels) + " |")
    print("|---|" + "---|" * len(labels))
    for case in cases:
        cols = []
        for label in labels:
            row = next((item for item in loaded[label][1] if item["case"] == case), None)
            if row is None:
                cols.append("-")
                continue
            mark = "✓" if row["passed"] else "✗" if row["passed"] is False else "?"
            missed = ("；缺：" + "、".join(row["missed"])) if row["missed"] else ""
            cols.append(f"{mark} {row['verified']}/{row['cells']} {row['wall']:.0f}s{missed}")
        print(f"| {case} | " + " | ".join(cols) + " |")


if __name__ == "__main__":
    main(sys.argv[1:])
