package com.noteweave.retrieval.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.ChatLlmClient;
import com.noteweave.config.NoteWeaveProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 从真实 HTTP 响应验证解析边界，不用预先转换过的 Hit 代替畸形 JSON。 */
class F1RerankHttpRegressionTest {
    static Stream<String> invalidResponses() {
        return Stream.of(
            "{\"results\":[{\"index\":0}]}",
            "{\"results\":[{\"index\":0,\"score\":null}]}",
            "{\"results\":[{\"index\":0,\"score\":\"bad\"}]}",
            "{\"results\":[{\"index\":0,\"score\":true}]}",
            "{\"results\":[{\"index\":0.9,\"score\":0.2}]}",
            "{\"results\":[{\"index\":-1,\"score\":0.2}]}",
            "{\"results\":[{\"index\":2,\"score\":0.2}]}",
            "{\"results\":[{\"index\":0,\"score\":0.2},{\"index\":0,\"score\":0.3}]}",
            "{\"results\":[{\"index\":0,\"score\":1e999}]}",
            "{\"results\":[{\"index\":0,\"score\":NaN}]}",
            "{\"results\":[{\"index\":0,\"score\":0.2},{\"index\":1}]}",
            "{\"results\":[{\"index\":0,\"score\":0.2},{\"index\":2,\"score\":0.3}]}",
            "{\"results\":[]}", "{}", "",
            "{\"results\":[{\"index\":\"0\",\"score\":0.2}]}",
            "{\"results\":[{\"index\":false,\"score\":0.2}]}",
            "{\"results\":[{\"index\":0,\"score\":\"0.8\"}]}",
            "{\"results\":[{\"index\":null,\"score\":0.2}]}",
            "{\"results\":[{\"score\":0.2}]}",
            "{\"results\":[{\"index\":2147483648,\"score\":0.2}]}",
            "{\"results\":[{\"index\":0,\"relevance_score\":null,\"score\":0.2}]}");
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    void malformedHttpResponseTriggersExactlyOneFallback(String response) throws Exception {
        try (Fixture f = new Fixture(response)) {
            RerankClient.RerankResult result = f.client(true).rerank("q", List.of("a", "b", "batch tail"), 1);
            assertThat(result.model()).isEqualTo(RerankClient.LLM_FALLBACK_MODEL);
            assertThat(f.requests.get()).isEqualTo(1);
            verify(f.llm, times(1)).streamChat(anyString(), anyString(), anyInt(), any());
            assertThat(result.hits()).extracting(RerankClient.Hit::score).containsExactly(0.9);
            assertThat(result.hits().get(0).scoreInfo().origin()).isEqualTo("LLM_FALLBACK");
            assertThat(result.hits().get(0).scoreInfo().model()).isEqualTo("llm-listwise-v1");
            assertThat(result.hits().get(0).scoreInfo().eligibleForThreshold()).isFalse();
        }
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    void malformedHttpResponseFailsWhenFallbackIsUnavailable(String response) throws Exception {
        try (Fixture f = new Fixture(response)) {
            assertThatThrownBy(() -> f.client(false).rerank("q", List.of("a", "b", "batch tail"), 1))
                .isInstanceOf(RuntimeException.class);
            assertThat(f.requests.get()).isEqualTo(1);
            verify(f.llm, never()).streamChat(anyString(), anyString(), anyInt(), any());
        }
    }

    @Test
    void legitimateZeroAndPartialReturnRemainProviderResults() throws Exception {
        try (Fixture f = new Fixture("{\"results\":[{\"index\":1,\"score\":0}]}")) {
            var result = f.client(true).rerank("q", List.of("a", "b", "batch tail"), 2);
            assertThat(result.hits()).hasSize(1);
            assertThat(result.hits().get(0).score()).isZero();
            assertThat(result.hits().get(0).documentIndex()).isEqualTo(1);
            assertThat(f.requests.get()).isEqualTo(1);
            verify(f.llm, never()).streamChat(anyString(), anyString(), anyInt(), any());
        }
    }

    @Test
    void validExtraResultsAreValidatedThenLimitedToTopN() throws Exception {
        try (Fixture f = new Fixture("{\"results\":[{\"index\":1,\"score\":0.35},{\"index\":0,\"score\":0.149}]}")) {
            var result = f.client(true).rerank("q", List.of("a", "b"), 1);
            assertThat(result.hits()).hasSize(1);
            assertThat(result.hits().get(0).score()).isEqualTo(0.35);
            verify(f.llm, never()).streamChat(anyString(), anyString(), anyInt(), any());
        }
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    void directProviderRejectsRawMalformedJson(String response) throws Exception {
        try (Fixture f = new Fixture(response)) {
            assertThatThrownBy(() -> f.provider.rerank("q", List.of("a", "b", "tail"), 1))
                    .isInstanceOf(RetrievalProviderException.class);
            assertThat(f.requests.get()).isEqualTo(1);
        }
    }

    @Test
    void realProducerCarriesRawPrecisionAndDoesNotCalibrateAlias() throws Exception {
        try (Fixture f = new Fixture("{\"results\":[{\"index\":0,\"relevance_score\":0.149},{\"index\":1,\"score\":0.349}]}")) {
            var hits = f.provider.rerank("q", List.of("a", "b"), 2).hits();
            assertThat(hits).extracting(h -> h.scoreInfo().value()).containsExactly(0.149, 0.349);
            assertThat(hits).allSatisfy(h -> {
                assertThat(h.scoreInfo().origin()).isEqualTo("PROVIDER_MODEL");
                assertThat(h.scoreInfo().model()).isEqualTo("noteweave-rerank");
                assertThat(h.scoreInfo().eligibleForThreshold()).isFalse();
            });
        }
    }

    @Test
    void declaredUnitIntervalRejectsOutOfRangeButUnknownScaleAllowsFiniteScore() throws Exception {
        try (Fixture f = new Fixture("{\"results\":[{\"index\":0,\"score\":-2}]}")) {
            assertThat(f.provider.rerank("q", List.of("a"), 1).hits().get(0).score()).isEqualTo(-2);
            var strict = new OpenAiCompatibleRerankClient(new ObjectMapper(), new NoteWeaveProperties(
                    null, null, null, null, null, null,
                    new NoteWeaveProperties.Rerank(true, "http://127.0.0.1:" + f.server.getAddress().getPort() + "/rerank",
                            "actual", "", 2, 100, 2, 1, "UNIT_INTERVAL", "KNOWN_PROVIDER_015_035"), null));
            assertThatThrownBy(() -> strict.rerank("q", List.of("a"), 1)).isInstanceOf(RetrievalProviderException.class);
        }
    }

    @Test
    void explicitScaleAndPolicyAreProducedWithoutRounding() throws Exception {
        try (Fixture f = new Fixture("{\"results\":[{\"index\":0,\"score\":0.149}]}")) {
            var strict = strictProvider(f);
            var info = strict.rerank("q", List.of("a"), 1).hits().get(0).scoreInfo();
            assertThat(info.value()).isEqualTo(0.149);
            assertThat(info.scoreScale()).isEqualTo("UNIT_INTERVAL");
            assertThat(info.thresholdPolicy()).isEqualTo("KNOWN_PROVIDER_015_035");
            assertThat(info.eligibleForThreshold()).isTrue();
        }
        try (Fixture f = new Fixture("{\"results\":[{\"index\":0,\"score\":1.001}]}")) {
            assertThatThrownBy(() -> strictProvider(f).rerank("q", List.of("a"), 1))
                    .isInstanceOf(RetrievalProviderException.class);
        }
    }

    private OpenAiCompatibleRerankClient strictProvider(Fixture f) {
        return new OpenAiCompatibleRerankClient(new ObjectMapper(), new NoteWeaveProperties(
                null, null, null, null, null, null,
                new NoteWeaveProperties.Rerank(true, "http://127.0.0.1:" + f.server.getAddress().getPort() + "/rerank",
                        "actual", "", 2, 100, 2, 1, "UNIT_INTERVAL", "KNOWN_PROVIDER_015_035"), null));
    }

    private static class Fixture implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger requests = new AtomicInteger();
        final ChatLlmClient llm = mock(ChatLlmClient.class);
        final OpenAiCompatibleRerankClient provider;
        Fixture(String response) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/rerank", exchange -> {
                requests.incrementAndGet();
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            provider = new OpenAiCompatibleRerankClient(new ObjectMapper(), new NoteWeaveProperties(
                null, null, null, null, null, null,
                new NoteWeaveProperties.Rerank(true, "http://127.0.0.1:" + server.getAddress().getPort() + "/rerank",
                    "noteweave-rerank", "", 2, 100, 2, 1), null));
            when(llm.isEnabled()).thenReturn(true);
            when(llm.streamChat(anyString(), anyString(), anyInt(), any())).thenReturn("{\"scores\":[1,9,0]}");
        }
        @SuppressWarnings("unchecked")
        FallbackRerankClient client(boolean enabled) {
            ObjectProvider<ChatLlmClient> lookup = mock(ObjectProvider.class);
            when(lookup.getIfAvailable()).thenReturn(llm);
            return new FallbackRerankClient(provider, lookup, new ObjectMapper(), enabled);
        }
        public void close() { server.stop(0); }
    }
}
