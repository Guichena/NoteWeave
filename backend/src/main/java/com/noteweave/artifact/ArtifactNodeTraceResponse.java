package com.noteweave.artifact;

import java.util.List;

public record ArtifactNodeTraceResponse(
        String nodeId,
        String skillKey,
        String outputSummary,
        String verificationStatus,
        List<String> verificationChecks,
        List<String> repairActions,
        boolean repaired
) {
}
