from __future__ import annotations

from collections import Counter
from datetime import datetime, timezone
import hashlib

from app.action_compat import resolve_action_compatibility
from app.io_limits import ContentSizeLimitError, read_text_file_limited
from app.capability_provider import (
    get_capability_provider_approval_status,
    get_capability_provider_discovery_status,
    get_capability_provider_health_status,
    get_capability_provider_status,
    list_capability_provider_candidates,
)
from app.artifact_skill_catalog import try_resolve_artifact_skill_definition
from app.models import (
    AcquisitionOperationReceipt,
    AcquisitionProviderAttempt,
    AcquisitionReceipt,
    AcquisitionSourceReceipt,
    ArtifactCapabilityResolution,
    ArtifactExecutionPlan,
    ArtifactTaskInput,
    CanonicalContentObject,
    CanonicalContentSegment,
    ContentAcquisitionPlan,
    ContentAcquisitionSourcePlan,
    ContentAcquisitionStep,
    ContextPack,
    SourceScopeItem,
)
from app.registry import resolve_style_profile
from app.acquisition_runtime import get_acquisition_result_payload
from app.provider_job_status import resolve_provider_job_status


def build_content_acquisition_plan(task_input: ArtifactTaskInput) -> ContentAcquisitionPlan:
    action_key = _resolve_effective_action_key(task_input)
    descriptors = _adapt_source_inputs(task_input, action_key)
    routes = set(describe_input_adapter_routes(task_input))
    route_summary = dict(Counter(descriptor["adapter_route"] for descriptor in descriptors))
    source_plans = [
        ContentAcquisitionSourcePlan(**descriptor["source_plan"])
        for descriptor in descriptors
    ]
    has_external_web = any(route in {"WEB_URL", "VIDEO_URL"} for route in routes)
    has_card_context = "CARD_CONTEXT" in routes

    if "VIDEO_URL" in routes:
        required_capabilities = ["EXTRACT_TRANSCRIPT"]
        return ContentAcquisitionPlan(
            strategy_key=f"acq-{task_input.task_id}",
            primary_strategy="VIDEO_TRANSCRIPT_PIPELINE",
            required_capabilities=required_capabilities,
            steps=[
                ContentAcquisitionStep(
                    step_id=f"{task_input.task_id}-resolve-video",
                    step_type="RESOLVE_URL_SOURCE",
                    description="Input adapter classifies video-like sources and prepares transcript extraction.",
                    status="PLANNED",
                ),
                ContentAcquisitionStep(
                    step_id=f"{task_input.task_id}-extract-transcript",
                    step_type="EXTRACT_TRANSCRIPT",
                    description="Extract transcript from the video source through the capability layer.",
                    capability_name="EXTRACT_TRANSCRIPT",
                    status="PLANNED",
                ),
                ContentAcquisitionStep(
                    step_id=f"{task_input.task_id}-normalize",
                    step_type="NORMALIZE_TO_CCO",
                    description="Normalize transcript output into canonical content objects.",
                    status="PLANNED",
                ),
            ],
            route_summary=route_summary,
            source_plans=source_plans,
            notes=[
                f"Input adapter routed sources as: {', '.join(sorted(routes))}.",
                "Prefer transcript extraction before fallback regeneration.",
                "Video-derived artifacts should enter the runtime as TRANSCRIPT CCOs.",
            ],
        )

    if "VIDEO_FILE" in routes:
        required_capabilities = ["TRANSCRIBE_AUDIO"]
        return ContentAcquisitionPlan(
            strategy_key=f"acq-{task_input.task_id}",
            primary_strategy="VIDEO_AUDIO_TRANSCRIPTION_PIPELINE",
            required_capabilities=required_capabilities,
            steps=[
                ContentAcquisitionStep(
                    step_id=f"{task_input.task_id}-resolve-video",
                    step_type="RESOLVE_VIDEO_SOURCE",
                    description="Input adapter resolves uploaded video files before extraction.",
                    status="PLANNED",
                ),
                ContentAcquisitionStep(
                    step_id=f"{task_input.task_id}-extract-audio",
                    step_type="EXTRACT_AUDIO_TRACK",
                    description="Extract the audio track from the uploaded video source.",
                    status="PLANNED",
                ),
                ContentAcquisitionStep(
                    step_id=f"{task_input.task_id}-transcribe-audio",
                    step_type="TRANSCRIBE_AUDIO",
                    description="Transcribe the extracted audio track through the capability layer.",
                    capability_name="TRANSCRIBE_AUDIO",
                    status="PLANNED",
                ),
                ContentAcquisitionStep(
                    step_id=f"{task_input.task_id}-normalize",
                    step_type="NORMALIZE_TO_CCO",
                    description="Normalize transcription output into canonical content objects.",
                    status="PLANNED",
                ),
            ],
            route_summary=route_summary,
            source_plans=source_plans,
            notes=[
                f"Input adapter routed sources as: {', '.join(sorted(routes))}.",
                "Uploaded video files use audio-track transcription before entering the harness.",
            ],
        )

    if action_key == "AUDIO_MINUTES" or "AUDIO_FILE" in routes:
        required_capabilities = ["TRANSCRIBE_AUDIO"]
        return ContentAcquisitionPlan(
            strategy_key=f"acq-{task_input.task_id}",
            primary_strategy="AUDIO_TRANSCRIPT_PIPELINE",
            required_capabilities=required_capabilities,
            steps=[
                ContentAcquisitionStep(
                    step_id=f"{task_input.task_id}-resolve-audio",
                    step_type="RESOLVE_AUDIO_SOURCE",
                    description="Resolve audio input and prepare transcription.",
                    status="PLANNED",
                ),
                ContentAcquisitionStep(
                    step_id=f"{task_input.task_id}-transcribe-audio",
                    step_type="TRANSCRIBE_AUDIO",
                    description="Transcribe audio input through the capability layer.",
                    capability_name="TRANSCRIBE_AUDIO",
                    status="PLANNED",
                ),
                ContentAcquisitionStep(
                    step_id=f"{task_input.task_id}-normalize",
                    step_type="NORMALIZE_TO_CCO",
                    description="Normalize transcription output into canonical content objects.",
                    status="PLANNED",
                ),
            ],
            route_summary=route_summary,
            source_plans=source_plans,
            notes=[
                f"Input adapter routed sources as: {', '.join(sorted(routes))}.",
                "Audio-derived artifacts should be grounded in transcript snapshots.",
            ],
        )

    if len(descriptors) > 1:
        steps: list[ContentAcquisitionStep] = [
            ContentAcquisitionStep(
                step_id=f"{task_input.task_id}-collect",
                step_type="COLLECT_WORKSPACE_MATERIALS",
                description="Collect workspace materials and saved artifacts within the current source scope.",
                status="PLANNED",
            )
        ]
        if has_card_context:
            steps.append(
                ContentAcquisitionStep(
                    step_id=f"{task_input.task_id}-read-card",
                    step_type="READ_CARD_CONTEXT",
                    description="Load card content and related context links before normalization.",
                    status="PLANNED",
                )
            )
        if has_external_web:
            steps.append(
                ContentAcquisitionStep(
                    step_id=f"{task_input.task_id}-read-web",
                    step_type="READ_EXTERNAL_CONTENT",
                    description="Read external web content through a capability layer before building CCOs.",
                    capability_name="READ_WEB_PAGE",
                    status="PLANNED",
                )
            )
        steps.extend(
            [
                ContentAcquisitionStep(
                    step_id=f"{task_input.task_id}-normalize",
                    step_type="NORMALIZE_TO_CCO",
                    description="Normalize resolved materials into canonical content objects.",
                    status="PLANNED",
                ),
                ContentAcquisitionStep(
                    step_id=f"{task_input.task_id}-merge",
                    step_type="MERGE_CONTEXT_BUNDLE",
                    description="Fuse normalized multi-source inputs into a mixed context bundle.",
                    status="PLANNED",
                ),
            ]
        )
        return ContentAcquisitionPlan(
            strategy_key=f"acq-{task_input.task_id}",
            primary_strategy="MIXED_CONTEXT_FUSION",
            required_capabilities=["READ_WEB_PAGE"] if has_external_web else [],
            steps=steps,
            route_summary=route_summary,
            source_plans=source_plans,
            notes=[
                f"Input adapter routed sources as: {', '.join(sorted(routes))}.",
                "Multiple sources are fused into a MIXED_CONTEXT CCO before context compilation.",
            ],
        )

    if has_external_web:
        return ContentAcquisitionPlan(
            strategy_key=f"acq-{task_input.task_id}",
            primary_strategy="WEB_SOURCE_DIGEST",
            required_capabilities=["READ_WEB_PAGE"],
            steps=[
                ContentAcquisitionStep(
                    step_id=f"{task_input.task_id}-resolve-url",
                    step_type="RESOLVE_URL_SOURCE",
                    description="Input adapter resolves URL metadata before external content read.",
                    status="PLANNED",
                ),
                ContentAcquisitionStep(
                    step_id=f"{task_input.task_id}-read-web",
                    step_type="READ_EXTERNAL_CONTENT",
                    description="Read external web content through a capability layer before building CCOs.",
                    capability_name="READ_WEB_PAGE",
                    status="PLANNED",
                ),
                ContentAcquisitionStep(
                    step_id=f"{task_input.task_id}-normalize",
                    step_type="NORMALIZE_TO_CCO",
                    description="Normalize external content into canonical content objects.",
                    status="PLANNED",
                ),
            ],
            route_summary=route_summary,
            source_plans=source_plans,
            notes=[
                f"Input adapter routed sources as: {', '.join(sorted(routes))}.",
                "External web content stays behind the capability layer before entering the harness.",
            ],
        )

    if has_card_context:
        return ContentAcquisitionPlan(
            strategy_key=f"acq-{task_input.task_id}",
            primary_strategy="CARD_CONTEXT_DIGEST",
            required_capabilities=[],
            steps=[
                ContentAcquisitionStep(
                    step_id=f"{task_input.task_id}-read-card",
                    step_type="READ_CARD_CONTEXT",
                    description="Load card content and linked context before normalization.",
                    status="PLANNED",
                ),
                ContentAcquisitionStep(
                    step_id=f"{task_input.task_id}-normalize",
                    step_type="NORMALIZE_TO_CCO",
                    description="Normalize card context into canonical content objects.",
                    status="PLANNED",
                ),
            ],
            route_summary=route_summary,
            source_plans=source_plans,
            notes=[
                f"Input adapter routed sources as: {', '.join(sorted(routes))}.",
                "Card inputs are normalized as CARD_CONTEXT CCOs with related source trace.",
            ],
        )

    steps: list[ContentAcquisitionStep] = [
        ContentAcquisitionStep(
            step_id=f"{task_input.task_id}-collect",
            step_type="COLLECT_WORKSPACE_MATERIALS",
            description="Collect workspace materials and saved artifacts within the current source scope.",
            status="PLANNED",
        ),
        ContentAcquisitionStep(
            step_id=f"{task_input.task_id}-normalize",
            step_type="NORMALIZE_TO_CCO",
            description="Normalize resolved materials into canonical content objects.",
            status="PLANNED",
        ),
    ]

    return ContentAcquisitionPlan(
        strategy_key=f"acq-{task_input.task_id}",
        primary_strategy="WORKSPACE_SOURCE_DIGEST",
        required_capabilities=[],
        steps=steps,
        route_summary=route_summary,
        source_plans=source_plans,
        notes=[
            f"Input adapter routed sources as: {', '.join(sorted(routes))}.",
            "Material scope is bound to the current workspace source pool.",
            "System artifacts participate only after they are saved as workspace materials.",
        ],
    )


