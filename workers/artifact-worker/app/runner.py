from __future__ import annotations

from app.acquisition_runtime import get_acquisition_result_payload, register_acquisition_runtime
from app.artifact_repository import commit_artifact_result, reserve_next_artifact_version
from app.artifact_skill_catalog import CATALOG_DIGEST
import hashlib
from app.capability_approval_queue import create_capability_request
from app.capability_resolver import resolve_capability_bindings, resolve_capability_providers
from app.capability_wait_queue import enqueue_waiting_task
from app.compiler import build_execution_plan
from app.composer import render_markdown, resolve_artifact_title
from app.content_runtime import (
    _is_bilibili_pdf_async_provider,
    build_acquisition_receipt,
    build_canonical_content_objects,
    build_context_pack,
)
from app.models import (
    AcquisitionReceipt,
    ArtifactCommitReceipt,
    ArtifactCapabilityResolution,
    CanonicalContentObject,
    ArtifactExecutionPlan,
    ArtifactJobSnapshot,
    ArtifactLifecycleStepTrace,
    ArtifactLifecycleTrace,
    ArtifactProgressEvent,
    ArtifactTaskInput,
    ArtifactTaskResult,
    ArtifactVersionSnapshot,
    ApprovalRuntimeTrace,
    CapabilityUnionRuntimeTrace,
    ContextPack,
    EvidenceCoverageReport,
    MemoryPromotionPreview,
    RetrievalFeedback,
    WritebackPreview,
)
from app.skill_graph import execute_skill_graph
from app.error_sanitizer import sanitize_error_message
from app.custom_mcp_executor import submit_custom_mcp_acquisition_operation
from app.generation_runtime import ArtifactConfigurationRequiredError, generate_artifact_sections
from app.export_runtime import export_artifact_if_required
from app.candidate_file_manifest import build_required_files
from app.llm_client import build_default_llm_client
from app.verifier import build_output_contract_trace, verify_artifact_output
from app.writeback_runtime import register_writeback_request


PHASE_SEQUENCE = [
    ("RESOLVING", 10, "artifact action resolved"),
    ("ACQUIRING", 30, "source bundle compiled"),
    ("COMPOSING", 60, "section drafts generated"),
    ("VERIFYING", 85, "schema gate and local repair completed"),
    ("EXPORTING", 100, "artifact draft exported"),
]


def _require_published_catalog(task_input: ArtifactTaskInput) -> None:
    if task_input.catalog_digest and task_input.catalog_digest != CATALOG_DIGEST:
        raise ValueError("artifact Skill catalog digest does not match the frozen Run")


def run_artifact_task(task_input: ArtifactTaskInput) -> tuple[list[ArtifactProgressEvent], ArtifactTaskResult]:
    _require_published_catalog(task_input)
    return _run_artifact_task(task_input)


def resume_artifact_task(
    task_input: ArtifactTaskInput,
    resume_checkpoint: dict[str, object],
) -> tuple[list[ArtifactProgressEvent], ArtifactTaskResult]:
    _require_published_catalog(task_input)
    try:
        schema_version = int(resume_checkpoint.get("schema_version", 0))
    except (TypeError, ValueError) as exc:
        raise ValueError("artifact resume checkpoint has an invalid schema_version") from exc
    if schema_version < 2:
        raise ValueError(
            "artifact resume checkpoint schema v2 is required; legacy full-task restart is disabled"
        )
    checkpoint_stage = str(resume_checkpoint.get("stage", "")).strip().upper()
    if checkpoint_stage != "CAPABILITY_GATE":
        raise ValueError(f"unsupported artifact resume checkpoint stage: {checkpoint_stage}")
    raw_plan = resume_checkpoint.get("execution_plan")
    if not isinstance(raw_plan, dict):
        raise ValueError("artifact resume checkpoint is missing execution_plan")
    validated_plan = ArtifactExecutionPlan.model_validate(raw_plan)
    raw_content_objects = resume_checkpoint.get("canonical_content_objects")
    raw_context_pack = resume_checkpoint.get("context_pack")
    if not isinstance(raw_content_objects, list) or not isinstance(raw_context_pack, dict):
        raise ValueError("artifact resume checkpoint is missing staged content state")
    raw_capability_resolution = resume_checkpoint.get("capability_resolution")
    raw_acquisition_receipt = resume_checkpoint.get("acquisition_receipt")
    if not isinstance(raw_capability_resolution, dict) or not isinstance(raw_acquisition_receipt, dict):
        raise ValueError("artifact resume checkpoint is missing capability gate state")
    ArtifactCapabilityResolution.model_validate(raw_capability_resolution)
    AcquisitionReceipt.model_validate(raw_acquisition_receipt)
    return _run_artifact_task(
        task_input,
        compiled_plan=validated_plan,
        checkpoint_content_objects=[
            CanonicalContentObject.model_validate(item) for item in raw_content_objects
        ],
        checkpoint_context_pack=ContextPack.model_validate(raw_context_pack),
        resume_stage=checkpoint_stage,
    )


