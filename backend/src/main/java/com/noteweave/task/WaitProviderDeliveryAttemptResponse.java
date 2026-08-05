package com.noteweave.task;

public record WaitProviderDeliveryAttemptResponse(
        String deliveryId,
        Integer dispatchCount,
        String dispatchedAt,
        String callbackDeadlineAt,
        String providerJobId,
        String providerReceiptId,
        String inputDigest,
        String ackStatus,
        String callbackReceivedAt,
        String resultLocator,
        String errorCode,
        String errorMessage
) {
}
