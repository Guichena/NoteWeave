package com.noteweave.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.noteweave.config.NoteWeaveProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpArtifactWorkerControlClientTest {

    @Test
    void providerAckRequestsHostOwnedResumeInsteadOfWorkerAutoResume() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<String> deferResume = new AtomicReference<>("");
        AtomicReference<String> requestBody = new AtomicReference<>("");
        server.createContext("/callbacks/acquisition/ack", exchange -> {
            deferResume.set(exchange.getRequestHeaders().getFirst("X-NoteWeave-Defer-Resume"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = """
                    {"operation":null,"receipt":null,"resumed_tasks":[]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
            NoteWeaveProperties properties = new NoteWeaveProperties(null, null,
                    new NoteWeaveProperties.Worker("http://127.0.0.1:" + server.getAddress().getPort()),
                    null, null, null);
            HttpArtifactWorkerControlClient client = new HttpArtifactWorkerControlClient(
                    mapper, properties, "worker-shared-secret");
            client.acknowledgeAcquisition(new ArtifactAcquisitionAckRequest(
                    "provider-token", "ACKNOWLEDGED", "", "", "", java.util.Map.of()));
            assertThat(deferResume.get()).isEqualTo("true");
            assertThat(mapper.readTree(requestBody.get()).path("callback_token").asText())
                    .isEqualTo("provider-token");
            assertThat(mapper.readTree(requestBody.get()).path("final_status").asText())
                    .isEqualTo("ACKNOWLEDGED");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldParseDirectFastApiControlResponseWithoutJavaApiEnvelope() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<String> receivedToken = new AtomicReference<>("");
        AtomicReference<String> requestBody = new AtomicReference<>("");
        server.createContext("/tasks/task-1/resume", exchange -> {
            receivedToken.set(exchange.getRequestHeaders().getFirst("X-NoteWeave-Internal-Token"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = """
                    {"task_id":"task-1","status":"COMPLETED","progress_events":5,"result_title":"Course Notes"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
            NoteWeaveProperties properties = new NoteWeaveProperties(
                    null,
                    null,
                    new NoteWeaveProperties.Worker("http://127.0.0.1:" + server.getAddress().getPort()),
                    null,
                    null,
                    null
            );
            HttpArtifactWorkerControlClient client = new HttpArtifactWorkerControlClient(
                    objectMapper,
                    properties,
                    "worker-shared-secret"
            );

            ArtifactWorkerExecutionResponse response = client.resumeTask(
                    "task-1",
                    new ArtifactWorkerResumeRequest("request-1", "delivery-1")
            );

            assertThat(response.taskId()).isEqualTo("task-1");
            assertThat(response.status()).isEqualTo("COMPLETED");
            assertThat(response.progressEvents()).isEqualTo(5);
            assertThat(receivedToken.get()).isEqualTo("worker-shared-secret");
            assertThat(objectMapper.readTree(requestBody.get()).path("request_id").asText())
                    .isEqualTo("request-1");
        } finally {
            server.stop(0);
        }
    }
}
