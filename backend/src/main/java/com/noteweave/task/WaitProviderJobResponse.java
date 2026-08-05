package com.noteweave.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record WaitProviderJobResponse(
        String providerId,
        String serverId,
        String toolName,
        String capabilityName,
        String operationKey,
        String providerStatus,
        String healthStatus,
        String providerJobStatus,
        String status,
        String requestId,
        String providerJobId,
        String providerReceiptId,
        String deliveryId,
        String callbackStatus,
        Integer dispatchCount,
        Integer previousFailedDeliveryCount,
        Boolean hasPreviousFailedDelivery,
        List<WaitProviderDeliveryAttemptResponse> providerDeliveryAttempts
) {
}
