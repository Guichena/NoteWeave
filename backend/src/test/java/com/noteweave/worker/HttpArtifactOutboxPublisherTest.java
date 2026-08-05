package com.noteweave.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.config.NoteWeaveProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpArtifactOutboxPublisherTest {

    @Test
    void shouldAuthenticateJavaToWorkerRunRequest() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<String> token = new AtomicReference<>("");
        AtomicReference<String> deliveryToken = new AtomicReference<>("");
        AtomicReference<String> method = new AtomicReference<>("");
        server.createContext("/tasks/task-auth/run", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("X-NoteWeave-Internal-Token"));
            deliveryToken.set(exchange.getRequestHeaders().getFirst(
                    "X-NoteWeave-Outbox-Delivery-Token"
            ));
            method.set(exchange.getRequestMethod());
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();

        try {
            NoteWeaveProperties properties = new NoteWeaveProperties(
                    null,
                    null,
                    new NoteWeaveProperties.Worker("http://127.0.0.1:" + server.getAddress().getPort()),
                    null,
                    null,
                    null
            );
            HttpArtifactOutboxPublisher publisher = new HttpArtifactOutboxPublisher(
                    new ObjectMapper(),
                    properties,
                    "worker-shared-secret"
            );

            publisher.publish(
                    "noteweave.artifact.job",
                    "artifact-1",
                    "{\"task_id\":\"task-auth\"}",
                    "delivery-token-1"
            );

            assertThat(method.get()).isEqualTo("POST");
            assertThat(token.get()).isEqualTo("worker-shared-secret");
            assertThat(deliveryToken.get()).isEqualTo("delivery-token-1");
        } finally {
            server.stop(0);
        }
    }
}
