package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.noteweave.answer.strategy.EvidenceBundle;
import com.noteweave.answer.strategy.PromptSpec;
import com.noteweave.common.BusinessException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class EvidenceCitationAssemblerTest {

    private final EvidenceCitationAssembler assembler =
            new EvidenceCitationAssembler(mock(JdbcTemplate.class));

    @Test
    void shouldAssembleCitationsInPromptEvidenceOrder() {
        EvidenceBundle bundle = bundle(List.of(evidence("one"), evidence("two")));
        PromptSpec prompt = new PromptSpec("system", "user", List.of("two", "one"), "qa-v1");

        var citations = assembler.assemble(bundle, prompt);

        assertThat(citations).extracting(EvidenceCitationAssembler.AssembledCitation::evidenceId)
                .containsExactly("two", "one");
        assertThat(citations.get(0).passageId()).isEqualTo("two");
        assertThat(citations.get(0).title()).isEqualTo("raw-title-two");
    }

    @Test
    void shouldReplaySameBundleToStableSemanticCitationMapping() {
        EvidenceBundle bundle = bundle(List.of(evidence("one"), evidence("two")));
        PromptSpec prompt = new PromptSpec(
                "system", "user", List.of("two", "one"), "qa-v1");

        var firstReplay = assembler.assemble(bundle, prompt);
        var secondReplay = assembler.assemble(bundle, prompt);

        assertThat(secondReplay)
                .isEqualTo(firstReplay)
                .isNotSameAs(firstReplay);
    }

    @Test
    void shouldPersistStableSemanticCitationMappingAcrossTwoReplays() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        EvidenceCitationAssembler replayAssembler = new EvidenceCitationAssembler(jdbcTemplate);
        EvidenceBundle bundle = bundle(List.of(evidence("one"), evidence("two")));
        PromptSpec prompt = new PromptSpec(
                "system", "user", List.of("two", "one"), "qa-v1");

        assertThat(replayAssembler.persist("workspace", "message-one", bundle, prompt))
                .isEqualTo(2);
        assertThat(replayAssembler.persist("workspace", "message-two", bundle, prompt))
                .isEqualTo(2);

        assertThat(jdbcTemplate.semanticCitations.subList(0, 2))
                .isEqualTo(jdbcTemplate.semanticCitations.subList(2, 4));
        assertThat(jdbcTemplate.sortOrders).containsExactly(0, 1, 0, 1);
    }

    @Test
    void shouldRejectPromptEvidenceOutsideBundle() {
        EvidenceBundle bundle = bundle(List.of(evidence("one")));
        PromptSpec prompt = new PromptSpec("system", "user", List.of("missing"), "qa-v1");

        assertThatThrownBy(() -> assembler.assemble(bundle, prompt))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void shouldRejectDuplicateEvidenceIdsBeforePersistence() {
        EvidenceBundle bundle = bundle(List.of(evidence("one"), evidence("one")));
        PromptSpec prompt = new PromptSpec("system", "user", List.of("one"), "qa-v1");

        assertThatThrownBy(() -> assembler.assemble(bundle, prompt))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Duplicate evidence id");
    }

    @Test
    void shouldRejectDuplicatePromptEvidenceReferences() {
        EvidenceBundle bundle = bundle(List.of(evidence("one")));
        PromptSpec prompt = new PromptSpec("system", "user", List.of("one", "one"), "qa-v1");

        assertThatThrownBy(() -> assembler.assemble(bundle, prompt))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("same evidence more than once");
    }

    @Test
    void shouldValidateKnowledgeVersionEvidenceWithoutCreatingSourceCitation() {
        EvidenceBundle.Evidence knowledge = new EvidenceBundle.Evidence(
                "knowledge-version:version", "KNOWLEDGE_VERSION", "", "", "",
                "item", "version", "Page", "content", "knowledge-version:version",
                1, 1, 1, "workspace-knowledge:item", null, "wiki-page-graph", 7, Map.of());
        EvidenceBundle bundle = bundle(List.of(knowledge));
        PromptSpec prompt = new PromptSpec(
                "system", "user", List.of("knowledge-version:version"), "wiki-v1");

        assertThat(assembler.assemble(bundle, prompt)).isEmpty();
    }

    private EvidenceBundle bundle(List<EvidenceBundle.Evidence> evidence) {
        return new EvidenceBundle("bundle", "qa-v1", evidence, false, List.of(), Instant.now());
    }

    private EvidenceBundle.Evidence evidence(String id) {
        return new EvidenceBundle.Evidence(
                id, "PASSAGE", "source-" + id, "snapshot-" + id, id,
                "", "", "title-" + id, "excerpt-" + id, "chunk:0",
                1, 1, 1, "workspace", Instant.now(), "selected", 10,
                Map.of("raw_title", "raw-title-" + id)
        );
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final List<List<Object>> semanticCitations = new ArrayList<>();
        private final List<Integer> sortOrders = new ArrayList<>();

        @Override
        public int update(String sql, Object... args) {
            if (sql.contains("insert into citation(")) {
                semanticCitations.add(List.copyOf(
                        Arrays.asList(args).subList(1, args.length)));
            } else if (sql.contains("insert into message_citation(")) {
                sortOrders.add((Integer) args[3]);
            }
            return 1;
        }
    }
}
