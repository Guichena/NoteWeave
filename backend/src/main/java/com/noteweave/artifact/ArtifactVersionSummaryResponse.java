package com.noteweave.artifact;

import java.time.Instant;

public record ArtifactVersionSummaryResponse(
        String versionId,
        String artifactJobId,
        String skillKey,
        int versionNo,
        String title,
        Instant createdAt
) {
}
