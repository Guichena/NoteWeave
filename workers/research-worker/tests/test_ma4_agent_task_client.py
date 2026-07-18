from __future__ import annotations

import io
import hashlib
import json
from types import SimpleNamespace
from urllib import error

import pytest

from app.agent_command_contract import ResearchAgentCommand
from app.agent_task_client import AgentTaskClaim
from app.research_agent_completion_contract import build_completion_envelope

_COMPLETION_TASK_ID = "00000000-0000-0000-0000-000000000001"


def _completion():
    return build_completion_envelope({
        "schema_version": "research-agent-completion.v1",
        "task_id": _COMPLETION_TASK_ID,
        "worker_instance_id": "worker-a",
        "lease_epoch": 2,
        "fencing_token": 4,
        "execution_key": f"deep-cell:{_COMPLETION_TASK_ID}:2:4",
        "task_snapshot_digest": "sha256:" + "1" * 64,
        "termination_reason": "NO_SUPPORTED_CANDIDATE",
        "budget_usage": {
            "llm_calls": 0,
            "search_calls": 1, "fetch_calls": 1, "read_calls": 1, "extract_calls": 1,
            "evidence_cards": 0, "candidates_submitted": 0,
        },
        "telemetry": {"search_hits": 0, "documents": 0, "windows": 0},
        "trace_digest": "sha256:" + "2" * 64,
        "evidence": [],
        "candidates": [],
    })


def _completion_with_candidate(*, base_cell_version: int):
    return build_completion_envelope({
        "schema_version": "research-agent-completion.v1",
        "task_id": _COMPLETION_TASK_ID,
        "worker_instance_id": "worker-a",
        "lease_epoch": 2,
        "fencing_token": 4,
        "execution_key": f"deep-cell:{_COMPLETION_TASK_ID}:2:4",
        "task_snapshot_digest": "sha256:" + "1" * 64,
        "termination_reason": "CANDIDATES_PROPOSED",
        "budget_usage": {
            "llm_calls": 0, "search_calls": 1, "fetch_calls": 1, "read_calls": 1,
            "extract_calls": 1, "evidence_cards": 1, "candidates_submitted": 1,
        },
        "telemetry": {"search_hits": 1, "documents": 1, "windows": 1},
        "trace_digest": "sha256:" + "2" * 64,
        "evidence": [{
            "evidence_key": "evidence-1", "window_id": "window-1", "source_id": "source-1",
            "source_title": "Source", "search_query": "query", "read_focus": "focus",
            "quote_text": "exact quote", "claim_text": "candidate value",
            "relation_type": "SUPPORTS", "support_score_ppm": 900_000,
            "conflict_score_ppm": 0, "snapshot_status": "WORKSPACE",
        }],
        "candidates": [{
            "candidate_key": "candidate-1", "cell_key": "entity-1:method",
            "base_cell_version": base_cell_version, "candidate_value": "candidate value",
            "evidence_keys": ["evidence-1"], "confidence_ppm": 900_000,
        }],
    })


def _receipt_response(completion, *, replay: bool) -> dict[str, object]:
    accepted = [
        {
            "cell_key": candidate.cell_key,
            "from_version": candidate.base_cell_version,
            "to_version": candidate.base_cell_version + 1,
            "decision": "ACCEPT",
            "reason_code": "VERIFIED",
        }
        for candidate in completion.candidates
    ]
    consumed = {
        **completion.budget_usage.model_dump(mode="json"),
        "evidence_appended": len(completion.evidence),
        "candidate_merges_accepted": len(accepted),
        "candidate_merges_rejected": 0,
    }
    unsigned: dict[str, object] = {
        "schema_version": "research-agent-completion-receipt.v1",
        "completion_id": "completion-1",
        "execution_id": "execution-1",
        "task_id": completion.task_id,
        "completion_digest": completion.envelope_digest,
        "evidence_appended": len(completion.evidence),
        "candidate_count": len(completion.candidates),
        "accepted_merges": accepted,
        "rejected_merges": [],
        "budget": {
            "state": "SETTLED",
            "reserved": consumed,
            "consumed": consumed,
            "released": {key: 0 for key in consumed},
        },
    }
    canonical = json.dumps(
        unsigned, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False
    ).encode("utf-8")
    receipt_digest = "sha256:" + hashlib.sha256(
        b"research-agent-completion-receipt.v1\n" + canonical
    ).hexdigest()
    return {**unsigned, "receipt_digest": receipt_digest, "idempotent_replay": replay}


