package com.noteweave.retrieval.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.config.NoteWeaveProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleEmbeddingClientTest {

    @Test
    void embedsQueryWithConfiguredModelDimensionsAndAuthorization() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/v1/embeddings", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = """
                    {"model":"embedding-v1","data":[{"index":0,"embedding":[0.1,0.2,0.3]}],"usage":{"total_tokens":7}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            ObjectMapper mapper = new ObjectMapper();
            OpenAiCompatibleEmbeddingClient client = new OpenAiCompatibleEmbeddingClient(
                    mapper,
                    properties(server, 3, 2, 100, 1));

            EmbeddingClient.EmbeddingResult result = client.embedQuery("semantic query");

            assertThat(result.model()).isEqualTo("embedding-v1");
            assertThat(result.dimensions()).isEqualTo(3);
            assertThat(result.totalTokens()).isEqualTo(7);
            assertThat(result.singleVector()).containsExactly(0.1f, 0.2f, 0.3f);
            assertThat(authorization.get()).isEqualTo("Bearer embedding-secret");
            JsonNode payload = mapper.readTree(requestBody.get());
            assertThat(payload.path("model").asText()).isEqualTo("embedding-v1");
            assertThat(payload.path("dimensions").asInt()).isEqualTo(3);
            assertThat(payload.path("input").get(0).asText()).isEqualTo("semantic query");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void batchesDocumentsAndTruncatesInputs() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger requestCount = new AtomicInteger();
        AtomicReference<String> longestInput = new AtomicReference<>("");
        server.createContext("/v1/embeddings", exchange -> {
            JsonNode payload = mapper.readTree(exchange.getRequestBody());
            JsonNode inputs = payload.path("input");
            inputs.forEach(input -> {
                if (input.asText().length() > longestInput.get().length()) {
                    longestInput.set(input.asText());
                }
            });
            StringBuilder data = new StringBuilder();
            for (int i = 0; i < inputs.size(); i++) {
                if (i > 0) {
                    data.append(',');
                }
                data.append("{\"index\":").append(i).append(",\"embedding\":[0.1,0.2,0.3]}");
            }
            requestCount.incrementAndGet();
            byte[] body = ("{\"model\":\"embedding-v1\",\"data\":[" + data
                    + "],\"usage\":{\"total_tokens\":5}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            OpenAiCompatibleEmbeddingClient client = new OpenAiCompatibleEmbeddingClient(
                    mapper,
                    properties(server, 3, 2, 5, 1));

            EmbeddingClient.EmbeddingResult result = client.embedDocuments(
                    List.of("123456789", "second", "third"));

            assertThat(result.vectors()).hasSize(3);
            assertThat(result.totalTokens()).isEqualTo(10);
            assertThat(requestCount.get()).isEqualTo(2);
            assertThat(longestInput.get()).hasSize(5);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsVectorsWithUnexpectedDimensions() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            byte[] body = """
                    {"data":[{"index":0,"embedding":[0.1,0.2]}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            OpenAiCompatibleEmbeddingClient client = new OpenAiCompatibleEmbeddingClient(
                    new ObjectMapper(),
                    properties(server, 3, 2, 100, 1));

            assertThatThrownBy(() -> client.embedQuery("query"))
                    .isInstanceOf(RetrievalProviderException.class)
                    .extracting(ex -> ((RetrievalProviderException) ex).errorCode())
                    .isEqualTo("EMBEDDING_DIMENSION_MISMATCH");
        } finally {
            server.stop(0);
        }
    }

    private NoteWeaveProperties properties(
            HttpServer server,
            int dimensions,
            int batchSize,
            int maxCharacters,
            int maxAttempts
    ) {
        return new NoteWeaveProperties(
                null, null, null, null, null,
                new NoteWeaveProperties.Embedding(
                        true,
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/embeddings",
                        "embedding-v1",
                        "embedding-secret",
                        dimensions,
                        batchSize,
                        2,
                        2,
                        maxCharacters,
                        maxAttempts),
                null,
                null);
    }
}
