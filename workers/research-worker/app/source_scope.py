"""按服务器冻结的版本与分支范围筛选来源，未知日期不推断为当前资料。"""
import re
from urllib.parse import urlsplit, unquote

_VERSION = re.compile(r"(?<![0-9.])([0-9]{1,3}(?:\.[0-9]+)*)(?![a-zA-Z0-9.])")
_DOC_DIRS = {"docs", "doc", "documentation", "manual", "refman", "reference", "javase", "pdf", "version", "versions", "release", "releases", "whatsnew", "release-notes", "changelog"}
_DEVELOPMENT = {"dev", "development", "nightly", "main", "master", "next"}
_CURRENT = {"current", "stable", "latest"}


def versions(label):
    text = re.sub(r"(?i)\b(?:RFC|PEP|JEP|CVE|ISO|IEC)\s*[-:]?\s*\d+(?:[-.:]\d+)*", "", label)
    text = re.sub(r"(?i)(?:19|20)\d{2}\s*(?:edition|年版|版本|版)|\bedition\s+(?:19|20)\d{2}", "", text)
    return list(dict.fromkeys(_VERSION.findall(text)))


def scope_for(snapshot, cell_key=None):
    contract = snapshot.query_policy.get("requirement_contract") or {}
    scopes = contract.get("source_scopes", [])
    if cell_key is not None:
        return next((item for item in scopes if item.get("cell_key") == cell_key), {})
    return scopes[0] if scopes else {}


def evaluate(scope, item, quote=""):
    if not scope:
        return {"reason_codes": [], "temporal_status": "UNKNOWN"}
    url = str(getattr(item, "url", "") or "")
    try:
        segments = [part for part in unquote(urlsplit(url).path).lower().split("/") if part]
    except ValueError:
        segments = []
    found = []
    for index, part in enumerate(segments):
        value = re.sub(r"^v(?=\d)", "", part)
        if index > 0 and segments[index - 1] in _DOC_DIRS:
            value = re.sub(r"\.(?:html?|md|rst)$", "", value)
        if re.fullmatch(r"\d{1,3}\.\d+(?:\.\d+)*", value) or (
                index > 0 and segments[index - 1] in _DOC_DIRS and re.fullmatch(r"\d{1,3}", value)):
            if value not in found:
                found.append(value)
    required = scope.get("required_versions", [])
    exact = any(value == target or value.startswith(target + ".") for target in required for value in found)
    coarse = any(target.startswith(value + ".") for target in required for value in found)
    anchors = versions(str(getattr(item, "source_title", "") or "") + " " + quote + " " + str(getattr(item, "window_text", "") or ""))
    anchor = any(value == target or value.startswith(target + ".") for target in required for value in anchors)
    status = "NOT_REQUIRED" if not required else "MATCH" if exact or (not found or coarse) and anchor else (
        "MISMATCH" if found and not coarse else "UNKNOWN")
    development = bool(set(segments) & _DEVELOPMENT)
    temporal = "DEVELOPMENT" if development else "CURRENT_ALIAS" if set(segments) & _CURRENT else "VERSIONED_SOURCE" if found else "UNKNOWN"
    reasons = []
    if status == "MISMATCH":
        reasons.append("SOURCE_VERSION_MISMATCH")
    if status == "UNKNOWN":
        reasons.append("SOURCE_VERSION_UNRESOLVED")
    if development and not scope.get("allow_development", False):
        reasons.append("SOURCE_DEVELOPMENT_BRANCH_MISMATCH")
    return {"reason_codes": reasons, "version_status": status, "temporal_status": temporal,
            "document_versions": found, "required_versions": required, "source_url": url,
            "as_of_date": scope.get("as_of_date", ""), "freshness_status": "UNKNOWN"}
