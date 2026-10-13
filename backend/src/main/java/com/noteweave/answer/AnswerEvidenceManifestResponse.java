package com.noteweave.answer;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.noteweave.memory.MemoryReferenceResponse;
import com.noteweave.retrieval.QueryRewrite;
import java.time.Instant;
import java.util.List;

public record AnswerEvidenceManifestResponse(
        String runId,
        List<Evidence> evidence,
        @JsonInclude(JsonInclude.Include.NON_NULL) QueryRewrite.View retrievalRewrite,
        List<MemoryReferenceResponse> memoryReferences
) {
    public AnswerEvidenceManifestResponse {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        memoryReferences = memoryReferences == null ? List.of() : List.copyOf(memoryReferences);
    }

    public AnswerEvidenceManifestResponse(
            String runId,
            List<Evidence> evidence,
            QueryRewrite.View retrievalRewrite
    ) {
        this(runId, evidence, retrievalRewrite, List.of());
    }

    public record Evidence(
            int rank, String evidenceId, String kind, String sourceId, String sourceSnapshotId,
            String passageId, String knowledgeItemId, String knowledgeVersionId, String title,
            String excerpt, String contentHash, String location, Instant freshAt, int characterCost,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> graphPath
    ) {
        public Evidence {
            graphPath = graphPath == null || graphPath.isEmpty() ? null : List.copyOf(graphPath);
        }
    }
}
