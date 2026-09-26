package com.noteweave.task;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Maps persisted task progress payloads into the waiting-context contract. */
final class TaskWaitContextAssembler {

    private final ObjectMapper objectMapper;

    TaskWaitContextAssembler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    WaitContextResponse build(String status, String payloadJson) {
        if (payloadJson == null) {
            return new WaitContextResponse(
                    blankIfNull(status), emptyProviderJob(), emptyApprovalRequest(), emptyWaitReason());
        }
        Map<String, Object> payload = readPayloadMap(payloadJson);
        return new WaitContextResponse(
                blankIfNull(status),
                toWaitProviderJob(payload.get("provider_job")),
                toWaitApprovalRequest(payload.get("approval_request")),
                toWaitReason(payload.get("wait_reason"))
        );
    }

    Map<String, WaitContextResponse> buildAll(
            Map<String, String> taskStatuses,
            List<String> waitingTaskIds,
            Map<String, String> payloads
    ) {
        LinkedHashMap<String, WaitContextResponse> contexts = new LinkedHashMap<>();
        payloads.forEach((taskId, payloadJson) -> contexts.put(
                taskId,
                build(taskStatuses.get(taskId), payloadJson)
        ));
        for (String taskId : waitingTaskIds) {
            contexts.putIfAbsent(taskId, new WaitContextResponse(
                    blankIfNull(taskStatuses.get(taskId)),
                    emptyProviderJob(),
                    emptyApprovalRequest(),
                    emptyWaitReason()
            ));
        }
        return contexts;
    }