def _run_artifact_task(
    task_input: ArtifactTaskInput,
    *,
    compiled_plan: ArtifactExecutionPlan | None = None,
    checkpoint_content_objects: list[CanonicalContentObject] | None = None,
    checkpoint_context_pack: ContextPack | None = None,
    resume_stage: str = "",
) -> tuple[list[ArtifactProgressEvent], ArtifactTaskResult]:
    llm_client = build_default_llm_client()
    has_direct_input = any(str(value).strip() for value in task_input.input_payload.inputs.values())
    if llm_client is None and not task_input.source_scope and not has_direct_input:
        raise ArtifactConfigurationRequiredError(
            "Artifact LLM is not configured and the task has no source content to extract"
        )
    plan = compiled_plan or build_execution_plan(task_input)
    canonical_content_objects = (
        list(checkpoint_content_objects)
        if checkpoint_content_objects is not None
        else build_canonical_content_objects(task_input)
    )
    context_pack = checkpoint_context_pack or build_context_pack(
        task_input, plan, canonical_content_objects
    )
    resolved_bindings = resolve_capability_bindings(
        plan.lazy_loaded_capabilities,
        task_input=task_input,
        action_key=plan.action_key,
        skill_graph_key=plan.skill_graph_key,
    )
    provider_bindings, unavailable_capabilities, pending_approval_capabilities = resolve_capability_providers(
        plan.lazy_loaded_capabilities,
        task_input=task_input,
        action_key=plan.action_key,
        skill_graph_key=plan.skill_graph_key,
    )
    capability_resolution = ArtifactCapabilityResolution(
        available_capabilities=list(
            dict.fromkeys(plan.lazy_loaded_capabilities + plan.deferred_capabilities)
        ),
        lazy_loaded_capabilities=plan.lazy_loaded_capabilities,
        deferred_capabilities=plan.deferred_capabilities,
        resolved_bindings=resolved_bindings,
        provider_bindings=provider_bindings,
        unavailable_capabilities=unavailable_capabilities,
    )
    if unavailable_capabilities:
        return _build_waiting_result(
            task_input,
            plan,
            canonical_content_objects,
            context_pack,
            capability_resolution,
            unavailable_capabilities,
        )
    if pending_approval_capabilities:
        approval_binding = next(
            binding
            for binding in provider_bindings
            if binding["capability_name"] == pending_approval_capabilities[0]
        )
        approval_request = create_capability_request(
            task_id=task_input.task_id,
            workspace_id=task_input.workspace_id,
            capability_name=approval_binding["capability_name"],
            provider_id=approval_binding["provider_id"],
            server_id=approval_binding["server_id"],
            tool_name=approval_binding["tool_name"],
        )
        return _build_waiting_result(
            task_input,
            plan,
            canonical_content_objects,
            context_pack,
            capability_resolution,
            [],
            wait_phase="WAITING_FOR_APPROVAL",
            wait_status="WAITING_FOR_APPROVAL",
            wait_message="artifact task is waiting for approval on required capabilities",
            approval_request=approval_request,
        )
    async_provider_capabilities = _resolve_async_provider_capabilities(
        task_input=task_input,
        plan=plan,
        capability_resolution=capability_resolution,
    )
    if async_provider_capabilities:
        return _build_waiting_result(
            task_input,
            plan,
            canonical_content_objects,
            context_pack,
            capability_resolution,
            [],
            wait_phase="WAITING_FOR_PROVIDER",
            wait_status="WAITING_FOR_PROVIDER",
            wait_message="artifact task is waiting for async provider execution to finish",
        )
    completed_acquisition_request_ids = _completed_acquisition_request_ids(task_input, plan)
    if resume_stage and completed_acquisition_request_ids:
        # Provider callbacks may add content after the checkpoint was written.
        # Re-materialize only the acquired content layer; planning and the
        # already-completed external operation remain checkpointed/durable.
        canonical_content_objects = build_canonical_content_objects(task_input)
        context_pack = build_context_pack(task_input, plan, canonical_content_objects)
    repaired_sections, node_traces, generation_trace = execute_skill_graph(
        task_input,
        plan,
        section_generator=lambda _fallback_sections: generate_artifact_sections(
            task_input=task_input,
            plan=plan,
            canonical_content_objects=canonical_content_objects,
            llm_client=llm_client,
        ),
    )
    if generation_trace is None:
        raise RuntimeError("artifact skill graph did not produce a section draft")
    repaired_checks: list[str] = []
    artifact_title = resolve_artifact_title(task_input, plan)
    rendered_markdown = render_markdown(task_input, plan, repaired_sections)
    # Render only after the content and its evidence have passed the final gate.
    # A failed candidate must never create a user-facing export.
    export_trace = {"status": "PENDING_VALIDATION", "format": "PDF", "file_name": ""}

    events = [
        ArtifactProgressEvent(
            phase=phase,
            progress_percent=progress_percent,
            message=message,
            metrics={
                "outline_sections": len(plan.outline),
                "source_count": len(task_input.source_scope),
                "repaired_checks": len(repaired_checks),
                "skill_nodes": len(plan.node_sequence),
            },
        )
        for phase, progress_percent, message in PHASE_SEQUENCE
    ]
    job_snapshot = ArtifactJobSnapshot(
        task_id=task_input.task_id,
        workspace_id=task_input.workspace_id,
        target_id=task_input.target_id,
        action_key=plan.action_key,
        status="COMPLETED",
    )
    # The local version store is only a debug projection. A Host-created Run
    # carries a frozen input snapshot and receives its Version ID from Host.
    version_id, parent_version_id = (
        ("", "") if task_input.input_snapshot_id
        else reserve_next_artifact_version(task_input.target_id)
    )
    version_snapshot = ArtifactVersionSnapshot(
        version_id=version_id,
        artifact_type=plan.artifact_type,
        title=artifact_title,
        status="DRAFT",
        summary=f"{artifact_title} generated by controlled artifact runtime.",
        parent_version_id=parent_version_id,
    )
    writeback_preview = _build_writeback_preview(
        plan=plan,
        capability_resolution=capability_resolution,
        version_id=version_snapshot.version_id,
    )
    acquisition_receipt = build_acquisition_receipt(
        task_input,
        plan,
        capability_resolution,
        canonical_content_objects,
    )
    acquisition_runtime_snapshot = register_acquisition_runtime(
        task_id=task_input.task_id,
        acquisition_receipt=acquisition_receipt,
    )
    acquisition_runtime_dispatches = _submit_async_custom_mcp_operations(acquisition_receipt)
    approval_trace = _build_approval_trace(
        plan=plan,
        capability_resolution=capability_resolution,
    )
    capability_union_trace = _build_capability_union_trace(
        plan=plan,
        capability_resolution=capability_resolution,
    )
    result = ArtifactTaskResult(
        result_title=artifact_title,
        result_payload={
            "markdown": rendered_markdown,
            "execution_plan": plan.model_dump(mode="json"),
            "execution_spec": plan.execution_spec.model_dump(mode="json"),
            "runtime_plan": plan.runtime_plan.model_dump(mode="json"),
            "sections": [section.model_dump(mode="json") for section in repaired_sections],
            "node_traces": [trace.model_dump(mode="json") for trace in node_traces],
            "generation_trace": generation_trace,
            "export_trace": export_trace,
            "canonical_content_objects": [
                cco.model_dump(mode="json") for cco in canonical_content_objects
            ],
            "context_pack": context_pack.model_dump(mode="json"),
            "material_resolution": [
                {
                    "source_id": source.source_id,
                    "source_snapshot_id": source.source_snapshot_id or str(
                        source.source_metadata.get("source_snapshot_id", "")),
                    "selected_window_ids": [
                        str(window.get("window_id", "")) for window in source.material_windows
                    ],
                    "gap": source.material_gap,
                    "scan_next_cursor": str(
                        source.source_metadata.get("material_scan_next_cursor", "")),
                }
                for source in task_input.source_scope
            ],
            "capability_resolution": capability_resolution.model_dump(mode="json"),
            "acquisition_receipt": acquisition_receipt.model_dump(mode="json"),
            "acquisition_runtime_snapshot": acquisition_runtime_snapshot,
            "acquisition_runtime_dispatches": acquisition_runtime_dispatches,
            "resume_checkpoint": {
                "resumed_from_stage": resume_stage,
                "completed_acquisition_request_ids": completed_acquisition_request_ids,
            }
            if resume_stage
            else {},
            "capability_union_trace": capability_union_trace.model_dump(mode="json"),
            "approval_trace": approval_trace.model_dump(mode="json"),
            "writeback_preview": writeback_preview.model_dump(mode="json"),
            "artifact_job": job_snapshot.model_dump(mode="json"),
            "artifact_version": version_snapshot.model_dump(mode="json"),
        },
        trace_summary=(
            "artifact runtime executed: resolve skill request -> execution spec planning -> skill graph runtime "
            "-> verifier / repair -> artifact version export"
        ),
        citations=[
            {
                "title": item.title,
                "source_id": item.source_id,
                "source_snapshot_id": item.source_snapshot_id or str(
                    item.source_metadata.get("source_snapshot_id", "")),
                "source_window_ids": [
                    str(window.get("window_id", "")) for window in item.material_windows
                ],
            }
            for item in task_input.source_scope[:3]
        ],
        job_snapshot=job_snapshot,
        version_snapshot=version_snapshot,
    )
    evidence_coverage = _build_evidence_coverage(
        task_input=task_input,
        plan=plan,
        sections=repaired_sections,
    )
    result.result_payload["evidence_coverage"] = evidence_coverage.model_dump(mode="json")
    output_contract_trace = build_output_contract_trace(result, plan, repaired_checks)
    result.result_payload["output_contract_trace"] = output_contract_trace.model_dump(mode="json")
    verification = verify_artifact_output(result, plan, repaired_checks)
    result.result_payload["verification"] = verification.model_dump(mode="json")
    if verification.status == "FAIL":
        raise ArtifactOutputContractViolationError(verification.failed_checks)
    export_trace = export_artifact_if_required(
        task_input=task_input,
        title=artifact_title,
        sections=repaired_sections,
    )
    result.result_payload["export_trace"] = export_trace
    if task_input.input_payload.skill_key.strip().lower() == "bilibili_course_note_pdf":
        if export_trace["status"] != "COMPILED":
            raise ArtifactOutputContractViolationError(["required PDF export did not compile"])
    required_files = build_required_files(rendered_markdown, export_trace)
    content_sha256 = hashlib.sha256(rendered_markdown.encode("utf-8")).hexdigest()
    candidate_id = hashlib.sha256(
        f"{task_input.task_id}:{task_input.input_snapshot_id}:{content_sha256}".encode("utf-8")
    ).hexdigest()
    result.result_payload["candidate"] = {
        "candidate_id": candidate_id,
        "task_id": task_input.task_id,
        "input_snapshot_id": task_input.input_snapshot_id,
        "catalog_digest": task_input.catalog_digest,
        "content_sha256": content_sha256,
        "required_files": required_files,
    }

    writeback_request = (
        None if task_input.input_snapshot_id else register_writeback_request(
            task_input=task_input,
            plan=plan,
            result=result,
            version_snapshot=version_snapshot,
            writeback_preview=writeback_preview,
        )
    )
    if writeback_request is not None:
        result.result_payload["writeback_request"] = writeback_request.model_dump(mode="json")
        result.result_payload["writeback_preview"]["request_id"] = writeback_request.request_id
        result.result_payload["writeback_preview"]["target_locator_preview"] = (
            writeback_request.target_locator_preview
        )
    result.result_payload["lifecycle_trace"] = _build_lifecycle_trace(
        events=events,
        status="COMPLETED",
        notes=[
            f"verification_status={verification.status}",
            f"repair_action_count={len(repaired_checks)}",
            f"generation_mode={generation_trace['mode']}",
            f"export_status={export_trace['status']}",
        ],
    ).model_dump(mode="json")
    retrieval_feedback = _build_retrieval_feedback(
        task_input=task_input,
        plan=plan,
        sections=repaired_sections,
    )
    memory_promotion_preview = _build_memory_promotion_preview(
        task_input=task_input,
        sections=repaired_sections,
        verification_status=verification.status,
    )
    retrieval_feedback.promotion_candidate_count = (
        memory_promotion_preview.candidate_memory_count
    )
    result.result_payload["retrieval_feedback"] = retrieval_feedback.model_dump(mode="json")
    result.result_payload["memory_promotion_preview"] = memory_promotion_preview.model_dump(mode="json")
    commit_receipt = (
        ArtifactCommitReceipt(
            commit_status="DEFERRED_TO_HOST", version_id="", retrieval_entry_id="",
            persisted_version_count=0, persisted_retrieval_count=0,
        )
        if task_input.input_snapshot_id else commit_artifact_result(
            task_id=task_input.task_id,
            workspace_id=task_input.workspace_id,
            target_id=task_input.target_id,
            result=result,
            retrieval_feedback=retrieval_feedback,
        )
    )
    result.result_payload["artifact_commit"] = commit_receipt.model_dump(mode="json")
    return events, result


