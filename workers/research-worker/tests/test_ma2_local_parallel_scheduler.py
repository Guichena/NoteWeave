from __future__ import annotations

from threading import Event, Lock
import time

from app.agent_contracts import AgentRole, CellTaskBundle, TaskBudget, TaskCellTarget
from app.models import ResearchStateCell


def _bundle(index: int) -> CellTaskBundle:
    entity_id = f"entity-{index}"
    column_key = "method"
    return CellTaskBundle(
        task_id=f"task-{index}",
        research_run_id="run-ma2",
        role=AgentRole.DEEP_CELL,
        entity_id=entity_id,
        entity_set_version=1,
        plan_revision=1,
        target_cells=[
            TaskCellTarget(
                cell_id=f"{entity_id}:{column_key}",
                entity_id=entity_id,
                column_key=column_key,
                expected_version=0,
            )
        ],
        budget=TaskBudget(),
        lease_epoch=1,
        fencing_token=1,
        idempotency_key=f"run-ma2:{index}",
    )


def _cells(bundles: list[CellTaskBundle]) -> dict[str, ResearchStateCell]:
    return {
        target.cell_id: ResearchStateCell(
            cell_id=target.cell_id,
            row_id=target.entity_id,
            entity_id=target.entity_id,
            column_key=target.column_key,
            candidate_value=f"{target.entity_id} value",
            evidence_refs=[f"evidence-{target.entity_id}"],
        )
        for bundle in bundles
        for target in bundle.target_cells
    }


def test_scheduler_should_bound_parallelism_and_collect_by_input_order() -> None:
    from app.local_parallel_scheduler import execute_local_parallel_bundles
    from app.sequential_candidate_executor import execute_sequential_bundle

    bundles = [_bundle(index) for index in range(3)]
    cells = _cells(bundles)
    lock = Lock()
    release = Event()
    in_flight = 0
    observed = 0

    def delayed_executor(bundle, task_cells):
        nonlocal in_flight, observed
        with lock:
            in_flight += 1
            observed = max(observed, in_flight)
            if observed >= 2:
                release.set()
        assert release.wait(timeout=2)
        # Force a completion order different from input order once all workers start.
        time.sleep(0.02 if bundle.task_id == "task-0" else 0.0)
        with lock:
            in_flight -= 1
        return execute_sequential_bundle(bundle, task_cells)

    summary = execute_local_parallel_bundles(
        bundles,
        cells,
        max_concurrency=2,
        execute_bundle=delayed_executor,
    )

    assert observed == 2
    assert summary.configured_max_concurrency == 2
    assert summary.effective_max_concurrency == 2
    assert summary.observed_max_in_flight == 2
    assert [result.task_id for result in summary.results] == [bundle.task_id for bundle in bundles]


def test_scheduler_should_pass_isolated_cell_snapshots_to_each_executor() -> None:
    from app.local_parallel_scheduler import execute_local_parallel_bundles
    from app.sequential_candidate_executor import execute_sequential_bundle

    bundles = [_bundle(0), _bundle(1)]
    cells = _cells(bundles)
    original_values = {cell_id: cell.candidate_value for cell_id, cell in cells.items()}
    seen_cell_object_ids: list[int] = []

    def mutating_executor(bundle, task_cells):
        target = bundle.target_cells[0]
        local_cell = task_cells[target.cell_id]
        seen_cell_object_ids.append(id(local_cell))
        local_cell.candidate_value = "local mutation"
        return execute_sequential_bundle(bundle, task_cells)

    summary = execute_local_parallel_bundles(
        bundles,
        cells,
        max_concurrency=2,
        execute_bundle=mutating_executor,
    )

    assert summary.execution_failure_count == 0
    assert len(set(seen_cell_object_ids)) == len(bundles)
    assert {cell_id: cell.candidate_value for cell_id, cell in cells.items()} == original_values


def test_scheduler_should_turn_one_executor_error_into_a_task_local_failed_result() -> None:
    from app.agent_contracts import AgentTaskStatus
    from app.local_parallel_scheduler import execute_local_parallel_bundles
    from app.sequential_candidate_executor import execute_sequential_bundle

    bundles = [_bundle(0), _bundle(1)]
    cells = _cells(bundles)

    def failing_executor(bundle, task_cells):
        if bundle.task_id == "task-0":
            raise RuntimeError("synthetic executor failure")
        return execute_sequential_bundle(bundle, task_cells)

    summary = execute_local_parallel_bundles(
        bundles,
        cells,
        max_concurrency=2,
        execute_bundle=failing_executor,
    )

    assert summary.execution_failure_count == 1
    assert summary.results[0].status == AgentTaskStatus.FAILED
    assert summary.results[0].termination_reason.startswith("EXECUTOR_ERROR:RuntimeError")
    assert summary.results[1].status == AgentTaskStatus.SUBMITTED


def test_scheduler_should_emit_cancelled_results_without_starting_executors() -> None:
    from app.agent_contracts import AgentTaskStatus
    from app.local_parallel_scheduler import execute_local_parallel_bundles

    bundles = [_bundle(0), _bundle(1)]
    calls = 0

    def should_not_run(_bundle, _task_cells):
        nonlocal calls
        calls += 1
        raise AssertionError("cancelled task must not execute")

    summary = execute_local_parallel_bundles(
        bundles,
        _cells(bundles),
        max_concurrency=2,
        execute_bundle=should_not_run,
        cancellation_checker=lambda: True,
    )

    assert calls == 0
    assert summary.execution_cancelled_count == len(bundles)
    assert all(result.status == AgentTaskStatus.CANCELLED for result in summary.results)
    assert all(result.termination_reason == "CANCELLATION_REQUESTED" for result in summary.results)
