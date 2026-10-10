package com.noteweave.retrieval.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.config.OpenAiSdkClients;
import com.openai.client.OpenAIClient;
import com.openai.core.RequestOptions;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.Embedding;
import com.openai.models.embeddings.EmbeddingCreateParams;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 基于 OpenAI 官方 Java SDK 的 Embedding 客户端，兼容任意 OpenAI 协议的向量服务。
 * <p>
 * 重试与退避交给 SDK（408、429、5xx 和连接错误）；本类负责分批、截断输入，
 * 并校验返回的向量数量与维度。
 */
@Component
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {
    private static final String ERROR_DISABLED = "EMBEDDING_PROVIDER_DISABLED";
    private static final String ERROR_REQUEST = "EMBEDDING_PROVIDER_REQUEST_FAILED";
    private static final String ERROR_RESPONSE = "EMBEDDING_PROVIDER_RESPONSE_INVALID";
    private static final String ERROR_DIMENSIONS = "EMBEDDING_DIMENSION_MISMATCH";
    private static final String EMBEDDINGS_PATH = "/embeddings";

    private final boolean enabled;
    private final OpenAIClient client;
    private final String model;
    private final int dimensions;
    private final int documentBatchSize;
    private final Duration queryTimeout;
    private final Duration batchTimeout;
    private final int maxInputCharacters;

    public OpenAiCompatibleEmbeddingClient(
            ObjectMapper objectMapper,
            NoteWeaveProperties properties
    ) {
        NoteWeaveProperties.Embedding config = properties.embedding();
        this.enabled = config.enabled()
                && text(config.endpoint()).length() > 0
                && text(config.model()).length() > 0
                && config.dimensions() > 0;
        this.model = text(config.model());
        this.dimensions = Math.max(1, config.dimensions());
        this.documentBatchSize = Math.max(1, config.documentBatchSize());
        this.queryTimeout = Duration.ofSeconds(Math.max(1L, config.queryTimeoutSeconds()));
        this.batchTimeout = Duration.ofSeconds(Math.max(1L, config.batchTimeoutSeconds()));
        this.maxInputCharacters = Math.max(1, config.maxInputCharacters());
        this.client = enabled
                ? OpenAiSdkClients.create(
                        ProviderEndpointSecurity.requireSecureOrLocal(config.endpoint()).toString(),
                        EMBEDDINGS_PATH,
                        text(config.apiKey()),
                        batchTimeout,
                        Math.max(1, config.maxAttempts()) - 1)
                : null;
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
        EmbeddingCreateParams params = EmbeddingCreateParams.builder()
                .model(model)
                .inputOfArrayOfStrings(inputs)
                .dimensions(dimensions)
                // SDK 默认请求 base64，很多兼容服务不支持，显式要求浮点数组。
                .encodingFormat(EmbeddingCreateParams.EncodingFormat.FLOAT)
                .build();
        CreateEmbeddingResponse response;
        try {
            response = client.embeddings().create(params, RequestOptions.builder().timeout(timeout).build());
        } catch (OpenAIServiceException ex) {
            throw new RetrievalProviderException(
                    ERROR_REQUEST, "Embedding provider returned HTTP " + ex.statusCode(), ex);
        } catch (OpenAIException ex) {
            throw new RetrievalProviderException(ERROR_REQUEST, "Embedding provider call failed", ex);
        }
        return parse(response, inputs.size());
    }

    private EmbeddingResult parse(CreateEmbeddingResponse response, int expectedCount) {
        try {
            List<IndexedVector> indexed = new ArrayList<>();
            for (Embedding item : response.data()) {
                List<Float> vector = item.embedding();
                if (vector.size() != dimensions) {
                    throw new RetrievalProviderException(
                            ERROR_DIMENSIONS,
                            "Embedding vector dimensions " + vector.size() + " do not match configured " + dimensions);
                }
                indexed.add(new IndexedVector((int) item.index(), List.copyOf(vector)));
            }
            indexed.sort(Comparator.comparingInt(IndexedVector::index));
            if (indexed.size() != expectedCount) {
                throw new RetrievalProviderException(
                        ERROR_RESPONSE,
                        "Embedding response count " + indexed.size() + " does not match request " + expectedCount);
            }
            long totalTokens = response._usage().asKnown()
                    .map(CreateEmbeddingResponse.Usage::_totalTokens)
                    .flatMap(field -> field.asKnown())
                    .orElse(0L);
            String responseModel = response._model().asKnown().orElse(model);
            return new EmbeddingResult(
                    indexed.stream().map(IndexedVector::vector).toList(),
                    responseModel,
                    dimensions,
                    totalTokens);
        } catch (RetrievalProviderException ex) {
            throw ex;
        } catch (RuntimeException ex) {
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

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private record IndexedVector(int index, List<Float> vector) {
    }
}
