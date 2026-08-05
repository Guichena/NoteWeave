package com.noteweave.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ArtifactAcquisitionCallbackReceiptTraceResponse(
        String receiptId,
        String requestId,
        String taskId,
        String sourceId,
        String operationKey,
        String deliveryId,
        @JsonProperty("provider_job_status")
        String providerJobStatus,
        String callbackStatus,
        String providerReceiptId,
        String providerJobId,
        String resultLocator,
        String completedAt,
        Integer dispatchCount,
        String errorCode,
        String errorMessage
) {
}