def test_client_should_claim_with_server_lease_identity(monkeypatch) -> None:
    from app.agent_task_client import JavaResearchAgentTaskClient

    client = JavaResearchAgentTaskClient("http://backend", "token", "worker-a")
    calls: list[tuple[str, str, dict, str]] = []

    def fake_request(method, path, payload=None, idempotency_key=""):
        calls.append((method, path, payload or {}, idempotency_key))
        if path.endswith("/claim"):
            return {"data": {"task_id": "agent-task-1", "lease_epoch": 2, "fencing_token": 4, "target_cells_json": "[]", "budget_json": "{}"}}

    monkeypatch.setattr(client, "_request", fake_request)
    claim = client.claim("agent-task-1", lease_seconds=45)
    assert claim.agent_task_id == "agent-task-1"
    assert claim.lease_epoch == 2
    assert claim.fencing_token == 4
    assert calls[0][1] == "/internal/research-agent-tasks/agent-task-1/claim"
    assert len(calls) == 1


def test_client_heartbeat_should_use_worker_scoped_timeout_inside_lease_interval(monkeypatch) -> None:
    from app.agent_task_client import JavaResearchAgentTaskClient

    client = JavaResearchAgentTaskClient(
        "http://backend",
        "token",
        "worker-a",
        heartbeat_request_timeout_seconds=0.4,
    )
    claim = AgentTaskClaim("task-1", 2, 7, "[]", "{}", "{}", "sha256:" + "1" * 64)
    observed_timeouts: list[float] = []

    class Response:
        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

        def read(self, *_args) -> bytes:
            return json.dumps({"data": {
                "task_id": claim.agent_task_id,
                "lease_epoch": claim.lease_epoch,
                "fencing_token": claim.fencing_token,
                "target_cells_json": claim.target_cells_json,
                "budget_json": claim.budget_json,
                "task_snapshot_json": claim.task_snapshot_json,
                "snapshot_digest": claim.snapshot_digest,
            }}).encode("utf-8")

    def fake_urlopen(_request, *, timeout):
        observed_timeouts.append(timeout)
        return Response()

    monkeypatch.setattr("app.agent_task_client.request.urlopen", fake_urlopen)
    assert client.heartbeat(claim, lease_seconds=12) == claim
    assert observed_timeouts == [0.4]


def test_client_should_classify_not_claimable_without_leaking_backend_body(monkeypatch) -> None:
    from app.agent_task_client import AgentTaskNotClaimableError, JavaResearchAgentTaskClient

    client = JavaResearchAgentTaskClient("http://backend", "token", "worker-a")
    response_body = b'{"code":"RESEARCH_AGENT_TASK_NOT_CLAIMABLE","message":"api_key=never-log-this"}'

    def fake_urlopen(*_args, **_kwargs):
        raise error.HTTPError("http://backend/task", 400, "bad request", {}, io.BytesIO(response_body))

    monkeypatch.setattr("app.agent_task_client.request.urlopen", fake_urlopen)

    with pytest.raises(AgentTaskNotClaimableError) as exc:
        client.claim("agent-task-1")

    assert "RESEARCH_AGENT_TASK_NOT_CLAIMABLE" in str(exc.value)
    assert "never-log-this" not in str(exc.value)


def test_client_should_report_delivery_failure_with_outbox_and_delivery_identity(monkeypatch) -> None:
    from app.agent_task_client import JavaResearchAgentTaskClient

    client = JavaResearchAgentTaskClient("http://backend", "token", "worker-a")
    calls: list[tuple[str, str, dict, str]] = []

    def fake_request(method, path, payload=None, idempotency_key=""):
        calls.append((method, path, payload or {}, idempotency_key))
        return {"data": {"failure_id": "failure-1"}}

    monkeypatch.setattr(client, "_request", fake_request)
    command = ResearchAgentCommand(
        schema_version="research-agent-command.v1",
        command_id="outbox-1",
        research_run_id="run-1",
        agent_task_id="task-1",
        idempotency_key="delivery-2",
        delivery_attempt=2,
    )

    client.report_delivery_failure(command, {"error_type": "TimeoutError", "trace_digest": "sha256:failure"})

    assert calls[0][1] == "/internal/research-agent/delivery-failures"
    assert calls[0][2]["outbox_id"] == "outbox-1"
    assert calls[0][2]["failure_key"] == "outbox-1:delivery:2:TimeoutError"
    assert calls[0][3] == "agent-failure:outbox-1:delivery:2:TimeoutError"


