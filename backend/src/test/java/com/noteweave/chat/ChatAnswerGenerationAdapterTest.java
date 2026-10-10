package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.noteweave.common.BusinessException;
import com.noteweave.research.ResearchGeneratedSourceReadGate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ChatAnswerGenerationAdapterTest {

    @Test
    void disabledLlmShouldFailLoudWhenTemplateFallbackIsNotExplicitlyEnabled() {
        ChatLlmClient client = mock(ChatLlmClient.class);
        when(client.isEnabled()).thenReturn(false);
        ChatAnswerGenerationAdapter adapter = new ChatAnswerGenerationAdapter(
                mock(JdbcTemplate.class), client, mock(ResearchGeneratedSourceReadGate.class), false);

        assertThatThrownBy(() -> adapter.generate("template draft", 1200, ignored -> {}))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo("ANSWER_LLM_CONFIGURATION_REQUIRED");
                    assertThat(error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });
        assertThat(adapter.configuredModel()).isEqualTo("unconfigured");
    }

    @Test
    void testProfileMayExplicitlyEnableTemplateReplay() {
        ChatLlmClient client = mock(ChatLlmClient.class);
        when(client.isEnabled()).thenReturn(false);
        ChatAnswerGenerationAdapter adapter = new ChatAnswerGenerationAdapter(
                mock(JdbcTemplate.class), client, mock(ResearchGeneratedSourceReadGate.class), true);
        List<String> chunks = new ArrayList<>();

        assertThat(adapter.generate("line one\nline two", 1200, chunks::add))
                .isEqualTo("line one\nline two");
        assertThat(chunks).isNotEmpty();
        assertThat(adapter.configuredModel()).isEqualTo("template-fallback");
    }

    @Test
    void templateFallbackShouldRejectEmptyDraft() {
        ChatLlmClient client = mock(ChatLlmClient.class);
        when(client.isEnabled()).thenReturn(false);
        ChatAnswerGenerationAdapter adapter = new ChatAnswerGenerationAdapter(
                mock(JdbcTemplate.class), client, mock(ResearchGeneratedSourceReadGate.class), true);

        assertThatThrownBy(() -> adapter.generate("  ", 1200, ignored -> {}))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo("ANSWER_TEMPLATE_EMPTY_RESPONSE");
                    assertThat(error.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                });
    }

    @Test
    void enabledLlmShouldRejectEmptyGeneration() {
        ChatLlmClient client = mock(ChatLlmClient.class);
        when(client.isEnabled()).thenReturn(true);
        when(client.streamChat(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.<java.util.function.Consumer<String>>any()))
                .thenReturn("  ");
        ChatAnswerGenerationAdapter adapter = new ChatAnswerGenerationAdapter(
                mock(JdbcTemplate.class), client, mock(ResearchGeneratedSourceReadGate.class), false);

        assertThatThrownBy(() -> adapter.generate("draft", 1200, ignored -> {}))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo("ANSWER_LLM_EMPTY_RESPONSE");
                    assertThat(error.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                });
    }

    @Test
    void replayChunksShouldNotEmitEmptyDelta() {
        ChatAnswerGenerationAdapter adapter = new ChatAnswerGenerationAdapter(
                mock(JdbcTemplate.class), mock(ChatLlmClient.class),
                mock(ResearchGeneratedSourceReadGate.class), true);

        assertThat(adapter.replayChunks("  ")).isEmpty();
    }

    @Test
    void revokedResearchCitationMustNotReachAnswerGenerationMaterial() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:answer-citation-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table conversation_message(id varchar(36), workspace_id varchar(36), content varchar(500), assistant_request_id varchar(36))");
        jdbc.execute("create table answer_run(assistant_request_id varchar(36), maximum_output_tokens int)");
        jdbc.execute("create table source(id varchar(36), generated_by varchar(64), generated_ref_id varchar(36))");
        jdbc.execute("create table citation(id varchar(36), workspace_id varchar(36), source_id varchar(36), title varchar(100), quote_text varchar(500))");
        jdbc.execute("create table message_citation(message_id varchar(36), citation_id varchar(36), sort_order int)");
        jdbc.update("insert into conversation_message values ('message', 'workspace', 'draft', 'request')");
        jdbc.update("insert into source values ('source', 'research_agent', 'run')");
        jdbc.update("insert into citation values ('citation', 'workspace', 'source', 'Report', 'secret quote')");
        jdbc.update("insert into message_citation values ('message', 'citation', 0)");
        ResearchGeneratedSourceReadGate gate = mock(ResearchGeneratedSourceReadGate.class);
        ChatAnswerGenerationAdapter adapter = new ChatAnswerGenerationAdapter(
                jdbc, mock(ChatLlmClient.class), gate, true);
        assertThat(adapter.load("request").citationLines()).singleElement().asString()
                .contains("secret quote");
        doThrow(new BusinessException("RESEARCH_SOURCE_CONTEXT_REDACTED", "revoked", HttpStatus.CONFLICT))
                .when(gate).requireReadable("workspace", "research_agent", "run");
        assertThatThrownBy(() -> adapter.load("request"))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("RESEARCH_SOURCE_CONTEXT_REDACTED"));
    }
}
