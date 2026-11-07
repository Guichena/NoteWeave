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


def is_negative_availability(claim: str) -> bool:
    return bool(_NEGATIVE.search(claim))


def entity_versions(label: str) -> set[str]:
    """只取研究对象的发布版本，忽略规范编号、edition 年份与协议名称中的数字。"""
    label = re.sub(r"\b(?:RFC|PEP|JEP|CVE|ISO|IEC)\s*[-:]?\s*\d+(?:[-.:]\d+)*", "", label, flags=re.I)
    label = re.sub(r"\b(?:19|20)\d{2}\s*(?:edition|版|版本|年版)\b|\bedition\s+(?:19|20)\d{2}\b", "", label, flags=re.I)
    return set(re.findall(r"(?<![\w./-])(\d+(?:\.\d+)*)(?![\w.])", label))


def later_introduction(versions: set[str], quote: str, entity_label: str = "", *, scan_window: bool = False) -> bool:
    """只识别目标版本之后的明确引入边界，保留原文供后续抽取和核验。"""
    if not versions:
        return False
    product = re.split(r"\s+v?\d", entity_label, maxsplit=1)[0].strip()
    boundary_quote = re.sub(re.escape(product) + r"\s+(?=v?\d)", "", quote, flags=re.I) if product and product != entity_label else quote
    if scan_window:
        # 页面可能先谈另一个功能的旧版引入时间；这里只决定是否展示原文，不授予结论资格。
        return any(all(tuple(int(part) for part in version.split(".")) <
                       tuple(int(part) for part in match.group(1).split(".")) for version in versions)
                   for match in [*_INTRODUCED.finditer(boundary_quote), *_INTRODUCED_ZH.finditer(boundary_quote)])
    introduced = _INTRODUCED.search(boundary_quote) or _INTRODUCED_ZH.search(boundary_quote)
    if not introduced:
        return False
    boundary = tuple(int(part) for part in introduced.group(1).split("."))
    return all(tuple(int(part) for part in version.split(".")) < boundary for version in versions)


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


def document_family(source: object) -> tuple[str, str] | None:
    """同站点、同文档路径的语言和版本别名；只有识别到版本目录时才归并。"""
    parsed = urlsplit(str(getattr(source, "url", "") or ""))
    if parsed.query:
        return None
    segments = [part for part in parsed.path.split("/") if part]
    if segments and re.fullmatch(r"[a-z]{2}(?:-[a-z]{2})?", segments[0], re.I):
        segments = segments[1:]
    for index, part in enumerate(segments):
        if (index == 0 or segments[index - 1].lower() in _DOC_DIRS) and re.fullmatch(
                r"v?\d{1,3}(?:\.\d+)*|dev|latest|current|stable", part, re.I):
            return (parsed.hostname or "", "/".join(segments[:index] + segments[index + 1:]))
    return None


def supports_entity_version(versions: set[str], card: object, source: object, entity_label: str = "") -> bool:
    if not versions:
        return True
    quote = str(getattr(card, "quote_text", "") or "")
    claim = str(getattr(card, "claim_text", "") or "")
    product = re.split(r"\s+v?\d", entity_label, maxsplit=1)[0].strip()
    # 旧版提案即使写着目标版本，也不能用将来时的设计承诺证明已经发布的能力。
    proposal = re.search(r"(?:^|/)(?:pep-\d+|jep-\d+|jeps/\d+)(?:[./]|$)",
                         urlsplit(str(getattr(source, "url", "") or "")).path, re.I)
    draft = re.search(r"(?im)^[ \t|*+-]*(?:Status|状态)[ \t|*]*(?:[:|])[ \t]*"
                      r"(?:\n[ \t]*[-*]?[ \t]*)?(?:Draft|Proposed|Deferred|Withdrawn|草案|提案)\b",
                      str(getattr(source, "window_text", "") or "")[:2000])
    if draft or (proposal and re.search(r"\bproposes?\b|\bwould\s+(?:be|support|provide|add|allow)\b|\bwill\s+(?:be|remain|support|provide|add|allow)\b|提议|拟议|计划(?:提供|支持|加入)|将(?:提供|支持|加入|保持)", quote, re.I)):
        return False
    # 只有明确的实际引入边界才能证明旧版缺少功能；草案和将来的承诺不能充当该边界。
    if is_negative_availability(claim) and later_introduction(versions, quote, entity_label):
        return True
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
