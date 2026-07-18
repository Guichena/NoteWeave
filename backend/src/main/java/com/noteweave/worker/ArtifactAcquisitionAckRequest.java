package com.noteweave.worker;

import java.util.Map;

public record ArtifactAcquisitionAckRequest(
        String callbackToken,
        String finalStatus,
        String resultLocator,
        String errorCode,
        String errorMessage,
        Map<String, Object> providerPayload
) {
}
