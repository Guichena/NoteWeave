"""Internal Backend client for leases, permits, and MA4G atomic completion."""

from __future__ import annotations

import hashlib
import hmac
import http.client
import json
import re
import unicodedata
from dataclasses import dataclass
from typing import Any
from urllib import error, request

from app.agent_command_contract import ResearchAgentCommand
from app.research_agent_completion_contract import (
    ResearchAgentCompletionEnvelope,
    domain_separated_json_digest,
    require_worker_instance_id,
    serialize_completion_envelope,
)
from app.unicode_contract import has_unicode_boundary_whitespace, is_unicode_blank
from app.task_snapshot_contract import ResearchAgentTaskSnapshot


class AgentTaskNotClaimableError(RuntimeError):
    """A normal command-delivery race: another execution owns or finished the task."""


class AgentTaskApiError(RuntimeError):
    """Sanitized internal API failure with a status suitable for retry classification."""

    def __init__(self, status_code: int, error_code: str = "") -> None:
        self.status_code = status_code
        self.error_code = error_code
        super().__init__(f"Research agent API failed: {status_code}{f' {error_code}' if error_code else ''}")


class AgentTaskProtocolError(RuntimeError):
    """Backend returned a successful HTTP response that violates the completion contract."""


@dataclass(frozen=True)
class AgentTaskClaim:
    agent_task_id: str
    lease_epoch: int
    fencing_token: int
    target_cells_json: str
    budget_json: str
    task_snapshot_json: str | None = None
    snapshot_digest: str | None = None


@dataclass(frozen=True)
class AgentCompletionReceipt:
    schema_version: str
    completion_id: str
    execution_id: str
    task_id: str
    completion_digest: str
    receipt_digest: str
    idempotent_replay: bool


@dataclass(frozen=True)
class ExternalSnapshotArchiveReceipt:
    archive_id: str
    task_id: str
    window_id: str
    source_id: str
    snapshot_status: str
    snapshot_key: str
    content_sha256: str
    idempotent_replay: bool


@dataclass(frozen=True)
class ArchivedExternalSnapshot:
    """One server-owned external page already archived for this task by an earlier execution."""

    window_id: str
    source_id: str
    source_title: str
    source_url: str
    provider: str
    adapter: str
    snapshot_key: str
    content_text: str
    content_sha256: str


@dataclass(frozen=True)
class WorkspaceWindowSearchHit:
    source_window_id: str
    source_snapshot_id: str
    source_id: str
    source_title: str
    query: str
    window_text: str
    score_ppm: int


