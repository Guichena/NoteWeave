package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.config.NoteWeaveProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpArtifactWorkerExportClientTest {

    @Test
    void fetchesWorkerFileWithArtifactInternalToken() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<String> receivedToken = new AtomicReference<>();
        server.createContext("/tasks/task-1/exports/frame-1.png", exchange -> {
            receivedToken.set(exchange.getRequestHeaders().getFirst("X-NoteWeave-Internal-Token"));
            byte[] file = "frame-bytes".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, file.length);
            exchange.getResponseBody().write(file);
            exchange.close();
        });
        server.start();
        try {
            NoteWeaveProperties properties = new NoteWeaveProperties(null, null,
                    new NoteWeaveProperties.Worker("http://127.0.0.1:" + server.getAddress().getPort()),
                    null, null, null);
            HttpArtifactWorkerExportClient client = new HttpArtifactWorkerExportClient(
                    properties, "artifact-worker-secret", 3, 30);

            assertThat(client.fetch("task-1", "frame-1.png"))
                    .isEqualTo("frame-bytes".getBytes(StandardCharsets.UTF_8));
            assertThat(receivedToken.get()).isEqualTo("artifact-worker-secret");
        } finally {
            server.stop(0);
        }
    }
}
