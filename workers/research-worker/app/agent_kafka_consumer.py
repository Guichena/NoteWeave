"""MA4G agent-command consumer with atomic-completion ACK boundaries."""

from __future__ import annotations

import json
import os
import signal
import sys
import tempfile
import time
from hashlib import sha256
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable, Protocol

from pydantic import ValidationError

from app.agent_command_contract import ResearchAgentCommand
from app.agent_task_client import (
    AgentCompletionReceipt,
    AgentTaskApiError,
    AgentTaskClaim,
    AgentTaskNotClaimableError,
    AgentTaskProtocolError,
    JavaResearchAgentTaskClient,
)
from app.config import load_settings
from app.execution_control import (
    ExecutionControl,
    ExecutionStopped,
    LeaseKeeper,
    execution_control_scope,
    require_execution_active,
)
from app.drain_control import DrainAwareKafkaMessages, DrainCoordinator, DrainGraceExpired, DrainRequested
from app.task_snapshot_contract import TaskSnapshotTrustError, require_trusted_claim_snapshot
from app.research_agent_completion_contract import (
    ResearchAgentCompletionEnvelope,
    serialize_completion_envelope,
)
from app.kafka_primitives import (
    DeadLetterPublishError,
    DeadLetterSink,
    InMemoryDeadLetterSink,
    KafkaConsumeSummary,
    KafkaDeadLetterSink,
    commit_if_supported as _commit_if_supported,
    kafka_security_options,
    message_value as _message_value,
)
from app.trace_security import sanitize_error_message, sanitize_trace_payload


class AgentTaskClient(Protocol):
    def claim(self, task_id: str, *, lease_seconds: int = 60) -> AgentTaskClaim:
        ...

    def heartbeat(self, claim: AgentTaskClaim, *, lease_seconds: int = 60) -> AgentTaskClaim:
        ...

    def complete(self, completion: ResearchAgentCompletionEnvelope) -> AgentCompletionReceipt:
        ...


class AgentDeliveryFailureReporter(Protocol):
    def report_delivery_failure(self, command: ResearchAgentCommand, failure: dict[str, object]) -> None:
        ...


AgentExecutor = Callable[[ResearchAgentCommand, AgentTaskClaim], ResearchAgentCompletionEnvelope]


class AgentCommandIdentityError(RuntimeError):
    """An executor returned a completion identity other than the authoritative claim."""


@dataclass(frozen=True)
class AgentCommandExecutionResponse:
    agent_task_id: str
    execution_id: str
    status: str
    completion_id: str | None = None
    completion_digest: str | None = None


@dataclass
class _ActiveAgentExecution:
    command: ResearchAgentCommand
    claim: AgentTaskClaim
    snapshot_digest: str
    control: ExecutionControl
    keeper: LeaseKeeper
    result: ResearchAgentCompletionEnvelope | None = None


def handle_research_agent_command(
    payload_json: str,
    client: AgentTaskClient,
    executor: AgentExecutor,
    *,
    worker_instance_id: str,
    lease_seconds: int = 60,
    heartbeat_interval_seconds: float | None = None,
    heartbeat_failure_budget_seconds: float | None = None,
) -> AgentCommandExecutionResponse:
    payload = json.loads(payload_json)
    command = ResearchAgentCommand.model_validate(payload)
    execution = _start_agent_execution(
        command,
        client,
        worker_instance_id,
        lease_seconds,
        heartbeat_interval_seconds,
        heartbeat_failure_budget_seconds,
    )
    try:
        with execution_control_scope(execution.control):
            _execute_agent_execution(execution, executor, worker_instance_id)
            return _finalize_agent_execution(execution, client)
    finally:
        execution.keeper.close()


