package com.noteweave.source;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 文档解析 Worker 的连接与资源预算，默认优先使用 Docling。 */
@ConfigurationProperties(prefix = "noteweave.source-parser")
public record SourceDocumentParserProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("http://localhost:18103") String baseUrl,
        @DefaultValue("180") int timeoutSeconds,
        @DefaultValue("3") int connectTimeoutSeconds,
        @DefaultValue("20000000") int maxChars,
        @DefaultValue("") String internalAuthToken
) {
    public SourceDocumentParserProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://localhost:18103" : baseUrl.strip();
        internalAuthToken = internalAuthToken == null ? "" : internalAuthToken.strip();
        if (timeoutSeconds < 1 || timeoutSeconds > 600 || connectTimeoutSeconds < 1 || connectTimeoutSeconds > 60) {
            throw new IllegalArgumentException("文档解析连接与读取超时必须处于有限范围");
        }
        if (maxChars < 1 || maxChars > 20_000_000) {
            throw new IllegalArgumentException("文档解析字符上限必须在 1 到 20000000 之间");
        }
    }

    static SourceDocumentParserProperties localDefaults() {
        return new SourceDocumentParserProperties(false, "http://localhost:18103", 180, 3, 20_000_000, "");
    }
}
