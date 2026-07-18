package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArtifactWorkerInputPayloadTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void skillFirstPayloadShouldNotRetainActionKeyRecordComponent() {
        List<String> componentNames = Arrays.stream(ArtifactWorkerInputPayload.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(componentNames).containsExactly(
                "skillKey",
                "styleProfileKey",
                "contextSnapshotId",
                "userRequirement",
                "generationBrief",
                "inputs"
        );
    }

    @Test
    void skillFirstPayloadShouldSerializeOnlySkillFirstFields() {
        ArtifactWorkerInputPayload payload = ArtifactWorkerInputPayload.skillFirst(
                "resume_highlight",
                "executive",
                "ctx-1",
                "write strong resume bullets",
                "write strong resume bullets",
                Map.of("language", "zh-CN")
        );

        Map<String, Object> serialized = objectMapper.convertValue(payload, Map.class);

        assertThat(serialized).containsOnlyKeys(
                "skillKey",
                "styleProfileKey",
                "contextSnapshotId",
                "userRequirement",
                "generationBrief",
                "inputs"
        );
        assertThat(serialized).doesNotContainKey("actionKey");
    }
}
