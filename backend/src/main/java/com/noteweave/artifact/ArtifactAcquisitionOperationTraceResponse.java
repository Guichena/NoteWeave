package com.noteweave.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ArtifactAcquisitionOperationTraceResponse(
        String requestId,
        String providerJobId,
        String capabilityName,
        String providerId,
        String serverId,
        String toolName,
        @JsonProperty("provider_status")
        String providerStatus,
        @JsonProperty("health_status")
        String healthStatus,
        @JsonProperty("provider_job_status")
        String providerJobStatus,
        String callbackStatus,
        String deliveryId,
        String providerReceiptId,
        Integer dispatchCount,
        List<ArtifactAcquisitionDeliveryAttemptTraceResponse> providerDeliveryAttempts
) {
}
