"""检查证据适用的发布版本；来源权威性不能替代版本适用性。"""
from __future__ import annotations

import re
from urllib.parse import urlsplit

_DOC_DIRS = {"docs", "doc", "documentation", "manual", "refman", "reference", "javase", "pdf"}
_METADATA = re.compile(r"(?im)^\s*(?:[-*|]\s*)?(?:Python-Version|JDK-Version|Release-Version|Release)\s*[:|]\s*"
                       r"(?:\n\s*[-*]?\s*)?v?(\d+(?:\.\d+)*)\b")
_INTRODUCED = re.compile(r"(?i)(?:starting\s+(?:with\s+)?(?:the\s+)?|(?:added|introduced|new)\s+in\s+(?:version\s+)?"
                         r"|since\s+(?:version\s+)?)(\d+(?:\.\d+)+)\b")
_INTRODUCED_ZH = re.compile(r"(?:从|自)\s*(\d+(?:\.\d+)+)\s*(?:版本\s*)?(?:开始|起)")
_NEGATIVE = re.compile(r"(?i)\b(?:unavailable|unsupported)\b|\b(?:does|do|did)\s+not\s+(?:support|provide|offer)\b"
                       r"|\bnot\s+(?:yet\s+)?(?:available|supported|provided|offered)\b"
                       r"|不支持|尚未提供|未提供|不提供|不可用")


def source_versions(source: object) -> set[str]:
    path = urlsplit(str(getattr(source, "url", "") or "")).path
    # 网址中同一页面可能有发布版本和文档站版本；具体章节路径优先，正文语义仍由答案检查核对。
    found = set(re.findall(r"(?<![\w.])v?(\d+\.\d+(?:\.\d+)*)(?![\w.])", path))
    segments = [part for part in path.lower().split("/") if part]
    found |= {part.removeprefix("v") for previous, part in zip(segments, segments[1:])
              if previous in _DOC_DIRS and re.fullmatch(r"v?\d{1,3}", part)}
    metadata = set(_METADATA.findall(str(getattr(source, "window_text", "") or "")))
    # 文档中的示例配置不能覆盖网址明确绑定的发布版本。
    return found or metadata


def supports_entity_version(versions: set[str], card: object, source: object, entity_label: str = "") -> bool:
    if not versions:
        return True
    quote = str(getattr(card, "quote_text", "") or "")
    claim = str(getattr(card, "claim_text", "") or "")
    product = re.split(r"\s+v?\d", entity_label, maxsplit=1)[0].strip()
    boundary_quote = re.sub(re.escape(product) + r"\s+(?=v?\d)", "", quote, flags=re.I) if product and product != entity_label else quote
    # 明确的引入边界可以证明旧版没有这项能力；仍须通过后续逐项语义核验。
    introduced = _INTRODUCED.search(boundary_quote) or _INTRODUCED_ZH.search(boundary_quote)
    if introduced and _NEGATIVE.search(claim):
        boundary = tuple(int(part) for part in introduced.group(1).split("."))
        if all(tuple(int(part) for part in version.split(".")) < boundary for version in versions):
            return True
    # 旧版提案即使写着目标版本，也不能用将来时的设计承诺证明已经发布的能力。
    proposal = re.search(r"(?:^|/)(?:pep-\d+|jep-\d+|jeps/\d+)(?:[./]|$)",
                         urlsplit(str(getattr(source, "url", "") or "")).path, re.I)
    draft = re.search(r"(?im)^[ \t|*+-]*(?:Status|状态)[ \t|*]*(?:[:|])[ \t]*"
                      r"(?:\n[ \t]*[-*]?[ \t]*)?(?:Draft|Proposed|Deferred|Withdrawn|草案|提案)\b",
                      str(getattr(source, "window_text", "") or "")[:2000])
    if (proposal or draft) and re.search(r"\bwill\s+(?:be|remain|support|provide|add|allow)\b|计划(?:提供|支持|加入)|将(?:提供|支持|加入|保持)", quote, re.I):
        return False
    scoped = source_versions(source)
    if scoped:
        return bool(scoped & versions)
    # 无版本的网址或提案不能仅凭官方域名证明某一发布版；引文须明确包含目标版本。
    # 普通计数（如 12 个线程）不能证明版本 12；只识别明确的版本词或当前产品名。
    prefixes = [r"\bversion", r"\brelease", "版本", "发行版"]
    if product and product != entity_label:
        prefixes.append(re.escape(product))
    quoted = set(re.findall(r"(?i)(?:" + "|".join(prefixes) + r")\s*v?(\d+(?:\.\d+)*)(?![\w.])", quote))
    return bool(quoted & versions)
