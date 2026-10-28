"""核对已核验单元格的证据是否出自这一行的研究对象：证据讲的是另一篇论文、另一个产品时，单元格把别人的事实记在了这个对象名下。

用法：python3 run_with_env.py entity_audit.py <明细输出> <gather_synth.py 的输出>...
用评分模型逐题判断，只输出统计和判为不一致的单元格，不输出密钥。
"""
import collections
import json
import sys
from concurrent.futures import ThreadPoolExecutor

from point_evidence import judge

SYSTEM = ("你核对研究表格里每个单元格的证据是否出自这一行的研究对象。每个单元格给出研究对象 entity、列名 column、"
          "单元格结论 value 和证据原文 quotes（方括号里是来源标题）。对每个单元格判断 verdict："
          "SAME 表示原文陈述的事实属于该研究对象（对比文章里关于该对象的那一部分也算）；"
          "OTHER 表示原文讲的是另一个对象，例如另一篇论文或另一项研究的数据、另一个产品或项目、另一份规范，"
          "单元格却把它记在该对象名下；UNCLEAR 表示原文看不出属于哪个对象。"
          "研究对象是论文或研究时，按作者、年份、标题、研究设计判断；版本不同但是同一产品不算 OTHER。"
          "actual 写原文实际讲的对象，reason 用一句话说明。"
          "只输出 JSON：{\"cells\": [{\"index\": 0, \"verdict\": \"SAME|OTHER|UNCLEAR\", \"actual\": \"...\", \"reason\": \"...\"}]}")


def cells_of(synthesis_input):
    cells = []
    for cell in synthesis_input.get("cells") or []:
        quotes = [quote["quote"][:500] for quote in cell.get("evidence_quotes") or []][:3]
        if quotes:
            cells.append({"index": len(cells), "cell_key": cell.get("cell_key", ""),
                          "entity": cell.get("entity_label", ""), "column": cell.get("column_label", ""),
                          "value": str(cell.get("candidate_value", ""))[:300], "quotes": quotes})
    return cells


def analyse(item):
    cells = cells_of(item["synthesis_input"])
    if not cells:
        return item, cells, {}, None
    try:
        result = judge([{"role": "system", "content": SYSTEM}, {"role": "user", "content": json.dumps({
            "question": item["synthesis_input"].get("question", ""),
            "cells": [{key: cell[key] for key in ("index", "entity", "column", "value", "quotes")} for cell in cells],
        }, ensure_ascii=False)}])
    except Exception as exc:  # noqa: BLE001
        return item, cells, {}, type(exc).__name__
    verdicts = {entry.get("index"): entry for entry in result.get("cells", []) if isinstance(entry, dict)}
    return item, cells, verdicts, None


def kind_of(case):
    return case.removeprefix("h-").split("-")[0]


def main(out_path, paths):
    items = [item for path in paths for item in json.load(open(path, encoding="utf-8"))]
    totals, by_kind, by_pass = collections.Counter(), collections.Counter(), collections.Counter()
    details = []
    with ThreadPoolExecutor(6) as pool:
        for item, cells, verdicts, error in pool.map(analyse, items):
            if error:
                print(f"{item['label']} {item['case']} ERROR {error}")
                totals["errors"] += 1
                continue
            other = 0
            for cell in cells:
                entry = verdicts.get(cell["index"]) or {}
                verdict = str(entry.get("verdict") or "MISSING").upper()
                totals[verdict] += 1
                by_kind[(kind_of(item["case"]), verdict)] += 1
                details.append({"label": item["label"], "case": item["case"], "passed": item["passed"],
                                "cell_key": cell["cell_key"], "entity": cell["entity"], "column": cell["column"],
                                "value": cell["value"], "verdict": verdict, "actual": entry.get("actual", ""),
                                "reason": entry.get("reason", "")})
                if verdict == "OTHER":
                    other += 1
                    print(f"{item['label'][6:]:<16} {item['case'][:30]:<30} OTHER {cell['entity'][:40]} | "
                          f"{cell['column'][:30]} | 实际：{str(entry.get('actual', ''))[:60]}")
            by_pass[("passed" if item["passed"] else "failed", "cases")] += 1
            by_pass[("passed" if item["passed"] else "failed", "with_other")] += 1 if other else 0
    json.dump(details, open(out_path, "w", encoding="utf-8"), ensure_ascii=False)
    print("TOTALS", json.dumps(dict(totals)))
    print("BY_KIND", json.dumps({f"{kind}/{verdict}": count for (kind, verdict), count in sorted(by_kind.items())}))
    print("BY_PASS", json.dumps({f"{group}/{key}": count for (group, key), count in sorted(by_pass.items())}))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2:])
