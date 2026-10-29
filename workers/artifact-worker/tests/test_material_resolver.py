from __future__ import annotations

import hashlib
from pathlib import Path

import pytest

from app.material_resolver import select_frozen_windows
from app.runner import run_artifact_task
from tests.test_callback import _build_resume_task_input
from tests.pipeline.test_pipeline import Model
import app.runner as runner_module


def _window(index: int, content: str) -> dict[str, object]:
    return {
        "window_id": f"window-{index}", "chunk_no": index, "window_no": 0,
        "content": content, "location_info": f"page {index}",
        "checksum_sha256": hashlib.sha256(content.encode("utf-8")).hexdigest(),
    }


def test_later_fact_is_selected_from_paginated_frozen_windows(monkeypatch) -> None:
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
    result = generate_selected_window(task, "window-19", monkeypatch)
    assert any("OnlyInLaterWindow" in sentence["text"] and sentence["chunk_id"] == "window-19"
               for sentence in result.result_payload["evidence"]["sentences"])
    assert "window-19" in [citation["chunk_id"] for citation in result.citations]


def test_changed_window_content_is_rejected_before_generation() -> None:
    tampered = _window(1, "original")
    tampered["content"] = "changed"

    with pytest.raises(ValueError, match="checksum mismatch"):
        select_frozen_windows(query="changed", fetch_page=lambda cursor: {
            "windows": [tampered], "next_cursor": "",
        })


def test_real_architecture_document_late_fact_reaches_frozen_artifact_citation(monkeypatch) -> None:
    document = (Path(__file__).resolve().parents[3] / "docs" / "Artifact-Skill执行架构.md").read_text(
        encoding="utf-8"
    )
    paragraphs = document.splitlines(keepends=True)
    chunks: list[str] = []
    current = ""
    for paragraph in paragraphs:
        if current and len(current) + len(paragraph) > 420:
            chunks.append(current)
            current = ""
        current += paragraph
    if current:
        chunks.append(current)
    windows = [_window(index, content) for index, content in enumerate(chunks)]
    late = next(window for window in windows if "Workflow History 兼容" in str(window["content"]))
    assert int(late["chunk_no"]) > 12

    def fetch(cursor: str) -> dict[str, object]:
        start = int(cursor) if cursor else 0
        end = min(start + 4, len(windows))
        return {"windows": windows[start:end], "next_cursor": str(end) if end < len(windows) else ""}

    selected, gap, cursor = select_frozen_windows(
        query="Workflow History 兼容 Activity 幂等", fetch_page=fetch,
        max_selected_windows=6, max_selected_bytes=5_000,
    )
    assert late["window_id"] in [window["window_id"] for window in selected]
    assert gap == "SELECTION_BUDGET_REACHED"
    assert cursor == ""

    task = _build_resume_task_input()
    task.source_scope[0].material_windows = selected
    task.source_scope[0].material_gap = gap
    result = generate_selected_window(task, late["window_id"], monkeypatch)
    assert late["window_id"] in [s["chunk_id"] for s in result.result_payload["evidence"]["sentences"]]
    assert late["window_id"] in [citation["chunk_id"] for citation in result.citations]


def generate_selected_window(task, window_id, monkeypatch):
    from copy import deepcopy
    import json
    class SelectedWindowModel(Model):
        def complete_json(self, purpose, payload):
            if "evidence" not in payload["inputs"]:
                return super().complete_json(purpose, payload)
            changed = deepcopy(payload)
            sentences = changed["inputs"]["evidence"]["sentences"]
            selected = next(s for s in sentences if s["chunk_id"] == window_id)
            sentences.remove(selected)
            sentences.insert(0, selected)
            return super().complete_json(purpose, changed).replace('"s0001"', json.dumps(selected["id"]))
    monkeypatch.setattr(runner_module, "build_default_llm_client", lambda: SelectedWindowModel("DOC"))
    return run_artifact_task(task)[1]