class ArtifactOutputContractViolationError(RuntimeError):
    error_code = "ARTIFACT_OUTPUT_CONTRACT_FAILED"

    def __init__(self, failed_checks: list[str]) -> None:
        self.failed_checks = list(failed_checks)
        super().__init__("Artifact output contract failed: " + "; ".join(self.failed_checks))


def _build_waiting_result(
    task_input: ArtifactTaskInput,
    plan: object,
    canonical_content_objects: list[object],
    context_pack: object,
    capability_resolution: ArtifactCapabilityResolution,
    unavailable_capabilities: list[str],
    wait_phase: str = "WAITING_FOR_CAPABILITY",
    wait_status: str = "WAITING_FOR_CAPABILITY",
    wait_message: str = "artifact task is waiting for unavailable capability providers",
    approval_request: dict[str, object] | None = None,
) -> tuple[list[ArtifactProgressEvent], ArtifactTaskResult]:
    acquisition_receipt = build_acquisition_receipt(
        task_input,
        plan,
        capability_resolution,
        canonical_content_objects,
        wait_status=wait_status,
    )
    wait_queue_entry = enqueue_waiting_task(
        task_input=task_input,
        unavailable_capabilities=unavailable_capabilities,
        status=wait_status,
        approval_request=approval_request,
        blocked_operations=_extract_blocked_operations(acquisition_receipt),
        resume_checkpoint={
            "schema_version": 2,
            "stage": "CAPABILITY_GATE",
            "execution_plan": plan.model_dump(mode="json"),
            "canonical_content_objects": [
                cco.model_dump(mode="json") for cco in canonical_content_objects
            ],
            "context_pack": context_pack.model_dump(mode="json"),
            "capability_resolution": capability_resolution.model_dump(mode="json"),
            "acquisition_receipt": acquisition_receipt.model_dump(mode="json"),
        },
    )
    wait_event = ArtifactProgressEvent(
        phase=wait_phase,
        progress_percent=35,
        message=wait_message,
        metrics={
            "unavailable_capability_count": len(unavailable_capabilities),
            "approval_request_count": 1 if approval_request else 0,
        },
        payload=_build_wait_event_payload(
            acquisition_receipt=acquisition_receipt,
            wait_status=wait_status,
            unavailable_capabilities=unavailable_capabilities,
            approval_request=approval_request,
        ),
    )
    job_snapshot = ArtifactJobSnapshot(
        task_id=task_input.task_id,
        workspace_id=task_input.workspace_id,
        target_id=task_input.target_id,
        action_key=plan.action_key,
        status=wait_status,
    )
    version_snapshot = ArtifactVersionSnapshot(
        version_id="",
        artifact_type=plan.artifact_type,
        title=plan.action_display_name,
        status=wait_status,
        summary="Artifact generation is waiting for required provider actions before execution.",
    )
    result = ArtifactTaskResult(
        result_title=plan.action_display_name,
        result_payload={
            "markdown": "",
            "execution_plan": plan.model_dump(mode="json"),
            "sections": [],
            "node_traces": [],
            "canonical_content_objects": [
                cco.model_dump(mode="json") for cco in canonical_content_objects
            ],
            "context_pack": context_pack.model_dump(mode="json"),
            "capability_resolution": capability_resolution.model_dump(mode="json"),
            "acquisition_receipt": acquisition_receipt.model_dump(mode="json"),
            "writeback_preview": _build_writeback_preview(
                plan=plan,
                capability_resolution=capability_resolution,
                status_override=wait_status,
            ).model_dump(mode="json"),
            "wait_reason": {
                "status": wait_status,
                "unavailable_capabilities": unavailable_capabilities,
            },
            "approval_request": approval_request or {},
            "wait_queue_entry": wait_queue_entry,
            "artifact_job": job_snapshot.model_dump(mode="json"),
            "artifact_version": version_snapshot.model_dump(mode="json"),
        },
        trace_summary=(
            "artifact runtime paused before skill graph execution due to provider gating"
        ),
        citations=[],
        job_snapshot=job_snapshot,
        version_snapshot=version_snapshot,
    )
    acquisition_runtime_snapshot = register_acquisition_runtime(
        task_id=task_input.task_id,
        acquisition_receipt=acquisition_receipt,
    )
    result.result_payload["acquisition_runtime_snapshot"] = acquisition_runtime_snapshot
    result.result_payload["acquisition_runtime_dispatches"] = _submit_async_custom_mcp_operations(
        acquisition_receipt
    )
    result.result_payload["approval_trace"] = _build_approval_trace(
        plan=plan,
        capability_resolution=capability_resolution,
        wait_status=wait_status,
        approval_request=approval_request,
    ).model_dump(mode="json")
    result.result_payload["capability_union_trace"] = _build_capability_union_trace(
        plan=plan,
        capability_resolution=capability_resolution,
    ).model_dump(mode="json")
    result.result_payload["lifecycle_trace"] = _build_lifecycle_trace(
        events=[wait_event],
        status=wait_status,
        notes=[
            f"wait_phase={wait_phase}",
            f"unavailable_capability_count={len(unavailable_capabilities)}",
        ],
    ).model_dump(mode="json")
    return [wait_event], result


