"""MA2 bounded in-process execution for immutable cell-task bundles.

The scheduler deliberately owns no canonical ledger state.  Executors receive
isolated snapshots and return immutable ``AgentExecutionResult`` objects; the
coordinator remains responsible for verifier-gated merging in a stable order.
"""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
import hashlib
from threading import Lock
from typing import Callable

from app.agent_contracts import AgentExecutionResult, AgentTaskStatus, CellTaskBundle
from app.models import ResearchStateCell
from app.sequential_candidate_executor import execute_sequential_bundle


BundleExecutor = Callable[[CellTaskBundle, dict[str, ResearchStateCell]], AgentExecutionResult]
CancellationChecker = Callable[[], bool]


@dataclass(frozen=True)
class LocalParallelExecutionSummary:
    results: list[AgentExecutionResult]
    configured_max_concurrency: int
    effective_max_concurrency: int
    observed_max_in_flight: int
    execution_failure_count: int
    execution_cancelled_count: int


class _InFlightTracker:
    def __init__(self) -> None:
        self._lock = Lock()
        self._in_flight = 0
        self.max_in_flight = 0

    def start(self) -> None:
        with self._lock:
            self._in_flight += 1
            self.max_in_flight = max(self.max_in_flight, self._in_flight)

    def finish(self) -> None:
        with self._lock:
            self._in_flight -= 1


def execute_local_parallel_bundles(
    bundles: list[CellTaskBundle],
    cells_by_id: dict[str, ResearchStateCell],
    *,
    max_concurrency: int,
    execute_bundle: BundleExecutor = execute_sequential_bundle,
    cancellation_checker: CancellationChecker | None = None,
) -> LocalParallelExecutionSummary:
    """Execute independent bundles with bounded concurrency and stable output order.

    ``cancellation_checker`` intentionally has a boolean contract here.  The
    existing runtime's exception-based cancellation boundary remains outside
    the scheduler until task-level cancellation is made durable in MA3/MA4.
    """
    configured = max(1, int(max_concurrency))
    effective = min(configured, len(bundles))
    if not bundles:
        return LocalParallelExecutionSummary([], configured, 0, 0, 0, 0)

    results: list[AgentExecutionResult | None] = [None] * len(bundles)
    tracker = _InFlightTracker()
    if effective == 1:
        for index, bundle in enumerate(bundles):
            if _is_cancellation_requested(cancellation_checker):
                results[index] = _cancelled_result(bundle)
                continue
            results[index] = _run_one(
                bundle.model_copy(deep=True),
                _isolated_cell_snapshot(bundle, cells_by_id),
                execute_bundle,
                cancellation_checker,
                tracker,
            )
        ordered_results = [result for result in results if result is not None]
        return LocalParallelExecutionSummary(
            results=ordered_results,
            configured_max_concurrency=configured,
            effective_max_concurrency=effective,
            observed_max_in_flight=tracker.max_in_flight,
            execution_failure_count=sum(result.status == AgentTaskStatus.FAILED for result in ordered_results),
            execution_cancelled_count=sum(result.status == AgentTaskStatus.CANCELLED for result in ordered_results),
        )

    futures: dict[object, int] = {}

    with ThreadPoolExecutor(max_workers=effective, thread_name_prefix="research-cell") as executor:
        for index, bundle in enumerate(bundles):
            if _is_cancellation_requested(cancellation_checker):
                results[index] = _cancelled_result(bundle)
                continue
            snapshot = _isolated_cell_snapshot(bundle, cells_by_id)
            future = executor.submit(
                _run_one,
                bundle.model_copy(deep=True),
                snapshot,
                execute_bundle,
                cancellation_checker,
                tracker,
            )
            futures[future] = index
        for future in as_completed(futures):
            results[futures[future]] = future.result()

    ordered_results = [result for result in results if result is not None]
    return LocalParallelExecutionSummary(
        results=ordered_results,
        configured_max_concurrency=configured,
        effective_max_concurrency=effective,
        observed_max_in_flight=tracker.max_in_flight,
        execution_failure_count=sum(result.status == AgentTaskStatus.FAILED for result in ordered_results),
        execution_cancelled_count=sum(result.status == AgentTaskStatus.CANCELLED for result in ordered_results),
    )


def _run_one(
    bundle: CellTaskBundle,
    task_cells: dict[str, ResearchStateCell],
    execute_bundle: BundleExecutor,
    cancellation_checker: CancellationChecker | None,
    tracker: _InFlightTracker,
) -> AgentExecutionResult:
    if _is_cancellation_requested(cancellation_checker):
        return _cancelled_result(bundle)
    tracker.start()
    try:
        return execute_bundle(bundle, task_cells)
    except Exception as error:
        return _failed_result(bundle, error)
    finally:
        tracker.finish()


def _isolated_cell_snapshot(
    bundle: CellTaskBundle,
    cells_by_id: dict[str, ResearchStateCell],
) -> dict[str, ResearchStateCell]:
    return {
        target.cell_id: cell.model_copy(deep=True)
        for target in bundle.target_cells
        if (cell := cells_by_id.get(target.cell_id)) is not None
    }


def _is_cancellation_requested(checker: CancellationChecker | None) -> bool:
    return bool(checker()) if checker is not None else False


def _cancelled_result(bundle: CellTaskBundle) -> AgentExecutionResult:
    return AgentExecutionResult(
        execution_id=f"execution-cancelled-{_stable_key(bundle.task_id, str(bundle.lease_epoch), str(bundle.fencing_token))}",
        task_id=bundle.task_id,
        lease_epoch=bundle.lease_epoch,
        fencing_token=bundle.fencing_token,
        status=AgentTaskStatus.CANCELLED,
        termination_reason="CANCELLATION_REQUESTED",
    )


def _failed_result(bundle: CellTaskBundle, error: Exception) -> AgentExecutionResult:
    return AgentExecutionResult(
        execution_id=f"execution-failed-{_stable_key(bundle.task_id, str(bundle.lease_epoch), str(bundle.fencing_token))}",
        task_id=bundle.task_id,
        lease_epoch=bundle.lease_epoch,
        fencing_token=bundle.fencing_token,
        status=AgentTaskStatus.FAILED,
        termination_reason=f"EXECUTOR_ERROR:{type(error).__name__}",
    )


def _stable_key(*parts: str) -> str:
    return hashlib.sha256("\x1f".join(parts).encode("utf-8")).hexdigest()[:20]
