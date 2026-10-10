package com.noteweave.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.worker.WorkerCompleteRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;

/** Validates typed content before Version publication; legacy snapshots remain replayable. */
final class ArtifactContentIr {
    private static final Set<String> FIELDS = Set.of(
            "schema_version", "artifact_type", "title", "sections", "markdown_sha256", "content_digest");
    private static final Set<String> SECTION_FIELDS = Set.of("heading", "body", "source_refs");

    private ArtifactContentIr() {}

    static void validate(WorkerCompleteRequest request, String markdown, Map<?, ?> candidate,
                         boolean required) {
        validate(request, markdown, candidate, required, "");
    }

    static void validate(WorkerCompleteRequest request, String markdown, Map<?, ?> candidate,
                         boolean required, String expectedArtifactType) {
        Map<String, Object> payload = request.resultPayload();
        Object raw = payload == null ? null : payload.get("content_ir");
        Object claimedDigest = candidate == null ? null : candidate.get("content_ir_digest");
        if (raw == null && claimedDigest == null) {
            if (required) throw new BusinessException("ARTIFACT_CONTENT_IR_REQUIRED",
                    "Published artifact Run requires typed content IR", HttpStatus.CONFLICT);
            return;
        }
        if (!(raw instanceof Map<?, ?> ir) || !ir.keySet().equals(FIELDS)
                || !"artifact-content-v1".equals(ir.get("schema_version"))
                || !(ir.get("artifact_type") instanceof String type) || type.isBlank()
                || (required && !expectedArtifactType.isBlank()
                        && !type.equals(expectedArtifactType))
                || !request.resultTitle().equals(ir.get("title"))
                || !sha256(markdown).equals(ir.get("markdown_sha256"))
                || !(ir.get("sections") instanceof List<?> sections) || sections.isEmpty()
                || !sections.equals(payload.get("sections"))) {
            throw invalid();
        }
        for (Object rawSection : sections) {
            if (!(rawSection instanceof Map<?, ?> section) || !section.keySet().equals(SECTION_FIELDS)
                    || !(section.get("heading") instanceof String heading) || heading.isBlank()
                    || !(section.get("body") instanceof String body) || body.isBlank()
                    || !(section.get("source_refs") instanceof List<?> refs)
                    || refs.stream().anyMatch(ref -> !(ref instanceof String text) || text.isBlank())) {
                throw invalid();
            }
        }
        try {
            Map<String, Object> content = new TreeMap<>();
            ir.forEach((key, value) -> {
                if (!"content_digest".equals(key)) content.put(String.valueOf(key), canonical(value));
            });
            String expected = sha256(new ObjectMapper().writeValueAsString(content));
            if (!expected.equals(ir.get("content_digest")) || !expected.equals(claimedDigest)) {
                throw invalid();
            }
        } catch (BusinessException invalid) {
            throw invalid;
        } catch (Exception ex) {
            throw invalid();
        }
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, child) -> sorted.put(String.valueOf(key), canonical(child)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(ArtifactContentIr::canonical).toList();
        return value;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static BusinessException invalid() {
        return new BusinessException("ARTIFACT_CONTENT_IR_INVALID",
                "Typed artifact content does not match the verified Candidate", HttpStatus.CONFLICT);
    }
}
