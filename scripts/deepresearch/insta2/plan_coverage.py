"""统计规划是否覆盖了评测的必答要点：对每题的每个 must_cover，判断规划的专家要点或列是否涉及。

用法：python3 run_with_env.py plan_coverage.py <标签> <数据集路径>
用评分模型判断，只输出统计和未覆盖的条目，不输出密钥。
"""
import glob
import json
import os
import sys
import urllib.request


def judge(messages):
    base = os.environ["NOTEWEAVE_RESEARCH_LLM_BASE_URL"].rstrip("/")
    body = json.dumps({"model": os.environ["NOTEWEAVE_RESEARCH_LLM_MODEL"], "messages": messages,
                       "temperature": 0, "response_format": {"type": "json_object"}}).encode()
    request = urllib.request.Request(base + "/chat/completions", data=body, headers={
        "Content-Type": "application/json", "Authorization": "Bearer " + os.environ["NOTEWEAVE_RESEARCH_LLM_API_KEY"]})
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.loads(json.loads(response.read())["choices"][0]["message"]["content"])


def main(label, dataset):
    cases = {item["case_key"]: item for item in json.load(open(dataset, encoding="utf-8"))["cases"]}
    total = covered = 0
    for path in sorted(glob.glob(f"/data/workspace/noteweave-research-eval/runs/{label}/merged/*.json")):
        record = json.load(open(path, encoding="utf-8"))
        case = cases[record["case_key"]]
        plan = (record["resources"].get("plan") or {}).get("data") or {}
        points = (plan.get("brief") or {}).get("required_points", [])
        columns = [f"{column.get('label')}: {column.get('description') or ''}"[:300] for column in plan.get("columns", [])]
        result = judge([
            {"role": "system", "content": "你判断研究规划是否涉及评审要点。对每个 must_cover 条目，只要规划的某个专家要点或某一列"
                                          "明确要求研究这件事，就算 covered。只输出 JSON：{\"items\": [{\"item\": 原文, \"covered\": true/false}]}"},
            {"role": "user", "content": json.dumps({"question": case["question"], "must_cover": case["must_cover"],
                                                    "required_points": points, "columns": columns}, ensure_ascii=False)}])
        items = result.get("items", [])
        missed = [item["item"] for item in items if not item.get("covered")]
        total += len(case["must_cover"])
        covered += len(case["must_cover"]) - len(missed)
        print(record["case_key"].ljust(32), f"{len(case['must_cover']) - len(missed)}/{len(case['must_cover'])}",
              ("缺：" + "、".join(missed)) if missed else "")
    print(f"TOTAL {covered}/{total}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