def _start_agent_execution(
    command: ResearchAgentCommand,
    client: AgentTaskClient,
    worker_instance_id: str,
    lease_seconds: int,
    heartbeat_interval_seconds: float | None,
    heartbeat_failure_budget_seconds: float | None,
    control: ExecutionControl | None = None,
) -> _ActiveAgentExecution:
    claim = client.claim(command.agent_task_id, lease_seconds=lease_seconds)
    snapshot = require_trusted_claim_snapshot(claim)
    if command.research_run_id != snapshot.research_run_id:
        raise AgentCommandIdentityError("agent command run does not match authoritative claim snapshot")
    control = control or ExecutionControl()
    interval = heartbeat_interval_seconds if heartbeat_interval_seconds is not None else lease_seconds / 4
    keeper = LeaseKeeper(
        client,
        claim,
        control,
        lease_seconds=lease_seconds,
        interval_seconds=interval,
        failure_budget_seconds=heartbeat_failure_budget_seconds,
    )
    try:
        keeper.start()
    except Exception:
        keeper.close()
        raise
    return _ActiveAgentExecution(command, claim, snapshot.snapshot_digest, control, keeper)


def _execute_agent_execution(execution: _ActiveAgentExecution, executor: AgentExecutor,
                             worker_instance_id: str) -> None:
    require_execution_active()
    result = executor(execution.command, execution.claim)
    require_execution_active()
    if not isinstance(result, ResearchAgentCompletionEnvelope):
        raise AgentCommandIdentityError("agent executor must return an atomic completion envelope")
    _require_completion_identity(
        result,
        execution.claim,
        execution.snapshot_digest,
        worker_instance_id,
    )
    execution.result = result


def _finalize_agent_execution(execution: _ActiveAgentExecution,
                              client: AgentTaskClient) -> AgentCommandExecutionResponse:
    require_execution_active()
    if execution.result is None:
        raise RuntimeError("agent execution has no immutable completion to finalize")
    receipt = client.complete(execution.result)
    # A drain deadline which fired while the request was in flight must not
    # acknowledge the Kafka record; a later owner may replay the immutable
    # completion receipt safely.
    require_execution_active()
    return AgentCommandExecutionResponse(
        execution.command.agent_task_id,
        receipt.execution_id,
        "COMPLETED",
        receipt.completion_id,
        receipt.completion_digest,
    )


def _require_completion_identity(completion: ResearchAgentCompletionEnvelope, claim: AgentTaskClaim,
                                 snapshot_digest: str, worker_instance_id: str) -> None:
    # Revalidation catches unsafe model_copy(update=...) use and makes the
    # envelope digest part of the consumer's identity guard, not just the HTTP
    # client's guard.
    try:
        serialize_completion_envelope(completion)
    except Exception as exc:
        raise AgentCommandIdentityError("agent executor completion does not match authoritative claim identity") from exc
    if (
        completion.task_id != claim.agent_task_id
        or completion.worker_instance_id != worker_instance_id
        or completion.lease_epoch != claim.lease_epoch
        or completion.fencing_token != claim.fencing_token
        or completion.task_snapshot_digest != snapshot_digest
        or completion.task_snapshot_digest != claim.snapshot_digest
    ):
        raise AgentCommandIdentityError("agent executor completion does not match authoritative claim identity")


