"""评分缺失的必答要点，成文拿到的原文里有没有答案：有则损失在成文，没有则在检索或抽取。

用法：python3 run_with_env.py point_evidence.py <gather_synth.py 的输出>...
用评分模型判断，只输出统计和逐条结论，不输出密钥。
"""
import collections
import json
import os
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor

SYSTEM = ("你判断评审要点能否从给定原文中得到回答。对每个 missing 要点：如果 quotes 里某几条原文明确陈述了回答该要点"
          "所需的事实（对比类要点需要两边各有原文），answerable 为 true，并在 keys 里列出这些原文的 key；只是相关背景、"
          "只答了一边、或需要原文之外的知识才能回答的，answerable 为 false，partial 写清缺什么。"
          "只输出 JSON：{\"items\": [{\"item\": 要点原文, \"answerable\": true/false, \"keys\": [...], \"partial\": \"...\"}]}")


def judge(messages):
    base = os.environ["NOTEWEAVE_RESEARCH_LLM_BASE_URL"].rstrip("/")
    body = json.dumps({"model": os.environ["NOTEWEAVE_RESEARCH_LLM_MODEL"], "messages": messages,
                       "temperature": 0, "response_format": {"type": "json_object"}}).encode()
    request = urllib.request.Request(base + "/chat/completions", data=body, headers={
        "Content-Type": "application/json", "Authorization": "Bearer " + os.environ["NOTEWEAVE_RESEARCH_LLM_API_KEY"]})
    with urllib.request.urlopen(request, timeout=300) as response:
        return json.loads(json.loads(response.read())["choices"][0]["message"]["content"])


def quotes_of(synthesis_input):
    quotes, seen = [], set()
    for group, kind in ((synthesis_input.get("cells") or [], "VERIFIED"), (synthesis_input.get("leads") or [], "LEAD")):
        for cell in group:
            for quote in cell.get("evidence_quotes") or []:
                key = quote["evidence_key"]
                if key in seen:
                    continue
                seen.add(key)
                quotes.append({"key": key[-10:], "kind": kind, "entity": cell.get("entity_label", ""),
                               "column": cell.get("column_label", ""), "quote": quote["quote"][:700]})
    return quotes


def analyse(item):
    quotes = quotes_of(item["synthesis_input"])
    kinds = {quote["key"]: quote["kind"] for quote in quotes}
    try:
        result = judge([{"role": "system", "content": SYSTEM}, {"role": "user", "content": json.dumps({
            "question": item["synthesis_input"].get("question", ""), "missing": item["missing"],
            "quotes": quotes}, ensure_ascii=False)}])
    except Exception as exc:  # noqa: BLE001
        return item, [], f"{type(exc).__name__}"
    rows = []
    for entry in result.get("items", []):
        keys = [key for key in entry.get("keys") or [] if key in kinds]
        source = "NONE"
        if entry.get("answerable") and keys:
            source = "VERIFIED" if any(kinds[key] == "VERIFIED" for key in keys) else "LEAD"
        rows.append((entry.get("item", ""), source, str(entry.get("partial") or "")[:120]))
    return item, rows, None


def main(paths):
    items = [item for path in paths for item in json.load(open(path, encoding="utf-8")) if item["missing"]]
    totals = collections.Counter()
    with ThreadPoolExecutor(6) as pool:
        for item, rows, error in pool.map(analyse, items):
            if error:
                print(f"{item['label']} {item['case']} ERROR {error}")
                totals["errors"] += 1
                continue
            for point, source, partial in rows:
                totals[source] += 1
                print(f"{item['label'][6:]:<20} {item['case'][:30]:<30} {source:<8} {point[:40]} | {partial}")
    print("TOTALS", json.dumps(dict(totals)))


if __name__ == "__main__":
    main(sys.argv[1:])
