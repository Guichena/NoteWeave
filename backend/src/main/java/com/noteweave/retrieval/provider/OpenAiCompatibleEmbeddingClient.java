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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {
    private static final String ERROR_DISABLED = "EMBEDDING_PROVIDER_DISABLED";
    private static final String ERROR_REQUEST = "EMBEDDING_PROVIDER_REQUEST_FAILED";
    private static final String ERROR_RESPONSE = "EMBEDDING_PROVIDER_RESPONSE_INVALID";
    private static final String ERROR_DIMENSIONS = "EMBEDDING_DIMENSION_MISMATCH";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final boolean enabled;
    private final URI endpoint;
    private final String model;
    private final String apiKey;
    private final int dimensions;
    private final int documentBatchSize;
    private final Duration queryTimeout;
    private final Duration batchTimeout;
    private final int maxInputCharacters;
    private final int maxAttempts;

    @Autowired
    public OpenAiCompatibleEmbeddingClient(
            ObjectMapper objectMapper,
            NoteWeaveProperties properties
    ) {
        this(objectMapper, properties, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    OpenAiCompatibleEmbeddingClient(
            ObjectMapper objectMapper,
            NoteWeaveProperties properties,
            HttpClient httpClient
    ) {
        NoteWeaveProperties.Embedding config = properties.embedding();
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.enabled = config.enabled()
                && text(config.endpoint()).length() > 0
                && text(config.model()).length() > 0
                && config.dimensions() > 0;
        this.endpoint = URI.create(enabled ? config.endpoint() : "http://localhost/disabled");
        this.model = text(config.model());
        this.apiKey = text(config.apiKey());
        this.dimensions = Math.max(1, config.dimensions());
        this.documentBatchSize = Math.max(1, config.documentBatchSize());
        this.queryTimeout = Duration.ofSeconds(Math.max(1L, config.queryTimeoutSeconds()));
        this.batchTimeout = Duration.ofSeconds(Math.max(1L, config.batchTimeoutSeconds()));
        this.maxInputCharacters = Math.max(1, config.maxInputCharacters());
        this.maxAttempts = Math.max(1, config.maxAttempts());
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public EmbeddingResult embedQuery(String text) {
        ensureEnabled();
        return request(List.of(limit(text)), queryTimeout);
    }

    @Override
    public EmbeddingResult embedDocuments(List<String> texts) {
        ensureEnabled();
        if (texts == null || texts.isEmpty()) {
            return new EmbeddingResult(List.of(), model, dimensions, 0L);
        }
        List<List<Float>> vectors = new ArrayList<>(texts.size());
        long totalTokens = 0L;
        for (int start = 0; start < texts.size(); start += documentBatchSize) {
            int end = Math.min(texts.size(), start + documentBatchSize);
            List<String> batch = texts.subList(start, end).stream().map(this::limit).toList();
            EmbeddingResult result = request(batch, batchTimeout);
            vectors.addAll(result.vectors());
            totalTokens += result.totalTokens();
        }
        return new EmbeddingResult(vectors, model, dimensions, totalTokens);
    }

    private EmbeddingResult request(List<String> inputs, Duration timeout) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", inputs);
        body.put("dimensions", dimensions);
        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(body);
        } catch (Exception ex) {
            throw new RetrievalProviderException(ERROR_REQUEST, "Embedding request serialization failed", ex);
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
                    return parse(response.body(), inputs.size());
                }
                if (!retryable(response.statusCode()) || attempt == maxAttempts) {
                    throw new RetrievalProviderException(
                            ERROR_REQUEST,
                            "Embedding provider returned HTTP " + response.statusCode());
                }
            } catch (IOException ex) {
                if (attempt == maxAttempts) {
                    throw new RetrievalProviderException(ERROR_REQUEST, "Embedding provider call failed", ex);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RetrievalProviderException(ERROR_REQUEST, "Embedding provider call interrupted", ex);
            }
            backoff(attempt);
        }
        throw new RetrievalProviderException(ERROR_REQUEST, "Embedding provider call failed");
    }

    private EmbeddingResult parse(String body, int expectedCount) {
        try {
            JsonNode root = objectMapper.readTree(body == null ? "{}" : body);
            List<IndexedVector> indexed = new ArrayList<>();
            for (JsonNode item : root.path("data")) {
                int index = item.path("index").asInt(indexed.size());
                JsonNode embedding = item.path("embedding");
                if (!embedding.isArray()) {
                    throw new RetrievalProviderException(ERROR_RESPONSE, "Embedding response is missing a vector");
                }
                List<Float> vector = new ArrayList<>(embedding.size());
                embedding.forEach(value -> vector.add(value.floatValue()));
                if (vector.size() != dimensions) {
                    throw new RetrievalProviderException(
                            ERROR_DIMENSIONS,
                            "Embedding vector dimensions " + vector.size() + " do not match configured " + dimensions);
                }
                indexed.add(new IndexedVector(index, List.copyOf(vector)));
            }
            indexed.sort(Comparator.comparingInt(IndexedVector::index));
            if (indexed.size() != expectedCount) {
                throw new RetrievalProviderException(
                        ERROR_RESPONSE,
                        "Embedding response count " + indexed.size() + " does not match request " + expectedCount);
            }
            long totalTokens = root.path("usage").path("total_tokens").asLong(0L);
            String responseModel = root.path("model").asText(model);
            return new EmbeddingResult(
                    indexed.stream().map(IndexedVector::vector).toList(),
                    responseModel,
                    dimensions,
                    totalTokens);
        } catch (RetrievalProviderException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RetrievalProviderException(ERROR_RESPONSE, "Embedding provider response is invalid", ex);
        }
    }

    private String limit(String value) {
        String input = text(value);
        if (input.length() <= maxInputCharacters) {
            return input;
        }
        return input.substring(0, maxInputCharacters);
    }

    private void ensureEnabled() {
        if (!enabled) {
            throw new RetrievalProviderException(ERROR_DISABLED, "Embedding provider is not configured");
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
            throw new RetrievalProviderException(ERROR_REQUEST, "Embedding retry interrupted", ex);
        }
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private record IndexedVector(int index, List<Float> vector) {
    }
}