def test_client_permit_should_send_only_lease_identity_and_tool_not_caller_scope(monkeypatch) -> None:
    from app.agent_task_client import JavaResearchAgentTaskClient

    client = JavaResearchAgentTaskClient("http://backend", "token", "worker-a")
    calls: list[tuple[str, str, dict, str]] = []

    def fake_request(method, path, payload=None, idempotency_key=""):
        calls.append((method, path, payload or {}, idempotency_key))
        return {"data": {"status": "GRANTED", "tool_identity": "search"}}

    monkeypatch.setattr(client, "_request", fake_request)
    snapshot = SimpleNamespace(
        task_id="task-1",
        lease_epoch=2,
        fencing_token=7,
        provider_key="must-not-be-sent",
        workspace_id="must-not-be-sent",
        research_run_id="must-not-be-sent",
        role="must-not-be-sent",
    )

    client.require_permit(snapshot, "search")

    assert calls[0][1] == "/internal/research-agent/permits"
    assert calls[0][2] == {
        "task_id": "task-1",
        "worker_instance_id": "worker-a",
        "lease_epoch": 2,
        "fencing_token": 7,
        "tool_identity": "search",
    }
    assert calls[0][3] == "agent-permit:task-1:2:7:search"


def test_client_permit_should_stop_managed_execution_on_authoritative_stale_lease(monkeypatch) -> None:
    from app.agent_task_client import AgentTaskApiError, JavaResearchAgentTaskClient
    from app.execution_control import ExecutionControl, ExecutionStopped, execution_control_scope

    client = JavaResearchAgentTaskClient("http://backend", "token", "worker-a")
    snapshot = SimpleNamespace(task_id="task-1", lease_epoch=2, fencing_token=7)
    monkeypatch.setattr(
        client,
        "_request",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(
            AgentTaskApiError(400, "RESEARCH_AGENT_TASK_STALE_LEASE")
        ),
    )
    control = ExecutionControl()

    with execution_control_scope(control):
        with pytest.raises(ExecutionStopped, match="STALE_LEASE"):
            client.require_permit(snapshot, "search")

    assert control.stop_reason == "STALE_LEASE"


def test_client_should_archive_external_snapshot_with_only_authoritative_lease_identity(monkeypatch) -> None:
    from app.agent_task_client import JavaResearchAgentTaskClient

    client = JavaResearchAgentTaskClient("http://backend", "token", "worker-a")
    snapshot = SimpleNamespace(task_id="task-1", lease_epoch=2, fencing_token=7)
    calls: list[tuple[str, str, dict, str]] = []

    def fake_request(method, path, payload=None, idempotency_key=""):
        calls.append((method, path, payload or {}, idempotency_key))
        return {"data": {
            "archive_id": "archive-1", "task_id": "task-1", "window_id": "window-1", "source_id": "source-1",
            "snapshot_status": "EXTERNAL_ARCHIVED", "snapshot_key": "research/external/task-1/" + "a" * 64,
            "content_sha256": "b" * 64, "idempotent_replay": False,
        }}

    monkeypatch.setattr(client, "_request", fake_request)
    receipt = client.archive_external_snapshot(
        snapshot, window_id="window-1", source_id="source-1", source_title="External source",
        source_url="https://example.com/research", provider="search-provider", adapter="external_url",
        content_text="Archived external quote.",
    )

    assert receipt.snapshot_status == "EXTERNAL_ARCHIVED"
    assert calls[0][1] == "/internal/research-agent/external-snapshots"
    assert calls[0][2] == {
        "task_id": "task-1", "worker_instance_id": "worker-a", "lease_epoch": 2, "fencing_token": 7,
        "window_id": "window-1", "source_id": "source-1", "source_title": "External source",
        "source_url": "https://example.com/research", "provider": "search-provider", "adapter": "external_url",
        "content_text": "Archived external quote.",
    }
    assert calls[0][3].startswith("agent-archive:task-1:2:7:window-1:source-1:")


