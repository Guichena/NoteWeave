"""容器内：按线上同样的提示重跑成文，统计正文漏写的已核验单元格是模型没写，还是写了被逐句校验删掉。

用法（在用 Worker 镜像起的临时容器内，不要在评测用的 Worker 容器里跑，见 README）：python diag_omit.py <输入 JSON 列表> [并发数]
只打印统计、删掉的原因和被删句子的前 160 字，不打印密钥。
"""
import collections
import json
import sys
import threading
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace

import app.narrative_report as nr
from app.deep_cell_executor import _build_execution_llm

_local = threading.local()
_original = nr._normalize_narrative


def _capturing(payload, cells, *args, **kwargs):
    _local.payload = payload
    return _original(payload, cells, *args, **kwargs)


nr._normalize_narrative = _capturing


def _statement_failure(text, bound, keys, all_cells):
    """找出第一句通不过核对的句子和原因：数字或版本不在所引原文里，或提到的研究对象与引用不符。"""
    statements = nr.re.findall(r".+?(?:[。！？!?]|\.(?!\d)(?=\s|\[|$)|$)(?:\s*\[evidence:[^\[\]\s]+\])*", text, nr.re.S)
    has_inline = bool(nr._INLINE_CITATION.search(text))
    for statement in statements:
        if not statement.strip():
            continue
        if has_inline and not nr._INLINE_CITATION.search(statement):
            # 段落里有行内引用时，不带引用的句子不能借用别句的原文：有数字或提到研究对象就通不过
            tokens = nr._typed_tokens(statement)
            mentioned = [cell for cell in all_cells if nr._mentions_entity(statement, str(cell.get("entity_label") or ""))]
            if tokens or mentioned:
                kind = f"UNCITED_TOKENS {sorted(tokens)}" if tokens else "UNCITED_ENTITY"
                return kind, statement.strip()[:160]
            continue
        if not nr._cited_text_supported(statement, bound, keys, all_cells):
            refs = set(nr._INLINE_CITATION.findall(statement)) or set(keys)
            sources = []
            for cell in bound:
                if set(cell["evidence_keys"]) & refs:
                    quotes = cell.get("evidence_quotes") or []
                    sources.extend(str(item["quote"]) for item in quotes if item["evidence_key"] in refs)
                    sources.append(str(cell.get("entity_label") or ""))
                    if not quotes:
                        sources.append(nr._cell_source_text(cell))
            extra = nr._typed_tokens(nr._INLINE_CITATION.sub("", statement)) - nr._typed_tokens(" ".join(sources))
            reason = f"TOKENS {sorted(extra)}" if extra else "ENTITY_MISMATCH"
            return reason, nr._INLINE_CITATION.sub("", statement).strip()[:160]
    return "UNKNOWN", ""


def analyse(item):
    synthesis_input = item["synthesis_input"]
    llm = _build_execution_llm(lambda: None, SimpleNamespace(temperature=0.2, max_llm_calls=2))
    result = nr.NarrativeReportPolisher(llm).polish(synthesis_input)
    payload = getattr(_local, "payload", None)
    cells = nr._validated_cells(synthesis_input)
    leads = nr._validated_leads(synthesis_input, {str(cell["cell_key"]) for cell in cells})
    cells_by_key = {str(cell["cell_key"]): cell for cell in [*cells, *leads]}
    written = set()      # 模型在段落里声明过的单元格
    dropped = collections.defaultdict(list)  # 单元格 -> 删掉的原因
    if isinstance(payload, dict):
        for section in payload.get("sections", []) or []:
            if not isinstance(section, dict):
                continue
            for paragraph in section.get("paragraphs", []) or []:
                if not isinstance(paragraph, dict):
                    continue
                text = str(paragraph.get("text") or "").strip()
                keys = [key for key in nr._clean_strings(paragraph.get("cell_keys")) if key in cells_by_key]
                written.update(keys)
                if not keys:
                    continue
                bound = [cells_by_key[key] for key in keys]
                allowed = {key for cell in bound for key in cell["evidence_keys"]}
                unknown = set(nr._INLINE_CITATION.findall(text)) - allowed
                if unknown:
                    for key in keys:
                        dropped[key].append(("UNKNOWN_CITATION", text[:160]))
                    continue
                bound_text, inline = nr._bind_inline_citations(text, allowed)
                evidence = inline or [key for key in nr._clean_strings(paragraph.get("evidence_keys")) if key in allowed]
                if not evidence:
                    for key in keys:
                        dropped[key].append(("NO_EVIDENCE", text[:160]))
                    continue
                if not nr._cited_text_supported(bound_text, bound, evidence, list(cells_by_key.values()),
                                                synthesis_input.get("question", "")):
                    reason = _statement_failure(bound_text, bound, evidence, list(cells_by_key.values()))
                    for key in keys:
                        dropped[key].append(reason)
                    continue
                for key in keys:
                    if not set(cells_by_key[key]["evidence_keys"]) & set(evidence):
                        dropped[key].append(("CELL_NOT_CITED", text[:160]))
    covered = {key for section in result.narrative.get("sections", [])
               if section.get("heading") not in ("补充核验结果", "Additional verified findings")
               for paragraph in section["paragraphs"] for key in paragraph["cell_keys"]}
    omitted = [cell for cell in cells if cell["cell_key"] not in covered]
    return {
        "label": item["label"], "case": item["case"], "passed": item["passed"], "mode": result.mode,
        "cells": len(cells), "omitted": [{
            "column": cell["column_label"][:40], "entity": cell["entity_label"][:30],
            "written": cell["cell_key"] in written, "reasons": dropped.get(cell["cell_key"], [])[:2],
        } for cell in omitted],
        "unanswered_points": len(nr._clean_strings((payload or {}).get("unanswered_points")))
        if isinstance(payload, dict) else None,
    }


def main(path, workers):
    items = json.load(open(path, encoding="utf-8"))
    totals = collections.Counter()
    with ThreadPoolExecutor(workers) as pool:
        for row in pool.map(analyse, items):
            totals["cases"] += 1
            totals["cells"] += row["cells"]
            totals["omitted"] += len(row["omitted"])
            totals["cases_with_omission"] += bool(row["omitted"])
            for cell in row["omitted"]:
                if not cell["written"]:
                    totals["omitted_never_written"] += 1
                else:
                    kind = cell["reasons"][0][0].split(" ")[0] if cell["reasons"] else "WRITTEN_BUT_KEPT_ELSEWHERE"
                    totals["omitted_dropped_" + kind] += 1
            print(json.dumps(row, ensure_ascii=False), flush=True)
    print("TOTALS", json.dumps(dict(totals)))


if __name__ == "__main__":
    main(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 4)
