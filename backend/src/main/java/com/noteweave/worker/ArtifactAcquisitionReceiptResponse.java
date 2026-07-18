package com.noteweave.worker;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ArtifactAcquisitionReceiptResponse(
        String receiptId,
        String requestId,
        String taskId,
        String sourceId,
        String operationKey,
        String deliveryId,
        String callbackToken,
        String providerJobStatus,
        String callbackStatus,
        String providerReceiptId,
        String providerJobId,
        String resultLocator,
        String completedAt,
        String errorCode,
        String errorMessage,
        Integer dispatchCount
) {
}
