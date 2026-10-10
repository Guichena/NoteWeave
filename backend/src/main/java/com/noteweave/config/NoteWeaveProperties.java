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
        if (kafka == null || kafka.topics() == null) {
            kafka = new Kafka(kafka == null || kafka.enabled(), defaultTopics());
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

    public record Kafka(boolean enabled, Topics topics) {
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