def build_canonical_content_objects(
    task_input: ArtifactTaskInput,
    action_key: str | None = None,
) -> list[CanonicalContentObject]:
    objects: list[CanonicalContentObject] = []
    effective_action_key = action_key.strip().upper() if action_key else _resolve_effective_action_key(task_input)
    descriptors = _adapt_source_inputs(task_input, effective_action_key)
    for index, descriptor in enumerate(descriptors, start=1):
        source = descriptor["source"]
        plain_text = descriptor["plain_text"]
        metadata = descriptor["metadata"]
        acquisition_payload = _resolve_source_acquisition_payload(
            task_id=task_input.task_id,
            source_id=source.source_id,
            planned_operations=descriptor["source_plan"]["planned_operations"],
        )
        if acquisition_payload:
            derived_plain_text, derived_metadata = _build_text_from_acquisition_payload(
                acquisition_payload
            )
            if derived_plain_text:
                plain_text = derived_plain_text
                metadata = {
                    **metadata,
                    **derived_metadata,
                    "acquisition_payload_applied": True,
                }
        if descriptor["normalization_target_kind"] == "TRANSCRIPT":
            objects.append(
                CanonicalContentObject(
                    cco_id=f"cco-{task_input.task_id}-{index}-transcript",
                    kind="TRANSCRIPT",
                    title=source.title,
                    plain_text=plain_text,
                    segments=[
                        CanonicalContentSegment(
                            segment_id=f"cco-{task_input.task_id}-{index}-transcript-seg-1",
                            text=plain_text,
                            segment_role="TRANSCRIPT",
                        )
                    ],
                    metadata={
                        **metadata,
                        "derived_from": "transcript_pipeline",
                    },
                    source_trace=[
                        f"workspace:{task_input.workspace_id}",
                        f"source:{source.source_id}",
                        "derived_from:transcript_pipeline",
                    ],
                )
            )
            kind = "EXTERNAL_CONTENT_SNAPSHOT"
        else:
            kind = descriptor["cco_kind"]
        objects.append(
            CanonicalContentObject(
                cco_id=f"cco-{task_input.task_id}-{index}",
                kind=kind,
                title=source.title,
                plain_text=plain_text,
                segments=[
                    CanonicalContentSegment(
                        segment_id=f"cco-{task_input.task_id}-{index}-seg-1",
                        text=plain_text,
                        segment_role=kind,
                    )
                ],
                metadata=metadata,
                source_trace=[
                    f"workspace:{task_input.workspace_id}",
                    f"source:{source.source_id}",
                    f"source_type:{kind}",
                ],
            )
        )
    if len(descriptors) > 1:
        source_kinds = [descriptor["cco_kind"] for descriptor in descriptors]
        source_trace = [
            trace
            for descriptor in descriptors
            for trace in (
                f"workspace:{task_input.workspace_id}",
                f"source:{descriptor['source'].source_id}",
                f"source_type:{descriptor['cco_kind']}",
            )
        ]
        objects.append(
            CanonicalContentObject(
                cco_id=f"cco-{task_input.task_id}-mixed-context",
                kind="MIXED_CONTEXT",
                title="Mixed Context Bundle",
                plain_text="\n".join(descriptor["plain_text"] for descriptor in descriptors),
                segments=[
                    CanonicalContentSegment(
                        segment_id=f"cco-{task_input.task_id}-mixed-context-seg-{index}",
                        text=descriptor["plain_text"],
                        segment_role=descriptor["cco_kind"],
                    )
                    for index, descriptor in enumerate(descriptors, start=1)
                ],
                metadata={
                    "adapter_route": "MIXED_CONTEXT_FUSION",
                    "source_count": len(descriptors),
                    "source_kinds": source_kinds,
                },
                source_trace=source_trace,
            )
        )
    return objects


