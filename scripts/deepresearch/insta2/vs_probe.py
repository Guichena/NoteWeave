"""在 Worker 镜像的临时容器里运行：比较题各写一条点名全部比较对象的检索词（A vs B 加主题），
用评测同样的搜索渠道搜索、抓取前几篇网页，再判断此前缺失的要点能否从这些网页回答。

用法：python vs_probe.py <vs_input.py 的输出> <明细输出> [每题抓取篇数]
只输出检索词、网址与结论，不输出密钥。判断提示与 page_evidence.py 相同。
"""
import collections
import json
import os
import sys
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

from app.fetch_access import fetch_blocked
from app.fetch_adapters import _build_default_url_snapshot_transport
from app.search_adapters import _build_external_search_adapters

_PARAGRAPH_CHARS = 700
_TOP_PARAGRAPHS = 14

QUERY = ("给定一道比较题和各比较对象的名称，写一条英文网页检索词，用来找到同时讲两边的对比文章：点名全部比较对象，"
         "用 vs 连接，再加上题目关心的主题，共 3 到 9 个词，不要 site: 限定。只输出 JSON：{\"query\": \"...\"}")
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


def search(query, limit=8):
    for adapter in _build_external_search_adapters():
        try:
            results = adapter.transport.search(query, limit)
        except Exception as error:  # 换下一个渠道
            print("search failed", adapter.provider_name, type(error).__name__, flush=True)
            continue
        if results:
            return adapter.provider_name, results
    return "", []


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


def probe(case, fetch_count, transport):
    query = str(chat(QUERY, json.dumps({"question": case["question"], "entities": case["entities"]},
                                       ensure_ascii=False)).get("query") or "").strip()
    provider, results = search(query)
    pages = {}
    for result in results:
        url = str(result.get("url") or "")
        if len(pages) >= fetch_count or not url or fetch_blocked(url):
            continue
        try:
            snapshot = transport.read(url, query, 12000)
        except Exception:
            continue
        text = str(snapshot.get("text") or "")
        if len(text.strip()) > 200:
            pages[url] = text
    verdicts = []
    for point in case["points"]:
        keywords = [str(word).casefold() for word in chat(KEYWORDS, json.dumps(
            {"question": case["question"], "point": point["point"]}, ensure_ascii=False)).get("keywords", [])
                    if str(word).strip()]
        scored = []
        for url, text in paragraphs(pages):
            hits = sum(1 for word in keywords if word in text.casefold())
            if hits:
                scored.append((hits, url, text))
        scored.sort(key=lambda row: -row[0])
        chosen = [{"id": index, "url": url, "text": text} for index, (_, url, text) in enumerate(scored[:_TOP_PARAGRAPHS])]
        verdict = chat(JUDGE, json.dumps({"question": case["question"], "point": point["point"], "paragraphs": chosen},
                                         ensure_ascii=False)) if chosen else {"answerable": False, "partial": "关键词没有命中任何段落"}
        urls = sorted({chosen[index]["url"] for index in verdict.get("paragraphs", [])
                       if isinstance(index, int) and 0 <= index < len(chosen)})
        verdicts.append({**point, "answerable": bool(verdict.get("answerable")), "urls": urls,
                         "note": verdict.get("answer") or verdict.get("partial") or ""})
    return {**case, "query": query, "provider": provider, "result_urls": [str(r.get("url")) for r in results],
            "fetched": list(pages), "verdicts": verdicts}


def main(path, out_path, fetch_count):
    cases = json.load(open(path, encoding="utf-8"))
    transport = _build_default_url_snapshot_transport()
    totals = collections.Counter()
    results = []
    with ThreadPoolExecutor(4) as pool:
        for result in pool.map(lambda case: probe(case, fetch_count, transport), cases):
            results.append(result)
            hosts = " ".join(urllib.parse.urlsplit(url).hostname or url for url in result["fetched"])
            print(f"## {result['case']} | {result['query']} | {result['provider']} | fetched {len(result['fetched'])}: {hosts}",
                  flush=True)
            for verdict in result["verdicts"]:
                totals["answerable" if verdict["answerable"] else "not_found"] += 1
                print(f"   {verdict['label'][6:]:<14} {'PAGE' if verdict['answerable'] else 'NONE'} {verdict['point']} | "
                      f"{verdict['note'][:160]} | {' '.join(verdict['urls'])}", flush=True)
    json.dump(results, open(out_path, "w", encoding="utf-8"), ensure_ascii=False)
    print("TOTALS", json.dumps(dict(totals)))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 4)
