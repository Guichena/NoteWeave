from __future__ import annotations


def resolve_provider_job_status(
    *,
    capability_name: str = "",
    operation_status: str = "",
    callback_status: str = "",
) -> str:
    normalized_capability_name = capability_name.strip().upper()
    normalized_operation_status = operation_status.strip().upper()
    normalized_callback_status = callback_status.strip().upper()

    if not normalized_capability_name:
        if normalized_callback_status == "PENDING_UPSTREAM" or normalized_operation_status == "PLANNED":
            return "PENDING_UPSTREAM"
        return "NOT_REQUIRED"

    if normalized_callback_status == "ACKNOWLEDGED":
        return "SUCCEEDED"
    if normalized_callback_status == "FAILED":
        return "FAILED"
    if normalized_callback_status == "DISPATCHED_TO_PROVIDER":
        return "DISPATCHED"
    if normalized_callback_status == "PROVIDER_OUTCOME_READY":
        return "CALLBACK_PENDING"
    if normalized_callback_status in {
        "WAITING_FOR_APPROVAL",
        "WAITING_FOR_CAPABILITY",
        "WAITING_FOR_PROVIDER",
    }:
        return normalized_callback_status

    if normalized_operation_status in {
        "WAITING_FOR_APPROVAL",
        "WAITING_FOR_CAPABILITY",
        "WAITING_FOR_PROVIDER",
    }:
        return normalized_operation_status
    if normalized_operation_status == "COMPLETED":
        return "SUCCEEDED"
    if normalized_operation_status == "FAILED":
        return "FAILED"

    if normalized_callback_status in {"NOT_REQUIRED", "PENDING_UPSTREAM"}:
        return normalized_callback_status

    return normalized_callback_status or normalized_operation_status
