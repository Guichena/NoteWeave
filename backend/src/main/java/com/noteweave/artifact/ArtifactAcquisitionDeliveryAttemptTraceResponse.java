package com.noteweave.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ArtifactAcquisitionDeliveryAttemptTraceResponse(
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
