package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.config.NoteWeaveProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class CpaSessionRoutingTest {

    @Test
    void outgoingHttpRequestsReuseConversationIdentityAcrossChangedPrompts() throws Exception {
        List<String> sessions = new CopyOnWriteArrayList<>();
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String session = exchange.getRequestHeaders().getFirst("X-Session-ID");
            sessions.add(session == null ? "" : session);
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("data: {\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
            exchange.close();
        });
        server.start();
        try {
            ObjectMapper mapper = new ObjectMapper();
            NoteWeaveProperties properties = new NoteWeaveProperties(
                    null, null, null, null, null,
                    new NoteWeaveProperties.Llm(true,
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                            "ds-4.1", "test-only-key", 30));
            HttpChatLlmClient client = new HttpChatLlmClient(mapper, properties);
            ChatAnswerGenerationAdapter adapter = new ChatAnswerGenerationAdapter(
                    mock(JdbcTemplate.class), client, mock(com.noteweave.research.ResearchGeneratedSourceReadGate.class), false);
            List<String> tokens = new ArrayList<>();

            assertThat(adapter.generateInConversation("ws-A", "conversation-A", "first prompt", 1200, tokens::add))
                    .isEqualTo("OK");
            adapter.generateInConversation("ws-A", "conversation-A", "new evidence and compacted history", 1200, tokens::add);
            adapter.generateInConversation("ws-A", "conversation-B", "first prompt", 1200, tokens::add);
            adapter.generateInConversation("ws-B", "conversation-A", "first prompt", 1200, tokens::add);
            client.streamChat("system", "standalone request", 1200, tokens::add);

            assertThat(sessions).hasSize(5);
            assertThat(sessions.get(0)).startsWith("noteweave-chat-")
                    .isEqualTo(sessions.get(1)).doesNotContain("conversation-A");
            assertThat(sessions.get(2)).isNotEqualTo(sessions.get(0));
            assertThat(sessions.get(3)).isNotEqualTo(sessions.get(0));
            assertThat(sessions.get(4)).isEmpty(); // Singleton must not retain another conversation's ID.
            assertThat(mapper.readTree(bodies.get(0)).path("model").asText()).isEqualTo("ds-4.1");
            assertThat(mapper.readTree(bodies.get(1)).path("messages").get(1).path("content").asText())
                    .isEqualTo("new evidence and compacted history");
            assertThat(tokens).containsExactly("OK", "OK", "OK", "OK", "OK");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void deterministicIdentitySurvivesProcessRestartAndDoesNotCollapseMissingContext() {
        assertThat(ChatLlmClient.routingSessionId("chat", "ws", "conversation"))
                .isEqualTo(ChatLlmClient.routingSessionId("chat", "ws", "conversation"))
                .isNotEqualTo(ChatLlmClient.routingSessionId("artifact", "ws", "conversation"));
        assertThat(ChatLlmClient.routingSessionId("chat", "", "conversation")).isNull();
        assertThat(ChatLlmClient.routingSessionId("chat", "ws", null)).isNull();
    }
}