class JavaResearchAgentTaskClient:
    def __init__(self, java_base_url: str, internal_auth_token: str, worker_instance_id: str,
                 *, completion_max_attempts: int = 3,
                 heartbeat_request_timeout_seconds: float = 5.0) -> None:
        self.java_base_url = java_base_url.rstrip("/")
        self.internal_auth_token = internal_auth_token.strip()
        self.worker_instance_id = require_worker_instance_id(worker_instance_id)
        if completion_max_attempts < 1 or completion_max_attempts > 10:
            raise ValueError("completion_max_attempts must be between 1 and 10")
        if heartbeat_request_timeout_seconds <= 0 or heartbeat_request_timeout_seconds > 30:
            raise ValueError("heartbeat_request_timeout_seconds must be between zero and 30")
        self.completion_max_attempts = completion_max_attempts
        self.heartbeat_request_timeout_seconds = heartbeat_request_timeout_seconds

    def claim(self, agent_task_id: str, *, lease_seconds: int = 60) -> AgentTaskClaim:
        payload = self._data(self._request(
            "POST",
            f"/internal/research-agent-tasks/{agent_task_id}/claim",
            {"worker_instance_id": self.worker_instance_id, "lease_seconds": lease_seconds},
            idempotency_key=f"{agent_task_id}:{self.worker_instance_id}:claim",
        ))
        return AgentTaskClaim(
            agent_task_id=str(payload["task_id"]),
            lease_epoch=int(payload["lease_epoch"]),
            fencing_token=int(payload["fencing_token"]),
            target_cells_json=str(payload.get("target_cells_json") or "[]"),
            budget_json=str(payload.get("budget_json") or "{}"),
            task_snapshot_json=_optional_string(payload.get("task_snapshot_json")),
            snapshot_digest=_optional_string(payload.get("snapshot_digest")),
        )

    def heartbeat(self, claim: AgentTaskClaim, *, lease_seconds: int = 60) -> AgentTaskClaim:
        payload = self._data(self._request(
            "POST",
            f"/internal/research-agent-tasks/{claim.agent_task_id}/heartbeat",
            {
                "worker_instance_id": self.worker_instance_id,
                "lease_epoch": claim.lease_epoch,
                "fencing_token": claim.fencing_token,
                "lease_seconds": lease_seconds,
            },
            idempotency_key=f"{claim.agent_task_id}:{claim.lease_epoch}:{claim.fencing_token}:heartbeat",
            request_timeout_seconds=min(
                self.heartbeat_request_timeout_seconds,
                max(0.1, lease_seconds / 8),
            ),
        ))
        return AgentTaskClaim(
            agent_task_id=str(payload["task_id"]),
            lease_epoch=int(payload["lease_epoch"]),
            fencing_token=int(payload["fencing_token"]),
            target_cells_json=str(payload.get("target_cells_json") or "[]"),
            budget_json=str(payload.get("budget_json") or "{}"),
            task_snapshot_json=_optional_string(payload.get("task_snapshot_json")),
            snapshot_digest=_optional_string(payload.get("snapshot_digest")),
        )

    def complete(self, completion: ResearchAgentCompletionEnvelope) -> AgentCompletionReceipt:
        """Atomically finalize one execution, replaying byte-identical bytes on ambiguity."""
        if completion.worker_instance_id != self.worker_instance_id:
            raise ValueError("completion worker_instance_id must match client identity")
        serialized = serialize_completion_envelope(completion)
        idempotency_key = (
            f"{completion.task_id}:{completion.execution_key}:complete:{completion.envelope_digest}"
        )
        for attempt in range(self.completion_max_attempts):
            # Imported lazily to keep the transport contract reusable while
            # still honoring MA4H stop signals at every HTTP replay boundary.
            from app.execution_control import require_execution_active
            require_execution_active()
            try:
                response = self._request_serialized(
                    "POST",
                    f"/internal/research-agent-tasks/{completion.task_id}/complete",
                    serialized,
                    idempotency_key=idempotency_key,
                    not_claimable_is_normal=False,
                    max_response_bytes=_MAX_RECEIPT_BYTES,
                )
                data = response.get("data") if isinstance(response, dict) else None
                if not isinstance(data, dict):
                    raise AgentTaskProtocolError("completion response does not contain object data")
                return _validated_completion_receipt(data, completion)
            except AgentTaskApiError as exc:
                if (exc.status_code != 429 and exc.status_code < 500) or attempt + 1 >= self.completion_max_attempts:
                    raise
            except (AgentTaskProtocolError, json.JSONDecodeError, UnicodeDecodeError) as exc:
                if attempt + 1 >= self.completion_max_attempts:
                    if isinstance(exc, AgentTaskProtocolError):
                        raise
                    raise AgentTaskProtocolError("completion response is not valid UTF-8 JSON") from exc
        raise AgentTaskProtocolError("completion response retry loop ended without a receipt")

    def require_permit(self, snapshot: ResearchAgentTaskSnapshot, tool_identity: str) -> None:
        try:
            data = self._data(self._request(
                "POST",
                "/internal/research-agent/permits",
                {
                    "task_id": snapshot.task_id,
                    "worker_instance_id": self.worker_instance_id,
                    "lease_epoch": snapshot.lease_epoch,
                    "fencing_token": snapshot.fencing_token,
                    "tool_identity": tool_identity,
                },
                idempotency_key=(
                    f"agent-permit:{snapshot.task_id}:{snapshot.lease_epoch}:"
                    f"{snapshot.fencing_token}:{tool_identity}"
                ),
            ))
        except AgentTaskApiError as exc:
            if exc.error_code == "RESEARCH_AGENT_TASK_STALE_LEASE":
                from app.execution_control import stop_current_execution
                stop_current_execution("STALE_LEASE")
            raise
        if set(data) != {"status", "tool_identity"} or data.get("status") != "GRANTED" \
                or data.get("tool_identity") != tool_identity:
            raise AgentTaskProtocolError("Research agent permit response does not match the requested tool")

    def search_workspace_windows(
        self,
        snapshot: ResearchAgentTaskSnapshot,
        *,
        queries: list[str],
        limit: int,
    ) -> list[WorkspaceWindowSearchHit]:
        """Search only source snapshots already frozen into the authoritative task."""
        response = self._request(
            "POST",
            "/internal/research-agent/workspace-windows/search",
            {
                "task_id": snapshot.task_id,
                "worker_instance_id": self.worker_instance_id,
                "lease_epoch": snapshot.lease_epoch,
                "fencing_token": snapshot.fencing_token,
                "queries": queries,
                "limit": limit,
            },
            idempotency_key=(
                f"agent-workspace-search:{snapshot.task_id}:{snapshot.lease_epoch}:"
                f"{snapshot.fencing_token}:{hashlib.sha256(json.dumps(queries).encode('utf-8')).hexdigest()}:{limit}"
            ),
        )
        raw_hits = response.get("data") if isinstance(response, dict) else None
        if not isinstance(raw_hits, list):
            raise AgentTaskProtocolError("workspace window search response data must be an array")
        hits: list[WorkspaceWindowSearchHit] = []
        expected = {
            "source_window_id", "source_snapshot_id", "source_id", "source_title",
            "query", "window_text", "score_ppm",
        }
        for raw in raw_hits:
            if not isinstance(raw, dict) or set(raw) != expected:
                raise AgentTaskProtocolError("workspace window search hit contains missing or unknown fields")
            values = {key: raw.get(key) for key in expected - {"score_ppm"}}
            if any(not isinstance(value, str) or not value.strip() for value in values.values()):
                raise AgentTaskProtocolError("workspace window search hit has an invalid text field")
            score = raw.get("score_ppm")
            if isinstance(score, bool) or not isinstance(score, int) or score < 0 or score > 1_000_000:
                raise AgentTaskProtocolError("workspace window search hit has an invalid score")
            hits.append(WorkspaceWindowSearchHit(score_ppm=score, **values))
        return hits

    def archive_external_snapshot(
        self,
        snapshot: ResearchAgentTaskSnapshot,
        *,
        window_id: str,
        source_id: str,
        source_title: str,
        source_url: str,
        provider: str,
        adapter: str,
        content_text: str,
    ) -> ExternalSnapshotArchiveReceipt:
        """Persist external text first; only the returned archive may back completion evidence."""
        content_digest = hashlib.sha256(content_text.encode("utf-8")).hexdigest()
        try:
            data = self._data(self._request(
                "POST",
                "/internal/research-agent/external-snapshots",
                {
                    "task_id": snapshot.task_id,
                    "worker_instance_id": self.worker_instance_id,
                    "lease_epoch": snapshot.lease_epoch,
                    "fencing_token": snapshot.fencing_token,
                    "window_id": window_id,
                    "source_id": source_id,
                    "source_title": source_title,
                    "source_url": source_url,
                    "provider": provider,
                    "adapter": adapter,
                    "content_text": content_text,
                },
                idempotency_key=(
                    f"agent-archive:{snapshot.task_id}:{snapshot.lease_epoch}:"
                    f"{snapshot.fencing_token}:{window_id}:{source_id}:{content_digest}"
                ),
            ))
        except AgentTaskApiError as exc:
            if exc.error_code == "RESEARCH_AGENT_TASK_STALE_LEASE":
                from app.execution_control import stop_current_execution
                stop_current_execution("STALE_LEASE")
            raise
        return _validated_external_archive_receipt(data, snapshot, window_id, source_id)

    def archived_external_snapshot_inventory(
        self,
        snapshot: ResearchAgentTaskSnapshot,
    ) -> list[ArchivedExternalSnapshot]:
        """Read the task's server-owned external archive before paying to fetch it again."""
        response = self._request(
            "POST",
            "/internal/research-agent/external-snapshots/archived",
            {
                "task_id": snapshot.task_id,
                "worker_instance_id": self.worker_instance_id,
                "lease_epoch": snapshot.lease_epoch,
                "fencing_token": snapshot.fencing_token,
            },
            idempotency_key=(
                f"agent-archive-inventory:{snapshot.task_id}:{snapshot.lease_epoch}"
                f":{snapshot.fencing_token}"
            ),
        )
        raw_items = response.get("data") if isinstance(response, dict) else None
        if not isinstance(raw_items, list):
            raise AgentTaskProtocolError("external archive inventory response data must be an array")
        inventory: list[ArchivedExternalSnapshot] = []
        for raw in raw_items:
            if not isinstance(raw, dict) or set(raw) != _EXTERNAL_ARCHIVE_INVENTORY_FIELDS:
                raise AgentTaskProtocolError("external archive inventory row has missing or unknown fields")
            values = {key: raw.get(key) for key in _EXTERNAL_ARCHIVE_INVENTORY_FIELDS}
            if any(not isinstance(value, str) or is_unicode_blank(value) for value in values.values()):
                raise AgentTaskProtocolError("external archive inventory row has an invalid text field")
            if any(has_unicode_boundary_whitespace(value) for value in values.values()):
                raise AgentTaskProtocolError("external archive inventory row has boundary whitespace")
            content_sha256 = str(values["content_sha256"])
            if not _SHA256_TEXT_PATTERN.fullmatch(content_sha256):
                raise AgentTaskProtocolError("external archive inventory content_sha256 is invalid")
            # Recomputed locally: a mismatched archive can never become completion evidence.
            if not hmac.compare_digest(
                hashlib.sha256(str(values["content_text"]).encode("utf-8")).hexdigest(), content_sha256
            ):
                raise AgentTaskProtocolError("external archive inventory content does not match its digest")
            inventory.append(ArchivedExternalSnapshot(**values))
        return inventory

    def report_delivery_failure(self, command: ResearchAgentCommand, failure: dict[str, object]) -> None:
        error_message = str(failure.get("error_message") or "").strip()
        reason_code = (
            error_message
            if re.fullmatch(r"[A-Z][A-Z0-9_]{2,127}", error_message)
            else str(failure.get("error_type") or "AGENT_COMMAND_FAILURE")[:128]
        )
        digest = str(failure.get("trace_digest") or "")[:128]
        if not digest:
            raise ValueError("delivery failure requires a sanitized trace digest")
        failure_identity = f"{command.command_id}:delivery:{command.delivery_attempt}:{reason_code}"
        self._data(self._request(
            "POST",
            "/internal/research-agent/delivery-failures",
            {
                "research_run_id": command.research_run_id,
                "task_id": command.agent_task_id,
                "outbox_id": command.command_id,
                "failure_key": failure_identity,
                "reason_code": reason_code,
                "trace_digest": digest,
                "delivery_attempt": command.delivery_attempt,
            },
            idempotency_key=f"agent-failure:{failure_identity}",
        ))

    def _request(self, method: str, path: str, payload: dict[str, Any] | None = None,
                 idempotency_key: str = "", request_timeout_seconds: float = 30.0) -> dict[str, Any]:
        serialized = None if payload is None else json.dumps(payload).encode("utf-8")
        return self._request_serialized(
            method,
            path,
            serialized,
            idempotency_key=idempotency_key,
            request_timeout_seconds=request_timeout_seconds,
        )

    def _request_serialized(self, method: str, path: str, serialized_payload: bytes | None = None,
                            *, idempotency_key: str = "", not_claimable_is_normal: bool = True,
                            max_response_bytes: int | None = None,
                            request_timeout_seconds: float = 30.0) -> dict[str, Any]:
        req = request.Request(
            f"{self.java_base_url}{path}",
            data=serialized_payload,
            method=method,
            headers={
                "Content-Type": "application/json",
                **({"X-NoteWeave-Internal-Token": self.internal_auth_token} if self.internal_auth_token else {}),
                **({"X-NoteWeave-Idempotency-Key": idempotency_key} if idempotency_key else {}),
            },
        )
        try:
            with request.urlopen(req, timeout=request_timeout_seconds) as response:
                raw_bytes = response.read() if max_response_bytes is None else response.read(max_response_bytes + 1)
                if max_response_bytes is not None and len(raw_bytes) > max_response_bytes:
                    raise AgentTaskProtocolError("Research agent API response exceeds the byte limit")
                raw = raw_bytes.decode("utf-8", errors="strict")
        except error.HTTPError as exc:
            error_code = _read_error_code(exc)
            if not_claimable_is_normal and error_code in {
                "RESEARCH_AGENT_TASK_NOT_CLAIMABLE", "RESEARCH_AGENT_RUN_TERMINAL"
            }:
                raise AgentTaskNotClaimableError(error_code) from exc
            raise AgentTaskApiError(exc.code, error_code) from exc
        except error.URLError as exc:
            raise AgentTaskApiError(503, "RESEARCH_AGENT_API_UNAVAILABLE") from exc
        except (TimeoutError, ConnectionError, OSError, http.client.HTTPException) as exc:
            raise AgentTaskApiError(503, "RESEARCH_AGENT_API_UNAVAILABLE") from exc
        return _strict_json_response(raw) if raw else {}

    @staticmethod
    def _data(response: dict[str, Any]) -> dict[str, Any]:
        data = response.get("data") if isinstance(response, dict) else None
        if not isinstance(data, dict):
            raise RuntimeError("Research agent API response does not contain object data")
        return data