def build_context_pack(
    task_input: ArtifactTaskInput,
    plan: ArtifactExecutionPlan,
    canonical_content_objects: list[CanonicalContentObject],
) -> ContextPack:
    style_profile = resolve_style_profile(plan.style_profile_key)
    segments = [
        segment.text
        for cco in canonical_content_objects
        for segment in cco.segments[:1]
    ]
    return ContextPack(
        task_brief=plan.execution_spec.generation_brief or task_input.input_payload.generation_brief or plan.action_display_name,
        main_content="\n".join(segments[:3]),
        segments=segments,
        execution_spec=plan.execution_spec.model_dump(mode="json"),
        style_profile=style_profile.model_dump(mode="json"),
        prompt_recipe={
            "recipe_id": plan.prompt_recipe.recipe_id,
            "recipe_name": plan.prompt_recipe.recipe_name,
            "generation_mode": plan.prompt_recipe.generation_mode,
            "system_intent": plan.prompt_recipe.system_intent,
            "section_guidance": plan.prompt_recipe.section_guidance,
            "node_guidance": plan.prompt_recipe.node_guidance,
            "citation_policy": plan.prompt_recipe.citation_policy,
        },
        user_preference={
            "action_resolution": plan.action_resolution.model_dump(mode="json"),
            "skill_key": plan.skill_key,
            "goal": plan.execution_spec.goal,
            "focus_points": plan.execution_spec.focus_points,
            "style_profile_key": plan.style_profile_key,
            "style_profile_name": plan.style_profile_name,
            "notes": plan.notes,
        },
        output_contract=plan.output_contract,
        source_trace=[
            trace
            for cco in canonical_content_objects
            for trace in cco.source_trace
        ],
    )