def consume_research_agent_commands(
    messages: Iterable[object],
    client: AgentTaskClient,
    executor: AgentExecutor,
    *,
    worker_instance_id: str,
    max_messages: int | None = None,
    max_attempts: int = 3,
    dead_letter_sink: DeadLetterSink | None = None,
    failure_reporter: AgentDeliveryFailureReporter | None = None,
    backoff_base_seconds: float = 0.0,
    lease_seconds: int = 60,
    heartbeat_interval_seconds: float | None = None,
    heartbeat_failure_budget_seconds: float | None = None,
    drain_coordinator: DrainCoordinator | None = None,
) -> KafkaConsumeSummary:
    """Consume a dedicated agent-command topic with explicit ACK/DLQ boundaries.

    Once an executor has produced a result, retries retain that exact immutable
    object and only replay the finalize endpoint.  A response-loss retry must
    never reclaim the task or rerun provider/tool work.
    """
    sink = dead_letter_sink or InMemoryDeadLetterSink()
    consumed = completed = failed_attempts = skipped = failed_messages = dead_letters = 0
    attempts_limit = max(1, max_attempts)
    iterator = iter(messages)
    while True:
        if max_messages is not None and consumed >= max_messages:
            break
        if drain_coordinator is not None and drain_coordinator.is_draining:
            break
        try:
            message = next(iterator)
        except StopIteration:
            break
        consumed += 1
        try:
            payload_json = _message_value(message)
            command = _validate_command(payload_json)
        except Exception as exc:
            failed_attempts += 1
            failed_messages += 1
            _dead_letter_or_stop(
                sink,
                _dlq_record(getattr(message, "value", message), exc, attempt_count=1, retryable=False),
                exc,
            )
            dead_letters += 1
            _commit_if_supported(messages)
            continue

        last_error: Exception | None = None
        did_complete = False
        did_skip = False
        attempt_count = 0
        execution: _ActiveAgentExecution | None = None
        control = ExecutionControl()
        registered = False
        if drain_coordinator is not None:
            try:
                drain_coordinator.register(command.agent_task_id, control)
                registered = True
            except DrainRequested:
                break
        try:
            for attempt_index in range(attempts_limit):
                attempt_count += 1
                try:
                    if execution is None:
                        execution = _start_agent_execution(
                            command,
                            client,
                            worker_instance_id,
                            lease_seconds,
                            heartbeat_interval_seconds,
                            heartbeat_failure_budget_seconds,
                            control,
                        )
                    with execution_control_scope(execution.control):
                        if execution.result is None:
                            _execute_agent_execution(execution, executor, worker_instance_id)
                        _finalize_agent_execution(execution, client)
                    did_complete = True
                    break
                except AgentTaskNotClaimableError:
                    did_skip = True
                    break
                except ExecutionStopped:
                    # Lease loss is an execution-control event, not poison data.
                    # Leave the Kafka offset uncommitted for lease/reaper recovery.
                    if execution is not None and execution.control.stop_reason == "DRAIN_GRACE_EXPIRED":
                        raise DrainGraceExpired("research agent drain grace expired")
                    raise
                except Exception as exc:
                    last_error = exc
                    failed_attempts += 1
                    if not _is_retryable_agent_error(exc):
                        break
                    if attempt_index + 1 < attempts_limit and backoff_base_seconds > 0:
                        time.sleep(min(backoff_base_seconds * (2 ** attempt_index), 5.0))
        finally:
            if execution is not None:
                execution.keeper.close()
            if registered and drain_coordinator is not None:
                drain_coordinator.unregister(command.agent_task_id, control)

        if did_complete:
            completed += 1
            _commit_if_supported(messages)
            continue
        if did_skip:
            skipped += 1
            _commit_if_supported(messages)
            continue

        failed_messages += 1
        record = _dlq_record(payload_json, last_error or RuntimeError("unknown agent consumer failure"), attempt_count, _is_retryable_agent_error(last_error))
        _dead_letter_or_stop(
            sink,
            record,
            last_error,
        )
        _report_failure_or_stop(failure_reporter, command, record, last_error)
        dead_letters += 1
        _commit_if_supported(messages)

    return KafkaConsumeSummary(
        consumed_count=consumed,
        completed_count=completed,
        failed_count=failed_attempts,
        skipped_count=skipped,
        failed_message_count=failed_messages,
        failed_attempt_count=failed_attempts,
        dead_letter_count=dead_letters,
    )


def create_research_agent_kafka_consumer() -> object:
    settings = load_settings()
    try:
        from kafka import KafkaConsumer
    except ImportError as exc:
        raise RuntimeError("kafka-python is required for Kafka consumption") from exc
    return KafkaConsumer(
        settings.kafka_research_agent_topic,
        bootstrap_servers=settings.kafka_bootstrap_servers,
        group_id=settings.kafka_research_agent_group_id,
        enable_auto_commit=False,
        auto_offset_reset="earliest",
        **kafka_security_options(settings),
    )


