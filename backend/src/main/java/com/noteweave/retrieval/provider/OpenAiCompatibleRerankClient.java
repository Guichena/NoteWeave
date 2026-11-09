package com.noteweave.retrieval.provider;

import com.noteweave.answer.strategy.ScoreInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.config.NoteWeaveProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class OpenAiCompatibleRerankClient implements RerankClient {
    private static final String ERROR_DISABLED = "RERANK_PROVIDER_DISABLED";
    private static final String ERROR_REQUEST = "RERANK_PROVIDER_REQUEST_FAILED";
    private static final String ERROR_RESPONSE = "RERANK_PROVIDER_RESPONSE_INVALID";

    private final ObjectMapper objectMapper;
    private final java.util.function.Supplier<HttpClient> clientFactory;
    private volatile HttpClient httpClient;
    private final boolean enabled;
    private final URI endpoint;
    private final String model;
    private final String scoreScale;
    private final String thresholdPolicy;
    private final String apiKey;
    private final int batchSize;
    private final int maxDocumentCharacters;
    private final Duration timeout;
    private final int maxAttempts;
    /** 交互式回答单次重排的总时长上限（含重试）。 */
    private Duration interactiveBudget = Duration.ofMillis(6000);
    /** 交互式重排超时或失败后，在这段时间内直接跳过重排，避免并发回答和同一回答的下一次重排再等一轮。 */
    private Duration interactiveCooldown = Duration.ofSeconds(30);
    private static final Duration MIN_RETRY_WINDOW = Duration.ofMillis(1500);
    private volatile long interactiveSkipUntilNanos;

    @Autowired
    public OpenAiCompatibleRerankClient(ObjectMapper objectMapper, NoteWeaveProperties properties) {
        // HTTP/1.1：HTTP/2 下所有请求复用一条连接，这条连接空闲后被网络静默断开时，
        // JDK 客户端超时也不会丢弃它，之后每次重排都等满超时（线上空闲 44 分钟后每次 60 秒）
        this(objectMapper, properties, () -> HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    OpenAiCompatibleRerankClient(
            ObjectMapper objectMapper,
            NoteWeaveProperties properties,
            HttpClient httpClient
    ) {
        this(objectMapper, properties, () -> httpClient);
    }

    OpenAiCompatibleRerankClient(
            ObjectMapper objectMapper,
            NoteWeaveProperties properties,
            java.util.function.Supplier<HttpClient> clientFactory
    ) {
        NoteWeaveProperties.Rerank config = properties.rerank();
        this.objectMapper = objectMapper;
        this.clientFactory = clientFactory;
        this.httpClient = clientFactory.get();
        this.enabled = config.enabled()
                && !text(config.endpoint()).isBlank()
                && !text(config.model()).isBlank();
        this.endpoint = enabled
                ? ProviderEndpointSecurity.requireSecureOrLocal(config.endpoint())
                : URI.create("http://localhost/disabled");
        this.model = text(config.model());
        this.scoreScale = config.scoreScale();
        this.thresholdPolicy = config.thresholdPolicy();
        this.apiKey = text(config.apiKey());
        this.batchSize = Math.max(1, config.batchSize());
        this.maxDocumentCharacters = Math.max(1, config.maxDocumentCharacters());
        this.timeout = Duration.ofSeconds(Math.max(1L, config.timeoutSeconds()));
        this.maxAttempts = Math.max(1, config.maxAttempts());
    }

    @org.springframework.beans.factory.annotation.Value("${noteweave.rerank.interactive-budget-ms:6000}")
    void setInteractiveBudgetMillis(long millis) {
        this.interactiveBudget = Duration.ofMillis(Math.max(500L, millis));
    }

    @org.springframework.beans.factory.annotation.Value("${noteweave.rerank.interactive-cooldown-ms:30000}")
    void setInteractiveCooldownMillis(long millis) {
        this.interactiveCooldown = Duration.ofMillis(Math.max(0L, millis));
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public RerankResult rerank(String query, List<String> documents, int topN) {
        ensureEnabled();
        if (documents == null || documents.isEmpty() || topN <= 0) {
            return new RerankResult(List.of(), model);
        }
        int requestCount = Math.min(documents.size(), batchSize);
        List<String> requestDocuments = documents.subList(0, requestCount).stream()
                .map(this::limit)
                .toList();
        int effectiveTopN = Math.min(Math.max(1, topN), requestDocuments.size());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("query", text(query));
        body.put("documents", requestDocuments);
        body.put("top_n", effectiveTopN);
        body.put("return_documents", false);

        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(body);
        } catch (Exception ex) {
            throw new RetrievalProviderException(ERROR_REQUEST, "Rerank request serialization failed", ex);
        }

        boolean interactive = InteractiveRerankBudget.active();
        long deadline = interactive ? System.nanoTime() + interactiveBudget.toNanos() : Long.MAX_VALUE;
        if (interactive && System.nanoTime() < interactiveSkipUntilNanos) {
            throw new RetrievalProviderException(ERROR_REQUEST, "Rerank provider recently timed out; skipped for interactive answer");
        }
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Duration attemptTimeout = timeout;
            if (interactive) {
                Duration remaining = Duration.ofNanos(deadline - System.nanoTime());
                if (attempt > 1 && remaining.compareTo(MIN_RETRY_WINDOW) < 0) {
                    markInteractiveFailure();
                    throw new RetrievalProviderException(ERROR_REQUEST, "Rerank interactive budget exhausted");
                }
                // 还能重试时给新连接上的重试留出时间：首个请求落在已断开的旧连接上时，重试仍在预算内
                Duration usable = attempt < maxAttempts && remaining.compareTo(MIN_RETRY_WINDOW.multipliedBy(2)) > 0
                        ? remaining.minus(MIN_RETRY_WINDOW) : remaining;
                attemptTimeout = usable.compareTo(timeout) < 0 ? usable : timeout;
            }
            HttpClient client = httpClient;
            try {
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(endpoint)
                        .timeout(attemptTimeout)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
                if (!apiKey.isBlank()) {
                    requestBuilder.header("Authorization", "Bearer " + apiKey);
                }
                HttpResponse<String> response = client.send(
                        requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() / 100 == 2) {
                    interactiveSkipUntilNanos = 0L;
                    return parse(response.body(), requestDocuments.size(), effectiveTopN);
                }
                if (!retryable(response.statusCode()) || attempt == maxAttempts) {
                    if (interactive && retryable(response.statusCode())) markInteractiveFailure();
                    throw new RetrievalProviderException(
                            ERROR_REQUEST,
                            "Rerank provider returned HTTP " + response.statusCode());
                }
            } catch (IOException ex) {
                // 超时或连接错误后丢弃整个连接池，重试与后续请求都走新连接
                resetConnections(client);
                if (attempt == maxAttempts) {
                    if (interactive) markInteractiveFailure();
                    throw new RetrievalProviderException(ERROR_REQUEST, "Rerank provider call failed", ex);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RetrievalProviderException(ERROR_REQUEST, "Rerank provider call interrupted", ex);
            }
            backoff(attempt);
        }
        throw new RetrievalProviderException(ERROR_REQUEST, "Rerank provider call failed");
    }

    private RerankResult parse(String body, int documentCount, int topN) {
        try {
            JsonNode root = objectMapper.readTree(body == null ? "{}" : body);
            JsonNode results = root.path("results");
            if (!results.isArray()) {
                results = root.path("data").path("results");
            }
            if (!results.isArray()) {
                results = root.path("output").path("results");
            }
            if (!results.isArray()) {
                throw new RetrievalProviderException(ERROR_RESPONSE, "Rerank response is missing results");
            }
            if (results.isEmpty()) {
                throw new RetrievalProviderException(ERROR_RESPONSE, "Rerank response has no results");
            }
            List<Hit> hits = new ArrayList<>();
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            String responseModel = root.path("model").asText(model);
            int rank = 1;
            for (JsonNode item : results) {
                JsonNode indexNode = item.path("index");
                if (!indexNode.isIntegralNumber() || !indexNode.canConvertToInt()) {
                    throw new RetrievalProviderException(ERROR_RESPONSE, "Rerank index must be an integer");
                }
                int index = indexNode.intValue();
                if (index < 0 || index >= documentCount || !seen.add(index)) {
                    throw new RetrievalProviderException(ERROR_RESPONSE, "Rerank index is out of range or duplicated");
                }
                JsonNode scoreNode = item.has("relevance_score") ? item.path("relevance_score") : item.path("score");
                if (!scoreNode.isNumber() || !Double.isFinite(scoreNode.doubleValue())) {
                    throw new RetrievalProviderException(ERROR_RESPONSE, "Rerank score must be a finite number");
                }
                double score = scoreNode.doubleValue();
                if (ScoreInfo.UNIT_INTERVAL.equals(scoreScale) && (score < 0 || score > 1)) {
                    throw new RetrievalProviderException(ERROR_RESPONSE, "Rerank score is outside declared scale");
                }
                // 先验证全部响应项，不能让 topN 后的非法项被截取隐藏。
                if (hits.size() < topN) {
                    hits.add(new Hit(index, score, rank++,
                            ScoreInfo.provider(responseModel, score, scoreScale, thresholdPolicy)));
                }
            }
            return new RerankResult(hits, responseModel);
        } catch (RetrievalProviderException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RetrievalProviderException(ERROR_RESPONSE, "Rerank provider response is invalid", ex);
        }
    }

    private String limit(String value) {
        String input = text(value);
        return input.length() <= maxDocumentCharacters
                ? input
                : input.substring(0, maxDocumentCharacters);
    }

    private void ensureEnabled() {
        if (!enabled) {
            throw new RetrievalProviderException(ERROR_DISABLED, "Rerank provider is not configured");
        }
    }

    private boolean retryable(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    private synchronized void resetConnections(HttpClient failed) {
        if (httpClient == failed) {
            HttpClient fresh = clientFactory.get();
            if (fresh != failed) {
                httpClient = fresh;
            }
        }
    }

    private void markInteractiveFailure() {
        interactiveSkipUntilNanos = System.nanoTime() + interactiveCooldown.toNanos();
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep(Math.min(1000L, 100L << Math.min(4, Math.max(0, attempt - 1))));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RetrievalProviderException(ERROR_REQUEST, "Rerank retry interrupted", ex);
        }
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }
}
