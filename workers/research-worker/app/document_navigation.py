"""在既有抓取权限内选择相关目录链接，链接文本只作为阅读线索。"""
import hashlib
import re
import posixpath
from html.parser import HTMLParser
from urllib.parse import urljoin, urlsplit, urlunsplit, unquote


class _Links(HTMLParser):
    def __init__(self):
        super().__init__()
        self.items, self.current, self.label = [], None, []

    def handle_starttag(self, tag, attrs):
        if tag == "a":
            self.current, self.label = dict(attrs).get("href"), []

    def handle_data(self, data):
        if self.current is not None:
            self.label.append(data)

    def handle_endtag(self, tag):
        if tag == "a" and self.current is not None:
            self.items.append({"url": self.current, "label": " ".join(self.label)[:300]})
            self.current = None


def document_links(text, base):
    parser = _Links()
    try:
        parser.feed(text[:2_000_000])
    except Exception:
        pass
    links = parser.items + [{"url": url, "label": label[:300]} for label, url in re.findall(r"\[([^\]\n]{1,300})\]\(([^\s)]+)\)", text)]
    result, seen = [], set()
    for link in links:
        try:
            url = urljoin(base, link["url"])
            parsed = urlsplit(url)
            if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password or len(url) > 2000:
                continue
            if url not in seen:
                result.append({"url": url, "label": link["label"]})
                seen.add(url)
        except ValueError:
            continue
        if len(result) == 200:
            break
    return result


def navigation_hits(snapshot, documents):
    from app.models import ResearchSearchHit
    policy = snapshot.query_policy.get("navigation_policy", {})
    maximum = min(2, max(0, int(policy.get("max_follow_pages", 0))))
    if not maximum or policy.get("max_depth") != 1:
        return []
    terms = set(re.findall(r"[a-z0-9_]{3,}|[\u4e00-\u9fff]{2,}", str(snapshot.query_policy.get("column_specs", "")).lower()))
    requirements = snapshot.query_policy.get("requirement_contract", {}).get("requirements", [])
    terms.update(re.findall(r"[a-z0-9_]{3,}|[\u4e00-\u9fff]{2,}", " ".join(req.get("text", "") for req in requirements).lower()))
    visited = {doc.url for doc in documents}
    candidates = []
    for doc in documents:
        base = urlsplit(doc.url)
        if doc.fetch_status != "FETCHED" or not base.hostname:
            continue
        for link in getattr(doc, "navigation_links", []):
            try:
                parsed = urlsplit(link["url"])
                # 同主机和同文档目录，禁止目录中的外部跳转、登录、下载和另一版本目录。
                directory = base.path.rsplit("/", 1)[0] + "/"
                decoded_path = unquote(parsed.path)
                if (parsed.hostname != base.hostname or parsed.port != base.port or parsed.scheme != base.scheme
                        or parsed.username or parsed.password or "\\" in decoded_path
                        or ".." in decoded_path.split("/") or not posixpath.normpath(decoded_path).startswith(posixpath.normpath(unquote(directory)).rstrip("/") + "/")
                        or re.search(r"(?:^|/)(?:login|logout|download)(?:/|$)|\.(?:zip|exe|tar|gz)$", parsed.path, re.I)):
                    continue
                url = urlunsplit(parsed)
                # 锚点窗口另行归档；HTTP 适配器会优先读取锚点附近的完整章节上下文。
                if url in visited:
                    continue
                label = str(link.get("label") or "")
                haystack = (label + " " + unquote(parsed.path)).lower()
                score = sum(term in haystack for term in terms)
                if not score:
                    continue
                candidates.append((score, url, label, doc))
            except (ValueError, KeyError):
                continue
    result = []
    for _, url, label, doc in sorted(candidates, key=lambda item: (-item[0], item[1])):
        if url in visited:
            continue
        visited.add(url)
        identity = hashlib.sha256(url.encode()).hexdigest()[:24]
        result.append(ResearchSearchHit(hit_id="navigation-" + identity, source_id="navigation-" + identity,
            source_title=label or doc.source_title, url=url, query=doc.query, rank=len(result) + 1, snippet="",
            confidence_score=0.5, retrieval_reason="目录相关章节定向阅读", provider=doc.provider, adapter="external_url",
            source_domain=doc.source_domain, source_quality=doc.source_quality, source_quality_score=doc.source_quality_score))
        if len(result) >= maximum:
            break
    return result
