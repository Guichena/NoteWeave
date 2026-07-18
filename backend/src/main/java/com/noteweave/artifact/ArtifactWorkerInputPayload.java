package com.noteweave.artifact;

import java.util.Map;

public record ArtifactWorkerInputPayload(
        String skillKey,
        String styleProfileKey,
        String contextSnapshotId,
        String userRequirement,
        String generationBrief,
        Map<String, Object> inputs
) {
    public static ArtifactWorkerInputPayload skillFirst(
            String skillKey,
            String styleProfileKey,
            String contextSnapshotId,
            String userRequirement,
            String generationBrief,
            Map<String, Object> inputs
    ) {
        return new ArtifactWorkerInputPayload(
                skillKey,
                styleProfileKey,
                contextSnapshotId,
                userRequirement,
                generationBrief,
                inputs
        );
    }
}
