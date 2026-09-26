from __future__ import annotations

import hashlib

import pytest

from app.content_runtime import build_canonical_content_objects
from app.material_resolver import select_frozen_windows
from app.runner import run_artifact_task
from tests.test_runner import _build_resume_task_input


def _window(index: int, content: str) -> dict[str, object]:
    return {
        "window_id": f"window-{index}", "chunk_no": index, "window_no": 0,
        "content": content, "location_info": f"page {index}",
        "checksum_sha256": hashlib.sha256(content.encode("utf-8")).hexdigest(),
    }


def test_later_fact_is_selected_from_paginated_frozen_windows() -> None:
    windows = [_window(index, f"Opening section {index}") for index in range(20)]
    windows[-1] = _window(19, "OnlyInLaterWindow is the grounded answer")

    def fetch(cursor: str) -> dict[str, object]:
        start = int(cursor) if cursor else 0
        end = min(start + 4, len(windows))
        return {"windows": windows[start:end], "next_cursor": str(end) if end < len(windows) else ""}

    selected, gap, cursor = select_frozen_windows(
        query="Explain OnlyInLaterWindow", fetch_page=fetch,
        max_selected_windows=5,
    )

    assert "window-19" in [window["window_id"] for window in selected]
    assert gap == "SELECTION_BUDGET_REACHED"
    assert cursor == ""

    task = _build_resume_task_input()
    task.source_scope[0].material_windows = selected
    task.source_scope[0].material_gap = gap
    objects = build_canonical_content_objects(task)
    serialized = " ".join(str(item.model_dump(mode="json")) for item in objects)
    assert "OnlyInLaterWindow" in serialized
    assert "window-19" in serialized
    _, result = run_artifact_task(task)
    assert "window-19" in result.result_payload["material_resolution"][0]["selected_window_ids"]
    assert "window-19" in result.citations[0]["source_window_ids"]


def test_changed_window_content_is_rejected_before_generation() -> None:
    tampered = _window(1, "original")
    tampered["content"] = "changed"

    with pytest.raises(ValueError, match="checksum mismatch"):
        select_frozen_windows(query="changed", fetch_page=lambda cursor: {
            "windows": [tampered], "next_cursor": "",
        })
