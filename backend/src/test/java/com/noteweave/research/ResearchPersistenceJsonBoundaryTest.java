package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ResearchPersistenceJsonBoundaryTest {

    @Test
    void readModelListParsingShouldRejectCorruptStoredJson() {
        ResearchRunReadRepository repository = new ResearchRunReadRepository(
                mock(JdbcTemplate.class), new ObjectMapper());

        assertThat(repository.readStringList("[\"evidence-1\"]"))
                .containsExactly("evidence-1");
        assertThat(repository.readStringList(""))
                .isEmpty();
        assertThatThrownBy(() -> repository.readStringList("not-json"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.code()).isEqualTo("RESEARCH_READ_MODEL_LIST_PARSE_FAILED"));
        assertThatThrownBy(() -> repository.readStringList("null"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.code()).isEqualTo("RESEARCH_READ_MODEL_LIST_PARSE_FAILED"));
    }

    @Test
    void verifierNotesShouldAcceptBothLegacyListsAndStructuredAuditObjects() {
        ResearchRunReadRepository repository = new ResearchRunReadRepository(
                mock(JdbcTemplate.class), new ObjectMapper());

        assertThat(repository.readStructuredJson("[]")).isEqualTo(java.util.List.of());
        assertThat(repository.readStructuredJson("{\"candidate_slots\":[1,2]}"))
                .isEqualTo(java.util.Map.of("candidate_slots", java.util.List.of(1, 2)));
        assertThatThrownBy(() -> repository.readStructuredJson("\"plain text\""))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.code()).isEqualTo("RESEARCH_READ_MODEL_JSON_PARSE_FAILED"));
    }

    @Test
    void resumeSummaryParsingShouldNotTurnCorruptJsonIntoRawContext() {
        ResearchBriefCompiler compiler = new ResearchBriefCompiler(
                mock(JdbcTemplate.class), new ObjectMapper());

        assertThat(compiler.readMap("{\"verified\":true}"))
                .containsEntry("verified", true);
        assertThat(compiler.readMap(""))
                .isEmpty();
        assertThatThrownBy(() -> compiler.readMap("not-json"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.code()).isEqualTo("RESEARCH_CONTEXT_SNAPSHOT_MISMATCH"));
    }

    @Test
    void sourceScopeParsingShouldNotTurnNullIntoAnEmptyScope() {
        ResearchSourceScopeLoader loader = new ResearchSourceScopeLoader(
                mock(JdbcTemplate.class), new ObjectMapper());

        assertThatThrownBy(() -> loader.count("null"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.code()).isEqualTo("RESEARCH_SOURCE_SCOPE_PARSE_FAILED"));
    }
}
