package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.config.NoteWeaveProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpChatLlmClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldStreamTokensThroughOfficialSdkWithBudgetAndAuthorization() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        start(200, """
                data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[{"index":0,"delta":{"role":"assistant"}}]}

                data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[{"index":0,"delta":{"content":"引用"}}]}

                data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[{"index":0,"delta":{"content":"回答"},"finish_reason":"stop"}]}

                data: [DONE]

                """, requestBody, authorization);
        List<String> tokens = new ArrayList<>();

        String answer = client("test-model", "chat-secret").streamChat("system", "user", 1800, tokens::add);

        assertThat(answer).isEqualTo("引用回答");
        assertThat(tokens).containsExactly("引用", "回答");
        assertThat(authorization.get()).isEqualTo("Bearer chat-secret");
        JsonNode body = mapper.readTree(requestBody.get());
        assertThat(body.path("model").asText()).isEqualTo("test-model");
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.path("max_tokens").asInt()).isEqualTo(1800);
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("messages").get(1).path("content").asText()).isEqualTo("user");
        assertThat(body.has("thinking")).isFalse();
    }

    @Test
    void shouldDisableThinkingForGlm5ToPreserveVisibleAnswerBudget() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        start(200, chunk("答案", "stop") + "data: [DONE]\n\n", requestBody, new AtomicReference<>());

        client("glm-5.3-flash", "").streamChat("system", "user", 2400, ignored -> {});

        JsonNode body = mapper.readTree(requestBody.get());
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
    }

    @Test
    void shouldRejectSuccessfulStreamWithoutContent() throws Exception {
        start(200, chunk(null, "stop") + "data: [DONE]\n\n", new AtomicReference<>(), new AtomicReference<>());

        assertThatThrownBy(() -> client("test-model", "").streamChat("system", "user", 100, ignored -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without content");
    }

    @Test
    void shouldRejectStreamThatEndsWithoutFinishReason() throws Exception {
        start(200, chunk("partial", null), new AtomicReference<>(), new AtomicReference<>());

        assertThatThrownBy(() -> client("test-model", "").streamChat("system", "user", 100, ignored -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without finish_reason");
    }

    @Test
    void shouldReportProviderHttpErrors() throws Exception {
        start(401, "{\"error\":{\"message\":\"bad key\"}}", new AtomicReference<>(), new AtomicReference<>());

        assertThatThrownBy(() -> client("test-model", "").streamChat("system", "user", 100, ignored -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LLM stream call failed");
    }

    @Test
    void shouldNotFollowProviderRedirects() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> redirectedAuthorization = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().add("Location", "/elsewhere");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/elsewhere", exchange -> {
            redirectedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        assertThatThrownBy(() -> client("test-model", "chat-secret").streamChat("system", "user", 100, ignored -> {}))
                .isInstanceOf(IllegalStateException.class);
        assertThat(redirectedAuthorization.get()).isNull();
    }

    private void start(
            int status,
            String body,
            AtomicReference<String> requestBody,
            AtomicReference<String> authorization
    ) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add(
                    "Content-Type", status == 200 ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    private String chunk(String content, String finishReason) {
        String delta = content == null ? "{}" : "{\"content\":\"" + content + "\"}";
        String finish = finishReason == null ? "" : ",\"finish_reason\":\"" + finishReason + "\"";
        return "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\","
                + "\"choices\":[{\"index\":0,\"delta\":" + delta + finish + "}]}\n\n";
    }

    private HttpChatLlmClient client(String model, String apiKey) {
        NoteWeaveProperties properties = new NoteWeaveProperties(
                null, null, null, null, null,
                new NoteWeaveProperties.Llm(
                        true,
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                        model, apiKey, 30));
        return new HttpChatLlmClient(mapper, properties);
    }
}