def build_acquisition_receipt(
    task_input: ArtifactTaskInput,
    plan: ArtifactExecutionPlan,
    capability_resolution: ArtifactCapabilityResolution,
    canonical_content_objects: list[CanonicalContentObject],
    wait_status: str = "",
) -> AcquisitionReceipt:
    descriptors = _adapt_source_inputs(task_input, plan.action_key)
    overall_status = wait_status or "COMPLETED"
    cco_index = _index_ccos_by_source(canonical_content_objects)
    cco_stats = _index_cco_stats_by_source(canonical_content_objects)
    provider_bindings = {
        binding["capability_name"]: binding
        for binding in capability_resolution.provider_bindings
    }
    resolved_bindings = {
        binding["capability_name"]: binding
        for binding in capability_resolution.resolved_bindings
    }
    source_receipts: list[AcquisitionSourceReceipt] = []

    for descriptor in descriptors:
        source_plan = descriptor["source_plan"]
        required_capabilities = source_plan["required_capabilities"]
        wait_capability = next(
            (
                capability_name
                for capability_name in required_capabilities
                if _capability_wait_status(
                    capability_name,
                    capability_resolution=capability_resolution,
                    provider_bindings=provider_bindings,
                    wait_status=wait_status,
                )
                != "COMPLETED"
            ),
            "",
        )
        operations: list[AcquisitionOperationReceipt] = []
        downstream_wait = False

        for operation_key in source_plan["planned_operations"]:
            capability_name = _resolve_operation_capability(operation_key)
            request_id = _build_operation_request_id(
                task_id=task_input.task_id,
                source_id=source_plan["source_id"],
                operation_key=operation_key,
            )
            resolved_binding = resolved_bindings.get(capability_name, {})
            runtime_payload = get_acquisition_result_payload(request_id) if capability_name else None
            operation_status = "COMPLETED"
            if downstream_wait:
                operation_status = "PLANNED"
            elif capability_name:
                operation_status = _capability_wait_status(
                    capability_name,
                    capability_resolution=capability_resolution,
                    provider_bindings=provider_bindings,
                    wait_status=wait_status,
                )
                if (
                    operation_status == "COMPLETED"
                    and _requires_async_provider_execution(
                        task_input=task_input,
                        capability_name=capability_name,
                        resolved_binding=resolved_binding,
                        runtime_payload=runtime_payload,
                    )
                ):
                    operation_status = "WAITING_FOR_PROVIDER"
                if operation_status != "COMPLETED":
                    downstream_wait = True

            completed_at = _resolve_operation_completed_at(operation_status)
            callback_status = _resolve_operation_callback_status(
                capability_name=capability_name,
                operation_status=operation_status,
            )
            operations.append(
                AcquisitionOperationReceipt(
                    operation_key=operation_key,
                    status=operation_status,
                    request_id=request_id,
                    input_locator=_resolve_input_locator(descriptor),
                    requested_at=_utc_now(),
                    completed_at=completed_at,
                    provider_receipt_id=_resolve_operation_provider_receipt_id(
                        capability_name=capability_name,
                        operation_status=operation_status,
                        request_id=request_id,
                    ),
                    provider_job_id=_resolve_operation_provider_job_id(
                        capability_name=capability_name,
                        operation_status=operation_status,
                        request_id=request_id,
                        server_id=str(resolved_binding.get("server_id", "")),
                    ),
                    adapter_callback_token=_resolve_operation_adapter_callback_token(
                        capability_name=capability_name,
                        operation_status=operation_status,
                        request_id=request_id,
                    ),
                    delivery_id=_resolve_operation_delivery_id(
                        capability_name=capability_name,
                        operation_status=operation_status,
                        request_id=request_id,
                    ),
                    callback_token=_resolve_operation_callback_token(
                        capability_name=capability_name,
                        operation_status=operation_status,
                        request_id=request_id,
                    ),
                    provider_job_status=resolve_provider_job_status(
                        capability_name=capability_name,
                        operation_status=operation_status,
                        callback_status=callback_status,
                    ),
                    callback_status=callback_status,
                    callback_received_at=_resolve_operation_callback_received_at(
                        capability_name=capability_name,
                        operation_status=operation_status,
                        completed_at=completed_at,
                    ),
                    capability_name=capability_name,
                    provider_id=str(resolved_binding.get("provider_id", "")),
                    server_id=str(resolved_binding.get("server_id", "")),
                    tool_name=str(resolved_binding.get("tool_name", "")),
                    selection_reason=str(resolved_binding.get("selection_reason", "")),
                    discovery_status=str(
                        provider_bindings.get(capability_name, {}).get("discovery_status", "")
                    ),
                    last_discovered_at=str(
                        provider_bindings.get(capability_name, {}).get("last_discovered_at", "")
                    ),
                    provider_status=str(
                        provider_bindings.get(capability_name, {}).get("provider_status", "")
                    ),
                    health_status=str(
                        provider_bindings.get(capability_name, {}).get("health_status", "")
                    ),
                    last_checked_at=str(
                        provider_bindings.get(capability_name, {}).get("last_checked_at", "")
                    ),
                    approval_status=str(
                        provider_bindings.get(capability_name, {}).get("approval_status", "")
                    ),
                    output_kind=source_plan["normalization_target_kind"]
                    if operation_key == "NORMALIZE_TO_CCO"
                    else "",
                    result_locator=_resolve_operation_result_locator(
                        operation_key=operation_key,
                        operation_status=operation_status,
                        request_id=request_id,
                        server_id=str(resolved_binding.get("server_id", "")),
                        tool_name=str(resolved_binding.get("tool_name", "")),
                        produced_cco_ids=cco_index.get(source_plan["source_id"], []),
                    ),
                    output_summary=_resolve_operation_output_summary(
                        operation_key=operation_key,
                        operation_status=operation_status,
                        descriptor=descriptor,
                        normalization_target_kind=source_plan["normalization_target_kind"],
                    ),
                    content_digest=_resolve_operation_content_digest(
                        operation_status=operation_status,
                        descriptor=descriptor,
                        normalization_target_kind=source_plan["normalization_target_kind"],
                    ),
                    payload_char_count=_resolve_operation_payload_char_count(
                        operation_status=operation_status,
                        descriptor=descriptor,
                    ),
                    segment_count=_resolve_operation_segment_count(
                        operation_key=operation_key,
                        operation_status=operation_status,
                        descriptor=descriptor,
                        cco_stats=cco_stats.get(source_plan["source_id"], {}),
                    ),
                    source_ref_count=_resolve_operation_source_ref_count(
                        operation_status=operation_status,
                        descriptor=descriptor,
                    ),
                    error_code=_resolve_operation_error_code(operation_status),
                    error_message=_resolve_operation_error_message(
                        capability_name=capability_name,
                        wait_capability=wait_capability,
                        operation_status=operation_status,
                    ),
                    retryable=_resolve_operation_retryable(operation_status),
                    provider_attempts=_resolve_provider_attempts(
                        capability_name=capability_name,
                        descriptor=descriptor,
                        plan=plan,
                        operation_status=operation_status,
                        selected_provider_id=str(resolved_binding.get("provider_id", "")),
                        selected_server_id=str(resolved_binding.get("server_id", "")),
                        selected_tool_name=str(resolved_binding.get("tool_name", "")),
                    ),
                    notes=_build_operation_receipt_notes(
                        capability_name=capability_name,
                        wait_capability=wait_capability,
                        operation_status=operation_status,
                    ),
                )
            )

        source_status = (
            "COMPLETED"
            if all(operation.status == "COMPLETED" for operation in operations)
            else wait_status or "IN_PROGRESS"
        )
        if any(operation.status == "WAITING_FOR_PROVIDER" for operation in operations):
            source_status = "WAITING_FOR_PROVIDER"
        source_receipts.append(
            AcquisitionSourceReceipt(
                source_id=source_plan["source_id"],
                title=source_plan["title"],
                source_platform=source_plan["source_platform"],
                adapter_route=source_plan["adapter_route"],
                normalization_target_kind=source_plan["normalization_target_kind"],
                status=source_status,
                operations=operations,
                produced_cco_ids=cco_index.get(source_plan["source_id"], []),
                notes=list(source_plan["notes"]),
            )
        )

    if not wait_status and any(
        operation.status == "WAITING_FOR_PROVIDER"
        for source_receipt in source_receipts
        for operation in source_receipt.operations
    ):
        overall_status = "WAITING_FOR_PROVIDER"

    return AcquisitionReceipt(
        receipt_id=f"acq-receipt-{task_input.task_id}",
        status=overall_status,
        execution_mode=(
            "CUSTOM_MCP_ASYNC_RUNTIME"
            if overall_status == "WAITING_FOR_PROVIDER"
            else "SIMULATED_RUNTIME"
        ),
        primary_strategy=plan.content_acquisition_plan.primary_strategy,
        route_summary=dict(plan.content_acquisition_plan.route_summary),
        source_receipts=source_receipts,
        executed_capabilities=list(
            dict.fromkeys(capability_resolution.lazy_loaded_capabilities)
        ),
        wait_reason={
            "status": wait_status,
            "capabilities": list(capability_resolution.unavailable_capabilities),
        }
        if wait_status == "WAITING_FOR_CAPABILITY"
        else {
            "status": wait_status,
            "capabilities": [
                binding["capability_name"]
                for binding in capability_resolution.provider_bindings
                if binding.get("approval_status") not in {"APPROVED", "NOT_REQUIRED"}
                and binding["capability_name"] in capability_resolution.lazy_loaded_capabilities
            ],
        }
        if wait_status == "WAITING_FOR_APPROVAL"
        else {
            "status": overall_status,
            "capabilities": [
                operation.capability_name
                for source_receipt in source_receipts
                for operation in source_receipt.operations
                if operation.status == "WAITING_FOR_PROVIDER" and operation.capability_name
            ],
            "request_ids": [
                operation.request_id
                for source_receipt in source_receipts
                for operation in source_receipt.operations
                if operation.status == "WAITING_FOR_PROVIDER"
            ],
        }
        if overall_status == "WAITING_FOR_PROVIDER"
        else {},
        notes=list(plan.content_acquisition_plan.notes),
    )


