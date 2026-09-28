package com.noteweave.artifact;

import com.noteweave.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.worker.WorkerCompleteRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.List;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;

/** A Worker candidate is bound to one frozen Run input, never to a Version number. */
record ArtifactCandidate(String candidateId, String digest, String contentSha256) {
    static ArtifactCandidate from(String taskId, String inputSnapshotId, WorkerCompleteRequest request,
                                  String markdown) {
        return from(taskId, inputSnapshotId, "", request, markdown);
    }

    static ArtifactCandidate from(String taskId, String inputSnapshotId, String expectedCatalogDigest,
                                  WorkerCompleteRequest request, String markdown) {
        return from(taskId, inputSnapshotId, expectedCatalogDigest, "", request, markdown);
    }

    static ArtifactCandidate from(String taskId, String inputSnapshotId, String expectedCatalogDigest,
                                  String expectedArtifactType, WorkerCompleteRequest request, String markdown) {
        String contentSha256 = sha256(markdown);
        String candidateId = sha256(taskId + ":" + inputSnapshotId + ":" + contentSha256);
        Object raw = request.resultPayload() == null ? null : request.resultPayload().get("candidate");
        if (raw == null && !expectedCatalogDigest.isBlank()) {
            throw new BusinessException("ARTIFACT_CANDIDATE_REQUIRED",
                    "Published artifact Run requires a Worker Candidate", HttpStatus.CONFLICT);
        }
        if (raw != null) {
            if (!(raw instanceof Map<?, ?> value)
                    || !taskId.equals(value.get("task_id"))
                    || !inputSnapshotId.equals(value.get("input_snapshot_id"))
                    || (!expectedCatalogDigest.isBlank()
                            && !expectedCatalogDigest.equals(value.get("catalog_digest")))
                    || !contentSha256.equals(value.get("content_sha256"))
                    || !candidateId.equals(value.get("candidate_id"))) {
                throw new BusinessException("ARTIFACT_CANDIDATE_INVALID",
                        "Candidate does not match the frozen Run input and content", HttpStatus.CONFLICT);
            }
        }
        ArtifactContentIr.validate(request, markdown, raw instanceof Map<?, ?> map ? map : null,
                !expectedCatalogDigest.isBlank(), expectedArtifactType);
        try {
            Object canonical = canonicalValue(Map.of(
                    "result_type", request.resultType(),
                    "result_title", request.resultTitle(),
                    "result_payload", request.resultPayload() == null ? Map.of() : request.resultPayload(),
                    "citations", request.citations() == null ? List.of() : request.citations()));
            String digest = sha256(new ObjectMapper().writeValueAsString(canonical));
            return new ArtifactCandidate(candidateId, digest, contentSha256);
        } catch (Exception ex) {
            throw new BusinessException("ARTIFACT_CANDIDATE_INVALID",
                    "Candidate payload cannot be canonicalized", HttpStatus.CONFLICT);
        }
    }

    private static Object canonicalValue(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> sorted = new TreeMap<>();
            raw.forEach((key, child) -> sorted.put(String.valueOf(key), canonicalValue(child)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(ArtifactCandidate::canonicalValue).toList();
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
}
