package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.noteweave.common.BusinessException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

class ChatAnswerGenerationAdapterTest {

    @Test
    void disabledLlmShouldFailLoudWhenTemplateFallbackIsNotExplicitlyEnabled() {
        ChatLlmClient client = mock(ChatLlmClient.class);
        when(client.isEnabled()).thenReturn(false);
        ChatAnswerGenerationAdapter adapter = new ChatAnswerGenerationAdapter(
                mock(JdbcTemplate.class), client, false);

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
                mock(JdbcTemplate.class), client, true);
        List<String> chunks = new ArrayList<>();

        assertThat(adapter.generate("line one\nline two", 1200, chunks::add))
                .isEqualTo("line one\nline two");
        assertThat(chunks).isNotEmpty();
        assertThat(adapter.configuredModel()).isEqualTo("template-fallback");
    }
}
