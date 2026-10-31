"""page_evidence.out 里抓到的网页也没有答案（NONE）的要点：规划有没有要求、检索词有没有专门问到。

用法：python3 run_with_env.py exp/query_gap.py exp/page_evidence.out
对每个要点，把题目、所有取证任务的实体、列要求与检索词交给评分模型，分为：
PLAN_MISSING（没有列要求研究这件事）、QUERY_MISSING（列有要求，但没有一条检索词专门问它）、
QUERY_PRESENT（有检索词专门问它，搜索结果里没有答案），并判断答案是否只在另一个比较对象的资料里（cross_entity）。
"""
import collections
import glob
import json
import os
import subprocess
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor

ROOT = "/data/workspace/noteweave-research-eval"
DEV = "/data/workspace/noteweave-research-dev2"
DATASETS = {"dev": f"{DEV}/experiments/deep-research/quality-eval/dataset.json",
            "holdout": f"{DEV}/experiments/deep-research/quality-eval/dataset-holdout.json"}


def query(sql):
    out = subprocess.run([f"{ROOT}/qraw.sh", sql], capture_output=True, text=True).stdout
    return [line.split("\t") for line in out.splitlines()]


def chat(system, user):
    base = os.environ["NOTEWEAVE_RESEARCH_LLM_BASE_URL"].rstrip("/")
    body = json.dumps({"model": os.environ["NOTEWEAVE_RESEARCH_LLM_MODEL"], "temperature": 0,
                       "response_format": {"type": "json_object"},
                       "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}]}).encode()
    request = urllib.request.Request(base + "/chat/completions", data=body, headers={
        "Content-Type": "application/json", "Authorization": "Bearer " + os.environ["NOTEWEAVE_RESEARCH_LLM_API_KEY"]})
    with urllib.request.urlopen(request, timeout=180) as response:
        return json.loads(json.loads(response.read())["choices"][0]["message"]["content"])


def tasks(run_id):
    rows = query("select t.role, t.execution_context_json, c.envelope_json from research_agent_task t "
                 "left join research_agent_completion c on t.id = c.research_agent_task_id "
                 f"where t.research_run_id='{run_id}' and t.role in ('DEEP_CELL','COUNTERFACTUAL') order by t.created_at")
    out = []
    for role, context_json, envelope_json in rows:
        policy = json.loads(context_json).get("query_policy") or {}
        envelope = json.loads(envelope_json) if envelope_json not in ("", "NULL") else {}
        report = (envelope.get("extraction_diagnostics") or {}).get("execution_report") or {}
        out.append({"role": role, "entity": policy.get("entity_label"),
                    "columns": [f"{spec.get('label')}: {str(spec.get('description') or '')[:400]}"
                                for spec in policy.get("column_specs") or []],
                    "queries": [item if isinstance(item, str) else item.get("query") for item in report.get("queries") or []]})
    return out


def classify(line):
    parts = line.split()
    run, prefix, point = parts[0], parts[1], " ".join(parts[3:]).split(" | ")[0]
    kind, label = run.split("-", 1)
    path = next(iter(glob.glob(f"{ROOT}/runs/final-{kind}-{label}/merged/{prefix}*.json")))
    record = json.load(open(path, encoding="utf-8"))
    case = {item["case_key"]: item for item in json.load(open(DATASETS[kind], encoding="utf-8"))["cases"]}[record["case_key"]]
    result = chat(
        "你分析深度研究系统为什么没有检索到某个必答要点。只输出 JSON："
        "{\"category\": \"PLAN_MISSING|QUERY_MISSING|QUERY_PRESENT\", \"cross_entity\": true/false, "
        "\"query\": \"专门问到该要点的检索词原文，没有则为空\", \"reason\": \"一句话\"}。"
        "PLAN_MISSING：没有任何一列的说明要求研究这个要点。QUERY_MISSING：有列要求，但该实体的检索词没有一条专门针对它"
        "（只是在长检索词里顺带一个词也算没有专门针对）。QUERY_PRESENT：有检索词专门针对它。"
        "cross_entity：比较题里这一侧的答案通常是没有某特性或要与另一侧对照才能说明，官方资料多写在另一个比较对象的文档或对比文章里。",
        json.dumps({"question": case["question"], "must_cover_point": point, "tasks": tasks(record["run_id"])},
                   ensure_ascii=False))
    return run, record["case_key"], point, result


def main(path):
    lines = [line.rstrip("\n") for line in open(path, encoding="utf-8") if line.split()[2:3] == ["NONE"]]
    counts = collections.Counter()
    with ThreadPoolExecutor(6) as pool:
        for run, case, point, result in pool.map(classify, lines):
            counts[result.get("category")] += 1
            counts["cross_entity"] += bool(result.get("cross_entity"))
            print(f"{run:<16} {case[:30]:<30} {result.get('category'):<14} cross={str(bool(result.get('cross_entity'))):<5} "
                  f"{point[:30]} | {result.get('query', '')[:80]} | {result.get('reason', '')[:120]}", flush=True)
    print("TOTALS", dict(counts))


if __name__ == "__main__":
    main(sys.argv[1])
