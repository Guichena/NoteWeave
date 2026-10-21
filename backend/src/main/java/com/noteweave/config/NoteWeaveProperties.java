package com.noteweave.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "noteweave")
public record NoteWeaveProperties(
        Storage storage,
        Document document,
        Worker worker,
        Kafka kafka,
        Elasticsearch elasticsearch,
        Embedding embedding,
        Rerank rerank,
        Llm llm
) {

    public NoteWeaveProperties(
            Storage storage,
            Document document,
            Worker worker,
            Kafka kafka,
            Elasticsearch elasticsearch,
            Llm llm
    ) {
        this(storage, document, worker, kafka, elasticsearch, null, null, llm);
    }

    @ConstructorBinding
    public NoteWeaveProperties {
        if (storage == null) {
            storage = new Storage("local", Path.of("target/noteweave-storage"),
                    new Minio("http://localhost:9000", "", "",
                            "us-east-1", "noteweave-source", "noteweave-derived", "noteweave-export"));
        }
        if (document == null) {
            document = new Document(600, 80);
        }
        if (worker == null) {
            worker = new Worker("http://localhost:18092");
        }
        if (kafka == null) {
            kafka = new Kafka(true, defaultTopics());
        } else if (kafka.topics() == null) {
            // 仅补齐主题缺省值，保留已配置的重试预算
            kafka = new Kafka(kafka.enabled(), defaultTopics(), kafka.retryMaxAttempts(), kafka.retryMaxBackoffMs());
        }
        if (elasticsearch == null) {
            elasticsearch = new Elasticsearch(true, "localhost", 9200, "http", "noteweave_chunk", "", "");
        }
        if (embedding == null) {
            embedding = new Embedding(
                    true,
                    "http://localhost:11434/v1/embeddings",
                    "noteweave-embedding",
                    "",
                    1024,
                    32,
                    10L,
                    60L,
                    12_000,
                    3);
        }
        if (rerank == null) {
            rerank = new Rerank(
                    true,
                    "http://localhost:11434/v1/rerank",
                    "noteweave-rerank",
                    "",
                    80,
                    1800,
                    20L,
                    3);
        }
        if (llm == null) {
            llm = new Llm(true, "http://localhost:11434/v1/chat/completions", "noteweave-chat", "", 60L);
        }
    }

    private static Topics defaultTopics() {
        return new Topics(
                "noteweave.source.parse",
                "noteweave.retrieval.projection",
                "noteweave.wiki.ingest",
                "noteweave.wiki.retract",
                "noteweave.conversation.summary",
                "noteweave.memory.extraction",
                "noteweave.source.chunk",
                "noteweave.source.embed",
                "noteweave.source.index");
    }

    public record Storage(String backend, Path localRoot, Minio minio) {
    }

    public record Minio(
            String endpoint,
            String accessKey,
            String secretKey,
            String region,
            String bucketSource,
            String bucketDerived,
            String bucketExport
    ) {
    }

    /** 资料切片预算，单位是估算 Token：片段上限与相邻片段的句子级重叠上限。 */
    public record Document(int chunkMaxTokens, int chunkOverlapTokens) {
    }

    public record Worker(String artifactBaseUrl) {
    }

    /**
     * Kafka 消费重试预算：{@code retryMaxAttempts} 次退避后进入 DLT，
     * 单次退避上限为 {@code retryMaxBackoffMs}（指数退避 {@code min(1000 << retry, cap)} 叠加抖动）。
     */
    public record Kafka(boolean enabled, Topics topics, int retryMaxAttempts, long retryMaxBackoffMs) {

        /** 默认重试次数：约 1s+2s+4s+8s+16s 五次退避后进入 DLT。 */
        public static final int DEFAULT_RETRY_MAX_ATTEMPTS = 5;
        /** 默认单次退避上限，单位毫秒。 */
        public static final long DEFAULT_RETRY_MAX_BACKOFF_MS = 30_000L;
        /** 允许配置的重试次数下限。 */
        public static final int MIN_RETRY_MAX_ATTEMPTS = 1;
        /** 允许配置的重试次数上限，避免消费位点长时间滞留。 */
        public static final int MAX_RETRY_MAX_ATTEMPTS = 10;
        /**
         * 允许配置的单次退避上限，单位毫秒。10 次退避的最坏累计约 13 分钟，
         * 仍低于默认 {@code max.poll.interval.ms}（1800000 ms），不会因退避把消费实例踢出消费组。
         */
        public static final long MAX_RETRY_MAX_BACKOFF_MS = 300_000L;

        @ConstructorBinding
        public Kafka {
            retryMaxAttempts = retryMaxAttempts <= 0
                    ? DEFAULT_RETRY_MAX_ATTEMPTS
                    : Math.min(Math.max(retryMaxAttempts, MIN_RETRY_MAX_ATTEMPTS), MAX_RETRY_MAX_ATTEMPTS);
            retryMaxBackoffMs = retryMaxBackoffMs <= 0
                    ? DEFAULT_RETRY_MAX_BACKOFF_MS
                    : Math.min(retryMaxBackoffMs, MAX_RETRY_MAX_BACKOFF_MS);
        }

        /** 兼容既有构造点：只关心启用开关与主题时沿用默认重试预算。 */
        public Kafka(boolean enabled, Topics topics) {
            this(enabled, topics, DEFAULT_RETRY_MAX_ATTEMPTS, DEFAULT_RETRY_MAX_BACKOFF_MS);
        }
    }

    public record Topics(
            String sourceParse,
            String retrievalProjection,
            String wikiIngest,
            String wikiRetract,
            String conversationSummary,
            String memoryExtraction,
            String sourceChunk,
            String sourceEmbed,
            String sourceIndex
    ) {
        @ConstructorBinding
        public Topics {
            memoryExtraction = defaultTopic(memoryExtraction, "noteweave.memory.extraction");
            sourceChunk = defaultTopic(sourceChunk, "noteweave.source.chunk");
            sourceEmbed = defaultTopic(sourceEmbed, "noteweave.source.embed");
            sourceIndex = defaultTopic(sourceIndex, "noteweave.source.index");
        }

        public Topics(String sourceParse, String retrievalProjection, String wikiIngest,
                      String wikiRetract, String conversationSummary) {
            this(sourceParse, retrievalProjection, wikiIngest, wikiRetract, conversationSummary,
                    null, null, null, null);
        }

        private static String defaultTopic(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }

        /** 由任务 outbox 投递的全部主题。 */
        public java.util.List<String> all() {
            return java.util.List.of(sourceParse, retrievalProjection, wikiIngest, wikiRetract,
                    conversationSummary, memoryExtraction, sourceChunk, sourceEmbed, sourceIndex);
        }
    }

    public record Elasticsearch(
            boolean enabled,
            String host,
            int port,
            String scheme,
            String indexPrefix,
            String username,
            String password
    ) {
        @ConstructorBinding
        public Elasticsearch {
        }

        public Elasticsearch(boolean enabled, String host, int port, String scheme, String indexPrefix) {
            this(enabled, host, port, scheme, indexPrefix, "", "");
        }
    }

    public record Embedding(
            boolean enabled,
            String endpoint,
            String model,
            String apiKey,
            int dimensions,
            int documentBatchSize,
            long queryTimeoutSeconds,
            long batchTimeoutSeconds,
            int maxInputCharacters,
            int maxAttempts
    ) {
    }

    public record Rerank(
            boolean enabled,
            String endpoint,
            String model,
            String apiKey,
            int batchSize,
            int maxDocumentCharacters,
            long timeoutSeconds,
            int maxAttempts
    ) {
    }

    public record Llm(
            boolean enabled,
            String endpoint,
            String model,
            String apiKey,
            long timeoutSeconds
    ) {
    }
}
