package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;

class ArtifactTypedNodeTraceTest {
    private final ArtifactPayloadReadModelAssembler assembler = new ArtifactPayloadReadModelAssembler(
            new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));

    @Test
    void readsRealNodePurposeCallsAndSchemaDiagnostics() {
        var trace = assembler.readRuntimeTrace("""
                {"node_traces":[{"node_id":"write","skill_key":"resume_bullet_writer",
                  "output_summary":"节点已执行","verification_status":"PASS",
                  "verification_checks":[],"repair_actions":[],"repaired":false,
                  "node_kind":"LLM_STEP","purpose":"artifact.node.resume_bullet_writer",
                  "prompt_recipe_id":"node.resume_bullet_writer.v1","llm_calls":2,
                  "validation_errors":["输出缺少事实引用"],"input_fields":["normalized_facts"],
                  "output_fields":["sections","bullets"],
                  "execution_trace":{"status":"COMPLETED","optional_diagnostic":null}}]}
                """);
        var node = trace.nodeTraces().get(0);
        assertThat(node.nodeKind()).isEqualTo("LLM_STEP");
        assertThat(node.purpose()).isEqualTo("artifact.node.resume_bullet_writer");
        assertThat(node.llmCalls()).isEqualTo(2);
        assertThat(node.validationErrors()).containsExactly("输出缺少事实引用");
        assertThat(node.inputFields()).containsExactly("normalized_facts");
        assertThat(node.outputFields()).containsExactly("sections", "bullets");
        assertThat(node.executionTrace()).containsEntry("optional_diagnostic", null);
    }

    @Test
    void legacyNodeTraceRemainsReadableWithEmptyNewFields() {
        var node = assembler.readRuntimeTrace("""
                {"node_traces":[{"node_id":"write","skill_key":"resume_bullet_writer",
                  "output_summary":"历史记录","verification_status":"PASS",
                  "verification_checks":[],"repair_actions":[],"repaired":false}]}
                """).nodeTraces().get(0);
        assertThat(node.nodeKind()).isEmpty();
        assertThat(node.llmCalls()).isZero();
        assertThat(node.validationErrors()).isEmpty();
        assertThat(node.executionTrace()).isEmpty();
    }
}
