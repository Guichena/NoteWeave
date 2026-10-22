"""Background heartbeat for one artifact execution.

The host renews the workload quota lease and the outbox delivery lease on
POST /internal/worker/tasks/{taskId}/heartbeat. Progress is only reported
after the graph finishes, so a long run has to heartbeat on its own.

Ordinary heartbeat failures are logged and the run continues. A STALE or
takeover response stops the run and the caller must drop the local result.
"""

from __future__ import annotations

import logging
import re
from contextlib import contextmanager
from contextvars import ContextVar
from threading import Event, Lock, Thread, current_thread
from typing import Callable, Iterator

logger = logging.getLogger(__name__)

_ERROR_CODE = re.compile(r'"code"\s*:\s*"([A-Z0-9_]+)"')
_TAKEOVER_CODES = frozenset({
    "WORKER_CALLBACK_DELIVERY_STALE",
    "WORKLOAD_LEASE_LOST",
    "WORKER_CALLBACK_OWNER_MISMATCH",
})
_TERMINAL_ACK = frozenset({"CANCELLED", "FAILED", "COMPLETED"})
_CURRENT: ContextVar[ArtifactLeaseHeartbeat | None] = ContextVar(
    "artifact_execution_heartbeat",
    default=None,
)


class ArtifactExecutionSuperseded(Exception):
    """The host no longer wants this worker to finish the task."""

    def __init__(self, task_id: str, reason: str) -> None:
        self.task_id = task_id
        self.reason = (reason or "STALE").strip() or "STALE"
        super().__init__(f"artifact execution stopped: {self.reason}")


class ArtifactLeaseHeartbeat:
    """One daemon thread that heartbeats until the execution ends."""

    def __init__(
        self,
        send: Callable[[str], object],
        task_id: str,
        interval_seconds: float,
    ) -> None:
        if interval_seconds <= 0:
            raise ValueError("artifact heartbeat interval must be positive")
        self.task_id = task_id
        self.interval_seconds = interval_seconds
        self._send = send
        self._stop = Event()
        self._superseded = Event()
        self._lock = Lock()
        self._reason = ""
        self._thread: Thread | None = None
        self._closed = False

    @property
    def reason(self) -> str:
        with self._lock:
            return self._reason

    def start(self) -> None:
        with self._lock:
            if self._closed or self._thread is not None:
                return
            thread = Thread(
                target=self._run,
                name=f"artifact-heartbeat-{self.task_id[:40]}",
                daemon=True,
            )
            self._thread = thread
        thread.start()

    def close(self) -> None:
        with self._lock:
            self._closed = True
            thread = self._thread
        self._stop.set()
        # ident stays unset until start() returns. Joining that thread raises.
        if thread is not None and thread.ident is not None and thread is not current_thread():
            thread.join(timeout=1.0)

    def running(self) -> bool:
        thread = self._thread
        return bool(thread is not None and thread.is_alive() and not self._closed)

    def abandoned(self) -> bool:
        return self._superseded.is_set()

    def checkpoint(self) -> None:
        if self._superseded.is_set():
            raise ArtifactExecutionSuperseded(self.task_id, self.reason or "STALE")

    def _supersede(self, reason: str) -> None:
        with self._lock:
            if not self._reason:
                self._reason = reason or "STALE"
            self._superseded.set()

    def _run(self) -> None:
        if self._closed:
            return
        while not self._stop.is_set():
            if not self._beat_once():
                return
            if self._stop.wait(self.interval_seconds):
                return

    def _beat_once(self) -> bool:
        if self._stop.is_set():
            return False
        try:
            response = self._send(self.task_id)
        except Exception as exc:
            reason = _takeover_reason(exc)
            if reason:
                self._supersede(reason)
                logger.info(
                    "Artifact heartbeat reported takeover; execution will stop: task_id=%s reason=%s",
                    self.task_id,
                    reason,
                )
                return False
            logger.warning(
                "Artifact heartbeat failed; execution continues: task_id=%s status=%s code=%s",
                self.task_id,
                getattr(exc, "status_code", type(exc).__name__),
                _error_code(str(exc)) or "-",
            )
            return True
        reason = _terminal_ack_reason(response)
        if reason:
            self._supersede(reason)
            logger.info(
                "Artifact heartbeat saw a terminal task; execution will stop: task_id=%s status=%s",
                self.task_id,
                reason,
            )
            return False
        return True


