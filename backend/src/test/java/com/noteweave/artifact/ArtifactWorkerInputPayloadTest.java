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
                "inputs",
                "taskParameters",
                "taskParametersUnsupported"
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
                Map.of("language", "zh-CN"),
                "输出语言：中文",
                ""
        );

        Map<String, Object> serialized = objectMapper.convertValue(payload, Map.class);

        assertThat(serialized).containsOnlyKeys(
                "skillKey",
                "styleProfileKey",
                "contextSnapshotId",
                "userRequirement",
                "generationBrief",
                "inputs",
                "taskParameters",
                "taskParametersUnsupported"
        );
        assertThat(serialized).doesNotContainKey("actionKey");
        // 验收回归：任务参数单独成字段，不能混进 Skill 用户选项
        assertThat(serialized.get("inputs")).isEqualTo(Map.of("language", "zh-CN"));
        assertThat(serialized.get("taskParameters")).isEqualTo("输出语言：中文");
    }

    @Test
    void legacyInputsWithTaskParametersShouldBeStrippedWhenRead() {
        ArtifactPayloadReadModelAssembler assembler = new ArtifactPayloadReadModelAssembler(objectMapper);

        Map<String, Object> inputs = assembler.readInputs(
                "{\"count\":\"10\",\"task_parameters\":\"输出语言：中文\",\"task_parameters_unsupported\":\"\"}");

        assertThat(inputs).containsOnlyKeys("count");
    }
}
