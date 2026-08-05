package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ResearchAgentTaskSnapshotCanonicalizerTest {

    private final ResearchAgentTaskSnapshotCanonicalizer canonicalizer =
            new ResearchAgentTaskSnapshotCanonicalizer(new ObjectMapper());

    @Test
    void shouldOrderNestedSnapshotObjectKeysByUnsignedUtf8LikePython() {
        var snapshot = canonicalizer.canonicalize(new ResearchAgentTaskSnapshotCanonicalizer.SnapshotInput(
                "task-1", "run-1", "workspace-1", "DEEP_CELL", "entity-1", "branch-main",
                1, 1, 1, 1,
                "[{\"cell_key\":\"entity-1:claim\",\"base_cell_version\":0}]",
                "{\"search_calls\":1}",
                "{\"provider_key\":\"research\",\"source_policy\":{\"😀\":1,\"\uE000\":2},"
                        + "\"query_policy\":{\"query\":\"test\"}}"));

        assertThat(snapshot.json()).contains("\"source_policy\":{\"\uE000\":2,\"😀\":1}");
    }

    @Test
    void shouldOmitNullQuorumGroupFromSingleCandidateV2DigestLikePython() {
        var snapshot = canonicalizer.canonicalize(new ResearchAgentTaskSnapshotCanonicalizer.SnapshotInput(
                "research-agent-task-snapshot.v2",
                "task-1", "run-1", "workspace-1", "DEEP_CELL", "entity-1", "branch-main",
                1, 1, 1, 1,
                "[{\"cell_id\":\"entity-1:claim\",\"expected_version\":0}]",
                "{\"llm_calls\":1}",
                "{\"provider_key\":\"research\",\"source_policy\":{\"source_scope\":[]},"
                        + "\"query_policy\":{\"query\":\"test\"}}",
                "deep-cell:logical-1", null, 1, 1, false));

        assertThat(snapshot.json()).doesNotContain("\"quorum_group_key\"");
        assertThat(snapshot.digest()).isEqualTo(
                "sha256:d30eae285854a2f4276062ed7a00a0aa74145213de0f9873a33872d42d7d81cd");
    }
}
