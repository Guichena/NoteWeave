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
}
