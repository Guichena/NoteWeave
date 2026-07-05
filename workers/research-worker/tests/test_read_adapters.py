from __future__ import annotations

from app.models import ResearchSearchHit, ResearchTaskInput
from app.planner import build_research_plan
from app.read_adapters import (
    CompositeReadAdapter,
    UrlReadAdapter,
    WorkspaceReadAdapter,
    run_research_read,
)


class FakeUrlSnapshotTransport:
    def __init__(self, content_by_url: dict[str, dict[str, object]]) -> None:
        self.content_by_url = content_by_url
        self.calls: list[tuple[str, str, int]] = []

    def read(self, url: str, query: str, token_budget: int) -> dict[str, object]:
        self.calls.append((url, query, token_budget))
        return self.content_by_url[url]


def _build_task_input() -> ResearchTaskInput:
    return ResearchTaskInput.model_validate(
        {
            "task_id": "task-read-adapter-1",
            "workspace_id": "ws-1",
            "target_id": "run-read-adapter-1",
            "source_scope": [
                {
                    "source_id": "src-workspace",
                    "title": "Workspace Evidence",
                    "summary": "Workspace summary about verifier loops.",
                    "sample_text": "Workspace full text says verifier loops need local and global checks.",
                }
            ],
            "control_pack": {
                "pack_type": "research",
                "target_key": "DEFAULT",
                "task_neighborhood": "RESEARCH_DEFAULT",
            },
            "input_payload": {
                "question": "How should verifier loops read evidence?",
                "profile_key": "DEFAULT",
            },
        }
    )


def _workspace_hit() -> ResearchSearchHit:
    return ResearchSearchHit(
        hit_id="hit-workspace",
        source_id="src-workspace",
        source_title="Workspace Evidence",
        query="verifier loops",
        rank=1,
        snippet="Workspace snippet.",
        confidence_score=0.9,
        retrieval_reason="workspace match",
        search_angle="direct",
        adapter="workspace",
        provider="workspace",
    )


def _url_hit(hit_id: str = "hit-url", rank: int = 2) -> ResearchSearchHit:
    return ResearchSearchHit(
        hit_id=hit_id,
        source_id=f"web-{hit_id}",
        source_title="External Evidence",
        query="verifier loops external",
        rank=rank,
        snippet="External snippet about verifier loops.",
        confidence_score=0.81,
        retrieval_reason="external search match",
        search_angle="coverage_gap",
        url=f"https://example.com/{hit_id}",
        provider="fake-web",
        adapter="external",
    )


def test_workspace_read_adapter_should_open_workspace_window() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    adapter = WorkspaceReadAdapter()

    window = adapter.read_hit(task_input, plan, _workspace_hit(), window_index=1)

    assert window is not None
    assert window.source_id == "src-workspace"
    assert window.query == "verifier loops"
    assert "local and global checks" in window.window_text
    assert window.adapter == "workspace"
    assert window.snapshot_status == "WORKSPACE"
    assert window.token_estimate > 0


def test_url_read_adapter_without_transport_should_create_fallback_window() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    adapter = UrlReadAdapter()

    window = adapter.read_hit(task_input, plan, _url_hit(), window_index=1)

    assert window is not None
    assert window.url == "https://example.com/hit-url"
    assert "External snippet about verifier loops" in window.window_text
    assert "https://example.com/hit-url" in window.window_text
    assert window.adapter == "external_url"
    assert window.snapshot_status == "FALLBACK"
    assert "no url snapshot transport" in window.retention_reason


def test_url_read_adapter_with_transport_should_use_snapshot_content() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    hit = _url_hit()
    transport = FakeUrlSnapshotTransport(
        {
            hit.url: {
                "text": "Fetched page snapshot with verifier loop details.",
                "snapshot_key": "research/ws-1/run-1/snapshot/page.json",
            }
        }
    )
    adapter = UrlReadAdapter(transport=transport)

    window = adapter.read_hit(task_input, plan, hit, window_index=1)

    assert window is not None
    assert window.window_text == "Fetched page snapshot with verifier loop details."
    assert window.snapshot_status == "FETCHED"
    assert window.snapshot_key == "research/ws-1/run-1/snapshot/page.json"
    assert transport.calls == [(hit.url, hit.query, 800)]


def test_composite_read_adapter_should_preserve_hit_order_and_retention_budget() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    plan.stop_contract["tool_response_retention_budget"] = 2
    hits = [_url_hit("hit-url-1", 1), _workspace_hit(), _url_hit("hit-url-2", 3)]
    adapter = CompositeReadAdapter([WorkspaceReadAdapter(), UrlReadAdapter()])

    windows = adapter.read(task_input, plan, hits)

    assert [window.hit_id for window in windows] == ["hit-url-1", "hit-workspace"]
    assert len(windows) == 2


def test_run_research_read_should_emit_required_window_contract() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)

    windows = run_research_read(task_input, plan, [_workspace_hit(), _url_hit()])

    assert len(windows) == 2
    assert all(window.source_id for window in windows)
    assert all(window.query for window in windows)
    assert all(window.read_focus for window in windows)
    assert all(window.token_estimate > 0 for window in windows)
