"""临时容器内：同一批成文输入，线上版本与新版本各出一份报告，第一次模型输出两边共用，供评分模型配对比较。

用法（在用 Worker 镜像起的临时容器内，不要在评测用的 Worker 容器里跑）：
    python replay_ab.py <gather_synth.py 的输出> <新版 narrative_report.py> <输出文件> [并发数] [旧版文件]
旧版默认用镜像里的 app.narrative_report，此时两版共用第一次模型输出，只比较整理逻辑；新版遇到第一次输出不合格时
照常带着失败原因再调一次模型。给了旧版文件时（例如比较两版提示），两版各自调用模型，不共用输出。
输出里带上成文输入与两版成文结构，供后端校验逐份核对；只写报告与统计，不打印密钥。
"""
import importlib.util
import json
import sys
import threading
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace

import app.narrative_report as old
from app.deep_cell_executor import _build_execution_llm

FALLBACK_HEADINGS = ("补充核验结果", "Additional verified findings")
_lock = threading.Lock()


class Recording:
    """包住模型客户端，记录每次输出；给了 replay 时先按顺序返回这些输出，用完再真实调用。"""

    def __init__(self, llm, replay=None):
        self.llm, self.replay, self.outputs = llm, list(replay or []), []

    def complete_json(self, purpose, payload):
        output = self.replay.pop(0) if self.replay else self.llm.complete_json(purpose, payload)
        self.outputs.append(output)
        return output

    def __getattr__(self, name):
        return getattr(self.llm, name)


def _llm():
    return _build_execution_llm(lambda: None, SimpleNamespace(temperature=0.2, max_llm_calls=2))


def _stats(result, cells):
    narrative = result.narrative
    body = [paragraph for section in narrative.get("sections", []) if section["heading"] not in FALLBACK_HEADINGS
            for paragraph in section["paragraphs"]]
    covered = {key for paragraph in body for key in paragraph.get("cell_keys", [])}
    return {"mode": result.mode, "reason": result.reason, "llm_calls": result.llm_calls,
            "omitted": sum(1 for cell in cells if cell["cell_key"] not in covered),
            "body_chars": sum(len(paragraph["text"]) for paragraph in body),
            "table_rows": len((narrative.get("comparison_table") or {}).get("rows", []))}


def analyse(item, new, base, share):
    synthesis_input = item["synthesis_input"]
    first = Recording(_llm())
    try:
        cells = base._validated_cells(synthesis_input)
        old_result = base.NarrativeReportPolisher(first).polish(synthesis_input)
    except Exception as exc:  # noqa: BLE001
        return {"label": item["label"], "case": item["case"], "error": f"old {type(exc).__name__}"}
    second = Recording(_llm(), replay=first.outputs[:1] if share else None)
    try:
        new_result = new.NarrativeReportPolisher(second).polish(synthesis_input)
    except Exception as exc:  # noqa: BLE001
        return {"label": item["label"], "case": item["case"], "error": f"new {type(exc).__name__}"}
    return {"label": item["label"], "case": item["case"], "passed": item.get("passed"),
            "synthesis_input": synthesis_input,
            "old": {**_stats(old_result, cells), "markdown": old_result.markdown, "narrative": old_result.narrative},
            "new": {**_stats(new_result, cells), "markdown": new_result.markdown, "narrative": new_result.narrative,
                    "extra_llm_calls": max(0, len(second.outputs) - 1)}}


def _load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def main(path, new_path, out_path, workers, old_path=None):
    new = _load("narrative_report_new", new_path)
    base = _load("narrative_report_old", old_path) if old_path else old
    items = json.load(open(path, encoding="utf-8"))
    rows = []
    with ThreadPoolExecutor(workers) as pool:
        for row in pool.map(lambda item: analyse(item, new, base, old_path is None), items):
            rows.append(row)
            brief = {key: {k: v for k, v in row[key].items() if k not in ("markdown", "narrative")} for key in ("old", "new") if key in row}
            print(json.dumps({"label": row["label"], "case": row["case"], "error": row.get("error"), **brief},
                             ensure_ascii=False), flush=True)
    json.dump(rows, open(out_path, "w", encoding="utf-8"), ensure_ascii=False)
    print("DONE", len(rows))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]) if len(sys.argv) > 4 else 4,
         sys.argv[5] if len(sys.argv) > 5 else None)
