package com.noteweave.artifact;

import java.util.List;

public record ArtifactCapabilityUnionTraceResponse(
        String status,
        String decision,
        String reasonCode,
        String policyKey,
        String skillScope,
        String workspaceScope,
        List<String> capabilityScope,
        List<String> externalNetworkCapabilities,
        List<String> writebackCapabilities,
        List<String> blockedCapabilities,
        List<ArtifactCapabilityUnionDecisionResponse> capabilityDecisions,
        List<String> notes
) {
}
