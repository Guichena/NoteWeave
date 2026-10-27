"""成文原文里没有答案的必答要点，在该次运行抓到的网页全文里有没有：有则损失在抽取，没有则在检索。

用法：python3 run_with_env.py exp/page_evidence.py <point_evidence.py 的输出> <明细输出> [并发数]
只看 point_evidence.py 判为 NONE 的要点。先让模型给出要点的中英文关键词，按关键词命中从该次运行的
research_external_snapshot 里挑最相关的段落，再让模型判断这些段落能否回答要点。关键词预筛可能漏掉
措辞不同的段落，结论偏保守（判为检索缺失的里面可能有少数其实抓到了）。不输出密钥。
"""
import base64
import collections
import json
import os
import re
import subprocess
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor

ROOT = "/data/workspace/noteweave-research-eval"
TAB = chr(9)
_PARAGRAPH_CHARS = 700
_TOP_PARAGRAPHS = 14

KEYWORDS = ("给定研究问题和一个评审要点，列出在英文或中文技术文档里回答该要点时最可能出现的关键词或短语，"
            "每个比较对象分别列出，共 6 到 12 个，英文为主，不要列问题里所有段落都会出现的对象名本身。"
            "只输出 JSON：{\"keywords\": [...]}")
JUDGE = ("你判断评审要点能否从给定网页段落中得到回答。段落来自研究过程中抓到的网页。对比类要点需要两边各有明确陈述；"
         "只是相关背景、只答了一边、或需要段落之外的知识才能回答的，answerable 为 false。answerable 为 true 时在 "
         "paragraphs 里列出用到的段落编号，并在 answer 里用一句话写出答案；为 false 时在 partial 里写清缺什么、"
         "哪一边有。只输出 JSON：{\"answerable\": true/false, \"paragraphs\": [...], \"answer\": \"...\", \"partial\": \"...\"}")


def chat(system, user):
    base = os.environ["NOTEWEAVE_RESEARCH_LLM_BASE_URL"].rstrip("/")
    body = json.dumps({"model": os.environ["NOTEWEAVE_RESEARCH_LLM_MODEL"], "temperature": 0,
                       "response_format": {"type": "json_object"},
                       "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}]}).encode()
    request = urllib.request.Request(base + "/chat/completions", data=body, headers={
        "Content-Type": "application/json", "Authorization": "Bearer " + os.environ["NOTEWEAVE_RESEARCH_LLM_API_KEY"]})
    with urllib.request.urlopen(request, timeout=300) as response:
        return json.loads(json.loads(response.read())["choices"][0]["message"]["content"])


def snapshots(run_id):
    sql = ("select replace(to_base64(source_url), char(10), ''), replace(to_base64(content_text), char(10), '') "
           f"from research_external_snapshot where research_run_id='{run_id}'")
    out = subprocess.run([f"{ROOT}/qraw.sh", sql], capture_output=True, text=True).stdout
    pages = {}
    for line in out.splitlines():
        parts = line.split(TAB)
        if len(parts) == 2:
            url, text = (base64.b64decode(part).decode("utf-8") for part in parts)
            pages.setdefault(url, text)
    return pages


def paragraphs(pages):
    for url, text in pages.items():
        lines, size = [], 0
        for line in text.splitlines():
            lines.append(line)
            size += len(line) + 1
            if size >= _PARAGRAPH_CHARS:
                yield url, "\n".join(lines)
                lines, size = [], 0
        if lines:
            yield url, "\n".join(lines)


def analyse(item, record_of):
    record = record_of(item["label"], item["case"])
    question = record.get("question") or item["case"]
    keywords = [str(word).casefold() for word in chat(KEYWORDS, json.dumps(
        {"question": question, "point": item["point"]}, ensure_ascii=False)).get("keywords", []) if str(word).strip()]
    pages = snapshots(record["run_id"])
    scored = []
    for url, text in paragraphs(pages):
        folded = text.casefold()
        hits = sum(1 for word in keywords if word in folded)
        if hits:
            scored.append((hits, url, text))
    scored.sort(key=lambda row: -row[0])
    chosen = [{"id": index, "url": url, "text": text} for index, (_, url, text) in enumerate(scored[:_TOP_PARAGRAPHS])]
    verdict = chat(JUDGE, json.dumps({"question": question, "point": item["point"], "paragraphs": chosen},
                                     ensure_ascii=False)) if chosen else {"answerable": False, "partial": "关键词没有命中任何段落"}
    urls = sorted({chosen[index]["url"] for index in verdict.get("paragraphs", [])
                   if isinstance(index, int) and 0 <= index < len(chosen)})
    return {**item, "pages": len(pages), "keywords": keywords, "answerable": bool(verdict.get("answerable")),
            "urls": urls, "answer": verdict.get("answer", ""), "partial": verdict.get("partial", "")}


def main(path, out_path, workers):
    items = []
    for line in open(path, encoding="utf-8"):
        match = re.match(r"(\S+)\s+(\S+)\s+NONE\s+(.+?) \|", line)
        if match:
            items.append({"label": "final-" + match.group(1), "case_prefix": match.group(2), "point": match.group(3)})
    records = {}

    def record_of(label, case):
        if (label, case) not in records:
            records[(label, case)] = json.load(open(f"{ROOT}/runs/{label}/merged/{case}.json", encoding="utf-8"))
        return records[(label, case)]

    for item in items:
        # point_evidence.py 的输出把题目名截到 30 个字符，这里按前缀找回完整题目名
        prefix = item.pop("case_prefix")
        item["case"] = next(name[:-5] for name in sorted(os.listdir(f"{ROOT}/runs/{item['label']}/merged"))
                            if name.startswith(prefix))
    totals = collections.Counter()
    results = []
    with ThreadPoolExecutor(workers) as pool:
        for result in pool.map(lambda item: analyse(item, record_of), items):
            results.append(result)
            totals["answerable" if result["answerable"] else "not_found"] += 1
            print(f"{result['label'][6:]:<18} {result['case'][:30]:<30} {'PAGE' if result['answerable'] else 'NONE'} "
                  f"{result['point']} | {result['answer'] or result['partial']} | {' '.join(result['urls'])}", flush=True)
    json.dump(results, open(out_path, "w", encoding="utf-8"), ensure_ascii=False)
    print("TOTALS", json.dumps(dict(totals)))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 4)
