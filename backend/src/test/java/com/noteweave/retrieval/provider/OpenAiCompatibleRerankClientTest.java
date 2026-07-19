package com.noteweave.retrieval.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.config.NoteWeaveProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleRerankClientTest {

    @Test
    void reranksBoundedDocumentsAndPreservesProviderOrder() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/v1/rerank", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = """
                    {"model":"rerank-v1","results":[
                      {"index":1,"relevance_score":0.91},
                      {"index":0,"relevance_score":0.72}
                    ]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            OpenAiCompatibleRerankClient client = new OpenAiCompatibleRerankClient(
                    mapper,
                    properties(server));

            RerankClient.RerankResult result = client.rerank(
                    "question",
                    List.of("123456789", "second document", "ignored by batch limit"),
                    2);

            assertThat(result.model()).isEqualTo("rerank-v1");
            assertThat(result.hits()).containsExactly(
                    new RerankClient.Hit(1, 0.91, 1),
                    new RerankClient.Hit(0, 0.72, 2));
            JsonNode payload = mapper.readTree(requestBody.get());
            assertThat(payload.path("query").asText()).isEqualTo("question");
            assertThat(payload.path("documents")).hasSize(2);
            assertThat(payload.path("documents").get(0).asText()).isEqualTo("12345");
            assertThat(payload.path("top_n").asInt()).isEqualTo(2);
            assertThat(payload.path("return_documents").asBoolean()).isFalse();
        } finally {
            server.stop(0);
        }
    }

    private NoteWeaveProperties properties(HttpServer server) {
        return new NoteWeaveProperties(
                null, null, null, null, null,
                null,
                new NoteWeaveProperties.Rerank(
                        true,
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/rerank",
                        "rerank-v1",
                        "rerank-secret",
                        2,
                        5,
                        2,
                        1),
                null);
    }
}
