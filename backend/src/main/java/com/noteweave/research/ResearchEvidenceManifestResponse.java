package com.noteweave.research;

import java.time.Instant;
import java.util.List;

public record ResearchEvidenceManifestResponse(
        String manifestId,
        String runId,
        String reportContentHash,
        List<Evidence> evidence,
        Instant createdAt
) {
    public ResearchEvidenceManifestResponse {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public record Evidence(
            int rank,
            String evidenceId,
            String sourceId,
            String sourceSnapshotId,
            String passageId,
            String title,
            String excerpt,
            String contentHash,
            String location
    ) { }
}
