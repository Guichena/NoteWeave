package com.noteweave.embedding.service;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.embedding.config.EmbeddingProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StubEmbeddingClient implements EmbeddingClient {

    private final EmbeddingProperties embeddingProperties;

    @Override
    public List<float[]> embedTexts(List<String> texts) {
        if (!embeddingProperties.enabled()) {
            return List.of();
        }
        if (!embeddingProperties.stub().enabled()
                && (embeddingProperties.api().apiKey() == null || embeddingProperties.api().apiKey().isBlank())) {
            throw new BusinessException(ErrorCode.LLM_CONFIG_MISSING, "Embedding API key is missing");
        }
        List<float[]> vectors = new ArrayList<>();
        for (String text : texts == null ? List.<String>of() : texts) {
            vectors.add(toVector(text == null ? "" : text, Math.max(1, embeddingProperties.api().dimension())));
        }
        return vectors;
    }

    private float[] toVector(String text, int dimension) {
        float[] vector = new float[dimension];
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < dimension; i++) {
                vector[i] = (digest[i % digest.length] & 0xff) / 255.0f;
            }
            return vector;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate stub embedding", ex);
        }
    }
}