def _extract_blocked_operations(acquisition_receipt: AcquisitionReceipt) -> list[dict[str, object]]:
    blocked_operations: list[dict[str, object]] = []
    for source_receipt in acquisition_receipt.source_receipts:
        for operation in source_receipt.operations:
            if operation.status not in {
                "WAITING_FOR_APPROVAL",
                "WAITING_FOR_CAPABILITY",
                "WAITING_FOR_PROVIDER",
            }:
                continue
            blocked_operations.append(
                {
                    "request_id": operation.request_id,
                    "source_id": source_receipt.source_id,
                    "operation_key": operation.operation_key,
                    "capability_name": operation.capability_name,
                    "callback_status": operation.callback_status,
                }
            )
    return blocked_operations


def _build_wait_event_payload(
    *,
    acquisition_receipt: AcquisitionReceipt,
    wait_status: str,
    unavailable_capabilities: list[str],
    approval_request: dict[str, object] | None,
) -> dict[str, object]:
    blocked_operations = _extract_blocked_operations(acquisition_receipt)
    primary_operation = _select_primary_blocked_operation(acquisition_receipt, wait_status)
    provider_job: dict[str, object] = {}
    if primary_operation is not None:
        provider_delivery_attempts = _build_wait_provider_delivery_attempts(primary_operation)
        previous_failed_delivery_count = _count_failed_delivery_attempts(provider_delivery_attempts)
        provider_job = {
            "provider_id": primary_operation.provider_id,
            "server_id": primary_operation.server_id,
            "tool_name": primary_operation.tool_name,
            "capability_name": primary_operation.capability_name,
            "operation_key": primary_operation.operation_key,
            "provider_status": primary_operation.provider_status,
            "health_status": primary_operation.health_status,
            "status": primary_operation.status,
            "request_id": primary_operation.request_id,
            "provider_job_id": primary_operation.provider_job_id,
            "provider_receipt_id": primary_operation.provider_receipt_id,
            "delivery_id": primary_operation.delivery_id,
            "callback_token": primary_operation.callback_token,
            "adapter_callback_token": primary_operation.adapter_callback_token,
            "provider_job_status": primary_operation.provider_job_status,
            "callback_status": primary_operation.callback_status,
            "dispatch_count": _resolve_wait_dispatch_count(
                primary_operation=primary_operation,
                provider_delivery_attempts=provider_delivery_attempts,
            ),
            "previous_failed_delivery_count": previous_failed_delivery_count,
            "has_previous_failed_delivery": previous_failed_delivery_count > 0,
            "provider_delivery_attempts": provider_delivery_attempts,
        }
    return {
        "provider_job": provider_job,
        "approval_request": approval_request or {},
        "wait_reason": {
            "status": wait_status,
            "unavailable_capabilities": unavailable_capabilities,
            "blocked_operations": blocked_operations,
        },
    }


