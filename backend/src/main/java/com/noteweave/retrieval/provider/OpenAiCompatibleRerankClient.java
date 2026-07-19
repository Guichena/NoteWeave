package com.noteweave.retrieval.provider;

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
    private final HttpClient httpClient;
    private final boolean enabled;
    private final URI endpoint;
    private final String model;
    private final String apiKey;
    private final int batchSize;
    private final int maxDocumentCharacters;
    private final Duration timeout;
    private final int maxAttempts;

    @Autowired
    public OpenAiCompatibleRerankClient(ObjectMapper objectMapper, NoteWeaveProperties properties) {
        this(objectMapper, properties, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    OpenAiCompatibleRerankClient(
            ObjectMapper objectMapper,
            NoteWeaveProperties properties,
            HttpClient httpClient
    ) {
        NoteWeaveProperties.Rerank config = properties.rerank();
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.enabled = config.enabled()
                && !text(config.endpoint()).isBlank()
                && !text(config.model()).isBlank();
        this.endpoint = enabled
                ? ProviderEndpointSecurity.requireSecureOrLocal(config.endpoint())
                : URI.create("http://localhost/disabled");
        this.model = text(config.model());
        this.apiKey = text(config.apiKey());
        this.batchSize = Math.max(1, config.batchSize());
        this.maxDocumentCharacters = Math.max(1, config.maxDocumentCharacters());
        this.timeout = Duration.ofSeconds(Math.max(1L, config.timeoutSeconds()));
        this.maxAttempts = Math.max(1, config.maxAttempts());
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

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(endpoint)
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
                if (!apiKey.isBlank()) {
                    requestBuilder.header("Authorization", "Bearer " + apiKey);
                }
                HttpResponse<String> response = httpClient.send(
                        requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() / 100 == 2) {
                    return parse(response.body(), requestDocuments.size(), effectiveTopN);
                }
                if (!retryable(response.statusCode()) || attempt == maxAttempts) {
                    throw new RetrievalProviderException(
                            ERROR_REQUEST,
                            "Rerank provider returned HTTP " + response.statusCode());
                }
            } catch (IOException ex) {
                if (attempt == maxAttempts) {
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
            List<Hit> hits = new ArrayList<>();
            int rank = 1;
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                if (index < 0 || index >= documentCount) {
                    throw new RetrievalProviderException(ERROR_RESPONSE, "Rerank response contains an invalid index");
                }
                double score = item.has("relevance_score")
                        ? item.path("relevance_score").asDouble()
                        : item.path("score").asDouble();
                hits.add(new Hit(index, score, rank++));
                if (hits.size() >= topN) {
                    break;
                }
            }
            String responseModel = root.path("model").asText(model);
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
