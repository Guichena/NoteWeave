from __future__ import annotations

from urllib.parse import urlparse

from app.models import ResearchPlan, ResearchSearchHit


HIGH_TRUST_QUALITY_SCORES = {
    "WORKSPACE_SOURCE": 0.98,
    "OFFICIAL_DOC": 0.93,
    "GOVERNMENT_SOURCE": 0.95,
    "ACADEMIC_SOURCE": 0.92,
    "REPOSITORY_SOURCE": 0.84,
    "REFERENCE_SOURCE": 0.72,
    "SECONDARY_SOURCE": 0.63,
    "GENERAL_WEB": 0.55,
}


def infer_source_domain(url: str) -> str:
    normalized = (url or "").strip()
    if not normalized:
        return ""
    try:
        domain = urlparse(normalized).netloc.strip().lower()
    except ValueError:
        return ""
    if domain.startswith("www."):
        domain = domain[4:]
    return domain


def infer_source_quality(
    *,
    url: str,
    provider: str,
    adapter: str,
    title: str,
    content_text: str = "",
) -> tuple[str, float]:
    del provider
    if adapter == "workspace":
        return _adjust_for_content_quality("WORKSPACE_SOURCE", HIGH_TRUST_QUALITY_SCORES["WORKSPACE_SOURCE"], content_text)

    domain = infer_source_domain(url)
    title_lower = (title or "").strip().lower()
    if domain.endswith(".gov"):
        return _adjust_for_content_quality("GOVERNMENT_SOURCE", HIGH_TRUST_QUALITY_SCORES["GOVERNMENT_SOURCE"], content_text)
    if domain.endswith(".edu"):
        return _adjust_for_content_quality("ACADEMIC_SOURCE", HIGH_TRUST_QUALITY_SCORES["ACADEMIC_SOURCE"], content_text)
    if any(token in domain for token in ["docs.", "developer.", "api.", "support."]):
        return _adjust_for_content_quality("OFFICIAL_DOC", HIGH_TRUST_QUALITY_SCORES["OFFICIAL_DOC"], content_text)
    if "github.com" in domain or "gitlab.com" in domain:
        return _adjust_for_content_quality("REPOSITORY_SOURCE", HIGH_TRUST_QUALITY_SCORES["REPOSITORY_SOURCE"], content_text)
    if any(token in domain for token in ["wikipedia.org", "wikidata.org"]):
        return _adjust_for_content_quality("REFERENCE_SOURCE", HIGH_TRUST_QUALITY_SCORES["REFERENCE_SOURCE"], content_text)
    if any(token in domain for token in ["medium.com", "substack.com", "news", "blog"]):
        return _adjust_for_content_quality("SECONDARY_SOURCE", HIGH_TRUST_QUALITY_SCORES["SECONDARY_SOURCE"], content_text)
    if any(token in title_lower for token in ["official", "documentation", "report", "study"]):
        return _adjust_for_content_quality("OFFICIAL_DOC", HIGH_TRUST_QUALITY_SCORES["OFFICIAL_DOC"] - 0.05, content_text)
    return _adjust_for_content_quality("GENERAL_WEB", HIGH_TRUST_QUALITY_SCORES["GENERAL_WEB"], content_text)


def _adjust_for_content_quality(source_quality: str, score: float, content_text: str) -> tuple[str, float]:
    text = " ".join((content_text or "").strip().split())
    if not text:
        return source_quality, score
    lowered = text.lower()
    weak_markers = ["todo", "coming soon", "placeholder", "lorem ipsum", "under construction"]
    word_count = len(text.split())
    if word_count < 12 or any(marker in lowered for marker in weak_markers):
        return "GENERAL_WEB", min(score, 0.45)
    if word_count < 40 and source_quality in {"REPOSITORY_SOURCE", "OFFICIAL_DOC", "GOVERNMENT_SOURCE", "ACADEMIC_SOURCE"}:
        return "SECONDARY_SOURCE", min(score, HIGH_TRUST_QUALITY_SCORES["SECONDARY_SOURCE"])
    return source_quality, score


def infer_search_lane(plan: ResearchPlan, query: str) -> str:
    lowered = (query or "").lower()
    if "verified evidence search" in lowered or "direct answer with evidence" in lowered:
        return "DEEP_FOCUS"
    if "counterfactual" in lowered:
        return "COUNTERFACTUAL"
    if "coverage gap" in lowered:
        return "WIDE_DISCOVERY"
    if "constraint" in lowered or "source" in lowered:
        return "WIDE_DISCOVERY"
    if str(plan.stop_contract.get("depth", "")).strip().upper() == "DEEP":
        return "DEEP_FOCUS"
    return "BALANCED"


def infer_read_strategy(plan: ResearchPlan, hit: ResearchSearchHit) -> str:
    recovery_mode = str(plan.stop_contract.get("recovery_mode", "")).strip().upper()
    if recovery_mode == "COUNTERFACTUAL_RECHECK":
        return "COUNTERFACTUAL_DEEP_READ"
    if recovery_mode == "READ_MORE":
        return "WIDE_COVERAGE_READ"
    if recovery_mode == "EXTRACT_AGAIN":
        return "REEXTRACT_REUSE_READ"
    if hit.search_lane == "WIDE_DISCOVERY":
        return "WIDE_COVERAGE_READ"
    if hit.source_quality in {"OFFICIAL_DOC", "GOVERNMENT_SOURCE", "ACADEMIC_SOURCE"}:
        return "DEEP_EVIDENCE_READ"
    return "BALANCED_READ"
