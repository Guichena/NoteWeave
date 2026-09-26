"""D-39 phase 1: MA4 task-level recovery inside one DEEP_CELL task.

A DEEP_CELL task whose lease expired is re-claimed under a new lease epoch / fencing token.
The re-execution must not pay again for external pages this task already pushed through the
server-owned archive, and the usage it reports must describe the calls it really made.
"""

from __future__ import annotations

import hashlib
import json
from types import SimpleNamespace

import pytest

from app.agent_command_contract import ResearchAgentCommand
from app.agent_task_client import AgentTaskClaim, ArchivedExternalSnapshot
from app.deep_cell_executor import DeepCellExecutor
from app.task_snapshot_contract import ResearchAgentTaskSnapshot, snapshot_digest

_TASK_ID = "00000000-0000-0000-0000-000000000001"
_ARCHIVED_URL = "https://example.com/already-archived"
_FRESH_URL = "https://example.com/not-archived-yet"
_SNAPSHOT_KEY = "research/external/" + "a" * 64


def _external_snapshot() -> dict[str, object]:
    payload: dict[str, object] = {
        "schema_version": "research-agent-task-snapshot.v1",
        "task_id": _TASK_ID,
        "research_run_id": "run-1",
        "workspace_id": "workspace-1",
        "role": "DEEP_CELL",
        "entity_id": "entity-1",
        "branch_id": "branch-main",
        "plan_revision": 2,
        "entity_set_version": 3,
        "lease_epoch": 2,
        "fencing_token": 7,
        "target_cells": [{"cell_id": "entity-1:method", "expected_version": 3}],
        "budget": {"llm_calls": 2},
        "provider_key": "research-default",
        "source_policy": {
            "source_scope": [
                {"source_id": "source-1", "title": "Trusted note", "sample_text": "The method is documented."}
            ],
            "allow_external_search": True,
            "allow_external_fetch": True,
        },
        "query_policy": {"query": "How does the method work?"},
    }
    return {**payload, "snapshot_digest": snapshot_digest(payload)}


def _claim(snapshot: dict[str, object]) -> AgentTaskClaim:
    return AgentTaskClaim(
        str(snapshot["task_id"]), 2, 7, json.dumps(["entity-1:method"]), '{"llm_calls":2}',
        json.dumps(snapshot), str(snapshot["snapshot_digest"]),
    )


def _command() -> ResearchAgentCommand:
    return ResearchAgentCommand(
        schema_version="research-agent-command.v1", command_id="command-1", research_run_id="run-1",
        agent_task_id=_TASK_ID, idempotency_key="idem-1", delivery_attempt=1,
    )


def _hit(url: str, hit_id: str = "hit-1"):
    return SimpleNamespace(
        hit_id=hit_id, source_id="external-1", source_title="External page", url=url,
        provider="external-provider", adapter="external_url", rank=1, adapter_name="external_url",
    )


def _archive_row(url: str = _ARCHIVED_URL, window_id: str = "window-1") -> ArchivedExternalSnapshot:
    content = "server owned external text recovered across a lease change"
    return ArchivedExternalSnapshot(
        window_id=window_id,
        source_id="external-1",
        source_title="External page",
        source_url=url,
        provider="external-provider",
        adapter="external_url",
        snapshot_key=_SNAPSHOT_KEY,
        content_text=content,
        content_sha256=hashlib.sha256(content.encode("utf-8")).hexdigest(),
    )


def _extraction_result():
    from app.extraction_result import ExtractionResult, ExtractionTerminationReason

    return ExtractionResult(termination_reason=ExtractionTerminationReason.NO_READ_WINDOWS)


class _RecoveryPermit:
    """Models the re-claimed lease: the server answers with the earlier attempt's archive."""

    def __init__(self, inventory: list[ArchivedExternalSnapshot] | None = None) -> None:
        self.calls: list[tuple[str, str]] = []
        self.archive_calls: list[dict[str, object]] = []
        self.inventory_calls = 0
        self._inventory = inventory if inventory is not None else [_archive_row()]

    def require_permit(self, snapshot, tool_identity: str) -> None:
        self.calls.append((snapshot.provider_key, tool_identity))

    def archived_external_snapshot_inventory(self, snapshot) -> list[ArchivedExternalSnapshot]:
        self.inventory_calls += 1
        return list(self._inventory)

    def archive_external_snapshot(self, snapshot, **payload):
        self.archive_calls.append(payload)
        return SimpleNamespace(
            task_id=snapshot.task_id,
            window_id=payload["window_id"],
            source_id=payload["source_id"],
            snapshot_status="EXTERNAL_ARCHIVED",
            snapshot_key=_SNAPSHOT_KEY,
        )