def _select_primary_blocked_operation(
    acquisition_receipt: AcquisitionReceipt,
    wait_status: str,
):
    for source_receipt in acquisition_receipt.source_receipts:
        for operation in source_receipt.operations:
            if operation.status == wait_status:
                return operation
    for source_receipt in acquisition_receipt.source_receipts:
        for operation in source_receipt.operations:
            if operation.status.startswith("WAITING_FOR_"):
                return operation
    return None


def _build_wait_provider_delivery_attempts(primary_operation: object) -> list[dict[str, object]]:
    delivery_id = str(getattr(primary_operation, "delivery_id", "")).strip()
    capability_name = str(getattr(primary_operation, "capability_name", "")).strip()
    if not delivery_id or not capability_name:
        return []
    return [
        {
            "delivery_id": delivery_id,
            "dispatch_count": 1,
            "callback_token": str(getattr(primary_operation, "callback_token", "")).strip(),
            "dispatched_at": str(getattr(primary_operation, "requested_at", "")).strip(),
            "callback_deadline_at": "",
            "provider_job_id": str(getattr(primary_operation, "provider_job_id", "")).strip(),
            "provider_receipt_id": str(getattr(primary_operation, "provider_receipt_id", "")).strip(),
            "input_digest": "",
            "ack_status": "PENDING",
            "callback_received_at": str(getattr(primary_operation, "callback_received_at", "")).strip(),
            "result_locator": str(getattr(primary_operation, "result_locator", "")).strip(),
            "error_code": str(getattr(primary_operation, "error_code", "")).strip(),
            "error_message": str(getattr(primary_operation, "error_message", "")).strip(),
        }
    ]


def _resolve_wait_dispatch_count(
    *,
    primary_operation: object,
    provider_delivery_attempts: list[dict[str, object]],
) -> int:
    explicit_count = int(getattr(primary_operation, "dispatch_count", 0) or 0)
    if explicit_count > 0:
        return explicit_count
    return len(provider_delivery_attempts)


def _count_failed_delivery_attempts(provider_delivery_attempts: list[dict[str, object]]) -> int:
    return sum(
        1
        for attempt in provider_delivery_attempts
        if str(attempt.get("ack_status", "")).strip().upper() == "FAILED"
    )