def describe_input_adapter_routes(task_input: ArtifactTaskInput) -> list[str]:
    action_key = _resolve_effective_action_key(task_input)
    return [descriptor["adapter_route"] for descriptor in _adapt_source_inputs(task_input, action_key)]


def _adapt_source_inputs(task_input: ArtifactTaskInput, action_key: str) -> list[dict[str, object]]:
    descriptors: list[dict[str, object]] = []
    for source in _resolve_effective_sources(task_input):
        normalized_source_type = source.source_type.strip().upper() or "DOCUMENT_TEXT"
        adapter_route = _resolve_adapter_route(normalized_source_type, source.source_uri)
        cco_kind = _resolve_cco_kind(normalized_source_type, adapter_route)
        source_platform = _resolve_source_platform(
            normalized_source_type,
            adapter_route,
            source.source_uri,
            source.source_metadata,
        )
        planned_operations = _resolve_planned_operations(adapter_route)
        required_capabilities = _resolve_source_required_capabilities(adapter_route)
        normalization_target_kind = _resolve_normalization_target_kind(
            action_key,
            normalized_source_type,
            adapter_route,
        )
        selected_windows = [
            window for window in source.material_windows
            if isinstance(window, dict) and str(window.get("content", "")).strip()
        ]
        plain_text = (
            "\n\n".join(str(window["content"]) for window in selected_windows)
            if selected_windows else source.sample_text.strip() or source.summary.strip() or source.title
        )
        metadata: dict[str, object] = {
            "original_source_type": normalized_source_type,
            "adapter_route": adapter_route,
            "source_platform": source_platform,
            "source_uri": source.source_uri,
            "related_source_ids": list(source.related_source_ids),
            "selected_source_window_ids": [
                str(window.get("window_id", "")) for window in selected_windows
            ],
            "selected_source_window_locations": [
                str(window.get("location_info", "")) for window in selected_windows
            ],
            "material_gap": source.material_gap,
            **source.source_metadata,
        }
        descriptors.append(
            {
                "source": source,
                "plain_text": plain_text,
                "adapter_route": adapter_route,
                "cco_kind": cco_kind,
                "normalization_target_kind": normalization_target_kind,
                "metadata": metadata,
                "source_plan": {
                    "source_id": source.source_id,
                    "title": source.title,
                    "source_type": normalized_source_type,
                    "source_platform": source_platform,
                    "adapter_route": adapter_route,
                    "normalization_target_kind": normalization_target_kind,
                    "planned_operations": planned_operations,
                    "required_capabilities": required_capabilities,
                    "related_source_ids": list(source.related_source_ids),
                    "notes": _build_source_plan_notes(
                        source_platform=source_platform,
                        adapter_route=adapter_route,
                        normalization_target_kind=normalization_target_kind,
                    ),
                },
            }
        )
    return descriptors


def _resolve_effective_action_key(task_input: ArtifactTaskInput) -> str:
    return resolve_action_compatibility(task_input).effective_action_key


def _resolve_effective_sources(task_input: ArtifactTaskInput) -> list[SourceScopeItem]:
    sources = list(task_input.source_scope)
    existing_uris = {
        source.source_uri.strip().lower()
        for source in sources
        if source.source_uri.strip()
    }
    sources.extend(_build_virtual_input_sources(task_input, existing_uris))
    return sources


def _build_virtual_input_sources(
    task_input: ArtifactTaskInput,
    existing_uris: set[str],
) -> list[SourceScopeItem]:
    skill_definition = try_resolve_artifact_skill_definition(task_input.input_payload.skill_key)
    inputs = task_input.input_payload.inputs
    candidate_keys: list[str] = []
    if skill_definition is not None:
        candidate_keys.extend(skill_definition.url_input_keys)
    candidate_keys.extend(
        key
        for key in inputs
        if key not in candidate_keys and (key.lower() == "url" or key.lower().endswith("_url"))
    )
    virtual_sources: list[SourceScopeItem] = []
    seen_uris = set(existing_uris)
    next_index = 1
    for key in candidate_keys:
        url_value = str(inputs.get(key, "")).strip()
        normalized_url = url_value.lower()
        if not url_value or normalized_url in seen_uris:
            continue
        seen_uris.add(normalized_url)
        virtual_sources.append(
            SourceScopeItem(
                source_id=f"input-url-{next_index}",
                title=_build_virtual_input_source_title(url_value, skill_definition.display_name if skill_definition else ""),
                summary=f"User supplied URL input via {key}.",
                source_type="URL",
                source_uri=url_value,
                source_metadata={
                    "virtual_source": True,
                    "source_origin": "INPUT_PAYLOAD",
                    "input_key": key,
                },
            )
        )
        next_index += 1
    return virtual_sources


def _build_virtual_input_source_title(url_value: str, skill_display_name: str) -> str:
    if skill_display_name:
        return f"{skill_display_name} Input URL"
    if "bilibili.com" in url_value.lower() or "b23.tv" in url_value.lower():
        return "Bilibili Input URL"
    return "Skill Input URL"


def _resolve_adapter_route(source_type: str, source_uri: str) -> str:
    normalized_uri = source_uri.strip().lower()
    if source_type in {"VIDEO_FILE"}:
        return "VIDEO_FILE"
    if source_type in {"AUDIO_FILE"}:
        return "AUDIO_FILE"
    if source_type in {"CARD", "USER_CARD"}:
        return "CARD_CONTEXT"
    if source_type in {"TEXT", "RAW_TEXT"}:
        return "RAW_TEXT"
    if source_type in {"URL", "WEB_PAGE"}:
        if "bilibili.com" in normalized_uri or "b23.tv" in normalized_uri:
            return "VIDEO_URL"
        return "WEB_URL"
    return "DOCUMENT_TEXT"


def _resolve_cco_kind(source_type: str, adapter_route: str) -> str:
    if adapter_route == "CARD_CONTEXT":
        return "CARD_CONTEXT"
    if adapter_route in {"WEB_URL", "VIDEO_URL"}:
        return "WEB_PAGE"
    if adapter_route == "RAW_TEXT":
        return "RAW_TEXT"
    if source_type in {"TEXT", "RAW_TEXT"}:
        return "RAW_TEXT"
    return "DOCUMENT_TEXT"


