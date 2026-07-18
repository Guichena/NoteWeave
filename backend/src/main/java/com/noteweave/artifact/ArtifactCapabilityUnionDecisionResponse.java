package com.noteweave.artifact;

public record ArtifactCapabilityUnionDecisionResponse(
        String capabilityName,
        String scopeType,
        String serverId,
        String toolName,
        String providerId,
        String riskLevel,
        String approvalMode,
        String discoveryStatus,
        String providerStatus,
        String healthStatus,
        String approvalStatus,
        String selectionReason,
        String routeBasis,
        String runtimeStatus
) {
}
