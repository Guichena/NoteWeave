package com.noteweave.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.noteweave.common.BusinessException;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.source.SourceTranscriptionPort;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class HttpSourceTranscriptionClientTest {
    @Test
    void queueFullIsDistinguishedAndFrozenTaskIdIsSent() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var query = new AtomicReference<String>();
        var token = new AtomicReference<String>();
        server.createContext("/internal/source-transcriptions", exchange -> {
            query.set(exchange.getRequestURI().getQuery());
            token.set(exchange.getRequestHeaders().getFirst("X-NoteWeave-Internal-Token"));
            exchange.getRequestBody().transferTo(java.io.OutputStream.nullOutputStream());
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        server.start();
        var file = Files.createTempFile("transcription-http-", ".mp3");
        try {
            Files.writeString(file, "媒体替身");
            var properties = new NoteWeaveProperties(null, null,
                    new NoteWeaveProperties.Worker("http://127.0.0.1:" + server.getAddress().getPort()), null, null, null);
            var client = new HttpSourceTranscriptionClient(properties, "test-token", 1, 2);
            var error = catchThrowableOfType(() -> client.submit(new SourceTranscriptionPort.Request(
                    "ws", "src", "snap", "audio.mp3", "audio/mpeg", "task-1"), file), BusinessException.class);
            assertThat(error.code()).isEqualTo("SOURCE_TRANSCRIPTION_QUEUE_FULL");
            assertThat(error.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(query.get()).contains("task_id=task-1");
            assertThat(token.get()).isEqualTo("test-token");
        } finally {
            server.stop(0);
            Files.deleteIfExists(file);
        }
    }
}
