from __future__ import annotations

import json
import re


def parse_json_payload(text: str) -> object | None:
    stripped = text.strip()
    if not stripped:
        return None
    candidates = [stripped]
    fenced = re.search(r"```(?:json)?\s*(.*?)```", stripped, flags=re.IGNORECASE | re.DOTALL)
    if fenced:
        candidates.insert(0, fenced.group(1).strip())
    object_start = stripped.find("{")
    object_end = stripped.rfind("}")
    if object_start >= 0 and object_end > object_start:
        candidates.append(stripped[object_start : object_end + 1])
    for candidate in candidates:
        repaired = re.sub(r"//.*?$", "", candidate, flags=re.MULTILINE)
        repaired = re.sub(r",\s*([}\]])", r"\1", repaired)
        try:
            return json.loads(repaired)
        except json.JSONDecodeError:
            continue
    return None
