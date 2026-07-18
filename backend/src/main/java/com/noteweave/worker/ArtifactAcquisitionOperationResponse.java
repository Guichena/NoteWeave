package com.noteweave.worker;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ArtifactAcquisitionOperationResponse(
        String requestId,
        String taskId,
        String capabilityName,
        String providerId,
        String serverId,
        String toolName,
        String providerJobId,
        String providerReceiptId,
        String deliveryId,
        String callbackToken,
        String providerStatus,
        String healthStatus,
        String providerJobStatus,
        String callbackStatus,
        Integer dispatchCount,
        List<ArtifactAcquisitionDeliveryAttemptResponse> providerDeliveryAttempts
) {
}
