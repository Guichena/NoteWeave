package com.noteweave.llm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "noteweave.llm")
public record LlmProperties(
        Stub stub,
        Api api
) {
    public record Stub(
            boolean enabled,
            String provider,
            String model
    ) {
    }

    public record Api(
            String baseUrl,
            String apiKey,
            String model,
            Double temperature,
            Integer maxTokens,
            Integer timeoutSeconds
    ) {
    }
}