def _read_error_code(exc: error.HTTPError) -> str:
    """Extract only the stable API code; never echo an internal response body into Worker logs."""
    try:
        payload = json.loads(exc.read().decode("utf-8", errors="replace"))
    except Exception:
        return ""
    if not isinstance(payload, dict):
        return ""
    code = payload.get("code")
    return code.strip() if isinstance(code, str) else ""


def _reject_duplicate_response_pairs(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise AgentTaskProtocolError("Research agent API response contains a duplicate JSON object key")
        result[key] = value
    return result


def _strict_json_response(raw: str) -> dict[str, Any]:
    if raw.startswith("\ufeff"):
        raise AgentTaskProtocolError("Research agent API response must not contain a BOM")
    parsed = json.loads(raw, object_pairs_hook=_reject_duplicate_response_pairs)
    if not isinstance(parsed, dict):
        raise AgentTaskProtocolError("Research agent API response must contain one JSON object")
    return parsed


def _optional_string(value: object) -> str | None:
    if value is None:
        return None
    normalized = str(value).strip()
    return normalized or None


_SHA256_PATTERN = re.compile(r"^sha256:[0-9a-f]{64}$")
_SHA256_TEXT_PATTERN = re.compile(r"^[0-9a-f]{64}$")
_RECEIPT_SCHEMA = "research-agent-completion-receipt.v1"
_RECEIPT_FIELDS = {
    "schema_version", "completion_id", "execution_id", "task_id", "completion_digest",
    "receipt_digest", "idempotent_replay", "outcome", "evidence_appended", "candidate_count",
    "accepted_merges", "rejected_merges", "budget",
}
_MERGE_FIELDS = {"cell_key", "from_version", "to_version", "decision", "reason_code"}
_BUDGET_FIELDS = {"state", "reserved", "consumed", "released"}
_RESERVATION_KEYS = {
    "llm_calls", "search_calls", "fetch_calls", "read_calls", "extract_calls", "evidence_cards",
    "evidence_appended", "candidates_submitted", "candidate_merges_accepted",
    "candidate_merges_rejected",
}
_MAX_RECEIPT_COUNTER = 1_000_000
_MAX_CELL_VERSION = 2_147_483_647
_MAX_RECEIPT_BYTES = 256 * 1024
_EXTERNAL_ARCHIVE_RECEIPT_FIELDS = {
    "archive_id", "task_id", "window_id", "source_id", "snapshot_status", "snapshot_key",
    "content_sha256", "idempotent_replay",
}
_EXTERNAL_ARCHIVE_INVENTORY_FIELDS = {
    "window_id", "source_id", "source_title", "source_url", "provider", "adapter",
    "snapshot_key", "content_text", "content_sha256",
}


def _validated_completion_receipt(payload: dict[str, Any],
                                  completion: ResearchAgentCompletionEnvelope) -> AgentCompletionReceipt:
    if set(payload) != _RECEIPT_FIELDS:
        raise AgentTaskProtocolError("completion response contains missing or unknown fields")

    def identifier(container: dict[str, Any], key: str, *, max_chars: int = 160) -> str:
        value = container.get(key)
        if (not isinstance(value, str) or is_unicode_blank(value) or has_unicode_boundary_whitespace(value)
                or len(value) > max_chars or not unicodedata.is_normalized("NFC", value)):
            raise AgentTaskProtocolError(f"completion response has invalid {key}")
        return value

    def counter(container: dict[str, Any], key: str) -> int:
        value = container.get(key)
        if isinstance(value, bool) or not isinstance(value, int) or value < 0 or value > _MAX_RECEIPT_COUNTER:
            raise AgentTaskProtocolError(f"completion response has invalid {key}")
        return value

    def cell_version(container: dict[str, Any], key: str) -> int:
        value = container.get(key)
        if isinstance(value, bool) or not isinstance(value, int) or value < 0 or value > _MAX_CELL_VERSION:
            raise AgentTaskProtocolError(f"completion response has invalid {key}")
        return value

    def counter_map(raw: object, scope: str) -> dict[str, int]:
        if not isinstance(raw, dict) or set(raw) != _RESERVATION_KEYS:
            raise AgentTaskProtocolError(f"completion response has invalid {scope}")
        return {key: counter(raw, key) for key in _RESERVATION_KEYS}

    def merge_list(raw: object, scope: str) -> list[dict[str, Any]]:
        if not isinstance(raw, list) or len(raw) > 3:
            raise AgentTaskProtocolError(f"completion response has invalid {scope}")
        result: list[dict[str, Any]] = []
        for item in raw:
            if not isinstance(item, dict) or set(item) != _MERGE_FIELDS:
                raise AgentTaskProtocolError(f"completion response has invalid {scope}")
            identifier(item, "cell_key")
            identifier(item, "decision", max_chars=32)
            identifier(item, "reason_code", max_chars=96)
            cell_version(item, "from_version")
            cell_version(item, "to_version")
            result.append(item)
        return result

    schema_version = identifier(payload, "schema_version", max_chars=64)
    if schema_version != _RECEIPT_SCHEMA:
        raise AgentTaskProtocolError("completion response schema_version is unsupported")
    completion_id = identifier(payload, "completion_id", max_chars=64)
    execution_id = identifier(payload, "execution_id", max_chars=64)
    task_id = identifier(payload, "task_id", max_chars=36)
    completion_digest = identifier(payload, "completion_digest", max_chars=71)
    receipt_digest = identifier(payload, "receipt_digest", max_chars=71)
    if task_id != completion.task_id:
        raise AgentTaskProtocolError("completion response task_id does not match request")
    if not _SHA256_PATTERN.fullmatch(completion_digest) or not hmac.compare_digest(
        completion_digest, completion.envelope_digest
    ):
        raise AgentTaskProtocolError("completion response digest does not match request")
    if not _SHA256_PATTERN.fullmatch(receipt_digest):
        raise AgentTaskProtocolError("completion response receipt_digest is invalid")
    replay = payload.get("idempotent_replay")
    if not isinstance(replay, bool):
        raise AgentTaskProtocolError("completion response idempotent_replay must be boolean")
    outcome = identifier(payload, "outcome", max_chars=32)
    if outcome not in {"COMMITTED", "QUORUM_PENDING", "QUORUM_MERGED", "QUORUM_REPAIR_REQUIRED"}:
        raise AgentTaskProtocolError("completion response outcome is unsupported")
    evidence_appended = counter(payload, "evidence_appended")
    candidate_count = counter(payload, "candidate_count")
    accepted = merge_list(payload.get("accepted_merges"), "accepted_merges")
    rejected = merge_list(payload.get("rejected_merges"), "rejected_merges")
    cells = [str(item["cell_key"]) for item in [*accepted, *rejected]]
    pending_shape_invalid = outcome == "QUORUM_PENDING" and (accepted or rejected)
    committed_shape_invalid = outcome != "QUORUM_PENDING" and len(accepted) + len(rejected) != candidate_count
    if len(cells) != len(set(cells)) or pending_shape_invalid or committed_shape_invalid:
        raise AgentTaskProtocolError("completion response merge counts or cells are inconsistent")
    budget = payload.get("budget")
    if not isinstance(budget, dict) or set(budget) != _BUDGET_FIELDS or budget.get("state") != "SETTLED":
        raise AgentTaskProtocolError("completion response budget is invalid")
    reserved = counter_map(budget.get("reserved"), "budget.reserved")
    consumed = counter_map(budget.get("consumed"), "budget.consumed")
    released = counter_map(budget.get("released"), "budget.released")
    if any(reserved[key] != consumed[key] + released[key] for key in _RESERVATION_KEYS):
        raise AgentTaskProtocolError("completion response budget conservation is invalid")
    expected_usage = completion.budget_usage.model_dump(mode="json")
    if any(consumed[key] != expected_usage[key] for key in expected_usage):
        raise AgentTaskProtocolError("completion response worker usage does not match request")
    if (
        evidence_appended != len(completion.evidence)
        or candidate_count != len(completion.candidates)
        or consumed["evidence_appended"] != evidence_appended
        or consumed["candidate_merges_accepted"] != len(accepted)
        or consumed["candidate_merges_rejected"] != len(rejected)
    ):
        raise AgentTaskProtocolError("completion response derived counters are inconsistent")
    unsigned = {key: value for key, value in payload.items() if key not in {"receipt_digest", "idempotent_replay"}}
    expected_receipt_digest = domain_separated_json_digest(_RECEIPT_SCHEMA, unsigned)
    if not hmac.compare_digest(receipt_digest, expected_receipt_digest):
        raise AgentTaskProtocolError("completion response receipt_digest does not match canonical receipt")
    return AgentCompletionReceipt(
        schema_version=schema_version,
        completion_id=completion_id,
        execution_id=execution_id,
        task_id=task_id,
        completion_digest=completion_digest,
        receipt_digest=receipt_digest,
        idempotent_replay=replay,
    )


def _validated_external_archive_receipt(
    payload: dict[str, Any], snapshot: ResearchAgentTaskSnapshot, window_id: str, source_id: str
) -> ExternalSnapshotArchiveReceipt:
    if set(payload) != _EXTERNAL_ARCHIVE_RECEIPT_FIELDS:
        raise AgentTaskProtocolError("external archive response contains missing or unknown fields")
    required = ("archive_id", "task_id", "window_id", "source_id", "snapshot_key", "content_sha256")
    values: dict[str, str] = {}
    for key in required:
        value = payload.get(key)
        if (not isinstance(value, str) or is_unicode_blank(value) or has_unicode_boundary_whitespace(value)
                or len(value) > 512 or not unicodedata.is_normalized("NFC", value)):
            raise AgentTaskProtocolError(f"external archive response has invalid {key}")
        values[key] = value
    if (values["task_id"] != snapshot.task_id or values["window_id"] != window_id
            or values["source_id"] != source_id or payload.get("snapshot_status") != "EXTERNAL_ARCHIVED"
            or not re.fullmatch(r"[0-9a-f]{64}", values["content_sha256"])
            or not values["snapshot_key"].startswith("research/external/" + snapshot.task_id + "/")
            or not isinstance(payload.get("idempotent_replay"), bool)):
        raise AgentTaskProtocolError("external archive response does not match the requested snapshot")
    return ExternalSnapshotArchiveReceipt(
        archive_id=values["archive_id"], task_id=values["task_id"], window_id=values["window_id"],
        source_id=values["source_id"], snapshot_status="EXTERNAL_ARCHIVED",
        snapshot_key=values["snapshot_key"], content_sha256=values["content_sha256"],
        idempotent_replay=bool(payload["idempotent_replay"]),
    )
