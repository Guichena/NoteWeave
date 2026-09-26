package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import com.noteweave.worker.WorkerCompleteRequest;
import java.util.List;
import java.util.Map;
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

    private WorkerCompleteRequest request(Map<String, Object> payload) {
        return new WorkerCompleteRequest("MARKDOWN", "Artifact", payload, "", List.of());
    }
}
