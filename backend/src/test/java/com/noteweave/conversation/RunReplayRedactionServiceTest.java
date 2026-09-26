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
