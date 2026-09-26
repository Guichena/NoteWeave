"""MA4H process-wide drain coordination for the distributed agent consumer."""

from __future__ import annotations

from collections import deque
from contextlib import contextmanager
from threading import Event, Lock, Thread
from time import monotonic
from typing import Callable, Iterator

from app.execution_control import ExecutionControl


class DrainRequested(RuntimeError):
    """Raised when code attempts to register work after drain begins."""


class DrainGraceExpired(RuntimeError):
    """Raised when an active record must remain unacknowledged after grace."""


class DrainCoordinator:
    """One process-wide, irreversible drain state with an active registry."""

    def __init__(self, *, clock: Callable[[], float] = monotonic,
                 start_watchdog: bool = True) -> None:
        self._clock = clock
        self._start_watchdog = start_watchdog
        self._draining = Event()
        self._lock = Lock()
        self._deadline: float | None = None
        self._active: dict[str, ExecutionControl] = {}

    @property
    def is_draining(self) -> bool:
        return self._draining.is_set()

    @property
    def deadline(self) -> float | None:
        with self._lock:
            return self._deadline

    @property
    def active_count(self) -> int:
        with self._lock:
            return len(self._active)

    def register(self, execution_key: str, control: ExecutionControl) -> None:
        key = str(execution_key).strip()
        if not key:
            raise ValueError("drain execution key is required")
        with self._lock:
            if self._draining.is_set():
                raise DrainRequested("research agent consumer is draining")
            if key in self._active:
                raise RuntimeError("execution is already registered for drain control")
            self._active[key] = control

    def unregister(self, execution_key: str, control: ExecutionControl) -> None:
        with self._lock:
            if self._active.get(execution_key) is control:
                del self._active[execution_key]

    @contextmanager
    def execution(self, execution_key: str, control: ExecutionControl) -> Iterator[ExecutionControl]:
        self.register(execution_key, control)
        try:
            yield control
        finally:
            self.unregister(execution_key, control)

    def request_drain(self, *, grace_seconds: float) -> bool:
        if grace_seconds <= 0:
            raise ValueError("drain grace_seconds must be positive")
        with self._lock:
            if self._draining.is_set():
                return False
            self._deadline = self._clock() + grace_seconds
            self._draining.set()
            deadline = self._deadline
        if self._start_watchdog:
            Thread(
                target=self._watch_deadline,
                args=(deadline,),
                name="research-agent-drain-watchdog",
                daemon=True,
            ).start()
        return True

    def expire_grace(self, *, now: float | None = None) -> int:
        observed = self._clock() if now is None else now
        with self._lock:
            if not self._draining.is_set() or self._deadline is None or observed < self._deadline:
                return 0
            controls = list(self._active.values())
        stopped = 0
        for control in controls:
            if control.stop("DRAIN_GRACE_EXPIRED"):
                stopped += 1
        return stopped

    def _watch_deadline(self, deadline: float) -> None:
        remaining = max(0.0, deadline - self._clock())
        if remaining:
            Event().wait(remaining)
        # Timeout waits may return a few microseconds before the injected
        # clock observes the deadline.  Use the frozen deadline so the sole
        # watchdog cannot exit without ever expiring active work.
        self.expire_grace(now=deadline)


class DrainAwareKafkaMessages:
    """Bounded-poll iterator that never yields a newly polled record after drain."""

    def __init__(self, consumer: object, coordinator: DrainCoordinator,
                 *, poll_timeout_ms: int = 500) -> None:
        if poll_timeout_ms < 1 or poll_timeout_ms > 5000:
            raise ValueError("poll_timeout_ms must be between 1 and 5000")
        self.consumer = consumer
        self.coordinator = coordinator
        self.poll_timeout_ms = poll_timeout_ms
        self._pending: deque[object] = deque()

    def __iter__(self) -> "DrainAwareKafkaMessages":
        return self

    def __next__(self) -> object:
        while True:
            if self.coordinator.is_draining:
                raise StopIteration
            if self._pending:
                return self._pending.popleft()
            poll = getattr(self.consumer, "poll", None)
            if not callable(poll):
                raise RuntimeError("Kafka consumer does not support bounded poll")
            batches = poll(timeout_ms=self.poll_timeout_ms)
            if self.coordinator.is_draining:
                raise StopIteration
            if not isinstance(batches, dict):
                raise RuntimeError("Kafka poll must return a partition-to-records mapping")
            for records in batches.values():
                self._pending.extend(records)

    def commit(self, offsets=None) -> None:
        commit = getattr(self.consumer, "commit", None)
        if callable(commit):
            if offsets is None:
                commit()
            else:
                commit(offsets=offsets)

    def close(self) -> None:
        close = getattr(self.consumer, "close", None)
        if callable(close):
            close(autocommit=False)
