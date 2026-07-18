package com.noteweave.artifact;

import java.util.List;
import java.util.Map;

public record ArtifactApprovalTraceResponse(
        String status,
        String decision,
        String reasonCode,
        List<String> requiredCapabilities,
        List<String> pendingCapabilities,
        List<String> satisfiedCapabilities,
        Map<String, Object> approvalRequest,
        List<ArtifactApprovalCapabilityDecisionResponse> capabilityDecisions,
        List<String> notes
) {
}
