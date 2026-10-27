"""容器内：同一次模型成文输出分别用线上版本和新版本的整理逻辑处理，比较正文漏写的已核验单元格数。

用法（在用 Worker 镜像起的临时容器内，不要在评测用的 Worker 容器里跑，见 README）：python compare_omit.py <输入 JSON 列表> <新版 narrative_report.py> [并发数]
新模块从文件单独加载，不改动容器里正在运行的代码。只打印统计，不打印报告正文与密钥。
"""
import collections
import importlib.util
import json
import sys
import threading
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace

import app.narrative_report as old
from app.deep_cell_executor import _build_execution_llm

_local = threading.local()
_original = old._normalize_narrative
FALLBACK_HEADINGS = ("补充核验结果", "Additional verified findings")


def _capturing(payload, cells, *args, **kwargs):
    _local.calls.append((payload, cells, args, kwargs))
    return _original(payload, cells, *args, **kwargs)


old._normalize_narrative = _capturing


def _omitted(narrative, cells):
    covered = {key for section in narrative["sections"] if section["heading"] not in FALLBACK_HEADINGS
               for paragraph in section["paragraphs"] for key in paragraph["cell_keys"]}
    return sum(1 for cell in cells if cell["cell_key"] not in covered)


def _body_chars(narrative):
    return sum(len(paragraph["text"]) for section in narrative["sections"] if section["heading"] not in FALLBACK_HEADINGS
               for paragraph in section["paragraphs"])


def analyse(item, new):
    _local.calls = []
    llm = _build_execution_llm(lambda: None, SimpleNamespace(temperature=0.2, max_llm_calls=2))
    old.NarrativeReportPolisher(llm).polish(item["synthesis_input"])
    rows = []
    for payload, cells, args, kwargs in _local.calls:
        row = {"cells": len(cells)}
        for name, module in (("old", old), ("new", new)):
            normalize = _original if module is old else module._normalize_narrative
            try:
                narrative = normalize(payload, cells, *args, **kwargs)
                table = narrative.get("comparison_table") or {}
                row[name] = {"omitted": _omitted(narrative, cells), "chars": _body_chars(narrative),
                             "rows": len(table.get("rows", []))}
            except ValueError as exc:
                row[name] = {"error": str(exc)}
        rows.append(row)
    return {"label": item["label"], "case": item["case"], "passed": item["passed"], "attempts": rows}


def main(path, new_path, workers):
    spec = importlib.util.spec_from_file_location("narrative_report_new", new_path)
    new = importlib.util.module_from_spec(spec)
    sys.modules["narrative_report_new"] = new
    spec.loader.exec_module(new)
    items = json.load(open(path, encoding="utf-8"))
    totals = collections.Counter()
    with ThreadPoolExecutor(workers) as pool:
        for row in pool.map(lambda item: analyse(item, new), items):
            print(json.dumps(row, ensure_ascii=False), flush=True)
            for attempt in row["attempts"]:
                totals["attempts"] += 1
                totals["cells"] += attempt["cells"]
                for name in ("old", "new"):
                    result = attempt[name]
                    if "error" in result:
                        totals[f"{name}_errors"] += 1
                    else:
                        totals[f"{name}_omitted"] += result["omitted"]
                        totals[f"{name}_body_chars"] += result["chars"]
                        totals[f"{name}_table_rows"] += result["rows"]
                        totals[f"{name}_attempts_with_omission"] += bool(result["omitted"])
    print("TOTALS", json.dumps(dict(totals)))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 4)