def _resolve_source_platform(
    source_type: str,
    adapter_route: str,
    source_uri: str,
    source_metadata: dict[str, object],
) -> str:
    normalized_platform = str(source_metadata.get("platform", "")).strip().upper()
    if normalized_platform:
        return normalized_platform
    normalized_uri = source_uri.strip().lower()
    if "bilibili.com" in normalized_uri or "b23.tv" in normalized_uri:
        return "BILIBILI"
    if adapter_route == "WEB_URL":
        return "WEB"
    if adapter_route == "VIDEO_URL":
        return "VIDEO_WEB"
    if adapter_route in {"VIDEO_FILE", "AUDIO_FILE"}:
        return "LOCAL_FILE"
    if adapter_route == "CARD_CONTEXT":
        return "WORKSPACE_CARD"
    if adapter_route == "RAW_TEXT":
        return "RAW_TEXT"
    if source_type in {"DOCUMENT_TEXT", "DOCUMENT", "PDF", "MARKDOWN"}:
        return "WORKSPACE_DOC"
    return "WORKSPACE_DOC"


def _resolve_planned_operations(adapter_route: str) -> list[str]:
    route_to_operations = {
        "VIDEO_URL": ["RESOLVE_URL_SOURCE", "EXTRACT_TRANSCRIPT", "NORMALIZE_TO_CCO"],
        "VIDEO_FILE": [
            "RESOLVE_VIDEO_SOURCE",
            "EXTRACT_AUDIO_TRACK",
            "TRANSCRIBE_AUDIO",
            "NORMALIZE_TO_CCO",
        ],
        "AUDIO_FILE": ["RESOLVE_AUDIO_SOURCE", "TRANSCRIBE_AUDIO", "NORMALIZE_TO_CCO"],
        "WEB_URL": ["RESOLVE_URL_SOURCE", "READ_EXTERNAL_CONTENT", "NORMALIZE_TO_CCO"],
        "CARD_CONTEXT": ["READ_CARD_CONTEXT", "NORMALIZE_TO_CCO"],
        "RAW_TEXT": ["NORMALIZE_TO_CCO"],
        "DOCUMENT_TEXT": ["COLLECT_WORKSPACE_MATERIALS", "NORMALIZE_TO_CCO"],
    }
    return list(route_to_operations.get(adapter_route, ["COLLECT_WORKSPACE_MATERIALS", "NORMALIZE_TO_CCO"]))


def _resolve_source_required_capabilities(adapter_route: str) -> list[str]:
    route_to_capabilities = {
        "VIDEO_URL": ["EXTRACT_TRANSCRIPT"],
        "VIDEO_FILE": ["TRANSCRIBE_AUDIO"],
        "AUDIO_FILE": ["TRANSCRIBE_AUDIO"],
        "WEB_URL": ["READ_WEB_PAGE"],
    }
    return list(route_to_capabilities.get(adapter_route, []))


def _resolve_normalization_target_kind(
    action_key: str,
    source_type: str,
    adapter_route: str,
) -> str:
    if action_key in {"VIDEO_SUMMARY", "AUDIO_MINUTES", "COURSE_NOTES"} or adapter_route in {
        "VIDEO_FILE",
        "AUDIO_FILE",
        "VIDEO_URL",
    }:
        return "TRANSCRIPT"
    return _resolve_cco_kind(source_type, adapter_route)


def _build_source_plan_notes(
    *,
    source_platform: str,
    adapter_route: str,
    normalization_target_kind: str,
) -> list[str]:
    return [
        f"source platform inferred as {source_platform}",
        f"adapter route resolved as {adapter_route}",
        f"normalization target kind is {normalization_target_kind}",
    ]


def _resolve_operation_capability(operation_key: str) -> str:
    return {
        "READ_EXTERNAL_CONTENT": "READ_WEB_PAGE",
        "EXTRACT_TRANSCRIPT": "EXTRACT_TRANSCRIPT",
        "TRANSCRIBE_AUDIO": "TRANSCRIBE_AUDIO",
    }.get(operation_key, "")


def _requires_async_provider_execution(
    *,
    task_input: ArtifactTaskInput,
    capability_name: str,
    resolved_binding: dict[str, object],
    runtime_payload: dict[str, object] | None,
) -> bool:
    if not capability_name or runtime_payload:
        return False
    server_id = str(resolved_binding.get("server_id", "")).strip().lower()
    if server_id.startswith("custom-"):
        return True
    return _is_bilibili_pdf_async_provider(
        task_input=task_input,
        capability_name=capability_name,
        server_id=server_id,
    )


def _is_bilibili_pdf_async_provider(
    *,
    task_input: ArtifactTaskInput,
    capability_name: str,
    server_id: str,
) -> bool:
    skill_key = task_input.input_payload.skill_key.strip().lower()
    if skill_key != "bilibili_course_note_pdf":
        return False
    if capability_name == "EXTRACT_TRANSCRIPT" and server_id == "builtin-bilibili-mcp":
        return True
    if capability_name == "TRANSCRIBE_AUDIO" and server_id in {"builtin-asr", "builtin-media"}:
        return True
    return False


def _resolve_source_acquisition_payload(
    *,
    task_id: str,
    source_id: str,
    planned_operations: list[str],
) -> dict[str, object]:
    for operation_key in planned_operations:
        capability_name = _resolve_operation_capability(operation_key)
        if not capability_name:
            continue
        request_id = _build_operation_request_id(
            task_id=task_id,
            source_id=source_id,
            operation_key=operation_key,
        )
        payload = get_acquisition_result_payload(request_id)
        if payload:
            return payload
    return {}


def _build_text_from_acquisition_payload(
    payload: dict[str, object],
) -> tuple[str, dict[str, object]]:
    subtitle_preview = payload.get("subtitle_preview")
    if isinstance(subtitle_preview, list):
        preview_lines = [str(line).strip() for line in subtitle_preview if str(line).strip()]
        if preview_lines:
            return (
                "\n".join(preview_lines),
                {
                    "acquisition_mode": str(payload.get("acquisition_mode", "")),
                    "selected_subtitle_path": str(payload.get("selected_subtitle_path", "")),
                    "subtitle_file_count": len(payload.get("subtitle_files") or []),
                },
            )
    transcription = payload.get("transcription") or {}
    artifacts = transcription.get("artifacts") or {}
    for key in ("txt_files", "srt_files"):
        values = artifacts.get(key) or []
        if not values:
            continue
        text = _read_text_file(values[0])
        if text:
            return (
                text,
                {
                    "acquisition_mode": str(payload.get("acquisition_mode", "")),
                    "transcription_execution_mode": str(transcription.get("execution_mode", "")),
                    "transcript_artifact_path": str(values[0]),
                },
            )
    selected_subtitle_path = str(payload.get("selected_subtitle_path", "")).strip()
    if selected_subtitle_path:
        text = _read_text_file(selected_subtitle_path)
        if text:
            return (
                text,
                {
                    "acquisition_mode": str(payload.get("acquisition_mode", "")),
                    "selected_subtitle_path": selected_subtitle_path,
                },
            )
    return "", {}


