package com.noteweave.worker;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

class ArtifactWorkerRestClientFactoryTest {

    @Test
    void shouldEnforceConfiguredReadTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(1_500);
                exchange.sendResponseHeaders(204, -1);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        try {
            var client = ArtifactWorkerRestClientFactory.create(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "",
                    1,
                    1
            );

            assertThatThrownBy(() -> client.get().uri("/slow").retrieve().toBodilessEntity())
                    .isInstanceOf(RestClientException.class);
        } finally {
            server.stop(0);
        }
    }
}
