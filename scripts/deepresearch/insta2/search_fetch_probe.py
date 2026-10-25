"""在 Worker 容器内对几条检索词各搜一次，逐个抓取前几个命中，统计哪些站点、哪种失败原因占掉了阅读名额。

用法：docker exec -i <worker> python - < search_fetch_probe.py
"""
import collections
import os
import urllib.parse

from app.fetch_adapters import _build_default_url_snapshot_transport
from app.search_adapters import DeepSeekSearchTransport

QUERIES = [
    "pair programming software quality no significant effect mixed results case study",
    "pair programming software quality meta-analysis systematic review defect reduction significance",
    "empirical study coverage threshold defect escape rate arxiv",
    "branch coverage false confidence defect escapes",
    "test-driven development meta-analysis no significant defect rate reduction",
    "code review effectiveness empirical study defects found",
]

search = DeepSeekSearchTransport(os.environ["NOTEWEAVE_RESEARCH_DEEPSEEK_SEARCH_API_KEY"])
transport = _build_default_url_snapshot_transport()
by_host = collections.Counter()
failed_host = collections.Counter()
codes = collections.Counter()
for query in QUERIES:
    print("##", query)
    for item in search.search(query, 7)[:7]:
        url = str(item["url"])
        host = urllib.parse.urlsplit(url).hostname or ""
        snapshot = transport.read(url, query, 4000)
        ok = bool(str(snapshot.get("text") or "").strip())
        by_host[host] += 1
        if not ok:
            failed_host[host] += 1
            codes[str(snapshot.get("fetch_failure_code"))] += 1
        print("  %s %s %s" % ("OK  " if ok else "FAIL", url[:110], "" if ok else snapshot.get("fetch_failure_code")))
print("hosts:", by_host.most_common(12))
print("failed hosts:", failed_host.most_common(12))
print("codes:", codes.most_common(8))