def _read_text_file(path_value: object) -> str:
    path = str(path_value).strip()
    if not path:
        return ""
    try:
        return read_text_file_limited(path)
    except (OSError, ContentSizeLimitError):
        return ""


def _capability_wait_status(
    capability_name: str,
    *,
    capability_resolution: ArtifactCapabilityResolution,
    provider_bindings: dict[str, dict[str, object]],
    wait_status: str,
) -> str:
    if not capability_name:
        return "COMPLETED"
    if wait_status == "WAITING_FOR_CAPABILITY":
        if capability_name in capability_resolution.unavailable_capabilities:
            return "WAITING_FOR_CAPABILITY"
    if wait_status == "WAITING_FOR_APPROVAL":
        provider_binding = provider_bindings.get(capability_name, {})
        if str(provider_binding.get("approval_status", "")) != "APPROVED":
            return "WAITING_FOR_APPROVAL"
    return "COMPLETED"


def _build_operation_receipt_notes(
    *,
    capability_name: str,
    wait_capability: str,
    operation_status: str,
) -> list[str]:
    if not capability_name:
        return []
    if operation_status == "WAITING_FOR_CAPABILITY":
        return [f"waiting on unavailable capability: {wait_capability or capability_name}"]
    if operation_status == "WAITING_FOR_APPROVAL":
        return [f"waiting on approval for capability: {wait_capability or capability_name}"]
    return [f"capability executed through: {capability_name}"]


def _index_ccos_by_source(
    canonical_content_objects: list[CanonicalContentObject],
) -> dict[str, list[str]]:
    cco_index: dict[str, list[str]] = {}
    for cco in canonical_content_objects:
        for trace in cco.source_trace:
            if not trace.startswith("source:"):
                continue
            source_id = trace.split(":", 1)[1]
            cco_index.setdefault(source_id, [])
            if cco.cco_id not in cco_index[source_id]:
                cco_index[source_id].append(cco.cco_id)
    return cco_index


def _index_cco_stats_by_source(
    canonical_content_objects: list[CanonicalContentObject],
) -> dict[str, dict[str, int]]:
    cco_index: dict[str, dict[str, int]] = {}
    for cco in canonical_content_objects:
        for trace in cco.source_trace:
            if not trace.startswith("source:"):
                continue
            source_id = trace.split(":", 1)[1]
            cco_index.setdefault(source_id, {"segment_count": 0})
            cco_index[source_id]["segment_count"] += len(cco.segments)
    return cco_index


def _build_operation_request_id(
    *,
    task_id: str,
    source_id: str,
    operation_key: str,
) -> str:
    return f"fetch-{task_id}-{source_id}-{operation_key.lower()}"


def _resolve_input_locator(descriptor: dict[str, object]) -> str:
    metadata = descriptor["metadata"]
    source = descriptor["source"]
    source_uri = str(metadata.get("source_uri", "")).strip()
    if source_uri:
        return source_uri
    return f"workspace://{source.source_id}"


def _resolve_operation_completed_at(operation_status: str) -> str:
    if operation_status != "COMPLETED":
        return ""
    return _utc_now()


def _resolve_operation_provider_receipt_id(
    *,
    capability_name: str,
    operation_status: str,
    request_id: str,
) -> str:
    if not capability_name or operation_status not in {"COMPLETED", "WAITING_FOR_PROVIDER"}:
        return ""
    return f"provider-receipt-{request_id}"


def _resolve_operation_provider_job_id(
    *,
    capability_name: str,
    operation_status: str,
    request_id: str,
    server_id: str,
) -> str:
    if not capability_name or operation_status not in {"COMPLETED", "WAITING_FOR_PROVIDER"}:
        return ""
    normalized_server_id = server_id or "unknown-provider"
    return f"provider-job-{normalized_server_id}-{request_id}"


def _resolve_operation_adapter_callback_token(
    *,
    capability_name: str,
    operation_status: str,
    request_id: str,
) -> str:
    if not capability_name or operation_status not in {"COMPLETED", "WAITING_FOR_PROVIDER"}:
        return ""
    return f"adapter-callback-{request_id}"


def _resolve_operation_delivery_id(
    *,
    capability_name: str,
    operation_status: str,
    request_id: str,
) -> str:
    if not capability_name or operation_status != "WAITING_FOR_PROVIDER":
        return ""
    return f"acq-delivery-{request_id}-1"


def _resolve_operation_callback_token(
    *,
    capability_name: str,
    operation_status: str,
    request_id: str,
) -> str:
    if not capability_name or operation_status != "WAITING_FOR_PROVIDER":
        return ""
    return f"acq-callback-token-{request_id}-1"


def _resolve_operation_callback_status(
    *,
    capability_name: str,
    operation_status: str,
) -> str:
    if not capability_name:
        return "PENDING_UPSTREAM" if operation_status == "PLANNED" else "NOT_REQUIRED"
    if operation_status == "COMPLETED":
        return "ACKNOWLEDGED"
    if operation_status == "WAITING_FOR_PROVIDER":
        return "WAITING_FOR_PROVIDER"
    return operation_status


def _resolve_operation_callback_received_at(
    *,
    capability_name: str,
    operation_status: str,
    completed_at: str,
) -> str:
    if not capability_name or operation_status != "COMPLETED":
        return ""
    return completed_at


def _resolve_operation_result_locator(
    *,
    operation_key: str,
    operation_status: str,
    request_id: str,
    server_id: str,
    tool_name: str,
    produced_cco_ids: list[str],
) -> str:
    if operation_status != "COMPLETED":
        return ""
    if operation_key == "NORMALIZE_TO_CCO" and produced_cco_ids:
        return f"cco://{produced_cco_ids[0]}"
    if server_id and tool_name:
        return f"provider://{server_id}/{tool_name}/{request_id}"
    return ""


def _resolve_operation_output_summary(
    *,
    operation_key: str,
    operation_status: str,
    descriptor: dict[str, object],
    normalization_target_kind: str,
) -> str:
    if operation_status != "COMPLETED":
        return ""
    if operation_key == "NORMALIZE_TO_CCO":
        return f"Normalized source into {normalization_target_kind} content object."
    plain_text = str(descriptor["plain_text"]).strip()
    return plain_text[:120]


def _resolve_operation_content_digest(
    *,
    operation_status: str,
    descriptor: dict[str, object],
    normalization_target_kind: str,
) -> str:
    if operation_status != "COMPLETED":
        return ""
    plain_text = str(descriptor["plain_text"]).strip()
    digest_input = f"{normalization_target_kind}:{plain_text}".encode("utf-8")
    return hashlib.sha256(digest_input).hexdigest()[:12]


