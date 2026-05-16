package com.noteweave.personal.source.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.error.BusinessException;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SafeUrlContentFetcherTest {

    @Test
    void validateShouldRejectNonHttpSchemes() {
        SafeUrlContentFetcher fetcher = new SafeUrlContentFetcher(
                (uri, timeout) -> response(200, Map.of(), "ok"),
                host -> List.of(publicAddress(host)),
                3,
                1024
        );

        assertThatThrownBy(() -> fetcher.validate("ftp://example.test/resource"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("http");
    }

    @Test
    void validateShouldRejectLoopbackAndPrivateHostsWithoutCallingTransport() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SafeUrlContentFetcher fetcher = new SafeUrlContentFetcher(
                (uri, timeout) -> {
                    calls.incrementAndGet();
                    return response(200, Map.of(), "ok");
                },
                host -> List.of(InetAddress.getByName("127.0.0.1")),
                3,
                1024
        );

        assertThatThrownBy(() -> fetcher.validate("http://127.0.0.1/admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("unsafe");
        assertThat(calls).hasValue(0);
    }

    @Test
    void fetchShouldFollowSafeRedirectAndReturnBody() {
        SafeUrlContentFetcher fetcher = new SafeUrlContentFetcher(
                (uri, timeout) -> {
                    if ("https://example.test/start".equals(uri.toString())) {
                        return response(302, Map.of("location", List.of("https://cdn.example.test/final")), "");
                    }
                    if ("https://cdn.example.test/final".equals(uri.toString())) {
                        return response(200, Map.of("content-type", List.of("text/plain; charset=utf-8")), "final body");
                    }
                    throw new IllegalStateException("Unexpected uri " + uri);
                },
                host -> List.of(publicAddress(host)),
                3,
                1024
        );

        FetchedUrlContent fetched = fetcher.fetch("https://example.test/start");
        assertThat(new String(fetched.body(), StandardCharsets.UTF_8)).isEqualTo("final body");
        assertThat(fetched.contentType()).contains("text/plain");
    }

    @Test
    void fetchShouldRejectRedirectToBlockedTarget() throws Exception {
        SafeUrlContentFetcher fetcher = new SafeUrlContentFetcher(
                (uri, timeout) -> response(302, Map.of("location", List.of("http://127.0.0.1/internal")), ""),
                host -> {
                    if ("example.test".equals(host)) {
                        return List.of(publicAddress(host));
                    }
                    return List.of(InetAddress.getByName("127.0.0.1"));
                },
                3,
                1024
        );

        assertThatThrownBy(() -> fetcher.fetch("https://example.test/start"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("unsafe");
    }

    @Test
    void fetchShouldRejectOversizedResponseBodies() {
        SafeUrlContentFetcher fetcher = new SafeUrlContentFetcher(
                (uri, timeout) -> response(200, Map.of("content-type", List.of("text/plain")), "01234567890"),
                host -> List.of(publicAddress(host)),
                3,
                10
        );

        assertThatThrownBy(() -> fetcher.fetch("https://example.test/large"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("too large");
    }

    private static UrlFetchTransportResponse response(int statusCode, Map<String, List<String>> headers, String body) {
        return new UrlFetchTransportResponse(
                statusCode,
                headers,
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
        );
    }

    private static InetAddress publicAddress(String host) {
        try {
            return InetAddress.getByAddress(host, new byte[]{93, (byte) 184, 34, 10});
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
