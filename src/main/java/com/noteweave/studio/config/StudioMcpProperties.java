package com.noteweave.studio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "noteweave.studio.mcp")
public record StudioMcpProperties(
        Bilibili bilibili
) {

    public record Bilibili(
            boolean remoteEnabled,
            String remoteBaseUrl,
            int timeoutSeconds
    ) {
    }
}
