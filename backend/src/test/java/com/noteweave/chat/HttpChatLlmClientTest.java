package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.config.NoteWeaveProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpChatLlmClientTest {

    @Test
    void shouldSendStrategyOutputBudgetToCompatibleChatCompletionRequest() {
        NoteWeaveProperties properties = new NoteWeaveProperties(
                null, null, null, null, null,
                new NoteWeaveProperties.Llm(
                        true, "http://localhost:18080/v1/chat/completions",
                        "test-model", "", 30));
        HttpChatLlmClient client = new HttpChatLlmClient(new ObjectMapper(), properties);

        Map<String, Object> body = client.buildRequestBody("system", "user", 1800);

        assertThat(body)
                .containsEntry("model", "test-model")
                .containsEntry("stream", true)
                .containsEntry("max_tokens", 1800);
        assertThat(body.get("messages")).isEqualTo(List.of(
                Map.of("role", "system", "content", "system"),
                Map.of("role", "user", "content", "user")));
    }
}