    private Map<String, Object> readPayloadMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json,
                    new TypeReference<LinkedHashMap<String, Object>>() { });
            if (parsed == null) {
                throw invalidShape("$");
            }
            return parsed;
        } catch (Exception ex) {
            if (ex instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException("TASK_EVENT_PAYLOAD_PARSE_FAILED", "任务事件载荷解析失败");
        }
    }

    private WaitProviderJobResponse toWaitProviderJob(Object value) {
        Map<String, Object> providerJob = nestedMap(value, "provider_job");
        return new WaitProviderJobResponse(
                readText(providerJob, "provider_id"),
                readText(providerJob, "server_id"),
                readText(providerJob, "tool_name"),
                readText(providerJob, "capability_name"),
                readText(providerJob, "operation_key"),
                readText(providerJob, "provider_status"),
                readText(providerJob, "health_status"),
                readText(providerJob, "provider_job_status"),
                readText(providerJob, "status"),
                readText(providerJob, "request_id"),
                readText(providerJob, "provider_job_id"),
                readText(providerJob, "provider_receipt_id"),
                readText(providerJob, "delivery_id"),
                readText(providerJob, "callback_status"),
                resolveDispatchCount(providerJob),
                resolvePreviousFailedDeliveryCount(providerJob),
                resolveHasPreviousFailedDelivery(providerJob),
                readProviderDeliveryAttempts(providerJob.get("provider_delivery_attempts"))
        );
    }

    private WaitApprovalRequestResponse toWaitApprovalRequest(Object value) {
        Map<String, Object> approvalRequest = nestedMap(value, "approval_request");
        return new WaitApprovalRequestResponse(
                readText(approvalRequest, "request_id"),
                readText(approvalRequest, "task_id"),
                readText(approvalRequest, "workspace_id"),
                readText(approvalRequest, "capability_name"),
                readText(approvalRequest, "provider_id"),
                readText(approvalRequest, "server_id"),
                readText(approvalRequest, "tool_name"),
                readText(approvalRequest, "status")
        );
    }

    private WaitReasonResponse toWaitReason(Object value) {
        Map<String, Object> waitReason = nestedMap(value, "wait_reason");
        return new WaitReasonResponse(
                readText(waitReason, "status"),
                readText(waitReason, "provider_id"),
                readText(waitReason, "operation_key"),
                readText(waitReason, "capability_name"),
                readStringList(waitReason.get("unavailable_capabilities"), "wait_reason.unavailable_capabilities"),
                readStringList(waitReason.get("capabilities"), "wait_reason.capabilities"),
                readBlockedOperations(waitReason.get("blocked_operations"), "wait_reason.blocked_operations")
        );
    }

    private List<String> readStringList(Object value, String field) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> items)) {
            throw invalidShape(field);
        }
        List<String> normalized = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw invalidShape(field);
            }
            normalized.add(text.trim());
        }
        return List.copyOf(normalized);
    }

    private List<WaitBlockedOperationResponse> readBlockedOperations(Object value, String field) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> items)) {
            throw invalidShape(field);
        }
        List<WaitBlockedOperationResponse> operations = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            Map<String, Object> record = nestedMap(items.get(index), field + "[" + index + "]");
            operations.add(new WaitBlockedOperationResponse(
                    readText(record, "request_id"),
                    readText(record, "source_id"),
                    readText(record, "operation_key"),
                    readText(record, "capability_name"),
                    readText(record, "callback_status")
            ));
        }
        return List.copyOf(operations);
    }

    private String readText(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private Integer readInteger(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            long candidate = number.longValue();
            if ((number instanceof Float || number instanceof Double)
                    && number.doubleValue() != candidate) {
                throw invalidShape(key);
            }
            if (candidate < Integer.MIN_VALUE || candidate > Integer.MAX_VALUE) {
                throw invalidShape(key);
            }
            return (int) candidate;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                throw invalidShape(key);
            }
        }
        throw invalidShape(key);
    }

    private Integer resolvePreviousFailedDeliveryCount(Map<String, Object> providerJob) {
        Integer explicitCount = readInteger(providerJob, "previous_failed_delivery_count");
        if (explicitCount != null) {
            return explicitCount;
        }
        List<Map<String, Object>> deliveryAttempts = nestedMapList(
                providerJob.get("provider_delivery_attempts"),
                "provider_job.provider_delivery_attempts");
        if (deliveryAttempts.isEmpty()) {
            return null;
        }
        int failedAttempts = (int) deliveryAttempts.stream()
                .map(attempt -> readText(attempt, "ack_status"))
                .filter(status -> "FAILED".equalsIgnoreCase(status))
                .count();
        return failedAttempts;
    }

    private Integer resolveDispatchCount(Map<String, Object> providerJob) {
        Integer explicitCount = readInteger(providerJob, "dispatch_count");
        if (explicitCount != null) {
            return explicitCount;
        }
        List<Map<String, Object>> deliveryAttempts = nestedMapList(
                providerJob.get("provider_delivery_attempts"),
                "provider_job.provider_delivery_attempts");
        if (deliveryAttempts.isEmpty()) {
            return null;
        }
        return deliveryAttempts.stream()
                .map(attempt -> readInteger(attempt, "dispatch_count"))
                .filter(java.util.Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(null);
    }

    private Boolean resolveHasPreviousFailedDelivery(Map<String, Object> providerJob) {
        Object explicitValue = providerJob.get("has_previous_failed_delivery");
        if (explicitValue instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (explicitValue instanceof String text && !text.isBlank()) {
            if ("true".equalsIgnoreCase(text.trim())) {
                return true;
            }
            if ("false".equalsIgnoreCase(text.trim())) {
                return false;
            }
            throw invalidShape("has_previous_failed_delivery");
        }
        if (explicitValue != null) {
            throw invalidShape("has_previous_failed_delivery");
        }
        Integer failedCount = resolvePreviousFailedDeliveryCount(providerJob);
        if (failedCount == null) {
            return null;
        }
        return failedCount > 0;
    }

    private List<WaitProviderDeliveryAttemptResponse> readProviderDeliveryAttempts(Object value) {
        List<Map<String, Object>> deliveryAttempts = nestedMapList(value, "provider_job.provider_delivery_attempts");
        if (deliveryAttempts.isEmpty()) {
            return List.of();
        }
        List<WaitProviderDeliveryAttemptResponse> attempts = new ArrayList<>();
        for (Map<String, Object> attempt : deliveryAttempts) {
            attempts.add(new WaitProviderDeliveryAttemptResponse(
                    readText(attempt, "delivery_id"),
                    readInteger(attempt, "dispatch_count"),
                    readText(attempt, "dispatched_at"),
                    readText(attempt, "callback_deadline_at"),
                    readText(attempt, "provider_job_id"),
                    readText(attempt, "provider_receipt_id"),
                    readText(attempt, "input_digest"),
                    readText(attempt, "ack_status"),
                    readText(attempt, "callback_received_at"),
                    readText(attempt, "result_locator"),
                    readText(attempt, "error_code"),
                    readText(attempt, "error_message")
            ));
        }
        return List.copyOf(attempts);
    }

    private List<Map<String, Object>> nestedMapList(Object value, String field) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> items)) {
            throw invalidShape(field);
        }
        List<Map<String, Object>> maps = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            maps.add(nestedMap(items.get(index), field + "[" + index + "]"));
        }
        return List.copyOf(maps);
    }

    private Map<String, Object> nestedMap(Object value, String field) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw invalidShape(field);
        }
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null) {
                throw invalidShape(field);
            }
            normalized.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return normalized;
    }

    private BusinessException invalidShape(String field) {
        return new BusinessException("TASK_EVENT_PAYLOAD_SHAPE_INVALID",
                "任务等待上下文字段类型无效: " + field);
    }

    private WaitProviderJobResponse emptyProviderJob() {
        return new WaitProviderJobResponse(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, List.of()
        );
    }

    private WaitApprovalRequestResponse emptyApprovalRequest() {
        return new WaitApprovalRequestResponse(null, null, null, null, null, null, null, null);
    }

    private WaitReasonResponse emptyWaitReason() {
        return new WaitReasonResponse(null, null, null, null, List.of(), List.of(), List.of());
    }

    private String blankIfNull(String value) {
        return value == null ? "" : value;
    }
}
