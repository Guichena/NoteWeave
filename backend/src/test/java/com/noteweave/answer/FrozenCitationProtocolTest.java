package com.noteweave.answer;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.answer.generation.CitationBinding;
import com.noteweave.answer.generation.GenerationContract;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FrozenCitationProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void eventMustExplicitlyDeclareBindingState() {
        GenerationContract contract = new GenerationContract(
                GenerationContract.SCHEMA_VERSION, "QA", "prompt-v1", "system", false,
                List.of("passage:1"), List.of(new CitationBinding(1, "passage:1", List.of("citation:1"))),
                "bundle:1", Map.of("passage:1", "a".repeat(64)), List.of());
        assertThat(mapper.valueToTree(EvidenceReadyEvent.from(contract)).path("protocol_state").asText())
                .isEqualTo("BINDING_V1");
    }

    @Test
    void runSnapshotMustHaveFrozenProtocolField() {
        assertThat(java.util.Arrays.stream(AnswerRunResponse.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)).contains("citationProtocol");
    }

    @Test
    void manifestMustHaveSameFrozenProtocolField() {
        assertThat(java.util.Arrays.stream(AnswerEvidenceManifestResponse.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)).contains("citationProtocol");
    }
}
