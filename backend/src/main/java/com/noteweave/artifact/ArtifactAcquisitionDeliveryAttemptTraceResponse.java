package com.noteweave.artifact;

public record ArtifactAcquisitionDeliveryAttemptTraceResponse(
        String deliveryId,
        Integer dispatchCount,
        String callbackToken,
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
