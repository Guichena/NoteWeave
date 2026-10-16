package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArtifactOutputContractPublicationPolicyTest {
    @SuppressWarnings("unchecked")
    private Map<String, Object> contract() throws Exception {
        var mapper = new ObjectMapper();
        var catalog = mapper.readValue(getClass().getResourceAsStream("/artifact-skill-catalog-v2.json"), Map.class);
        var entry = (Map<String, Object>) ((List<?>) catalog.get("skills")).get(0);
        return (Map<String, Object>) entry.get("output_contract");
    }

    @Test
    void acceptsPublishedContractAndKeepsExecutionPolicyOutOfPublicMetadata() throws Exception {
        ArtifactOutputContractPublicationPolicy.validate(contract());
        String metadata = new ObjectMapper().writeValueAsString(new ArtifactSkillCatalogService().listSkills());
        assertThat(metadata).doesNotContain("output_contract", "backtrack_to", "prompt_recipe", "no_fabricated_metrics");
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsUnknownRulesAndInvalidLevels() throws Exception {
        Map<String, Object> contract = contract();
        Map<String, Object> rule = (Map<String, Object>) ((List<?>) contract.get("rules")).get(0);
        rule.put("name", "load_remote_script");
        assertThatThrownBy(() -> ArtifactOutputContractPublicationPolicy.validate(contract))
                .isInstanceOf(IllegalStateException.class);
        rule.put("name", "bullet_count");
        rule.put("level", "IGNORE");
        assertThatThrownBy(() -> ArtifactOutputContractPublicationPolicy.validate(contract))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsInvertedBoundsUnknownParametersAndRemoteReferences() throws Exception {
        Map<String, Object> contract = contract();
        Map<String, Object> sections = (Map<String, Object>) contract.get("sections");
        sections.put("min", 8); sections.put("max", 3);
        assertThatThrownBy(() -> ArtifactOutputContractPublicationPolicy.validate(contract))
                .isInstanceOf(IllegalStateException.class);
        sections.put("min", 3); sections.put("max", 8);
        contract.put("json_schema", Map.of("$ref", "https://untrusted.example/schema"));
        assertThatThrownBy(() -> ArtifactOutputContractPublicationPolicy.validate(contract))
                .isInstanceOf(IllegalStateException.class);
        contract.remove("json_schema");
        Map<String, Object> rule = (Map<String, Object>) ((List<?>) contract.get("rules")).get(0);
        rule.put("parameters", Map.of("section_index", 1, "arbitrary_code", "run.py"));
        assertThatThrownBy(() -> ArtifactOutputContractPublicationPolicy.validate(contract))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void exposesIntentAndBudgetDiagnosticsAndReadsOlderPayloads() {
        ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        var assembler = new ArtifactPayloadReadModelAssembler(mapper);
        var trace = assembler.readRuntimeTrace("""
                {"intent_compilation_trace":{"method":"LLM","llm_calls":2},
                 "llm_usage":{"current_call_count":3,"max_total_calls":64}}
                """);
        assertThat(trace.intentCompilationTrace()).containsEntry("method", "LLM").containsEntry("llm_calls", 2);
        assertThat(trace.llmUsage()).containsEntry("current_call_count", 3);
        var old = assembler.readRuntimeTrace("{\"verification\":{\"status\":\"PASS\"}}");
        assertThat(old.intentCompilationTrace()).isEmpty();
        assertThat(old.llmUsage()).isEmpty();
    }
}
