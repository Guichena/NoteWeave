"""MA4H local execution stop-control and lease-heartbeat primitive.

The primitive is deliberately independent from Kafka and tool adapters so its
failure semantics are deterministic and testable before it is wired into the
consumer lifecycle.  A stopped control is irreversible for one execution.
"""

from __future__ import annotations

from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass, field
from threading import Event, Lock, Thread
from time import monotonic
from typing import Iterator, Protocol

from app.agent_task_client import AgentTaskApiError, AgentTaskClaim, AgentTaskNotClaimableError


class ExecutionStopped(RuntimeError):
    """Raised when code attempts further work after a local stop signal."""


class HeartbeatClient(Protocol):
    def heartbeat(self, claim: AgentTaskClaim, *, lease_seconds: int = 60) -> AgentTaskClaim:
        ...


@dataclass
class ExecutionControl:
    """Thread-safe, write-once stop state for one claimed execution."""

    _reason: str = ""
    _last_successful_heartbeat_at: float | None = field(default=None, init=False, repr=False)
    _stopped: Event = field(default_factory=Event, init=False, repr=False)
    _lock: Lock = field(default_factory=Lock, init=False, repr=False)

    @property
    def is_active(self) -> bool:
        return not self._stopped.is_set()

    @property
    def stop_reason(self) -> str:
        with self._lock:
            return self._reason

    @property
    def last_successful_heartbeat_at(self) -> float | None:
        with self._lock:
            return self._last_successful_heartbeat_at

    def record_successful_heartbeat(self, now: float) -> None:
        with self._lock:
            if not self._stopped.is_set():
                self._last_successful_heartbeat_at = now

    def stop(self, reason: str) -> bool:
        normalized = str(reason).strip().upper()
        if not normalized:
            raise ValueError("execution stop reason is required")
        with self._lock:
            if self._stopped.is_set():
                return False
            self._reason = normalized
            self._stopped.set()
            return True

    def require_active(self) -> None:
        if not self.is_active:
            raise ExecutionStopped(f"execution stopped: {self.stop_reason or 'UNKNOWN'}")


_CURRENT_EXECUTION_CONTROL: ContextVar[ExecutionControl | None] = ContextVar(
    "research_agent_execution_control",
    default=None,
)


@contextmanager
def execution_control_scope(control: ExecutionControl) -> Iterator[ExecutionControl]:
    """Bind one control to all cooperative calls in the current execution."""
    token = _CURRENT_EXECUTION_CONTROL.set(control)
    try:
        yield control
    finally:
        _CURRENT_EXECUTION_CONTROL.reset(token)


def require_execution_active() -> None:
    """Fail closed inside a managed execution; remain a no-op for legacy callers."""
    control = _CURRENT_EXECUTION_CONTROL.get()
    if control is not None:
        control.require_active()


def stop_current_execution(reason: str) -> None:
    """Apply an authoritative remote stop signal to the managed execution."""
    control = _CURRENT_EXECUTION_CONTROL.get()
    if control is None:
        raise ExecutionStopped(f"execution stopped: {str(reason).strip().upper() or 'UNKNOWN'}")
    control.stop(reason)
    control.require_active()


class LeaseKeeper:
    """One heartbeat state machine for an already claimed execution.

    `start()` performs the first heartbeat synchronously before any provider
    work can begin, then schedules bounded heartbeats in one owned thread.
    `tick()` remains clock-injected so the failure-budget state machine is
    deterministic in unit tests.
    """

    def __init__(
        self,
        client: HeartbeatClient,
        claim: AgentTaskClaim,
        control: ExecutionControl,
        *,
        lease_seconds: int,
        interval_seconds: float,
        failure_budget_seconds: float | None = None,
    ) -> None:
        if lease_seconds < 4:
            raise ValueError("lease_seconds must be at least four seconds")
        if interval_seconds <= 0 or interval_seconds >= lease_seconds / 3:
            raise ValueError("heartbeat interval must be positive and less than lease_seconds / 3")
        budget = failure_budget_seconds if failure_budget_seconds is not None else interval_seconds * 2
        if budget <= 0 or budget >= lease_seconds:
            raise ValueError("heartbeat failure budget must be positive and less than lease_seconds")
        self._client = client
        self._claim = claim
        self._control = control
        self.lease_seconds = lease_seconds
        self.interval_seconds = interval_seconds
        self.failure_budget_seconds = budget
        self._first_failure_at: float | None = None
        self._shutdown = Event()
        self._lifecycle_lock = Lock()
        self._thread: Thread | None = None
        self._started = False
        self._closed = False

    @property
    def is_running(self) -> bool:
        thread = self._thread
        return bool(thread and thread.is_alive())

    def start(self) -> None:
        """Heartbeat now, then start exactly one scheduler for this lease."""
        with self._lifecycle_lock:
            if self._started:
                raise RuntimeError("LeaseKeeper may only be started once")
            if self._closed:
                raise RuntimeError("LeaseKeeper is already closed")
            self._started = True

        if not self._tick_safely(monotonic()):
            self._control.require_active()

        thread = Thread(
            target=self._run,
            name=f"research-agent-lease-keeper-{self._claim.agent_task_id}",
            daemon=True,
        )
        self._thread = thread
        thread.start()

    def close(self) -> None:
        """Stop future heartbeats and wait a bounded time for the scheduler."""
        with self._lifecycle_lock:
            if self._closed:
                return
            self._closed = True
            self._shutdown.set()
            thread = self._thread
        if thread is not None:
            thread.join(timeout=max(1.0, self.interval_seconds * 2))

    def _run(self) -> None:
        while not self._shutdown.wait(self.interval_seconds):
            if not self._tick_safely(monotonic()):
                return

    def _tick_safely(self, now: float) -> bool:
        try:
            return self.tick(now=now)
        except Exception:
            self._control.stop("HEARTBEAT_FAILED")
            return False

    def tick(self, *, now: float) -> bool:
        """Send one heartbeat and return whether local work may continue."""
        if not self._control.is_active:
            return False
        try:
            updated = self._client.heartbeat(self._claim, lease_seconds=self.lease_seconds)
            if updated != self._claim:
                self._control.stop("STALE_LEASE")
                return False
        except AgentTaskNotClaimableError:
            self._control.stop("STALE_LEASE")
            return False
        except AgentTaskApiError as exc:
            if exc.status_code < 500 and exc.status_code != 429:
                self._control.stop("STALE_LEASE")
                return False
            return self._record_unavailable(now)
        except (TimeoutError, ConnectionError, OSError):
            return self._record_unavailable(now)

        self._first_failure_at = None
        self._claim = updated
        self._control.record_successful_heartbeat(now)
        return True

    def _record_unavailable(self, now: float) -> bool:
        if self._first_failure_at is None:
            self._first_failure_at = now
        if now - self._first_failure_at >= self.failure_budget_seconds:
            self._control.stop("HEARTBEAT_UNAVAILABLE")
            return False
        return True
