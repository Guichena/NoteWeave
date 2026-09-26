package com.noteweave.artifact;

import java.util.List;

public record ArtifactSourceWindowPageResponse(
        String sourceId, String sourceSnapshotId, List<ArtifactSourceWindowResponse> windows,
        String nextCursor, boolean truncatedByBudget) { }
