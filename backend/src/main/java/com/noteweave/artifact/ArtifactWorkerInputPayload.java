package com.noteweave.artifact;

import java.util.Map;

public record ArtifactWorkerInputPayload(
        String skillKey,
        String styleProfileKey,
        String contextSnapshotId,
        String userRequirement,
        String generationBrief,
        Map<String, Object> inputs,
        String taskParameters,
        String taskParametersUnsupported
) {
    public static ArtifactWorkerInputPayload skillFirst(
            String skillKey,
            String styleProfileKey,
            String contextSnapshotId,
            String userRequirement,
            String generationBrief,
            Map<String, Object> inputs,
            String taskParameters,
            String taskParametersUnsupported
    ) {
        return new ArtifactWorkerInputPayload(
                skillKey,
                styleProfileKey,
                contextSnapshotId,
                userRequirement,
                generationBrief,
                inputs,
                taskParameters == null ? "" : taskParameters,
                taskParametersUnsupported == null ? "" : taskParametersUnsupported
        );
    }
}
