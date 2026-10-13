package com.noteweave.answer;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.noteweave.memory.MemoryReferenceResponse;
import com.noteweave.retrieval.QueryRewrite;
import java.time.Instant;
import java.util.List;

public record AnswerEvidenceManifestResponse(
        String runId,
        List<Evidence> evidence,
        @JsonInclude(JsonInclude.Include.NON_NULL) QueryRewrite.View retrievalRewrite,
        List<MemoryReferenceResponse> memoryReferences,
        @JsonInclude(JsonInclude.Include.NON_NULL) String evidenceSufficiency
) {
    public AnswerEvidenceManifestResponse {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        memoryReferences = memoryReferences == null ? List.of() : List.copyOf(memoryReferences);
        evidenceSufficiency = EvidenceSufficiency.canonical(evidenceSufficiency);
    }

    public AnswerEvidenceManifestResponse(
            String runId,
            List<Evidence> evidence,
            QueryRewrite.View retrievalRewrite,
            List<MemoryReferenceResponse> memoryReferences
    ) {
        this(runId, evidence, retrievalRewrite, memoryReferences, null);
    }

    public AnswerEvidenceManifestResponse(
            String runId,
            List<Evidence> evidence,
            QueryRewrite.View retrievalRewrite
    ) {
        this(runId, evidence, retrievalRewrite, List.of(), null);
    }

    public record Evidence(
            int rank, String evidenceId, String kind, String sourceId, String sourceSnapshotId,
            String passageId, String knowledgeItemId, String knowledgeVersionId, String title,
            String excerpt, String contentHash, String location, Instant freshAt, int characterCost,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> graphPath,
            @JsonProperty("text_origin") @JsonInclude(JsonInclude.Include.NON_NULL) String textOrigin,
            @JsonProperty("ocr_confidence") @JsonInclude(JsonInclude.Include.NON_NULL) Double ocrConfidence
    ) {
        public Evidence {
            graphPath = graphPath == null || graphPath.isEmpty() ? null : List.copyOf(graphPath);
        }

        public Evidence(int rank, String evidenceId, String kind, String sourceId, String sourceSnapshotId,
                        String passageId, String knowledgeItemId, String knowledgeVersionId, String title,
                        String excerpt, String contentHash, String location, Instant freshAt, int characterCost,
                        List<String> graphPath) {
            this(rank, evidenceId, kind, sourceId, sourceSnapshotId, passageId, knowledgeItemId, knowledgeVersionId,
                    title, excerpt, contentHash, location, freshAt, characterCost, graphPath, null, null);
        }
    }
}
