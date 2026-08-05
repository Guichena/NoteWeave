from __future__ import annotations

from app.fetch_adapters import WorkspaceFetchAdapter, run_research_fetch
from app.models import ResearchFetchedDocument, ResearchSearchHit, ResearchTaskInput
from app.planner import build_research_plan
from app.read_adapters import (
    CompositeReadAdapter,
    UrlReadAdapter,
    WorkspaceReadAdapter,
    run_research_read,
)


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


def _fetched_url_document() -> ResearchFetchedDocument:
    return ResearchFetchedDocument(
        fetch_id="fetch-1",
        hit_id="hit-url",
        source_id="web-hit-url",
        source_title="External Evidence",
        query="verifier loops external",
        rank=2,
        url="https://example.com/hit-url",
        provider="fake-web",
        adapter="external_url",
        search_angle="coverage_gap",
        snapshot_text="Fetched page snapshot with verifier loop details.",
        snapshot_status="FETCHED",
        snapshot_key="research/ws-1/run-1/snapshot/page.json",
        fetch_status="FETCHED",
        fetch_method="FAKE_FETCH",
        content_origin="FETCHED_SNAPSHOT",
        content_type_label="WEBPAGE",
        fetch_error_reason="",
        fetch_failure_code="",
        fetch_attempts=["FAKE_FETCH"],
        transport_chain=["FAKE_FETCH"],
        transport_resolution="FAKE_FETCH",
        transport_fallback_reason="",
        transport_fallback_code="",
        transport_attempt_count=1,
        snapshot_archive_ready=True,
        source_domain="example.com",
        source_quality="OFFICIAL_DOC",
        source_quality_score=0.93,
    )


def test_workspace_read_adapter_should_open_workspace_window_from_fetched_document() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    document = WorkspaceFetchAdapter().fetch_hit(task_input, plan, _workspace_hit(), fetch_index=1)

    assert document is not None
    window = WorkspaceReadAdapter().read_hit(task_input, plan, document, window_index=1)

    assert window is not None
    assert window.source_id == "src-workspace"
    assert window.query == "verifier loops"
    assert "local and global checks" in window.window_text
    assert window.adapter == "workspace"
    assert window.snapshot_status == "WORKSPACE"
    assert window.fetch_status == "WORKSPACE_READY"
    assert window.fetch_method == "WORKSPACE"
    assert window.content_origin == "WORKSPACE_TEXT"
    assert window.content_type_label == "WORKSPACE_TEXT"
    assert window.fetch_failure_code == ""
    assert window.transport_chain == ["WORKSPACE"]
    assert window.transport_resolution == "WORKSPACE"
    assert window.transport_fallback_code == ""
    assert window.transport_attempt_count == 1
    assert window.snapshot_archive_ready is False
    assert window.source_quality == "WORKSPACE_SOURCE"
    assert window.read_strategy
    assert window.token_estimate > 0


def test_workspace_fetch_and_read_should_use_the_server_selected_window_not_the_seed_sample() -> None:
    task_input = ResearchTaskInput.model_validate({
        **_build_task_input().model_dump(mode="json"),
        "source_scope": [{
            "source_id": "src-workspace",
            "title": "Workspace Evidence",
            "sample_text": "First window sample.",
            "source_snapshot_id": "snapshot-1",
            "source_window_id": "window-1",
        }],
    })
    plan = build_research_plan(task_input)
    hit = ResearchSearchHit(
        hit_id="workspace-window-2",
        source_id="src-workspace",
        source_snapshot_id="snapshot-1",
        source_window_id="window-2",
        workspace_window_text="Second window contains the grounded answer.",
        source_title="Workspace Evidence",
        query="grounded answer",
        rank=1,
        snippet="Second window contains the grounded answer.",
        confidence_score=0.95,
        retrieval_reason="lease-bound workspace search",
        adapter="workspace",
        provider="workspace",
    )

    document = WorkspaceFetchAdapter().fetch_hit(task_input, plan, hit, fetch_index=1)
    assert document is not None
    window = WorkspaceReadAdapter().read_hit(task_input, plan, document, window_index=1)

    assert document.snapshot_text == "Second window contains the grounded answer."
    assert document.source_snapshot_id == "snapshot-1"
    assert window is not None
    assert window.window_id == "window-2"
    assert window.source_window_id == "window-2"
    assert window.source_snapshot_id == "snapshot-1"


