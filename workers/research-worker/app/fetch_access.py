"""记录哪些站点拒绝自动读取，供抓取排序和定向检索避开。

出版社和论文索引站常对自动读取返回 403：实测 ACM Digital Library、ScienceDirect、ResearchGate 用 DeepSeek
读取、Tavily Extract 和直接抓取都读不到正文。规划把这类站点列为权威域名时，site: 检索词的命中全部读不了，
抓取名额被白白占掉。除已知站点外，本进程里某个站点连续两次被拒且从未读到正文，也按受阻处理。
"""

from __future__ import annotations

import re
import threading
import urllib.parse

KNOWN_BLOCKED_HOSTS = frozenset({"dl.acm.org", "sciencedirect.com", "researchgate.net"})
# 同一站点被拒绝几次且没有成功读取过，就认为它拒绝自动读取
BLOCKED_AFTER_DENIALS = 2
_DENIED_CODE = re.compile(r"HTTP_40[13]\b")

_lock = threading.Lock()
_outcomes: dict[str, list[int]] = {}


def site_host(url_or_domain: str) -> str:
    value = url_or_domain.strip().casefold()
    host = urllib.parse.urlsplit(value).hostname if "://" in value else value.split("/", 1)[0]
    host = (host or "").removeprefix("www.")
    return host


def record_fetch_outcome(url: str, readable: bool, failure_code: str) -> None:
    host = site_host(url)
    if not host:
        return
    with _lock:
        outcome = _outcomes.setdefault(host, [0, 0])
        if readable:
            outcome[0] += 1
        elif _DENIED_CODE.search(failure_code or ""):
            outcome[1] += 1


def fetch_blocked(url_or_domain: str) -> bool:
    host = site_host(url_or_domain)
    if not host:
        return False
    if any(host == blocked or host.endswith("." + blocked) for blocked in KNOWN_BLOCKED_HOSTS):
        return True
    with _lock:
        readable, denied = _outcomes.get(host, (0, 0))
    return readable == 0 and denied >= BLOCKED_AFTER_DENIALS


def reset_fetch_outcomes() -> None:
    """测试用：清空本进程的读取记录。"""
    with _lock:
        _outcomes.clear()
