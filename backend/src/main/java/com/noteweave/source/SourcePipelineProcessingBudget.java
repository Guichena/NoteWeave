package com.noteweave.source;

import com.noteweave.config.NoteWeaveProperties;

/** 批次租约与消费轮询使用同一请求上界，避免配置变大后租约先过期。 */
public final class SourcePipelineProcessingBudget {
    private SourcePipelineProcessingBudget() {}

    public static int maxPollIntervalMs(NoteWeaveProperties properties, int configuredMillis) {
        if (configuredMillis < 1) throw new IllegalArgumentException("Kafka 轮询超时必须为正整数");
        long minimumMillis = 0;
        try {
            if (properties != null) {
                NoteWeaveProperties.Embedding embedding = properties.embedding();
                long batchSize = Math.max(1, embedding.documentBatchSize());
                long providerBatches = (64L + batchSize - 1) / batchSize + 1;
                // 末批另有一条资料向量；SDK 尝试计入超时与退避，存储及提交另留两分钟。
                long callsSeconds = Math.multiplyExact(providerBatches, Math.max(1L, embedding.maxAttempts()));
                callsSeconds = Math.multiplyExact(callsSeconds,
                        Math.addExact(Math.max(1L, embedding.batchTimeoutSeconds()), 5L));
                long llmSeconds = properties.llm() == null ? 60L : Math.max(10L, properties.llm().timeoutSeconds());
                long minimumSeconds = Math.addExact(120L, Math.multiplyExact(2L, llmSeconds));
                minimumMillis = Math.multiplyExact(Math.addExact(minimumSeconds, callsSeconds), 1000L);
            }
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("资料请求预算超出 Kafka 轮询超时范围", exception);
        }
        long effective = Math.max(configuredMillis, minimumMillis);
        if (effective > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("资料批次请求上界超过 Kafka 可配置的轮询超时");
        }
        return (int) effective;
    }

    public static int embeddingLeaseSeconds(NoteWeaveProperties properties, int configuredPollMillis,
                                            int configuredLeaseSeconds) {
        if (configuredLeaseSeconds < 1 || configuredLeaseSeconds > 86_400) {
            throw new IllegalArgumentException("向量批次租约必须处于 1 到 86400 秒之间");
        }
        long minimumSeconds = (maxPollIntervalMs(properties, configuredPollMillis) + 999L) / 1000L + 60L;
        long effective = Math.max(configuredLeaseSeconds, minimumSeconds);
        if (effective > 86_400) throw new IllegalArgumentException("资料请求预算超过允许的批次租约上限");
        return (int) effective;
    }
}