def create_research_agent_kafka_dead_letter_sink() -> KafkaDeadLetterSink:
    settings = load_settings()
    try:
        from kafka import KafkaProducer
    except ImportError as exc:
        raise RuntimeError("kafka-python is required for Kafka dead-letter publishing") from exc
    producer = KafkaProducer(
        bootstrap_servers=settings.kafka_bootstrap_servers,
        acks="all",
        retries=max(1, settings.kafka_consume_max_attempts),
        value_serializer=lambda value: json.dumps(value, ensure_ascii=False).encode("utf-8"),
        **kafka_security_options(settings),
    )
    return KafkaDeadLetterSink(producer, settings.kafka_research_agent_dlq_topic)


def run_research_agent_kafka_consumer_forever(executor: AgentExecutor | None = None) -> None:
    settings = load_settings()
    if not settings.research_agent_consumer_enabled:
        return
    worker_instance_id = settings.research_agent_worker_instance_id
    if not worker_instance_id:
        raise RuntimeError("Research agent Kafka consumer requires NOTEWEAVE_RESEARCH_AGENT_WORKER_INSTANCE_ID")
    if executor is None and not settings.research_agent_deep_cell_executor_enabled:
        raise RuntimeError("Research agent DEEP_CELL executor is disabled by configuration")
    heartbeat_interval_seconds = getattr(settings, "research_agent_heartbeat_interval_seconds", None)
    if heartbeat_interval_seconds is None:
        heartbeat_interval_seconds = settings.research_agent_lease_seconds / 4
    heartbeat_failure_budget_seconds = getattr(
        settings, "research_agent_heartbeat_failure_budget_seconds", None
    )
    if heartbeat_failure_budget_seconds is None:
        heartbeat_failure_budget_seconds = heartbeat_interval_seconds * 2
    heartbeat_request_timeout_seconds = getattr(
        settings, "research_agent_heartbeat_request_timeout_seconds", None
    )
    if heartbeat_request_timeout_seconds is None:
        heartbeat_request_timeout_seconds = min(5.0, heartbeat_interval_seconds / 2)
    client = JavaResearchAgentTaskClient(
        settings.java_base_url,
        settings.internal_auth_token,
        worker_instance_id,
        completion_max_attempts=settings.research_agent_completion_max_attempts,
        heartbeat_request_timeout_seconds=heartbeat_request_timeout_seconds,
    )
    if executor is None:
        from app.deep_cell_executor import DeepCellExecutor
        if settings.research_agent_fake_provider_enabled:
            from app.deterministic_fake_toolchain import DeterministicFakeToolchain
            executor = DeepCellExecutor(client, worker_instance_id, toolchain=DeterministicFakeToolchain(), enable_llm=False)
        else:
            executor = DeepCellExecutor(client, worker_instance_id)
    coordinator = DrainCoordinator()
    consumer = create_research_agent_kafka_consumer()
    messages = DrainAwareKafkaMessages(
        consumer,
        coordinator,
        poll_timeout_ms=getattr(settings, "research_agent_drain_poll_timeout_ms", 500),
    )
    restore_handlers = _install_drain_signal_handlers(
        coordinator,
        getattr(settings, "research_agent_drain_grace_seconds", 30),
    )
    health_file = _health_file(settings)
    _write_health_marker(health_file)
    try:
        consume_research_agent_commands(
            messages,
            client,
            executor,
            worker_instance_id=worker_instance_id,
            max_attempts=settings.kafka_consume_max_attempts,
            dead_letter_sink=create_research_agent_kafka_dead_letter_sink(),
            failure_reporter=client,
            lease_seconds=settings.research_agent_lease_seconds,
            heartbeat_interval_seconds=heartbeat_interval_seconds,
            heartbeat_failure_budget_seconds=heartbeat_failure_budget_seconds,
            drain_coordinator=coordinator,
        )
    finally:
        _remove_health_marker(health_file)
        restore_handlers()
        messages.close()


def _health_file(settings: object) -> Path:
    configured = os.environ.get("NOTEWEAVE_RESEARCH_AGENT_HEALTH_FILE", "").strip()
    return Path(configured or (Path(tempfile.gettempdir()) / "noteweave-research-agent-consumer.ready"))