def _submit_async_custom_mcp_operations(
    acquisition_receipt: AcquisitionReceipt,
) -> list[dict[str, object]]:
    dispatches: list[dict[str, object]] = []
    for source_receipt in acquisition_receipt.source_receipts:
        for operation in source_receipt.operations:
            if not str(operation.server_id).strip().startswith("custom-"):
                continue
            if operation.status == "WAITING_FOR_PROVIDER":
                try:
                    dispatches.append(
                        submit_custom_mcp_acquisition_operation(operation.request_id)
                    )
                except ValueError as exc:
                    dispatches.append(
                        {
                            "request_id": operation.request_id,
                            "status": "DISPATCH_FAILED",
                            "error_message": sanitize_error_message(str(exc)),
                        }
                    )
    return dispatches


def _build_writeback_preview(
    *,
    plan: object,
    capability_resolution: ArtifactCapabilityResolution,
    version_id: str = "",
    status_override: str = "",
) -> WritebackPreview:
    if plan.writeback_gate.decision == "SKIP":
        return WritebackPreview(
            status="SKIPPED",
            requested_mode=plan.writeback_gate.requested_mode,
            allowed_target="",
            execution_mode=plan.writeback_gate.execution_mode,
            required_capabilities=[],
            version_id=version_id,
            notes=[],
        )
    if plan.writeback_gate.decision == "DENY":
        return WritebackPreview(
            status="DENIED",
            requested_mode=plan.writeback_gate.requested_mode,
            allowed_target="",
            execution_mode=plan.writeback_gate.execution_mode,
            required_capabilities=plan.writeback_gate.required_capabilities,
            version_id=version_id,
            notes=list(plan.writeback_gate.notes),
        )

    status = "READY_FOR_HOST_WRITEBACK"
    if status_override == "WAITING_FOR_APPROVAL":
        status = "WAITING_FOR_APPROVAL"
    elif status_override == "WAITING_FOR_CAPABILITY":
        status = "WAITING_FOR_CAPABILITY"
    elif status_override == "WAITING_FOR_PROVIDER":
        status = "WAITING_FOR_PROVIDER"
    elif any(
        binding["capability_name"] in plan.writeback_gate.required_capabilities
        and binding.get("approval_status") != "APPROVED"
        for binding in capability_resolution.provider_bindings
    ):
        status = "WAITING_FOR_APPROVAL"
    elif any(
        binding["capability_name"] in plan.writeback_gate.required_capabilities
        and (
            binding.get("discovery_status") not in {"REGISTERED", "DISCOVERED"}
            or binding.get("provider_status") != "AVAILABLE"
            or binding.get("health_status") in {"DOWN", "UNAVAILABLE"}
        )
        for binding in capability_resolution.provider_bindings
    ):
        status = "WAITING_FOR_CAPABILITY"

    return WritebackPreview(
        status=status,
        requested_mode=plan.writeback_gate.requested_mode,
        allowed_target=plan.writeback_gate.allowed_target,
        execution_mode=plan.writeback_gate.execution_mode,
        required_capabilities=list(plan.writeback_gate.required_capabilities),
        version_id=version_id,
        notes=list(plan.writeback_gate.notes),
    )


def _build_approval_trace(
    *,
    plan: object,
    capability_resolution: ArtifactCapabilityResolution,
    wait_status: str = "",
    approval_request: dict[str, object] | None = None,
) -> ApprovalRuntimeTrace:
    required_capabilities = list(plan.approval_gate.required_capabilities)
    if not required_capabilities:
        return ApprovalRuntimeTrace(
            status="NOT_REQUIRED",
            decision=plan.approval_gate.decision,
            reason_code=plan.approval_gate.reason_code,
            required_capabilities=[],
            pending_capabilities=[],
            satisfied_capabilities=[],
            approval_request={},
            capability_decisions=[],
            notes=list(plan.approval_gate.notes),
        )

    capability_decisions: list[dict[str, object]] = []
    pending_capabilities: list[str] = []
    satisfied_capabilities: list[str] = []
    for capability_name in required_capabilities:
        binding = next(
            (
                item
                for item in capability_resolution.provider_bindings
                if item["capability_name"] == capability_name
            ),
            {},
        )
        approval_status = str(binding.get("approval_status", ""))
        if approval_status == "APPROVED":
            runtime_status = "SATISFIED"
            satisfied_capabilities.append(capability_name)
        elif wait_status == "WAITING_FOR_APPROVAL":
            runtime_status = "WAITING_FOR_APPROVAL"
            pending_capabilities.append(capability_name)
        else:
            runtime_status = "PENDING_APPROVAL"
            pending_capabilities.append(capability_name)
        capability_decisions.append(
            {
                "capability_name": capability_name,
                "provider_id": str(binding.get("provider_id", "")),
                "server_id": str(binding.get("server_id", "")),
                "tool_name": str(binding.get("tool_name", "")),
                "approval_status": approval_status,
                "provider_status": str(binding.get("provider_status", "")),
                "health_status": str(binding.get("health_status", "")),
                "discovery_status": str(binding.get("discovery_status", "")),
                "selection_reason": str(binding.get("selection_reason", "")),
                "runtime_status": runtime_status,
            }
        )

    if wait_status == "WAITING_FOR_APPROVAL":
        status = "WAITING_FOR_APPROVAL"
    elif pending_capabilities:
        status = "PENDING_APPROVAL"
    else:
        status = "SATISFIED"

    notes = list(plan.approval_gate.notes)
    if approval_request:
        notes.append(
            "runtime created an approval request for the selected capability binding"
        )
    return ApprovalRuntimeTrace(
        status=status,
        decision=plan.approval_gate.decision,
        reason_code=plan.approval_gate.reason_code,
        required_capabilities=required_capabilities,
        pending_capabilities=pending_capabilities,
        satisfied_capabilities=satisfied_capabilities,
        approval_request=approval_request or {},
        capability_decisions=capability_decisions,
        notes=notes,
    )


