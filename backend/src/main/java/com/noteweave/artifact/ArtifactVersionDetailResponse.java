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
        ArtifactVerificationTraceResponse verification,
        ArtifactRuntimeTraceResponse runtimeTrace,
        List<ArtifactFileMetadataResponse> files,
        Instant createdAt
) {
    public ArtifactVersionDetailResponse(String versionId, String artifactJobId, String skillKey,
            int versionNo, String title, String contentMarkdown, String traceSummary,
            List<Map<String, Object>> citations, ArtifactRuntimeTraceResponse runtimeTrace,
            List<ArtifactFileMetadataResponse> files, Instant createdAt) {
        this(versionId, artifactJobId, skillKey, versionNo, title, contentMarkdown, traceSummary,
                citations, runtimeTrace == null ? null : runtimeTrace.verification(), runtimeTrace, files, createdAt);
    }
}