def _resolve_operation_payload_char_count(
    *,
    operation_status: str,
    descriptor: dict[str, object],
) -> int:
    if operation_status != "COMPLETED":
        return 0
    return len(str(descriptor["plain_text"]).strip())


def _resolve_operation_segment_count(
    *,
    operation_key: str,
    operation_status: str,
    descriptor: dict[str, object],
    cco_stats: dict[str, int],
) -> int:
    if operation_status != "COMPLETED":
        return 0
    if operation_key == "NORMALIZE_TO_CCO":
        segment_count = int(cco_stats.get("segment_count", 0))
        if segment_count:
            return segment_count
    return 1 if str(descriptor["plain_text"]).strip() else 0


def _resolve_operation_source_ref_count(
    *,
    operation_status: str,
    descriptor: dict[str, object],
) -> int:
    if operation_status != "COMPLETED":
        return 0
    metadata = descriptor["metadata"]
    return 1 + len(list(metadata.get("related_source_ids", [])))


def _resolve_operation_error_code(operation_status: str) -> str:
    return {
        "WAITING_FOR_CAPABILITY": "CAPABILITY_UNAVAILABLE",
        "WAITING_FOR_APPROVAL": "CAPABILITY_APPROVAL_PENDING",
        "WAITING_FOR_PROVIDER": "PROVIDER_EXECUTION_PENDING",
        "PLANNED": "UPSTREAM_OPERATION_PENDING",
    }.get(operation_status, "")


def _resolve_operation_error_message(
    *,
    capability_name: str,
    wait_capability: str,
    operation_status: str,
) -> str:
    if operation_status == "WAITING_FOR_CAPABILITY":
        return f"Capability {wait_capability or capability_name} is currently unavailable."
    if operation_status == "WAITING_FOR_APPROVAL":
        return f"Capability {wait_capability or capability_name} is waiting for approval."
    if operation_status == "WAITING_FOR_PROVIDER":
        return f"Capability {capability_name} is running through an async provider execution."
    if operation_status == "PLANNED":
        return "Waiting for upstream acquisition operation to complete before execution."
    return ""


def _resolve_operation_retryable(operation_status: str) -> bool:
    return operation_status in {
        "WAITING_FOR_CAPABILITY",
        "WAITING_FOR_APPROVAL",
        "WAITING_FOR_PROVIDER",
        "PLANNED",
    }


def _resolve_provider_attempts(
    *,
    capability_name: str,
    descriptor: dict[str, object],
    plan: ArtifactExecutionPlan,
    operation_status: str,
    selected_provider_id: str,
    selected_server_id: str,
    selected_tool_name: str,
) -> list[AcquisitionProviderAttempt]:
    if not capability_name:
        return []
    routes = {str(descriptor["adapter_route"])}
    action_key = plan.action_key
    skill_graph_key = plan.skill_graph_key
    candidates = list_capability_provider_candidates(capability_name)
    matching_candidates = [
        candidate
        for candidate in candidates
        if _provider_candidate_matches_context(candidate, routes, action_key, skill_graph_key)
    ]
    if matching_candidates:
        candidates = matching_candidates
    attempts: list[AcquisitionProviderAttempt] = []
    for candidate in sorted(candidates, key=lambda item: item["preference_rank"], reverse=True):
        provider_id = str(candidate["provider_id"])
        server_id = str(candidate["server_id"])
        tool_name = str(candidate["tool_name"])
        discovery_status = get_capability_provider_discovery_status(
            capability_name,
            provider_id=provider_id,
            tool_name=tool_name,
        )
        provider_status = get_capability_provider_status(
            capability_name,
            provider_id=provider_id,
            tool_name=tool_name,
        )
        health_status = get_capability_provider_health_status(
            capability_name,
            provider_id=provider_id,
            tool_name=tool_name,
        )
        approval_status = get_capability_provider_approval_status(
            capability_name,
            provider_id=provider_id,
            tool_name=tool_name,
        )
        attempts.append(
            AcquisitionProviderAttempt(
                provider_id=provider_id,
                server_id=server_id,
                tool_name=tool_name,
                discovery_status=discovery_status,
                provider_status=provider_status,
                health_status=health_status,
                approval_status=approval_status,
                selection_reason=str(candidate.get("mapping_source", "")),
                preference_rank=int(candidate.get("preference_rank", 0)),
                attempt_result=_resolve_provider_attempt_result(
                    operation_status=operation_status,
                    selected_provider_id=selected_provider_id,
                    selected_server_id=selected_server_id,
                    selected_tool_name=selected_tool_name,
                    candidate=candidate,
                    discovery_status=discovery_status,
                    provider_status=provider_status,
                    health_status=health_status,
                    approval_status=approval_status,
                ),
            )
        )
    return attempts


def _provider_candidate_matches_context(
    candidate: dict[str, object],
    routes: set[str],
    action_key: str,
    skill_graph_key: str,
) -> bool:
    route_match = (
        not routes
        or "*" in candidate["supported_routes"]
        or routes.intersection(set(candidate["supported_routes"]))
    )
    action_match = "*" in candidate["supported_actions"] or action_key in candidate["supported_actions"]
    skill_graph_match = (
        "*" in candidate["supported_skill_graphs"]
        or skill_graph_key in candidate["supported_skill_graphs"]
    )
    return route_match and action_match and skill_graph_match


def _resolve_provider_attempt_result(
    *,
    operation_status: str,
    selected_provider_id: str,
    selected_server_id: str,
    selected_tool_name: str,
    candidate: dict[str, object],
    discovery_status: str,
    provider_status: str,
    health_status: str,
    approval_status: str,
) -> str:
    is_selected = (
        str(candidate["provider_id"]) == selected_provider_id
        and str(candidate["server_id"]) == selected_server_id
        and str(candidate["tool_name"]) == selected_tool_name
    )
    if is_selected:
        if operation_status == "WAITING_FOR_CAPABILITY":
            return "WAITING_FOR_CAPABILITY"
        if operation_status == "WAITING_FOR_APPROVAL":
            return "WAITING_FOR_APPROVAL"
        if operation_status == "WAITING_FOR_PROVIDER":
            return "WAITING_FOR_PROVIDER"
        return "SELECTED"
    if discovery_status not in {"REGISTERED", "DISCOVERED"}:
        return "SKIPPED_UNDISCOVERED"
    if provider_status != "AVAILABLE" or health_status in {"DOWN", "UNAVAILABLE"}:
        return "SKIPPED_UNHEALTHY"
    if approval_status not in {"APPROVED", "NOT_REQUIRED"}:
        return "SKIPPED_APPROVAL_PENDING"
    return "SKIPPED_LOWER_PRIORITY"


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()