def _write_health_marker(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"pid": os.getpid(), "started_at": time.time()}), encoding="utf-8")


def _remove_health_marker(path: Path) -> None:
    try:
        path.unlink()
    except FileNotFoundError:
        pass


def research_agent_healthcheck() -> int:
    path = Path(os.environ.get("NOTEWEAVE_RESEARCH_AGENT_HEALTH_FILE", "").strip()
                or (Path(tempfile.gettempdir()) / "noteweave-research-agent-consumer.ready"))
    try:
        marker = json.loads(path.read_text(encoding="utf-8"))
        pid = int(marker["pid"])
        os.kill(pid, 0)
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError):
        return 1
    return 0


def _install_drain_signal_handlers(coordinator: DrainCoordinator,
                                   grace_seconds: int) -> Callable[[], None]:
    previous: dict[int, object] = {}

    def request_drain(_signum: int, _frame: object) -> None:
        coordinator.request_drain(grace_seconds=grace_seconds)

    for signum in (signal.SIGTERM, signal.SIGINT):
        previous[signum] = signal.signal(signum, request_drain)

    def restore() -> None:
        for signum, handler in previous.items():
            signal.signal(signum, handler)

    return restore


def _validate_command(payload_json: str) -> ResearchAgentCommand:
    return ResearchAgentCommand.model_validate(json.loads(payload_json))


def _is_retryable_agent_error(exc: Exception | None) -> bool:
    if exc is None or isinstance(exc, (ValidationError, json.JSONDecodeError, AgentCommandIdentityError,
                                       AgentTaskProtocolError,
                                       AgentTaskNotClaimableError, ExecutionStopped, TaskSnapshotTrustError)):
        return False
    if isinstance(exc, AgentTaskApiError):
        return exc.status_code == 429 or exc.status_code >= 500
    return isinstance(exc, (TimeoutError, ConnectionError, OSError))


def _dlq_record(original_message: object, exc: Exception, attempt_count: int, retryable: bool) -> dict[str, object]:
    sanitized = sanitize_error_message(exc)
    return {
        "original_message": _sanitize_dead_letter_message(original_message),
        "error_type": type(exc).__name__,
        "error_message": sanitized,
        "trace_digest": "sha256:" + sha256(sanitized.encode("utf-8")).hexdigest(),
        "attempt_count": attempt_count,
        "retryable": retryable,
    }


def _sanitize_dead_letter_message(original_message: object) -> str:
    candidate: object = original_message
    if isinstance(candidate, bytes):
        candidate = candidate.decode("utf-8", errors="replace")
    if isinstance(candidate, str):
        try:
            candidate = json.loads(candidate)
        except (json.JSONDecodeError, TypeError):
            return sanitize_error_message(repr(candidate), max_chars=4000)
    if isinstance(candidate, dict):
        return json.dumps(sanitize_trace_payload(candidate), sort_keys=True, separators=(",", ":"))[:4000]
    return sanitize_error_message(repr(candidate), max_chars=4000)


def _dead_letter_or_stop(sink: DeadLetterSink, record: dict[str, object], cause: Exception | None) -> None:
    if not sink.publish(record):
        raise DeadLetterPublishError("Kafka agent-command dead-letter publish failed; offset not committed") from cause


def _report_failure_or_stop(reporter: AgentDeliveryFailureReporter | None, command: ResearchAgentCommand,
                            record: dict[str, object], cause: Exception | None) -> None:
    if reporter is None:
        return
    try:
        reporter.report_delivery_failure(command, record)
    except Exception as exc:
        raise DeadLetterPublishError("Agent delivery failure projection failed; offset not committed") from (cause or exc)


def main(executor: AgentExecutor | None = None) -> None:
    if "--healthcheck" in sys.argv[1:]:
        raise SystemExit(research_agent_healthcheck())
    try:
        if executor is None:
            run_research_agent_kafka_consumer_forever()
        else:
            run_research_agent_kafka_consumer_forever(executor)
    except DrainGraceExpired as exc:
        raise SystemExit(75) from exc


if __name__ == "__main__":
    main()
