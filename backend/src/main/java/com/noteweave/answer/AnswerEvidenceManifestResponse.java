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
        @JsonInclude(JsonInclude.Include.NON_NULL) String evidenceSufficiency,
        /** 引用协议版本；有冻结生成契约时是新协议，历史回答为 null，前端按旧解析处理。 */
        @JsonInclude(JsonInclude.Include.NON_NULL) String protocolVersion,
        /** 本次回答允许引用的证据集合，前端只给集合内的编号出角标。 */
        List<String> allowedEvidenceIds,
        /** 编号到证据身份与引用行的绑定。 */
        List<CitationBindingView> citationBindings
) {
    /** 新协议的标识；历史回答不带这个值。 */
    public static final String PROTOCOL_VERSION = "citation-binding-v1";

    public AnswerEvidenceManifestResponse {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        memoryReferences = memoryReferences == null ? List.of() : List.copyOf(memoryReferences);
        evidenceSufficiency = EvidenceSufficiency.canonical(evidenceSufficiency);
        allowedEvidenceIds = allowedEvidenceIds == null ? List.of() : List.copyOf(allowedEvidenceIds);
        citationBindings = citationBindings == null ? List.of() : List.copyOf(citationBindings);
    }

    public AnswerEvidenceManifestResponse(
            String runId,
            List<Evidence> evidence,
            QueryRewrite.View retrievalRewrite,
            List<MemoryReferenceResponse> memoryReferences,
            String evidenceSufficiency
    ) {
        this(runId, evidence, retrievalRewrite, memoryReferences, evidenceSufficiency,
                null, List.of(), List.of());
    }

    /** 一条编号绑定；编号与回答正文里的 [n] 一致。 */
    public record CitationBindingView(int number, String evidenceId, List<String> citationIds) {
        public CitationBindingView {
            citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
        }
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
