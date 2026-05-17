package com.noteweave.embedding.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "noteweave.embedding")
public record EmbeddingProperties(
        boolean enabled,
        Stub stub,
        Api api
) {
    public record Stub(
            boolean enabled
    ) {
    }

    public record Api(
            String baseUrl,
            String apiKey,
            String model,
            int dimension,
            int batchSize
    ) {
    }
}