class _CountingToolchain:
    def __init__(self, hits: list[object] | None = None) -> None:
        self.calls: list[tuple[str, list[object]]] = []
        self._hits = hits if hits is not None else [_hit(_ARCHIVED_URL)]

    def search(self, _task_input, _plan, *, allow_external: bool):
        self.calls.append(("search", list(self._hits)))
        return list(self._hits)

    def fetch(self, _task_input, _plan, hits, *, allow_external: bool):
        self.calls.append(("fetch", list(hits)))
        return ["document:" + str(getattr(hit, "url", "")) for hit in hits]

    def read(self, _task_input, _plan, documents, *, allow_external: bool):
        self.calls.append(("read", list(documents)))
        return ["window"]

    def extract(self, _task_input, _plan, windows, *, llm_client):
        self.calls.append(("extract", list(windows)))
        return _extraction_result()


def test_reclaimed_task_should_not_refetch_a_page_the_task_already_archived() -> None:
    permit = _RecoveryPermit([_archive_row()])
    toolchain = _CountingToolchain([_hit(_ARCHIVED_URL)])
    snapshot = _external_snapshot()

    completion = DeepCellExecutor(permit, "worker-a", toolchain=toolchain, enable_llm=False)(
        _command(), _claim(snapshot))

    assert [name for name, _ in toolchain.calls] == ["extract"]
    assert permit.calls == [("research-default", "extract")]
    assert permit.inventory_calls == 1
    assert completion.budget_usage.search_calls == 0
    assert completion.budget_usage.fetch_calls == 0
    assert completion.budget_usage.read_calls == 0
    assert completion.budget_usage.extract_calls == 1


def test_recovered_windows_keep_the_server_archive_identity_and_are_not_archived_twice() -> None:
    permit = _RecoveryPermit([_archive_row()])
    toolchain = _CountingToolchain([_hit(_ARCHIVED_URL)])

    DeepCellExecutor(permit, "worker-a", toolchain=toolchain, enable_llm=False)(
        _command(), _claim(_external_snapshot()))

    recovered = toolchain.calls[-1][1]
    assert len(recovered) == 1
    window = recovered[0]
    assert window.window_id == "window-1"
    assert window.url == _ARCHIVED_URL
    assert window.snapshot_status == "EXTERNAL_ARCHIVED"
    assert window.snapshot_key == _SNAPSHOT_KEY
    assert window.snapshot_archive_ready is True
    assert window.fetch_method == "ARCHIVE_REUSE"
    assert window.window_text == "server owned external text recovered across a lease change"
    # Nothing is written back: the server already owns exactly this content.
    assert permit.archive_calls == []


def test_recovery_uses_only_the_archive_even_when_search_finds_a_fresh_page() -> None:
    permit = _RecoveryPermit([_archive_row()])
    toolchain = _CountingToolchain([_hit(_ARCHIVED_URL, "hit-1"), _hit(_FRESH_URL, "hit-2")])

    completion = DeepCellExecutor(permit, "worker-a", toolchain=toolchain, enable_llm=False)(
        _command(), _claim(_external_snapshot()))

    assert [name for name, _ in toolchain.calls] == ["extract"]
    assert permit.calls == [("research-default", "extract")]
    assert completion.budget_usage.search_calls == 0
    assert completion.budget_usage.fetch_calls == 0
    assert completion.budget_usage.read_calls == 0


def test_recovered_read_focus_is_deterministic_for_the_same_task_snapshot() -> None:
    permit = _RecoveryPermit([_archive_row()])
    toolchain = _CountingToolchain([_hit(_ARCHIVED_URL)])

    DeepCellExecutor(permit, "worker-a", toolchain=toolchain, enable_llm=False)(
        _command(), _claim(_external_snapshot()))

    first = toolchain.calls[-1][1][0]
    assert first.read_focus == "archive-reuse:method"
    assert first.query == "How does the method work?"

    toolchain2 = _CountingToolchain([_hit(_ARCHIVED_URL)])
    DeepCellExecutor(_RecoveryPermit([_archive_row()]), "worker-b", toolchain=toolchain2, enable_llm=False)(
        _command(), _claim(_external_snapshot()))
    assert toolchain2.calls[-1][1][0].read_focus == first.read_focus