@pytest.mark.parametrize(
    "response_loss",
    [error.URLError("response lost after commit"), ConnectionResetError("connection reset after commit")],
)
def test_client_complete_should_retry_response_loss_with_byte_identical_envelope_and_identity(
    monkeypatch, response_loss
) -> None:
    from app.agent_task_client import JavaResearchAgentTaskClient

    completion = _completion()
    client = JavaResearchAgentTaskClient(
        "http://backend", "token", "worker-a", completion_max_attempts=2
    )
    requests: list[tuple[bytes | None, str | None]] = []

    class Response:
        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

        def read(self, *_args) -> bytes:
            return json.dumps({"data": _receipt_response(completion, replay=True)}).encode("utf-8")

    def response_loss_then_replay(req, *, timeout):
        assert timeout == 30
        requests.append((req.data, req.get_header("X-noteweave-idempotency-key")))
        if len(requests) == 1:
            raise response_loss
        return Response()

    monkeypatch.setattr("app.agent_task_client.request.urlopen", response_loss_then_replay)
    receipt = client.complete(completion)

    assert receipt.completion_id == "completion-1"
    assert receipt.schema_version == "research-agent-completion-receipt.v1"
    assert receipt.execution_id == "execution-1"
    assert receipt.completion_digest == completion.envelope_digest
    assert receipt.idempotent_replay is True
    assert len(requests) == 2
    assert requests[0] == requests[1]
    assert requests[0][0] is not None
    assert json.loads(requests[0][0]) == completion.model_dump(mode="json", exclude_none=True)


def test_client_complete_should_stop_before_retry_when_execution_control_stops(monkeypatch) -> None:
    from app.agent_task_client import AgentTaskApiError, JavaResearchAgentTaskClient
    from app.execution_control import ExecutionControl, ExecutionStopped, execution_control_scope

    client = JavaResearchAgentTaskClient(
        "http://backend", "token", "worker-a", completion_max_attempts=3
    )
    control = ExecutionControl()
    attempts = 0

    def stop_after_ambiguous_attempt(*_args, **_kwargs):
        nonlocal attempts
        attempts += 1
        control.stop("STALE_LEASE")
        raise AgentTaskApiError(503, "RESEARCH_AGENT_API_UNAVAILABLE")

    monkeypatch.setattr(client, "_request_serialized", stop_after_ambiguous_attempt)
    with execution_control_scope(control):
        with pytest.raises(ExecutionStopped, match="STALE_LEASE"):
            client.complete(_completion())

    assert attempts == 1


def test_client_complete_should_reject_envelope_for_another_worker_before_network(monkeypatch) -> None:
    from app.agent_task_client import JavaResearchAgentTaskClient

    client = JavaResearchAgentTaskClient("http://backend", "token", "worker-b")
    monkeypatch.setattr(
        "app.agent_task_client.request.urlopen",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(AssertionError("network must not be called")),
    )

    with pytest.raises(ValueError, match="worker_instance_id"):
        client.complete(_completion())


@pytest.mark.parametrize("worker_id", ["Worker-A", " worker-a", "worker-a ", "执行-worker"])
def test_client_should_reject_noncanonical_worker_identity_before_network(worker_id: str) -> None:
    from app.agent_task_client import JavaResearchAgentTaskClient

    with pytest.raises(ValueError, match="worker_instance_id"):
        JavaResearchAgentTaskClient("http://backend", "token", worker_id)


@pytest.mark.parametrize(
    ("changed_field", "changed_value"),
    [
        ("schema_version", "research-agent-completion-receipt.v2"),
        ("schema_version", "<missing>"),
        ("task_id", "different-task"),
        ("completion_digest", "sha256:" + "4" * 64),
        ("receipt_digest", "not-a-sha256-digest"),
        ("evidence_appended", 1),
        ("execution_id", " "),
        ("completion_id", ""),
    ],
)
def test_client_complete_should_reject_mismatched_or_malformed_success_receipt(
    monkeypatch, changed_field, changed_value
) -> None:
    from app.agent_task_client import AgentTaskProtocolError, JavaResearchAgentTaskClient

    completion = _completion()
    client = JavaResearchAgentTaskClient("http://backend", "token", "worker-a")
    response = _receipt_response(completion, replay=False)
    if changed_value == "<missing>":
        response.pop(changed_field)
    else:
        response[changed_field] = changed_value
    monkeypatch.setattr(client, "_request_serialized", lambda *_args, **_kwargs: {"data": response})

    with pytest.raises(AgentTaskProtocolError):
        client.complete(completion)


