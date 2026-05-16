package com.noteweave.chat.runtime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "noteweave.chat.runtime")
public record ChatRuntimeProperties(
        int deltaChunkSize,
        long deltaDelayMs
) {
}
