package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.config.NoteWeaveProperties;
import java.util.List;
import java.util.Map;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    @Test
    void shouldDisableThinkingForGlm5ToPreserveVisibleAnswerBudget() {
        NoteWeaveProperties properties = new NoteWeaveProperties(
                null, null, null, null, null,
                new NoteWeaveProperties.Llm(
                        true, "http://localhost:18080/v1/chat/completions",
                        "glm-5.3-flash", "", 30));
        HttpChatLlmClient client = new HttpChatLlmClient(new ObjectMapper(), properties);

        Map<String, Object> body = client.buildRequestBody("system", "user", 2400);

        assertThat(body).containsEntry("thinking", Map.of("type", "disabled"));
    }

    @Test
    void shouldRejectSuccessfulStreamWithoutContent() {
        HttpChatLlmClient client = client();
        String sse = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n"
                + "data: [DONE]\n\n";

        assertThatThrownBy(() -> client.consumeStream(stream(sse), ignored -> {}))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("without content");
    }

    @Test
    void shouldRejectMalformedSsePayload() {
        HttpChatLlmClient client = client();

        assertThatThrownBy(() -> client.consumeStream(stream("data: {broken}\n\n"), ignored -> {}))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void shouldRejectContentStreamWithoutDoneMarker() {
        HttpChatLlmClient client = client();

        assertThatThrownBy(() -> client.consumeStream(
                stream("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"),
                ignored -> {}))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("without [DONE]");
    }

    @Test
    void shouldConsumeContentAndIgnoreProtocolOnlyEvents() throws IOException {
        HttpChatLlmClient client = client();
        String sse = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\n"
                + "data: [DONE]\n\n";

        assertThat(client.consumeStream(stream(sse), ignored -> {})).isEqualTo("answer");
    }

    private HttpChatLlmClient client() {
        NoteWeaveProperties properties = new NoteWeaveProperties(
                null, null, null, null, null,
                new NoteWeaveProperties.Llm(
                        true, "http://localhost:18080/v1/chat/completions",
                        "test-model", "", 30));
        return new HttpChatLlmClient(new ObjectMapper(), properties);
    }

    private ByteArrayInputStream stream(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