def test_client_complete_should_exact_replay_after_malformed_success_body(monkeypatch) -> None:
    from app.agent_task_client import JavaResearchAgentTaskClient

    completion = _completion()
    client = JavaResearchAgentTaskClient(
        "http://backend", "token", "worker-a", completion_max_attempts=2
    )
    requests: list[tuple[bytes | None, str]] = []
    valid = _receipt_response(completion, replay=True)

    def malformed_then_exact(_method, _path, serialized_payload, *, idempotency_key, **_kwargs):
        requests.append((serialized_payload, idempotency_key))
        if len(requests) == 1:
            return {"data": {**valid, "task_id": "truncated-or-wrong"}}
        return {"data": valid}

    monkeypatch.setattr(client, "_request_serialized", malformed_then_exact)
    receipt = client.complete(completion)

    assert receipt.idempotent_replay is True
    assert len(requests) == 2
    assert requests[0] == requests[1]


def test_client_receipt_should_accept_nonnegative_int32_cell_versions_above_usage_counter_limit(monkeypatch) -> None:
    from app.agent_task_client import JavaResearchAgentTaskClient

    completion = _completion_with_candidate(base_cell_version=1_500_000)
    client = JavaResearchAgentTaskClient("http://backend", "token", "worker-a")
    response = _receipt_response(completion, replay=False)
    monkeypatch.setattr(client, "_request_serialized", lambda *_args, **_kwargs: {"data": response})

    receipt = client.complete(completion)

    assert receipt.completion_digest == completion.envelope_digest
    assert response["accepted_merges"][0]["from_version"] == 1_500_000  # type: ignore[index]
    assert response["accepted_merges"][0]["to_version"] == 1_500_001  # type: ignore[index]


def test_client_complete_should_retry_same_bytes_after_duplicate_key_receipt_json(monkeypatch) -> None:
    from app.agent_task_client import JavaResearchAgentTaskClient

    completion = _completion()
    client = JavaResearchAgentTaskClient(
        "http://backend", "token", "worker-a", completion_max_attempts=2
    )
    valid_body = json.dumps(
        {"data": _receipt_response(completion, replay=True)}, separators=(",", ":")
    ).encode("utf-8")
    needle = f'"task_id":"{completion.task_id}"'.encode("utf-8")
    duplicate_body = valid_body.replace(
        needle,
        f'"task_id":"wrong-task","task_id":"{completion.task_id}"'.encode("utf-8"),
        1,
    )
    request_bodies: list[bytes | None] = []

    class Response:
        def __init__(self, body: bytes) -> None:
            self.body = body

        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

        def read(self, *_args) -> bytes:
            return self.body

    def duplicate_then_valid(req, *, timeout):
        assert timeout == 30
        request_bodies.append(req.data)
        return Response(duplicate_body if len(request_bodies) == 1 else valid_body)

    monkeypatch.setattr("app.agent_task_client.request.urlopen", duplicate_then_valid)
    receipt = client.complete(completion)

    assert receipt.idempotent_replay is True
    assert len(request_bodies) == 2
    assert request_bodies[0] == request_bodies[1]


def test_client_complete_should_bound_receipt_body_before_decode_and_retry_same_bytes(monkeypatch) -> None:
    from app.agent_task_client import JavaResearchAgentTaskClient

    completion = _completion()
    client = JavaResearchAgentTaskClient(
        "http://backend", "token", "worker-a", completion_max_attempts=2
    )
    valid_body = json.dumps({"data": _receipt_response(completion, replay=True)}).encode("utf-8")
    request_bodies: list[bytes | None] = []
    read_limits: list[int | None] = []

    class Response:
        def __init__(self, body: bytes) -> None:
            self.body = body

        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

        def read(self, limit=None) -> bytes:
            read_limits.append(limit)
            return self.body if limit is None else self.body[:limit]

    def oversized_then_valid(req, *, timeout):
        assert timeout == 30
        request_bodies.append(req.data)
        body = b"{" + b"x" * (256 * 1024 + 1) if len(request_bodies) == 1 else valid_body
        return Response(body)

    monkeypatch.setattr("app.agent_task_client.request.urlopen", oversized_then_valid)
    assert client.complete(completion).idempotent_replay is True
    assert read_limits == [256 * 1024 + 1, 256 * 1024 + 1]
    assert request_bodies[0] == request_bodies[1]
