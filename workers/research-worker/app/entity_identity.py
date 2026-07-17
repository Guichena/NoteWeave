from __future__ import annotations

import hashlib
import re


def canonical_entity_id(
    *,
    entity_hint: str = "",
    source_title: str = "",
    source_id: str = "",
    url: str = "",
) -> str:
    """Build one system-owned entity id for search hits and evidence cards.

    A model may suggest an entity name, but never owns the persisted identifier.
    Source title is preferred over source id/url so the same named entity can be
    supported by more than one source.
    """

    candidate = _normalize_name(entity_hint) or _normalize_name(source_title)
    if not candidate:
        candidate = _normalize_name(source_id) or _normalize_url(url) or "unknown-entity"
    digest = hashlib.sha256(candidate.encode("utf-8")).hexdigest()[:16]
    return f"entity-{digest}"


def canonical_entity_name(entity_hint: str, source_title: str, fallback: str) -> str:
    return (entity_hint or source_title or fallback or "Unknown entity").strip()[:200]


def _normalize_name(value: str) -> str:
    return re.sub(r"[^\w\u3400-\u9fff]+", " ", (value or "").strip().lower()).strip()


def _normalize_url(value: str) -> str:
    return (value or "").strip().lower().rstrip("/")
