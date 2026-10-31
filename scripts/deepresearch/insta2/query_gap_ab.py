"""宿主机侧：对 page_evidence.out 里 NONE 的要点，分别判断两版重放的检索词有没有一条专门问到它。

用法：python3 run_with_env.py exp/query_gap_ab.py exp/page_evidence.out exp/replay_queries.json
两版分开各问一次评分模型，不让它看到另一版。只输出统计与逐条结果，不输出密钥。
"""
import collections
import glob
import json
import os
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor

ROOT = "/data/workspace/noteweave-research-eval"
DEV = "/data/workspace/noteweave-research-dev2"
DATASETS = {"dev": f"{DEV}/experiments/deep-research/quality-eval/dataset.json",
            "holdout": f"{DEV}/experiments/deep-research/quality-eval/dataset-holdout.json"}
SYSTEM = ("你判断检索词是否专门问到某个要点。只输出 JSON：{\"targeted\": true/false, \"query\": \"专门问到它的检索词原文，没有则为空\"}。"
          "专门问到：这条检索词的主题就是这个要点（或该要点在某个研究对象上的一侧），用词能让搜索引擎找到讲这件事的页面。"
          "只是在罗列多个概念的长检索词里顺带出现一个相关词，不算专门问到。")


def chat(user):
    base = os.environ["NOTEWEAVE_RESEARCH_LLM_BASE_URL"].rstrip("/")
    body = json.dumps({"model": os.environ["NOTEWEAVE_RESEARCH_LLM_MODEL"], "temperature": 0,
                       "response_format": {"type": "json_object"},
                       "messages": [{"role": "system", "content": SYSTEM}, {"role": "user", "content": user}]}).encode()
    request = urllib.request.Request(base + "/chat/completions", data=body, headers={
        "Content-Type": "application/json", "Authorization": "Bearer " + os.environ["NOTEWEAVE_RESEARCH_LLM_API_KEY"]})
    with urllib.request.urlopen(request, timeout=180) as response:
        return json.loads(json.loads(response.read())["choices"][0]["message"]["content"])


def main(source, replay_path):
    replay = json.load(open(replay_path, encoding="utf-8"))
    jobs = []
    for line in open(source, encoding="utf-8"):
        parts = line.split()
        if parts[2:3] != ["NONE"]:
            continue
        run, prefix, point = parts[0], parts[1], " ".join(parts[3:]).split(" | ")[0]
        kind = run.split("-", 1)[0]
        tasks = [item for item in replay if item["run"] == run and item["case"].startswith(prefix)]
        case_key = tasks[0]["case"]
        question = {item["case_key"]: item for item in json.load(open(DATASETS[kind], encoding="utf-8"))["cases"]}[case_key]["question"]
        for side in ("old", "new"):
            queries = [{"entity": task["entity"], "query": query} for task in tasks for query in task[side]["queries"]]
            jobs.append((run, case_key, point, side, json.dumps(
                {"question": question, "point": point, "queries": queries}, ensure_ascii=False)))
    counts = collections.Counter()
    rows = collections.defaultdict(dict)
    with ThreadPoolExecutor(6) as pool:
        for (run, case, point, side, _), result in zip(jobs, pool.map(lambda job: chat(job[4]), jobs)):
            counts[side] += bool(result.get("targeted"))
            rows[(run, case, point)][side] = result
    for (run, case, point), sides in rows.items():
        print(f"{run:<16} {case[:30]:<30} {point[:24]:<24} "
              f"old {'Y' if sides['old'].get('targeted') else '-'} new {'Y' if sides['new'].get('targeted') else '-'} "
              f"| {sides['new'].get('query', '')[:90]}")
    print("TARGETED", dict(counts), "of", len(rows))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
