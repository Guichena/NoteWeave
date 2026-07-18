package com.noteweave.answer;

import java.time.Instant;
import java.util.List;

public record AnswerEvidenceManifestResponse(String runId, List<Evidence> evidence) {
    public AnswerEvidenceManifestResponse {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public record Evidence(
            int rank, String evidenceId, String kind, String sourceId, String sourceSnapshotId,
            String passageId, String knowledgeItemId, String knowledgeVersionId, String title,
            String excerpt, String contentHash, String location, Instant freshAt, int characterCost
    ) { }
}
