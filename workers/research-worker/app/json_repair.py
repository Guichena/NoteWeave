from __future__ import annotations

import json
import re


def parse_json_payload(text: str) -> object | None:
    """Parse common LLM JSON outputs with small, deterministic repairs."""
    candidates = _json_candidates(text)
    for candidate in candidates:
        repaired = _repair_json(candidate)
        try:
            return json.loads(repaired)
        except json.JSONDecodeError:
            continue
    return None


def _json_candidates(text: str) -> list[str]:
    stripped = text.strip()
    if not stripped:
        return []

    candidates = [stripped]
    fenced_match = re.search(r"```(?:json)?\s*(.*?)```", stripped, flags=re.IGNORECASE | re.DOTALL)
    if fenced_match:
        candidates.insert(0, fenced_match.group(1).strip())

    object_candidate = _slice_between(stripped, "{", "}")
    if object_candidate:
        candidates.append(object_candidate)
    array_candidate = _slice_between(stripped, "[", "]")
    if array_candidate:
        candidates.append(array_candidate)
    return candidates


def _slice_between(text: str, left: str, right: str) -> str:
    start = text.find(left)
    end = text.rfind(right)
    if start == -1 or end == -1 or end <= start:
        return ""
    return text[start:end + 1]


def _repair_json(text: str) -> str:
    without_comments = re.sub(r"//.*?$", "", text, flags=re.MULTILINE)
    without_trailing_commas = re.sub(r",\s*([}\]])", r"\1", without_comments)
    return without_trailing_commas.strip()
