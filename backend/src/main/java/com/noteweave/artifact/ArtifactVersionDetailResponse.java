package com.noteweave.artifact;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ArtifactVersionDetailResponse(
        String versionId,
        String artifactJobId,
        String skillKey,
        int versionNo,
        String title,
        String contentMarkdown,
        String traceSummary,
        List<Map<String, Object>> citations,
        ArtifactRuntimeTraceResponse runtimeTrace,
        List<ArtifactFileMetadataResponse> files,
        Instant createdAt
) {
}
