package com.noteweave.source;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 片段上下文生成的提示词版本和有限输入、输出预算。 */
@ConfigurationProperties(prefix = "noteweave.source-context")
public record SourceChunkContextProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("source-context-v1") String promptVersion,
        @DefaultValue("1000") int chunkExcerptChars,
        @DefaultValue("48000") int maxInputChars,
        @DefaultValue("600") int maxContextChars,
        @DefaultValue("12000") int maxOutputTokens
) {
    public SourceChunkContextProperties {
        if (promptVersion == null || !promptVersion.matches("[A-Za-z0-9._-]{1,63}")) {
            throw new IllegalArgumentException("片段上下文提示词版本必须是有效的版本标识");
        }
        if (chunkExcerptChars < 1 || chunkExcerptChars > 16000 || maxInputChars < 4096 || maxInputChars > 200000
                || maxContextChars < 1 || maxContextChars > 2000 || maxOutputTokens < 256 || maxOutputTokens > 32000) {
            throw new IllegalArgumentException("片段上下文输入、输出预算超出允许范围");
        }
    }
}
