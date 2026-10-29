package com.noteweave.artifact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        Instant createdAt,
        String kind,
        JsonNode ir
) {
    private static final ObjectMapper PAYLOAD_READER = new ObjectMapper();

    public ArtifactVersionDetailResponse(String versionId, String artifactJobId, String skillKey,
            int versionNo, String title, String contentMarkdown, String traceSummary,
            List<Map<String, Object>> citations, ArtifactVerificationTraceResponse verification,
            ArtifactRuntimeTraceResponse runtimeTrace, List<ArtifactFileMetadataResponse> files, Instant createdAt, String kind) {
        this(versionId, artifactJobId, skillKey, versionNo, title, contentMarkdown, traceSummary,
                citations, verification, runtimeTrace, files, createdAt, kind, null);
    }

    /** 历史版本缺少 IR 或载荷不可解析时保留 Markdown 阅读，不影响版本详情返回。 */
    static JsonNode readIr(String resultPayloadJson) {
        if (resultPayloadJson == null || resultPayloadJson.isBlank()) return null;
        try {
            JsonNode payload = PAYLOAD_READER.readTree(resultPayloadJson);
            JsonNode content = payload == null ? null : payload.get("ir");
            return content != null && content.isObject() ? content : null;
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    public ArtifactVersionDetailResponse(String versionId, String artifactJobId, String skillKey,
            int versionNo, String title, String contentMarkdown, String traceSummary,
            List<Map<String, Object>> citations, ArtifactVerificationTraceResponse verification,
            ArtifactRuntimeTraceResponse runtimeTrace, List<ArtifactFileMetadataResponse> files, Instant createdAt) {
        this(versionId, artifactJobId, skillKey, versionNo, title, contentMarkdown, traceSummary,
                citations, verification, runtimeTrace, files, createdAt, "", null);
    }
    public ArtifactVersionDetailResponse(String versionId, String artifactJobId, String skillKey,
            int versionNo, String title, String contentMarkdown, String traceSummary,
            List<Map<String, Object>> citations, ArtifactRuntimeTraceResponse runtimeTrace,
            List<ArtifactFileMetadataResponse> files, Instant createdAt) {
        this(versionId, artifactJobId, skillKey, versionNo, title, contentMarkdown, traceSummary,
                citations, runtimeTrace == null ? null : runtimeTrace.verification(), runtimeTrace, files, createdAt, "");
    }
}