def test_url_read_adapter_should_preserve_fetched_document_metadata() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)

    window = UrlReadAdapter().read_hit(task_input, plan, _fetched_url_document(), window_index=1)

    assert window is not None
    assert window.url == "https://example.com/hit-url"
    assert window.window_text == "Fetched page snapshot with verifier loop details."
    assert window.adapter == "external_url"
    assert window.snapshot_status == "FETCHED"
    assert window.snapshot_key == "research/ws-1/run-1/snapshot/page.json"
    assert window.fetch_status == "FETCHED"
    assert window.fetch_method == "FAKE_FETCH"
    assert window.content_origin == "FETCHED_SNAPSHOT"
    assert window.content_type_label == "WEBPAGE"
    assert window.fetch_failure_code == ""
    assert window.fetch_attempts == ["FAKE_FETCH"]
    assert window.transport_chain == ["FAKE_FETCH"]
    assert window.transport_resolution == "FAKE_FETCH"
    assert window.transport_fallback_code == ""
    assert window.transport_attempt_count == 1
    assert window.snapshot_archive_ready is True
    assert window.source_domain == "example.com"
    assert "after url snapshot fetch" in window.retention_reason


def test_url_read_adapter_should_reject_fallback_document_as_evidence() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    document = _fetched_url_document().model_copy(
        update={
            "fetch_status": "FALLBACK_USED",
            "fetch_method": "HTTP",
            "content_origin": "SEARCH_SNIPPET_FALLBACK",
            "content_type_label": "PDF",
            "fetch_error_reason": "unsupported binary content type: application/pdf",
            "fetch_failure_code": "UNSUPPORTED_CONTENT_TYPE",
            "transport_fallback_reason": "unsupported binary content type: application/pdf",
            "transport_fallback_code": "UNSUPPORTED_CONTENT_TYPE",
        }
    )

    window = UrlReadAdapter().read_hit(task_input, plan, document, window_index=1)

    assert window is None


def test_composite_read_adapter_should_preserve_document_order() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    workspace_document = WorkspaceFetchAdapter().fetch_hit(task_input, plan, _workspace_hit(), fetch_index=1)
    assert workspace_document is not None
    documents = [_fetched_url_document(), workspace_document]
    adapter = CompositeReadAdapter([WorkspaceReadAdapter(), UrlReadAdapter()])

    windows = adapter.read(task_input, plan, documents)

    assert [window.hit_id for window in windows] == ["hit-url", "hit-workspace"]
    assert len(windows) == 2


def test_run_research_read_should_support_prefetched_documents_and_legacy_search_hits() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    prefetched_windows = run_research_read(
        task_input,
        plan,
        fetched_documents=[_fetched_url_document()],
    )
    legacy_windows = run_research_read(
        task_input,
        plan,
        [_workspace_hit()],
    )

    assert len(prefetched_windows) == 1
    assert prefetched_windows[0].hit_id == "hit-url"
    assert len(legacy_windows) == 1
    assert legacy_windows[0].source_id == "src-workspace"


def test_requirement_targeted_read_should_annotate_focus_from_prefetched_documents() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    plan.stop_contract["recovery_mode"] = "READ_MORE"
    plan.stop_contract["recovery_target_requirement_labels"] = ["Evidence finding: verify the claim"]
    plan.stop_contract["recovery_target_columns"] = ["claim_text", "evidence_excerpt"]

    windows = run_research_read(
        task_input,
        plan,
        fetched_documents=[_fetched_url_document()],
    )

    assert len(windows) == 1
    assert "prioritize evidence finding: verify the claim" in windows[0].read_focus.lower()
    assert "fill columns claim_text, evidence_excerpt" in windows[0].read_focus.lower()


def test_requirement_targeted_read_should_surface_requirement_target_metadata() -> None:
    payload = _build_task_input().model_dump(mode="json")
    payload["input_payload"]["research_intent"] = {
        "research_goal": "Verify the primary answer direction.",
        "deliverable_format": "Evidence-backed brief",
        "constraints": ["Keep the answer anchored to verified evidence."],
        "time_range": "",
        "depth": "STANDARD",
    }
    task_input = ResearchTaskInput.model_validate(payload)
    plan = build_research_plan(task_input)
    targeted_query = next(
        query for query in plan.query_set if "verified evidence search" in query
    )
    document = _fetched_url_document().model_copy(update={"query": targeted_query})

    windows = run_research_read(
        task_input,
        plan,
        fetched_documents=[document],
    )

    assert len(windows) == 1
    assert windows[0].query_family == "verified_evidence"
    assert windows[0].target_requirement_ids == ["constraint_finding_1"]
    assert windows[0].target_requirement_labels == [
        "Evidence finding: Keep the answer anchored to verified evidence."
    ]
    assert "claim_text" in windows[0].target_columns
    assert "evidence_excerpt" in windows[0].target_columns
    assert "prioritize evidence finding: keep the answer anchored to verified evidence." in windows[0].read_focus.lower()


def test_run_research_fetch_and_read_should_form_search_fetch_read_chain() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)

    fetched_documents = run_research_fetch(task_input, plan, [_workspace_hit()])
    windows = run_research_read(task_input, plan, fetched_documents=fetched_documents)

    assert len(fetched_documents) == 1
    assert fetched_documents[0].fetch_status == "WORKSPACE_READY"
    assert len(windows) == 1
    assert windows[0].fetch_status == "WORKSPACE_READY"
