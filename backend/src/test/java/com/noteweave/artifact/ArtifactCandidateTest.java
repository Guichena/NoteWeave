package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import com.noteweave.worker.WorkerCompleteRequest;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

class ArtifactCandidateTest {
    private static final String MARKDOWN = "# Frozen artifact\n\nGrounded content";

    @Test
    void identicalTaskSnapshotAndContentYieldOneCandidate() {
        WorkerCompleteRequest request = request(Map.of("markdown", MARKDOWN));
        ArtifactCandidate first = ArtifactCandidate.from("task-1", "snapshot-1", request, MARKDOWN);
        ArtifactCandidate replay = ArtifactCandidate.from("task-1", "snapshot-1", request, MARKDOWN);

        assertThat(replay).isEqualTo(first);
        assertThat(first.candidateId()).hasSize(64);
        assertThat(ArtifactCandidate.from("task-1", "snapshot-2", request, MARKDOWN))
                .isNotEqualTo(first);
    }

    @Test
    void forgedWorkerEnvelopeCannotChangeFrozenSnapshotOrContent() {
        ArtifactCandidate expected = ArtifactCandidate.from(
                "task-1", "snapshot-1", request(Map.of("markdown", MARKDOWN)), MARKDOWN);
        Map<String, Object> envelope = Map.of(
                "task_id", "task-1",
                "input_snapshot_id", "snapshot-2",
                "content_sha256", expected.contentSha256(),
                "candidate_id", expected.candidateId());

        assertThatThrownBy(() -> ArtifactCandidate.from("task-1", "snapshot-1",
                request(Map.of("markdown", MARKDOWN, "candidate", envelope)), MARKDOWN))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("frozen Run input");
    }

    @Test
    void candidateCannotClaimDifferentPublishedCatalog() {
        ArtifactCandidate expected = ArtifactCandidate.from(
                "task-1", "snapshot-1", request(Map.of("markdown", MARKDOWN)), MARKDOWN);
        Map<String, Object> envelope = Map.of(
                "task_id", "task-1",
                "input_snapshot_id", "snapshot-1",
                "catalog_digest", "different",
                "content_sha256", expected.contentSha256(),
                "candidate_id", expected.candidateId());
        assertThatThrownBy(() -> ArtifactCandidate.from("task-1", "snapshot-1", "expected",
                request(Map.of("markdown", MARKDOWN, "candidate", envelope)), MARKDOWN))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void publishedRunCannotCompleteWithoutCandidate() {
        assertThatThrownBy(() -> ArtifactCandidate.from("task-1", "snapshot-1", "published-digest",
                request(Map.of("markdown", MARKDOWN)), MARKDOWN))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("requires a Worker Candidate");
    }

    @Test
    void typedContentMustMatchSectionsMarkdownAndCandidateDigest() throws Exception {
        Map<String, Object> section = Map.of("heading", "Overview", "body", "Grounded content",
                "source_refs", List.of("source-1"));
        Map<String, Object> ir = new LinkedHashMap<>(Map.of(
                "schema_version", "artifact-content-v1", "artifact_type", "STUDY_GUIDE",
                "title", "Artifact", "sections", List.of(section),
                "markdown_sha256", sha256(MARKDOWN)));
        String irDigest = sha256(new com.fasterxml.jackson.databind.ObjectMapper()
                .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .writeValueAsString(ir));
        assertThat(irDigest).isEqualTo("a0c2be9cf0c27a62e9df870032d9ff7892b5c6a7966d4f8bd889a064127c0b6c");
        ir.put("content_digest", irDigest);
        ArtifactCandidate base = ArtifactCandidate.from(
                "task-1", "snapshot-1", request(Map.of("markdown", MARKDOWN)), MARKDOWN);
        Map<String, Object> candidate = Map.of(
                "task_id", "task-1", "input_snapshot_id", "snapshot-1",
                "content_sha256", base.contentSha256(), "candidate_id", base.candidateId(),
                "content_ir_digest", irDigest);
        Map<String, Object> payload = Map.of("markdown", MARKDOWN, "sections", List.of(section),
                "content_ir", ir, "candidate", candidate);

        assertThat(ArtifactCandidate.from("task-1", "snapshot-1", request(payload), MARKDOWN)
                .candidateId()).isEqualTo(base.candidateId());
        Map<String, Object> forged = new LinkedHashMap<>(ir);
        forged.put("markdown_sha256", "0".repeat(64));
        assertThatThrownBy(() -> ArtifactCandidate.from("task-1", "snapshot-1",
                request(Map.of("markdown", MARKDOWN, "sections", List.of(section),
                        "content_ir", forged, "candidate", candidate)), MARKDOWN))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Typed artifact content");
    }

    private String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private WorkerCompleteRequest request(Map<String, Object> payload) {
        return new WorkerCompleteRequest("MARKDOWN", "Artifact", payload, "", List.of());
    }
}
