from __future__ import annotations

import hashlib
import re
from typing import Callable


def select_frozen_windows(
    *,
    query: str,
    fetch_page: Callable[[str], dict[str, object]],
    max_scan_windows: int = 256,
    max_selected_windows: int = 12,
    max_selected_bytes: int = 65_536,
) -> tuple[list[dict[str, object]], str, str]:
    """Read one immutable Snapshot, then select grounded windows within a fixed budget."""
    scanned: list[dict[str, object]] = []
    cursor = ""
    gap = ""
    while len(scanned) < max_scan_windows:
        page = fetch_page(cursor)
        raw_windows = page.get("windows", [])
        if not isinstance(raw_windows, list):
            raise ValueError("source window page has no windows array")
        for raw in raw_windows:
            if not isinstance(raw, dict):
                raise ValueError("source window must be an object")
            content = str(raw.get("content", ""))
            checksum = hashlib.sha256(content.encode("utf-8")).hexdigest()
            if checksum != raw.get("checksum_sha256"):
                raise ValueError("source window checksum mismatch")
            scanned.append(raw)
            if len(scanned) >= max_scan_windows:
                break
        next_cursor = str(page.get("next_cursor", ""))
        if not next_cursor:
            cursor = ""
            break
        if next_cursor == cursor or not raw_windows:
            raise ValueError("source window cursor did not advance")
        cursor = next_cursor
    if cursor:
        gap = "SCAN_LIMIT_REACHED"

    terms = {
        term.lower() for term in re.findall(r"[\u4e00-\u9fff]{2,}|[A-Za-z0-9_]{3,}", query)
        if len(term) >= 2
    }
    scores = [
        sum(term in str(window.get("content", "")).lower() for term in terms)
        for window in scanned
    ]
    relevant = sorted(range(len(scanned)), key=lambda index: (-scores[index], index))
    relevant = [index for index in relevant if scores[index] > 0]
    coverage = [
        round(position * (len(scanned) - 1) / max(1, max_selected_windows - 1))
        for position in range(min(max_selected_windows, len(scanned)))
    ] if scanned else []
    order = list(dict.fromkeys([*relevant, *coverage, *range(len(scanned))]))
    chosen: list[dict[str, object]] = []
    remaining = max_selected_bytes
    for index in order:
        window = scanned[index]
        size = len(str(window.get("content", "")).encode("utf-8"))
        if size > remaining:
            gap = gap or "SELECTION_BUDGET_REACHED"
            continue
        chosen.append(window)
        remaining -= size
        if len(chosen) == max_selected_windows:
            if len(scanned) > len(chosen):
                gap = gap or "SELECTION_BUDGET_REACHED"
            break
    chosen.sort(key=lambda window: (int(window.get("chunk_no", 0)), int(window.get("window_no", 0))))
    return chosen, gap, cursor
