"""宿主机侧：replay_ab.py 产出的新旧两份报告，用评测的评分模型各评 3 次，配对比较必答要点覆盖与通过数。

用法：python3 run_with_env.py exp/judge_ab.py <replay_ab 输出> <明细输出> [并发数]
评分输入沿用该题原评测记录（引用列表、过程指标），只替换报告正文；报告末尾按后端 ResearchReportCitations
的写法附上引用核对（标题、原文摘录、网址），与真实评测看到的报告一致。合计只统计两边都评分成功的题目。不输出密钥。
"""
import base64
import collections
import glob
import json
import os
import re
import subprocess
import sys
import threading
from concurrent.futures import ThreadPoolExecutor

ROOT = "/data/workspace/noteweave-research-eval"
DEV = "/data/workspace/noteweave-research-dev"
sys.path.insert(0, f"{DEV}/scripts/deepresearch")
import score_quality_eval as sq  # noqa: E402

_records: dict[str, dict] = {}
_cases: dict[str, dict] = {}
_sources: dict[str, dict] = {}
_sources_lock = threading.Lock()
_INLINE = re.compile(r"\[(evidence:[0-9a-f]{16,64})]")
_AUDIT_HEADINGS = ("## 引用核对", "## Citation audit", "## 补充引用")
_OPEN, _CLOSE = chr(0x201C), chr(0x201D)
NL, TAB = chr(10), chr(9)


def _sources_of(run_id):
    with _sources_lock:
        if run_id not in _sources:
            sql = ("select evidence_key, replace(to_base64(coalesce(source_title, '')), char(10), ''), "
                   "replace(to_base64(coalesce(source_url, '')), char(10), ''), "
                   "replace(to_base64(coalesce(quote_text, '')), char(10), '') "
                   f"from source_evidence where research_run_id='{run_id}'")
            out = subprocess.run([f"{ROOT}/qraw.sh", sql], capture_output=True, text=True).stdout
            found = {}
            for line in out.splitlines():
                parts = line.split(TAB)
                if len(parts) == 4 and parts[0] not in found:
                    found[parts[0]] = [base64.b64decode(part).decode("utf-8") for part in parts[1:]]
            _sources[run_id] = found
        return _sources[run_id]


def _one_line(value):
    return " ".join((value or "").split())


def with_citation_audit(run_id, markdown):
    """与后端 ResearchReportCitations.consolidate 相同：去掉原有引用核对类小节，按首次出现顺序在末尾列出引用。"""
    keys = list(dict.fromkeys(_INLINE.findall(markdown)))
    if not keys:
        return markdown
    kept, skipping = [], False
    for line in markdown.split(NL):
        if line.startswith("## "):
            skipping = line.startswith(_AUDIT_HEADINGS)
        if not skipping:
            kept.append(line)
    body = re.sub(r"(?m)^\s*- Snapshot: `[^`]*`" + NL, "", NL.join(kept)).rstrip()
    sources = _sources_of(run_id)
    audit = ["", "", "## Citation audit", ""]
    for number, key in enumerate(keys, 1):
        title, url, quote = sources.get(key, ["", "", ""])
        audit += [f"### [{number}] {_one_line(title) or '来源'}", "", f"- Evidence: `{key}`"]
        quote = _one_line(quote)
        if quote:
            audit.append(f"  - Exact quote: {_OPEN}{quote[:300] + '…' if len(quote) > 300 else quote}{_CLOSE}")
        if url.strip():
            audit.append(f"  - URL: {url.strip()}")
        audit.append("")
    return body + NL.join(audit).rstrip() + NL


def load(label):
    if label in _records:
        return
    dataset = open(f"{ROOT}/runs/{label}/dataset.txt", encoding="utf-8").read().strip()
    dataset = dataset if os.path.isabs(dataset) else f"{DEV}/{dataset}"
    _cases[label] = {case["case_key"]: case for case in json.load(open(dataset, encoding="utf-8"))["cases"]}
    _records[label] = {}
    for path in glob.glob(f"{ROOT}/runs/{label}/merged/*.json"):
        record = json.load(open(path, encoding="utf-8"))
        _records[label][record["case_key"]] = record


def judge(row, call):
    case, record = _cases[row["label"]][row["case"]], _records[row["label"]][row["case"]]
    result = {"label": row["label"], "case": row["case"]}
    for side in ("old", "new"):
        replayed = {**record, "run_detail": {**(record.get("run_detail") or {}),
                                             "final_report_markdown": with_citation_audit(
                                                 record["run_id"], row[side]["markdown"])}}
        scored = sq.score_case(case, replayed, call, samples=3, cache=None)
        verdict = scored["judge"] or {}
        result[side] = {"passed": scored["passed"],
                        "covered": [item.get("item") for item in verdict.get("must_cover", []) if item.get("covered")],
                        "missing": [item.get("item") for item in verdict.get("must_cover", []) if not item.get("covered")],
                        "scores": verdict.get("scores"), "status": verdict.get("status")}
    return result


def main(path, out_path, workers):
    sq.configure_judge_from_chat(os.environ)
    call = sq.openai_compatible_judge()
    rows = [row for row in json.load(open(path, encoding="utf-8")) if "old" in row and "new" in row]
    for row in rows:
        load(row["label"])
    totals = collections.Counter()
    results = []
    with ThreadPoolExecutor(workers) as pool:
        for result in pool.map(lambda row: judge(row, call), rows):
            results.append(result)
            totals["cases"] += 1
            # 任一边评分模型没给出结果（NOT_RUN 等）时整对不计入，否则会把评分失败算成漏写
            if any(result[side]["status"] != "SCORED" for side in ("old", "new")):
                totals["pairs_not_scored"] += 1
            else:
                for side in ("old", "new"):
                    totals[f"{side}_passed"] += bool(result[side]["passed"])
                    totals[f"{side}_covered"] += len(result[side]["covered"])
            print(f"{result['label'][6:]:<18} {result['case'][:34]:<34} "
                  f"old {len(result['old']['covered'])} {'P' if result['old']['passed'] else '-'}  "
                  f"new {len(result['new']['covered'])} {'P' if result['new']['passed'] else '-'}", flush=True)
    json.dump(results, open(out_path, "w", encoding="utf-8"), ensure_ascii=False)
    print("TOTALS", json.dumps(dict(totals)))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 4)
