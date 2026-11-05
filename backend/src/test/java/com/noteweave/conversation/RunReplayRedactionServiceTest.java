package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RunReplayRedactionServiceTest {

    private final RunReplayRedactionService service = new RunReplayRedactionService(
            org.mockito.Mockito.mock(JdbcTemplate.class), new ObjectMapper());

    @Test
    void shouldRejectNonArraySourceScopeInsteadOfTreatingItAsNoReference() {
        assertThatThrownBy(() -> invoke(
                "artifactSourceScopeContains", "{\"source_id\":\"source-1\"}", "source-1"))
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Stored Artifact source scope JSON must be an array");
    }

    @Test
    void shouldRejectNonObjectUpstreamRefInsteadOfTreatingItAsNoReference() {
        assertThatThrownBy(() -> invoke(
                "artifactUpstreamReferences", "[\"source-1\"]", "source-1", null, Set.of("snapshot-1")))
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Stored Artifact upstream refs JSON contains a non-object item");
    }

    @Test
    void shouldRejectNonArraySourceScopeWhenRedactingBody() {
        assertThatThrownBy(() -> invoke(
                "redactArtifactSourceBody", "{\"source_id\":\"source-1\"}", "source-1"))
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Stored Artifact source scope JSON must be an array");
    }

    /** 契约按 snake_case 写库，脱敏必须清掉 system_prompt 与 allowed_page_titles 本身。 */
    @Test
    @SuppressWarnings("unchecked")
    void shouldClearStoredSnakeCaseContractFieldsWhenRedactingAnswerRun() throws Exception {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        org.mockito.Mockito.when(jdbc.query(
                        org.mockito.ArgumentMatchers.startsWith("select generation_contract_json"),
                        org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.ResultSetExtractor.class),
                        org.mockito.ArgumentMatchers.eq("workspace-1"),
                        org.mockito.ArgumentMatchers.eq("run-1")))
                .thenReturn("{\"schema_version\":\"generation-contract-v1\",\"system_prompt\":\"含资料标题的提示\","
                        + "\"allowed_page_titles\":[\"已撤销页面\"]}");
        RunReplayRedactionService redaction = new RunReplayRedactionService(jdbc, new ObjectMapper());

        redaction.redactAnswerRun("workspace-1", "run-1");

        org.mockito.ArgumentCaptor<Object> written = org.mockito.ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(jdbc).update(
                org.mockito.ArgumentMatchers.eq(
                        "update answer_run set generation_contract_json=? where workspace_id=? and id=?"),
                written.capture(),
                org.mockito.ArgumentMatchers.eq("workspace-1"),
                org.mockito.ArgumentMatchers.eq("run-1"));
        var stored = new ObjectMapper().readTree(String.valueOf(written.getValue()));
        org.assertj.core.api.Assertions.assertThat(stored.path("system_prompt").asText()).isEmpty();
        org.assertj.core.api.Assertions.assertThat(stored.path("allowed_page_titles")).isEmpty();
        org.assertj.core.api.Assertions.assertThat(stored.has("systemPrompt")).isFalse();
        org.assertj.core.api.Assertions.assertThat(stored.path("schema_version").asText())
                .isEqualTo("generation-contract-v1");
    }

    private Object invoke(String name, Object... args) throws Exception {
        Method method = switch (name) {
            case "artifactSourceScopeContains" -> service.getClass().getDeclaredMethod(
                    name, String.class, String.class);
            case "artifactUpstreamReferences" -> service.getClass().getDeclaredMethod(
                    name, String.class, String.class, String.class, Set.class);
            case "redactArtifactSourceBody" -> service.getClass().getDeclaredMethod(
                    name, String.class, String.class);
            default -> throw new IllegalArgumentException(name);
        };
        method.setAccessible(true);
        return method.invoke(service, args);
    }
}
