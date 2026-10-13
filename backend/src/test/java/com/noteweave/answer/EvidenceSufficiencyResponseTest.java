package com.noteweave.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.noteweave.retrieval.QueryRewrite;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvidenceSufficiencyResponseTest {
    private final ObjectMapper objectMapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .findAndRegisterModules();

    @Test
    void blankStoredValueIsOmittedAndKnownValueUsesTheContractName() throws Exception {
        JsonNode omitted = objectMapper.readTree(objectMapper.writeValueAsString(answer(null)));
        JsonNode blank = objectMapper.readTree(objectMapper.writeValueAsString(answer("  ")));
        JsonNode unknown = objectMapper.readTree(objectMapper.writeValueAsString(answer("MAYBE")));
        JsonNode present = objectMapper.readTree(objectMapper.writeValueAsString(answer("PARTIAL")));

        assertThat(omitted.has("evidence_sufficiency")).isFalse();
        assertThat(blank.has("evidence_sufficiency")).isFalse();
        assertThat(unknown.has("evidence_sufficiency")).isFalse();
        assertThat(present.path("evidence_sufficiency").asText()).isEqualTo("PARTIAL");
        assertThat(EvidenceSufficiency.canonical(null)).isNull();
        assertThat(EvidenceSufficiency.canonical("NOT_RUN")).isEqualTo("NOT_RUN");
    }

    @Test
    void legacyManifestConstructorOmitsTheField() throws Exception {
        JsonNode legacy = objectMapper.readTree(objectMapper.writeValueAsString(
                new AnswerEvidenceManifestResponse("run", List.of(), (QueryRewrite.View) null)));
        JsonNode stored = objectMapper.readTree(objectMapper.writeValueAsString(
                new AnswerEvidenceManifestResponse("run", List.of(), null, List.of(), "NOT_RUN")));

        assertThat(legacy.has("evidence_sufficiency")).isFalse();
        assertThat(legacy.path("memory_references").isArray()).isTrue();
        assertThat(stored.path("evidence_sufficiency").asText()).isEqualTo("NOT_RUN");
    }

    private AnswerRunResponse answer(String sufficiency) {
        return new AnswerRunResponse(
                "run", "workspace", "conversation", "QA", "COMPLETED",
                "query", "answer", "request", "plan", "model", 100,
                false, List.of(), 1, "COMPLETED", "正文",
                null, null, null, null, null, null, sufficiency);
    }
}