def _build_capability_union_trace(
    *,
    plan: object,
    capability_resolution: ArtifactCapabilityResolution,
) -> CapabilityUnionRuntimeTrace:
    policy = plan.capability_union_policy
    resolved_binding_map = {
        str(binding["capability_name"]): binding
        for binding in capability_resolution.resolved_bindings
    }
    provider_binding_map = {
        str(binding["capability_name"]): binding
        for binding in capability_resolution.provider_bindings
    }
    external_network_capabilities: list[str] = []
    writeback_capabilities: list[str] = []
    capability_decisions: list[dict[str, object]] = []

    for capability_name in policy.capability_scope:
        resolved_binding = resolved_binding_map.get(capability_name, {})
        provider_binding = provider_binding_map.get(capability_name, {})
        scope_type = str(resolved_binding.get("scope_type", ""))
        if scope_type == "EXTERNAL_NETWORK":
            external_network_capabilities.append(capability_name)
        if scope_type in {"WRITE", "EXPORT"}:
            writeback_capabilities.append(capability_name)
        capability_decisions.append(
            {
                "capability_name": capability_name,
                "scope_type": scope_type,
                "server_id": str(resolved_binding.get("server_id", "")),
                "tool_name": str(resolved_binding.get("tool_name", "")),
                "provider_id": str(provider_binding.get("provider_id", "")),
                "risk_level": str(resolved_binding.get("risk_level", "")),
                "approval_mode": str(resolved_binding.get("approval_mode", "")),
                "discovery_status": str(provider_binding.get("discovery_status", "")),
                "provider_status": str(provider_binding.get("provider_status", "")),
                "health_status": str(provider_binding.get("health_status", "")),
                "approval_status": str(provider_binding.get("approval_status", "")),
                "selection_reason": str(
                    provider_binding.get(
                        "selection_reason",
                        resolved_binding.get("selection_reason", ""),
                    )
                ),
                "route_basis": str(provider_binding.get("route_basis", resolved_binding.get("route_basis", ""))),
                "action_basis": str(
                    provider_binding.get("action_basis", resolved_binding.get("action_basis", ""))
                ),
                "skill_graph_basis": str(
                    provider_binding.get(
                        "skill_graph_basis",
                        resolved_binding.get("skill_graph_basis", ""),
                    )
                ),
                "runtime_status": (
                    "BLOCKED"
                    if capability_name in policy.blocked_capabilities
                    else "ALLOWED"
                ),
            }
        )

    notes = list(policy.notes)
    if external_network_capabilities:
        notes.append(
            "runtime union trace surfaced external-network capabilities for downstream review"
        )
    if writeback_capabilities:
        notes.append(
            "runtime union trace surfaced write/export capabilities for host-managed writeback review"
        )

    return CapabilityUnionRuntimeTrace(
        status=policy.decision,
        decision=policy.decision,
        reason_code=policy.reason_code,
        policy_key=policy.policy_key,
        action_scope=policy.action_scope,
        skill_scope=policy.skill_scope,
        workspace_scope=policy.workspace_scope,
        capability_scope=list(policy.capability_scope),
        external_network_capabilities=external_network_capabilities,
        writeback_capabilities=writeback_capabilities,
        blocked_capabilities=list(policy.blocked_capabilities),
        capability_decisions=capability_decisions,
        notes=notes,
    )


def _build_lifecycle_trace(
    *,
    events: list[ArtifactProgressEvent],
    status: str,
    notes: list[str] | None = None,
) -> ArtifactLifecycleTrace:
    steps = [
        ArtifactLifecycleStepTrace(
            phase=event.phase,
            status=status if event == events[-1] else "COMPLETED",
            progress_percent=event.progress_percent,
            message=event.message,
            metrics=dict(event.metrics),
        )
        for event in events
    ]
    current_phase = steps[-1].phase if steps else ""
    return ArtifactLifecycleTrace(
        status=status,
        current_phase=current_phase,
        steps=steps,
        notes=notes or [],
    )


def _resolve_async_provider_capabilities(
    *,
    task_input: ArtifactTaskInput,
    plan: object,
    capability_resolution: ArtifactCapabilityResolution,
) -> list[str]:
    pending_capabilities: list[str] = []
    for source_plan in plan.content_acquisition_plan.source_plans:
        for operation_key in source_plan.planned_operations:
            capability_name = {
                "READ_EXTERNAL_CONTENT": "READ_WEB_PAGE",
                "EXTRACT_TRANSCRIPT": "EXTRACT_TRANSCRIPT",
                "TRANSCRIBE_AUDIO": "TRANSCRIBE_AUDIO",
            }.get(operation_key, "")
            if not capability_name:
                continue
            resolved_binding = next(
                (
                    binding
                    for binding in capability_resolution.resolved_bindings
                    if binding["capability_name"] == capability_name
                ),
                {},
            )
            server_id = str(resolved_binding.get("server_id", "")).strip().lower()
            if (
                not server_id.startswith("custom-")
                and not _is_bilibili_pdf_async_provider(
                    task_input=task_input,
                    capability_name=capability_name,
                    server_id=server_id,
                )
            ):
                continue
            request_id = (
                f"fetch-{task_input.task_id}-{source_plan.source_id}-{operation_key.lower()}"
            )
            if get_acquisition_result_payload(request_id):
                continue
            if capability_name not in pending_capabilities:
                pending_capabilities.append(capability_name)
    return pending_capabilities


def _completed_acquisition_request_ids(
    task_input: ArtifactTaskInput,
    plan: ArtifactExecutionPlan,
) -> list[str]:
    completed: list[str] = []
    for source_plan in plan.content_acquisition_plan.source_plans:
        for operation_key in source_plan.planned_operations:
            request_id = (
                f"fetch-{task_input.task_id}-{source_plan.source_id}-{operation_key.lower()}"
            )
            if get_acquisition_result_payload(request_id):
                completed.append(request_id)
    return completed


