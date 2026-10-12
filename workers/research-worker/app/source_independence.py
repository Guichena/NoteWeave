"""注册域名、转载指纹和独立来源置信度；PSL 只读取依赖包自带的快照。"""
import hashlib
import re
import unicodedata
from collections import Counter
from decimal import Decimal, ROUND_HALF_UP
from urllib.parse import urlsplit

import tldextract

_extract = tldextract.TLDExtract(cache_dir=None, suffix_list_urls=(), include_psl_private_domains=True)
CONFIDENCE_CAP = 950_000


def registered_domain(domain):
    normalized = unicodedata.normalize("NFKC", domain).strip().casefold().rstrip(".")
    if normalized.startswith(("workspace-source:", "external-source:")):
        return normalized
    host = urlsplit(normalized if "://" in normalized else "//" + normalized).hostname or normalized
    host = host.encode("idna").decode("ascii")
    return _extract(host).top_domain_under_public_suffix or host


def quote_tokens(text):
    return re.findall(r"[a-z0-9]+|[\u3400-\u9fff]", unicodedata.normalize("NFKC", text).lower())


def simhash(text):
    weights = [0] * 64
    for token, count in Counter(quote_tokens(text)).items():
        digest = int.from_bytes(hashlib.sha256(token.encode("utf-8")).digest()[:8], "big")
        for bit in range(64):
            weights[bit] += count if digest & (1 << bit) else -count
    return sum(1 << bit for bit, weight in enumerate(weights) if weight > 0)


def independent_confidence(evidence):
    parents = list(range(len(evidence)))
    def root(index):
        while parents[index] != index:
            parents[index] = parents[parents[index]]
            index = parents[index]
        return index
    for i, left in enumerate(evidence):
        for j in range(i):
            right = evidence[j]
            duplicate = (registered_domain(left["source_domain"]) == registered_domain(right["source_domain"])
                         or (left.get("lineage_digest") and left.get("lineage_digest") == right.get("lineage_digest"))
                         or left["quote_text"] == right["quote_text"]
                         or (min(len(quote_tokens(left["quote_text"])), len(quote_tokens(right["quote_text"]))) >= 4
                             and (simhash(left["quote_text"]) ^ simhash(right["quote_text"])).bit_count() <= 3))
            if duplicate:
                parents[root(i)] = root(j)
    strengths = {}
    for index, item in enumerate(evidence):
        key = root(index)
        strengths[key] = max(strengths.get(key, 0), int(item["support_score_ppm"]))
    remaining = Decimal(1)
    for score in strengths.values():
        remaining *= Decimal(1) - Decimal(score) / Decimal(1_000_000)
    confidence = int(((1 - remaining) * 1_000_000).quantize(Decimal(1), rounding=ROUND_HALF_UP))
    return len(strengths), min(CONFIDENCE_CAP, confidence)