def test_http_client_should_parse_the_server_inventory_and_skip_the_paid_round() -> None:
    """End-to-end over HTTP: one re-claimed task, one archived page, zero second payment."""
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    import threading

    from app.agent_task_client import JavaResearchAgentTaskClient

    row = _archive_row()
    calls: list[str] = []

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self) -> None:  # noqa: N802 - stdlib HTTP handler contract
            length = int(self.headers.get("Content-Length", "0"))
            request = json.loads(self.rfile.read(length) or b"{}")
            calls.append(self.path)
            if self.path == "/internal/research-agent/permits":
                data: object = {
                    "status": "GRANTED",
                    "tool_identity": request["tool_identity"],
                }
            elif self.path == "/internal/research-agent/workspace-windows/search":
                data = []
            elif self.path == "/internal/research-agent/external-snapshots/archived":
                data = [{
                    "window_id": row.window_id, "source_id": row.source_id, "source_title": row.source_title,
                    "source_url": row.source_url, "provider": row.provider, "adapter": row.adapter,
                    "snapshot_key": row.snapshot_key, "content_text": row.content_text,
                    "content_sha256": row.content_sha256,
                }]
            else:
                self.send_error(404)
                return
            encoded = json.dumps({"data": data}).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)

        def log_message(self, _format: str, *_args: object) -> None:
            return

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        client = JavaResearchAgentTaskClient(
            f"http://127.0.0.1:{server.server_port}", "internal-test-token", "worker-a"
        )
        toolchain = _CountingToolchain([_hit(_ARCHIVED_URL)])
        completion = DeepCellExecutor(client, "worker-a", toolchain=toolchain, enable_llm=False)(
            _command(), _claim(_external_snapshot()))
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)

    assert calls == [
        "/internal/research-agent/external-snapshots/archived",
        "/internal/research-agent/permits",
    ]
    assert [name for name, _ in toolchain.calls] == ["extract"]
    assert completion.budget_usage.search_calls == 0
    assert completion.budget_usage.fetch_calls == 0
    assert completion.budget_usage.read_calls == 0


def test_http_client_should_reject_an_inventory_row_that_does_not_match_its_digest(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from app.agent_task_client import AgentTaskProtocolError, JavaResearchAgentTaskClient
    import app.agent_task_client as client_module

    row = _archive_row()
    tampered = {
        "window_id": row.window_id, "source_id": row.source_id, "source_title": row.source_title,
        "source_url": row.source_url, "provider": row.provider, "adapter": row.adapter,
        "snapshot_key": row.snapshot_key, "content_text": "tampered bytes",
        "content_sha256": row.content_sha256,
    }
    payload = json.dumps({"data": [tampered]}).encode("utf-8")

    class _FakeResponse:
        def read(self) -> bytes:
            return payload

        def __enter__(self) -> "_FakeResponse":
            return self

        def __exit__(self, *_args: object) -> None:
            return None

    monkeypatch.setattr(client_module.request, "urlopen", lambda *_args, **_kwargs: _FakeResponse())
    client = JavaResearchAgentTaskClient("http://127.0.0.1:1", "token", "worker-a")

    snapshot = ResearchAgentTaskSnapshot.model_validate(_external_snapshot())
    with pytest.raises(AgentTaskProtocolError, match="content does not match its digest"):
        client.archived_external_snapshot_inventory(snapshot)


def test_without_the_inventory_capability_the_full_provider_round_unchanged() -> None:
    from app.deep_cell_executor import _recoverable_external_windows

    class _LegacyPermit:
        def __init__(self) -> None:
            self.calls: list[str] = []
            self.archive_calls: list[dict[str, object]] = []

        def require_permit(self, snapshot, tool_identity: str) -> None:
            self.calls.append(tool_identity)

        def archive_external_snapshot(self, snapshot, **payload):
            self.archive_calls.append(payload)
            return SimpleNamespace(
                task_id=snapshot.task_id, window_id=payload["window_id"],
                source_id=payload["source_id"], snapshot_status="EXTERNAL_ARCHIVED",
                snapshot_key=_SNAPSHOT_KEY,
            )

    permit = _LegacyPermit()
    toolchain = _CountingToolchain([_hit(_ARCHIVED_URL)])
    completion = DeepCellExecutor(permit, "worker-a", toolchain=toolchain, enable_llm=False)(
        _command(), _claim(_external_snapshot()))

    assert _recoverable_external_windows(_external_snapshot(), permit) == []  # type: ignore[arg-type]
    assert [name for name, _ in toolchain.calls] == ["search", "fetch", "read", "extract"]
    assert permit.calls == ["search", "fetch", "read", "extract"]
    assert completion.budget_usage.fetch_calls == 1