@contextmanager
def artifact_execution_heartbeat(
    client: object,
    task_id: str,
    *,
    interval_seconds: float,
) -> Iterator[ArtifactLeaseHeartbeat | None]:
    """Heartbeat while the block runs. Missing clients are a no-op.

    The host heartbeat route requires the outbox delivery token. A Java
    client without one would be rejected as STALE and would abort a debug
    or token-less resume, so those calls do not start a heartbeat.
    """
    sender = getattr(client, "send_heartbeat", None)
    if client is None or not callable(sender) or interval_seconds <= 0:
        yield None
        return
    if hasattr(client, "delivery_token") and not str(getattr(client, "delivery_token") or "").strip():
        yield None
        return
    heartbeat = ArtifactLeaseHeartbeat(sender, task_id, interval_seconds)
    token = _CURRENT.set(heartbeat)
    heartbeat.start()
    try:
        yield heartbeat
    finally:
        heartbeat.close()
        _CURRENT.reset(token)


_MATERIAL_HEARTBEATS: dict[str, ArtifactLeaseHeartbeat] = {}
_MATERIAL_GUARD = Lock()


def ensure_material_execution_heartbeat(
    client: object,
    task_id: str,
    *,
    interval_seconds: float,
) -> ArtifactLeaseHeartbeat | None:
    """Keep one VIDEO_MATERIAL heartbeat alive across provider stages.

    The Kafka handler returns as soon as the task is waiting. Subtitle and
    frame tools then run on background threads, and the next stage starts
    only after the previous tool finishes. Stopping the heartbeat when the
    handler returns lets the 70-minute outbox lease expire and the row is
    republished. The caller stops this heartbeat when the attempt ends.
    """
    sender = getattr(client, "send_heartbeat", None)
    if client is None or not callable(sender) or interval_seconds <= 0:
        return None
    if hasattr(client, "delivery_token") and not str(getattr(client, "delivery_token") or "").strip():
        return None
    with _MATERIAL_GUARD:
        current = _MATERIAL_HEARTBEATS.get(task_id)
        if current is not None and (current.abandoned() or current.running()):
            return current
        heartbeat = ArtifactLeaseHeartbeat(sender, task_id, interval_seconds)
        _MATERIAL_HEARTBEATS[task_id] = heartbeat
    heartbeat.start()
    return heartbeat


def stop_material_execution_heartbeat(task_id: str) -> None:
    with _MATERIAL_GUARD:
        heartbeat = _MATERIAL_HEARTBEATS.pop(task_id, None)
    if heartbeat is not None:
        heartbeat.close()


@contextmanager
def material_execution_lease(
    client: object,
    task_id: str,
    *,
    interval_seconds: float,
) -> Iterator[ArtifactLeaseHeartbeat | None]:
    """Bind the material heartbeat for this call without stopping it on exit."""
    heartbeat = ensure_material_execution_heartbeat(
        client, task_id, interval_seconds=interval_seconds)
    token = _CURRENT.set(heartbeat) if heartbeat is not None else None
    try:
        yield heartbeat
    finally:
        if token is not None:
            _CURRENT.reset(token)


def artifact_heartbeat_is_active() -> bool:
    return _CURRENT.get() is not None


def checkpoint_artifact_execution() -> None:
    """Stop the current run when a heartbeat has already been superseded."""
    current = _CURRENT.get()
    if current is not None:
        current.checkpoint()


def _error_code(detail: str) -> str:
    match = _ERROR_CODE.search(detail)
    return match.group(1) if match else ""


def _takeover_reason(exc: Exception) -> str:
    status = getattr(exc, "status_code", None)
    if not isinstance(status, int):
        return ""
    detail = str(exc)
    code = _error_code(detail)
    if code in _TAKEOVER_CODES or "STALE" in code:
        return code
    if status == 409 and "STALE" in detail.upper():
        return code or "STALE"
    return ""


def _terminal_ack_reason(response: object) -> str:
    if not isinstance(response, dict):
        return ""
    data = response.get("data")
    status = ""
    if isinstance(data, dict):
        status = str(data.get("status") or "")
    elif response.get("status"):
        status = str(response.get("status") or "")
    code = str(response.get("code") or "")
    if code in _TAKEOVER_CODES or "STALE" in code:
        return code
    normalized = status.upper()
    if normalized in _TERMINAL_ACK:
        return normalized
    return ""