def _build_retrieval_feedback(
    *,
    task_input: ArtifactTaskInput,
    plan: object,
    sections: list[object],
) -> RetrievalFeedback:
    supporting_source_ids = [source.source_id for source in task_input.source_scope]
    derived_passages: list[dict[str, object]] = []
    for index, section in enumerate(sections[:3], start=1):
        source_refs = list(section.source_refs)
        preview_text = " ".join(line.strip() for line in section.body.splitlines() if line.strip())
        derived_passages.append(
            {
                "chunk_id": f"{task_input.task_id}:{index}",
                "section_heading": section.heading,
                "summary_text": preview_text[:180],
                "source_refs": source_refs,
                "retrieval_tags": [plan.action_key, plan.style_profile_key, "ARTIFACT_DERIVED"],
            }
        )

    return RetrievalFeedback(
        retrieval_label="ARTIFACT_DERIVED",
        index_status="DRAFT",
        retrieval_weight_hint="Draft artifact remains below original sources in retrieval ranking.",
        supporting_source_ids=supporting_source_ids,
        derived_passage_count=len(derived_passages),
        derived_passages=derived_passages,
        notes=[
            "Original Source > User Card > Pinned Artifact > Indexed Artifact > Draft Artifact",
            "Derived passages preserve section-level evidence trace for downstream retrieval preview.",
        ],
    )


def _build_evidence_coverage(
    *,
    task_input: ArtifactTaskInput,
    plan: object,
    sections: list[object],
) -> EvidenceCoverageReport:
    source_title_to_id = {
        source.title: source.source_id
        for source in task_input.source_scope
    }
    section_evidence: list[dict[str, object]] = []
    sections_missing_evidence: list[str] = []
    supporting_source_ids: list[str] = []

    for section in sections:
        source_refs = list(section.source_refs)
        source_ids = [
            source_title_to_id[source_ref]
            for source_ref in source_refs
            if source_ref in source_title_to_id
        ]
        if source_refs:
            supporting_source_ids.extend(source_ids)
        else:
            sections_missing_evidence.append(section.heading)
        section_evidence.append(
            {
                "section_heading": section.heading,
                "source_refs": source_refs,
                "source_ids": source_ids,
                "evidence_status": "COVERED" if source_refs else "MISSING",
            }
        )

    section_count = len(sections)
    covered_section_count = sum(
        1
        for item in section_evidence
        if item["evidence_status"] == "COVERED"
    )
    coverage_ratio = (
        round(covered_section_count / section_count, 2)
        if section_count
        else 0.0
    )
    unique_supporting_source_ids = list(dict.fromkeys(supporting_source_ids))
    required_density = str(plan.evidence_gate.required_citation_density or "LOW").upper()
    status = "PASS"
    notes = [
        f"required_density={required_density}",
        f"covered_sections={covered_section_count}/{section_count}",
    ]
    if required_density == "HIGH":
        minimum_covered_sections = section_count
        minimum_unique_sources = min(2, len(task_input.source_scope))
    elif required_density == "MEDIUM":
        minimum_covered_sections = section_count
        minimum_unique_sources = 1 if task_input.source_scope else 0
    else:
        minimum_covered_sections = 1 if section_count else 0
        minimum_unique_sources = 1 if task_input.source_scope else 0

    if covered_section_count < minimum_covered_sections:
        status = "FAIL"
        notes.append("covered section count below citation density requirement")
    if len(unique_supporting_source_ids) < minimum_unique_sources:
        status = "FAIL"
        notes.append("unique supporting source count below citation density requirement")
    if sections_missing_evidence:
        notes.append(
            "sections missing evidence: " + ", ".join(sections_missing_evidence)
        )

    return EvidenceCoverageReport(
        status=status,
        required_citation_density=required_density,
        section_count=section_count,
        covered_section_count=covered_section_count,
        coverage_ratio=coverage_ratio,
        supporting_source_ids=unique_supporting_source_ids,
        section_evidence=section_evidence,
        sections_missing_evidence=sections_missing_evidence,
        notes=notes,
    )


def _build_memory_promotion_preview(
    *,
    task_input: ArtifactTaskInput,
    sections: list[object],
    verification_status: str,
) -> MemoryPromotionPreview:
    source_title_to_id = {
        source.title: source.source_id
        for source in task_input.source_scope
    }
    candidate_memories: list[dict[str, object]] = []
    for index, section in enumerate(sections[:3], start=1):
        source_refs = list(section.source_refs)
        source_ids = [
            source_title_to_id[source_ref]
            for source_ref in source_refs
            if source_ref in source_title_to_id
        ]
        summary_text = " ".join(line.strip() for line in section.body.splitlines() if line.strip())
        candidate_memories.append(
            {
                "memory_key": f"memory-candidate-{task_input.task_id}-{index}",
                "section_heading": section.heading,
                "summary_text": summary_text[:160],
                "source_refs": source_refs,
                "source_ids": source_ids,
                "promotion_reason": "section carries reusable artifact insight with evidence trace",
            }
        )

    eligible = bool(task_input.source_scope) and verification_status in {"PASS", "WARN"}
    novelty_gate_status = "PENDING_REVIEW" if eligible and candidate_memories else "SKIPPED"
    supporting_source_ids = list(
        dict.fromkeys(
            source_id
            for candidate in candidate_memories
            for source_id in candidate["source_ids"]
        )
    )
    return MemoryPromotionPreview(
        eligible=eligible,
        candidate_memory_count=len(candidate_memories),
        novelty_gate_status=novelty_gate_status,
        supporting_source_ids=supporting_source_ids,
        candidate_memories=candidate_memories,
        notes=[
            "Artifact-derived memory candidates require user confirmation before promotion.",
            "Candidate memories inherit section-level evidence trace instead of bypassing source boundaries.",
        ],
    )
