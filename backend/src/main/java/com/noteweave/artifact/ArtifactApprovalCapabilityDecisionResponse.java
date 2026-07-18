package com.noteweave.artifact;

public record ArtifactApprovalCapabilityDecisionResponse(
        String capabilityName,
        String providerId,
        String serverId,
        String toolName,
        String approvalStatus,
        String providerStatus,
        String healthStatus,
        String discoveryStatus,
        String selectionReason,
        String runtimeStatus
) {
}
