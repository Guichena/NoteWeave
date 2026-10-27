package com.noteweave.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PARSE-01：解析服务失败按状态码分类。
 * 429/503/504 与超时可重试，415/422 是终态；Retry-After 要能被读出来并交给消费端退避。
 */
class HttpSourceDocumentParserStatusClassificationTest {

    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir Path temporary;

    @Test
    void rateLimitIsRetryableAndCarriesRetryAfter() throws Exception {
        SourceParserUnavailableException failure = failureFor(429, "7", 5);

        assertThat(failure.code()).isEqualTo("SOURCE_PARSER_RATE_LIMITED");
        assertThat(failure.retryable()).isTrue();
        assertThat(failure.retryAfter()).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void serviceUnavailableAndGatewayTimeoutAreRetryable() throws Exception {
        for (int status : new int[] {503, 504}) {
            SourceParserUnavailableException failure = failureFor(status, null, 5);
            assertThat(failure.code()).as("HTTP %d", status).isEqualTo("SOURCE_PARSER_UNAVAILABLE");
            assertThat(failure.retryable()).as("HTTP %d", status).isTrue();
            assertThat(failure.retryAfter()).isNull();
        }
    }

    @Test
    void unsupportedMediaTypeAndUnprocessableEntityAreTerminal() throws Exception {
        for (int status : new int[] {415, 422}) {
            SourceParserUnavailableException failure = failureFor(status, null, 5);
            assertThat(failure.code()).as("HTTP %d", status).isEqualTo("SOURCE_PARSER_REJECTED");
            assertThat(failure.retryable()).as("HTTP %d", status).isFalse();
        }
    }

    @Test
    void readTimeoutIsRetryable() throws Exception {
        SourceParserUnavailableException failure = failureFor(200, null, 1, 2_500L);

        assertThat(failure.code()).isEqualTo("SOURCE_PARSER_UNAVAILABLE");
        assertThat(failure.retryable()).isTrue();
    }

    @Test
    void retryAfterAcceptsSecondsHttpDatesAndGarbage() {
        assertThat(HttpSourceDocumentParserClient.parseRetryAfter("120")).isEqualTo(Duration.ofSeconds(120));
        assertThat(HttpSourceDocumentParserClient.parseRetryAfter("0")).isEqualTo(Duration.ZERO);
        assertThat(HttpSourceDocumentParserClient.parseRetryAfter("999999"))
                .isEqualTo(Duration.ofSeconds(3_600));
        assertThat(HttpSourceDocumentParserClient.parseRetryAfter("not-a-delay")).isNull();
        assertThat(HttpSourceDocumentParserClient.parseRetryAfter("  ")).isNull();

        String futureDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(30));
        assertThat(HttpSourceDocumentParserClient.parseRetryAfter(futureDate))
                .isNotNull()
                .satisfies(delay -> assertThat(delay).isBetween(Duration.ZERO, Duration.ofSeconds(60)));
    }

    private SourceParserUnavailableException failureFor(int status, String retryAfter, int timeoutSeconds)
            throws Exception {
        return failureFor(status, retryAfter, timeoutSeconds, 0L);
    }

    private SourceParserUnavailableException failureFor(
            int status, String retryAfter, int timeoutSeconds, long delayMillis) throws Exception {
        Path original = temporary.resolve("original.pdf");
        Files.write(original, new byte[] {37, 80, 68, 70, 10});
        String sha256 = SourceDocumentTextExtractor.sha256(original);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/internal/parse", exchange -> {
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (retryAfter != null) {
                exchange.getResponseHeaders().set("Retry-After", retryAfter);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        var properties = new SourceDocumentParserProperties(
                true, "http://127.0.0.1:" + server.getAddress().getPort(), 2, timeoutSeconds, 1000, "");
        var request = new SourceDocumentParserPort.Request(
                "workspace-1", "source-1", "snapshot-1", "资料.pdf", "application/pdf", sha256, 1000);
        try {
            new HttpSourceDocumentParserClient(properties, mapper).parse(request, original);
            throw new AssertionError("应当抛出 SourceParserUnavailableException");
        } catch (SourceParserUnavailableException failure) {
            return failure;
        } finally {
            server.stop(0);
        }
    }
}
