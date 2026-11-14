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
                    new RerankClient.Hit(1, 0.91, 1, com.noteweave.answer.strategy.ScoreInfo.provider("rerank-v1", 0.91)),
                    new RerankClient.Hit(0, 0.72, 2, com.noteweave.answer.strategy.ScoreInfo.provider("rerank-v1", 0.72)));
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

    @Test
    void maxAttemptsOneDoesNotRetryRetryableHttpErrors() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/v1/rerank", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(500, 0);
            exchange.close();
        });
        server.start();

        try {
            OpenAiCompatibleRerankClient client = new OpenAiCompatibleRerankClient(
                    new ObjectMapper(),
                    properties(server));

            assertThatThrownBy(() -> client.rerank("question", List.of("document"), 1))
                    .isInstanceOf(RetrievalProviderException.class)
                    .hasMessageContaining("HTTP 500");
            assertThat(hits.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void interactiveAnswersStopWaitingAfterTheBudgetAndSkipDuringCooldown() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/v1/rerank", exchange -> {
            // 第一次请求卡住（线上实测重排服务挂起），之后恢复正常
            if (hits.incrementAndGet() == 1) {
                try {
                    Thread.sleep(4000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = "{\"model\":\"rerank-v1\",\"results\":[{\"index\":0,\"relevance_score\":0.5}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            // 线上配置：单次 20 秒、最多 3 次；交互预算 800 毫秒
            OpenAiCompatibleRerankClient client = new OpenAiCompatibleRerankClient(
                    new ObjectMapper(), properties(server, 20, 3));
            client.setInteractiveBudgetMillis(800);
            client.setInteractiveCooldownMillis(30_000);

            long started = System.nanoTime();
            assertThatThrownBy(() -> InteractiveRerankBudget.run(
                    () -> client.rerank("question", List.of("document"), 1)))
                    .isInstanceOf(RetrievalProviderException.class);
            assertThat((System.nanoTime() - started) / 1_000_000L).isLessThan(2_500L);
            assertThat(hits.get()).isEqualTo(1);

            // 冷却期内交互式重排直接跳过，不再发请求
            long skipped = System.nanoTime();
            assertThatThrownBy(() -> InteractiveRerankBudget.run(
                    () -> client.rerank("question", List.of("document"), 1)))
                    .isInstanceOf(RetrievalProviderException.class);
            assertThat((System.nanoTime() - skipped) / 1_000_000L).isLessThan(200L);
            assertThat(hits.get()).isEqualTo(1);

            // 后台调用不受交互预算和冷却影响；成功后交互式调用恢复
            assertThat(client.rerank("question", List.of("document"), 1).hits()).hasSize(1);
            assertThat(InteractiveRerankBudget.run(() -> client.rerank("question", List.of("document"), 1)).hits())
                    .hasSize(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void timedOutAttemptRetriesOnAFreshConnectionPool() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/v1/rerank", exchange -> {
            if (hits.incrementAndGet() == 1) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = "{\"model\":\"rerank-v1\",\"results\":[{\"index\":0,\"relevance_score\":0.5}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        AtomicInteger built = new AtomicInteger();
        try {
            OpenAiCompatibleRerankClient client = new OpenAiCompatibleRerankClient(
                    new ObjectMapper(), properties(server, 1, 2), () -> {
                        built.incrementAndGet();
                        return java.net.http.HttpClient.newBuilder()
                                .version(java.net.http.HttpClient.Version.HTTP_1_1).build();
                    });

            assertThat(client.rerank("question", List.of("document"), 1).hits()).hasSize(1);
            // 第一次超时后重建了连接池，重试在新连接上成功
            assertThat(built.get()).isEqualTo(2);
            assertThat(hits.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    private NoteWeaveProperties properties(HttpServer server, int timeoutSeconds, int maxAttempts) {
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
                        timeoutSeconds,
                        maxAttempts),
                null);
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
